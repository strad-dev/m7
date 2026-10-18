package death;

import instructions.Server;
import instructions.bosses.WitherActions;
import instructions.clear.ClearManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import plugin.M7;
import plugin.PlayerInventoryBackup;
import plugin.Utils;

import java.time.Duration;
import java.util.*;

/**
 * Death and revival in both live modes; classic has only /kill and out of bounds (MAP.md § Death, revival and real terminals). Gate is
 * {@code Difficulty.deathsEnabled()}: Perfect RNG kills the same as Realistic. Players stay invulnerable, nothing
 * here is HP-driven; every death is an explicit instakill from a mechanic:
 * <ul>
 *   <li>Storm lightning on a player not fully under a pillar ({@code storm/Storm.strikeUnsheltered});</li>
 *   <li>Storm pillar closing on a player ({@code storm/Storm.pollPlayerCrush});</li>
 *   <li>Goldor's 60-tick invalid-location sweep ({@code goldor/Goldor.pollInvalidLocations});</li>
 *   <li>relic in the wrong cauldron ({@code listeners/WitherKingListener}).</li>
 * </ul>
 * Plus {@code /kill} ({@link GenericKill}) and out of bounds ({@code listeners/OutOfBounds}), through
 * {@link #forceKill}: every mode, no {@link CheatDeath}.
 *
 * <p>{@link #kill} / {@link #forceKill} are the only ways in and own the whole decision: mode gate, run gate, who's killable,
 * {@link CheatDeath} proc, wipe check. Call sites must not pre-screen any of it or they drift.
 *
 * <p>A ghost is a vanilla spectator on purpose: {@code Utils.isSpectator} is already what every player mechanic
 * checks, so ghosts are locked out of terminals, devices, relics etc. with no new checks. Consequences:
 * <ul>
 *   <li>{@code ClearManager.isRealPlayer} goes false, so {@link #kill} banks them on the roster
 *       ({@code WitherActions.noteInRun}) before the flip: the network derives group size from it. Same as the
 *       quit hook.</li>
 *   <li>{@code MiscListener.onGameModeChange} would burn the golden name on the flip, so our flips go through
 *       {@link #expectGameModeChange}.</li>
 * </ul>
 *
 * <p>Revival is automatic: infinite Revive Stones assumed, so {@link #REVIVE_TICKS} after death the ghost is
 * restored in place with the inventory they died with. They revive themselves, so the line names them twice.
 *
 * <p>Party wipe ({@link #wipe}) is the exception: the last death never becomes a ghost. The wipe ends the session,
 * restores every ghost's mode and inventory, and gathers the party on one spot.
 */
public final class Deaths {
	private Deaths() {}

	private static final int REVIVE_TICKS = 100;
	/** One tick longer than its 20-tick slot so titles don't flicker apart. */
	private static final int TITLE_STAY_TICKS = 21;

	/**
	 * Held in death order.
	 * @param gameMode revival restores it rather than assuming Adventure
	 * @param diedAt   absolute server tick; countdown and revival read it
	 * @param reviveAt null = revive where they stand
	 */
	private record Ghost(UUID uuid, PlayerInventoryBackup inventory, GameMode gameMode, int diedAt, Location reviveAt) {}

	private static final Map<UUID, Ghost> ghosts = new LinkedHashMap<>();

	/** Next {@code PlayerGameModeChangeEvent} is ours. Consumed by {@link #ownsGameModeChange}. */
	private static final java.util.Set<UUID> expectedModeChange = new java.util.HashSet<>();

	private static BukkitTask driver;

	// ==================== the kill ====================

	/**
	 * @param killer mob name, plain text (inserted unparsed)
	 * @return true if they died. False for every refusal alike (mode, no run, not a runner, already dead,
	 *         {@link CheatDeath} proc); no call site treats them differently.
	 */
	public static boolean kill(Player p, String killer) {
		if(!damage.Difficulty.deathsEnabled()) return false;
		return kill(p, mob(killer), false, null);
	}

