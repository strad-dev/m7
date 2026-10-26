package plugin;

import java.util.Locale;

/**
 * <b>Alpha GDrag Damage</b>: an experimental dungeon setting. On, the Golden Dragon's base Strength is +370 instead of
 * +300 (read by {@code damage/Pet.ownStats}, so Chimera's copy follows). Leaderboards still take the run.
 */
public enum AlphaGDrag {
	/** +300 Strength. Default. */
	OFF,
	/** +370 Strength. */
	ON;

	private static AlphaGDrag current = OFF;

	public static AlphaGDrag current() {
		return current;
	}

	public static void set(AlphaGDrag a) {
		if(a == null || a == current) return;
		current = a;
		// Cached aggregates and Chimera lore both hold the old Strength.
		damage.Stats.invalidateAll();
		for(org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) damage.StatLore.refreshChimeraLore(p);
	}

	public static boolean enabled() {
		return current == ON;
	}

	/** Next value, wrapping. */
	public static AlphaGDrag toggle() {
		AlphaGDrag[] all = values();
		set(all[(current.ordinal() + 1) % all.length]);
		return current;
	}

	/** Previous value, wrapping: a right-click on the menu button. */
	public static AlphaGDrag toggleBack() {
		AlphaGDrag[] all = values();
		set(all[(current.ordinal() + all.length - 1) % all.length]);
		return current;
	}

	/** Parse any case, or null. Accepts {@link #id()} (the network's ids) and hand-typed spellings. */
	public static AlphaGDrag parse(String s) {
		if(s == null) return null;
		return switch(s.toLowerCase(Locale.ROOT)) {
			case "on", "true", "enabled", "yes" -> ON;
			case "off", "false", "disabled", "no" -> OFF;
			default -> null;
		};
	}

	/** Lower-case id for the run payload and the network's stored setting. Must match the network's. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
