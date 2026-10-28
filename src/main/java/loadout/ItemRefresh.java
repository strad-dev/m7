package loadout;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import plugin.Catalog;

import java.util.*;

/**
 * Keeps a SAVED loadout in step with CURRENT item definitions.
 * <p>
 * Saved loadouts hold frozen copies from when the player picked them, so later item changes (lore, attribute
 * modifiers, {@code can_break}, stat blocks) would never reach them. On every editor open and kit hand-out each
 * saved stack is looked up in the live catalog and REPLACED by today's definition. No migration scripts needed;
 * just change the item factory.
 * <p>
 * <b>Invariant:</b> {@link #key} is material + display name + item ID, and the ID is the FIRST lore line
 * ({@code items.ItemUtils.getID()} reads {@code getLore().getFirst()}). Appending lore is fine. Prepending above
 * the ID, or moving it out of lore, silently breaks matching: every saved item is left frozen (see
 * {@link #refresh}) and stops getting updates. Keep the ID on line 0.
 * <p>
 * Template supplies the definition; the saved stack keeps its amount and enchants (applied on top, saved level
 * wins). That keeps a Tank's Power 17 Terminator from becoming the palette's Archer Power 70 copy.
 * <p>
 * The network plugin has a twin ({@code loadout/ItemRefresh.java}) for servers M7 isn't on: same key rule, same
 * {@link #RENAMED} table, but templates come from the exported JSON instead of live factories.
 * <b>Keep the two in sync.</b>
 */
public final class ItemRefresh {
	private ItemRefresh() {}

	/**
	 * Renamed display names, old -> new. {@link #key} includes the name, so a rename would leave saved items frozen
	 * with the old label forever. Each entry is a pure rename (same material and ID), so the old key is rewritten
	 * and matched normally.
	 * <p>
	 * Permanent: someone who hasn't opened the editor since the rename is still out there, so <b>don't prune</b>.
	 * Add a line on every rename, here AND in the network plugin's copy.
	 */
	private static final Map<String, String> RENAMED = Map.of(
			"Withered Golem Sword", "Suspicious Golem Sword",
			"Withered Axe of the Shredded", "Suspicious Axe of the Shredded",
			"Withered Ragnarok Axe", "Withered Ragnarοck Axe",
			"Withered Ragnarock Axe", "Withered Ragnarοck Axe",   // plain ASCII o -> the Greek omicron (U+03BF) it now uses
			"Bonzo Staff", "Heroic Bonzo Staff");

	/**
	 * Display names of deleted items to CLEAR from saved loadouts, leaving the slot empty.
	 * <p>
	 * Exception to {@link #refresh}'s "no template, leave it alone" rule. That rule is right for an item merely
	 * absent from the palette (it may come back), wrong for one deleted outright: the player would carry a dead
	 * stack forever.
	 * <p>
	 * Matched on display name alone (any material), so only list names that are unmistakably one item. Permanent
	 * like {@link #RENAMED}, and mirrored in the network plugin's copy.
	 * <ul>
	 *   <li>Rapid Bonemerang: deleted from the Archer kit. Its throw was never implemented.</li>
	 * </ul>
	 */
	private static final Set<String> REMOVED = Set.of("Rapid Bonemerang");

	/**
	 * Materials of deleted items with NO display name, which {@link #REMOVED} can't match (their {@link #key} is
	 * {@code MATERIAL||}; listing {@code ""} would clear every unnamed item).
	 * <p>
	 * Matched on material alone, so only list a material NO current item uses: check the palette and every default
	 * kit in {@code ClassKits} first. Permanent and mirrored in the network copy, like the tables above.
	 * <ul>
	 *   <li>SOUL_SAND: old lava-jump block, bare stack with a {@code can_place_on} stamp, no name or ID. Dropped
	 *       when slot 34 became the Heroic Jerry-chine Gun.</li>
	 * </ul>
	 */
	private static final Set<Material> REMOVED_MATERIALS = Set.of(Material.SOUL_SAND);

