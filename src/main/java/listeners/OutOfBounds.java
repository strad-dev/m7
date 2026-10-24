package listeners;

import instructions.Server;
import instructions.bosses.Watcher;
import instructions.bosses.WitherActions;
import instructions.clear.Rooms;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import plugin.M7;
import plugin.Utils;

/**
 * Kills anyone who leaves the part of the dungeon the run is in. In bounds is exactly:
 * <ul>
 *   <li>while the clear is on: a clear room's volume, {@link Rooms#inRoomBounds} (its cells, floor..ceiling), or a
 *       door, {@link Rooms#inDoor}, frame included;</li>
 *   <li>once it's over (portal entered, or a boss-only section): the boss arena, {@link LavaJump#isInBossArena} (all
 *       five phases);</li>
 *   <li>both, for {@link #BOSS_GRACE_TICKS} after portal entry. A clear-only run keeps the clear's bounds.</li>
 * </ul>
 * Everything else is out, mainly the 1-block crevices between rooms: they run the length of the grid and let a
 * player walk past a closed door. A door is the only legal way through one.
 * <p>
 * Gated on {@code Deaths.inRun()}: {@link Server#isRunStarted()}, same as {@code CustomItems.onBlockBreak}'s
 * door/ceiling lock, so getting into position before the countdown ends is allowed, and off once the practice ends or a clear-only run enters the portal. Before that, live modes only send strays back to spawn every
 * 40 ticks ({@link #schedulePreRunReturns}).
 * <p>
 * Only SURVIVAL and ADVENTURE die. Creative is exempt (map editing, same bypass as {@code CustomItems.onBlockBreak};
 * the practice scoreboard flags a mid-run switch), and so are spectators.
 * <p>
 * A run death in every mode ({@code Deaths.forceKill}), with the ghost moved to {@link #respawnPoint} and revived
 * there, or it would revive out of bounds and die again.
 */
public final class OutOfBounds {
	private static BukkitTask poller;

	public static void start() {
		if(poller != null) return;
		poller = new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	public static void stop() {
		if(poller != null) {
			poller.cancel();
			poller = null;
		}
	}

	/** Ticks after portal entry (the "Clear" split) during which the clear stays in bounds too: 20 s. */
	private static final int BOSS_GRACE_TICKS = 400;

	/**
	 * In bounds for the phase the run is in: the clear (rooms and doors) while it's on, the boss arena once it's over.
	 * For {@link #BOSS_GRACE_TICKS} after portal entry both count, so nobody still in the dungeon dies the tick the party
	 * warps.
	 */
	public static boolean isInBounds(Location loc) {
		boolean arena = LavaJump.isInBossArena(loc);
		// A clear-only run ends at the portal with everyone still in the Blood Room, and isRunStarted stays true until
		// the next run: it never gets a boss phase.
		if(clearActive() || WitherActions.runSection().equals("clear")) return !arena && inClear(loc);
		Integer clearEnd = WitherActions.getSplitEnd("Clear");
		if(clearEnd != null && Utils.runTick() - clearEnd < BOSS_GRACE_TICKS) return arena || inClear(loc);
		return arena;
	}

	private static boolean inClear(Location loc) {
		return Rooms.inRoomBounds(loc) || Rooms.inDoor(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
	}

	/** This run has a clear and nobody has entered the Watcher's portal yet. */
	private static boolean clearActive() {
		String section = WitherActions.runSection();
		return (section.equals("clear") || section.equals("all")) && WitherActions.getSplitEnd("Clear") == null;
	}

	/** A clear-only run whose portal has been entered: the run is over, though practice mode stays on. */
	private static boolean clearOnlyDone() {
		return WitherActions.runSection().equals("clear") && WitherActions.getSplitEnd("Clear") != null;
	}

	private static void tick() {
		if(!death.Deaths.inRun() || clearOnlyDone()) return;
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(outOfBounds(p)) kill(p);
		}
	}

	private static boolean outOfBounds(Player p) {
		// Only survival or adventure. Creative gets the same bypass as CustomItems.onBlockBreak. An allowlist, not
		// "not creative", so no new game mode gets this without someone saying so.
		GameMode gm = p.getGameMode();
		if(gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
		// The plugin's own spectate state leaves the player in ADVENTURE.
		if(Utils.isSpectator(p)) return false;
		if(p.isDead() || p.getHealth() <= 0.0) return false;
		return !isInBounds(p.getLocation());
	}

	private static final int PRE_RUN_CHECK_TICKS = 40;

	/**
	 * Before the run, live modes only: every {@link #PRE_RUN_CHECK_TICKS} after {@code /m7practice} until the section
	 * starts at {@code startTick}, anyone out of bounds goes back to the section's spawn, alive. Tracked tasks, so a new
	 * run or {@code endPractice} cancels them.
	 */
	public static void schedulePreRunReturns(World world, String section, int startTick) {
		if(damage.Difficulty.current() == damage.Difficulty.CLASSIC) return;
		for(int t = PRE_RUN_CHECK_TICKS; t < startTick; t += PRE_RUN_CHECK_TICKS) {
			Utils.scheduleTask(() -> {
				Location spawn = commands.Practice.spawn(world, section);
				for(Player p : Bukkit.getOnlinePlayers()) {
					if(!outOfBounds(p)) continue;
					Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " out of bounds before the run, back to spawn");
					p.teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
				}
			}, t);
		}
	}

	private static void kill(Player p) {
		Location loc = p.getLocation();
		Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " out of bounds at "
				+ Utils.round(loc.getX(), 2) + " " + Utils.round(loc.getY(), 2) + " " + Utils.round(loc.getZ(), 2));
		death.Deaths.forceKill(p, death.Deaths.mob("The World Border"), respawnPoint(p.getWorld()));
	}

	/** Dungeon entrance while the clear is on, the boss spawn after the portal or in a boss-only section. */
	private static Location respawnPoint(World world) {
		return clearActive() ? JoinListener.dungeonEntrance() : Watcher.bossSpawn(world);
	}
}
