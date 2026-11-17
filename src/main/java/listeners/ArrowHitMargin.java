package listeners;

import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;

public class ArrowHitMargin implements Listener {
	private static final int FULL_HIT_MARGIN_AGE = 8;

	public static void fullHitMargin(AbstractArrow arrow) {
		if(arrow.getTicksLived() < FULL_HIT_MARGIN_AGE) {
			arrow.setTicksLived(FULL_HIT_MARGIN_AGE);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onLaunch(ProjectileLaunchEvent e) {
		if(e.getEntity() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player) {
			fullHitMargin(arrow);
		}
	}
}
