package damage;

/**
 * One named contribution to one stat on one item (MAP.md §2.4).
 * <p>
 * <b>Terms, never totals.</b> Ragnarock Axe Strength is seven terms (base, stars, books, art of war, reforge, gem,
 * Chimera), not 626. §2.4: no pre-summed (340, 626, 749.5) or pre-scaled (2264.4, 1278.72, 5727.6) constant anywhere;
 * those are outputs.
 * <p>
 * Only BASE, CATA_LEVEL and STARS are authored values. The rest store an ID: {@link Upgrade}, {@link Reforges},
 * {@link Gemstones}, {@link Pet} for Chimera.
 */
public record StatTerm(Source source, Stat stat, double value) {

	public enum Source {
		/** As the wiki prints it. */
		BASE,
		/** Itemised beside base so x6.66 stays a pipeline stage. */
		CATA_LEVEL,
		/** Non-dungeon star bonus, flat since nothing scales it (§1.0.3). */
		STARS,
		BOOKS,
		ENCHANT,
		REFORGE,
		GEMSTONE,
		CHIMERA
	}

	public static StatTerm base(Stat stat, double value) {
		return new StatTerm(Source.BASE, stat, value);
	}

	public static StatTerm cataLevel(Stat stat, double value) {
		return new StatTerm(Source.CATA_LEVEL, stat, value);
	}

	public static StatTerm stars(Stat stat, double value) {
		return new StatTerm(Source.STARS, stat, value);
	}
}
