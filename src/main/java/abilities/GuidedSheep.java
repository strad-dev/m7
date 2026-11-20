package abilities;

import damage.DungeonClass;
import damage.GuidedCarriers;
import items.ItemUtils;
import org.bukkit.DyeColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;

/**
 * Mage regular drop ability: AI-less, no-gravity sheep flown a block a tick where the caster looks, detonating on
 * a mob or solid block.
 * <p>
 * Flight is {@link ItemUtils#launchGuided}, shared with Spirit Sceptre's Guided Bat; only the damage rule is here.
 * It steers: heading is re-read every tick. Mage has no ultimate, so a sprinting drop does nothing.
 */
public final class GuidedSheep implements ClassAbility {
	public static final GuidedSheep INSTANCE = new GuidedSheep();

	private GuidedSheep() {}

	/**
	 * Flat and low: comes only from Mage class level (no Intelligence, Ability Damage or gear), which is why it's
	 * weak at endgame while Wither Impact from the same Mage casts from 66,600 and lands in the hundreds of
	 * millions. 410,025 is the owner's measured hit at Mage 50; the wiki gives only per-level increments
	 * ("+100-2000" per level, "+500-10000" on its Dungeons page) and the pages disagree.
	 * <p>
	 * Via {@code dealDerived}: already a finished hit, so no Rulers/Smite/class multiplier, and kept out of the
	 * rolling history Explosive Shot and Rapid Fire read.
	 */
	private static final double DAMAGE = 410_025;

	/**
	 * Blocks. Undocumented anywhere, so matched to Spirit Sceptre's Guided Bat. Block breaking is separate: the
	 * Superboom radius in {@code ItemUtils.triggerSuperboomRadius}.
	 */
	private static final double BLAST_RADIUS = 6;

	@Override
	public DungeonClass owner() {
		return DungeonClass.MAGE;
	}

	@Override
	public boolean ultimate() {
		return false;
	}

	@Override
	public int cooldownTicks() {
		return 600; // 30s
	}

	@Override
	public boolean mageReduced() {
		return true;
	}

	@Override
	public boolean cast(Player p) {
		guidedSheep(p);
		return true;
	}

	public static void guidedSheep(Player p) {
		Sheep sheep = (Sheep) p.getWorld().spawnEntity(p.getEyeLocation().add(0, -0.65, 0), EntityType.SHEEP);
		sheep.setColor(DyeColor.WHITE);
		GuidedCarriers.stampFlat(sheep, "Guided Sheep", DAMAGE);
		ItemUtils.launchGuided(p, sheep, BLAST_RADIUS);
	}
}
