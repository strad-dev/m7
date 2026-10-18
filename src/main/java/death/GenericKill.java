package death;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.persistence.PersistentDataType;
import plugin.M7;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /kill} on a runner mid-run, or any other GENERIC_KILL (an admin, the network's AFK check), is a run death in
 * every mode: ghost, revival, wipe ({@link Deaths#forceKill}). A ghost is spared outright, or vanilla would kill the
 * spectator and respawn them out of ghost state. Outside a run ({@link Deaths#inRun}) vanilla's /kill stands; the
 * network's anticheat ends the practice first so its kill is a real death screen.
 * <p>
 * Killer in the death line: whoever typed the {@code /kill} ({@link #pending}), else {@link #KILLER_KEY}, else
 * "Unknown".
 */
public final class GenericKill implements Listener {
	/** Plain-text PDC string naming a non-command killer. Set by the caller around the damage call; the network's AFK
	 *  check uses it. */
	public static final NamespacedKey KILLER_KEY = new NamespacedKey("m7", "killer");
	private static final String DEFAULT_KILLER = "Unknown";

	/** {@code /kill} targets resolved this tick -> who typed it. The damage lands inside the same dispatch. */
	private static final Map<UUID, Component> pending = new HashMap<>();

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
		noteKill(e.getPlayer(), e.getMessage());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onServerCommand(ServerCommandEvent e) {
		noteKill(e.getSender(), e.getCommand());
	}

	private static void noteKill(CommandSender sender, String line) {
		String[] parts = line.trim().split("\\s+", 2);
		String label = parts[0].toLowerCase();
		if(label.startsWith("/")) label = label.substring(1);
		if(!label.equals("kill") && !label.equals("minecraft:kill")) return;
		List<Entity> targets;
		if(parts.length < 2) {
			targets = sender instanceof Entity self ? List.of(self) : List.of();
		} else {
			try {
				targets = Bukkit.selectEntities(sender, parts[1].trim());
			} catch(IllegalArgumentException ex) {
				return; // vanilla will reject it too
			}
		}
		Component killer = sender instanceof Player p ? Deaths.displayName(p) : Deaths.mob(sender.getName());
		boolean any = false;
		for(Entity t : targets) {
			if(t instanceof Player) {
				pending.put(t.getUniqueId(), killer);
				any = true;
			}
		}
		if(any) Bukkit.getScheduler().runTask(M7.getInstance(), pending::clear);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onKill(EntityDamageEvent e) {
		if(e.getCause() != EntityDamageEvent.DamageCause.KILL) return;
		if(!(e.getEntity() instanceof Player p)) return;
		Component killer = killer(p);
		if(!Deaths.inRun()) return;
		if(Deaths.isGhost(p)) {
			e.setCancelled(true);
			return;
		}
		if(!Deaths.appliesTo(p)) return;
		e.setCancelled(true);
		Deaths.forceKill(p, killer, null);
	}

	private static Component killer(Player p) {
		Component typed = pending.remove(p.getUniqueId());
		if(typed != null) return typed;
		return Deaths.mob(p.getPersistentDataContainer().getOrDefault(KILLER_KEY, PersistentDataType.STRING, DEFAULT_KILLER));
	}
}
