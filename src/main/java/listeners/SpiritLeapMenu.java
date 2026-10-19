package listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.NonNull;
import plugin.Utils;

import java.util.*;

/**
 * Practice Spirit Leap GUI: 5-row chest, up to four teammates. Each owns a quadrant of class-coloured glass (Archer
 * green, Berserk red, Healer yellow, Mage light blue, Tank gray) with their head in the corner; any click in it leaps
 * to them. Middle row and column are white dividers.
 * <p>
 * A class always lands in the same quadrant ({@link #arrange}), so the leap becomes muscle memory.
 * <p>
 * The instance is the {@link InventoryHolder} and carries the slot-to-teammate map for {@link SpiritLeapListener}.
 */
public class SpiritLeapMenu implements InventoryHolder {
	private static final int SIZE = 45; // 5 rows x 9

	// Quadrant slot groups (cols 0-3 / 5-8, rows 0-1 / 3-4) and each quadrant's outer corner.
	private static final int[][] QUADRANTS = {
			{0, 1, 2, 3, 9, 10, 11, 12},        // top-left
			{5, 6, 7, 8, 14, 15, 16, 17},       // top-right
			{27, 28, 29, 30, 36, 37, 38, 39},   // bottom-left
			{32, 33, 34, 35, 41, 42, 43, 44}    // bottom-right
	};
	private static final int[] CORNERS = {0, 8, 36, 44};
	/** Order classes claim quadrants. Also {@link #candidates}' sort order, so two of one class split the same way. */
	private static final List<String> CLASS_ORDER = List.of("Archer", "Berserk", "Healer", "Mage", "Tank");
	// Middle row (18-26) and middle column (4,13,22,31,40) are dividers.
	private static final int[] CROSS = {4, 13, 18, 19, 20, 21, 22, 23, 24, 25, 26, 31, 40};

	private final Inventory inv;
	private final Map<Integer, Player> slotTargets = new HashMap<>();

	private SpiritLeapMenu(Player viewer) {
		inv = Bukkit.createInventory(this, SIZE, Utils.msg("<dark_gray>Spirit Leap"));
		build(viewer);
	}

	public static void open(Player viewer) {
		viewer.openInventory(new SpiritLeapMenu(viewer).inv);
	}

	/** True if there's at least one teammate the viewer could leap to. */
	public static boolean hasCandidates(Player viewer) {
		return !candidates(viewer).isEmpty();
	}

	@Override
	public @NonNull Inventory getInventory() {
		return inv;
	}

	/** Teammate a slot leaps to, or null for dividers / empty quadrants / the player's own inventory. */
	public Player targetForSlot(int slot) {
		return slotTargets.get(slot);
	}

	private void build(Player viewer) {
		ItemStack divider = pane(Material.WHITE_STAINED_GLASS_PANE, " ");
		for(int s : CROSS) inv.setItem(s, divider);

		Player[] bySpot = arrange(viewer, candidates(viewer));
		for(int q = 0; q < QUADRANTS.length; q++) {
			Player t = bySpot[q];
			if(t == null) continue;
			String cls = resolveClass(t);
			ItemStack glass = pane(classPane(cls), classColor(cls) + "Leap to " + t.getName());
			for(int s : QUADRANTS[q]) {
				inv.setItem(s, glass);
				slotTargets.put(s, t);
			}
			inv.setItem(CORNERS[q], head(t, cls)); // head also leaps on click
		}
	}

	/**
	 * Quadrant per teammate. A class's HOME quadrant is its place in {@link #CLASS_ORDER} minus the viewer's own class:
	 * Archer is top-left for a Mage, Berserk is for an Archer. A taken home (second player of a class) falls to the
	 * lowest free quadrant; {@link #candidates} caps at four, so there is always room.
	 */
	private static Player[] arrange(Player viewer, List<Player> targets) {
		List<String> home = new ArrayList<>(CLASS_ORDER);
		home.remove(resolveClass(viewer)); // four classes left for four quadrants
		Player[] spots = new Player[QUADRANTS.length];
		List<Player> overflow = new ArrayList<>();
		for(Player t : targets) {
			int spot = home.indexOf(resolveClass(t));
			if(spot >= 0 && spot < spots.length && spots[spot] == null) spots[spot] = t;
			else overflow.add(t);
		}
		for(Player t : overflow) {
			for(int s = 0; s < spots.length; s++) {
				if(spots[s] == null) {
					spots[s] = t;
					break;
				}
			}
		}
		return spots;
	}

	/** Online, non-spectating players other than the viewer, by class then name, capped at 4. */
	private static List<Player> candidates(Player viewer) {
		List<Player> list = new ArrayList<>();
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(p.equals(viewer)) continue;
			if(Utils.isSpectator(p)) continue;
			list.add(p);
		}
		list.sort(Comparator.comparingInt((Player p) -> CLASS_ORDER.indexOf(resolveClass(p))).thenComparing(Player::getName));
		return list.size() > 4 ? new ArrayList<>(list.subList(0, 4)) : list;
	}

	/** Class from the player's scoreboard tag (set by /class), else Mage. */
	public static String resolveClass(Player p) {
		for(String c : new String[]{"Archer", "Berserk", "Healer", "Mage", "Tank"}) {
			if(p.getScoreboardTags().contains(c)) return c;
		}
		return "Mage";
	}

	private static Material classPane(String cls) {
		return switch(cls) {
			case "Archer" -> Material.GREEN_STAINED_GLASS_PANE;
			case "Berserk" -> Material.RED_STAINED_GLASS_PANE;
			case "Healer" -> Material.YELLOW_STAINED_GLASS_PANE;
			case "Tank" -> Material.GRAY_STAINED_GLASS_PANE;
			default -> Material.LIGHT_BLUE_STAINED_GLASS_PANE; // Mage
		};
	}

	private static String classColor(String cls) {
		return switch(cls) {
			case "Archer" -> "<green>";
			case "Berserk" -> "<red>";
			case "Healer" -> "<yellow>";
			case "Tank" -> "<gray>";
			default -> "<aqua>"; // Mage (light blue)
		};
	}

	private static ItemStack pane(Material mat, String name) {
		ItemStack it = new ItemStack(mat);
		ItemMeta meta = it.getItemMeta();
		meta.displayName(Utils.mm(name));
		it.setItemMeta(meta);
		return it;
	}

	private static ItemStack head(Player target, String cls) {
		ItemStack it = new ItemStack(Material.PLAYER_HEAD);
		SkullMeta meta = (SkullMeta) it.getItemMeta();
		meta.setOwningPlayer(target);
		meta.displayName(Utils.mm(classColor(cls) + target.getName()));
		it.setItemMeta(meta);
		return it;
	}
}
