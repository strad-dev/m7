package instructions.clear;

import instructions.Server;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import plugin.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Ice Fill puzzle (grid 1,4). Three levels at y=69/70/71; the player freezes every ice block of the active level
 * in one continuous stroke: no diagonals, no doubling back, no skipping. A violation breaks the layer and resets
 * it after 60 ticks. All three done → {@link ClearManager#puzzleSolved} (green check) and
 * {@link Server#openIceFillRewards()} reveals the two reward chests; their Power-V blessings are claimed by
 * opening them.
 */
public final class PuzzleIceFill {
	private PuzzleIceFill() {
	}

	private static final int[] LEVEL_Y = {69, 70, 71};
	private static final int RESET_TICKS = 60;

	// Per-layer boxes {minX, minZ, maxX, maxZ, y}. Only ice inside counts.
	private static final int[][] LAYER_BOX = {
			{-52, -154, -49, -152, 69}, // layer 1
			{-59, -155, -54, -151, 70}, // layer 2
			{-68, -156, -61, -150, 71}, // layer 3
	};

	@SuppressWarnings("unchecked")
	private static final List<int[]>[] levels = new List[3];

	private static World world;
	private static int currentLevel;
	private static int[] previous; // coords of the last frozen block, or null
	private static int frozenCount;
	private static boolean solved, failing;
	private static java.util.UUID solver;
	private static int gen;

	public static void begin(World w) {
		world = w;
		gen++;
		currentLevel = 0;
		previous = null;
		frozenCount = 0;
		solved = failing = false;
		solver = null;
		for(int l = 0; l < 3; l++) {
			levels[l] = new ArrayList<>();
			int[] box = LAYER_BOX[l];
			int y = box[4];
			for(int x = box[0]; x <= box[2]; x++) {
				for(int z = box[1]; z <= box[3]; z++) {
					Block b = w.getBlockAt(x, y, z);
					if(b.getType() == Material.ICE || b.getType() == Material.PACKED_ICE) {
						if(b.getType() == Material.PACKED_ICE) b.setType(Material.ICE, false); // fresh start
						levels[l].add(new int[]{x, y, z});
					}
				}
			}
		}
		Utils.debug(Utils.DebugType.SERVER, "Ice Fill scan: L1=" + levels[0].size() + " L2=" + levels[1].size() + " L3=" + levels[2].size());
	}

	public static void stop() {
		gen++;
		if(world != null) {
			for(int l = 0; l < 3; l++) {
				if(levels[l] == null) continue;
				for(int[] c : levels[l]) {
					Block b = world.getBlockAt(c[0], c[1], c[2]);
					if(b.getType() == Material.PACKED_ICE || b.getType() == Material.AIR) b.setType(Material.ICE, false);
				}
				levels[l] = null;
			}
		}
	}

	public static void tick(World w, List<Player> players) {
		if(solved || failing || levels[0] == null) return;
		int y = LEVEL_Y[currentLevel];
		for(Player p : players) {
			if(solver != null && !p.getUniqueId().equals(solver)) continue;
			Block below = p.getLocation().getBlock().getRelative(0, -1, 0);
			if(below.getY() != y) continue;
			if(!inCurrentLevel(below.getX(), below.getZ())) continue;
			step(p, below);
		}
	}

	private static void step(Player p, Block below) {
		int[] here = {below.getX(), below.getY(), below.getZ()};
		if(previous != null && sameCoords(here, previous)) return; // standing on the last block
		if(below.getType() == Material.PACKED_ICE) { // already frozen → doubling back
			fail(p, "<red>Oops!  You stepped on the wrong block!");
			return;
		}
		if(below.getType() != Material.ICE) return;
		if(previous == null) {
			solver = p.getUniqueId();
			freeze(p, below, here);
		} else if(adjacent(here, previous)) {
			freeze(p, below, here);
		} else {
			fail(p, "<red>Don't move diagonally!  Bad!"); // diagonal or non-adjacent
		}
	}

	private static void freeze(Player p, Block below, int[] here) {
		below.setType(Material.PACKED_ICE, false);
		previous = here;
		frozenCount++;
		world.playSound(below.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_SNOW_BREAK, 2.0f, 1.0f);
		if(frozenCount >= levels[currentLevel].size()) levelComplete(p);
	}

	private static void levelComplete(Player p) {
		Server.IceFill.playIceFillSounds(currentLevel + 1, p); // per-level tritone
		currentLevel++;
		previous = null;
		frozenCount = 0;
		solver = null;
		while(currentLevel < 3 && levels[currentLevel].isEmpty()) currentLevel++; // skip empty levels (safety)
		if(currentLevel >= 3) {
			solved = true;
			Server.openIceFillRewards();
			ClearManager.puzzleSolved(Rooms.ICE_FILL, p); // green check (reward chests are opened by hand)
			// Gate-opening sound: pressure-plate click every 5t for 40t, to the completer.
			for(int t = 0; t <= 40; t += 5) {
				Utils.scheduleTask(() -> Utils.playLocalSound(p, Sound.BLOCK_WOODEN_PRESSURE_PLATE_CLICK_ON, 2.0f, 0.5f), t);
			}
		}
	}

	private static void fail(Player p, String message) {
		failing = true;
		solver = null;
		previous = null;
		frozenCount = 0;
		Bukkit.broadcast(Utils.msg(message));
		final int level = currentLevel;
		final int g = gen;
		for(int[] c : levels[level]) {
			world.getBlockAt(c[0], c[1], c[2]).setType(Material.AIR, false);
			// Vanilla plays a block's break sound at soundType.pitch * 0.8; ice uses the GLASS type, so 0.8.
			world.playSound(new Location(world, c[0] + 0.5, c[1] + 0.5, c[2] + 0.5), Sound.BLOCK_GLASS_BREAK, 2.0f, 0.8f);
		}
		Utils.scheduleTask(() -> {
			if(g != gen || solved) return;
			for(int[] c : levels[level]) {
				Block b = world.getBlockAt(c[0], c[1], c[2]);
				if(b.getType() == Material.AIR) b.setType(Material.ICE, false);
			}
			failing = false;
		}, RESET_TICKS);
	}

	private static boolean inCurrentLevel(int x, int z) {
		for(int[] c : levels[currentLevel]) if(c[0] == x && c[2] == z) return true;
		return false;
	}

	private static boolean adjacent(int[] a, int[] b) {
		return a[1] == b[1] && Math.abs(a[0] - b[0]) + Math.abs(a[2] - b[2]) == 1;
	}

	private static boolean sameCoords(int[] a, int[] b) {
		return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
	}
}
