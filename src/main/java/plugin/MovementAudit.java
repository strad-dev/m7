package plugin;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SUPER-verbose dev tool: prints a player's trajectory per tick after a launch (spring boots, lava jump, bonzo /
 * jerry-chine knockback) for physics analysis. Inert otherwise. Own 1-tick task.
 */
public class MovementAudit {
	// Cap so a trace that never lands can't print forever.
	private static final int MAX_TRACE_TICKS = 200;

	private static final class State {
		final String source;
		int ticks;
		boolean leftGround;
		double lastX, lastY, lastZ;
		double peakY;
		State(String source, Location at) {
			this.source = source;
			this.lastX = at.getX();
			this.lastY = at.getY();
			this.lastZ = at.getZ();
			this.peakY = at.getY();
		}
	}

	private static final Map<UUID, State> audits = new ConcurrentHashMap<>();
	private static BukkitTask task;

	public static boolean hasAirborneAudit(UUID id) {
		return audits.containsKey(id);
	}

	/** Trace {@code p} until they land or it's cancelled. SUPER verbose only. */
	public static void startAirborneAudit(Player p, String source) {
		if(!Utils.isSuperVerbose()) return;
		if(isFlying(p)) return; // no trajectory to trace
		Location at = p.getLocation();
		audits.put(p.getUniqueId(), new State(source, at));
		Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " [" + source + "] airborne trace START at Y="
				+ Utils.round(at.getY(), 5) + " vel=" + fmtVec(p.getVelocity()));
		ensureTask();
	}

	public static void cancelAirborneAudit(UUID id) {
		audits.remove(id);
		if(audits.isEmpty()) stopTask();
	}

	public static void cancelAll() {
		audits.clear();
		stopTask();
	}

	private static void ensureTask() {
		if(task != null && !task.isCancelled()) return;
		task = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), MovementAudit::tick, 1L, 1L);
	}

	private static void stopTask() {
		if(task != null) {
			task.cancel();
			task = null;
		}
	}

	private static void tick() {
		if(audits.isEmpty()) { stopTask(); return; }
		// Operator left SUPER verbose mid-flight.
		if(!Utils.isSuperVerbose()) { audits.clear(); stopTask(); return; }
		for(Iterator<Map.Entry<UUID, State>> it = audits.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, State> entry = it.next();
			Player p = Bukkit.getPlayer(entry.getKey());
			if(p == null || !p.isOnline()) { it.remove(); continue; }
			State st = entry.getValue();
			// Flight kills the trajectory: it replaces gravity, and a forced spectator flip (run end, death) also
			// teleports, so the rest would be noise and "landed" might never come.
			if(isFlying(p)) {
				Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " [" + st.source + "] airborne trace CANCELLED after "
						+ st.ticks + "t (started flying), peak Y=" + Utils.round(st.peakY, 5));
				it.remove();
				continue;
			}
			st.ticks++;
			Location cur = p.getLocation();
			double dx = cur.getX() - st.lastX, dy = cur.getY() - st.lastY, dz = cur.getZ() - st.lastZ;
			st.lastX = cur.getX();
			st.lastY = cur.getY();
			st.lastZ = cur.getZ();
			if(cur.getY() > st.peakY) st.peakY = cur.getY();
			boolean onGround = ((org.bukkit.craftbukkit.entity.CraftPlayer) p).getHandle().onGround(); // server-side flag; Player#isOnGround() is deprecated
			if(!onGround) st.leftGround = true;
			Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " [" + st.source + "] t+" + st.ticks
					+ " Y=" + Utils.round(cur.getY(), 5)
					+ " dY=" + Utils.round(dy, 5)
					+ " dXZ=" + Utils.round(Math.hypot(dx, dz), 5)
					+ " vel=" + fmtVec(p.getVelocity())
					+ (onGround ? " [GROUND]" : ""));
			// End once they've left the ground and come back (the launch tick can still read grounded), or at the cap.
			if((onGround && st.leftGround) || st.ticks >= MAX_TRACE_TICKS) {
				Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " [" + st.source + "] airborne trace END after "
						+ st.ticks + "t, peak Y=" + Utils.round(st.peakY, 5));
				it.remove();
			}
		}
		if(audits.isEmpty()) stopTask();
	}

	/** Spectator checked too: a gamemode change and its flying flag may land on different ticks. */
	private static boolean isFlying(Player p) {
		return p.isFlying() || p.getGameMode() == GameMode.SPECTATOR;
	}

	private static String fmtVec(Vector v) {
		return Utils.round(v.getX(), 4) + "," + Utils.round(v.getY(), 4) + "," + Utils.round(v.getZ(), 4);
	}
}
