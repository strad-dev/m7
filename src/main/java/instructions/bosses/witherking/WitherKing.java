package instructions.bosses.witherking;

import instructions.bosses.CustomBossBar;
import instructions.bosses.WitherActions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.title.Title;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonDeathPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.phys.Vec3;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.craftbukkit.entity.CraftEnderDragon;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import plugin.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;

/**
 * Wither King. After Necron ({@code Necron#chainNext}) or standalone ({@code /m7practice witherking}).
 * <ol>
 *   <li><b>Summon</b>: pick up each of five statue relics (→ slot 8) and right-click its altar (Y 6/7). All five
 *       placed starts the intro.</li>
 *   <li><b>Dragons</b>: the King (5 HP) loses 1 per dragon. Three spawn on timers (a pair, then a third), the last
 *       two the tick the last living dragon dies. Colour per slot is the set order, rolled in realistic only
 *       ({@link #spawnOrder}); the schedule never moves. Kills come in via {@link #handleDragonKilled} from
 *       {@code damage.Damage.deal}'s kill chokepoint.</li>
 * </ol>
 * Not a {@code WitherLord} (5-HP scale, MAGIC name, dragon-driven HP) but uses the same tick machinery.
 */
@SuppressWarnings({"unused", "DataFlowIssue"})
public class WitherKing {
	private static World world;
	private static Wither witherKing;
	/** Only via {@code /m7practice witherking}; the Necron chain passes false. */
	private static boolean standalone;
	private static final Random random = new Random();
	private static final String[] dragonDieMessage = {"Oh, this one hurts!", "I have more of those.", "My soul is disposable."};

	/** Final kill to congratulation. */
	private static final int END_DELAY_TICKS = 10;

	// --- Summon-phase relics ---
	/** Wool, chat colour, label, statue spawn point, altar X/Z. */
	private enum Relic {
		RED(Material.RED_WOOL, "<red>", "Red", 20.5, 6.8125, 59.5, 51, 42),
		GREEN(Material.GREEN_WOOL, "<dark_green>", "Green", 20.5, 6.8125, 94.5, 49, 44),
		PURPLE(Material.PURPLE_WOOL, "<light_purple>", "Purple", 56.5, 8.8125, 132.5, 54, 41),
		BLUE(Material.LIGHT_BLUE_WOOL, "<aqua>", "Blue", 91.5, 6.8125, 94.5, 59, 44),
		ORANGE(Material.ORANGE_WOOL, "<gold>", "Orange", 92.5, 6.8125, 56.5, 57, 42);

		final Material wool;
		final String mm;          // MiniMessage tag
		final String label;
		final double x, y, z;     // statue: center X/Z, bottom Y
		final int altarX, altarZ; // altar pillar (Y 6 & 7)

		Relic(Material wool, String mm, String label, double x, double y, double z, int altarX, int altarZ) {
			this.wool = wool;
			this.mm = mm;
			this.label = label;
			this.x = x; this.y = y; this.z = z;
			this.altarX = altarX; this.altarZ = altarZ;
		}

		/** Legacy §-string, compared against {@link Utils#displayName}. */
		String itemName() { return Utils.mmLegacy(mm + label + " Relic"); }

		/** Floating wool's center Y; Purple's statue is 2 blocks higher. */
		double displayY() { return this == PURPLE ? 9.5 : 7.5; }

		static Relic fromWool(Material m) {
			for(Relic r : values()) if(r.wool == m) return r;
			return null;
		}

		static Relic altarAt(int x, int z) {
			for(Relic r : values()) if(r.altarX == x && r.altarZ == z) return r;
			return null;
		}
	}

	private static final Map<Relic, ItemDisplay> statueDisplays = new HashMap<>();
	private static final Map<Relic, Interaction> statueInteractions = new HashMap<>();
	private static final Map<UUID, Relic> interactionRelic = new HashMap<>();
	private static final List<ItemDisplay> altarWoolDisplays = new ArrayList<>();
	private static final Set<Relic> placedRelics = new HashSet<>();

	// Grows 0.1 scale/tick from 0.1 to 4.
	private static BukkitTask growthTask;
	private static final double WITHER_KING_SCALE = 4.0;

	// Y-axis spin shared by every relic + altar wool display.
	private static BukkitTask rotationTask;
	private static float rotationAngle = 0f;
	private static final float ROTATION_STEP = (float) (Math.PI / 40); // full turn every 80 ticks
	private static final Vector3f DISPLAY_SCALE = new Vector3f(0.66666f, 0.66666f, 0.66666f);

