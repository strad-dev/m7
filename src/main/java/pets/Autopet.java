package pets;

import damage.Difficulty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import plugin.Utils;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Autopet: swap the player's pet when something happens.
 * <p>
 * Rule is <b>(trigger, pet to equip, any number of exception pets)</b>; an exception means "fire UNLESS that pet
 * is out", so a deliberate choice isn't undone by the next trigger. Rules live on the profile ({@code Pets}),
 * edited in {@link AutopetMenu}.
 * <p>
 * <b>No-op outside {@link Difficulty#manualPets()}</b>, checked at each entry point, not at registration, since
 * the mode is a server-wide global {@code /m7practice} changes between runs.
 * <p>
 * Three of four triggers aren't events: each needs one call from the file that owns the moment (see the
 * {@code onXxx} methods). Only Rod Swap is a Bukkit event, handled here.
 */
public final class Autopet implements Listener {

	/**
	 * Autopet chat line; {@code Pets.equip} appends the pet name.
	 * <p>
	 * <b>The "o" is a Greek omicron (U+03BF) on purpose.</b> SkyBlock client mods hide Hypixel's real "Autopet
	 * equipped your ..." line by text match, and ate ours too. Don't "fix" it back.
	 */
	private static final String ANNOUNCEMENT = "<green>Autοpet<gray> equipped your ";

	/**
	 * The four things that can move a pet. Display data lives here, not in {@link AutopetMenu}, so a new trigger is
	 * one constant and shows in the menu for free (same as {@code Difficulty}'s {@code displayName}).
	 */
	public enum Trigger {
		/** Run going live: countdown ends, start door opens. */
		RUN_START("On Run Start", Material.OAK_DOOR),
		/** First hit after a lull. See {@link #onCombatHit}. */
		ENTER_COMBAT("On Enter Combat", Material.IRON_SWORD),
		/** Maxor's phase starting. */
		MAXOR_SPAWN("When Maxor Spawns", Material.WITHER_SKELETON_SKULL),
		/**
		 * Throwing a fishing rod. Holds an ordered CYCLE, not one pet; each throw steps to the next. See
		 * {@link #advanceRodCycle}.
		 */
		ROD_SWAP("Rod Swap", Material.FISHING_ROD);

		private final String displayName;
		private final Material icon;

		Trigger(String displayName, Material icon) {
			this.displayName = displayName;
			this.icon = icon;
		}

		public String displayName() {
			return displayName;
		}

		public Material icon() {
			return icon;
		}

		/** True if it holds a cycle instead of one pet. */
		public boolean isCycle() {
			return this == ROD_SWAP;
		}

		static Trigger parse(String name) {
			if(name == null) return null;
			for(Trigger t : values()) if(t.name().equals(name)) return t;
			return null;
		}
	}

	/**
	 * One rule: what to equip, what not to interrupt.
	 * <p>
	 * Null {@code pet} is "off"; empty {@code exceptions} is "always fire". Exceptions with no pet are kept, so the
	 * two halves can be set in either order without the first vanishing; that's what {@link #isOff()} is for.
	 * <p>
	 * <b>{@code exceptions} is IMMUTABLE, in {@link PetType} declaration order.</b> List, not {@code EnumSet}: a
	 * record component must be immutable for value semantics, and {@code List.copyOf} does it in one call where an
	 * EnumSet needs a defensive copy per read. Normalised in the constructor so lore, picker and saved file all
	 * show the same order without sorting. Duplicates fold out.
	 */
	public record Rule(PetType pet, List<PetType> exceptions) {
		public Rule {
			exceptions = normalise(exceptions);
		}

		/** Nothing set. {@code Pets.setRule} DROPS such a rule instead of storing it. */
		public boolean isOff() {
			return pet == null && exceptions.isEmpty();
		}

		/** Does the pet currently out veto this rule? */
		public boolean excepts(PetType out) {
			return out != null && exceptions.contains(out);
		}