	/**
	 * {@code /kill} ({@link GenericKill}) and out of bounds: every mode, classic included, and no {@link CheatDeath}, as
	 * vanilla's /kill skips totems.
	 *
	 * @param killer   already styled: {@link #mob} or {@link #displayName}
	 * @param reviveAt the ghost is moved here and revives here; null = where they died
	 */
	public static boolean forceKill(Player p, Component killer, Location reviveAt) {
		return kill(p, killer, true, reviveAt);
	}

	/**
	 * A practice is live. {@code Server.isRunStarted()} alone stays true after {@code Practice.endPractice} until the next
	 * {@code /setup}, and the network's anticheat ends the practice to get a vanilla kill through {@link GenericKill}.
	 */
	public static boolean inRun() {
		return Server.isRunStarted() && WitherActions.isPracticeMode();
	}

	private static boolean kill(Player p, Component killer, boolean forced, Location reviveAt) {
		if(!inRun()) return false;
		if(!appliesTo(p)) return false;

		// Hit cue, not death cue, so it plays before anything decides: a mask proc with no sound reads as nothing.
		playHurtSound(p);

		if(!forced && CheatDeath.tryProc(p)) return false;

		// Bank while still readable: a ghost is out of realPlayers(), and leaderboards take group size from the roster.
		WitherActions.noteInRun(p);
		// Death is certain now. Scored here, not per branch: a wipe is still a death.
		instructions.clear.ClearManager.noteDeath();

		announceDeath(p, killer);
		// Party-wide "someone is down"; playGlobalSound plays at each listener.
		Utils.playGlobalSound(Sound.BLOCK_NOTE_BLOCK_PLING, 2.0f, 0.5f);
		Utils.debug(Utils.DebugType.SERVER, Utils.getRealName(p) + " was killed by "
				+ PlainTextComponentSerializer.plainText().serialize(killer));

		// Before ghosting: last one standing never becomes a ghost (avoids a flicker and inventory round trip).
		if(isLastAlive(p)) {
			wipe(p);
			return true;
		}

		ghosts.put(p.getUniqueId(),
				new Ghost(p.getUniqueId(), new PlayerInventoryBackup(p), p.getGameMode(), Utils.serverTick(), reviveAt));
		// A terminal is one player's at a time; a ghost mustn't hold it. Close handler clears its pending flag.
		p.closeInventory();
		expectGameModeChange(p);
		p.setGameMode(GameMode.SPECTATOR);
		if(reviveAt != null) p.teleport(reviveAt, PlayerTeleportEvent.TeleportCause.PLUGIN);
		showReviveTitle(p, REVIVE_TICKS);
		return true;
	}

