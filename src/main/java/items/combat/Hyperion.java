package items.combat;

import damage.Rarity;
import damage.ReforgeId;
import items.*;
import listeners.LavaJump;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import plugin.Utils;

import java.util.List;

/**
 * One item, two reforges: Heroic and Withered (Fabled) are two {@link ReforgeId}s, with a {@code damage/Items} term
 * list per name (MAP.md §2.4). Wither Impact's Intelligence scaling (0.3 off 10,000) is the highest of any (§7).
 */
public final class Hyperion implements Weapon, AbilityItem {
	public static final Hyperion INSTANCE = new Hyperion();

	private Hyperion() {}

	@Override
	public String loreId() {
		return "skyblock/combat/scylla";
	}

	@Override
	public Material material() {
		return Material.IRON_SWORD;
	}

	@Override
	public String baseName() {
		return "Hyperion";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.LEGENDARY;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.HEROIC;
	}

	@Override
	public List<ReforgeId> reforges() {
		return List.of(ReforgeId.HEROIC, ReforgeId.FABLED);
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "HYPERION");
	}

	@Override
	public boolean mageBeams() {
		return true;
	}

	@Override
	public boolean hasRightClick() {
		return true;
	}

	@Override
	public boolean usableWhileWatching() {
		return true;
	}

	@Override
	public boolean onRightClick(Cast cast) {
		witherImpact(cast.player());
		return true;
	}

	public static void witherImpact(Player p) {
		impactTeleport(p);
		if(!Utils.isSpectator(p)) implode(p);
	}

	/** Implosion radius, eye to the nearest point of a mob's hitbox. */
	private static final double IMPLOSION_RADIUS = 10;

	// After the teleport: it hits around where you land, not where you cast.
	private static void implode(Player p) {
		Location eye = p.getEyeLocation();
		p.getWorld().spawnParticle(Particle.EXPLOSION, eye, 1);
		List<Entity> entities = p.getWorld().getNearbyEntities(eye, IMPLOSION_RADIUS, IMPLOSION_RADIUS, IMPLOSION_RADIUS,
				entity -> withinSphere(eye, entity, IMPLOSION_RADIUS)).stream().toList();
		List<EntityType> doNotKill = ItemUtils.doNotKill();
		int damaged = 0;
		double dealt = 0;
		ItemStack wand = p.getInventory().getItemInMainHand();
		for(Entity entity : entities) {
			// Never players, like the other AoEs. The old fake-player-only exclusion let implosion hit practicers.
			if(!doNotKill.contains(entity.getType()) && entity instanceof LivingEntity entity1 && !(entity instanceof Player) && entity1.getHealth() > 0 && !(entity instanceof Wither wither && wither.getInvulnerableTicks() != 0)) {
				// 10,000 base, 0.3 Int scaling (MAP.md §7). Ability formula: no Strength, no Crit Damage, on
				// purpose, abilities are an option, not a damage strategy.
				double sbDamage = damage.Damage.ability(p, entity1, wand);
				// Sum what deal REPORTS, so the message matches the damage numbers after defense. A target that took
				// nothing (Wither King, NPC) isn't counted.
				double hit = damage.Damage.deal(entity1, sbDamage, damage.DamageKind.MAGIC, p, damage.DamagePath.ABILITY);
				if(hit > 0) {
					dealt += hit;
					damaged += 1;
				}
			}
		}
		damage.Damage.reportAoe(p, "Implosion", damaged, dealt);
		Utils.playLocalSound(p, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);

		// wither shield sound, on a 100-tick cooldown per player.
		ItemUtils.playWitherShieldSound(p);
	}

	private static boolean withinSphere(Location eye, Entity entity, double radius) {
		BoundingBox b = entity.getBoundingBox();
		double dx = Math.max(Math.max(b.getMinX() - eye.getX(), 0), eye.getX() - b.getMaxX());
		double dy = Math.max(Math.max(b.getMinY() - eye.getY(), 0), eye.getY() - b.getMaxY());
		double dz = Math.max(Math.max(b.getMinZ() - eye.getZ(), 0), eye.getZ() - b.getMaxZ());
		return dx * dx + dy * dy + dz * dz <= radius * radius;
	}

	private static void impactTeleport(Player p) {
		// Inside the F7 Goldor/Necron arena, Wither Impact implodes but does not teleport.
		if(LavaJump.isInBossArena(p.getLocation())) {
			return;
		}

		Location origin = p.getLocation().clone();
		RayTraceResult result = p.rayTraceBlocks(11.65);
		if(result == null) {
			Location l = p.getLocation().add(p.getLocation().getDirection().multiply(10));
			l.setX(Math.floor(l.getX()) + 0.5);
			l.setY(Math.floor(l.getY()));
			l.setZ(Math.floor(l.getZ()) + 0.5);

			// Check if the target location is safe
			Block feetBlock = l.getBlock();
			Block headBlock = feetBlock.getRelative(BlockFace.UP);

			// If either block is solid, we need to adjust
			if(!feetBlock.isPassable() || !headBlock.isPassable()) {
				// Try to move up until we find a safe spot or reach original height
				double originalY = p.getLocation().getY();
				Location checkLoc = l.clone();
				boolean foundSafe = false;

				// Check up to 10 blocks up or until at original height
				for(int i = 0; i < 10; i++) {
					checkLoc.add(0, 1, 0);
					Block checkFeet = checkLoc.getBlock();
					Block checkHead = checkFeet.getRelative(BlockFace.UP);

					// Check if this position is safe (2 blocks of air)
					if(checkFeet.isPassable() && checkHead.isPassable()) {
						// Also check we're not in a 1-block gap if above original height
						if(checkLoc.getY() >= originalY) {
							Block aboveHead = checkHead.getRelative(BlockFace.UP);
							if(!aboveHead.isPassable()) {
								// This is a 1-block gap at or above original height, so skip it
								continue;
							}
						}

						l = checkLoc.clone();
						foundSafe = true;
						break;
					}

					// Stop if we've reached or passed original height and no safe spot
					if(checkLoc.getY() >= originalY) {
						break;
					}
				}

				// If no safe spot found, don't teleport
				if(!foundSafe) {
					p.sendMessage(Utils.msg("<red>No safe teleport location found!"));
					return;
				}
			}

			// Additional check for 1-block tall spaces when below original height
			if(l.getY() < p.getLocation().getY()) {
				Block aboveHead = l.getBlock().getRelative(BlockFace.UP, 2);
				if(!aboveHead.isPassable()) {
					// This would put player in crawl mode below their starting position
					// Try to find a better spot
					for(int i = 1; i <= 3; i++) {
						Location upLoc = l.clone().add(0, i, 0);
						Block upFeet = upLoc.getBlock();
						Block upHead = upFeet.getRelative(BlockFace.UP);
						Block upAbove = upHead.getRelative(BlockFace.UP);

						if(upFeet.isPassable() && upHead.isPassable() && upAbove.isPassable()) {
							l = upLoc;
							break;
						}
					}
				}
			}

			ItemUtils.noRotateTeleport(p, l);
			Utils.debug(Utils.DebugType.SERVER, "Teleporting " + p.getName() + " to " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
		} else {
			switch(result.getHitBlockFace()) {
				case SELF -> {
					// empty case
				}
				case UP -> {
					Location l = result.getHitBlock().getLocation().add(0.5, 1, 0.5);
					ItemUtils.noRotateTeleport(p, l);
					Utils.debug(Utils.DebugType.SERVER, "Teleporting " + p.getName() + " to " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
				}
				case DOWN -> {
					Location l = result.getHitBlock().getLocation().add(0.5, -2, 0.5);
					ItemUtils.noRotateTeleport(p, l);
					Utils.debug(Utils.DebugType.SERVER, "Teleporting " + p.getName() + " to " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
				}
				default -> {
					// Hit a side face, so backtrack until we find a safe spot
					Location hitLocation = result.getHitPosition().toLocation(p.getWorld());
					Vector direction = origin.getDirection().normalize();

					// Calculate max backtrack distance (don't go past player's origin)
					double maxBacktrack = origin.distance(hitLocation);

					// Backtrack from the exact hit point
					Location checkLoc = hitLocation.clone();
					Location lastSafe = null;
					double totalBacktracked = 0;

					// Backtrack in smaller increments for more precision
					for(int i = 0; i < 100; i++) { // 120 * 0.1 = 12 blocks
						// Backtrack by 0.1 blocks for precision
						checkLoc.subtract(direction.clone().multiply(0.1));
						totalBacktracked += 0.1;

						// Don't go past the player's starting position
						if(totalBacktracked > maxBacktrack) {
							break;
						}

						// Check current block
						Block feetBlock = checkLoc.getBlock();
						Block headBlock = feetBlock.getRelative(BlockFace.UP);

						if(feetBlock.isPassable() && headBlock.isPassable()) {
							// This spot is safe, but keep checking for the optimal position
							lastSafe = checkLoc.clone();

							// Check if we've backtracked enough (at least 0.5 blocks from wall)
							if(checkLoc.distance(hitLocation) >= 0.5) {
								// Center on the block we're in
								Location l = new Location(checkLoc.getWorld(), Math.floor(checkLoc.getX()) + 0.5, Math.floor(checkLoc.getY()), Math.floor(checkLoc.getZ()) + 0.5);
								ItemUtils.noRotateTeleport(p, l);
								Utils.debug(Utils.DebugType.SERVER, "Teleporting " + p.getName() + " to " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
								p.setFallDistance(0);
								Utils.playLocalSound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
								return;
							}
						}
					}

					// If we found a safe spot but didn't teleport yet
					if(lastSafe != null) {
						Location l = new Location(lastSafe.getWorld(), Math.floor(lastSafe.getX()) + 0.5, Math.floor(lastSafe.getY()), Math.floor(lastSafe.getZ()) + 0.5);
						ItemUtils.noRotateTeleport(p, l);
						Utils.debug(Utils.DebugType.SERVER, "Teleporting " + p.getName() + " to " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
					}
				}
			}
		}
		p.setFallDistance(0);
		Utils.playLocalSound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
	}
}
