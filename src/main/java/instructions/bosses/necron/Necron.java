package instructions.bosses.necron;

import instructions.Server;
import instructions.bosses.CustomBossBar;
import instructions.bosses.WitherLord;
import instructions.bosses.witherking.WitherKing;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.*;
import org.bukkit.craftbukkit.entity.CraftWither;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import plugin.BossScheduler;
import plugin.Utils;

import java.util.Collections;
import java.util.Random;

/**
 * Necron. Damage-driven like the other three: {@link #clampDamage} turns HP thresholds into phase changes. Unlike
 * them he is always damageable between events, chasing a player (Maxor-style aggro) until a threshold starts a
 * short immune interlude:
 * <ul>
 *   <li><b>80% HP</b> → frenzy: teleport to the middle, blind players, hold still for {@value #FRENZY_DURATION_TICKS}t.</li>
 *   <li><b>25% HP</b> → fireball attack ({@link #destroyPlatform}) in place for {@value #FIREBALL_DURATION_TICKS}t.</li>
 *   <li><b>5% HP</b>  → frenzy again ({@value #FRENZY_DURATION_TICKS}t).</li>
 *   <li><b>0% HP</b>  → death, then chain to the Wither King after {@value #DEATH_TO_WK_TICKS}t.</li>
 * </ul>
 * Only the fireball attack is modelled: at TAS DPS no other ability window (skull barrages, beams, swords,
 * lightning) ever triggers.
 */
public final class Necron extends WitherLord {
	public static final Necron INSTANCE = new Necron();

	private static final Random random = new Random();
	private static final String[] FRENZY_START_MESSAGES = {"Sometimes when you have a problem, you just need to destroy it all and start again.", "WITNESS MY RAW NUCLEAR POWER!"};
	private static final String[] FRENZY_END_MESSAGES = {"ARGH!", "Let's make some space!"};

	// Aggro: same as Maxor's chase.
	private static final double AGGRO_STOP_DISTANCE = 3.0;
	private static final double AGGRO_Y_OFFSET = 1.0;
	private static final double AGGRO_MAX_SPEED = 0.5;

	// Fractions of max HP, consumed in order.
	private static final double[] THRESHOLD_FRACTIONS = {0.80, 0.25, 0.05};

	private static final int AGGRO_START_TICK = 80;
	private static final int INTRO_END_TICK = 100;       // damageability begins here
	private static final int FRENZY_DURATION_TICKS = 80;
	private static final int FIREBALL_DURATION_TICKS = 60;
	private static final int DEATH_TO_WK_TICKS = 60;

	// Frenzy snap point (his spawn).
	private static final double MIDDLE_X = 54.5, MIDDLE_Y = 66, MIDDLE_Z = 76.5;
	private static final float MIDDLE_YAW = 0f;
	private static final float MIDDLE_PITCH = 0f;

	// Platform top-center blocks. All AIR = already destroyed; guards the intro destroy.
	private static final int PLATFORM_Y = 63;
	private static final int PLATFORM_X1 = 53, PLATFORM_X2 = 55;
	private static final int PLATFORM_Z1 = 113, PLATFORM_Z2 = 115;

	private int eventsDone;          // 0 → 80% pending, 1 → 25%, 2 → 5%, 3 → death only
	private boolean inInterlude;     // frenzy or fireball; damage rejected
	private boolean damageable;      // false during intro and interludes
	// Boss-lane task so damageability returns at the START of its tick; a beam that tick isn't a tick late.
	private Runnable interludeEndTask;

	private Runnable barTicker;
	// So the bar counts down the same clock interludeEndTask fires on, not its own counter.
	private int interludeEndTick;
	private boolean interludeIsFireball;
	// Lets the damageable stretch clear the bar once instead of broadcasting an empty one every tick.
	private boolean barShown;

	private Necron() {
		register(this);
	}

	public static void necronInstructions(World world, boolean doContinue) {
		INSTANCE.start(world, doContinue);
	}

	@Override protected String name() { return "Necron"; }
	@Override protected String displayName() { return "Necron"; }
	@Override protected Location spawnLocation() { return new Location(world, 54.5, 66, 76.5, 0f, 0f); }
	@Override protected double maxHealth() { return damage.MobStats.NECRON.internalHealth(); }

	@Override
	protected void resetState() {
		cancelInterludeEndTask();
		cancelBarTicker();
		NecronDrag.release();
		if(boss != null) clearAggro();
		CustomBossBar.removeStunIndicator();
		eventsDone = 0;
		inInterlude = false;
		damageable = false;
		interludeEndTick = 0;
		interludeIsFireball = false;
	}

