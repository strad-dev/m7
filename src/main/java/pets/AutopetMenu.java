package pets;

import damage.Difficulty;
import loadout.SpectatorGuiAccess;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import plugin.M7;
import plugin.Menus;
import plugin.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Autopet Settings window behind slot 46 of {@link PetMenu}: one button per {@link Autopet.Trigger}; lore lists
 * what it fires on, what it summons and what it won't interrupt.
 * <p>
 * Both clicks open a {@link PetPicker}: left picks the pet the rule summons, right toggles its exceptions. It
 * used to step the value per click like {@code commands/SettingsMenu}, which was six clicks to get back once
 * there were five pets plus "off", and hid the other values. A few values steps; a roster opens the roster.
 * <p>
 * Rod Swap is the odd one out: it holds an ordered cycle, not one pet, so nothing to pick or except. Both clicks
 * open the cycle editor (the picker's third mode).
 * <p>
 * Every click is cancelled: click target, not an inventory.
 * <p>
 * Registered as a listener by {@code M7.onEnable} via {@code petMenu.autopetMenu()}, and forwards the three
 * inventory events to its picker, so {@link PetPicker} needs no registration of its own.
 */
public final class AutopetMenu implements Listener {

	private static final int SIZE = 27;
	/** One button per trigger, along the middle row. */
	private static final int[] TRIGGER_SLOTS = {10, 12, 14, 16};
	private static final int HEADER_SLOT = 4, BACK_SLOT = 22;

	/** Parent window, so Back returns rather than closes. */
	private final PetMenu parent;

	/**
	 * Picker every rule click opens. Owned here (as this is owned by {@link PetMenu}) so the two point at each
	 * other without a static handle, and it rides this class's listener registration.
	 */
	private final PetPicker picker = new PetPicker(this);

	AutopetMenu(PetMenu parent) {
		this.parent = parent;
	}

	void open(Player p) {
		Holder h = new Holder();
		Inventory gui = Bukkit.createInventory(h, SIZE, Utils.msg("<dark_gray>Autopet Settings"));
		h.inv = gui;
		draw(p, gui);
		p.openInventory(gui);
		SpectatorGuiAccess.install(p);
	}

	private void draw(Player p, Inventory gui) {
		for(int slot = 0; slot < SIZE; slot++) gui.setItem(slot, PetMenu.button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
		gui.setItem(HEADER_SLOT, PetMenu.button(Material.BOOK, "<red>Autopet Settings",
				List.of("<gray>Summon a pet for you when", "<gray>something happens in the run.")));
		gui.setItem(BACK_SLOT, PetMenu.button(Material.ARROW, "<yellow>Back to Pets", List.of()));

		Autopet.Trigger[] triggers = Autopet.Trigger.values();
		for(int i = 0; i < triggers.length && i < TRIGGER_SLOTS.length; i++) {
			Autopet.Trigger t = triggers[i];
			gui.setItem(TRIGGER_SLOTS[i], PetMenu.button(t.icon(), "<gold>" + t.displayName(), lore(p, t)));
		}
	}

	private List<String> lore(Player p, Autopet.Trigger t) {
		List<String> out = new ArrayList<>();
		out.add("");
		if(t.isCycle()) {
			out.add("<gray>Cycle:");
			out.addAll(PetPicker.cycleLines(Pets.rodCycle(p)));
			out.add("");
			out.add("<yellow>Click to edit the cycle");
			return out;
		}
		Autopet.Rule rule = Pets.rule(p, t);
		PetType pet = rule == null ? null : rule.pet();
		// Order set by Autopet.Rule's constructor, so same sequence as the picker.
		List<PetType> except = rule == null ? List.of() : rule.exceptions();
		out.add("<gray>Summons: " + (pet == null ? "<dark_gray>Off" : pet.colouredName()));
		if(except.isEmpty()) {
			out.add("<gray>Unless out: <dark_gray>Nothing");
		} else {
			out.add("<gray>Unless out:");
			for(PetType e : except) out.add("<gray>  " + e.colouredName());
		}
		out.add("");
		out.add("<yellow>Left click to pick the pet");
		out.add("<yellow>Right click to pick the exceptions");
		return out;
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		InventoryHolder top = e.getView().getTopInventory().getHolder();
		// Picker rides this registration; it cancels the click itself, before anything is read, same as below.
		if(top instanceof PetPicker.Holder ph) {
			picker.onClick(e, ph);
			return;
		}
		if(!(top instanceof Holder)) return;
		if(Menus.ignoreDoubleClick(e)) return;
		e.setCancelled(true); // a click target, not an inventory - see PetMenu
		if(!(e.getWhoClicked() instanceof Player p)) return;
		if(e.getClickedInventory() != e.getView().getTopInventory()) return;
		// No mode check on purpose: /petloadout reaches this in every mode, and it only edits the player's own
		// preference; a gate would just make the buttons silently stop working.

		int slot = e.getRawSlot();
		if(slot == BACK_SLOT) {
			// Deferred a tick like every view swap: this window's close has to land first. Outside the live modes, back to
			// arranging, since the summoning half would summon.
			Bukkit.getScheduler().runTask(M7.getInstance(), () -> parent.open(p, !Difficulty.manualPets()));
			return;
		}
		for(int i = 0; i < TRIGGER_SLOTS.length && i < Autopet.Trigger.values().length; i++) {
			if(TRIGGER_SLOTS[i] != slot) continue;
			Autopet.Trigger t = Autopet.Trigger.values()[i];
			// Rod Swap: no single pet, nothing to except, so both clicks open the cycle editor.
			PetPicker.Mode mode = t.isCycle() ? PetPicker.Mode.CYCLE
					: e.isRightClick() ? PetPicker.Mode.EXCEPTIONS : PetPicker.Mode.PET;
			// Deferred a tick, same reason.
			Bukkit.getScheduler().runTask(M7.getInstance(), () -> picker.open(p, t, mode));
			return;
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		InventoryHolder top = e.getView().getTopInventory().getHolder();
		if(top instanceof PetPicker.Holder) picker.onDrag(e);
		else if(top instanceof Holder) e.setCancelled(true);
	}

	/**
	 * Nothing is ever on the cursor here, but the spectator bypass armed on open has to come off, or it stays on
	 * the connection for the rest of the session.
	 */
	@EventHandler
	public void onClose(InventoryCloseEvent e) {
		InventoryHolder top = e.getView().getTopInventory().getHolder();
		if(top instanceof PetPicker.Holder) {
			picker.onClose(e); // uninstalls too, and reopens this window unless it's the one swapping out
			return;
		}
		if(!(top instanceof Holder)) return;
		if(e.getPlayer() instanceof Player p) SpectatorGuiAccess.uninstall(p);
	}

	/** Marker holder; no state, every value is read off the player's profile. */
	public static final class Holder implements InventoryHolder {
		Inventory inv;

		@Override
		public @NotNull Inventory getInventory() {
			return inv;
		}
	}
}
