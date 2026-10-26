package damage;

import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

/**
 * Equipped pet. In classic it's assumed, not owned (MAP.md §1.13): it follows from what the player is doing
 * and wearing. In the live modes ({@link Difficulty#manualPets()}) the player chooses and {@code pets/} answers.
 * <p>
 * Three separate contributions, NOT the same numbers:
 * <ul>
 *   <li>{@link #ownStats()} - profile-level base stats, not cata-scaled (not a dungeon item).</li>
 *   <li>{@link #statAdditive(Stat)} - additive % on a stat, e.g. Golden Dragon +5% Strength.</li>
 *   <li>{@link #damageAdditive} - damage-level additive %, e.g. Golden Dragon +250% (§7).</li>
 * </ul>
 * Chimera stacks with the pet (§1.13): V copies BASE stats at 100%, so Golden Dragon +300 Strength plus Chimera's
 * +300. No second +5%. Chimera's copy lands on the WEAPON and is cata-scaled (x6.65) while the pet's own isn't, which
 * is why the Mage wants the Golden Dragon and the Archer (Duplex, not Chimera) barely cares (§7 pet cross-check).
 */
public enum Pet {
	/** Default (§1.13). */
	GOLDEN_DRAGON(StatBlock.of(Stat.STRENGTH, 300)),
	/** Ability pet, which is why neither dragon's damage additive is live on a cast (§7). */
	CROW(StatBlock.of(Stat.INTELLIGENCE, 225, Stat.ABILITY_DAMAGE, 30)),
	/** Archer / Berserk during the Wither King phase. */
	ENDER_DRAGON(StatBlock.of(Stat.STRENGTH, 75, Stat.CRIT_CHANCE, 15, Stat.CRIT_DAMAGE, 90)),
	/** Cheat-death pet ({@code death/CheatDeath}), pickable in the live modes. No assumption table returns it. */
	PHOENIX(StatBlock.of(Stat.STRENGTH, 90, Stat.INTELLIGENCE, 225)),
	/**
	 * Assumed with a Racing Helmet or Cow Hat, pickable in the live modes. Int is its only damage-relevant stat (Speed,
	 * Magic Find, Pet Luck unmodelled). Holds Unalloyed Speed, not a relic, which raises a speed CAP and multiplies
	 * nothing, so this is the raw level-100 figure.
	 */
	BLACK_CAT(StatBlock.of(Stat.INTELLIGENCE, 100), 150);

	private final StatBlock ownStats;
	private final int maxSpeedBonus;

	Pet(StatBlock ownStats) {
		this(ownStats, 0);
	}

	Pet(StatBlock ownStats, int maxSpeedBonus) {
		this.ownStats = ownStats;
		this.maxSpeedBonus = maxSpeedBonus;
	}

	/**
	 * Max Speed bonus. Only Black Cat: +150 = 100 pet + 50 Unalloyed Speed ("Grants +50 Max Speed Cap"). Outside
	 * {@link #ownStats} since it's not a damage stat; only reader is {@code plugin/MaxSpeedSync}. The old hat
	 * numbers decompose from it: Cow Hat 550 = 400 + 150, Racing Helmet 650 = 400 + 150 + helmet's 100.
	 */
	public int maxSpeedBonus() {
		return maxSpeedBonus;
	}

	/** Alpha GDrag Damage ({@code plugin/AlphaGDrag}) swaps Golden Dragon's +300 Strength for this. */
	private static final StatBlock ALPHA_GOLDEN_DRAGON = StatBlock.of(Stat.STRENGTH, 370);

	/** Profile level, so NOT cata-scaled. */
	public StatBlock ownStats() {
		if(this == GOLDEN_DRAGON && plugin.AlphaGDrag.enabled()) return ALPHA_GOLDEN_DRAGON;
		return ownStats;
	}

	/** Chimera V: base stats at 100%, landing on the weapon (§1.13, §2). */
	public StatBlock chimeraCopy() {
		return ownStats();
	}

	/** Additive % on one STAT (§1.13), 5 = +5%. Crow, Phoenix, Black Cat have none on a modelled stat. */
	public double statAdditive(Stat stat) {
		return switch(this) {
			case GOLDEN_DRAGON -> stat == Stat.STRENGTH ? 5.0 : 0.0;
			case ENDER_DRAGON -> (stat == Stat.STRENGTH || stat == Stat.CRIT_DAMAGE) ? 10.0 : 0.0;
			default -> 0.0;
		};
	}

	/**
	 * Additive % on a HIT (§7 misc table). Ender Dragon +200% Ender-only; Golden Dragon +250% always. Phoenix's
	 * Fourth Flare burn is its own unmodelled source, so summoning it really gives up the dragon's additive.
	 */
	public double damageAdditive(java.util.Set<MobType> targetTypes) {
		return switch(this) {
			case GOLDEN_DRAGON -> 250.0;
			case ENDER_DRAGON -> targetTypes.contains(MobType.ENDER) ? 200.0 : 0.0;
			default -> 0.0;
		};
	}

	/**
	 * Live modes: the player's choice wins ({@link Difficulty#manualPets()}), a map read in {@code pets/Pets} since
	 * this runs per aggregate. That branch sits ABOVE the hat override on purpose: a hat forces the Black Cat only
	 * when nobody chose, so a hat no longer costs a Realistic player their pet. Move it below the hat test to put
	 * that cost back.
	 * <p>
	 * Otherwise ASSUMED per §1.13, evaluated live since path, phase and helmet change mid-run. Order: hat, cast, WK
	 * phase for the two classes that swap, default.
	 */
	public static Pet forPlayer(Player p, DamagePath path) {
		if(Difficulty.manualPets()) return pets.Pets.equippedDamagePet(p);
		PlayerInventory inv = p.getInventory();
		if(plugin.FakePlayerInventory.isRacingHelmet(inv.getHelmet()) || plugin.FakePlayerInventory.isCowHat(inv.getHelmet())) {
			return BLACK_CAT;
		}
		if(path == DamagePath.ABILITY) return CROW;
		DungeonClass clazz = DungeonClass.of(p);
		if((clazz == DungeonClass.ARCHER || clazz == DungeonClass.BERSERK) && MobStats.witherKingPhaseActive()) {
			return ENDER_DRAGON;
		}
		return GOLDEN_DRAGON;
	}
}
