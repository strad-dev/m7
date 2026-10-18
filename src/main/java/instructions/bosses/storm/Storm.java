package instructions.bosses.storm;

import instructions.Actions;
import instructions.Server;
import instructions.bosses.CustomBossBar;
import instructions.bosses.MobGroup;
import instructions.bosses.MobSpawnSpec;
import instructions.bosses.WitherLord;
import instructions.bosses.goldor.Goldor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import plugin.BossScheduler;
import plugin.Utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@SuppressWarnings("DataFlowIssue")
public final class Storm extends WitherLord {
	public static final Storm INSTANCE = new Storm();

	// Aggro + crush detection enable here.
	private static final int INTRO_END_TICK = 645;

	// pollCycle runs on phase ticks divisible by this (Hypixel's 20-tick grid).
	private static final int PAD_CYCLE_TICKS = 20;
	// First volley. The "Storm moves in" countdown runs from here to INTRO_END_TICK.
	private static final int LIGHTNING_TICK = 515;

	private static final double CRUSH_DAMAGE_FRACTION = 0.05;
	private static final double STUN_DAMAGE_CAP_FRACTION = 0.55;
	private static final int CRUSH_EXPLOSION_DELAY = 20;
	// Each 0.3-block step through diorite costs ~1.9 ray power, so destruction radius is ~power/6 blocks.
	private static final float CRUSH_EXPLOSION_POWER = 50.0f;
	private static final int CRUSH_DETECTOR_WINDOW = 61;
	// Lowest a descending pillar pushes Storm; at or below it the pillar crushes.
	private static final double STORM_FLOOR_Y = 169.0;
	private static final int STUN_AUTO_ENRAGE_TICKS = 160;

	// Aggro, post-intro and post-enrage.
	private static final double AGGRO_STOP_DISTANCE = 6.0;
	private static final double AGGRO_Y_OFFSET = 2.0;
	private static final double AGGRO_MAX_SPEED = 0.66666;

	// Miners and sentries face this.
	private static final Location FACING_CENTER = new Location(null, 73.5, 0, 53.5);

	// Wither Guards at fixed spots, not randomized.
	private static final double[][] SENTRY_COORDS = {{114.5, 175, 35.5}, {114.5, 175, 45.5}, {114.5, 175, 61.5}, {114.5, 175, 71.5}, {86.5, 175, 35.5}, {86.5, 175, 45.5}, {86.5, 175, 61.5}, {86.5, 175, 71.5}, {60.5, 175, 35.5}, {60.5, 175, 45.5}, {60.5, 175, 61.5}, {60.5, 175, 71.5}, {32.5, 175, 35.5}, {32.5, 175, 45.5}, {32.5, 175, 61.5}, {32.5, 175, 71.5}, {79.5, 170, 104.5}, {77.5, 170, 103.5}, {75.5, 170, 103.5}, {73.5, 170, 103.5}, {71.5, 170, 103.5}, {69.5, 170, 103.5}, {67.5, 170, 104.5}, {22.5, 172, 59.5}, {23.5, 172, 57.5}, {23.5, 172, 55.5}, {23.5, 172, 53.5}, {23.5, 172, 51.5}, {23.5, 172, 49.5}, {22.5, 172, 47.5}, {67.5, 170, 2.5}, {69.5, 170, 3.5}, {71.5, 170, 3.5}, {73.5, 170, 3.5}, {75.5, 170, 3.5}, {77.5, 170, 3.5}, {79.5, 170, 2.5}, {124.5, 172, 47.5}, {123.5, 172, 49.5}, {123.5, 172, 51.5}, {123.5, 172, 53.5}, {123.5, 172, 55.5}, {123.5, 172, 57.5}, {124.5, 172, 59.5}};

	private static final Random random = new Random();
	private static final String[] LIGHTNING_MESSAGE = {"ENERGY HEED MY CALL!", "THUNDER LET ME BE YOUR CATALYST!"};
	private static final String[] CRUSHED_MESSAGE = {"Ouch, that hurt!", "Oof"};
	private static final String[] ENRAGE_MESSAGE = {"THAT WAS ONLY IN MY WAY!", "Slowing me down will be your greatest accomplishment!", "This factory is too small for me!", "BEGONE PILLAR!"};

	private final List<PillarOscillator> pillars = new ArrayList<>();
	private final List<MobGroup> mobGroups = new ArrayList<>();
	private final List<WitherSkeleton> sentries = new ArrayList<>();

	// Boss ticker, so crush detection runs at tick start, before players' beams.
	private Runnable cycleTicker;
	// Boss-lane, fires at the start of its tick.
	private Runnable stunEnrageTask;
	private boolean crushEnabled;
	private boolean inStun;
	// Stamped at the crush so the bar counts down the mechanic's own clock. Like Maxor's.
	private int stunEndTick;
	private double stunDamageDealt;
	// Latched at the 55% cap; clampDamage rejects everything until the next crush. Without it, same-tick arrows after
	// the cap-enrage over-DPS, since enrage flips inStun=false mid-tick.
	private boolean stunCapReached;
	private PadAndPillar currentCrushPillar;
	private boolean crushExplosionActive;
	// Exactly one detonation per crush; lets the death path force a pending one rather than lose the pillar.
	private boolean crushExplosionPending;
	// Captured at the crush, so the blast hits the right column even if Storm dies or moves in the 20t delay.
	private Location pendingCrushLoc;
	// For pollCycle's overdue-detonation safety net.
	private int crushArmedTick;

	private Storm() {
		register(this);
	}

	public static void stormInstructions(World world, boolean doContinue) {
		INSTANCE.start(world, doContinue);
	}

	@Override
	protected String name() {
		return "Storm";
	}

	@Override
	protected String displayName() {
		return "Storm";
	}

	@Override
	protected Location spawnLocation() {
		return new Location(world, 102.5, 182, 53.5, 90f, 0f);
	}

