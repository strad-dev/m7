package damage;

/**
 * Equipment (necklace / cloak / belt / gloves): <b>assumed, never an item</b> (MAP.md §1.11). No ItemStacks, slots
 * or palette entries; each class is taken to wear the right ones as a flat per-class source.
 * <p>
 * Mage's set is path-dependent, one reason the stat cache is keyed {@code (player, path)}: Soulweaver Gloves (beam)
 * and Manticore Claw (ability) share a slot, and the Balloon Snake changes reforge with them.
 * <p>
 * Plain SkyBlock numbers; x6.66 is applied as a stage like items. Exception: Manticore Claw isn't a dungeon item, so
 * its 20 / 37.5 / 3 stay flat, and its Ability Damage isn't starred so no x1.81 either.
 * <p>
 * Reforge terms are inline, not looked up: §2.1 says the equipment reforge table is "only needed if equipment
 * reforges ever change", since §1.11 publishes resolved per-piece values. They differ by unpublished rarity
 * (Strengthened necklace +6 Strength, cloak +7). Each number still appears once.
 */
public final class Equipment {
	private Equipment() {}

	/** Already scaled. Every class wears the Adaptive Belt and its ability differs per class, so it's built per class. */
	public static StatBlock forClass(DungeonClass clazz, DamagePath path) {
		return clazz == DungeonClass.MAGE ? mage(path) : martial(clazz);
	}

	/** Archer / Berserk / Healer / Tank: one set, differing only in the belt ability. */
	private static StatBlock martial(DungeonClass clazz) {
		StatBlock scaled = StatBlock.EMPTY
				// Bone Necklace (Strengthened): 18 The One + 6 reforge
				.plus(Stat.STRENGTH, 18 + 6)
				// Shadow Assassin Cloak (Strengthened): 25 base + 7 reforge
				.plus(Stat.STRENGTH, 25 + 7)
				// Adaptive Belt (Bloodshot): 10 base + 5 reforge, plus the class ability below
				.plus(Stat.STRENGTH, 10 + 5)
				// Soulweaver Gloves (Strengthened): 10 base + 6 reforge Strength, 10 base Crit Damage
				.plus(Stat.STRENGTH, 10 + 6)
				.plus(Stat.CRIT_DAMAGE, 10);
		scaled = switch(clazz) {
			case BERSERK -> scaled.plus(Stat.STRENGTH, 10);      // belt ability
			case ARCHER -> scaled.plus(Stat.CRIT_DAMAGE, 5);     // belt ability
			default -> scaled;                                   // Healer and Tank: nothing damage-relevant
		};
		return scaled.scaled(Scale.SB_CATA_MULT, Scale.SB_STAR_MULT);
	}

	/** Beam set trades the ability set's Int for Crit Damage, which the ability formula can't use (§7). */
	private static StatBlock mage(DamagePath path) {
		boolean ability = path == DamagePath.ABILITY;
		StatBlock scaled = StatBlock.EMPTY
				// Shadow Assassin Cloak (Brilliant), same on both paths: 25 base Strength, 15 reforge Intelligence
				.plus(Stat.STRENGTH, 25)
				.plus(Stat.INTELLIGENCE, 15)
				// Adaptive Belt (Bloodshot): 10 base + 5 reforge Strength, and its Mage ability is +25 Intelligence
				.plus(Stat.STRENGTH, 10 + 5)
				.plus(Stat.INTELLIGENCE, 25)
				// Balloon Snake: 18 The One Strength either way, then the reforge splits the two sets
				.plus(Stat.STRENGTH, 18);
		if(ability) {
			// Brilliant snake: 10 base + 9 reforge Intelligence
			scaled = scaled.plus(Stat.INTELLIGENCE, 10 + 9);
		} else {
			// Menacing snake: 10 base Intelligence, 4 reforge Crit Damage
			scaled = scaled.plus(Stat.INTELLIGENCE, 10).plus(Stat.CRIT_DAMAGE, 4);
			// Soulweaver Gloves (Brilliant), the BEAM half of the shared gloves slot
			scaled = scaled.plus(Stat.STRENGTH, 10).plus(Stat.CRIT_DAMAGE, 10).plus(Stat.INTELLIGENCE, 12);
		}
		StatBlock out = scaled.scaled(Scale.SB_CATA_MULT, Scale.SB_STAR_MULT);
		if(ability) {
			// Manticore Claw (Brilliant), ABILITY half of the gloves slot. Not a dungeon item, so flat: 20 Strength,
			// 22.5 ability + 15 reforge Intelligence, 3 Ability Damage.
			out = out.plus(Stat.STRENGTH, 20)
					.plus(Stat.INTELLIGENCE, 22.5 + 15)
					.plus(Stat.ABILITY_DAMAGE, 3);
		}
		return out;
	}
}
