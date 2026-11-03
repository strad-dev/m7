package scenario;

import instructions.Server;
import instructions.bosses.goldor.Goldor;
import instructions.bosses.maxor.Maxor;
import instructions.bosses.storm.PadAndPillar;
import instructions.bosses.storm.Storm;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import plugin.M7;
import plugin.ScenarioCompleteEvent;
import plugin.Utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /m7scenario}: drills one part of a run, from a mid-run state, until its requirements are met. A scenario is
 * practice mode underneath ({@code Practice.prepareRun}) but reports only as a scenario. At most one at a time.
 */
public final class Scenarios {
	private Scenarios() {}

	/** How a type takes requirement arguments. */
	public enum Args {
		/** None allowed; the type's {@code fixed} ones apply. */
		NONE,
		/** Exactly one of the options. */
		ONE,
		/** Any combination of the options, at least one, at most one early enter. */
		ANY
	}

	/**
	 * @param spawn {x, y, z, yaw, pitch}
	 * @param section Goldor section the scenario is in (0-3), -1 outside Goldor
	 */
	public record Type(String id, String phase, double[] spawn, boolean realisticOnly, Args args, List<String> options,
	                   List<String> fixed, int section) {
		public Location spawn(World world) {
			return new Location(world, spawn[0], spawn[1], spawn[2], (float) spawn[3], (float) spawn[4]);
		}
	}

	private static final List<String> S1 = List.of("1", "2", "3", "4", "dev", "ll", "rl", "lee2", "hee2");
	private static final List<String> S2 = List.of("1", "2", "3", "4", "5", "dev", "tl", "bl", "lee3", "hee3", "core");
	private static final List<String> S3 = List.of("1", "2", "3", "4", "dev", "ll", "rl", "gate");
	private static final List<String> S4 = List.of("1", "2", "3", "4", "bl", "tl", "core");

	public static final List<Type> TYPES = List.of(
			new Type("storm-purple", "storm", new double[]{110.5, 170, 94.5, 180, 0}, false, Args.NONE, List.of(), List.of("crush"), -1),
			new Type("predev-classic", "maxor", new double[]{73.5, 221, 14.5, 0, 0}, true, Args.NONE, List.of(), List.of("lights", "arrows"), -1),
			new Type("predev-new", "storm", new double[]{73.5, 165, 53.5, 0, 0}, true, Args.ONE, List.of("lights", "arrows"), List.of(), -1),
			new Type("i4", "goldor", new double[]{63.5, 127, 35.5, 0, 0}, false, Args.NONE, List.of(), List.of("i4"), -1),
			new Type("s1-ss", "goldor", new double[]{108.3, 120, 94.0, -90, 0}, false, Args.ANY, S1, List.of(), 0),
			new Type("s1-llc", "goldor", new double[]{106.5, 123.0625, 111.7, 0, 0}, false, Args.ANY, S1, List.of(), 0),
			new Type("s2-lee2", "goldor", new double[]{58.0, 109, 131.0, 90, 0}, false, Args.ANY, S2, List.of(), 1),
			new Type("s2-hee2", "goldor", new double[]{60.5, 132, 139.5, 90, 0}, false, Args.ANY, S2, List.of(), 1),
			new Type("s3-lee3", "goldor", new double[]{2.0, 109, 103.5, 180, 0}, false, Args.ANY, S3, List.of(), 2),
			new Type("s3-hee3", "goldor", new double[]{18.5, 121.5, 92.5, 180, 0}, false, Args.ANY, S3, List.of(), 2),
			new Type("s4-core", "goldor", new double[]{54.5, 115.0625, 50.5, 180, 0}, false, Args.ANY, S4, List.of(), 3));

	/** Early enter spots: {x, y, z}. "core" depends on the section. */
	private static final Map<String, double[]> EARLY_ENTERS = Map.of(
			"lee2", new double[]{58.0, 109, 131.0},
			"hee2", new double[]{60.5, 132, 139.5},
			"lee3", new double[]{2.0, 109, 103.5},
			"hee3", new double[]{18.5, 121.5, 92.5});
	private static final double[] S2_CORE = {54.5, 115.0625, 50.5};
	private static final double[] S4_CORE = {54.5, 115, 55.5};

	/** Goldor items, the names {@link Goldor#preComplete} takes. */
	private static final Set<String> ITEMS = Set.of("1", "2", "3", "4", "5", "dev", "ll", "rl", "tl", "bl");

