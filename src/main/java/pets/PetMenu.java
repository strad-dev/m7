package pets;

import damage.Difficulty;
import items.ItemFactory;
import loadout.SpectatorGuiAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import plugin.M7;
import plugin.Menus;
import plugin.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /pets} and {@code /petloadout} - one 54-slot window, two modes.
 * <p>
 * {@code /pets} summons: click a head and that pet comes out. {@code /petloadout} is ARRANGING mode: pick a pet up
 * and drop it in another slot, swapping. One class because layout, furniture and containment rules are identical
 * and a second copy would drift.
 *
 * <h2>Nothing here is an inventory</h2>
 * <b>Every click is cancelled, in both inventories, before anything else</b>, same as
 * {@code goldor/GoldorTerminalGui}: click targets only, nothing picked up, moved, dropped, shift-clicked in,
 * number-keyed out or dragged. Meaningful clicks are handled by hand after cancelling.
 *
 * <h2>A pet may never leave the menu</h2>
 * Only arranging puts a stack on the cursor, a fresh COPY of a pet head: reaching the world is a free item spawn,
 * and still on the cursor at close is the same a tick later. So {@link #finish} is the ONE way a session ends
 * (put the pet back, save, clear cursor), reached from close, quit, death and plugin disable, idempotent since it
 * removes the session first. Same structure as {@code loadout/LoadoutEditor.finish}: missing one of those four
 * paths is how items disappear.
 *
 * <h2>Live modes only</h2>
 * {@code /pets} refuses in classic and says why. There the pet is assumed from what the player does and wears ({@code damage/Pet.forPlayer}), so a menu would offer a choice nothing reads.
 * {@code /petloadout} opens in any mode: it only edits layout and autopet settings.
 */
public final class PetMenu implements CommandExecutor, Listener {

	private static final int SIZE = 54;

	/** Black panes. The ONLY place the window's shape is written down. */
	private static final int[] FILLER_SLOTS = {0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 47, 48, 50, 51, 53};

	/** The four buttons. */
	private static final int HEADER_SLOT = 4, AUTOPET_SLOT = 46, CLOSE_SLOT = 49, RESET_SLOT = 52;

	/** Hypixel's Autopet head texture, off the real item. See {@link #autopetButton()}. */
	private static final String AUTOPET_TEXTURE =
			"eyJ0aW1lc3RhbXAiOjE1MTgyODU5Njg3NjMsInByb2ZpbGVJZCI6ImIwZDczMmZlMDBmNzQwN2U5ZTdmNzQ2MzAxY2Q5OGNhIiwicHJvZmlsZU5hbWUiOiJPUHBscyIsInNpZ25hdHVyZVJlcXVpcmVkIjp0cnVlLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzMxNmE4Yjk2Y2I5YWVlYTUxNDBkZDkzNmM2ZWJiOTY0ZWZjYmZkNzFhZTIxN2Q5ZjcwODg4ZWZiMTRlN2MwIn19fQ==";

	/**
	 * Pet slots: whatever the panes and four buttons leave.
	 * <p>
	 * <b>Derived, never listed twice.</b> Comes out as 10-16, 19-25, 28-34, 37-43 (28 slots); check against that
	 * if the shape moves, but a second constant is how a pane and a pet slot end up on the same square.
	 */
	private static final List<Integer> PET_SLOTS = derivePetSlots();

	private static List<Integer> derivePetSlots() {
		boolean[] taken = new boolean[SIZE];
		for(int slot : FILLER_SLOTS) taken[slot] = true;
		taken[HEADER_SLOT] = true;
		taken[AUTOPET_SLOT] = true;
		taken[CLOSE_SLOT] = true;
		taken[RESET_SLOT] = true;
		List<Integer> out = new ArrayList<>();
		for(int slot = 0; slot < SIZE; slot++) if(!taken[slot]) out.add(slot);
		return List.copyOf(out);
	}

	/** Pet slots ascending. {@code Pets} reads this for its default layout and repair path. */
	public static List<Integer> petSlots() {
		return PET_SLOTS;
	}

	/**
	 * Live arranging sessions: player -> layout being edited. Present == live, which makes {@link #finish}
	 * idempotent. Profile is only written at session end, so an abandoned arrange still saves but a half-done swap
	 * is never written twice.
	 */
	private final Map<UUID, Map<Integer, PetType>> sessions = new HashMap<>();

	/** Pet on a player's cursor, per live session. Absent = empty cursor. */
	private final Map<UUID, PetType> carried = new HashMap<>();

	/**
	 * Settings window behind slot 46. Owned here so the two point at each other (its Back reopens this) without a
	 * static handle; still has to be REGISTERED as a listener, hence {@link #autopetMenu()}.
	 */
	private final AutopetMenu autopet = new AutopetMenu(this);

	/** For {@code M7.onEnable} to register as a listener. */
	public AutopetMenu autopetMenu() {
		return autopet;
	}

	// ==================== commands ====================

	@Override
	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
		if(!(sender instanceof Player p)) {
			sender.sendMessage(Utils.msg("<red>Only players have pets"));
			return true;
		}
		boolean arranging = command.getName().equalsIgnoreCase("petloadout");
		// /petloadout only edits settings, so it opens in any mode.
		if(!arranging && !Difficulty.manualPets()) {
			// Say which mode it needs: opening onto an assumed pet would be worse than not opening.
			sender.sendMessage(Utils.msg("<red>Pets are yours to pick only in <white>Perfect RNG</white> and <white>Realistic</white> mode."));
			sender.sendMessage(Utils.msg("<gray>The mode is <white>" + Difficulty.current().displayName()
					+ "</white>, where your pet follows from what you are doing."));
			return true;
		}
		open(p, arranging);
		return true;
	}

	/** Package-private so {@link AutopetMenu}'s Back button can reopen the summoning half. */
	void open(Player p, boolean arranging) {
		Holder holder = new Holder(arranging);
		Inventory gui = Bukkit.createInventory(holder, SIZE,
				Utils.msg(arranging ? "<dark_gray>Pets: Arrange" : "<dark_gray>Pets"));
		holder.inv = gui;
		if(arranging) sessions.put(p.getUniqueId(), new LinkedHashMap<>(Pets.layout(p)));
		draw(p, holder);
		p.openInventory(gui);
		// Idle m7 players are spectators and vanilla refuses their container clicks; arm the bypass. No-op otherwise.
		SpectatorGuiAccess.install(p);
	}

	/** Redraw the whole window from this mode's layout. */
	private void draw(Player p, Holder holder) {
		Inventory gui = holder.inv;
		for(int slot : FILLER_SLOTS) gui.setItem(slot, filler());

		PetType out = Pets.equipped(p);
		gui.setItem(HEADER_SLOT, button(Material.BONE, "<green>Pets",
				List.of("<gray>Currently summoned:", out.colouredName())));
		gui.setItem(AUTOPET_SLOT, autopetButton());
		gui.setItem(CLOSE_SLOT, button(Material.BARRIER, "<red>Close", List.of()));
		gui.setItem(RESET_SLOT, button(Material.LAVA_BUCKET, "<yellow>Reset to Default",
				List.of("<gray>Put your pets back on the first", "<gray>four slots of the menu.")));

		Map<Integer, PetType> layout = layoutFor(p, holder);
		for(int slot : PET_SLOTS) {
			PetType pet = layout.get(slot);
			gui.setItem(slot, pet == null ? null : pet.icon(pet == out, holder.arranging));
		}
	}

	/** Arranging edits a working copy; summoning reads the saved one off the profile. */
	private Map<Integer, PetType> layoutFor(Player p, Holder holder) {
		if(!holder.arranging) return Pets.layout(p);
		return sessions.getOrDefault(p.getUniqueId(), Pets.layout(p));
	}

	// ==================== clicks ====================

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
		if(Menus.ignoreDoubleClick(e)) return;
		e.setCancelled(true); // every slot, both inventories, before anything is read
		if(!(e.getWhoClicked() instanceof Player p)) return;

		if(e.getClickedInventory() != e.getView().getTopInventory()) {
			// Click in the player's own inventory. With a pet on the cursor it's an attempt to put it where it can
			// never go, so say so instead of doing nothing.
			if(carried.containsKey(p.getUniqueId())) refuse(p);
			return;
		}

		int slot = e.getRawSlot();
		boolean holding = carried.containsKey(p.getUniqueId());
		if(PET_SLOTS.contains(slot)) {
			if(holder.arranging) arrangeClick(p, holder, slot);
			else summonClick(p, holder, slot);
			return;
		}
		// Furniture. Carrying a pet, all of it is refused, buttons included, so a pet is never lost behind an
		// action that swaps the window out. Escape still closes and finish() puts the pet back.
		if(holding) {
			refuse(p);
			return;
		}
		switch(slot) {
			case CLOSE_SLOT -> {
				// Deferred a tick: Bukkit says never close a view from its own click event (same as GoldorListener
				// on a solved terminal).
				Bukkit.getScheduler().runTask(M7.getInstance(), () -> p.closeInventory());
			}
			case AUTOPET_SLOT -> {
				// Also deferred, and load-bearing in arranging mode: this window's close fires first, so finish()
				// saves the layout before the settings menu opens.
				Bukkit.getScheduler().runTask(M7.getInstance(), () -> autopet.open(p));
			}
			case RESET_SLOT -> {
				Pets.resetLayout(p);
				if(holder.arranging) sessions.put(p.getUniqueId(), new LinkedHashMap<>(Pets.layout(p)));
				draw(p, holder);
				p.sendMessage(Utils.msg("<yellow>Put your pets back on the default slots"));
			}
			default -> { } // header and panes do nothing
		}
	}

	/**
	 * {@code /pets}: clicking a head summons it and <b>closes the window</b> (leaving it open just made a second
	 * click the way out). Redraw still runs so the glint is right for the tick left. Close deferred a tick, as
	 * everywhere here.
	 * <p>
	 * A click that changes nothing (empty slot, pet already out) leaves the window alone.
	 */
	private void summonClick(Player p, Holder holder, int slot) {
		PetType pet = Pets.layout(p).get(slot);
		if(pet == null) return;
		if(Pets.equip(p, pet, "<green>You summoned your ")) { // pet name ends the line
			draw(p, holder);
			Bukkit.getScheduler().runTask(M7.getInstance(), () -> p.closeInventory());
		}
	}

	/**
	 * {@code /petloadout}: clicking a pet slot picks up, puts down or swaps.
	 * <p>
	 * Uses the real cursor so it feels like moving an item. The carried pet is tracked separately too: the
	 * ItemStack is only a picture, and {@link #finish} needs the pet without parsing a head.
	 */
	private void arrangeClick(Player p, Holder holder, int slot) {
		Map<Integer, PetType> layout = sessions.get(p.getUniqueId());
		if(layout == null) return; // no live session: stale view
		UUID id = p.getUniqueId();
		PetType held = carried.get(id);
		if(held == null) {
			PetType pet = layout.remove(slot);
			if(pet == null) return; // empty slot, empty cursor
			carried.put(id, pet);
			p.setItemOnCursor(pet.icon(pet == Pets.equipped(p), true));
		} else {
			PetType under = layout.put(slot, held);
			if(under == null) {
				carried.remove(id);
				p.setItemOnCursor(null);
			} else {
				carried.put(id, under);
				p.setItemOnCursor(under.icon(under == Pets.equipped(p), true));
			}
		}
		draw(p, holder);
		p.updateInventory();
	}

	private static void refuse(Player p) {
		p.sendMessage(Utils.msg("<red>A pet can only go in another pet slot"));
	}

	/** Dragging moves items too, so refused. */
	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if(e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
	}

	/**
	 * Backstop for every drop path: while open, nothing goes on the ground. Cheaper than keeping a click allowlist
	 * exhaustive forever.
	 */
	@EventHandler(ignoreCancelled = true)
	public void onDropItem(PlayerDropItemEvent e) {
		if(e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
	}

	// ==================== the four ends ====================

	@EventHandler
	public void onClose(InventoryCloseEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
		if(e.getPlayer() instanceof Player p) finish(p);
	}

	/**
	 * Quit with the menu open. Whichever of this and {@link #onClose} fires first ends the session; {@link #finish}
	 * is idempotent.
	 */
	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		finish(e.getPlayer());
		Pets.unload(e.getPlayer().getUniqueId());
	}

	/**
	 * Dying with the menu open. Carried pet goes back into the layout, and the head already listed as a drop is
	 * removed: it's a menu COPY and would spawn a pet head nobody owned.
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onDeath(PlayerDeathEvent e) {
		Player p = e.getEntity();
		if(!sessions.containsKey(p.getUniqueId())) return;
		finish(p);
		e.getDrops().removeIf(PetMenu::isPetIcon);
	}

	/**
	 * Load a joining player's pet profile so the damage path never reads disk, and apply their starting pet if no
	 * run is live. Here because this class is already the package's registered listener.
	 */
	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Pets.preload(e.getPlayer().getUniqueId());
		// Before the run is live (mid-countdown or idle) you start on your starting pet. Mid-run, leave it.
		if(!instructions.Server.isRunStarted()) Pets.applyStartingPet(e.getPlayer());
	}

	/** End and save every arranging session. Called from {@code M7.onDisable}. */
	public void restoreAll() {
		for(UUID id : new ArrayList<>(sessions.keySet())) {
			Player p = Bukkit.getPlayer(id);
			if(p == null) {
				sessions.remove(id); // offline with a session left
				carried.remove(id);
				continue;
			}
			finish(p);
			p.closeInventory();
		}
		Pets.clearCache();
	}

	/**
	 * The ONE way an arranging session ends: put the pet back, save, clear the cursor. Idempotent, session is
	 * removed first. No-op for {@code /pets}, which never opens a session.
	 */
	private void finish(Player p) {
		UUID id = p.getUniqueId();
		Map<Integer, PetType> layout = sessions.remove(id);
		PetType held = carried.remove(id);
		if(layout == null) return;
		// finally, like the loadout editor: the spectator bypass rewrites EVERY container click from this player,
		// so it must come off even if the save throws, and the cursor must be cleared or a pet head rides out.
		try {
			// A pet never ends up nowhere. 28 slots, five pets, so a free one exists.
			if(held != null) Pets.placeInFirstFree(layout, held);
			Pets.setLayout(p, layout);
		} finally {
			SpectatorGuiAccess.uninstall(p);
			p.setItemOnCursor(null);
			p.updateInventory();
		}
	}

	/**
	 * Is this one of the menu's pet heads? Material + plain display name, like {@code Catalog.paletteKey}. No false
	 * positive possible: pet heads never leave this window.
	 */
	private static boolean isPetIcon(ItemStack it) {
		if(it == null || it.getType() != Material.PLAYER_HEAD || !it.hasItemMeta()) return false;
		ItemMeta meta = it.getItemMeta();
		if(meta == null) return false;
		String name = Utils.plain(meta.displayName());
		for(PetType t : PetType.values()) if(Utils.plain(Utils.mm(t.colouredName())).equals(name)) return true;
		return false;
	}

	// ==================== helpers ====================

	private static ItemStack filler() {
		return button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
	}

	/**
	 * Hypixel's Autopet head (used to be a book). Built via {@code ItemFactory.head}, then stripped of the
	 * Protection 5 it adds for wearable heads, same as {@link PetType#icon}: one copy of the profile/NBT code per
	 * CLAUDE.md, no enchant line on a button.
	 * <p>
	 * The real item's "Rules used: N/28" counter is left off: four triggers here, not 28, so it would be wrong.
	 */
	private static ItemStack autopetButton() {
		ItemStack head = ItemFactory.head("<red>Autopet", "petsAutopet", AUTOPET_TEXTURE, null);
		for(Enchantment e : new ArrayList<>(head.getEnchantments().keySet())) head.removeEnchantment(e);
		ItemMeta m = head.getItemMeta();
		if(m != null) {
			m.displayName(Utils.msg("<red>Autopet").decoration(TextDecoration.ITALIC, false));
			List<Component> lore = new ArrayList<>();
			for(String line : List.of("<gray>Define custom <red>rules <gray>to automatically",
					"<gray>equip your pets.", "", "<yellow>Click to setup autopet!")) {
				lore.add(Utils.msg(line).decoration(TextDecoration.ITALIC, false));
			}
			m.lore(lore);
			head.setItemMeta(m);
		}
		return head;
	}

	static ItemStack button(Material mat, String name, List<String> lore) {
		ItemStack it = new ItemStack(mat);
		ItemMeta m = it.getItemMeta();
		if(m != null) {
			m.displayName(Utils.msg(name).decoration(TextDecoration.ITALIC, false));
			List<Component> rendered = new ArrayList<>(lore.size());
			for(String line : lore) rendered.add(Utils.msg(line).decoration(TextDecoration.ITALIC, false));
			m.lore(rendered);
			it.setItemMeta(m);
		}
		return it;
	}

	/** Marker holder carrying which mode this window is. */
	public static final class Holder implements InventoryHolder {
		final boolean arranging;
		Inventory inv;

		Holder(boolean arranging) {
			this.arranging = arranging;
		}

		@Override
		public @NotNull Inventory getInventory() {
			return inv;
		}
	}
}
