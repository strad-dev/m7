package listeners;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.*;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import plugin.M7;
import plugin.MovementAudit;
import plugin.Utils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LavaJump {
	/** Hypixel: pitch <= -40 (looking steeply up) gives the super bounce, anything else the normal one. */
	private static final float SUPER_BOUNCE_PITCH = -40.0F;
	private static final double SUPER_LAUNCH_VELOCITY = 3.0375D;
	private static final double NORMAL_LAUNCH_VELOCITY = 2.25D;
	private static final int RELAUNCH_COOLDOWN_TICKS = 10;
	/** Rise above the launch position that proves the client applied the bounce. Smallest launch is 2.25 and a
	 *  player sitting in lava only sinks, so half a block can't be anything else. */
	private static final double RESPONSE_RISE = 0.5D;
	/** Ticks to wait for that rise before bouncing anyway: covers a bad round trip, but a bounce the client never
	 *  applied can't strand someone in lava. */
	private static final int RESPONSE_TIMEOUT_TICKS = 60;
	/** LivingEntity#getFluidJumpThreshold: 0.4 for eye height >= 0.4. Lava height <= this is shallow
	 *  (travelInLava vertical x0.8, big); above is deep (x0.5, small). */
	private static final double PLAYER_FLUID_JUMP_THRESHOLD = 0.4D;

	// Goldor boss arena bounds: -8 254 -8 to 134 0 147
	private static final double MIN_X = -8, MAX_X = 134;
	private static final double MIN_Y = 0, MAX_Y = 254;
	private static final double MIN_Z = -8, MAX_Z = 147;

	public static boolean isInBossArena(Location loc) {
		return loc.getX() >= MIN_X && loc.getX() <= MAX_X
				&& loc.getY() >= MIN_Y && loc.getY() <= MAX_Y
				&& loc.getZ() >= MIN_Z && loc.getZ() <= MAX_Z;
	}

	/** Last bounce served: tick, height they must clear to prove the client applied it, and the contact block. */
	private record Launch(int tick, double y, int blockX, int blockY, int blockZ) {}

	private static final Map<UUID, Launch> lastLaunch = new HashMap<>();
	private static BukkitTask poller;

	public static void start() {
		if(poller != null) return;
		poller = new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	public static void stop() {
		if(poller != null) {
			poller.cancel();
			poller = null;
		}
		lastLaunch.clear();
	}

	private static void tick() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			check(p);
		}
	}

	/**
	 * Whether a player still in lava may be bounced again.
	 * <p>
	 * The cooldown alone wasn't enough: real players' movement is client-authoritative, so {@code p.getLocation()}
	 * sits in the launch block for the whole round trip of a stalled client, and {@link #RELAUNCH_COOLDOWN_TICKS}
	 * expires inside it. Live report: 8 ticks of silence at 371 ms, a second 3.038 sent 10 ticks after the first,
	 * the arcs stacking as a 5.390 rise in one tick, and StradDevHub's envelope check killed the player (rightly).
	 * <p>
	 * So the wait is on evidence: answered once they're above the launch height, which only a bounce does. A new
	 * block is a new contact, and {@link #RESPONSE_TIMEOUT_TICKS} stops an unapplied bounce stranding them.
	 */
	private static boolean mayRelaunch(Location loc, Launch last, int now) {
		int age = now - last.tick();
		if(age < RELAUNCH_COOLDOWN_TICKS) return false;
		if(loc.getBlockX() != last.blockX() || loc.getBlockY() != last.blockY() || loc.getBlockZ() != last.blockZ()) {
			return true;
		}
		return loc.getY() > last.y() + RESPONSE_RISE || age >= RESPONSE_TIMEOUT_TICKS;
	}

	private static void check(Player p) {
		if(p.getGameMode() == GameMode.SPECTATOR) return;
		if(p.isFlying()) return;
		Location loc = p.getLocation();
		UUID id = p.getUniqueId();

		boolean touchingLava = isInBossArena(loc) && loc.getBlock().getType() == Material.LAVA;

		if(!touchingLava) {
			lastLaunch.remove(id);
			return;
		}

		int now = MinecraftServer.currentTick;
		Launch last = lastLaunch.get(id);
		if(last != null && !mayRelaunch(loc, last, now)) return;
		lastLaunch.put(id, new Launch(now, loc.getY(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));

		Utils.debug(Utils.DebugType.SERVER, p.getName() + (last == null ? " lava contact" : " still-in-lava rebounce") + " detected at Y=" + Utils.round(loc.getY(), 5));
		Utils.playLocalSound(p, Sound.ENTITY_PLAYER_HURT, 1.0F, 1.0F);

		ServerPlayer npc = ((CraftPlayer) p).getHandle();
		Utils.scheduleTask(() -> {
			float pitch = npc.getXRot();
			boolean superBounce = pitch <= SUPER_BOUNCE_PITCH;
			double launch = superBounce ? SUPER_LAUNCH_VELOCITY : NORMAL_LAUNCH_VELOCITY;

			Vec3 m = npc.getDeltaMovement();
			npc.setDeltaMovement(new Vec3(m.x(), launch, m.z()));
			npc.hurtMarked = true;
			MovementAudit.startAirborneAudit(p, "lavajump");

			Utils.debug(Utils.DebugType.SERVER, p.getName() + (superBounce ? " SUPER" : " normal") + " bounce (pitch "
					+ Utils.round(pitch, 2) + ") launched at " + launch);

			// Classify big/small from lava DEPTH, vanilla's own test (LivingEntity.travelInLava: getFluidHeight(LAVA)
			// <= getFluidJumpThreshold()). The 26.2 server no longer reflects a real player's dragged velocity, so the
			// old post-launch getDeltaMovement().y always looked "big". Depth is server-authoritative.
			double lavaHeight = npc.getFluidHeight(net.minecraft.tags.FluidTags.LAVA);
			String kind = lavaHeight <= PLAYER_FLUID_JUMP_THRESHOLD ? "big" : "small";
			Utils.debug(Utils.DebugType.SERVER, p.getName() + " lava launched (lava height " + Utils.round(lavaHeight, 4) + ") classified " + kind);
		}, 1);
	}
}
