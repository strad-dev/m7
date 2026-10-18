package loadout;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import plugin.M7;

import java.lang.reflect.Field;

/**
 * Lets a SPECTATOR edit the loadout GUI without leaving spectator mode.
 *
 * <p>Vanilla {@code ServerGamePacketListenerImpl#handleContainerClick} checks {@code player.isSpectator()} and,
 * if true, resyncs the menu and returns BEFORE any Bukkit {@code InventoryClickEvent}, so no plugin hook can allow
 * it. Idle players on m7 sit in spectator ({@code M7Bridge.makeSpectator}), so {@code /m7loadout} opened there but
 * nothing could be moved.
 *
 * <p>This netty handler consumes the click packet and re-runs vanilla's {@code handleContainerClick} on the main
 * thread inside a silent server-side game-mode flip: {@link ServerPlayerGameMode} {@code gameModeForPlayer} is
 * written by reflection, never via {@code setGameModeForPlayer}/{@code changeGameModeForPlayer}, which broadcast
 * and fire the Bukkit event. {@code isSpectator()} reads false for that one call and the client is told nothing.
 * Otherwise the player stays a spectator: no gravity, collision, visibility change or
 * {@code PlayerGameModeChangeEvent}.
 *
 * <p>Flip and call MUST be one synchronous block. {@code handleContainerClick} starts with
 * {@code PacketUtils.ensureRunningOnSameThread}, which re-queues netty-thread work onto the main thread, so a
 * "clear" queued before {@code super.channelRead} and a "restore" after don't reliably sandwich it. Calling the
 * handler ourselves from a main-thread task avoids the ordering problem.
 *
 * <p>Consuming the packet is safe: nothing else on the pipeline wants container clicks ({@code tas_interceptor}
 * only touches movement, use and attack), and a non-spectator's click runs normally, minus the flip.
 *
 * <p>Installed only while a spectator has the editor open ({@link #install}/{@link #uninstall} from
 * {@link LoadoutEditor}), so normal players never carry it.
 *
 * <p>NOTE: the network plugin has an identical copy ({@code loadout/SpectatorGuiAccess.java}) for servers M7 isn't
 * on; keep in sync. Handler names differ on purpose so the two never collide on one pipeline.
 */
public final class SpectatorGuiAccess extends ChannelDuplexHandler {
	/** Not the network plugin's name ({@code straddev_spectator_gui}), since both may exist on m7. */
	private static final String HANDLER_NAME = "m7_spectator_gui";

	/** {@code ServerPlayerGameMode#gameModeForPlayer}, written directly so the flip never reaches the client. */
	private static Field gameModeField;
	private static boolean fieldUnavailable;

	private final Player player;

	private SpectatorGuiAccess(Player player) {
		this.player = player;
	}

	// ===== install / uninstall =====

	/** Arm the bypass for a spectator opening the editor. No-op if not in spectator. */
	public static void install(Player p) {
		if(p.getGameMode() != GameMode.SPECTATOR) return;
		if(field() == null) {
			// Mappings moved: leave vanilla alone rather than break the GUI, but say so. A silent no-op looks
			// exactly like "the editor is broken".
			M7.getInstance().getLogger().warning("Spectator GUI bypass unavailable: "
					+ "ServerPlayerGameMode#gameModeForPlayer not found. Spectators can't edit loadouts.");
			return;
		}
		try {
			Channel ch = channel(p);
			if(ch.pipeline().get(HANDLER_NAME) == null) {
				ch.pipeline().addBefore("packet_handler", HANDLER_NAME, new SpectatorGuiAccess(p));
			}
		} catch(Exception e) {
			M7.getInstance().getLogger().warning("Could not arm the spectator GUI bypass for "
					+ p.getName() + ": " + e);
		}
	}

	/** Always safe to call, whether or not the bypass was armed. */
	public static void uninstall(Player p) {
		try {
			Channel ch = channel(p);
			if(ch.pipeline().get(HANDLER_NAME) != null) ch.pipeline().remove(HANDLER_NAME);
		} catch(Exception ignored) {
			// channel already closed
		}
	}

	private static Channel channel(Player p) {
		return ((CraftPlayer) p).getHandle().connection.connection.channel;
	}

	// ===== interception =====

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		MinecraftServer server = MinecraftServer.getServer();
		if(msg instanceof ServerboundContainerClickPacket click && server != null) {
			// Consume and run vanilla's handler on the main thread with the spectator flag cleared for that call.
			// Class comment says why bracketing super.channelRead with two queued tasks doesn't work.
			server.execute(() -> handleAsNonSpectator(click));
			return;
		}
		super.channelRead(ctx, msg);
	}

	/** Main thread. Run the click as a non-spectator, then restore the real mode. */
	private void handleAsNonSpectator(ServerboundContainerClickPacket click) {
		ServerPlayer nms = handle();
		if(nms == null) return;
		GameType prev = nms.gameMode.getGameModeForPlayer();
		// Only flip if actually spectator right now: a practice pull may have moved them to adventure, and this
		// must never force them back into spectator.
		boolean flipped = prev == GameType.SPECTATOR;
		if(flipped) write(nms, GameType.ADVENTURE);
		try {
			nms.connection.handleContainerClick(click);
		} finally {
			if(flipped) write(nms, prev);
		}
	}

	private ServerPlayer handle() {
		return player.isOnline() ? ((CraftPlayer) player).getHandle() : null;
	}

	private static void write(ServerPlayer nms, GameType type) {
		Field f = field();
		if(f == null) return;
		try {
			f.set(nms.gameMode, type);
		} catch(IllegalAccessException ignored) {
			// setAccessible succeeded at lookup time; nothing useful to do here
		}
	}

	private static Field field() {
		if(gameModeField == null && !fieldUnavailable) {
			try {
				gameModeField = ServerPlayerGameMode.class.getDeclaredField("gameModeForPlayer");
				gameModeField.setAccessible(true);
			} catch(NoSuchFieldException | RuntimeException e) {
				fieldUnavailable = true;
			}
		}
		return gameModeField;
	}
}
