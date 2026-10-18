package pets;

import loadout.SpectatorGuiAccess;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import plugin.M7;
import plugin.Menus;
import plugin.Utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Window a rule in {@link AutopetMenu} opens: a wall of pet heads to click.
 * <p>
 * Replaced a click-to-step button (six clicks to get back with five pets plus "off", other values hidden).
 * <b>One class, three {@link Mode}s</b>, since furniture, containment, spectator bypass and return ordering are
 * shared and a copy would drift:
 * <ul>
 *   <li>{@link Mode#PET} - single select, which pet the rule summons. Has an explicit <b>Off</b> button, since a
 *       rule with no pet fires nothing and there must be a way back to that;</li>
 *   <li>{@link Mode#EXCEPTIONS} - multi select, toggling pets in the exception list;</li>
 *   <li>{@link Mode#CYCLE} - Rod Swap's ordered rotation: a click APPENDS, so any pet can appear anywhere, any
 *       number of times.</li>
 * </ul>
 *
 * <h2>Nothing here is an inventory</h2>
 * <b>Every click is cancelled, both inventories, before anything is read</b>, as in {@link PetMenu} and
 * {@code goldor/GoldorTerminalGui}. Nothing reaches the cursor, so unlike {@link PetMenu}'s arranging mode there's
 * no session to unwind.
 *
 * <h2>Not a listener of its own</h2>
 * {@link AutopetMenu} owns the instance and forwards the three inventory events, so no line in
 * {@code M7.onEnable}. One registration for the whole {@code /pets} tree also keeps the open/close ordering
 * below honest: the same handler sees both windows' closes.
 *
 * <h2>The close always goes back</h2>
 * Escape lands you in the autopet menu, not nothing. Reopen is deferred a tick for the ordering reason in
 * {@link PetMenu}: the old view's close fires before the new one opens. {@link #swapping} stops deliberate swaps
 * reopening the menu twice.
 */
public final class PetPicker {

	/** What a click in this window means. */
	public enum Mode {
		/** Pick the pet a rule summons. Choosing closes the picker. */
		PET,
		/** Toggle pets in the exception list. Stays open. */
		EXCEPTIONS,
		/** Append to Rod Swap's cycle. Stays open. */
		CYCLE
	}

	private static final int SIZE = 27;
	private static final int HEADER_SLOT = 4;
	/** Middle row, heads centred along it. Nine squares, five pets. */
	private static final int ROW_START = 9, ROW_WIDTH = 9;
	/** Bottom row buttons. Sides are per mode; middle always goes back. */
	private static final int LEFT_SLOT = 20, BACK_SLOT = 22, RIGHT_SLOT = 24;

	/** Parent window; every exit returns to it. */
	private final AutopetMenu parent;

	/**
	 * Players whose picker the menu itself is swapping out (Back, or a pick in {@link Mode#PET}). Reopen is already
	 * scheduled, so {@link #onClose} must not schedule a second (open, close, open a tick later).
	 */
	private final Set<UUID> swapping = new HashSet<>();

	PetPicker(AutopetMenu parent) {
		this.parent = parent;
	}

	void open(Player p, Autopet.Trigger t, Mode mode) {
		Holder h = new Holder(t, mode);
		Inventory gui = Bukkit.createInventory(h, SIZE, Utils.msg("<dark_gray>" + title(t, mode)));
		h.inv = gui;
		draw(p, h);
		p.openInventory(gui);
		// Idle m7 spectators get container clicks refused; armed after open, removed in onClose, like PetMenu.
		SpectatorGuiAccess.install(p);
	}

	private static String title(Autopet.Trigger t, Mode mode) {
		return switch(mode) {
			case PET -> "Summon: " + t.displayName();
			case EXCEPTIONS -> "Except: " + t.displayName();
			case CYCLE -> "Rod Swap Cycle";
		};
	}

	// ==================== drawing ====================

	private void draw(Player p, Holder h) {
		Inventory gui = h.inv;
		for(int slot = 0; slot < SIZE; slot++) gui.setItem(slot, PetMenu.button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
		gui.setItem(HEADER_SLOT, header(p, h));

		PetType[] pets = PetType.values();
		for(int i = 0; i < pets.length && i < ROW_WIDTH; i++) gui.setItem(slotOf(i), entry(p, h, pets[i]));

		switch(h.mode) {
			case PET -> {
				gui.setItem(LEFT_SLOT, PetMenu.button(Material.BARRIER, "<red>Off",
						List.of("<gray>Fire nothing on this trigger.", "", "<yellow>Click to turn the rule off")));
				gui.setItem(BACK_SLOT, PetMenu.button(Material.ARROW, "<yellow>Back",
						List.of("<gray>Leave the rule as it is.")));
			}
			case EXCEPTIONS -> gui.setItem(BACK_SLOT, PetMenu.button(Material.ARROW, "<yellow>Done", List.of()));
			case CYCLE -> {
				gui.setItem(LEFT_SLOT, PetMenu.button(Material.SHEARS, "<yellow>Remove Last",
						List.of("<gray>Drop the last pet off the end", "<gray>of the cycle.")));
				gui.setItem(RIGHT_SLOT, PetMenu.button(Material.LAVA_BUCKET, "<red>Clear Cycle",
						List.of("<gray>Empty the cycle, which turns", "<gray>the Rod Swap rule off.")));
				gui.setItem(BACK_SLOT, PetMenu.button(Material.ARROW, "<yellow>Done", List.of()));
			}
		}
	}

	/** Slot of the i-th pet: centred in the middle row, so five of nine start at slot 11. */
	private static int slotOf(int i) {
		return ROW_START + Math.max(0, (ROW_WIDTH - Math.min(PetType.values().length, ROW_WIDTH)) / 2) + i;
	}

	/** Pet on a slot, or null if not a head. */
	private static PetType petAt(int slot) {
		PetType[] pets = PetType.values();
		for(int i = 0; i < pets.length && i < ROW_WIDTH; i++) if(slotOf(i) == slot) return pets[i];
		return null;
	}

	private ItemStack header(Player p, Holder h) {
		List<String> lore = new ArrayList<>();
		switch(h.mode) {
			case PET -> {
				PetType pet = rulePet(p, h.trigger);
				lore.add("<gray>Summons: " + (pet == null ? "<dark_gray>Off" : pet.colouredName()));
				lore.add("");
				lore.add("<gray>Pick the pet this trigger");
				lore.add("<gray>summons.  One only.");
			}
			case EXCEPTIONS -> {
				List<PetType> except = exceptions(p, h.trigger);
				lore.add("<gray>The rule will NOT fire while");
				lore.add("<gray>one of these is already out:");
				if(except.isEmpty()) lore.add("<dark_gray>  nothing");
				else for(PetType e : except) lore.add("<gray>  " + e.colouredName());
			}
			case CYCLE -> {
				lore.add("<gray>Each throw of your Pitchin' Rod");
				lore.add("<gray>steps one place along:");
				lore.addAll(cycleLines(Pets.rodCycle(p)));
			}
		}
		Material mat = h.mode == Mode.CYCLE ? Material.FISHING_ROD : h.trigger.icon();
		return PetMenu.button(mat, "<gold>" + title(h.trigger, h.mode), lore);
	}

	/**
	 * A pet head with the last lore line swapped for what a click here does.
	 * <p>
	 * Built by {@link PetType#icon}, the one place head texture and tooltip are assembled; a second NBT copy is
	 * what CLAUDE.md warns makes saved stacks stop matching. Only the trailing "Click to summon!" line is
	 * rewritten. Glint is free: {@code icon} sets it from the flag passed as "selected".
	 */
	private ItemStack entry(Player p, Holder h, PetType pet) {
		List<Integer> at = positions(Pets.rodCycle(p), pet); // cycle mode only, but cheap
		// Switch EXPRESSION so a fourth mode is a compile error, not a head that never glints.
		boolean selected = switch(h.mode) {
			case PET -> pet == rulePet(p, h.trigger);
			case EXCEPTIONS -> exceptions(p, h.trigger).contains(pet);
			case CYCLE -> !at.isEmpty();
		};

		List<String> trailing = new ArrayList<>();
		switch(h.mode) {
			case PET -> trailing.add(selected ? "<green><bold>THIS RULE'S PET" : "<yellow>Click to summon this on the trigger");
			case EXCEPTIONS -> {
				if(selected) {
					trailing.add("<green><bold>AN EXCEPTION");
					trailing.add("<yellow>Click to stop excepting it");
				} else {
					trailing.add("<yellow>Click to except it");
				}
			}
			case CYCLE -> {
				if(selected) {
					StringBuilder sb = new StringBuilder("<gray>In the cycle at: <white>");
					for(int i = 0; i < at.size(); i++) sb.append(i == 0 ? "" : ", ").append(at.get(i));
					trailing.add(sb.toString());
				}
				trailing.add("<yellow>Click to add it to the end");
			}
		}

		ItemStack it = pet.icon(selected, false);
		ItemMeta m = it.getItemMeta();
		if(m == null) return it;
		List<Component> lore = m.lore();
		List<Component> out = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
		if(!out.isEmpty()) out.removeLast(); // icon()'s summon-menu action line
		for(String line : trailing) out.add(Utils.mm(line));
		m.lore(out);
		it.setItemMeta(m);
		// Cycle mode: stack count is the repeat count (listed three times reads x3); positions are in the lore.
		if(h.mode == Mode.CYCLE) it.setAmount(Math.max(1, Math.min(64, at.size())));
		return it;
	}

	/**
	 * The cycle, one numbered line per throw. <b>Capped</b>: the editor appends, so there's no length limit, and a
	 * tooltip taller than the screen shows less. Shared with {@link AutopetMenu}'s Rod Swap button so both cap alike.
	 */
	static List<String> cycleLines(List<PetType> cycle) {
		List<String> out = new ArrayList<>();
		if(cycle.isEmpty()) {
			out.add("<dark_gray>  empty (off)");
			return out;
		}
		int shown = Math.min(cycle.size(), CYCLE_LINES);
		for(int i = 0; i < shown; i++) out.add("<gray>  " + (i + 1) + ". " + cycle.get(i).colouredName());
		if(cycle.size() > shown) out.add("<dark_gray>  ... and " + (cycle.size() - shown) + " more");
		return out;
	}

	/** Cycle entries listed before summarising the rest. */
	private static final int CYCLE_LINES = 12;

	/** Every 1-based position of {@code pet} in the cycle; repeats allowed. */
	private static List<Integer> positions(List<PetType> cycle, PetType pet) {
		List<Integer> out = new ArrayList<>();
		for(int i = 0; i < cycle.size(); i++) if(cycle.get(i) == pet) out.add(i + 1);
		return out;
	}

	// ==================== clicks ====================

	/** Called by {@link AutopetMenu}'s handler once it recognises this holder. */
	void onClick(InventoryClickEvent e, Holder h) {
		if(Menus.ignoreDoubleClick(e)) return;
		e.setCancelled(true); // every slot, both inventories, before anything is read
		if(!(e.getWhoClicked() instanceof Player p)) return;
		if(e.getClickedInventory() != e.getView().getTopInventory()) return;
		// No mode check, same reason as AutopetMenu.

		int slot = e.getRawSlot();
		if(slot == BACK_SLOT) {
			back(p);
			return;
		}
		PetType pet = petAt(slot);
		switch(h.mode) {
			case PET -> {
				if(slot == LEFT_SLOT) {
					setPet(p, h.trigger, null);
					back(p);
				} else if(pet != null) {
					setPet(p, h.trigger, pet);
					back(p); // single select: choice is the exit
				}
			}
			case EXCEPTIONS -> {
				if(pet == null) return;
				toggleException(p, h.trigger, pet);
				draw(p, h);
			}
			case CYCLE -> {
				if(slot == LEFT_SLOT) removeLast(p);
				else if(slot == RIGHT_SLOT) setCycle(p, List.of());
				else if(pet != null) append(p, pet);
				else return; // header or pane
				draw(p, h);
			}
		}
	}

	/** Dragging moves items too, so refused, as in every window here. */
	void onDrag(InventoryDragEvent e) {
		e.setCancelled(true);
	}

	/**
	 * Every close. The spectator bypass armed on open has to come off or it stays for the session.
	 * <p>
	 * Escape returns to the autopet menu. Three guards: quit fires a close for a player already gone, shutdown
	 * fires one while the scheduler refuses tasks, and another window ({@code /pets}, a Goldor terminal) may have
	 * opened in the tick we waited, which a reopen would yank them out of.
	 */
	void onClose(InventoryCloseEvent e) {
		if(!(e.getPlayer() instanceof Player p)) return;
		SpectatorGuiAccess.uninstall(p);
		if(swapping.remove(p.getUniqueId())) return; // reopen already in flight
		if(!M7.getInstance().isEnabled()) return;
		Bukkit.getScheduler().runTask(M7.getInstance(), () -> {
			if(!p.isOnline()) return;
			// CRAFTING (or CREATIVE) is Bukkit's "no container open"; anything else arrived while waiting, not ours.
			InventoryType open = p.getOpenInventory().getType();
			if(open != InventoryType.CRAFTING && open != InventoryType.CREATIVE) return;
			parent.open(p);
		});
	}

	/** Back to the parent menu. Deferred a tick: this view's close has to land first. */
	private void back(Player p) {
		swapping.add(p.getUniqueId());
		Bukkit.getScheduler().runTask(M7.getInstance(), () -> {
			if(p.isOnline()) parent.open(p);
			else swapping.remove(p.getUniqueId()); // left before the reopen; nothing will consume the flag
		});
	}

	// ==================== the edits ====================

	private static PetType rulePet(Player p, Autopet.Trigger t) {
		Autopet.Rule rule = Pets.rule(p, t);
		return rule == null ? null : rule.pet();
	}

	private static List<PetType> exceptions(Player p, Autopet.Trigger t) {
		Autopet.Rule rule = Pets.rule(p, t);
		return rule == null ? List.of() : rule.exceptions();
	}

	/** Set the pet half, keeping exceptions: halves are set in either order, neither clears the other. */
	private static void setPet(Player p, Autopet.Trigger t, PetType pet) {
		Pets.setRule(p, t, new Autopet.Rule(pet, exceptions(p, t)));
	}

	private static void toggleException(Player p, Autopet.Trigger t, PetType pet) {
		List<PetType> now = new ArrayList<>(exceptions(p, t));
		if(!now.remove(pet)) now.add(pet);
		Pets.setRule(p, t, new Autopet.Rule(rulePet(p, t), now));
	}

	private static void append(Player p, PetType pet) {
		List<PetType> cycle = new ArrayList<>(Pets.rodCycle(p));
		cycle.add(pet); // no contains() check: repeats are the point
		setCycle(p, cycle);
	}

	private static void removeLast(Player p) {
		List<PetType> cycle = new ArrayList<>(Pets.rodCycle(p));
		if(cycle.isEmpty()) return;
		cycle.removeLast();
		setCycle(p, cycle);
	}

	/**
	 * Only write for the cycle, so the cursor is never left pointing into a changed list. The copy is required:
	 * {@code Pets.rodCycle} returns the live list and {@code setRodCycle} clears it before refilling.
	 */
	private static void setCycle(Player p, List<PetType> cycle) {
		Pets.setRodCycle(p, cycle);
		Autopet.clearRodCursor(p);
	}

	/** Marker holder: which rule, which mode. Values live on the profile. */
	public static final class Holder implements InventoryHolder {
		final Autopet.Trigger trigger;
		final Mode mode;
		Inventory inv;

		Holder(Autopet.Trigger trigger, Mode mode) {
			this.trigger = trigger;
			this.mode = mode;
		}

		@Override
		public @NotNull Inventory getInventory() {
			return inv;
		}
	}
}
