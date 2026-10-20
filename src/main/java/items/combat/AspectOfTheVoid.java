package items.combat;

import damage.Rarity;
import damage.ReforgeId;
import items.*;
import listeners.LavaJump;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import plugin.Utils;

/**
 * Etherwarp on sneak right-click, 12-block Instant Transmission otherwise.
 * <p>
 * The boss-arena refusal stays inside the ability: it reports fired either way, so the click is consumed and the
 * two-tick gate stamped. Hoisting it would change that.
 */
public final class AspectOfTheVoid implements Weapon, AbilityItem {
	public static final AspectOfTheVoid INSTANCE = new AspectOfTheVoid();

	private AspectOfTheVoid() {}

	@Override
	public String loreId() {
		return "skyblock/combat/aotv";
	}

	@Override
	public Material material() {
		return Material.DIAMOND_SHOVEL;
	}

	@Override
	public String baseName() {
		return "Aspect of the Void";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.EPIC;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.WARPED;
	}

	/**
	 * The only item needing more NBT than {@code id}; these keys make a client read it as a <b>Warped</b> AOTV.
	 * <p>
	 * TOP LEVEL of {@code minecraft:custom_data}, no {@code ExtraAttributes}: SkyblockAPI reads it there
	 * ({@code DataType.simple}), and Catharsis (the retexture) resolves the model from {@code id}
	 * ({@code skyblock:items/aspect_of_the_void.json}) then picks the warped variant off {@code ethermerge}. A pack
	 * may also range on {@code tuned_transmission}. None of it needs Hypixel.
	 * <p>
	 * {@code ethermerge} as an INT still reads {@code true} ({@code getBoolean -> asBoolean -> NumericTag.asByte}).
	 * The display name isn't matched client-side.
	 * <p>
	 * {@code modifier} is the REFORGE, spelled after the reforge STONE: Warped Stone's id is {@code AOTE_STONE}, so
	 * {@code aote_stone}, not {@code warped}. Separate from {@code ethermerge} (the Etherwarp upgrade); without it the
	 * item reads unreforged. Only written for {@link ReforgeId#WARPED}.
	 */
	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), nbt -> {
			nbt.putString("id", "ASPECT_OF_THE_VOID");
			nbt.putInt("ethermerge", 1);
			nbt.putInt("tuned_transmission", 4);
			if(reforge == ReforgeId.WARPED) nbt.putString("modifier", "aote_stone");
		});
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
		aotv(cast.player());
		return true;
	}

	public static void aotv(Player p) {
		// Disabled only in the boss arena in adventure (practice default), so it can't skip boss mechanics. A watcher
		// isn't in the run, so it has none to skip.
		if(p.getGameMode() == org.bukkit.GameMode.ADVENTURE && LavaJump.isInBossArena(p.getLocation())
				&& !Utils.isSpectator(p)) return;
		Utils.debug(Utils.DebugType.SERVER, "Starting at " + Utils.round(p.getLocation().getX(), 2) + " " + Utils.round(p.getLocation().getY(), 2) + " " + Utils.round(p.getLocation().getZ(), 2) + " " + Utils.round(p.getLocation().getYaw(), 2) + " " + Utils.round(p.getLocation().getPitch(), 2));
		if(p.isSneaking()) {
			RayTraceResult result = p.rayTraceBlocks(61);
			if(result != null) {
				Block b = result.getHitBlock();
				Location l = b.getLocation().add(0.5, 1, 0.5);
				if(l.getBlock().getType().isSolid() || l.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
					Utils.debug(Utils.DebugType.SERVER, "Could not Etherwarp " + p.getName() + " to " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
					return;
				}
				p.setFallDistance(0);
				Utils.playLocalSound(p, Sound.ENTITY_ENDER_DRAGON_HURT, 1, 0.50F);
				Utils.debug(Utils.DebugType.SERVER, "Etherwarping " + p.getName() + " to " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
				ItemUtils.noRotateTeleport(p, l);
			} else {
				Utils.debug(Utils.DebugType.SERVER, "Could not Etherwarp " + p.getName() + " at all");
			}
		} else {
			Location origin = p.getLocation().clone();
			RayTraceResult result = p.rayTraceBlocks(13.65);
			if(result == null) {
				Location l = p.getLocation().add(p.getLocation().getDirection().multiply(12));
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
						for(int i = 0; i < 120; i++) { // 120 * 0.1 = 12 blocks
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
}
