package damage;

import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Damage-over-time procs (MAP.md §7). Each is a separate instance on its own timer: never fold into
 * {@code sumAdditive}, and their instances must not re-trigger themselves.
 *
 * <table>
 *   <caption>The procs</caption>
 *   <tr><th>Source</th><th>Behaviour</th></tr>
 *   <tr><td>Fire Aspect III</td><td>9% of the hit at ticks 0, 20, 40, 60, 80: five procs over 80t.</td></tr>
 *   <tr><td>Flame I (every bow)</td><td>6% of the arrow at ticks 0, 20, 40, 60, 80. Bow, so not from
 *       {@link #onHit}: {@code Arrows.hit} calls {@link #flame}.</td></tr>
 *   <tr><td>Thunderlord VII</td><td>Every third hit ON THE SAME MOB, 60% of that hit. Not a DoT: fires once on the
 *       hit, state is only the per-target counter.</td></tr>
 *   <tr><td>Venomous VII</td><td>Each hit adds a stack worth 1.5% of DPS, cap 40 (60% of DPS), every 20t for 100t.
 *       DPS = 8x the highest hit in the last 100 ticks, so a capped proc is 480% of it. Stop hitting and the stacks
 *       are gone.</td></tr>
 * </table>
 *
 * <h2>Refresh</h2>
 * An effect has a cadence ({@code nextTick}, every 20t) and an end time ({@code expiresAt}). A re-hit moves the END
 * TIME only, to {@code now + duration}, and updates the per-tick damage. No damage on the spot, and the 20t phase
 * never moves.
 * <p>
 * Both used to be wrong, together the largest damage bug in the system:
 * <ul>
 *   <li>{@code onHit} dealt an immediate Fire and Venomous instance on EVERY primary hit. A beam lands every 5-7
 *       ticks, so "5 procs over 80t" fired 3-4 times a second on top of its chain; with Venomous the biggest number
 *       in the system, total damage roughly tripled.</li>
 *   <li>Refresh restored a {@code procsLeft} COUNTER instead of extending an end time, i.e. a cooldown reset: the
 *       effect never ended while anyone hit and its phase drifted every proc.</li>
 * </ul>
 * That shared shape is why Fire Aspect and Venomous share one abstraction.
 *
 * <h2>One effect per (ATTACKER, target, kind)</h2>
 * Two players on one mob each run their own Fire and Venomous, own cadence and end time, never refreshing each
 * other's. Inputs are the attacker's too: 9% of THEIR hit, THEIR DPS and stack ramp ({@link CombatState#venomousDps}
 * / {@link CombatState#noteVenomousHit}, keyed on the player).
 * <p>
 * Identity is the attacker's UUID, not the {@link Player}: Bukkit entity equality is entity-ID, which a relog
 * changes, so a reconnected player would start a duplicate beside their own. The effect drops once the UUID lookup
 * comes back empty.
 * <p>
 * Fire Aspect, Thunderlord and Venomous are sword-only (§7); Flame is the one bow proc.
 */
public final class Procs {
	private Procs() {}

	private static final double FIRE_ASPECT_SHARE = 0.09;
	private static final int FIRE_ASPECT_INTERVAL = 20;
	/** Procs at 0, 20, 40, 60, 80; ends 80t after the applying hit. */
	private static final int FIRE_ASPECT_DURATION = 80;

	private static final double FLAME_SHARE = 0.06;
	private static final int FLAME_INTERVAL = 20;
	/** Same shape as Fire Aspect: 0, 20, 40, 60, 80. */
	private static final int FLAME_DURATION = 80;

	private static final double THUNDERLORD_SHARE = 0.60;
	private static final int THUNDERLORD_EVERY = 3;

	private static final double VENOMOUS_PER_STACK = 0.015;
	private static final int VENOMOUS_INTERVAL = 20;
	/** "Every 20t for 100t": procs at 0, 20, 40, 60, 80, 100. */
	private static final int VENOMOUS_DURATION = 100;

	/**
	 * {@code nextTick} is the cadence, set once and only advanced by {@code interval}. {@code expiresAt} is the end
	 * time, the ONLY field a re-hit moves (with {@code amount}). No remaining-procs counter on purpose: a count is a
	 * cooldown in disguise, and restoring it every hit made these unbounded.
	 */
	private static final class Effect {
		final UUID targetId;
		final LivingEntity target;
		/** Part of identity, so each attacker gets their own chain on one mob. */
		final UUID attackerId;
		final DamageKind kind;
		final int interval;
		double amount;
		int nextTick;
		int expiresAt;

		Effect(LivingEntity target, Player attacker, DamageKind kind, double amount, int interval, int duration) {
			this.targetId = target.getUniqueId();
			this.target = target;
			this.attackerId = attacker.getUniqueId();
			this.kind = kind;
			this.interval = interval;
			this.amount = amount;
			this.nextTick = MinecraftServer.currentTick + interval;
			this.expiresAt = MinecraftServer.currentTick + duration;
		}
	}

	private static final List<Effect> EFFECTS = new ArrayList<>();
	/** Thunderlord melee hits, keyed attacker then target: hitting another mob neither advances nor resets this one. */
	private static final java.util.Map<UUID, java.util.Map<UUID, Integer>> thunderlordCount = new java.util.HashMap<>();

	public static void reset() {
		EFFECTS.clear();
		thunderlordCount.clear();
	}

	/** One repeating task for every effect, not one per proc. */
	public static void start() {
		Bukkit.getScheduler().runTaskTimer(plugin.M7.getInstance(), Procs::tick, 1L, 1L);
	}

	/**
	 * Every PRIMARY hit. Thunderlord fires when due; Fire Aspect and Venomous apply or refresh. Nothing here deals
	 * Fire or Venomous directly: {@link #apply} deals tick 0 only on a FRESH application (see class doc).
	 */
	public static void onHit(Player attacker, LivingEntity target, double sbDamage, DamagePath path) {
		if(attacker == null || target == null || sbDamage <= 0) return;
		if(!path.isMelee()) return;                                  // sword-only

		// Thunderlord VII: every third hit ON THE SAME MOB, 60% of that hit. A per-player counter was wrong: three
		// hits over three mobs procced, and clipping another mob wiped progress.
		int hits = thunderlordCount
				.computeIfAbsent(attacker.getUniqueId(), k -> new java.util.HashMap<>())
				.merge(target.getUniqueId(), 1, Integer::sum);
		if(hits % THUNDERLORD_EVERY == 0) {
			Damage.dealSecondary(target, sbDamage * THUNDERLORD_SHARE, DamageKind.THUNDERLORD, attacker);
		}

		// Fire Aspect III: 9%, five procs over 80t. Duplex's debuff is FIRE only, so it scales these, not the hit.
		double fire = sbDamage * FIRE_ASPECT_SHARE * TargetDebuffs.fireMultiplier(target);
		apply(target, attacker, DamageKind.FIRE, fire, FIRE_ASPECT_INTERVAL, FIRE_ASPECT_DURATION);

		venomous(attacker, target);
	}

	/** Flame I off an arrow that landed. Duplex scales it like Fire Aspect: both are FIRE. */
	public static void flame(Player attacker, LivingEntity target, double sbDamage) {
		if(attacker == null || target == null || sbDamage <= 0) return;
		double flame = sbDamage * FLAME_SHARE * TargetDebuffs.fireMultiplier(target);
		apply(target, attacker, DamageKind.FLAME, flame, FLAME_INTERVAL, FLAME_DURATION);
	}

	/**
	 * Venomous alone, for a beam on an ARMOURED wither: the stack and the poison window build, and its ticks deal
	 * nothing until the armour drops ({@code Damage.deal} refuses them).
	 */
	public static void buildVenomous(Player attacker, LivingEntity target) {
		if(attacker == null || target == null) return;
		venomous(attacker, target);
	}

	private static void venomous(Player attacker, LivingEntity target) {
		// Venomous VII: a stack per hit, 1.5% of DPS (8x highest hit in 100t), cap 40 = 480% of that hit, every 20t
		// for 100t. §7's "(80% of the hit)" is 8x smaller than its own DPS definition; owner ruled DPS operative, so
		// this is the largest proc by design, which is why it must run on its own 20t cadence, never per swing.
		// Ramp is bounded by the window too: cleared when this player's poison lapses (see tick).
		int stacks = CombatState.noteVenomousHit(attacker, target.getUniqueId());
		// Applied even at 0 (an armoured wither and no recent hit): the window is what resets the ramp.
		double venom = CombatState.venomousDps(attacker) * VENOMOUS_PER_STACK * stacks;
		apply(target, attacker, DamageKind.VENOMOUS, venom, VENOMOUS_INTERVAL, VENOMOUS_DURATION);
	}

	/**
	 * Apply, or refresh the running (attacker, target, kind) effect. Fresh deals tick 0 now and starts the cadence.
	 * Refresh updates damage and end time only; no damage, {@code nextTick} untouched. Matching on the attacker makes
	 * a second player's hit a fresh application of their own.
	 */
	private static void apply(LivingEntity target, Player attacker, DamageKind kind, double amount, int interval,
			int duration) {
		int now = MinecraftServer.currentTick;
		UUID targetId = target.getUniqueId();
		UUID attackerId = attacker.getUniqueId();
		for(Effect e : EFFECTS) {
			if(e.kind == kind && e.targetId.equals(targetId) && e.attackerId.equals(attackerId)) {
				e.amount = amount;
				e.expiresAt = now + duration;
				return;
			}
		}
		EFFECTS.add(new Effect(target, attacker, kind, amount, interval, duration));
		Damage.dealSecondary(target, amount, kind, attacker);
	}

	private static void tick() {
		if(EFFECTS.isEmpty()) return;
		int now = MinecraftServer.currentTick;
		for(Iterator<Effect> it = EFFECTS.iterator(); it.hasNext(); ) {
			Effect e = it.next();
			boolean dead = e.target.isDead() || e.target.getHealth() <= 0;
			// Dropping a dead mob's counters here keeps the per-target maps bounded; every melee hit applies Fire
			// Aspect, so every counted target passes through this loop. Thunderlord's count expires only with the
			// mob; the Venomous ramp with either (below).
			if(dead) forgetTarget(e.targetId);
			// From the UUID so a relog can't strand the effect or start a second chain.
			Player attacker = Bukkit.getPlayer(e.attackerId);
			// Strict >, so a proc exactly ON the end tick still lands.
			if(dead || attacker == null || now > e.expiresAt) {
				// Ramp dies with this player's poison; other players' ramps on the mob keep running. Double
				// removal after forgetTarget is a no-op.
				if(e.kind == DamageKind.VENOMOUS) CombatState.resetVenomous(e.attackerId, e.targetId);
				it.remove();
				continue;
			}
			if(now < e.nextTick) continue;
			Damage.dealSecondary(e.target, e.amount, e.kind, attacker);
			// Advance from the SCHEDULED tick so the phase can't drift. If the driver ran late, missed procs are
			// dropped, not burst-fired.
			e.nextTick += e.interval;
			if(e.nextTick <= now) e.nextTick = now + e.interval;
		}
	}

	/** Dead target: drop every attacker's Thunderlord, Venomous ramp and Tarantula count on it. */
	private static void forgetTarget(UUID targetId) {
		for(java.util.Map<UUID, Integer> perTarget : thunderlordCount.values()) perTarget.remove(targetId);
		CombatState.forgetTarget(targetId);
	}
}