	public static boolean isEarlyEnter(String req) {
		return EARLY_ENTERS.containsKey(req) || req.equals("core");
	}

	public static Type byId(String id) {
		for(Type t : TYPES) if(t.id().equalsIgnoreCase(id)) return t;
		return null;
	}

	// ---------- the running scenario ----------

	private static Type type;
	private static List<String> requirements;
	private static World world;
	private static final Set<String> done = new LinkedHashSet<>();
	/** Early-enter spots reached, latched: the gate can be blown after. */
	private static final Set<String> reached = new LinkedHashSet<>();
	private static int startTick = -1;
	private static boolean finished;
	private static BukkitTask poller;

	/** Set up or running, from {@link #start} until the teardown's {@link #stop}. */
	public static boolean isActive() {
		return type != null;
	}

	/**
	 * Stages {@code t} and counts down {@code countdown} ticks. Participants are already kitted and teleported.
	 * {@code reqs} are validated, {@code t.fixed()} when the type takes none.
	 */
	public static void start(World w, Type t, List<String> reqs, int countdown) {
		commands.Practice.prepareRun(w, t.phase());
		type = t;
		requirements = List.copyOf(reqs);
		world = w;

		stage();
		Goldor.INSTANCE.forceEndPhase();
		pets.Pets.applyStartingPets();
		int ticks = Math.max(1, countdown);
		listeners.OutOfBounds.schedulePreRunReturns(t.spawn(w), ticks);
		Bukkit.broadcast(Utils.msg("<gold>Scenario <white><id></white><gray>: <reqs>",
				Placeholder.unparsed("id", t.id()), Placeholder.unparsed("reqs", String.join(" ", requirements))));
		Server.countdownThenRun(ticks, "Scenario", Scenarios::begin);
	}

	/** World state before the countdown: what the earlier part of the run would have left behind. */
	private static void stage() {
		if(type.id().equals("storm-purple")) {
			// Purple ran 400t with someone on its pad: down to 169, up to 189, then one cycle back down.
			Storm.INSTANCE.skipIntro(400);
			Storm.INSTANCE.presetPillar(PadAndPillar.PURPLE, 186, true);
			return;
		}
		if(!type.phase().equals("goldor")) return;
		// Lights predev, as /m7practice goldor deals it.
		Utils.runCommand("fill 62 136 142 58 133 142 minecraft:lever[face=wall,facing=north,powered=true]");
		Utils.runCommand("fill 58 136 143 62 133 143 minecraft:redstone_lamp[lit=true]");
		if(type.section() >= 0) Goldor.dealArrowPredev(world);
	}

