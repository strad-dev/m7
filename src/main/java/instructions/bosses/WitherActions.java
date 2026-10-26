package instructions.bosses;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftWither;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import plugin.Utils;
import plugin.BossScheduler;
import plugin.M7;

import java.util.*;

@SuppressWarnings("unused")
public class WitherActions {

	// Aggro movers run in BossScheduler's MOVEMENT lane, not at tick start. Value is the handle clearWitherAggro unregisters.
	private static final Map<UUID, Runnable> witherAggroTasks = new HashMap<>();
	private static BukkitTask armorTask = null;

	// Server tick each wither's armour last DROPPED, for wasMadeVulnerableThisTick.
	private static final Map<UUID, Integer> lastVulnerableTick = new HashMap<>();

	// --- Aggro target: last damager; same-tick ties go to the alphabetically-first name. ---
	private static volatile boolean practiceMode = false;
	private static volatile Player lastDamager = null;
	private static volatile int lastDamageTick = Integer.MIN_VALUE;

	/** Set by {@code Practice.runPractice}. Resets the aggro damager. */
	public static void setPracticeMode(boolean on) {
		practiceMode = on;
		lastDamager = null;
		lastDamageTick = Integer.MIN_VALUE;
	}

	public static boolean isPracticeMode() { return practiceMode; }

	/**
	 * A WON run, as a plain {@link plugin.RunCompleteEvent}; unlistened it fires into the void, so M7 stays standalone.
	 * Practice only. Fired as soon as the ending is DECIDED: {@code chainNext(false)}, clear completion, or the Wither
	 * King's scoreboard (not the end of its dialogue). It means the run is OVER; a listener that wants the ending on
	 * screen owns that delay, as the network plugin does.
	 */
	public static void signalRunComplete() {
		signalRunComplete(true);
	}

	/** {@code success} false for a failed run ({@code Storm.endFailedRun}): the network still frees its slot, no leaderboard. */
	public static void signalRunComplete(boolean success) {
		if (!practiceMode) return;
		// Prefer captureRunResult()'s earlier snapshot; see that method.
		plugin.RunResult result = (success && pendingResult != null)
				? pendingResult
				: plugin.RunResult.capture(runSection, success);
		pendingResult = null;
		signalled = true;
		Bukkit.getPluginManager().callEvent(new plugin.RunCompleteEvent(result));
	}

	/** THIS run has reported. Cleared by {@link #startRunTracking}. */
	private static volatile boolean signalled = false;

	/** This run has reported its ending: won, failed or abandoned. */
	public static boolean runReported() { return signalled; }

	/**
	 * A run torn down with no ending of its own ({@code /m7practice end}, network time-out/force-end, last player
	 * leaving). Same event, {@code success=false}, so the splits and milestones reached are still banked; the
	 * consumer decides what they count for (the network drops only the two whole-run boards). No-op once signalled.
	 * <br>
	 * Call it BEFORE any teardown: {@code ClearManager.stop} drops the score and the spectator flip empties the
	 * roster, so the top of {@code Practice.endPractice} is the last instant the result is readable.
	 */
	public static void signalRunAbandoned() {
		if (!practiceMode || signalled) return;
		signalRunComplete(false);
	}

	/** Snapshot taken ahead of the signal, or null. */
	private static volatile plugin.RunResult pendingResult = null;

	/**
	 * Snapshots the result NOW for a later {@link #signalRunComplete()}. A result records who's still in the run, so
	 * timing matters: the Wither King once signalled after its ~9s dialogue and a player walking out emptied the
	 * roster and dropped the run. The capture is pinned to when the numbers are PRINTED, wherever the signal moves.
	 */
	public static void captureRunResult() {
		if (!practiceMode) return;
		pendingResult = plugin.RunResult.capture(runSection, true);
	}

	// --- Identity of the current run (/m7practice <section>), for the run-result payload ---
	private static volatile String runSection = "all";
	private static volatile String runId = "";

	/**
	 * Records the section and mints a run id. Called once by {@code Practice.runPractice}. The id lets a consumer drop
	 * duplicates: the 300-score milestone is signalled when reached and the run-complete payload repeats it.
	 */
	public static void startRunTracking(String section) {
		runSection = section == null ? "all" : section;
		runId = java.util.UUID.randomUUID().toString();
		pendingResult = null;
		signalled = false;
		runRoster.clear();
	}

	public static String runSection() { return runSection; }

	/** Stable across every report this run makes. */
	public static String runId() { return runId; }

