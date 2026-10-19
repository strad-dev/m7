package instructions.bosses;

import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import plugin.M7;
import plugin.Utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class BossMotion {
	private BossMotion() {}

	private static final Set<BukkitRunnable> forceMoveTasks = new HashSet<>();

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
	public static void cancelAll() {
		for(BukkitRunnable runnable : new ArrayList<>(forceMoveTasks)) runnable.cancel();
		forceMoveTasks.clear();
	}

	/** Rotates the boss to the given yaw/pitch. */
	public static void turnHead(Entity entity, float yaw, float pitch) {
		Location to = entity.getLocation();
		to.setYaw(yaw);
		to.setPitch(pitch);
		entity.teleport(to);
	}
}
