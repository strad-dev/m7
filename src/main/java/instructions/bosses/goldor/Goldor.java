package instructions.bosses.goldor;

import instructions.bosses.WitherLord;
import instructions.bosses.necron.Necron;
import net.kyori.adventure.title.Title;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.entity.CraftWither;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import plugin.BossScheduler;
import plugin.M7;
import plugin.Utils;

import java.time.Duration;
import java.util.*;

public final class Goldor extends WitherLord {
	public static final Goldor INSTANCE = new Goldor();

	// Patrol waypoints, block-center XZ. Y stays 118.
	private static final double WP_AX = 100.5, WP_AZ = 40.5;
	private static final double WP_BX = 100.5, WP_BZ = 132.5;
	private static final double WP_CX = 8.5,   WP_CZ = 132.5;
	private static final double WP_DX = 8.5,   WP_DZ = 40.5;
	private static final double PATROL_SPEED = 0.1;

	// Core approach. Y descends to 116 independently of horizontal motion.
	private static final double CORE_TARGET_X = 54.5, CORE_TARGET_Z = 40.5;
	private static final double CORE_FINAL_X  = 54.5, CORE_FINAL_Z  = 114.5;
	private static final double CORE_TARGET_Y = 116.0;
	private static final double CORE_APPROACH_SPEED = 0.8;
	private static final double Y_DESCENT_SPEED = 0.1;
	// Live modes: patrol, not the core approach, and no core damage until every live player is inside blocks 38..70,
	// 109..157, 54..119.
	private static final BoundingBox CORE_ROOM = new BoundingBox(38, 109, 54, 71, 158, 120);

	// S3 frame wall (-2,119,74 to -2,125,80), expanded 1 each way for the frame entity's offset from its block.
	private static final BoundingBox S3_FRAME_BOUNDS = new BoundingBox(-3, 118, 73, 0, 126, 81);

	// S1 Simon Says start button; keep in sync with GoldorListener.SIMON_B{X,Y,Z}.
	private static final int SIMON_BX = 110, SIMON_BY = 121, SIMON_BZ = 91;
	// Block behind it, also stonk-immune.
	private static final int SIMON_BEHIND_BX = 111, SIMON_BEHIND_BY = 121, SIMON_BEHIND_BZ = 91;
	// Whole Simon Says column, stonk/break immune (isProtected): start button, backing, the "i1" sign at
	// (110,121,93), whose text therefore survives across runs, and the 16 input buttons (x=110, y 120..123, z 92..95).
	private static final int SS_ZONE_X1 = 110, SS_ZONE_X2 = 111;
	private static final int SS_ZONE_Y1 = 119, SS_ZONE_Y2 = 124;
	private static final int SS_ZONE_Z1 = 91,  SS_ZONE_Z2 = 96;
	// S2 "Lights" lever mounts (levers at z=142, mounts at z=143).
	private static final int LIGHTS_MOUNT_Z = 143, LIGHTS_MOUNT_X1 = 58, LIGHTS_MOUNT_X2 = 62, LIGHTS_MOUNT_Y1 = 133, LIGHTS_MOUNT_Y2 = 136;
	// S4 Sharp Shooter start plate; keep in sync with GoldorListener.PLATE_{X,Y,Z}. Plate and support both immune.
	private static final int PLATE_BX = 63, PLATE_BY = 127, PLATE_BZ = 35;

	// [sectionIdx][leverIdx] → {x, y, z}, shared by buildS1..buildS4 and resetSectionLevers. Section levers only, NOT
	// the S2 "Lights" levers, which Server.serverSetup resets.
	private static final int[][][] SECTION_LEVER_COORDS = {
			{{106, 124, 113}, {94, 124, 113}},  // S1
			{{27, 124, 127},  {23, 132, 138}},  // S2
			{{2, 122, 55},    {14, 122, 55}},   // S3
			{{84, 121, 34},   {86, 128, 46}},   // S4
	};

	private final List<GoldorSection> sections = new ArrayList<>(4);
	private int currentSectionIdx = 0;
	/** Goldor-relative tick the current section began. */
	private int sectionStartTick = 0;
	/** Core opened (S4 complete); times the final kill. */
	private int coreOpenTick = 0;
	private boolean phaseActive = false;
	/** Before this he's on patrol and takes no health damage. */
	private boolean coreOpen = false;
	/** Core open AND everyone inside {@link #CORE_ROOM}; latched. Until then he patrols and core hits are patrol hits. */
	private boolean coreEngaged = false;
	/** Last patrol hit; halves patrol speed for 10 ticks. */
	private int lastDamagedTick = -1000;
	private BukkitTask patrolTask;
	private BukkitTask coreApproachTask;
	private final List<ItemFrame> protectedFrames = new ArrayList<>();

	/** Sanity figure only: the device is whatever {@link #arrowFrames} finds, but a mismatch means the map is wrong. */
	private static final int ARROW_FRAME_COUNT = 9;

	/** Solved = all nine on ordinal 1 of the EIGHT rotation states; {@code rotateClockwise()} steps one. */
	private static final Rotation ARROW_SOLVED_ROTATION = Rotation.CLOCKWISE_45;

	/** Ordinal 0, one click short: the stand-in's odd frame. */
	private static final Rotation ARROW_STANDIN_ROTATION = Rotation.NONE;

