package plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import instructions.bosses.WitherActions;
import instructions.clear.ClearManager;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Facts about a finished /m7practice run (ticks, score, who was there), attached to {@link RunCompleteEvent}. Knows
 * nothing about leaderboards or categories; that's the listener's business, so M7 stays standalone. A listener
 * that won't compile against this can read {@link #toJson()} with one reflective call.
 * <br>
 * Server ticks (20/s). "Overall" ticks are from the run's t=0 ({@link Utils#runTick()}); phase durations from their
 * boss's start ({@link Utils#phaseTick()}). A {@code null} Integer means not reached, e.g. clear milestones on a
 * boss-only practice.
 */
public final class RunResult {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** /m7practice section: all, clear, boss, maxor, storm, goldor, necron, witherking. */
	public String section;

	/** Same across every report this run makes ({@link WitherActions#runId()}). */
	public String runId;

	/** False only for a failed run (enraged Storm with no pillars left). */
	public boolean success;

	/**
	 * {@code Difficulty.id()}: {@code classic}, {@code perfect_rng} or {@code rta} (MAP.md §0).
	 * <p>
	 * Times across modes aren't comparable (live modes maintain four debuffs and depend on blessings collected;
	 * Realistic adds puzzles and the pet), so records key on it. The network's board key is
	 * {@code category|groupSize|difficulty}; legacy 2-part keys migrate to {@code classic}, which reproduces the old
	 * hand-tuned damage.
	 * <p>
	 * Realistic writes {@code rta}: {@code realistic} is already on disk for runs under what is now Perfect RNG. The
	 * network's {@code Leaderboards} rewrites those old keys once, by raw string.
	 */
	public String difficulty;

	/**
	 * {@code damage/Mayor.id()}: {@code jerry_paul}, {@code paul}, {@code jerry}, {@code other} or {@code derpy};
	 * the class javadoc has what each changes.
	 * <p>
	 * Times across mayors aren't comparable (a Derpy full clear tops out at 309, not 319). The network folds the
	 * five onto three boards: Paul, Other, Derpy.
	 */
	public String mayor;

	/**
	 * Run had Fix Watcher Bug on ({@code plugin/WatcherFix}). The network's {@code Leaderboards.submit} keeps it off the
	 * Clear and Full Run boards; boss boards still take it.
	 */
	public boolean watcherFix;

	/**
	 * Run had Bat Bug on ({@code plugin/BatBug}). The network's {@code Leaderboards.submit} keeps it off the 300 Score and
	 * Full Clear boards and never counts it as S+.
	 */
	public boolean batBug;

	/** {@link Utils#runTick()} at completion. */
	public int runTicks;

	/** Clear ended (boss portal entered, or blood done on a clear-only run); null if no clear. */
	public Integer clearEndTick;

	/** Blood finished; null if never. */
	public Integer bloodDoneTick;

	/** First reached 300 score; null if never. */
	public Integer score300Tick;

	/** Became a full clear (max score AND blood done); null otherwise. */
	public Integer fullClearTick;

	/** Final team score; null with no clear phase. */
	public Integer teamScore;

	/** Grade for {@link #teamScore} (S+, S, A...); null with no clear phase. */
	public String grade;

	/** Phase-relative duration per completed boss: Maxor, Storm, Goldor, Necron, WitherKing. */
	public Map<String, Integer> phaseDurations;

	/** Overall tick each section finished: Clear, Maxor, Storm, Terminals, Goldor, Necron, WitherKing. */
	public Map<String, Integer> splitEnds;

	/**
	 * The run's roster, including anyone who disconnected ({@link WitherActions#noteInRun}). Group size comes from
	 * this, so a duo whose second player lags out is still a duo.
	 */
	public List<Participant> participants = new ArrayList<>();

	public static final class Participant {
		public String uuid;
		public String name;
		/**
		 * Never left Adventure all run (the scoreboard's golden-name check). For a disconnect, the state they left
		 * with; quitting isn't a mode change.
		 */
		public boolean stayedAdventure;

		Participant(WitherActions.RosterMember m) {
			this.uuid = m.uuid().toString();
			this.name = m.name();
			this.stayedAdventure = m.stayedAdventure();
		}
	}

	private RunResult() {}

	/**
	 * Call at completion: ticks and score are read live, and only a player still in Adventure can be ADDED to the
	 * roster ({@code /m7practice end} makes everyone a spectator).
	 */
	public static RunResult capture(String section, boolean success) {
		RunResult r = new RunResult();
		r.section = section;
		r.runId = WitherActions.runId();
		r.success = success;
		r.difficulty = damage.Difficulty.current().id();
		r.mayor = damage.Mayor.current().id();
		r.watcherFix = WatcherFix.enabled();
		r.batBug = BatBug.enabled();
		r.runTicks = Utils.runTick();
		r.phaseDurations = WitherActions.phaseDurations();
		r.splitEnds = WitherActions.splitEnds();
		r.clearEndTick = WitherActions.getSplitEnd("Clear");

		// Only if a clear ran: on a boss-only practice teamScore() reports a meaningless ~120 from an unexplored map,
		// which would poison a leaderboard.
		if (ClearManager.isActive()) {
			r.teamScore = ClearManager.teamScore();
			r.grade = ClearManager.grade();
			r.bloodDoneTick = nullIfUnset(ClearManager.bloodDoneTick());
			r.score300Tick = nullIfUnset(ClearManager.score300Tick());
			r.fullClearTick = nullIfUnset(ClearManager.fullClearTick());
		}

		// Refresh with who's here, then report the WHOLE roster; reporting only survivors turned a duo into a solo.
		for (Player p : ClearManager.realPlayers()) WitherActions.noteInRun(p);
		for (WitherActions.RosterMember m : WitherActions.runRoster()) r.participants.add(new Participant(m));
		return r;
	}

	private static Integer nullIfUnset(int tick) {
		return tick < 0 ? null : tick;
	}

	/** For listeners that don't compile against this class. */
	public String toJson() {
		return GSON.toJson(this);
	}
}
