package items.bows;

import damage.Rarity;
import damage.ReforgeId;
import items.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import plugin.Cooldowns;
import plugin.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Shortbow</b>: never drawn, every shot a full-damage crit (§1.2).
 * <p>
 * A click fires at once if the cooldown is up (5, or 4 with full Thermodynamic); one on cooldown is armed and
 * {@link #pollAll()} fires it the first tick it's up. Either way from the eye at the click. One volley per cooldown
 * however hard it's spammed, and a left + right click on one tick make one shot.
 * <p>
 * Salvation has its own 5-tick clock, held here and not via {@link #cooldownTicks()}, so a RIGHT-click can't spend
 * it. Neither takes the Mage reduction: weapons, not abilities.
 */
public final class Terminator implements Bow, AbilityItem {
	public static final Terminator INSTANCE = new Terminator();

	private Terminator() {}

	@Override
	public String loreId() {
		return "skyblock/combat/terminator";
	}

	@Override
	public Material material() {
		return Material.BOW;
	}

	@Override
	public String baseName() {
		return "Terminator";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.LEGENDARY;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.PRECISE;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "TERMINATOR");
	}

	@Override
	public boolean shortbow() {
		return true;
	}

	@Override
	public List<String> arrowTags() {
		return List.of("TerminatorArrow");
	}

	@Override
	public Arrow spawnBonusArrow(Player p, Location aimFrom, ItemStack bow, float speed) {
		Arrow arrow = p.getWorld().spawnArrow(aimFrom, aimFrom.getDirection(), speed, 0);
		arrow.setPierceLevel(4);
		arrow.setShooter(p);
		arrow.setWeapon(bow);
		arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
		return arrow;
	}

	@Override
	public void bonusArrowSound(Player p, boolean archerBonus) {
		Utils.playLocalSound(p, Sound.ENTITY_ARROW_SHOOT, 1.0F, 1.0F);
	}

	@Override
	public boolean removesArrowInSolid() {
		return true;
	}

	@Override
	public boolean suppressesBlockBreak() {
		return true;
	}

	@Override
	public boolean mageReduced() {
		return false;
	}

	@Override
	public boolean hasRightClick() {
		return true;
	}

	@Override
	public boolean hasLeftClick() {
		return true;
	}

	/** Arms a volley: fires now if off cooldown, else {@link #pollAll()} fires it when ready. */
	@Override
	public boolean onRightClick(Cast cast) {
		arm(cast.player(), cast.tick());
		return true;
	}

	/** Arms the volley too, plus Salvation if ready. The tick is recorded either way, so a beam on cooldown still shoots. */
	@Override
	public boolean onLeftClick(Cast cast) {
		Player p = cast.player();
		arm(p, cast.tick());
		if(!Cooldowns.ready(p, SALVATION_KEY)) return false;
		Cooldowns.start(p, SALVATION_KEY, SALVATION_COOLDOWN_TICKS);
		salvation(p);
		return true;
	}

	/** Kept off {@link #cooldownKey()} so a right-click can't spend it. */
	private static final String SALVATION_KEY = "skyblock/combat/terminator/salvation";

	// The shared left-click guard only caps to 1/tick; this is Salvation's own cooldown. No Mage reduction.
	private static final int SALVATION_COOLDOWN_TICKS = 5;

	/** Tick a volley was last armed, and last fired, per player. */
	private static final Map<UUID, Integer> lastPacketTick = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> lastFireTick = new ConcurrentHashMap<>();
	/**
	 * Eye at the last click. The volley flies where the player looked when they clicked, like Hypixel: firing from
	 * the poll's live eye aimed a click wherever the head had turned since (the TAS turns for Rapid Fire on the same tick).
	 */
	private static final Map<UUID, Location> armedAim = new ConcurrentHashMap<>();

	private static void arm(Player p, int tick) {
		lastPacketTick.put(p.getUniqueId(), tick);
		armedAim.put(p.getUniqueId(), p.getEyeLocation());
		tryFire(p, MinecraftServer.currentTick);
	}

	/**
	 * Per tick: anyone with a click since their last shot fires at the first tick at or after
	 * {@code lastFire + cooldown}. Anchored to the first shot. Started from {@link plugin.M7}.
	 */
	public static void pollAll() {
		int now = MinecraftServer.currentTick;
		for(UUID id : lastPacketTick.keySet()) {
			Player p = Bukkit.getPlayer(id);
			if(p != null && p.isOnline()) tryFire(p, now);
		}
	}

	/** Fires the armed volley if there's a click since the last shot, the Terminator is held and the cooldown is up. */
	private static void tryFire(Player p, int now) {
		UUID id = p.getUniqueId();
		int lastPacket = lastPacketTick.getOrDefault(id, Integer.MIN_VALUE / 2);
		int lastFire = lastFireTick.getOrDefault(id, Integer.MIN_VALUE / 2);
		if(lastPacket <= lastFire) return;                 // no new right-click since the last shot
		if(!"skyblock/combat/terminator".equals(ItemUtils.getID(p.getInventory().getItemInMainHand()))) return; // must hold it
		int cooldown = ItemUtils.isThermoSet(p) ? 4 : 5;
		if(now < lastFire + cooldown) return;
		Location aim = armedAim.get(id);
		fire(p, aim != null && aim.getWorld() == p.getWorld() ? aim.clone() : p.getEyeLocation());
		lastFireTick.put(id, now);
	}

	/** Part of the run reset. */
	public static void reset() {
		lastPacketTick.clear();
		lastFireTick.clear();
		armedAim.clear();
	}

	private static void fire(Player p, Location eye) {
		// you don't need arrows
		p.getInventory().remove(Material.ARROW);
		p.getInventory().remove(Material.TIPPED_ARROW);
		p.getInventory().remove(Material.SPECTRAL_ARROW);

		ServerLevel nmsWorld = ((CraftWorld) p.getWorld()).getHandle();
		ServerPlayer nmsPlayer = ((CraftPlayer) p).getHandle();

		// Creative skips the arrow check, so BowItem.use() starts a draw even with the interact cancelled.
		// Stop it next tick, after vanilla has run.
		Utils.scheduleTask(() -> {
			if(nmsPlayer.isUsingItem()) nmsPlayer.stopUsingItem();
		}, 1);

		Vector baseDirection = eye.getDirection().normalize();
		Vector leftDirection = baseDirection.clone().rotateAroundY(Math.toRadians(-5));
		Vector rightDirection = baseDirection.clone().rotateAroundY(Math.toRadians(5));

		// vanilla: eyeY - 0.1
		Location l = eye.add(0, -0.1, 0);

		net.minecraft.world.entity.projectile.arrow.Arrow nmsLeft = new net.minecraft.world.entity.projectile.arrow.Arrow(nmsWorld, 0, 0, 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
		net.minecraft.world.entity.projectile.arrow.Arrow nmsMiddle = new net.minecraft.world.entity.projectile.arrow.Arrow(nmsWorld, 0, 0, 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
		net.minecraft.world.entity.projectile.arrow.Arrow nmsRight = new net.minecraft.world.entity.projectile.arrow.Arrow(nmsWorld, 0, 0, 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);

		nmsLeft.setPos(l.getX(), l.getY(), l.getZ());
		nmsMiddle.setPos(l.getX(), l.getY(), l.getZ());
		nmsRight.setPos(l.getX(), l.getY(), l.getZ());

		// shoot() sets velocity and rotation
		float speed = 3.175f;
		nmsLeft.shoot(leftDirection.getX(), leftDirection.getY(), leftDirection.getZ(), speed, 0);
		nmsMiddle.shoot(baseDirection.getX(), baseDirection.getY(), baseDirection.getZ(), speed, 0);
		nmsRight.shoot(rightDirection.getX(), rightDirection.getY(), rightDirection.getZ(), speed, 0);

		nmsLeft.setOwner(nmsPlayer);
		nmsMiddle.setOwner(nmsPlayer);
		nmsRight.setOwner(nmsPlayer);

		nmsWorld.addFreshEntity(nmsLeft);
		nmsWorld.addFreshEntity(nmsMiddle);
		nmsWorld.addFreshEntity(nmsRight);

		Arrow left = (Arrow) nmsLeft.getBukkitEntity();
		Arrow middle = (Arrow) nmsMiddle.getBukkitEntity();
		Arrow right = (Arrow) nmsRight.getBukkitEntity();

		// Shortbow: each of the three arrows is one shot's damage, no draw scaling (MAP.md §1.2). Stamped at fire
		// time (§1.0.5), so a mid-flight swap can't change it.
		//
		// Old hand-tuned terms are gone: Power/Strength-potion bonuses are the weapon's stats via the formula, the
		// 97.5% Thermodynamic penalty is deleted (the set only raises the attack-speed cap to 150, the 4-tick
		// cooldown), and the Spring Boots / Racing Helmet reductions are deleted.
		ItemStack bow = p.getInventory().getItemInMainHand();

		for(Arrow arrow : Arrays.asList(left, middle, right)) {
			arrow.setPierceLevel(4);
			arrow.setShooter(p);
			arrow.setWeapon(bow);
			arrow.addScoreboardTag("TerminatorArrow");
			arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
			damage.Arrows.stamp(arrow, p, bow, 1.0, 1.0);
		}

		for(Arrow arrow : Arrays.asList(left, middle, right)) {
			Utils.scheduleTask(() -> {
				if(arrow.isValid() && arrow.getLocation().getBlock().getType().isSolid()) arrow.remove();
			}, 1);
		}

		Utils.playLocalSound(p, Sound.ENTITY_ARROW_SHOOT, 1.0F, 1.0F);

		// Duplex + Archer arrows: Bow's shared implementation.
		INSTANCE.fireBonusArrows(p, l, bow, speed, 1.0);
	}

	/**
	 * Left-click beam. NOT a bow shot (MAP.md §1.4): always full charge, so always crits. The old flat 20 is gone;
	 * it's the Terminator's stats through the bow formula.
	 */
	public static void salvation(Player p) {
		ItemStack weapon = p.getInventory().getItemInMainHand();
		Location l = p.getLocation();
		l.add(0, 1.62, 0);

		Vector v = l.getDirection();
		v.setX(v.getX() / 3);
		v.setY(v.getY() / 3);
		v.setZ(v.getZ() / 3);
		World world = l.getWorld();
		Set<Entity> damagedEntities = new HashSet<>();
		List<EntityType> doNotKill = ItemUtils.doNotKill();
		damagedEntities.add(p);
		int pierce = 5;
		for(int i = 0; i < 192 && pierce > 0; i++) {
			if(l.getBlock().getType().isSolid()) {
				break;
			}
			assert world != null;
			ArrayList<Entity> entities = (ArrayList<Entity>) world.getNearbyEntities(l, 1, 1, 1);
			for(Entity entity : entities) {
				if(!damagedEntities.contains(entity) && !doNotKill.contains(entity.getType()) && entity instanceof LivingEntity entity1 && !(entity instanceof Player) && entity1.getHealth() > 0 && !(entity instanceof Wither wither && wither.getInvulnerableTicks() != 0)) {
					damagedEntities.add(entity);
					double sbDamage = damage.Damage.bow(p, entity1, weapon, 1.0,
							l.distance(p.getLocation()), false);
					damage.Damage.deal(entity1, sbDamage, damage.DamageKind.NORMAL, p, damage.DamagePath.BOW);
					pierce--;
				}
			}
			Particle.DustOptions particle = new Particle.DustOptions(Color.RED, 1.0F);
			world.spawnParticle(Particle.DUST, l, 1, particle);
			l.add(v);
		}
		Utils.playLocalSound(p, Sound.ENTITY_GUARDIAN_DEATH, 0.5f, 2.0F);
	}
}
