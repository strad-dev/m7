package plugin;

import java.util.Locale;

/**
 * <b>Bat Bug</b>: an experimental dungeon setting. On, each player's first bat kill grants +1 score, Hypixel's
 * shard bug (read by {@code instructions/clear/ClearManager.bonus}). Carried on {@link RunResult#batBug}: no 300 Score
 * or Full Clear board, never S+.
 */
public enum BatBug {
	/** First bat killed by any player grants +1 score. Default. */
	OFF,
	/** First bat killed by each player grants +1 score. */
	ON;

	private static BatBug current = OFF;

	public static BatBug current() {
		return current;
	}

	public static void set(BatBug b) {
		if(b != null) current = b;
	}

	public static boolean enabled() {
		return current == ON;
	}

	/** Next value, wrapping. */
	public static BatBug toggle() {
		BatBug[] all = values();
		set(all[(current.ordinal() + 1) % all.length]);
		return current;
	}

	/** Previous value, wrapping: a right-click on the menu button. */
	public static BatBug toggleBack() {
		BatBug[] all = values();
		set(all[(current.ordinal() + all.length - 1) % all.length]);
		return current;
	}

	/** Parse any case, or null. Accepts {@link #id()} (the network's ids) and hand-typed spellings. */
	public static BatBug parse(String s) {
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