	@Override
	protected void onStart() {
		startBarTicker();


		// Goldor's split ends as Necron spawns (Wither-King practice scoreboard).
		instructions.bosses.WitherActions.recordSplit("Goldor", Utils.runTick());
		// Intro: not damageable. Salvo 20, platform 60, Goodbye + chase 80, damageable 100. The salvo leads the platform
		// by its own 40t.
		int salvoTick = 20;
		sendChatMessage("You went further than any human before, congratulations.");
		Utils.scheduleTask(() -> sendChatMessage("I'm afraid your journey ends now."), 40);
		Utils.scheduleTask(() -> destroyPlatform(true), salvoTick); // guarded by platformIntact
		Utils.scheduleTask(() -> sendChatMessage("Goodbye."), 80);

		// Chase from Goodbye; armour off and damageable at the end of the intro.
		Utils.scheduleTask(() -> setAggro(AGGRO_STOP_DISTANCE, AGGRO_Y_OFFSET, AGGRO_MAX_SPEED), AGGRO_START_TICK);
		Utils.scheduleTask(() -> {
			setArmor(false);
			damageable = true;
			// No ??? indicator here: only after a frenzy (endInterlude).
		}, INTRO_END_TICK);
	}

	@Override
	protected void chainNext(boolean doContinue) {
		if(doContinue) {
			WitherKing.witherKingInstructions(world, false);
		} else {
			instructions.bosses.WitherActions.signalRunComplete(); // no Wither King this practice
		}
	}

	// ---------- Damage / interludes ----------

	/** Thresholds (80% / 25% / 5%) consumed in order: a hit crossing the next is clamped to it and starts that
	 *  interlude; at 0% it kills. Modelled on Storm's. */
	@Override
	public double clampDamage(double incoming) {
		if(boss == null) return incoming;
		if(dying) return 0;
		if(incoming <= 0) return 0;

		if(inInterlude) {
			// Like Goldor on patrol: hits connect but take no health. Blocking suppresses the flash, so send it ourselves.
			Utils.broadcastPacket(new ClientboundHurtAnimationPacket(((CraftWither) boss).getHandle()));
			return 0;
		}
		if(!damageable) return 0; // intro: immune, no feedback

		double currentHp = boss.getHealth();
		double threshold = nextThreshold();

		if(currentHp - incoming <= threshold) {
			if(threshold <= 0.0) {
				// Leave DYING_SLIVER so vanilla doesn't despawn him before the dialogue; shown as "1" via TASDying.
				enterDyingState();
				return Math.max(0, currentHp - DYING_SLIVER);
			}
			triggerInterlude(eventsDone);
			return currentHp - threshold;
		}
		return incoming;
	}

	/** Absolute HP of the next interlude, or 0 (death) once all are consumed. */
	private double nextThreshold() {
		double maxHp = maxHealth();
		if(eventsDone < THRESHOLD_FRACTIONS.length) return maxHp * THRESHOLD_FRACTIONS[eventsDone];
		return 0.0;
	}

	/** idx 0 & 2 → frenzy, 1 → fireball attack. */
	private void triggerInterlude(int idx) {
		inInterlude = true;
		damageable = false;
		eventsDone++;

		clearAggro();
		// Shield stays DOWN so arrows still connect for feedback; clampDamage takes no health.
		setArmor(false);
		CustomBossBar.removeStunIndicator(); // immune now

		int duration;
		if(idx == 1) {
			// 25%: in place, no teleport, no blindness.
			duration = FIREBALL_DURATION_TICKS;
			Utils.timer("<green>Necron fireball attack at " + formatTick(displayTick()));
			destroyPlatform(false); // fireballs only
		} else {
			// 80% and 5%: frenzy.
			duration = FRENZY_DURATION_TICKS;
			moveBossToCenter();
			sendChatMessage(FRENZY_START_MESSAGES[random.nextInt(FRENZY_START_MESSAGES.length)]);
			applyBlindness();
			frenzySounds(duration);
			Utils.timer("<green>Necron frenzy at " + formatTick(displayTick()));
		}

		cancelInterludeEndTask();
		interludeEndTask = BossScheduler.schedule(() -> endInterlude(idx), duration);

		// Re-render: this runs from the damage path, after the HUD ticker drew this tick's bar from the old state.
		interludeEndTick = displayTick() + duration;
		interludeIsFireball = idx == 1;
		updateActionBar();
	}

