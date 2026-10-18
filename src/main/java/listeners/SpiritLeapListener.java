package listeners;

import instructions.Actions;
import instructions.bosses.WitherActions;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import plugin.Menus;
import plugin.Utils;

/** Practice Spirit Leap menu ({@link SpiritLeapMenu}): the Infinileap opens it (errors outside a run); a quadrant leaps. */
public class SpiritLeapListener implements Listener {

	@EventHandler
	public void onLeapItemRightClick(PlayerInteractEvent e) {
		if(e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		if(e.getAction() == Action.RIGHT_CLICK_BLOCK && Utils.usesBlock(e.getPlayer(), e.getClickedBlock())) return;
		// Resolved through the registry, so the item opens its own menu (items.MenuItem.open); this owns only the gates.
		if(!(items.ItemRegistry.of(e.getItem()) instanceof items.MenuItem menu)) return;
		Player p = e.getPlayer();
		e.setCancelled(true);
		p.setCooldown(Material.ENDER_PEARL, 0); // clear the ender pearl cooldown

		if(!WitherActions.isPracticeMode()) {
			p.sendMessage(Utils.msg("<red>This item can't be used right now!"));
			return;
		}
		if(!SpiritLeapMenu.hasCandidates(p)) {
			p.sendMessage(Utils.msg("<red>Found no one to leap to!"));
			return;
		}
		menu.open(p);
	}

	@EventHandler
	public void onMenuClick(InventoryClickEvent e) {
		if(!(e.getInventory().getHolder() instanceof SpiritLeapMenu menu)) return;
		if(Menus.ignoreDoubleClick(e)) return;
		e.setCancelled(true); // lock the menu; never move items
		if(!(e.getWhoClicked() instanceof Player p)) return;
		Player target = menu.targetForSlot(e.getRawSlot());
		if(target == null || !target.isOnline()) return;
		p.closeInventory();
		Actions.leap(p, target); // viewer still holds the Infinileap
	}
}
