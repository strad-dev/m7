package damage;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Decides equipment set (§1.11), Accessory Power (§1.12) and class bonuses (§1.14).
 * <p>
 * Mirrors {@code items.ItemUtils.isMageClass}: players carry a class scoreboard tag from {@code /class}.
 */
public enum DungeonClass {
	MAGE, ARCHER, BERSERK, HEALER, TANK;

	/**
	 * By scoreboard tag. Defaults to MAGE, like the beam gate every other class check falls back to.
	 */
	public static DungeonClass of(Player p) {
		if(p == null) return MAGE;
		for(DungeonClass c : values()) {
			if(p.getScoreboardTags().contains(name(c))) return c;
		}
		return MAGE;
	}

	/** Scoreboard tag / loadout role name, e.g. {@code "Berserk"}. */
	public static String name(DungeonClass c) {
		String n = c.name().toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}

	/**
	 * {@code p} is the ONLY non-spectator on this class: the "solo" column of §1.14. Evaluated live, since parties
	 * are whatever players picked; the old fake layout (four Mages, one always-solo Archer) broke for real parties.
	 */
	public static boolean isSoloOnClass(Player p) {
		DungeonClass mine = of(p);
		int count = 0;
		for(Player other : Bukkit.getOnlinePlayers()) {
			if(other.getGameMode() == GameMode.SPECTATOR) continue;
			if(of(other) == mine) count++;
		}
		return count <= 1;
	}

	/** Default path for readouts ({@code /eq}, lore). Mage is BEAM: Mage Staff turns every melee into the beam (§1.11). */
	public DamagePath primaryPath() {
		return switch(this) {
			case MAGE -> DamagePath.BEAM;
			case ARCHER -> DamagePath.BOW;
			default -> DamagePath.MELEE;
		};
	}
}
