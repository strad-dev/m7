package instructions.bosses;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Wither;
import org.bukkit.scheduler.BukkitTask;
import plugin.M7;
import plugin.Utils;

import java.util.List;

/**
 * Base for Maxor, Storm, Goldor, Necron. Not WitherKing: its 5-HP scale, MAGIC name, dragon-driven HP and
 * single-arg constructor don't fit. Each subclass is an {@code INSTANCE} singleton reused across fights;
 * {@link #start(World, boolean)} clears per-fight state via {@link #resetState()}.
 */
@SuppressWarnings("DataFlowIssue")
public abstract class WitherLord {
	protected Wither boss;
	protected World world;
	protected int tick;
	/**
	 * Health a killing blow leaves, one {@code HP_STEP}; the dying state pins HP here too. Must stay above zero or
	 * vanilla despawns the boss before the death dialogue ends. Only has to be non-zero: Maxor/Storm/Necron used 1% of
	 * max (8-14M), so the bar sat at "14M" on a dead boss.
	 */
	public static final double DYING_SLIVER = 0.001;

	protected BukkitTask tickerTask;
	protected boolean dying;
	protected boolean doContinue;

	/** Fresh fight: clears the previous one, spawns the boss, then {@link #onStart()}. */
	public final void start(World w, boolean doContinue) {
		this.world = w;
		this.doContinue = doContinue;

		// Class-ability cooldowns reset on entering a boss fight.
		listeners.CustomItems.resetClassAbilityCooldowns();

		if(boss != null) {
			boss.remove();
			boss = null;
		}
		if(tickerTask != null && !tickerTask.isCancelled()) {
			tickerTask.cancel();
			tickerTask = null;
		}
		resetState();

		this.tick = 0;
		Utils.markPhaseStart();
		this.dying = false;

		spawn();
		Utils.scheduleTask(() -> CustomBossBar.setupWitherBossBar(boss, displayName()), 1);

		onStart();
		startTicker();
	}

	/**
	 * The cleanup {@link #start} does up front, run now so the gap between runs is inactive. Callers:
	 * {@code Server.serverInstructions} (a still-active phase rejected the new run's pre-fired sharpshooter arrows as
	 * "device already activated") and {@code Practice.endPractice} (nothing else calls {@code resetState}, so an early end
	 * left Goldor's gates up and every boss's flags set).
	 */
	public final void forceEndPhase() {
		if(boss != null) {
			boss.remove();
			boss = null;
		}
		if(tickerTask != null && !tickerTask.isCancelled()) {
			tickerTask.cancel();
			tickerTask = null;
		}
		resetState();
	}

