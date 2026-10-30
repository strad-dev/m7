package damage;

import instructions.clear.ClearManager;
import plugin.Utils;

/**
 * Run mode: classic, Perfect RNG, realistic (MAP.md §0).
 * <p>
 * <b>A flag on inputs, not a second damage path.</b> The five damage inputs that differ are target states or stat
 * inputs, never constants in a formula: classic = lookups answer yes, live modes = lookups read live state. Bake one
 * into a hit calculation as a literal and the live modes become a rewrite.
 *
 * <table>
 *   <caption>What the modes change</caption>
 *   <tr><th>Input</th><th>Classic</th><th>Perfect RNG and realistic</th></tr>
 *   <tr><td>Last Breath (up to x0.5 defense)</td><td>all 5 stacks</td><td>built by landing arrows</td></tr>
 *   <tr><td>Lethality (x0.91^4 defense)</td><td>all 4 stacks</td><td>built by hitting</td></tr>
 *   <tr><td>Ice Spray debuff (x1.1)</td><td>always</td><td>cast, 8 blocks, 5s</td></tr>
 *   <tr><td>Twilight Arrow Poison (x1.1)</td><td>always</td><td>bow hit, 20s</td></tr>
 *   <tr><td>Blessing LEVELS</td><td>maxed</td><td>the chests the party actually opened</td></tr>
 *   <tr><td>Death</td><td>impossible</td><td>the storm, Goldor and relic instakills all fire</td></tr>
 * </table>
 *
 * Classic lets a player focus on movement and routing without keeping up four debuffs and a blessing count. The
 * TAS was reasoned in it. Realistic is the default.
 * <p>
 * <b>Perfect RNG and realistic differ only in what's done BY HAND</b>, never damage. Perfect RNG rolls in your
 * favour: easy terminal boards, Sharp Shooter in the one learnable order, fixed dragon colours. Realistic takes that
 * away: {@link #realPuzzles()} turns on generated terminals, real Arrow Align, rolled Sharp Shooter, dragon orders and
 * Simon Says sequences. Both run their own pet menu ({@link #manualPets()}).
 * <p>
 * Ask {@link #liveInputs()}, never {@code == PERFECT_RNG}, which silently drops realistic to the classic tables.
 * <p>
 * Times across modes aren't comparable, so anything recording a run carries the mode ({@code plugin/RunResult},
 * network leaderboard key).
 * <p>
 * First of the {@code /dungeonsettings}; {@link Mayor} is independent and stacks with any mode.
 */
public enum Difficulty {
	/**
	 * Perfect RNG plus everything a real run makes you do by hand: generated terminals, working devices.
	 * <p>
	 * <b>Id is {@code rta}, not {@code realistic}</b>: {@code realistic} is on disk as the id of every run under what
	 * is now Perfect RNG, and board keys are permanent, so reusing it would make old and new files indistinguishable.
	 * "RTA Mode" was the design-time name. Default.
	 */
	REALISTIC("rta", "Realistic"),
	/**
	 * Live damage inputs and death, every roll goes your way: stand-in terminal boards, fixed Sharp Shooter and
	 * dragon orders. <b>Used to be called "Realistic"</b>; old "Ultra Realistic" folded into it
	 * (see network {@code Leaderboards} migration) and is where the stand-in boards come from.
	 */
	PERFECT_RNG("perfect_rng", "Perfect RNG"),
	/** TAS style: all four debuffs applied, blessings maxed, nobody dies. */
	CLASSIC("classic", "Classic");

	// Maxed blessing levels, the classic table (§0). TOTAL levels: sum over every blessing of that type collected.
	private static final int MAX_POWER = 29;
	private static final int MAX_TIME = 5;
	private static final int MAX_WISDOM = 14;
	private static final int MAX_STONE = 9;

	private static Difficulty current = REALISTIC;

	private final String id;
	private final String displayName;

