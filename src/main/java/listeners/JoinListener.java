package listeners;

import instructions.bosses.CustomBossBar;
import instructions.bosses.Watcher;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import nms.PlayerPacketInterceptor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BossBar;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftWither;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.lang.reflect.Field;

public class JoinListener implements Listener {
	/** Player setup, packet interceptor install and boss bars for a joiner. */
	@EventHandler
	public void onJoin(PlayerJoinEvent ev) {
		// Raw runTaskLater, NOT Utils.scheduleTask: this is join infrastructure (attributes, no-collision team,
		// interceptor install) and must not be tracked. A network-warped practicer joins and on the SAME tick
		// M7Bridge dispatches /m7practice -> runPractice -> Utils.cancelAllScheduled(), which would cancel this
		// pending task and leave no interceptor (no drop abilities, bow release or mob-melee mage beam).
		Bukkit.getScheduler().runTaskLater(plugin.M7.getInstance(), () -> {
			Player joiningPlayer = ev.getPlayer();

			applyPlayerSetup(joiningPlayer);

			try {
				Channel ch = getChannel(joiningPlayer);
				if (ch.pipeline().get("tas_interceptor") == null)
					ch.pipeline().addBefore("packet_handler", "tas_interceptor",
							new PlayerPacketInterceptor(joiningPlayer));
			} catch (Exception ex) {
				Bukkit.getLogger().warning("[M7] Could not inject interceptor for "
						+ joiningPlayer.getName() + ": " + ex.getMessage());
			}

			BossBar activeBossBar = CustomBossBar.getActiveBossBar();
			Wither activeWither = CustomBossBar.getActiveWither();

			if(activeBossBar != null && activeWither != null) {
				activeBossBar.addPlayer(joiningPlayer);

				// Hide the vanilla wither boss bar for them
				if(activeWither instanceof CraftWither) {
					try {
						WitherBoss nmsWither = ((CraftWither) activeWither).getHandle();
						ServerPlayer nmsPlayer = ((CraftPlayer) joiningPlayer).getHandle();

						nmsWither.bossEvent.removePlayer(nmsPlayer);
					} catch(Exception e) {
						Bukkit.getLogger().warning("Failed to remove vanilla wither bossbar for joining player: " + e.getMessage());
					}
				}
			}

			BossBar watcherBossBar = Watcher.getActiveBossBar();
			if(watcherBossBar != null) {
				watcherBossBar.addPlayer(joiningPlayer);
			}
		}, 1L);
	}

	/**
	 * Attributes and team a real practicer needs: no attack cooldown, no knockback of either kind, no fall damage, no
	 * burning, one-tick breaking, no collisions, class speed. Idempotent; re-applied on respawn too, since a respawn
	 * builds a fresh {@code ServerPlayer}.
	 */
	static void applyPlayerSetup(Player p) {
		// No attack cooldown.
		var attackSpeed = p.getAttribute(Attribute.ATTACK_SPEED);
		if(attackSpeed != null) attackSpeed.setBaseValue(100);

		// Full knockback resistance, so no armor needs to grant it.
		var knockback = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
		if(knockback != null) knockback.setBaseValue(1);

		// Explosion knockback (Necron's fireballs) is this separate attribute, not KNOCKBACK_RESISTANCE.
		var explosionKb = p.getAttribute(Attribute.EXPLOSION_KNOCKBACK_RESISTANCE);
		if(explosionKb != null) explosionKb.setBaseValue(1);

		// No fall damage.
		var safeFall = p.getAttribute(Attribute.SAFE_FALL_DISTANCE);
		if(safeFall != null) safeFall.setBaseValue(1024);

		// No fire ticks ever. BURNING_TIME multiplies every ignite (LivingEntity.igniteForTicks), so 0 means 0 ticks.
		// MiscListener.onPlayerCombust cancelling the event is only the server half: the client runs the same
		// lava-ignite path for itself (LavaFluid.entityInside, not server-gated) and would show fire for 15s after a
		// lava jump. BURNING_TIME is synced, so the client's copy is 0 too.
		var burningTime = p.getAttribute(Attribute.BURNING_TIME);
		if(burningTime != null) burningTime.setBaseValue(0);

		// Clear a burn in progress: while remainingFireTicks > 0, Entity.lavaIgnite takes its no-event branch, so a
		// stale burn re-arms on every lava contact.
		p.setFireTicks(0);

		// Instant breaking: BLOCK_BREAK_SPEED is the final multiplier on destroy speed (MINING_EFFICIENCY is additive
		// and needs a suitable tool), so 1024 breaks anything in one tick. Items with can_break that must not break
		// blocks cancel it with a -1024 modifier (Utils.placeAndBreakAnythingInAdventure). Dungeonbreaker ADDS 1024.
		var breakSpeed = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
		if(breakSpeed != null) breakSpeed.setBaseValue(1024);

		// No-collision team so players don't push each other.
		plugin.PlayerCollision.addToNoCollisionTeam(p);

		plugin.MaxSpeedSync.initSpeed(p);
	}