	/**
	 * Someone actually running the phase. Public so a mechanic can gate its visuals (no Storm bolt on a spectator)
	 * on the same answer, without pre-screening the kill.
	 * <p>
	 * SURVIVAL/ADVENTURE allowlist, not "not creative", same as {@code OutOfBounds}: creative is exempt from every
	 * protection and no new mode should apply silently. Spectators excluded by mode; covers existing ghosts too.
	 */
	public static boolean appliesTo(Player p) {
		if(p == null || !p.isOnline()) return false;
		GameMode gm = p.getGameMode();
		if(gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
		return !Utils.isSpectator(p);
	}

	/** World-positioned, not {@code Utils.playLocalSound}, so nearby teammates hear it. Volume 1 = vanilla radius. */
	private static void playHurtSound(Player p) {
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
	}

	public static boolean isGhost(Player p) {
		return p != null && ghosts.containsKey(p.getUniqueId());
	}

	/**
	 * Their death is a wipe. Asked while {@code p} is still in Adventure, so the question is whether anyone else is
	 * in {@code ClearManager.realPlayers()} (already excludes ghosts; same definition the roster and HUD
	 * use). A creative onlooker counts as alive and holds the run open.
	 */
	private static boolean isLastAlive(Player p) {
		for(Player other : ClearManager.realPlayers()) {
			if(!other.equals(p)) return false;
		}
		return true;
	}

	// ==================== the wipe ====================

	/** Yaw 0, pitch 0. */
	private static final double[] WIPE_RETURN = {28.5, 166, 118.5};

	/**
	 * Run lost. {@code lastToDie} never became a ghost.
	 * <p>
	 * Ends the session itself, unlike the other failure path: standalone nothing listens to run-complete, so the
	 * party would be stuck as spectators in a run that never stops. Does what {@code /m7practice end} does, then
	 * restores mode and inventory and teleports everyone to one spot.
	 * <p>
	 * Order: ghosts restored before teardown so {@code ClearManager.stop}'s hotbar cleanup sees them as real;
	 * run-complete signalled before teardown while state is intact for {@code RunResult.capture};
	 * {@code endPractice} gets {@code toSpectator = false} since we already placed everyone.
	 * <p>
	 * Same signal as Storm's all-pillars-gone failure, so the network frees its slot and re-asserts spectator (m7
	 * idle state). Payload keeps phase durations and clear milestones, so leaderboards keep finished sections
	 * ({@code Leaderboards.submit}).
	 */
	private static void wipe(Player lastToDie) {
		World world = lastToDie.getWorld();
		Bukkit.broadcast(Utils.msg("<red>Your whole party is dead!  You failed the run."));

		List<Player> party = new ArrayList<>();
		party.add(lastToDie);
		for(Ghost g : ghosts.values()) {
			Player p = Bukkit.getPlayer(g.uuid());
			if(p == null || !p.isOnline()) continue; // already on the roster; a relog re-enters normally
			expectGameModeChange(p);
			p.setGameMode(g.gameMode());
			g.inventory().restore(p);
			p.clearTitle();
			party.add(p);
		}
		ghosts.clear();

		WitherActions.signalRunComplete(false);
		commands.Practice.endPractice(world, false);

		Location home = new Location(world, WIPE_RETURN[0], WIPE_RETURN[1], WIPE_RETURN[2], 0f, 0f);
		for(Player p : party) p.teleport(home, PlayerTeleportEvent.TeleportCause.PLUGIN);
	}

	// ==================== revival ====================

	/**
	 * Per-tick driver: revival countdowns, revivals, durability refresh, action-bar fallback.
	 * <p>
	 * Raw repeating task from {@code M7.onEnable}, untracked so it survives {@code Utils.cancelAllScheduled};
	 * otherwise a boss teardown could strand a ghost in spectator.
	 */
	public static void start() {
		if(driver != null) return;
		driver = new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		}.runTaskTimer(M7.getInstance(), 1L, 1L);
	}

	public static void stop() {
		if(driver != null) {
			driver.cancel();
			driver = null;
		}
		ghosts.clear();
	}

