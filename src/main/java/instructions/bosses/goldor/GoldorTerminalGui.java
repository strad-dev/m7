package instructions.bosses.goldor;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import plugin.M7;
import plugin.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Terminal puzzle for the live modes; classic keeps the one-click terminal ({@code GoldorListener.tryActivateTerminal}).
 * Realistic gets the generated puzzle, Perfect RNG the STAND-IN board (one obvious answer, ultra-realistic's old
 * terminal), chosen by {@code Difficulty.realPuzzles()} at every open. Activation still goes through
 * {@code Goldor.onActivation}, so nothing else in the phase changes.
 * <p>
 * Every click in the view is cancelled first, both inventories; a solving click is then handled.
 * <p>
 * One player at a time via the terminal's pending flag. Closing early throws the board away (a half-done Melody
 * starts over, as on Hypixel); only the TYPE is fixed per terminal.
 * <p>
 * No shared {@link ItemStack}s: four puzzles set per-slot amounts or glints, and "every write builds its own" is
 * easier to keep than a list of safe aliases.
 * <p>
 * {@link #onClosed()} must run for EVERY close: Melody owns a repeating task.
 */
public final class GoldorTerminalGui implements InventoryHolder {

	private static final Random RANDOM = new Random();

	/**
	 * Titles are NOT Hypixel's strings on purpose: Odin title-matches terminals and takes the clicks over (on
	 * 2026-09-12 it ate every click client-side and the GUI looked dead). So:
	 * <ul>
	 *   <li>every lowercase {@code o} is a Greek omicron (U+03BF);</li>
	 *   <li>with no {@code o}, one {@code e} is a Cyrillic {@code е} (U+0435), Select All's in "Select";</li>
	 *   <li>Starts With drops Hypixel's colon; it has neither letter, so it's the weakest.</li>
	 * </ul>
	 * Two titles are templates filled by {@link GoldorTerminalGui#title()}. Display-only. Don't "correct" them.
	 */
	public enum Type {
		/** "Correct all the panes!" - turn every red pane green.  Either button. */
		ON_OFF(45, "Cοrrect all the panes!"),
		/** "Change all to same color!" - cycle nine panes onto one colour.  Left steps forward, right steps back. */
		SAME_COLOR(45, "Change all tο same cοlοr!"),
		/** "Select all the RED items!" - the colour is rolled, so this one is a template. */
		SELECT_ALL(54, "Sеlect all the %s items!"),
		/** "What starts with 'D'?" - the letter is rolled, so this one is a template too.  No colon. */
		STARTS_WITH(45, "What starts with '%s'?"),
		/** "Click the button on time!" - three rows, each cleared by clicking its button on the beat. */
		MELODY(54, "Click the buttοn οn time!"),
		/** "Click in order!" - ten numbered panes, ascending. */
		CLICK_IN_ORDER(36, "Click in οrder!");

		public final int size;
		public final String title;

		Type(int size, String title) {
			this.size = size;
			this.title = title;
		}
	}

	/** Stand-in Melody pin, S2's fifth terminal, kept from ultra-realistic. */
	private static final int STANDIN_MELODY_X = 40, STANDIN_MELODY_Y = 124, STANDIN_MELODY_Z = 123;

	/** No Click In Order ({@link #assignTypes}). */
	private static final List<Type> STANDIN_POOL =
			List.of(Type.ON_OFF, Type.SAME_COLOR, Type.SELECT_ALL, Type.STARTS_WITH);

	/**
	 * From {@link GoldorSection}'s constructor. At most one of each type per section, dealt WITHOUT replacement from
	 * six types (S2 has five terminals, the rest four). The old Melody pin only existed because five terminals and
	 * five types forced it. The stand-in set deals differently ({@link #assignStandInTypes}): no board exists for
	 * Click In Order there. More terminals than types is a build mistake: logged, pool reused.
	 */
	static void assignTypes(List<GoldorTerminal> terminals) {
		if(!damage.Difficulty.realPuzzles()) {
			assignStandInTypes(terminals);
			return;
		}
		deal(terminals, new ArrayList<>(List.of(Type.values())));
	}

	/** Perfect RNG: Melody on the pin only, four others from {@link #STANDIN_POOL}; every section has four non-pin terminals. */
	private static void assignStandInTypes(List<GoldorTerminal> terminals) {
		List<GoldorTerminal> rest = new ArrayList<>(terminals.size());
		for(GoldorTerminal t : terminals) {
			if(t.x == STANDIN_MELODY_X && t.y == STANDIN_MELODY_Y && t.z == STANDIN_MELODY_Z) t.setType(Type.MELODY);
			else rest.add(t);
		}
		deal(rest, new ArrayList<>(STANDIN_POOL));
	}

	/** Warns and reuses the pool if a section outruns it. */
	private static void deal(List<GoldorTerminal> terminals, List<Type> pool) {
		Collections.shuffle(pool, RANDOM);
		int dealt = 0;
		for(GoldorTerminal t : terminals) {
			if(dealt == pool.size()) {
				M7.getInstance().getLogger().warning("Goldor S" + (t.sectionIdx + 1) + " has more than "
						+ pool.size() + " terminals, so its puzzle types cannot all be distinct.");
				dealt = 0;
			}
			t.setType(pool.get(dealt++));
		}
	}

	// ==================== layout ====================

	private static int slot(int row, int col) {
		return row * 9 + col;
	}

	private static ItemStack item(Material m) {
		return item(m, 1);
	}

	private static ItemStack item(Material m, int amount) {
		return noTooltip(new ItemStack(m, amount));
	}

	/** Same Color keeps its tooltip: the pane name spells out the clicks. */
	private static ItemStack withTooltip(Material m, int amount) {
		return new ItemStack(m, amount);
	}

	private static ItemStack noTooltip(ItemStack stack) {
		stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true));
		return stack;
	}

	private static final Material FILLER = Material.BLACK_STAINED_GLASS_PANE;

	// --- On/Off: rows 1-3, cols 2-6, fifteen panes inside a two-column frame ---
	private static final int[] ON_OFF_SLOTS = {
			11, 12, 13, 14, 15,
			20, 21, 22, 23, 24,
			29, 30, 31, 32, 33};
	/** Most panes green at open; zero allowed. */
	private static final int ON_OFF_MAX_FREE = 7;

	// --- Same Color: the 3x3 block, row-major ---
	private static final int[] SAME_COLOR_SLOTS = {12, 13, 14, 21, 22, 23, 30, 31, 32};

	/** A finished pane's count (a full stack reads as done); one right click is 63, two is 62. */
	private static final int RUBIX_DONE_AMOUNT = 64;

	/** Click order: LEFT steps forward, RIGHT back, both wrap. This IS {@link RubixSolver}'s numbering. */
	private static final Material[] CYCLE = {
			Material.ORANGE_STAINED_GLASS_PANE,
			Material.YELLOW_STAINED_GLASS_PANE,
			Material.GREEN_STAINED_GLASS_PANE,
			Material.BLUE_STAINED_GLASS_PANE,
			Material.RED_STAINED_GLASS_PANE};

	// --- Melody: three rows of five cells, button to the right ---
	private static final int MELODY_FIRST_ROW = 1, MELODY_LAST_ROW = 3;
	private static final int MELODY_FIRST_COL = 1, MELODY_LAST_COL = 5;
	private static final int MELODY_BUTTON_COL = 7;
	private static final int MELODY_STEP_TICKS = 10;
	/** Freeze after an off-target click; a miss costs time, never the row. */
	private static final int MELODY_MISS_FREEZE_TICKS = 20;

	private static int melodyLastRow() {
		return MELODY_LAST_ROW;
	}

	/** Last is {@code 8 - first}: ten numbers. */
	private static int clickOrderFirstCol() {
		return 2;
	}

	/** Rows 1-2, cols 2-6. */
	private static int[] clickOrderSlots() {
		int first = clickOrderFirstCol();
		int last = 8 - first;
		int[] out = new int[2 * (last - first + 1)];
		int n = 0;
		for(int row = 1; row <= 2; row++) {
			for(int col = first; col <= last; col++) out[n++] = slot(row, col);
		}
		return out;
	}

	// ==================== item pools ====================

	/** Families in all sixteen dye colours; colour-prefixed, these are Materials. */
	private static final String[] DYED_SUFFIXES = {
			"WOOL", "CARPET", "TERRACOTTA", "GLAZED_TERRACOTTA", "CONCRETE", "CONCRETE_POWDER",
			"STAINED_GLASS", "STAINED_GLASS_PANE", "SHULKER_BOX", "BED", "CANDLE", "BANNER", "DYE"};

	private static List<Material> dyedPool;
	private static List<Material> itemPool;
	private static String startLetters;

	/** Per slot, so answer count varies per board but not per question ({@link #buildPick}). */
	private static final double SELECT_ALL_ANSWER_CHANCE = 0.20;
	private static final double STARTS_WITH_ANSWER_CHANCE = 0.25;

	/** Built once; a missing suffix is skipped. */
	private static List<Material> dyedPool() {
		if(dyedPool == null) {
			List<Material> out = new ArrayList<>();
			for(DyeColor colour : DyeColor.values()) {
				for(String suffix : DYED_SUFFIXES) {
					Material m = Material.getMaterial(colour.name() + "_" + suffix);
					if(m != null) out.add(m);
				}
			}
			dyedPool = List.copyOf(out);
		}
		return dyedPool;
	}

	/** Real items only, never a barrier: that marks "not an answer". */
	private static List<Material> itemPool() {
		if(itemPool == null) {
			List<Material> out = new ArrayList<>();
			for(Material m : Material.values()) {
				if(m.isLegacy() || !m.isItem() || m.isAir() || m == Material.BARRIER) continue;
				out.add(m);
			}
			itemPool = List.copyOf(out);
		}
		return itemPool;
	}

	/** Derived, not A-Z: nothing starts with X, and an unanswerable question would hang the terminal. */
	private static String startLetters() {
		if(startLetters == null) {
			StringBuilder sb = new StringBuilder();
			for(char c = 'A'; c <= 'Z'; c++) {
				for(Material m : itemPool()) {
					if(displayName(m).charAt(0) == c) {
						sb.append(c);
						break;
					}
				}
			}
			startLetters = sb.toString();
		}
		return startLetters;
	}

	/**
	 * Upper-cased Material name, not the translation (which only the client has). A few differ ({@code COOKED_BEEF}
	 * / "Steak"), but the puzzle stays self-consistent since the letter comes from these names.
	 */
	private static String displayName(Material m) {
		return m.name().replace('_', ' ').toUpperCase(Locale.ROOT);
	}

	// ==================== instance ====================

	private final Inventory inv;
	private final GoldorTerminal terminal;
	public final Type type;
	/** Perfect RNG's board. Read at build and click only; type, title and terminal are the same either way. */
	private final boolean standIn;
	/** Stand-in only; -1 if not a single-click type. */
	private int standInAnswer = -1;
	/** Latched so a same-tick second click can't activate twice. */
	private boolean solved;

	/** On/Off: panes still red. */
	private int onOffLeft;

	/** Same Color: each pane's {@link #CYCLE} index. */
	private int[] rubix;
	/** Picked ONCE from the opening board; re-picking would renumber every hint mid-solve. */
	private int rubixTarget;

	/** Answer slots not yet clicked; empty = solved. */
	private final Set<Integer> unpicked = new HashSet<>();
	private DyeColor pickColour;
	private char pickLetter;

	/** Click In Order: next number owed, and the last. */
	private int nextNumber = 1;
	private int lastNumber;
	/** Latched at build. */
	private int[] orderSlots = new int[0];

	/** Past {@link #melodyLastRow()} once solved. */
	private int melodyRow = MELODY_FIRST_ROW;
	private int[] melodyTarget;
	/** Bottom purple marker, right under the last playable row (4). Latched in {@link #buildMelody()}. */
	private int melodyBottomRow = MELODY_LAST_ROW + 1;
	// Mover position, direction and the two pacing counters.
	private int melodyPos = MELODY_FIRST_COL;
	private int melodyDir = 1;
	private int melodySince;
	private int melodyFreeze;
	private BukkitTask melodyTicker;

	private GoldorTerminalGui(GoldorTerminal terminal, Type type, boolean standIn) {
		this.terminal = terminal;
		this.type = type;
		this.standIn = standIn;
		// Template titles need their subject before the inventory exists. The stand-in rolls too; its answer uses it.
		rollTitleSubject();
		this.inv = Bukkit.createInventory(this, type.size, Utils.msg("<dark_gray>" + title()));
		build();
	}

	/** Caller owns the pending flag and every gate. */
	public static void open(Player p, GoldorTerminal terminal) {
		GoldorTerminalGui gui = new GoldorTerminalGui(terminal, terminal.type(), !damage.Difficulty.realPuzzles());
		p.openInventory(gui.inv);
		// Not in the constructor: the ticker cancels itself with no viewers, and there are none yet. The stand-in
		// has no mover; it's parked on target.
		if(gui.type == Type.MELODY && !gui.standIn) gui.startMelodyTicker();
		if(gui.type == Type.MELODY) melodyShout(p, 0);
	}

	/** Said as the player on open and after rows 1 and 2, real and stand-in. */
	private static void melodyShout(Player p, int rowsDone) {
		p.chat(p.getName().toUpperCase(java.util.Locale.ROOT) + " HAS NEVER SEEN SUCH BULLSHIT BEFORE " + rowsDone + "/" + (MELODY_LAST_ROW - MELODY_FIRST_ROW + 1));
	}

	@Override
	public @NonNull Inventory getInventory() {
		return inv;
	}

	public GoldorTerminal terminal() {
		return terminal;
	}

	/** So closing isn't treated as giving up. */
	public boolean isSolved() {
		return solved;
	}

	/**
	 * Every close, solved or not; idempotent. Only Melody has work: its repeating task would otherwise keep a dead
	 * chest alive for the rest of the run.
	 */
	public void onClosed() {
		stopMelodyTicker();
	}

	// ==================== titles ====================

	/** Select All's colour, Starts With's letter. */
	private void rollTitleSubject() {
		switch(type) {
			case SELECT_ALL -> pickColour = DyeColor.values()[RANDOM.nextInt(DyeColor.values().length)];
			case STARTS_WITH -> pickLetter = startLetters().charAt(RANDOM.nextInt(startLetters().length()));
			default -> { }
		}
	}

	private String title() {
		return switch(type) {
			case SELECT_ALL -> String.format(type.title, pickColour.name().replace('_', ' '));
			case STARTS_WITH -> String.format(type.title, pickLetter);
			default -> type.title;
		};
	}

	// ==================== rendering ====================

	private void build() {
		if(standIn) {
			buildStandIn();
			return;
		}
		switch(type) {
			case ON_OFF -> buildOnOff();
			case SAME_COLOR -> buildSameColor();
			case SELECT_ALL -> buildPick(dyedPool(), this::matchesColour, SELECT_ALL_ANSWER_CHANCE);
			case STARTS_WITH -> buildPick(itemPool(), this::matchesLetter, STARTS_WITH_ANSWER_CHANCE);
			case MELODY -> buildMelody();
			case CLICK_IN_ORDER -> buildClickInOrder();
		}
	}

	private void fill(Material with) {
		for(int i = 0; i < inv.getSize(); i++) inv.setItem(i, item(with));
	}

	/** Black glass edge, {@code body} inside. */
	private void frameAndFill(Material body) {
		int rows = inv.getSize() / 9;
		for(int row = 0; row < rows; row++) {
			for(int col = 0; col < 9; col++) {
				boolean edge = row == 0 || row == rows - 1 || col == 0 || col == 8;
				inv.setItem(slot(row, col), item(edge ? FILLER : body));
			}
		}
	}

	/** 21 at size 45, 28 at 54. */
	private int[] innerSlots() {
		int rows = inv.getSize() / 9;
		int[] out = new int[(rows - 2) * 7];
		int n = 0;
		for(int row = 1; row <= rows - 2; row++) {
			for(int col = 1; col <= 7; col++) out[n++] = slot(row, col);
		}
		return out;
	}

	// --- On/Off ---

	/** Fifteen panes, 0-7 already green, in a two-column frame (five wide, not seven). */
	private void buildOnOff() {
		frameAndFill(Material.RED_STAINED_GLASS_PANE);
		for(int row = 1; row <= 3; row++) {
			inv.setItem(slot(row, 1), item(FILLER));
			inv.setItem(slot(row, 7), item(FILLER));
		}
		List<Integer> shuffled = new ArrayList<>();
		for(int s : ON_OFF_SLOTS) shuffled.add(s);
		Collections.shuffle(shuffled, RANDOM);
		int free = RANDOM.nextInt(ON_OFF_MAX_FREE + 1);
		for(int i = 0; i < free; i++) inv.setItem(shuffled.get(i), item(Material.LIME_STAINED_GLASS_PANE));
		onOffLeft = ON_OFF_SLOTS.length - free;
	}

	// --- Same Color ---

	/** Random colours; an all-one-colour roll is rerolled, it would open solved. */
	private void buildSameColor() {
		fill(FILLER);
		rubix = new int[SAME_COLOR_SLOTS.length];
		do {
			for(int i = 0; i < rubix.length; i++) rubix[i] = RANDOM.nextInt(CYCLE.length);
		} while(allSameColour());
		rubixTarget = RubixSolver.bestTarget(rubix);
		drawSameColor();
	}

	/**
	 * Stack size = clicks to the target AND which button, like Odin's overlay: 1/2 = left clicks, 63/62 = right
	 * clicks, 64 = correct. A pane is never more than two off, so the ranges can't meet. All nine repainted every
	 * click; cheaper than a rule about which to skip.
	 */
	private void drawSameColor() {
		for(int i = 0; i < SAME_COLOR_SLOTS.length; i++) {
			int signed = RubixSolver.signed(rubix[i], rubixTarget);
			ItemStack pane = withTooltip(CYCLE[rubix[i]], rubixAmount(signed));
			ItemMeta meta = pane.getItemMeta();
			if(meta != null) {
				meta.displayName(Utils.mm(rubixHint(signed)));
				// A finished pane GLINTS, unmistakable at a glance where an amount of 1 was invisible.
				meta.setEnchantmentGlintOverride(signed == 0);
				pane.setItemMeta(meta);
			}
			inv.setItem(SAME_COLOR_SLOTS[i], pane);
		}
	}

	/**
	 * Left: {@code signed}. Right: {@link #RUBIX_DONE_AMOUNT} minus the clicks. Done: 64.
	 * <p>
	 * Right counts down from a full stack because a negative count can't reach the 26.2 client: verified,
	 * {@code ItemStack$2.decode} returns EMPTY for {@code count <= 0} before reading the id (pre-1.20.5 it was a raw byte,
	 * hence negative stacks on old servers). A count of 1 draws no badge, so "one left click" is the one value not
	 * visible at a glance. Odin's "Left Clicks Only" (forward 0..4) would be one branch in {@code RubixSolver.signed}.
	 */
	private static int rubixAmount(int signed) {
		if(signed == 0) return RUBIX_DONE_AMOUNT;
		return signed > 0 ? signed : RUBIX_DONE_AMOUNT + signed;
	}

	/** The count spelled out as the pane's name, for a player meeting a 62 the first time. */
	private static String rubixHint(int signed) {
		if(signed == 0) return "<dark_gray>Correct";
		return signed > 0
				? "<green>" + signed + " left-click" + (signed == 1 ? "" : "s")
				: "<red>" + -signed + " right-click" + (signed == -1 ? "" : "s");
	}

	private boolean allSameColour() {
		for(int c : rubix) if(c != rubix[0]) return false;
		return true;
	}

	// --- Select All / Starts With ---

	/**
	 * Answers scattered in a wall of barriers (barriers painted first; non-answers are never seen).
	 * <p>
	 * Each slot rolls answer-or-not FIRST at {@code answerChance}, then draws from that side. Drawing from the
	 * whole pool made density a property of the QUESTION: a colour is 13 of 208 dyed items, a letter anything from a
	 * handful to hundreds. No answer at all (under 1 board in 400) forces one in.
	 * <p>
	 * Duplicates allowed, like Hypixel; de-duplicating would cap answers at the matching pool size (two, for some letters).
	 */
	private void buildPick(List<Material> pool, Predicate<Material> matches, double answerChance) {
		frameAndFill(Material.BARRIER);
		int[] slots = innerSlots();
		List<Material> answers = new ArrayList<>();
		List<Material> others = new ArrayList<>();
		for(Material m : pool) (matches.test(m) ? answers : others).add(m);
		if(answers.isEmpty()) return;
		Material[] drawn = new Material[slots.length];
		boolean any = false;
		for(int i = 0; i < slots.length; i++) {
			boolean answer = RANDOM.nextDouble() < answerChance;
			List<Material> from = answer ? answers : others;
			drawn[i] = from.get(RANDOM.nextInt(from.size()));
			any |= answer;
		}
		if(!any) drawn[RANDOM.nextInt(drawn.length)] = answers.get(RANDOM.nextInt(answers.size()));
		for(int i = 0; i < slots.length; i++) {
			if(!matches.test(drawn[i])) continue;
			inv.setItem(slots[i], item(drawn[i]));
			unpicked.add(slots[i]);
		}
	}

	/** Prefix match, so {@code LIGHT_BLUE_WOOL} is not {@code BLUE}. */
	private boolean matchesColour(Material m) {
		return m.name().startsWith(pickColour.name() + "_");
	}

	private boolean matchesLetter(Material m) {
		return displayName(m).charAt(0) == pickLetter;
	}

	/** Component glint, so no fake enchantment in the tooltip ({@code loadout/ItemRefresh} knows the override). */
	private static ItemStack glint(ItemStack stack) {
		ItemMeta meta = stack.getItemMeta();
		if(meta != null) {
			meta.setEnchantmentGlintOverride(true);
			stack.setItemMeta(meta);
		}
		return noTooltip(stack);
	}

	// --- Melody ---

	private void buildMelody() {
		fill(FILLER);
		melodyBottomRow = melodyLastRow() + 1;
		melodyTarget = new int[MELODY_LAST_ROW + 1];
		for(int row = MELODY_FIRST_ROW; row <= MELODY_LAST_ROW; row++) melodyTarget[row] = rollMelodyTarget(row);
		drawMelodyMarkers();
		for(int row = MELODY_FIRST_ROW; row <= melodyLastRow(); row++) drawMelodyRow(row);
	}

	/** Row 1 never gets the first cell: the mover starts there, a free click. */
	private static int rollMelodyTarget(int row) {
		int lo = row == MELODY_FIRST_ROW ? MELODY_FIRST_COL + 1 : MELODY_FIRST_COL;
		return lo + RANDOM.nextInt(MELODY_LAST_COL - lo + 1);
	}

	/** Purple markers top and bottom in the ACTIVE row's target column; the rest of those rows is filler. */
	private void drawMelodyMarkers() {
		if(melodyRow > melodyLastRow()) return;
		int bottom = melodyBottomRow;
		for(int col = MELODY_FIRST_COL; col <= MELODY_LAST_COL; col++) {
			Material m = col == melodyTarget[melodyRow] ? Material.PURPLE_STAINED_GLASS_PANE : FILLER;
			inv.setItem(slot(0, col), item(m));
			inv.setItem(slot(bottom, col), item(m));
		}
	}

	/** Active or inactive only; a cleared row renders inactive, no "done" look. */
	private void drawMelodyRow(int row) {
		boolean active = row == melodyRow;
		for(int col = MELODY_FIRST_COL; col <= MELODY_LAST_COL; col++) {
			Material m = active
					? (col == melodyPos ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE)
					: Material.WHITE_STAINED_GLASS_PANE;
			inv.setItem(slot(row, col), item(m));
		}
		inv.setItem(slot(row, MELODY_BUTTON_COL),
				item(active ? Material.GREEN_TERRACOTTA : Material.RED_TERRACOTTA));
	}

	/**
	 * Raw {@code runTaskTimer}, not {@code Utils.scheduleTask}: tracked tasks die to {@code cancelAllScheduled} and the
	 * mover would stop dead mid-puzzle (like the packet interceptor did). Owned here: {@link #onClosed()}, solving and
	 * a viewerless chest cancel it; teardown reaches it via {@code GoldorTerminal.cleanup} closing the view.
	 */
	private void startMelodyTicker() {
		melodyTicker = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), this::melodyTick, 1L, 1L);
	}

	/** Idempotent; null for every other type. */
	private void stopMelodyTicker() {
		if(melodyTicker != null) {
			melodyTicker.cancel();
			melodyTicker = null;
		}
	}

	/** A cell every {@link #MELODY_STEP_TICKS}, bouncing off the walls. A miss freezes the clock without resetting it. */
	private void melodyTick() {
		if(solved || inv.getViewers().isEmpty()) {
			stopMelodyTicker();
			return;
		}
		if(melodyFreeze > 0) {
			melodyFreeze--;
			return;
		}
		if(++melodySince < MELODY_STEP_TICKS) return;
		melodySince = 0;
		int next = melodyPos + melodyDir;
		if(next < MELODY_FIRST_COL || next > MELODY_LAST_COL) melodyDir = -melodyDir;
		melodyPos += melodyDir;
		drawMelodyRow(melodyRow);
	}

	// --- Click In Order ---

	/**
	 * 1..10, shuffled; the number is the stack size, the only copy of the permutation. The stand-in
	 * deals them in order, only reached if the mode changed under a built section ({@link #assignTypes}).
	 */
	private void buildClickInOrder() {
		fill(FILLER);
		orderSlots = clickOrderSlots();
		lastNumber = orderSlots.length;
		List<Integer> numbers = new ArrayList<>();
		for(int n = 1; n <= lastNumber; n++) numbers.add(n);
		if(!standIn) Collections.shuffle(numbers, RANDOM);
		for(int i = 0; i < orderSlots.length; i++) {
			inv.setItem(orderSlots[i], item(Material.RED_STAINED_GLASS_PANE, numbers.get(i)));
		}
		drawClickInOrder();
	}

	/** Colour by distance from the next number: solved green, next lime, then yellow, orange, the rest red. */
	private void drawClickInOrder() {
		for(int s : orderSlots) {
			ItemStack at = inv.getItem(s);
			if(at == null) continue;
			int n = at.getAmount();
			Material colour = switch(n - nextNumber) {
				case 0 -> Material.LIME_STAINED_GLASS_PANE;
				case 1 -> Material.YELLOW_STAINED_GLASS_PANE;
				case 2 -> Material.ORANGE_STAINED_GLASS_PANE;
				default -> n < nextNumber ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
			};
			// A solved pane drops its number (amount 1 draws none); still below nextNumber, so it stays green and dead.
			inv.setItem(s, item(colour, n < nextNumber ? 1 : n));
		}
	}

	// ==================== the Perfect RNG stand-in boards ====================

	/**
	 * One obvious answer, and clicking it is the terminal: ultra-realistic's originals. Melody reuses the real board
	 * with the mover parked on target. Click In Order is never dealt here; its branch only runs after a mode flip.
	 */
	private void buildStandIn() {
		standInAnswer = standInAnswerSlot();
		switch(type) {
			case ON_OFF -> {
				// Same two-column frame as the real board.
				frameAndFill(Material.LIME_STAINED_GLASS_PANE);
				for(int row = 1; row <= 3; row++) {
					inv.setItem(slot(row, 1), item(FILLER));
					inv.setItem(slot(row, 7), item(FILLER));
				}
				inv.setItem(standInAnswer, item(Material.RED_STAINED_GLASS_PANE));
			}
			case SAME_COLOR -> {
				fill(FILLER);
				for(int s : SAME_COLOR_SLOTS) inv.setItem(s, withTooltip(Material.BLUE_STAINED_GLASS_PANE, 1));
				inv.setItem(standInAnswer, withTooltip(Material.GREEN_STAINED_GLASS_PANE, 1));
			}
			case SELECT_ALL -> {
				frameAndFill(Material.BARRIER);
				inv.setItem(standInAnswer, item(Material.valueOf(pickColour.name() + "_CONCRETE")));
			}
			case STARTS_WITH -> {
				frameAndFill(Material.BARRIER);
				inv.setItem(standInAnswer, item(standInLetterItem()));
			}
			case MELODY -> buildStandInMelody();
			case CLICK_IN_ORDER -> buildClickInOrder();
		}
	}

	/** Middle of the second-to-last row: slot 31 or 40, as ultra-realistic had. */
	private int standInAnswerSlot() {
		return switch(type) {
			case MELODY, CLICK_IN_ORDER -> -1; // real solver
			default -> slot(inv.getSize() / 9 - 2, 4);
		};
	}

	/** Really starts with the title's letter. */
	private Material standInLetterItem() {
		List<Material> matches = new ArrayList<>();
		for(Material m : itemPool()) if(matchesLetter(m)) matches.add(m);
		return matches.isEmpty() ? Material.DIAMOND : matches.get(RANDOM.nextInt(matches.size()));
	}

	/** Every target on the first cell and no ticker, like ultra-realistic's; {@link #melodyClick} never misses. */
	private void buildStandInMelody() {
		fill(FILLER);
		melodyBottomRow = melodyLastRow() + 1;
		melodyTarget = new int[MELODY_LAST_ROW + 1];
		for(int row = 0; row < melodyTarget.length; row++) melodyTarget[row] = MELODY_FIRST_COL;
		drawMelodyMarkers();
		for(int row = MELODY_FIRST_ROW; row <= melodyLastRow(); row++) drawMelodyRow(row);
	}

	/** Same contract as {@link #onClick}. Same Color takes LEFT only, so the stand-in doesn't teach the wrong button. */
	private boolean standInClick(Player clicker, int slot, ClickType click) {
		if(type == Type.MELODY) return melodyClick(clicker, slot);
		if(type == Type.CLICK_IN_ORDER) return clickInOrderClick(clicker, slot);
		if(type == Type.SAME_COLOR && !click.isLeftClick()) return false;
		if(slot != standInAnswer) return false;
		solved = true;
		return true;
	}

	// ==================== solving ====================

	/**
	 * An already-cancelled click in the TOP inventory. True ONLY when solved; the caller activates and closes. No
	 * wrong click is punished (Melody's off-beat only costs time). Progress clicks play {@link #cue}.
	 */
	public boolean onClick(Player clicker, int slot, ClickType click) {
		if(solved) return false;
		if(standIn) return standInClick(clicker, slot, click);
		return switch(type) {
			case ON_OFF -> onOffClick(clicker, slot);
			case SAME_COLOR -> sameColorClick(clicker, slot, click);
			case SELECT_ALL, STARTS_WITH -> pickClick(clicker, slot);
			case MELODY -> melodyClick(clicker, slot);
			case CLICK_IN_ORDER -> clickInOrderClick(clicker, slot);
		};
	}

	/** Red → green, either button. */
	private boolean onOffClick(Player clicker, int slot) {
		if(indexOf(ON_OFF_SLOTS, slot) < 0) return false;
		ItemStack at = inv.getItem(slot);
		if(at == null || at.getType() != Material.RED_STAINED_GLASS_PANE) return false;
		inv.setItem(slot, item(Material.LIME_STAINED_GLASS_PANE));
		if(--onOffLeft > 0) { cue(clicker); return false; }
		solved = true;
		return true;
	}

	private boolean sameColorClick(Player clicker, int slot, ClickType click) {
		int i = indexOf(SAME_COLOR_SLOTS, slot);
		if(i < 0) return false;
		// A pane on target is locked, so there's a visible "safe to stop touching" state.
		if(rubix[i] == rubixTarget) return false;
		int step = click.isRightClick() ? -1 : click.isLeftClick() ? 1 : 0;
		if(step == 0) return false;
		rubix[i] = Math.floorMod(rubix[i] + step, CYCLE.length);
		drawSameColor();
		// Written as the RULE ("all nine match"), not the consequence of locking ("all on target"); same state today.
		if(!allSameColour()) { cue(clicker); return false; }
		solved = true;
		return true;
	}

	/** An answer glints and stops counting; anything else does nothing. */
	private boolean pickClick(Player clicker, int slot) {
		if(!unpicked.remove(slot)) return false;
		ItemStack picked = inv.getItem(slot);
		if(picked != null) inv.setItem(slot, glint(picked.clone()));
		if(!unpicked.isEmpty()) { cue(clicker); return false; }
		solved = true;
		return true;
	}

	/** Off the beat the mover freezes but the row stands; Hypixel's Melody is timing, not memory. */
	private boolean melodyClick(Player clicker, int slot) {
		if(slot != slot(melodyRow, MELODY_BUTTON_COL)) return false;
		if(melodyPos != melodyTarget[melodyRow]) {
			melodyFreeze = MELODY_MISS_FREEZE_TICKS;
			miss(clicker);
			return false;
		}
		int cleared = melodyRow++;
		drawMelodyRow(cleared); // back to inactive
		if(melodyRow > melodyLastRow()) {
			solved = true;
			stopMelodyTicker();
			return true;
		}
		melodyShout(clicker, melodyRow - MELODY_FIRST_ROW);
		// The mover CARRIES OVER, same cell, direction and clock: one continuous walk, not a fresh reaction test
		// per row. No freeze can be live here; a freeze parks the mover off target.
		drawMelodyMarkers();
		drawMelodyRow(melodyRow);
		cue(clicker);
		return false;
	}

	/** Next number only; anything else is eaten. */
	private boolean clickInOrderClick(Player clicker, int slot) {
		if(indexOf(orderSlots, slot) < 0) return false;
		ItemStack at = inv.getItem(slot);
		if(at == null || at.getAmount() != nextNumber) return false;
		nextNumber++;
		drawClickInOrder();
		if(nextNumber <= lastNumber) { cue(clicker); return false; }
		solved = true;
		return true;
	}

	/**
	 * Pling to the clicker on every progress click, all six types, since a landed click and an eaten one looked the
	 * same. Not on the SOLVING click: {@code Goldor.onActivation} plays its own and two stack into one louder note.
	 * Wrong clicks get nothing, except {@link #miss}.
	 */
	private static void cue(Player clicker) {
		clicker.playSound(clicker, Sound.BLOCK_NOTE_BLOCK_PLING, 2.0F, 2.0F);
	}

	/** Melody's off-beat button only; the freeze is otherwise invisible. Clicker only, and reads as a fault, not progress. */
	private static void miss(Player clicker) {
		clicker.playSound(clicker, Sound.ENTITY_ENDERMAN_TELEPORT, 2.0F, 0.5F);
	}

	private static int indexOf(int[] slots, int slot) {
		for(int i = 0; i < slots.length; i++) if(slots[i] == slot) return i;
		return -1;
	}
}