	private void endInterlude(int idx) {
		if(dying || boss == null || !boss.isValid()) return;
		inInterlude = false;
		damageable = true;
		setArmor(false);
		if(idx != 1) {
			// ??? indicator and frenzy-end line only after a frenzy, never the fireball attack or intro.
			CustomBossBar.spawnAnimatedStunnedIndicator(boss, Integer.MAX_VALUE);
			sendChatMessage(FRENZY_END_MESSAGES[random.nextInt(FRENZY_END_MESSAGES.length)]);
		}
		// After the FIRST frenzy he stays planted at the middle, so the 25% fireball attack finds him in place.
		if(idx != 0) setAggro(AGGRO_STOP_DISTANCE, AGGRO_Y_OFFSET, AGGRO_MAX_SPEED);

		// Clear the counter now: the HUD ticker already drew this tick's bar (0t) before this ran.
		updateActionBar();
	}

	private void cancelInterludeEndTask() {
		if(interludeEndTask != null) BossScheduler.removeTicker(interludeEndTask);
		interludeEndTask = null;
	}

	// ---------- Action-bar tick timers ----------

	/**
	 * Per-tick HUD, same slot as Storm's and Maxor's. One segment, each counting down an IMMUNE window:
	 * <ul>
	 *   <li><b>Damageable In</b>: the intro ({@link #INTRO_END_TICK}t).</li>
	 *   <li><b>Frenzy</b>: 80% and 5% ({@link #FRENZY_DURATION_TICKS}t).</li>
	 *   <li><b>Fireballs</b>: 25% ({@link #FIREBALL_DURATION_TICKS}t).</li>
	 * </ul>
	 * Counts off the tick stamped in {@link #triggerInterlude}, so it can't drift from the task that ends the window.
	 * No clash with {@code ClearManager}'s bar, which skips anyone outside the room grid.
	 */
	private void updateActionBar() {
		int t = displayTick();
		String bar;
		if(inInterlude) {
			int left = Math.max(0, interludeEndTick - t);
			bar = interludeIsFireball ? "<gold>Fireballs <white>" + left + "t" : "<red>Frenzy <white>" + left + "t";
		} else if(!damageable) {
			bar = "<yellow>Damageable In <white>"
					+ Math.max(0, INTRO_END_TICK - t) + "t";
		} else {
			// Nothing to count: clear once rather than every tick.
			if(barShown) {
				barShown = false;
				Utils.broadcastActionBar(Component.empty());
			}
			return;
		}
		barShown = true;
		Utils.broadcastActionBar(Utils.msg(bar));
	}

	private void startBarTicker() {
		cancelBarTicker();
		barTicker = new Runnable() {
			@Override
			public void run() {
				if(boss == null || boss.isDead()) {
					BossScheduler.removeTicker(this);
					barTicker = null;
					barShown = false;
					Utils.broadcastActionBar(Component.empty());
					return;
				}
				updateActionBar();
			}
		};
		BossScheduler.addTicker(barTicker);
	}

	private void cancelBarTicker() {
		if(barTicker != null) {
			BossScheduler.removeTicker(barTicker);
			barTicker = null;
		}
		// Wipe instead of letting the last "Frenzy 1t" sit through its fade-out.
		barShown = false;
		Utils.broadcastActionBar(Component.empty());
	}

	// ---------- Movement (snap to middle for a frenzy) ----------

	private void moveBossToCenter() {
		net.minecraft.world.entity.LivingEntity nms = ((CraftWither) boss).getHandle();
		nms.absSnapTo(Necron.MIDDLE_X, Necron.MIDDLE_Y, Necron.MIDDLE_Z, Necron.MIDDLE_YAW, Necron.MIDDLE_PITCH);
		nms.setYHeadRot(Necron.MIDDLE_YAW); // undo aggro look-control so he faces forward
		nms.setDeltaMovement(Vec3.ZERO);
		nms.hurtMarked = true;
		PositionMoveRotation pmr = new PositionMoveRotation(new Vec3(Necron.MIDDLE_X, Necron.MIDDLE_Y, Necron.MIDDLE_Z), Vec3.ZERO, Necron.MIDDLE_YAW, Necron.MIDDLE_PITCH);
		ClientboundTeleportEntityPacket pkt = ClientboundTeleportEntityPacket.teleport(nms.getId(), pmr, Collections.emptySet(), nms.onGround());
		Utils.broadcastPacket(pkt);
	}

	// ---------- Frenzy effects ----------

