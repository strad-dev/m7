package instructions.bosses.maxor;

import instructions.Server;
import instructions.bosses.CustomBossBar;
import instructions.bosses.WitherLord;
import instructions.bosses.storm.Storm;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import plugin.BossScheduler;
import plugin.ClassKits;
import plugin.Utils;

import java.time.Duration;
import java.util.*;

@SuppressWarnings("DataFlowIssue")
public final class Maxor extends WitherLord {
	public static final Maxor INSTANCE = new Maxor();

	private static final String ENERGY_CRYSTAL_ID = "skyblock/game/energy_crystal";
	private static final String ENERGY_CRYSTAL_NAME = "<gold><bold>﴾ <red>Energy Crystal<gold> ﴿";
	private static final String[] LASER_MESSAGE = {"YOU TRICKED ME!", "THAT BEAM!  IT HURTS!  IT HURTS!"};

	// Laser/stun cycle constants.
	private static final double LASER_CENTER_X = 73.5;
	private static final double LASER_CENTER_Z = 73.5;
	private static final double LASER_RADIUS_SQ = 2.5 * 2.5;
	private static final int CHARGE_DELAY_TICKS = 28;
	// Laser only tests Maxor's position on phase ticks divisible by this. Also the action bar's countdown period.
	private static final int LASER_CYCLE_TICKS = 10;
	// Maxor starts moving here, so it's also where the laser countdown starts.
	private static final int AGGRO_TICK = 80;
	// Stun to auto-enrage. A constant because the action bar counts down the same number the enrage is scheduled on.
	private static final int STUN_AUTO_ENRAGE_TICKS = 160;
	private static final int CRYSTAL_RESPAWN_DELAY_TICKS = 40;

	// Energy Crystal pressure plates. Stonk and break immune (isProtected).
	private static final int PLATE_Y = 224, PLATE_Z = 41;
	private static final int PLATE_LEFT_X = 94, PLATE_RIGHT_X = 52;

	private final Random random = new Random();

	// Top spawn crystals, pickupable. Nulled on pickUp.
	private EnderCrystal topLeftCrystal;
	private EnderCrystal topRightCrystal;
	// Plate-placed, NOT pickupable.
	private EnderCrystal plateLeftCrystal;
	private EnderCrystal plateRightCrystal;
	// Click-to-place prompts over an empty plate while anyone carries a crystal. Index 0 left, 1 right.
	private final Interaction[] plateInteractions = new Interaction[2];
	private final TextDisplay[] plateLabels = new TextDisplay[2];
	private final Map<UUID, ItemStack> previousSlot8 = new HashMap<>();
	/** Every opening-wave miner across all groups, so a re-spawn can clear the previous set. */
	private final List<WitherSkeleton> miners = new ArrayList<>();

	// Boss ticker so the stun applies BEFORE players' beams each tick; a beam on the stun tick reads post-stun state.
	private Runnable laserTicker;
	// Boss-lane, so it fires at the start of its tick.
	private Runnable stunEnrageTask;
	private boolean platesActive;
	private boolean inStun;
	private double stunDamageDealt;
	// Latched at the 75% stun cap; clampDamage rejects everything until the next stun. Without it, same-tick arrows
	// after the cap-enrage over-DPS, since enrage flips inStun=false mid-tick and re-opens the uncapped path.
	private boolean stunCapReached;
	// A ticker, not an event: the plate's powered flag is a level, not an edge.
	private Runnable plateTicker;

	// Own ticker, not the laser scan's: that is removed on the stun, and two of three segments outlive it.
	private Runnable barTicker;
	// So the bar counts down the mechanic's own clocks.
	private int stunEndTick;
	// Lets an idle phase clear the bar once instead of broadcasting an empty one every tick.
	private boolean barShown;

	private Maxor() {
		register(this);
	}

	/** For /m7practice, Watcher and the boss chain. */
	public static void maxorInstructions(World world, boolean doContinue) {
		INSTANCE.start(world, doContinue);
		// Autopet "boss fight starts" trigger, after start() so the entity exists. No-op outside realistic.
		pets.Autopet.onMaxorSpawn();
	}

	@Override
	protected String name() {
		return "Maxor";
	}

