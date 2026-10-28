package damage;

import instructions.bosses.WitherActions;
import instructions.bosses.WitherLord;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import org.bukkit.attribute.Attribute;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import plugin.Utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Damage formulas and the single application boundary (MAP.md §7).
 *
 * <pre>
 * melee   = (5 + Damage) x (1 + Strength/100) x (1 + CritDamage/100)      // ALWAYS crits
 *         x (1 + sumAdditive/100) x product(Multiplicative_i)
 *
 * bow     = the same shape, x chargeFraction; a non-full draw ALSO drops the crit term entirely
 *
 * ability = BaseDamage x (1 + (Intelligence/100) x AbilityScaling) x (1 + AbilityDamage/100)
 *         x (1 + sumAdditive/100) x product(Multiplicative_i)             // no Strength, no Crit Damage
 *           where BaseDamage is the authored figure x SB_CATA_MULT on a dungeon item (66,500 for Wither Impact)
 *
 * mageBeam = melee x (0.30 + 0.0009 x Intelligence)                       // the Mage Staff passive
 * </pre>
 *
 * <b>No {@code Strength/5} term</b>: SkyBlock removed it years ago, and it inflates melee and beam x2.69 at these
 * stats. Strength enters only via {@code (1 + Strength/100)}.
 * <p>
 * <b>All math in real SkyBlock units</b> (billions). One conversion to MC health, in {@link #deal}:
 * <pre>
 * mcDamage = ( sbDamage x bossResistance / (1 + defense/100) ) / 1e6
 * </pre>
 * For Necron those two factors are /82.
 * <p>
 * <b>One damage path.</b> There used to be three ({@code hurtServer(genericKill)}, {@code setHealth} for dragons,
 * {@code wither.damage()} for arrows on withers) with different i-frame, armor, event and aggro behaviour, already
 * patched with workarounds. {@link #deal} reads health, subtracts and sets it; vanilla isn't a participant.
 */
public final class Damage {
	private Damage() {
	}

	// ===================== §7 damage-level additive sources =====================
	private static final double SHARPNESS_VII = 50;
	private static final double POWER_VII = 65;
	private static final double SNIPE_IV_PER_10_BLOCKS = 4;
	private static final double ARCHERY_IV_POTION = 80;
	private static final double SMITE_VII = 50;
	private static final double BANE_OF_ARTHROPODS_VII = 50;
	private static final double ENDER_SLAYER_VII = 50;
	private static final double PYROCLASM_VI = 80;
	private static final double CUBISM_VI = 40;
	private static final double GRAVITY_VI = 40;   // the renamed Dragon Hunter: +40% to Airborne
	private static final double IMPALING_V = 30;
	private static final double SMOLDERING_V = 30;
	private static final double FIRST_STRIKE_V = 125;
	private static final double TRIPLE_STRIKE_V = 50;
	private static final double GIANT_KILLER_VII = 65;
	private static final double TITAN_KILLER_PER_100_DEFENSE = 20;
	private static final double TITAN_KILLER_CAP = 80;
	private static final double EXECUTE_VI_PER_PERCENT_MISSING = 1.25;
	private static final double PROSECUTE_VI_PER_PERCENT_REMAINING = 1.0;
	private static final double PRECISE_HEADSHOT = 10;
	private static final double SWARM_V_PER_ENEMY = 10;
	private static final int SWARM_MAX_ENEMIES = 10;
	private static final double SWARM_RADIUS = 10;
	private static final double RULER = 39;
	private static final double WARRIOR = 25;
	private static final double SKELETOR = 25;
	private static final double ELITE = 30;
	private static final double DOMINANCE = 15;
	private static final double COMBAT_60 = 210;
	private static final double RING_OF_LOVE = 100;
	private static final double DRACONIC_ARTIFACT = 5;

	// ===================== §7 damage-level multiplicative sources =====================
	private static final double HYPERION_VS_WITHER = 1.5;
	/** Death Bow's "+100% damage to Undead mobs": x2 vs {@link MobType#UNDEAD}. */
	private static final double DEATH_BOW_VS_UNDEAD = 2.0;
	private static final double OVERLOAD = 1.5;
	private static final double BOOK_OF_PROGRESSION = 1.05;
	private static final double TARANTULA_RING = 1.15;

	// ===================== ammunition =====================
	/**
	 * Armorshred Arrow: ranged hits use x0.95 of the target's defense. Assumed like Archery IV and Overload (no ammo
	 * types). Only affects the defense THIS hit divides by; not a debuff, nothing written back.
	 */
	private static final double ARMORSHRED_DEFENSE = 0.95;

	// ===================== §7 mage beam =====================
	/** Mage Staff: 30% plus 0.09% per Int, ADDED to the 30%, not multiplied. */
	private static final double BEAM_BASE = 0.30;
	private static final double BEAM_PER_INTELLIGENCE = 0.0009;

	/**
	 * Beam range tiers (§7), each doubling the previous total. Full damage to the cutoff, then linear to zero at max
	 * range. Mostly the CUTOFF grows: in the boss arena it's full out to 35 and fades over the last 15.
	 */
	public record BeamRange(double cutoff, double maxRange) {
		public static final BeamRange DEFAULT = new BeamRange(10, 25);
		public static final BeamRange BOSS_ARENA = new BeamRange(35, 50);
		public static final BeamRange WITHER_KING = new BeamRange(70, 100);

		public double falloff(double distance) {
			if(distance <= cutoff) return 1.0;
			if(distance >= maxRange) return 0.0;
			return (maxRange - distance) / (maxRange - cutoff);
		}
	}

	/** WK tier is a PHASE check: {@code LavaJump.isInBossArena} is one box that already contains the WK arena. */
	public static BeamRange beamRange(Player p) {
		if(MobStats.witherKingPhaseActive()) return BeamRange.WITHER_KING;
		return listeners.LavaJump.isInBossArena(p.getLocation()) ? BeamRange.BOSS_ARENA : BeamRange.DEFAULT;
	}

	// ===================== computing a hit =====================

	/** Weapon = main hand at the time. */
	public static double melee(Player p, LivingEntity target, ItemStack weapon) {
		ItemDef def = Items.of(weapon);
		applyOnHitDebuffs(p, target, DamagePath.MELEE, def);
		Breakdown b = Breakdown.begin();
		return finish(p, target, DamagePath.MELEE, def, statCore(p, DamagePath.MELEE, true, b), null, b);
	}

	/** Melee rescaled by Mage Staff, then faded by distance. Counts as melee for the whole sword enchant list. */
	public static double beam(Player p, LivingEntity target, ItemStack weapon, double distance) {
		ItemDef def = Items.of(weapon);
		applyOnHitDebuffs(p, target, DamagePath.BEAM, def);
		StatBlock stats = Stats.of(p, DamagePath.BEAM);
		Breakdown b = Breakdown.begin();
		double core = statCore(p, DamagePath.BEAM, true, b);
		double beamMultiplier = BEAM_BASE + BEAM_PER_INTELLIGENCE * stats.get(Stat.INTELLIGENCE);
		BeamRange range = beamRange(p);
		double falloff = range.falloff(distance);
		if(b != null) {
			// Labelled "Intelligence": at these Int levels the 0.30 is a rounding error.
			b.factor("Intelligence", beamMultiplier);
			if(falloff < 1.0) b.factor("Distance falloff", falloff);
		}
		return finish(p, target, DamagePath.BEAM, def, core * beamMultiplier * falloff, null, b);
	}

	/**
	 * Bare punch: melee with NO WEAPON, so no held-item stats, no {@link ItemDef}, no reforge or lore-ID
	 * multipliers. What a non-melee item lands. A bow used to run the full melee path and a Precise Terminator
	 * punched for most of a sword; in SkyBlock a bow melee is a punch.
	 * <p>
	 * ENCHANTS go, ATTRIBUTES stay: Sharpness, Smite, Giant/Titan Killer, Execute, Prosecute, First/Triple Strike and
	 * the mob-type enchants are skipped in {@link #additivePercent}. Rulers, Warrior, Elite, Dominance, combo, Combat
	 * 60, Ring of Love, pet, Draconic Artifact, class bonuses and all non-held stats stay.
	 * <p>
	 * Hits only the punched mob: no Lethality, procs or Cleave ({@link #dealPunch}).
	 */
	public static double punch(Player p, LivingEntity target) {
		Breakdown b = Breakdown.begin();
		return finish(p, target, DamagePath.MELEE, null, statCore(Stats.unarmed(p), true, b), null, b, false);
	}

	/** STAT half of melee, no target. For thrown axes, which fix damage at THROW time. */
	public static double meleeCore(Player p) {
		return statCore(p, DamagePath.MELEE, true, null);
	}

	/** TARGET half of melee, when a thrown axe connects. */
	public static double meleeFinish(Player p, LivingEntity target, ItemStack weapon, double core) {
		ItemDef def = Items.of(weapon);
		applyOnHitDebuffs(p, target, DamagePath.MELEE, def);
		Breakdown b = Breakdown.begin();
		// Settled at throw time, so the breakdown can only show it as one number.
		if(b != null) b.base("Stat core", core);
		return finish(p, target, DamagePath.MELEE, def, core, null, b);
	}

	/**
	 * STAT half of a bow shot, stamped by {@link Arrows} at fire time (§1.0.5).
	 *
	 * @param weapon the bow that fired, not the main hand: bonus arrows are stamped ticks later
	 * @param crit   false only for a partial draw, which loses the whole crit term
	 */
	public static double bowCore(Player p, ItemDef weapon, boolean crit) {
		return statCore(Stats.of(p, DamagePath.BOW, weapon), crit, null);
	}

	/**
	 * TARGET half of a bow shot, when the arrow lands.
	 *
	 * @param blocksTravelled for Snipe IV's +4% per 10 blocks
	 */
	public static double bowFinish(Player p, LivingEntity target, ItemDef weapon, double core, double blocksTravelled, boolean headshot) {
		Breakdown b = Breakdown.begin();
		// Stamped at fire time, so one figure, like a thrown axe.
		if(b != null) b.base("Stat core", core);
		return finish(p, target, DamagePath.BOW, weapon, core, new BowContext(blocksTravelled, headshot), b);
	}

	/** Whole bow shot in one call, for paths resolved at hit time (Salvation beam, never draw-scaled). */
	public static double bow(Player p, LivingEntity target, ItemStack weapon, double chargeFraction, double blocksTravelled, boolean headshot) {
		ItemDef def = Items.of(weapon);
		applyOnHitDebuffs(p, target, DamagePath.BOW, def);
		boolean full = chargeFraction >= 1.0;
		double charge = Math.max(0, Math.min(chargeFraction, 1.0));
		// Not bowCore + bowFinish (same numbers) so /verbose super gets a full breakdown instead of one stat core.
		Breakdown b = Breakdown.begin();
		double core = statCore(p, DamagePath.BOW, full, b) * charge;
		if(b != null && charge < 1.0) b.factor("Draw", charge);
		return finish(p, target, DamagePath.BOW, def, core, new BowContext(blocksTravelled, headshot), b);
	}

	/**
	 * Right-click ability: no Strength, no Crit Damage. Intended as an option, not a damage strategy. Base takes the
	 * Catacombs bonus ({@link Scale#SB_CATA_MULT}) like item stats; skipping it made every ability 6.65x too weak.
	 */
	public static double ability(Player p, LivingEntity target, ItemStack weapon) {
		ItemDef def = Items.of(weapon);
		if(def == null || def.ability() == null) return 0;
		applyOnHitDebuffs(p, target, DamagePath.ABILITY, def);
		StatBlock stats = Stats.of(p, DamagePath.ABILITY);
		double base = abilityBase(def);
		double intelligence = 1.0 + (stats.get(Stat.INTELLIGENCE) / 100.0) * def.ability().intelligenceScaling();
		double abilityDamage = 1.0 + stats.get(Stat.ABILITY_DAMAGE) / 100.0;
		double core = base * intelligence * abilityDamage;
		Breakdown b = Breakdown.begin();
		if(b != null) {
			// No Strength/Crit rows: x1 rows would imply they were considered. SCALED base, as the tooltip shows.
			b.base("Base Damage", base);
			b.factor("Intelligence", intelligence);
			b.factor("Ability Damage", abilityDamage);
		}
		return finish(p, target, DamagePath.ABILITY, def, core, null, b);
	}

	/**
	 * STAT half of a cast, the ability counterpart of {@link #bowCore}: the Guided Bat fixes damage at FIRE time.
	 *
	 * @return 0 if the weapon has no computed ability
	 */
	public static double abilityCore(Player p, ItemStack weapon) {
		ItemDef def = Items.of(weapon);
		if(def == null || def.ability() == null) return 0;
		StatBlock stats = Stats.of(p, DamagePath.ABILITY);
		double intelligence = 1.0 + (stats.get(Stat.INTELLIGENCE) / 100.0) * def.ability().intelligenceScaling();
		double abilityDamage = 1.0 + stats.get(Stat.ABILITY_DAMAGE) / 100.0;
		return abilityBase(def) * intelligence * abilityDamage;
	}

	/** TARGET half of a cast, on impact. Debuffs first, like {@link #meleeFinish}. */
	public static double abilityFinish(Player p, LivingEntity target, ItemDef weapon, double core) {
		applyOnHitDebuffs(p, target, DamagePath.ABILITY, weapon);
		Breakdown b = Breakdown.begin();
		// Settled earlier, so one number, like bowFinish and meleeFinish.
		if(b != null) b.base("Stat core", core);
		return finish(p, target, DamagePath.ABILITY, weapon, core, null, b);
	}

	/**
	 * Base after the dungeon stage: a DUNGEON item's wiki figure takes the Catacombs bonus (§7), as in
	 * {@link ItemDef#stats}. Authored {@code 10_000} stays the overworld tooltip; Wither Impact casts from 66,500.
	 */
	static double abilityBase(ItemDef def) {
		double base = def.ability().baseDamage();
		return def.dungeonItem() ? base * Scale.SB_CATA_MULT : base;
	}

	private record BowContext(double blocksTravelled, boolean headshot) {
	}

	/**
	 * Health moves in thousandths of an MC health point (1,000 SkyBlock damage): fine enough nobody notices, coarse
	 * enough that health isn't a 15-digit double. Thousandths lose least of tenths/hundredths/thousandths.
	 * STORED HEALTH only; the floating number and {@code /verbose} show the unrounded figure, so they can differ by
	 * half a step on purpose.
	 */
	private static final double HP_STEP = 0.001;

	/** Cheap on purpose: runs on every instance at Terminator rates. */
	private static double roundHp(double mcDamage) {
		return Math.round(mcDamage / HP_STEP) * HP_STEP;
	}

	/** Melee/bow stat half. {@code crit} false only for a partial draw; §7 makes every hit crit so runs are comparable. */
	private static double statCore(Player p, DamagePath path, boolean crit, Breakdown b) {
		return statCore(Stats.of(p, path), crit, b);
	}

	/** Against a caller-chosen aggregate (unarmed, for {@link #punch}). */
	private static double statCore(StatBlock stats, boolean crit, Breakdown b) {
		double base = Scale.PLAYER_BASE_DAMAGE + stats.get(Stat.DAMAGE);
		double strength = 1.0 + stats.get(Stat.STRENGTH) / 100.0;
		double critDamage = crit ? 1.0 + stats.get(Stat.CRIT_DAMAGE) / 100.0 : 1.0;
		if(b != null) {
			b.base("Base Damage", base);
			b.factor("Strength", strength);
			// Partial draw loses the crit term entirely, so no row.
			if(crit) b.factor("Crit Damage", critDamage);
		}
		return base * strength * critDamage;
	}

	/** Damage-level stage: one additive factor, then the multiplicative product. */
	private static double finish(Player p, LivingEntity target, DamagePath path, ItemDef weapon, double core, BowContext bow, Breakdown b) {
		return finish(p, target, path, weapon, core, bow, b, true);
	}

	/** {@code weaponEnchants} false for a {@link #punch}: drops only the enchant half of the additive sum. */
	private static double finish(Player p, LivingEntity target, DamagePath path, ItemDef weapon, double core, BowContext bow, Breakdown b, boolean weaponEnchants) {
		if(target == null || core <= 0) return 0;
		double additive = additivePercent(p, target, path, weapon, bow, weaponEnchants);
		double multiplicative = multiplicative(p, target, path, weapon);
		double total = core * (1.0 + additive / 100.0) * multiplicative;
		if(b != null) {
			b.factor("Additive Damage", 1.0 + additive / 100.0);
			b.factor("Multiplicative Damage", multiplicative);
			b.complete(total);
		}
		return total;
	}

	// ===================== additive =====================

	/**
	 * {@code sumAdditive} for one hit, as a percentage. Beam counts as melee. Bows lose the sword-only enchants
	 * (Execute, Prosecute, First/Triple Strike, Giant/Titan Killer, Sharpness), so arrows miss Prosecute's +100% and
	 * Titan Killer's +80%.
	 */
	private static double additivePercent(Player p, LivingEntity target, DamagePath path, ItemDef weapon, BowContext bow, boolean weaponEnchants) {
		Set<MobType> types = MobStats.typesOf(target);
		boolean sword = path.isMelee() || path == DamagePath.ABILITY;
		double sum = 0;

		// --- always on ---
		sum += COMBAT_60;
		sum += RING_OF_LOVE;                       // assumed always procs
		sum += DOMINANCE;                          // assumed always (full health)
		if(MobStats.isElite(target)) sum += ELITE;
		sum += CombatState.comboAdditive(p);
		for(MobType t : types) if(t.hasRuler()) sum += RULER;
		if(target instanceof EnderDragon) sum += DRACONIC_ARTIFACT;
		Pet pet = Pet.forPlayer(p, path);
		sum += pet.damageAdditive(types);

		// --- ENCHANTMENTS, on the WEAPON, so a punch gets none. Everything outside this block (attributes, potions,
		// pet, class) belongs to the player and a punch keeps it.
		if(weaponEnchants) {
			if(weapon != null && weapon.swarm()) sum += SWARM_V_PER_ENEMY * swarmEnemies(p);
			// These four are on swords AND bows; the rest are sword-only.
			if(types.contains(MobType.CUBIC)) sum += CUBISM_VI;
			if(types.contains(MobType.AIRBORNE)) sum += GRAVITY_VI;
			if(types.contains(MobType.AQUATIC)) sum += IMPALING_V;
			if(types.contains(MobType.INFERNAL)) sum += SMOLDERING_V;
			if(sword) {
				if(types.contains(MobType.SKELETAL) || types.contains(MobType.WITHER) || types.contains(MobType.UNDEAD)) {
					sum += SMITE_VII;
				}
				if(types.contains(MobType.ARTHROPOD)) sum += BANE_OF_ARTHROPODS_VII;
				if(types.contains(MobType.ENDER)) sum += ENDER_SLAYER_VII;
				if(types.contains(MobType.MAGMATIC)) sum += PYROCLASM_VI;

				// --- either/or pairs: take the larger, NEVER sum ---
				sum += Math.max(giantKiller(), titanKiller(target));
				sum += Math.max(execute(target), prosecute(target));
			}
			if(path.isMelee()) {
				// First/Triple Strike: melee-only, either/or.
				double firstStrike = CombatState.isFirstHitOn(p, target.getUniqueId()) ? FIRST_STRIKE_V : 0;
				double tripleStrike = CombatState.isTripleStrikeHitOn(p, target.getUniqueId()) ? TRIPLE_STRIKE_V : 0;
				sum += Math.max(firstStrike, tripleStrike);
				sum += SHARPNESS_VII;
			}
			if(path == DamagePath.BOW) {
				sum += POWER_VII;
				if(bow != null) {
					sum += SNIPE_IV_PER_10_BLOCKS * (bow.blocksTravelled() / 10.0);
					// Precise headshot is the reforge's, still the weapon's.
					if(bow.headshot() && weapon != null && weapon.reforge() == ReforgeId.PRECISE) sum += PRECISE_HEADSHOT;
				}
			}
		}

		// --- attributes and potions: the PLAYER's, so they survive a punch ---
		if(path.isMelee()) sum += WARRIOR;
		if(path == DamagePath.BOW) {
			sum += SKELETOR;
			sum += ARCHERY_IV_POTION;
		}

		// --- class bonuses (§1.14); Berserk's repeated-hit stack is the largest additive source ---
		DungeonClass clazz = DungeonClass.of(p);
		sum += ClassBonuses.damageAdditive(p, clazz, path, target.getUniqueId(), DungeonClass.isSoloOnClass(p));
		return sum;
	}

	/** Live enemies within 10 blocks of the PLAYER at the hit, not the target, capped at 10. */
	private static int swarmEnemies(Player p) {
		int n = 0;
		for(org.bukkit.entity.Entity e : p.getNearbyEntities(SWARM_RADIUS, SWARM_RADIUS, SWARM_RADIUS)) {
			if(!(e instanceof org.bukkit.entity.Enemy) || !(e instanceof LivingEntity mob)) continue;
			if(mob.isDead() || mob.getHealth() <= 0 || mob.getScoreboardTags().contains("TASDying")) continue;
			if(mob.getLocation().distanceSquared(p.getLocation()) > SWARM_RADIUS * SWARM_RADIUS) continue;
			if(++n >= SWARM_MAX_ENEMIES) break;
		}
		return n;
	}

	private static double giantKiller() {
		// Giant Killer VII: +65%, target HP assumed always far above the player's.
		return GIANT_KILLER_VII;
	}

	/** Titan Killer VII: +20% per 100 defense, cap +80%. */
	private static double titanKiller(LivingEntity target) {
		double defense = MobStats.defenseOf(target);
		return Math.min(TITAN_KILLER_CAP, TITAN_KILLER_PER_100_DEFENSE * defense / 100.0);
	}

	/** Execute VI: +1.25% per 1% MISSING health. Overtakes Prosecute below ~44%. */
	private static double execute(LivingEntity target) {
		return EXECUTE_VI_PER_PERCENT_MISSING * (100.0 - healthPercent(target));
	}

	/** Prosecute VI: +1% per 1% REMAINING health, +100% at full. */
	private static double prosecute(LivingEntity target) {
		return PROSECUTE_VI_PER_PERCENT_REMAINING * healthPercent(target);
	}

	private static double healthPercent(LivingEntity target) {
		var attr = target.getAttribute(Attribute.MAX_HEALTH);
		double max = attr == null ? 0 : attr.getValue();
		if(max <= 0) return 100;
		return Math.clamp(target.getHealth() / max, 0.0, 1.0) * 100.0;
	}

	// ===================== multiplicative =====================

	/** {@code product(Multiplicative_i)}. Order doesn't matter. */
	private static double multiplicative(Player p, LivingEntity target, DamagePath path, ItemDef def) {
		double product = BOOK_OF_PROGRESSION;

		// Hyperion x1.5 vs EVERY Wither-type mob (Lords, Miners, trash), NOT the Withered Dragons. Old code had this
		// inside out as "-33% vs non-withers" (1/1.5); only this form survives.
		if(def != null && "skyblock/combat/scylla".equals(def.loreId()) && MobStats.typesOf(target).contains(MobType.WITHER)) {
			product *= HYPERION_VS_WITHER;
		}
		// Death Bow x2 vs Undead: Lords, Miners/trash, Crypt Undead, Watcher's Undeads, Prince; NOT the dragons or
		// Shadow Assassins. Keyed on lore ID so it follows the WEAPON, and Duplex / Archer-bonus arrows get it too.
		if(def != null && "skyblock/combat/death_bow".equals(def.loreId()) && MobStats.typesOf(target).contains(MobType.UNDEAD)) {
			product *= DEATH_BOW_VS_UNDEAD;
		}
		if(def != null && path.isMelee()) product *= def.reforge().meleeMultiplier();   // Fabled x1.15
		if(path == DamagePath.BOW) product *= OVERLOAD;                                  // assumed always procs
		if(path == DamagePath.ABILITY) product *= lovingMultiplier(p);
		if(path.isMelee() && CombatState.isTarantulaHit(p, target.getUniqueId())) product *= TARANTULA_RING;

		DungeonClass clazz = DungeonClass.of(p);
		product *= ClassBonuses.damageMultiplier(p, clazz, path, DungeonClass.isSoloOnClass(p));

		// Two x1.1 target debuffs; on the target, so they help every attacker.
		product *= TargetDebuffs.damageMultiplier(target);
		return product;
	}

	/** Loving's x1.05, abilities only. A CHESTPLATE reforge, so only that slot is read. */
	private static double lovingMultiplier(Player p) {
		ItemDef def = Items.of(p.getInventory().getChestplate());
		if(def != null && def.reforge() == ReforgeId.LOVING) return def.reforge().abilityMultiplier();
		return 1.0;
	}

	// ===================== debuffs the hit itself applies =====================

	/**
	 * Apply this hit's debuffs BEFORE its damage is computed, so it benefits from them (§7 ordering; the opposite of
	 * the obvious implementation). Also called alone on paths that skip damage: stacks land even when damage
	 * doesn't, so a beam on an invulnerable boss still builds Lethality and Venomous.
	 */
	public static void applyOnHitDebuffs(Player p, LivingEntity target, DamagePath path, ItemDef weapon) {
		applyOnHitDebuffs(p, target, path, weapon, true);
	}

	/** {@code buildsLastBreath} false for the Archer's two bonus arrows. Full rule at {@link Arrows#stamp}. */
	public static void applyOnHitDebuffs(Player p, LivingEntity target, DamagePath path, ItemDef weapon, boolean buildsLastBreath) {
		if(target == null) return;
		// Lethality is a sword enchant; bows never build it.
		if(path.isMelee()) TargetDebuffs.applyLethality(target, p);
		if(path == DamagePath.BOW) {
			TargetDebuffs.applyTwilightPoison(target);
			if(weapon == null || !weapon.swarm()) TargetDebuffs.applyDuplexFire(target);
			if(buildsLastBreath && weapon != null && "skyblock/combat/last_breath".equals(weapon.loreId())) {
				TargetDebuffs.applyLastBreath(target);
			}
		}
	}

	/** For call sites holding the stack, not the definition. */
	public static void applyOnHitDebuffs(Player p, LivingEntity target, DamagePath path, ItemStack weapon) {
		applyOnHitDebuffs(p, target, path, Items.of(weapon));
	}

	// ===================== the single application boundary =====================

	/**
	 * Deal a computed SkyBlock-scale hit, the primary instance for procs and Cleave.
	 * <p>
	 * Returns the REPORTED hit: after defense and resistance, before any boss clamp, what {@link DamageNumbers}
	 * shows. Callers printing their own summary (Implosion's "hit N enemies for X") must use this, not their
	 * sbDamage, or they report pre-defense numbers.
	 * <p>
	 * <b>Aggro only if health actually moved</b>, every path: not on an armoured Maxor, Goldor mid-terminals or
	 * Necron mid-interlude (clamped to feedback-only). The beam, thrown axes and Flaming Flay arc may aggro a fully
	 * invulnerable wither, and do it at their own armour checks before calling here, the only place that state is
	 * visible.
	 * <p>
	 * {@code dealNoAggro} for arrows is gone; arrows and melee are the same case now.
	 */
	public static double deal(LivingEntity target, double sbDamage, DamageKind kind, Player attacker, DamagePath path) {
		return deal(target, sbDamage, kind, attacker, path, true, true, true);
	}

	/**
	 * DERIVED instance: figure copied from the rolling history, already finished (Rapid Fire 75%, Explosive Shot
	 * 100%, Berserk's thrown axe). Same as {@link #deal} except it does NOT feed the history it read.
	 * <p>
	 * §1.14's "only real hits go in", load-bearing: Rapid Fire's 50 arrows over 200 ticks each re-query, so any
	 * factor above 1 compounds fifty times and overflows a double. Even at 1.0 it's wrong: each hit would re-stamp
	 * the old max with the current tick and "highest in the last minute" would never decay.
	 */
	public static double dealDerived(LivingEntity target, double sbDamage, DamageKind kind, Player attacker, DamagePath path) {
		return deal(target, sbDamage, kind, attacker, path, true, true, false);
	}

	/** A {@link #punch}: aggros like a hit, but no procs, Cleave or history. */
	public static double dealPunch(LivingEntity target, double sbDamage, Player attacker) {
		return deal(target, sbDamage, DamageKind.NORMAL, attacker, DamagePath.MELEE, false, true, false);
	}

	/**
	 * Secondary instance (Cleave or proc): same boundary, but makes no Cleave or procs of its own, one level only.
	 * Never aggros; {@link DamageKind#pullsAggro} enforces that too.
	 */
	public static double dealSecondary(LivingEntity target, double sbDamage, DamageKind kind, Player attacker) {
		return deal(target, sbDamage, kind, attacker, DamagePath.MELEE, false, false, false);
	}

	/**
	 * @return the reported hit (SkyBlock scale, after defense and resistance, before any boss clamp), 0 if nothing landed.
	 */
	private static double deal(LivingEntity target, double sbDamage, DamageKind kind, Player attacker, DamagePath path, boolean primary, boolean aggro, boolean feedsHistory) {
		if(target == null || sbDamage <= 0) return 0;

		// Never-touch targets first.
		// Villager NPCs (Mort / Wizard). This used to hit with genericKill, the same source as vanilla /kill, so
		// blocking at the source is what lets a KILL-cause event on a villager mean a real /kill.
		if(target instanceof Villager) return 0;
		// The Watcher can't be damaged; you win by killing its Undeads.
		if(target.getScoreboardTags().contains("TASWatcher")) return 0;
		if(target.getScoreboardTags().contains(instructions.bosses.necron.NecronDrag.PIG_TAG)) return 0;
		// A guided carrier in flight (Guided Sheep, Guided Bat): its own blast removes it.
		if(target.getScoreboardTags().contains(items.ItemUtils.GUIDED_TAG)) return 0;
		// A dying WK dragon is a corpse. Vanilla ticks dragonDeathTime toward 200 at health 0, but Bukkit
		// setHealth(0) calls die() and EnderDragon.handleKillingBlow sets health back to 1, pausing it. So each hit
		// rewound the animation a tick (swinging every tick froze the dragon in the air), drew a full number, fired
		// procs/Cleave, and re-ran the whole death sequence incl. EntityDeathEvent and loot. Refuse here.
		if(instructions.bosses.witherking.WitherKing.isDyingDragon(target)) return 0;
		// Aggro used to be noted here, before the immunity returns, so a boss chased whoever hit it through an armoured
		// window. Now it's noted below where health moved; the three shield-aggro abilities note it themselves.

		// WK is immune to direct damage; HP moves only via dragon kills. Debuffs already landed at the call site.
		if(target.getScoreboardTags().contains("TASWitherKing")) return 0;

		double defense = TargetDebuffs.reducedDefense(target, MobStats.defenseOf(target));
		// Armorshred: BOW-path divisor uses x0.95 defense, target untouched. Gated on path, so Salvation and
		// Explosive Shot get it too, like Power VII, Archery IV, Skeletor and Overload.
		if(path == DamagePath.BOW) defense *= ARMORSHRED_DEFENSE;
		double resistance = MobStats.resistanceOf(target);
		double mcDamage = sbDamage * resistance / Scale.defenseDivisor(defense) / Scale.SB_PER_MC_HP;
		double preClamp = mcDamage;

		// Armoured wither takes nothing. Call sites check too, but this stops Cleave/procs slipping past.
		// WithersNotImmuneToArrows' same-tick "vulnerable then re-armoured" exception clears the counter first.
		// Venomous still shows its number through the shield, health untouched.
		if(target instanceof Wither armoured && armoured.getInvulnerableTicks() != 0) {
			if(kind == DamageKind.VENOMOUS && !target.getScoreboardTags().contains("TASDying")) {
				DamageNumbers.show(target, preClamp * Scale.SB_PER_MC_HP, kind, attacker);
			}
			return 0;
		}

		// Hurt sound on PRE-clamp damage, or hits past Maxor's 75% / Storm's 55% stun cap go silent.
		witherHurtSound(target, attacker, mcDamage, kind);

		// Read before the clamp: a killing blow's clamp enters the dying state and tags TASDying mid-hit.
		boolean dyingBefore = target.getScoreboardTags().contains("TASDying");

		// Boss clamps (Maxor 75% stun cap, Storm 55% crush cap, Necron thresholds, Goldor patrol immunity, dying
		// states), called explicitly since no EntityDamageEvent fires.
		//
		// A clamp decides HOW MUCH HEALTH MOVES, never what's reported: `preClamp` feeds the floating number and
		// /verbose Final Damage, `mcDamage` the health bar. So Goldor/Necron immune windows show full numbers with no
		// per-boss opt-in (showsUnclampedDamage is gone), and a killing blow reads as the full hit.
		if(target instanceof Wither wither) {
			WitherLord lord = WitherLord.activeFor(wither);
			if(lord != null) mcDamage = lord.clampDamage(mcDamage);
		}

		// Health moves in HP_STEP, after clamps. mcDamage stays unrounded: the number and /verbose report the TRUE hit.
		double applied = roundHp(mcDamage);

		double healthBefore = target.getHealth();
		// What THIS hit wrote. The kill check uses it, not a re-read: setHealth(0) runs vanilla's death and an
		// EnderDragon's handleKillingBlow sets health back to 1 (phase DYING), so a re-read sees a live 1-HP dragon
		// on its killing hit and handleDragonKilled never runs.
		double healthAfter = healthBefore;
		if(applied > 0) {
			// Belt and braces: a mob that took vanilla damage a tick ago would still carry an i-frame window.
			target.setNoDamageTicks(0);
			healthAfter = Math.max(0, healthBefore - applied);
			target.setHealth(healthAfter);
			// setHealth skips the red hurt flash, so send it ourselves.
			Utils.broadcastPacket(new ClientboundHurtAnimationPacket(((CraftLivingEntity) target).getHandle()));
			Utils.changeName(target);
			// Aggro only here, where health moved. That IS the rule, every path.
			if(aggro) noteAggro(target, attacker, kind, preClamp);
		}

		// Kill chokepoints: no event fires on this path, so deaths are detected here. Gated on ALIVE before this hit
		// so Cleave/procs on a corpse can't re-arm the post-kill buff or pad the combo.
		if(healthBefore > 0 && healthAfter <= 0) {
			if(target.getScoreboardTags().contains("WatcherMob")) {
				instructions.bosses.Watcher.INSTANCE.registerMobKill(target);
			}
			if(target instanceof org.bukkit.entity.EnderDragon dragon && dragon.getScoreboardTags().contains("WitherKingDragon")) {
				instructions.bosses.witherking.WitherKing.handleDragonKilled(dragon);
			}
			if(attacker != null) CombatState.noteKill(attacker);
			TargetDebuffs.forget(target);
		}

		if(attacker != null && primary) {
			// History takes only PRIMARY hits that weren't read out of it. Its consumers want a best HIT, so recording
			// a Venomous tick or Rapid Fire arrow closes a feedback loop. Same rule as dealDerived; neither optional.
			if(feedsHistory) CombatState.recordDamage(attacker, sbDamage);
			CombatState.noteHit(attacker, target.getUniqueId(), path);
			CombatState.spendPostKillBuff(attacker);
		}

		// What SkyBlock shows: after resistance and defense, BEFORE clamp, unquantised. Three figures: `preClamp`
		// (displayed), `mcDamage` (clamp allowed), `applied` (quantised, the only one health sees).
		double reported = preClamp * Scale.SB_PER_MC_HP;
		// Draw only for a target ALIVE to take it. Uses `healthBefore` + `dyingBefore` since this runs after setHealth
		// and the clamp, and live state would hide the killing blow's number. Suppresses hits on corpses (late Cleave/procs, stray
		// arrows) and on a boss at DYING_SLIVER, where TASDying is the only tell (HP frozen non-zero).
		boolean showsNumber = healthBefore > 0 && !dyingBefore;
		if(showsNumber) DamageNumbers.show(target, reported, kind, attacker);
		// /verbose uses the SAME gate; corpse procs and Cleave were the bulk of the log.
		verbose(attacker, target, sbDamage, mcDamage, preClamp, defense, resistance, kind, showsNumber);
		if(primary) {
			Procs.onHit(attacker, target, sbDamage, path);
			Cleave.spread(attacker, target, sbDamage, path);
		}
		return reported;
	}

	/**
	 * Only reached when health moved. Per-KIND gate here too: only direct hits aggro, never procs or Cleave, whatever
	 * the caller passed. Only the four boss withers (TASWither) have an aggro target.
	 */
	private static void noteAggro(LivingEntity target, Player attacker, DamageKind kind, double preClamp) {
		if(attacker == null || !kind.pullsAggro()) return;
		if(!(target instanceof Wither wither) || !target.getScoreboardTags().contains("TASWither")) return;
		// Pre-clamp, so two hits a stun cap squashes to the same health still rank by size.
		WitherActions.noteDamager(attacker, wither, preClamp);
	}

	/**
	 * Boss wither hurt noise. Moved here from {@code MiscListener.onWitherHurtSound}, whose event this path never
	 * fires. Rules:
	 * <ul>
	 *   <li>PRE-clamp damage, so a stun-capped hit still sounds;</li>
	 *   <li>silent while dying (only the death noise);</li>
	 *   <li>silent for a beam, which sends its own constant-volume sound to the beamer;</li>
	 *   <li>DIRECT hits only ({@link DamageKind#playsHurtSound}): DoTs made one swing six noises.</li>
	 * </ul>
	 */
	private static void witherHurtSound(LivingEntity target, Player attacker, double preClampDamage, DamageKind kind) {
		if(!(target instanceof Wither wither) || preClampDamage <= 0) return;
		if(!kind.playsHurtSound()) return;
		WitherLord lord = WitherLord.activeFor(wither);
		if(lord != null && lord.isDying()) return;
		if(items.ItemUtils.beamDamageInProgress) return;

		org.bukkit.Location loc = wither.getLocation();
		wither.getWorld().playSound(loc, org.bukkit.Sound.ENTITY_WITHER_HURT, 1.0f, 1.0f);
		if(attacker != null) attacker.playSound(loc, org.bukkit.Sound.ENTITY_WITHER_HURT, 1.0f, 1.0f);
	}

	// ===================== §7a verbose breakdowns =====================

	/**
	 * One hit's formula terms in multiply order. Threaded through the formulas, since {@link #deal} only gets a
	 * finished double that can't be decomposed.
	 * <p>
	 * {@link #begin()} is null unless {@code /verbose super}, and producers null-guard, so the normal path allocates
	 * nothing (Terminator rates would be thousands of concatenations a second).
	 * <p>
	 * {@link #complete} parks it in {@link #lastBreakdown} for {@link #verbose}. Main-thread only; verbose CONSUMES it
	 * and checks the total matches, so a Cleave hit or proc (no formula) can't print the previous hit's rows.
	 */
	private static final class Breakdown {
		private String baseLabel = "Base Damage";
		private double baseValue;
		private final java.util.List<String> rows = new java.util.ArrayList<>();
		private double total;

		static Breakdown begin() {
			return Utils.isSuperVerbose() ? new Breakdown() : null;
		}

		void base(String label, double value) {
			baseLabel = label;
			baseValue = value;
		}

		void factor(String label, double value) {
			rows.add(label + ": " + factorText(value));
		}

		void complete(double total) {
			this.total = total;
			lastBreakdown = this;
		}
	}

	private static Breakdown lastBreakdown;

	/**
	 * {@code showsNumber} is {@link DamageNumbers}' gate: no floating number, no log, at both levels. Otherwise corpse
	 * procs and Cleave (several per kill, over the remaining Fire/Venomous windows) are most of the log.
	 */
	private static void verbose(Player attacker, LivingEntity target, double sbDamage, double mcDamage, double preClamp, double defense, double resistance, DamageKind kind, boolean showsNumber) {
		// Always consumed, or the next formula-less hit (proc, Cleave) inherits these rows.
		Breakdown b = lastBreakdown;
		lastBreakdown = null;
		if(!showsNumber) return;
		if(Utils.getVerboseLevel().ordinal() < Utils.VerboseLevel.ON.ordinal()) return;

		// Final Damage = Total x defense x boss, no clamp, so the breakdown factorises fully. Clamp is its own line.
		double finalDamage = preClamp * Scale.SB_PER_MC_HP;
		double dealt = mcDamage * Scale.SB_PER_MC_HP;
		double defenseFactor = 1.0 / Scale.defenseDivisor(defense);
		boolean clamped = Math.abs(preClamp - mcDamage) > 1e-9;

		if(!Utils.isSuperVerbose()) {
			// `on`: total, target-side reductions as ONE factor, result; a fourth line only if a clamp bit.
			Utils.debug(Utils.DebugType.BOSS, attacker, "Total Damage: " + integer(sbDamage) + "\n  Defense & Boss Multiplier: " + factorText(defenseFactor * resistance) + "\n  Final Damage: " + integer(finalDamage) + (clamped ? "\n  Dealt (boss clamp): " + integer(dealt) : ""));
			return;
		}

		// `super`: every term. Player-side rows come from the Breakdown, so an ability has no Strength row, not a x1.
		StringBuilder sb = new StringBuilder();
		sb.append(kind).append(' ').append(attacker == null ? "?" : attacker.getName()).append(" -> ").append(target.getName());
		if(b != null && Math.abs(b.total - sbDamage) <= 1e-6) {
			sb.append("\n  ").append(b.baseLabel).append(": +").append(trimZeros(Utils.roundCommas(b.baseValue, 2)));
			for(String row : b.rows) sb.append("\n  ").append(row);
		}
		sb.append("\n  Total Damage: ").append(integer(sbDamage));
		sb.append("\n  Defense (").append(defenseText(target, defense)).append("): ").append(factorText(defenseFactor));
		sb.append("\n  Boss Multiplier: ").append(factorText(resistance));
		sb.append("\n  Final Damage: ").append(integer(finalDamage));
		// What boss mechanics let through, only when different. Clamps act AFTER everything above, so it's a line,
		// not a factor.
		if(clamped) sb.append("\n  Dealt (boss clamp): ").append(integer(dealt));
		Utils.debug(Utils.DebugType.BOSS, attacker, sb.toString());
	}

	/** EFFECTIVE defense, or {@code raw -> effective} when Lethality, Last Breath or Armorshred reduced it. */
	private static String defenseText(LivingEntity target, double effective) {
		double raw = MobStats.defenseOf(target);
		String shown = trimZeros(Utils.roundCommas(effective, 2));
		if(Math.abs(raw - effective) <= 1e-6) return shown;
		return trimZeros(Utils.roundCommas(raw, 2)) + " -> " + shown;
	}

	/** {@code x100}, {@code x1.05}, {@code x0.0769}. */
	private static String factorText(double value) {
		return "x" + trimZeros(Utils.roundCommas(value, 4));
	}

	/** {@code x100}, not {@code x100.0000}. */
	private static String trimZeros(String s) {
		if(s.indexOf('.') < 0) return s;
		int end = s.length();
		while(end > 0 && s.charAt(end - 1) == '0') end--;
		if(end > 0 && s.charAt(end - 1) == '.') end--;
		return s.substring(0, end);
	}

	/**
	 * The one "hit N enemies" line for every group ability (Implosion, Guided Bat, Explosive Shot, Guided Sheep,
	 * thrown axe). Silent on no hits; counts only what {@code deal} REPORTED, so zeroed targets (armoured wither,
	 * NPC, clamped boss) aren't counted, keeping it in line with the floating numbers.
	 * <p>
	 * ONE decimal place like the real message ({@code Your Spirit Sceptre hit 1 enemy for 66,342.2 damage.}), so not
	 * {@link #integer}; a whole total reads {@code 66,342.0}.
	 *
	 * @param ability name as the message says it, not always the ability: "Implosion", but the Sceptre names the ITEM
	 * @param hits    targets that reported above zero
	 * @param dealt   sum of what they reported, SkyBlock damage
	 */
	public static void reportAoe(Player p, String ability, int hits, double dealt) {
		if(p == null || hits <= 0) return;
		p.sendMessage(Utils.msg("<gray>Your " + ability + " hit <red>" + hits + "</red> "
				+ (hits == 1 ? "enemy" : "enemies") + " for <red>" + Utils.roundCommas(dealt, 1) + "</red> damage."));
	}

	/** Every digit, never abbreviated (§7a), comma-separated. {@code Locale.ROOT} so it's a comma on every host. */
	public static String integer(double value) {
		return String.format(java.util.Locale.ROOT, "%,d", (long) Math.floor(value));
	}

	/** Itemised stats for {@code /verbose super} and {@code /eq}; per player, not per hit. */
	public static Map<String, StatBlock> statBreakdown(Player p, DamagePath path) {
		return new LinkedHashMap<>(Stats.breakdown(p, path));
	}
}
