package loadout;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import plugin.Catalog;
import plugin.Menus;
import plugin.Utils;

import java.util.*;

/**
 * {@code /m7loadout} - editor for the CURRENTLY SELECTED class loadout.
 *
 * <p><b>The player's OWN inventory is the loadout's four inventory rows.</b> A 41-slot kit is 36 inventory slots
 * plus armour and off-hand. The old editor drew the 36 inside the chest, and people kept misreading them as "some
 * GUI". So on open we SNAPSHOT the real inventory, write the loadout into it and let them arrange it like in a
 * run; on close we save those 36 slots and give their own items back. The rest is in the chest:
 * <pre>
 *   rows 1-4 (0-35) : item palette, one page (click to copy to the cursor, shift-click to drop it in a free slot)
 *   row 5    (36)   : previous page          (37-43): the labelled seam          (44): next page
 *   row 6    (45-48): helmet, chestplate, leggings, boots
 *            (52)   : trash - click with an item on the cursor to delete it
 *            (53)   : load the class's default kit
 * </pre>
 * <b>No off-hand slot.</b> M7 doesn't allow one, so array slot 40 is never editable and is written back EMPTY on
 * save, which also clears one saved before that rule.
 * <p>
 * An empty armour slot shows a white pane named for what goes there. Placeholders, never contents:
 * {@link #isPlaceholder} keeps them out of a saved loadout.
 *
 * <p><b>Nothing may escape, nothing may be lost.</b> Palette hands out COPIES, so one reaching the world is a free
 * item spawn; the real inventory sits in a field, so any exit that doesn't restore it eats their items. Hence
 * {@link #finish}, the ONE way an editor ends (save, restore, clear cursor), reached from close, quit, death and
 * plugin disable, idempotent since it removes the snapshot first. Clicks are an ALLOWLIST, default cancel, so an
 * action Bukkit adds later fails closed.
 *
 * <p>Palette comes from {@link Catalog#palette()} in memory, so it's always current and works before any export.
 *
 * <p>NOTE: twin of the network plugin's {@code loadout/LoadoutEditor.java} - keep in sync, especially the
 * containment layers and {@link #finish}.
 */
public class LoadoutEditor implements CommandExecutor, Listener {
	private static final int PALETTE_START = 0, PALETTE_COUNT = 36;   // rows 1-4 of the chest
	/** Row 5 is the seam: page arrows at each end, labelled bar between. */
	private static final int PREV_SLOT = 36, DIVIDER_START = 37, DIVIDER_END = 43, NEXT_SLOT = 44;
	private static final int HELMET_SLOT = 45, CHEST_SLOT = 46, LEGS_SLOT = 47, BOOTS_SLOT = 48;
	private static final int TRASH_SLOT = 52, RESET_SLOT = 53;
	/**
	 * Pet you START the run with, in the dead space before the buttons. Here because it's part of what you take in,
	 * like the kit, but NOT saved into the loadout: writes straight to {@code pets/<uuid>.json}.
	 * <p>
	 * <b>Its own member there, NOT the pet out.</b> Using {@code equipped} was wrong: {@code /pets} and autopet
	 * move it during a run, so the button showed whatever the last run left out. {@code Pets.applyStartingPets}
	 * turns the preference into the actual starting pet.
	 */
	private static final int PET_SLOT = 51;
	/**
	 * Last gear slot in the bottom row; between it and trash is dead space. No off-hand (M7 doesn't allow one):
	 * slot 40 is written back EMPTY on every save, see {@code finish}.
	 */
	private static final int GEAR_END = BOOTS_SLOT;
	/** 36 loadout slots the player's inventory stands in for: [0..8] hotbar, [9..35] storage. */
	private static final int MAIN_SLOTS = 36;
	// Hotbar slot 9 (index 8) is RESERVED for the SkyBlock Menu: every kit has it there, so it's pinned, not
	// editable, and written back on every save.
	private static final int MENU_ARR_INDEX = 8;

	/** Open editors: player -> their 36 real inventory slots. Present == live. */
	private final Map<UUID, ItemStack[]> snapshots = new HashMap<>();

	/** Chest slot -> loadout index, armour/off-hand row only (the 36 are in the player's inventory). */
	private static int arrIndex(int gui) {
		return switch(gui) {
			case HELMET_SLOT -> 36;
			case CHEST_SLOT -> 37;
			case LEGS_SLOT -> 38;
			case BOOTS_SLOT -> 39;
			default -> -1; // no off-hand in M7, slot 40 never editable
		};
	}

	private static boolean isPalette(int gui) {
		return gui >= PALETTE_START && gui < PALETTE_START + PALETTE_COUNT;
	}

