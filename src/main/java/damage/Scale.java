package damage;

/**
 * Scale constants the damage system is built on. §2.4: nothing pre-scaled or pre-summed anywhere else, so each is
 * used in one place and every "in dungeons" figure is derived at runtime.
 */
public final class Scale {
	private Scale() {}

	/**
	 * SkyBlock HP per MC health point (§5). Everything in this package is real SkyBlock units; this is the only
	 * conversion, once in {@link Damage#apply}. {@code double} is exact to 2^53, so billions are safe.
	 */
	public static final double SB_PER_MC_HP = 1_000_000.0;

	/**
	 * Catacombs scaling for the four core stats on a DUNGEON item (§1.0.1), stars and cata level together, so
	 * authored terms are plain unscaled values.
	 * <p>
	 * 6.65, not 6.66: the General's Medallion nerf took 0.01x off every stat, moving this and {@link #SB_STAR_MULT}
	 * together. Both measured, not derived; if the medallion moves they move.
	 * <p>
	 * Also applies to an ABILITY'S BASE DAMAGE (§7); the wiki lists the base as "increased by Catacombs Stat Bonus".
	 * Wither Impact's tooltip is {@code base x (1 + Int/100 x 0.3) x (1 + AbilityDamage/100)}, so dividing out the
	 * factors recovers the base: 10,000 outside, 66,500 = 10,000 x 6.65 inside:
	 * <pre>
	 * Int  6,175.88  AbilityDamage 144.5  ->    477,450.6  = 10,000 x  47.745
	 * Int  3,390.55  AbilityDamage  90    ->    212,261.4  = 10,000 x  21.226
	 * Int 17,695.39  AbilityDamage 134    ->  8,416,350.1  = 66,500 x 126.562
	 * Int 23,403.71  AbilityDamage 220    -> 15,153,727.7  = 66,500 x 227.876
	 * </pre>
	 * The base used to skip this, understating EVERY ability 6.65x: Wither Impact read ~65M on a 300M Wither Miner
	 * where a real M7 Mage does hundreds of millions. Applied only in {@link Damage#abilityBase}.
	 */
	public static final double SB_CATA_MULT = 6.66;

	/** Stars-only scaling for non-core dungeon stats (§1.0.2), e.g. Ability Damage. 1.80 not 1.81, same nerf. */
	public static final double SB_STAR_MULT = 1.81;

	/** Boss / mini-boss flat x0.1 on top of defense (§5). Without it the chain is 10x too fast. */
	public static final double BOSS_RESISTANCE = 0.10;

	/** Player's own +5 Damage (§1.0.4), the formula's {@code 5 +}. Never in an item block, never dungeon-scaled. */
	public static final double PLAYER_BASE_DAMAGE = 5.0;

	/**
	 * Hypixel's curve (§5): {@code sbDamage / (1 + defense/100)}. NOT vanilla's {@code min(20, armor)/25}; see §5
	 * for why {@code minecraft:armor} stays 0.
	 */
	public static double defenseDivisor(double defense) {
		return 1.0 + Math.max(0.0, defense) / 100.0;
	}
}
