package abilities;

import damage.DungeonClass;
import items.ItemUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import plugin.M7;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Archer regular drop ability: three-arrow spread; impacts go through the shared Superboom radius, so it opens
 * crypts and cracked walls like TNT.
 * <p>
 * Each arrow detonates on the first mob or block it touches, no pierce: the blast is the damage, and a piercing
 * arrow dropped its explosion blocks past the target. The "hit N enemies" line prints once when all three are
 * spent, since they share one {@code alreadyHurt} list.
 */
public final class ExplosiveShot implements ClassAbility {
	public static final ExplosiveShot INSTANCE = new ExplosiveShot();

	private ExplosiveShot() {}

	@Override
	public DungeonClass owner() {
		return DungeonClass.ARCHER;
	}

	@Override
	public boolean ultimate() {
		return false;
	}

	@Override
	public int cooldownTicks() {
		return 400; // 20s
	}

	/** Mob contact range that stops an arrow. Same as the guided carriers'. */
	private static final double HIT_RANGE = 1;

	/** Every mob within this of impact takes full damage. */
	private static final double BLAST_RADIUS = 4;

	@Override
	public boolean cast(Player p) {
		explosiveShot(p);
		return true;
	}

	public static void explosiveShot(Player p) {
		ServerLevel nmsWorld = ((CraftWorld) p.getWorld()).getHandle();
		ServerPlayer nmsPlayer = ((CraftPlayer) p).getHandle();

		Vector baseDirection = p.getEyeLocation().getDirection().normalize();
		Vector leftDirection = baseDirection.clone().rotateAroundY(Math.toRadians(-7.5));
		Vector rightDirection = baseDirection.clone().rotateAroundY(Math.toRadians(7.5));

		Location l = p.getEyeLocation().add(0, -0.1, 0);
		float speed = 1.5f;
		List<LivingEntity> alreadyHurt = new ArrayList<>();
		Set<Block> visitedBlocks = new HashSet<>();
		// Shared by all three arrows so the summary is the ability total. Arrays because three runnables write them.
		int[] damaged = {0};
		double[] dealt = {0};
		List<Vector> directions = List.of(leftDirection, baseDirection, rightDirection);
		// From the list, not a constant, so adding an arrow can't leave the summary waiting on one never fired.
		int[] pending = {directions.size()};
		for(Vector dir : directions) {
			net.minecraft.world.entity.projectile.arrow.Arrow nmsArrow = new net.minecraft.world.entity.projectile.arrow.Arrow(nmsWorld, 0, 0, 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
			nmsArrow.setPos(l.getX(), l.getY(), l.getZ());
			nmsArrow.shoot(dir.getX(), dir.getY(), dir.getZ(), speed, 0);
			nmsArrow.setOwner(nmsPlayer);
			nmsWorld.addFreshEntity(nmsArrow);

			Arrow arrow = (Arrow) nmsArrow.getBukkitEntity();
			arrow.setDamage(0);
			// No pierce, see class doc.
			arrow.setPierceLevel(0);
			arrow.setShooter(p);
			arrow.setWeapon(p.getInventory().getItemInMainHand());
			arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);

			new BukkitRunnable() {
				@Override
				public void run() {
					// Spent on touching a mob (same lookup as guided carriers), the ground or a solid block. Mob
					// check is ours, not vanilla's: arrow-hit listeners cancel ProjectileHitEvent for a wither,
					// which would leave it flying on.
					LivingEntity struck = ItemUtils.firstMobNear(arrow.getLocation(), HIT_RANGE);
					if(struck == null && arrow.isValid() && !arrow.isDead() && !arrow.isOnGround()
							&& !arrow.getLocation().getBlock().getType().isSolid()) {
						return;
					}
					Location impact = struck != null
							? struck.getLocation().add(0, struck.getHeight() / 2.0, 0)
							: arrow.getLocation();

					// Each arrow: 100% of highest arrow damage in the last minute (MAP.md §1.14). DERIVED: already
					// a finished hit, so no second pass through the formula and it stays out of the history.
					double sbDamage = damage.CombatState.maxInLastTicks(p, 1200);
					for(Entity e : impact.getWorld().getNearbyEntities(impact, BLAST_RADIUS, BLAST_RADIUS, BLAST_RADIUS)) {
						if(e instanceof LivingEntity target && !alreadyHurt.contains(target) && !(e instanceof Player) && !(target.hasPotionEffect(PotionEffectType.RESISTANCE) && target.getPotionEffect(PotionEffectType.RESISTANCE).getAmplifier() == 255) && !(e instanceof Wither wither && wither.getInvulnerableTicks() != 0)) {
							double reported = damage.Damage.dealDerived(target, sbDamage, damage.DamageKind.NORMAL, p,
									damage.DamagePath.BOW);
							alreadyHurt.add(target);
							if(reported > 0) {
								dealt[0] += reported;
								damaged[0]++;
							}
						}
					}

					p.getWorld().spawnParticle(Particle.EXPLOSION, impact, 10, 0.5, 0.5, 0.5, 0);
					p.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1, 1f);

					ItemUtils.triggerSuperboomRadius(impact, p, visitedBlocks);

					arrow.remove();
					cancel();
					// Last arrow to land prints the summary.
					if(--pending[0] == 0) damage.Damage.reportAoe(p, "Explosive Shot", damaged[0], dealt[0]);
				}
			}.runTaskTimer(M7.getInstance(), 1L, 1L);
		}
	}}
