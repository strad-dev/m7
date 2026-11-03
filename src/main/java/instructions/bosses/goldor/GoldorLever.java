package instructions.bosses.goldor;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import plugin.Utils;

public final class GoldorLever {
	private static final String INACTIVE_TEXT = "<red>Not Activated";
	private static final String ACTIVE_TEXT = "<green>Activated";

	public final int sectionIdx;
	public final int leverIdx;
	public final int x, y, z;

	private final TextDisplay display;
	private boolean activated = false;

	public GoldorLever(World world, int sectionIdx, int leverIdx, int x, int y, int z) {
		this.sectionIdx = sectionIdx;
		this.leverIdx = leverIdx;
		this.x = x;
		this.y = y;
		this.z = z;
		Location displayLoc = new Location(world, x + 0.5, y + 0.625, z + 0.5);
		this.display = world.spawn(displayLoc, TextDisplay.class, e -> {
			e.text(Utils.msg(INACTIVE_TEXT));
			e.setBillboard(Display.Billboard.CENTER);
			e.setAlignment(TextDisplay.TextAlignment.CENTER);
			e.addScoreboardTag("TASNoName");
		});
	}

	public boolean isActivated() {
		return activated;
	}

	/** Inert for a /m7scenario: label gone, flipping it does nothing. */
	private boolean disabled = false;

	public void disable() {
		disabled = true;
		cleanup();
	}

	public boolean isDisabled() {
		return disabled;
	}

	public boolean isLeverBlock(int bx, int by, int bz) {
		return bx == x && by == y && bz == z;
	}

	public void markActivated() {
		activated = true;
		display.text(Utils.msg(ACTIVE_TEXT));
	}

	public void cleanup() {
		if(display != null && display.isValid()) display.remove();
	}
}