	@Override
	protected String displayName() {
		return "Maxor";
	}

	@Override
	protected Location spawnLocation() {
		return new Location(world, 73.5, 226, 53.5, 0f, 0f);
	}

	@Override
	protected double maxHealth() {
		return damage.MobStats.MAXOR.internalHealth();
	}

	@Override
	protected void resetState() {
		cancelLaserScan();
		cancelStunEnrageTask();
		cancelBarTicker();
		cancelPlateTicker();
		removePlatePrompts();
		CustomBossBar.removeStunIndicator();
		inStun = false;
		stunDamageDealt = 0;
		stunCapReached = false;
		stunEndTick = 0;
		platesActive = false;
		// Indicator goes red in beginLaserCharge and back only in triggerStun, so a run ending between them left it
		// red. Black is idle (serverSetup:289 sets the same block).
		Utils.runCommand("setblock 73 224 73 minecraft:black_stained_glass");
		// Crystals are cleared by resetCrystals() in onStart.
	}

	@Override
	protected void onStart() {
		startBarTicker();
		startPlateTicker();

		// Plates arm the tick the fight does. The poll picks up an already-pressed plate next tick, so no rechecks to queue.
		Utils.scheduleTask(() -> platesActive = true, AGGRO_TICK);

		resetCrystals();

		sendChatMessage("WELL WELL WELL, LOOK WHO'S HERE!");
		Utils.scheduleTask(() -> sendChatMessage("I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU."), 40);
		// Third line lands ON the aggro tick.
		Utils.scheduleTask(() -> sendChatMessage("DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE."), 80);
		Utils.scheduleTask(() -> {
			setAggro(3.0, 1.0, 0.5);
			spawnMiners();
			Utils.playGlobalSound(Sound.ENTITY_WITHER_SPAWN);
			Utils.playGlobalSound(Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0F, 2.0F);
		}, AGGRO_TICK);
	}

	@Override
	protected void chainNext(boolean doContinue) {
		if(doContinue) {
			Storm.stormInstructions(world, true);
		} else {
			instructions.bosses.WitherActions.signalRunComplete(); // last boss of this practice
		}
	}

	public static ItemStack getEnergyCrystalItem() {
		return ClassKits.getSkyBlockItem(Material.NETHER_STAR, "<red>Energy Crystal", ENERGY_CRYSTAL_ID, "MAXOR_ENERGY_CRYSTAL");
	}

	public boolean notEnergyCrystal(Entity e) {
		// Only the top spawn crystals can be picked up.
		return !(e instanceof EnderCrystal) || (!e.equals(topLeftCrystal) && !e.equals(topRightCrystal));
	}

	/** A crystal plate or the block under it (breaking that pops the plate). Stonk/break immune in EVERY phase,
	*  pre-run prep included, like {@link instructions.bosses.goldor.Goldor#isProtected}. */
	public boolean isProtected(Block b) {
		return (b.getY() == PLATE_Y || b.getY() == PLATE_Y - 1) && b.getZ() == PLATE_Z
				&& (b.getX() == PLATE_LEFT_X || b.getX() == PLATE_RIGHT_X);
	}

	public void resetCrystals() {
		if(world == null) world = Bukkit.getWorlds().getFirst();

		if(topLeftCrystal != null) topLeftCrystal.remove();
		if(topRightCrystal != null) topRightCrystal.remove();
		if(plateLeftCrystal != null) {
			plateLeftCrystal.remove();
			plateLeftCrystal = null;
		}
		if(plateRightCrystal != null) {
			plateRightCrystal.remove();
			plateRightCrystal = null;
		}

		topLeftCrystal = spawnEnergyCrystal(new Location(world, 82.5, 238.48, 50.5));
		topRightCrystal = spawnEnergyCrystal(new Location(world, 64.5, 238.48, 50.5));
		autoPickUp(topLeftCrystal, LEFT_AUTO_PICKUP);
		autoPickUp(topRightCrystal, RIGHT_AUTO_PICKUP);
	}

	private static final BoundingBox LEFT_AUTO_PICKUP = new BoundingBox(85, 238, 49, 80, 240, 53);
	private static final BoundingBox RIGHT_AUTO_PICKUP = new BoundingBox(67, 238, 49, 62, 240, 53);

