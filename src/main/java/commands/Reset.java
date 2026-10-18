package commands;

import instructions.Actions;
import instructions.Server;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import plugin.MovementAudit;
import plugin.Utils;

public class Reset implements CommandExecutor {
	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
		if(!(sender instanceof Player p)) {
			sender.sendMessage(Utils.msg("Only players can run this"));
			return true;
		}

		MovementAudit.cancelAll();
		Actions.cancelAllMovement();
		Utils.cancelAllScheduled();
		items.combat.RagnarockAxe.reset();
		// Cleanup before serverSetup respawns minibosses so it never nukes the fresh spawns.
		Server.hardMobCleanup();
		Server.serverSetup(Bukkit.getWorld("world"));
		p.sendMessage(Utils.msg("Reset the dungeon"));
		return true;
	}
}
