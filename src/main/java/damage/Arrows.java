package damage;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import plugin.M7;

/**
 * Arrow damage (MAP.md §1.0.5).
 * <p>
 * Stat half of a shot is resolved at fire time and stamped on the arrow, so swapping weapons mid-flight changes
 * nothing. Hypixel computes it all at the hit, which is what the arrow-damage tech exploits; we don't reproduce that.
 * <p>
 * Target half (Rulers, Titan Killer, Prosecute, type enchants, target debuffs) isn't knowable at fire time and
 * resolves at {@link #resolve}. Also stamped: weapon name, so the firing reforge decides Precise's headshot bonus,
 * and origin, so Snipe IV's +4% per 10 blocks reads real flight distance.
 */
public final class Arrows {
	private Arrows() {}

	private static final NamespacedKey CORE = key("arrow_core");
	private static final NamespacedKey WEAPON = key("arrow_weapon");
	private static final NamespacedKey ORIGIN = key("arrow_origin");
	private static final NamespacedKey HITS = key("arrow_hits");
	/** 1 if this arrow may build a Last Breath stack. See {@link #stamp}. */
	private static final NamespacedKey BUILDS_LAST_BREATH = key("arrow_last_breath");
	/** 1 if fired at full draw and so crits. See {@link #isCrit}. */
	private static final NamespacedKey CRIT = key("arrow_crit");
	/** 1 if this arrow carries a finished figure, not a stat core. See {@link #stampFlat}. */
	private static final NamespacedKey DERIVED = key("arrow_derived");

	/** Piercing I: each mob after the first takes 25% (§7). */
	private static final double PIERCING_SHARE = 0.25;

	private static NamespacedKey key(String name) {
		return new NamespacedKey(M7.getInstance(), name);
	}

	/**
	 * Stamp a freshly-fired arrow with its damage.
	 *
	 * @param chargeFraction vanilla's {@code min(useTicks/20, 1)}. 1.0 for a shortbow (Terminator is never drawn) or
	 *                       a full draw. A partial draw scales damage AND loses the crit term, so it's much worse
	 *                       than the fraction suggests.
	 * @param share          fraction of a normal arrow, for Duplex's extra arrow at x0.2
	 */
	public static void stamp(AbstractArrow arrow, Player shooter, ItemStack weapon, double chargeFraction,
			double share) {
		stamp(arrow, shooter, weapon, chargeFraction, share, true);
	}

	/**
	 * As above, with control over whether this arrow may build a Last Breath stack. Last Breath's own shot and its
	 * Duplex arrow: yes. Archer's two bonus arrows: no, the only case this flag exists for. Explosive Shot / Rapid
	 * Fire while holding a Last Breath: yes, for free, since those stamp the main-hand weapon.
	 * The weapon still has to be a Last Breath; this can only take the answer away.
	 */
	public static void stamp(AbstractArrow arrow, Player shooter, ItemStack weapon, double chargeFraction,
			double share, boolean buildsLastBreath) {
		if(arrow == null || shooter == null) return;
		boolean full = chargeFraction >= 1.0;
		ItemDef def = Items.of(weapon);
		double core = Damage.bowCore(shooter, def, full) * Math.max(0, Math.min(chargeFraction, 1.0)) * share;
		var pdc = arrow.getPersistentDataContainer();
		pdc.set(CORE, PersistentDataType.DOUBLE, core);
		pdc.set(WEAPON, PersistentDataType.STRING, def == null ? "" : def.displayName());
		Location o = arrow.getLocation();
		pdc.set(ORIGIN, PersistentDataType.STRING, o.getX() + "," + o.getY() + "," + o.getZ());
		pdc.set(HITS, PersistentDataType.INTEGER, 0);
		pdc.set(BUILDS_LAST_BREATH, PersistentDataType.INTEGER, buildsLastBreath ? 1 : 0);
		// Crit is decided by the draw at fire time, so it travels with the arrow; the draw is over by the hit.
		pdc.set(CRIT, PersistentDataType.INTEGER, full ? 1 : 0);
		// So a hit path we don't intercept sees a sane number, not the default 2.0. Our paths never read it.
		arrow.setDamage(0);
	}

	/**
	 * Stamp an arrow with an already-decided figure, not the shooter's stat core: Rapid Fire's 75% of the player's
	 * best recent arrow (§1.14).
	 * <p>
	 * That figure is a finished hit, so the target half must not run on it again: running it through
	 * {@link Damage#bowFinish} charges Rulers, Titan Killer, Snipe, Power, class multiplier etc. twice, x4-x10.
	 * {@link #resolve} still applies the arrow's debuffs (Last Breath stacks, §1.14) and Piercing's 25%, since those
	 * belong to the projectile, not the formula.
	 * <p>
	 * With {@code deal}'s history feedback the double charge also compounded: each arrow read the history, landed
	 * several times it and recorded that, so 50 arrows over 200 ticks overflowed. Both halves are fixed and both are
	 * needed; either alone still leaves the ability several times too strong.
	 */
	public static void stampFlat(AbstractArrow arrow, Player shooter, ItemStack weapon, double core) {
		if(arrow == null || shooter == null) return;
		var pdc = arrow.getPersistentDataContainer();
		pdc.set(CORE, PersistentDataType.DOUBLE, core);
		ItemDef def = Items.of(weapon);
		pdc.set(WEAPON, PersistentDataType.STRING, def == null ? "" : def.displayName());
		Location o = arrow.getLocation();
		pdc.set(ORIGIN, PersistentDataType.STRING, o.getX() + "," + o.getY() + "," + o.getZ());
		pdc.set(HITS, PersistentDataType.INTEGER, 0);
		// Explosive Shot and Rapid Fire stamp the HELD weapon, so off a Last Breath these count as Last Breath arrows.
		pdc.set(BUILDS_LAST_BREATH, PersistentDataType.INTEGER, 1);
		// Ability arrows are never drawn, so always crit.
		pdc.set(CRIT, PersistentDataType.INTEGER, 1);
		pdc.set(DERIVED, PersistentDataType.INTEGER, 1);
		arrow.setDamage(0);
	}

