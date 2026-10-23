package instructions.bosses;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import instructions.bosses.maxor.Maxor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.craftbukkit.profile.CraftPlayerProfile;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import plugin.WatcherFix;
import plugin.BossScheduler;
import plugin.M7;
import plugin.Utils;

import java.util.*;

/**
 * Blood Room pre-boss. Detection armed at clear-tick 0 spawns the Watcher the first tick a player is in bounds; kill
 * lines fire on real Blood-Mob deaths ({@link #handleMobDeath}); 80t after the last death a portal opens that
 * teleports the party to the boss spawn and hands off to Maxor (clear-only: entry ends the run). Singleton; {@link #resetState()} clears per-fight state.
 */
public class Watcher {
	public static final Watcher INSTANCE = new Watcher();

	private Watcher() {
	}

	private Zombie watcher;
	private World world;
	private static final Location ORIGINAL_POSITION = new Location(null, -120.5, 72.0, -56.5, -180, 0);
	private final List<Location> MOB_SPAWN_LOCATIONS = new ArrayList<>();
	/**
	 * Blood mob names in spawn order, parallel to {@link #MOB_SPAWN_LOCATIONS}, filled at {@link #spawnEncounter}.
	 * Ask {@link #mobTotal()}, never "15".
	 */
	private final List<String> mobNames = new ArrayList<>();
	private static final List<String> SPAWN_LINES = List.of("This guy looks like a fighter.", "Hmmm... this one!", "You'll do.", "Go, fight!", "Go and live again!");
	private static final List<String> KILLED_LINES = List.of("Not bad.", "That one was weak anyway.", "I'm impressed.", "Very nice.", "Aw, I liked that one.");
	private int mobCount = 0;
	private int mobsKilled = 0;
	private static final Random random = new Random();
	private static final double MAX_SPEED = 1.0;        // blocks per tick
	private static final double ACCEL = 0.1;            // blocks per tick per tick
	private static final double HEAD_ACCEL = 0.08;      // blocks per tick per tick, the head stand
	/** Phase tick the second wave starts, from the Blood Room opening: the hard cooldown {@link WatcherFix} removes. */
	private static final int SECOND_WAVE_TICK = 420;
	/** {@link WatcherFix}: second wave this long after he is back on his perch. */
	private static final int FIXED_SECOND_WAVE_DELAY = 60;

	private BossBar watcherBossBar;

	private boolean active = false;            // encounter running (detection / death guard)
	private boolean doContinue = false;        // chain into Maxor on portal entry (set by arm())
	private int triggerPhaseTick = 0;          // Utils.phaseTick() at spawn, the overall-column basis
	private BukkitTask detectTask;
	private BukkitTask portalDetectTask;

	// Blood Room bounds
	private static final double BR_MIN_X = -136, BR_MAX_X = -106;
	private static final double BR_MIN_Y = 66, BR_MAX_Y = 99;
	private static final double BR_MIN_Z = -72, BR_MAX_Z = -42;

	private static final Location BOSS_SPAWN = new Location(null, 73.5, 221, 14.5, 0f, 0f);

	/** Where the portal drops the party; {@code OutOfBounds}' boss-phase revive point too. */
	public static Location bossSpawn(World w) {
		Location l = BOSS_SPAWN.clone();
		l.setWorld(w);
		return l;
	}

	// ============================== Arming & detection ==============================

	/** Does NOT spawn or detect; that's {@link #beginDetection(World)} at clear-tick 0. From Practice.runPractice. */
	public void arm(World w, boolean doContinue) {
		this.world = w;
		this.doContinue = doContinue;
	}

