package instructions.bosses.storm;

import org.bukkit.Sound;
import plugin.Utils;

/**
 * One pillar's oscillation. Storm polls every 20 ticks and calls {@link #runCycle(int)} when the pad is occupied.
 * Material spans {@link #bottomY} to the y196 anchor, which never moves. DOWN clones row bottomY to bottomY-1;
 * UP air-fills row bottomY.
 * <br>
 * Cycle motion (measured in-game):
 * <pre>
 *   bottomY at  | direction | this cycle's behavior
 *   cycle start | at start  |
 *   ------------|-----------|---------------------------------------
 *   limit (169 or 189) | (any) | stall 8t, reverse, 3 clones × 4t in new direction
 *   else, within 5 of limit  | (any) | (limit-bottomY) clones × 4t, then idle
 *   else                     | (any) | 5 clones × 4t
 * </pre>
 */
public final class PillarOscillator {
	private enum Direction {
		DOWN, UP;
		Direction reverse() { return this == DOWN ? UP : DOWN; }
	}

	private final PadAndPillar pillar;
	private int bottomY;
	private Direction direction;
	private int lastMovementTick;
	private boolean used;
	/** Set by {@link #markUsed()}; makes every still-queued {@link #moveOne} op a no-op. */
	private boolean frozen;

	public PillarOscillator(PadAndPillar pillar) {
		this.pillar = pillar;
		reset();
	}

	/** Bottom y175, DOWN, no movement, not used. */
	public void reset() {
		bottomY = PadAndPillar.PILLAR_BOTTOM_INITIAL;
		direction = Direction.DOWN;
		lastMovementTick = Integer.MIN_VALUE;
		used = false;
		frozen = false;
	}

	/** Mid-oscillation start ({@code /m7scenario}); blocks are the caller's, see {@link #placeFromInitial}. */
	void setState(int bottomY, boolean descending) {
		this.bottomY = bottomY;
		this.direction = descending ? Direction.DOWN : Direction.UP;
	}

	/** Blocks of a pillar at its setup bottom moved to {@code bottomY}, silently. */
	static void placeFromInitial(PadAndPillar pillar, int bottomY) {
		if(bottomY > PadAndPillar.PILLAR_BOTTOM_INITIAL) {
			Utils.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air",
					pillar.pillarX1(), PadAndPillar.PILLAR_BOTTOM_INITIAL, pillar.pillarZ1(),
					pillar.pillarX2(), bottomY - 1, pillar.pillarZ2()));
		}
		for(int y = PadAndPillar.PILLAR_BOTTOM_INITIAL; y > bottomY; y--) {
			Utils.runCommand(String.format("clone %d %d %d %d %d %d %d %d %d",
					pillar.pillarX1(), y, pillar.pillarZ1(),
					pillar.pillarX2(), y, pillar.pillarZ2(),
					pillar.pillarX1(), y - 1, pillar.pillarZ1()));
		}
	}

	public PadAndPillar getPillar() { return pillar; }
	public int getBottomY() { return bottomY; }

	/** Pillar has crushed Storm; its pad no longer activates it. */
	public boolean isUsed() { return used; }

	/**
	 * Pad goes dead and queued clone ops are neutered. The freeze matters: {@link #runCycle} queues up to five
	 * {@code moveOne}s over 16 ticks via {@code Utils.scheduleTask} (no cancel handle), and without it the rest of
	 * the cycle keeps descending into Storm in the 20 ticks before the crush explosion, re-burying him.
	 */
	public void markUsed() {
		used = true;
		freeze();
	}

	/** Neuters queued clone ops without consuming the pillar. Used when Storm dies mid-cycle. */
	public void freeze() {
		frozen = true;
	}

	/**
	 * One 20-tick cycle, a clone op every 4 ticks. Caller gates on pad presence. {@code currentTick} is recorded as
	 * the last movement for crush-detector arming.
	 */
	public void runCycle(int currentTick) {
		boolean atLimit = (direction == Direction.DOWN && bottomY == PadAndPillar.PILLAR_BOTTOM_MIN)
				|| (direction == Direction.UP && bottomY == PadAndPillar.PILLAR_BOTTOM_MAX);

		int delay;
		int clones;
		if(atLimit) {
			direction = direction.reverse();
			delay = 8;
			clones = 3;
		} else {
			int limit = (direction == Direction.DOWN) ? PadAndPillar.PILLAR_BOTTOM_MIN : PadAndPillar.PILLAR_BOTTOM_MAX;
			clones = Math.min(5, Math.abs(limit - bottomY));
			delay = 0;
		}

		for(int i = 0; i < clones; i++) {
			Utils.scheduleTask(this::moveOne, delay + i * 4L);
		}

		// Only downward motion arms the crush detector.
		if(direction == Direction.DOWN) {
			lastMovementTick = currentTick;
		}
	}

	private void moveOne() {
		if(frozen) return;
		if(direction == Direction.DOWN) {
			// Push Storm out from under before placing the row. At the floor this no-ops and the next poll's crush detector handles him.
			Storm.INSTANCE.tryPushBelowDescendingPillar(pillar, bottomY - 1);
			Utils.runCommand(
					String.format("clone %d %d %d %d %d %d %d %d %d",
							pillar.pillarX1(), bottomY, pillar.pillarZ1(),
							pillar.pillarX2(), bottomY, pillar.pillarZ2(),
							pillar.pillarX1(), bottomY - 1, pillar.pillarZ1()));
			bottomY--;
		} else {
			Utils.runCommand(
					String.format("fill %d %d %d %d %d %d minecraft:air",
							pillar.pillarX1(), bottomY, pillar.pillarZ1(),
							pillar.pillarX2(), bottomY, pillar.pillarZ2()));
			bottomY++;
		}
		Utils.playGlobalSound(Sound.BLOCK_PISTON_CONTRACT, 0.5f, 1.0f);
	}

	/** Descending AND a DOWN cycle started within {@code windowTicks}. An UP pillar never arms crush, even inside the window. */
	public boolean movedRecently(int currentTick, int windowTicks) {
		if(direction != Direction.DOWN) return false;
		return lastMovementTick != Integer.MIN_VALUE && (currentTick - lastMovementTick) <= windowTicks;
	}

	/**
	 * HUD counterpart of {@link #movedRecently}: armed ticks left counting {@code currentTick}, so
	 * {@code windowTicks + 1} on the DOWN cycle's first tick, 1 on the last, 0 if not armed.
	 */
	public int armedTicksLeft(int currentTick, int windowTicks) {
		if(!movedRecently(currentTick, windowTicks)) return 0;
		return windowTicks - (currentTick - lastMovementTick) + 1;
	}
}