	@Override
	protected double maxHealth() {
		return damage.MobStats.STORM.internalHealth();
	}

	@Override
	protected void resetState() {
		cancelCycleTask();
		cancelStunEnrageTask();
		CustomBossBar.removeStunIndicator();
		crushEnabled = false;
		inStun = false;
		stunEndTick = 0;
		stunDamageDealt = 0;
		stunCapReached = false;
		currentCrushPillar = null;
		// A leftover armed explosion must not detonate into the new run.
		crushExplosionPending = false;
		pendingCrushLoc = null;
		cleanupMobs();
		pillars.clear();
		for(PadAndPillar p : PadAndPillar.ACTIVE) {
			pillars.add(new PillarOscillator(p));
		}
	}

	@Override
	protected void onStart() {
		// Maxor's split ends as Storm spawns (Wither-King practice scoreboard).
		instructions.bosses.WitherActions.recordSplit("Maxor", plugin.Utils.runTick());
		spawnMobGroups();
		initialMovement();
		scheduleIntroDialogue();

		Utils.scheduleTask(() -> {
			crushEnabled = true;
			setAggro(AGGRO_STOP_DISTANCE, AGGRO_Y_OFFSET, AGGRO_MAX_SPEED);
		}, INTRO_END_TICK);

		startCycleTask();
	}

	@Override
	protected void chainNext(boolean doContinue) {
		if(doContinue) {
			Goldor.goldorInstructions(world, true);
		} else {
			instructions.bosses.WitherActions.signalRunComplete(); // last boss of this practice
		}
	}

	private void scheduleIntroDialogue() {
		sendChatMessage("Pathetic Maxor, just like expected.");
		Utils.scheduleTask(() -> sendChatMessage("Don't boast about beating this simple-minded Wither."), 60);
		Utils.scheduleTask(() -> sendChatMessage("My abilities are unparalleled, in may ways I am the last bastion."), 120);
		Utils.scheduleTask(() -> sendChatMessage("The memory of your death will be your fondest, focus up!"), 180);
		// Belongs to the FLIGHT, not the dialogue.
		Utils.scheduleTask(() -> {
			Actions.turnHead(boss, 90f, 0f);
			sendChatMessage("The power of lightning is quite phenomenal.  A single strike can vaporize a person whole.");
		}, 400);
		countdownTitle("4", 420);
		Utils.scheduleTask(() -> sendChatMessage("I'd be happy to show you what that's like!"), 460);
		countdownTitle("3", 445);
		countdownTitle("2", 470);
		countdownTitle("1", 495);
		// Leads the first volley by 10t.
		Utils.scheduleTask(() -> sendChatMessage(LIGHTNING_MESSAGE[random.nextInt(LIGHTNING_MESSAGE.length)]), 505);
		Utils.scheduleTask(this::lightningVolley, 515);
		Utils.scheduleTask(this::lightningVolley, 525);
	}

	/**
	 * Held 25 ticks.
	 */
	private void countdownTitle(String digit, int at) {
		Utils.scheduleTask(() -> {
			for(Player player : Bukkit.getOnlinePlayers()) {
				player.showTitle(Title.title(Utils.msg("<dark_red>" + digit), Utils.msg(""),
						Title.Times.times(Duration.ofMillis(0L), Duration.ofMillis(25 * 50L), Duration.ofMillis(0L))));
			}
		}, at);
	}

