package damage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reforge table keyed {@code (reforgeId, category, rarity)} as Hypixel does it (MAP.md §2.1), not per item.
 * <p>
 * Heroic / Suspicious / Precise are the wiki's full tables and reproduce §1 exactly (Heroic Mythic = Hyperion's
 * +50/+125, Heroic Legendary = Ice Spray's +40/+100, Precise Mythic = Terminator/Last Breath +34/+70).
 * <p>
 * Others are pinned by §1 at the ONE rarity anything wears them at. Another rarity logs once instead of silently
 * answering zero.
 * <p>
 * Attack Speed unmodelled and Crit Chance only stored: §7 makes every hit crit, and the attack-speed cap comes from
 * the Thermodynamic set (§1.10).
 */
public final class Reforges {
	private Reforges() {}

	private record Key(ReforgeId reforge, ItemCategory category, Rarity rarity) {}

	private static final Map<Key, StatBlock> TABLE = new HashMap<>();
	/** So a missing row costs one log line, not one per hit. */
	private static final Set<Key> WARNED = new HashSet<>();

	private static void put(ReforgeId r, ItemCategory c, Rarity rarity, StatBlock stats) {
		TABLE.put(new Key(r, c, rarity), stats);
	}

	static {
		// ===== Wiki tables: Reforging/Sword and Fishing Rod, Reforging/Ranged Weapon (§2.1) =====
		put(ReforgeId.HEROIC, ItemCategory.SWORD, Rarity.RARE, StatBlock.of(Stat.STRENGTH, 25, Stat.INTELLIGENCE, 65));
		put(ReforgeId.HEROIC, ItemCategory.SWORD, Rarity.EPIC, StatBlock.of(Stat.STRENGTH, 32, Stat.INTELLIGENCE, 80));
		put(ReforgeId.HEROIC, ItemCategory.SWORD, Rarity.LEGENDARY, StatBlock.of(Stat.STRENGTH, 40, Stat.INTELLIGENCE, 100));
		put(ReforgeId.HEROIC, ItemCategory.SWORD, Rarity.MYTHIC, StatBlock.of(Stat.STRENGTH, 50, Stat.INTELLIGENCE, 125));

		// Suspicious also grants flat +15 Damage at EVERY rarity, as the Damage stat, not a multiplier (§1.9).
		put(ReforgeId.SUSPICIOUS, ItemCategory.SWORD, Rarity.RARE, StatBlock.of(Stat.CRIT_DAMAGE, 50, Stat.CRIT_CHANCE, 3, Stat.DAMAGE, 15));
		put(ReforgeId.SUSPICIOUS, ItemCategory.SWORD, Rarity.EPIC, StatBlock.of(Stat.CRIT_DAMAGE, 65, Stat.CRIT_CHANCE, 5, Stat.DAMAGE, 15));
		put(ReforgeId.SUSPICIOUS, ItemCategory.SWORD, Rarity.LEGENDARY, StatBlock.of(Stat.CRIT_DAMAGE, 85, Stat.CRIT_CHANCE, 7, Stat.DAMAGE, 15));
		put(ReforgeId.SUSPICIOUS, ItemCategory.SWORD, Rarity.MYTHIC, StatBlock.of(Stat.CRIT_DAMAGE, 110, Stat.CRIT_CHANCE, 10, Stat.DAMAGE, 15));

		put(ReforgeId.PRECISE, ItemCategory.RANGED, Rarity.EPIC, StatBlock.of(Stat.STRENGTH, 18, Stat.CRIT_DAMAGE, 32, Stat.CRIT_CHANCE, 11));
		put(ReforgeId.PRECISE, ItemCategory.RANGED, Rarity.LEGENDARY, StatBlock.of(Stat.STRENGTH, 25, Stat.CRIT_DAMAGE, 50, Stat.CRIT_CHANCE, 13));
		put(ReforgeId.PRECISE, ItemCategory.RANGED, Rarity.MYTHIC, StatBlock.of(Stat.STRENGTH, 34, Stat.CRIT_DAMAGE, 70, Stat.CRIT_CHANCE, 15));

		// ===== Pinned by §1 at one rarity each =====
		// Fabled @ Mythic: Hyperion, Dark Claymore, Flaming Flay +75 Strength / +50 Crit Damage.
		put(ReforgeId.FABLED, ItemCategory.SWORD, Rarity.MYTHIC, StatBlock.of(Stat.STRENGTH, 75, Stat.CRIT_DAMAGE, 50));
		// Withered @ Epic: Ragnarock Axe +160 Strength. NOT the Fabled alias (§1.0.6).
		put(ReforgeId.WITHERED, ItemCategory.SWORD, Rarity.EPIC, StatBlock.of(Stat.STRENGTH, 160));
		// Warped @ Legendary: AOTV +165 Damage / +165 Strength / +150 Intelligence.
		put(ReforgeId.WARPED, ItemCategory.SWORD, Rarity.LEGENDARY,
				StatBlock.of(Stat.DAMAGE, 165, Stat.STRENGTH, 165, Stat.INTELLIGENCE, 150));

		// Armour. Ancient is rarity-keyed: Spirit Mask (Mythic) 35/50/25, Bonzo's Mask (Epic) 18/50/16 (§1.10).
		put(ReforgeId.ANCIENT, ItemCategory.ARMOR, Rarity.MYTHIC,
				StatBlock.of(Stat.STRENGTH, 35, Stat.CRIT_DAMAGE, 50, Stat.INTELLIGENCE, 25));
		put(ReforgeId.ANCIENT, ItemCategory.ARMOR, Rarity.EPIC,
				StatBlock.of(Stat.STRENGTH, 18, Stat.CRIT_DAMAGE, 50, Stat.INTELLIGENCE, 16));
		// Diamond Necron Head is SPECIAL (red), not "above Mythic" here, so it needs its own row or the lookup
		// zeroes its reforge. Same numbers as MYTHIC on purpose, to keep its output from when it was Legendary.
		put(ReforgeId.ANCIENT, ItemCategory.ARMOR, Rarity.SPECIAL,
				StatBlock.of(Stat.STRENGTH, 35, Stat.CRIT_DAMAGE, 50, Stat.INTELLIGENCE, 25));
		put(ReforgeId.NECROTIC, ItemCategory.ARMOR, Rarity.LEGENDARY, StatBlock.of(Stat.INTELLIGENCE, 150));
		put(ReforgeId.NECROTIC, ItemCategory.ARMOR, Rarity.MYTHIC, StatBlock.of(Stat.INTELLIGENCE, 200));
		put(ReforgeId.LOVING, ItemCategory.ARMOR, Rarity.MYTHIC, StatBlock.of(Stat.INTELLIGENCE, 120));
		// Renowned grants nothing on purpose (§1.10); its buff is +1% additive per piece, in Profile.
		for(Rarity r : Rarity.values()) put(ReforgeId.RENOWNED, ItemCategory.ARMOR, r, StatBlock.EMPTY);

		for(Rarity r : Rarity.values()) put(ReforgeId.NONE, ItemCategory.SWORD, r, StatBlock.EMPTY);
		for(Rarity r : Rarity.values()) put(ReforgeId.NONE, ItemCategory.RANGED, r, StatBlock.EMPTY);
		for(Rarity r : Rarity.values()) put(ReforgeId.NONE, ItemCategory.ARMOR, r, StatBlock.EMPTY);
		for(Rarity r : Rarity.values()) put(ReforgeId.NONE, ItemCategory.EQUIPMENT, r, StatBlock.EMPTY);
	}

	/** Read at EFFECTIVE (recombed) rarity, never base (§1.0.9); {@link ItemDef#rarity()} already gives that. */
	public static StatBlock stats(ReforgeId reforge, ItemCategory category, Rarity rarity) {
		Key key = new Key(reforge, category, rarity);
		StatBlock found = TABLE.get(key);
		if(found != null) return found;
		if(WARNED.add(key)) {
			plugin.M7.getInstance().getLogger().warning(
					"No reforge row for " + reforge + " / " + category + " / " + rarity
							+ " - treating it as no stats.  Add the row to damage/Reforges if this combination is real.");
		}
		return StatBlock.EMPTY;
	}
}
