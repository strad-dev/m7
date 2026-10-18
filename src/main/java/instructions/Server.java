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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import plugin.ChatFont;
import plugin.M7tas;
import plugin.Utils;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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
		String name = picker != null ? Utils.getRealName(picker) : "Someone";
		Bukkit.broadcast(Utils.msg("<gold>[MVP<dark_blue>++<gold>] <name> <green>has obtained <dark_gray>Wither Key<green>!", Placeholder.unparsed("name", name)));
		if(picker != null) Utils.playLocalSound(picker, Sound.ENTITY_ITEM_PICKUP, 2.0f, 1.0f);
	}

	public static void grantBloodKey(Player picker) {
		if(hasBloodKey) return;
		hasBloodKey = true;
		String name = picker != null ? Utils.getRealName(picker) : "Someone";
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
			Utils.scheduleTask(() -> startSection(world, section), MAXOR_GRACE_TICKS);
			return;
		}

		int seconds = Math.max(1, delayTicks / 20);
		if(instructions.bosses.WitherActions.isPracticeMode()) {
			// Practice: the delay IS the countdown, starting now.
			countdownThenStart(world, section, seconds);
		} else {
			// TAS: window then 5s countdown. Fake-player choreography is calibrated to this timing, don't touch.
			Bukkit.broadcast(Utils.msg("TAS starts in " + seconds + " seconds"));
			Utils.scheduleTask(() -> countdownThenStart(world, section, 5), delayTicks);
		}
	}

	/** Enough for warped-in clients to load. */
	private static final int MAXOR_GRACE_TICKS = 20;

	/**
	 * "Starting in N seconds", then start. Only the first second, 10 and the last five are announced so a long warp-in
	 * doesn't spam. First message at tick 0. Every section but boss/maxor.
	 */
	private static void countdownThenStart(World world, String section, int seconds) {
		for(int i = seconds; i >= 1; i--) {
			int secs = i;
			if(secs != seconds && secs > 5 && secs != 10) continue;
			Utils.scheduleTask(() -> {
				Bukkit.broadcast(Utils.msg("<green>Starting in " + secs + " second" + (secs == 1 ? "" : "s")));
				Utils.playGlobalSound(Sound.BLOCK_LEVER_CLICK, 2.0F, 1.0F);
			}, (seconds - i) * 20L);
		}
		Utils.scheduleTask(() -> {
			Bukkit.broadcast(Utils.msg("<green>Run started"));
			startSection(world, section);
		}, seconds * 20L);
	}

	/** Callers own the delay/countdown. */
	private static void startSection(World world, String section) {
		// Every section starts here, so this is "the run is live".
		runStarted = true;
		switch(section) {
			case "all", "clear" -> {
				Utils.markPhaseStart();
				Utils.playGlobalSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0F, 1.0F);
				// One-shot Blood Room detection; Watcher spawns on first entry (Maxor handoff armed in TAS.runTAS).
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
	 * the old list-based {@code blanketKill}. Called from {@code /reset}, {@code /setup}, {@code TAS.runPractice}
	 * and {@code M7tas.onDisable}, always before spawns and never inside {@link #serverSetup} (would race its spawns).
	 *
	 * <p>Exclusions are exactly the types the plugin never spawns (only referenced in lists like this and
	 * {@code items.ItemUtils.doNotKill}), so they can't leak a run entity:
	 * <ul>
	 *   <li>{@code player}: real and fake;
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
		Bukkit.broadcast(Utils.msg("<gold><name><green> opened a <dark_gray><bold>WITHER </bold><green>door!", Placeholder.unparsed("name", Utils.getRealName(p))));
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

		private static void spawnQuestion(int questionNum, String questionText, String[] answers, TextDisplay[] options, Location particleStart, Player player, World world) {
			Bukkit.broadcast(Utils.msg(""));
			Bukkit.broadcast(Utils.msg("<gold>                                <bold>Question #" + questionNum));
			Bukkit.broadcast(Utils.msg("<gold>" + questionText));
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
			Utils.scheduleTask(() -> {
				answeredCorrectly(questionNum, options);
				Actions.rightClick(player);
			}, 61);
		}

		private static void answeredCorrectly(int questionNum, TextDisplay[] options) {
			Bukkit.broadcast(Utils.msg(ORUO + "<gold>akc0303 <green>answered <gold>Question #" + questionNum + "<green> correctly!"));
			Utils.playGlobalSound(Sound.ENTITY_GUARDIAN_HURT, 2.0f, 0.5f);
			Utils.playGlobalSound(Sound.ENTITY_PLAYER_LEVELUP, 2.0f, 0.75f);
			options[0].remove();
			options[1].remove();
			options[2].remove();
		}

		public static void run(Player player, World world) {
			Utils.scheduleTask(() -> {
				Actions.turnHead(player, 0f, 5f);
				Actions.setHotbarSlot(player, 5);
			}, 137);
			Utils.scheduleTask(() -> oruoMessage("Prove your knowledge by answering 3 questions and I shall reward you in ways that transcend time!"), 155);
			Utils.scheduleTask(() -> oruoMessage("Answer incorrectly, and your moment of ineptitude will live on for generations."), 195);
			final TextDisplay[] options = new TextDisplay[3];
			Location particleStart = PARTICLE_START.clone();
			particleStart.setWorld(world);
			Utils.scheduleTask(() -> spawnQuestion(1, "                      How is the run going so far?", new String[]{"Alright", "Trash", "Literally tick-perfect"}, options, particleStart, player, world), 235);
			Utils.scheduleTask(() -> oruoMessage("2 question left... then you will have proven your worth to me!"), 336);
			Utils.scheduleTask(() -> spawnQuestion(2, "Did you know that you can sub scribe to Stradivarius Violin to                         see more content like this?!", new String[]{"Oh wow, I should sub scribe!", "Oh wow, I should sub scribe!!", "Oh wow, I should sub scribe!!!"}, options, particleStart, player, world), 376);
			Utils.scheduleTask(() -> oruoMessage("One more question!"), 477);
			Utils.scheduleTask(() -> spawnQuestion(3, "                             Is akc0303 bald?", new String[]{"No", "Yes", "Decline to Answer"}, options, particleStart, player, world), 517);
			Utils.scheduleTask(() -> {
				Bukkit.broadcast(Utils.msg("<dark_green>Archer: Quiz Cleared"));
				Bukkit.broadcast(Utils.msg("<dark_green>Archer: Clear Finished in 578 Ticks (28.90 seconds)"));
			}, 578);
			Utils.scheduleTask(() -> oruoMessage("I bestow upon you all the power of a hundred years!"), 598);
			Utils.scheduleTask(() -> Utils.broadcastBlessing(player, Utils.BlessingType.TIME, 5), 618);
		}
	}

	public static class IceFill {
		private static BukkitTask iceFillTask;
		private static final Set<Block> frozenBlocks = new HashSet<>();

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

		private static void startIceFillTask(Player player) {
			if(iceFillTask != null) {
				iceFillTask.cancel();
			}

			frozenBlocks.clear();

			iceFillTask = new BukkitRunnable() {
				@Override
				public void run() {
					Block below = player.getLocation().subtract(0, 1, 0).getBlock();
					if(below.getType() == Material.ICE) {
						below.setType(Material.PACKED_ICE, false);
						frozenBlocks.add(below);
						Utils.playGlobalSound(Sound.BLOCK_SNOW_BREAK, 2.0f, 1.0f);
					}
				}
			}.runTaskTimer(M7tas.getInstance(), 0L, 1L);
		}

		public static void stopIceFillTask() {
			if(iceFillTask != null) {
				iceFillTask.cancel();
				iceFillTask = null;
			}

			for(Block block : frozenBlocks) {
				if(block.getType() == Material.PACKED_ICE) {
					block.setType(Material.ICE, false);
				}
			}
			frozenBlocks.clear();
		}

		public static void run(Player player, World world) {
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 121);
			Utils.scheduleTask(() -> {
				startIceFillTask(player);
				Actions.rightClick(player);
			}, 122);
			Utils.scheduleTask(() -> Actions.rightClick(player), 123);
			Utils.scheduleTask(() -> Actions.rightClick(player), 124);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 125);
			Utils.scheduleTask(() -> Actions.rightClick(player), 126);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 127);
			Utils.scheduleTask(() -> Actions.rightClick(player), 128);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 129);
			Utils.scheduleTask(() -> Actions.rightClick(player), 130);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 131);
			Utils.scheduleTask(() -> {
				Actions.rightClick(player);
				playIceFillSounds(1, player);
			}, 132);
			Utils.scheduleTask(() -> Actions.rightClick(player), 133);
			Utils.scheduleTask(() -> Actions.rightClick(player), 134);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 135);
			Utils.scheduleTask(() -> Actions.rightClick(player), 136);
			Utils.scheduleTask(() -> Actions.rightClick(player), 137);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 138);
			Utils.scheduleTask(() -> Actions.rightClick(player), 139);
			Utils.scheduleTask(() -> Actions.rightClick(player), 140);
			Utils.scheduleTask(() -> Actions.rightClick(player), 141);
			Utils.scheduleTask(() -> Actions.rightClick(player), 142);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 143);
			Utils.scheduleTask(() -> Actions.rightClick(player), 144);
			Utils.scheduleTask(() -> Actions.turnHead(player, -90f, 60f), 145);
			Utils.scheduleTask(() -> Actions.rightClick(player), 146);
			Utils.scheduleTask(() -> Actions.rightClick(player), 147);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 148);
			Utils.scheduleTask(() -> Actions.rightClick(player), 149);
			Utils.scheduleTask(() -> Actions.rightClick(player), 150);
			Utils.scheduleTask(() -> Actions.turnHead(player, -90f, 60f), 151);
			Utils.scheduleTask(() -> Actions.rightClick(player), 152);
			Utils.scheduleTask(() -> Actions.rightClick(player), 153);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 154);
			Utils.scheduleTask(() -> Actions.rightClick(player), 155);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 156);
			Utils.scheduleTask(() -> Actions.rightClick(player), 157);
			Utils.scheduleTask(() -> Actions.rightClick(player), 158);
			Utils.scheduleTask(() -> Actions.rightClick(player), 159);
			Utils.scheduleTask(() -> Actions.rightClick(player), 160);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 161);
			Utils.scheduleTask(() -> Actions.rightClick(player), 162);
			Utils.scheduleTask(() -> Actions.rightClick(player), 163);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 164);
			Utils.scheduleTask(() -> {
				Actions.rightClick(player);
				playIceFillSounds(2, player);
			}, 165);
			Utils.scheduleTask(() -> Actions.rightClick(player), 166);
			Utils.scheduleTask(() -> Actions.rightClick(player), 167);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 168);
			Utils.scheduleTask(() -> Actions.rightClick(player), 169);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 170);
			Utils.scheduleTask(() -> Actions.rightClick(player), 171);
			Utils.scheduleTask(() -> Actions.rightClick(player), 172);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 173);
			Utils.scheduleTask(() -> Actions.rightClick(player), 174);
			Utils.scheduleTask(() -> Actions.turnHead(player, -90f, 60f), 175);
			Utils.scheduleTask(() -> Actions.rightClick(player), 176);
			Utils.scheduleTask(() -> Actions.rightClick(player), 177);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 178);
			Utils.scheduleTask(() -> Actions.rightClick(player), 179);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 180);
			Utils.scheduleTask(() -> Actions.rightClick(player), 181);
			Utils.scheduleTask(() -> Actions.rightClick(player), 182);
			Utils.scheduleTask(() -> Actions.rightClick(player), 183);
			Utils.scheduleTask(() -> Actions.rightClick(player), 184);
			Utils.scheduleTask(() -> Actions.rightClick(player), 185);
			Utils.scheduleTask(() -> Actions.rightClick(player), 186);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 187);
			Utils.scheduleTask(() -> Actions.rightClick(player), 188);
			Utils.scheduleTask(() -> Actions.turnHead(player, -90f, 60f), 189);
			Utils.scheduleTask(() -> Actions.rightClick(player), 190);
			Utils.scheduleTask(() -> Actions.rightClick(player), 191);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 192);
			Utils.scheduleTask(() -> Actions.rightClick(player), 193);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 194);
			Utils.scheduleTask(() -> Actions.rightClick(player), 195);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 196);
			Utils.scheduleTask(() -> Actions.rightClick(player), 197);
			Utils.scheduleTask(() -> Actions.rightClick(player), 198);
			Utils.scheduleTask(() -> Actions.turnHead(player, -90f, 60f), 199);
			Utils.scheduleTask(() -> Actions.rightClick(player), 200);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 201);
			Utils.scheduleTask(() -> Actions.rightClick(player), 202);
			Utils.scheduleTask(() -> Actions.turnHead(player, -90f, 60f), 203);
			Utils.scheduleTask(() -> Actions.rightClick(player), 204);
			Utils.scheduleTask(() -> Actions.rightClick(player), 205);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 206);
			Utils.scheduleTask(() -> Actions.rightClick(player), 207);
			Utils.scheduleTask(() -> Actions.turnHead(player, -90f, 60f), 208);
			Utils.scheduleTask(() -> Actions.rightClick(player), 209);
			Utils.scheduleTask(() -> Actions.rightClick(player), 210);
			Utils.scheduleTask(() -> Actions.turnHead(player, 180f, 60f), 211);
			Utils.scheduleTask(() -> Actions.rightClick(player), 212);
			Utils.scheduleTask(() -> Actions.rightClick(player), 213);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 214);
			Utils.scheduleTask(() -> Actions.rightClick(player), 215);
			Utils.scheduleTask(() -> Actions.rightClick(player), 216);
			Utils.scheduleTask(() -> Actions.rightClick(player), 217);
			Utils.scheduleTask(() -> Actions.rightClick(player), 218);
			Utils.scheduleTask(() -> Actions.rightClick(player), 219);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 220);
			Utils.scheduleTask(() -> Actions.rightClick(player), 221);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 222);
			Utils.scheduleTask(() -> Actions.rightClick(player), 223);
			Utils.scheduleTask(() -> Actions.turnHead(player, 0f, 60f), 224);
			Utils.scheduleTask(() -> Actions.rightClick(player), 225);
			Utils.scheduleTask(() -> Actions.rightClick(player), 226);
			Utils.scheduleTask(() -> Actions.turnHead(player, 90f, 60f), 227);
			Utils.scheduleTask(() -> {
				Actions.rightClick(player);
				playIceFillSounds(3, player);
				Server.openIceFillRewards();
				Bukkit.broadcast(Utils.msg("<red>Berserk: Ice Fill Cleared"));
			}, 228);
			Utils.scheduleTask(() -> Actions.move(player, "WP", 1), 229);
			Utils.scheduleTask(() -> Actions.turnHead(player, 68f, -33f), 230);
			Utils.scheduleTask(() -> {
				Actions.leftClick(player);
				Utils.broadcastBlessing(player, Utils.BlessingType.POWER, 5);
				Utils.playSecretFoundSound(player, Utils.SecretType.BLESSING_CHEST);
			}, 248);
			Utils.scheduleTask(() -> Actions.turnHead(player, 112f, -33f), 249);
			Utils.scheduleTask(() -> {
				Actions.leftClick(player);
				Utils.broadcastBlessing(player, Utils.BlessingType.POWER, 5);
				Utils.playSecretFoundSound(player, Utils.SecretType.BLESSING_CHEST);
				Bukkit.broadcast(Utils.msg("<red>Berserk: Clear Finished in 250 Ticks (12.50 seconds)"));
			}, 250);
		}
	}
}