	/** Countdown over. */
	private static void begin() {
		startTick = Utils.serverTick();
		switch(type.phase()) {
			case "maxor" -> Maxor.maxorInstructions(world, false);
			case "storm" -> Storm.stormInstructions(world, false);
			case "goldor" -> {
				int section = Math.max(0, type.section());
				Goldor.goldorInstructions(world, false, section);
				if(type.section() >= 0) {
					// i4 is done: someone did it at the start of S1.
					if(section < 3) Goldor.INSTANCE.preComplete(3, Set.of("1", "2", "3", "4", "bl", "tl"));
					Set<String> keep = new LinkedHashSet<>(requirements);
					keep.retainAll(ITEMS);
					Goldor.INSTANCE.preComplete(section, keep);
				}
			}
		}
		poller = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), Scenarios::poll, 0L, 1L);
	}

	private static void poll() {
		if(finished || type == null) return;
		for(String req : requirements) {
			if(done.contains(req) || !met(req)) continue;
			done.add(req);
			Utils.playGlobalSound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
			Bukkit.broadcast(Utils.msg("<green>✔ <white><req></white> <gray>(" + elapsed() + "t)", Placeholder.unparsed("req", req)));
		}
		if(done.size() == requirements.size()) finish(true, null);
	}

	private static boolean met(String req) {
		int section = type.section();
		if(ITEMS.contains(req)) return Goldor.INSTANCE.isItemDone(section, req);
		return switch(req) {
			case "gate" -> Goldor.INSTANCE.isGateBlown(section);
			case "lee2" -> reach(req) && Goldor.INSTANCE.isGateBlown(0);
			case "hee2" -> reach(req) && Goldor.INSTANCE.isGateBlown(0) && Goldor.INSTANCE.isItemDone(1, "dev");
			case "lee3", "hee3" -> reach(req) && Goldor.INSTANCE.isGateBlown(1);
			case "core" -> reach(req);
			case "lights" -> listeners.GoldorListener.allLightsLit(world);
			case "arrows" -> Goldor.arrowPredevDone(world);
			case "i4" -> Goldor.INSTANCE.isItemDone(3, "dev");
			// Set by onStormCrushed.
			default -> false;
		};
	}

	/** Some live player's hitbox has come within a block of the spot; latched. */
	private static boolean reach(String req) {
		if(reached.contains(req)) return true;
		double[] at = req.equals("core") ? (type.section() == 3 ? S4_CORE : S2_CORE) : EARLY_ENTERS.get(req);
		for(Player p : world.getPlayers()) {
			if(Utils.isSpectator(p) || p.isDead()) continue;
			BoundingBox b = p.getBoundingBox();
			double dx = Math.max(0, Math.max(b.getMinX() - at[0], at[0] - b.getMaxX()));
			double dy = Math.max(0, Math.max(b.getMinY() - at[1], at[1] - b.getMaxY()));
			double dz = Math.max(0, Math.max(b.getMinZ() - at[2], at[2] - b.getMaxZ()));
			if(dx * dx + dy * dy + dz * dz <= 1.0) {
				reached.add(req);
				return true;
			}
		}
		return false;
	}

	private static int elapsed() {
		return startTick < 0 ? 0 : Utils.serverTick() - startTick;
	}

	/** From {@code Storm.triggerCrush}: the purple crush is the requirement, any other pillar fails it. */
	public static void onStormCrushed(PadAndPillar pillar) {
		if(type == null || finished || !requirements.contains("crush")) return;
		if(pillar == PadAndPillar.PURPLE) {
			done.add("crush");
			Bukkit.broadcast(Utils.msg("<green>✔ <white>crush</white> <gray>(" + elapsed() + "t)"));
			finish(true, null);
		} else {
			finish(false, "Storm crushed in " + (pillar == null ? "no" : pillar.name()) + " pillar");
		}
	}

	/** A run ending reached us instead (party wipe, Storm out of pillars, the boss phase over). */
	public static void onRunEnded(boolean success) {
		if(type == null || finished) return;
		finish(false, success ? "boss phase ended" : "run lost");
	}

	/** Torn down before an ending ({@code /m7practice end}, a new run, a new scenario): reported, no chat. */
	public static void onAbandoned() {
		if(type == null || finished) return;
		finished = true;
		report(false, "abandoned");
	}

	/** Report, then end the session next tick: callers are mid-boss-logic, which a teardown here would pull out from under. */
	private static void finish(boolean success, String reason) {
		finished = true;
		int t = elapsed();
		String time = t + " ticks (" + String.format("%.2f", t / 20.0) + " seconds)";
		Bukkit.broadcast(success
				? Utils.msg("<green><bold>Scenario complete</bold> <white><id></white> in " + time, Placeholder.unparsed("id", type.id()))
				: Utils.msg("<red><bold>Scenario failed</bold> <white><id></white>: <why> <gray>(" + time + ")",
						Placeholder.unparsed("id", type.id()), Placeholder.unparsed("why", reason)));
		report(success, reason);
		World w = world;
		Utils.scheduleTask(() -> commands.Practice.endPractice(w), 1);
	}

	private static void report(boolean success, String reason) {
		List<ScenarioCompleteEvent.Member> players = new ArrayList<>();
		for(Player p : world.getPlayers()) {
			if(Utils.isSpectator(p)) continue;
			players.add(new ScenarioCompleteEvent.Member(p.getUniqueId().toString(), p.getName()));
		}
		Bukkit.getPluginManager().callEvent(new ScenarioCompleteEvent(new ScenarioCompleteEvent.Result(type.id(), requirements,
				success, reason, startTick < 0 ? null : elapsed(), damage.Difficulty.current().id(), players)));
	}

	/** Forget the scenario. From {@code Practice.prepareRun} and {@code Practice.endPractice}. */
	public static void stop() {
		// A new run replacing an unfinished scenario still reports it.
		onAbandoned();
		Storm.INSTANCE.clearScenarioSetup();
		if(poller != null) {
			poller.cancel();
			poller = null;
		}
		type = null;
		requirements = null;
		world = null;
		done.clear();
		reached.clear();
		startTick = -1;
		finished = false;
	}
}