	private void autoPickUp(EnderCrystal crystal, BoundingBox box) {
		List<Player> inside = new ArrayList<>();
		for(Player p : world.getPlayers()) {
			Location l = p.getLocation();
			if(l.getX() >= box.getMinX() && l.getX() <= box.getMaxX()
					&& l.getY() >= box.getMinY() && l.getY() <= box.getMaxY()
					&& l.getZ() >= box.getMinZ() && l.getZ() <= box.getMaxZ()) inside.add(p);
		}
		inside.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
		for(Player p : inside) {
			pickUp(p, crystal);
			if(crystal.isDead()) return;
		}
	}

	/**
	 * Either click, both routed here by {@code MiscListener}. Spectators are refused HERE so every route gets the
	 * check: a spectator's pickup used to strand the crystal in their inventory and brick the mechanic. Vanilla fires
	 * the interact event for spectators too.
	 */
	public void pickUp(Player p, EnderCrystal crystal) {
		if(Utils.isSpectator(p)) return;
		if(notEnergyCrystal(crystal)) return;

		// Already holding one anywhere? Reject.
		if(carriesCrystal(p)) return;

		ItemStack prev = p.getInventory().getItem(8);
		previousSlot8.put(p.getUniqueId(), prev == null ? null : prev.clone());
		p.getInventory().setItem(8, getEnergyCrystalItem());

		crystal.remove();
		if(crystal.equals(topLeftCrystal)) topLeftCrystal = null;
		else if(crystal.equals(topRightCrystal)) topRightCrystal = null;

		Bukkit.broadcast(Utils.msg("<gold>" + p.getName() + "<green> picked up an <aqua>Energy Crystal<green>!"));
		Utils.timer(formatTick(displayTick()));
	}

