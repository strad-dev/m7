package items;

import instructions.Server;
import instructions.bosses.goldor.Goldor;
import instructions.bosses.maxor.Maxor;
import items.armor.ThermodynamicHelmet;
import listeners.LavaJump;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import plugin.M7;
import plugin.PlayerCollision;
import plugin.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item behaviour shared by more than one item, plus the per-run block state that goes with it. Formerly private
 * statics on {@code listeners/CustomItems}.
 * <ol>
 *   <li><b>Shared combat</b>: mage beam + its ray-AABB test, thrown axe (Axe of the Shredded, Berserk
 *       {@code drop stack}), guided carriers (Spirit Sceptre bat, Mage sheep), Superboom radius, AoE blacklist.</li>
 *   <li><b>World state with teardown obligations</b>: stonk, crypt and Superboom-wall restores. These use RAW
 *       {@code runTaskLater}, not {@code Utils.scheduleTask}: {@code cancelAllScheduled()} would kill a tracked
 *       task and leave permanent AIR holes and orphaned crypt mobs. The flushes below are the force-restore.</li>
 *   <li><b>Class and set predicates</b>: {@link #isMageClass}, {@link #effectiveCooldown}, {@link #isThermoSet}.</li>
 * </ol>
 * The dispatcher keeps only what belongs to the CLICK: rate gates, melee path, mage-beam/left-click-ability tests.
 */
public final class ItemUtils {
	private ItemUtils() {}

	// True while mageBeam's damage call is on the stack. damage/Damage.witherHurtSound reads it to skip its
	// at-location sound: the beam sends its own constant-volume one to the beamer, so it would double up.
	public static boolean beamDamageInProgress = false;

	private static final Map<UUID, Integer> lastWitherShieldSoundTick = new ConcurrentHashMap<>();

	public static final Map<Location, BlockData> pendingStonkRestorations = new HashMap<>();
	public static final Map<Location, BukkitTask> pendingStonkTasks = new HashMap<>();

	// Crypt + Superboom-wall restores, like the stonk maps: set to AIR, restored after SUPERBOOM_REGEN_TICKS by a raw
	// task (not Utils.scheduleTask, which cancelAllScheduled() would kill), flushed by flushBlockRestorations().
	private static final Map<Location, BlockData> pendingBlockRestorations = new HashMap<>();
	private static final List<BukkitTask> pendingBlockTasks = new ArrayList<>();
	private static final List<Zombie> pendingCryptMobs = new ArrayList<>();

	// Ticks a crypt or cracked-brick wall stays open. Shared so both halves of one blast regrow together.
	private static final int SUPERBOOM_REGEN_TICKS = 100;

	// DETECTION radius for everything through triggerSuperboomRadius (TNT, Explosive Shot, Guided Sheep): cube
	// half-extent scanned for a valid crypt/wall block, Chebyshev, no line of sight. 2 = 5x5x5. How much is removed
	// is the second search in triggerSuperboomAt (crypt rectangle, cracked-brick flood-fill), deliberately NOT scaled
	// by this. Reach is vanilla's interaction range (see superboom).
	private static final int SUPERBOOM_RADIUS = 2;

	// Last Superboom tick per player. TNT fires from the ability dispatch or a raw vanilla placement
	// (onInfinityboomPlace); this caps it to one blast per player per tick so a click reaching both won't double-boom.
	private static final Map<UUID, Integer> lastSuperboomTick = new ConcurrentHashMap<>();

	/** Crypts blown up this run, keyed by min-corner, so one can't be farmed for repeated kills. */
	private static final Set<String> activatedCrypts = new HashSet<>();

	/** Each hitbox face is inflated by this for the beam. */
	private static final double MAGE_BEAM_LENIENCY = 0.5;

	public static String getID(ItemStack item) {
		if(item == null || !item.hasItemMeta()) {
			return "";
		} else if(!item.getItemMeta().hasLore()) {
			return "";
		} else return Utils.firstLorePlain(item.getItemMeta());
	}

	public static List<EntityType> doNotKill() {
		List<EntityType> doNotKill = new ArrayList<>();
		doNotKill.add(EntityType.ACACIA_BOAT);
		doNotKill.add(EntityType.ACACIA_CHEST_BOAT);
		doNotKill.add(EntityType.ALLAY);
		doNotKill.add(EntityType.ARMOR_STAND);
		doNotKill.add(EntityType.ARROW);
		doNotKill.add(EntityType.AXOLOTL);
		doNotKill.add(EntityType.BLOCK_DISPLAY);
		doNotKill.add(EntityType.BIRCH_BOAT);
		doNotKill.add(EntityType.BIRCH_CHEST_BOAT);
		doNotKill.add(EntityType.CAT);
		doNotKill.add(EntityType.CHERRY_BOAT);
		doNotKill.add(EntityType.CHERRY_CHEST_BOAT);
		doNotKill.add(EntityType.CHEST_MINECART);
		doNotKill.add(EntityType.COMMAND_BLOCK_MINECART);
		doNotKill.add(EntityType.DARK_OAK_BOAT);
		doNotKill.add(EntityType.DARK_OAK_CHEST_BOAT);
		doNotKill.add(EntityType.DONKEY);
		doNotKill.add(EntityType.DRAGON_FIREBALL);
		doNotKill.add(EntityType.FIREBALL);
		doNotKill.add(EntityType.EGG);
		doNotKill.add(EntityType.ENDER_PEARL);
		doNotKill.add(EntityType.EXPERIENCE_BOTTLE);
		doNotKill.add(EntityType.EXPERIENCE_ORB);
		doNotKill.add(EntityType.FALLING_BLOCK);
		doNotKill.add(EntityType.FIREWORK_ROCKET);
		doNotKill.add(EntityType.FISHING_BOBBER);
		doNotKill.add(EntityType.FURNACE_MINECART);
		doNotKill.add(EntityType.GLOW_ITEM_FRAME);
		doNotKill.add(EntityType.HOPPER_MINECART);
		doNotKill.add(EntityType.HORSE);
		doNotKill.add(EntityType.ITEM_FRAME);
		doNotKill.add(EntityType.ITEM_DISPLAY);
		doNotKill.add(EntityType.INTERACTION);
		doNotKill.add(EntityType.JUNGLE_BOAT);
		doNotKill.add(EntityType.JUNGLE_CHEST_BOAT);
		doNotKill.add(EntityType.LEASH_KNOT);
		doNotKill.add(EntityType.LIGHTNING_BOLT);
		doNotKill.add(EntityType.LLAMA);
		doNotKill.add(EntityType.LLAMA_SPIT);
		doNotKill.add(EntityType.MANGROVE_BOAT);
		doNotKill.add(EntityType.MANGROVE_CHEST_BOAT);
		doNotKill.add(EntityType.MARKER);
		doNotKill.add(EntityType.MINECART);
		doNotKill.add(EntityType.MULE);
		doNotKill.add(EntityType.OAK_BOAT);
		doNotKill.add(EntityType.OAK_CHEST_BOAT);
		doNotKill.add(EntityType.OCELOT);
		doNotKill.add(EntityType.PAINTING);
		doNotKill.add(EntityType.PARROT);
		doNotKill.add(EntityType.SHULKER_BULLET);
		doNotKill.add(EntityType.SKELETON_HORSE);
		doNotKill.add(EntityType.SMALL_FIREBALL);
		doNotKill.add(EntityType.SNOWBALL);
		doNotKill.add(EntityType.SPAWNER_MINECART);
		doNotKill.add(EntityType.SPECTRAL_ARROW);
		doNotKill.add(EntityType.SPRUCE_BOAT);
		doNotKill.add(EntityType.SPRUCE_CHEST_BOAT);
		doNotKill.add(EntityType.TEXT_DISPLAY);
		doNotKill.add(EntityType.TNT);
		doNotKill.add(EntityType.TRIDENT);
		doNotKill.add(EntityType.UNKNOWN);
		doNotKill.add(EntityType.VILLAGER);
		doNotKill.add(EntityType.WITHER_SKULL);
		doNotKill.add(EntityType.WOLF);
		return doNotKill;
	}

	public static void playWitherShieldSound(Player p) {
		int currentTick = MinecraftServer.currentTick;
		Integer lastTick = lastWitherShieldSoundTick.get(p.getUniqueId());
		if(lastTick == null || currentTick - lastTick >= 100) {
			Utils.playLocalSound(p, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 0.66666f);
			lastWitherShieldSoundTick.put(p.getUniqueId(), currentTick);
		}
	}

	/**
	 * Teleport without touching rotation. Yaw/pitch go out RELATIVE, so a high-ping player who turned between click
	 * and teleport keeps their new view instead of snapping back to what the server last knew.
	 * <br>
	 * The delta must be 0/0: relative components are OFFSETS ({@code PositionMoveRotation.calculateAbsolute}), so
	 * passing the player's yaw would add it on top and spin them.
	 * <br>
	 * Uses the connection because Paper deprecated {@code TeleportFlag.Relative} rotation in 1.21.3 with no Bukkit
	 * replacement. This CraftBukkit overload still fires {@code PlayerTeleportEvent} (PLUGIN) and honours a cancel.
	 * Same-world only.
	 */
	public static void noRotateTeleport(Player p, Location l) {
		ServerPlayer sp = ((CraftPlayer) p).getHandle();
		Vec3 delta = standsOn(p, l) ? new Vec3(0, STANDING_DELTA_Y, 0) : Vec3.ZERO;
		sp.connection.teleport(
				new PositionMoveRotation(new Vec3(l.getX(), l.getY(), l.getZ()), delta, 0f, 0f),
				Set.of(Relative.Y_ROT, Relative.X_ROT),
				PlayerTeleportEvent.TeleportCause.PLUGIN);
	}

	public static final double STANDING_DELTA_Y = -0.08 * 0.98;

	public static boolean standsOn(Player p, Location l) {
		double w = p.getWidth() / 2;
		return l.getWorld().hasCollisionsIn(new BoundingBox(l.getX() - w, l.getY() - 1.0E-3, l.getZ() - w, l.getX() + w, l.getY(), l.getZ() + w));
	}

	/** Feet offset for a teleport landing in {@code b}: 0 if passable, the top of a low block (carpet) to stand on, else -1. */
	public static double teleportFloor(Block b) {
		if(b.isPassable()) return 0;
		double top = 0;
		for(BoundingBox box : b.getCollisionShape().getBoundingBoxes()) top = Math.max(top, box.getMaxY());
		return top <= 0.2 ? top : -1;
	}

	/** Clear the per-run crypt-farm guard (called at run start). */
	public static void resetCrypts() {
		activatedCrypts.clear();
	}

	public static boolean checkAndActivateCrypt(Block clicked, Player p) {
		// No crypts in the boss arena, but its slab/stair terrain passes the rectangle test (a lone bottom slab over
		// air is a 1x1 crypt, gold makes it a Prince), so every Superboom there spawned a free lurker that counted
		// toward bonus score. Returning false still lets triggerSuperboomAt do the cracked-brick flood-fill.
		if(LavaJump.isInBossArena(clicked.getLocation())) return false;
		Material type = clicked.getType();
		int slabY;

		if(type == Material.SMOOTH_STONE_SLAB || type == Material.GOLD_BLOCK) {
			slabY = clicked.getY();
		} else if(type == Material.STONE_BRICK_STAIRS) {
			slabY = clicked.getY() + 1;
		} else {
			return false;
		}

		// Flood-fill horizontally to collect slab-layer blocks
		Set<Block> slabBlocks = new HashSet<>();
		Queue<Block> queue = new LinkedList<>();
		World world = clicked.getWorld();
		Block startBlock = world.getBlockAt(clicked.getX(), slabY, clicked.getZ());
		Material startType = startBlock.getType();
		if(startType != Material.SMOOTH_STONE_SLAB && startType != Material.GOLD_BLOCK) return false;
		if(startType == Material.SMOOTH_STONE_SLAB) {
			BlockData bd = startBlock.getBlockData();
			if(bd instanceof Slab slab && slab.getType() == Slab.Type.TOP) return false;
		}
		queue.add(startBlock);
		slabBlocks.add(startBlock);

		while(!queue.isEmpty() && slabBlocks.size() <= 1000) {
			Block current = queue.poll();
			for(BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
				Block neighbor = current.getRelative(face);
				if(slabBlocks.contains(neighbor)) continue;
				Material nType = neighbor.getType();
				if(nType == Material.SMOOTH_STONE_SLAB) {
					BlockData bd = neighbor.getBlockData();
					if(bd instanceof Slab slab && slab.getType() == Slab.Type.TOP) continue;
					slabBlocks.add(neighbor);
					queue.add(neighbor);
				} else if(nType == Material.GOLD_BLOCK) {
					slabBlocks.add(neighbor);
					queue.add(neighbor);
				}
			}
		}
		if(slabBlocks.size() > 1000) return false;

		// Compute bounding box
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for(Block b : slabBlocks) {
			if(b.getX() < minX) minX = b.getX();
			if(b.getX() > maxX) maxX = b.getX();
			if(b.getZ() < minZ) minZ = b.getZ();
			if(b.getZ() > maxZ) maxZ = b.getZ();
		}

		// Verify full rectangle
		int expectedSize = (maxX - minX + 1) * (maxZ - minZ + 1);
		if(slabBlocks.size() != expectedSize) return false;
		Set<Long> slabPositions = new HashSet<>();
		for(Block b : slabBlocks) {
			slabPositions.add(((long) (b.getX() - minX)) * 10000 + (b.getZ() - minZ));
		}
		for(int x = minX; x <= maxX; x++) {
			for(int z = minZ; z <= maxZ; z++) {
				if(!slabPositions.contains(((long) (x - minX)) * 10000 + (z - minZ))) return false;
			}
		}

		// Validate bottom layer
		Set<Block> stairBlocks = new HashSet<>();
		for(int x = minX; x <= maxX; x++) {
			for(int z = minZ; z <= maxZ; z++) {
				Block below = world.getBlockAt(x, slabY - 1, z);
				Material bType = below.getType();
				if(bType == Material.STONE_BRICK_STAIRS) {
					stairBlocks.add(below);
				} else if(bType != Material.AIR) {
					return false;
				}
			}
		}

		boolean isPrince = slabBlocks.stream().anyMatch(b -> b.getType() == Material.GOLD_BLOCK);

		// Farmable once: a repeat still opens and restores, but spawns no lurker.
		String cryptKey = minX + "," + slabY + "," + minZ;
		boolean alreadyBlownUp = !activatedCrypts.add(cryptKey);
		if(alreadyBlownUp) {
			p.sendMessage(Utils.msg("<red>You have already blown up this crypt!"));
		}

		// Store block data
		Map<Location, BlockData> stored = new HashMap<>();
		for(Block b : slabBlocks) {
			stored.put(b.getLocation(), b.getBlockData().clone());
			b.setType(Material.AIR, false);
		}
		for(Block b : stairBlocks) {
			stored.put(b.getLocation(), b.getBlockData().clone());
			b.setType(Material.AIR, false);
		}

		Utils.playLocalSound(p, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);

		double centerX = (minX + maxX) / 2.0 + 0.5;
		double centerZ = (minZ + maxZ) / 2.0 + 0.5;
		Location spawnLoc = new Location(world, centerX, slabY - 1, centerZ);
		Zombie mob = alreadyBlownUp ? null : Server.spawnCryptLurker(spawnLoc, isPrince);

		pendingBlockRestorations.putAll(stored);
		if(mob != null) pendingCryptMobs.add(mob);
		BukkitTask[] holder = new BukkitTask[1];
		holder[0] = Bukkit.getScheduler().runTaskLater(M7.getInstance(), () -> {
			for(Map.Entry<Location, BlockData> entry : stored.entrySet()) {
				entry.getKey().getBlock().setBlockData(entry.getValue(), false);
				pendingBlockRestorations.remove(entry.getKey());
			}
			if(mob != null) {
				if(mob.isValid()) {
					// Lurker survived the regen, so the crypt isn't spent: un-mark it for another try.
					// A killed lurker is invalid here, so its key stays.
					mob.remove();
					activatedCrypts.remove(cryptKey);
				}
				pendingCryptMobs.remove(mob);
			}
			pendingBlockTasks.remove(holder[0]);
		}, SUPERBOOM_REGEN_TICKS);
		pendingBlockTasks.add(holder[0]);

		return true;
	}

	/**
	 * Superboom at {@code center}, at most once per player per tick. Called by the ability dispatch and by
	 * {@code CustomItems.onCustomBlockPlace}, for when vanilla places the TNT instead of the click firing it.
	 */
	public static void superboomAt(Player p, Location center) {
		int currentTick = MinecraftServer.currentTick;
		if(currentTick == lastSuperboomTick.getOrDefault(p.getUniqueId(), -1)) return;
		lastSuperboomTick.put(p.getUniqueId(), currentTick);
		triggerSuperboomRadius(center, p);
	}

	public static void triggerSuperboomRadius(Location center, Player p) {
		triggerSuperboomRadius(center, p, new HashSet<>());
	}

	public static void triggerSuperboomRadius(Location center, Player p, Set<Block> visited) {
		// Tell Goldor about every explosion (Superboom, Explosive Shot, Guided Sheep).
		instructions.bosses.goldor.Goldor.INSTANCE.notifyExplosionAt(center);
		World world = center.getWorld();
		int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
		for(int dx = -SUPERBOOM_RADIUS; dx <= SUPERBOOM_RADIUS; dx++) {
			for(int dy = -SUPERBOOM_RADIUS; dy <= SUPERBOOM_RADIUS; dy++) {
				for(int dz = -SUPERBOOM_RADIUS; dz <= SUPERBOOM_RADIUS; dz++) {
					Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
					Material type = b.getType();
					if((type == Material.SMOOTH_STONE_SLAB || type == Material.GOLD_BLOCK || type == Material.STONE_BRICK_STAIRS || type == Material.CRACKED_STONE_BRICKS) && visited.add(b)) {
						triggerSuperboomAt(b, p);
					}
				}
			}
		}
	}

	public static void triggerSuperboomAt(Block block, Player p) {
		// 1. Try crypt
		if(block.getType() == Material.SMOOTH_STONE_SLAB || block.getType() == Material.GOLD_BLOCK || block.getType() == Material.STONE_BRICK_STAIRS) {
			if(checkAndActivateCrypt(block, p)) return;
		}

		// 2. Cracked stone bricks flood-fill
		if(block.getType() != Material.CRACKED_STONE_BRICKS) return;

		Set<Block> connected = new HashSet<>();
		Queue<Block> queue = new LinkedList<>();
		queue.add(block);
		connected.add(block);
		while(!queue.isEmpty()) {
			Block current = queue.poll();
			for(BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
				Block neighbor = current.getRelative(face);
				if(neighbor.getType() == Material.CRACKED_STONE_BRICKS && connected.add(neighbor)) {
					queue.add(neighbor);
				}
			}
		}

		Map<Location, BlockData> original = new HashMap<>();
		for(Block b : connected) {
			original.put(b.getLocation(), b.getBlockData().clone());
			b.setType(Material.AIR, false);
		}
		Utils.playLocalSound(p, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
		pendingBlockRestorations.putAll(original);
		BukkitTask[] holder = new BukkitTask[1];
		holder[0] = Bukkit.getScheduler().runTaskLater(M7.getInstance(), () -> {
			for(Map.Entry<Location, BlockData> entry : original.entrySet()) {
				entry.getKey().getBlock().setBlockData(entry.getValue(), false);
				pendingBlockRestorations.remove(entry.getKey());
			}
			pendingBlockTasks.remove(holder[0]);
		}, SUPERBOOM_REGEN_TICKS);
		pendingBlockTasks.add(holder[0]);
	}

	// ===================== guided carriers =====================
	// Spirit Sceptre bat and Mage sheep are one projectile with a different animal and damage rule, so the flight
	// lives here once. They used to disagree: the sheep flew straight through mobs.

	/** Blocks per tick. */
	private static final double GUIDED_SPEED = 1;

	/** Max flight before it detonates where it is: 10 seconds. */
	private static final int GUIDED_MAX_TICKS = 200;

	/** How close a mob must be to the carrier's next position to stop it. */
	private static final double GUIDED_HIT_RANGE = 1;

	/**
	 * Marks a carrier in flight, so a bat doesn't stop on a sheep or on itself (its own hitbox is in the sphere it
	 * scans). Not {@code isInvulnerable()}: Goldor is invulnerable all phase but takes ability damage through his
	 * clamp, so that test would make him immune.
	 */
	public static final String GUIDED_TAG = "TASGuidedCarrier";

	/**
	 * Fly a spawned animal at {@link #GUIDED_SPEED} toward where its caster is looking NOW, re-read every tick (the
	 * Sceptre "follows your aim", so it can be steered back at you), and detonate on the first mob or solid block.
	 * The caller spawns it and sets species details (sheep colour, bat awake flag); this makes it inert.
	 * <p>
	 * The blast goes through {@link #triggerSuperboomRadius}, so both open crypts and walls like the TNT.
	 * <p>
	 * Raw {@code runTaskTimer}, not {@code Utils.scheduleTask}: the carrier is invulnerable and self-removing, so a
	 * teardown mid-flight leaves at most one animal that detonates harmlessly.
	 * <p>
	 * Knows nothing about damage: the caster stamps it on via {@code damage/GuidedCarriers} before launch and the
	 * blast reads it back, like arrows (§1.0.5). An unstamped carrier explodes for nothing, which is legitimate.
	 *
	 * @param blastRadius blocks from impact
	 */
	public static void launchGuided(Player p, LivingEntity carrier, double blastRadius) {
		carrier.setAI(false);
		carrier.setGravity(false);
		carrier.setInvulnerable(true);
		carrier.setSilent(true);
		carrier.customName(null);
		carrier.setCustomNameVisible(false);
		carrier.setCollidable(false);
		carrier.addScoreboardTag("TASNoName");
		carrier.addScoreboardTag(GUIDED_TAG);
		PlayerCollision.addEntityToNoCollisionTeam(carrier);

		// Built once per launch: doNotKill() allocates every call and this scans every tick.
		List<EntityType> doNotKill = doNotKill();

		new BukkitRunnable() {
			int ticks = 0;

			@Override
			public void run() {
				// Out of time, gone, or caster left: detonate here. Damage was stamped at launch, so it still hits.
				if(ticks++ >= GUIDED_MAX_TICKS || !carrier.isValid() || !p.isOnline()) {
					detonateGuided(p, carrier, carrier.getLocation(), blastRadius, doNotKill);
					cancel();
					return;
				}
				Vector velocity = p.getEyeLocation().getDirection().normalize().multiply(GUIDED_SPEED);
				Location next = carrier.getLocation().add(velocity);
				if(next.getBlock().getType().isSolid() || firstMobNear(next, doNotKill) != null) {
					detonateGuided(p, carrier, next, blastRadius, doNotKill);
					cancel();
					return;
				}
				// Face where it's going, or a steered sheep slides sideways.
				next.setDirection(velocity);
				carrier.teleport(next);
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	/**
	 * Effect, Superboom pass, stamped damage on every mob in range, then the "hit N enemies" line. Carrier is removed
	 * LAST because the stamp is read off it. Only hits {@code deal} reported above zero count, so an armoured wither
	 * or NPC isn't claimed.
	 */
	private static void detonateGuided(Player p, LivingEntity carrier, Location center, double blastRadius,
			List<EntityType> doNotKill) {
		center.getWorld().spawnParticle(Particle.EXPLOSION, center, 10, 0.5, 0.5, 0.5, 0);
		center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1, 1f);
		triggerSuperboomRadius(center, p);
		int damaged = 0;
		double dealt = 0;
		for(Entity nearby : center.getWorld().getNearbyEntities(center, blastRadius, blastRadius, blastRadius)) {
			if(!isGuidedTarget(nearby, doNotKill)) continue;
			double hit = damage.GuidedCarriers.hit(carrier, p, (LivingEntity) nearby);
			if(hit > 0) {
				dealt += hit;
				damaged++;
			}
		}
		damage.Damage.reportAoe(p, damage.GuidedCarriers.abilityName(carrier), damaged, dealt);
		PlayerCollision.removeEntityFromNoCollisionTeam(carrier);
		carrier.remove();
	}

	private static LivingEntity firstMobNear(Location point, List<EntityType> doNotKill) {
		return firstMobNear(point, GUIDED_HIT_RANGE, doNotKill);
	}

	/** First mob within {@code range}, or null. Also used by Explosive Shot, whose arrows detonate on contact. */
	public static LivingEntity firstMobNear(Location point, double range) {
		return firstMobNear(point, range, doNotKill());
	}

	private static LivingEntity firstMobNear(Location point, double range, List<EntityType> doNotKill) {
		for(Entity nearby : point.getWorld().getNearbyEntities(point, range, range, range)) {
			if(isGuidedTarget(nearby, doNotKill)) return (LivingEntity) nearby;
		}
		return null;
	}

	/** Same rule as other AoEs (never a player), plus never a carrier, including itself. */
	private static boolean isGuidedTarget(Entity e, List<EntityType> doNotKill) {
		if(!(e instanceof LivingEntity mob) || e instanceof Player) return false;
		if(e.getScoreboardTags().contains(GUIDED_TAG) || doNotKill.contains(e.getType())) return false;
		if(e.getScoreboardTags().contains(instructions.bosses.necron.NecronDrag.PIG_TAG)) return false;
		if(mob.isDead() || mob.getHealth() <= 0) return false;
		return !(e instanceof Wither wither && wither.getInvulnerableTicks() != 0);
	}

	public static boolean canStonk(Block b) {
		return !Goldor.INSTANCE.isProtected(b) && !Maxor.INSTANCE.isProtected(b) && b.getType().getHardness() != -1;
	}

	public static void stonk(Player p, Block b) {
		if(canStonk(b)) {
			BlockData data = b.getBlockData().clone();
			Location loc = b.getLocation();
			Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " Stonking block at " + Utils.round(loc.getX(), 3) + " " + Utils.round(loc.getY(), 5) + " " + Utils.round(loc.getZ(), 3));

			b.setType(Material.AIR, false); // no-physics: attached neighbours (carpets, portals, …) don't pop off
			pendingStonkRestorations.put(loc, data);
			BukkitTask task = Bukkit.getScheduler().runTaskLater(M7.getInstance(), () -> {
				// No physics, like every restore: with it a carpet whose support was also stonked pops off for good,
				// and the six neighbours get re-shaped. setBlockData carries the material.
				b.setBlockData(data, false);
				pendingStonkRestorations.remove(loc);
				pendingStonkTasks.remove(loc);
			}, 200);
			pendingStonkTasks.put(loc, task);
		}
	}

	public static void flushStonkRestorations() {
		pendingStonkTasks.values().forEach(BukkitTask::cancel);
		pendingStonkTasks.clear();
		for(Map.Entry<Location, BlockData> entry : pendingStonkRestorations.entrySet()) {
			entry.getKey().getBlock().setBlockData(entry.getValue(), false);
		}
		pendingStonkRestorations.clear();
	}

	/**
	 * Restore every open wall/crypt now, despawn crypt mobs and cancel the pending restores. Called from
	 * Server.serverSetup so /reset and /setup put everything back at once.
	 */
	public static void flushBlockRestorations() {
		pendingBlockTasks.forEach(BukkitTask::cancel);
		pendingBlockTasks.clear();
		for(Map.Entry<Location, BlockData> entry : pendingBlockRestorations.entrySet()) {
			entry.getKey().getBlock().setBlockData(entry.getValue(), false);
		}
		pendingBlockRestorations.clear();
		for(Zombie mob : pendingCryptMobs) {
			if(mob.isValid()) mob.remove();
		}
		pendingCryptMobs.clear();
	}

	/**
	 * Thrown axe: a spinning ItemDisplay flying 100 blocks, damaging what it passes. Axe of the Shredded pierces;
	 * Berserk {@code drop stack} copies it but does NOT pierce (§1.14).
	 *
	 * @param ability display name for the "hit N enemies" line
	 * @param derived false for the Axe of the Shredded (stat core, still needs {@code meleeFinish}). True for the
	 *                Berserk throw, whose core comes from damage history and is FINISHED: finishing it again would
	 *                double the Rulers, repeated-hit stack and class multiplier, and recording it would let each
	 *                throw read the last one's inflated output (see {@link damage.Damage#dealDerived}).
	 */
	public static void throwAxe(Player p, String ability, double core, boolean pierce, boolean derived) {
		Utils.playLocalSound(p, Sound.BLOCK_LAVA_POP, 1.0F, 1.0F);
		ItemStack weapon = p.getInventory().getItemInMainHand();

		Location startLoc = p.getEyeLocation();
		Vector direction = startLoc.getDirection().normalize();

		// Spin axis: horizontal perpendicular to travel (90 degrees clockwise from above)
		double dx = direction.getX();
		double dz = direction.getZ();

		Vector spinAxis = new Vector(-dz, 0, dx).normalize();

		// Looking straight up/down: use yaw instead
		if(Math.abs(dx) < 0.001 && Math.abs(dz) < 0.001) {
			float yaw = startLoc.getYaw();
			spinAxis = new Vector(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
		}

		ItemDisplay axe = p.getWorld().spawn(startLoc, ItemDisplay.class);
		axe.setItemStack(new ItemStack(Material.DIAMOND_AXE));
		axe.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);

		Vector finalSpinAxis = spinAxis;
		new BukkitRunnable() {
			int distance = 0;
			Location currentLoc = startLoc.clone();
			float spinRotation = 0;
			boolean notedAggro = false;
			final Set<UUID> hit = new HashSet<>();
			// For the "hit N enemies" line, printed once when the axe is spent, not per mob.
			int damaged = 0;
			double dealt = 0;

			private void finish() {
				damage.Damage.reportAoe(p, ability, damaged, dealt);
				axe.remove();
				cancel();
			}

			@Override
			public void run() {
				if(distance >= 100 || !axe.isValid()) {
					finish();
					return;
				}

				Location nextLoc = currentLoc.clone().add(direction);
				if(nextLoc.getBlock().getType().isSolid()) {
					finish();
					return;
				}

				// 1 block per tick
				currentLoc = nextLoc;

				// Taking aggro is a requirement of this ability, so it's noted the first tick the axe overlaps a boss
				// whether or not damage lands. Only this, the mage beam and the Flaming Flay arc may aggro a fully
				// shielded wither; everything else needs real damage.
				boolean stop = false;
				for(Entity e : currentLoc.getWorld().getNearbyEntities(currentLoc, 1.0, 2.0, 1.0)) {
					if(!notedAggro && e instanceof Wither w && w.getScoreboardTags().contains("TASWither")) {
						instructions.bosses.WitherActions.noteDamager(p, w, 0);
						notedAggro = true;
					}
					if(!(e instanceof LivingEntity mob) || e instanceof Player) continue;
					if(mob.isDead() || mob.getHealth() <= 0 || !hit.add(mob.getUniqueId())) continue;
					if(e instanceof Wither w2 && w2.getInvulnerableTicks() != 0) {
						// Stacks still build through the shield, like the beam; no damage.
						damage.Damage.applyOnHitDebuffs(p, w2, damage.DamagePath.MELEE, weapon);
						damage.Procs.buildVenomous(p, w2);
						continue;
					}
					double reported;
					if(derived) {
						// Debuffs (Lethality) still land; only the damage half is skipped, it's already in the figure.
						damage.Damage.applyOnHitDebuffs(p, mob, damage.DamagePath.MELEE, weapon);
						reported = damage.Damage.dealDerived(mob, core, damage.DamageKind.NORMAL, p, damage.DamagePath.MELEE);
					} else {
						double sbDamage = damage.Damage.meleeFinish(p, mob, weapon, core);
						reported = damage.Damage.deal(mob, sbDamage, damage.DamageKind.NORMAL, p, damage.DamagePath.MELEE);
					}
					if(reported > 0) {
						dealt += reported;
						damaged++;
					}
					if(!pierce) stop = true;
				}
				if(stop) {
					finish();
					return;
				}

				spinRotation += 36; // positive = forward spin

				Quaternionf rotation = new Quaternionf().rotateAxis((float) Math.toRadians(spinRotation), (float) finalSpinAxis.getX(), (float) finalSpinAxis.getY(), (float) finalSpinAxis.getZ());

				axe.setTransformation(new Transformation(new Vector3f(0, 0, 0),
						rotation, new Vector3f(1, 1, 1),
						new Quaternionf()
				));

				axe.teleport(currentLoc);

				distance++;
			}
		}.runTaskTimer(M7.getInstance(), 1L, 1L);
	}

	/** True if {@code p} is the Mage CLASS (drives cooldown reduction), by the class tag from /class. */
	public static boolean isMageClass(Player p) {
		return p.getScoreboardTags().contains("Mage");
	}

	/** Mage cooldown reduction: a SOLO mage gets -75%, two or more mages -50%. Non-mages unchanged. Not used for
	*  Terminator or Salvation, which are weapons, not abilities. */
	public static int effectiveCooldown(Player p, int baseTicks) {
		if(!isMageClass(p)) return baseTicks;
		return mageCount() <= 1 ? baseTicks / 4 : baseTicks / 2;
	}

	private static long mageCount() {
		return Bukkit.getOnlinePlayers().stream().filter(ItemUtils::isMageClass).count();
	}

	public static void sendCooldownMessage(Player p, int ticksRemaining) {
		String format = String.format("%.2f", Math.max(0, ticksRemaining) / 20.0);
		p.sendMessage(Utils.msg("<red>This ability is on cooldown for " + format + " seconds!"));
	}

	/**
	 * Full 4/4 Thermodynamic, the plugin's one set bonus: raises the attack-speed cap, so the Terminator's 5-tick
	 * cooldown becomes 4. Checks {@code Wearable.setId}, not the display name, so an unrelated item with the word in
	 * it can't count.
	 */
	public static boolean isThermoSet(Player p) {
		org.bukkit.inventory.PlayerInventory inv = p.getInventory();
		return wearsSet(inv.getHelmet(), ThermodynamicHelmet.THERMODYNAMIC)
				&& wearsSet(inv.getChestplate(), ThermodynamicHelmet.THERMODYNAMIC)
				&& wearsSet(inv.getLeggings(), ThermodynamicHelmet.THERMODYNAMIC)
				&& wearsSet(inv.getBoots(), ThermodynamicHelmet.THERMODYNAMIC);
	}

	private static boolean wearsSet(ItemStack item, String setId) {
		Wearable worn = ItemRegistry.wearable(item);
		return worn != null && worn.setId().equals(setId);
	}

	public static void mageBeam(Player p) {
		Location l = p.getLocation();

		// Three range tiers, each doubling (MAP.md §7): 10+15 = 25 default, 35+15 = 50 boss arena, 70+30 = 100 Wither
		// King. WK needs a PHASE check, since its arena sits inside the boss-arena box.
		double range = damage.Damage.beamRange(p).maxRange();

		double yaw = Math.toRadians(l.getYaw());

		// 90 degrees right
		double rightYaw = yaw + Math.toRadians(90);

		// 16 pixels = 1 block
		double offsetX = -Math.sin(rightYaw) * (5.0 / 16.0);
		double offsetZ = Math.cos(rightYaw) * (5.0 / 16.0);
		double offsetY = 1.62 - (13.0 / 16.0);

		l.add(offsetX, offsetY, offsetZ);

		// DRAWN from the right hand (`l`), AIMED from the eye: the trail runs hand to wherever the crosshair ray ends,
		// so it lands on the crosshair and still leaves the hand, like Hypixel.
		//
		// Replaces casting the ray from the hand along the look direction, which left the trail parallel to the
		// crosshair, ~0.31 right and ~0.81 down, so it never went where you aimed.
		//
		// Only the ENDPOINT is shared: a mob on the hand-to-target segment but off the crosshair ray is crossed
		// without being hit. MAGE_BEAM_LENIENCY (0.5 per face) absorbs that.
		Location eye = p.getEyeLocation();
		Vector direction = eye.getDirection();
		Vector eyeVec = eye.toVector();

		// Trace entities and blocks from the eye; the closer one wins, so a wall blocks the beam. Entities get the
		// leniency margin, blocks stay precise.
		RayTraceResult entityResult = findTargetEntity(p, eye, direction, range);
		RayTraceResult blockResult = p.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);

		double entityDist = entityResult != null ? entityResult.getHitPosition().distance(eyeVec) : Double.MAX_VALUE;
		double blockDist = blockResult != null ? blockResult.getHitPosition().distance(eyeVec) : Double.MAX_VALUE;

		Vector targetPoint;
		Entity targetEntity;
		if(entityResult != null && entityDist <= blockDist) {
			targetEntity = entityResult.getHitEntity();
			targetPoint = entityResult.getHitPosition();
		} else if(blockResult != null) {
			targetEntity = null;
			targetPoint = blockResult.getHitPosition();
		} else {
			// Nothing in range: aim at the far end of the crosshair ray.
			targetEntity = null;
			targetPoint = eyeVec.clone().add(direction.clone().multiply(range));
		}

		Vector handToTarget = targetPoint.clone().subtract(l.toVector());
		double distance = handToTarget.length();
		handToTarget.normalize();

		// Distance to target, not max range
		int iterations = (int) (distance / 0.33333);
		Vector v = handToTarget.multiply(0.33333);

		for(int i = 0; i < iterations; i++) {
			spawnFireworkParticle(l);
			l.add(v);
		}

		// No hurt sound off a dead mob, including a boss pinned dying (TASDying, HP frozen at 1).
		boolean targetDead = targetEntity instanceof LivingEntity dead
				&& (dead.isDead() || dead.getHealth() <= 0 || dead.getScoreboardTags().contains("TASDying"));

		// Hit sounds go ONLY to the beamer (and spectators) at constant volume, never at the target.
		ItemStack held = p.getInventory().getItemInMainHand();
		if(targetEntity instanceof Wither wither && wither.getInvulnerableTicks() != 0) {
			// Armoured (e.g. mid-intro): no damage, but note the damager so the boss aggros them once aggro turns on.
			// Only the beam, thrown axe and Flaming Flay arc may do this (see damage/Damage.deal).
			if(wither.getScoreboardTags().contains("TASWither")) instructions.bosses.WitherActions.noteDamager(p, wither, 0);
			// Debuffs land even without damage (MAP.md §7), so Lethality is stacked by the time it opens up. Covers
			// Maxor and Storm, which can't be arrow-debuffed before they're vulnerable.
			// beam() applies them; its figure goes in the history too, or Venomous ticks for 0 until the first real hit.
			damage.CombatState.recordDamage(p, damage.Damage.beam(p, wither, held, distance));
			damage.Procs.buildVenomous(p, wither);
			if(!targetDead) Utils.playLocalSound(p, Sound.ENTITY_WITHER_HURT, 1.0f, 1.0f);
		} else if(targetEntity instanceof LivingEntity temp) {
			// MELEE hit rescaled by the Mage Staff passive, faded by distance across the range tiers (§7). The old
			// table is gone: Hyperion's "-33% vs non-wither" is now its x1.5 vs Wither (1/1.5 = 0.667), the Rag buff
			// is +150% of the axe's Strength via stats, and the Spring Boots / Racing Helmet penalties are deleted,
			// those now cost only the stats their slot would carry.
			double sbDamage = damage.Damage.beam(p, temp, held, distance);
			// Silence the target so vanilla doesn't play its hurt sound at it; beamDamageInProgress does the same
			// for onWitherHurtSound (withers are always silent, so silence can't signal it).
			boolean wasSilent = temp.isSilent();
			temp.setSilent(true);
			beamDamageInProgress = true;
			try {
				damage.Damage.deal(temp, sbDamage, damage.DamageKind.NORMAL, p, damage.DamagePath.BEAM);
			} finally {
				beamDamageInProgress = false;
				temp.setSilent(wasSilent);
			}
			if(!targetDead) {
				String hurtSound = Utils.getHurtSoundKey(temp);
				if(hurtSound != null) Utils.playLocalSound(p, hurtSound, 1.0f, 1.0f);
			}
		}
	}

	/**
	 * NEAREST mob the ray enters, hitboxes expanded by {@link #MAGE_BEAM_LENIENCY}. Not Bukkit's ray trace, which
	 * returns the first entity found, not the closest. Skips players and Resistance 255 (marks an unhittable mob).
	 */
	private static RayTraceResult findTargetEntity(Player p, Location origin, Vector direction, double range) {
		Vector start = origin.toVector();
		double reach = range + MAGE_BEAM_LENIENCY;
		Entity best = null;
		double bestT = Double.MAX_VALUE;
		Vector bestHit = null;
		for(Entity e : p.getWorld().getNearbyEntities(origin, reach, reach, reach)) {
			if(!(e instanceof LivingEntity le) || e instanceof Player || e.isDead()) continue;
			if(le.hasPotionEffect(PotionEffectType.RESISTANCE) && le.getPotionEffect(PotionEffectType.RESISTANCE).getAmplifier() == 255) continue;
			double t = rayBoxDistance(start, direction, e.getBoundingBox().expand(MAGE_BEAM_LENIENCY), range);
			if(t >= 0 && t < bestT) {
				bestT = t;
				best = e;
				bestHit = start.clone().add(direction.clone().multiply(t));
			}
		}
		return best == null ? null : new RayTraceResult(bestHit, best);
	}

	/**
	 * Slab method: {@code t} where the ray (unit direction) first enters {@code box}, -1 if not within
	 * {@code maxDist}, 0 if it starts inside.
	 */
	private static double rayBoxDistance(Vector start, Vector direction, BoundingBox box, double maxDist) {
		double[] o = {start.getX(), start.getY(), start.getZ()};
		double[] d = {direction.getX(), direction.getY(), direction.getZ()};
		double[] lo = {box.getMinX(), box.getMinY(), box.getMinZ()};
		double[] hi = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
		double tmin = 0.0, tmax = maxDist;
		for(int i = 0; i < 3; i++) {
			if(Math.abs(d[i]) < 1e-8) {
				if(o[i] < lo[i] || o[i] > hi[i]) return -1; // parallel to this slab and outside it
			} else {
				double t1 = (lo[i] - o[i]) / d[i];
				double t2 = (hi[i] - o[i]) / d[i];
				if(t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
				tmin = Math.max(tmin, t1);
				tmax = Math.min(tmax, t2);
				if(tmin > tmax) return -1;
			}
		}
		return tmin;
	}

	public static void spawnFireworkParticle(Location l) {
		ClientboundLevelParticlesPacket packet = new ClientboundLevelParticlesPacket(ParticleTypes.FIREWORK, false, false, l.getX(), l.getY(), l.getZ(), 0.0f, 0.0f, 0.0f, 0.0f, 1);
		Utils.broadcastPacket(packet);
	}
}
