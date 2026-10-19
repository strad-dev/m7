package instructions.clear;

import java.util.ArrayList;
import java.util.List;

/**
 * Room definition (name, type, grid cells, miniboss/blessing, secrets) plus per-run state driving its checkmark.
 * Built once in {@link Rooms}; {@link #reset()} clears run state.
 *
 * <p>Checkmark rules ({@link #check()}):
 * <ul>
 *   <li>START / FAIRY: always GREEN, no mechanics.</li>
 *   <li>PUZZLE (Quiz, Ice Fill): GREEN when solved, no white stage.</li>
 *   <li>YELLOW: GREEN when its miniboss dies.</li>
 *   <li>NORMAL: WHITE when the miniboss dies (miniboss-less Wizard: last Crypt Lurker dies, on entry in classic), GREEN once all counted secrets found.</li>
 *   <li>TRAP: WHITE when the Power-II chest opens, GREEN once all secrets found.</li>
 *   <li>BLOOD: WHITE when the Watcher camp is cleared; no secrets, so effectively GREEN.</li>
 * </ul>
 */
public class Room {
	public enum Check {NONE, WHITE, GREEN}

	public final String name;
	public final RoomType type;
	/** Each {@code {gx, gz}}. */
	public final int[][] cells;
	/**
	 * Lowest and highest legal block Y. Measured, no rule derives them (Dino Dig Site drops to 36, Well and Museum
	 * reach 119). Used by {@code listeners.OutOfBounds} kill test and {@link Rooms#isCeiling} (unbreakable roof).
	 */
	public final int minY, maxY;
	/**
	 * Room depth (1..5), the Roman numeral shown on the room. Mob stats scale by {@code 1 + 0.10 x (depth - 1)}
	 * ({@code damage.MobStats.depthMultiplier}); observed 13.2M Angry Archaeologist in Deathmite (depth II) = 12M x 1.10.
	 * <p>
	 * Use it directly, don't BFS for depth: grid adjacency is not the door graph, so BFS gets exactly the missing
	 * rooms wrong.
	 */
	public final int level;
	public final boolean hasMiniboss;
	/** Granted on clear (miniboss kill / puzzle solve); empty for none. */
	public final Blessing[] clearBlessings;
	public final List<Secret> secrets = new ArrayList<>();

	/** Miniboss killed (NORMAL/YELLOW), Power-II chest opened (TRAP) or camp cleared (BLOOD). */
	public boolean cleared;
	public boolean solved;
	/** Any player has entered. Until then the map draws it grey with a "?". */
	public boolean explored;

	Room(String name, RoomType type, int[][] cells, int minY, int maxY, int level, boolean hasMiniboss, Blessing[] clearBlessings) {
		this.name = name;
		this.type = type;
		this.cells = cells;
		this.minY = minY;
		this.maxY = maxY;
		this.level = level;
		this.hasMiniboss = hasMiniboss;
		this.clearBlessings = clearBlessings;
	}

	Room addSecret(Secret s) {
		s.room = this;
		secrets.add(s);
		return this;
	}

	public int countedSecretTotal() {
		int n = 0;
		for(Secret s : secrets) if(s.counted) n++;
		return n;
	}

	public int countedSecretFound() {
		int n = 0;
		for(Secret s : secrets) if(s.counted && s.found) n++;
		return n;
	}

	public boolean allCountedFound() {
		return countedSecretFound() >= countedSecretTotal();
	}

	public Check check() {
		switch(type) {
			case START, FAIRY -> {
				return Check.GREEN;
			}
			case PUZZLE -> {
				return solved ? Check.GREEN : Check.NONE;
			}
			case YELLOW -> {
				return cleared ? Check.GREEN : Check.NONE;
			}
			case BLOOD, TRAP -> {
				if(!cleared) return Check.NONE;
				return allCountedFound() ? Check.GREEN : Check.WHITE;
			}
			case NORMAL -> {
				// Miniboss-less Wizard: ClearManager sets `cleared` (mobs dead, or entry in classic).
				if(!cleared) return Check.NONE;
				return allCountedFound() ? Check.GREEN : Check.WHITE;
			}
			default -> {
				return Check.NONE;
			}
		}
	}

	public void reset() {
		cleared = false;
		solved = false;
		explored = false;
		for(Secret s : secrets) {
			s.found = false;
			s.entityId = null;
		}
	}
}