	/** True if stamped by us, i.e. {@link #resolve} can answer for it. */
	public static boolean isStamped(AbstractArrow arrow) {
		return arrow != null && arrow.getPersistentDataContainer().has(CORE, PersistentDataType.DOUBLE);
	}

	/** True if damage came from the rolling history: its hit goes through {@link Damage#dealDerived} and must not feed it back. */
	public static boolean isDerived(AbstractArrow arrow) {
		return arrow != null
				&& arrow.getPersistentDataContainer().getOrDefault(DERIVED, PersistentDataType.INTEGER, 0) == 1;
	}

	/**
	 * True if fired at full draw (or never drawn). A partial draw drops the crit term from the damage AND the crit
	 * form from the number. Defaults true for arrows stamped before this flag existed, matching what they dealt.
	 */
	public static boolean isCrit(AbstractArrow arrow) {
		return arrow == null
				|| arrow.getPersistentDataContainer().getOrDefault(CRIT, PersistentDataType.INTEGER, 1) == 1;
	}

	/**
	 * Resolve against {@code target} and deal it. Every arrow hit goes through here so no call site can forget that
	 * a Rapid Fire arrow must not feed the damage history back (see {@link Damage#dealDerived}).
	 *
	 * @return the reported hit, as {@link Damage#deal} defines it
	 */
	public static double hit(AbstractArrow arrow, Player shooter, LivingEntity target) {
		return hit(arrow, shooter, target, true);
	}

	/** As above, with {@code countPierce} as {@link #resolve(AbstractArrow, Player, LivingEntity, boolean)} means it. */
	public static double hit(AbstractArrow arrow, Player shooter, LivingEntity target, boolean countPierce) {
		double sbDamage = resolve(arrow, shooter, target, countPierce);
		// Partial draw isn't a crit: grey digits, no ✧. Display half of the rule applied at stamp time.
		DamageKind kind = isCrit(arrow) ? DamageKind.NORMAL : DamageKind.MAGIC;
		double reported = isDerived(arrow)
				? Damage.dealDerived(target, sbDamage, kind, shooter, DamagePath.BOW)
				: Damage.deal(target, sbDamage, kind, shooter, DamagePath.BOW);
		// Zero where deal refused the target outright (armoured wither, WK, corpse), the same cases that skip Fire Aspect.
		ItemDef def = arrow == null ? null : Items.byName(arrow.getPersistentDataContainer()
				.getOrDefault(WEAPON, PersistentDataType.STRING, ""));
		if(reported > 0 && def != null && def.flame()) Procs.flame(shooter, target, sbDamage);
		return reported;
	}

	/**
	 * SkyBlock damage this arrow does to {@code target}, applying its debuffs first (§7 ordering) and Piercing's 25%
	 * after the first mob. Private because the figure and how it's dealt are one decision; go through {@link #hit}.
	 */
	private static double resolve(AbstractArrow arrow, Player shooter, LivingEntity target) {
		return resolve(arrow, shooter, target, true);
	}

	/**
	 * As above; {@code countPierce} false for one arrow hitting several mobs for the same damage, e.g. Explosive
	 * Bow's 3-block blast where §1.9 gives every mob full damage. Piercing is passing THROUGH mobs, a different thing.
	 */
	private static double resolve(AbstractArrow arrow, Player shooter, LivingEntity target, boolean countPierce) {
		if(arrow == null || shooter == null || target == null) return 0;
		var pdc = arrow.getPersistentDataContainer();
		Double core = pdc.get(CORE, PersistentDataType.DOUBLE);
		if(core == null) return 0;

		ItemDef def = Items.byName(pdc.getOrDefault(WEAPON, PersistentDataType.STRING, ""));
		boolean buildsLastBreath = pdc.getOrDefault(BUILDS_LAST_BREATH, PersistentDataType.INTEGER, 1) == 1;
		Damage.applyOnHitDebuffs(shooter, target, DamagePath.BOW, def, buildsLastBreath);

		double piercing = 1.0;
		if(countPierce) {
			int priorHits = pdc.getOrDefault(HITS, PersistentDataType.INTEGER, 0);
			pdc.set(HITS, PersistentDataType.INTEGER, priorHits + 1);
			if(priorHits > 0) piercing = PIERCING_SHARE;
		}

		// Derived core already has the target half in it; bowFinish would charge it twice. See stampFlat.
		if(isDerived(arrow)) return core * piercing;

		return Damage.bowFinish(shooter, target, def, core, blocksTravelled(arrow), isHeadshot(arrow, target))
				* piercing;
	}

	/** Flight distance for Snipe IV. Zero if never stamped with an origin. */
	private static double blocksTravelled(AbstractArrow arrow) {
		String origin = arrow.getPersistentDataContainer().getOrDefault(ORIGIN, PersistentDataType.STRING, "");
		String[] parts = origin.split(",");
		if(parts.length != 3) return 0;
		try {
			Vector from = new Vector(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
					Double.parseDouble(parts[2]));
			return from.distance(arrow.getLocation().toVector());
		} catch(NumberFormatException e) {
			return 0;
		}
	}

	/** Headshot for Precise's +10%. Approximated from arrow Y vs eye height; projectile hits report no contact point. */
	private static boolean isHeadshot(AbstractArrow arrow, LivingEntity target) {
		return arrow.getLocation().getY() >= target.getEyeLocation().getY() - 0.3;
	}
}
