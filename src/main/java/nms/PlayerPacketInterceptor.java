package nms;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import listeners.CustomItems;
import listeners.GoldorListener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import plugin.M7;

public class PlayerPacketInterceptor extends ChannelDuplexHandler {
	private final Player player;

	public PlayerPacketInterceptor(Player player) {
		this.player = player;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if(msg instanceof ServerboundPlayerActionPacket pkt) {
			// Drop key (Q / Ctrl+Q) and bow release. DROP still goes to vanilla: CustomItems' PlayerDropItemEvent
			// handler cancels the physical drop for class players while handleDrop fires the ability. A bow RELEASE
			// is consumed and released by hand with instabuild on, so it fires without a real arrow.
			var action = pkt.getAction();
			if(action == ServerboundPlayerActionPacket.Action.DROP_ITEM) {
				Bukkit.getScheduler().runTask(M7.getInstance(), () -> {
					CustomItems.handleDrop(player, true);
					player.updateInventory();
				});
			} else if(action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
				Bukkit.getScheduler().runTask(M7.getInstance(), () -> {
					CustomItems.handleDrop(player, false);
					player.updateInventory();
				});
			} else if(action == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
				// Ask the item, never a lore-ID list. It was hardcoded to Last Breath and Explosive Bow, so the Death
				// Bow drew and fired nothing, silently.
				if(items.ItemRegistry.of(player.getInventory().getItemInMainHand()) instanceof items.Bow bow
						&& bow.holdToDraw()) {
					Bukkit.getScheduler().runTask(M7.getInstance(), () -> {
						ServerPlayer sp = ((CraftPlayer) player).getHandle();
						boolean was = sp.getAbilities().instabuild;
						sp.getAbilities().instabuild = true;
						sp.releaseUsingItem();
						sp.getAbilities().instabuild = was;
					});
					return;
				}
			} else if(action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
				// Left-click ON A BLOCK: the ONLY place the client says which block (the swing has no position), so
				// block abilities (Superboom TNT) target this, not a server ray trace. The block is never broken:
				// handleCustomItems cancels the follow-up interact and onBlockBreak refuses as a backstop.
				//
				// In adventure this only arrives for can_break items (Dungeonbreaker, TNT via
				// Utils.placeAndBreakAnythingInAdventure). Verified in the 26.2 client: startDestroyBlock returns early
				// on blockActionRestricted. Vanilla's own PlayerInteractEvent fires for the same packet, same tick, and
				// the 1/tick guards collapse the two.
				//
				// Server executor: same main-thread FIFO as vanilla's handler, runs before it. Like UseItemOn below.
				net.minecraft.core.BlockPos bp = pkt.getPos();
				int bx = bp.getX(), by = bp.getY(), bz = bp.getZ();
				MinecraftServer.getServer().execute(() ->
					CustomItems.handleCustomItems(null, EquipmentSlot.HAND, player.getInventory().getItemInMainHand(),
							Action.LEFT_CLICK_BLOCK, player, player.getWorld().getBlockAt(bx, by, bz)));
			}
		}
		if(msg instanceof ServerboundAttackPacket attackPkt) {
			// 26.2 moved melee into ServerboundAttackPacket (ServerboundInteractPacket is interact/interact-at only).
			// Every attack dispatches LEFT_CLICK_AIR: EntityDamageByEntityEvent only fires when damage lands (not on
			// shielded withers or dying mobs), and no PlayerInteractEvent fires with a mob in melee range, so this is
			// what fires the beam there.
			//
			// The entity id is also the only source of an ordinary melee hit, since vanilla damage is cancelled
			// (CustomItems.onEntityDamageByEntity). meleeAttack stands down for left-click-ability items, so a beam
			// swing doesn't also melee.
			//
			// Server executor, not runTask: runTask landed a tick late, so a beam on a mob ran a tick behind a beam at
			// air and the 5-tick cooldown dropped the next click if the two alternated.
			int targetId = attackPkt.entityId();
			MinecraftServer.getServer().execute(() -> {
				CustomItems.handleCustomItems(null, EquipmentSlot.HAND, player.getInventory().getItemInMainHand(), Action.LEFT_CLICK_AIR, player);
				net.minecraft.world.entity.Entity target =
						((org.bukkit.craftbukkit.CraftWorld) player.getWorld()).getHandle().getEntity(targetId);
				if(target != null) CustomItems.meleeAttack(player, target.getBukkitEntity());
			});
		}
		// Right-click abilities are dispatched straight from the packet (UseItemOn -> RIGHT_CLICK_BLOCK, UseItem ->
		// RIGHT_CLICK_AIR), bypassing vanilla's same-block interact suppression. CustomItems.rightClickGate dedupes
		// against vanilla's event across a tick, since the two are separate main-thread tasks and a tick boundary can
		// fall between them.
		//
		// Server executor, not Bukkit runTask: same FIFO queue as the packet handler, so it runs just before vanilla
		// processes the packet.
		if(msg instanceof ServerboundUseItemOnPacket usePkt && usePkt.getHand() == InteractionHand.MAIN_HAND) {
			net.minecraft.core.BlockPos bp = usePkt.getHitResult().getBlockPos();
			int bx = bp.getX(), by = bp.getY(), bz = bp.getZ();
			MinecraftServer.getServer().execute(() -> {
				org.bukkit.Material clicked = player.getWorld().getBlockAt(bx, by, bz).getType();
				// Reset vanilla's interact dedupe (useItemOn caches the last (block, hand, item) result), so rapid
				// clicks on one block (Simon Says) each fire PlayerInteractEvent; vanilla's listener doesn't.
				//
				// Never for a LEVER: one right-click sends UseItemOn more than once and the dedupe is what makes it
				// one toggle. Without it the lever flips twice, which broke the S2 "Lights" device (reads lamp state).
				if(clicked != org.bukkit.Material.LEVER) {
					((CraftPlayer) player).getHandle().gameMode.firedInteract = false;
				}
				// Simon Says clicks straight from the packet so rapid clicks all register (1/tick in GoldorListener).
				GoldorListener.tryRegisterSimonClick(player, bx, by, bz);
				// A lever, button or any block vanilla would use (Utils.usesBlock) owns the click, so no item ability on
				// top. Mirrors CustomItems.onPlayerInteract's guard; without it an item's right-click fired on S2 levers
				// and hijacked the toggle.

				org.bukkit.block.Block clickedBlock = player.getWorld().getBlockAt(bx, by, bz);
				if(clicked != org.bukkit.Material.LEVER && !org.bukkit.Tag.BUTTONS.isTagged(clicked)
						&& !instructions.clear.ClearManager.isSecretBlock(clickedBlock)
						&& !plugin.Utils.usesBlock(player, clickedBlock)) {
					// Vanilla's hit block, so block abilities (Superboom TNT) match vanilla's range and target.
					CustomItems.handleCustomItems(null, EquipmentSlot.HAND,
							player.getInventory().getItemInMainHand(), Action.RIGHT_CLICK_BLOCK, player, clickedBlock);
				}
			});
		} else if(msg instanceof ServerboundUseItemPacket airPkt && airPkt.getHand() == InteractionHand.MAIN_HAND) {
			MinecraftServer.getServer().execute(() -> {
				((CraftPlayer) player).getHandle().gameMode.firedInteract = false;
				CustomItems.handleCustomItems(null, EquipmentSlot.HAND,
						player.getInventory().getItemInMainHand(), Action.RIGHT_CLICK_AIR, player);
			});
		}
		super.channelRead(ctx, msg);
	}

}