	private void lightningVolley() {
		Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0F, 1.0F);
		Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0F, 1.0F);
		Utils.playGlobalSound(Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 1.0F);
		spamLightning();
		strikeUnsheltered();
	}

	// --- Deaths: the lightning volley, and a pillar closing on a player.  Both live modes; not classic ---

	/**
	 * Anyone not fully sheltered dies (not classic). Fires at both volleys, 10t apart, so one {@code CheatDeath} proc
	 * covers both; intended. Sheltered = EVERY column the hitbox overlaps is under pillar material, so half out from
	 * under an edge is struck. Only the three ACTIVE pillars shelter ({@link #inShelteringPillarColumn}).
	 */
	private void strikeUnsheltered() {
		if(!damage.Difficulty.deathsEnabled()) return;
		for(Player p : world.getPlayers()) {
			// Bolt gated like the kill, but struck first: a mask or Phoenix save doesn't mean the bolt missed.
			if(!death.Deaths.appliesTo(p)) continue;
			if(fullySheltered(p)) continue;
			world.strikeLightning(p.getLocation());
			death.Deaths.kill(p, "Storm");
		}
	}

	private boolean fullySheltered(Player p) {
		BoundingBox box = p.getBoundingBox();
		// Max edge is exclusive: nudge it in before flooring, or standing on a block line reads one column too many.
		int minX = (int) Math.floor(box.getMinX()), maxX = (int) Math.floor(box.getMaxX() - 1e-7);
		int minZ = (int) Math.floor(box.getMinZ()), maxZ = (int) Math.floor(box.getMaxZ() - 1e-7);
		int aboveHead = (int) Math.floor(box.getMaxY()) + 1;
		for(int x = minX; x <= maxX; x++) {
			for(int z = minZ; z <= maxZ; z++) {
				if(!inShelteringPillarColumn(x, z)) return false;
				if(!pillarMaterialAbove(x, z, aboveHead)) return false;
			}
		}
		return true;
	}

	private boolean pillarMaterialAbove(int x, int z, int fromY) {
		for(int y = fromY; y <= PadAndPillar.PILLAR_ANCHOR_Y; y++) {
			if(isPillarMaterial(world.getBlockAt(x, y, z).getType())) return true;
		}
		return false;
	}

	/**
	 * Hitbox inside pillar material dies (not classic): a pillar closing over a player or a player walking into one.
	 * Unlike the boss test it uses EVERY footprint, Red included: leftover diorite still crushes.
	 */
	private void pollPlayerCrush() {
		if(!damage.Difficulty.deathsEnabled()) return;
		for(Player p : world.getPlayers()) {
			if(!insidePillar(p)) continue;
			death.Deaths.kill(p, "Storm");
		}
	}

	private boolean insidePillar(Player p) {
		BoundingBox box = p.getBoundingBox();
		int minX = (int) Math.floor(box.getMinX()), maxX = (int) Math.floor(box.getMaxX() - 1e-7);
		int minY = (int) Math.floor(box.getMinY()), maxY = (int) Math.floor(box.getMaxY() - 1e-7);
		int minZ = (int) Math.floor(box.getMinZ()), maxZ = (int) Math.floor(box.getMaxZ() - 1e-7);
		for(int x = minX; x <= maxX; x++) {
			for(int y = minY; y <= maxY; y++) {
				for(int z = minZ; z <= maxZ; z++) {
					if(!isPillarMaterial(world.getBlockAt(x, y, z).getType())) continue;
					if(inAnyPillarColumn(x, z)) return true;
				}
			}
		}
		return false;
	}

	/**
	 * Both diorites, matching {@link #stormInCrushablePillar} and the crush explosion's filter.
	 */
	private static boolean isPillarMaterial(Material m) {
		return m == Material.DIORITE || m == Material.POLISHED_DIORITE;
	}

	/**
	 * {@link PadAndPillar#ACTIVE} only: Red has no pad, so hiding under it saves nobody. Deliberately not
	 * {@link #inAnyPillarColumn}, which the crush uses: being INSIDE Red's blocks still kills.
	 */
	private static boolean inShelteringPillarColumn(int x, int z) {
		return inPillarColumn(x, z, PadAndPillar.ACTIVE);
	}

	/**
	 * Any footprint, Red included.
	 */
	private static boolean inAnyPillarColumn(int x, int z) {
		return inPillarColumn(x, z, PadAndPillar.ALL);
	}

	private static boolean inPillarColumn(int x, int z, java.util.List<PadAndPillar> pillars) {
		for(PadAndPillar p : pillars) {
			if(x >= p.pillarX1() && x <= p.pillarX2() && z >= p.pillarZ1() && z <= p.pillarZ2()) return true;
		}
		return false;
	}

	private void initialMovement() {
		Actions.turnHead(boss, 45f, 0f);
		Actions.forceMove(boss, new Vector(-0.29, 0, 0.29), 100);
		Utils.scheduleTask(() -> {
			Actions.turnHead(boss, 135f, 0f);
			Actions.forceMove(boss, new Vector(-0.29, 0, -0.29), 100);
		}, 100);
		Utils.scheduleTask(() -> {
			Actions.turnHead(boss, -135f, 0f);
			Actions.forceMove(boss, new Vector(0.29, 0, -0.29), 100);
		}, 200);
		Utils.scheduleTask(() -> {
			Actions.turnHead(boss, -45f, 0f);
			Actions.forceMove(boss, new Vector(0.29, 0, 0.29), 100);
		}, 300);
	}

	// --- 20-tick poll loop: pad gating + crush detection ---

	private void startCycleTask() {
		cancelCycleTask();
		// Every tick, but the poll only on phase ticks divisible by 20: absolute, not registration-relative, so it
		// stays on the grid.
		cycleTicker = () -> {
			if(boss == null || boss.isDead()) {
				cancelCycleTask();
				return;
			}
			updateActionBar();
			// Every tick: clone ops land on their own 4-tick cadence.
			pollPlayerCrush();
			if(displayTick() % PAD_CYCLE_TICKS == 0) pollCycle();
		};
		BossScheduler.addTicker(cycleTicker);
		// Registration is deferred a tick, so run tick 0's poll now; a player already on a pad advances at 0, not 20.
		updateActionBar();
		pollCycle();
	}

	/**
	 * Per-tick HUD:
	 * <ul>
	 *   <li><b>Pad</b>: ticks to the next pad poll, 20t → 1t.</li>
	 *   <li><b>Armed</b>: per unused pillar inside its {@link #CRUSH_DETECTOR_WINDOW}, in its colour. From
	 *       {@link #LIGHTNING_TICK} on only; crush can't fire during the intro.</li>
	 *   <li><b>Storm moves in</b>: volley to {@link #INTRO_END_TICK}.</li>
	 *   <li><b>Stunned</b>: until auto-enrage, off {@link #stunEndTick}; gone early on a 55% cap-enrage, so it shows
	 *       how long he's ACTUALLY stunned.</li>
	 * </ul>
	 * Armed uses {@link #tick}, not {@link #displayTick()}, since that's what {@link #pollCycle} feeds the detector.
	 * Per player because "Pad" takes the nearest pad's colour. No clash with {@code ClearManager}'s bar, which skips
	 * anyone outside the room grid.
	 */
	private void updateActionBar() {
		int t = displayTick();
		String pad = "Pad <white>" + (PAD_CYCLE_TICKS - Math.floorMod(t, PAD_CYCLE_TICKS)) + "t";
		// Right behind the pad counter: it's what people read while it's up.
		String stun = inStun ? " <dark_gray>| <yellow>Stunned <white>" + Math.max(0, stunEndTick - t) + "t" : "";
		int volley = LIGHTNING_TICK;
		int introEnd = INTRO_END_TICK;
		String armed = t >= volley ? armedSegments() : "";
		String moves = t >= volley && t <= introEnd
				? " <dark_gray>| <red>Storm moves in <white>" + (introEnd - t) + "t"
				: "";
		for(Player p : Bukkit.getOnlinePlayers()) {
			Utils.sendActionBar(p, Utils.msg(nearestPadColor(p.getLocation()) + pad + stun + armed + moves));
		}
	}

	/**
	 * Fixed Purple/Yellow/Green order so segments don't swap places as timers run out. Same for every player.
	 */
	private String armedSegments() {
		StringBuilder sb = new StringBuilder();
		for(PillarOscillator osc : pillars) {
			if(osc.isUsed()) continue;
			int left = osc.armedTicksLeft(tick, CRUSH_DETECTOR_WINDOW);
			if(left <= 0) continue;
			sb.append(" <dark_gray>| ").append(osc.getPillar().color()).append("Armed <white>").append(left).append("t");
		}
		return sb.toString();
	}

	/**
	 * Of all four, Red included (still worth naming). Y ignored so it doesn't flip while riding a pillar.
	 */
	private static String nearestPadColor(Location loc) {
		PadAndPillar nearest = PadAndPillar.ALL.getFirst();
		double bestDistSq = Double.POSITIVE_INFINITY;
		for(PadAndPillar pad : PadAndPillar.ALL) {
			double dx = loc.getX() - pad.padBox().getCenterX();
			double dz = loc.getZ() - pad.padBox().getCenterZ();
			double d2 = dx * dx + dz * dz;
			if(d2 < bestDistSq) {
				bestDistSq = d2;
				nearest = pad;
			}
		}
		return nearest.color();
	}

	private static void broadcastActionBar(Component bar) {
		Utils.broadcastActionBar(bar);
	}

	/**
	 * Advance each occupied pad's pillar, then crush detection. Every 20th phase tick plus tick 0.
	 */
	private void pollCycle() {
		// Used pillars' pads are dead.
		for(PillarOscillator osc : pillars) {
			if(osc.isUsed()) continue;
			if(padOccupied(osc.getPillar().padBox())) {
				osc.runCycle(tick);
			}
		}

		// Safety net: a scheduler flush could swallow the detonation, leaving the pillar up and crushExplosionPending
		// latched forever (no more crushes). Fire it here if overdue.
		if(crushExplosionPending && tick - crushArmedTick > CRUSH_EXPLOSION_DELAY + 20) {
			fireCrushExplosion();
		}

		// Crush: after the intro, not stunned, within the window after a pillar's last move. crushExplosionPending
		// is gated too: the 55% cap is often hit at ~18-19t, before the 20t explosion, and he'd be re-crushed on the
		// same unexploded diorite.
		if(crushEnabled && !inStun && !dying && !crushExplosionPending && anyPillarMovedRecently() && stormInCrushablePillar()) {
			triggerCrush();
		}
	}

	private void cancelCycleTask() {
		if(cycleTicker != null) {
			BossScheduler.removeTicker(cycleTicker);
			cycleTicker = null;
			// Wipe instead of letting the last "Pad 7t" sit through its fade-out.
			broadcastActionBar(Component.empty());
		}
	}

	private boolean padOccupied(BoundingBox padBox) {
		for(Player p : world.getPlayers()) {
			if(p.getGameMode() == GameMode.SPECTATOR) continue;
			int bx = p.getLocation().getBlockX();
			int by = p.getLocation().getBlockY();
			int bz = p.getLocation().getBlockZ();
			if(bx >= padBox.getMinX() && bx <= padBox.getMaxX() && by >= padBox.getMinY() && by <= padBox.getMaxY() && bz >= padBox.getMinZ() && bz <= padBox.getMaxZ()) {
				return true;
			}
		}
		return false;
	}

	private boolean anyPillarMovedRecently() {
		for(PillarOscillator osc : pillars) {
			if(osc.movedRecently(tick, CRUSH_DETECTOR_WINDOW)) return true;
		}
		return false;
	}

	/**
	 * Hitbox overlaps diorite of an UNUSED pillar. A used pillar's blocks linger until (or past) its explosion, and a
	 * second crush on them would, via {@link #findPillarStormIsIn}'s fallback, consume an innocent pillar.
	 */
	private boolean stormInCrushablePillar() {
		BoundingBox box = boss.getBoundingBox();
		int minX = (int) Math.floor(box.getMinX());
		int maxX = (int) Math.floor(box.getMaxX());
		int minY = (int) Math.floor(box.getMinY());
		int maxY = (int) Math.floor(box.getMaxY());
		int minZ = (int) Math.floor(box.getMinZ());
		int maxZ = (int) Math.floor(box.getMaxZ());
		for(int x = minX; x <= maxX; x++) {
			for(int y = minY; y <= maxY; y++) {
				for(int z = minZ; z <= maxZ; z++) {
					Material m = world.getBlockAt(x, y, z).getType();
					if(m != Material.DIORITE && m != Material.POLISHED_DIORITE) continue;
					if(inUnusedPillarColumn(x, z)) return true;
				}
			}
		}
		return false;
	}

	private boolean inUnusedPillarColumn(int x, int z) {
		for(PillarOscillator osc : pillars) {
			if(osc.isUsed()) continue;
			PadAndPillar p = osc.getPillar();
			if(x >= p.pillarX1() && x <= p.pillarX2() && z >= p.pillarZ1() && z <= p.pillarZ2()) return true;
		}
		return false;
	}

	// --- Crush: like Maxor's stun, with a 0.55 cap and a 20t-delayed explosion ---

	private void triggerCrush() {
		// Super-verbose: exact crush position (packet-coordinate precision).
		if(Utils.isSuperVerbose()) {
			org.bukkit.Location loc = boss.getLocation();
			Utils.debug(Utils.DebugType.BOSS, "Storm crushed at " + Utils.round(loc.getX(), 3) + " " + Utils.round(loc.getY(), 5) + " " + Utils.round(loc.getZ(), 3));
		}
		// Pad goes dead; recorded so the T+20 explosion only destroys this column.
		PillarOscillator crushed = findPillarStormIsIn();
		currentCrushPillar = crushed != null ? crushed.getPillar() : null;
		if(crushed != null) crushed.markUsed();

		double maxHp = boss.getAttribute(Attribute.MAX_HEALTH).getValue();
		double crushDmg = maxHp * CRUSH_DAMAGE_FRACTION;
		double currentHp = boss.getHealth();

		// Crush would kill: leave DYING_SLIVER and run the death sequence.
		if(crushDmg >= currentHp) {
			clearAggro();
			setArmor(false);
			sendChatMessage(CRUSHED_MESSAGE[random.nextInt(CRUSHED_MESSAGE.length)]);
			boss.setHealth(DYING_SLIVER);
			Utils.playGlobalSound(Sound.ENTITY_WITHER_HURT);
			// Arm BEFORE dying so the position is captured where the pillar caught him; still T+20.
			scheduleCrushExplosion();
			enterDyingState();
			return;
		}

		inStun = true;
		stunDamageDealt = 0;
		stunCapReached = false;
		// Same tick the enrage is scheduled from, so bar and mechanic run out together.
		stunEndTick = displayTick() + STUN_AUTO_ENRAGE_TICKS;

		clearAggro();
		setArmor(false);
		sendChatMessage(CRUSHED_MESSAGE[random.nextInt(CRUSHED_MESSAGE.length)]);
		Utils.timer("<green>Storm crushed in " + formatTick(displayTick()));

		// 5% max HP straight to health; counts toward the 55% cap.
		boss.setHealth(Math.max(0.0, currentHp - crushDmg));
		stunDamageDealt += crushDmg;
		Utils.changeName(boss);
		Utils.playGlobalSound(Sound.ENTITY_WITHER_HURT);

		CustomBossBar.spawnAnimatedStunnedIndicator(boss, Integer.MAX_VALUE);

		// Start of tick, so a beam on the enrage tick sees the re-armoured boss.
		cancelStunEnrageTask();
		stunEnrageTask = BossScheduler.schedule(this::enrageStorm, STUN_AUTO_ENRAGE_TICKS);

		scheduleCrushExplosion();

		// Re-render: the ticker drew this tick's bar before pollCycle detected the crush.
		updateActionBar();
	}

	/**
	 * Pillar Storm overlaps horizontally, else the closest unused one; null only if all are used.
	 */
	private PillarOscillator findPillarStormIsIn() {
		BoundingBox box = boss.getBoundingBox();
		double sx1 = box.getMinX(), sx2 = box.getMaxX();
		double sz1 = box.getMinZ(), sz2 = box.getMaxZ();
		PillarOscillator closest = null;
		double closestDistSq = Double.POSITIVE_INFINITY;
		double sxMid = (sx1 + sx2) * 0.5;
		double szMid = (sz1 + sz2) * 0.5;
		for(PillarOscillator osc : pillars) {
			if(osc.isUsed()) continue;
			PadAndPillar p = osc.getPillar();
			// Columns occupy [pillarX1, pillarX2+1) × [pillarZ1, pillarZ2+1).
			if(sx2 >= p.pillarX1() && sx1 <= p.pillarX2() + 1
					&& sz2 >= p.pillarZ1() && sz1 <= p.pillarZ2() + 1) {
				return osc;
			}
			double pxMid = (p.pillarX1() + p.pillarX2() + 1) * 0.5;
			double pzMid = (p.pillarZ1() + p.pillarZ2() + 1) * 0.5;
			double ddx = sxMid - pxMid;
			double ddz = szMid - pzMid;
			double d2 = ddx * ddx + ddz * ddz;
			if(d2 < closestDistSq) {
				closestDistSq = d2;
				closest = osc;
			}
		}
		return closest;
	}

	/**
	 * Anchored to Storm's current position, independent of what happens to him in the delay.
	 */
	private void scheduleCrushExplosion() {
		pendingCrushLoc = boss != null ? boss.getLocation().clone() : pendingCrushLoc;
		crushExplosionPending = true;
		crushArmedTick = tick;
		Utils.scheduleTask(this::fireCrushExplosion, CRUSH_EXPLOSION_DELAY);
	}

	@SuppressWarnings("removal") // MOB_GRIEFING is deprecated-for-removal in 26.2 but works; no clean replacement.
	private void fireCrushExplosion() {
		// Idempotent: the death path may force it early.
		if(!crushExplosionPending) return;
		crushExplosionPending = false;
		// The recorded crush position, not boss.getLocation(): he may have died or moved. The pillar comes down either way.
		Location loc = pendingCrushLoc != null ? pendingCrushLoc
				: (boss != null && boss.isValid() ? boss.getLocation() : null);
		pendingCrushLoc = null;
		if(loc == null) return;
		// StormCrushExplosion scopes the blast by its source; with no live boss a power-50 blast would level the
		// arena, so clear the column by command.
		if(boss == null || !boss.isValid()) {
			clearCrushedPillarColumn();
			return;
		}
		// StormCrushExplosion keeps only diorite/polished_diorite with y<196. Level.explode() never breaks blocks for
		// a Mob source with mobGriefing off, whatever breakBlocks says, so toggle the gamerule around the call.
		Boolean prevMobGriefing = world.getGameRuleValue(GameRule.MOB_GRIEFING);
		try {
			world.setGameRule(GameRule.MOB_GRIEFING, true);
			crushExplosionActive = true;
			world.createExplosion(loc.getX(), loc.getY(), loc.getZ(), CRUSH_EXPLOSION_POWER, false, true, boss);
		} finally {
			crushExplosionActive = false;
			world.setGameRule(GameRule.MOB_GRIEFING, prevMobGriefing);
		}
	}

	/**
	 * Boss-less fallback for {@link #fireCrushExplosion}: same end state, no particles.
	 */
	private void clearCrushedPillarColumn() {
		PadAndPillar p = currentCrushPillar;
		if(p == null) return;
		for(String material : new String[]{"minecraft:diorite", "minecraft:polished_diorite"}) {
			Utils.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air replace %s",
					p.pillarX1(), PadAndPillar.PILLAR_BOTTOM_MIN, p.pillarZ1(),
					p.pillarX2(), PadAndPillar.PILLAR_ANCHOR_Y - 1, p.pillarZ2(), material));
		}
	}

	private void enrageStorm() {
		if(!inStun) return;
		inStun = false;
		cancelStunEnrageTask();

		setArmor(true);

		// All pillars gone and Storm alive: not enough DPS during the stuns, and he can never be stunned again, so
		// the run is lost. Taunt and fail instead of announcing the enrage.
		if(!dying && allPillarsExploded()) {
			playFailSequence();
		} else {
			sendChatMessage(ENRAGE_MESSAGE[random.nextInt(ENRAGE_MESSAGE.length)]);
			Utils.timer("<red>⚠ Storm is enraged! ⚠\n" + formatTick(displayTick()));
			for(Player player : Bukkit.getOnlinePlayers()) {
				player.showTitle(Title.title(Utils.msg(""), Utils.msg("<red>⚠ Storm is enraged! ⚠"),
						Title.Times.times(Duration.ofMillis(0L), Duration.ofMillis(40 * 50L), Duration.ofMillis(0L))));
			}
			Utils.playGlobalSound(Sound.ENTITY_WITHER_AMBIENT, 2.0F, 0.5F);
		}
		CustomBossBar.removeStunIndicator();
		setAggro(AGGRO_STOP_DISTANCE, AGGRO_Y_OFFSET, AGGRO_MAX_SPEED);

		// Re-render: both enrage paths run after the ticker drew this tick's bar.
		updateActionBar();
	}

	private boolean allPillarsExploded() {
		if(pillars.isEmpty()) return false;
		for(PillarOscillator osc : pillars) {
			if(!osc.isUsed()) return false;
		}
		return true;
	}

	/**
	 * Lines at +0 and +60t from the enrage, fail message and run end at +120t.
	 */
	private void playFailSequence() {
		sendChatMessage("Bahahaha!  Not a single intact pillar remains!");
		Utils.scheduleTask(() -> sendChatMessage("Rejoice, your last moments are with me and my lightning."), 60);
		Utils.scheduleTask(() -> {
			Bukkit.broadcast(Utils.msg("<red>You failed the run!"));
			endFailedRun();
		}, 120);
	}

	/**
	 * Same path as a normal end ({@code plugin.RunCompleteEvent}), so the network's session end clears the unkillable
	 * Storm instead of softlocking; standalone, /reset does. {@code success=false}, so no leaderboard.
	 */
	private void endFailedRun() {
		instructions.bosses.WitherActions.signalRunComplete(false);
	}

	private void cancelStunEnrageTask() {
		if(stunEnrageTask != null) BossScheduler.removeTicker(stunEnrageTask);
		stunEnrageTask = null;
	}

	/**
	 * Same as {@code Maxor.clampDamage} but with the 0.55 crush cap.
	 */
	@Override
	public double clampDamage(double incoming) {
		if(boss == null) return incoming;

		if(dying) return 0;

		// Cap already hit: reject everything, same-tick arrows included.
		if(stunCapReached) return 0;

		if(incoming <= 0) return 0;

		double currentHp = boss.getHealth();
		double maxHp = boss.getAttribute(Attribute.MAX_HEALTH).getValue();

		double cappedDmg = incoming;
		boolean willEnrage = false;
		if(inStun) {
			double damageCap = maxHp * STUN_DAMAGE_CAP_FRACTION;
			double remaining = Math.max(0, damageCap - stunDamageDealt);
			if(cappedDmg >= remaining) {
				cappedDmg = remaining;
				willEnrage = true;
			}
		}

		boolean willDie = false;
		if(cappedDmg >= currentHp) {
			cappedDmg = Math.max(0, currentHp - DYING_SLIVER);
			willDie = true;
			willEnrage = false;
		}

		if(willDie) {
			enterDyingState();
		} else {
			if(inStun) stunDamageDealt = Math.min(maxHp * STUN_DAMAGE_CAP_FRACTION, stunDamageDealt + cappedDmg);
			if(willEnrage) {
				// Latch BEFORE enraging so further same-tick hits are rejected.
				stunCapReached = true;
				enrageStorm();
			}
		}
		return cappedDmg;
	}

	private void enterDyingState() {
		dying = true;
		boss.addScoreboardTag("TASDying");
		cancelStunEnrageTask();
		cancelCycleTask();
		// cancelCycleTask only stops the NEXT cycle; queued DOWN clones would re-bury the dying Storm.
		for(PillarOscillator osc : pillars) {
			osc.freeze();
		}
		inStun = false;
		CustomBossBar.removeStunIndicator();
		Utils.scheduleTask(() -> {
			if(boss != null && boss.isValid()) boss.setHealth(DYING_SLIVER);
		}, 1);
		Utils.changeName(boss);
		playDeathDialogue();
	}

	private void playDeathDialogue() {
		// Goldor wall and handoff share this tick.
		int handoffTick = 60;
		sendChatMessage("I should have known that I stand no chance.");
		Server.playWitherDeathSound(boss);
		Utils.timer("<green>Storm killed in " + formatTick(displayTick()));
		// Restored on the next /reset.
		Utils.scheduleTask(instructions.bosses.BossTransition::openStormToGoldor, handoffTick);
		Utils.scheduleTask(() -> sendChatMessage("At least my son died by your hands."), 40);
		Utils.scheduleTask(() -> {
			Utils.timer("<green>Storm finished in " + formatTick(displayTick()));
			// Leaderboard duration at the phase's real end, not the killing blow. Before chainNext, which re-anchors the phase clock.
			instructions.bosses.WitherActions.recordPhaseDuration("Storm", displayTick());
			if(tickerTask != null && !tickerTask.isCancelled()) tickerTask.cancel();
			chainNext(doContinue);
		}, handoffTick);
	}

	public boolean isDyingWither(Wither w) {
		return dying && w != null && w.equals(boss);
	}

	/**
	 * Set in {@link #triggerCrush}, read by {@link listeners.StormCrushExplosion}.
	 */
	public PadAndPillar getCurrentCrushPillar() {
		return currentCrushPillar;
	}

	/**
	 * False only during {@code fireCrushExplosion}'s createExplosion call. {@link listeners.StormCrushExplosion} uses
	 * it to spot crush damage/knockback events that don't carry the wither.
	 */
	public boolean crushExplosionNotActive() {
		return !crushExplosionActive;
	}

	/**
	 * Before each DOWN clone: if the new bottom row would dip into Storm, shove him down a block. Stops at
	 * {@link #STORM_FLOOR_Y}, where the pillar crushes. Flying sideways into a stationary or rising pillar still
	 * crushes via the poll; flying up into one is stopped by {@link #clampRise}.
	 */
	public void tryPushBelowDescendingPillar(PadAndPillar pillar, int newBottomY) {
		if(boss == null || !boss.isValid()) return;
		BoundingBox box = boss.getBoundingBox();

		// Horizontal overlap with [pillarX1, pillarX2+1) × [pillarZ1, pillarZ2+1).
		if(box.getMaxX() <= pillar.pillarX1() || box.getMinX() >= pillar.pillarX2() + 1) return;
		if(box.getMaxZ() <= pillar.pillarZ1() || box.getMinZ() >= pillar.pillarZ2() + 1) return;

		// New row occupies [newBottomY, newBottomY+1); no overlap, nothing to push.
		if(box.getMaxY() <= newBottomY) return;

		if(boss.getLocation().getY() <= STORM_FLOOR_Y) return;

		Location loc = boss.getLocation();
		loc.setY(loc.getY() - 1);
		boss.teleport(loc);
	}

	/**
	 * Aggro never flies him up into pillar diorite: the step stops at the lowest pillar block above his head, and
	 * inside a pillar (flown in sideways, which is allowed) he can't rise at all. Any footprint, Red included.
	 */
	@Override
	protected double clampRise(BoundingBox box, double vy) {
		int minX = (int) Math.floor(box.getMinX()), maxX = (int) Math.floor(box.getMaxX() - 1e-7);
		int minY = (int) Math.floor(box.getMinY()), maxY = (int) Math.floor(box.getMaxY() + vy - 1e-7);
		int minZ = (int) Math.floor(box.getMinZ()), maxZ = (int) Math.floor(box.getMaxZ() - 1e-7);
		double allowed = vy;
		for(int x = minX; x <= maxX; x++) {
			for(int z = minZ; z <= maxZ; z++) {
				if(!inAnyPillarColumn(x, z)) continue;
				for(int y = minY; y <= maxY; y++) {
					if(!isPillarMaterial(world.getBlockAt(x, y, z).getType())) continue;
					allowed = Math.min(allowed, y - box.getMaxY());
					break;
				}
			}
		}
		return allowed;
	}

	// --- Mob spawning ---

	private void spawnMobGroups() {
		mobGroups.clear();

		String minerName = Utils.mmLegacy("Wither Miner <yellow>8M<red>❤");
		ItemStack stonePickaxe = new ItemStack(Material.STONE_PICKAXE);
		Location facingCenter = FACING_CENTER.clone();
		facingCenter.setWorld(world);

		// 0t: Pillar A (south pillar zone) + Pillar B (north pillar zone)
		mobGroups.add(new MobGroup(minerSpec("Pillar A", 20, MobSpawnSpec.uniformIn(36, 169, 37, 56, 169, 69), minerName, stonePickaxe, facingCenter, 0)));
		mobGroups.add(new MobGroup(minerSpec("Pillar B", 20, MobSpawnSpec.uniformIn(90, 169, 37, 110, 169, 69), minerName, stonePickaxe, facingCenter, 0)));

		// 40t: lava bridge groups
		mobGroups.add(new MobGroup(minerSpec("Lava bridge SW", 5, MobSpawnSpec.uniformIn(72, 168, 29, 74, 168, 31), minerName, stonePickaxe, facingCenter, 40)));
		mobGroups.add(new MobGroup(minerSpec("Lava bridge SE", 5, MobSpawnSpec.uniformIn(72, 168, 21, 74, 168, 23), minerName, stonePickaxe, facingCenter, 40)));
		mobGroups.add(new MobGroup(minerSpec("Lava bridge NW", 5, MobSpawnSpec.uniformIn(72, 168, 75, 74, 168, 77), minerName, stonePickaxe, facingCenter, 40)));
		mobGroups.add(new MobGroup(minerSpec("Lava bridge NE", 5, MobSpawnSpec.uniformIn(72, 168, 83, 74, 168, 86), minerName, stonePickaxe, facingCenter, 40)));

		// 40t: center-edge groups
		mobGroups.add(new MobGroup(minerSpec("Center edge N", 20, MobSpawnSpec.uniformIn(58, 163, 92, 88, 163, 100), minerName, stonePickaxe, facingCenter, 40)));
		mobGroups.add(new MobGroup(minerSpec("Center edge W", 20, MobSpawnSpec.uniformIn(26, 165, 37, 30, 165, 69), minerName, stonePickaxe, facingCenter, 40)));
		mobGroups.add(new MobGroup(minerSpec("Center edge S", 20, MobSpawnSpec.uniformIn(58, 163, 6, 88, 163, 14), minerName, stonePickaxe, facingCenter, 40)));
		mobGroups.add(new MobGroup(minerSpec("Center edge E", 20, MobSpawnSpec.uniformIn(116, 165, 37, 120, 165, 69), minerName, stonePickaxe, facingCenter, 40)));

		// 80t: center + 4 pad groups (each with one shadow assassin at a corner)
		mobGroups.add(new MobGroup(minerSpec("Center", 40, MobSpawnSpec.uniformIn(65, 165, 41, 81, 165, 65), minerName, stonePickaxe, facingCenter, 80)));

		mobGroups.add(new MobGroup(minerSpec("Pad NE", 10, MobSpawnSpec.uniformIn(108, 170, 88, 120, 170, 100), minerName, stonePickaxe, facingCenter, 80)));
		mobGroups.add(new MobGroup(shadowAssassinSpec("Shadow NE", 120.5, 100.5)));

		mobGroups.add(new MobGroup(minerSpec("Pad NW", 10, MobSpawnSpec.uniformIn(26, 170, 88, 38, 170, 100), minerName, stonePickaxe, facingCenter, 80)));
		mobGroups.add(new MobGroup(shadowAssassinSpec("Shadow NW", 26.5, 100.5)));

		mobGroups.add(new MobGroup(minerSpec("Pad SW", 10, MobSpawnSpec.uniformIn(26, 170, 6, 38, 170, 18), minerName, stonePickaxe, facingCenter, 80)));
		mobGroups.add(new MobGroup(shadowAssassinSpec("Shadow SW", 26.5, 6.5)));

		mobGroups.add(new MobGroup(minerSpec("Pad SE", 10, MobSpawnSpec.uniformIn(108, 170, 6, 120, 170, 18), minerName, stonePickaxe, facingCenter, 80)));
		mobGroups.add(new MobGroup(shadowAssassinSpec("Shadow SE", 120.5, 6.5)));

		// 44 fixed-location sentries.
		spawnSentries();

		for(MobGroup g : mobGroups) {
			g.spawn(world, random);
		}
	}

	private static MobSpawnSpec minerSpec(String groupName, int count, java.util.function.Function<Random, Vector> provider, String customName, ItemStack mainHand, Location facingCenter, int startTick) {
		return new MobSpawnSpec(groupName, EntityType.WITHER_SKELETON, count, provider, 4.0, -30, -20, customName, mainHand,
				/* aiEnabled */ true, /* aggressive */ true, /* adult */ false,
				/* silent */ false, /* persistent */ true, facingCenter, null, null, startTick,
				Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 2.0f);
	}

	private static MobSpawnSpec shadowAssassinSpec(String groupName, double x, double z) {
		ItemStack boots = Utils.createLeatherArmor(Material.LEATHER_BOOTS, Color.PURPLE, Utils.mmLegacy("<light_purple>Shadow Assassin Boots"));
		List<ItemStack> armor = Arrays.asList(new ItemStack(Material.AIR), new ItemStack(Material.AIR), new ItemStack(Material.AIR), boots);
		List<PotionEffect> effects = List.of(new PotionEffect(PotionEffectType.INVISIBILITY, -1, 0));
		return new MobSpawnSpec(groupName, EntityType.ZOMBIE, 1, MobSpawnSpec.fixed(x, 170, z), 15.0, -30, -20, Utils.mmLegacy("<light_purple><bold>Shadow Assassin </bold><yellow>30M<red>❤"), new ItemStack(Material.STONE_SWORD),
				/* aiEnabled */ false, /* aggressive */ false, /* adult */ true,
				/* silent */ true, /* persistent */ true,
				/* facingTarget */ null, effects, armor, 80,
				null, 1.0f);
	}

	private void spawnSentries() {
		sentries.clear();
		Location center = FACING_CENTER.clone();
		center.setWorld(world);

		for(double[] coords : SENTRY_COORDS) {
			Location loc = new Location(world, coords[0], coords[1], coords[2]);
			WitherSkeleton sentry = (WitherSkeleton) world.spawnEntity(loc, EntityType.WITHER_SKELETON);
			// Uses the Wither Miner's stats (MAP.md §5 has the Guard's as [TBD]). Wither + Undead, no Skeletal in MM.
			damage.MobStats.apply(sentry, damage.MobStats.WITHER_MINER);
			sentry.setAI(false);
			sentry.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
			// MobStats identifies it by name; keep the leading text.
			sentry.customName(Utils.msg("Wither Guard <yellow>" + Utils.formatHealthM(sentry) + "<red>❤"));
			sentry.setCustomNameVisible(true);

			Location targetLoc = new Location(world, center.getX(), loc.getY(), center.getZ());
			Vector direction = targetLoc.toVector().subtract(loc.toVector()).normalize();
			float yaw = (float) (Math.atan2(-direction.getX(), direction.getZ()) * 180.0 / Math.PI);
			float pitch = (float) (Math.asin(-direction.getY()) * 180.0 / Math.PI);
			Location facingLoc = loc.clone();
			facingLoc.setYaw(yaw);
			facingLoc.setPitch(pitch);
			sentry.teleport(facingLoc);

			net.minecraft.world.entity.monster.skeleton.WitherSkeleton nmsWs = (net.minecraft.world.entity.monster.skeleton.WitherSkeleton) ((org.bukkit.craftbukkit.entity.CraftWitherSkeleton) sentry).getHandle();
			nmsWs.setAggressive(true);

			sentries.add(sentry);
		}
	}

	public void cleanupMobs() {
		for(MobGroup g : mobGroups) g.cleanup();
		mobGroups.clear();
		for(WitherSkeleton ws : sentries) {
			if(ws != null && ws.isValid()) ws.remove();
		}
		sentries.clear();
	}

	private void spamLightning() {
		for(MobGroup g : mobGroups) {
			for(LivingEntity mob : g.getSpawned()) {
				if(mob == null || !mob.isValid()) continue;
				if(mob instanceof Zombie) continue; // not on Shadow Assassins
				Location l = mob.getLocation();
				world.strikeLightning(l);
				Utils.scheduleTask(() -> world.strikeLightning(l), 10);
			}
		}
		for(WitherSkeleton ws : sentries) {
			if(ws == null || !ws.isValid()) continue;
			Location l = ws.getLocation();
			world.strikeLightning(l);
			Utils.scheduleTask(() -> world.strikeLightning(l), 10);
		}
	}
}
