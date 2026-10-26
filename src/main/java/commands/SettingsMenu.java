package commands;

import damage.Difficulty;
import damage.Mayor;
import plugin.AlphaGDrag;
import plugin.BadBonzo;
import plugin.BatBug;
import plugin.WatcherFix;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.NonNull;
import plugin.Menus;
import plugin.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One-row <b>M7 Settings</b> menu behind bare {@code /dungeonsettings}: difficulty, mayor, Fix Watcher Bug, Bad Bonzo, Bat Bug and Alpha GDrag Damage, one
 * button each; lore lists every value with the active one bold, each click steps to the next.
 *
 * <p><b>Standalone only.</b> On the network these are party settings (they ride the practice request so a
 * party can't be mixed-mode) and the lobby has its own copy of this menu. This one flips server-wide globals,
 * which on a shared instance would change what someone else's run is scored under, so {@link #suppressed()}
 * refuses to open it when the network plugin is installed and {@code /dungeonsettings} falls back to text. The
 * text form works either way; the network gates it to admins (see {@code M7Bridge}).
 */
public final class SettingsMenu implements Listener {
	private static final int DIFFICULTY_SLOT = 0, MAYOR_SLOT = 1, WATCHER_FIX_SLOT = 2, BAD_BONZO_SLOT = 3, BAT_BUG_SLOT = 4, ALPHA_GDRAG_SLOT = 5;

	/** On every button so right-click isn't a secret. */
	private static final String CYCLE_HINT = "<yellow>Click to change <dark_gray>(right-click to go back)";

	/** Megakloon head from Hypixel's mayor menu: profile id + texture value + signature. */
	private static final UUID MAYOR_HEAD_ID = UUID.fromString("ff8b48bd-20ef-33c2-9a57-0df4860ebdc6");
	private static final String MAYOR_HEAD_VALUE = "ewogICJ0aW1lc3RhbXAiIDogMTU5Nzc4MDk0OTYzOCwKICAicHJvZmlsZUlkIiA6ICI0MWQzYWJjMmQ3NDk0MDBjOTA5MGQ1NDM0ZDAzODMxYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNZWdha2xvb24iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWI1OWM0M2Q4ZGJjY2ZkN2VjNmU2Mzk0YjYzMDRiNzBkNGVkMzE1YWRkMDQ5NGVlNzdjNzMzZjQxODE4YzczYSIKICAgIH0KICB9Cn0=";
	private static final String MAYOR_HEAD_SIGNATURE = "UeEf0Ir81BLTBE0PfVeEtU/6mHUw3xSP7XGcQYN18qlYS6J7qd+bQskaSJQbEHXr+axq2+5aPm/AfGrjNnl9zQn1EucBIwhdRBHnjuJeRY6x9VKEHAtX2gnpnNelU/oP6MKPZ1dUad4iAHQg8BmJR/oQpedvOJDuqdUZowe8WTVFC5qctJQWRIZCX0BWYK1O1xxJx4FZ9LzF++7qWZsVqO+qmOR8R7Xr4jFkF8cdNIRyezgcfhmw3BCqiDDheOzuJzo0l7y9kHR82reHus/JBLGyTy/iqMqlZFgNePEoaOGRgIvROw9oIS4R/19+UABIe0MDD6CSGgsE7VfgWeyCVw3qxmCSAZDHnYuYyH0zpnGQRsmQrx9aTjFXHf6g551MEpx7KGGTMaOm9b7ygOGuVGB/52UXR2W9UU+YtBZoUzDPMeVcM1NQno/fY3rLoSf5PfuaaEpZMlczYpH0DBvApEQ6FBm/XTOPeS5w3a+7UXh/wrjXu5b62rXG4SNZaBZRT76eseX3wZoCGiLYEh+IXfAJxXSKOqmlHmRmv6FGRzMcZpHzr63GqD0jJwpLuUZ54uuMgUtjx/liEZG1pesdUuf0ObRVf+xDxk/iLAiNKRvd6BoP7wVm0CkebDlyPObdKW0Ss+tAhB0y1o4das+n+UjpHUuPM2D/eLZFA01umyw=";

	/**
	 * True when the network plugin is installed: settings belong to the party and the lobby menu owns them.
	 * Presence check only, M7 has no compile-time dependency on it.
	 */
	public static boolean suppressed() {
		return Bukkit.getPluginManager().getPlugin("StradNetworkPlugin") != null;
	}

	public void open(Player p) {
		Holder h = new Holder();
		Inventory inv = Bukkit.createInventory(h, 9, Utils.msg("<dark_gray>M7 Settings"));
		h.inv = inv;

		List<String> diffLore = new ArrayList<>();
		// Difficulty.displayName(), not a label() switch here: one fewer place to go stale on a rename.
		for(Difficulty d : Difficulty.values()) diffLore.add(option(d == Difficulty.current(), colour(d), d.displayName()));
		diffLore.add("");
		diffLore.add(CYCLE_HINT);
		inv.setItem(DIFFICULTY_SLOT, button(Material.WITHER_SKELETON_SKULL, "<gold>Difficulty", diffLore));

		List<String> mayorLore = new ArrayList<>();
		for(Mayor m : Mayor.values()) mayorLore.add(option(m == Mayor.current(), colour(m), label(m)));
		mayorLore.add("");
		mayorLore.add(CYCLE_HINT);
		inv.setItem(MAYOR_SLOT, mayorHead(mayorLore));

		List<String> fixLore = new ArrayList<>();
		for(WatcherFix w : WatcherFix.values()) fixLore.add(option(w == WatcherFix.current(), colour(w), label(w)));
		fixLore.add("");
		fixLore.add("<gray>Second wave 3s after the Watcher returns");
		fixLore.add("<gray>to the center, not at tick 420");
		fixLore.add("");
		fixLore.add(CYCLE_HINT);
		inv.setItem(WATCHER_FIX_SLOT, button(Material.SMITHING_TABLE, "<gold>Fix Watcher Bug", fixLore));

		List<String> bonzoLore = new ArrayList<>();
		for(BadBonzo b : BadBonzo.values()) bonzoLore.add(option(b == BadBonzo.current(), colour(b), label(b)));
		bonzoLore.add("");
		bonzoLore.add("<gray>This should make Bonzo Staff behave closer");
		bonzoLore.add("<gray>to actual Hypixel, but I think Hypixel's");
		bonzoLore.add("<gray>implementation is bullshit.");
		bonzoLore.add("<light_purple>Experimental");
		bonzoLore.add("");
		bonzoLore.add(CYCLE_HINT);
		inv.setItem(BAD_BONZO_SLOT, button(Material.BREEZE_ROD, "<gold>Bad Bonzo", bonzoLore));

		List<String> batLore = new ArrayList<>();
		for(BatBug b : BatBug.values()) batLore.add(option(b == BatBug.current(), colour(b), label(b)));
		batLore.add("");
		batLore.add(BatBug.enabled() ? "<gray>First bat killed by each player" : "<gray>First bat killed by any player");
		batLore.add("<gray>grants +1 score");
		batLore.add("<light_purple>Experimental");
		batLore.add("");
		batLore.add(CYCLE_HINT);
		inv.setItem(BAT_BUG_SLOT, button(Material.BAT_SPAWN_EGG, "<gold>Bat Bug", batLore));

		List<String> gdragLore = new ArrayList<>();
		for(AlphaGDrag a : AlphaGDrag.values()) gdragLore.add(option(a == AlphaGDrag.current(), colour(a), label(a)));
		gdragLore.add("");
		gdragLore.add(AlphaGDrag.enabled() ? "<gray>Golden Dragon gives +370 Strength" : "<gray>Golden Dragon gives +300 Strength");
		gdragLore.add("<light_purple>Experimental");
		gdragLore.add("");
		gdragLore.add(CYCLE_HINT);
		inv.setItem(ALPHA_GDRAG_SLOT, button(Material.GOLD_INGOT, "<gold>Alpha GDrag Damage", gdragLore));

		p.openInventory(inv);
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
		if(Menus.ignoreDoubleClick(e)) return;
		e.setCancelled(true); // read-only row: nothing here is ever picked up
		if(!(e.getWhoClicked() instanceof Player p)) return;
		if(e.getClickedInventory() != e.getView().getTopInventory()) return;

		// Broadcasts like the text command: server-wide, so everyone online needs to know. Right-click steps back,
		// so any of three values is one click away.
		boolean back = e.isRightClick();
		if(e.getRawSlot() == DIFFICULTY_SLOT) {
			DungeonSettings.applyDifficulty(back ? Difficulty.toggleBack() : Difficulty.toggle());
		} else if(e.getRawSlot() == MAYOR_SLOT) {
			DungeonSettings.applyMayor(back ? Mayor.toggleBack() : Mayor.toggle());
		} else if(e.getRawSlot() == WATCHER_FIX_SLOT) {
			DungeonSettings.applyWatcherFix(back ? WatcherFix.toggleBack() : WatcherFix.toggle());
		} else if(e.getRawSlot() == BAD_BONZO_SLOT) {
			DungeonSettings.applyBadBonzo(back ? BadBonzo.toggleBack() : BadBonzo.toggle());
		} else if(e.getRawSlot() == BAT_BUG_SLOT) {
			DungeonSettings.applyBatBug(back ? BatBug.toggleBack() : BatBug.toggle());
		} else if(e.getRawSlot() == ALPHA_GDRAG_SLOT) {
			DungeonSettings.applyAlphaGDrag(back ? AlphaGDrag.toggleBack() : AlphaGDrag.toggle());
		} else {
			return;
		}
		open(p); // re-render in place, so the new value is under the cursor
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if(e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
	}

	// ===== helpers =====
	/** One option line: active value is bold and keeps its colour. */
	private static String option(boolean selected, String colour, String displayName) {
		return selected ? colour + "<bold>" + displayName : "<dark_gray>" + displayName;
	}

	/** Presentation only, so a switch here rather than a field on {@link Difficulty}. */
	private static String colour(Difficulty d) {
		return switch(d) {
			case CLASSIC -> "<aqua>";
			case PERFECT_RNG -> "<light_purple>";
			case REALISTIC -> "<red>";
		};
	}

	private static String colour(Mayor m) {
		return switch(m) {
			case JERRY_PAUL -> "<gold>";
			case PAUL -> "<green>";
			case JERRY -> "<yellow>";
			case OTHER -> "<gray>";
			case DERPY -> "<light_purple>";
		};
	}

	private static String colour(WatcherFix w) {
		return switch(w) {
			case OFF -> "<gray>";
			case ON -> "<gold>";
		};
	}

	private static String colour(BadBonzo b) {
		return switch(b) {
			case OFF -> "<gray>";
			case ON -> "<gold>";
		};
	}

	private static String colour(BatBug b) {
		return switch(b) {
			case OFF -> "<gray>";
			case ON -> "<gold>";
		};
	}

	private static String colour(AlphaGDrag a) {
		return switch(a) {
			case OFF -> "<gray>";
			case ON -> "<gold>";
		};
	}

	private static String label(AlphaGDrag a) {
		return switch(a) {
			case OFF -> "Off";
			case ON -> "On";
		};
	}

	private static String label(BatBug b) {
		return switch(b) {
			case OFF -> "Off";
			case ON -> "On";
		};
	}

	private static String label(BadBonzo b) {
		return switch(b) {
			case OFF -> "Off";
			case ON -> "On";
		};
	}

	private static String label(WatcherFix w) {
		return switch(w) {
			case OFF -> "Off";
			case ON -> "On";
		};
	}

	private static String label(Mayor m) {
		return switch(m) {
			case JERRY_PAUL -> "Jerry-Paul";
			case PAUL -> "Paul";
			case JERRY -> "Jerry";
			case OTHER -> "Other";
			case DERPY -> "Derpy";
		};
	}

	private static ItemStack mayorHead(List<String> lore) {
		ItemStack it = button(Material.PLAYER_HEAD, "<gold>Mayor", lore);
		if(it.getItemMeta() instanceof SkullMeta sm) {
			com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(MAYOR_HEAD_ID, "Megakloon");
			profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty(
					"textures", MAYOR_HEAD_VALUE, MAYOR_HEAD_SIGNATURE));
			sm.setPlayerProfile(profile);
			it.setItemMeta(sm);
		}
		return it;
	}

	private static ItemStack button(Material mat, String name, List<String> lore) {
		ItemStack it = new ItemStack(mat);
		ItemMeta m = it.getItemMeta();
		if(m != null) {
			m.displayName(Utils.msg(name).decoration(TextDecoration.ITALIC, false));
			List<Component> rendered = new ArrayList<>();
			for(String line : lore) rendered.add(Utils.msg(line).decoration(TextDecoration.ITALIC, false));
			m.lore(rendered);
			it.setItemMeta(m);
		}
		return it;
	}

	/** Marker holder; no state, both values are server-wide globals. */
	private static final class Holder implements InventoryHolder {
		Inventory inv;

		@Override
		public @NonNull Inventory getInventory() {
			return inv;
		}
	}
}
