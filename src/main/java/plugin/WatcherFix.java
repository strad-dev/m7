package plugin;

import java.util.Locale;

/**
 * <b>Fix Watcher Bug</b>: third dungeon setting next to {@code damage/Difficulty} and {@code damage/Mayor} (MAP.md §0).
 * Off, the Watcher's second wave waits for phase tick 420 like Hypixel's; on, it starts 60t after he is back on his
 * perch ({@code Watcher.returnToOriginalPosition}).
 * <p>
 * A fixed run never reaches the Clear or Full Run boards; boss boards still take it. {@code plugin/RunResult.watcherFix}
 * carries the flag; the network's {@code Leaderboards.submit} drops those boards.
 */
public enum WatcherFix {
	/** Hypixel's behaviour. Default. */
	OFF,
	/** Second wave on his return. */
	ON;

	private static WatcherFix current = OFF;

	public static WatcherFix current() {
		return current;
	}

	public static void set(WatcherFix w) {
		if(w != null) current = w;
	}

	public static boolean enabled() {
		return current == ON;
	}

	/** Next value, wrapping. */
	public static WatcherFix toggle() {
		WatcherFix[] all = values();
		set(all[(current.ordinal() + 1) % all.length]);
		return current;
	}

	/** Previous value, wrapping: a right-click on the menu button. */
	public static WatcherFix toggleBack() {
		WatcherFix[] all = values();
		set(all[(current.ordinal() + all.length - 1) % all.length]);
		return current;
	}

	/** Parse any case, or null. Accepts {@link #id()} (the network's ids) and hand-typed spellings. */
	public static WatcherFix parse(String s) {
		if(s == null) return null;
		return switch(s.toLowerCase(Locale.ROOT)) {
			case "on", "fixed", "true", "enabled", "yes" -> ON;
			case "off", "normal", "false", "disabled", "no" -> OFF;
			default -> null;
		};
	}

	/** Lower-case id for the run payload and the network's stored setting. Must match the network's. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