	/**
	 * Column of the stand-in's bottom-left frame ({@code -1.5 120 78.5}). Only z: {@link #standInArrowFrame} takes the
	 * lowest ROW first, since y=120 falls between two rows and made the old nearest-frame search tie.
	 */
	private static final double ARROW_STANDIN_Z = 78.5;

	/**
	 * One of the nine S3 Arrow Align frames: in the wall AND holding an arrow. Membership only; who may TURN one is
	 * {@link #isTurnableArrowFrame}. Static and off the frame itself, so it protects the wall in prep and between phases.
	 * <p>
	 * Never a nearest-frame search: frames sit at {@code y = row + 0.5}, so y=120 tied the 119.5 and 120.5 rows and
	 * the pick changed between runs. The arrow is the map's own marking, and survives the wall moving.
	 */
	public static boolean isArrowAlignFrame(ItemFrame frame) {
		if(frame == null) return false;
		if(!S3_FRAME_BOUNDS.contains(frame.getLocation().toVector())) return false;
		org.bukkit.inventory.ItemStack held = frame.getItem();
		return held != null && held.getType() == Material.ARROW;
	}

	/** Live scan, shared by the randomiser and the solve check so they never disagree. */
	private static List<ItemFrame> arrowFrames(World world) {
		List<ItemFrame> found = new ArrayList<>(ARROW_FRAME_COUNT);
		for(Entity e : world.getNearbyEntities(S3_FRAME_BOUNDS)) {
			if(e instanceof ItemFrame frame && isArrowAlignFrame(frame)) found.add(frame);
		}
		return found;
	}

	/**
	 * The stand-in's unfinished frame: lowest row, then nearest {@link #ARROW_STANDIN_Z}. Null only if the wall is
	 * missing. Fixed, not rolled: outside realistic it IS the device, and a device that moves can't be learned.
	 */
	private static ItemFrame standInArrowFrame(World world) {
		return standInArrowFrame(arrowFrames(world));
	}

	/** Off an existing scan, so the caller compares the SAME objects. */
	private static ItemFrame standInArrowFrame(List<ItemFrame> frames) {
		ItemFrame best = null;
		for(ItemFrame f : frames) {
			if(best == null) {
				best = f;
				continue;
			}
			double y = f.getLocation().getY(), bestY = best.getLocation().getY();
			if(y > bestY + 1e-6) continue;          // higher row
			if(y < bestY - 1e-6) {
				best = f;                           // lower row wins
				continue;
			}
			double dz = Math.abs(f.getLocation().getZ() - ARROW_STANDIN_Z);
			if(dz < Math.abs(best.getLocation().getZ() - ARROW_STANDIN_Z)) best = f;
		}
		return best;
	}

	/**
	 * Realistic: any frame not yet on {@link #ARROW_SOLVED_ROTATION}, so a solved frame locks like the stand-in's
	 * eight. Otherwise only {@link #standInArrowFrame}: the other eight already read the answer, and the stand-in
	 * activates on the first click, so one of them would finish the device off the wrong frame. Asked live; a cache
	 * would need invalidating on every teardown and mode change.
	 */
	public static boolean isTurnableArrowFrame(ItemFrame frame) {
		if(!isArrowAlignFrame(frame)) return false;
		if(damage.Difficulty.realPuzzles()) return frame.getRotation() != ARROW_SOLVED_ROTATION;
		ItemFrame standIn = standInArrowFrame(frame.getWorld());
		return standIn != null && standIn.getUniqueId().equals(frame.getUniqueId());
	}

	/**
	 * Realistic: random rotations, re-rolled while all nine are already solved (1 in 8^9). Classic and Perfect RNG:
	 * aligned bar {@link #standInArrowFrame} on {@link #ARROW_STANDIN_ROTATION}, one click short.
	 * <p>
	 * Only at device resets ({@link #resetS3Device}, {@link #protectAllItemFrames}), never mid-phase. All nine are
	 * written in every mode, or the stand-in would inherit the last realistic run's wall.
	 */
	public static void resetArrowFrames(World world) {
		List<ItemFrame> frames = arrowFrames(world);
		if(frames.isEmpty()) return;
		realisticArrowWall = damage.Difficulty.realPuzzles();
		if(frames.size() != ARROW_FRAME_COUNT) {
			Utils.debug(Utils.DebugType.ERROR, "Arrow Align: found " + frames.size()
					+ " arrow frames in the S3 wall, expected " + ARROW_FRAME_COUNT);
		}
		if(!damage.Difficulty.realPuzzles()) {
			ItemFrame odd = standInArrowFrame(frames);
			for(ItemFrame f : frames) f.setRotation(f == odd ? ARROW_STANDIN_ROTATION : ARROW_SOLVED_ROTATION);
			return;
		}
		java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
		Rotation[] all = Rotation.values(); // ordinal 0..7
		boolean alreadySolved;
		do {
			alreadySolved = true;
			for(ItemFrame f : frames) {
				Rotation r = all[rng.nextInt(all.length)];
				f.setRotation(r);
				if(r != ARROW_SOLVED_ROTATION) alreadySolved = false;
			}
		} while(alreadySolved);
	}

	/** Last {@link #resetArrowFrames} dealt the realistic wall, so the phase start may keep its pre-turns. */
	private static boolean realisticArrowWall = false;

