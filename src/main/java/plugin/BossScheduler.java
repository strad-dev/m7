package plugin;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Boss-priority tick scheduler: why all boss actions run at the start of the tick (CLAUDE.md "Boss Tick Ordering").
 * <p>
 * One heartbeat started at enable ({@link M7#onEnable}), so its task id is lower than any per-run choreography,
 * and CraftBukkit runs same-tick tasks in task-id order. Every boss ticker therefore runs BEFORE player beam and
 * melee, so a mage beam reads this tick's stun/enrage/HP. That removed the old +1-tick beam offsets.
 * <ul>
 *   <li>{@link #addTicker}: per-tick step (movement, stun/enrage scans), in registration order, so "move, then scan"
 *       registers the mover first.
 *   <li>{@link #schedule}: one-shot at the START of a future tick, for timed state changes a hit must observe
 *       deterministically.
 * </ul>
 * {@link CopyOnWriteArrayList} lets a ticker (un)register itself or another from inside {@code run()}.
 */
public final class BossScheduler {
	private BossScheduler() {}

	private static final List<Runnable> tickers = new CopyOnWriteArrayList<>();
	// Movement lane: boss entity movement (aggro movers), run from M7.onEnable's per-tick task, not the heartbeat. So
	// start-of-tick scans read the PRE-move position: a deliberate one-tick lag matching vanilla entity ticking.
	private static final List<Runnable> movementTickers = new CopyOnWriteArrayList<>();
	private static BukkitTask heartbeat;
	// Advanced once per tick at the start of the heartbeat; schedule() targets it.
	private static long bossTick = 0;

	/** Idempotent. Call at enable, before any boss can spawn. */
	public static void start() {
		if(heartbeat != null && !heartbeat.isCancelled()) return;
		heartbeat = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), () -> {
			// Advance FIRST, so bossTick is the same all tick and schedule() delays match from either lane.
			bossTick++;
			// These used to be independent tasks; one throwing must not stop the rest.
			for(Runnable ticker : tickers) {
				try {
					ticker.run();
				} catch(Throwable t) {
					M7.getInstance().getLogger().warning("Boss ticker threw: " + t);
				}
			}
		}, 0L, 1L);
	}

	/** Stop the heartbeat and drop every ticker. Call at disable. */
	public static void stop() {
		if(heartbeat != null && !heartbeat.isCancelled()) heartbeat.cancel();
		heartbeat = null;
		clearAll();
	}

	/**
	 * Drop every ticker, keep the heartbeat: {@link #stop()} for run teardown ({@code Practice.endPractice}).
	 * {@link Utils#cancelAllScheduled()} can't reach this lane and some {@link #schedule} one-shots keep no handle
	 * (Maxor's crystal respawn, the Wither King's, the Watcher's), so they would fire into a torn-down session,
	 * respawning deleted entities and setting blocks in a reset world.
	 */
	public static void clearAll() {
		tickers.clear();
		movementTickers.clear();
	}

	/** Per-tick boss step, in registration order, before all player choreography. */
	public static void addTicker(Runnable ticker) {
		tickers.add(ticker);
	}

	/** Unregister an {@link #addTicker} or {@link #schedule} handle. Safe from inside the ticker. */
	public static void removeTicker(Runnable ticker) {
		if(ticker != null) tickers.remove(ticker);
	}

	/** Per-tick boss movement step (aggro mover), NOT at the start of the tick. */
	public static void addMovementTicker(Runnable ticker) {
		movementTickers.add(ticker);
	}

	/** Unregister an {@link #addMovementTicker} mover. Safe from inside the mover. */
	public static void removeMovementTicker(Runnable ticker) {
		if(ticker != null) movementTickers.remove(ticker);
	}

	/** Called once per tick from M7.onEnable's movement driver. */
	public static void runMovementTickers() {
		for(Runnable ticker : movementTickers) {
			try {
				ticker.run();
			} catch(Throwable t) {
				M7.getInstance().getLogger().warning("Boss movement ticker threw: " + t);
			}
		}
	}

	/**
	 * Boss-lane {@code runTaskLater}: called during tick T from either lane, runs {@code action} once at the START of
	 * tick {@code T + delayTicks}, before that tick's players.
	 * <p>
	 * Use this, NOT {@code runTaskLater} or {@link Utils#scheduleTask}, for timed boss state a hit must observe
	 * (stun to enrage, immunity/cooldown windows, interlude ends). Maxor enters the laser at tick 195, enrage delay
	 * 160 fires at the start of 355, so a beam on 355 sees him re-armored; runTaskLater would fire after the beam.
	 *
	 * @return handle for {@link #removeTicker}
	 */
	public static Runnable schedule(Runnable action, long delayTicks) {
		final long target = bossTick + Math.max(1, delayTicks);
		final Runnable[] handle = new Runnable[1];
		handle[0] = () -> {
			if(bossTick >= target) {
				removeTicker(handle[0]);
				action.run();
			}
		};
		addTicker(handle[0]);
		return handle[0];
	}
}
