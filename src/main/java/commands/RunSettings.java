package commands;

/**
 * The six dungeon settings a run command may carry, shared by {@code /m7practice} and {@code /m7scenario}. Each is
 * optional: unset leaves the current setting, so standalone keeps its /dungeonsettings choice. The network always
 * sends all six, since every one is a server-wide global a run must not inherit from the last party.
 */
final class RunSettings {
	// Difficulty decides whether anyone can die (both live modes kill) and picks the leaderboard.
	damage.Difficulty difficulty;
	// Mob HP is written once at spawn, so the mayor must be right before anything spawns.
	damage.Mayor mayor;
	// Bare "on" / "off"; an inherited flag costs a clear record nobody chose to give up.
	plugin.WatcherFix watcherFix;
	// The rest are prefixed ("badbonzo=on"), since a bare on/off is Fix Watcher Bug's.
	plugin.BadBonzo badBonzo;
	plugin.BatBug batBug;
	plugin.AlphaGDrag alphaGDrag;

	/** Records {@code arg} if it is one of the six; false if it isn't, so the caller reads it as its own. */
	boolean accept(String arg) {
		String lower = arg.toLowerCase();
		if(lower.startsWith("alphagdrag=")) {
			plugin.AlphaGDrag v = plugin.AlphaGDrag.parse(arg.substring("alphagdrag=".length()));
			if(v != null) alphaGDrag = v;
			return true;
		}
		if(lower.startsWith("batbug=")) {
			plugin.BatBug v = plugin.BatBug.parse(arg.substring("batbug=".length()));
			if(v != null) batBug = v;
			return true;
		}
		if(lower.startsWith("badbonzo=")) {
			plugin.BadBonzo v = plugin.BadBonzo.parse(arg.substring("badbonzo=".length()));
			if(v != null) badBonzo = v;
			return true;
		}
		damage.Difficulty mode = damage.Difficulty.parse(arg);
		if(mode != null) {
			difficulty = mode;
			return true;
		}
		damage.Mayor m = damage.Mayor.parse(arg);
		if(m != null) {
			mayor = m;
			return true;
		}
		plugin.WatcherFix w = plugin.WatcherFix.parse(arg);
		if(w != null) {
			watcherFix = w;
			return true;
		}
		return false;
	}

	/** The difficulty the run will have once applied. */
	damage.Difficulty effectiveDifficulty() {
		return difficulty != null ? difficulty : damage.Difficulty.current();
	}

	/**
	 * Sets what was given, then names the experimental settings that are on. BEFORE the run arms: debuff, defense and
	 * blessing lookups read these live, and RunResult.capture stamps them at completion.
	 */
	void apply() {
		if(difficulty != null) damage.Difficulty.set(difficulty);
		if(mayor != null) damage.Mayor.set(mayor);
		if(watcherFix != null) plugin.WatcherFix.set(watcherFix);
		if(badBonzo != null) plugin.BadBonzo.set(badBonzo);
		if(batBug != null) plugin.BatBug.set(batBug);
		if(alphaGDrag != null) plugin.AlphaGDrag.set(alphaGDrag);
		Practice.broadcastExperimentalSettings();
	}
}
