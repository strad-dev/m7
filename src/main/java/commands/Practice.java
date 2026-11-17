package commands;

import instructions.bosses.BossMotion;
import instructions.Server;
import instructions.bosses.Watcher;
import instructions.bosses.WitherActions;
import instructions.bosses.goldor.Goldor;
import instructions.bosses.maxor.Maxor;
import instructions.bosses.necron.Necron;
import instructions.bosses.storm.Storm;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.NonNull;
import plugin.BossScheduler;
import plugin.MovementAudit;
import plugin.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
 * Practice
 * 1. Every non-spectator must have a class (/class) or the run is refused: no class means no kit and no class
 *    tag, a silently broken run.
 * 2. Equips each with their saved /m7loadout kit, refreshed to current item definitions, teleports them to the
 *    phase's default location, then starts it.
 * 3. "--no-teleport" skips the teleport. Bare "classic"/"perfect_rng"/"rta" sets the damage mode (MAP.md §0),
 *    a damage.Mayor id the mayor, "on"/"off" Fix Watcher Bug, "badbonzo=on"/"badbonzo=off" Bad Bonzo, "batbug=on"/"batbug=off" Bat Bug; omitted,
 *    current settings stand, so standalone keeps whatever /dungeonsettings set. The network always sends all five.
 * 4. Runs the boss and server instructions. Pre-run delay is 60 ticks (3s); a bare integer overrides it: the network sends
 *    "m7:m7practice <section> 400" for a 20s window. See Server.serverInstructions.
 *
 * On the network the bare /m7practice label is StradDevHub's queue command (alias of /m7, force-claimed at boot),
 * so this is only reachable as /m7:m7practice, which M7Bridge dispatches and blocks players from typing.
 * Standalone, the bare label is ours.
 */
public class Practice implements CommandExecutor {

	/** Default teleport location per phase: {x, y, z, yaw, pitch}. */
	private static final Map<String, double[]> DEFAULT_LOCATIONS = Map.of(
			"all", new double[]{-120.5, 71, -183.5, 0f, 0f},
			"clear", new double[]{-120.5, 71, -183.5, 0f, 0f},
			"boss", new double[]{73.5, 221, 14.5, 0f, 0f},
			"maxor", new double[]{73.5, 221, 14.5, 0f, 0f},
			"storm", new double[]{73.5, 165, 53.5, 0f, 0f},
			"goldor", new double[]{100.5, 116.06250, 40.5, 0f, 0f},
			"necron", new double[]{54.5, 64, 114.5, 180f, 0f},
			"witherking", new double[]{54.5, 5, 76.5, 180f, 0f});

	/** Where the section's run starts: {@code /m7practice}'s teleport target. */
	public static Location spawn(World world, String section) {
		double[] loc = DEFAULT_LOCATIONS.get(section);
		return new Location(world, loc[0], loc[1], loc[2], (float) loc[3], (float) loc[4]);
	}

	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
		if(!(sender instanceof Player p)) {
			sender.sendMessage(Utils.msg("Only players can run this"));
			return true;
		}

		if(args.length >= 1 && args[0].equalsIgnoreCase("end")) {
			endPractice(p.getWorld());
			p.sendMessage(Utils.msg("<yellow>Practice session ended"));
			return true;
		}

		String section = "all";
		boolean noTeleport = false;
		// Optional pre-run delay in ticks (bare integer). Default 60 (3s); network passes 400 (20s) when it warps
		// a party in.
		int delayTicks = 60;
		RunSettings settings = new RunSettings();
		for(String arg : args) {
			// Settings first: the section fallback below swallows any unknown word and would read "classic" as a section.
			if(settings.accept(arg)) continue;
			if(arg.equalsIgnoreCase("--no-teleport") || arg.equalsIgnoreCase("--noteleport")) noTeleport = true;
			else if(arg.matches("\\d+")) delayTicks = Integer.parseInt(arg);
			else section = arg.toLowerCase();
		}
		if(!DEFAULT_LOCATIONS.containsKey(section)) {
			p.sendMessage(Utils.msg("<red>Invalid section specified.  Valid sections: clear boss maxor storm goldor necron witherking"));
			return true;
		}

		World world = p.getWorld();

		if(!prepareParticipants(p, noTeleport ? null : spawn(world, section))) return true;

		settings.apply();

