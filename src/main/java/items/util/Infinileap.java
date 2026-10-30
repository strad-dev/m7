package items.util;

import damage.Rarity;
import damage.ReforgeId;
import items.ItemFactory;
import items.MenuItem;
import listeners.SpiritLeapMenu;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import plugin.Cooldowns;
import plugin.MovementAudit;
import plugin.Utils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;

/**
 * An ender pearl that must never be thrown (leaps go through {@link #leap}): interact cancel first,
 * {@code listeners/PearlHelper} as backstop. Right-click opens Spirit Leap, gated by
 * {@code listeners/SpiritLeapListener} on practice mode and someone to leap to.
 */
public final class Infinileap implements MenuItem {
	public static final Infinileap INSTANCE = new Infinileap();

	private Infinileap() {}

	@Override
	public String loreId() {
		return "skyblock/utility/infinileap";
	}

	@Override
	public Material material() {
		return Material.ENDER_PEARL;
	}

	@Override
	public String baseName() {
		return "Infinileap";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.EPIC;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.NONE;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "INFINITE_SPIRIT_LEAP");
	}

	public static final int SHORT_COOLDOWN_TICKS = 40;
	public static final int LONG_COOLDOWN_TICKS = 100;
	private static final String SHORT_COOLDOWN_KEY = "skyblock/utility/infinileap";
	private static final String LONG_COOLDOWN_KEY = "skyblock/utility/infinileap/long";

	@Override
	public void open(Player p) {
		if(onCooldown(p)) return;
		SpiritLeapMenu.open(p);
	}

	private static boolean onCooldown(Player p) {
		int shortLeft = Cooldowns.remaining(p, SHORT_COOLDOWN_KEY);
		int longLeft = Cooldowns.remaining(p, LONG_COOLDOWN_KEY);
		if(shortLeft <= 0 || longLeft <= 0) return false;
		items.ItemUtils.sendCooldownMessage(p, Math.min(shortLeft, longLeft));
		return true;
	}

	// The leap spends it, not opening. Counts as both leap types: the 2s one if ready, else the 5s one.
	private static void startCooldown(Player p) {
		if(Cooldowns.ready(p, SHORT_COOLDOWN_KEY)) {
			Cooldowns.start(p, SHORT_COOLDOWN_KEY, items.ItemUtils.effectiveCooldown(p, SHORT_COOLDOWN_TICKS));
		} else {
			Cooldowns.start(p, LONG_COOLDOWN_KEY, items.ItemUtils.effectiveCooldown(p, LONG_COOLDOWN_TICKS));
		}
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
		if(onCooldown(p)) return;
		startCooldown(p);

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
		Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " leaped to " + target.getName() + " at " + Utils.round(targetLoc.getX(), 3) + " " + Utils.round(targetLoc.getY(), 5) + " " + Utils.round(targetLoc.getZ(), 3));
	}

	private static void teleport(Player player, Location location) {
		player.setVelocity(new Vector(0, 0, 0));
		player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
	}
}
