package damage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Everything outside item / equipment / power layers (MAP.md §1.13): skills, slayers, potions, essence perks,
 * accessories' own stats, blessings, pets.
 * <p>
 * <b>Stat pipeline.</b> Additive sources sum, then multiplicative ones apply in turn:
 * <pre>
 * statTotal = ( sum of ALL base sources )        // items + armour + equipment + power + tunings + profile
 *           x ( 1 + sum of additive % )
 *           x product of multiplicative factors
 * </pre>
 * <b>Not the §7 damage buckets.</b> These multiply a STAT, those a HIT: Power 29 raises Strength, Fabled's x1.15
 * raises the hit. Swapping them is the easiest way to be off by 2x.
 * <p>
 * <b>Ruled: a stat's multipliers affect that ONE stat, all of it</b> (weapon, armour, equipment, power AND profile).
 * Applying one to part of a stat is worth 17x on time-to-kill.
 */
public final class Profile {
	private Profile() {}

	/**
	 * Unconditional base sources. Conditional ones ({@code !} in §1.13) come from the caller: pet via
	 * {@link Pet#ownStats()}, Ragnarock via {@link Stats#ragnarockStrength}, COMPUTED from the axe (§1.7), so "+939"
	 * is never a constant.
	 * <p>
	 * Inherent +5 Damage is NOT here: it's the formula's {@code 5 +} (§1.0.4), {@link Scale#PLAYER_BASE_DAMAGE}.
	 * <p>
	 * No blessings, including Stone's flat Damage which used to sum here: they're the last stage with their own
	 * shape ({@code (stat + flat) x percent}, {@link Blessings}), applied by {@code Stats.compute}.
	 */
	public static StatBlock base() {
		return StatBlock.EMPTY
				// ----- Damage -----
				.plus(Stat.DAMAGE, 4)            // Blazetekk Ham Radio (Bluertooth Ring)

				// ----- Strength -----
				.plus(Stat.STRENGTH, 124)        // SkyBlock Level, +1 per 5 levels (max 620)
				.plus(Stat.STRENGTH, 100)        // Foraging Skill
				.plus(Stat.STRENGTH, 78.75)      // Strength VIII potion, 75 x 1.05
				.plus(Stat.STRENGTH, 65)         // Attributes
				.plus(Stat.STRENGTH, 50)         // Strength Essence
				.plus(Stat.STRENGTH, 5)          // Forbidden Strength
				.plus(Stat.STRENGTH, 6)          // Blessing of Time (essence shop)
				.plus(Stat.STRENGTH, 3)          // Inferno Demonlord Levels
				.plus(Stat.STRENGTH, 10)         // Sunshine/Moonlight Crystal
				.plus(Stat.STRENGTH, 14)         // Blood God Crest
				.plus(Stat.STRENGTH, 10)         // Shark Tooth Necklace
				.plus(Stat.STRENGTH, 10)         // Pandora's Box
				.plus(Stat.STRENGTH, 8)          // Relic of Power
				.plus(Stat.STRENGTH, 2)          // Burststopper Artifact

				// ----- Crit Damage -----
				.plus(Stat.CRIT_DAMAGE, 50)      // base stat
				.plus(Stat.CRIT_DAMAGE, 40)      // Critical IV potion
				.plus(Stat.CRIT_DAMAGE, 40)      // Spirit IV potion
				.plus(Stat.CRIT_DAMAGE, 60)      // Enrichments, non-dungeon talismans
				.plus(Stat.CRIT_DAMAGE, 35.46)   // Enrichments, the 6 cata-buffed ones (no stars)
				.plus(Stat.CRIT_DAMAGE, 50)      // Critical Essence
				.plus(Stat.CRIT_DAMAGE, 16)      // Spider Slayer
				.plus(Stat.CRIT_DAMAGE, 12)      // Beacon Effect
				.plus(Stat.CRIT_DAMAGE, 6)       // Relic of Power
				.plus(Stat.CRIT_DAMAGE, 5)       // Vial of Venom
				.plus(Stat.CRIT_DAMAGE, 5)       // Red Claw Artifact
				.plus(Stat.CRIT_DAMAGE, 3)       // Wolf Slayer
				.plus(Stat.CRIT_DAMAGE, 1)       // Tiny Dancer

				// ----- Intelligence -----
				.plus(Stat.INTELLIGENCE, 300)    // Bottle of Jyrre
				.plus(Stat.INTELLIGENCE, 106)    // Enchanting Skill
				.plus(Stat.INTELLIGENCE, 100)    // Pocket Expresso Machine
				.plus(Stat.INTELLIGENCE, 86)     // Alchemy Skill
				.plus(Stat.INTELLIGENCE, 75)     // Intelligence Essence
				.plus(Stat.INTELLIGENCE, 65)     // Attributes
				.plus(Stat.INTELLIGENCE, 28)     // Harp Completions
				.plus(Stat.INTELLIGENCE, 15)     // Relic of Power
				.plus(Stat.INTELLIGENCE, 13)     // Enderman Slayer
				.plus(Stat.INTELLIGENCE, 10)     // Defuse Kit
				.plus(Stat.INTELLIGENCE, 10)     // Forbidden Intelligence
				.plus(Stat.INTELLIGENCE, 6)      // Blessing of Time (essence shop)
				.plus(Stat.INTELLIGENCE, 6)      // Melody's Hair
				.plus(Stat.INTELLIGENCE, 5)      // Century Cake
				.plus(Stat.INTELLIGENCE, 5)      // Aged Like Fine Jyrre
				.plus(Stat.INTELLIGENCE, 3)      // Bat Artifact
				.plus(Stat.INTELLIGENCE, 3)      // Beastmaster Crest
				.plus(Stat.INTELLIGENCE, 1)      // Big Brain Talisman
				.plus(Stat.INTELLIGENCE, 1)      // Necron's Ladder
				.plus(Stat.INTELLIGENCE, -2)     // Bluertooth Ring
				.plus(Stat.INTELLIGENCE, -4)     // Golden Jerry Artifact

				// ----- Ability Damage -----
				.plus(Stat.ABILITY_DAMAGE, 30);  // Enchanting Skill
	}

