package listeners;

import instructions.clear.DungeonMap;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import plugin.ClassKits;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <ul>
 *   <li><b>Linked slots</b>: in the E inventory, shift+left-click on a backpack slot swaps it with the hotbar slot in
 *       its column. The 9th column maps to hotbar 7, since 8 is the SkyBlock menu.</li>
 *   <li><b>SkyBlock-menu lock</b>: no click, number key, drag or drop moves the menu out of hotbar 8. Only direct
 *       inventory writes replace it (Energy Crystal, Wither-King relic).</li>
 * </ul>
 */
public class LinkedSlots implements Listener {

	private static final int MENU_SLOT = 8;
	/** Offhand slot in the E inventory, where the clear-phase dungeon map is locked. */
	private static final int OFFHAND_SLOT = 40;
	/** A double-click's pickup-all can trail its first click by ~5 ticks. */
	private static final int DOUBLE_CLICK_WINDOW = 10;
	/** Last tick a linked swap ran, per player; collapses a double-click's burst into one swap. */
	private static final Map<UUID, Integer> lastSwapTick = new HashMap<>();
	/** Hotbar slot the last linked swap moved an item INTO; a trailing double-click on it is ignored. */
	private static final Map<UUID, Integer> lastSwapHotbar = new HashMap<>();

	@EventHandler
	public void onInventoryClick(InventoryClickEvent e) {
		if(!(e.getWhoClicked() instanceof Player p)) return;
		PlayerInventory inv = p.getInventory();

		// SkyBlock-menu lock
		if(ClassKits.isSkyblockMenu(inv.getItem(MENU_SLOT))) {
			boolean clicksMenuSlot = e.getClickedInventory() != null && e.getClickedInventory().equals(inv) && e.getSlot() == MENU_SLOT;
			boolean numberKeyToMenu = e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() == MENU_SLOT;
			boolean swapOffhandMenu = e.getClick() == ClickType.SWAP_OFFHAND && e.getClickedInventory() != null
					&& e.getClickedInventory().equals(inv) && e.getSlot() == MENU_SLOT;
			if(clicksMenuSlot || numberKeyToMenu || swapOffhandMenu) {
				e.setCancelled(true);
				return;
			}
		}

		// Offhand dungeon-map lock
		if(DungeonMap.isDungeonMap(inv.getItemInOffHand())) {
			boolean clicksOffhand = e.getClickedInventory() != null && e.getClickedInventory().equals(inv) && e.getSlot() == OFFHAND_SLOT;
			// SWAP_OFFHAND (F over any slot) pulls the map out whatever slot is hovered.
			boolean swapOffhand = e.getClick() == ClickType.SWAP_OFFHAND;
			if(clicksOffhand || swapOffhand) {
				e.setCancelled(true);
				return;
			}
		}

		// A shift+double-click's second click trails ~5 ticks later as SHIFT_LEFT on the hotbar slot I just swapped
		// INTO. Vanilla would shift-move it back and undo the swap, so swallow it.
		if((e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.DOUBLE_CLICK)
				&& e.getSlotType() == InventoryType.SlotType.QUICKBAR) {
			Integer swapTick = lastSwapTick.get(p.getUniqueId());
			Integer swapHotbar = lastSwapHotbar.get(p.getUniqueId());
			if(swapTick != null && swapHotbar != null && swapHotbar == e.getSlot()
					&& MinecraftServer.currentTick - swapTick <= DOUBLE_CLICK_WINDOW) {
				e.setCancelled(true);
				return;
			}
		}

		// Linked slots. Top row (9-17) is ignored; only 18-35 link.
		if(e.getClick() != ClickType.SHIFT_LEFT) return;
		// E inventory only. Creative's is client-authoritative (ServerboundSetCreativeModeSlotPacket, never an
		// InventoryClickEvent), so this is survival/adventure only.
		if(e.getView().getTopInventory().getType() != InventoryType.CRAFTING) return;
		if(e.getClickedInventory() == null || !e.getClickedInventory().equals(inv)) return;
		int slot = e.getSlot();
		if(slot < 18 || slot > 35) return; // vanilla otherwise
		int hotbar = Math.min((slot - 9) % 9, 7); // column, but the 9th column maps to 7 (slot 8 is the menu)

		ItemStack back = inv.getItem(slot);
		ItemStack bar = inv.getItem(hotbar);
		// Only swap two real items; vanilla handles an empty slot.
		if(isEmpty(back) || isEmpty(bar)) return;

		e.setCancelled(true); // suppress the vanilla shift-move
		// A double-click's burst lands in one tick: swap at most once per player per tick.
		int now = MinecraftServer.currentTick;
		if(lastSwapTick.getOrDefault(p.getUniqueId(), -1) == now) return;
		lastSwapTick.put(p.getUniqueId(), now);
		lastSwapHotbar.put(p.getUniqueId(), hotbar);

		inv.setItem(slot, bar);
		inv.setItem(hotbar, back);
		p.updateInventory();
	}

	private static boolean isEmpty(ItemStack item) {
		return item == null || item.getType() == Material.AIR;
	}

	@EventHandler
	public void onInventoryDrag(InventoryDragEvent e) {
		if(!(e.getWhoClicked() instanceof Player p)) return;
		if(!ClassKits.isSkyblockMenu(p.getInventory().getItem(MENU_SLOT))) return;
		for(int raw : e.getRawSlots()) {
			Inventory at = e.getView().getInventory(raw);
			if(at != null && at.equals(p.getInventory()) && e.getView().convertSlot(raw) == MENU_SLOT) {
				e.setCancelled(true);
				return;
			}
		}
	}

	@EventHandler
	public void onDrop(PlayerDropItemEvent e) {
		ItemStack dropped = e.getItemDrop().getItemStack();
		// Menu and offhand dungeon map are undroppable.
		if(ClassKits.isSkyblockMenu(dropped) || DungeonMap.isDungeonMap(dropped)) {
			e.setCancelled(true);
		}
	}

	// F with no inventory open fires this, not InventoryClickEvent: keep the menu and offhand dungeon map in place.
	@EventHandler
	public void onSwapHands(PlayerSwapHandItemsEvent e) {
		if(ClassKits.isSkyblockMenu(e.getMainHandItem()) || ClassKits.isSkyblockMenu(e.getOffHandItem())
				|| DungeonMap.isDungeonMap(e.getMainHandItem()) || DungeonMap.isDungeonMap(e.getOffHandItem())) {
			e.setCancelled(true);
		}
	}
}