		/** Dedupe into declaration order, null-tolerant. EnumSet iterates by ordinal. */
		private static List<PetType> normalise(List<PetType> pets) {
			if(pets == null || pets.isEmpty()) return List.of();
			EnumSet<PetType> set = EnumSet.noneOf(PetType.class);
			for(PetType t : pets) if(t != null) set.add(t);
			return List.copyOf(set);
		}
	}

	// ==================== the triggers ====================

	/**
	 * Run going live.
	 * <p>
	 * Called from {@code instructions/Server.java} {@code startSection}, right under {@code runStarted = true;}.
	 * Every section's start funnels through there (clear via countdown, boss via load grace), so it's the only
	 * spot meaning "live" for a party at the door AND one warped straight to Goldor.
	 */
	public static void onRunStart() {
		fireForEveryone(Trigger.RUN_START);
	}

	/**
	 * Maxor's phase starting.
	 * <p>
	 * Called from {@code instructions/bosses/maxor/Maxor.java} {@code maxorInstructions}, right under
	 * {@code INSTANCE.start(world, doContinue);}. Boss entity exists by then ({@code WitherLord.start}), and both
	 * the boss chain and {@code /m7practice maxor} go through that facade.
	 */
	public static void onMaxorSpawn() {
		fireForEveryone(Trigger.MAXOR_SPAWN);
	}

	/**
	 * A hit landing; how this class learns a fight started.
	 * <p>
	 * Called from top of {@code damage/CombatState.java} {@code noteHit}, after its null guard. {@code noteHit} is
	 * the "primary hit landed" chokepoint; there's no in-combat flag to hook instead.
	 * <p>
	 * Transition is decided here so the call site is unconditional: a hit more than {@link #COMBAT_LAPSE_TICKS}
	 * after the last is entering combat, sooner is the same fight. Nothing ticks to expire it; the test runs at
	 * the next hit.
	 */
	public static void onCombatHit(Player p) {
		if(p == null || !Difficulty.manualPets()) return;
		int now = Utils.serverTick();
		Integer last = lastHitTick.put(p.getUniqueId(), now);
		if(last != null && now - last <= COMBAT_LAPSE_TICKS) return; // same fight
		fire(p, Trigger.ENTER_COMBAT);
	}

	/**
	 * Ticks without a hit before the next counts as entering combat. 10s: covers walking between mobs in a room,
	 * short enough that the next section is a new fight.
	 */
	private static final int COMBAT_LAPSE_TICKS = 200;

	/** Server tick of each player's last primary hit. Absent = not in combat. */
	private static final Map<UUID, Integer> lastHitTick = new HashMap<>();

	/**
	 * <b>The throw, not the reel.</b> {@code PlayerFishEvent} fires several times per cast; only {@code FISHING}
	 * is the bobber leaving the hand. Any other state would step the cycle twice per throw.
	 * <p>
	 * Gated on the Pitchin' Rod by name, not material: the Flaming Flay is a fishing rod too and its ability throws
	 * a bobber, so every Mage cast would step the cycle.
	 * <p>
	 * First {@code PlayerFishEvent} handler in the plugin.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onFish(PlayerFishEvent e) {
		if(e.getState() != PlayerFishEvent.State.FISHING) return;
		if(!Difficulty.manualPets()) return;
		Player p = e.getPlayer();
		if(!items.util.PitchinRod.INSTANCE.matches(p.getInventory().getItemInMainHand())) return;
		fire(p, Trigger.ROD_SWAP);
	}

	/**
	 * Each player's last position in the Rod Swap cycle. <b>Memory only, never persisted</b>: a session position,
	 * not a preference; a stale one from disk would start a run mid-cycle. Cleared by {@link #reset} and by
	 * {@link #clearRodCursor} on any cycle edit.
	 * <p>
	 * <b>Exists because the cycle can hold a pet twice.</b> [A, B, A, C] with A out: {@code indexOf} always gives
	 * the first A, so it walks A, B, A, B and C is unreachable. Don't simplify back to a lookup.
	 */
	private static final Map<UUID, Integer> rodCursor = new HashMap<>();

