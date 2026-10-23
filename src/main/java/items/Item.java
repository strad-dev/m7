package items;

import damage.ItemDef;
import damage.Rarity;
import damage.ReforgeId;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import plugin.Utils;

import java.util.List;

/**
 * Superinterface of every custom item. One implementation per BASE item holds its identity, stack factory and
 * (via subinterfaces) behaviour, so an item is one file instead of four scattered entries.
 *
 * <h2>Reforge is a PARAMETER</h2>
 * Heroic and Withered Hyperion are one {@code Item} at two {@link ReforgeId}s (MAP.md §2.4). The display name is
 * COMPOSED from the reforge, so "any reforge on any item" is a change to {@link #reforges()}, which is hardcoded
 * per item for now.
 *
 * <h2>Colour is DERIVED from rarity</h2>
 * {@link #colouredName} is {@code <effectiveRarity().colour()>} plus the name, so an item can't show the wrong
 * tier. The four Renowned rarities and the Diamond Necron Head's were fixed in {@code damage/Items} for this.
 *
 * <h2>Two silent-break invariants</h2>
 * <ol>
 *   <li><b>Item ID stays on lore line 0.</b> {@code ItemUtils.getID} reads it and {@code Catalog.paletteKey}
 *       ({@code material | plain name | first lore line}) is what both {@code loadout/ItemRefresh} copies match
 *       saved loadouts on; {@code damage/StatLore} appends BELOW it. Move it and every saved loadout stops
 *       updating, with no migration.</li>
 *   <li><b>Material and plain name are identity.</b> A name change needs a permanent {@code ItemRefresh.RENAMED}
 *       entry in M7's copy AND StradDevHub's; a material change has no migration. Colour is free.</li>
 * </ol>
 */
public interface Item {

	// ===== identity =====

	/**
	 * Written as lore line 0. Empty for armour, wearable heads, SkyBlock Menu and Pitchin' Rod, which keeps their
	 * palette key's third component at {@code ""} as it always was.
	 */
	default String loreId() {
		return "";
	}

	Material material();

	/**
	 * No colour, no reforge word: {@code "Hyperion"}.
	 * <p>
	 * Lookalikes count: the Ragnarock Axe's has a Greek omicron (U+03BF), on purpose, so it stops colliding with a
	 * client mod. Wrong spelling drops it to the palette tail and freezes every saved copy. Grep
	 * {@code Ragnar\x{03BF}ck}, never type it.
	 */
	String baseName();

	/** Read {@link #effectiveRarity} instead; recombobulation is assumed on. */
	Rarity baseRarity();

	/**
	 * Everything is (§1.0.9). The two special tiers stay unrecombobulated via {@link Rarity#recombobulated()}.
	 */
	default boolean recombobulated() {
		return true;
	}

	/** What the item is drawn at, and what the reforge/gemstone tables key on. */
	default Rarity effectiveRarity() {
		return recombobulated() ? baseRarity().recombobulated() : baseRarity();
	}

	// ===== reforges =====

	ReforgeId defaultReforge();

	/**
	 * Every variant the palette offers. One for almost everything; Hyperion is {@code [HEROIC, FABLED]} and three
	 * Storm's pieces have an alternate-reforge twin.
	 */
	default List<ReforgeId> reforges() {
		return List.of(defaultReforge());
	}

	// ===== names =====

	/** PLAIN name at a reforge: the {@code damage/Items} key and {@code paletteKey} component. */
	default String displayName(ReforgeId reforge) {
		String prefix = reforge == null ? "" : reforge.displayName();
		return prefix.isEmpty() ? baseName() : prefix + " " + baseName();
	}

	default String displayName() {
		return displayName(defaultReforge());
	}

	/** MiniMessage: effective rarity colour + plain name. */
	default String colouredName(ReforgeId reforge) {
		return "<" + effectiveRarity().colour() + ">" + displayName(reforge);
	}

	default String colouredName() {
		return colouredName(defaultReforge());
	}

	// ===== stacks =====

	/** A FRESH stack. Must end with {@code damage.StatLore.apply}. */
	ItemStack build(ReforgeId reforge);

	default ItemStack build() {
		return build(defaultReforge());
	}

	/** In {@link #reforges()} order. The palette and templates are built from this. */
	default List<ItemStack> variants() {
		return reforges().stream().map(this::build).toList();
	}

	// ===== stats =====

	/** Null if it grants none. {@code damage/Items} stays the one place item NUMBERS live, keyed on plain name. */
	default ItemDef stats(ReforgeId reforge) {
		return damage.Items.byName(displayName(reforge));
	}

	// ===== click plumbing =====
	// Here, not on AbilityItem, because not every item needing them is an ability item (Infinileap is a menu,
	// Spring Boots a wearable).

	/**
	 * Right-click cancels the interact, suppressing vanilla use (bow draw, TNT place, Infinileap pearl). False only
	 * for Gyrokinetic Wand and Dungeonbreaker, which must still touch the world.
	 * <p>
	 * Only consulted for items with a lore ID; armour and heads have always gone through untouched.
	 */
	default boolean cancelsInteract() {
		return true;
	}

	/** Let right-clicking an ENTITY through (item frames etc). */
	default boolean allowsEntityInteract() {
		return false;
	}

	/**
	 * LEFT click is an ability, not a swing: NEVER break a block (even on cooldown or tick-capped) and never melee.
	 * The mage beam adds to this at runtime, since it depends on the holder's class.
	 */
	default boolean suppressesBlockBreak() {
		return false;
	}

	/**
	 * Works in the Trap room, where the clear phase disables right-click abilities. Only the Dungeonbreaker (and
	 * vanilla ender pearls, not an {@code Item}).
	 */
	default boolean usableInTrapRoom() {
		return false;
	}

	/** Right-click still works for a watcher ({@code Utils.WATCHING_TAG}), boss arena and Trap room included. The AOTV and the Hyperion (teleport only). */
	default boolean usableWhileWatching() {
		return false;
	}

	// ===== matching =====

	/** Any reforge. Material + plain name, like paletteKey. */
	default boolean matches(ItemStack stack) {
		if(stack == null || stack.getType() != material() || !stack.hasItemMeta()) return false;
		ItemMeta meta = stack.getItemMeta();
		if(meta == null) return false;
		String name = Utils.plain(meta.displayName());
		for(ReforgeId reforge : reforges()) {
			if(displayName(reforge).equals(name)) return true;
		}
		return false;
	}
}