	/**
	 * Realistic, before the phase: the wall turns but can't be finished, so the turn that would put the last frame
	 * on {@link #ARROW_SOLVED_ROTATION} is refused (false), leaving at least one unsolved.
	 */
	public static boolean preTurnArrowFrame(ItemFrame frame) {
		if(!damage.Difficulty.realPuzzles() || !isTurnableArrowFrame(frame)) return false;
		Rotation next = frame.getRotation().rotateClockwise();
		if(next == ARROW_SOLVED_ROTATION) {
			boolean othersSolved = true;
			for(ItemFrame f : arrowFrames(frame.getWorld())) {
				if(!f.getUniqueId().equals(frame.getUniqueId()) && f.getRotation() != ARROW_SOLVED_ROTATION) othersSolved = false;
			}
			if(othersSolved) return false;
		}
		frame.setRotation(next);
		return true;
	}

	/** S3 solve test, from {@code GoldorListener.processArrowFrame} after it turns the clicked frame. */
	public static boolean arrowFramesAligned(World world) {
		List<ItemFrame> frames = arrowFrames(world);
		if(frames.isEmpty()) return false;
		for(ItemFrame f : frames) if(f.getRotation() != ARROW_SOLVED_ROTATION) return false;
		return true;
	}
	private final Map<Location, BlockData> coreSnapshot = new HashMap<>();
	private boolean coreBarrierActive = false;

	private Goldor() {
		register(this);
	}

	public static void goldorInstructions(World world, boolean doContinue) {
		INSTANCE.start(world, doContinue);
	}

	@Override protected String name() { return "Goldor"; }
	@Override protected String displayName() { return "Goldor"; }
	@Override protected Location spawnLocation() { return new Location(world, 80.5, 118, 40.5, -90f, 0f); }
	@Override protected double maxHealth() { return damage.MobStats.GOLDOR.internalHealth(); }

	@Override
	protected void resetState() {
		phaseActive = false;
		coreOpen = false;
		coreEngaged = false;
		lastDamagedTick = -1000;
		currentSectionIdx = 0;
		bossSectionIdx = 0;
		sectionStartTick = 0;
		coreOpenTick = 0;
		for(GoldorSection s : sections) s.cleanup();
		sections.clear();
		if(patrolTask != null && !patrolTask.isCancelled()) patrolTask.cancel();
		patrolTask = null;
		if(coreApproachTask != null && !coreApproachTask.isCancelled()) coreApproachTask.cancel();
		coreApproachTask = null;
		cancelInvalidLocationTicker();
		if(coreBarrierActive) restoreCoreOriginalBlocks();
		coreSnapshot.clear();
		coreBarrierActive = false;
		for(ItemFrame f : protectedFrames) {
			if(f.isValid()) f.setInvulnerable(false);
		}
		protectedFrames.clear();
	}

	@Override
	protected void onStart() {
		// Storm's split ends as Goldor begins (practice scoreboard).
		instructions.bosses.WitherActions.recordSplit("Storm", plugin.Utils.runTick());
		startPhase();
		scheduleIntroDialogue();
	}

	@Override
	protected void chainNext(boolean doContinue) {
		if(doContinue) {
			Necron.necronInstructions(world, true);
			instructions.bosses.necron.NecronDrag.start(world);
		} else {
			instructions.bosses.WitherActions.signalRunComplete(); // last boss of this practice
		}
	}

	private void scheduleIntroDialogue() {
		sendChatMessage("Who dares trespass into my domain?");
		Utils.scheduleTask(() -> sendChatMessage("Little ants, plotting and scheming, thinking they are invincibile..."), 60);
		Utils.scheduleTask(() -> sendChatMessage("I won't let you break the factory core, I gave my life to my Master."), 120);
		Utils.scheduleTask(() -> sendChatMessage("No one matches me in close quarters."), 180);
	}

	// ---------- Phase setup ----------

	private void startPhase() {
		phaseActive = true;

		// Shield off on patrol so hits land (ding, hurt sound, patrol slow); clampDamage's !coreOpen branch keeps his health.
		setArmor(false);

		sections.add(buildS1());
		sections.add(buildS2());
		sections.add(buildS3());
		sections.add(buildS4());

		snapshotCoreOriginalBlocks();
		protectAllItemFrames();
		startPatrolTask();
		startInvalidLocationTicker();
		if(listeners.GoldorListener.INSTANCE != null) listeners.GoldorListener.INSTANCE.armSharpShooterIfOccupied(world);
	}

	private GoldorSection buildS1() {
		List<GoldorTerminal> terms = new ArrayList<>();
		terms.add(new GoldorTerminal(world, 0, 0, 110, 113, 73));
		terms.add(new GoldorTerminal(world, 0, 1, 110, 119, 79));
		terms.add(new GoldorTerminal(world, 0, 2, 90, 112, 92));
		terms.add(new GoldorTerminal(world, 0, 3, 90, 122, 101));
		GoldorDevice dev = new GoldorDevice(world, 0, 110, 121, 91, 1.0);
		List<GoldorLever> lev = buildLevers(0);
		GoldorGate gate = new GoldorGate(world, 0, makeBox(96, 121, 104, 124));
		return new GoldorSection(0, terms, dev, lev, gate);
	}

	private GoldorSection buildS2() {
		List<GoldorTerminal> terms = new ArrayList<>();
		terms.add(new GoldorTerminal(world, 1, 0, 68, 109, 122));
		terms.add(new GoldorTerminal(world, 1, 1, 59, 120, 123));
		terms.add(new GoldorTerminal(world, 1, 2, 47, 109, 122));
		terms.add(new GoldorTerminal(world, 1, 3, 39, 108, 142));
		terms.add(new GoldorTerminal(world, 1, 4, 40, 124, 123));
		GoldorDevice dev = new GoldorDevice(world, 1, 60, 131, 142);
		List<GoldorLever> lev = buildLevers(1);
		GoldorGate gate = new GoldorGate(world, 1, makeBox(16, 128, 19, 136));
		return new GoldorSection(1, terms, dev, lev, gate);
	}

