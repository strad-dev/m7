package instructions.bosses.goldor;

import listeners.GoldorListener;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import plugin.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real S1 Simon Says: the 4x4 obsidian grid lights a sequence, the party plays it back on buttons. Both live modes;
 * classic keeps the 12-click stand-in in {@code GoldorListener.processSimonClick}. Realistic rolls the cells, Perfect
 * RNG always plays {@link #FIXED_ORDER}.
 * <ol>
 *   <li>Start click opens a {@value #SPAM_WINDOW_TICKS}-tick spam window; it is click #1.</li>
 *   <li>Each further start click queues a lantern, counted per player.</li>
 *   <li>At close each player's count becomes skips ({@link #SKIP_THRESHOLDS}), summed: 6, 6 and 3 clicks = 2+2+1 = 5.</li>
 *   <li>Enough skips to pass the last phase completes it on the spot.</li>
 *   <li>Else N lanterns play back to back; the answer is the last S+1, which becomes the running sequence.</li>
 *   <li>Buttons go UP, the party answers, buttons come DOWN; each correct answer appends a cell and replays.</li>
 *   <li>Answering {@link #targetLength()} completes it.</li>
 * </ol>
 * User's rule: <i>"The buttons show up when the sea lantern sequence finishes. They disappear after the player
 * correctly inputs the solution for each step."</i> "Each step" = per PHASE, not per press; the only reading where a
 * sequence longer than one cell is playable.
 * <p>
 * Solves itself: the owed button is OAK, the rest stone ({@link #paintButtons}), like Same Color's hints.
 * <p>
 * A wrong button is a no-op. Hypixel resets on a wrong press, so this is a deliberate divergence; re-clicking start
 * is the reset.
 */
public final class GoldorSimonSays {

	public static final GoldorSimonSays INSTANCE = new GoldorSimonSays();

	/**
	 * The one coordinate table, {@code [row][column] -> {x, y, z}}: row 0 is the top ({@code y = 123}), column 0 is
	 * {@code z = 92}, as seen from the west. The exposed face is WEST, so buttons sit at {@code x - 1}. Read from the
	 * world file (matches {@code m7-1 test}), pending the user's confirmation; {@link #BUTTONS} derives from it.
	 * <ul>
	 *   <li>No lantern layer behind ({@code x = 112} is stone bricks), so the flash swaps the obsidian itself.</li>
	 *   <li>The 16 buttons aren't there at rest; only the start button at {@code 110 121 91} is. One slot,
	 *       {@code 110 121 93}, holds the {@code i1} sign, hence the BlockState restore.</li>
	 * </ul>
	 */
	private static final int[][][] GRID = {
			{{111, 123, 92}, {111, 123, 93}, {111, 123, 94}, {111, 123, 95}},  // top row
			{{111, 122, 92}, {111, 122, 93}, {111, 122, 94}, {111, 122, 95}},
			{{111, 121, 92}, {111, 121, 93}, {111, 121, 94}, {111, 121, 95}},
			{{111, 120, 92}, {111, 120, 93}, {111, 120, 94}, {111, 120, 95}},  // bottom row
	};

	private static final int ROWS = GRID.length;
	private static final int COLS = GRID[0].length;
	/** Also the ceiling on sequence length. */
	private static final int CELL_COUNT = ROWS * COLS;

	/** Perfect RNG's sequence, {@code row * COLS + col}: [1,1], [1,2], [2,2], [2,1], clockwise round the middle. */
	private static final int[] FIXED_ORDER = {5, 6, 10, 9};

	/** One block west of its cell, {@code face=wall, facing=west} like the start button. */
	private static final int BUTTON_DX = -1;

	/** DERIVED from {@link #GRID}; never author by hand. */
	private static final int[][] BUTTONS = new int[CELL_COUNT][];

	static {
		for(int i = 0; i < CELL_COUNT; i++) {
			int[] c = cell(i);
			BUTTONS[i] = new int[]{c[0] + BUTTON_DX, c[1], c[2]};
		}
	}

	/** Lit time and playback step; back to back, no gap. */
	private static final int FLASH_TICKS = 10;

	private static final int SPAM_WINDOW_TICKS = 10;

	/**
	 * Clicks for 1-5 skips, per player, then summed. {@code GoldorListener.tryRegisterSimonClick} caps one player at
	 * a click per tick, so solo tops out at {@value #SPAM_WINDOW_TICKS} clicks (3 skips); the last two need a team.
	 */
	private static final int[] SKIP_THRESHOLDS = {3, 6, 10, 15, 21};

	private enum State {
		IDLE,
		/** Start clicks are counted, not a reset. */
		SPAM,
		/** Grid presses don't count. */
		PLAYING,
		AWAITING
	}

	private State state = State.IDLE;
	/** Cell indices 0..15, always distinct, so gapless playback reads. */
	private final List<Integer> sequence = new ArrayList<>();
	/** Correct presses this phase. */
	private int inputIdx = 0;
	/** Per player, since skips are; never collapse to a total. */
	private final Map<UUID, Integer> spamClicks = new HashMap<>();
	/** Or -1. The window is judged by tick arithmetic, not task order. */
	private int windowStartTick = -1;
	/** Credited for a short-circuit. */
	private UUID starterId;
	/** Bumped on every reset and start; queued steps with a stale token do nothing, so a restart orphans the old run. */
	private int runToken = 0;
	/** So the force-restore never has to guess. */
	private World deviceWorld;
	/**
	 * Lit cells and what they replaced. Why {@link #cleanup()} exists: {@code Utils.cancelAllScheduled} kills the
	 * timer that would restore a flash in flight.
	 */
	private final Map<Integer, BlockData> litCells = new LinkedHashMap<>();
	/**
	 * Slot contents before the buttons went up; empty while down. {@link BlockState}, not {@link BlockData}, because
	 * one slot is the {@code i1} sign, whose text block data would lose; the stand-in modes still need it. Taken when
	 * buttons go up, not at startup, so a world reload can't leave a stale state.
	 */
	private final Map<Integer, BlockState> buttonSnapshot = new LinkedHashMap<>();
	private boolean buttonsUp = false;
	/** Lazy, so nothing calls into Bukkit at class init. */
	private BlockData buttonData;
	/** Oak hint button ({@link #paintButtons}). */
	private BlockData hintButtonData;

	private GoldorSimonSays() {
	}

	// ---------- Coordinate table accessors: the ONLY things that read GRID/BUTTONS ----------

	/** Row-major flat index into {@link #GRID}. */
	private static int[] cell(int i) {
		return GRID[i / COLS][i % COLS];
	}

	/**
	 * -1 if not a Simon button. Public: {@code GoldorListener}'s shared click guard (interact event and raw packet)
	 * must know the coords are ours before spending the per-tick dedupe.
	 */
	public static int cellAtButton(int bx, int by, int bz) {
		for(int i = 0; i < CELL_COUNT; i++) {
			int[] b = BUTTONS[i];
			if(b[0] == bx && b[1] == by && b[2] == bz) return i;
		}
		return -1;
	}

	// ---------- Input ----------

	/**
	 * Opens, spams, or resets. Judged on {@link #windowStartTick}, not on whether the close task ran, so a boundary
	 * click resolves the same whatever the scheduler drains first.
	 */
	public void onStartClick(Player p, boolean wasDeferred) {
		if(GoldorListener.cannotSolve(p)) return; // public entry point owns its own gate
		GoldorSection s1 = Goldor.INSTANCE.getSection(0);
		if(s1 == null || s1.device.isActivated()) return;
		if(state == State.SPAM && Utils.serverTick() - windowStartTick < SPAM_WINDOW_TICKS) {
			spamClicks.merge(p.getUniqueId(), 1, Integer::sum);
			Utils.debug(Utils.DebugType.BOSS, "Simon spam click by " + p.getName()
					+ " (" + spamClicks.get(p.getUniqueId()) + ")");
			return;
		}
		// Anything else restarts, mid-playback or mid-answer included: re-clicking start IS the reset.
		beginRun(p);
	}

	/**
	 * Only counts while AWAITING; a wrong cell is a no-op. Buttons only exist then anyway; the state check stays so a
	 * hand-placed button does nothing.
	 */
	public void onCellClick(Player p, int cellIdx, boolean wasDeferred) {
		if(GoldorListener.cannotSolve(p)) return; // public entry point owns its own gate
		GoldorSection s1 = Goldor.INSTANCE.getSection(0);
		if(s1 == null || s1.device.isActivated()) return;
		if(state != State.AWAITING || sequence.isEmpty()) return;
		if(inputIdx >= sequence.size()) return;
		if(sequence.get(inputIdx) != cellIdx) return; // wrong button: no reset, no penalty
		inputIdx++;
		// Move the oak hint to the next owed button.
		if(inputIdx < sequence.size()) { paintButtons(); cue(p); return; }

		// Phase answered: buttons down here, so "down" is the state between windows, not something each branch remembers.
		removeButtons();
		if(sequence.size() >= targetLength()) {
			complete(p, wasDeferred);
			return;
		}
		// Only the press finishing the DEVICE is silent: Goldor.onActivation plays its own and two stack into one louder note.
		cue(p);
		appendCell();
		playBack(sequence);
	}

	/** Correct-press pling, like the terminals, since a wrong press is silently eaten. Presser only, not the room. */
	private static void cue(Player p) {
		p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 2.0F, 2.0F);
	}

	// ---------- The run ----------

	/** The opening click is that player's #1. */
	private void beginRun(Player p) {
		resetRuntime();
		deviceWorld = p.getWorld();
		state = State.SPAM;
		windowStartTick = Utils.serverTick();
		starterId = p.getUniqueId();
		spamClicks.put(p.getUniqueId(), 1);
		final int token = runToken;
		Utils.scheduleTask(() -> { if(token == runToken) closeSpamWindow(); }, SPAM_WINDOW_TICKS);
		Utils.debug(Utils.DebugType.BOSS, "Simon Says started by " + p.getName());
	}

	/** Clicks into skips, then short-circuit or deal the opening sequence. */
	private void closeSpamWindow() {
		if(state != State.SPAM) return;
		GoldorSection s1 = Goldor.INSTANCE.getSection(0);
		if(s1 == null || s1.device.isActivated()) { resetRuntime(); return; }

		int clicks = 0, skips = 0;
		for(int c : spamClicks.values()) {
			clicks += c;
			skips += skipsFor(c);
		}

		// S skips = start at phase S+1, so past the last phase is finishing. The spec's "5+ skips", written so a target length of 4 follows.
		if(skips + 1 > targetLength()) {
			Utils.debug(Utils.DebugType.BOSS, "Simon Says short-circuited on " + skips + " skips");
			complete(resolveSolver(), false);
			return;
		}

		// N distinct cells, so at most 16. Only bites on a big team spread thin (five people could reach 19 clicks on 4 skips).
		int n = Math.max(1, Math.min(clicks, CELL_COUNT));
		int answerLen = Math.min(skips + 1, n);
		List<Integer> played = damage.Difficulty.realPuzzles() ? randomDistinctCells(n) : fixedOpening(n, answerLen);
		n = played.size();
		sequence.clear();
		sequence.addAll(played.subList(n - answerLen, n));
		inputIdx = 0;
		Utils.debug(Utils.DebugType.BOSS, "Simon Says: " + clicks + " clicks, " + skips + " skips, "
				+ n + " lanterns, answer is the last " + answerLen);
		// All N play; only the tail is the answer and carries forward.
		playBack(played);
	}

	/**
	 * {@value #FLASH_TICKS} ticks each, back to back, then take answers. Gapless only reads because cells never
	 * repeat ({@link #appendCell}); two identical neighbours would look like one long flash.
	 */
	private void playBack(List<Integer> toPlay) {
		state = State.PLAYING;
		inputIdx = 0;
		removeButtons(); // a restart can land mid-input
		final int token = ++runToken;
		for(int i = 0; i < toPlay.size(); i++) {
			final int cellIdx = toPlay.get(i);
			Utils.scheduleTask(() -> {
				if(token != runToken) return;
				restoreLitCells();
				flash(cellIdx);
			}, (long) i * FLASH_TICKS);
		}
		Utils.scheduleTask(() -> {
			if(token != runToken) return;
			restoreLitCells();
			state = State.AWAITING;
			inputIdx = 0;
			placeButtons(); // "the buttons show up when the sea lantern sequence finishes"
		}, (long) toPlay.size() * FLASH_TICKS);
	}

	/** A cell not already in the sequence; Perfect RNG's next {@link #FIXED_ORDER} cell. */
	private void appendCell() {
		if(!damage.Difficulty.realPuzzles()) {
			if(sequence.size() < FIXED_ORDER.length) sequence.add(FIXED_ORDER[sequence.size()]);
			return;
		}
		List<Integer> free = new ArrayList<>(CELL_COUNT);
		for(int i = 0; i < CELL_COUNT; i++) if(!sequence.contains(i)) free.add(i);
		if(free.isEmpty()) return; // unreachable at target 5; never repeat a cell
		sequence.add(free.get(ThreadLocalRandom.current().nextInt(free.size())));
	}

	private static List<Integer> randomDistinctCells(int n) {
		List<Integer> all = new ArrayList<>(CELL_COUNT);
		for(int i = 0; i < CELL_COUNT; i++) all.add(i);
		java.util.Collections.shuffle(all, ThreadLocalRandom.current());
		return new ArrayList<>(all.subList(0, Math.min(n, CELL_COUNT)));
	}

	/** Lead-in lanterns are the non-{@link #FIXED_ORDER} cells in index order, then the first {@code answerLen} of it. */
	private static List<Integer> fixedOpening(int n, int answerLen) {
		List<Integer> fixed = new ArrayList<>();
		for(int c : FIXED_ORDER) fixed.add(c);
		List<Integer> out = new ArrayList<>(CELL_COUNT);
		for(int i = 0; i < CELL_COUNT && out.size() < n - answerLen; i++) if(!fixed.contains(i)) out.add(i);
		out.addAll(fixed.subList(0, answerLen));
		return out;
	}

	private static int skipsFor(int clicks) {
		int skips = 0;
		for(int t : SKIP_THRESHOLDS) if(clicks >= t) skips++;
		return skips;
	}

	private static int targetLength() {
		return 4;
	}

	private void complete(Player p, boolean wasDeferred) {
		GoldorSection s1 = Goldor.INSTANCE.getSection(0);
		if(s1 == null || s1.device.isActivated()) return;
		resetRuntime();
		s1.device.markActivated();
		if(p != null) Goldor.INSTANCE.onActivation(p, s1, "device", wasDeferred);
	}

	/** Credit for a short-circuit: the opener, else any non-spectator (like the Sharp Shooter's ninth hit). */
	private Player resolveSolver() {
		Player starter = starterId == null ? null : Bukkit.getPlayer(starterId);
		if(starter != null && !GoldorListener.cannotSolve(starter)) return starter;
		for(Player pl : Bukkit.getOnlinePlayers()) if(!GoldorListener.cannotSolve(pl)) return pl;
		return null;
	}

	// ---------- Blocks ----------

	/** Obsidian → sea lantern, no physics. Keeps the replaced data rather than assuming obsidian, for map edits. */
	private void flash(int cellIdx) {
		if(deviceWorld == null || litCells.containsKey(cellIdx)) return;
		int[] c = cell(cellIdx);
		Block b = deviceWorld.getBlockAt(c[0], c[1], c[2]);
		litCells.put(cellIdx, b.getBlockData().clone());
		b.setType(Material.SEA_LANTERN, false);
	}

	/**
	 * Snapshots each slot, then places. Idempotent: a second snapshot would photograph the buttons and lose the
	 * {@code i1} sign. No physics, which here matters: an update pops a button off the wall.
	 */
	private void placeButtons() {
		if(deviceWorld == null || buttonsUp) return;
		if(buttonData == null) buttonData = Bukkit.createBlockData("minecraft:stone_button[face=wall,facing=west]");
		// Snapshot only; paintButtons places.
		for(int i = 0; i < CELL_COUNT; i++) {
			int[] b = BUTTONS[i];
			Block block = deviceWorld.getBlockAt(b[0], b[1], b[2]);
			// NEVER snapshot a button: a stray one of ours would become the "original" and permanent. Air is underneath.
			if(Tag.BUTTONS.isTagged(block.getType())) {
				Block air = deviceWorld.getBlockAt(b[0], b[1], b[2]);
				air.setType(Material.AIR, false);
				buttonSnapshot.put(i, air.getState());
			} else {
				buttonSnapshot.put(i, block.getState());
			}
		}
		buttonsUp = true;
		paintButtons();
	}

	/**
	 * Built-in solver: owed button OAK, the rest stone, like Same Color's hints ({@code GoldorTerminalGui.drawSameColor}).
	 * Practised as a routine, not memorised. Paints all 16 every time so "exactly one oak" is the painter's property.
	 * Never re-snapshots (would photograph our own buttons); no physics.
	 */
	private void paintButtons() {
		if(deviceWorld == null || !buttonsUp) return;
		if(hintButtonData == null) hintButtonData = Bukkit.createBlockData("minecraft:oak_button[face=wall,facing=west]");
		int hint = hintCell();
		for(int i = 0; i < CELL_COUNT; i++) {
			int[] b = BUTTONS[i];
			deviceWorld.getBlockAt(b[0], b[1], b[2]).setBlockData(i == hint ? hintButtonData : buttonData, false);
		}
	}

	/** -1 when not waiting; then no button is oak. */
	private int hintCell() {
		if(state != State.AWAITING || inputIdx < 0 || inputIdx >= sequence.size()) return -1;
		return sequence.get(inputIdx);
	}

	/**
	 * Synchronous, idempotent. {@code update(true, false)}: force (a button is there, not what the state describes),
	 * no physics. A state knows its world, so this works after {@link #deviceWorld} is gone.
	 */
	private void removeButtons() {
		if(!buttonsUp && buttonSnapshot.isEmpty()) return;
		for(BlockState st : buttonSnapshot.values()) st.update(true, false);
		buttonSnapshot.clear();
		buttonsUp = false;
	}

	/** Synchronous, idempotent. */
	private void restoreLitCells() {
		if(litCells.isEmpty()) return;
		if(deviceWorld != null) {
			for(Map.Entry<Integer, BlockData> e : litCells.entrySet()) {
				int[] c = cell(e.getKey());
				deviceWorld.getBlockAt(c[0], c[1], c[2]).setBlockData(e.getValue(), false);
			}
		}
		litCells.clear();
	}

	// ---------- Teardown ----------

	/**
	 * The one force-restore and public teardown: lanterns back, buttons down, run dropped. Called from all three
	 * teardown paths ({@code Practice.endPractice}, {@code Server.serverSetup} incl. via {@code GoldorListener.resetSimon},
	 * {@code M7.onDisable}); the playback timer can't cover them since {@code cancelAllScheduled} kills it.
	 */
	public void cleanup() {
		resetRuntime();
	}

	private void resetRuntime() {
		runToken++;
		restoreLitCells();
		removeButtons();
		state = State.IDLE;
		sequence.clear();
		inputIdx = 0;
		spamClicks.clear();
		windowStartTick = -1;
		starterId = null;
	}
}