	@Override
	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
		if(!(sender instanceof Player p)) {
			sender.sendMessage(Utils.msg("<red>Only players can edit a loadout"));
			return true;
		}
		String role = Loadouts.getSelectedClass(p.getUniqueId());
		if(role == null) {
			p.sendMessage(Utils.msg("<red>Pick a class first with <white>/class <name>"));
			return true;
		}
		Loadouts.seedIfAbsent(p.getUniqueId(), role);
		// Refresh frozen saved copies to current definitions first, so the editor never shows or re-saves a stale
		// item. See ItemRefresh.
		ItemRefresh.refreshSaved(p.getUniqueId());

		if(args.length >= 1 && args[0].equalsIgnoreCase("reset")) {
			Loadouts.setContents(p.getUniqueId(), role, Catalog.defaultFor(role));
			p.sendMessage(Utils.msg("<yellow>Reset your <white><role></white> loadout to the default kit",
					Placeholder.unparsed("role", role)));
			return true;
		}

		open(p, role);
		return true;
	}

	private void open(Player p, String role) {
		if(snapshots.containsKey(p.getUniqueId())) return; // already editing; a second open would snapshot copies

		EditorHolder holder = new EditorHolder(role, Catalog.palette());
		Inventory gui = Bukkit.createInventory(holder, 54,
				Utils.msg("<dark_gray>Loadout: <white><role>", Placeholder.unparsed("role", role)));
		holder.inv = gui;

		ItemStack[] arr = Loadouts.getContents(p.getUniqueId(), role); // non-null after seedIfAbsent
		// Take the real inventory BEFORE writing into it: this array is the only copy.
		snapshots.put(p.getUniqueId(), takeInventory(p));
		writeMain(p, arr);
		pinMenu(p, role, arr);
		for(int g = HELMET_SLOT; g <= GEAR_END; g++) gui.setItem(g, orPlaceholder(arr[arrIndex(g)], g));
		for(int g = GEAR_END + 1; g < TRASH_SLOT; g++) {
			gui.setItem(g, g == PET_SLOT ? petButton(p) : filler()); // dead space before the buttons
		}
		drawDivider(gui);
		gui.setItem(TRASH_SLOT, button(Material.LAVA_BUCKET, "<red>Trash (click with an item to delete it)"));
		refreshPalette(gui, holder);

		p.openInventory(gui);
		// Idle m7 players are spectators and vanilla refuses their container clicks; arm the bypass. No-op otherwise.
		SpectatorGuiAccess.install(p);
		p.sendMessage(Utils.msg("<gray>Editing <white><role>",
				Placeholder.unparsed("role", role)));
	}

	/** Redraw palette + page/reset buttons for the current page. */
	private void refreshPalette(Inventory gui, EditorHolder holder) {
		List<ItemStack> pal = holder.palette;
		int pages = Math.max(1, (pal.size() + PALETTE_COUNT - 1) / PALETTE_COUNT);
		if(holder.page < 0) holder.page = 0;
		if(holder.page >= pages) holder.page = pages - 1;
		for(int i = 0; i < PALETTE_COUNT; i++) {
			int idx = holder.page * PALETTE_COUNT + i;
			gui.setItem(PALETTE_START + i, idx < pal.size() ? pal.get(idx).clone() : null);
		}
		// Missing arrow shows the seam pane, not filler, so row 5 reads as one bar.
		gui.setItem(PREV_SLOT, holder.page > 0 ? button(Material.ARROW, "<yellow>Previous page") : divider());
		gui.setItem(NEXT_SLOT, holder.page < pages - 1 ? button(Material.ARROW, "<yellow>Next page") : divider());
		gui.setItem(RESET_SLOT, button(Material.BARRIER, "<red>Load default kit"));
	}

	// ===== events =====
	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) return;
		if(Menus.ignoreDoubleClick(e)) return;
		if(!(e.getWhoClicked() instanceof Player p)) return;
		int raw = e.getRawSlot();

		if(raw >= e.getView().getTopInventory().getSize()) {
			// Player's inventory = loadout rows 1-4. Anything keeping items in these 36 slots is allowed; anything
			// moving one OUT (shift into chest, drop, double-click collect that also vacuums palette copies) is
			// denied, default cancel.
			int slot = e.getSlot();
			if(slot == MENU_ARR_INDEX || e.getHotbarButton() == MENU_ARR_INDEX) {
				e.setCancelled(true); // pinned SkyBlock Menu, either end of the swap
				return;
			}
			switch(e.getAction()) {
				case NOTHING, PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
						PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR,
						HOTBAR_SWAP, HOTBAR_MOVE_AND_READD,
						PICKUP_FROM_BUNDLE, PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE,
						PLACE_FROM_BUNDLE, PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE -> { }
				default -> e.setCancelled(true);
			}
			return;
		}

		e.setCancelled(true); // every chest slot is handled by hand below
		if(arrIndex(raw) >= 0) {
			swapArmour(p, holder.inv, raw);
			return;
		}
		if(isPalette(raw)) {
			ItemStack tmpl = e.getCurrentItem();
			if(tmpl == null || tmpl.getType().isAir()) return;
			if(e.isShiftClick()) {
				giveToLoadout(p, tmpl.clone()); // first free inventory slot
				return;
			}
			ItemStack cursor = e.getCursor();
			if(cursor == null || cursor.getType().isAir()) p.setItemOnCursor(tmpl.clone());
			return;
		}
		if(raw == PREV_SLOT) { holder.page--; refreshPalette(holder.inv, holder); return; }
		if(raw == NEXT_SLOT) { holder.page++; refreshPalette(holder.inv, holder); return; }
		if(raw == RESET_SLOT) {
			ItemStack[] def = Catalog.defaultFor(holder.role);
			writeMain(p, def);
			pinMenu(p, holder.role, def);
			for(int g = HELMET_SLOT; g <= GEAR_END; g++) holder.inv.setItem(g, orPlaceholder(def[arrIndex(g)], g));
			return;
		}
		if(raw == PET_SLOT) {
			// Left forward, right back, like the network's other cycling buttons. Five is short enough to step;
			// a bigger roster wants pets/PetPicker, which can't live here since another window ends the session.
			cyclePet(p, e.isRightClick());
			holder.inv.setItem(PET_SLOT, petButton(p));
			return;
		}
		if(raw == TRASH_SLOT) {
			ItemStack held = p.getItemOnCursor();
			if(held != null && !held.getType().isAir()) p.setItemOnCursor(null); // delete the held item
		}
	}

	/**
	 * One armour/off-hand slot, by hand so it's never blank: taking an item leaves the placeholder, placing one
	 * swaps out what was there (placeholder swaps out as nothing). Vanilla would leave the pane in the loadout or
	 * the slot empty.
	 */
	private void swapArmour(Player p, Inventory gui, int slot) {
		ItemStack current = gui.getItem(slot);
		ItemStack held = isPlaceholder(current, slot) ? null : current;
		ItemStack cursor = p.getItemOnCursor();
		if(cursor == null || cursor.getType().isAir()) {
			if(held == null) return;
			p.setItemOnCursor(held);
			gui.setItem(slot, placeholder(slot));
		} else {
			gui.setItem(slot, cursor.clone());
			p.setItemOnCursor(held);
		}
		p.updateInventory();
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof EditorHolder)) return;
		int topSize = e.getView().getTopInventory().getSize();
		for(int raw : e.getRawSlots()) {
			// Chest slots are hand-driven and the pinned menu slot is off limits: cancel the whole drag.
			if(raw < topSize || e.getView().convertSlot(raw) == MENU_ARR_INDEX) {
				e.setCancelled(true);
				return;
			}
		}
	}

	/**
	 * Backstop for every drop path, whatever {@link InventoryClickEvent} called the click: while the editor is open,
	 * nothing goes on the ground. Cheaper than keeping the allowlist exhaustive forever.
	 */
	@EventHandler(ignoreCancelled = true)
	public void onDropItem(PlayerDropItemEvent e) {
		if(e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof EditorHolder) e.setCancelled(true);
	}

	@EventHandler
	public void onClose(InventoryCloseEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) return;
		if(!(e.getPlayer() instanceof Player p)) return;
		finish(p, holder, true);
	}

	/**
	 * Quit with the editor open. Whichever of this and {@link #onClose} fires first ends it; {@link #finish} is
	 * idempotent. Without this, a disconnect that skipped the close would lose the real inventory in
	 * {@link #snapshots}.
	 */
	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		finish(e.getPlayer(), holderOf(e.getPlayer()), true);
	}

	/**
	 * Dying with the editor open. The drop list is PALETTE COPIES, so it's replaced from the snapshot and the
	 * player drops what they actually had. LOWEST so the list is still ours to replace.
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onDeath(PlayerDeathEvent e) {
		Player p = e.getEntity();
		ItemStack[] snap = snapshots.get(p.getUniqueId());
		if(snap == null) return;
		finish(p, holderOf(p), true);
		e.getDrops().clear();
		for(ItemStack it : snap) if(it != null && !it.getType().isAir()) e.getDrops().add(it);
	}

	/** End and save every open editor. Called from {@code M7.onDisable}, before players are dropped. */
	public void restoreAll() {
		for(UUID id : new ArrayList<>(snapshots.keySet())) {
			Player p = Bukkit.getPlayer(id);
			if(p == null) {
				snapshots.remove(id); // offline, nothing to restore into
				continue;
			}
			finish(p, holderOf(p), true);
			p.closeInventory();
		}
	}

	/**
	 * The ONE way an editor ends: save 41 slots, give the real inventory back, clear the cursor. Idempotent,
	 * snapshot is removed first.
	 *
	 * @param holder open editor, or null from quit/death/disable; armour row is then read off whatever chest is
	 *               still on top, skipped if none.
	 */
	private void finish(Player p, EditorHolder holder, boolean save) {
		ItemStack[] snap = snapshots.remove(p.getUniqueId());
		if(snap == null) return;
		// finally: the spectator bypass rewrites EVERY container click from this player, so it must come off even
		// if the save throws, or it stays armed all session. Same for the inventory, or it keeps palette copies.
		try {
			if(save && holder != null) {
				ItemStack[] arr = new ItemStack[41];
				for(int i = 0; i < MAIN_SLOTS; i++) {
					ItemStack it = p.getInventory().getItem(i);
					arr[i] = it == null ? null : it.clone(); // live mirror, and the slot is about to be overwritten
				}
				for(int g = HELMET_SLOT; g <= GEAR_END; g++) {
					ItemStack it = holder.inv.getItem(g);
					arr[arrIndex(g)] = isPlaceholder(it, g) ? null : it;
				}
				arr[40] = null; // no off-hand in M7, clears one from before that rule
				// Reserved slot always gets the class default's menu.
				ItemStack menu = Catalog.defaultFor(holder.role)[MENU_ARR_INDEX];
				if(menu != null) arr[MENU_ARR_INDEX] = menu;
				Loadouts.setContents(p.getUniqueId(), holder.role, arr);
				p.sendMessage(Utils.msg("<green>Saved your <white><role></white> loadout",
						Placeholder.unparsed("role", holder.role)));
			}
		} finally {
			writeMain(p, snap);
			SpectatorGuiAccess.uninstall(p);
			p.setItemOnCursor(null); // don't leak a held palette copy
			p.updateInventory();
		}
	}

	/** The editor this player has open, or null. */
	private static EditorHolder holderOf(Player p) {
		return p.getOpenInventory().getTopInventory().getHolder() instanceof EditorHolder h ? h : null;
	}

	// ===== the player's inventory as the loadout's 36 slots =====
	/** Deep copy of the 36 inventory slots. The ONLY copy of the real items while the editor is open. */
	private static ItemStack[] takeInventory(Player p) {
		ItemStack[] out = new ItemStack[MAIN_SLOTS];
		for(int i = 0; i < MAIN_SLOTS; i++) {
			ItemStack it = p.getInventory().getItem(i);
			out[i] = it == null ? null : it.clone();
		}
		return out;
	}

	/** Write the first 36 entries of a 41-slot array (or a 36-slot snapshot) into the inventory. */
	private static void writeMain(Player p, ItemStack[] arr) {
		for(int i = 0; i < MAIN_SLOTS; i++) p.getInventory().setItem(i, arr != null && i < arr.length ? arr[i] : null);
		p.updateInventory();
	}

	/** Put the default SkyBlock Menu in the reserved slot, for loadouts saved before it was reserved or trashed. */
	private static void pinMenu(Player p, String role, ItemStack[] arr) {
		ItemStack menu = Catalog.defaultFor(role)[MENU_ARR_INDEX];
		p.getInventory().setItem(MENU_ARR_INDEX, menu != null ? menu : arr[MENU_ARR_INDEX]);
		p.updateInventory();
	}

	/** Palette shift-click: copy into the first free slot, never over the pinned menu. */
	private static void giveToLoadout(Player p, ItemStack copy) {
		for(int i = 0; i < MAIN_SLOTS; i++) {
			if(i == MENU_ARR_INDEX) continue;
			ItemStack at = p.getInventory().getItem(i);
			if(at == null || at.getType().isAir()) {
				p.getInventory().setItem(i, copy);
				p.updateInventory();
				return;
			}
		}
	}

	// ===== helpers =====
	/** White placeholder pane for an empty armour/off-hand slot, named for what goes there. */
	private static ItemStack placeholder(int gui) {
		return button(Material.WHITE_STAINED_GLASS_PANE, "<gray>" + slotName(gui));
	}

	private static ItemStack orPlaceholder(ItemStack it, int gui) {
		return it == null || it.getType().isAir() ? placeholder(gui) : it;
	}

	/**
	 * Is this the slot's placeholder pane? Built identically every time, so exact match; can't be forged since the
	 * palette has no white pane.
	 */
	private static boolean isPlaceholder(ItemStack it, int gui) {
		return it != null && it.isSimilar(placeholder(gui));
	}

	private static String slotName(int gui) {
		return switch(gui) {
			case HELMET_SLOT -> "Helmet";
			case CHEST_SLOT -> "Chestplate";
			case LEGS_SLOT -> "Leggings";
			case BOOTS_SLOT -> "Boots";
			default -> " ";
		};
	}

	/**
	 * Row 5: seam between palette and loadout. The halves look identical but mean opposite things (above you copy
	 * FROM, below is your kit), so a labelled bar beats explaining it in chat.
	 * <p>
	 * Only the first line can be the name (a newline in a display name does nothing), so the rule and second label
	 * are lore in the name's colour, reading as one block.
	 */
	private static void drawDivider(Inventory gui) {
		for(int g = DIVIDER_START; g <= DIVIDER_END; g++) gui.setItem(g, divider());
	}

	private static ItemStack divider() {
		return button(Material.GRAY_STAINED_GLASS_PANE, "<gray>▲ Item Palette", List.of("<gray>--------------------------", "<gray>▼ Your Armor & Inventory"));
	}

	/**
	 * Starting-pet button: the pet with its whole tooltip, action line rewritten. Built by {@code PetType.icon}
	 * like every pet (a second head-assembly copy makes saved stacks stop matching).
	 * <p>
	 * Always a pet to show: {@code Pets.startingPet} never returns null (falls back to Golden Dragon).
	 */
	private static ItemStack petButton(Player p) {
		pets.PetType pet = pets.Pets.startingPet(p);
		ItemStack it = pet.icon(true, false);
		ItemMeta m = it.getItemMeta();
		if(m == null) return it;
		List<Component> lore = m.lore();
		List<Component> out = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
		if(!out.isEmpty()) out.removeLast(); // icon()'s "CURRENTLY SUMMONED" line, which is /pets'
		out.add(Utils.msg("<gray>The pet every run of yours begins with.").decoration(TextDecoration.ITALIC, false));
		out.add(Utils.msg("<dark_gray>Applies in Realistic mode.").decoration(TextDecoration.ITALIC, false));
		out.add(Component.empty());
		out.add(Utils.msg("<yellow>Click to change <dark_gray>(right-click to go back)")
				.decoration(TextDecoration.ITALIC, false));
		m.lore(out);
		it.setItemMeta(m);
		return it;
	}

	/** Step the starting pet through {@code PetType}, wrapping. Writes the PREFERENCE, not the pet out. */
	private static void cyclePet(Player p, boolean back) {
		pets.PetType[] all = pets.PetType.values();
		if(all.length == 0) return;
		int at = 0;
		pets.PetType now = pets.Pets.startingPet(p);
		for(int i = 0; i < all.length; i++) if(all[i] == now) at = i;
		pets.Pets.setStartingPet(p, all[Math.floorMod(at + (back ? -1 : 1), all.length)]);
	}

	private static ItemStack filler() {
		return button(Material.GRAY_STAINED_GLASS_PANE, " ");
	}

	private static ItemStack button(Material mat, String name) {
		ItemStack it = new ItemStack(mat);
		ItemMeta m = it.getItemMeta();
		if(m != null) {
			m.displayName(Utils.msg(name).decoration(TextDecoration.ITALIC, false));
			it.setItemMeta(m);
		}
		return it;
	}

	/** Button with lore. Only the seam uses it; next to {@link #button(Material, String)} so both build alike. */
	private static ItemStack button(Material mat, String name, List<String> lore) {
		ItemStack it = button(mat, name);
		ItemMeta m = it.getItemMeta();
		if(m != null) {
			List<Component> rendered = new ArrayList<>();
			for(String line : lore) rendered.add(Utils.msg(line).decoration(TextDecoration.ITALIC, false));
			m.lore(rendered);
			it.setItemMeta(m);
		}
		return it;
	}


	/** Marker holder carrying the editor's class + palette paging state. */
	public static final class EditorHolder implements InventoryHolder {
		final String role;
		final List<ItemStack> palette;
		int page;
		Inventory inv;

		EditorHolder(String role, List<ItemStack> palette) {
			this.role = role;
			this.palette = palette;
		}

		@Override
		public @NotNull Inventory getInventory() {
			return inv;
		}
	}
}