	// --- Dragon phase ---
	private static final Map<String, EnderDragon> dragons = new HashMap<>();
	private static final Set<UUID> dyingDragons = new HashSet<>();
	/** Phase tick each dragon spawned, to time its kill. */
	private static final Map<String, Integer> dragonSpawnTick = new HashMap<>();
	private static int aliveCount = 0;
	/**
	 * Classic and Perfect RNG order. The pre-roll hardcoded order (v2.8.0 and earlier): Soul and Ice at 260t, Flame at
	 * 600, then Power and Apex on kills. Practised routes name these, so don't reorder.
	 */
	private static final List<String> SET_SPAWN_ORDER = List.of("purple", "blue", "orange", "red", "green");
	/**
	 * This run's order. SLOTS are fixed: 0 and 1 the 260t pair, 2 the third timer dragon, 3 and 4 the event pair.
	 * Colours are rolled in realistic only; set in {@link #witherKingInstructions} with the queue and latch. Nothing
	 * downstream may name a colour for a slot (keys on slot).
	 */
	private static final List<String> spawnOrder = new ArrayList<>(SET_SPAWN_ORDER);
	/**
	 * Last timer dragon, what {@link #lastTimerSpawned} latches on: slot 1. Slot 2 has no timer and heads the queue.
	 */
	private static String lastTimerDragon = "orange";

	/** Gates event spawns so an early death in the opening pair can't trigger them. */
	private static boolean lastTimerSpawned = false;
	/** Slots 2, 3 and 4, spawned when the last living dragon dies. */
	private static final Deque<String> eventQueue = new ArrayDeque<>();
	/** Spawn animation length; matches the {@link BossScheduler} delay. */
	private static final int DRAGON_SPAWN_ANIM = 100;
	/** Huge so it reads from across the arena. */
	private static final float DRAGON_COUNTDOWN_SCALE = 12f;
	/** Cancelled in {@link #forceCleanup}. */
	private static final List<BukkitTask> countdownTasks = new ArrayList<>();

	// ============================== Entry / summon phase ==============================

	public static void witherKingInstructions(World temp, boolean isStandalone) {
		world = temp;
		standalone = isStandalone;

		forceCleanup(temp);
		Utils.markPhaseStart();
		// Necron's split ends at WK phase start.
		WitherActions.recordSplit("Necron", Utils.runTick());

		eventQueue.clear();
		// Rebuilt first since the shuffle is in place, or a realistic run's order leaks into the next classic one.
		// Only colours move; the schedule is the same every run.
		spawnOrder.clear();
		spawnOrder.addAll(SET_SPAWN_ORDER);
		if(damage.Difficulty.realPuzzles()) Collections.shuffle(spawnOrder, random);
		lastTimerDragon = spawnOrder.get(1);
		eventQueue.add(spawnOrder.get(2));
		eventQueue.add(spawnOrder.get(3));
		eventQueue.add(spawnOrder.get(4));
		aliveCount = 0;
		lastTimerSpawned = false;

		spawnRelics();
	}

	/** ItemDisplay (0.66666³ wool) + Interaction (1 × 1.1875 × 1) per statue. */
	private static void spawnRelics() {
		for(Relic relic : Relic.values()) spawnRelicEntities(relic);
		startRotation();
	}

	/** Shared by the initial spawn and {@link #returnRelicToStatue}, so a returned relic is identical. Idempotent. */
	private static void spawnRelicEntities(Relic relic) {
		ItemDisplay existing = statueDisplays.get(relic);
		if(existing != null && existing.isValid()) return;

		// Wool floats at displayY; the hitbox stays on the statue.
		ItemDisplay display = world.spawn(new Location(world, relic.x, relic.displayY(), relic.z), ItemDisplay.class, d -> {
			d.setItemStack(new ItemStack(relic.wool));
			// CURRENT angle, not 0, so a mid-phase respawn stays in step with the other four.
			d.setTransformation(rotationTransform(rotationAngle));
			d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
			d.setPersistent(true);
			d.addScoreboardTag("TASWitherKingRelic");
			d.addScoreboardTag("TASNoName");
		});

		Interaction interaction = world.spawn(new Location(world, relic.x, relic.y, relic.z), Interaction.class, i -> {
			i.setInteractionWidth(1.0f);
			i.setInteractionHeight(1.1875f);
			i.setResponsive(true);
			i.setPersistent(true);
			i.addScoreboardTag("TASWitherKingRelic");
			i.addScoreboardTag("TASNoName");
		});

		statueDisplays.put(relic, display);
		statueInteractions.put(relic, interaction);
		interactionRelic.put(interaction.getUniqueId(), relic);
	}

	/**
	 * Wrong-cauldron undo: hand → its statue. A lost relic would strand the summon. Must run BEFORE the live-mode
	 * death, since {@code death/Deaths} snapshots the inventory and would hand back a duplicate on revival.
	 * <p>
	 * A placed relic is never returned: enforced here by "still in this player's hand", not left to the caller, since
	 * getting it wrong un-places a banked relic. That also makes a repeated event a no-op. Spectator-gated.
	 */
	public static void returnRelicToStatue(Player p, String color) {
		if(Utils.isSpectator(p)) return;
		Relic relic = Relic.valueOf(color);
		if(placedRelics.contains(relic)) return;                 // already banked
		if(!relic.name().equals(relicColorOfItem(p.getInventory().getItem(8)))) return; // not in this player's hand

		// Back to the SkyBlock-menu nether star, as placeRelic does.
		p.getInventory().setItem(8, items.util.SkyblockMenu.INSTANCE.build());
		Utils.setHotbarSlot(p, 8);
		spawnRelicEntities(relic);
	}

