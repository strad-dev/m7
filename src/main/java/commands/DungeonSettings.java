package commands;

import damage.Difficulty;
import damage.Mayor;
import plugin.BadBonzo;
import plugin.BatBug;
import plugin.WatcherFix;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import plugin.Utils;

/**
 * {@code /dungeonsettings [difficulty [<mode>] | mayor [<jerry_paul|paul|jerry|other|derpy>] | watcherfix [<on|off>] | badbonzo [<on|off>] | batbug [<on|off>]]} - this server's
 * dungeon settings (MAP.md §0). No args prints them; a setting with no value steps it to the next value.
 * <p>
 * Four independent settings, any combination is valid:
 * <ul>
 *   <li><b>difficulty</b> ({@link Difficulty}) - <i>classic</i> assumes all four debuffs applied and blessings
 *       maxed, so you can focus on movement and routing. <i>perfect_rng</i> makes those live inputs (debuffs have
 *       to be built, blessings are what the party collected) and turns on the instakills in {@code death/Deaths},
 *       but the dungeon still rolls your way: one-click terminals, short devices, assumed pet. <i>rta</i> (shown
 *       as "Realistic") adds the manual work: generated terminal puzzles, working devices, your own pet menu.</li>
 *   <li><b>mayor</b> ({@link Mayor}) - <i>jerry_paul</i> (default) gives EZPZ +10 bonus score, boosted blessings
 *       and Jerry's +10% stats, <i>paul</i> the first two, <i>jerry</i> only the stats, <i>other</i> none,
 *       <i>derpy</i> none and doubles every mob's health.</li>
 * </ul>
 * Both are flags on inputs, never second damage paths - see the two classes.
 * <p>
 * Bare {@code /dungeonsettings} from a player opens {@link SettingsMenu}, standalone only: on the network these
 * are party settings owned by the lobby menu, so text output stands.
 * <p>
 * Replaced {@code /toggledungeondifficulty}, which was the difficulty half.
 * <p>
 * On the network the party leader sets these ({@code /p settings difficulty <mode>},
 * {@code /p settings mayor <jerry_paul|paul|jerry|other|derpy>}) and they ride the practice request so the whole party inherits
 * them: a mixed-mode party would make one boss take different damage per player and let half of it die. This is
 * the standalone equivalent.
 * <p>
 * <b>Times from different settings aren't comparable.</b> So difficulty travels on the run payload
 * ({@code plugin/RunResult}) and network leaderboards key on it as a third axis; mayor travels too, and the
 * network folds it onto three boards (Paul, Other, Derpy).
 */
public class DungeonSettings implements CommandExecutor {
	private static final String USAGE =
			"<red>Usage: /dungeonsettings [difficulty [" + modeIds() + "] | mayor [jerry_paul|paul|jerry|other|derpy] | watcherfix [on|off] | badbonzo [on|off] | batbug [on|off]]";

	/**
	 * Typed mode names, joined from {@link Difficulty} so the usage line can't drift from the enum.
	 * {@code commandName()}, not {@code id()}: Realistic is typed {@code realistic}, only stored as {@code rta}.
	 */
	private static String modeIds() {
		return java.util.Arrays.stream(Difficulty.values())
				.map(Difficulty::commandName)
				.collect(java.util.stream.Collectors.joining("|"));
	}

	/** Menu a bare {@code /dungeonsettings} opens standalone. See {@link SettingsMenu#suppressed()}. */
	private final SettingsMenu menu;

	public DungeonSettings(SettingsMenu menu) {
		this.menu = menu;
	}

