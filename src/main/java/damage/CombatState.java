package damage;

import net.minecraft.server.MinecraftServer;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Per-player and per-(player, target) combat state: repeated-hit stack, post-kill buff, kill combo, Berserk
 * ultimate, Tarantula Ring's per-target tenth-hit counter, and the rolling damage history.
 * <p>
 * The history serves four features with one query at different windows (MAP.md §1.14): Berserk's axe throw (60s),
 * Explosive Shot and Rapid Fire (last minute, arrows) and Venomous's DPS (8x highest hit in 100 ticks).
 * <p>
 * Main-thread only. Cleared at run start by {@link #reset()}.
 */
public final class CombatState {
	private CombatState() {}

	private record Hit(int tick, double damage) {}

	/** 60s, the longest window any consumer asks for. */
	private static final int HISTORY_TICKS = 1200;
	private static final int VENOMOUS_WINDOW_TICKS = 100;
	/** Venomous "DPS" = 8x the biggest hit in its window. */
	private static final double VENOMOUS_DPS_FACTOR = 8.0;

	/** Consecutive hits on one target. Doesn't decay; only switching target resets it (§1.14). */
	private static final Map<UUID, UUID> lastTarget = new HashMap<>();
	private static final Map<UUID, Integer> repeatCount = new HashMap<>();
	/** Tick the post-kill buff expires at. Armed by a kill, spent by the next hit either way. */
	private static final Map<UUID, Integer> postKillExpiry = new HashMap<>();
	/** Kill combo: count, and the tick the chain breaks if no kill lands. */
	private static final Map<UUID, Integer> comboCount = new HashMap<>();
	private static final Map<UUID, Integer> comboExpiry = new HashMap<>();
	private static final Map<UUID, Integer> berserkUltimateEnd = new HashMap<>();
	/**
	 * Melee hits for Tarantula Ring's every-tenth x1.15, keyed attacker then target: per mob, so switching targets
	 * neither advances nor resets the one you left (like Thunderlord). Dropped on death ({@link #forgetTarget}).
	 */
	private static final Map<UUID, Map<UUID, Integer>> meleeHits = new HashMap<>();
	private static final Map<UUID, Deque<Hit>> history = new HashMap<>();
	/**
	 * Venomous ramp (1.5% per hit, 40-hit cap), keyed attacker then target: two players on one mob ramp separately.
	 * With the per-player {@link #history} behind {@link #venomousDps}, both halves of a proc are the attacker's own.
	 * An entry lives exactly as long as its 100t poison window ({@link #resetVenomous}), not the whole run.
	 */
	private static final Map<UUID, Map<UUID, Integer>> venomousHits = new HashMap<>();

	/** Called at run start, alongside the ability-cooldown reset. */
	public static void reset() {
		lastTarget.clear();
		repeatCount.clear();
		postKillExpiry.clear();
		comboCount.clear();
		comboExpiry.clear();
		berserkUltimateEnd.clear();
		meleeHits.clear();
		history.clear();
		venomousHits.clear();
		pets.Autopet.reset(); // autopet "entered combat" edge is derived from hits, so it resets with them
	}

	// ===================== repeated-hit stack (Berserk) =====================

	/** Prior consecutive hits on this target: 0 for the first. Read-only. */
	public static int repeatHits(Player p, UUID target) {
		if(p == null || target == null) return 0;
		return target.equals(lastTarget.get(p.getUniqueId())) ? repeatCount.getOrDefault(p.getUniqueId(), 0) : 0;
	}

	/** Advance the stack for a PRIMARY hit. Only switching target resets it. */
	public static void noteHit(Player p, UUID target, DamagePath path) {
		if(p == null || target == null) return;
		// Autopet "entered combat" trigger. No in-combat flag exists, so this is the one "primary hit landed"
		// chokepoint; Autopet derives the entry edge itself.
		pets.Autopet.onCombatHit(p);
		UUID id = p.getUniqueId();
		if(target.equals(lastTarget.get(id))) {
			repeatCount.merge(id, 1, Integer::sum);
		} else {
			lastTarget.put(id, target);
			repeatCount.put(id, 1);
		}
		if(path.isMelee()) meleeHits.computeIfAbsent(id, k -> new HashMap<>()).merge(target, 1, Integer::sum);
	}

	/** First hit on this target: First Strike's window. */
	public static boolean isFirstHitOn(Player p, UUID target) {
		return repeatHits(p, target) == 0;
	}

	/** First three hits on this target: Triple Strike's window. */
	public static boolean isTripleStrikeHitOn(Player p, UUID target) {
		return repeatHits(p, target) < 3;
	}

	/** True when this melee hit is the tenth ON THIS TARGET: Tarantula Ring's x1.15 (§7). Per mob, not a fight total. */
	public static boolean isTarantulaHit(Player p, UUID target) {
		if(p == null || target == null) return false;
		return (meleeHits.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(target, 0) + 1) % 10 == 0;
	}

	// ===================== kill-driven windows =====================

	/**
	 * Arms TWO independent windows, don't conflate them (§1.14): one-shot post-kill buff (expires 5s unspent) and
	 * the kill combo (resets after 3s between kills).
	 */
	public static void noteKill(Player p) {
		if(p == null) return;
		UUID id = p.getUniqueId();
		int now = MinecraftServer.currentTick;
		postKillExpiry.put(id, now + ClassBonuses.BERSERK_POST_KILL_TICKS);
		if(now > comboExpiry.getOrDefault(id, 0)) comboCount.put(id, 0);
		comboCount.merge(id, 1, Integer::sum);
		comboExpiry.put(id, now + 60); // combo = kills <=3s apart
	}

	public static boolean hasPostKillBuff(Player p) {
		return p != null && MinecraftServer.currentTick < postKillExpiry.getOrDefault(p.getUniqueId(), 0);
	}

	/** The next hit consumes it whether or not it was still live. */
	public static void spendPostKillBuff(Player p) {
		if(p != null) postKillExpiry.remove(p.getUniqueId());
	}

	/** Combo's additive %: +1% per mob killed in the chain, capped at +50% (§7). */
	public static double comboAdditive(Player p) {
		if(p == null) return 0;
		UUID id = p.getUniqueId();
		if(MinecraftServer.currentTick > comboExpiry.getOrDefault(id, 0)) return 0;
		return Math.min(comboCount.getOrDefault(id, 0), 50);
	}

	// ===================== Berserk ultimate =====================

	public static void startBerserkUltimate(Player p) {
		if(p != null) {
			berserkUltimateEnd.put(p.getUniqueId(), MinecraftServer.currentTick + ClassBonuses.BERSERK_ULTIMATE_TICKS);
		}
	}

	public static boolean berserkUltimateActive(Player p) {
		return p != null && MinecraftServer.currentTick < berserkUltimateEnd.getOrDefault(p.getUniqueId(), 0);
	}

	// ===================== rolling damage history =====================

	/**
	 * Record a finished hit. Only real hits (MAP.md §1.14): {@code Damage.deal} keeps out secondary (procs, Cleave)
	 * and DERIVED ones (copied out of this history), either of which would make the buffer feed on its own output.
	 */
	public static void recordDamage(Player p, double sbDamage) {
		if(p == null || sbDamage <= 0) return;
		Deque<Hit> q = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
		q.addLast(new Hit(MinecraftServer.currentTick, sbDamage));
		int cutoff = MinecraftServer.currentTick - HISTORY_TICKS;
		// Bounded by age, and by count so a Terminator volley can't grow it without limit.
		while(!q.isEmpty() && (q.peekFirst().tick() < cutoff || q.size() > 4096)) q.removeFirst();
	}

	/** Largest hit in the last {@code ticks}. Axe throw, Explosive Shot, Rapid Fire use 60s; Venomous 100 ticks. */
	public static double maxInLastTicks(Player p, int ticks) {
		if(p == null) return 0;
		Deque<Hit> q = history.get(p.getUniqueId());
		if(q == null) return 0;
		int cutoff = MinecraftServer.currentTick - ticks;
		double max = 0;
		for(Hit h : q) if(h.tick() >= cutoff) max = Math.max(max, h.damage());
		return max;
	}

	/**
	 * Venomous "DPS": 8x the biggest hit in the last 100 ticks. One stack is 1.5% of it, so 40 stacks = 60%, i.e. 480%
	 * of that hit. Only PRIMARY hits reach the history ({@link Damage}), and that is load-bearing: a Venomous tick is
	 * several times the hit behind it, so recording one would ratchet the figure up every tick.
	 */
	public static double venomousDps(Player p) {
		return maxInLastTicks(p, VENOMOUS_WINDOW_TICKS) * VENOMOUS_DPS_FACTOR;
	}

	/** Venomous ramp on one target: +1.5% per hit, cap 40 (60% of the DPS figure). */
	public static int venomousStacks(Player p, UUID target) {
		if(p == null || target == null) return 0;
		return venomousHits.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(target, 0);
	}

	/** Returns the new stack count, capped at 40. */
	public static int noteVenomousHit(Player p, UUID target) {
		if(p == null || target == null) return 0;
		Map<UUID, Integer> perTarget = venomousHits.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
		int next = Math.min(perTarget.getOrDefault(target, 0) + 1, 40);
		perTarget.put(target, next);
		return next;
	}

	/**
	 * Drop ONE attacker's ramp on one target, from {@code Procs} when their 100t poison lapses. A player who stops
	 * swinging restarts at one; other players' ramps on that mob are untouched.
	 */
	public static void resetVenomous(UUID attacker, UUID target) {
		if(attacker == null || target == null) return;
		Map<UUID, Integer> perTarget = venomousHits.get(attacker);
		if(perTarget != null) perTarget.remove(target);
	}

	/**
	 * Drop EVERY attacker's Venomous ramp and Tarantula count on a dead target, from {@code Procs.forgetTarget}
	 * with the Thunderlord counts. Without it the per-target maps grow all run.
	 */
	public static void forgetTarget(UUID target) {
		if(target == null) return;
		for(Map<UUID, Integer> perTarget : venomousHits.values()) perTarget.remove(target);
		for(Map<UUID, Integer> perTarget : meleeHits.values()) perTarget.remove(target);
	}
}
