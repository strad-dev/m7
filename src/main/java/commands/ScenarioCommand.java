package commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import plugin.Utils;
import scenario.Scenarios;

import java.util.ArrayList;
import java.util.List;

/**
 * /m7scenario <type> [ticks] [requirements...] [--ticks <n>]: countdown in ticks (default 60), either a bare number
 * above 5 straight after the type or flagged anywhere, so it can't be read as a terminal number; requirements per
 * type ({@link Scenarios.Type#args()}). Takes /m7practice's dungeon-setting args ({@link RunSettings}), else the
 * current settings stand. Ended by {@code /m7practice end}.
 */
public class ScenarioCommand implements CommandExecutor {
	public static final int DEFAULT_COUNTDOWN = 60;
	public static final String TICKS_FLAG = "--ticks";

	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
		if(!(sender instanceof Player p)) {
			sender.sendMessage(Utils.msg("Only players can run this"));
			return true;
		}
		List<String> ids = new ArrayList<>();
		for(Scenarios.Type t : Scenarios.TYPES) ids.add(t.id());
		if(args.length == 0) {
			p.sendMessage(Utils.msg("<red>Usage: /m7scenario <type> [ticks] [requirements] [--ticks <n>]  <gray>Types: <list>",
					Placeholder.unparsed("type", "<type>"), Placeholder.unparsed("n", "<n>"),
					Placeholder.unparsed("list", String.join(" ", ids))));
			return true;
		}
		Scenarios.Type type = Scenarios.byId(args[0]);
		if(type == null) {
			p.sendMessage(Utils.msg("<red>Unknown scenario.  <gray>Types: <list>", Placeholder.unparsed("list", String.join(" ", ids))));
			return true;
		}

		int countdown = DEFAULT_COUNTDOWN;
		List<String> reqs = new ArrayList<>();
		int first = 1;
		if(isBareTicks(args, 1)) {
			countdown = Integer.parseInt(args[1]);
			first = 2;
		}
		RunSettings settings = new RunSettings();
		for(int i = first; i < args.length; i++) {
			if(settings.accept(args[i])) continue;
			if(!args[i].equalsIgnoreCase(TICKS_FLAG)) {
				reqs.add(args[i].toLowerCase());
				continue;
			}
			if(i + 1 >= args.length || !args[i + 1].matches("\\d+")) {
				p.sendMessage(Utils.msg("<red>" + TICKS_FLAG + " needs a number of ticks after it."));
				return true;
			}
			countdown = Integer.parseInt(args[++i]);
		}

		String error = validate(type, reqs);
		if(error != null) {
			p.sendMessage(Utils.msg("<red><e>", Placeholder.unparsed("e", error)));
			return true;
		}
		if(type.realisticOnly() && settings.effectiveDifficulty() != damage.Difficulty.REALISTIC) {
			p.sendMessage(Utils.msg("<red><id> is Realistic mode only.  <gray>(/dungeonsettings difficulty realistic)",
					Placeholder.unparsed("id", type.id())));
			return true;
		}

		if(!Practice.prepareParticipants(p, type.spawn(p.getWorld()))) return true;
		settings.apply();
		Scenarios.start(p.getWorld(), type, type.args() == Scenarios.Args.NONE ? type.fixed() : reqs, countdown);
		return true;
	}

	/** {@code args[i]} is the unflagged countdown: straight after the type and above 5, the highest terminal number. */
	public static boolean isBareTicks(String[] args, int i) {
		return i == 1 && args.length > 1 && args[1].matches("\\d{1,9}") && Integer.parseInt(args[1]) > 5;
	}

	/** Null if {@code reqs} suit {@code type}, else what's wrong. */
	private static String validate(Scenarios.Type type, List<String> reqs) {
		String options = String.join(" ", type.options());
		switch(type.args()) {
			case NONE -> {
				if(!reqs.isEmpty()) return type.id() + " takes no requirements.";
			}
			case ONE -> {
				if(reqs.size() != 1 || !type.options().contains(reqs.getFirst())) return type.id() + " needs exactly one of: " + options;
			}
			case ANY -> {
				if(reqs.isEmpty()) return type.id() + " needs at least one of: " + options;
				int earlyEnters = 0;
				for(int i = 0; i < reqs.size(); i++) {
					String r = reqs.get(i);
					if(!type.options().contains(r)) return "Unknown requirement " + r + " for " + type.id() + ".  Options: " + options;
					if(reqs.indexOf(r) != i) return "Requirement " + r + " is listed twice.";
					if(Scenarios.isEarlyEnter(r)) earlyEnters++;
				}
				if(earlyEnters > 1) return "Only one early enter at a time.";
			}
		}
		return null;
	}
}
