package damage;

/**
 * Stats the damage system reads (MAP.md §1). {@link #core} = the four core stats (§1.0.1) that take the full x6.66;
 * the rest take stars-only x1.81 (§1.0.2). Crit Chance is only stored because reforges grant it; §7 makes every hit
 * a crit.
 */
public enum Stat {
	DAMAGE("Damage", "<red>", true),
	STRENGTH("Strength", "<red>", true),
	CRIT_DAMAGE("Crit Damage", "<blue>", true),
	INTELLIGENCE("Intelligence", "<aqua>", true),
	CRIT_CHANCE("Crit Chance", "<blue>", false),
	ABILITY_DAMAGE("Ability Damage", "<red>", false);

	// Stat glyphs (❁ ☠ ✎ ☣ ๑) used to live here for lore and /eq rows. Removed: Hypixel draws them from a resource-pack
	// font, so vanilla clients showed tofu boxes.

	private final String display;
	private final String colour;
	private final boolean core;

	Stat(String display, String colour, boolean core) {
		this.display = display;
		this.colour = colour;
		this.core = core;
	}

	/** As on item lore ("Crit Damage"). */
	public String display() {
		return display;
	}

	/** MiniMessage colour tag. */
	public String colour() {
		return colour;
	}

	/** Takes the full Catacombs multiplier, not stars-only (§1.0.1-2). */
	public boolean core() {
		return core;
	}
}
