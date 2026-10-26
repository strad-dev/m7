package commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class TabCompletor implements TabCompleter {
	@SuppressWarnings("NullableProblems")
	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if(!(sender instanceof Player)) {
			return new ArrayList<>();
		}

		List<String> completions = new ArrayList<>();
		String cmdName = command.getName().toLowerCase();

		switch(cmdName) {
			case "m7practice" -> {
				if(args.length == 1) {
					String[] sections = {"all", "clear", "boss", "maxor", "storm", "goldor", "necron", "witherking", "end"};
					String input = args[0].toLowerCase();

					for(String section : sections) {
						if(section.toLowerCase().startsWith(input)) {
							completions.add(section);
						}
					}
				} else if(args.length >= 2) {
					// /m7practice <section> [--no-teleport] [<delayTicks>] [<mode>]: args are order-independent
					// (see Practice), so suggest whichever isn't typed yet.
					String input = args[args.length - 1].toLowerCase();
					boolean hasNoTeleport = false, hasDelay = false, hasMode = false;
					for(int i = 1; i < args.length - 1; i++) {
						if(args[i].equalsIgnoreCase("--no-teleport") || args[i].equalsIgnoreCase("--noteleport")) hasNoTeleport = true;
						else if(args[i].matches("\\d+")) hasDelay = true;
						else if(damage.Difficulty.parse(args[i]) != null) hasMode = true;
					}
					if(!hasNoTeleport && "--no-teleport".startsWith(input)) {
						completions.add("--no-teleport");
					}
					if(!hasDelay) {
						// Pre-run delay in ticks: default 60 (3s); network warp-in uses 400 (20s).
						for(String preset : new String[]{"60", "100", "200", "400"}) {
							if(preset.startsWith(input)) completions.add(preset);
						}
					}
					if(!hasMode) {
						// commandName, not id: it's the spelling the FAQ and settings lines use.
						for(damage.Difficulty d : damage.Difficulty.values()) {
							if(d.commandName().startsWith(input)) completions.add(d.commandName());
						}
					}
				}
			}


			case "class" -> {
				if(args.length == 1) {
					for(String val : loadout.Loadouts.CLASSES) {
						if(val.toLowerCase().startsWith(args[0].toLowerCase())) {
							completions.add(val);
						}
					}
				}
			}

			case "m7loadout" -> {
				if(args.length == 1 && "reset".startsWith(args[0].toLowerCase())) {
					completions.add("reset");
				}
			}

			case "verbose" -> {
				if(args.length == 1) {
					for(String val : new String[]{"off", "timer", "on", "super", "true", "false"}) {
						if(val.startsWith(args[0].toLowerCase())) {
							completions.add(val);
						}
					}
				} else if(args.length == 2) {
					for(Player online : Bukkit.getOnlinePlayers()) {
						if(online.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
							completions.add(online.getName());
						}
					}
				}
			}

			case "dungeonsettings" -> {
				if(args.length == 1) {
					for(String setting : new String[]{"difficulty", "mayor", "watcherfix", "badbonzo", "batbug", "alphagdrag"}) {
						if(setting.startsWith(args[0].toLowerCase())) completions.add(setting);
					}
				} else if(args.length == 2 && args[0].equalsIgnoreCase("difficulty")) {
					// Off the enum, not a literal list, so a new mode can't be executable but unlistable.
					for(damage.Difficulty d : damage.Difficulty.values()) {
						if(d.commandName().startsWith(args[1].toLowerCase())) completions.add(d.commandName());
					}
				} else if(args.length == 2 && args[0].equalsIgnoreCase("mayor")) {
					// Off the enum, same as the modes above.
					for(damage.Mayor m : damage.Mayor.values()) {
						if(m.id().startsWith(args[1].toLowerCase())) completions.add(m.id());
					}
				} else if(args.length == 2 && args[0].equalsIgnoreCase("watcherfix")) {
					for(plugin.WatcherFix w : plugin.WatcherFix.values()) {
						if(w.id().startsWith(args[1].toLowerCase())) completions.add(w.id());
					}
				} else if(args.length == 2 && args[0].equalsIgnoreCase("badbonzo")) {
					for(plugin.BadBonzo b : plugin.BadBonzo.values()) {
						if(b.id().startsWith(args[1].toLowerCase())) completions.add(b.id());
					}
				} else if(args.length == 2 && args[0].equalsIgnoreCase("batbug")) {
					for(plugin.BatBug b : plugin.BatBug.values()) {
						if(b.id().startsWith(args[1].toLowerCase())) completions.add(b.id());
					}
				} else if(args.length == 2 && args[0].equalsIgnoreCase("alphagdrag")) {
					for(plugin.AlphaGDrag a : plugin.AlphaGDrag.values()) {
						if(a.id().startsWith(args[1].toLowerCase())) completions.add(a.id());
					}
				}
			}

			case "setspeed" -> {
				String[] speedPresets = {"400", "500", "600", "650"};
				if(args.length == 1) {
					String input = args[0].toLowerCase();
					for(Player online : Bukkit.getOnlinePlayers()) {
						if(online.getName().toLowerCase().startsWith(input)) {
							completions.add(online.getName());
						}
					}
					for(String preset : speedPresets) {
						if(preset.startsWith(input)) {
							completions.add(preset);
						}
					}
				} else if(args.length == 2) {
					String input = args[1].toLowerCase();
					for(String preset : speedPresets) {
						if(preset.startsWith(input)) {
							completions.add(preset);
						}
					}
				}
			}

		}

		return completions;
	}
}