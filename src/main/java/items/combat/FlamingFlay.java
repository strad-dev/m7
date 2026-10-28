package items.combat;

import damage.Rarity;
import damage.ReforgeId;
import items.*;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import plugin.M7;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Lobs a flame arc, angled up from the look direction. */
public final class FlamingFlay implements Weapon, AbilityItem {
	public static final FlamingFlay INSTANCE = new FlamingFlay();

	private FlamingFlay() {}

	@Override
	public String loreId() {
		return "skyblock/combat/flaming_flay";
	}

	@Override
	public Material material() {
		return Material.FISHING_ROD;
	}

	@Override
	public String baseName() {
		return "Flaming Flay";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.LEGENDARY;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.FABLED;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "FLAMING_FLAY");
	}

	@Override
	public boolean hasRightClick() {
		return true;
	}

	@Override
	public boolean onRightClick(Cast cast) {
		flamingFlay(cast.player());
		return true;
	}

	public static void flamingFlay(Player p) {
		Location startLoc = p.getEyeLocation();

		// Angle up by 5 degrees from player's look direction
		Vector direction = startLoc.getDirection().normalize();
		float pitch = Math.max(startLoc.getPitch() - 15, -90); // Subtract 5 degrees (negative pitch is up)
		float yaw = startLoc.getYaw();

		// Recalculate direction with adjusted pitch
		double xz = Math.cos(Math.toRadians(pitch));
		direction.setX(-xz * Math.sin(Math.toRadians(yaw)));
		direction.setY(-Math.sin(Math.toRadians(pitch)));
		direction.setZ(xz * Math.cos(Math.toRadians(yaw)));

		Vector velocity = direction.normalize().multiply(1.5); // Initial velocity

		new BukkitRunnable() {
			final Location currentLoc = startLoc.clone();
			final Vector currentVelocity = velocity.clone();
			int colorIndex = 0;
			double totalDistance = 0;
			final Set<Entity> hitEntities = new HashSet<>(); // Track hit entities to avoid duplicate damage

			@Override
			public void run() {
				// Apply reduced gravity to velocity
				currentVelocity.add(new Vector(0, -0.08, 0)); // Reduced gravity

				// Move the particle location
				Location previousLoc = currentLoc.clone();
				currentLoc.add(currentVelocity);

				// Calculate distance traveled this tick
				double distanceThisTick = previousLoc.distance(currentLoc);
				int particleCount = (int) (distanceThisTick * 5); // 5 particles per block

				// Spawn particles along the path
				for(int i = 0; i < particleCount; i++) {
					double t = (double) i / particleCount;
					Location particleLoc = previousLoc.clone().add(currentVelocity.clone().multiply(t));

					// Cycle through colors: Red, Yellow, Green
					Particle.DustOptions dust = switch(colorIndex % 3) {
						case 0 -> new Particle.DustOptions(Color.RED, 1.5f);
						case 1 -> new Particle.DustOptions(Color.YELLOW, 1.5f);
						default -> new Particle.DustOptions(Color.LIME, 1.5f); // Green
					};

					p.getWorld().spawnParticle(Particle.DUST, particleLoc, 1, 0, 0, 0, 0, dust);
					colorIndex++;
				}

				// Check for mob hits
				List<EntityType> doNotKill = ItemUtils.doNotKill();
				for(Entity entity : Objects.requireNonNull(currentLoc.getWorld()).getNearbyEntities(currentLoc, 0.5, 0.5, 0.5)) {
					if(hitEntities.contains(entity) || doNotKill.contains(entity.getType())) continue;
					if(!(entity instanceof LivingEntity entity1) || entity instanceof Player || entity1.getHealth() <= 0) continue;
					// One of three things that aggro a boss through a FULL shield (with the mage beam and thrown axe).
					// Noted here because damage/Damage only sees a zero hit.
					if(entity instanceof Wither w && w.getScoreboardTags().contains("TASWither")) {
						instructions.bosses.WitherActions.noteDamager(p, w, 0);
					}
					if(entity instanceof Wither armoured && armoured.getInvulnerableTicks() != 0) {
						// Stacks still build through the shield, like the beam; no damage.
						damage.Damage.applyOnHitDebuffs(p, armoured, damage.DamagePath.MELEE, p.getInventory().getItemInMainHand());
						damage.Procs.buildVenomous(p, armoured);
						hitEntities.add(entity);
						continue;
					}
					// Deals the SAME as its melee hit (MAP.md §1.8), so melee formula, not ability.
					double sbDamage = damage.Damage.melee(p, entity1, p.getInventory().getItemInMainHand());
					damage.Damage.deal(entity1, sbDamage, damage.DamageKind.NORMAL, p, damage.DamagePath.MELEE);
					hitEntities.add(entity);
				}

				totalDistance += distanceThisTick;

				// Stop conditions
				if(currentLoc.getBlock().getType().isSolid() || // Hit a block
						currentLoc.getY() < -64 || // Fell into void
						totalDistance > 12) { // Max distance reduced to 15 blocks
					cancel();
				}
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}
}
