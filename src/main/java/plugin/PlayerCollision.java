package plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;

public class PlayerCollision {
	private static Team noCollisionTeam;

	public static void setupNoCollisionTeam() {
		ScoreboardManager manager = Bukkit.getScoreboardManager();
		Scoreboard scoreboard = manager.getMainScoreboard();

		Team existingTeam = scoreboard.getTeam("nocollision");
		if(existingTeam != null) {
			existingTeam.unregister();
		}

		noCollisionTeam = scoreboard.registerNewTeam("nocollision");
		noCollisionTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
		noCollisionTeam.setCanSeeFriendlyInvisibles(false);
	}

	public static void addToNoCollisionTeam(Player player) {
		if(noCollisionTeam != null) {
			noCollisionTeam.addEntry(player.getName());
		}
	}

	public static void removeFromNoCollisionTeam(Player player) {
		if(noCollisionTeam != null) {
			noCollisionTeam.removeEntry(player.getName());
		}
	}

	public static void addEntityToNoCollisionTeam(Entity entity) {
		if(noCollisionTeam != null) {
			noCollisionTeam.addEntry(entity.getUniqueId().toString());
		}
	}

	public static void removeEntityFromNoCollisionTeam(Entity entity) {
		if(noCollisionTeam != null) {
			noCollisionTeam.removeEntry(entity.getUniqueId().toString());
		}
	}

	public static void cleanup() {
		if(noCollisionTeam != null) {
			for(String entry : new HashSet<>(noCollisionTeam.getEntries())) {
				noCollisionTeam.removeEntry(entry);
			}
			noCollisionTeam.unregister();
		}
	}
}