	/**
	 * Sum of additive % on one stat (22.35 = +22.35%). Legion counts OTHER players within 30 blocks, so a full party
	 * is 4. Renowned counts pieces worn, the only damage a Cow Hat or Spring Boots now gives; the old x0.70/x0.80
	 * penalties are deleted (§1.10, §8).
	 */
	public static double additivePercent(Player p, Stat stat, Pet pet) {
		double sum = 0;
		if(stat == Stat.STRENGTH || stat == Stat.CRIT_DAMAGE || stat == Stat.INTELLIGENCE
				|| stat == Stat.ABILITY_DAMAGE) {
			sum += Mayor.jerryBonus();
			sum += 1.4 * legionStacks(p);
			sum += 1.0 * renownedPiecesWorn(p);
		}
		switch(stat) {
			case STRENGTH -> sum += 1.75;                // Unlimited Power Attribute
			case CRIT_DAMAGE -> sum += 1.75;             // Unlimited Energy Attribute
			case INTELLIGENCE -> sum += 2.0 + 1.75;      // 2 IQ Points Talisman, Unlimited Torment Attribute
			default -> { }                               // Ability Damage has no attribute of its own
		}
		if(pet != null) sum += pet.statAdditive(stat);
		return sum;
	}

	/**
	 * Product of multiplicative factors on one stat EXCEPT blessings. They used to be here; Hypixel applies them LAST
	 * with the flat inside their percent ({@link Blessings}), and folding them in here put the flat outside it and
	 * exposed it to {@link #additivePercent}. {@code Stats.compute} runs this stage, then blessings.
	 */
	public static double multiplicative(Stat stat) {
		return switch(stat) {
			case STRENGTH -> 1.1;                         // Master Skull, Tier 7
			default -> 1.0;                               // nothing else has a non-blessing multiplier
		};
	}

	/** Other non-spectators within 30 blocks. Never yourself, so a full party is 4. */
	public static int legionStacks(Player p) {
		if(p == null) return 0;
		int n = 0;
		for(Player other : Bukkit.getOnlinePlayers()) {
			if(other == p || plugin.Utils.isSpectator(other)) continue;
			if(!other.getWorld().equals(p.getWorld())) continue;
			if(other.getLocation().distanceSquared(p.getLocation()) <= 30 * 30) n++;
		}
		return n;
	}

	/** Each worth +1% additive on the stats it applies to. */
	public static int renownedPiecesWorn(Player p) {
		if(p == null) return 0;
		PlayerInventory inv = p.getInventory();
		int n = 0;
		for(ItemStack piece : new ItemStack[]{inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()}) {
			ItemDef def = Items.of(piece);
			if(def != null && def.reforge() == ReforgeId.RENOWNED) n++;
		}
		return n;
	}
}
