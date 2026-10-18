package commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import plugin.M7;
import plugin.Utils;

public class SetSpeed implements CommandExecutor {
	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
		// /setspeed                  -> show self speed
		// /setspeed <speed>          -> set self speed
		// /setspeed <player>         -> show named player's speed
		// /setspeed <player> <speed> -> set named player's speed
		if(args.length == 0) {
			if(!(sender instanceof Player p)) {
				sender.sendMessage(Utils.msg("Console must specify a player: /setspeed \\<player> [speed]"));
				return true;
			}
			sender.sendMessage(Utils.msg("Current speed: " + getSpeed(p)));
			return true;
		}
		if(args.length == 1) {
			Integer parsed = tryParseInt(args[0]);
			if(parsed != null) {
				if(!(sender instanceof Player p)) {
					sender.sendMessage(Utils.msg("Console must specify a player: /setspeed \\<player> \\<speed>"));
					return true;
				}
				Utils.setSpeed(p, parsed);
				sender.sendMessage(Utils.msg("Speed set to " + parsed));
				return true;
			}
			Player target = resolvePlayer(args[0]);
			if(target == null) {
				sender.sendMessage(Utils.msg("No player named '<name>'", Placeholder.unparsed("name", args[0])));
				return true;
			}
			sender.sendMessage(Utils.msg("<name> current speed: " + getSpeed(target), Placeholder.unparsed("name", target.getName())));
			return true;
		}
		if(args.length == 2) {
			Player target = resolvePlayer(args[0]);
			if(target == null) {
				sender.sendMessage(Utils.msg("No player named '<name>'", Placeholder.unparsed("name", args[0])));
				return true;
			}
			Integer speed = tryParseInt(args[1]);
			if(speed == null) {
				sender.sendMessage(Utils.msg("Speed must be an integer"));
				return true;
			}
			Utils.setSpeed(target, speed);
			sender.sendMessage(Utils.msg("<name> speed set to " + speed, Placeholder.unparsed("name", target.getName())));
			return true;
		}
		sender.sendMessage(Utils.msg("Usage: /setspeed [player] [speed]"));
		return true;
	}

	private static Player resolvePlayer(String name) {
		return Bukkit.getPlayerExact(name);
	}

	private static Integer tryParseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch(NumberFormatException e) {
			return null;
		}
	}

	private static int getSpeed(Player p) {
		NamespacedKey key = new NamespacedKey(M7.getInstance(), "speed");
		for(AttributeModifier mod : p.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers()) {
			if(mod.getKey().equals(key)) {
				return (int) Math.round(mod.getAmount() * 100 + 100);
			}
		}
		return 100;
	}
}
