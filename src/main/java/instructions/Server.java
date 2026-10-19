package instructions;

import instructions.bosses.CustomBossBar;
import instructions.bosses.Watcher;
import instructions.bosses.goldor.Goldor;
import instructions.bosses.maxor.Maxor;
import instructions.bosses.necron.Necron;
import instructions.bosses.storm.Storm;
import instructions.bosses.witherking.WitherKing;
import listeners.GoldorListener;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import plugin.ChatFont;
import plugin.M7;
import plugin.Utils;

import java.util.Objects;

public class Server {
	private static final Zombie[] archaeologists = new Zombie[10];
	private static Zombie yellowShadowAssassin = null;
	private static final LivingEntity[] trashMobs = new LivingEntity[18]; // each 1x1 has 6 mobs spawned

	// --- Clear-phase keys & doors ---
	// Team-wide keys from the Angry Archaeologists; any player opens the door by clicking inside its bounds (MiscListener).
	private static boolean hasWitherKey = false;
	private static boolean hasBloodKey = false;
	private static boolean witherDoorOpened = false;
	private static boolean bloodDoorOpened = false;
	// False from serverSetup() until startSection(), so doors can't open during the countdown. Gates the door, not
	// the key: refusing a key mid-countdown would kill the archaeologist without granting it, door stuck forever.
	private static boolean runStarted = false;

	// {minX, minY, minZ, maxX, maxY, maxZ} inclusive, matching the openXxxDoor() fills.
	private static final int[] START_DOOR = {-122, 69, -170, -120, 72, -168};
	private static final int[] WITHER_DOOR = {-122, 69, -106, -120, 72, -104};
	private static final int[] BLOOD_DOOR = {-122, 69, -74, -120, 72, -72};

	public static void resetClearState() {
		hasWitherKey = false;
		hasBloodKey = false;
		witherDoorOpened = false;
		bloodDoorOpened = false;
		runStarted = false;
		instructions.clear.ClearManager.reset();
	}

	public static boolean isRunStarted() {
		return runStarted;
	}

	public static boolean hasWitherKey() {
		return hasWitherKey;
	}

	public static boolean hasBloodKey() {
		return hasBloodKey;
	}

	public static void grantWitherKey(Player picker) {
		if(hasWitherKey) return;
		hasWitherKey = true;
		String name = picker != null ? picker.getName() : "Someone";
		Bukkit.broadcast(Utils.msg("<gold>[MVP<dark_blue>++<gold>] <name> <green>has obtained <dark_gray>Wither Key<green>!", Placeholder.unparsed("name", name)));
		if(picker != null) Utils.playLocalSound(picker, Sound.ENTITY_ITEM_PICKUP, 2.0f, 1.0f);
	}

	public static void grantBloodKey(Player picker) {
		if(hasBloodKey) return;
		hasBloodKey = true;
		String name = picker != null ? picker.getName() : "Someone";
		Bukkit.broadcast(Utils.msg("<gold>[MVP<dark_blue>++<gold>] <name> <green>has obtained <red>Blood Key<green>!", Placeholder.unparsed("name", name)));
		if(picker != null) Utils.playLocalSound(picker, Sound.ENTITY_ITEM_PICKUP, 2.0f, 1.0f);
	}

	private static boolean inBounds(Block b, int[] d) {
		return b.getX() >= d[0] && b.getX() <= d[3] && b.getY() >= d[1] && b.getY() <= d[4] && b.getZ() >= d[2] && b.getZ() <= d[5];
	}

	public static boolean inStartDoor(Block b) {
		return inBounds(b, START_DOOR);
	}

	public static boolean inWitherDoor(Block b) {
		return inBounds(b, WITHER_DOOR);
	}

	public static boolean inBloodDoor(Block b) {
		return inBounds(b, BLOOD_DOOR);
	}

