package commands;

import damage.Difficulty;
import instructions.clear.Blessing;
import instructions.clear.ClearManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;
import plugin.Utils;

import java.util.Map;

/** /blessings: the level each type feeds the damage formulas now, and where it came from. */
public class BlessingsCommand implements CommandExecutor {
	private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};

	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
		boolean assumed = Difficulty.blessingsAssumedMax();
		sender.sendMessage(Utils.msg("<gold>Blessings <gray>(" + (assumed ? "assumed maxed, no clear this session" : "found in this run's clear") + ")"));
		for(Utils.BlessingType type : Utils.BlessingType.values()) {
			int max = Difficulty.maxBlessingLevel(type);
			sender.sendMessage(Utils.msg(" " + colour(type) + name(type) + " <white>" + Difficulty.blessingLevel(type)
					+ (max > 0 ? "<gray>/" + max : "")));
		}
		if(assumed) return true;
		Map<Blessing, Integer> tally = ClearManager.blessingTally();
		if(tally.isEmpty()) {
			sender.sendMessage(Utils.msg("<gray>None found yet."));
			return true;
		}
		sender.sendMessage(Utils.msg("<gray>Found:"));
		for(Map.Entry<Blessing, Integer> e : tally.entrySet()) {
			Blessing b = e.getKey();
			String level = b.level() < ROMAN.length ? ROMAN[b.level()] : String.valueOf(b.level());
			sender.sendMessage(Utils.msg(" " + colour(b.type()) + name(b.type()) + " " + level + (e.getValue() > 1 ? " <gray>x" + e.getValue() : "")));
		}
		return true;
	}

	private static String name(Utils.BlessingType type) {
		String n = type.name();
		return n.charAt(0) + n.substring(1).toLowerCase();
	}

	private static String colour(Utils.BlessingType type) {
		return switch(type) {
			case POWER -> "<red>";
			case STONE -> "<green>";
			case WISDOM -> "<aqua>";
			case TIME -> "<dark_green>";
			case LIFE -> "<light_purple>";
		};
	}
}