	private void spawn() {
		boss = (Wither) world.spawnEntity(spawnLocation(), EntityType.WITHER);
		boss.setAI(false);
		boss.setSilent(true);
		boss.setPersistent(true);
		boss.setRemoveWhenFarAway(false);
		// Formatted from maxHealth(), never hardcoded: Derpy doubles boss HP, so "800M" would show Maxor at half.
		boss.customName(Utils.msg("<gold><bold>﴾ <red>" + displayName() + "<gold> ﴿ </bold><yellow>"
				+ Utils.formatHealthM(maxHealth()) + "<red>❤"));
		boss.setCustomNameVisible(true);
		boss.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHealth());
		// minecraft:armor stays 0 on every mob (MAP.md §5): damage.Damage applies SkyBlock defense and writes health
		// directly, so vanilla reduction never runs. It used to be -30/-20 to claw back vanilla's cut.
		boss.getAttribute(Attribute.ARMOR).setBaseValue(0);
		boss.getAttribute(Attribute.ARMOR_TOUGHNESS).setBaseValue(0);
		boss.setHealth(maxHealth());
		boss.addScoreboardTag("TASWither");
		boss.addScoreboardTag("TAS" + name());
		boss.removeScoreboardTag("TASDying");
		WitherActions.setWitherArmor(boss, true);
	}

	private void startTicker() {
		tickerTask = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), () -> tick++, 0L, 1L);
	}

	/**
	 * Ordering-independent phase tick, DISPLAY ONLY. {@link #tick} is bumped in the scheduler heartbeat, so scheduled
	 * actions read it +1 while entity events (sharpshooter arrow hit) read it un-incremented. This uses the server
	 * tick minus phase start, constant across the tick, so both read D. {@link #tick} stays as is for behaviour
	 * checks (Goldor patrol slow window, Storm crush poll).
	 */
	protected final int displayTick() {
		return Utils.phaseTick();
	}

	// --- Subclass hooks ---

	/** Unformatted; scoreboard tag is "TAS" + name(). */
	protected abstract String name();

	/** Custom name, boss bar, chat. Same as name() for all four subclasses. */
	protected abstract String displayName();

	protected abstract Location spawnLocation();

	/** Internal HP, the {@code MobStats} {@code internalHealth()}. {@link #spawn()} formats the display HP from it. */
	protected abstract double maxHealth();

	/**
	 * Clamps one hit in Minecraft health; returns what's allowed, 0 = blocked. {@code damage.Damage.deal} calls it
	 * explicitly since no {@code EntityDamageEvent} fires for our damage (MAP.md §7); that also makes clamps cover
	 * every source, not just arrows-on-withers like the old MiscListener hooks. Implementations own side effects
	 * (dying state, enrage out of stun, consuming a threshold).
	 */
	public double clampDamage(double incoming) {
		return incoming;
	}

	// showsUnclampedDamage() is gone: the display always reports the unclamped figure (damage/Damage.deal). A clamp
	// decides how much health moves, not what the player hit for.

	/** Dialogue, movement, mob spawns. */
	protected abstract void onStart();

	/** Each subclass decides its own doContinue semantics. */
	protected abstract void chainNext(boolean doContinue);

	/** Clears per-fight flags, counters, tasks, collections. Called at the start of every {@link #start}. */
	protected abstract void resetState();

	// --- Shared helpers ---

	protected final void sendChatMessage(String message) {
		Bukkit.broadcast(Utils.msg("<dark_red>[BOSS] " + displayName() + "<red>: " + message));
		Utils.playGlobalSound(Sound.ENTITY_WITHER_AMBIENT);
	}

	protected final String formatTick(int t) {
		int overall = Utils.runTick();
		return "<green>" + String.format("%s ticks (%.2f seconds) | Overall: %s ticks (%.2f seconds)",
				formatWithSpaces(t), t / 20.0, formatWithSpaces(overall), overall / 20.0);
	}

	protected static String formatWithSpaces(int n) {
		StringBuilder sb = new StringBuilder();
		String s = String.valueOf(n);
		for(int i = 0; i < s.length(); i++) {
			if(i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
			sb.append(s.charAt(i));
		}
		return sb.toString();
	}

	protected final void setArmor(boolean on) {
		WitherActions.setWitherArmor(boss, on);
	}

	protected final void setAggro(double stopDistance, double yOffset, double maxSpeed) {
		WitherActions.setWitherAggro(boss, stopDistance, yOffset, maxSpeed, this::clampRise);
	}

	/** Aggro's upward step for hitbox {@code box}; default unlimited. */
	protected double clampRise(org.bukkit.util.BoundingBox box, double vy) {
		return vy;
	}

	protected final void clearAggro() {
		WitherActions.clearWitherAggro(boss);
	}

	// --- Find the WitherLord that owns a wither entity ---

	private static final List<WitherLord> SUBCLASS_INSTANCES = new java.util.ArrayList<>();

	/** For {@link #activeFor(Wither)}. Called from each subclass's static initializer. */
	protected static void register(WitherLord instance) {
		SUBCLASS_INSTANCES.add(instance);
	}

	/** Lord whose spawned boss is {@code w}, or null. */
	public static WitherLord activeFor(Wither w) {
		for(WitherLord lord : SUBCLASS_INSTANCES) {
			if(lord.boss != null && lord.boss.equals(w)) return lord;
		}
		return null;
	}

	/** Null between fights. */
	public final Wither getBoss() {
		return boss;
	}

	public final boolean isDying() {
		return dying;
	}
}
