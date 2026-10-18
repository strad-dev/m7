package items.combat;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import damage.Rarity;
import damage.ReforgeId;
import items.*;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.profile.CraftPlayerProfile;
import org.bukkit.entity.*;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import plugin.M7;
import plugin.MovementAudit;
import plugin.Utils;

import java.util.UUID;

/**
 * Gravity-less snowball with a Jerry head {@code ItemDisplay} riding along; on impact it knocks the shooter back,
 * scaled by {@code cos(firing pitch)}. That's the movement tech. Takes the SWORD reforge table.
 */
public final class JerrychineGun implements Weapon, AbilityItem, ProjectileItem {
	public static final JerrychineGun INSTANCE = new JerrychineGun();

	private JerrychineGun() {}

	@Override
	public String loreId() {
		return "skyblock/combat/jerrychine";
	}

	@Override
	public Material material() {
		return Material.GOLDEN_HORSE_ARMOR;
	}

	@Override
	public String baseName() {
		return "Jerry-chine Gun";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.EPIC;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.HEROIC;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "JERRY_STAFF");
	}

	@Override
	public boolean hasRightClick() {
		return true;
	}

	@Override
	public boolean onRightClick(Cast cast) {
		jerrychine(cast.player());
		return true;
	}

	@Override
	public String projectileTag() {
		return "jerrychine";
	}

	@Override
	public void onProjectileHit(ProjectileHitEvent e, Player shooter) {
		handleHit(e);
	}

	private static final double JERRY_SPEED = 20.0 / 30.0; // 20 blocks in 30 ticks
	private static final double JERRY_BOOST_V = 0.6;
	private static final double JERRY_BOOST_H = 0.446;
	private static final double JERRY_BOOST_RADIUS = 3.5;
	private static final float JERRY_HEAD_SCALE = 1.0f;
	// MHF_Villager head texture (Hypixel's generic Jerry villager skin)
	private static final String JERRY_HEAD_TEXTURE = "eyJ0aW1lc3RhbXAiOjE1MTIyMTE4MjQ0MzAsInByb2ZpbGVJZCI6ImJkNDgyNzM5NzY3YzQ1ZGNhMWY4YzMzYzQwNTMwOTUyIiwicHJvZmlsZU5hbWUiOiJNSEZfVmlsbGFnZXIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzgyMmQ4ZTc1MWM4ZjJmZDRjODk0MmM0NGJkYjJmNWNhNGQ4YWU4ZTU3NWVkM2ViMzRjMThhODZlOTNiIn19fQ==";

	private static ItemStack jerryHeadItem() {
		ItemStack head = new ItemStack(Material.PLAYER_HEAD);
		Multimap<String, Property> props = HashMultimap.create();
		props.put("textures", new Property("textures", JERRY_HEAD_TEXTURE));
		PropertyMap propertyMap = new PropertyMap(props);
		GameProfile gp = new GameProfile(UUID.randomUUID(), "jerryhead", propertyMap);
		CraftPlayerProfile profile = new CraftPlayerProfile(gp);
		SkullMeta meta = (SkullMeta) head.getItemMeta();
		assert meta != null;
		meta.setPlayerProfile(profile);
		head.setItemMeta(meta);
		return head;
	}

	public static void jerrychine(Player p) {
		Location l = p.getEyeLocation();
		Snowball s = (Snowball) l.getWorld().spawnEntity(l, EntityType.SNOWBALL);
		s.addScoreboardTag("jerrychine");
		s.setShooter(p);
		s.setGravity(false);
		s.setVisibleByDefault(false);
		s.setVelocity(l.getDirection().multiply(JERRY_SPEED));

		ItemDisplay head = (ItemDisplay) l.getWorld().spawnEntity(l, EntityType.ITEM_DISPLAY);
		head.setItemStack(jerryHeadItem());
		head.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
		Transformation t = head.getTransformation();
		head.setTransformation(new Transformation(
				new Vector3f(0f, 0f, 0f),
				t.getLeftRotation(),
				new Vector3f(JERRY_HEAD_SCALE, JERRY_HEAD_SCALE, JERRY_HEAD_SCALE),
				t.getRightRotation()));
		head.setBillboard(Display.Billboard.FIXED);
		head.setInterpolationDuration(1);
		head.setTeleportDuration(1);
		head.addScoreboardTag("jerrychineHead");

		new BukkitRunnable() {
			@Override
			public void run() {
				if(!s.isValid() || !head.isValid()) {
					head.remove();
					cancel();
					return;
				}
				Location to = s.getLocation();
				Vector v = s.getVelocity();
				double speed = v.length();
				if(speed > 1e-6) {
					to.setYaw((float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())));
					to.setPitch((float) Math.toDegrees(-Math.asin(v.getY() / speed)));
				}
				head.teleport(to);
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);

		l.getWorld().playSound(l, Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
		Utils.debug(Utils.DebugType.SERVER, p.getName() + " fired Jerry-chine #" + s.getEntityId() + " from " + Utils.round(l.getX(), 2) + " " + Utils.round(l.getY(), 2) + " " + Utils.round(l.getZ(), 2));
	}

	private static void handleHit(ProjectileHitEvent e) {
		if(!(e.getEntity() instanceof Snowball s)) return;
		if(!s.getScoreboardTags().contains("jerrychine")) return;
		e.setCancelled(true);
		s.remove();

		if(!(s.getShooter() instanceof Player p)) return;

		double distance = p.getLocation().distanceSquared(s.getLocation());
		if(distance > JERRY_BOOST_RADIUS * JERRY_BOOST_RADIUS) return;
		if(!(p instanceof CraftPlayer craftPlayer)) return;
		ServerPlayer serverPlayer = craftPlayer.getHandle();

		Vector direction = p.getLocation().toVector().subtract(s.getLocation().toVector()).normalize();
		direction.setY(0);
		direction.normalize();

		// No gravity, so velocity is the fire-time look direction: cos(pitch) = horizontal speed / total speed.
		Vector vel = s.getVelocity();
		double speed = vel.length();
		double cosPitch = speed > 1e-6 ? Math.hypot(vel.getX(), vel.getZ()) / speed : 0;
		double horizMag = JERRY_BOOST_H * cosPitch;

		direction.multiply(horizMag);
		direction.setY(JERRY_BOOST_V);

		if(!Double.isFinite(direction.getX())) direction.setX(0);
		if(!Double.isFinite(direction.getZ())) direction.setZ(0);

		serverPlayer.setOnGround(false);
		p.setVelocity(direction);
		MovementAudit.startAirborneAudit(p, "jerrychine"); // SUPER-verbose per-tick trace of the knockback arc
		double horizSpeed = Math.hypot(direction.getX(), direction.getZ());
		Utils.debug(Utils.DebugType.SERVER, "Jerry-chine moved " + p.getName() + " " + Utils.round(direction.getX(), 3) + " " + Utils.round(direction.getY(), 5) + " " + Utils.round(direction.getZ(), 3));
	}
}
