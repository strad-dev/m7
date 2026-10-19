package listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.util.Vector;
import plugin.Utils;

public class PearlHelper implements Listener {
	@EventHandler
	public void onPearlThrow(ProjectileLaunchEvent e) {
		if (!(e.getEntity() instanceof EnderPearl pearl)) return;
		if (!(e.getEntity().getShooter() instanceof Player p)) return;

		// The Infinileap IS an ender pearl and must never be thrown; Infinileap.leap does the leap. handleCustomItems
		// normally cancels first; this is the backstop, and it asks the item (items.MenuItem.blocksVanillaUse) so
		// any other pearl-shaped menu item is covered too.
		items.Item held = items.ItemRegistry.of(p.getInventory().getItemInMainHand());
		if(held instanceof items.MenuItem menu && menu.blocksVanillaUse()) {
			e.setCancelled(true);
			return;
		}

		Vector direction = p.getEyeLocation().getDirection();
		pearl.setVelocity(direction.multiply(1.5));

		Utils.debug(Utils.DebugType.SERVER, "Ender Pearl #" + pearl.getEntityId() + " from " + p.getName() + " thrown at " + Utils.round(p.getLocation().getX(), 3) + " " + Utils.round(p.getLocation().getY(), 5) + " " + Utils.round(p.getLocation().getZ(), 3) + " " + p.getLocation().getYaw() + " " + p.getLocation().getPitch() + " " + (p.isSneaking() ? "sneaking" : "standing"));
		Utils.scheduleTask(() -> p.setCooldown(Material.ENDER_PEARL, 0), 1);
	}

	@EventHandler
	public void onPearlLand(ProjectileHitEvent e) {
		if(e.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player p) {
			e.setCancelled(true);
			Location l;
			if(e.getHitBlock() != null && e.getHitBlockFace() != null) {
				BlockFace face = e.getHitBlockFace();
				// Thrown from inside a block, Bukkit inverts the face, so use velocity instead
				if(!p.getEyeLocation().getBlock().isPassable()) {
					Vector vel = pearl.getVelocity();
					double absX = Math.abs(vel.getX()), absY = Math.abs(vel.getY()), absZ = Math.abs(vel.getZ());
					if(absY >= absX && absY >= absZ) {
						face = vel.getY() > 0 ? BlockFace.UP : BlockFace.DOWN;
					} else if(absX >= absZ) {
						face = vel.getX() > 0 ? BlockFace.EAST : BlockFace.WEST;
					} else {
						face = vel.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
					}
				}
				if(face == BlockFace.UP) {
					// Top face: above the hit block
					l = e.getHitBlock().getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5);
				} else if(face == BlockFace.DOWN) {
					// Bottom face: the hit block's Y
					l = e.getHitBlock().getLocation().add(0.5, 0, 0.5);
				} else {
					// Side face: the adjacent air block
					l = e.getHitBlock().getRelative(face).getLocation().add(0.5, 0, 0.5);
				}
			} else {
				// Entity hit or unknown: pearl location, floored
				l = pearl.getLocation();
				l.setX(Math.floor(l.getX()) + 0.5);
				l.setY(Math.ceil(l.getY()));
				l.setZ(Math.floor(l.getZ()) + 0.5);
			}
			l.setYaw(p.getLocation().getYaw());
			l.setPitch(p.getLocation().getPitch());
			Utils.debug(Utils.DebugType.SERVER, "Ender Pearl #" + pearl.getEntityId() + " from " + p.getName() + " landed in " + pearl.getTicksLived() + " ticks");
			String hit;
			if(e.getHitBlock() != null) {
				hit = "block " + e.getHitBlockFace();
			} else if(e.getHitEntity() != null) {
				hit = "entity " + e.getHitEntity().getName();
			} else {
				hit = "something intangible";
			}
			Utils.debug(Utils.DebugType.SERVER, "Landed at " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3) + ", due to colliding with " + hit);
			pearl.remove();
			p.teleport(l);
		}
	}
}