	private static void tick() {
		int now = Utils.serverTick();

		if(!ghosts.isEmpty()) {
			// Copy: reviving mutates the map.
			for(Ghost g : ghosts.values().toArray(new Ghost[0])) {
				int elapsed = now - g.diedAt();
				Player p = Bukkit.getPlayer(g.uuid());
				if(p == null || !p.isOnline()) continue; // already on the roster; a relog re-enters normally
				if(elapsed >= REVIVE_TICKS) revive(p, g);
				else if(elapsed > 0 && elapsed % 20 == 0) showReviveTitle(p, REVIVE_TICKS - elapsed);
			}
		}

		// Every tick: CheatDeath throttles drawing itself and needs the exact expiry tick, or a ready mask shows a
		// part-empty bar.
		if(damage.Difficulty.deathsEnabled()) {
			for(Player p : Bukkit.getOnlinePlayers()) {
				CheatDeath.refreshDurability(p);
			}
		}

		// Per-player bar segments must tick where no boss HUD owns the bar, so this fills unclaimed ticks. No
		// fighting a live HUD: sendActionBar stamps the tick and boss HUDs run earlier in the tick. Here because
		// it's the plugin's one every-tick driver.
		//
		// Pass an empty base: sendActionBar appends the segments itself, and passing them printed every timer twice.
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(Utils.actionBarOwnedThisTick(p)) continue;
			if(CheatDeath.hasCooldowns(p) || items.combat.RagnarockAxe.ticksLeft(p) > 0
					|| !pets.Pets.actionBarSegment(p).isEmpty()) {
				Utils.sendActionBar(p, Component.empty());
			}
		}
	}

	/** No fades: it's a clock and fading makes it look like it drifts. */
	private static void showReviveTitle(Player p, int ticksLeft) {
		int seconds = ticksLeft / 20;
		p.showTitle(Title.title(
				Utils.msg("<yellow>BEING REVIVED"),
				Utils.msg("<green>You will be revived in " + seconds + "s."),
				Title.Times.times(Duration.ZERO, Duration.ofMillis(TITLE_STAY_TICKS * 50L), Duration.ZERO)));
	}

	/**
	 * Revives wherever 5s of spectator flight left them, so death costs distance too, unless the kill pinned a spot
	 * ({@link Ghost#reviveAt}, out of bounds). Out of bounds there means {@code OutOfBounds} kills them a tick later.
	 */
	private static void revive(Player p, Ghost g) {
		ghosts.remove(g.uuid());
		if(g.reviveAt() != null) p.teleport(g.reviveAt(), PlayerTeleportEvent.TeleportCause.PLUGIN);
		expectGameModeChange(p);
		p.setGameMode(g.gameMode());
		g.inventory().restore(p);
		p.clearTitle();

		String name = "<gold>" + Utils.getRealName(p);
		Bukkit.broadcast(Utils.msg("<green> ❣ " + name + " <green>was revived by " + name + "<green>!"));
	}

	// ==================== messages ====================

	/** "You were killed by" to the dead, "&lt;name&gt; was killed by" to others, so per player. Unparsed, no tag injection. */
	private static void announceDeath(Player p, Component killer) {
		TagResolver mob = Placeholder.component("mob", killer);
		TagResolver who = Placeholder.component("who", displayName(p));
		for(Player other : Bukkit.getOnlinePlayers()) {
			other.sendMessage(other.equals(p)
					? Utils.msg("<red> ☠ <gray>You were killed by <mob> <gray>and became a ghost.", mob)
					: Utils.msg("<red> ☠ <who> <gray>was killed by <mob> <gray>and became a ghost.", who, mob));
		}
	}

	/** A non-player killer (mob, "The World Border"): red, unparsed. */
	public static Component mob(String name) {
		return Utils.msg("<red><name>", Placeholder.unparsed("name", name));
	}

	/**
	 * MiniMessage display name (role prefix + colour) the network keeps on each player; its serializer escapes the
	 * name, so parsing it can't inject tags. Standalone there is none, so gold.
	 */
	public static final NamespacedKey NAME_KEY = new NamespacedKey("m7", "name");

	public static Component displayName(Player p) {
		String name = p.getPersistentDataContainer().get(NAME_KEY, PersistentDataType.STRING);
		if(name != null) return Utils.msg(name);
		return Utils.msg("<gold><name>", Placeholder.unparsed("name", Utils.getRealName(p)));
	}

	// ==================== lifecycle ====================

	/** So {@code MiscListener.onGameModeChange} doesn't burn their golden name. Dying isn't cheating. */
	private static void expectGameModeChange(Player p) {
		expectedModeChange.add(p.getUniqueId());
	}

	/** Consumes the flag, so the next change is the player's own again. */
	public static boolean ownsGameModeChange(UUID id) {
		return expectedModeChange.remove(id);
	}

	/** A logged-out ghost can't revive; the quit hook keeps them on the roster. */
	public static void onQuit(Player p) {
		ghosts.remove(p.getUniqueId());
		expectedModeChange.remove(p.getUniqueId());
	}

	/** At start and end of every session, so a run never inherits ghosts or saver cooldowns. */
	public static void reset() {
		ghosts.clear();
		expectedModeChange.clear();
		CheatDeath.reset();
	}
}