	/**
	 * The whole plate mechanic, polled per tick. The only trigger is the real stone plate's {@code powered} flag at
	 * {@code (x, 224, 41)}; vanilla owns hitbox, sensitivity and the 20-tick release. Polled because
	 * {@code Action.PHYSICAL} fires only on the EDGE, so someone already on the plate before holding a crystal, or
	 * before {@link #AGGRO_TICK}, never got a second one.
	 */
	private void plateTick() {
		if(!platesActive) return;
		if(plateLeftCrystal == null) tryPlate(PLATE_LEFT_X);
		if(plateRightCrystal == null) tryPlate(PLATE_RIGHT_X);

		boolean carried = false;
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(carriesCrystal(p)) {
				carried = true;
				break;
			}
		}
		updatePlatePrompt(0, PLATE_LEFT_X, carried && plateLeftCrystal == null);
		updatePlatePrompt(1, PLATE_RIGHT_X, carried && plateRightCrystal == null);
	}

	private static boolean carriesCrystal(Player p) {
		for(ItemStack item : p.getInventory().getContents()) {
			if(item != null && ENERGY_CRYSTAL_ID.equals(items.ItemUtils.getID(item))) return true;
		}
		return false;
	}

	private void updatePlatePrompt(int idx, int plateX, boolean show) {
		if(!show) {
			removePlatePrompt(idx);
			return;
		}
		Interaction interaction = plateInteractions[idx];
		TextDisplay label = plateLabels[idx];
		if(interaction != null && interaction.isValid() && label != null && label.isValid()) return;
		removePlatePrompt(idx);

		plateInteractions[idx] = world.spawn(new Location(world, plateX + 0.5, PLATE_Y, PLATE_Z + 0.5), Interaction.class, i -> {
			i.setInteractionWidth(1.0f);
			i.setInteractionHeight(1.5f);
			i.setResponsive(true);
			i.setPersistent(false);
			i.addScoreboardTag("TASNoName");
		});
		plateLabels[idx] = world.spawn(new Location(world, plateX + 0.5, PLATE_Y + 1.5, PLATE_Z + 0.5), TextDisplay.class, d -> {
			d.text(Utils.msg("<red>Energy Crystal Missing\n<yellow><bold>CLICK HERE"));
			d.setBillboard(Display.Billboard.CENTER);
			d.setAlignment(TextDisplay.TextAlignment.CENTER);
			d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
			d.setShadowed(true);
			d.setPersistent(false);
			d.addScoreboardTag("TASNoName");
		});
	}

	private void removePlatePrompt(int idx) {
		if(plateInteractions[idx] != null) {
			plateInteractions[idx].remove();
			plateInteractions[idx] = null;
		}
		if(plateLabels[idx] != null) {
			plateLabels[idx].remove();
			plateLabels[idx] = null;
		}
	}

	private void removePlatePrompts() {
		removePlatePrompt(0);
		removePlatePrompt(1);
	}

	/** Either click on a plate prompt places like stepping on that plate. True if {@code clicked} is a prompt. */
	public boolean clickPlatePrompt(Player p, Entity clicked) {
		for(int idx = 0; idx < plateInteractions.length; idx++) {
			if(!clicked.equals(plateInteractions[idx])) continue;
			if(platesActive) placeAtPlate(p, idx == 0 ? PLATE_LEFT_X : PLATE_RIGHT_X);
			return true;
		}
		return false;
	}

	/** Places a crystal if that plate is pressed. */
	private void tryPlate(int plateX) {
		if(world == null) world = Bukkit.getWorlds().getFirst();
		if(!(world.getBlockAt(plateX, PLATE_Y, PLATE_Z).getBlockData() instanceof Powerable plate)) return;
		if(!plate.isPowered()) return;

		// The flag doesn't say who pressed it, so look up who is in the plate's block. Not a gate: no holder there
		// just places nothing this tick.
		BoundingBox column = new BoundingBox(plateX, PLATE_Y, PLATE_Z, plateX + 1, PLATE_Y + 1, PLATE_Z + 1);
		for(Entity e : world.getNearbyEntities(column)) {
			if(e instanceof Player p && placeAtPlate(p, plateX)) return;
		}
	}

	/** True if {@code p}'s crystal was placed. */
	private boolean placeAtPlate(Player p, int plateX) {
		if(Utils.isSpectator(p)) return false;
		ItemStack slot8 = p.getInventory().getItem(8);
		if(slot8 == null || !ENERGY_CRYSTAL_ID.equals(items.ItemUtils.getID(slot8))) return false;

		boolean left = plateX == PLATE_LEFT_X;
		if((left ? plateLeftCrystal : plateRightCrystal) != null) return false;
		EnderCrystal placedCrystal = spawnEnergyCrystal(new Location(world, plateX + 0.5, PLATE_Y + 0.48, PLATE_Z + 0.5));
		if(left) plateLeftCrystal = placedCrystal;
		else plateRightCrystal = placedCrystal;

		ItemStack restore = previousSlot8.remove(p.getUniqueId());
		p.getInventory().setItem(8, restore != null ? restore : items.util.SkyblockMenu.INSTANCE.build());

		boolean bothPlaced = plateLeftCrystal != null && plateRightCrystal != null;
		int placed = bothPlaced ? 2 : 1;
		String placedColor = bothPlaced ? "<green>" : "<red>";
		String activeGame = placedColor + placed + "<green>/2 Energy Crystals are now active!";
		Bukkit.broadcast(Utils.msg(activeGame));
		Utils.timer(formatTick(displayTick()));
		for(Player player : Bukkit.getOnlinePlayers()) {
			player.showTitle(Title.title(Utils.msg(""), Utils.msg(activeGame),
					Title.Times.times(Duration.ofMillis(0L), Duration.ofMillis(40 * 50L), Duration.ofMillis(0L))));
		}

		if(bothPlaced) {
			beginLaserCharge();
		}
		return true;
	}

	private void beginLaserCharge() {
		cancelLaserScan();
		BossScheduler.schedule(() -> {
			if(boss == null || boss.isDead()) return;
			if(plateLeftCrystal == null || plateRightCrystal == null) return;
			String chargeMsg = "<green>The Energy Laser is charging up!\n" + formatTick(displayTick());
			Utils.timer(chargeMsg);
			for(Player player : Bukkit.getOnlinePlayers()) {
				player.showTitle(Title.title(Utils.msg(""), Utils.msg(chargeMsg.split("\n")[0]),
						Title.Times.times(Duration.ofMillis(0L), Duration.ofMillis(40 * 50L), Duration.ofMillis(0L))));
			}
			Utils.runCommand("setblock 73 224 73 minecraft:red_stained_glass");
			startLaserScan();
		}, CHARGE_DELAY_TICKS);
	}

	private void startLaserScan() {
		cancelLaserScan();
		laserTicker = new Runnable() {
			@Override
			public void run() {
				if(boss == null || boss.isDead()) {
					BossScheduler.removeTicker(this);
					laserTicker = null;
					return;
				}
				if(displayTick() % LASER_CYCLE_TICKS != 0) return;
				// No cooldown: it may stun again next cycle, even mid-stun.

				double dx = boss.getLocation().getX() - LASER_CENTER_X;
				double dz = boss.getLocation().getZ() - LASER_CENTER_Z;
				if(dx * dx + dz * dz <= LASER_RADIUS_SQ) {
					triggerStun();
					BossScheduler.removeTicker(this);
					laserTicker = null;
				}
			}
		};
		BossScheduler.addTicker(laserTicker);
		// Armed from a boss ticker, so the copy-on-write list skips it this tick; check the arming tick now.
		laserTicker.run();
	}

	private void cancelLaserScan() {
		if(laserTicker != null) {
			BossScheduler.removeTicker(laserTicker);
			laserTicker = null;
		}
	}

	/**
	 * Per-tick HUD, same slot as Storm's. One segment, checked in this order since later states overlap earlier:
	 * <ul>
	 *   <li><b>Laser</b>: ticks to the next laser check, {@link #LASER_CYCLE_TICKS} → 1t on the scan's grid. Shown
	 *       from {@link #AGGRO_TICK}, not when the scan arms: the grid is absolute (phase tick mod cycle), so it's right
	 *       while crystals are still being carried.</li>
	 *   <li><b>Stunned</b>: ticks to auto-enrage. Replaced early by a 75% cap-enrage ({@link #enrageMaxor} re-renders).</li>
	 * </ul>
	 * The stun counter uses the tick stamped at {@link #triggerStun}, so it can't drift. No clash with
	 * {@code ClearManager}'s bar, which skips anyone outside the room grid.
	 */
	private void updateActionBar() {
		int t = displayTick();
		String bar;
		if(inStun) {
			bar = "<yellow>Stunned <white>" + Math.max(0, stunEndTick - t) + "t";
		} else if(t >= AGGRO_TICK) {
			bar = "<red>Laser <white>" + (LASER_CYCLE_TICKS - Math.floorMod(t, LASER_CYCLE_TICKS)) + "t";
		} else {
			// Opening dialogue: clear once rather than every tick.
			if(barShown) {
				barShown = false;
				Utils.broadcastActionBar(Component.empty());
			}
			return;
		}
		barShown = true;
		Utils.broadcastActionBar(Utils.msg(bar));
	}

	/** Whole phase, from {@link #onStart}: the laser scan is removed on the stun, exactly when two segments apply. */
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

	/** {@link #plateTick} for the whole phase: plates outlive the laser scan and crystals respawn mid-fight. */
	private void startPlateTicker() {
		cancelPlateTicker();
		plateTicker = new Runnable() {
			@Override
			public void run() {
				if(boss == null || boss.isDead()) {
					BossScheduler.removeTicker(this);
					plateTicker = null;
					removePlatePrompts();
					return;
				}
				plateTick();
			}
		};
		BossScheduler.addTicker(plateTicker);
	}

	private void cancelPlateTicker() {
		if(plateTicker != null) {
			BossScheduler.removeTicker(plateTicker);
			plateTicker = null;
		}
	}

	private void cancelBarTicker() {
		if(barTicker != null) {
			BossScheduler.removeTicker(barTicker);
			barTicker = null;
		}
		// Wipe instead of letting the last segment sit through its fade-out.
		barShown = false;
		Utils.broadcastActionBar(Component.empty());
	}

	private void triggerStun() {
		double maxHp = boss.getAttribute(Attribute.MAX_HEALTH).getValue();
		double laserDmg = maxHp * 0.05;
		double currentHp = boss.getHealth();

		// Laser would kill: leave DYING_SLIVER and run the death sequence.
		if(laserDmg >= currentHp) {
			clearAggro();
			setArmor(false);
			sendChatMessage(LASER_MESSAGE[random.nextInt(LASER_MESSAGE.length)]);
			boss.setHealth(DYING_SLIVER);
			Utils.playGlobalSound(Sound.ENTITY_WITHER_HURT);
			enterDyingState();
			return;
		}

		inStun = true;
		stunDamageDealt = 0;
		stunCapReached = false;
		stunEndTick = displayTick() + STUN_AUTO_ENRAGE_TICKS;

		clearAggro();
		setArmor(false);
		sendChatMessage(LASER_MESSAGE[random.nextInt(LASER_MESSAGE.length)]);
		Utils.timer("<green>Maxor stunned in " + formatTick(displayTick()));

		// Laser: 5% max HP, straight to health, and counts toward the 75% stun cap.
		boss.setHealth(Math.max(0.0, currentHp - laserDmg));
		stunDamageDealt += laserDmg;
		Utils.changeName(boss);
		Utils.playGlobalSound(Sound.ENTITY_WITHER_HURT);

		CustomBossBar.spawnAnimatedStunnedIndicator(boss, Integer.MAX_VALUE);

		// Boss-lane: crystals respawn at the START of the tick so a same-tick right-click sees them. A raw
		// scheduleTask would run after the run-start right-click, leaving nothing to pick up.
		BossScheduler.schedule(() -> {
			resetCrystals();
			Utils.runCommand("setblock 73 224 73 minecraft:black_stained_glass");
		}, CRYSTAL_RESPAWN_DELAY_TICKS);

		// Enrage at the start of T+160 regardless of damage, so a beam on that tick sees the re-armoured boss.
		cancelStunEnrageTask();
		stunEnrageTask = BossScheduler.schedule(this::enrageMaxor, STUN_AUTO_ENRAGE_TICKS);

		// Re-render: the HUD ticker already drew this tick's bar from the pre-stun state.
		updateActionBar();
	}

	private void enterDyingState() {
		dying = true;
		boss.addScoreboardTag("TASDying");
		cancelStunEnrageTask();
		cancelLaserScan();
		cancelBarTicker();
		inStun = false;
		CustomBossBar.removeStunIndicator();
		// Display shows "1" via TASDying. Deferred 1 tick so vanilla's post-event setHealth doesn't overwrite it.
		Utils.scheduleTask(() -> {
			if(boss != null && boss.isValid()) boss.setHealth(DYING_SLIVER);
		}, 1);
		Utils.changeName(boss);
		playDeathDialogue();
	}

	private void cancelStunEnrageTask() {
		if(stunEnrageTask != null) {
			BossScheduler.removeTicker(stunEnrageTask);
		}
		stunEnrageTask = null;
	}

	private void enrageMaxor() {
		if(!inStun) return;
		inStun = false;
		cancelStunEnrageTask();

		setArmor(true);
		Bukkit.broadcast(Utils.msg("<red>⚠ Maxor is Enraged ⚠"));
		Utils.timer(formatTick(displayTick()));
		for(Player player : Bukkit.getOnlinePlayers()) {
			player.showTitle(Title.title(Utils.msg(""), Utils.msg("<red>⚠ Maxor is Enraged ⚠"),
					Title.Times.times(Duration.ofMillis(0L), Duration.ofMillis(40 * 50L), Duration.ofMillis(0L))));
		}
		Utils.playGlobalSound(Sound.ENTITY_WITHER_AMBIENT, 2.0F, 0.5F);
		CustomBossBar.removeStunIndicator();
		setAggro(3.0, 1.0, 0.5);

		// A cap-enrage runs mid-tick from the damage path, after the HUD ticker; flip "Stunned" to "Laser" now.
		updateActionBar();
	}

	/** A killing hit leaves DYING_SLIVER and starts the death dialogue. During a stun, cumulative damage caps at 75% of max, then enrage. */
	@Override
	public double clampDamage(double incoming) {
		if(boss == null) return incoming;

		if(dying) return 0;

		// Cap already hit: reject everything, same-tick arrows included, so 75% can't be exceeded.
		if(stunCapReached) return 0;

		if(incoming <= 0) return 0;

		double currentHp = boss.getHealth();
		double maxHp = boss.getAttribute(Attribute.MAX_HEALTH).getValue();

		// Stun cap FIRST, or one huge hit during a stun skips the cap by clamping straight to the sliver.
		double cappedDmg = incoming;
		boolean willEnrage = false;
		if(inStun) {
			double damageCap = maxHp * 0.75;
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
			willEnrage = false; // dying overrides
		}

		if(willDie) {
			enterDyingState();
		} else {
			if(inStun) stunDamageDealt = Math.min(maxHp * 0.75, stunDamageDealt + cappedDmg);
			if(willEnrage) {
				// Latch BEFORE enraging so further same-tick hits are rejected.
				stunCapReached = true;
				enrageMaxor();
			}
		}
		return cappedDmg;
	}

	private void playDeathDialogue() {
		// Storm wall and handoff share this tick.
		int handoffTick = 60;
		sendChatMessage("I'M TOO YOUNG TO DIE AGAIN!");
		Utils.timer("<green>Maxor killed in " + formatTick(displayTick()));
		Server.playWitherDeathSound(boss);
		// Restored on the next /reset.
		Utils.scheduleTask(instructions.bosses.BossTransition::openMaxorToStorm, handoffTick);
		Utils.scheduleTask(() -> sendChatMessage("I'LL MAKE YOU REMEMBER MY DEATH!"), 40);
		Utils.scheduleTask(() -> {
			Utils.timer("<green>Maxor finished in " + formatTick(displayTick()));
			// Leaderboard duration at the phase's real end, not the killing blow. Before chainNext, which re-anchors the phase clock.
			instructions.bosses.WitherActions.recordPhaseDuration("Maxor", displayTick());
			if(tickerTask != null && !tickerTask.isCancelled()) tickerTask.cancel();
			chainNext(doContinue);
		}, handoffTick);
	}

	public boolean isDyingWither(Wither w) {
		return dying && w != null && w.equals(boss);
	}

	private EnderCrystal spawnEnergyCrystal(Location loc) {
		EnderCrystal c = (EnderCrystal) world.spawnEntity(loc, EntityType.END_CRYSTAL);
		c.customName(Utils.msg(ENERGY_CRYSTAL_NAME));
		c.setCustomNameVisible(true);
		return c;
	}

	/** Opening wave on the aggro tick: 10 around {@code 73.5 225 73.5} r=3, and 20 around {@code 73.5 221 40.5} r=10. */
	private void spawnMiners() {
		// Remove any old Wither Skeletons
		for(WitherSkeleton witherSkeleton : miners) {
			if(witherSkeleton != null && witherSkeleton.isValid()) {
				witherSkeleton.remove();
			}
		}
		miners.clear();

		spawnMinerGroup(10, 73.5, 225, 73.5, 3);
		spawnMinerGroup(20, 73.5, 221, 40.5, 10);
	}

	/** Uniform over a HORIZONTAL disc. Y is fixed: no AI, they never fall. */
	private void spawnMinerGroup(int count, double x, double y, double z, double radius) {
		for(int i = 0; i < count; i++) {
			// sqrt, or they bunch in the middle: a ring's area grows with r.
			double r = radius * Math.sqrt(random.nextDouble());
			double angle = random.nextDouble() * Math.PI * 2;
			Location spawnLoc = new Location(world, x + r * Math.cos(angle), y, z + r * Math.sin(angle));

			WitherSkeleton miner = (WitherSkeleton) world.spawnEntity(spawnLoc, EntityType.WITHER_SKELETON);

			// Real stats (MAP.md §5): 300M, Wither + Undead, so Hyperion's x1.5 on top of Smite and Undead Ruler.
			// No Skeletal Ruler: Skeletal is Normal-mode only.
			damage.MobStats.apply(miner, damage.MobStats.WITHER_MINER);
			miner.setAI(false);

			// Give stone pickaxe
			miner.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_PICKAXE));

			// MobStats identifies it by name; keep the leading text.
			miner.customName(Utils.msg("Wither Miner <yellow>" + Utils.formatHealthM(miner) + "<red>❤"));
			miner.setCustomNameVisible(true);

			miners.add(miner);
		}
	}
}