	private GoldorSection buildS3() {
		List<GoldorTerminal> terms = new ArrayList<>();
		terms.add(new GoldorTerminal(world, 2, 0, -2, 109, 112));
		terms.add(new GoldorTerminal(world, 2, 1, -2, 119, 93));
		terms.add(new GoldorTerminal(world, 2, 2, 18, 123, 93));
		terms.add(new GoldorTerminal(world, 2, 3, -2, 109, 77));
		GoldorDevice dev = new GoldorDevice(world, 2, -2, 119, 74);
		List<GoldorLever> lev = buildLevers(2);
		GoldorGate gate = new GoldorGate(world, 2, makeBox(4, 48, 12, 51));
		return new GoldorSection(2, terms, dev, lev, gate);
	}

	private GoldorSection buildS4() {
		List<GoldorTerminal> terms = new ArrayList<>();
		terms.add(new GoldorTerminal(world, 3, 0, 41, 109, 30));
		terms.add(new GoldorTerminal(world, 3, 1, 44, 121, 30));
		terms.add(new GoldorTerminal(world, 3, 2, 67, 109, 30));
		terms.add(new GoldorTerminal(world, 3, 3, 72, 115, 47));
		GoldorDevice dev = new GoldorDevice(world, 3, 63, 126, 35);
		List<GoldorLever> lev = buildLevers(3);
		return new GoldorSection(3, terms, dev, lev, null);
	}

	private List<GoldorLever> buildLevers(int sectionIdx) {
		List<GoldorLever> lev = new ArrayList<>();
		int[][] coords = SECTION_LEVER_COORDS[sectionIdx];
		for(int i = 0; i < coords.length; i++) {
			int[] c = coords[i];
			lev.add(new GoldorLever(world, sectionIdx, i, c[0], c[1], c[2]));
		}
		return lev;
	}

	private static BoundingBox makeBox(int x1, int z1, int x2, int z2) {
		return new BoundingBox(
				Math.min(x1, x2), Math.min(115, 135), Math.min(z1, z2),
				Math.max(x1, x2) + 1, Math.max(115, 135) + 1, Math.max(z1, z2) + 1
		);
	}

	private void protectAllItemFrames() {
		// Per user: only S3 wall frames are immune.
		for(Entity e : world.getNearbyEntities(S3_FRAME_BOUNDS)) {
			if(e instanceof ItemFrame frame) {
				frame.setInvulnerable(true);
				protectedFrames.add(frame);
			}
		}
		// Fresh board, so a chained full run doesn't inherit the last one's rotations. Realistic keeps a realistic
		// wall: serverSetup already dealt it, and the pre-phase turns are the player's.
		if(!(damage.Difficulty.realPuzzles() && realisticArrowWall)) resetArrowFrames(world);
	}

	/** For /setup. Non-arrow frames are left alone; nothing may turn them anyway. */
	public static void resetS3Device(World world) {
		resetArrowFrames(world);
	}

	/** Section levers (not S2 "Lights") to unpowered, face and facing kept. From {@link instructions.Server#serverSetup}. */
	public static void resetSectionLevers(World world) {
		for(int[][] section : SECTION_LEVER_COORDS) {
			for(int[] c : section) {
				Block b = world.getBlockAt(c[0], c[1], c[2]);
				if(b.getBlockData() instanceof org.bukkit.block.data.Powerable pw && pw.isPowered()) {
					pw.setPowered(false);
					b.setBlockData(pw, false);
				}
			}
		}
	}

	/** Live coord check, not {@code protectedFrames}, so late-loaded frames match; phase-independent. */
	public boolean isInS3FrameRegion(ItemFrame frame) {
		return S3_FRAME_BOUNDS.contains(frame.getLocation().toVector());
	}

	// ---------- Patrol ----------

	private void startPatrolTask() {
		final int[] leg = {0};
		patrolTask = new BukkitRunnable() {
			@Override
			public void run() {
				if(boss == null || !boss.isValid() || dying) {
					cancel();
					return;
				}
				Location loc = boss.getLocation();
				double x = loc.getX(), z = loc.getZ();
				// Half speed within 10 ticks of a hit.
				double speed = (tick - lastDamagedTick < 10) ? PATROL_SPEED * 0.5 : PATROL_SPEED;
				double yaw;
				double dx = 0, dz = 0;
				switch(leg[0]) {
					case 0 -> {
						yaw = -90f;
						double rem = WP_AX - x;
						double step = Math.clamp(rem, 0, speed);
						dx = step;
						if(rem - step <= 1e-5) leg[0] = 1;
					}
					case 1 -> {
						yaw = 0f;
						double rem = WP_BZ - z;
						double step = Math.clamp(rem, 0, speed);
						dz = step;
						if(rem - step <= 1e-5) leg[0] = 2;
					}
					case 2 -> {
						yaw = 90f;
						double rem = x - WP_CX;
						double step = Math.clamp(rem, 0, speed);
						dx = -step;
						if(rem - step <= 1e-5) leg[0] = 3;
					}
					default -> {
						yaw = 180f;
						double rem = z - WP_DZ;
						double step = Math.clamp(rem, 0, speed);
						dz = -step;
						if(rem - step <= 1e-5) leg[0] = 0;
					}
				}
				moveBossTo(x + dx, loc.getY(), z + dz, (float) yaw);
			}
		}.runTaskTimer(M7.getInstance(), 1L, 1L);
	}