	/** On a door click: one-shot, needs run started and the key. */
	public static void tryOpenWitherDoor(Player p) {
		if(!runStarted || !hasWitherKey || witherDoorOpened) return;
		witherDoorOpened = true;
		openWitherDoor(p);
	}

	/** On a door click: one-shot, needs run started and the key. */
	public static void tryOpenBloodDoor() {
		if(!runStarted || !hasBloodKey || bloodDoorOpened) return;
		bloodDoorOpened = true;
		openBloodDoor();
	}

	public static void serverInstructions(World world, String section) {
		serverInstructions(world, section, 60);
	}

	/**
	 * @param delayTicks pre-run "get into position" window before the countdown. 60 (3s) by default; the network
	 *   passes more (e.g. 400) when warping a party in. Ignored by boss/maxor: Maxor starts on entry on Hypixel, so
	 *   those get only {@link #MAXOR_GRACE_TICKS} and no countdown.
	 */
	public static void serverInstructions(World world, String section, int delayTicks) {
		// Kill a lingering Goldor phase now, or a re-run's first sharpshooter arrows land in the old active S4 and are rejected.
		Goldor.INSTANCE.forceEndPhase();

		// Starting pet (editor slot 51). At setup, not startSection (countdown end), or the warp-in runs on last
		// run's pet and Max Speed. Late arrivals get it on join (PetMenu.onJoin); autopet RUN_START still wins.
		pets.Pets.applyStartingPets();

		if(section.equals("boss") || section.equals("maxor")) {
			listeners.OutOfBounds.schedulePreRunReturns(world, section, MAXOR_GRACE_TICKS);
			Utils.scheduleTask(() -> startSection(world, section), MAXOR_GRACE_TICKS);
			return;
		}

		// The delay IS the countdown, starting now.
		int ticks = Math.max(1, delayTicks);
		listeners.OutOfBounds.schedulePreRunReturns(world, section, ticks);
		countdownThenStart(world, section, ticks);
	}

	/** Enough for warped-in clients to load. */
	public static final int MAXOR_GRACE_TICKS = 20;

	/**
	 * "Starting in N seconds", then start exactly {@code ticks} later. Only the first second, 10 and the last five are
	 * announced so a long warp-in doesn't spam. First message at tick 0, rounded up; the rest on whole seconds before
	 * the start. Every section but boss/maxor.
	 */
	private static void countdownThenStart(World world, String section, int ticks) {
		int seconds = (ticks + 19) / 20;
		for(int i = seconds; i >= 1; i--) {
			int secs = i;
			if(secs != seconds && secs > 5 && secs != 10) continue;
			Utils.scheduleTask(() -> {
				Bukkit.broadcast(Utils.msg("<green>Starting in " + secs + " second" + (secs == 1 ? "" : "s")));
				Utils.playGlobalSound(Sound.BLOCK_LEVER_CLICK, 2.0F, 1.0F);
			}, secs == seconds ? 0 : ticks - secs * 20L);
		}
		Utils.scheduleTask(() -> {
			Bukkit.broadcast(Utils.msg("<green>Run started"));
			startSection(world, section);
		}, ticks);
	}

