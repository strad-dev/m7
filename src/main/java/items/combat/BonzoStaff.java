package items.combat;

import damage.Rarity;
import damage.ReforgeId;
import items.AbilityItem;
import items.Cast;
import items.ItemFactory;
import items.Weapon;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Fires a vanilla wind charge and records its fire tick so {@code MiscListener} and {@code Infinileap.leap} can age it.
 */
public final class BonzoStaff implements Weapon, AbilityItem {
	public static final BonzoStaff INSTANCE = new BonzoStaff();

	private BonzoStaff() {}

	@Override
	public String loreId() {
		return "skyblock/combat/bonzo";
	}

	@Override
	public Material material() {
		return Material.BREEZE_ROD;
	}

	@Override
	public String baseName() {
		return "Bonzo Staff";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.RARE;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.HEROIC;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "STARRED_BONZO_STAFF");
	}

	@Override
	public boolean mageBeams() {
		return true;
	}

	@Override
	public boolean hasRightClick() {
		return true;
	}

	@Override
	public boolean usableWhileWatching() {
		return true;
	}

	@Override
	public boolean onRightClick(Cast cast) {
		bonzo(cast.player());
		return true;
	}

	public static final Map<Integer, Integer> bonzoFireTick = new HashMap<>();

	public static void bonzo(Player p) {
		// Bad Bonzo: Hypixel's charge spawns a tick late, from where the player is by then.
		if(plugin.BadBonzo.enabled()) {
			plugin.Utils.scheduleTask(() -> {
				if(p.isOnline()) spawnCharge(p);
			}, 1);
			return;
		}
		spawnCharge(p);
	}

	private static void spawnCharge(Player p) {
		Location l = p.getEyeLocation();
		WindCharge windCharge = (WindCharge) l.getWorld().spawnEntity(l, EntityType.WIND_CHARGE);
		windCharge.addScoreboardTag("Bonzo");
		windCharge.setShooter(p);
		makeUndeflectable(windCharge);
		bonzoFireTick.put(windCharge.getEntityId(), MinecraftServer.currentTick);
	}

	/**
	 * Make the charge unpunchable for its whole flight.
	 * <p>
	 * Vanilla {@code Player.attack} deflects anything in {@code minecraft:redirectable_projectile} along the
	 * puncher's aim ({@code AIM_DEFLECT}) <b>and re-owns it to them</b>, so the launch no longer goes where aimed.
	 * <p>
	 * The switch is vanilla's own: {@code WindCharge.noDeflectTicks} refuses every deflection while positive, and a
	 * player-thrown charge starts at 5. Bukkit's {@code (type, level)} constructor leaves it at 0, which is why ours
	 * was punchable from the first tick. Extending it covers EVERY deflection path, not just the punch.
	 * <p>
	 * Reflection (private field), cached like {@code loadout.SpectatorGuiAccess}; a failed lookup is remembered.
	 * Verified on 26.2 with {@code javap}: {@code deflect} is {@code noDeflectTicks <= 0 && super.deflect(...)}.
	 * <p>
	 * Not synched, so the client still predicts the deflection: see {@code MiscListener.onPunchBonzoCharge}.
	 */
	private static void makeUndeflectable(WindCharge charge) {
		Field f = noDeflectField();
		if(f == null) return;
		try {
			f.setInt(((CraftEntity) charge).getHandle(), Integer.MAX_VALUE);
		} catch(IllegalAccessException | IllegalArgumentException | ClassCastException ignored) {
			// Can't normally happen after a good lookup; not worth logging.
		}
	}

	private static Field noDeflectTicks;
	private static boolean noDeflectUnavailable;

	private static Field noDeflectField() {
		if(noDeflectTicks == null && !noDeflectUnavailable) {
			try {
				noDeflectTicks = net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge.class
						.getDeclaredField("noDeflectTicks");
				noDeflectTicks.setAccessible(true);
			} catch(NoSuchFieldException | RuntimeException e) {
				noDeflectUnavailable = true;
			}
		}
		return noDeflectTicks;
	}
}
