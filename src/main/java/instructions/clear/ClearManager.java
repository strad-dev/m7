package instructions.clear;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import plugin.FakePlayerInventory;
import plugin.M7;
import plugin.Utils;

import java.awt.Color;
import java.util.*;
import java.util.List;

/**
 * Clear-phase controller: per-run state (secrets, checkmarks, bonus counters, blessing tally, score), secret
 * entity spawn/teardown, per-tick HUD + minimap + item pickup, and the score for the end-of-run scoreboard.
 *
 * <p>Entry points take any {@link Player} and scoring is team-wide.
 */
public final class ClearManager {
	private ClearManager() {
	}

	private static World world;
	private static boolean active;
	private static BukkitTask tickTask;

	// bonus counters
	private static int cryptLurkers;
	private static boolean firstPrince, firstBat, mimicKilled;
	private static final Set<UUID> batKillers = new HashSet<>();
	private static int deaths; // noteDeath(), from death/Deaths.kill

	// Wizard crystal ball, not one of the 47
	private static boolean crystalPickedUp, crystalHandedIn;

	// Wizard room mobs, live modes only: the last kill white-checks Wizard. Classic checks it on entry instead.
	private static final Set<UUID> wizardMobs = new HashSet<>();
	private static boolean wizardByKills;
	/** {x, y, z, count, max horizontal displacement}. */
	private static final double[][] WIZARD_GROUPS = {
			{-88.5, 69, -184.5, 8, 3},
			{-88.5, 69, -168.5, 5, 3.5},
			{-88.5, 68, -152.5, 8, 3},
			{-88.5, 69, -136.5, 5, 3.5},
			{-80.5, 69, -118.5, 5, 4},
			{-96.5, 69, -118.5, 5, 4},
	};

	// Read by damage/Difficulty in either live mode; published as plugin/BlessingState (#awardBlessing).
	private static final Map<Blessing, Integer> blessingTally = new LinkedHashMap<>();

	private static boolean milestone300;

	// Leaderboard milestones in overall run ticks, -1 = not reached. Stamped once, never recomputed.
	private static int score300Tick = -1;
	private static int bloodDoneTick = -1;
	private static int fullClearTick = -1;

	/** Earnable part of {@link #bonus()}, minus the mayor's flat term: 5 crypts + Prince + Bat + 2 mimic. */
	private static final int MAX_EARNED_BONUS = 9;

	// also targeted by Server.blanketKill
	public static final String TAG_ITEM = "SecretItem";
	public static final String TAG_BAT = "SecretBat";
	public static final String TAG_MIMIC = "SecretMimic";
	public static final String TAG_CRYPT = "SecretCryptLurker";
	public static final String TAG_WIZARD_MOB = "WizardCryptLurker";

	/** Placed skull block, right-clicked to collect; not an entity. */
	private static final Material ESSENCE_BLOCK = Material.WITHER_SKELETON_SKULL;

	/** Facings from the map builder, keyed "x,y,z"; unlisted chests auto-orient. */
	private static final Map<String, BlockFace> CHEST_FACING = new HashMap<>();
	static {
		CHEST_FACING.put("-114,69,-35", BlockFace.EAST);
		CHEST_FACING.put("-186,79,-26", BlockFace.WEST);
		CHEST_FACING.put("-186,61,-40", BlockFace.EAST);
		CHEST_FACING.put("-69,69,-61", BlockFace.WEST);
		CHEST_FACING.put("-172,83,-85", BlockFace.WEST);
		CHEST_FACING.put("-169,70,-83", BlockFace.WEST);
		CHEST_FACING.put("-186,62,-80", BlockFace.EAST);
		CHEST_FACING.put("-109,82,-89", BlockFace.WEST);
		CHEST_FACING.put("-125,92,-101", BlockFace.SOUTH);
		CHEST_FACING.put("-54,69,-89", BlockFace.WEST);
		CHEST_FACING.put("-64,52,-125", BlockFace.EAST);
		CHEST_FACING.put("-70,89,-185", BlockFace.EAST);
		CHEST_FACING.put("-22,88,-188", BlockFace.EAST);
		CHEST_FACING.put("-29,91,-163", BlockFace.EAST);
		CHEST_FACING.put("-71,75,-152", BlockFace.EAST);
		CHEST_FACING.put("-71,75,-154", BlockFace.EAST);
	}

	public static boolean isActive() {
		return active;
	}

	// ==================== lifecycle ====================

