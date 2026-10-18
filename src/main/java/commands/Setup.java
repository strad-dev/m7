package commands;

import instructions.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import plugin.Utils;

/*
 * Setup
 * - Does an initial mob spawning (to test without running a full practice)
 */
public class Setup implements CommandExecutor {
	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
		if(!(sender instanceof Player p)) {
			sender.sendMessage(Utils.msg("Only players can run this"));
			return true;
		}

		Server.hardMobCleanup();
		Server.serverSetup(p.getWorld());
		p.sendMessage(Utils.msg("Reset the dungeon (map + mobs)."));
		return true;
	}
}
