package plugin;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class PlayerInventoryBackup {
	private final ItemStack[] contents;
	private final ItemStack[] armorContents;
	private final ItemStack offHand;
	private final int heldItemSlot;

	public PlayerInventoryBackup(Player player) {
		PlayerInventory inv = player.getInventory();
		this.contents = inv.getContents().clone();
		this.armorContents = inv.getArmorContents().clone();
		this.offHand = inv.getItemInOffHand().clone();
		this.heldItemSlot = inv.getHeldItemSlot();
	}

	public void restore(Player player) {
		PlayerInventory inv = player.getInventory();
		inv.setContents(contents);
		inv.setArmorContents(armorContents);
		inv.setItemInOffHand(offHand);
		inv.setHeldItemSlot(heldItemSlot);
		player.updateInventory();
	}
}