	Difficulty(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public static Difficulty current() {
		return current;
	}

	public static void set(Difficulty d) {
		if(d != null) current = d;
	}

	/** Next mode in declaration order, wrapping. */
	public static Difficulty toggle() {
		Difficulty[] all = values();
		current = all[(current.ordinal() + 1) % all.length];
		return current;
	}

	/** Previous mode, wrapping. Right-click on the menu button. */
	public static Difficulty toggleBack() {
		Difficulty[] all = values();
		current = all[(current.ordinal() + all.length - 1) % all.length];
		return current;
	}

	/** Any case, or null. Tries {@link #id()} (network ids), then enum name, then {@link #ALIASES}. */
	public static Difficulty parse(String s) {
		if(s == null) return null;
		for(Difficulty d : values()) if(d.id.equalsIgnoreCase(s)) return d;
		for(Difficulty d : values()) if(d.name().equalsIgnoreCase(s)) return d;
		return ALIASES.get(s.toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * Typed spellings; only feed {@link #parse}, <b>never persisted</b>. {@code realistic} here means
	 * {@link #REALISTIC}, not the old leaderboard-key id (now {@link #PERFECT_RNG}); those keys are rewritten once by
	 * raw string in network {@code Leaderboards}, never through here. {@code ultra*} -> Perfect RNG, where it folded.
	 */
	private static final java.util.Map<String, Difficulty> ALIASES = java.util.Map.ofEntries(
			java.util.Map.entry("realistic", REALISTIC),
			java.util.Map.entry("rta_mode", REALISTIC),
			java.util.Map.entry("rtamode", REALISTIC),
			java.util.Map.entry("real", REALISTIC),
			java.util.Map.entry("perfectrng", PERFECT_RNG),
			java.util.Map.entry("perfect-rng", PERFECT_RNG),
			java.util.Map.entry("prng", PERFECT_RNG),
			java.util.Map.entry("rng", PERFECT_RNG),
			java.util.Map.entry("ultra_realistic", PERFECT_RNG),
			java.util.Map.entry("ultrarealistic", PERFECT_RNG),
			java.util.Map.entry("ultra-realistic", PERFECT_RNG),
			java.util.Map.entry("ultra", PERFECT_RNG));

	/** For run payload and leaderboard key. Must match the network's {@code m7.lb.Difficulty} ids. */
	public String id() {
		return id;
	}

	/** E.g. "Perfect RNG". Must match the network's {@code displayName}. */
	public String displayName() {
		return displayName;
	}

	/**
	 * Typed / tab-completed spelling. Every usage string, error and completion uses this; every persisted value uses
	 * {@code id()}. Differs only for Realistic ({@code realistic} vs stored {@code rta}): the id is a permanent key,
	 * and players shouldn't need to know it. Both parse; this only decides which we teach.
	 */
	public String commandName() {
		return this == REALISTIC ? "realistic" : id;
	}

	/** Debuff and defense-reduction lookups answer "applied" without checking. */
	public static boolean debuffsAssumed() {
		return current == CLASSIC;
	}

	/** Debuff and blessing lookups read live state, in EITHER live mode. Test here, never {@code == PERFECT_RNG}. */
	public static boolean liveInputs() {
		return current != CLASSIC;
	}

	/**
	 * Gate on every instakill in {@code death/Deaths} and Goldor's death ticks. Both live modes. Read live at each
	 * kill site, not latched; the mode is a global {@code /m7practice} sets before the run arms.
	 */
	public static boolean deathsEnabled() {
		return current != CLASSIC;
	}

	/**
	 * Real generated content instead of stand-ins: {@code goldor/GoldorTerminalGui} boards, working Arrow Align, rolled
	 * orders (Sharp Shooter's nine, the Wither King's five dragons, Simon Says' sequence). Realistic only. Changes no
	 * damage or timing, only how much work a section is, so nothing in {@code damage/} may read it.
	 */
	public static boolean realPuzzles() {
		return current == REALISTIC;
	}

	/**
	 * The real S1 device ({@code goldor/GoldorSimonSays}) instead of the 12-click stand-in. Both live modes; outside
	 * {@link #realPuzzles()} it plays one fixed order.
	 */
	public static boolean realSimonSays() {
		return current != CLASSIC;
	}

	/**
	 * Player manages their own pet ({@code pets/}). Both live modes; the one case {@code damage/Pet.forPlayer} stops
	 * answering from its table.
	 */
	public static boolean manualPets() {
		return current != CLASSIC;
	}

	// What a blessing is WORTH lives in damage/Blessings. This class owns only which LEVEL is in force.

	/**
	 * Total level the formulas use. Classic: maxed table. Live modes: the run's chest history, falling back to maxed
	 * with no clear phase this session. Public because {@code plugin/BlessingState} publishes it: collected and used
	 * differ whenever {@link #blessingsAssumedMax()}, and showing only one misreports every classic run.
	 */
	public static int blessingLevel(Utils.BlessingType type) {
		if(liveInputs() && clearPhaseInThisSession()) return ClearManager.collectedLevel(type);
		return maxBlessingLevel(type);
	}

	/** The maxed table: every blessing of {@code type} on the map. 0 for Life, which has no row. */
	public static int maxBlessingLevel(Utils.BlessingType type) {
		return switch(type) {
			case POWER -> MAX_POWER;
			case TIME -> MAX_TIME;
			case WISDOM -> MAX_WISDOM;
			case STONE -> MAX_STONE;
			default -> 0;
		};
	}

	/**
	 * {@link #blessingLevel} is answering from the maxed table: always in classic, and in a live mode with no clear
	 * phase. Published on {@code plugin/BlessingState} so a display can say which it shows.
	 */
	public static boolean blessingsAssumedMax() {
		return !(liveInputs() && clearPhaseInThisSession());
	}

	/** Live clear covers a run in progress; a non-empty tally covers after the clear hands off to the bosses. */
	private static boolean clearPhaseInThisSession() {
		return ClearManager.hasBlessingData();
	}
}
