package damage;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import plugin.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every item's stat definition as TERMS (MAP.md §1.1-1.10 via §2.4). The one place item numbers live.
 * <p>
 * Keyed on PLAIN DISPLAY NAME, not lore ID: armour has no lore ID and the two Hyperions share one. The name encodes
 * the reforge, the axis that separates variants (§2.4). Unregistered names contribute nothing, which is correct for
 * utility items (Dungeonbreaker, Infinileap, TNT).
 * <p>
 * Cross-check: every §1 total falls out of these terms unauthored: Heroic Hyperion 340/235/100/860, Fabled Hyperion
 * 340/560/162/480, Terminator 340/119/325, Dark Claymore 530/510/274, Last Breath 240/259/125, Ice Spray
 * 192/70/100/749.5, AOTV 315/295/100/174, Ragnarock 270/626/100. A change that breaks one is wrong. Only deliberate
 * departure: Flaming Flay carries Chimera (230/692/150, not §1.8's 392 Strength), see its entry.
 */
public final class Items {
	private Items() {}

	private static final Map<String, ItemDef> BY_NAME = new LinkedHashMap<>();

	private static void register(ItemDef def) {
		BY_NAME.put(def.displayName(), def);
	}

	static {
		// ============================== §1.1 Hyperion - Heroic and Fabled ==============================
		// One base, two reforges, two gem sets. Legendary -> recombed Mythic.
		register(ItemDef.of("Heroic Hyperion", ItemCategory.SWORD)
				.loreId("skyblock/combat/scylla").rarity(Rarity.LEGENDARY)
				.base(Stat.DAMAGE, 260).cataLevel(Stat.DAMAGE, 50)
				.base(Stat.STRENGTH, 150)
				.base(Stat.INTELLIGENCE, 350).cataLevel(Stat.INTELLIGENCE, 100)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.CRITICAL)
				.reforge(ReforgeId.HEROIC)
				// 1 Sapphire + 1 Combat slot; Heroic puts a Sapphire in the Combat slot too (§2.3).
				.typedGem(Gemstones.Type.SAPPHIRE).combatGem(Gemstones.Type.SAPPHIRE)
				.chimera()
				.ability(10_000, 0.3) // Wither Impact (§7)
				.build());
		register(ItemDef.of("Withered Hyperion", ItemCategory.SWORD)
				.loreId("skyblock/combat/scylla").rarity(Rarity.LEGENDARY)
				.base(Stat.DAMAGE, 260).cataLevel(Stat.DAMAGE, 50)
				.base(Stat.STRENGTH, 150)
				.base(Stat.INTELLIGENCE, 350).cataLevel(Stat.INTELLIGENCE, 100)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.CRITICAL)
				.reforge(ReforgeId.FABLED)
				.typedGem(Gemstones.Type.SAPPHIRE).combatGem(Gemstones.Type.ONYX)
				.chimera()
				.ability(10_000, 0.3)
				.build());

		// ============================== §1.2 Terminator - Precise ==============================
		// SHORTBOW: never drawn, every shot a full-damage crit.
		register(ItemDef.of("Precise Terminator", ItemCategory.RANGED)
				.loreId("skyblock/combat/terminator").rarity(Rarity.LEGENDARY)
				.base(Stat.DAMAGE, 310)
				.base(Stat.STRENGTH, 50)
				.base(Stat.CRIT_DAMAGE, 250)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.OVERLOAD)
				.reforge(ReforgeId.PRECISE)
				.shortbow()
				.build());

		// ============================== §1.3 Dark Claymore - Fabled ==============================
		register(ItemDef.of("Withered Dark Claymore", ItemCategory.SWORD)
				.loreId("skyblock/combat/claymore").rarity(Rarity.LEGENDARY)
				.base(Stat.DAMAGE, 500)
				.base(Stat.STRENGTH, 100)
				.base(Stat.CRIT_DAMAGE, 100)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.CRITICAL)
				.reforge(ReforgeId.FABLED)
				.combatGem(Gemstones.Type.ONYX).combatGem(Gemstones.Type.ONYX)
				.chimera()
				.build());

		// ============================== §1.4 Last Breath - Precise ==============================
		// DRAWN bow: damage scales by charge fraction, partial draw loses the crit term (§1.4). Handled in Damage's bow path.
		register(ItemDef.of("Precise Last Breath", ItemCategory.RANGED)
				.loreId("skyblock/combat/last_breath").rarity(Rarity.LEGENDARY)
				.base(Stat.DAMAGE, 210)
				.base(Stat.STRENGTH, 190)
				.base(Stat.CRIT_DAMAGE, 50)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.OVERLOAD)
				.reforge(ReforgeId.PRECISE)
				.build());

		// ============================== §1.5 Ice Spray Wand - Heroic ==============================
		// NO Art of War: §1.5's Strength is 30 potato + 40 reforge.
		register(ItemDef.of("Heroic Ice Spray Wand", ItemCategory.SWORD)
				.loreId("skyblock/combat/ice_spray").rarity(Rarity.EPIC)
				.base(Stat.DAMAGE, 108).cataLevel(Stat.DAMAGE, 54)
				.base(Stat.INTELLIGENCE, 267).cataLevel(Stat.INTELLIGENCE, 133.5)
				.with(Upgrade.POTATO_BOOKS, Upgrade.CRITICAL)
				.reforge(ReforgeId.HEROIC)
				.typedGem(Gemstones.Type.SAPPHIRE)
				.chimera()
				.ability(19_000, 0.1) // Ice Spray (§7). Also debuffs everything within 8 blocks.
				.build());

		// ============================== §1.6 Aspect of the Void - Warped, NOT dungeon-scaled ==============
		register(ItemDef.of("Warped Aspect of the Void", ItemCategory.SWORD)
				.loreId("skyblock/combat/aotv").rarity(Rarity.EPIC).notDungeon()
				.base(Stat.DAMAGE, 120)
				.base(Stat.STRENGTH, 100)
				.with(Upgrade.POTATO_BOOKS, Upgrade.CRITICAL)
				.reforge(ReforgeId.WARPED)
				.typedGem(Gemstones.Type.SAPPHIRE)
				.build());

		// ============================== §1.7 Ragnarock Axe - Withered, NOT dungeon-scaled ==============
		// Really Withered, a different reforge sharing the Fabled alias's display name, so NO Fabled x1.15 (§1.0.6).
		// Rare -> recombed Epic, pinned by its +11 Jasper.
		register(ItemDef.of("Withered Ragnarοck Axe", ItemCategory.SWORD)
				.loreId("skyblock/combat/rag").rarity(Rarity.RARE).notDungeon()
				.base(Stat.DAMAGE, 200).stars(Stat.DAMAGE, 40)
				.base(Stat.STRENGTH, 100).stars(Stat.STRENGTH, 20)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.CRITICAL)
				.reforge(ReforgeId.WITHERED)
				.combatGem(Gemstones.Type.JASPER)
				.chimera()
				.build());

		// ============================== §1.8 Flaming Flay - Fabled ==============================
		// §1.8 omitted Chimera (392 Strength = base + books + art of war + gems + reforge), contradicting §7's "every
		// sword runs Chimera". Owner ruled for §7: Golden Dragon adds +300 Strength, cata-scaled as an ITEM source to
		// +1998. So Strength is 692 -> 4608.72, not 392 -> 2610.72.
		register(ItemDef.of("Withered Flaming Flay", ItemCategory.SWORD)
				.loreId("skyblock/combat/flaming_flay").rarity(Rarity.LEGENDARY)
				.base(Stat.DAMAGE, 200)
				.base(Stat.STRENGTH, 250)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.CRITICAL)
				.reforge(ReforgeId.FABLED)
				.combatGem(Gemstones.Type.JASPER).combatGem(Gemstones.Type.JASPER)
				.chimera()
				.build());

		// ============================== §1.9 The five deferred weapons ==============================
		// Base stats from the wiki, reforges by the owner, at RECOMBED rarity. No gems; books/stars still unassigned.
		register(ItemDef.of("Heroic Bonzo Staff", ItemCategory.SWORD)
				.loreId("skyblock/combat/bonzo").rarity(Rarity.RARE)
				.base(Stat.DAMAGE, 160)
				.base(Stat.INTELLIGENCE, 250)
				.reforge(ReforgeId.HEROIC)
				.ability(1000, 0.2) // Showtime
				.build());
		// Sword reforge table; ItemCategory is the table axis, not the material.
		register(ItemDef.of("Heroic Jerry-chine Gun", ItemCategory.SWORD)
				.loreId("skyblock/combat/jerrychine").rarity(Rarity.EPIC).notDungeon()
				.base(Stat.DAMAGE, 80)
				.base(Stat.INTELLIGENCE, 200)
				.reforge(ReforgeId.HEROIC)
				.ability(500, 0.2)
				.build());
		register(ItemDef.of("Precise Explosive Bow", ItemCategory.RANGED)
				.loreId("skyblock/combat/explosive_bow").rarity(Rarity.EPIC).notDungeon()
				.base(Stat.DAMAGE, 100)
				.base(Stat.STRENGTH, 20)
				.reforge(ReforgeId.PRECISE)
				.build());
		register(ItemDef.of("Suspicious Axe of the Shredded", ItemCategory.SWORD)
				.loreId("skyblock/combat/aots").rarity(Rarity.LEGENDARY).notDungeon()
				.base(Stat.DAMAGE, 140)
				.base(Stat.STRENGTH, 115)
				.reforge(ReforgeId.SUSPICIOUS)
				.build());
		// Our ability is the Y-velocity zero, not Iron Punch, but stats are the real item's (§1.9).
		register(ItemDef.of("Suspicious Golem Sword", ItemCategory.SWORD)
				.loreId("skyblock/combat/golem_sword").rarity(Rarity.RARE).notDungeon()
				.base(Stat.DAMAGE, 80)
				.base(Stat.STRENGTH, 125)
				.reforge(ReforgeId.SUSPICIOUS)
				.build());

		// ============================== Spirit Sceptre - Heroic ==============================
		// STARRED (Thorn Fragment) build, per id STARRED_BAT_WAND: 190/330 not 180/300, Guided Bat from 2,250 not
		// 2,000. Dungeon item, one SAPPHIRE slot, Chimera like every sword (§7). Catacombs Level Bonus unauthored:
		// the wiki has no figure and a guessed cataLevel term can't be checked.
		register(ItemDef.of("Heroic Spirit Sceptre", ItemCategory.SWORD)
				.loreId("skyblock/combat/spirit_sceptre").rarity(Rarity.LEGENDARY)
				.base(Stat.DAMAGE, 190)
				.base(Stat.INTELLIGENCE, 330)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.CRITICAL)
				.reforge(ReforgeId.HEROIC)
				.typedGem(Gemstones.Type.SAPPHIRE)
				.chimera()
				.ability(2250, 0.2) // Guided Bat (§7)
				.build());

		// ============================== Death Bow - Precise ==============================
		// DUNGEON bow (Ophelia, after Floor VI), so x6.65. Overload not Critical (a sword enchant). Swarm V, the one
		// bow not on Duplex. Its x2 vs Undead is a MULTIPLICATIVE source in damage/Damage; arrow bounce unmodelled.
		register(ItemDef.of("Precise Death Bow", ItemCategory.RANGED)
				.loreId("skyblock/combat/death_bow").rarity(Rarity.EPIC)
				.base(Stat.DAMAGE, 300)
				.with(Upgrade.POTATO_BOOKS, Upgrade.ART_OF_WAR, Upgrade.OVERLOAD)
				.reforge(ReforgeId.PRECISE)
				.swarm()
				.build());

		// ============================== §1.10 Armour ==============================
		// No armour piece has a Damage stat.
		// Necron Head Bonus x2 ALWAYS applies (M7 only). Doubles only the helmet's own stats and commutes with x6.65.
		// SPECIAL, not Legendary: it's red and colour is DERIVED from rarity (Rarity.colour). Special never recombs,
		// so effective is SPECIAL too, the row Reforges holds for Ancient/ARMOR/SPECIAL.
		register(ItemDef.of("Ancient Diamond Necron Head", ItemCategory.ARMOR)
				.rarity(Rarity.SPECIAL)
				.base(Stat.STRENGTH, 40)
				.with(Upgrade.BIG_BRAIN)
				.reforge(ReforgeId.ANCIENT)
				.selfMultiplier(2.0)
				.build());
		register(necronPiece("Ancient Necron's Chestplate").with(Upgrade.REFLECTION).build());
		register(necronPiece("Ancient Necron's Leggings").with(Upgrade.SMARTY_PANTS).build());
		register(necronPiece("Ancient Necron's Boots").build());

		register(ancientStorm("Ancient Storm's Helmet", 400).with(Upgrade.BIG_BRAIN).build());
		register(ancientStorm("Ancient Storm's Chestplate", 250).with(Upgrade.REFLECTION).build());
		register(ancientStorm("Ancient Storm's Leggings", 250).with(Upgrade.SMARTY_PANTS).build());
		register(ancientStorm("Ancient Storm's Boots", 250).build());

		// Ability loadout helmet: the only Ability Damage on any armour.
		register(ItemDef.of("Necrotic Wither Goggles", ItemCategory.ARMOR)
				.rarity(Rarity.EPIC)
				.base(Stat.INTELLIGENCE, 300)
				.base(Stat.ABILITY_DAMAGE, 45)
				.with(Upgrade.BIG_BRAIN)
				.reforge(ReforgeId.NECROTIC)
				.build());

		// Alternate-reforge Storm's ("RCM"): Int only, trading Ancient's 35/62 for more Int; both slots Sapphire.
		register(altStorm("Loving Storm's Chestplate", ReforgeId.LOVING).with(Upgrade.REFLECTION).build());
		register(altStorm("Necrotic Storm's Leggings", ReforgeId.NECROTIC).with(Upgrade.SMARTY_PANTS).build());
		register(altStorm("Necrotic Storm's Boots", ReforgeId.NECROTIC).build());

		// Hotbar heads. Ancient is rarity-keyed: Mythic Spirit Mask 35/50/25, Epic Bonzo's Mask 18/50/16.
		register(ItemDef.of("Ancient Spirit Mask", ItemCategory.ARMOR)
				.rarity(Rarity.LEGENDARY)
				.base(Stat.INTELLIGENCE, 25)
				.with(Upgrade.BIG_BRAIN)
				.reforge(ReforgeId.ANCIENT)
				.build());
		register(ItemDef.of("Ancient Bonzo's Mask", ItemCategory.ARMOR)
				.rarity(Rarity.RARE)
				.base(Stat.INTELLIGENCE, 150)
				.with(Upgrade.BIG_BRAIN)
				.reforge(ReforgeId.ANCIENT)
				.build());

		// Not dungeon items; own stats are ZERO at this scale (§1.10). Registered so Profile counts them for Renowned's
		// +1% per piece, and so the old x0.70/x0.80 worn-item penalties stay deleted: a wearable affects damage only
		// through its stats, and these have none.
		// Rarity only decides colour (Cow Hat green, Spring Boots dark purple, rest light purple); Renowned is
		// StatBlock.EMPTY at every rarity.
		register(renownedWearable("Renowned Cow Hat", Rarity.COMMON));
		register(renownedWearable("Renowned Spring Boots", Rarity.RARE));
		register(renownedWearable("Renowned Racing Helmet", Rarity.LEGENDARY));
		register(renownedWearable("Renowned Thermodynamic Helmet", Rarity.LEGENDARY));
		register(renownedWearable("Renowned Thermodynamic Chestplate", Rarity.LEGENDARY));
		register(renownedWearable("Renowned Thermodynamic Leggings", Rarity.LEGENDARY));
		register(renownedWearable("Renowned Thermodynamic Boots", Rarity.LEGENDARY));
	}

	/** Renowned cosmetic / Thermodynamic piece: no terms, no gems, unscaled; BASE rarity only decides colour. */
	private static ItemDef renownedWearable(String name, Rarity base) {
		return ItemDef.of(name, ItemCategory.ARMOR).rarity(base).notDungeon()
				.reforge(ReforgeId.RENOWNED).build();
	}

	/** Three Necron body pieces, identical but for their one Int enchant. */
	private static ItemDef.Builder necronPiece(String name) {
		return ItemDef.of(name, ItemCategory.ARMOR)
				.rarity(Rarity.LEGENDARY)
				.base(Stat.STRENGTH, 40)
				.base(Stat.CRIT_DAMAGE, 30)
				.base(Stat.INTELLIGENCE, 10)
				.reforge(ReforgeId.ANCIENT)
				.combatGem(Gemstones.Type.JASPER).combatGem(Gemstones.Type.JASPER);
	}

	/** Four Ancient Storm's pieces, differing only in base Int and enchant. */
	private static ItemDef.Builder ancientStorm(String name, double baseIntelligence) {
		return ItemDef.of(name, ItemCategory.ARMOR)
				.rarity(Rarity.LEGENDARY)
				.base(Stat.INTELLIGENCE, baseIntelligence)
				.reforge(ReforgeId.ANCIENT)
				// Typed Sapphire + Combat; the alternates' 2 x Sapphire is legal in these slots (§2.3).
				.typedGem(Gemstones.Type.SAPPHIRE).combatGem(Gemstones.Type.ONYX);
	}

	/** Loving/Necrotic Storm's piece: both slots Sapphire, no Strength or Crit Damage. */
	private static ItemDef.Builder altStorm(String name, ReforgeId reforge) {
		return ItemDef.of(name, ItemCategory.ARMOR)
				.rarity(Rarity.LEGENDARY)
				.base(Stat.INTELLIGENCE, 250)
				.reforge(reforge)
				.typedGem(Gemstones.Type.SAPPHIRE).combatGem(Gemstones.Type.SAPPHIRE);
	}

	/** Null if the item grants no stats (utility item, empty slot). */
	public static ItemDef of(ItemStack item) {
		if(item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
		ItemMeta meta = item.getItemMeta();
		if(meta == null) return null;
		return BY_NAME.get(Utils.plain(meta.displayName()));
	}

	/** Exact display name, or null. */
	public static ItemDef byName(String displayName) {
		return BY_NAME.get(displayName);
	}

	/** Authoring order. For the lore renderer and self-check. */
	public static Collection<ItemDef> all() {
		return new ArrayList<>(BY_NAME.values());
	}
}