	/** Refresh every saved class, re-saving if anything changed. Returns slots changed (0 if nothing saved). */
	public static int refreshSaved(UUID uuid) {
		Map<String, ItemStack> templates = templates();
		if(templates.isEmpty()) return 0;
		Loadouts.LoadoutFile f = Loadouts.load(uuid);
		int changed = 0;
		for(Map.Entry<String, List<String>> e : f.perClass.entrySet()) {
			ItemStack[] arr = Loadouts.fromSer(e.getValue());
			int n = refreshAll(arr, templates);
			if(n > 0) {
				e.setValue(Loadouts.toSer(arr));
				changed += n;
			}
		}
		if(changed > 0) Loadouts.save(uuid, f);
		return changed;
	}

	/** Refresh a 41-slot array in place; returns slots changed. */
	public static int refreshAll(ItemStack[] arr, Map<String, ItemStack> templates) {
		if(arr == null || templates.isEmpty()) return 0;
		int changed = 0;
		for(int i = 0; i < arr.length; i++) {
			if(isRemoved(arr[i])) { // deleted item: empty the slot
				arr[i] = null;
				changed++;
				continue;
			}
			ItemStack fresh = refresh(arr[i], templates);
			if(fresh != null) {
				arr[i] = fresh;
				changed++;
			}
		}
		return changed;
	}

	/** True if a deleted item: by name ({@link #REMOVED}) or, if unnamed, by material ({@link #REMOVED_MATERIALS}). */
	public static boolean isRemoved(ItemStack it) {
		if(it == null || it.getType().isAir()) return false;
		if(REMOVED_MATERIALS.contains(it.getType())) return true;
		String k = key(it);
		String name = k == null ? null : nameIn(k);
		return name != null && REMOVED.contains(name);
	}

	/**
	 * Current version of a saved item, or null if already current or no template. An item the catalog no longer
	 * offers is left as is, so it doesn't silently eat the player's slot.
	 */
	public static ItemStack refresh(ItemStack saved, Map<String, ItemStack> templates) {
		String k = key(saved);
		ItemStack tmpl = k == null ? null : templates.get(k);
		// Miss: saved copy may predate a rename, retry under the current name.
		if(tmpl == null && k != null) {
			String renamed = renamedKey(k);
			if(renamed != null) tmpl = templates.get(renamed);
		}
		if(tmpl == null) return null;

		ItemStack fresh = tmpl.clone();
		ItemMeta fm = fresh.getItemMeta();
		if(fm == null || !fm.hasEnchantmentGlintOverride()) fresh.addUnsafeEnchantments(saved.getEnchantments());
		fresh.setAmount(saved.getAmount());
		// isSimilar skips amount, which was just copied, so this is a full compare.
		return saved.isSimilar(fresh) ? null : fresh;
	}

	/**
	 * Current version of every loadout item, keyed by {@link #key}. Palette first (canonical copies), then every
	 * class's default kit, which supplies what the palette withholds (SkyBlock Menu) and each class's Terminator Power.
	 */
	public static Map<String, ItemStack> templates() {
		Map<String, ItemStack> out = new HashMap<>();
		for(ItemStack it : Catalog.palette()) put(out, it);
		for(String role : Loadouts.CLASSES) {
			for(ItemStack it : Catalog.defaultFor(role)) put(out, it);
		}
		return out;
	}

	private static void put(Map<String, ItemStack> out, ItemStack it) {
		String k = key(it);
		if(k != null) out.putIfAbsent(k, it); // first source wins: palette is canonical
	}

	/**
	 * Template-matching identity: material + display name + item ID (first lore line). Same as
	 * {@code Catalog.paletteKey} on an arbitrary stack; change both, plus the network copy. Null for an empty slot.
	 */
	public static String key(ItemStack it) {
		if(it == null || it.getType().isAir()) return null;
		return Catalog.paletteKey(it);
	}

	/**
	 * Display-name segment of a {@link #key}, or null if not a key. Key is {@code MATERIAL|display name|item id};
	 * material and ID never contain {@code |}, so first and last separators bracket the name.
	 */
	private static String nameIn(String key) {
		int start = key.indexOf('|'), end = key.lastIndexOf('|');
		return start < 0 || end < start ? null : key.substring(start + 1, end);
	}

	/** Key with its name swapped for the current name, or null if never renamed. */
	private static String renamedKey(String key) {
		String name = nameIn(key);
		String now = name == null ? null : RENAMED.get(name);
		if(now == null) return null;
		return key.substring(0, key.indexOf('|') + 1) + now + key.substring(key.lastIndexOf('|'));
	}
}
