package damage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accessory Powers and tuning points (MAP.md §1.12). Assumed, never items; the selected Power scales off total
 * Magical Power.
 * <p>
 * Everything derives from ONE input, the accessory list; §1.12 says hardcoding MP or tunings loses that.
 * <p>
 * Not dungeon items, so no x6.65 or x1.80.
 */
public final class Powers {
	private Powers() {}

	/** Accessory bag MP OUTSIDE a dungeon, including one copy of each dungeon accessory below. The maximum. */
	private static final double OVERWORLD_ACCESSORY_MP = 2206;

	/** Enriched non-dungeon accessories. Each enrichment is +1 tuning point, never a stat. */
	private static final int OVERWORLD_ENRICHMENTS = 61;

	/**
	 * Dungeon accessories and their MP, the standard per-rarity values (Mythic 22, Legendary 16, Epic 12). "Double
	 * Accessory Power inside Dungeons" means each adds its MP again. A per-ACCESSORY property, never a global x2 on
	 * the total, which would give 4242 and overshoot by 88%.
	 */
	private static final Map<String, Double> DUNGEON_ACCESSORIES = new LinkedHashMap<>();

	static {
		DUNGEON_ACCESSORIES.put("Auto Recombobulator", 22.0);
		DUNGEON_ACCESSORIES.put("Master Skull Tier VII", 22.0);
		DUNGEON_ACCESSORIES.put("Scarf's Grimoire", 22.0);
		DUNGEON_ACCESSORIES.put("Treasure Artifact", 22.0);
		DUNGEON_ACCESSORIES.put("Wither Relic", 22.0);
		DUNGEON_ACCESSORIES.put("Catacombs Expert Ring", 16.0);
		DUNGEON_ACCESSORIES.put("Catacombs Gobbler", 16.0);
		DUNGEON_ACCESSORIES.put("General's Medallion", 12.0);
		DUNGEON_ACCESSORIES.put("Infinipot", 12.0);
	}

	/** M7 only, so the dungeon doubling is ALWAYS active. */
	public static double magicalPower() {
		double bonus = 0;
		for(double mp : DUNGEON_ACCESSORIES.values()) bonus += mp;
		return OVERWORLD_ACCESSORY_MP + bonus;
	}

	/** Legendary and up can be enriched: the 61 plus the Legendary+ dungeon accessories. */
	public static int enrichments() {
		int n = OVERWORLD_ENRICHMENTS;
		for(double mp : DUNGEON_ACCESSORIES.values()) if(mp >= 16) n++;
		return n;
	}

	/** Wiki's curve. Reproduces the 250 AP row for all three powers and Silky's 2000 AP row exactly; weights recovered from those. */
	public static double multiplier(double magicalPower) {
		return 29.97 * Math.pow(Math.log(0.0019 * magicalPower + 1.0), 1.2);
	}

	/**
	 * {@code weights} are per unit of multiplier; {@code unique} is a flat bonus that doesn't scale (Eccentric
	 * Painting's table keeps Ability Damage at +5 on every AP row).
	 */
	public enum Power {
		/** Magma Urchin. Archer, Berserk, Healer, Tank. */
		HURTFUL(StatBlock.of(Stat.STRENGTH, 4.8, Stat.CRIT_DAMAGE, 19.2), StatBlock.EMPTY),
		/** Luxurious Spool, Mage BEAM power. 0.6 Speed weight unmodelled. */
		SILKY(StatBlock.of(Stat.CRIT_DAMAGE, 22.8), StatBlock.EMPTY),
		/** Eccentric Painting, Mage ABILITY power. Costs Strength and Crit Damage, which that path can't use anyway. */
		BIZARRE(StatBlock.of(Stat.INTELLIGENCE, 43.2, Stat.CRIT_DAMAGE, -2.4, Stat.STRENGTH, -2.4),
				StatBlock.of(Stat.ABILITY_DAMAGE, 5));

		private final StatBlock weights;
		private final StatBlock unique;

		Power(StatBlock weights, StatBlock unique) {
			this.weights = weights;
			this.unique = unique;
		}

		public StatBlock stats(double magicalPower) {
			return weights.times(multiplier(magicalPower)).plus(unique);
		}
	}

	/** Mage's is path-dependent (Silky beam, Bizarre ability), the second reason the cache is keyed {@code (player, path)}. */
	public static Power powerFor(DungeonClass clazz, DamagePath path) {
		if(clazz != DungeonClass.MAGE) return Power.HURTFUL;
		return path == DamagePath.ABILITY ? Power.BIZARRE : Power.SILKY;
	}

	/** DERIVED: {@code floor(MP/10) + 13 + enrichments}, +13 from an attribute. */
	public static int tuningPoints(double magicalPower) {
		return (int) Math.floor(magicalPower / 10.0) + 13 + enrichments();
	}

	/** All points on Crit Damage, 1 point = +1. Flat, not dungeon-scaled. */
	public static StatBlock tunings(double magicalPower) {
		return StatBlock.of(Stat.CRIT_DAMAGE, tuningPoints(magicalPower));
	}

	/** Power plus tunings. */
	public static StatBlock forClass(DungeonClass clazz, DamagePath path) {
		double mp = magicalPower();
		return powerFor(clazz, path).stats(mp).plus(tunings(mp));
	}
}
