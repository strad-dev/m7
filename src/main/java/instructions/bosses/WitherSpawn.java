package instructions.bosses;

import instructions.bosses.storm.PadAndPillar;
import org.bukkit.World;
import plugin.Utils;

/** Wither-boss helpers not owned by one {@link WitherLord} subclass, e.g. the Storm pillar restore on every run. */
public final class WitherSpawn {
	private WitherSpawn() {}

	/**
	 * Restores the three active Storm pillars to y175..y196, air at y169..y174. Runs on every run setup,
	 * not just Storm's start, so the world resets across all runs. Air-clears y169..y175 (a pillar left
	 * over-extended), then clones down from the y196 anchor, doubling each pass: ~5 clones instead of 21.
	 */
	public static void restoreStormPillars(World world) {
		for(PadAndPillar p : PadAndPillar.ACTIVE) {
			// Air-clear y169..y175 inclusive.
			Utils.runCommand(
					String.format("fill %d %d %d %d %d %d minecraft:air",
							p.pillarX1(), PadAndPillar.PILLAR_BOTTOM_MIN, p.pillarZ1(),
							p.pillarX2(), PadAndPillar.PILLAR_BOTTOM_INITIAL, p.pillarZ2()));

			// Each pass copies the lowest `rowsToAdd` filled rows just below, doubling the column, capped at PILLAR_BOTTOM_INITIAL.
			int bottom = PadAndPillar.PILLAR_ANCHOR_Y; // filled down to here
			while(bottom > PadAndPillar.PILLAR_BOTTOM_INITIAL) {
				int filledRows = PadAndPillar.PILLAR_ANCHOR_Y - bottom + 1;
				int rowsToAdd = Math.min(filledRows, bottom - PadAndPillar.PILLAR_BOTTOM_INITIAL);
				int srcY1 = bottom;
				int srcY2 = bottom + rowsToAdd - 1;
				int dstY1 = bottom - rowsToAdd;
				Utils.runCommand(
						String.format("clone %d %d %d %d %d %d %d %d %d",
								p.pillarX1(), srcY1, p.pillarZ1(),
								p.pillarX2(), srcY2, p.pillarZ2(),
								p.pillarX1(), dstY1, p.pillarZ1()));
				bottom = dstY1;
			}
		}
	}
}