	/** From {@link instructions.Server#resetClearState()} in serverSetup. Also stops the loop; {@link #start} re-arms it. */
	public static void reset() {
		active = false;
		if(tickTask != null) {
			tickTask.cancel();
			tickTask = null;
		}
		// Runs before every section, so this pulls the map when jumping straight to a boss (/m7practice
		// witherking), where the clear loop that normally removes it isn't running.
		restoreMenus();
		// Ice Fill must be here: fail() airs a layer and restores it via Utils.scheduleTask, which /reset and /setup
		// kill, and begin() only registers ICE/PACKED_ICE, so the hole would stay forever. stop() restores AIR ->
		// ICE from retained coords, the only way back.
		PuzzleQuiz.stop();
		PuzzleIceFill.stop();
		World w = world != null ? world : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst());
		if(w != null) {
			removeSecretEntities(w);
			teardownSecretBlocks(w);
		}
		Rooms.reset();
		DungeonMap.markDirty();
		cryptLurkers = 0;
		firstPrince = firstBat = mimicKilled = false;
		batKillers.clear();
		deaths = 0;
		crystalPickedUp = crystalHandedIn = false;
		wizardMobs.clear();
		wizardByKills = false;
		milestone300 = false;
		score300Tick = bloodDoneTick = fullClearTick = -1;
		// Publish the clear too, or a consumer shows last run's blessings through a run that collects none. Only
		// if non-empty, so /setup on an idle server is silent.
		if(!blessingTally.isEmpty()) {
			blessingTally.clear();
			publishBlessings();
		}
	}

	public static void start(World w) {
		world = w;
		reset();
		placeSecretBlocks();
		spawnSecretEntities();
		if(damage.Difficulty.liveInputs()) {
			wizardByKills = true;
			spawnWizardMobs();
		}
		PuzzleQuiz.reset();
		PuzzleIceFill.begin(w);
		active = true;
		exploreRoom(Rooms.byName("Start"));    // players spawn in it
		exploreRoom(Rooms.byName("Red Blue")); // entrance room starts explored
		giveMaps();
		if(tickTask != null) tickTask.cancel();
		tickTask = Bukkit.getScheduler().runTaskTimer(M7.getInstance(), ClearManager::tick, 1L, 1L);
		// Empty opening tally: listeners show a display from the start, and it tells "collected nothing" apart from
		// "no clear phase".
		publishBlessings();
	}

	public static void stop(World w) {
		active = false;
		if(tickTask != null) {
			tickTask.cancel();
			tickTask = null;
		}
		PuzzleQuiz.stop();
		PuzzleIceFill.stop();
		if(w != null) {
			removeSecretEntities(w);
			teardownSecretBlocks(w);
		}
		restoreMenus();
	}

	// ==================== spawning ====================

	/** Chests face an open side; essence skulls reset to the wither variant. */
	private static void placeSecretBlocks() {
		for(Room r : Rooms.all()) {
			for(Secret s : r.secrets) {
				if(s.isChest()) {
					Block b = world.getBlockAt(s.blockX(), s.blockY(), s.blockZ());
					// Mimic = trapped chest, same tell as Hypixel.
					b.setType(s.mimic ? Material.TRAPPED_CHEST : Material.CHEST, false);
					if(b.getBlockData() instanceof Directional dir) {
						BlockFace face = CHEST_FACING.getOrDefault(s.blockX() + "," + s.blockY() + "," + s.blockZ(), openFace(b));
						if(face != null) {
							dir.setFacing(face);
							b.setBlockData(dir, false);
						}
					}
				} else if(s.type == Utils.SecretType.ESSENCE) {
					// Essence is in the static map; a prior run may have made it a normal skull. Keep orientation.
					Block b = world.getBlockAt(s.blockX(), s.blockY(), s.blockZ());
					Material m = b.getType();
					if(m == Material.SKELETON_SKULL) convertSkull(b, Material.WITHER_SKELETON_SKULL);
					else if(m == Material.SKELETON_WALL_SKULL) convertSkull(b, Material.WITHER_SKELETON_WALL_SKULL);
					else if(m != Material.WITHER_SKELETON_SKULL && m != Material.WITHER_SKELETON_WALL_SKULL) b.setType(ESSENCE_BLOCK, false);
				}
			}
		}
	}

	/** Undoes {@link #placeSecretBlocks}. */
	private static void teardownSecretBlocks(World w) {
		for(Room r : Rooms.all()) {
			for(Secret s : r.secrets) {
				if(s.isChest()) {
					Block b = w.getBlockAt(s.blockX(), s.blockY(), s.blockZ());
					if(b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST) b.setType(Material.AIR, false);
				} else if(s.type == Utils.SecretType.ESSENCE) {
					Block b = w.getBlockAt(s.blockX(), s.blockY(), s.blockZ());
					if(b.getType() == Material.SKELETON_SKULL) convertSkull(b, Material.WITHER_SKELETON_SKULL);
					else if(b.getType() == Material.SKELETON_WALL_SKULL) convertSkull(b, Material.WITHER_SKELETON_WALL_SKULL);
				}
			}
		}
	}

	/** Keeps floor rotation / wall facing. */
	private static void convertSkull(Block b, Material target) {
		BlockData old = b.getBlockData();
		BlockData nd = target.createBlockData();
		if(old instanceof Rotatable or && nd instanceof Rotatable nr) nr.setRotation(or.getRotation());
		if(old instanceof Directional od && nd instanceof Directional ndd) ndd.setFacing(od.getFacing());
		b.setBlockData(nd, false);
	}

	/** First horizontal face with a non-occluding neighbour. */
	private static BlockFace openFace(Block b) {
		for(BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
			if(!b.getRelative(f).getType().isOccluding()) return f;
		}
		return null;
	}

	private static void spawnSecretEntities() {
		int items = 0, bats = 0;
		for(Room r : Rooms.all()) {
			for(Secret s : r.secrets) {
				// One failure mustn't abort the rest.
				try {
					switch(s.type) {
						case ITEM -> { spawnItem(s); items++; }
						case BAT -> { spawnBat(s); bats++; }
						default -> {
						}
					}
				} catch(Throwable ex) {
					Bukkit.getLogger().warning("[M7 clear] failed to spawn secret " + s.type + " at " + s.blockX() + "," + s.blockY() + "," + s.blockZ() + ": " + ex);
				}
			}
		}
		Bukkit.getLogger().info("[M7 clear] spawned " + items + " items, " + bats + " bats");
	}

	private static void spawnItem(Secret s) {
		Item item = world.dropItem(s.location(world), new ItemStack(Material.PAPER));
		s.entityId = item.getUniqueId();
		item.addScoreboardTag(TAG_ITEM);
		// map's `/kill @e[type=item]` command block excludes tag=!secret
		item.addScoreboardTag("secret");
		item.setPickupDelay(32767); // never auto-picked; collected manually at 3× range
		item.setPersistent(true);
		item.setGravity(false);
		try { item.setVelocity(new org.bukkit.util.Vector(0, 0, 0)); } catch(Throwable ignored) {}
		try { item.setWillAge(false); } catch(Throwable ignored) {}
		try { item.setUnlimitedLifetime(true); } catch(Throwable ignored) {}
	}

	private static void spawnBat(Secret s) {
		Bat bat = (Bat) world.spawnEntity(s.location(world), EntityType.BAT);
		bat.setAI(false);
		bat.setSilent(true);
		bat.setAwake(true);
		bat.setGravity(false);
		bat.setPersistent(true);
		bat.setRemoveWhenFarAway(false);
		bat.setCustomNameVisible(false);
		Objects.requireNonNull(bat.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(1);
		bat.setHealth(1);
		bat.addScoreboardTag(TAG_BAT);
		s.entityId = bat.getUniqueId();
	}

	private static void spawnWizardMobs() {
		java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
		for(double[] g : WIZARD_GROUPS) {
			for(int i = 0; i < (int) g[3]; i++) {
				// Uniform over the disc, not clumped at the centre.
				double r = g[4] * Math.sqrt(rng.nextDouble());
				double a = rng.nextDouble() * 2 * Math.PI;
				Location loc = new Location(world, g[0] + r * Math.cos(a), g[1], g[2] + r * Math.sin(a), rng.nextFloat() * 360f - 180f, 0f);
				try {
					wizardMobs.add(spawnWizardMob(loc).getUniqueId());
				} catch(Throwable ex) {
					Bukkit.getLogger().warning("[M7 clear] failed to spawn Crypt Lurker at " + loc + ": " + ex);
				}
			}
		}
	}

	private static Zombie spawnWizardMob(Location loc) {
		Zombie z = (Zombie) world.spawnEntity(loc, EntityType.ZOMBIE);
		z.setAdult();
		// finalizeSpawn can make a chicken jockey and hand out armour or a held item.
		if(z.isInsideVehicle()) {
			Entity vehicle = z.getVehicle();
			z.leaveVehicle();
			if(vehicle != null) vehicle.remove();
		}
		z.getEquipment().clear();
		z.getEquipment().setItemInMainHand(new ItemStack(Material.BONE));
		z.setAI(false);
		z.setSilent(true);
		z.setPersistent(true);
		z.setRemoveWhenFarAway(false);
		z.setCustomNameVisible(true);
		z.addScoreboardTag(TAG_WIZARD_MOB);
		damage.MobStats.apply(z, damage.MobStats.CRYPT_LURKER);
		z.customName(Utils.msg("<red>Crypt Lurker <yellow>" + Utils.formatHealthM(z) + "<red>❤"));
		return z;
	}

	private static void removeSecretEntities(World w) {
		for(Entity e : w.getEntities()) {
			if(e.getScoreboardTags().contains(TAG_ITEM)
					|| e.getScoreboardTags().contains(TAG_BAT)
					|| e.getScoreboardTags().contains(TAG_MIMIC)
					|| e.getScoreboardTags().contains(TAG_WIZARD_MOB)) {
				e.remove();
			}
		}
	}

	// ==================== offhand map ====================

	private static void giveMaps() {
		for(Player p : mapHolders()) manageMapAndMenu(p);
	}

	/** Everyone who can see an offhand: real players and adventure watchers (WATCHING_TAG), not spectator mode. */
	private static List<Player> mapHolders() {
		List<Player> out = new ArrayList<>();
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(p.getGameMode() != org.bukkit.GameMode.SPECTATOR) out.add(p);
		}
		return out;
	}

	private static ItemStack skyblockMenu() {
		return items.util.SkyblockMenu.INSTANCE.build();
	}

	/** Map in offhand inside the grid, removed outside (boss). Slot 8 always the SkyBlock Menu. Only touches our
	 *  map or the menu star, never e.g. a Maxor crystal. */
	private static void manageMapAndMenu(Player p) {
		ItemStack slot8 = p.getInventory().getItem(8);
		if(!FakePlayerInventory.isSkyblockMenu(slot8)) {
			if(slot8 == null || slot8.getType() == Material.AIR || DungeonMap.isAnyDungeonMap(slot8)) {
				p.getInventory().setItem(8, skyblockMenu());
			}
		}
		ItemStack off = p.getInventory().getItemInOffHand();
		if(Rooms.inGrid(p.getLocation())) {
			if(!DungeonMap.isDungeonMap(off)) p.getInventory().setItemInOffHand(DungeonMap.mapItem());
		} else if(DungeonMap.isAnyDungeonMap(off)) {
			p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
		}
	}

	private static void restoreMenus() {
		for(Player p : mapHolders()) {
			if(DungeonMap.isAnyDungeonMap(p.getInventory().getItemInOffHand())) {
				p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
			}
			ItemStack slot8 = p.getInventory().getItem(8);
			if(DungeonMap.isAnyDungeonMap(slot8)) p.getInventory().setItem(8, skyblockMenu());
		}
	}

	// ==================== per-tick loop ====================

	/** Spectator-mode viewers don't reveal. */
	private static void markExploration() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(Utils.isSpectator(p)) continue;
			Room r = Rooms.roomAt(p.getLocation());
			if(r != null && !r.explored) {
				r.explored = true;
				DungeonMap.markDirty();
			}
		}
	}

	/** Explore without entry: wither door → Deathmite, blood door → Blood. */
	public static void exploreRoom(Room r) {
		if(r != null && !r.explored) {
			r.explored = true;
			DungeonMap.markDirty();
		}
	}

	private static void tick() {
		if(!active) return;
		markExploration();
		List<Player> players = realPlayers();
		for(Player p : mapHolders()) manageMapAndMenu(p);
		for(Player p : players) {
			collectItems(p);
			updateActionBar(p);
		}
		// Spectators aren't in realPlayers() but still get the bar.
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(Utils.isSpectator(p)) updateActionBar(p);
		}
		// Wizard has no miniboss: in classic, white check on first entry.
		if(!wizardByKills && !Rooms.WIZARD.cleared) {
			for(Player p : players) {
				if(Rooms.roomAt(p.getLocation()) == Rooms.WIZARD) {
					Rooms.WIZARD.cleared = true;
					afterEvent(Rooms.WIZARD);
					break;
				}
			}
		}
		PuzzleQuiz.tick(world, players);
		PuzzleIceFill.tick(world, players);
	}

	private static void collectItems(Player p) {
		for(Room r : Rooms.all()) {
			for(Secret s : r.secrets) {
				if(s.type != Utils.SecretType.ITEM || s.found || s.entityId == null) continue;
				Entity e = Bukkit.getEntity(s.entityId);
				if(e == null) continue;
				// 3× vanilla's ~1-block pickup range
				if(e.getLocation().distanceSquared(p.getLocation()) <= 9.0) {
					secretFound(p, s);
				}
			}
		}
	}

	private static void updateActionBar(Player p) {
		if(!Rooms.inGrid(p.getLocation())) return; // e.g. boss arena
		Room room = Rooms.roomAt(p.getLocation()); // null in a buffer
		// In a buffer: drop room name + room Secrets, grey colour; run-wide segments stay.
		String color = room != null ? hex(room.type.color) : "<gray>";
		// "Crypts 3/5 (M, P, B)": mimic, prince, bat
		List<String> flags = new ArrayList<>();
		if(mimicKilled) flags.add("M");
		if(firstPrince) flags.add("P");
		if(firstBat) flags.add(plugin.BatBug.enabled() ? batBonus() + "B" : "B");
		String crypts = "Crypts <white>" + Math.min(cryptLurkers, 5) + "/5" + (flags.isEmpty() ? "" : " (" + String.join(", ", flags) + ")");
		String bar = "";
		if(room != null) {
			bar += color + room.name
					+ " <dark_gray>| " + color + "Secrets <white>" + room.countedSecretFound() + "/" + room.countedSecretTotal()
					+ " <dark_gray>| ";
		}
		bar += color + "Total <white>" + totalSecretsFound() + "/" + totalSecrets()
				+ " <dark_gray>| " + color + crypts
				+ " <dark_gray>| " + color + "Score <white>" + teamScore();
		Utils.sendActionBar(p, Utils.msg(bar));
	}

	// ==================== gameplay events (Player-generic) ====================

	public static void secretFound(Player p, Secret s) {
		if(s == null || s.found) return;
		s.found = true;
		Utils.playSecretFoundSound(p, s.type);
		items.tools.Dungeonbreaker.onSecret(p);
		if(s.blessing != null) awardBlessing(p, s.blessing);
		if(s.room != null && s.room.type == RoomType.TRAP && s.blessing != null
				&& s.blessing.type() == Utils.BlessingType.POWER && s.blessing.level() == 2) {
			s.room.cleared = true;
		}
		if(s.entityId != null) {
			Entity e = Bukkit.getEntity(s.entityId);
			if(e != null && !(e instanceof LivingEntity)) e.remove(); // items; bats/mimics die naturally
		}
		if(world != null) {
			if(s.isChest() && !s.mimic) {
				Block b = world.getBlockAt(s.blockX(), s.blockY(), s.blockZ());
				if(b.getState() instanceof Lidded lid) lid.open();
			} else if(s.type == Utils.SecretType.ESSENCE) {
				// Collected essence becomes a normal skull.
				Block b = world.getBlockAt(s.blockX(), s.blockY(), s.blockZ());
				if(b.getType() == Material.WITHER_SKELETON_SKULL) convertSkull(b, Material.SKELETON_SKULL);
				else if(b.getType() == Material.WITHER_SKELETON_WALL_SKULL) convertSkull(b, Material.SKELETON_WALL_SKULL);
			}
		}
		afterEvent(s.room);
	}

	public static void minibossKilled(Room room, Player killer) {
		if(room == null || room.cleared) return;
		room.cleared = true;
		for(Blessing b : room.clearBlessings) {
			awardBlessing(killer, b);
			// Pickup ding to the earner, like key grants.
			if(killer != null) Utils.playLocalSound(killer, Sound.ENTITY_ITEM_PICKUP, 2.0f, 1.0f);
		}
		afterEvent(room);
	}

	public static void puzzleSolved(Room room, Player p) {
		puzzleSolved(room, p, true);
	}

	/**
	 * Green check and score now. {@code awardBlessings} false leaves them for {@link #awardRoomBlessings}: the Quiz
	 * scores on the last answer but Oruo gives Time V after his dialogue.
	 */
	public static void puzzleSolved(Room room, Player p, boolean awardBlessings) {
		if(room == null || room.solved) return;
		room.solved = true;
		if(awardBlessings) for(Blessing b : room.clearBlessings) awardBlessing(p, b);
		afterEvent(room);
	}

	/** Blessings only, no checkmark change (deferred-blessing puzzles). */
	public static void awardRoomBlessings(Room room, Player p) {
		if(room == null) return;
		for(Blessing b : room.clearBlessings) awardBlessing(p, b);
	}

	public static void cryptKilled(boolean isPrince) {
		cryptLurkers++;
		if(isPrince && !firstPrince) firstPrince = true;
		afterEvent(null);
	}

	/** The last one white-checks Wizard. */
	public static void wizardMobKilled(UUID id) {
		if(!wizardMobs.remove(id) || !wizardMobs.isEmpty() || Rooms.WIZARD.cleared) return;
		Rooms.WIZARD.cleared = true;
		afterEvent(Rooms.WIZARD);
	}

	public static void mimicKilledEvent(Player p, Secret mimicSecret) {
		if(!mimicKilled) mimicKilled = true;
		secretFound(p, mimicSecret); // +2 bonus flagged above; this is the regular secret
		afterEvent(mimicSecret == null ? null : mimicSecret.room);
	}

	public static void noteBatKill(Player killer) {
		boolean scored = !firstBat;
		firstBat = true;
		if(killer != null && !Utils.isSpectator(killer) && batKillers.add(killer.getUniqueId()) && plugin.BatBug.enabled()) scored = true;
		if(scored) afterEvent(null);
	}

	private static int batBonus() {
		if(!firstBat) return 0;
		return plugin.BatBug.enabled() ? Math.max(1, batKillers.size()) : 1;
	}

	// ---- Wizard crystal ball (not a counted secret) ----
	public static boolean hasCrystal() {
		return crystalPickedUp && !crystalHandedIn;
	}

	public static boolean crystalBallAvailable() {
		return !crystalPickedUp;
	}

	public static void pickUpCrystal(Player p) {
		if(crystalPickedUp) return;
		crystalPickedUp = true;
		Utils.playSecretFoundSound(p, Utils.SecretType.ITEM);
		p.sendMessage(Utils.msg("<green>You found a Special Crystal!")); // only the picker sees it
	}

	public static void handInCrystal(Player p) {
		if(!hasCrystal()) return;
		crystalHandedIn = true;
		Utils.playLocalSound(p, Sound.ENTITY_VILLAGER_YES);
		Bukkit.broadcast(Utils.msg("<yellow>[NPC] Wizard<white>: Oh my lovely crystal ball, mi so happy"));
		Utils.scheduleTask(() -> {
			Utils.playLocalSound(p, Sound.ENTITY_VILLAGER_YES);
			Bukkit.broadcast(Utils.msg("<yellow>[NPC] Wizard<white>: You deserve a reward young gobelin"));
		}, 20);
		Utils.scheduleTask(() -> {
			Utils.playLocalSound(p, Sound.ENTITY_VILLAGER_YES);
			Bukkit.broadcast(Utils.msg("<yellow>[NPC] Wizard<white>: Granted your team a <light_purple>Blessing of Wisdom I"));
			awardBlessing(p, new Blessing(Utils.BlessingType.WISDOM, 1));
		}, 60);
	}

	// ---- lookups for the listener ----
	public static Secret findChestSecret(int x, int y, int z) {
		for(Room r : Rooms.all()) {
			for(Secret s : r.secrets) {
				if(s.isChest() && s.blockX() == x && s.blockY() == y && s.blockZ() == z) return s;
			}
		}
		return null;
	}

	/** Chest or essence at these coords, or null. */
	public static Secret findSecretAtBlock(int x, int y, int z) {
		for(Room r : Rooms.all()) {
			for(Secret s : r.secrets) {
				if((s.isChest() || s.type == Utils.SecretType.ESSENCE)
						&& s.blockX() == x && s.blockY() == y && s.blockZ() == z) return s;
			}
		}
		return null;
	}

	public static Secret findSecretByEntity(java.util.UUID id) {
		if(id == null) return null;
		for(Room r : Rooms.all()) {
			for(Secret s : r.secrets) {
				if(id.equals(s.entityId)) return s;
			}
		}
		return null;
	}

	/** A mimic chest spawns its Mimic instead; the secret completes on kill. */
	public static void openChest(Player p, Secret s) {
		if(s == null || s.found) return;
		if(s.room == Rooms.ICE_FILL && !Rooms.ICE_FILL.solved) return;
		if(s.mimic) {
			if(s.entityId != null && Bukkit.getEntity(s.entityId) != null) return; // already out
			spawnMimic(p.getWorld(), s);
			return;
		}
		secretFound(p, s);
	}

	private static void spawnMimic(World w, Secret s) {
		Block b = w.getBlockAt(s.blockX(), s.blockY(), s.blockZ());
		if(b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST) b.setType(Material.AIR, false);
		Zombie z = (Zombie) w.spawnEntity(s.location(w).add(0.5, 0, 0.5), EntityType.ZOMBIE);
		z.setBaby();
		z.setAI(false);
		z.setSilent(true);
		z.setPersistent(true);
		z.setRemoveWhenFarAway(false);
		z.setCustomNameVisible(true);
		z.customName(Utils.msg("<red>Mimic <yellow>4M<red>❤"));
		Objects.requireNonNull(z.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(2);
		z.setHealth(2);
		Objects.requireNonNull(z.getAttribute(Attribute.ARMOR)).setBaseValue(-30);
		Objects.requireNonNull(z.getAttribute(Attribute.ARMOR_TOUGHNESS)).setBaseValue(-20);
		z.getEquipment();
		z.getEquipment().clear();
		z.getEquipment().setItemInMainHand(new ItemStack(Material.CHEST));
		z.addScoreboardTag(TAG_MIMIC);
		s.entityId = z.getUniqueId();
	}

	/** For attributing a miniboss or secret kill. */
	public static Player nearestRealPlayer(Location loc) {
		Player best = null;
		double bestSq = Double.MAX_VALUE;
		for(Player p : realPlayers()) {
			double d = p.getLocation().distanceSquared(loc);
			if(d < bestSq) {
				bestSq = d;
				best = p;
			}
		}
		return best;
	}

	public static void awardBlessing(Player p, Blessing b) {
		Utils.broadcastBlessing(p, b.type(), b.level());
		blessingTally.merge(b, 1, Integer::sum);
		publishBlessings();
	}

	/** Network plugin shows it in the tab list. */
	private static void publishBlessings() {
		Bukkit.getPluginManager().callEvent(new plugin.BlessingChangeEvent(plugin.BlessingState.capture()));
	}

	/** Checkmark + score milestone after any event. */
	private static void afterEvent(Room room) {
		// Progress counts as exploring (e.g. miniboss beamed from outside), so the map shows the checkmark.
		exploreRoom(room);
		DungeonMap.markDirty();
		if(!milestone300 && teamScore() >= 300) {
			milestone300 = true;
			int t = Utils.runTick();
			score300Tick = t;
			Bukkit.broadcast(Utils.msg("<green><bold>300 score reached</bold> in " + spaced(t) + " ticks (" + String.format("%.2f", t / 20.0) + " seconds)"));
			Utils.playGlobalSound(Sound.ENTITY_ARROW_HIT_PLAYER, 2.0f, 0.5f);
			// Report now, not at run end, so it counts even if the team resets right after. score300Tick is set
			// first because the payload reads it.
			instructions.bosses.WitherActions.signalScoreMilestone(300);
		}
		checkFullClear();
	}

	// ==================== leaderboard milestones ====================

	/**
	 * From {@code Watcher.bloodCampFinished()} when the Watcher vanishes (run end on clear-only practice). Records
	 * nothing when the clear isn't running (boss-only practice).
	 */
	public static void noteBloodDone() {
		if(!active || bloodDoneTick >= 0) return;
		bloodDoneTick = Utils.runTick();
		checkFullClear();
	}

	/** Full clear = max score AND blood done. Either can land last, so both paths call this. */
	private static void checkFullClear() {
		if(!active || fullClearTick >= 0) return;
		if(bloodDoneTick < 0 || teamScore() < perfectScore()) return;
		fullClearTick = Utils.runTick();
	}

	/** Overall run ticks, -1 if not reached. */
	public static int score300Tick() { return score300Tick; }

	public static int bloodDoneTick() { return bloodDoneTick; }

	/** {@link #perfectScore()} + blood. */
	public static int fullClearTick() { return fullClearTick; }

	// ==================== scoring ====================

	public static int totalSecrets() {
		int n = 0;
		for(Room r : Rooms.all()) n += r.countedSecretTotal();
		return n;
	}

	public static int totalSecretsFound() {
		int n = 0;
		for(Room r : Rooms.all()) n += r.countedSecretFound();
		return n;
	}

	private static int unsolvedPuzzles() {
		int n = 0;
		for(Room r : Rooms.all()) if(r.type == RoomType.PUZZLE && !r.solved) n++;
		return n;
	}

	/** Checked cells out of 36 (Museum = 4), Hypixel's room-clear fraction. Any checkmark counts. */
	private static int checkedCells() {
		int cells = 0;
		// Blood always counts, as in Hypixel's live projection.
		for(Room r : Rooms.all()) if(r.check() != Room.Check.NONE || r.type == RoomType.BLOOD) cells += r.cells.length;
		return cells;
	}

	/**
	 * Only caller is {@code death/Deaths.kill}, once per real death: after the mode gate and {@code CheatDeath},
	 * wipes included. In classic only {@code /kill} and out of bounds get here.
	 */
	public static void noteDeath() {
		deaths++;
	}

	/** 20 + up to 80 from room clears − 10 per unsolved puzzle − deaths, clamped [20,100].
	 *  <p>
	 *  Death penalty {@code 2n - 1} as real Catacombs: first costs 1, each after 2. {@code Math.max(0, ...)} keeps
	 *  0 deaths at 0, not +1. */
	public static int skill() {
		int skillRooms = (int) Math.min(80, Math.floor(80.0 * checkedCells() / 36.0));
		int puzzlePenalty = 10 * unsolvedPuzzles();
		int deathPenalty = Math.max(0, deaths * 2 - 1);
		return Math.clamp(20 + skillRooms - puzzlePenalty - deathPenalty, 20, 100);
	}

	public static int explore() {
		int checkPts = (int) Math.min(60, Math.floor(60.0 * checkedCells() / 36.0));
		int total = totalSecrets();
		int secretPts = total == 0 ? 0 : (int) Math.min(40, Math.floor((double) totalSecretsFound() / total * 40.0));
		return checkPts + secretPts;
	}

	@SuppressWarnings("SameReturnValue")
	public static int speed() {
		return 100;
	}

	/** Flat +10 is Mayor Paul's EZPZ perk, 0 under other mayors ({@code damage/Mayor}); read live, not a literal. */
	public static int bonus() {
		return damage.Mayor.scoreBonus()
				+ Math.min(cryptLurkers, 5) + (firstPrince ? 1 : 0) + batBonus() + (mimicKilled ? 2 : 0);
	}

	/** Max score: 300 + best {@link #bonus()}. 319 under Paul, 309 otherwise. */
	public static int perfectScore() {
		return 300 + damage.Mayor.scoreBonus() + MAX_EARNED_BONUS;
	}

	public static int teamScore() {
		return skill() + explore() + speed() + bonus();
	}

	public static String grade() {
		int s = teamScore();
		if(s >= 300) return "S+";
		if(s >= 270) return "S";
		if(s >= 230) return "A";
		if(s >= 160) return "B";
		if(s >= 100) return "C";
		return "D";
	}

	// ==================== blessing tally ====================

	public static Map<Blessing, Integer> blessingTally() {
		return blessingTally;
	}

	public static int blessingCount(Blessing b) {
		return blessingTally.getOrDefault(b, 0);
	}

	/**
	 * Summed by level (Power V = 5). {@code damage/Difficulty} and {@code plugin/BlessingState} both read this
	 * rather than walking the tally, so the sum lives in one place.
	 */
	public static int collectedLevel(Utils.BlessingType type) {
		int level = 0;
		for(Map.Entry<Blessing, Integer> e : blessingTally.entrySet()) {
			if(e.getKey().type() == type) level += e.getKey().level() * e.getValue();
		}
		return level;
	}

	/** Count, ignoring level. */
	public static int collectedCount(Utils.BlessingType type) {
		int n = 0;
		for(Map.Entry<Blessing, Integer> e : blessingTally.entrySet()) {
			if(e.getKey().type() == type) n += e.getValue();
		}
		return n;
	}

	/**
	 * Tally is real for this session: clear live, or non-empty after handing off to bosses. False for boss-only
	 * practice, where maxed blessings are assumed.
	 */
	public static boolean hasBlessingData() {
		return active || !blessingTally.isEmpty();
	}

	// ==================== helpers ====================

	/** Non-spectator: get HUD and map, collect secrets. */
	public static List<Player> realPlayers() {
		List<Player> out = new ArrayList<>();
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(isRealPlayer(p)) out.add(p);
		}
		return out;
	}

	/**
	 * {@link #realPlayers()} test for one player, so the run roster ({@link instructions.bosses.WitherActions#noteInRun})
	 * agrees with the HUD; it judges quitting players, who aren't in the online list.
	 */
	public static boolean isRealPlayer(Player p) {
		if(p == null) return false;
		return !Utils.isSpectator(p);
	}

	private static final Set<Material> ESSENCE_SKULLS =
			EnumSet.of(Material.WITHER_SKELETON_SKULL, Material.WITHER_SKELETON_WALL_SKULL);

	/** Ice layers and their polished andesite frame. */
	private static final Set<Material> ICE_FILL_FIXTURES =
			EnumSet.of(Material.ICE, Material.PACKED_ICE, Material.POLISHED_ANDESITE);

	/**
	 * Static-map blocks never breakable: secret chests, Quiz buttons, essence skulls, Ice Fill ice and andesite.
	 * <p>
	 * Not gated on the clear phase: the Dungeonbreaker works any time and its break is permanent
	 * ({@code setType(AIR)}), so one break outside a run edits every later run (an Ice Fill hole is silently
	 * absorbed by {@code PuzzleIceFill.begin}). Stonk's 200-tick removal is no better mid-run; this sits above the
	 * tool branch in {@code onBlockBreak} so covers both. Survival/creative bypass it all.
	 * <p>
	 * Skulls match by material anywhere, since an unregistered skull is still map. Ice/andesite only in the Ice
	 * Fill room; elsewhere they're ordinary blocks.
	 */
	public static boolean isMapFixture(Block b) {
		if(b == null) return false;
		if(ESSENCE_SKULLS.contains(b.getType())) return true;
		if(ICE_FILL_FIXTURES.contains(b.getType()) && Rooms.roomAt(b.getLocation()) == Rooms.ICE_FILL) return true;
		if(PuzzleQuiz.isButtonArea(b)) return true;
		for(Room r : Rooms.all()) {
			for(Secret s : r.secrets) {
				if(s.isChest() && b.getX() == s.blockX() && b.getY() == s.blockY() && b.getZ() == s.blockZ()) return true;
			}
		}
		return false;
	}

	private static String hex(Color c) {
		return String.format("<#%02X%02X%02X>", c.getRed(), c.getGreen(), c.getBlue());
	}

	/** Space-separated thousands, e.g. 3084 → "3 084". */
	private static String spaced(int n) {
		String s = String.valueOf(n);
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < s.length(); i++) {
			if(i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
			sb.append(s.charAt(i));
		}
		return sb.toString();
	}
}