	// --- Run roster: everyone who took part, INCLUDING anyone who disconnected ---
	// By uuid, so a relog is one member. Group size comes from this, so never "who's online at the end": a duo whose
	// second player lagged out at 64s handed the survivor a 65s SOLO record. Fed by noteInRun (quit hook, every
	// RunResult.capture).
	private static final Map<UUID, RosterMember> runRoster = new LinkedHashMap<>();

	/** As last SEEN: a disconnected member keeps the state they left with. */
	public record RosterMember(UUID uuid, String name, boolean stayedAdventure) {}

	/** Skips spectators (the clear HUD's test). Safe from a quit handler: they're still in Adventure there. */
	public static void noteInRun(Player p) {
		if(!practiceMode || p == null) return;
		if(!instructions.clear.ClearManager.isRealPlayer(p)) return;
		runRoster.put(p.getUniqueId(), new RosterMember(p.getUniqueId(), p.getName(), stayedAdventure(p)));
	}

	/** In first-seen order. */
	public static List<RosterMember> runRoster() { return new ArrayList<>(runRoster.values()); }

	/** {@link plugin.ScoreMilestoneEvent}, fired when reached so the time stands even if the team resets. Same contract as {@link #signalRunComplete()}. */
	public static void signalScoreMilestone(int score) {
		if (!practiceMode) return;
		Bukkit.getPluginManager().callEvent(
				new plugin.ScoreMilestoneEvent(score, plugin.RunResult.capture(runSection, true)));
	}

	/** Despite the name, TRUE means {@code p} can NOT aggro: spectators, and everyone outside practice. */
	private static boolean isAggroEligible(Player p) {
		if(Utils.isSpectator(p)) return true; // spectators never aggro
		return !practiceMode;
	}

	// --- Section splits: overall tick (Utils.runTick()) at each section's finish, for the Wither-King practice scoreboard ---
	private static final Map<String, Integer> splitEnds = new java.util.LinkedHashMap<>();

	public static void recordSplit(String section, int overallTick) { splitEnds.put(section, overallTick); }

	/** Null if it wasn't run this session. */
	public static Integer getSplitEnd(String section) { return splitEnds.get(section); }

	/** In finish order. */
	public static Map<String, Integer> splitEnds() { return new java.util.LinkedHashMap<>(splitEnds); }

	// --- Per-phase durations (leaderboards) ---
	// PHASE-RELATIVE tick when the phase ENDED ("<Boss> finished in"), not the killing blow, which is 80-200t
	// earlier. Unlike splitEnds (stamped by the NEXT section, so a standalone "/m7practice maxor" gets none), each
	// boss stamps its own, so a single-phase practice is timed like one inside a full run.
	private static final Map<String, Integer> phaseDurations = new java.util.LinkedHashMap<>();

	public static void recordPhaseDuration(String phase, int phaseTicks) { phaseDurations.put(phase, phaseTicks); }

	/** Null if not completed this run. */
	public static Integer getPhaseDuration(String phase) { return phaseDurations.get(phase); }

	/** In completion order. */
	public static Map<String, Integer> phaseDurations() { return new java.util.LinkedHashMap<>(phaseDurations); }

	/** At the start of every /m7practice run. */
	public static void clearSplits() {
		splitEnds.clear();
		phaseDurations.clear();
	}

	// --- Golden-name anti-cheat: anyone who changed game mode this run shows white, not gold ---
	private static final Set<UUID> gameModeChanged = new HashSet<>();

	/** At the start of every /m7practice run. */
	public static void clearGameModeChanges() { gameModeChanged.clear(); }

	public static void noteGameModeChange(UUID id) { gameModeChanged.add(id); }

	/** Adventure the whole run. */
	public static boolean stayedAdventure(Player p) {
		return p.getGameMode() == GameMode.ADVENTURE && !gameModeChanged.contains(p.getUniqueId());
	}

	/** Makes {@code p} the aggro target. Same-tick ties go to the alphabetically-first name; a later tick always wins. */
	public static void noteDamager(Player p) {
		if(p == null) return;
		if(isAggroEligible(p)) return;
		int now = MinecraftServer.currentTick;
		if(now > lastDamageTick) {
			lastDamager = p;
			lastDamageTick = now;
		} else if(now == lastDamageTick && (lastDamager == null || p.getName().compareTo(lastDamager.getName()) < 0)) {
			lastDamager = p;
		}
	}