	@Override
	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label,
			String @NonNull [] args) {
		if(args.length == 0) {
			// Standalone player gets the menu; console and everyone on the network get text.
			if(sender instanceof Player p && !SettingsMenu.suppressed()) menu.open(p);
			else show(sender);
			return true;
		}
		switch(args[0].toLowerCase()) {
			case "difficulty", "mode" -> difficulty(sender, args);
			case "mayor" -> mayor(sender, args);
			case "watcherfix" -> watcherFix(sender, args);
			case "badbonzo" -> badBonzo(sender, args);
			case "batbug" -> batBug(sender, args);
			default -> sender.sendMessage(Utils.msg(USAGE));
		}
		return true;
	}

	/** Current settings, one line each. What bare {@code /dungeonsettings} prints. */
	private static void show(CommandSender sender) {
		sender.sendMessage(Utils.msg("<gold><bold>DUNGEON SETTINGS"));
		// Name, not id: "rta" is a storage key. Every name is also a parse alias, so what's shown can be typed back.
		sender.sendMessage(Utils.msg("<dark_gray>- <gray>difficulty: <yellow><value>  <dark_gray><desc>",
				Placeholder.unparsed("value", Difficulty.current().displayName()),
				Placeholder.parsed("desc", describe(Difficulty.current()))));
		sender.sendMessage(Utils.msg("<dark_gray>- <gray>mayor: <yellow><value>",
				Placeholder.unparsed("value", Mayor.current().id())));
		sender.sendMessage(Utils.msg("<dark_gray>- <gray>watcherfix: <yellow><value>  <dark_gray><desc>",
				Placeholder.unparsed("value", WatcherFix.current().id()),
				Placeholder.unparsed("desc", describe(WatcherFix.current()))));
		sender.sendMessage(Utils.msg("<dark_gray>- <gray>badbonzo: <yellow><value>  <dark_gray><desc>",
				Placeholder.unparsed("value", BadBonzo.current().id()),
				Placeholder.unparsed("desc", describe(BadBonzo.current()))));
		sender.sendMessage(Utils.msg("<dark_gray>- <gray>batbug: <yellow><value>  <dark_gray><desc>",
				Placeholder.unparsed("value", BatBug.current().id()),
				Placeholder.unparsed("desc", describe(BatBug.current()))));
		sender.sendMessage(Utils.msg("<dark_gray>Change one with <white>/dungeonsettings <setting> [value]"));
	}

	private static void difficulty(CommandSender sender, String[] args) {
		Difficulty next;
		if(args.length >= 2) {
			next = Difficulty.parse(args[1]);
			if(next == null) {
				sender.sendMessage(Utils.msg(USAGE));
				return;
			}
			Difficulty.set(next);
		} else {
			// No value: step to next mode, like the old bare /toggledungeondifficulty.
			next = Difficulty.toggle();
		}
		applyDifficulty(next);
	}

	/**
	 * Announce a new difficulty. Split out because {@link SettingsMenu} sets the same global from a click and must
	 * announce it the same way: a silent server-wide change scores someone else's run under a mode they never chose.
	 */
	static void applyDifficulty(Difficulty next) {
		Bukkit.broadcast(Utils.msg("<gold><bold>DUNGEON DIFFICULTY<reset><gray> is now <yellow><value>",
				Placeholder.unparsed("value", next.displayName())));
		Bukkit.broadcast(Utils.msg("<gray><desc>", Placeholder.parsed("desc", describe(next))));
	}

	private static void mayor(CommandSender sender, String[] args) {
		Mayor next;
		if(args.length >= 2) {
			next = Mayor.parse(args[1]);
			if(next == null) {
				sender.sendMessage(Utils.msg(USAGE));
				return;
			}
			Mayor.set(next);
		} else {
			next = Mayor.toggle();
		}
		applyMayor(next);
	}

	/** Announce a new mayor. Same split and reason as {@link #applyDifficulty}. */
	static void applyMayor(Mayor next) {
		Bukkit.broadcast(Utils.msg("<gold><bold>MAYOR<reset><gray> is now <yellow><value>",
				Placeholder.unparsed("value", next.id())));
		// HP is latched at spawn, so a mid-session change leaves mobs already on the floor alone.
		if(instructions.bosses.WitherActions.isPracticeMode()) {
			Bukkit.broadcast(Utils.msg("<dark_gray>Mob health is set when a mob spawns, so this only affects what spawns from now on."));
		}
	}

	private static void watcherFix(CommandSender sender, String[] args) {
		WatcherFix next;
		if(args.length >= 2) {
			next = WatcherFix.parse(args[1]);
			if(next == null) {
				sender.sendMessage(Utils.msg(USAGE));
				return;
			}
			WatcherFix.set(next);
		} else {
			next = WatcherFix.toggle();
		}
		applyWatcherFix(next);
	}

	/** Announce Fix Watcher Bug on/off. Same split and reason as {@link #applyDifficulty}. */
	static void applyWatcherFix(WatcherFix next) {
		Bukkit.broadcast(Utils.msg("<gold><bold>FIX WATCHER BUG<reset><gray> is now <yellow><value>",
				Placeholder.unparsed("value", next.id())));
		Bukkit.broadcast(Utils.msg("<gray><desc>", Placeholder.unparsed("desc", describe(next))));
	}

	private static void badBonzo(CommandSender sender, String[] args) {
		BadBonzo next;
		if(args.length >= 2) {
			next = BadBonzo.parse(args[1]);
			if(next == null) {
				sender.sendMessage(Utils.msg(USAGE));
				return;
			}
			BadBonzo.set(next);
		} else {
			next = BadBonzo.toggle();
		}
		applyBadBonzo(next);
	}

	/** Announce Bad Bonzo on/off. Same split and reason as {@link #applyDifficulty}. */
	static void applyBadBonzo(BadBonzo next) {
		Bukkit.broadcast(Utils.msg("<gold><bold>BAD BONZO<reset><gray> is now <yellow><value>",
				Placeholder.unparsed("value", next.id())));
		Bukkit.broadcast(Utils.msg("<gray><desc>", Placeholder.unparsed("desc", describe(next))));
	}

	private static void batBug(CommandSender sender, String[] args) {
		BatBug next;
		if(args.length >= 2) {
			next = BatBug.parse(args[1]);
			if(next == null) {
				sender.sendMessage(Utils.msg(USAGE));
				return;
			}
			BatBug.set(next);
		} else {
			next = BatBug.toggle();
		}
		applyBatBug(next);
	}

	/** Announce Bat Bug on/off. Same split and reason as {@link #applyDifficulty}. */
	static void applyBatBug(BatBug next) {
		Bukkit.broadcast(Utils.msg("<gold><bold>BAT BUG<reset><gray> is now <yellow><value>",
				Placeholder.unparsed("value", next.id())));
		Bukkit.broadcast(Utils.msg("<gray><desc>", Placeholder.unparsed("desc", describe(next))));
	}

	/** One line on what a value means. MiniMessage: inserted as a parsed placeholder. */
	private static String describe(Difficulty d) {
		return switch(d) {
			case CLASSIC -> "Debuffs are automatically applied and blessings are always maxed.";
			case PERFECT_RNG -> "Debuffs must be manually applied, you can <bold><red>die</red></bold>, terminals must be manually completed.  Assumes the best RNG possible.";
			case REALISTIC -> "In addition to modifiers in Perfect RNG, you must manually manage pets, and terminals are randomly generated.";
		};
	}

	private static String describe(BadBonzo b) {
		return switch(b) {
			case OFF -> "Bonzo Staff wind charges spawn on the tick you click.";
			case ON -> "This should make Bonzo Staff behave closer to actual Hypixel, but I think Hypixel's implementation is bullshit.";
		};
	}

	private static String describe(WatcherFix w) {
		return switch(w) {
			case OFF -> "The Watcher waits for tick 420 before his second wave, like Hypixel.";
			case ON -> "The Watcher starts his second wave 3s after returning to the center.";
		};
	}

	private static String describe(BatBug b) {
		return switch(b) {
			case OFF -> "First bat killed by any player grants +1 score.";
			case ON -> "First bat killed by each player grants +1 score.";
		};
	}
}
