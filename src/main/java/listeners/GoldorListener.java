package listeners;

import instructions.bosses.goldor.*;
import net.minecraft.server.MinecraftServer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import plugin.Utils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GoldorListener implements Listener {

	/** Single registered instance (M7.onEnable), so static reset paths reach instance state. */
	public static GoldorListener INSTANCE;

	public GoldorListener() {
		INSTANCE = this;
		// Java zero-fills sharpHitTick and 0 is a real tick, so set a defined start state instead of waiting for /setup.
		resetSharpHits();
	}

	// ------ Per-device runtime state (cleared on each phase via Goldor's resetState by reference) ------

	// Simon Says STAND-IN (classic): GLOBAL click count, not per-player; SIMON_STANDIN_CLICKS activates, no time limit.
	// Reset on completion and serverSetup (resetSimon).  Live modes use the real GoldorSimonSays behind the same entry point.
	private int simonClicks = 0;
	private static final int SIMON_STANDIN_CLICKS = 12;
	// Last tick each player registered a Simon Says click (start button, and the 16 grid buttons in the live modes).
	// One right-click can surface as TWO same-tick PlayerInteractEvents (off-hand while sneaking, and/or vanilla
	// re-firing after PlayerPacketInterceptor resets its dedupe), so same-tick repeats per player are dropped.
	// Real clicks land on different ticks, so nothing is rate-limited.  Stale entries self-expire via the comparison.
	// Load-bearing for the real device: a duplicate inside its 10-tick spam window would hand out an unearned skip,
	// worth a whole phase.  Also caps one player at 10 clicks (3 skips) per window, so the 15/21 thresholds need
	// a team - the intended "sum across players".
	private final Map<UUID, Integer> lastSimonClickTick = new HashMap<>();
	// Last toggle of each S2 Lights lever, keyed "x_y_z".  In ADVENTURE one click emits a right-click AND a phantom
	// left-click on the same lever a tick or two apart, and toggling on both undoes it.  Only that phantom is
	// swallowed (opposite-type click, same lever, within a couple ticks); same-button spam and other levers are never throttled.
	private record LeverToggle(int tick, boolean wasRight) {}
	private final Map<String, LeverToggle> lastS2LeverToggle = new HashMap<>();
	private static final int S2_PHANTOM_WINDOW_TICKS = 2;
	// Sharp Shooter: hit state on the 9 target blocks
	private final boolean[][] sharpHits = new boolean[3][3]; // [xIdx 0..2 → 68/66/64][yIdx 0..2 → 130/128/126]
	private int sharpHitCount = 0;
	/**
	 * LIVE MODES ONLY: index into {@link #sharpOrder}, 0..8; -1 before the plate starts the device, 9 past the end.
	 * <p>
	 * <b>An index into the permutation, not a target</b> ({@code seq = yIdx * 3 + xIdx}).  It used to be the target
	 * itself, which fixed the walk to -X-then-Y; never treat it as a wall position again, even for Perfect RNG.
	 * Each target is active at most once; it never loops.
	 */
	private int sharpCursor = -1;
	/**
	 * LIVE MODES ONLY: the emerald's walk as a permutation of the nine {@code seq} encodings, null while unarmed.
	 * Realistic rolls it fresh on every arming ({@link #onPlateStep}); Perfect RNG always gets
	 * {@link #SEQUENTIAL_SHARP_ORDER}, a known wall being what "rolls in your favour" means here.
	 * Dropped by {@link #resetSharpHits}.
	 */
	private int[] sharpOrder;
	/**
	 * LIVE MODES ONLY: tick an arrow last struck each target, {@code Integer.MIN_VALUE} for never.  Scratch for one
	 * tick, not a bank: a stamp only counts while its tick is the current one.  See {@link #registerSequentialHit}.
	 */
	private final int[][] sharpHitTick = new int[3][3];
	/** World the sequential device was armed in, so {@link #pollSharpPlate} can reset it. */
	private World sharpWorld;
	/** Tick the plate was FIRST seen empty while running, -1 while occupied: the one tick of grace that lets a solve beat a reset ({@link #pollSharpPlate}). */
	private int sharpPlateEmptySince = -1;

	// Simon Says START button.  The 16 grid buttons' coords come from GoldorSimonSays' grid table.
	private static final int SIMON_BX = 110, SIMON_BY = 121, SIMON_BZ = 91;
	// Sharp Shooter plate coord
	private static final int PLATE_X = 63, PLATE_Y = 127, PLATE_Z = 35;
	// Sharp Shooter target Z
	private static final int TARGET_Z = 50;
	// Sharp Shooter target X values
	private static final int[] TARGET_XS = {68, 66, 64};
	// Sharp Shooter target Y values
	private static final int[] TARGET_YS = {130, 128, 126};
	// Lights levers bounding box (S2 device)
	private static final int LIGHTS_X1 = 58, LIGHTS_X2 = 62;
	private static final int LIGHTS_Y1 = 133, LIGHTS_Y2 = 136;
	private static final int LIGHTS_Z = 142;
	// Redstone lamps are the levers' mount blocks, one behind.  Solved only when EVERY lamp is lit.
	private static final int LIGHTS_LAMP_Z = 143;

	/**
	 * True if {@code p} must not progress a Goldor device ({@link Utils#isSpectator}): a spectator on m7.  Not redundant with vanilla: CraftBukkit still fires interact events for spectators, and the
	 * raw {@code ServerboundUseItemOnPacket} path ({@code PlayerPacketInterceptor} → {@link #tryRegisterSimonClick})
	 * bypasses vanilla entirely.
	 * <p>
	 * Checked at every entry point AND in the {@code process*} solvers, which can run a tick later off
	 * {@link #runWhenPhaseActive}.  Public because {@link GoldorSimonSays} credits a short-circuited solve to a
	 * player it picks, who must pass the same gate.
	 */
	public static boolean cannotSolve(Player p) {
		return Utils.isSpectator(p);
	}

	// =================== Terminal click (right-click) ===================
	@EventHandler(priority = EventPriority.LOW)
	public void onInteractAt(PlayerInteractAtEntityEvent e) {
		if(cannotSolve(e.getPlayer())) return;
		// Cancelled so the held item's ability stands down (CustomItems.onPlayerInteractAtEntity).
		if(tryActivateTerminal(e.getRightClicked(), e.getPlayer())) e.setCancelled(true);
	}

	// =================== Terminal click (left-click) ===================
	@EventHandler(priority = EventPriority.LOW)
	public void onLeftClickTerminal(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
		if(!(e.getDamager() instanceof Player p)) return;
		if(cannotSolve(p)) return;
		if(tryActivateTerminal(e.getEntity(), p)) e.setCancelled(true);
	}

	/** True if the entity is a current-section terminal and the activation was accepted or already pending: the click is consumed. */
	private boolean tryActivateTerminal(Entity ent, Player p) {
		if(Goldor.INSTANCE.isPhaseInactive()) return false;
		String tagPrefix = GoldorTerminal.TAG_PREFIX;
		for(String tag : ent.getScoreboardTags()) {
			if(!tag.startsWith(tagPrefix)) continue;
			int[] idx = GoldorTerminal.parseTag(tag);
			if(idx == null) return false;
			GoldorSection sec = Goldor.INSTANCE.getSection(idx[0]);
			if(sec == null) return false;
			if(idx[0] != Goldor.INSTANCE.getCurrentSectionIdx()) return false; // section-gated
			if(idx[1] < 0 || idx[1] >= sec.terminals.size()) return false;
			GoldorTerminal term = sec.terminals.get(idx[1]);
			if(term.isActivated() || term.isPending()) return true;

			term.setPending();
			// Live modes: the click opens the puzzle and solving it activates.  Pending spans the whole time the GUI is
			// open, so a terminal is one player's at a time.  deathsEnabled(), not realPuzzles(): Perfect RNG opens the
			// stand-in board (GoldorTerminalGui picks); only classic keeps the one-click terminal.
			if(damage.Difficulty.deathsEnabled()) {
				GoldorTerminalGui.open(p, term);
				return true;
			}
			Utils.scheduleTask(() -> {
				if(term.isActivated()) return;
				term.markActivated();
				Goldor.INSTANCE.onActivation(p, sec, "terminal");
			}, 1L);
			return true;
		}
		return false;
	}

	// =================== Terminal puzzle GUI (live modes only) ===================

	/**
	 * Every click in a terminal puzzle, either inventory, is cancelled first, then read as a possible solve: puzzles
	 * are click targets, not inventories ({@link GoldorTerminalGui}).  No deferral needed, unlike the one-click
	 * path: the phase is active once a GUI is open, so the solve is credited to the click's own tick.
	 */
	@EventHandler(priority = EventPriority.LOW)
	public void onTerminalGuiClick(InventoryClickEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof GoldorTerminalGui gui)) return;
		if(plugin.Menus.ignoreDoubleClick(e)) return;
		e.setCancelled(true);
		if(!(e.getWhoClicked() instanceof Player p)) return;
		if(cannotSolve(p)) return;
		// Only clicks in the puzzle count; the player's own inventory is just cancelled.
		if(e.getClickedInventory() != e.getView().getTopInventory()) return;
		if(!gui.onClick(p, e.getSlot(), e.getClick())) return;

		GoldorTerminal term = gui.terminal();
		GoldorSection sec = Goldor.INSTANCE.getSection(term.sectionIdx);
		// Activate on the click's own tick so the time is what the player earned.  Close is deferred a tick since
		// Bukkit forbids closing a view inside its own click event; a click in between is harmless, the GUI latches "solved".
		if(sec != null && !term.isActivated()) {
			term.markActivated();
			Goldor.INSTANCE.onActivation(p, sec, "terminal");
		}
		Bukkit.getScheduler().runTask(plugin.M7.getInstance(), () -> p.closeInventory());
	}

	/** Dragging moves items too, so it is refused. */
	@EventHandler(priority = EventPriority.LOW)
	public void onTerminalGuiDrag(InventoryDragEvent e) {
		if(e.getView().getTopInventory().getHolder() instanceof GoldorTerminalGui) e.setCancelled(true);
	}

	/**
	 * Closing unsolved hands the terminal back: pending clears so anyone can reopen it, and progress is lost (a
	 * half-done Melody starts over).  A solved puzzle must NOT clear the flag, or pending outlives its activation.
	 * <p>
	 * <b>{@code onClosed()} runs BEFORE that early return</b>: it is the GUI's teardown, and Melody's mover is a
	 * repeating task that would otherwise paint panes into a closed inventory for the rest of the run.  Idempotent,
	 * so a solved Melody (mover already stopped) goes through it too.
	 */
	@EventHandler
	public void onTerminalGuiClose(InventoryCloseEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof GoldorTerminalGui gui)) return;
		gui.onClosed();
		if(gui.isSolved()) return;
		gui.terminal().clearPending();
	}

	// =================== Lever flip + Simon Says button + Lights levers ===================
	@EventHandler(priority = EventPriority.LOW)
	public void onInteract(PlayerInteractEvent e) {
		boolean rightClick = e.getAction() == Action.RIGHT_CLICK_BLOCK;
		boolean leftClick = e.getAction() == Action.LEFT_CLICK_BLOCK;
		if(!rightClick && !leftClick) return;
		// Main hand only: a sneaking right-click also fires an off-hand event, which would double-count Simon Says.
		if(e.getHand() != EquipmentSlot.HAND) return;
		Block b = e.getClickedBlock();
		if(b == null) return;
		int bx = b.getX(), by = b.getY(), bz = b.getZ();
		Player p = e.getPlayer();
		// Return BEFORE the Lights branch so a spectator's event isn't cancelled or toggled for them; vanilla ignores it.
		if(cannotSolve(p)) return;

		// Simon Says buttons (S1), right-click only: start button in every mode, plus the 16 grid buttons in
		// realistic.  Deferred if the phase isn't up yet so a chained full run's click counts: Goldor only goes
		// active when Storm dies.
		if(isSimonInput(p, bx, by, bz)) {
			// CANCEL FIRST.  MiscListener.onStoneButtonInArena also cancels arena buttons, but at NORMAL and by
			// re-reading the block type.  A phase-completing cell click makes GoldorSimonSays pull all 16 buttons in
			// this call, so that guard read AIR and let the event through, and vanilla wrote the pressed button
			// back into the cleared slot.  That is why the clicked button used to survive teardown.
			e.setCancelled(true);
			if(rightClick) tryRegisterSimonClick(p, bx, by, bz);
			return;
		}

		// Lights levers (S2): the puzzle reads the lever's toggled state.  I own the toggle for both clicks: cancel so
		// vanilla never flips it, then flip it myself.  Vanilla's toggle differed per gamemode and could double-toggle
		// from duplicate UseItemOn packets (flip → unflip); owning it is identical in every gamemode.
		if(b.getType() == Material.LEVER
				&& bx >= LIGHTS_X1 && bx <= LIGHTS_X2
				&& by >= LIGHTS_Y1 && by <= LIGHTS_Y2
				&& bz == LIGHTS_Z) {
			e.setCancelled(true); // don't let vanilla toggle or break the lever; I do it myself
			int now = MinecraftServer.currentTick;
			String leverKey = bx + "_" + by + "_" + bz;
			LeverToggle last = lastS2LeverToggle.get(leverKey);
			// Phantom = opposite-type click within a couple ticks (adventure right→left echo).  Real repeats are the same type or later.
			boolean phantom = last != null && last.wasRight() != rightClick && now - last.tick() <= S2_PHANTOM_WINDOW_TICKS;
			if(!phantom) {
				lastS2LeverToggle.put(leverKey, new LeverToggle(now, rightClick));
				toggleLever(b);
			}
			runWhenPhaseActive(deferred -> processLightsClick(p));
			return;
		}

		// Section levers only matter once the phase is active.
		if(Goldor.INSTANCE.isPhaseInactive()) return;
		if(b.getType() == Material.LEVER) {
			for(GoldorSection sec : new GoldorSection[]{
					Goldor.INSTANCE.getSection(0),
					Goldor.INSTANCE.getSection(1),
					Goldor.INSTANCE.getSection(2),
					Goldor.INSTANCE.getSection(3)}) {
				if(sec == null) continue;
				for(GoldorLever lev : sec.levers) {
					if(lev.isLeverBlock(bx, by, bz)) {
						if(sec.idx != Goldor.INSTANCE.getCurrentSectionIdx()) return; // section-gated
						if(lev.isActivated()) return;
						lev.markActivated();
						Goldor.INSTANCE.onActivation(p, sec, "lever");
						return;
					}
				}
			}
		}
	}

	/** True if (bx,by,bz) takes S1 clicks: start button always, grid buttons in the live modes.  Same lookup as {@link #tryRegisterSimonClick}. */
	private static boolean isSimonInput(Player p, int bx, int by, int bz) {
		if(bx == SIMON_BX && by == SIMON_BY && bz == SIMON_BZ) return true;
		return simonCellAt(p, bx, by, bz) >= 0;
	}

	/**
	 * Grid cell whose button is at (bx,by,bz), or -1.  The block must really BE a button: {@link GoldorSimonSays}
	 * only places them during an input window, so this ignores clicks outside one.  Coords alone would also match
	 * the "i1" sign at {@code 110 121 93}.
	 */
	private static int simonCellAt(Player p, int bx, int by, int bz) {
		if(!damage.Difficulty.realSimonSays()) return -1;
		int idx = GoldorSimonSays.cellAtButton(bx, by, bz);
		if(idx < 0) return -1;
		return Tag.BUTTONS.isTagged(p.getWorld().getBlockAt(bx, by, bz).getType()) ? idx : -1;
	}

	/**
	 * Register a Simon Says click if (bx,by,bz) is a device button, deduped to once per player per tick (see
	 * {@code lastSimonClickTick}: one click can surface twice on a tick, and a duplicate could hand out a skip).
	 * <p>
	 * Two main-thread callers: {@link #onInteract} (simulated packets) and the raw
	 * {@code ServerboundUseItemOnPacket} in {@code PlayerPacketInterceptor} for real players, which bypasses
	 * vanilla's interact suppression so rapid clicks aren't throttled to a few per second.
	 */
	public static void tryRegisterSimonClick(Player p, int bx, int by, int bz) {
		if(INSTANCE == null) return;
		if(cannotSolve(p)) return; // interceptor path skips vanilla's spectator gating
		boolean start = bx == SIMON_BX && by == SIMON_BY && bz == SIMON_BZ;
		final int cellIdx = start ? -1 : simonCellAt(p, bx, by, bz);
		if(!start && cellIdx < 0) return;
		int now = MinecraftServer.currentTick;
		if(INSTANCE.lastSimonClickTick.getOrDefault(p.getUniqueId(), -1) == now) return;
		INSTANCE.lastSimonClickTick.put(p.getUniqueId(), now);
		if(start) INSTANCE.runWhenPhaseActive(deferred -> INSTANCE.processSimonClick(p, deferred));
		else INSTANCE.runWhenPhaseActive(deferred -> INSTANCE.processSimonCell(p, cellIdx, deferred));
	}

	/**
	 * Start-button click once the phase is live; re-checks state, so safe deferred.  Two devices, one entry (like
	 * {@link #registerSharpHit}): the live modes go to {@link GoldorSimonSays}, classic to the 12-click stand-in.
	 */
	private void processSimonClick(Player p, boolean wasDeferred) {
		if(cannotSolve(p)) return;
		if(damage.Difficulty.realSimonSays()) {
			GoldorSimonSays.INSTANCE.onStartClick(p, wasDeferred);
			return;
		}
		GoldorSection s1 = Goldor.INSTANCE.getSection(0);
		if(s1 == null || s1.device.isActivated()) return;
		simonClicks++;
		Utils.debug(Utils.DebugType.BOSS, p, "Button clicked by " + p.getName() + " " + simonClicks + "/" + SIMON_STANDIN_CLICKS);
		if(simonClicks >= SIMON_STANDIN_CLICKS) {
			s1.device.markActivated();
			Goldor.INSTANCE.onActivation(p, s1, "device", wasDeferred);
			simonClicks = 0;
		}
	}

	/** Grid-button click once the phase is live, live modes only.  Re-checks mode and spectator gate since a deferred click can land after either changed. */
	private void processSimonCell(Player p, int cellIdx, boolean wasDeferred) {
		if(cannotSolve(p)) return;
		if(!damage.Difficulty.realSimonSays()) return;
		GoldorSimonSays.INSTANCE.onCellClick(p, cellIdx, wasDeferred);
	}

	/** Activate S2 Lights only once every lamp is lit.  Lamps settle after the block update, so the grid is read
	*  NEXT tick; activation is credited to the click's tick, hence wasDeferred=true. */
	private void processLightsClick(Player p) {
		if(cannotSolve(p)) return;
		GoldorSection s2 = Goldor.INSTANCE.getSection(1);
		if(s2 == null || s2.device.isActivated()) return;
		World w = p.getWorld();
		Utils.scheduleTask(() -> {
			if(s2.device.isActivated() || cannotSolve(p)) return;
			if(!allLightsLit(w)) return;
			s2.device.markActivated();
			Goldor.INSTANCE.onActivation(p, s2, "device", true);
		}, 1L);
	}

	/** Flip an S2 Lights lever's powered state, with physics so its lamp relights. */
	private static void toggleLever(Block b) {
		if(b.getBlockData() instanceof org.bukkit.block.data.Powerable pw) {
			pw.setPowered(!pw.isPowered());
			b.setBlockData(pw, true); // physics=true: updates the lever's neighbours → lights the lamp behind it
			// Vanilla LeverBlock.pull also updates the mount block, which lets the strongly-powered mount lamp light
			// its neighbours (the cross).  setBlockData skips that, so without this only the one lamp behind lights.
			net.minecraft.server.level.ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) b.getWorld()).getHandle();
			net.minecraft.core.BlockPos mountPos = new net.minecraft.core.BlockPos(b.getX(), b.getY(), b.getZ() + 1); // lamp is at LIGHTS_LAMP_Z = lever z + 1
			level.updateNeighborsAt(mountPos, net.minecraft.world.level.block.Blocks.LEVER, null);
		}
	}

	/** True only if every S2 lamp (lever mounts, z=143) is lit. */
	private static boolean allLightsLit(World w) {
		for(int x = LIGHTS_X1; x <= LIGHTS_X2; x++) {
			for(int y = LIGHTS_Y1; y <= LIGHTS_Y2; y++) {
				Block b = w.getBlockAt(x, y, LIGHTS_LAMP_Z);
				if(b.getType() != Material.REDSTONE_LAMP) return false;
				if(!(b.getBlockData() instanceof org.bukkit.block.data.Lightable lamp) || !lamp.isLit()) return false;
			}
		}
		return true;
	}

	/** Run now if the phase is active, else retry once next tick.  The only real race is an interaction landing the
	 *  tick the phase activates but before its task runs; a longer window would mask real mistimings. */
	private void runWhenPhaseActive(java.util.function.Consumer<Boolean> action) {
		if(!Goldor.INSTANCE.isPhaseInactive()) { action.accept(false); return; }
		// Told it was deferred, so it credits the click's true tick.
		Utils.scheduleTask(() -> { if(!Goldor.INSTANCE.isPhaseInactive()) action.accept(true); }, 1L);
	}

	// =================== Item frame rotation: ONLY the device's frames may be touched ===================
	// PHASE-INDEPENDENT, like the punch/break guards below.  It used to return when the phase was inactive, so in
	// prep and between phases any frame could be rotated, and nothing re-deals the grid mid-run.
	// Goldor.isTurnableArrowFrame is STATIC (frame position + item) so it answers before any scan.  MODE-DEPENDENT:
	// in realistic any frame not yet solved, else only the stand-in's one unfinished frame.  Every other frame is
	// always untouchable.
	@EventHandler(priority = EventPriority.LOWEST)
	public void onInteractEntity(PlayerInteractEntityEvent e) {
		if(!(e.getRightClicked() instanceof ItemFrame frame)) return;
		Player p = e.getPlayer();
		if(!Goldor.INSTANCE.isInS3FrameRegion(frame)) return; // frames outside S3 behave normally
		if(Goldor.isTurnableArrowFrame(frame)) {
			if(Goldor.INSTANCE.isPhaseInactive()) {
				e.setCancelled(true);
				// Realistic turns early, short of the solve.
				if(!cannotSolve(p) && Goldor.preTurnArrowFrame(frame)) return;
				// Defer an early solve (full-run chain timing) and cancel vanilla's turn now: processArrowFrame turns
				// it itself, so an early click was worth two steps.
				runWhenPhaseActive(deferred -> processArrowFrame(frame, p, deferred));
				return;
			}
			// processArrowFrame rotates it; cancel so vanilla doesn't double-turn when the held item is exempt from CustomItems' cancel.
			if(processArrowFrame(frame, p, false)) e.setCancelled(true);
			return;
		}
		if(p.getGameMode() == GameMode.CREATIVE) return; // creative bypass
		e.setCancelled(true);
	}

	/**
	 * Turn one Arrow Align frame and judge the device.  Returns true whenever the click was taken, not only on a
	 * solve: CustomItems' cancel skips vanilla's rotation, so the turn is done here, and the caller cancels so a
	 * click vanilla did reach isn't worth two steps.
	 * <p>
	 * Realistic needs all nine on ordinal 1, and a frame on it locks; classic + Perfect RNG only let the bottom-left frame through
	 * ({@code Goldor.isTurnableArrowFrame}) and turning it activates.  Re-checks all state, so safe deferred.
	 */
	private boolean processArrowFrame(ItemFrame frame, Player p, boolean wasDeferred) {
		if(cannotSolve(p)) return false; // here, not in onInteractEntity, so frame PROTECTION still applies to spectators
		if(Goldor.INSTANCE.isPhaseInactive()) return false;
		if(!Goldor.isTurnableArrowFrame(frame)) return false;
		GoldorSection s3 = Goldor.INSTANCE.getSection(2);
		if(s3 == null || s3.device.isActivated()) return false;
		frame.setRotation(frame.getRotation().rotateClockwise());
		// Judged after the turn, so this click's frame counts.
		if(damage.Difficulty.realPuzzles() && !Goldor.arrowFramesAligned(frame.getWorld())) return true;
		s3.device.markActivated();
		Goldor.INSTANCE.onActivation(p, s3, "device", wasDeferred);
		return true;
	}

	/** Same rule for {@code PlayerInteractAtEntityEvent}, which has its own handler list.  The solve rides the other event. */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onInteractAtFrame(PlayerInteractAtEntityEvent e) {
		if(!(e.getRightClicked() instanceof ItemFrame frame)) return;
		if(!Goldor.INSTANCE.isInS3FrameRegion(frame)) return;
		if(Goldor.isTurnableArrowFrame(frame)) return;
		if(e.getPlayer().getGameMode() == GameMode.CREATIVE) return; // creative bypass
		e.setCancelled(true);
	}

	// =================== Punching items out of S3 frames: cancelled (creative bypass) ===================
	// Phase-independent (isInS3FrameRegion, not isProtectedFrame): items can never be knocked out.
	@EventHandler(priority = EventPriority.LOWEST)
	public void onFramePunch(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
		if(e.getEntity() instanceof ItemFrame frame && Goldor.INSTANCE.isInS3FrameRegion(frame)) {
			if(e.getDamager() instanceof Player p && p.getGameMode() == GameMode.CREATIVE) return; // creative bypass
			e.setCancelled(true);
		}
	}

	// Broader EntityDamageEvent fallback (non-entity damage sources). No player → no creative bypass. Phase-independent.
	@EventHandler(priority = EventPriority.LOWEST)
	public void onFrameDamage(org.bukkit.event.entity.EntityDamageEvent e) {
		if(e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) return; // handled by onFramePunch
		if(e.getEntity() instanceof ItemFrame frame && Goldor.INSTANCE.isInS3FrameRegion(frame)) {
			e.setCancelled(true);
		}
	}

	// =================== Sharp Shooter: the plate starts it (live modes only) ===================

	/**
	 * Stepping on the gold plate BEGINS the S4 device (live modes): the emerald appears on the first target and the
	 * player shoots along the order.  Classic keeps the original: any order, plate held per hit.  Realistic rolls a
	 * fresh order per arming; Perfect RNG gets {@link #SEQUENTIAL_SHARP_ORDER} (ultra-realistic's old fixed walk),
	 * so the wall can be learned.
	 * <p>
	 * Own handler because {@link #onInteract} returns early for non-clicks and its main-hand guard doesn't fit a step.
	 * The plate must be HELD: stepping off resets ({@link #pollSharpPlate}), and arrows before the step do nothing,
	 * so the wall can't be pre-fired.
	 */
	@EventHandler(priority = EventPriority.LOW)
	public void onPlateStep(PlayerInteractEvent e) {
		if(e.getAction() != Action.PHYSICAL) return;
		Block b = e.getClickedBlock();
		if(b == null || b.getX() != PLATE_X || b.getY() != PLATE_Y || b.getZ() != PLATE_Z) return;
		if(cannotSolve(e.getPlayer())) return;
		if(!damage.Difficulty.deathsEnabled()) return;
		if(Goldor.INSTANCE.isPhaseInactive()) return;
		if(sharpCursor >= 0) return; // already running; a second step is not a restart

		GoldorSection s4 = Goldor.INSTANCE.getSection(3);
		if(s4 == null || s4.device.isActivated()) return;

		// Decided per arming, so stepping back on deals a new wall.  Perfect RNG's clone is defensive: nothing
		// mutates it today, but one shared array would be a trap.
		sharpOrder = damage.Difficulty.realPuzzles()
				? randomSharpOrder()
				: SEQUENTIAL_SHARP_ORDER.clone();
		sharpCursor = 0;
		sharpWorld = b.getWorld();
		renderSharpTargets(sharpWorld);
	}

	/**
	 * Perfect RNG's fixed walk: +X across a row (right to left from the plate, which faces +Z), then -Y, (64, 130) to
	 * (68, 126).  Written out because it is mode content, the wall a player learns.
	 */
	private static final int[] SEQUENTIAL_SHARP_ORDER = {2, 1, 0, 5, 4, 3, 8, 7, 6};

	/** Fresh permutation of the nine {@code seq} encodings (Fisher-Yates). */
	private static int[] randomSharpOrder() {
		int[] order = new int[9];
		for(int i = 0; i < order.length; i++) order[i] = i;
		java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
		for(int i = order.length - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int tmp = order[i];
			order[i] = order[j];
			order[j] = tmp;
		}
		return order;
	}

	/**
	 * Reset the whole sequential device once nobody is on the plate: progress is not banked, the task is "stay on
	 * the plate and clear all nine".
	 * <p>
	 * Polled per tick because {@code Action.PHYSICAL} only fires on the way ON.  Reuses {@link #isPlayerOnPlate}
	 * (classic's per-hit gate) so both devices agree on who is standing there, spectators excluded.
	 * <p>
	 * <b>A SOLVE BEATS A RESET on the same tick</b>, so the reset lands a tick after the plate is first seen empty.
	 * Resetting on sight could wipe state under a ninth arrow landing later that same tick, and
	 * {@link #registerSharpHit} would reject it.  A completing hit clears {@code sharpCursor}, which quiets this
	 * poll; a real step-off pays one invisible tick.
	 */
	private static void pollSharpPlate() {
		if(INSTANCE == null || INSTANCE.sharpCursor < 0) return; // not running
		if(Goldor.INSTANCE.isPhaseInactive()) return;            // the next /setup resets it
		if(INSTANCE.isPlayerOnPlate()) {
			INSTANCE.sharpPlateEmptySince = -1;
			return;
		}
		int now = plugin.Utils.serverTick();
		if(INSTANCE.sharpPlateEmptySince < 0) {
			INSTANCE.sharpPlateEmptySince = now; // first empty tick: this tick's arrows still get their chance
			return;
		}
		if(now == INSTANCE.sharpPlateEmptySince) return;
		World w = INSTANCE.sharpWorld;
		if(w != null) INSTANCE.resetSharpShooter(w);
		else INSTANCE.resetSharpHits();
	}

	/** Start the per-tick plate watch from {@code M7.onEnable}.  Raw and untracked like {@code OutOfBounds.start}, so a boss teardown flushing the scheduler can't kill it. */
	public static void startSharpPlatePoll() {
		if(sharpPlateTask != null) return;
		sharpPlateTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(
				plugin.M7.getInstance(), GoldorListener::pollSharpPlate, 1L, 1L);
	}

	public static void stopSharpPlatePoll() {
		if(sharpPlateTask != null) {
			sharpPlateTask.cancel();
			sharpPlateTask = null;
		}
	}

	private static org.bukkit.scheduler.BukkitTask sharpPlateTask;

	// =================== Sharp Shooter arrows ===================
	@EventHandler(priority = EventPriority.LOW)
	public void onProjectileHit(ProjectileHitEvent e) {
		Block hit = e.getHitBlock();
		if(hit == null) return;
		if(hit.getZ() != TARGET_Z) return;

		int xIdx = -1, yIdx = -1;
		for(int i = 0; i < TARGET_XS.length; i++) if(hit.getX() == TARGET_XS[i]) { xIdx = i; break; }
		for(int i = 0; i < TARGET_YS.length; i++) if(hit.getY() == TARGET_YS[i]) { yIdx = i; break; }
		Player shooter = (e.getEntity().getShooter() instanceof Player pl) ? pl : null;
		boolean nearWall = hit.getX() >= 63 && hit.getX() <= 69 && hit.getY() >= 125 && hit.getY() <= 131;
		if(Utils.isSuperVerbose() && nearWall) {
			org.bukkit.Location at = e.getEntity().getLocation();
			sharpLog(shooter, e.getEntity().getType().name().toLowerCase() + " (" + (shooter == null ? "?" : shooter.getName()) + ") hit "
					+ hit.getX() + " " + hit.getY() + " " + hit.getZ() + " " + e.getHitBlockFace() + " at " + Utils.round(at.getX(), 3)
					+ " " + Utils.round(at.getY(), 3) + " " + Utils.round(at.getZ(), 3) + (xIdx < 0 || yIdx < 0 ? ", not a target" : ""));
		}
		if(xIdx < 0 || yIdx < 0) return;

		World world = hit.getWorld();

		// A pre-fired arrow can land before the phase is up; the hit (and the arrow MiscListener removes) would be lost, so defer it.
		if(Goldor.INSTANCE.isPhaseInactive()) {
			sharpLog(shooter, "  phase not up, retrying next tick");
			deferSharpHit(world, xIdx, yIdx, shooter);
			return;
		}
		registerSharpHit(world, xIdx, yIdx, shooter, false);
	}

	/** Retry once next tick, same rule as {@link #runWhenPhaseActive}; still inactive means a real mistiming, dropped. */
	private void deferSharpHit(World world, int xIdx, int yIdx, Player shooter) {
		Utils.scheduleTask(() -> {
			if(!Goldor.INSTANCE.isPhaseInactive()) registerSharpHit(world, xIdx, yIdx, shooter, true);
			else sharpLog(shooter, "  deferred hit on " + TARGET_XS[xIdx] + " " + TARGET_YS[yIdx] + " dropped: phase still not up");
		}, 1L);
	}

	/** Sharp Shooter trace, {@code /verbose super} only. Filtered by shooter; one with none never matches a filter. */
	private static void sharpLog(Player shooter, String message) {
		if(!Utils.isSuperVerbose() || (shooter == null && Utils.getVerboseFilter() != null)) return;
		Utils.debug(Utils.DebugType.SERVER, shooter, "[i4] " + message);
	}

	/**
	 * Register one Sharp Shooter hit (idempotent per target); the ninth distinct hit completes S4.  Re-checks all
	 * gates, so safe deferred.
	 * <p>
	 * Two devices: classic is any order with the plate checked per hit; live modes are sequential
	 * ({@link #onPlateStep}), with the plate watched per tick.  Sequential counts only the ACTIVE target, except
	 * within the SAME TICK: a volley is several calls in one tick, and emerald + next target together should both
	 * complete whichever is processed first.  See {@link #registerSequentialHit}.
	 */
	private void registerSharpHit(World world, int xIdx, int yIdx, Player shooter, boolean wasDeferred) {
		if(Goldor.INSTANCE.isPhaseInactive()) return;
		GoldorSection s4 = Goldor.INSTANCE.getSection(3);
		String target = TARGET_XS[xIdx] + " " + TARGET_YS[yIdx];
		if(s4 == null || s4.device.isActivated()) { sharpLog(shooter, "  " + target + " ignored: device already done"); return; }
		boolean sequential = damage.Difficulty.deathsEnabled();
		// Plate: per-hit requirement in classic, one-off start in sequential.
		if(sequential ? sharpCursor < 0 : !isPlayerOnPlate()) {
			sharpLog(shooter, "  " + target + " ignored: " + (sequential ? "plate not stepped on yet" : "nobody on the plate"));
			return;
		}
		if(sharpHits[xIdx][yIdx]) { sharpLog(shooter, "  " + target + " ignored: already hit"); return; }

		if(sequential) {
			String active = activeSharpTarget();
			if(!registerSequentialHit(world, xIdx, yIdx)) { // not the active target: nothing completed
				sharpLog(shooter, "  " + target + " stamped, not counted: active target is " + active);
				return;
			}
		} else {
			sharpHits[xIdx][yIdx] = true;
			sharpHitCount++;
			setTargetBlock(world, xIdx, yIdx, TARGET_HIT);
		}
		sharpLog(shooter, "  " + target + " counted" + (wasDeferred ? " (deferred)" : "") + ", " + sharpHitCount + "/9");
		if(sharpHitCount >= 9) {
			if(shooter == null || cannotSolve(shooter)) {
				shooter = null;
				for(Player pl : Bukkit.getOnlinePlayers()) { if(!cannotSolve(pl)) { shooter = pl; break; } }
			}
			s4.device.markActivated();
			if(shooter != null) Goldor.INSTANCE.onActivation(shooter, s4, "device", wasDeferred);
			resetSharpShooter(world);
		}
	}

	/**
	 * One arrow in the sequential device.  Every hit stamps {@link #sharpHitTick}; only a hit on the ACTIVE target
	 * then advances the emerald, over every target stamped THIS tick.
	 * <p>
	 * So in-tick processing order can't matter: emerald first advances and stops, then the second arrow hits the new
	 * active target; next-target first just stamps, then the emerald's arrow advances over both.  <b>Don't make the
	 * stamp a boolean</b>: that let the wall be picked off out of order.  "Next" is next in {@link #sharpOrder};
	 * stamps stay on the wall's (x, y) grid, which is what the arrow knows.
	 *
	 * @return true if at least one target completed, i.e. caller should check for the ninth.
	 */
	private boolean registerSequentialHit(World world, int xIdx, int yIdx) {
		int now = Utils.serverTick();
		sharpHitTick[xIdx][yIdx] = now;
		if(sharpOrder == null || sharpCursor < 0 || sharpCursor >= sharpOrder.length) return false;
		if(sharpOrder[sharpCursor] != yIdx * 3 + xIdx) return false;
		while(sharpCursor < sharpOrder.length
				&& sharpHitTick[sharpOrder[sharpCursor] % 3][sharpOrder[sharpCursor] / 3] == now) {
			int seq = sharpOrder[sharpCursor];
			sharpHits[seq % 3][seq / 3] = true;
			sharpHitCount++;
			sharpCursor++;
		}
		renderSharpTargets(world);
		return true;
	}

	/** "x y" of the emerald's current target, for the trace. */
	private String activeSharpTarget() {
		if(sharpOrder == null || sharpCursor < 0 || sharpCursor >= sharpOrder.length) return "none";
		int seq = sharpOrder[sharpCursor];
		return TARGET_XS[seq % 3] + " " + TARGET_YS[seq / 3];
	}

	/** True if (xIdx, yIdx) is the emerald's current target.  False while unarmed, so the wall reads all blue. */
	private boolean isActiveSharpTarget(int xIdx, int yIdx) {
		if(sharpOrder == null || sharpCursor < 0 || sharpCursor >= sharpOrder.length) return false;
		return sharpOrder[sharpCursor] == yIdx * 3 + xIdx;
	}

	/** Redraw all nine from state (struck red, active emerald, rest blue), so a target the emerald jumped over can't keep the wrong colour. */
	private void renderSharpTargets(World world) {
		for(int xIdx = 0; xIdx < 3; xIdx++) {
			for(int yIdx = 0; yIdx < 3; yIdx++) {
				Material mat = sharpHits[xIdx][yIdx] ? TARGET_HIT
						: (isActiveSharpTarget(xIdx, yIdx) ? TARGET_ACTIVE : TARGET_RESTING);
				setTargetBlock(world, xIdx, yIdx, mat);
			}
		}
	}

	private boolean isPlayerOnPlate() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(cannotSolve(p)) continue; // a hovering spectator must not hold it down
			Location pl = p.getLocation();
			if(Math.abs(pl.getX() - (PLATE_X + 0.5)) <= 0.6
					&& Math.abs(pl.getZ() - (PLATE_Z + 0.5)) <= 0.6
					&& Math.abs(pl.getY() - PLATE_Y) <= 1.5) {
				return true;
			}
		}
		return false;
	}

	private void resetSharpHits() {
		for(int i = 0; i < 3; i++) for(int j = 0; j < 3; j++) {
			sharpHits[i][j] = false;
			sharpHitTick[i][j] = Integer.MIN_VALUE;
		}
		sharpHitCount = 0;
		sharpCursor = -1; // not started: the plate has to begin it again
		sharpOrder = null; // next arming rolls its own
		sharpWorld = null;
		sharpPlateEmptySince = -1;
	}

	/** Reset BOTH S1 devices (stand-in counter; real one's run, lit lanterns back to obsidian).  Called from
	 *  {@link instructions.Server#serverSetup} so a new run inherits no clicks, sequence or flash. */
	public void resetSimon() {
		simonClicks = 0;
		GoldorSimonSays.INSTANCE.cleanup();
	}

	// Sharp Shooter targets: blue = resting, red = hit, emerald = active target (live modes).
	private static final Material TARGET_RESTING = Material.BLUE_TERRACOTTA;
	private static final Material TARGET_HIT = Material.RED_TERRACOTTA;
	private static final Material TARGET_ACTIVE = Material.EMERALD_BLOCK;

	/** Physics suppressed. */
	private void setTargetBlock(World world, int xIdx, int yIdx, Material mat) {
		world.getBlockAt(TARGET_XS[xIdx], TARGET_YS[yIdx], TARGET_Z).setType(mat, false);
	}

	/** Revert all nine targets to blue and clear hit state.  On completion and {@link instructions.Server#serverSetup}. */
	public void resetSharpShooter(World world) {
		for(int i = 0; i < TARGET_XS.length; i++) {
			for(int j = 0; j < TARGET_YS.length; j++) {
				setTargetBlock(world, i, j, TARGET_RESTING);
			}
		}
		resetSharpHits();
		unpowerPlate(world);
	}

	/**
	 * Force the S4 plate at {@code 63 127 35} back to power 0.
	 * <p>
	 * <b>A powered plate silently bricks the device.</b>  {@code BasePressurePlateBlock.entityInside} only calls
	 * {@code checkPressed} when {@code getSignalForState(state) == 0}, so no {@code Action.PHYSICAL}, no
	 * {@link #onPlateStep}, and S4 can never start in either live mode.  No error, nothing in the log.
	 * <p>
	 * It sticks because the release is a SCHEDULED BLOCK TICK: any write without that tick pending (teardown
	 * mid-press, a {@code clone}/{@code fill} from a region captured while someone stood on it) leaves it powered forever.
	 * <p>
	 * Forced on both edges of a run: here (via {@code Server.serverSetup}) and {@code Practice.endPractice}.
	 * {@code applyPhysics = false}; nothing is wired to it.  Harmless with a player on it: the next
	 * {@code entityInside} presses it again.
	 */
	public static void unpowerPlate(World world) {
		if(world == null) return;
		Block b = world.getBlockAt(PLATE_X, PLATE_Y, PLATE_Z);
		org.bukkit.block.data.BlockData data = b.getBlockData();
		// Gold is a weighted plate: analogue power 0-15.  The Powerable branch keeps this fixed if the material changes.
		if(data instanceof org.bukkit.block.data.AnaloguePowerable ap) {
			if(ap.getPower() == 0) return;
			ap.setPower(0);
			b.setBlockData(ap, false);
		} else if(data instanceof org.bukkit.block.data.Powerable pw) {
			if(!pw.isPowered()) return;
			pw.setPowered(false);
			b.setBlockData(pw, false);
		}
	}

	// =================== Item-frame indestructibility (S3 only, creative bypass) ===================
	// Phase-independent (isInS3FrameRegion).  Also cancels the PHYSICS cause, so a frame survives its support block being broken.
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onHangingBreak(HangingBreakEvent e) {
		if(!(e.getEntity() instanceof ItemFrame frame) || !Goldor.INSTANCE.isInS3FrameRegion(frame)) return;
		if(e instanceof org.bukkit.event.hanging.HangingBreakByEntityEvent be
				&& be.getRemover() instanceof Player p
				&& p.getGameMode() == GameMode.CREATIVE) return;
		e.setCancelled(true);
	}

	// =================== Gate explosion (real EntityExplodeEvent fallback) ===================
	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent e) {
		if(Goldor.INSTANCE.isPhaseInactive()) return;
		Location loc = e.getEntity().getLocation();
		Goldor.INSTANCE.notifyExplosionAt(loc);
	}
}
