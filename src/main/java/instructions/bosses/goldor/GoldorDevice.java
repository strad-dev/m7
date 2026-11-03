package instructions.bosses.goldor;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import plugin.Utils;

public final class GoldorDevice {
	private static final String INACTIVE_TEXT = "<red>Inactive\nDevice";
	private static final String ACTIVE_TEXT = "<green>Device\nActive";

	public final int sectionIdx;
	private final TextDisplay display;
	private boolean activated = false;

	public GoldorDevice(World world, int sectionIdx, int x, int y, int z) {
		this(world, sectionIdx, x, y, z, 1.5);
	}

	public GoldorDevice(World world, int sectionIdx, int x, int y, int z, double yOffset) {
		this.sectionIdx = sectionIdx;
		Location displayLoc = new Location(world, x + 0.5, y + yOffset, z + 0.5);
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

	/** Inert for a /m7scenario: label gone, no input reaches it. */
	private boolean disabled = false;

	public void disable() {
		disabled = true;
		cleanup();
	}

	public boolean isDisabled() {
		return disabled;
	}

	/** Done or disabled: input has nothing to progress. */
	public boolean isClosed() {
		return activated || disabled;
	}

	public void markActivated() {
		activated = true;
		display.text(Utils.msg(ACTIVE_TEXT));
	}

	public void cleanup() {
		if(display != null && display.isValid()) display.remove();
	}
}