	private void moveBossTo(double x, double y, double z, float yaw) {
		net.minecraft.world.entity.LivingEntity nms = ((CraftWither) boss).getHandle();
		nms.absSnapTo(x, y, z, yaw, nms.getXRot());
		nms.setDeltaMovement(Vec3.ZERO);
		nms.hurtMarked = true;
		PositionMoveRotation pmr = new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, nms.getXRot());
		ClientboundTeleportEntityPacket pkt = ClientboundTeleportEntityPacket.teleport(nms.getId(), pmr, Collections.emptySet(), nms.onGround());
		Utils.broadcastPacket(pkt);
	}


	// ---------- Death ticks: the invalid-location sweep (both live modes) ----------

	/**
	 * Floor footprints {@code {xMin, xMax, zMin, zMax}}, inclusive, S1..S4, ringing the arena (S1 north, S2 west, S3
	 * south, S4 east back to S1). Unbounded in Y on purpose: nothing else is at a corridor's X/Z.
	 * <p>
	 * Measured in-game edges, all matching the table:
	 * <ul>
	 *   <li>S2 starts at Z 122 (Z 121 is not S2)</li>
	 *   <li>S2 ends at Z 145 (Z 146 is not S2)</li>
	 *   <li>S3 ends at X 17 (X 18 is not S3 - it is the S2 side of their shared gate)</li>
	 *   <li>S4 ends at Z 49 (Z 50 is not S4)</li>
	 * </ul>
	 * <ul>
	 *   <li>S4 and S1 share X 89; {@link #sectionAt} takes progression order, so it reads S1.</li>
	 *   <li>Z 50 is in no section: the doorway inside S3's gate box, never invalid.</li>
	 * </ul>
	 * Widths (23 / 24 / 21 / 21) and lengths (93 / 94 / 95 / 93) really are uneven.
	 */
	private static final int[][] SECTION_BOUNDS = {
			{89, 111, 29, 121},   // S1
			{18, 111, 122, 145},  // S2
			{-3, 17, 51, 145},    // S3
			{-3, 89, 29, 49},     // S4
	};

	/** Also how long a player has to get out. */
	private static final int INVALID_LOCATION_POLL_TICKS = 60;

	private Runnable invalidLocationTicker;

	/**
	 * Death ticks, every {@link #INVALID_LOCATION_POLL_TICKS} ({@code deathsEnabled}). Two independent rules:
	 * <ul>
	 *   <li><b>Ahead of the party</b>: a section whose gate hasn't opened ({@link #currentSectionIdx}). Stops gate skips.</li>
	 *   <li><b>Overtaken</b>: Goldor physically in a section past yours ({@link #bossSectionIdx}), regardless of
	 *       progress. Finish S2 while he walks S1 or S2 and you live; linger in S1 as he enters S2 and you die.</li>
	 * </ul>
	 * S4 is exempt from OVERTAKEN only, and only once open: before that it's fatal like any unopened section (it used
	 * to be exempt from both, making S4 free parking all phase). The arithmetic gives this for free, since
	 * {@code bossSectionIdx} never exceeds 3. Overtaken is for good: once he reaches S4, S1-S3 stay fatal however many
	 * laps he walks. Outside every corridor is never invalid.
	 * <p>
	 * 60t poll vs {@code CheatDeath}'s 60t shortest immunity: staying put is saved once, then dies. Intended.
	 */
	private void startInvalidLocationTicker() {
		invalidLocationTicker = () -> {
			if(!phaseActive || dying) return;
			// Every tick: the bar is a countdown and the tracker must not miss a crossing. Only the sweep is throttled.
			updateActionBar();
			trackBossSection();
			if(displayTick() % INVALID_LOCATION_POLL_TICKS != 0) return;
			pollInvalidLocations();
		};
		BossScheduler.addTicker(invalidLocationTicker);
	}

	private void cancelInvalidLocationTicker() {
		if(invalidLocationTicker != null) {
			BossScheduler.removeTicker(invalidLocationTicker);
			invalidLocationTicker = null;
			// Wipe instead of letting the last "Death Ticks 3t" fade out. A cheat-death cooldown still showing is intended.
			Utils.broadcastActionBar(net.kyori.adventure.text.Component.empty());
		}
	}

	/**
	 * Countdown to the next death-tick sweep (Hypixel's name for {@link #pollInvalidLocations}). Live modes only; a
	 * countdown to nothing is worse than none. On the sweep's own phase-tick grid, so it can't drift.
	 * {@code death/Deaths}' action-bar fallback defers to it, since {@code Utils.sendActionBar} stamps the tick.
	 */
	private void updateActionBar() {
		if(!damage.Difficulty.deathsEnabled()) return;
		int left = INVALID_LOCATION_POLL_TICKS - Math.floorMod(displayTick(), INVALID_LOCATION_POLL_TICKS);
		Utils.broadcastActionBar(Utils.msg("<gold>Death Ticks <white>" + left + "t"));
	}

	private void pollInvalidLocations() {
		if(!damage.Difficulty.deathsEnabled()) return;
		for(Player p : world.getPlayers()) {
			int section = sectionAt(p.getLocation());
			if(section < 0) continue; // not in a corridor
			if(!isInvalidSection(section)) continue;
			death.Deaths.kill(p, "Goldor");
		}
	}

	/**
	 * See {@link #startInvalidLocationTicker}. No S4 special case, deliberately: the arithmetic already exempts
	 * it, and an explicit one swallowed the first rule too.
	 */
	private boolean isInvalidSection(int idx) {
		if(idx > currentSectionIdx) return true;   // gate not opened yet
		return bossSectionIdx > idx;               // Goldor walked past this corridor
	}

	/**
	 * Goldor's corridor. TRACKED, not derived: he spawns at (80.5, 40.5), inside S4's box, which would kill a party in
	 * S1 on the first sweep, and the loop would un-overtake stragglers every lap. Starts at S1;
	 * {@link #trackBossSection} only steps to the NEXT corridor, up to S4.
	 */
	private int bossSectionIdx = 0;

	/**
	 * Single forward steps only, and S4 is the last: a lap back into S1 never makes S1-S3 safe again. Between
	 * corridors reads -1 and is ignored; at 0.1/tick he can't skip one.
	 */
	private void trackBossSection() {
		if(boss == null || !boss.isValid()) return;
		int geo = sectionAt(boss.getLocation());
		if(geo < 0) return;
		if(geo == bossSectionIdx + 1) bossSectionIdx = geo;
	}

	/** -1 for none; first match in progression order wins. */
	private static int sectionAt(Location loc) {
		int x = loc.getBlockX(), z = loc.getBlockZ();
		for(int i = 0; i < SECTION_BOUNDS.length; i++) {
			int[] b = SECTION_BOUNDS[i];
			if(x >= b[0] && x <= b[1] && z >= b[2] && z <= b[3]) return i;
		}
		return -1;
	}

	// ---------- Activation API ----------

	public GoldorSection getSection(int idx) {
		if(idx < 0 || idx >= sections.size()) return null;
		return sections.get(idx);
	}

	public GoldorSection getCurrentSection() {
		return getSection(currentSectionIdx);
	}

	public int getCurrentSectionIdx() {
		return currentSectionIdx;
	}

	public boolean isPhaseInactive() {
		return !phaseActive;
	}

	public void onActivation(Player p, GoldorSection ownSection, String thingLabel) {
		onActivation(p, ownSection, thingLabel, false);
	}

	/** {@code wasDeferred}: a device click held by GoldorListener's one-tick pre-phase grace, credited to {@code tick - 1}. */
	public void onActivation(Player p, GoldorSection ownSection, String thingLabel, boolean wasDeferred) {
		if(!phaseActive) return;
		int now = wasDeferred ? Math.max(0, displayTick() - 1) : displayTick();
		GoldorSection cur = getCurrentSection();
		if(cur == null) return;

		ownSection.completed++;

		int order, total;
		if(ownSection == cur) {
			order = cur.completed;
			total = cur.totalItems;
		} else {
			total = cur.totalItems;
			if(cur.completed == 0) {
				order = 1;
			} else {
				order = cur.completed;
			}
		}
		broadcastActivation(p, thingLabel, order, total);
		Utils.timer(verboseTimingLine(now));

		if(ownSection == cur && cur.completed >= cur.totalItems) {
			onAllItemsComplete(cur, now);
		}
	}

	/** Section and whole-Goldor elapsed ticks. {@code now} is the effective tick ({@code tick - 1} if grace-deferred). */
	public String verboseTimingLine(int now) {
		int secTicks = now - sectionStartTick;
		return "<green>" + String.format("S%d: %s ticks (%.2f seconds) | Terminals: %s ticks (%.2f seconds)",
				currentSectionIdx + 1, formatWithSpaces(secTicks), secTicks / 20.0, formatWithSpaces(now), now / 20.0);
	}

	/** Like an activation line; section time is from that gate's own section. */
	public String gateDestroyedLine(int gateSectionStartTick) {
		int secTicks = displayTick() - gateSectionStartTick;
		int termTicks = displayTick();
		return "<green>" + String.format("Gate destroyed in %s ticks (%.2f seconds) | Terminals: %s ticks (%.2f seconds)",
				formatWithSpaces(secTicks), secTicks / 20.0, formatWithSpaces(termTicks), termTicks / 20.0);
	}

	/** Not yet "complete": S1-S3 stay current until the gate goes ({@link #onGateDestroyed}). S4 has no gate. */
	private void onAllItemsComplete(GoldorSection s, int now) {
		if(s.idx < 3) {
			// Immediate if already blown, else the 100t auto-destruct.
			s.gate.onSectionComplete();
		} else {
			reportSectionFinished(s, now);
			onCoreOpen();
		}
	}

	/** The real "section complete" for S1-S3: report timing to now and advance. */
	public void onGateDestroyed(int sectionIdx) {
		GoldorSection s = getSection(sectionIdx);
		if(s == null || sectionIdx != currentSectionIdx) return;
		reportSectionFinished(s, displayTick());
		currentSectionIdx++;
		sectionStartTick = displayTick();
		GoldorSection next = getCurrentSection();
		if(next != null && next.gate != null) next.gate.setSectionStartTick(displayTick());
	}

	private void reportSectionFinished(GoldorSection s, int now) {
		int sectionTicks = now - sectionStartTick;
		Utils.timer("<green>" + String.format("S%d finished in %s ticks (%.2f seconds) | Terminals: ",
				s.idx + 1, formatWithSpaces(sectionTicks), sectionTicks / 20.0) + formatTick(now));
	}

	public static void broadcastActivation(Player p, String thing, int order, int total) {
		String msg = "<gold>" + p.getName() + " "
				+ "<green>activated a " + thing + " ("
				+ "<red>" + order + "<green>/" + total + ")";
		Bukkit.broadcast(Utils.msg(msg));
		for(Player pl : Bukkit.getOnlinePlayers()) {
			pl.showTitle(Title.title(Utils.msg(""), Utils.msg(msg),
					Title.Times.times(Duration.ofMillis(0L), Duration.ofMillis(40 * 50L), Duration.ofMillis(0L))));
		}
		playActivationSound();
	}

	/**
	 * Every terminal/device/lever activation. Melody's per-row cue is the same noise but a local playSound in
	 * {@link GoldorTerminalGui}, for the solver only; changing one doesn't change the other.
	 */
	public static void playActivationSound() {
		Utils.playGlobalSound(Sound.BLOCK_NOTE_BLOCK_PLING, 2.0F, 2.0F);
	}

	/**
	 * Immune to stonk/dungeonbreaker/any break in every phase: losing one soft-locks a section or knocks an
	 * interactable off its mount. Positional, so it holds before the phase spins up.
	 */
	public boolean isProtected(Block b) {
		int bx = b.getX(), by = b.getY(), bz = b.getZ();
		// S1 Simon Says column.
		if(bx >= SS_ZONE_X1 && bx <= SS_ZONE_X2 && by >= SS_ZONE_Y1 && by <= SS_ZONE_Y2 && bz >= SS_ZONE_Z1 && bz <= SS_ZONE_Z2) return true;
		// S2 "Lights" backing (z=143) and levers (z=142).
		if((bz == LIGHTS_MOUNT_Z || bz == LIGHTS_MOUNT_Z - 1) && bx >= LIGHTS_MOUNT_X1 && bx <= LIGHTS_MOUNT_X2 && by >= LIGHTS_MOUNT_Y1 && by <= LIGHTS_MOUNT_Y2) return true;
		// S4 plate AND support. Only the support used to be immune: stonking the plate soft-locked S4 for 200t, or
		// for good if the run ended first. Same as Maxor's crystal plates.
		if(bx == PLATE_BX && bz == PLATE_BZ && (by == PLATE_BY || by == PLATE_BY - 1)) return true;
		// Section levers and the block under each.
		for(int[][] section : SECTION_LEVER_COORDS) {
			for(int[] c : section) {
				if(bx == c[0] && bz == c[2] && (by == c[1] || by == c[1] - 1)) return true;
			}
		}
		return false;
	}

	/** From M7.onDisable(), so a mid-fight stop never leaves gates open. */
	public void shutdownRegenerateGates() {
		for(GoldorSection s : sections) {
			if(s.gate != null) s.gate.cleanup();
		}
		if(coreBarrierActive) {
			restoreCoreOriginalBlocks();
			coreBarrierActive = false;
		}
	}

	/** From Superboom TNT and other explosions. */
	public void notifyExplosionAt(Location loc) {
		if(!phaseActive) return;
		for(GoldorSection s : sections) {
			if(s.gate == null) continue;
			if(s.gate.getExpandedBounds().contains(loc.toVector())) {
				s.gate.onExplosion();
			}
		}
	}

	// ---------- Core open + approach + death ----------

	private void snapshotCoreOriginalBlocks() {
		coreSnapshot.clear();
		for(int x = 52; x <= 56; x++) {
			for(int y = 115; y <= 121; y++) {
				int z = 54;
				Block b = world.getBlockAt(x, y, z);
				if(b.getType() != Material.AIR) {
					coreSnapshot.put(b.getLocation(), b.getBlockData().clone());
				}
			}
		}
	}

	private void onCoreOpen() {
		coreOpen = true;
		coreOpenTick = displayTick();
		// Terminals split ends as the core opens.
		instructions.bosses.WitherActions.recordSplit("Terminals", plugin.Utils.runTick());
		// Live modes: he keeps patrolling until the party is in the core room (startCoreApproach stops it).
		if(!damage.Difficulty.liveInputs()) stopPatrol();

		sendChatMessage("You have done it, you destroyed the factory...");
		setArmor(false);

		coreGateBarrierTransition();
		boss.setInvulnerable(false);

		startCoreApproach();

		Utils.scheduleTask(() -> sendChatMessage("But you have nowhere to hide anymore!"), 60);
		Utils.scheduleTask(() -> sendChatMessage("YOU ARE FACE TO FACE WITH GOLDOR!"), 120);
	}

	private void coreGateBarrierTransition() {
		coreBarrierActive = true;
		for(Location loc : coreSnapshot.keySet()) {
			loc.getBlock().setType(Material.BARRIER, false);
		}
		String msg = "<green>The Core entrance is opening!";
		Bukkit.broadcast(Utils.msg(msg));
		for(Player pl : Bukkit.getOnlinePlayers()) {
			pl.showTitle(Title.title(Utils.msg(""), Utils.msg(msg),
					Title.Times.times(Duration.ofMillis(0L), Duration.ofMillis(40 * 50L), Duration.ofMillis(0L))));
		}
		Utils.playGlobalSound(Sound.BLOCK_NOTE_BLOCK_PLING, 2.0F, 2.0F);
		Utils.scheduleTask(() -> {
			for(Location loc : coreSnapshot.keySet()) {
				loc.getBlock().setType(Material.AIR, false);
			}
			coreBarrierActive = false;
		}, 20);
	}

	private void restoreCoreOriginalBlocks() {
		for(Map.Entry<Location, BlockData> entry : coreSnapshot.entrySet()) {
			entry.getKey().getBlock().setBlockData(entry.getValue(), false);
		}
	}

	private void startCoreApproach() {
		final int[] phase = {0};
		coreApproachTask = new BukkitRunnable() {
			@Override
			public void run() {
				if(boss == null || !boss.isValid() || dying) {
					cancel();
					return;
				}
				if(!coreEngaged) {
					if(!everyoneInCoreRoom()) return;
					coreEngaged = true;
					stopPatrol();
				}
				Location loc = boss.getLocation();
				double x = loc.getX(), y = loc.getY(), z = loc.getZ();

				// Y descends independently of horizontal motion.
				double ny = y;
				if(y > CORE_TARGET_Y) {
					ny = Math.max(CORE_TARGET_Y, y - Y_DESCENT_SPEED);
				}

				if(phase[0] == 0) {
					double dx = CORE_TARGET_X - x;
					double dz = CORE_TARGET_Z - z;
					double mag = Math.sqrt(dx * dx + dz * dz);
					if(mag <= CORE_APPROACH_SPEED) {
						float yaw = computeYaw(dx, dz);
						moveBossTo(CORE_TARGET_X, ny, CORE_TARGET_Z, yaw);
						phase[0] = 1;
					} else {
						double nx = x + dx / mag * CORE_APPROACH_SPEED;
						double nz = z + dz / mag * CORE_APPROACH_SPEED;
						float yaw = computeYaw(dx, dz);
						moveBossTo(nx, ny, nz, yaw);
					}
				} else {
					double rem = CORE_FINAL_Z - z;
					if(rem <= PATROL_SPEED) {
						moveBossTo(CORE_FINAL_X, ny, CORE_FINAL_Z, 0f);
						if(ny == CORE_TARGET_Y) cancel();
					} else {
						moveBossTo(CORE_FINAL_X, ny, z + PATROL_SPEED, 0f);
					}
				}
			}
		}.runTaskTimer(M7.getInstance(), 1L, 1L);
	}

	private void stopPatrol() {
		if(patrolTask != null && !patrolTask.isCancelled()) patrolTask.cancel();
	}

	private boolean everyoneInCoreRoom() {
		if(!damage.Difficulty.liveInputs()) return true;
		for(Player p : world.getPlayers()) {
			if(Utils.isSpectator(p) || p.isDead()) continue;
			if(!CORE_ROOM.contains(p.getLocation().toVector())) return false;
		}
		return true;
	}

	private static float computeYaw(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	// ---------- Damage / death ----------

	/** He dies silently; vanilla death is suppressed. */
	@Override
	public double clampDamage(double incoming) {
		if(boss == null) return incoming;
		if(dying) return 0;
		if(incoming <= 0) return 0;
		// Patrol, or core open with someone still outside: hits register (ding, 10-tick slow) but take no health. Blocking kills the hurt flash, so send it;
		// one packet flashes ~10 ticks, matching the slow window.
		if(!coreOpen || !coreEngaged) {
			lastDamagedTick = tick;
			Utils.broadcastPacket(new ClientboundHurtAnimationPacket(((CraftWither) boss).getHandle()));
			return 0;
		}
		if(boss.getHealth() - incoming <= 0) {
			// Deal all but DYING_SLIVER; returning 0 left the killing blow not moving the bar at all.
			double currentHp = boss.getHealth();
			enterDyingState();
			return Math.max(0, currentHp - DYING_SLIVER);
		}
		return incoming;
	}

	private void enterDyingState() {
		dying = true;
		boss.addScoreboardTag("TASDying");
		boss.setInvulnerable(true);
		if(coreApproachTask != null && !coreApproachTask.isCancelled()) coreApproachTask.cancel();
		Utils.scheduleTask(() -> {
			if(boss != null && boss.isValid()) {
				try { boss.setHealth(DYING_SLIVER); } catch (IllegalArgumentException ignored) {}
				Utils.changeName(boss);
			}
		}, 1);
		playDeathDialogue();
	}

	private void playDeathDialogue() {
		int handoffTick = 60;
		sendChatMessage("...");
		// Since core opened, then Terminals and Overall.
		int coreTicks = displayTick() - coreOpenTick;
		Utils.timer("<green>" + String.format("Goldor killed in %s ticks (%.2f seconds) | Terminals: ",
				formatWithSpaces(coreTicks), coreTicks / 20.0) + formatTick(displayTick()));
		Utils.scheduleTask(() -> sendChatMessage("Necron, forgive me."), 40);
		// Restored on the next /reset. On the handoff: no dialogue left to cover it.
		Utils.scheduleTask(instructions.bosses.BossTransition::openGoldorToNecron, 60);
		Utils.scheduleTask(() -> {
			Utils.timer("<green>Goldor finished in " + formatTick(displayTick()));
			// Leaderboard duration at the phase's real end, before chainNext re-anchors the clock. The WHOLE phase,
			// terminals and core, like /m7practice goldor; not the core-only column.
			instructions.bosses.WitherActions.recordPhaseDuration("Goldor", displayTick());
			if(tickerTask != null && !tickerTask.isCancelled()) tickerTask.cancel();
			chainNext(doContinue);
		}, handoffTick);
		Utils.scheduleTask(() -> {
			if(boss != null && boss.isValid()) boss.remove();
		}, 160);
	}
}