		runPractice(world, section, delayTicks);
		return true;
	}

	/** One line per experimental setting that is on, before a run starts. */
	static void broadcastExperimentalSettings() {
		if(plugin.BatBug.enabled()) {
			org.bukkit.Bukkit.broadcast(Utils.msg("<gold><bold>BAT BUG<reset><gray> is on.  <light_purple>Experimental."));
		}
		if(plugin.BadBonzo.enabled()) {
			org.bukkit.Bukkit.broadcast(Utils.msg("<gold><bold>BAD BONZO<reset><gray> is on.  <light_purple>Experimental."));
		}
		if(plugin.WatcherFix.enabled()) {
			org.bukkit.Bukkit.broadcast(Utils.msg("<gold><bold>FIX WATCHER BUG<reset><gray> is on."));
		}
	}

	/**
	 * Class check, kit hand-out and teleport ({@code target} null skips it) for every participant. False, with
	 * {@code p} told why, if someone has no class. Shared with {@code /m7scenario}.
	 */
	static boolean prepareParticipants(Player p, Location target) {
		// Participants: online and not in spectator (the idle state on networked m7). Shared by class check, kit
		// hand-out and teleport so all three agree.
		List<Player> participants = new ArrayList<>();
		for(Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
			if(Utils.isSpectator(online)) continue;
			participants.add(online);
		}

		// Every participant needs a class: without one, no kit and no class tag, so no abilities or class-gated
		// damage. The network blocks this up front in /m7practice, so this never fires for a bridged run.
		List<String> noClass = new ArrayList<>();
		for(Player participant : participants) {
			if(loadout.Loadouts.getSelectedClass(participant.getUniqueId()) == null) noClass.add(participant.getName());
		}
		if(!noClass.isEmpty()) {
			boolean solo = noClass.size() == 1 && noClass.getFirst().equals(p.getName());
			p.sendMessage(solo
					? Utils.msg("<red>Pick a class first with <white>/class <c></white> before starting practice.",
							Placeholder.unparsed("c", "<archer|mage|tank|berserk|healer>"))
					: Utils.msg("<red>Some players have not picked a class yet!  <white><who></white>  <gray>(they need /class <c>)",
							Placeholder.unparsed("who", String.join(", ", noClass)),
							Placeholder.unparsed("c", "<archer|mage|tank|berserk|healer>")));
			return false;
		}

		// Saved kit for the selected class. The only way to get items now (/getcustomitems is gone): /class,
		// /m7loadout, then /m7practice. applyFor refreshes to current item definitions and sets the class tag
		// (gates mage beam and per-class damage). Idempotent on the network: M7Bridge applied the same file on join.
		for(Player participant : participants) {
			loadout.Loadouts.applyFor(participant);
		}

		// Skipped with --no-teleport so players start where they are.
		if(target != null) {
			for(Player participant : participants) {
				participant.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
				items.ItemUtils.landStanding(participant);
			}
		}
		return true;
	}

	/** A practice run is set up or running (from {@link #runPractice} until {@link #endPractice}). */
	public static boolean isActive() {
		return WitherActions.isPracticeMode();
	}

	/** Split stamped when each boss phase starts, latest first, and that phase's section. */
	private static final String[][] PHASE_SPLITS = {
			{"Necron", "witherking"}, {"Goldor", "necron"}, {"Storm", "goldor"}, {"Maxor", "storm"}, {"Clear", "maxor"}};

	/** Section of the boss phase the practice is in, or null outside practice, while the clear is on, or once the run has ended. */
	public static String currentBossSection() {
		String section = WitherActions.runSection();
		// A won run stays in practice mode until something ends it (the network's teardown), but it's over.
		if(!isActive() || WitherActions.runReported() || section.equals("clear")) return null;
		for(String[] split : PHASE_SPLITS) {
			if(WitherActions.getSplitEnd(split[0]) != null) return split[1];
		}
		if(section.equals("all")) return null;
		return DEFAULT_LOCATIONS.containsKey(section) ? section : "maxor";
	}

	/**
	 * Boss and server instructions. Bosses chain ({@code /m7practice boss} runs Maxor→Storm→Goldor→Necron) via each
	 * boss's chainNext.
	 */
	public static void runPractice(World world, String section) {
		runPractice(world, section, 60);
	}

	/**
	 * @param delayTicks pre-run window before the section starts (default 60 = 3s; network passes 400 = 20s when
	 *   it warps a party in). Forwarded to {@link Server#serverInstructions}.
	 */
	public static void runPractice(World world, String section, int delayTicks) {
		prepareRun(world, section);
		Server.serverInstructions(world, section, delayTicks);
	}

	/** Everything a run does before its section is staged: state resets, cleanup, {@code serverSetup}. Shared with {@code /m7scenario}. */
	public static void prepareRun(World world, String section) {
		scenario.Scenarios.stop();
		WitherActions.setPracticeMode(true);
		// Clear previous run's splits; this run records its own for the Wither King scoreboard.
		WitherActions.clearSplits();
		// Section + fresh run id, so this run's reports (300-score milestone, run-complete payload) match up.
		WitherActions.startRunTracking(section);
		// Game-mode-change tracking (practice scoreboard's golden-name anti-cheat).
		WitherActions.clearGameModeChanges();
		listeners.CustomItems.resetBerserkDamage();
		listeners.CustomItems.resetTerminatorCooldowns();
		// Class-ability (drop) cooldowns.
		listeners.CustomItems.resetAbilityCooldowns();
		// Per-run crypt-farm guard.
		items.ItemUtils.resetCrypts();
		// No ghosts or cheat-death cooldowns from a previous run.
		death.Deaths.reset();

		// Cancel choreography left from a previous run.
		Utils.cancelAllScheduled();
		Watcher.INSTANCE.arm(world, section.equals("all"));

		// Overall-run timer anchors at first boss spawn ("Overall" column reads it).
		Utils.markRunStart();
		MovementAudit.cancelAll();
		BossMotion.cancelAll();
		// Purge strays a prior (maybe aborted) run leaked. BEFORE serverSetup (minibosses) and serverInstructions
		// (bosses) so it never nukes this run's spawns, same order as /reset and /setup. Catches untracked withers
		// and crystals that serverSetup's forceCleanups miss, since those only free tracked refs.
		Server.hardMobCleanup();
		Server.serverSetup(world);
	}

	/**
	 * Cancel the practice session: stop choreography + movement, disarm the boss chain, practice mode off, clear
	 * boss entities. Game modes are left alone: putting players back in spectator (m7's idle state) is the network's
	 * ({@code M7Bridge.resetToSpectators}) or another plugin's.
	 */
	public static void endPractice(World world) {
		// FIRST: bank what the party finished. A cancelled, timed-out, force-ended or abandoned run has no ending
		// to report, so otherwise the splits and clear milestones it reached are lost. No-op if already reported
		// (win, wipe, Storm fail). Must precede setPracticeMode(false) (signalRunComplete won't fire outside
		// practice mode) and ClearManager.stop, which destroys what the result reads.
		WitherActions.signalRunAbandoned();
		WitherActions.setPracticeMode(false);
		// After the abandon signal, which reads it to stay quiet.
		scenario.Scenarios.stop();
		// Before the rest of the teardown, so a pending revival can't fire into it and mask durability bars come
		// off instead of being saved into a loadout.
		death.Deaths.reset();
		instructions.clear.ClearManager.stop(world); // remove secrets/chests, restore hotbar map slot, stop HUD loop
		Utils.cancelAllScheduled();
		items.combat.RagnarockAxe.reset();
		MovementAudit.cancelAll();
		BossMotion.cancelAll();
		Watcher.INSTANCE.arm(world, false);
		// arm() only sets fields, so the Watcher's portal detector would keep scanning and warp players to Maxor
		// after the run. forceCleanup stops it and closes the portal.
		Watcher.forceCleanup();
		// Drop the boss lane before teardown: cancelAllScheduled can't reach it, and its un-held one-shots (Maxor's
		// crystal respawn, the Wither King's) would fire into a dead session.
		BossScheduler.clearAll();
		// S4 plate un-presses from a block tick it queues itself, so teardown mid-press can leave it powered, and a
		// powered plate never fires Action.PHYSICAL again: S4 bricked for every later run. Forced off on both edges
		// of a run; Server.serverSetup is the other.
		listeners.GoldorListener.unpowerPlate(world);
		// S1 device writes blocks too (sea lantern over an obsidian cell, 16 input buttons), and its playback runs
		// on tracked scheduleTasks that cancelAllScheduled just killed. So restore synchronously here, the "after"
		// edge; Server.serverSetup is the "before".
		instructions.bosses.goldor.GoldorSimonSays.INSTANCE.cleanup();
		// Only caller of each boss's resetState. Without it an early end left Goldor active with gates blown open,
		// the core entrance an invisible barrier, and every boss's flags set.
		Maxor.INSTANCE.forceEndPhase();
		Storm.INSTANCE.forceEndPhase();
		Goldor.INSTANCE.forceEndPhase();
		Necron.INSTANCE.forceEndPhase();
		// Clear bosses, energy crystals, Wither King dragons/relics.
		for(org.bukkit.entity.Entity e : world.getEntities()) {
			if(e instanceof org.bukkit.entity.Wither
					|| e instanceof org.bukkit.entity.EnderCrystal
					|| e.getScoreboardTags().contains("SkyblockBoss")
					|| e.getScoreboardTags().contains("TASWitherKing")
					|| e.getScoreboardTags().contains("WitherKingDragon")
					|| e.getScoreboardTags().contains("TASWitherKingRelic")
					|| e.getScoreboardTags().contains("TASWatcher")) {
				e.remove();
			}
		}
	}
}
