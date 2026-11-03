package instructions.bosses.goldor;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import plugin.Utils;

/**
 * One Goldor terminal: Interaction hitbox plus two floating labels. In classic a click activates it. In both live
 * modes it opens {@link GoldorTerminalGui} (realistic's generated puzzle or Perfect RNG's stand-in) and only solving
 * it activates. Either way {@link #markActivated} is the single finish line.
 */
public final class GoldorTerminal {
	public static final String TAG_PREFIX = "goldor_terminal_";

	private static final String INACTIVE_LINE_1 = "<red>Inactive Terminal";
	private static final String INACTIVE_LINE_2 = "<green><bold>CLICK HERE";
	private static final String ACTIVE_TEXT = "<green>Terminal Active";

	public final int sectionIdx;
	public final int terminalIdx;

	private final Interaction interaction;
	private final TextDisplay displayTop;
	private final TextDisplay displayBottom;
	private boolean activated = false;
	private boolean pending = false;
	/**
	 * Live-mode puzzle. Assigned by the SECTION ({@link GoldorTerminalGui#assignTypes}): "at most one of each type
	 * per section" needs the whole set. Set once per phase, so reopening keeps the same puzzle.
	 */
	private GoldorTerminalGui.Type type;

	/** Hitbox block. {@link GoldorTerminalGui#assignTypes} reads it for the stand-in Melody pin (S2's fifth, {@code 40 124 123}). */
	public final int x, y, z;

	public GoldorTerminal(World world, int sectionIdx, int terminalIdx, int x, int y, int z) {
		this.sectionIdx = sectionIdx;
		this.terminalIdx = terminalIdx;
		this.x = x;
		this.y = y;
		this.z = z;

		Location interactionLoc = new Location(world, x + 0.5, y, z + 0.5);
		// Two TextDisplays so the gap between them has no background.
		Location bottomLoc = new Location(world, x + 0.5, y + 1.0, z + 0.5);
		Location topLoc = new Location(world, x + 0.5, y + 1.375, z + 0.5);

		this.interaction = world.spawn(interactionLoc, Interaction.class, e -> {
			e.setInteractionWidth(1.0f);
			e.setInteractionHeight(1.5f);
			e.setResponsive(true);
			e.addScoreboardTag(TAG_PREFIX + sectionIdx + "_" + terminalIdx);
			e.addScoreboardTag("TASNoName");
		});

		this.displayTop = world.spawn(topLoc, TextDisplay.class, e -> {
			e.text(Utils.msg(INACTIVE_LINE_1));
			e.setBillboard(Display.Billboard.CENTER);
			e.setAlignment(TextDisplay.TextAlignment.CENTER);
			e.addScoreboardTag("TASNoName");
		});

		this.displayBottom = world.spawn(bottomLoc, TextDisplay.class, e -> {
			e.text(Utils.msg(INACTIVE_LINE_2));
			e.setBillboard(Display.Billboard.CENTER);
			e.setAlignment(TextDisplay.TextAlignment.CENTER);
			e.addScoreboardTag("TASNoName");
		});
	}

	public boolean isActivated() {
		return activated;
	}

	/** Inert for a /m7scenario: hitbox and labels removed. */
	private boolean disabled = false;

	public void disable() {
		disabled = true;
		cleanup();
	}

	public boolean isDisabled() {
		return disabled;
	}

	public boolean isPending() {
		return pending;
	}

	public void setPending() {
		pending = true;
	}

	/** Frees the terminal for someone else; closing an unsolved puzzle lands here. */
	public void clearPending() {
		pending = false;
	}

	public GoldorTerminalGui.Type type() {
		return type;
	}

	/** Only {@link GoldorTerminalGui#assignTypes}, once, as the section is built. */
	void setType(GoldorTerminalGui.Type type) {
		this.type = type;
	}

	public void markActivated() {
		activated = true;
		pending = false;
		// Top emptied (no background when empty), active label goes on the bottom.
		displayTop.text(Utils.msg(""));
		displayBottom.text(Utils.msg(ACTIVE_TEXT));
	}

	public void cleanup() {
		// Don't leave anyone in a puzzle for a terminal that no longer exists.
		for(Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
			if(p.getOpenInventory().getTopInventory().getHolder() instanceof GoldorTerminalGui gui
					&& gui.terminal() == this) {
				p.closeInventory();
			}
		}
		if(interaction != null && interaction.isValid()) interaction.remove();
		if(displayTop != null && displayTop.isValid()) displayTop.remove();
		if(displayBottom != null && displayBottom.isValid()) displayBottom.remove();
	}

	public static int[] parseTag(String tag) {
		if(!tag.startsWith(TAG_PREFIX)) return null;
		String[] parts = tag.substring(TAG_PREFIX.length()).split("_");
		if(parts.length != 2) return null;
		try {
			return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