	/** 0.66666³, rotated {@code angle} radians about Y, spinning in place. */
	private static Transformation rotationTransform(float angle) {
		return new Transformation(
				new Vector3f(0f, 0f, 0f),
				new AxisAngle4f(angle, 0f, 1f, 0f),
				DISPLAY_SCALE,
				new AxisAngle4f(0f, 0f, 0f, 1f));
	}

	/** Runs until {@link #forceCleanup}. */
	private static void startRotation() {
		if(rotationTask != null && !rotationTask.isCancelled()) return;
		rotationTask = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), () -> {
			rotationAngle += ROTATION_STEP;
			if(rotationAngle > (float) (2 * Math.PI)) rotationAngle -= (float) (2 * Math.PI);
			Transformation t = rotationTransform(rotationAngle);
			for(ItemDisplay d : statueDisplays.values()) if(d != null && d.isValid()) d.setTransformation(t);
			for(ItemDisplay d : altarWoolDisplays) if(d != null && d.isValid()) d.setTransformation(t);
		}, 1L, 1L);
	}

	/** 0.1 scale/tick up to {@link #WITHER_KING_SCALE}. */
	private static void startWitherKingGrowth() {
		if(growthTask != null && !growthTask.isCancelled()) growthTask.cancel();
		growthTask = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), () -> {
			if(witherKing == null || !witherKing.isValid()) {
				if(growthTask != null) growthTask.cancel();
				growthTask = null;
				return;
			}
			double scale = witherKing.getAttribute(Attribute.SCALE).getBaseValue();
			if(scale >= WITHER_KING_SCALE) {
				witherKing.getAttribute(Attribute.SCALE).setBaseValue(WITHER_KING_SCALE);
				growthTask.cancel();
				growthTask = null;
				return;
			}
			witherKing.getAttribute(Attribute.SCALE).setBaseValue(Math.min(WITHER_KING_SCALE, scale + 0.1));
		}, 1L, 1L);
	}

	/** Null if not a still-present relic interaction. */
	public static String relicColorForInteraction(Interaction interaction) {
		Relic r = interactionRelic.get(interaction.getUniqueId());
		return r == null ? null : r.name();
	}

	/** Null if not an altar block. */
	public static String altarColorAt(int x, int z) {
		Relic r = Relic.altarAt(x, z);
		return r == null ? null : r.name();
	}

	/** Only for a real relic: right wool AND "[Color] Relic" name. */
	public static String relicColorOfItem(ItemStack item) {
		if(item == null) return null;
		Relic r = Relic.fromWool(item.getType());
		if(r == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return null;
		return r.itemName().equals(Utils.displayName(item.getItemMeta())) ? r.name() : null;
	}

	/** Blocks picking up a second. */
	public static boolean isHoldingRelic(Player p) {
		return relicColorOfItem(p.getInventory().getItem(8)) != null;
	}

	/**
	 * From the relic Interaction right-click. Spectators refused HERE: a spectator's pickup stranded the summon with
	 * the relic unrecoverable, and vanilla fires the interact event for spectators too.
	 */
	public static void pickUpRelic(Player p, String color) {
		if(Utils.isSpectator(p)) return;
		// One at a time.
		if(isHoldingRelic(p)) return;
		Relic relic = Relic.valueOf(color);

		ItemStack itemStack = new ItemStack(relic.wool);
		ItemMeta meta = itemStack.getItemMeta();
		meta.displayName(Utils.nameComponent(relic.itemName()));
		itemStack.setItemMeta(meta);

		// So it can't be picked up twice.
		removeRelicEntities(relic);

		Bukkit.broadcast(Utils.msg("<gold>" + p.getName() + "<green> picked up the " + relic.mm + relic.label + " Relic<green>!"));
		Utils.timer("<green>Picked up in " + formatTick());
		p.getInventory().setItem(8, Utils.placeOnAltarsInAdventure(itemStack));
		Utils.setHotbarSlot(p, 8);
		Utils.playGlobalSound(Sound.ENTITY_ENDERMAN_SCREAM, 2.0f, 0.5f);
	}

	/** From the altar right-click. Spectator-gated like {@link #pickUpRelic}; covers a relic carried into spectator. */
	public static void placeRelic(Player p, String color) {
		if(Utils.isSpectator(p)) return;
		Relic relic = Relic.valueOf(color);
		if(placedRelics.contains(relic)) return;
		placedRelics.add(relic);

		// Same cube and spin as the statue relics.
		Location woolLoc = new Location(world, relic.altarX + 0.5, 8.5, relic.altarZ + 0.5);
		ItemDisplay wool = world.spawn(woolLoc, ItemDisplay.class, d -> {
			d.setItemStack(new ItemStack(relic.wool));
			d.setTransformation(rotationTransform(rotationAngle));
			d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
			d.setPersistent(true);
			d.addScoreboardTag("TASWitherKingRelic");
			d.addScoreboardTag("TASNoName");
		});
		altarWoolDisplays.add(wool);

		// Back to the SkyBlock-menu nether star.
		p.getInventory().setItem(8, items.util.SkyblockMenu.INSTANCE.build());
		Utils.setHotbarSlot(p, 8);
		Utils.playGlobalSound(Sound.ENTITY_ENDERMAN_SCREAM, 2.0f, 0.5f);

		if(placedRelics.size() == 5) {
			Utils.timer("<gold><bold>All Relics </bold><green>placed in " + formatTick());
			startWitherKingIntro();
		} else {
			Utils.timer(relic.mm + relic.label + " Relic (" + placedRelics.size() + "/5)<green> placed in " + formatTick());
		}
	}

	// ============================== Wither King intro ==============================

	/**
	 * Golem-repair and thunder beds, three lines, first dragons. Golem repairs on a 10t grid (20-60), thunder ends with
	 * the last line, slot 0 spawns with "You... again?" at 60 and slot 1 with the second line at 120. Slot 2 has no
	 * timer ({@link #lastTimerDragon}).
	 */
	private static void startWitherKingIntro() {
		int breakStep = 10;
		int firstLine = 60;
		int secondLine = 120;
		int lastLine = 180;
		Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.5f), 20);
		for(int i = 20; i <= 20 + breakStep * 4; i += breakStep) {
			Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.ENTITY_ITEM_BREAK, 1.0f, 0.5f), i);
		}
		for(int i = 20; i <= 181; i += 20) {
			Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 1.0f), i);
			Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, 1.0f), i);
		}
		Utils.scheduleTask(() -> sendChatMessage("You... again?"), firstLine);
		// Spawns 20t before "I no longer wish to fight" at scale 0.1 and grows into existence.
		Utils.scheduleTask(() -> {
			witherKing = (Wither) world.spawnEntity(new Location(world, 54.5, 6, 32.5, 0f, 0f), EntityType.WITHER);
			witherKing.setAI(false);
			witherKing.setSilent(true);
			witherKing.setPersistent(true);
			witherKing.setRemoveWhenFarAway(false);
			witherKing.customName(Utils.msg("<gold><bold>﴾ <red><obfuscated>Wither-King</obfuscated><gold> ﴿ </bold><yellow>5<red>❤"));
			witherKing.setCustomNameVisible(true);
			witherKing.getAttribute(Attribute.MAX_HEALTH).setBaseValue(5);
			witherKing.getAttribute(Attribute.ARMOR).setBaseValue(-30);
			witherKing.getAttribute(Attribute.ARMOR_TOUGHNESS).setBaseValue(-20);
			witherKing.getAttribute(Attribute.SCALE).setBaseValue(0.1);
			witherKing.setHealth(5);
			witherKing.addScoreboardTag("TASWither");
			witherKing.addScoreboardTag("TASWitherKing");
			// That tag IS MobStats.witherKingPhaseActive: Pet.forPlayer moves Archer and Berserk to the Ender Dragon,
			// so their Chimera lore still shows the Golden Dragon's. The refresh is deferred and skips unchanged pets.
			for(Player pl : Bukkit.getOnlinePlayers()) damage.StatLore.refreshChimeraLore(pl);
			WitherActions.setWitherArmor(witherKing, true);
			startWitherKingGrowth();

			Utils.scheduleTask(() -> CustomBossBar.setupWitherBossBar(witherKing, "<obfuscated>Wither-King"), 1);
		}, secondLine - 20);
		Utils.scheduleTask(() -> {
			sendChatMessage("I no longer wish to fight, but I know that will not stop you.");
			Utils.playGlobalSound(Sound.ENTITY_WITHER_AMBIENT, 2.0f, 0.67f);
		}, secondLine);
		Utils.scheduleTask(() -> sendChatMessage("We will decide it all, here, now."), lastLine);
		// Timer dragons by SLOT. Slots 3 and 4 come from handleDragonKilled.
		Utils.scheduleTask(() -> spawnDragon(spawnOrder.get(0)), 60);
		Utils.scheduleTask(() -> spawnDragon(spawnOrder.get(1)), 120);
	}

	/** "orange" → gold-bold "Flame Dragon". */
	private static String dragonName(String color) {
		return switch(color) {
			case "orange" -> "<gold><bold>Flame Dragon";
			case "green" -> "<dark_green><bold>Apex Dragon";
			case "red" -> "<red><bold>Power Dragon";
			case "blue" -> "<aqua><bold>Ice Dragon";
			case "purple" -> "<light_purple><bold>Soul Dragon";
			default -> "<gray><bold>Unknown Dragon";
		};
	}

	/** Reverse lookup of {@link #dragons}, "" if unknown. */
	private static String colorOf(EnderDragon dragon) {
		for(Map.Entry<String, EnderDragon> en : dragons.entrySet()) {
			if(en.getValue() != null && en.getValue().getUniqueId().equals(dragon.getUniqueId())) return en.getKey();
		}
		return "";
	}

	public static void spawnDragon(String color) {
		for(int i = 0; i < 81; i += 20) {
			Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f), i);
			Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.0f), i);
		}
		String dragonName = dragonName(color);
		Location spawnLocation = switch(color) {
			case "orange" -> new Location(world, 86.5, 15, 56.5, 180f, 0f);
			case "green" -> new Location(world, 26.5, 15, 94.5, 0f, 0f);
			case "red" -> new Location(world, 26.5, 15, 59.5, 45f, 0f);
			case "blue" -> new Location(world, 85.5, 15, 94.5, 180f, 0f);
			case "purple" -> new Location(world, 56.5, 14, 126.5, 0f, 0f);
			default -> new Location(world, 54.5, 15, 76.5);
		};

		Bukkit.broadcast(Utils.msg("<yellow>The " + dragonName + "</bold><yellow> is spawning!"));
		Utils.timer("<yellow>Triggered in " + formatTick());
		announceDragonSpawn(color, dragonName, spawnLocation);

		// Boss lane, start of tick, so a same-tick beam hits it. A raw scheduleTask runs after the beams.
		BossScheduler.schedule(() -> {
			// Timed from the actual spawn, not the animation start.
			dragonSpawnTick.put(color, Utils.phaseTick());
			Utils.timer("<yellow>" + dragonName + "</bold><yellow> spawned in " + formatTick());
			EnderDragon dragon = (EnderDragon) world.spawnEntity(spawnLocation, EntityType.ENDER_DRAGON);
			dragons.put(color, dragon);
			dragon.setSilent(true);
			dragon.setPersistent(true);
			dragon.setRemoveWhenFarAway(false);
			// Formatted, not "1B": Derpy doubles the dragons too.
			dragon.customName(Utils.msg("<gold><bold>﴾ <red>" + dragonName + "<gold> ﴿ </bold><yellow>"
					+ Utils.formatHealthM(damage.MobStats.WITHERED_DRAGON.internalHealth()) + "<red>❤"));
			dragon.setCustomNameVisible(true);
			dragon.getAttribute(Attribute.MAX_HEALTH).setBaseValue(damage.MobStats.WITHERED_DRAGON.internalHealth());
			dragon.getAttribute(Attribute.ARMOR).setBaseValue(0);
			dragon.setHealth(damage.MobStats.WITHERED_DRAGON.internalHealth());
			dragon.addScoreboardTag("WitherKingDragon");
			// Parts sit at the origin until the first aiStep places them, and older arrows tick before the dragon, so
			// without this no arrow can hit on the spawn tick.
			((org.bukkit.craftbukkit.entity.CraftEnderDragon) dragon).getHandle().aiStep();
			aliveCount++;
			if(color.equals(lastTimerDragon)) lastTimerSpawned = true;
			Utils.playGlobalSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 1.0f);
			Utils.playGlobalSound(Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.0f);
			Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 1.0f);
			Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 2.0f, 1.0f);
		}, 100);
	}

	/** Arrow "ding" (the clear's 300-score sound), a per-player title, and a floating tick countdown. */
	private static void announceDragonSpawn(String color, String dragonName, Location spawnLocation) {
		Utils.playGlobalSound(Sound.ENTITY_ARROW_HIT_PLAYER, 2.0f, 0.5f);

		Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(40 * 50L), Duration.ofMillis(10 * 50L));
		Title title = Title.title(Utils.msg(dragonName + " <yellow>spawning!"), Utils.msg(""), times);
		for(Player p : Bukkit.getOnlinePlayers()) {
			p.showTitle(title);
		}

		startDragonCountdown(spawnLocation);
	}

	/** "100t" … "1t" above the spawn point, then removes itself. */
	private static void startDragonCountdown(Location spawnLocation) {
		Location loc = spawnLocation.clone().add(0, 3, 0);
		TextDisplay display = world.spawn(loc, TextDisplay.class, d -> {
			d.text(Utils.msg("<green>" + DRAGON_SPAWN_ANIM + "t"));
			d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
			d.setSeeThrough(true);
			d.setPersistent(true);
			d.addScoreboardTag("WitherKingDragon"); // swept by forceCleanup with the dragons
			d.setTransformation(new Transformation(new Vector3f(0f, 0f, 0f), new AxisAngle4f(0f, 0f, 0f, 1f),
					new Vector3f(DRAGON_COUNTDOWN_SCALE, DRAGON_COUNTDOWN_SCALE, DRAGON_COUNTDOWN_SCALE), new AxisAngle4f(0f, 0f, 0f, 1f)));
		});
		BukkitTask[] holder = new BukkitTask[1];
		int[] remaining = {DRAGON_SPAWN_ANIM};
		holder[0] = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), () -> {
			remaining[0]--;
			if(remaining[0] <= 0 || !display.isValid()) {
				if(display.isValid()) display.remove();
				holder[0].cancel();
				countdownTasks.remove(holder[0]);
				return;
			}
			display.text(Utils.msg("<green>" + remaining[0] + "t"));
		}, 1L, 1L);
		countdownTasks.add(holder[0]);
	}

	/** From {@code damage.Damage.deal}'s kill chokepoint. Next event dragon once the arena is clear; the fifth starts the death sequence. */
	public static void handleDragonKilled(EnderDragon dragon) {
		if(dragon == null || dyingDragons.contains(dragon.getUniqueId())) return;
		dyingDragons.add(dragon.getUniqueId());
		String color = colorOf(dragon);
		int elapsed = Utils.phaseTick() - dragonSpawnTick.getOrDefault(color, Utils.phaseTick());
		Utils.timer("<yellow>" + dragonName(color) + "</bold><yellow> killed in " + formatDragonKillTick(elapsed));
		instaKillDragon(dragon);
		aliveCount--;

		boolean isFinalDragon = eventQueue.isEmpty() && aliveCount <= 0;
		if(isFinalDragon) {
			playDragonDeathSound(false);
			deathSequence();
		} else {
			playDragonDeathSound(true);
			// Guarded on lastTimerSpawned so an early death in the opening pair can't trigger it.
			if(lastTimerSpawned && aliveCount <= 0 && !eventQueue.isEmpty()) {
				spawnDragon(eventQueue.poll());
			}
		}
	}

	/** Playing its death animation. instaKillDragon pins HP to 1, so isDead()/getHealth() can't tell; the UUID set is authoritative. */
	public static boolean isDyingDragon(Entity e) {
		return e != null && dyingDragons.contains(e.getUniqueId());
	}

	/** Death animation in place, not a flight to 0,0,0: reflects targetLocation to here and dragonDeathTime to 1. */
	public static void instaKillDragon(EnderDragon dragon) {
		if(!(dragon instanceof CraftEnderDragon craftDragon)) return;
		net.minecraft.world.entity.boss.enderdragon.EnderDragon nmsDragon = craftDragon.getHandle();
		nmsDragon.getPhaseManager().setPhase(EnderDragonPhase.DYING);
		DragonPhaseInstance phase = nmsDragon.getPhaseManager().getCurrentPhase();
		if(phase instanceof DragonDeathPhase deathPhase) {
			try {
				Field targetField = DragonDeathPhase.class.getDeclaredField("targetLocation");
				targetField.setAccessible(true);
				Location l = dragon.getLocation();
				targetField.set(deathPhase, new Vec3(l.getX(), l.getY(), l.getZ()));
			} catch(ReflectiveOperationException e) {
				Bukkit.getLogger().warning("Failed to force dragon death targetLocation: " + e.getMessage());
			}
		}
		nmsDragon.setDeltaMovement(Vec3.ZERO);
		nmsDragon.setHealth(1.0F);
		try {
			Field deathTimeField = nmsDragon.getClass().getDeclaredField("dragonDeathTime");
			deathTimeField.setAccessible(true);
			deathTimeField.setInt(nmsDragon, 1);
		} catch(ReflectiveOperationException e) {
			Bukkit.getLogger().warning("Failed to force dragon dragonDeathTime: " + e.getMessage());
		}
	}

	public static void playDragonDeathSound(boolean sendMessage) {
		for(int i = 0; i < 191; i += 10) {
			Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f), i);
		}
		Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 1.0f), 200);
		if(witherKing.getHealth() == 1) {
			witherKing.setHealth(0.001);
		} else {
			witherKing.setHealth(witherKing.getHealth() - 1);
		}
		if(sendMessage) {
			sendChatMessage(dragonDieMessage[random.nextInt(dragonDieMessage.length)]);
		}
	}

	// ============================== Death / end ==============================

	public static void deathSequence() {
		// M7Bridge.dialogueHoldTicks holds 240t after the score. Signal + hold is 250.
		sendChatMessage("Incredible.  You did what I couldn't do myself.");
		Utils.scheduleTask(() -> sendChatMessage("In a way, I should thank you.  I lost all hope centuries ago that it would ever end."), 60);
		Utils.scheduleTask(() -> sendChatMessage("I hope you'll become the Heroes I could never be."), 120);
		Utils.scheduleTask(() -> sendChatMessage("So long champions of this mad world!"), 180);
		Utils.scheduleTask(() -> sendChatMessage("My strengths are depleting.  This... this is it."), 240);
		Utils.scheduleTask(() -> { if(witherKing != null && witherKing.isValid()) witherKing.remove(); }, 300);

		Utils.scheduleTask(WitherKing::printFinalMessage, END_DELAY_TICKS);

		// Signal at the SCOREBOARD (t=10), not the dialogue's end (t=250): a party walking out mid-dialogue lost the
		// run. The network holds teardown 240t (M7Bridge.dialogueHoldTicks) so the spectator drop doesn't move.
		Utils.scheduleTask(WitherActions::signalRunComplete, END_DELAY_TICKS);
	}

	/** Chained run: live splits. Standalone WK practice: one line. */
	private static void printFinalMessage() {
		WitherActions.recordSplit("WitherKing", Utils.runTick());
		WitherActions.recordPhaseDuration("WitherKing", Utils.phaseTick());

		if(standalone) {
			Bukkit.broadcast(Utils.msg("<green>You defeated the <red><bold>Wither King</bold><green>"
					+ " in " + formatWithSpaces(Utils.phaseTick()) + " ticks!  Try doing the full run to see how you fare."));
			Utils.playGlobalSound(Sound.ENTITY_PLAYER_LEVELUP);
			Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 2f, 1f), 1);
		} else {
			printPracticeScoreboard();
		}

		// Freeze the result with the scoreboard so it matches the printed numbers and a mid-dialogue leave can't wipe it.
		WitherActions.captureRunResult();
	}

	/** Chained practice run: real splits. Boss-only runs end with "Clear: Skipped". */
	private static void printPracticeScoreboard() {
		int overall = Utils.runTick();
		boolean clearRan = WitherActions.getSplitEnd("Clear") != null;

		// Split = end − previous end.
		Map<String, Integer> sp = new HashMap<>();
		int prev = 0;
		for(String s : new String[]{"Clear", "Maxor", "Storm", "Terminals", "Goldor", "Necron", "WitherKing"}) {
			Integer end = WitherActions.getSplitEnd(s);
			if(end == null) continue;
			sp.put(s, end - prev);
			prev = end;
		}

		// One message, not one per line: client mods drop a line identical to an earlier one (the bottom bar, blank lines).
		List<Component> out = new ArrayList<>();
		out.add(Utils.msg("<green><bold>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		out.add(Utils.msg("                <red>Master Mode The Catacombs <dark_gray>- <yellow>Floor VII"));
		out.add(Utils.msg(""));
		if(clearRan) {
			out.add(Utils.msg("                           <white>Team Score: <green>" + instructions.clear.ClearManager.teamScore()
					+ " <white>(<aqua><bold>" + instructions.clear.ClearManager.grade() + "</bold><white>)"));
		} else {
			out.add(Utils.msg("                           <white>Team Score: <red>Skipped"));
		}
		out.add(Utils.msg(" <red>☠ <yellow>Defeated <red>Maxor, Storm, Goldor, and Necron <yellow>in <green>" + overall + " ticks"));
		out.add(Utils.msg("                         <green>" + formatTime(overall)));
		out.add(Utils.msg(""));
		out.add(Utils.msg("                              <gold>> <yellow><bold>EXTRA INFO </bold><gold>\\<"));
		out.add(Utils.msg("                                   <green><bold>SPLITS"));
		List<String> segs = new ArrayList<>();
		if(clearRan) {
			segs.add(seg("<blue>", "Clear", sp.get("Clear")));
			segs.add(seg("<aqua>", "Maxor", sp.get("Maxor")));
			segs.add(seg("<red>", "Storm", sp.get("Storm")));
			segs.add(seg("<yellow>", "Terminals", sp.get("Terminals")));
			segs.add(seg("<gold>", "Goldor", sp.get("Goldor")));
			segs.add(seg("<dark_red>", "Necron", sp.get("Necron")));
			segs.add(seg("<gray>", "Wither King", sp.get("WitherKing")));
		} else {
			segs.add(seg("<aqua>", "Maxor", sp.get("Maxor")));
			segs.add(seg("<red>", "Storm", sp.get("Storm")));
			segs.add(seg("<yellow>", "Terminals", sp.get("Terminals")));
			segs.add(seg("<gold>", "Goldor", sp.get("Goldor")));
			segs.add(seg("<dark_red>", "Necron", sp.get("Necron")));
			segs.add(seg("<gray>", "Wither King", sp.get("WitherKing")));
			segs.add(Utils.mmLegacy("<blue><bold>Clear</bold><white>: Skipped"));
		}
		for(String line : packLines(segs)) {
			out.add(Utils.nameComponent(ChatFont.centerPad(line)));
		}
		out.add(Utils.msg(""));
		out.add(Utils.nameComponent(ChatFont.centerPad(Utils.mmLegacy("<green><bold>PLAYERS"))));
		for(String line : packPlayerLines()) {
			out.add(Utils.nameComponent(ChatFont.centerPad(line)));
		}
		out.add(Utils.msg(""));
		out.add(Utils.msg("   <green><bold>Plugin by </bold><aqua>Stradivarius Violin<green>, also known as <aqua>Beethoven_"));
		out.add(Utils.msg("    <red><bold>YOUTUBE</bold><aqua>: https://www.youtube.com/@Stradivarius_Violin"));
		out.add(Utils.msg("               <blue><bold>DISCORD</bold><aqua>: https://discord.gg/gNfPwa8"));
		out.add(Utils.msg("<green><bold>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		Bukkit.broadcast(Component.join(JoinConfiguration.newlines(), out));

		Utils.playGlobalSound(Sound.ENTITY_PLAYER_LEVELUP);
		Utils.scheduleTask(() -> Utils.playGlobalSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 2f, 1f), 1);
	}

	private static String seg(String colorTag, String label, Integer ticks) {
		return Utils.mmLegacy(colorTag + "<bold>" + label + "</bold><white>: " + (ticks == null ? "-" : formatWithSpaces(ticks)) + " ticks");
	}

	/** Legacy §-string, for {@link ChatFont} width. */
	private static final String SEG_SEP = Utils.mmLegacy("<white> | ");

	/** Greedy: as many per line as fit {@link ChatFont#WRAP_WIDTH}. */
	private static List<String> packLines(List<String> segments) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for(String s : segments) {
			if(line.isEmpty()) {
				line.append(s);
				continue;
			}
			String candidate = line + SEG_SEP + s;
			if(ChatFont.width(candidate) > ChatFont.WRAP_WIDTH) {
				lines.add(line.toString());
				line = new StringBuilder(s);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if(!line.isEmpty()) lines.add(line.toString());
		return lines;
	}

	/** Spectators excluded. Gold if Adventure the whole run (minor anti-cheat), else white. */

	private static List<String> packPlayerLines() {
		List<String> names = new ArrayList<>();
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(Utils.isSpectator(p)) continue;
			String colorTag = WitherActions.stayedAdventure(p) ? "<gold>" : "<white>";
			names.add(Utils.mmLegacy(colorTag + p.getName()));
		}
		if(names.isEmpty()) names.add(Utils.mmLegacy("<gray>(none)"));
		return packLines(names);
	}

	// ============================== Cleanup / helpers ==============================

	/** Removes relic/altar/King/dragon entities and resets per-fight state. */
	public static void forceCleanup(World w) {
		world = w;
		if(rotationTask != null && !rotationTask.isCancelled()) {
			rotationTask.cancel();
			rotationTask = null;
		}
		rotationAngle = 0f;
		if(growthTask != null && !growthTask.isCancelled()) {
			growthTask.cancel();
			growthTask = null;
		}
		if(witherKing != null) {
			witherKing.remove();
			witherKing = null;
		}
		for(EnderDragon dragon : dragons.values()) {
			if(dragon != null && dragon.isValid()) dragon.remove();
		}
		dragons.clear();
		dyingDragons.clear();
		dragonSpawnTick.clear();

		// Their TextDisplays are swept below by tag.
		for(BukkitTask t : countdownTasks) if(t != null && !t.isCancelled()) t.cancel();
		countdownTasks.clear();

		for(ItemDisplay d : statueDisplays.values()) if(d != null && d.isValid()) d.remove();
		for(Interaction i : statueInteractions.values()) if(i != null && i.isValid()) i.remove();
		for(ItemDisplay d : altarWoolDisplays) if(d != null && d.isValid()) d.remove();
		statueDisplays.clear();
		statueInteractions.clear();
		altarWoolDisplays.clear();
		interactionRelic.clear();
		placedRelics.clear();

		// Orphans from a prior run.
		if(world != null) {
			for(org.bukkit.entity.Entity e : world.getEntities()) {
				if(e.getScoreboardTags().contains("TASWitherKingRelic") || e.getScoreboardTags().contains("WitherKingDragon")) {
					e.remove();
				}
			}
		}

		aliveCount = 0;
		lastTimerSpawned = false;
		eventQueue.clear();
	}

	private static void removeRelicEntities(Relic relic) {
		ItemDisplay display = statueDisplays.remove(relic);
		if(display != null && display.isValid()) display.remove();
		Interaction interaction = statueInteractions.remove(relic);
		if(interaction != null) {
			interactionRelic.remove(interaction.getUniqueId());
			if(interaction.isValid()) interaction.remove();
		}
	}

	private static String formatTime(int ticks) {
		double secs = ticks / 20.0;
		int mins = (int) (secs / 60);
		double rem = secs - mins * 60.0;
		return String.format("%.2f seconds | %d:%05.2f", secs, mins, rem);
	}

	/** 4404 → "4 404". */
	private static String formatWithSpaces(int n) {
		StringBuilder sb = new StringBuilder();
		String s = String.valueOf(n);
		for(int i = 0; i < s.length(); i++) {
			if(i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
			sb.append(s.charAt(i));
		}
		return sb.toString();
	}

	/** Phase ticks + overall column (live in practice). */
	private static String formatTick() {
		int t = Utils.phaseTick();
		int overall = Utils.runTick();
		return "<green>" + String.format("%s ticks (%.2f seconds) | Overall: %s ticks (%.2f seconds)",
				formatWithSpaces(t), t / 20.0, formatWithSpaces(overall), overall / 20.0);
	}

	/** Since that dragon spawned, then the WK clock, then overall. */
	private static String formatDragonKillTick(int dragonElapsed) {
		int phase = Utils.phaseTick();
		int overall = Utils.runTick();
		return "<green>" + String.format("%s ticks (%.2f seconds) | Wither King: %s ticks (%.2f seconds) | Overall: %s ticks (%.2f seconds)",
				formatWithSpaces(dragonElapsed), dragonElapsed / 20.0,
				formatWithSpaces(phase), phase / 20.0,
				formatWithSpaces(overall), overall / 20.0);
	}

	private static void sendChatMessage(String message) {
		Bukkit.broadcast(Utils.msg("<dark_red>[BOSS] <red><obfuscated>Wither-King</obfuscated>: " + message));
	}
}