	/** Caps a tick's upward step; {@code box} is the hitbox after that tick's horizontal step. */
	@FunctionalInterface
	public interface RiseLimit {
		double clamp(org.bukkit.util.BoundingBox box, double vy);
	}

	/** Per-tick vertical cap so a big goalY-wy gap doesn't snap-teleport. */
	private static final double AGGRO_SPEED_VERTICAL_MAX = 0.2;

	/**
	 * Chases {@link #noteDamager}'s target (closest player until someone hits it) with a vanilla-shape PD controller:
	 * goal is the spot {@code stopDistance} from the target, {@code yOffset} up; {@code v += dir*A - v*0.6} then
	 * {@code *= 0.91}, like {@code Wither.aiStep}. Vanilla's A=0.3 gives ~0.4717 blocks/tick, so
	 * {@code A = maxSpeed * 0.636} makes steady state {@code maxSpeed}. Overshoot snaps to the goal and zeroes
	 * velocity. {@code noPhysics} on, so it phases through walls. Wither must have setAI(false). {@code riseLimit} may
	 * cut an upward step, never a horizontal one.
	 */
	public static void setWitherAggro(Wither wither, double stopDistance, double yOffset, double maxSpeed, RiseLimit riseLimit) {
		clearWitherAggro(wither);

		net.minecraft.world.entity.boss.wither.WitherBoss w = ((CraftWither) wither).getHandle();
		w.noPhysics = true;

		// move_steady = A * 1.5723 → A = maxSpeed / 1.5723.
		final double A = maxSpeed * 0.636;

		// So the ticker can unregister itself.
		final Runnable[] handle = new Runnable[1];
		Runnable task = new Runnable() {
			// The PD's "v"; zeroed on every snap.
			double vxState = 0, vzState = 0;
			// Goal Y, held while the target is mid-jump. trackedId re-seeds it on a target switch.
			int trackedId = -1;
			double trackedY = 0;

			@Override
			public void run() {
				// Last beam/melee damager (NOT arrows), else the closest player. A damager who left, died, changed
				// world or went spectator falls back to closest.
				Player damager = lastDamager;
				if(damager == null || !damager.isOnline() || damager.isDead() || damager.getWorld() != wither.getWorld()
						|| Utils.isSpectator(damager)) {
					damager = closestPlayer(wither);
				}
				net.minecraft.world.entity.LivingEntity active = damager == null ? null : ((CraftLivingEntity) damager).getHandle();

				if(w.isRemoved() || active == null || active.isRemoved() || !active.isAlive()) {
					// No target: hold. Only the boss disappearing tears the task down.
					if(!w.isRemoved()) return;
					witherAggroTasks.remove(wither.getUniqueId());
					w.noPhysics = false;
					BossScheduler.removeMovementTicker(handle[0]);
					return;
				}

				double wx = w.getX(), wy = w.getY(), wz = w.getZ();
				double tx = active.getX(), tz = active.getZ();

				// Closest point on the stopDistance ring; inside it, hold and never back away.
				double dx = wx - tx;
				double dz = wz - tz;
				double horiz = Math.sqrt(dx * dx + dz * dz);
				double goalX, goalZ;
				if(horiz <= stopDistance) {
					goalX = wx;
					goalZ = wz;
				} else {
					double scale = stopDistance / horiz;
					goalX = tx + dx * scale;
					goalZ = tz + dz * scale;
				}
				// A jump must not drag the wither up: while the target's hitbox top stays under the wither's, keep the
				// pre-jump Y. Falling and on-ground changes (stairs, landing higher) track at once.
				double targetY = active.getY();
				if(active.getId() != trackedId) {
					trackedId = active.getId();
				} else if(targetY > trackedY && !active.onGround()
						&& targetY + active.getBbHeight() <= wy + w.getBbHeight()) {
					targetY = trackedY;
				}
				trackedY = targetY;
				double goalY = targetY + yOffset;

				// PD step, snap on overshoot. State persists so it accelerates from rest like vanilla.
				double mx = goalX - wx;
				double mz = goalZ - wz;
				double horizMove = Math.sqrt(mx * mx + mz * mz);
				double vx, vz;
				if(horizMove < 1e-9) {
					vxState = 0;
					vzState = 0;
					vx = 0;
					vz = 0;
				} else {
					double dirx = mx / horizMove;
					double dirz = mz / horizMove;
					vxState += dirx * A - vxState * 0.6;
					vzState += dirz * A - vzState * 0.6;
					double stepLen = Math.sqrt(vxState * vxState + vzState * vzState);
					if(stepLen >= horizMove) {
						// Overshoot: snap, no ramp-down.
						vx = mx;
						vz = mz;
						vxState = 0;
						vzState = 0;
					} else {
						vx = vxState;
						vz = vzState;
					}
				}

				// Y arrives with XZ at maxSpeed, clamped to AGGRO_SPEED_VERTICAL_MAX.
				double my = goalY - wy;
				double vy;
				if(horizMove < 1e-9) {
					vy = my;
				} else {
					double ticksToArrive = Math.max(1.0, horizMove / maxSpeed);
					vy = my / ticksToArrive;
				}
				if(Math.abs(vy) > AGGRO_SPEED_VERTICAL_MAX) {
					vy = Math.signum(vy) * AGGRO_SPEED_VERTICAL_MAX;
				}
				if(vy > 0) {
					net.minecraft.world.phys.AABB bb = w.getBoundingBox().move(vx, 0, vz);
					vy = Math.max(0, Math.min(vy, riseLimit.clamp(new org.bukkit.util.BoundingBox(
							bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ), vy)));
				}

				Vec3 v = new Vec3(vx, vy, vz);
				w.setDeltaMovement(v);
				w.hurtMarked = true;

				// travel() no-ops under setAI(false), so move manually; noPhysics makes it a pure setPos.
				w.move(MoverType.SELF, v);

				// LookControl's own math, so it matches rendering; sets xRot, yRot, yHeadRot.
				w.lookAt(EntityAnchorArgument.Anchor.EYES, active.getEyePosition());

				// Wither.aiStep's `*= 0.91` air drag. With the 0.6 damping, same ramp as vanilla, independent of maxSpeed.
				vxState *= 0.91;
				vzState *= 0.91;
			}
		};

		handle[0] = task;
		BossScheduler.addMovementTicker(task);
		witherAggroTasks.put(wither.getUniqueId(), task);
	}