	/** One-shot scan from clear-tick 0: spawns the Watcher the first tick a player is in bounds. */
	public void beginDetection(World w) {
		this.world = w;
		if(detectTask != null && !detectTask.isCancelled()) {
			detectTask.cancel();
		}
		detectTask = new BukkitRunnable() {
			@Override
			public void run() {
				if(active) {
					cancel();
					return;
				}
				if(firstInBounds() != null) {
					spawnEncounter();
					cancel();
				}
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	/**
	 * Spawns on Blood Door open, like F7, not on walking in. Only if {@link #beginDetection} armed this run and he
	 * hasn't spawned; cancels the bounds scan.
	 */
	public void startOnBloodDoor() {
		if(active) return;
		if(detectTask == null || detectTask.isCancelled()) return; // not armed this run
		detectTask.cancel();
		spawnEncounter();
	}

	/** First in-bounds non-spectator, else null. */
	private Player firstInBounds() {
		if(world == null) return null;
		for(Player p : world.getPlayers()) {
			if(Utils.isSpectator(p)) continue;
			if(inBloodRoom(p.getLocation())) return p;
		}
		return null;
	}

	private static boolean inBloodRoom(Location l) {
		return l.getX() >= BR_MIN_X && l.getX() <= BR_MAX_X
				&& l.getY() >= BR_MIN_Y && l.getY() <= BR_MAX_Y
				&& l.getZ() >= BR_MIN_Z && l.getZ() <= BR_MAX_Z;
	}

	// ============================== Encounter ==============================

	private void spawnEncounter() {
		resetState();
		active = true;
		triggerPhaseTick = Utils.phaseTick();
		ORIGINAL_POSITION.setWorld(world);

		Utils.timer("<green>Watcher spawned on tick " + triggerPhaseTick);

		watcher = (Zombie) world.spawnEntity(ORIGINAL_POSITION, EntityType.ZOMBIE);
		watcher.addScoreboardTag("TASWatcher");
		watcher.setAI(false);
		watcher.setSilent(true);
		watcher.setPersistent(true);
		watcher.setRemoveWhenFarAway(false);

		Multimap<String, Property> props = HashMultimap.create();
		props.put("textures", new Property("textures", "ewogICJ0aW1lc3RhbXAiIDogMTc0Njg0OTQ1NjQxOSwKICAicHJvZmlsZUlkIiA6ICIxZjk0OTQzN2RlYmQ0ODgyYTlhYzZhZmZmN2RhNDcxMSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaWlra2FLYSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS83YjBkNTI3OGNkZWUwNGM1MjBhOWY1ZDE1M2E1MmI0ZWZjNzBmMzAzMjM5MjY2OGQyMTExNjJkNWFkYzAxYjExIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=", "awvIfqy7f12hqzBY/BZrhqCpC3xl0zeb0xTOVERZlzXsmk+ZivSyC8ZAlsR1Kmam0aLNDlvO3Nrl8ZGg5n77H+aUkZsoGz4DsuV2GoFv71UxXpPgAVkiCw0kPmNr9O17JChNU2HrO2hd1X3kqPX9gbA/JZ4+kCpcmbEtr7+VAl7xScOEWvKZPimdijG6hkNrBnkcttk+TYdIenrKNrZf346l2nD9nRif+1istHv9ouxZ7GguZPFFTTqtuljhdjsDQ5lQnFN/Q0b4cENMErlAkzam4n2jwTBJPWz9BeIUdgpOr4qyp4bTOLrD3mVfdSEJ+Q4hMjQLZZeYLxMZLSCqm56ns+rzm7O0aj7/+sjxngWZuT8z4U+g2J5QOOA3n8R3Z+QvEHitb1RZdM8DccYb9VwSbGG2jZ8acInxSoIT5bFWWfp0Bh+rwfuNe+v2hFReyUz35BwKrYUOxqL4+A7/McSpik/C+9BVMYL5n78FMD+1+SlJniMwAoPlRpz87yGYivEH9aAlEnTLE+7Tpp6wsiFCaQp5WJ8vfJnV9HVxDYjFs7xB29Cw+FIQnYSsT5U7Uv6znjBMWRmHI9zeU7GzQ0eNQkThSbzX+dE/c1WyPXVuL/wTfefbgh6jm1i6rNGz/a3RdnWk8ItXu/pYQjSmKnc2FJH+x28VXkYl3qQr0gw="));
		PropertyMap propertyMap = new PropertyMap(props);
		GameProfile gp = new GameProfile(UUID.randomUUID(), "watcher", propertyMap);

		CraftPlayerProfile profile = new CraftPlayerProfile(gp);

		ItemStack helmet = new ItemStack(Material.PLAYER_HEAD);
		SkullMeta meta = (SkullMeta) helmet.getItemMeta();
		assert meta != null;
		meta.setPlayerProfile(profile);
		helmet.setItemMeta(meta);

		Objects.requireNonNull(watcher.getEquipment()).setHelmet(helmet);
		watcher.getEquipment().setChestplate(new ItemStack(Material.AIR));
		watcher.getEquipment().setLeggings(new ItemStack(Material.AIR));
		watcher.getEquipment().setBoots(new ItemStack(Material.AIR));
		watcher.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));
		watcher.getEquipment().setItemInOffHand(new ItemStack(Material.AIR));
		watcher.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, -1, 255, false, false));
		watcher.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, -1, 255, false, false));
		Objects.requireNonNull(watcher.getAttribute(Attribute.ARMOR)).setBaseValue(-30);
		Objects.requireNonNull(watcher.getAttribute(Attribute.ARMOR_TOUGHNESS)).setBaseValue(-20);
		Objects.requireNonNull(watcher.getAttribute(Attribute.SCALE)).setBaseValue(1.5);

		// Before the boss bar, which counts down from the table's size.
		fillMobTables();
		createWatcherBossBar();

		// Anchored to the real entry tick, not the old hardcoded 3-tick "time to bounds".
		sendChatMessage("Ah, we meet again.  As I foresaw...");
		Utils.scheduleTask(() -> travelToAndSpawnMob(MOB_SPAWN_LOCATIONS.getFirst(), mobNames.getFirst()), 90);
	}

	/**
	 * 15 mobs; Diamante Giant and Bonzo take s3a3m3's and editqble's old spots so the opening trip is short. The first
	 * four are the first wave, then he returns to his perch ({@link #returnToOriginalPosition()}).
	 */
	private void fillMobTables() {
		MOB_SPAWN_LOCATIONS.clear();
		mobNames.clear();
		mob(-109.5, 71, -52.5, "Diamante Giant"); // s3a3m3's spawn point
		mob(-111.5, 71, -45.5, "Bonzo");          // editqble's spawn point
		mob(-111.5, 75, -45.5, "BeansBeans13");
		mob(-111.5, 79, -45.5, "valej");
		/* -------------------- back to the perch until tick 420 -------------------- */
		mob(-109.5, 75, -52.5, "JennAiel");
		mob(-109.5, 79, -52.5, "_a18");
		mob(-109.5, 79, -56.5, "BananaBrigade");
		mob(-109.5, 79, -60.5, "Katsumi9877");
		mob(-111.5, 79, -67.5, "derM0RITZZ");
		mob(-111.5, 75, -67.5, "aalatif_");
		mob(-111.5, 71, -67.5, "oystor");
		mob(-109.5, 71, -60.5, "Cubpletionist");
		mob(-109.5, 75, -60.5, "akc0303");
		mob(-109.5, 75, -56.5, "AsapIcey");
		mob(-109.5, 71, -56.5, "Beethoven_");
	}

	/** Where he flies to and who he drops there. */
	private void mob(double x, double y, double z, String name) {
		MOB_SPAWN_LOCATIONS.add(new Location(world, x, y, z));
		mobNames.add(name);
	}

	/** What the boss bar counts down from. */
	private int mobTotal() {
		return mobNames.size();
	}

	// ============================== Event-driven kills ==============================

	// A kill can arrive twice (EntityDeathEvent AND the Damage.deal kill chokepoint), so dedupe by UUID.
	private final Set<UUID> countedMobKills = new HashSet<>();

	/** From the EntityDeathEvent listener. */
	public void handleMobDeath(EntityDeathEvent e) {
		registerMobKill(e.getEntity());
	}

	/**
	 * Counts a kill once, via {@link #handleMobDeath} or the {@code damage.Damage.deal} chokepoint. The chokepoint is
	 * the backstop for an instakill on the spawn tick, which EntityDeathEvent misses since the entity isn't fully ticked in.
	 */
	public void registerMobKill(LivingEntity mob) {
		if(!active) return;
		if(!mob.getScoreboardTags().contains("WatcherMob")) return;
		if(!countedMobKills.add(mob.getUniqueId())) return;

		mobsKilled++;
		updateWatcherBossBar();
		Utils.timer("<green>Blood Mob " + mobsKilled + "/" + mobTotal() + " killed | " + formatTick(phaseRel()));

		if(mobsKilled < mobTotal()) {
			sendChatMessage(KILLED_LINES.get(random.nextInt(5)));
		} else {
			sendChatMessage("You have proven yourself.  You may pass.");
			// Both modes: "all" portals to Maxor, clear-only ends on portal entry.
			Utils.scheduleTask(this::openPortal, 80);
		}
	}

	/** Here so it reports on the Watcher phase clock, like "Entered Boss". */
	public void bloodCampFinished() {
		Utils.timer("<green>Blood Camp finished in " + formatTick(phaseRel()));
		// Leaderboard milestone; the end of a clear-only practice.
		instructions.clear.ClearManager.noteBloodDone();
	}

	/** Power V + Life V + green check, when the Watcher vanishes, NOT on the final kill. */
	private void awardBloodClear() {
		if(instructions.clear.ClearManager.isActive()) {
			org.bukkit.entity.Player near = instructions.clear.ClearManager.nearestRealPlayer(new org.bukkit.Location(world, -121, 70, -57));
			instructions.clear.ClearManager.minibossKilled(instructions.clear.Rooms.BLOOD, near);
		}
	}

	// ============================== Portal sequence ==============================

	private void openPortal() {
		// He vanishes with the strike; counters stay until handoff.
		removeWatcherEntity();
		bloodCampFinished(); // only once the Watcher vanishes
		awardBloodClear(); // lands as the portal appears

		world.spawnEntity(new Location(world, -120.5, 69, -42.5), EntityType.LIGHTNING_BOLT);
		Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_IMPACT);
		Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
		Utils.runCommand("fill -120 69 -43 -122 72 -43 minecraft:nether_portal[axis=x]");
		Utils.timer("<green>Boss Portal Opened in " + formatTick(phaseRel()));
		Utils.debug(Utils.DebugType.BOSS, "Lightning struck, nether portal opened" + (Utils.isSuperVerbose() ? " at -120..-122 / 69..72 / -43" : ""));

		if(portalDetectTask != null && !portalDetectTask.isCancelled()) {
			portalDetectTask.cancel();
		}
		portalDetectTask = new BukkitRunnable() {
			@Override
			public void run() {
				for(Player p : world.getPlayers()) {
					// A spectator must not warp the party; after a run everyone IS one.
					if(Utils.isSpectator(p)) continue;
					if(inPortal(p)) {
						enterPortal(p);
						cancel();
						return;
					}
				}
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	/** Pairs with {@link #openPortal}'s fill; from {@link #enterPortal} and {@link #cleanup}. */
	private void closePortal() {
		if(world == null) return;
		Utils.runCommand("fill -120 69 -43 -122 72 -43 minecraft:air");
	}

	/** Portal blocks x [-122,-120], y [69,72], z -43; axis=x, so only the middle 4px of z is the portal. */
	private static final org.bukkit.util.BoundingBox PORTAL = new org.bukkit.util.BoundingBox(-122, 69, -43 + 6 / 16.0, -119, 73, -43 + 10 / 16.0);

	/** Hitbox touches the portal itself, as vanilla's entity-inside test. */
	private static boolean inPortal(Player p) {
		return p.getBoundingBox().overlaps(PORTAL);
	}

	private void enterPortal(Player p) {
		Location boss = BOSS_SPAWN.clone();
		boss.setWorld(world);
		// Clear split ends on portal entry (Wither-King practice scoreboard).
		WitherActions.recordSplit("Clear", Utils.runTick());
		Utils.timer("<green>Portal entered in " + formatTick(phaseRel()));
		// Console, not debug: the network runs at verbose timer, which hides who ended the clear.
		M7.getInstance().getLogger().info("Watcher portal entered by " + p.getName() + " (" + p.getGameMode() + ") at " + fmt(p.getLocation()));
		if(!doContinue) {
			Utils.debug(Utils.DebugType.BOSS, p, "Portal entered by " + p.getName() + " → clear ends");
			closePortal();
			active = false;
			WitherActions.signalRunComplete();
			return;
		}
		Utils.debug(Utils.DebugType.BOSS, p, "Portal entered by " + p.getName() + " → teleporting all players");

		// Teleport THIS tick; the boss starts NEXT tick.
		for(Player pl : world.getPlayers()) {
			if(Utils.isSpectator(pl)) continue;
			pl.teleport(boss);
		}
		// doContinue is true for "all", so Maxor → Storm → ….
		Utils.scheduleTask(() -> Maxor.maxorInstructions(world, doContinue), 1);

		closePortal();
		active = false;
	}

	// ============================== Boss bar ==============================

	private void createWatcherBossBar() {
		String title = Utils.mmLegacy("<gold><bold>﴾ <red>The Watcher<gold> ﴿ </bold><yellow>" + mobTotal() + "<red>❤");

		watcherBossBar = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID);
		watcherBossBar.setProgress(1.0);

		for(Player player : Bukkit.getOnlinePlayers()) {
			watcherBossBar.addPlayer(player);
		}
	}

	private void updateWatcherBossBar() {
		if(watcherBossBar == null) {
			return;
		}

		int mobsRemaining = mobTotal() - mobsKilled;
		double progress = mobTotal() == 0 ? 0 : mobsRemaining / (double) mobTotal();

		String title = Utils.mmLegacy("<gold><bold>﴾ <red>The Watcher<gold> ﴿ </bold><yellow>" + mobsRemaining + "<red>❤");

		watcherBossBar.setTitle(title);
		watcherBossBar.setProgress(Math.clamp(progress, 0.0, 1.0));
	}

	// ============================== Choreography (unchanged) ==============================

	private void travelToAndSpawnMob(Location l, String mobName) {
		Location current = watcher.getLocation();
		moveEntitySmooth(watcher, current, l, watcherSpeed(), () -> {
			mobCount++;
			final int idx = mobCount; // 1-based; mobCount advances as spawns chain
			Location headStart = l.clone();
			Location endLoc = new Location(world, -120.5, 75, -56.5);

			ArmorStand stand = world.spawn(headStart, ArmorStand.class);
			stand.setGravity(false);
			stand.setVisible(false);
			stand.setCustomNameVisible(false);
			stand.setInvulnerable(true);
			// Marker = no hitbox. The stand drifts where players aim; with a hitbox arrows hit it, our damage path
			// ignores setInvulnerable and kills it, and even a cancelled hit eats pierce. Head and pose still render.
			stand.setMarker(true);
			stand.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, -1, 255));
			ItemStack zombieHead = new ItemStack(Material.ZOMBIE_HEAD);
			Objects.requireNonNull(stand.getEquipment()).setHelmet(zombieHead);

			if(Utils.isSuperVerbose()) Utils.debug(Utils.DebugType.BOSS, "Begin spawning " + mobName + " (" + fmt(l) + ")");
			moveEntitySmooth(stand, headStart.clone().add(0, 1, 0), endLoc, 0.4, () -> {
				// Only the spawn is on the boss lane; travel stays on the raw schedule. schedule(...,1) fires at the
				// START of next tick, so the mob exists before the mage's same-tick beam (mid-tick lost that race).
				BossScheduler.schedule(() -> {
					spawnMob(endLoc, mobName);
					Utils.timer("<green>Blood Mob " + idx + "/" + mobTotal() + " spawned (" + mobName + ") | " + formatTick(phaseRel()));
					stand.remove();
				}, 1);
			});

			sendChatMessage(SPAWN_LINES.get(random.nextInt(5)));

			if(mobCount == 4 || mobCount == mobTotal()) {
				returnToOriginalPosition();
			} else {
				travelToAndSpawnMob(MOB_SPAWN_LOCATIONS.get(mobCount), mobNames.get(mobCount));
			}
		});
	}

	/**
	 * Trapezoid speed profile (accel, cruise at {@code maxSpeed}, decel), one teleport per tick. Tick counts round up,
	 * so the raw profile misses the distance and the old code snapped with {@code teleport(end)} on the last tick.
	 * {@code scale} stretches {@code cumulative} to land on {@code end}; tick count unchanged.
	 */
	private void moveEntitySmooth(Entity entity, Location start, Location end, double maxSpeed, Runnable onComplete) {
		final double accel = entity.equals(watcher) ? ACCEL : HEAD_ACCEL;
		final Vector totalVector = end.toVector().subtract(start.toVector());
		final double totalDistance = totalVector.length();
		final Vector direction = totalVector.clone().normalize();

		final double accelDist = (maxSpeed * maxSpeed) / (2 * accel);

		int accelTicks, cruiseTicks, decelTicks;

		if(totalDistance < (accelDist + accelDist)) {
			// Triangular: never reaches maxSpeed
			double peakSpeed = Math.sqrt(totalDistance * accel);
			accelTicks = (int) Math.ceil(peakSpeed / accel);
			decelTicks = accelTicks;
			cruiseTicks = 0;
		} else {
			accelTicks = (int) Math.ceil(maxSpeed / accel);
			decelTicks = accelTicks;
			double cruiseDistance = totalDistance - accelDist - accelDist;
			cruiseTicks = (int) Math.ceil(cruiseDistance / maxSpeed);
		}

		final int movementTicks = accelTicks + cruiseTicks + decelTicks;

		// Distance by the END of each tick, unscaled. Index 0 is the start, so read cumulative[tick + 1].
		final double[] cumulative = new double[movementTicks + 1];
		double travelled = 0;
		for(int i = 0; i < movementTicks; i++) {
			double speed;
			if(i < accelTicks) speed = accel * (i + 1);
			else if(i < accelTicks + cruiseTicks) speed = maxSpeed;
			else speed = Math.max(0, maxSpeed - accel * (i - accelTicks - cruiseTicks + 1));
			travelled += speed;
			cumulative[i + 1] = travelled;
		}
		final double scale = travelled > 1e-9 ? totalDistance / travelled : 1.0;

		entity.teleport(start.clone());

		new BukkitRunnable() {
			int tick = 0;

			@Override
			public void run() {
				if(!entity.isValid()) {
					cancel();
					return;
				}

				// Read off the profile, not accumulated, so a scaled trip lands on `end`.
				Location currentLoc = start.clone().add(direction.clone().multiply(cumulative[tick + 1] * scale));

				if(entity.equals(watcher)) {
					watcher.teleport(currentLoc.clone().setDirection(direction));
				} else {
					entity.teleport(currentLoc.clone());
				}

				if(entity instanceof ArmorStand armorStand) {
					world.spawnParticle(Particle.DUST, currentLoc.clone().add(0, 1.5, 0), 0, new Particle.DustOptions(Color.BLACK, 1));
					world.spawnParticle(Particle.DUST, currentLoc.clone().add(0, 1.5, 0), 0, new Particle.DustOptions(Color.PURPLE, 1));
					world.spawnParticle(Particle.DUST, currentLoc.clone().add(0, 1.5, 0), 0, new Particle.DustOptions(Color.WHITE, 1));
					EulerAngle pose = armorStand.getHeadPose();
					armorStand.setHeadPose(pose.add(0, Math.toRadians(25), 0));
				}

				tick++;
				if(tick >= movementTicks) {
					entity.teleport(end);

					if(entity.equals(watcher)) {
						Vector lookVec = ORIGINAL_POSITION.toVector().subtract(end.toVector());
						if(lookVec.lengthSquared() < 1e-6) {
							// At ORIGINAL_POSITION: preserve its yaw/pitch
							Location finalLoc2 = end.clone();
							finalLoc2.setYaw(ORIGINAL_POSITION.getYaw());
							finalLoc2.setPitch(ORIGINAL_POSITION.getPitch());
							watcher.teleport(finalLoc2);
							if(onComplete != null) {
								onComplete.run();
							}
							cancel();
							return;
						} else {
							lookVec.normalize();
						}
						Location finalLoc = end.clone();
						finalLoc.setDirection(lookVec);
						watcher.teleport(finalLoc);
					}

					if(onComplete != null) {
						onComplete.run();
					}
					cancel();
				}
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	private void spawnMob(Location location, String mobName) {
		Zombie mob = (Zombie) world.spawnEntity(location, EntityType.ZOMBIE);
		mob.setAdult();
		// Clear random armor and prevent chicken jockey from finalizeSpawn
		Objects.requireNonNull(mob.getEquipment()).setHelmet(null);
		mob.getEquipment().setChestplate(null);
		mob.getEquipment().setLeggings(null);
		mob.getEquipment().setBoots(null);
		if(mob.isInsideVehicle()) {
			Entity vehicle = mob.getVehicle();
			mob.leaveVehicle();
			if(vehicle != null) vehicle.remove();
		}
		mob.setCustomNameVisible(true);
		mob.addScoreboardTag("WatcherMob");
		mob.setAI(true);
		Utils.scheduleTask(() -> mob.setAI(false), 20);
		mob.setGravity(true);
		mob.setSilent(true);
		mob.setPersistent(true);
		mob.setRemoveWhenFarAway(false);

		// Real HP/defense (MAP.md §5); the Watcher stays immune but adds must be damaged down. All carry x0.1 boss
		// resistance; the 6M rank-and-file also 2000 defense.
		if(mobName.equals("Diamante Giant")) {
			damage.MobStats.apply(mob, damage.MobStats.DIAMANTE_GIANT);
			mob.customName(Utils.msg("<yellow>" + mobName + "<red> ❤<yellow>" + Utils.formatHealthM(mob)));
			Objects.requireNonNull(mob.getAttribute(Attribute.SCALE)).setBaseValue(6);
			Objects.requireNonNull(mob.getEquipment()).setHelmet(new ItemStack(Material.DIAMOND_HELMET));
			mob.getEquipment().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
			mob.getEquipment().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
			mob.getEquipment().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
			mob.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
		} else if(mobName.equals("Bonzo")) {
			damage.MobStats.apply(mob, damage.MobStats.BONZO);
			mob.customName(Utils.msg("<yellow>" + mobName + "<red> ❤<yellow>" + Utils.formatHealthM(mob)));
			Objects.requireNonNull(mob.getEquipment()).setItemInMainHand(new ItemStack(Material.BLAZE_ROD));
		} else {
			damage.MobStats.apply(mob, damage.MobStats.WATCHER_UNDEAD);
			mob.customName(Utils.msg("<yellow>" + mobName + "<red> ❤<yellow>" + Utils.formatHealthM(mob)));
			Objects.requireNonNull(mob.getEquipment()).setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
			mob.getEquipment().setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
			mob.getEquipment().setBoots(new ItemStack(Material.CHAINMAIL_BOOTS));
			mob.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
		}
		Objects.requireNonNull(location.getWorld()).playSound(location, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0F, 2.0F);
	}

	private void returnToOriginalPosition() {
		if(watcher != null && world != null) {
			if(mobCount != mobTotal()) {
				Runnable secondWave = () -> {
					Utils.debug(Utils.DebugType.BOSS, "Watcher moved");
					travelToAndSpawnMob(MOB_SPAWN_LOCATIONS.get(mobCount), mobNames.get(mobCount));
				};
				// Latched so a mid-trip flip can't start the second wave twice or never.
				boolean fixed = WatcherFix.enabled();
				moveEntitySmooth(watcher, watcher.getLocation(), ORIGINAL_POSITION, watcherSpeed(), () -> {
					sendChatMessage("Let's see how you can handle this.");
					if(fixed) Utils.scheduleTask(secondWave, FIXED_SECOND_WAVE_DELAY);
				});
				if(fixed) return;
				// Waits for an ABSOLUTE tick: second wave 21s after the Blood Room opened, however long the first
				// four took. Min 1, the chain hands off through the scheduler.
				Utils.scheduleTask(secondWave, Math.max(1, SECOND_WAVE_TICK - phaseRel()));
			} else {
				moveEntitySmooth(watcher, watcher.getLocation(), ORIGINAL_POSITION, watcherSpeed(), null);
			}
		}
	}

	/** Blocks per tick. The head stand keeps its own 0.4. */
	private static double watcherSpeed() {
		return MAX_SPEED;
	}

	private void sendChatMessage(String message) {
		Bukkit.broadcast(Utils.msg("<red>[BOSS] The Watcher<white>: " + message));
	}

	// ============================== Cleanup / state ==============================

	/** Clears per-fight flags, counters, tasks, leftover mobs. */
	private void resetState() {
		mobCount = 0;
		mobsKilled = 0;
		countedMobKills.clear();
		MOB_SPAWN_LOCATIONS.clear();
		mobNames.clear();
		if(portalDetectTask != null && !portalDetectTask.isCancelled()) {
			portalDetectTask.cancel();
		}
		portalDetectTask = null;
		// Stale Blood Mobs from an aborted fight would miscount.
		if(world != null) {
			for(Entity ent : world.getEntitiesByClass(Zombie.class)) {
				if(ent.getScoreboardTags().contains("WatcherMob")) ent.remove();
			}
		}
	}

	private void removeWatcherEntity() {
		if(watcherBossBar != null) {
			watcherBossBar.removeAll();
			watcherBossBar = null;
		}
		if(watcher != null && !watcher.isDead()) {
			watcher.remove();
		}
		watcher = null;
	}

	private void cleanup() {
		removeWatcherEntity();
		if(detectTask != null && !detectTask.isCancelled()) {
			detectTask.cancel();
		}
		detectTask = null;
		if(portalDetectTask != null && !portalDetectTask.isCancelled()) {
			portalDetectTask.cancel();
		}
		portalDetectTask = null;
		// Else a run ending between openPortal and entry left the portal up for good: no serverSetup fill covers it.
		closePortal();
		active = false;
		mobsKilled = 0;
		mobCount = 0;
		countedMobKills.clear();
	}

	// For /setup and /reset
	public static void forceCleanup() {
		INSTANCE.cleanup();
	}

	public static BossBar getActiveBossBar() {
		return INSTANCE.watcherBossBar;
	}

	// ============================== Logging (like WitherLord) ==============================

	private int phaseRel() {
		return Utils.phaseTick() - triggerPhaseTick;
	}

	/** Phase = ticks since the Watcher engaged; overall = triggerPhaseTick + t. */
	private String formatTick(int t) {
		int overall = t + triggerPhaseTick;
		return "<green>" + String.format("Watcher: %s ticks (%.2f seconds) | Overall: %s ticks (%.2f seconds)",
				formatWithSpaces(t), t / 20.0, formatWithSpaces(overall), overall / 20.0);
	}

	private static String formatWithSpaces(int n) {
		return WitherLord.formatWithSpaces(n);
	}

	private static String fmt(Location l) {
		return Utils.round(l.getX(), 1) + " " + Utils.round(l.getY(), 1) + " " + Utils.round(l.getZ(), 1);
	}
}
