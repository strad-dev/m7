package damage;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import plugin.M7;

/**
 * Damage carried by a guided carrier: Spirit Sceptre's bat and Mage's Guided Sheep.
 * <p>
 * Same rule as arrows (§1.0.5): the figure is written into the entity's PDC at spawn and the click is over. The
 * flight ({@code items.ItemUtils.launchGuided}) knows nothing about damage and the blast just reads the stamp, so
 * turning, swapping weapons, losing a buff or dying mid-flight changes nothing.
 * <p>
 * Two stamp forms, and the wrong one is a real bug, so {@link #hit} is the only way to apply one:
 * <ul>
 *   <li>{@link #stamp} - ITEM ability stat core, still owed the target half; finished at
 *       {@link Damage#abilityFinish} and dealt primary.</li>
 *   <li>{@link #stampFlat} - FINISHED figure (Guided Sheep's flat class-level damage), via
 *       {@link Damage#dealDerived}: the target half would charge Rulers, Smite and class multiplier it never had, and
 *       feeding the history would seed what Explosive Shot and Rapid Fire read.</li>
 * </ul>
 * Deliberately parallel to {@code damage/Arrows}, down to key names; separate because a carrier is a living animal
 * on the ability path with no draw, pierce or Last Breath.
 */
public final class GuidedCarriers {
	private GuidedCarriers() {}

	/** Stat core, or a finished hit if {@link #DERIVED} is set. */
	private static final NamespacedKey CORE = key("guided_core");
	/** Plain display name, so the target half resolves against the item that fired it. */
	private static final NamespacedKey WEAPON = key("guided_weapon");
	/** 1 if {@link #CORE} is a FINISHED figure. See {@link #stampFlat}. */
	private static final NamespacedKey DERIVED = key("guided_derived");
	/** For the blast's "hit N enemies" line. */
	private static final NamespacedKey ABILITY = key("guided_ability");

	private static NamespacedKey key(String name) {
		return new NamespacedKey(M7.getInstance(), name);
	}

	/**
	 * Stamp an ITEM ability's stat core, from {@link Damage#abilityCore} at fire time.
	 *
	 * @param ability display name, for the "hit N enemies" line
	 * @param weapon  recorded by display name so the finish reads the right definition
	 */
	public static void stamp(LivingEntity carrier, String ability, ItemStack weapon, double core) {
		if(carrier == null) return;
		var pdc = carrier.getPersistentDataContainer();
		pdc.set(CORE, PersistentDataType.DOUBLE, core);
		ItemDef def = Items.of(weapon);
		pdc.set(WEAPON, PersistentDataType.STRING, def == null ? "" : def.displayName());
		pdc.set(DERIVED, PersistentDataType.INTEGER, 0);
		pdc.set(ABILITY, PersistentDataType.STRING, ability);
	}

	/** Stamp a finished figure, which {@link #hit} deals derived. */
	public static void stampFlat(LivingEntity carrier, String ability, double sbDamage) {
		if(carrier == null) return;
		var pdc = carrier.getPersistentDataContainer();
		pdc.set(CORE, PersistentDataType.DOUBLE, sbDamage);
		pdc.set(DERIVED, PersistentDataType.INTEGER, 1);
		pdc.set(ABILITY, PersistentDataType.STRING, ability);
	}

	/** Stamped ability name, or {@code ""} if unstamped. */
	public static String abilityName(LivingEntity carrier) {
		if(carrier == null) return "";
		return carrier.getPersistentDataContainer().getOrDefault(ABILITY, PersistentDataType.STRING, "");
	}

	/**
	 * Resolve the blast against one mob and deal it. Like {@code Arrows.hit}: the figure and how it's dealt are one
	 * decision.
	 *
	 * @return the reported hit, as {@link Damage#deal} defines it, or 0 if nothing landed
	 */
	public static double hit(LivingEntity carrier, Player shooter, LivingEntity target) {
		if(carrier == null || shooter == null || target == null) return 0;
		var pdc = carrier.getPersistentDataContainer();
		Double core = pdc.get(CORE, PersistentDataType.DOUBLE);
		if(core == null || core <= 0) return 0;
		if(pdc.getOrDefault(DERIVED, PersistentDataType.INTEGER, 0) == 1) {
			return Damage.dealDerived(target, core, DamageKind.MAGIC, shooter, DamagePath.ABILITY);
		}
		ItemDef def = Items.byName(pdc.getOrDefault(WEAPON, PersistentDataType.STRING, ""));
		double sbDamage = Damage.abilityFinish(shooter, target, def, core);
		return Damage.deal(target, sbDamage, DamageKind.MAGIC, shooter, DamagePath.ABILITY);
	}
}