	/** This plugin's "spawn": in the Start room facing the first door. World spawn is above the map; never use it. */
	public static Location dungeonEntrance() {
		World w = Bukkit.getWorld("world");
		return w == null ? null : new Location(w, -120.5, 71, -183.5, 0.0f, 0.0f);
	}

	// Real players spawn at the entrance on join, or at the current boss phase's spawn (the entrance is out of bounds
	// there). LOWEST so a /m7practice on the same join (network plugin sending a practicer in) teleports afterwards
	// and wins.
	@EventHandler(priority = EventPriority.LOWEST)
	public void onJoinSpawn(PlayerJoinEvent ev) {
		Player jp = ev.getPlayer();
		if (plugin.Utils.isSpectator(jp)) return;              // don't yank spectators
		Location entrance = dungeonEntrance();
		if (entrance == null) return;
		String boss = commands.Practice.currentBossSection();
		jp.teleport(boss == null ? entrance : commands.Practice.spawn(entrance.getWorld(), boss));
	}

	// Respawn at the entrance too: vanilla's world spawn drops them into the boss arena mid-clear. Run deaths are
	// ghosts and never respawn; this is the network anticheat's vanilla kill and a /kill outside a run.
	@EventHandler
	public void onRespawn(PlayerRespawnEvent ev) {
		Player p = ev.getPlayer();
		Location entrance = dungeonEntrance();
		if (entrance != null) ev.setRespawnLocation(entrance);
		// Raw runTaskLater as in onJoin, and it must run after the respawn has placed the new player.
		Bukkit.getScheduler().runTaskLater(plugin.M7.getInstance(), () -> {
			if (p.isOnline()) applyPlayerSetup(p);
		}, 1L);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent ev) {
		Player p = ev.getPlayer();
		// Bank them on the run roster now, the last moment they're readable, or the group size shrinks under the
		// survivors (a duo losing a player at 64s used to record a 65s solo). No-op outside a run.
		instructions.bosses.WitherActions.noteInRun(p);
		// A ghost who logs out can't be revived; drop the pending revival.
		death.Deaths.onQuit(p);
		// Drop cached speed transition state so a relog re-evaluates.
		plugin.MaxSpeedSync.forget(p.getUniqueId());
		try {
			Channel ch = getChannel(p);
			if (ch.pipeline().get("tas_interceptor") != null)
				ch.pipeline().remove("tas_interceptor");
		} catch (Exception ignored) { /* channel already closed */ }
	}

	private static Channel getChannel(Player player) throws Exception {
		ServerPlayer nms = ((CraftPlayer) player).getHandle();
		Field connField = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
		connField.setAccessible(true);
		Connection conn = (Connection) connField.get(nms.connection);
		Field channelField = Connection.class.getDeclaredField("channel");
		channelField.setAccessible(true);
		return (Channel) channelField.get(conn);
	}
}