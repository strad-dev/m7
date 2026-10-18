package instructions;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import plugin.M7;
import plugin.MovementAudit;
import plugin.Utils;

import java.util.*;

public class Actions {

	private static final Set<BukkitRunnable> forceMoveTasks = new HashSet<>();

	// Spigot-mojang compile name is detectEquipmentUpdatesPublic; Paper runtime has only detectEquipmentUpdates.
	// Resolve whichever exists at class-load time.
	private static final java.lang.reflect.Method DETECT_EQUIPMENT_UPDATES = resolveDetectEquipmentUpdates();

	private static java.lang.reflect.Method resolveDetectEquipmentUpdates() {
		for(String name : new String[]{"detectEquipmentUpdates", "detectEquipmentUpdatesPublic"}) {
			try {
				java.lang.reflect.Method m = net.minecraft.world.entity.LivingEntity.class.getMethod(name);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {
			}
		}
		throw new IllegalStateException("LivingEntity.detectEquipmentUpdates[Public] not found on runtime");
	}

	/**
	 * Forces a LivingEntity to move in a given direction regardless of physics
	 *
	 * @param entity        The Entity being moved
	 * @param perTick       The distance to move per tick
	 * @param durationTicks Number of ticks to move
	 */
	public static void forceMove(LivingEntity entity, Vector perTick, int durationTicks) {
		// only handle CraftLivingEntity/NMS and positive duration
		if(!(entity instanceof CraftLivingEntity cle) || durationTicks <= 0) return;

		net.minecraft.world.entity.LivingEntity nmsEntity = cle.getHandle();

		Vec3 motion = new Vec3(perTick.getX(), perTick.getY(), perTick.getZ());

		BukkitRunnable runnable = new BukkitRunnable() {
			int ticks = 0;

			@Override
			public void run() {
				if(ticks++ >= durationTicks || nmsEntity.isRemoved()) {
					forceMoveTasks.remove(this);
					cancel();
					return;
				}

				Vec3 currentPos = nmsEntity.position();

				Vec3 newPos = currentPos.add(motion);

				nmsEntity.absSnapTo(newPos.x(), newPos.y(), newPos.z(), nmsEntity.getYRot(), nmsEntity.getXRot());

				// keeps client-side interpolation
				nmsEntity.setDeltaMovement(motion);

				nmsEntity.hurtMarked = true;

				// Send position update to all nearby players
				if(nmsEntity instanceof ServerPlayer serverPlayer) {
					// For players, use the connection teleport
					serverPlayer.connection.teleport(newPos.x(), newPos.y(), newPos.z(), nmsEntity.getYRot(), nmsEntity.getXRot());
				} else {
					// For non-player entities, send teleport packet to all tracking players
					PositionMoveRotation posRotation = new PositionMoveRotation(newPos, Vec3.ZERO, nmsEntity.getYRot(), nmsEntity.getXRot());
					ClientboundTeleportEntityPacket teleportPacket = ClientboundTeleportEntityPacket.teleport(nmsEntity.getId(), posRotation, Set.of(), nmsEntity.onGround());
					Utils.broadcastPacket(teleportPacket);
				}
			}
		};
		forceMoveTasks.add(runnable);
		runnable.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	/** Hard-stops every forceMove. Practice start/end and /reset. */
	public static void cancelAllMovement() {
		for(BukkitRunnable runnable : new ArrayList<>(forceMoveTasks)) runnable.cancel();
		forceMoveTasks.clear();
	}

	/**
	 * Change which hotbar slot (0-8) the player is “holding”,
	 * and broadcast that slot change.
	 *
	 * @param p           The player whose hotbar slot is to be changed.
	 * @param hotbarIndex The index of the new hotbar slot (0-8).
	 */
	public static void setHotbarSlot(Player p, int hotbarIndex) {
		if(!(p instanceof CraftPlayer cp)) {
			return;
		}
		ServerPlayer npc = cp.getHandle();

		// Update the NMS held-slot index
		npc.getInventory().setSelectedSlot(hotbarIndex);

		// Apply item attribute modifiers (Dungeonbreaker's BLOCK_BREAK_SPEED) this frame, not next entity tick.
		try {
			DETECT_EQUIPMENT_UPDATES.invoke(npc);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}

		// Tell that player's client "your held slot is now hotbarIndex"
		ClientboundSetHeldSlotPacket heldPkt = new ClientboundSetHeldSlotPacket(hotbarIndex);
		cp.getHandle().connection.send(heldPkt);

		// Broadcast the new main-hand item to all viewers
		ClientboundSetEquipmentPacket equipPkt = new ClientboundSetEquipmentPacket(npc.getId(), List.of(Pair.of(EquipmentSlot.MAINHAND, npc.getInventory().getSelectedItem())));
		Utils.broadcastPacket(equipPkt);
	}

	/** Rotates a non-player entity (Storm) to the given yaw/pitch. */
	public static void turnHead(Entity entity, float yaw, float pitch) {
		Location to = entity.getLocation();
		to.setYaw(yaw);
		to.setPitch(pitch);
		entity.teleport(to);
	}


	public static void leap(Player p, Player target) {
		// Needs the Infinileap in hand.
		ItemStack held = p.getInventory().getItemInMainHand();
		if(!"skyblock/utility/infinileap".equals(items.ItemUtils.getID(held))) {
			String heldDesc;
			if(held.getType().isAir()) {
				heldDesc = "an empty hand";
			} else {
				items.ItemUtils.getID(held);
				heldDesc = held.getType() + (held.hasItemMeta() && held.getItemMeta().hasDisplayName() ? " (" + Utils.displayName(held.getItemMeta()) + ")" : "")
						+ " [id=" + items.ItemUtils.getID(held) + "]";
			}
			Utils.debug(Utils.DebugType.ERROR, p.getName() + " tried to leap while holding " + heldDesc + " instead of an Infinileap");
			return;
		}

		// Leap voids bonzo motion: cancel the airborne audit (would log a giant delta and bogus "landed") and remove
		// their in-flight wind charges so a late hit can't push them.
		MovementAudit.cancelAirborneAudit(p.getUniqueId());
		for(WindCharge windCharge : p.getWorld().getEntitiesByClass(WindCharge.class)) {
			if(windCharge.getScoreboardTags().contains("Bonzo") && windCharge.getShooter() == p) {
				items.combat.BonzoStaff.bonzoFireTick.remove(windCharge.getEntityId());
				windCharge.remove();
			}
		}

		Location targetLoc = target.getLocation();
		teleport(p, targetLoc);

		if(p instanceof CraftPlayer cp) {
			ServerPlayer npc = cp.getHandle();
			npc.setDeltaMovement(Vec3.ZERO);
			// Leaps are usually mid-air, so onGround is unreliable; false makes the next move() use air physics
			// (~0.02/tick, ignores speed). Force grounded; gravity still applies, onGround only sets friction.
			npc.setOnGround(true);
			npc.hurtMarked = true;
			// The tracker lags a tick, so observers glide on pre-leap momentum. Broadcast position + zero velocity now.
			PositionMoveRotation pmr = PositionMoveRotation.of(npc);
			Utils.broadcastPacket(ClientboundTeleportEntityPacket.teleport(npc.getId(), pmr, EnumSet.noneOf(net.minecraft.world.entity.Relative.class), npc.onGround()));
			Utils.broadcastPacket(new ClientboundSetEntityMotionPacket(npc.getId(), Vec3.ZERO));
		}

		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F);
		Utils.debug(Utils.DebugType.CLIENT, p.getName() + " leaped to " + target.getName() + " at " + Utils.round(targetLoc.getX(), 3) + " " + Utils.round(targetLoc.getY(), 5) + " " + Utils.round(targetLoc.getZ(), 3));
	}

	/**
	 * Arrow with no random spread. {@code launchProjectile} and the vanilla bow go through
	 * {@code shootFromRotation} with inaccuracy 1.0F; this uses NMS {@code shoot(..., 0)}, like
	 * {@code items.bows.Terminator}'s shotgun arrows.
	 * <br>
	 * Spawn (with vanilla's -0.1 Y) and direction both come from {@code aimFrom}, so delayed bonus arrows reuse the
	 * fire-time aim rather than where the shooter moved.
	 */
	public static Arrow fireDeterministicArrow(Player p, Location aimFrom, float speed, double damage) {
		ServerLevel nmsWorld = ((CraftWorld) p.getWorld()).getHandle();
		ServerPlayer nmsPlayer = ((CraftPlayer) p).getHandle();
		Vector dir = aimFrom.getDirection();

		net.minecraft.world.entity.projectile.arrow.Arrow nmsArrow = new net.minecraft.world.entity.projectile.arrow.Arrow(
				nmsWorld, 0, 0, 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
		nmsArrow.setPos(aimFrom.getX(), aimFrom.getY() - 0.1, aimFrom.getZ());
		nmsArrow.shoot(dir.getX(), dir.getY(), dir.getZ(), speed, 0);
		nmsArrow.setOwner(nmsPlayer);
		nmsWorld.addFreshEntity(nmsArrow);

		Arrow arrow = (Arrow) nmsArrow.getBukkitEntity();
		arrow.setDamage(damage);
		arrow.setShooter(p);
		arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
		arrow.setWeapon(p.getInventory().getItemInMainHand());
		return arrow;
	}

/**
	 * Sets up the player's initial location by resetting their velocity, teleporting them
	 * to the specified location, and updating the location of any spectators.
	 *
	 * @param player   The player whose initial location is being set.
	 * @param location The location where the player will be teleported.
	 */
	public static void teleport(Player player, Location location) {
		player.setVelocity(new Vector(0, 0, 0));
		player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
	}

}