	/** Callers own the delay/countdown. */
	private static void startSection(World world, String section) {
		// Every section starts here, so this is "the run is live".
		runStarted = true;
		switch(section) {
			case "all", "clear" -> {
				Utils.markPhaseStart();
				Utils.playGlobalSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0F, 1.0F);
				// One-shot Blood Room detection; Watcher spawns on first entry.
				Watcher.INSTANCE.beginDetection(world);
				// Inside this branch on purpose: "run start" means the first clear door. Above the switch it fired
				// for every section, e.g. at the Wither King in a WK-only practice.
				pets.Autopet.onRunStart(); // no-op outside realistic
				openFirstDoor();
				spawnMinibosses(world);
				instructions.clear.ClearManager.start(world);
			}
			case "boss" -> Maxor.maxorInstructions(world, true);
			case "maxor" -> Maxor.maxorInstructions(world, false);
			case "storm" -> Storm.stormInstructions(world, false);
			case "goldor" -> {
				Utils.runCommand("fill 62 136 142 58 133 142 minecraft:lever[face=wall,facing=north,powered=true]");
				Utils.runCommand("fill 58 136 143 62 133 143 minecraft:redstone_lamp[lit=true]");
				Goldor.goldorInstructions(world, false);
			}
			case "necron" -> {
				Utils.scheduleTask(() -> Utils.runCommand("fill 53 63 113 55 63 115 minecraft:air"), 1);
				Necron.necronInstructions(world, false);
			}
			case "witherking" -> WitherKing.witherKingInstructions(world, true);
		}
	}

	/**
	 * Removes every entity except permanent fixtures. Run-end cleanups don't fire reliably, so aborted runs left
	 * orphans (mobs, stun indicators, terminal labels, lever hitboxes, quiz options, crystals, projectiles) stacking
	 * up. Every transient entity is respawned by the run that needs it, so nuking all closes every leak; replaces
	 * the old list-based {@code blanketKill}. Called from {@code /reset}, {@code /setup}, {@code Practice.runPractice}
	 * and {@code M7.onDisable}, always before spawns and never inside {@link #serverSetup} (would race its spawns).
	 *
	 * <p>Exclusions are exactly the types the plugin never spawns (only referenced in lists like this and
	 * {@code items.ItemUtils.doNotKill}), so they can't leak a run entity:
	 * <ul>
	 *   <li>{@code player};
	 *   <li>{@code villager}: Mort/Wizard NPCs;
	 *   <li>{@code item_frame}, {@code glow_item_frame}, {@code painting}, {@code block_display}: map decoration;
	 *   <li>{@code marker}: command anchors.
	 * </ul>
	 */
	private static boolean cleanupInProgress = false;

	public static boolean isCleanupInProgress() {
		return cleanupInProgress;
	}

	public static void hardMobCleanup() {
		cleanupInProgress = true;
		try {
			// remove(), not /kill: no EntityDeathEvent, so a purge never grants a key or blessing. getEntities() is a
			// snapshot, safe to remove while iterating.
			for(org.bukkit.World w : Bukkit.getWorlds()) {
				for(org.bukkit.entity.Entity e : w.getEntities()) {
					switch(e.getType()) {
						case PLAYER, VILLAGER, ITEM_FRAME, GLOW_ITEM_FRAME, PAINTING, BLOCK_DISPLAY, MARKER -> {
						}
						default -> e.remove();
					}
				}
			}
		} finally {
			cleanupInProgress = false;
		}
	}

	public static void serverSetup(World world) {
		items.ItemUtils.flushStonkRestorations();
		// Restore superboomed walls/crypts, despawn crypt mobs, clear pearl cooldowns.
		items.ItemUtils.flushBlockRestorations();
		for(Player pl : Bukkit.getOnlinePlayers()) {
			pl.setCooldown(Material.ENDER_PEARL, 0);
		}
		// Minibosses spawn in startSection, not here.
		resetClearState();
		Utils.runCommand("fill -122 69 -170 -120 72 -168 minecraft:chiseled_stone_bricks");
		Utils.runCommand("fill -69 82 -155 -69 74 -151 minecraft:air replace minecraft:barrier"); // clear any leftover Ice-Fill gate barriers
		Utils.runCommand("fill -69 82 -155 -69 74 -151 minecraft:iron_bars replace minecraft:air");
		Utils.runCommand("fill -120 69 -106 -122 72 -104 minecraft:coal_block");
		Utils.runCommand("fill -122 69 -74 -120 72 -72 minecraft:red_terracotta");
		Utils.runCommand("setblock 73 224 73 minecraft:black_stained_glass");
		Utils.runCommand("fill 62 136 142 58 133 142 minecraft:lever[face=wall,facing=north,powered=false]");
		Utils.runCommand("fill 58 136 143 62 133 143 minecraft:redstone_lamp[lit=false]");
		Utils.runCommand("fill 56 121 54 52 115 54 minecraft:gold_block");
		Utils.runCommand("clone 70 -5 120 38 -1 99 38 59 99");
		// Restore every boss-to-boss transition wall opened during a previous run.
		instructions.bosses.BossTransition.resetAll();
//		Utils.runCommand("setblock 54 64 79 minecraft:jungle_planks");
//		Utils.runCommand("setblock 54 64 80 minecraft:jungle_stairs");
//		Utils.runCommand("setblock 54 63 79 minecraft:stone_brick_slab[type=top]");
		CustomBossBar.forceCleanup();
		Watcher.forceCleanup();
		WitherKing.forceCleanup(world);
		Storm.INSTANCE.cleanupMobs();
		instructions.bosses.WitherSpawn.restoreStormPillars(world);
		Goldor.resetS3Device(world);
		Goldor.resetSectionLevers(world);
		// Restore S1 device blocks (lanterns, 16 buttons, "i1" sign). Outside the listener null-check: blocks outlive
		// the listener. Idempotent; resetSimon calls it again.
		instructions.bosses.goldor.GoldorSimonSays.INSTANCE.cleanup();
		if(GoldorListener.INSTANCE != null) {
			GoldorListener.INSTANCE.resetSharpShooter(world);
			GoldorListener.INSTANCE.resetSimon();
		}
//		turnArrow(world, false);
	}

	private static void spawnMinibosses(World world) {
		for(Zombie zombie : archaeologists) {
			if(zombie != null) {
				zombie.remove();
			}
		}

		Location[] locations = {new Location(world, -120.5, 69, -152.5, 90f, 0f), // Red Blue (I)
				new Location(world, -120.5, 67, -88.5, -90f, 0f), // Deathmite (II)
				new Location(world, -24.5, 69, -184.5, 0f, 0f), // Well (III)
				new Location(world, -56.5, 69, -48.5, -90f, 0f), // Gravel (III)
				new Location(world, -168.5, 65.0625, -104.5f, -180, 0f), // Museum (III)
				new Location(world, -35.5, 69, -120.5, 90f, 0f), // Dino Dig Site (IV)
				new Location(world, -184.5, 69, -28.5, -90f, 0f), // Market (IV)
				new Location(world, -152.5, 69, -24.5, -90f, 0f), // Hallway (V)
		};


		for(int i = 0; i < locations.length; i++) {
			Zombie zombie = (Zombie) world.spawnEntity(locations[i], EntityType.ZOMBIE);
			zombie.setCustomNameVisible(true);
			zombie.setAI(false);
			zombie.setSilent(true);
			zombie.setAdult();
			zombie.setPersistent(true);
			zombie.setRemoveWhenFarAway(false);
			// Real HP/defense/types (MAP.md §5): 12M scaled by room depth (+10% per tier), so the name comes after
			// apply(). Also clears the old negative armour attributes.
			damage.MobStats.apply(zombie, damage.MobStats.ANGRY_ARCHAEOLOGIST
					.atDepthOf(locations[i]));
			zombie.customName(Utils.msg("<light_purple><bold>Angry Archaeologist </bold><yellow>"
					+ Utils.formatHealthM(zombie) + "<red>❤"));

			zombie.getEquipment().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
			zombie.getEquipment().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
			zombie.getEquipment().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
			zombie.getEquipment().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
			zombie.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));

			// First (-120.5,69,-152.5) drops the Wither Key, the next the Blood Key.
			if(i == 0) zombie.addScoreboardTag("WitherKeyMob");
			else if(i == 1) zombie.addScoreboardTag("BloodKeyMob");
			// Kill white-checks and blesses its room (ClearListener).
			zombie.addScoreboardTag("ClearMiniboss");

			archaeologists[i] = zombie;
		}

		if(yellowShadowAssassin != null) {
			yellowShadowAssassin.remove();
		}

		Location assassinSpawn = new Location(world, -184.5, 69, -184.5, 0f, 0f);
		yellowShadowAssassin = (Zombie) world.spawnEntity(assassinSpawn, EntityType.ZOMBIE);
		yellowShadowAssassin.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, -1, 0));
		yellowShadowAssassin.setCustomNameVisible(true);
		yellowShadowAssassin.setAI(false);
		yellowShadowAssassin.setSilent(true);
		yellowShadowAssassin.setAdult();
		yellowShadowAssassin.setPersistent(true);
		yellowShadowAssassin.setRemoveWhenFarAway(false);
		yellowShadowAssassin.addScoreboardTag("ClearMiniboss"); // Yellow miniboss → green check + Wisdom V on kill
		// 140M x 1.10 (depth II) = 154M (MAP.md §5). Not Storm's 145M Shadow Assassins. Humanoid + Arcane: those two
		// Rulers, no Smite.
		damage.MobStats.apply(yellowShadowAssassin, damage.MobStats.YELLOW_SHADOW_ASSASSIN.atDepthOf(assassinSpawn));
		yellowShadowAssassin.customName(Utils.msg("<light_purple><bold>Shadow Assassin </bold><yellow>"
				+ Utils.formatHealthM(yellowShadowAssassin) + "<red>❤"));

		ItemStack boots = Utils.createLeatherArmor(Material.LEATHER_BOOTS, Color.PURPLE, Utils.mmLegacy("<light_purple>Shadow Assassin Boots"));
		yellowShadowAssassin.getEquipment().setBoots(boots);
		yellowShadowAssassin.getEquipment().setLeggings(new ItemStack(Material.AIR));
		yellowShadowAssassin.getEquipment().setChestplate(new ItemStack(Material.AIR));
		yellowShadowAssassin.getEquipment().setHelmet(new ItemStack(Material.AIR));
		yellowShadowAssassin.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
	}

	public static void openFirstDoor() {
		Utils.runCommand("fill -122 69 -170 -120 72 -168 minecraft:glass");
		Utils.scheduleTask(() -> Utils.runCommand("fill -122 69 -170 -120 72 -168 minecraft:air"), 15);
	}

	public static void openWitherDoor(Player p) {
		Utils.runCommand("fill -120 69 -106 -122 72 -104 minecraft:glass");
		Utils.scheduleTask(() -> Utils.runCommand("fill -120 69 -106 -122 72 -104 minecraft:air"), 15);
		Utils.playGlobalSound(Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 2.0F);
		Bukkit.broadcast(Utils.msg("<gold><name><green> opened a <dark_gray><bold>WITHER </bold><green>door!", Placeholder.unparsed("name", p.getName())));
		// Auto-explores the room behind it on the map.
		instructions.clear.ClearManager.exploreRoom(instructions.clear.Rooms.byName("Deathmite"));
	}

	public static void openBloodDoor() {
		Utils.runCommand("fill -122 69 -74 -120 72 -72 minecraft:glass");
		Utils.scheduleTask(() -> Utils.runCommand("fill -122 69 -74 -120 72 -72 minecraft:air"), 15);
		Utils.playGlobalSound(Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 2.0F);
		Utils.playGlobalSound(Sound.ENTITY_GHAST_HURT, 1.0F, 0.5F);
		Bukkit.broadcast(Utils.msg("<red>The <bold>BLOOD DOOR</bold> has been opened!"));
		Bukkit.broadcast(Utils.msg("<dark_purple>A shiver runs down your spine..."));
		// Watcher starts when the Blood Door opens, if armed and not already spawned by entry.
		Watcher.INSTANCE.startOnBloodDoor();
		// Auto-explores the room behind it on the map.
		instructions.clear.ClearManager.exploreRoom(instructions.clear.Rooms.BLOOD);
	}

	public static void openIceFillRewards() {
		// Iron bars → barriers for 20 ticks, then air (gate opens on a delay).
		Utils.runCommand("fill -69 82 -155 -69 74 -151 minecraft:barrier replace minecraft:iron_bars");
		Utils.scheduleTask(() -> Utils.runCommand("fill -69 82 -155 -69 74 -151 minecraft:air replace minecraft:barrier"), 20);
	}

	public static void playWitherDeathSound(Wither wither) {
		Utils.playGlobalSound(Sound.ENTITY_WITHER_DEATH);
		wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_SHOOT, 2.0F, 1.0F), 4);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 10);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_SHOOT, 2.0F, 1.0F), 14);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 20);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_SHOOT, 2.0F, 1.0F), 24);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 30);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_SHOOT, 2.0F, 1.0F), 34);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 40);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_SHOOT, 2.0F, 1.0F), 44);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 50);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_SHOOT, 2.0F, 1.0F), 54);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 60);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 70);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 80);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 90);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 100);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 110);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 120);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 130);
		Utils.scheduleTask(() -> wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_WITHER_HURT, 2.0F, 1.0F), 140);
		Utils.scheduleTask(wither::remove, 160);
	}

	public static Zombie spawnCryptLurker(Location loc, boolean isPrince) {
		Zombie zombie = (Zombie) Objects.requireNonNull(loc.getWorld()).spawnEntity(loc, EntityType.ZOMBIE);
		zombie.setAdult();
		// Clear random armor and prevent chicken jockey from finalizeSpawn
		zombie.getEquipment().setHelmet(null);
		zombie.getEquipment().setChestplate(null);
		zombie.getEquipment().setLeggings(null);
		zombie.getEquipment().setBoots(null);
		if(zombie.isInsideVehicle()) {
			Entity vehicle = zombie.getVehicle();
			zombie.leaveVehicle();
			if(vehicle != null) vehicle.remove();
		}
		zombie.setAI(false);
		zombie.setSilent(true);
		zombie.setPersistent(true);
		zombie.setRemoveWhenFarAway(false);
		zombie.setCustomNameVisible(true);
		zombie.getEquipment().setItemInMainHand(new ItemStack(Material.BONE));
		// +1 bonus per Crypt Lurker (cap 5), +1 for a Prince (ClearListener). damage/MobStats reads the tags, so set before apply().
		zombie.addScoreboardTag(instructions.clear.ClearManager.TAG_CRYPT);
		if(isPrince) zombie.addScoreboardTag("SecretPrince");
		// Crypt Undead 9M, Prince 1M, Undead + Subterranean (Smite + both Rulers), depth-scaled (MAP.md §5).
		damage.MobStats.apply(zombie, (isPrince ? damage.MobStats.PRINCE : damage.MobStats.CRYPT_UNDEAD).atDepthOf(loc));
		String mobName = isPrince ? "Prince" : "Crypt Lurker";
		zombie.customName(Utils.msg("<red>" + mobName + " ❤<yellow>" + Utils.formatHealthM(zombie)));
		return zombie;
	}

	public static class Quiz {
		private static final Location PARTICLE_START = new Location(null, -24.5, 85.5, -23.0);
		private static final double[][] OPTION_COORDS = {{-19.5, 71.5, -33.5}, {-24.5, 71.5, -30.5}, {-29.5, 71.5, -33.5}};
		private static final String[] OPTION_LABELS = {"ⓐ", "ⓑ", "ⓒ"};
		private static final float[] OPTION_PITCHES = {0.6f, 0.7f, 0.8f};
		private static final String ORUO = "<dark_red>[STATUE] Oruo the Omniscient<white>: ";

		public static void oruoMessage(String message) {
			Bukkit.broadcast(Utils.msg(ORUO + message));
			Utils.playGlobalSound(Sound.ENTITY_GUARDIAN_HURT, 2.0f, 0.5f);
		}

		/** Question animation without auto-answer. Fills {@code options} (a=+40t, b=+50t, c=+60t); accept answers after (c). */
		public static void animateQuestion(World world, Player player, int questionNum, String questionText, String[] answers, TextDisplay[] options) {
			Location particleStart = PARTICLE_START.clone();
			particleStart.setWorld(world);
			Bukkit.broadcast(Utils.msg(""));
			Bukkit.broadcast(Utils.msg("<gold>                                <bold>Question #" + questionNum));
			// Wrapped + centred at render time: a line naming a player can't know its width (PuzzleQuiz Q3).
			for(String line : ChatFont.centerLines(questionText)) {
				Bukkit.broadcast(Utils.msg("<gold>" + line));
			}
			Bukkit.broadcast(Utils.msg(""));
			for(int i = 0; i < 3; i++) {
				Bukkit.broadcast(Utils.msg("<gold>     " + OPTION_LABELS[i] + " <green>" + answers[i]));
			}
			Bukkit.broadcast(Utils.msg(""));
			Utils.playGlobalSound(Sound.ENTITY_GUARDIAN_HURT, 2.0f, 0.5f);
			for(int i = 0; i < 3; i++) {
				final int idx = i;
				Utils.scheduleTask(() -> spawnParticleTrail(world, particleStart, new Location(world, OPTION_COORDS[idx][0], OPTION_COORDS[idx][1], OPTION_COORDS[idx][2])), idx * 10);
			}
			for(int i = 0; i < 3; i++) {
				final int idx = i;
				Utils.scheduleTask(() -> {
					options[idx] = spawnOption(world, new Location(world, OPTION_COORDS[idx][0], OPTION_COORDS[idx][1], OPTION_COORDS[idx][2]), Utils.mmLegacy("<gold>" + OPTION_LABELS[idx] + " <green>" + answers[idx]));
					if(player != null) Utils.playLocalSound(player, Sound.ENTITY_ITEM_PICKUP, 2.0f, OPTION_PITCHES[idx]);
				}, 40 + idx * 10);
			}
		}

		/** Remove the floating option labels spawned by {@link #animateQuestion}. */
		public static void removeOptions(TextDisplay[] options) {
			for(int i = 0; i < options.length; i++) {
				if(options[i] != null) {
					options[i].remove();
					options[i] = null;
				}
			}
		}

		private static TextDisplay spawnOption(World world, Location loc, String text) {
			TextDisplay td = (TextDisplay) world.spawnEntity(loc, EntityType.TEXT_DISPLAY);
			td.text(Utils.nameComponent(text));
			td.setAlignment(TextDisplay.TextAlignment.CENTER);
			td.setBillboard(Display.Billboard.CENTER);
			return td;
		}

		private static void spawnParticleTrail(World world, Location start, Location end) {
			int duration = 40;
			double dx = (end.getX() - start.getX()) / duration;
			double dy = (end.getY() - start.getY()) / duration;
			double dz = (end.getZ() - start.getZ()) / duration;
			for(int i = 0; i <= duration; i++) {
				final int tick = i;
				Utils.scheduleTask(() -> world.spawnParticle(Particle.HAPPY_VILLAGER, start.getX() + dx * tick, start.getY() + dy * tick, start.getZ() + dz * tick, 1, 0, 0, 0, 0), i);
			}
		}
	}

	public static class IceFill {
		public static void playIceFillSounds(int level, Player player) {
			switch(level) {
				case 1 -> {
					Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 1.189446f);
					Utils.scheduleTask(() -> Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 1.301903f), 5); // avg of the first & third note
					Utils.scheduleTask(() -> Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 1.41436f), 10);
				}
				case 2 -> {
					Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 1.4987f);
					Utils.scheduleTask(() -> Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 1.5878f), 5);
					Utils.scheduleTask(() -> Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 1.6821f), 10);
				}
				case 3 -> {
					Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 1.782f);
					Utils.scheduleTask(() -> Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 1.888f), 5);
					Utils.scheduleTask(() -> Utils.playLocalSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 2.0f), 10);
				}
			}
		}
	}
}