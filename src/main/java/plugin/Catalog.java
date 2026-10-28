package plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import damage.ItemDef;
import damage.ReforgeId;
import items.Item;
import items.ItemRegistry;
import items.armor.StormBoots;
import items.armor.StormChestplate;
import items.armor.StormLeggings;
import items.armor.WitherGoggles;
import items.bows.DeathBow;
import items.combat.GolemSword;
import items.combat.SpiritSceptre;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exports the item catalog to {@code ../data/m7-item-catalog.json} on enable, for the lobby loadout editor's
 * palette and default kits. M7 is the SOLE writer and source of truth; the network only reads it.
 * <br>
 * Format (matches the network plugin's reader):
 *   { "palette": [ "&lt;base64 item&gt;", ... ],
 *     "defaults": { "Archer": [ &lt;41 base64-or-null&gt; ], "Mage": [...], ... },
 *     "pets": { "GOLDEN_DRAGON": "&lt;base64 head&gt;", ... },
 *     "petSlots": [ 10, 11, ... ], "defaultPet": "GOLDEN_DRAGON",
 *     "autopet": [ { "name": "RUN_START", "displayName": "On Run Start", "icon": "OAK_DOOR",
 *                    "cycle": false }, ... ] }
 *
 * 41-slot layout: [0..35] main inventory, [36] helmet, [37] chestplate, [38] leggings, [39] boots, [40] off-hand
 * (ClassKits#classLoadoutContents).
 */
public final class Catalog {
	private static final String[] ROLES = {"Archer", "Berserk", "Healer", "Mage", "Tank"};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private Catalog() {}

	/**
	 * A class's default 41-slot kit, freshly built from the item factories, with the per-class Terminator Power.
	 * <br>
	 * This and {@link #palette()} are the in-memory catalog; {@link #export()} only serializes them and M7's editor
	 * reads them directly, so the two can't disagree and the editor works with an unwritable shared folder.
	 */
	public static ItemStack[] defaultFor(String role) {
		ItemStack[] arr = ClassKits.classLoadoutContents(role);
		int power = terminatorPower(role);
		if (power > 0 && arr[4] != null && arr[4].getType() == Material.BOW) {
			arr[4].addUnsafeEnchantment(Enchantment.POWER, power);
		}
		return arr;
	}

	/**
	 * Every editor item, fresh, in {@link #PALETTE_ORDER}.
	 * <br>
	 * Deduped on {@link #paletteKey}, not raw bytes: copies of one item differ byte-for-byte (per-class Terminator
	 * Power, formerly random head profile ids) and listed twice. Stable sort, so an unlisted item keeps discovery
	 * order in the tail (ROLES x slot, then extraPaletteItems).
	 */
	public static List<ItemStack> palette() {
		LinkedHashMap<String, ItemStack> byKey = new LinkedHashMap<>();
		for (String role : ROLES) {
			for (ItemStack it : defaultFor(role)) {
				if (it != null && !it.getType().isAir() && !hiddenFromPalette(it)) byKey.putIfAbsent(paletteKey(it), it);
			}
		}
		// Items in no default kit.
		for (ItemStack it : extraPaletteItems()) {
			if (it != null && !it.getType().isAir()) byKey.putIfAbsent(paletteKey(it), it);
		}
		return byKey.values().stream()
				.sorted(Comparator.comparingInt(Catalog::paletteRank))
				.collect(Collectors.toCollection(ArrayList::new));
	}

	public static void export() {
		try {
			CatalogFile f = new CatalogFile();
			f.defaults = new LinkedHashMap<>();
			for (String role : ROLES) f.defaults.put(role, toSer(defaultFor(role)));
			f.palette = new ArrayList<>();
			for (ItemStack it : palette()) {
				String b64 = ItemSerial.toB64(it);
				if (b64 != null) f.palette.add(b64);
			}
			f.pets = petIcons();
			f.petSlots = new ArrayList<>(pets.PetMenu.petSlots());
			f.defaultPet = pets.Pets.DEFAULT_PET.name();
			f.autopet = autopetTriggers();

			Path file = dataDir().resolve("m7-item-catalog.json");
			save(file, f);
			M7.getInstance().getLogger().info("Exported M7 item catalog (" + f.palette.size()
					+ " palette items) to " + file);
		} catch (Exception e) {
			M7.getInstance().getLogger().warning("Failed to export M7 item catalog: " + e);
		}
	}

	/**
	 * Every pet's ARRANGING icon, keyed on the {@code PetType} name the pets file stores.
	 * <p>
	 * The lobby's {@code /petloadout} has no {@code PetType}; exporting finished heads keeps each tooltip written once
	 * instead of in a second enum that would drift.
	 * <p>
	 * Not equipped, editing: the lobby only edits, and {@code equipped} only changes the glint, which the lobby sets
	 * itself from the file's {@code equipped} field.
	 */
	private static Map<String, String> petIcons() {
		Map<String, String> out = new LinkedHashMap<>();
		for (pets.PetType pet : pets.PetType.values()) {
			String b64 = ItemSerial.toB64(pet.icon(false, true));
			if (b64 != null) out.put(pet.name(), b64);
		}
		return out;
	}

	/**
	 * Autopet triggers in menu order, for the network's Autopet window. Only what a button needs: enum name (the
	 * pets file's rule key), label, icon, and whether it holds a cycle. Rules live in {@code pets/<uuid>.json}.
	 */
	private static List<CatalogFile.PetTrigger> autopetTriggers() {
		List<CatalogFile.PetTrigger> out = new ArrayList<>();
		for (pets.Autopet.Trigger t : pets.Autopet.Trigger.values()) {
			CatalogFile.PetTrigger e = new CatalogFile.PetTrigger();
			e.name = t.name();
			e.displayName = t.displayName();
			e.icon = t.icon().name();
			e.cycle = t.isCycle();
			out.add(e);
		}
		return out;
	}

	/** One entry per slot, null for empty. */
	private static List<String> toSer(ItemStack[] arr) {
		List<String> ser = new ArrayList<>(41);
		for (int i = 0; i < 41; i++) ser.add(ItemSerial.toB64(arr != null && i < arr.length ? arr[i] : null));
		return ser;
	}

	/**
	 * In default kits but not the palette. Just the SkyBlock Menu: no ability, and the network editor pins it to the
	 * last hotbar slot itself. (The abilityless Rapid Bonemerang was removed from the Archer kit instead.)
	 */
	private static boolean hiddenFromPalette(ItemStack it) {
		return items.util.SkyblockMenu.INSTANCE.matches(it);
	}

	/**
	 * Palette items in no default kit. The Storm pieces are alternate reforges of the Mage's, so the same class at a
	 * different {@code ReforgeId}.
	 */
	static List<ItemStack> extraPaletteItems() {
		return List.of(
				GolemSword.INSTANCE.build(),
				WitherGoggles.INSTANCE.build(),
				SpiritSceptre.INSTANCE.build(),
				DeathBow.INSTANCE.build(),
				StormChestplate.INSTANCE.build(ReforgeId.LOVING),
				StormLeggings.INSTANCE.build(ReforgeId.NECROTIC),
				StormBoots.INSTANCE.build(ReforgeId.NECROTIC));
	}

	/**
	 * Hand-picked palette order. Each block of nine is one editor ROW, four rows a page
	 * ({@code LoadoutEditor.PALETTE_COUNT}), so the fifth block is page 2; keep blocks nine long or later rows shift.
	 * <br>
	 * Entries are {@link #orderName}: PLAIN display name ({@code reforge + base name}), or the material for the
	 * nameless ender pearls. Renaming an item means renaming it here; {@link #verify()} warns at boot. Lookalikes
	 * count: the Ragnarock Axe's name has a Greek omicron (U+03BF), not an ASCII o.
	 * <br>
	 * Unlisted items sort to the end in discovery order (empty today). Fabled displays as {@code Withered} on purpose
	 * (MAP.md §1.0.6).
	 */
	private static final List<String> PALETTE_ORDER = List.of(
			// Row 1: the core damage kit.
			"Heroic Hyperion",
			"Withered Hyperion",
			"Precise Terminator",
			"Withered Dark Claymore",
			"Warped Aspect of the Void",
			"Dungeonbreaker",
			"Heroic Bonzo Staff",
			"Infinityboom TNT",
			"Infinileap",
			// Row 2: situational weapons and the pearls.
			"Heroic Ice Spray Wand",
			"Precise Last Breath",
			"Withered Ragnarοck Axe",   // Greek omicron, matching ClassKits - see the note above
			"Suspicious Axe of the Shredded",
			"Withered Flaming Flay",
			"Heroic Jerry-chine Gun",
			"Suspicious Golem Sword",
			"Precise Explosive Bow",
			"ENDER_PEARL",
			// Row 3: utility, the cosmetic heads, then the Necron set.
			"Gyrokinetic Wand",
			"Tactical Insertion",
			"Pitchin' Rod of the Sea",
			"Ancient Spirit Mask",
			"Ancient Bonzo's Mask",
			"Ancient Diamond Necron Head",
			"Ancient Necron's Chestplate",
			"Ancient Necron's Leggings",
			"Ancient Necron's Boots",
			// Row 4: the Storm set, then its alternate-reforge pieces.
			"Ancient Storm's Helmet",
			"Ancient Storm's Chestplate",
			"Ancient Storm's Leggings",
			"Ancient Storm's Boots",
			"Necrotic Wither Goggles",
			"Loving Storm's Chestplate",
			"Necrotic Storm's Leggings",
			"Necrotic Storm's Boots",
			"Renowned Cow Hat",
			// Row 5: the rest of the Renowned wearables, then the two weapons no default kit carries. Here, not by the
			// other weapons, because this is the only short row; inserting on row 2 would shift every later row.
			"Renowned Spring Boots",
			"Renowned Racing Helmet",
			"Renowned Thermodynamic Helmet",
			"Renowned Thermodynamic Chestplate",
			"Renowned Thermodynamic Leggings",
			"Renowned Thermodynamic Boots",
			"Heroic Spirit Sceptre",
			"Precise Death Bow");

	/**
	 * Boot self-check of the three places an item's identity is written, called from {@code M7.onEnable} before
	 * the export. Catches two silent failures:
	 * <ul>
	 *   <li>a variant missing from {@link #PALETTE_ORDER} quietly sorts to the tail, and a short block shifts every
	 *       later row;</li>
	 *   <li>a rarity disagreeing with {@code damage/Items} changes the derived COLOUR. Four were already wrong when
	 *       derivation went in (Cow Hat, Spring Boots, Racing Helmet, Thermodynamic set, all Epic).</li>
	 * </ul>
	 * Only WARNS: not worth refusing to boot over, and the line names the item.
	 */
	public static void verify() {
		// The one exemption: the SkyBlock Menu is withheld from the editor.
		final String HIDDEN = items.util.SkyblockMenu.INSTANCE.displayName();
		java.util.logging.Logger log = M7.getInstance().getLogger();
		int problems = 0;
		for (Item item : ItemRegistry.ALL) {
			for (ReforgeId reforge : item.reforges()) {
				String name = item.displayName(reforge);
				if (!PALETTE_ORDER.contains(name) && !name.equals(HIDDEN)) {
					log.warning("Item " + name + " is not in Catalog.PALETTE_ORDER, so it sorts to the tail of the"
							+ " loadout palette.  Add it to the right block of nine.");
					problems++;
				}
				ItemDef def = item.stats(reforge);
				if (def != null && def.rarity() != item.effectiveRarity()) {
					log.warning("Item " + name + " is " + item.effectiveRarity() + " per its item class but "
							+ def.rarity() + " per damage/Items, so it is being drawn in the wrong colour.");
					problems++;
				}
			}
		}
		List<String> variants = ItemRegistry.variantNames();
		for (String listed : PALETTE_ORDER) {
			// ENDER_PEARL is the one entry that isn't an Item: a bare nameless stack.
			if (!variants.contains(listed) && !listed.equals(Material.ENDER_PEARL.name())) {
				log.warning("Catalog.PALETTE_ORDER lists " + listed + ", which no item builds any more.  Remove it,"
						+ " or fix the name (a lookalike character counts as a rename).");
				problems++;
			}
		}
		if (problems == 0) {
			log.info("Item catalog self-check passed: " + variants.size() + " variants, palette order intact.");
		}
	}

	/** Index in {@link #PALETTE_ORDER}, or the end if unlisted. */
	private static int paletteRank(ItemStack it) {
		int i = PALETTE_ORDER.indexOf(orderName(it));
		return i < 0 ? PALETTE_ORDER.size() : i;
	}

	/** Plain display name, or the material for a nameless stack. */
	private static String orderName(ItemStack it) {
		ItemMeta meta = it.hasItemMeta() ? it.getItemMeta() : null;
		String name = meta == null ? "" : Utils.plain(meta.displayName());
		return name.isEmpty() ? it.getType().name() : name;
	}

	/**
	 * LOGICAL identity: material + display name + lore ID, all PLAIN so a colour change can't split an item. Other
	 * NBT (enchant levels, profile ids, amounts) is ignored. Same ID, different name (Heroic vs Withered Hyperion)
	 * stay separate.
	 * <br>
	 * Both loadout/ItemRefresh copies (M7's and the network's) match saved loadouts with this exact rule; keep all
	 * three in sync.
	 * <br>
	 * Only lore line 0 is read, so lore can grow freely and saved copies still refresh. Move the ID off line 0
	 * ({@code items.ItemUtils.getID()} requires it there) and every saved loadout silently stops updating.
	 */
	public static String paletteKey(ItemStack it) {
		ItemMeta meta = it.hasItemMeta() ? it.getItemMeta() : null;
		if (meta == null) return it.getType().name() + "||";
		return it.getType().name() + "|" + Utils.plain(meta.displayName()) + "|" + Utils.firstLorePlain(meta);
	}

	/** Matches the TAS: Archer 70, Berserk/Healer/Tank 17, Mage none. */
	private static int terminatorPower(String role) {
		return switch (role) {
			case "Archer" -> 70;
			case "Berserk", "Healer", "Tank" -> 17;
			default -> 0;
		};
	}

	/** {@code <server>/../data}, the network Config default. */
	public static Path dataDir() throws Exception {
		Path dir = M7.getInstance().getServer().getWorldContainer().toPath().resolve("../data").normalize();
		Files.createDirectories(dir);
		return dir;
	}

	private static void save(Path file, Object value) throws Exception {
		Path parent = file.getParent();
		if (parent != null) Files.createDirectories(parent);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(value, w);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ex) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Field names must match the network plugin's reader. */
	public static class CatalogFile {
		public List<String> palette = new ArrayList<>();
		public Map<String, List<String>> defaults = new LinkedHashMap<>();
		/** Pet name -> arranging icon, for the lobby pet menu. */
		public Map<String, String> pets = new LinkedHashMap<>();
		/** Pet menu's 28 slots, so the shape is written once. */
		public List<Integer> petSlots = new ArrayList<>();
		/** {@code Pets.DEFAULT_PET}, the lobby header's fallback. */
		public String defaultPet;
		/** Autopet triggers in menu order. */
		public List<PetTrigger> autopet = new ArrayList<>();

		/** As much of a trigger as a button needs. */
		public static class PetTrigger {
			public String name;
			public String displayName;
			/** A {@code Material} name; the reader falls back if it ever stops resolving. */
			public String icon;
			/** Holds an ordered cycle instead of one pet (Rod Swap). */
			public boolean cycle;
		}
	}
}