	/** Spectating viewers are skipped so the spectated view isn't disrupted. */
	private void applyBlindness() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 0, false, false)); // 1 second
		}
	}

	private void frenzySounds(int duration) {
		for(int t = 0; t < duration; t += 20) {
			Utils.scheduleTask(() -> {
				Utils.playGlobalSound(Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.0f);
				Utils.playGlobalSound(Sound.ENTITY_WITHER_AMBIENT, 2.0f, 0.67f);
			}, t);
		}
	}

	// ---------- Platform destroy / fireball attack ----------

	/** Fireball salvo. Only the intro one ({@code allowDestroy}) swaps in the destroyed platform, and only if it's
	*  still intact on the destroy tick; one the players already broke open is left alone. */
	private void destroyPlatform(boolean allowDestroy) {
		// He's stationary for both: the intro fires from spawn, the 25% one from the middle where the first frenzy
		// planted him, so no chase momentum to cancel.
		shootFireball();
		Utils.scheduleTask(this::shootFireball, 10);
		Utils.scheduleTask(this::shootFireball, 20);
		Utils.scheduleTask(this::shootFireball, 30);
		Utils.scheduleTask(() -> {
			shootFireball();
			// Checked on the destroy tick so earlier breaks are honoured. Destroyed variant at y -10..-6, intact at -5..-1.
			if(allowDestroy && platformIntact()) Utils.runCommand("clone 70 -10 120 38 -6 99 38 59 99");
		}, 40);
		Utils.scheduleTask(this::shootFireball, 50);
		Utils.scheduleTask(this::shootFireball, 60);
		Utils.scheduleTask(this::shootFireball, 70);
	}

	/** False once every top-center block (53..55, 63, 113..115) is AIR. */
	private boolean platformIntact() {
		for(int x = PLATFORM_X1; x <= PLATFORM_X2; x++) {
			for(int z = PLATFORM_Z1; z <= PLATFORM_Z2; z++) {
				if(world.getBlockAt(x, PLATFORM_Y, z).getType() != Material.AIR) return true;
			}
		}
		return false;
	}

	private void shootFireball() {
		Fireball fireball = (Fireball) world.spawnEntity(boss.getLocation().add(0, 3, 0), EntityType.FIREBALL);
		fireball.setVelocity(new Vector(0, -0.25, 1.25));
		Utils.scheduleTask(fireball::remove, 21);
		Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_IMPACT);
		Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
	}

	// ---------- Death ----------

	private void enterDyingState() {
		dying = true;
		boss.addScoreboardTag("TASDying");
		cancelInterludeEndTask();
		cancelBarTicker();
		inInterlude = false;
		damageable = false;
		clearAggro();
		setArmor(false);
		CustomBossBar.removeStunIndicator();
		Utils.scheduleTask(() -> {
			if(boss != null && boss.isValid()) {
				try { boss.setHealth(DYING_SLIVER); } catch (IllegalArgumentException ignored) {}
				Utils.changeName(boss);
			}
		}, 1);
		playDeathDialogue();
	}

	private void playDeathDialogue() {
		final int deathTick = displayTick(); // t=0 of the death sequence
		// Handoff tick; the delays below are measured from it.
		final int toWitherKing = DEATH_TO_WK_TICKS;
		sendChatMessage("All this, for nothing...");
		Server.playWitherDeathSound(boss);
		Utils.timer("<green>Necron killed in " + formatTick(displayTick()));
		// Wall to Wither King's arena opens 100t after the handoff (restored on next /reset).
		Utils.scheduleTask(instructions.bosses.BossTransition::openNecronToWitherKing, toWitherKing + 100);
		Utils.scheduleTask(() -> sendChatMessage("I understand your words now, my master."), 40);
		// note: In most mods, the Necron timer ends 2 seconds too early, making Wither King start 2 seconds too early.
		// This TAS fixes that. To compare to those timers, subtract 2 seconds here and add 2 seconds to Wither King time.
		Utils.scheduleTask(() -> {
			Utils.timer("<green>Necron finished in " + formatTick(displayTick()));
			// Leaderboard duration at the phase's real end, not the killing blow. Before chainNext, which re-anchors the phase clock.
			instructions.bosses.WitherActions.recordPhaseDuration("Necron", displayTick());
			if(tickerTask != null && !tickerTask.isCancelled()) tickerTask.cancel();
			chainNext(doContinue);
		}, toWitherKing);
		Utils.scheduleTask(() -> sendChatMessage("The Catacombs... are no more."), 80);

		/*
		 * note: all of the wither partitions are one-ticked in this TAS, matching DPS achieved in normal f7
		 * thus, there are no timesaves available in normal f7 VS master mode m7
		 */
		// Normal F7 completes toWitherKing + 40 after the final blow.
		final int normalF7Overall = Utils.runTick() + toWitherKing + 40;
		Utils.scheduleTask(() -> {
			double secs = normalF7Overall / 20.0;
			int mins = (int) (secs / 60);
			double rem = secs - mins * 60.0;
			Bukkit.broadcast(Utils.msg("<gold>Normal Floor 7 Finishes Here in " + formatWithSpaces(normalF7Overall)
					+ " ticks (" + String.format("%.2f", secs) + " seconds | " + mins + ":" + String.format("%05.2f", rem) + ")"));
		}, 100);
	}
}