	/** Restores collision; leftover delta movement is left for vanilla aiStep to dampen. */
	public static void clearWitherAggro(Wither wither) {
		Runnable prior = witherAggroTasks.remove(wither.getUniqueId());
		if(prior != null) {
			BossScheduler.removeMovementTicker(prior);
		}
		net.minecraft.world.entity.boss.wither.WitherBoss w = ((CraftWither) wither).getHandle();
		w.noPhysics = false;
	}

	/** Auto-aggro fallback until someone beam/melee damages it. Eligibility per {@link #isAggroEligible}. */
	private static Player closestPlayer(Wither wither) {
		Player closest = null;
		double best = Double.MAX_VALUE;
		Location loc = wither.getLocation();
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(isAggroEligible(p)) continue;
			if(Utils.isSpectator(p)) continue;
			if(p.isDead() || p.getWorld() != wither.getWorld()) continue;
			double d = p.getLocation().distanceSquared(loc);
			if(d < best) { best = d; closest = p; }
		}
		return closest;
	}

	public static void setWitherArmor(Wither wither, boolean showArmor) {

		if(armorTask != null && !armorTask.isCancelled()) {
			armorTask.cancel();
			armorTask = null;
		}

		if(showArmor) {
			// Shield THIS tick: the task's first run is next tick, so a mid-tick enrage would let same-tick hits land (over-DPS).
			wither.setInvulnerableTicks(3);
			armorTask = new BukkitRunnable() {
				@Override
				public void run() {
					wither.setInvulnerableTicks(3);
				}
			}.runTaskTimer(M7.getInstance(), 0L, 1L); // every tick
		} else {
			wither.setInvulnerableTicks(0);
			// So a same-tick re-arm (cap-enrage in the damage handler) still lets a Terminator/Last Breath arrow through.
			lastVulnerableTick.put(wither.getUniqueId(), MinecraftServer.currentTick);
		}
	}

	/**
	 * Armour dropped THIS server tick, even if re-armed since. {@link listeners.WithersNotImmuneToArrows} reads the
	 * live counter, but arrow hits resolve in the entity-physics lane AFTER the start-of-tick boss scans, so a window
	 * that opens and closes in one tick was invisible to arrows while a same-tick beam connected.
	 */
	public static boolean wasMadeVulnerableThisTick(Wither wither) {
		return lastVulnerableTick.getOrDefault(wither.getUniqueId(), Integer.MIN_VALUE) == MinecraftServer.currentTick;
	}

}
