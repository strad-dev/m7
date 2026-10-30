package listeners;

import instructions.bosses.witherking.WitherKing;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Wither-King summon phase. Pickup: right-click a relic's Interaction entity. Placement: right-click the matching
 * altar (Y 6/7) holding it. A held relic is locked in the hotbar until placed on its altar.
 */
public class WitherKingListener implements Listener {

	/**
	 * Dragons never attack. A summoned dragon stays in vanilla's HOVERING phase, but {@code EnderDragon.aiStep} still
	 * runs {@code knockBack} (wings: push + 5) and {@code hurt} (head/neck: 10) on whoever touches it in any
	 * non-sitting phase.
	 */
	@EventHandler(ignoreCancelled = true)
	public void onDragonHit(EntityDamageByEntityEvent e) {
		if(e.getEntity() instanceof Player && isWitherKingDragon(e.getDamager())) e.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onDragonPush(EntityPushedByEntityAttackEvent e) {
		if(e.getEntity() instanceof Player && isWitherKingDragon(e.getPushedBy())) e.setCancelled(true);
	}

	private static boolean isWitherKingDragon(Entity entity) {
		if(entity instanceof EnderDragonPart part) entity = part.getParent();
		return entity instanceof EnderDragon && entity.getScoreboardTags().contains("WitherKingDragon");
	}

	/** Right-click a relic's Interaction entity to pick it up, unless already carrying one. */
	@EventHandler
	public void onPickup(PlayerInteractAtEntityEvent e) {
		if(!(e.getRightClicked() instanceof Interaction interaction)) return;
		String color = WitherKing.relicColorForInteraction(interaction);
		if(color == null) return;
		e.setCancelled(true);
		if(WitherKing.isHoldingRelic(e.getPlayer())) return; // one relic at a time
		WitherKing.pickUpRelic(e.getPlayer(), color);
	}

	/**
	 * Right-click holding a relic: the wool is never placed, and only the matching altar (Y 6/7) takes the relic.
	 * <p>
	 * The WRONG cauldron sends it back to its statue in every mode, and outside classic kills you too
	 * ({@code death/Deaths}). The summon needs all five, so a relic can never be lost. The return must come FIRST:
	 * {@code Deaths} snapshots the inventory for revival, and a relic still in hand would come back as a duplicate.
	 * <p>
	 * A non-altar click is just cancelled. Placing and returning are exclusive; {@code returnRelicToStatue}
	 * re-checks that too.
	 */
	@EventHandler
	public void onPlace(PlayerInteractEvent e) {
		if(e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		// Main hand only: cancelling it makes vanilla try the off hand, a SECOND event for the same click that would
		// re-run the branch below (same as GoldorListener).
		if(e.getHand() != EquipmentSlot.HAND) return;
		Player p = e.getPlayer();
		String heldColor = WitherKing.relicColorOfItem(p.getInventory().getItemInMainHand());
		if(heldColor == null) return; // not holding a relic
		e.setCancelled(true); // never let the wool be placed as a block

		Block b = e.getClickedBlock();
		if(b == null || (b.getY() != 6 && b.getY() != 7)) return;
		String altarColor = WitherKing.altarColorAt(b.getX(), b.getZ());
		if(altarColor == null) return; // not an altar

		if(altarColor.equals(heldColor)) {
			WitherKing.placeRelic(p, altarColor);
		} else {
			WitherKing.returnRelicToStatue(p, heldColor);
			death.Deaths.kill(p, "Wither King"); // no-op in classic
		}
	}

	// Held relic stays locked in the hotbar until placed

	/** No moving a relic in any inventory (click, shift-click, number key). */
	@EventHandler
	public void onRelicClick(InventoryClickEvent e) {
		if(WitherKing.relicColorOfItem(e.getCurrentItem()) != null || WitherKing.relicColorOfItem(e.getCursor()) != null) {
			e.setCancelled(true);
			return;
		}
		if(e.getClick() == ClickType.NUMBER_KEY && e.getWhoClicked() instanceof Player p) {
			ItemStack swapTarget = p.getInventory().getItem(e.getHotbarButton());
			if(WitherKing.relicColorOfItem(swapTarget) != null) e.setCancelled(true);
		}
	}

	/** No dragging a relic. */
	@EventHandler
	public void onRelicDrag(InventoryDragEvent e) {
		if(WitherKing.relicColorOfItem(e.getOldCursor()) != null) {
			e.setCancelled(true);
			return;
		}
		for(ItemStack it : e.getNewItems().values()) {
			if(WitherKing.relicColorOfItem(it) != null) {
				e.setCancelled(true);
				return;
			}
		}
	}

	/** No dropping a relic. */
	@EventHandler
	public void onRelicDrop(PlayerDropItemEvent e) {
		if(WitherKing.relicColorOfItem(e.getItemDrop().getItemStack()) != null) e.setCancelled(true);
	}

	/** Backstop: a relic wool is never placed as a block. */
	@EventHandler
	public void onRelicBlockPlace(BlockPlaceEvent e) {
		if(WitherKing.relicColorOfItem(e.getItemInHand()) != null) e.setCancelled(true);
	}
}