	/**
	 * Forget a player's place in the cycle. Called by {@link PetPicker}'s cycle editor on every edit: the old index
	 * points at a different throw, and restarting at the front is the only non-arbitrary answer.
	 */
	public static void clearRodCursor(Player p) {
		if(p != null) rodCursor.remove(p.getUniqueId());
	}

	/**
	 * Step the Rod Swap cycle once and summon what it lands on.
	 * <p>
	 * <b>A pet outside the cycle jumps to the front.</b> It has no position to advance from, so a hand-summoned pet
	 * gets the cycle back in one throw.
	 * <p>
	 * Otherwise steps off {@link #rodCursor}, trusted only while it POINTS at the pet out. Cold start, a hand summon
	 * from {@code /pets} or a resized cycle leave it disagreeing, and then the pet's first occurrence is the best
	 * guess (the old {@code indexOf} behaviour, kept for that case).
	 */
	private static void advanceRodCycle(Player p) {
		List<PetType> cycle = Pets.rodCycle(p);
		if(cycle.isEmpty()) return;
		PetType out = Pets.equipped(p);
		int at;
		if(!cycle.contains(out)) {
			at = 0;
		} else {
			Integer cursor = rodCursor.get(p.getUniqueId());
			int from = cursor != null && cursor < cycle.size() && cycle.get(cursor) == out ? cursor : cycle.indexOf(out);
			at = (from + 1) % cycle.size();
		}
		rodCursor.put(p.getUniqueId(), at);
		// Cursor moves even if the pet doesn't: a cycle can list a pet twice in a row, and Pets.equip is quiet on a
		// no-op, so the throw still costs a place and says nothing.
		Pets.equip(p, cycle.get(at), ANNOUNCEMENT);
	}

	// ==================== firing ====================

	/** Run a trigger for every real player online, for the two whole-run triggers. */
	private static void fireForEveryone(Trigger t) {
		if(!Difficulty.manualPets()) {
			Utils.debug(Utils.DebugType.BOSS, "Autopet " + t.name() + ": skipped, mode is "
					+ Difficulty.current().displayName() + " and autopet is off in Classic");
			return;
		}
		for(Player p : Bukkit.getOnlinePlayers()) fire(p, t);
	}

	/**
	 * Apply a player's rule for a trigger, if any and no exception vetoes it. Spectators skipped: idle m7 players
	 * are spectators, not in the run, and would get a line per boss.
	 */
	private static void fire(Player p, Trigger t) {
		if(!Difficulty.manualPets() || p == null) return;
		// EVERY skip below logs why at BOSS verbosity. Not firing and firing onto the pet already out are both
		// silent in chat, so "autopet did nothing" has five causes; these lines tell them apart.
		if(Utils.isSpectator(p)) {
			Utils.debug(Utils.DebugType.BOSS, p, "Autopet " + t.name() + ": " + p.getName() + " is a spectator");
			return;
		}
		if(t.isCycle()) {
			advanceRodCycle(p);
			return;
		}
		Rule rule = Pets.rule(p, t);
		if(rule == null || rule.pet() == null) {
			Utils.debug(Utils.DebugType.BOSS, p, "Autopet " + t.name() + ": " + p.getName() + " has no rule for it");
			return;
		}
		if(rule.excepts(Pets.equipped(p))) {
			Utils.debug(Utils.DebugType.BOSS, p, "Autopet " + t.name() + ": vetoed for " + p.getName()
					+ " - " + Pets.equipped(p).name() + " is an exception");
			return;
		}
		if(!Pets.equip(p, rule.pet(), ANNOUNCEMENT)) {
			Utils.debug(Utils.DebugType.BOSS, p, "Autopet " + t.name() + ": nothing to do for " + p.getName()
					+ " - " + rule.pet().name() + " was already out");
		}
	}

	/** Forget combat state and rod cycle positions. Both per-session, neither saved. */
	public static void reset() {
		lastHitTick.clear();
		rodCursor.clear();
	}
}
