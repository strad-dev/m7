package items;

import net.minecraft.nbt.CompoundTag;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import plugin.ClassKits;
import plugin.Utils;

import java.util.function.Consumer;

/**
 * The three ways an {@code Item} builds its stack.
 * <p>
 * All DELEGATE to the existing builders, which are still called from outside {@code items/} (Energy Crystal,
 * SkyBlock Menu, Shadow Assassin Boots). A second copy of the NBT/lore assembly would drift and saved loadouts
 * would stop matching their template.
 * <p>
 * The two name paths differ ({@code Utils.mm} wraps in {@code <!italic>}; leather round-trips through legacy) and
 * stay separate on purpose: switching would rewrite every saved copy on the next refresh. Harmless, but churn.
 */
public final class ItemFactory {
	private ItemFactory() {}

	/** {@code loreId} on lore line 0, SkyBlock {@code id} in NBT. */
	public static ItemStack item(Material material, String name, String loreId, String skyblockId) {
		return ClassKits.getSkyBlockItem(material, name, loreId, skyblockId);
	}

	/** For items needing more NBT than a bare {@code id} (AOTV). */
	public static ItemStack item(Material material, String name, String loreId, Consumer<CompoundTag> nbt) {
		return ClassKits.getSkyBlockItem(material, name, loreId, nbt);
	}

	/**
	 * {@code identifier} seeds the profile UUID and must be STABLE: a random one made each copy byte-different,
	 * which listed shared heads once per class in the palette.
	 */
	public static ItemStack head(String name, String identifier, String texture, String signature) {
		return ClassKits.getCustomHead(name, identifier, texture, signature);
	}

	/** No lore ID, so {@code StatLore} leaves lore line 0 blank. */
	public static ItemStack leather(Material material, Color colour, String name) {
		return Utils.createLeatherArmor(material, colour, Utils.mmLegacy(name));
	}
}
