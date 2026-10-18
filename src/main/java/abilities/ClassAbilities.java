package abilities;

import damage.DungeonClass;
import items.ItemUtils;
import org.bukkit.entity.Player;
import plugin.Cooldowns;

import java.util.List;

/**
 * Registry of drop-key abilities; the one place a drop becomes a cast.
 * <p>
 * Replaced per-class {@code if(name.equals("Archer") || tags.contains(...))} branches that each had their own
 * cooldown map. Those used {@code equals} for Archer and {@code startsWith} for Mage and disagreed with
 * {@code DungeonClass.of}: a player tagged Berserk named {@code Archer...} got Archer abilities. Now class comes
 * from one rule, the one {@code /class} sets. {@link DungeonClass#of} defaults to MAGE, which would hand Guided
 * Sheep to classless players, so {@link #hasExplicitClass} gates it.
 */
public final class ClassAbilities {
	private ClassAbilities() {}

	/** Healer and Tank have none. */
	private static final List<ClassAbility> ALL = List.of(
			RapidFire.INSTANCE, ExplosiveShot.INSTANCE, GuidedSheep.INSTANCE, Ragnarok.INSTANCE,
			AxeThrow.INSTANCE);

	/**
	 * Ability a drop press fires, or null: no class, or nothing on that side of the key (Mage sprinting, Healer, Tank).
	 * @param ultimate true for plain {@code drop} (not sprinting)
	 */
	public static ClassAbility forDrop(Player p, boolean ultimate) {
		if(!hasExplicitClass(p)) return null;
		DungeonClass owner = DungeonClass.of(p);
		for(ClassAbility ability : ALL) {
			if(ability.owner() == owner && ability.ultimate() == ultimate) return ability;
		}
		return null;
	}

	/** Fires the ability if off cooldown, else sends the cooldown message. No-op when the presser has none. */
	public static void dispatch(Player p, boolean ultimate) {
		ClassAbility ability = forDrop(p, ultimate);
		if(ability == null) return;
		int remaining = Cooldowns.remaining(p, ability.cooldownKey());
		if(remaining > 0) {
			ItemUtils.sendCooldownMessage(p, remaining);
			return;
		}
		if(!ability.cast(p)) return;
		int cooldown = ability.mageReduced()
				? ItemUtils.effectiveCooldown(p, ability.cooldownTicks())
				: ability.cooldownTicks();
		Cooldowns.start(p, ability.cooldownKey(), cooldown);
	}

	/** Class given by tag. Same lookup as {@link DungeonClass#of} minus its MAGE fallback. */
	private static boolean hasExplicitClass(Player p) {
		if(p == null) return false;
		for(DungeonClass c : DungeonClass.values()) {
			String name = DungeonClass.name(c);
			if(p.getScoreboardTags().contains(name)) return true;
		}
		return false;
	}
}
