package commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import plugin.Utils;
import plugin.Utils.VerboseLevel;

public class Verbose implements CommandExecutor {
	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
		if(!(sender instanceof Player p)) {
			sender.sendMessage(Utils.msg("Only players can run this"));
			return true;
		}

		if(args.length < 1 || args.length > 2) {
			p.sendMessage(Utils.msg("<red>Usage: /verbose \\<off|timer|on|super> [player]"));
			return true;
		}

		VerboseLevel level;
		switch(args[0].toLowerCase()) {
			case "off", "false" -> level = VerboseLevel.OFF;
			case "timer" -> level = VerboseLevel.TIMER;
			case "on", "true" -> level = VerboseLevel.ON; // from SUPER this drops back to normal, not a no-op
			// `super` toggles: on, or back to normal if already on.
			case "super" -> level = (Utils.getVerboseLevel() == VerboseLevel.SUPER) ? VerboseLevel.ON : VerboseLevel.SUPER;
			default -> {
				p.sendMessage(Utils.msg("<red>Usage: /verbose \\<off|timer|on|super> [player]"));
				return true;
			}
		}
		String filter = null;
		if(args.length == 2) {
			Player target = Bukkit.getPlayerExact(args[1]);
			if(target == null) {
				p.sendMessage(Utils.msg("<red>No player named <n>", Placeholder.unparsed("n", args[1])));
				return true;
			}
			filter = target.getName();
		}
		Utils.setVerboseLevel(level);
		Utils.setVerboseFilter(filter);
		p.sendMessage(Utils.msg("<green>Verbose Mode: " + level + (filter == null ? "" : " (only <n>)"), Placeholder.unparsed("n", filter == null ? "" : filter)));
		return true;
	}
}
