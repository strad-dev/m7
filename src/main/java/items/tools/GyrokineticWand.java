package items.tools;

import damage.Rarity;
import damage.ReforgeId;
import items.AbilityItem;
import items.Cast;
import items.ItemFactory;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import plugin.M7;
import plugin.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** LEFT-click ability; right-click isn't one, so interact cancel and entity-interact block are both off. */
public final class GyrokineticWand implements AbilityItem {
	public static final GyrokineticWand INSTANCE = new GyrokineticWand();

	private GyrokineticWand() {}

	@Override
	public String loreId() {
		return "skyblock/combat/gyro";
	}

	@Override
	public Material material() {
		return Material.BLAZE_ROD;
	}

	@Override
	public String baseName() {
		return "Gyrokinetic Wand";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.EPIC;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.NONE;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "GYROKINETIC_WAND");
	}

	@Override
	public boolean cancelsInteract() {
		return false;
	}

	@Override
	public boolean allowsEntityInteract() {
		return true;
	}

	@Override
	public boolean suppressesBlockBreak() {
		return true;
	}

	@Override
	public boolean hasLeftClick() {
		return true;
	}

	@Override
	public int cooldownTicks() {
		return 600; // 30s
	}

	@Override
	public boolean onLeftClick(Cast cast) {
		gyro(cast.player());
		return true;
	}

	public static void gyro(Player p) {
		RayTraceResult result = p.rayTraceBlocks(24);
		if(result == null) {
			return;
		}
		Location l = result.getHitBlock().getLocation();
		p.getWorld().spawnParticle(Particle.PORTAL, l, 128);
		l.setY(l.getY() + 1);
		new BukkitRunnable() {
			float pitch = 0.5f;

			@Override
			public void run() {
				if(pitch >= 2.0f) {
					cancel();
					return;
				}
				Utils.playLocalSound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 2.0f, pitch);
				pitch += 0.025f;
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);

		Material[] blockTypes = {Material.OBSIDIAN, Material.PURPLE_CONCRETE, Material.PURPLE_STAINED_GLASS};
		List<FallingBlock> fallingBlocks = new ArrayList<>();
		boolean[] effectEnded = {false}; // Flag to track if effect has ended

		// Initial block spawning
		for(double angle = 0; angle < Math.PI * 2; angle += Math.PI / 32) {
			double x = l.getX() + Math.cos(angle) * 10;
			double z = l.getZ() + Math.sin(angle) * 10;

			// Find the next air block going upwards from the requested location
			double y = l.getY();
			while(!Objects.requireNonNull(l.getWorld()).getBlockAt((int) x, (int) y, (int) z).getType().isAir() && y < l.getWorld().getMaxHeight()) {
				y++;
			}
			y += 0.05; // Place falling block 0.05 above the air block
			Location blockLoc = new Location(l.getWorld(), x, Math.max(y, l.getY()), z);

			Material blockType = blockTypes[(int) (Math.random() * blockTypes.length)];
			FallingBlock block = l.getWorld().spawn(blockLoc, FallingBlock.class, fb -> fb.setBlockData(blockType.createBlockData()));
			block.setGravity(false);
			block.setDropItem(false);
			block.setInvulnerable(true);

			block.setHurtEntities(false);
			block.setPersistent(true);
			block.setCancelDrop(true);

			fallingBlocks.add(block);
		}

		// Ensure blocks don't place by constantly checking and respawning if needed
		BukkitRunnable blockChecker = new BukkitRunnable() {
			@Override
			public void run() {
				if(effectEnded[0]) {
					cancel();
					return;
				}

				List<FallingBlock> toRemove = new ArrayList<>();
				List<FallingBlock> toAdd = new ArrayList<>();

				for(FallingBlock block : fallingBlocks) {
					if(block.isDead() || !block.isValid()) {
						// The block has turned into a real block or disappeared
						toRemove.add(block);

						// Create a new block at the same location
						Material blockType = blockTypes[(int) (Math.random() * blockTypes.length)];
						Location respawnLoc = block.getLocation().clone().add(0, 0.1, 0);

						FallingBlock newBlock = Objects.requireNonNull(respawnLoc.getWorld()).spawn(respawnLoc, FallingBlock.class, fb -> fb.setBlockData(blockType.createBlockData()));
						newBlock.setGravity(false);
						newBlock.setDropItem(false);
						newBlock.setInvulnerable(true);
						newBlock.setHurtEntities(false);
						newBlock.setPersistent(true);
						newBlock.setCancelDrop(true);

						toAdd.add(newBlock);
					}
				}

				// Update our block list
				fallingBlocks.removeAll(toRemove);
				fallingBlocks.addAll(toAdd);

				if(fallingBlocks.isEmpty()) {
					cancel();
				}
			}
		};
		blockChecker.runTaskTimer(M7.getInstance(), 1L, 1L);

		new BukkitRunnable() {
			int tick = 0;
			final List<Location> initialPositions = new ArrayList<>();
			final double TOTAL_TICKS = 60.0; // 3 seconds
			final double CONVERGE_TICKS = 40.0; // 2 seconds to converge

			@Override
			public void run() {
				if(tick == 0) {
					for(FallingBlock block : fallingBlocks) {
						initialPositions.add(block.getLocation());
					}
				}

				if(tick >= TOTAL_TICKS) {
					effectEnded[0] = true;
					fallingBlocks.forEach(block -> {
						if(block.isValid()) {
							block.remove();
						}
					});
					fallingBlocks.clear();
					cancel();
					return;
				}

				double progress = Math.min(tick / CONVERGE_TICKS, 1.0);

				for(int i = 0; i < fallingBlocks.size(); i++) {
					FallingBlock block = fallingBlocks.get(i);
					if(!block.isValid()) continue; // Skip invalid blocks

					Location startLoc = i < initialPositions.size() ? initialPositions.get(i) : block.getLocation();

					// Linear interpolation between start and rift
					Vector direction = l.toVector().subtract(startLoc.toVector());
					Vector currentPos = startLoc.toVector().add(direction.multiply(progress));

					if(progress < 1.0) {
						// Add constant wobble effect
						Vector wobble = new Vector((Math.random() - 0.5) * 4, (Math.random() - 0.5) * 4, (Math.random() - 0.5) * 4);

						currentPos.add(wobble);
					} else {
						// Keep blocks at center with slight wobble
						Vector wobble = new Vector((Math.random() - 0.5) * 2, (Math.random() - 0.5) * 2, (Math.random() - 0.5) * 2);
						currentPos = l.toVector().add(wobble);
					}

					Location targetLoc = currentPos.toLocation(l.getWorld());

					// Ensure minimum height above ground
					// Find the next air block going upwards from the requested location
					double minY = l.getY();
					while(!Objects.requireNonNull(l.getWorld()).getBlockAt(targetLoc.getBlockX(), (int) minY, targetLoc.getBlockY()).getType().isAir() && minY < l.getWorld().getMaxHeight()) {
						minY++;
					}
					minY += 0.05; // Place falling block 0.05 above the air block
					targetLoc.setY(Math.max(targetLoc.getY(), minY));

					// Calculate velocity vector based on distance to target with increased speed
					Vector velocity = targetLoc.toVector().subtract(block.getLocation().toVector()).multiply(0.3);

					block.setVelocity(velocity);
				}

				tick++;
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);

		for(Entity e : Objects.requireNonNull(l.getWorld()).getNearbyEntities(l, 10, 10, 10)) {
			// Immune: the Watcher, villager NPCs (Mort/Wizard) and bosses (withers, ender dragons).
			if(e instanceof LivingEntity entity && !(entity instanceof Player) && !(entity instanceof Wither)
					&& !(entity instanceof EnderDragon)
					&& !(entity instanceof Villager)
					&& !entity.getScoreboardTags().contains("TASWatcher")) {
				new BukkitRunnable() {
					int tick = 0;

					@Override
					public void run() {
						if(tick >= 60) { // 3 seconds * 20 ticks
							cancel();
							return;
						}

						if(tick < 10) { // First 0.5 seconds - pull in
							Location entityLoc = entity.getLocation();
							double x = (l.getX() - entityLoc.getX()) / 5;
							double y = (l.getY() - entityLoc.getY()) / 5;
							double z = (l.getZ() - entityLoc.getZ()) / 5;
							entity.setVelocity(new Vector(x, y, z));
						} else { // Next 2.5 seconds - keep at rift
							entity.teleport(l);
						}

						tick++;
					}
				}.runTaskTimer(M7.getInstance(), 0L, 1L);
			}
		}
	}
}
