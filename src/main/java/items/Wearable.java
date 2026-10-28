package items;

import death.CheatDeath;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Armour sets, wearable heads, Spring Boots. Replaced display-name comparisons in four places
 * ({@code ClassKits.isCowHat} etc, {@code MaxSpeedSync}, {@code isThermoSet}, {@code CheatDeath.pick}).
 * <p>
 * A wearable affects DAMAGE only through its stats (§1.10, §8). The old x0.70 / x0.80 penalties on Spring Boots,
 * Racing Helmet, Cow Hat and masks are deleted: the helmet slot is exclusive, so a Cow Hat already costs the Storm
 * Helmet's Intelligence and a multiplier on top double-penalised it.
 */
public interface Wearable extends Item {

	EquipmentSlot slot();

	/**
	 * Added to <b>Max Speed</b>. A BONUS, not a total: it used to be the helmet's finished number (400 / 550 / 650),
	 * which folded in the Black Cat's +150, so Cow Hat and Racing Helmet disagreed about a stat neither owns.
	 * {@code plugin/MaxSpeedSync} sums base, Alpha shard, pet and helmet now.
	 */
	default int maxSpeedBonus() {
		return 0;
	}

	/**
	 * Bonzo's Mask, Spirit Mask. The Phoenix is itemless so has no {@code Wearable}. Precedence is
	 * {@link CheatDeath.Saver}'s declaration order.
	 */
	default CheatDeath.Saver saver() {
		return null;
	}

	/**
	 * "" for none. Only Thermodynamic has a bonus, a RATE one: 4/4 turns the Terminator's 5-tick cooldown into 4.
	 * Deliberately not a per-hit multiplier.
	 */
	default String setId() {
		return "";
	}

	/** Cancels the Wither King relic carry debuff (Cow Hat). */
	default boolean exemptsRelicDebuff() {
		return false;
	}
}
