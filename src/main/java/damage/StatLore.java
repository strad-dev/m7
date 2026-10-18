package damage;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import plugin.M7;
import plugin.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Renders an item's stats into its lore (MAP.md §7b).
 * <p>
 * <b>Lore is output, never input.</b> Numbers come from {@link Items}' term lists, the same ones the damage math
 * reads, so they can't drift; hand-writing them would be the second source of truth §2.4 forbids.
 *
 * <h2>The ID stays on lore line 0</h2>
 * {@code items.ItemUtils.getID} reads line 0, and {@code loadout/ItemRefresh} matches saved loadouts on
 * {@code Catalog.paletteKey} ({@code material | display name | first lore line}). So stat lines are APPENDED below
 * line 0, never above it, or custom items lose their abilities and every saved loadout silently stops updating.
 * {@link #strip} obeys the same rule: never line 0, never a line it didn't positively recognise.
 * <p>
 * Shows the DUNGEON-SCALED number (Fabled Hyperion {@code +3729.6} Strength, not {@code +560}). No zero rows;
 * reforge and rarity are in the name already.
 *
 * <h2>Two renderings, because the pet moves</h2>
 * The pet reaches one term, {@code ItemDef.breakdown}'s {@code chimera (<pet>)}, so only Chimera items have
 * pet-dependent lore and only they get re-rendered.
 * <ul>
 *   <li>{@link #apply(ItemStack)} - TEMPLATE: factories, {@code plugin/FakePlayerInventory} and {@code plugin/Utils}
 *       have no player, so it renders the assumed Golden Dragon (§1.13).</li>
 *   <li>{@link #refreshChimeraLore(Player)} - LIVE: re-renders the Chimera items a player carries for their actual
 *       pet, in every mode.</li>
 * </ul>
 */
public final class StatLore {
	private StatLore() {}

	/** SkyBlock's tooltip order. */
	private static final Stat[] ROWS = {Stat.DAMAGE, Stat.STRENGTH, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE,
			Stat.INTELLIGENCE, Stat.ABILITY_DAMAGE};

	/**
	 * Plain-text shape of one row, built from {@link #ROWS} so it can't drift from {@link #apply(ItemStack, Pet)}:
	 * stat name, {@code ": +"}, number, anchored both ends. The name prefix makes it safe: line 0 (the ID, or armour's
	 * blank) can't read as {@code "Strength: +..."}. The number half is looser than {@code roundCommas} on purpose: a
	 * false negative leaves a stale row for one re-render, a false positive eats a real lore line.
	 */
	private static final Pattern ROW_SHAPE = rowShape();

	private static Pattern rowShape() {
		StringBuilder names = new StringBuilder();
		for(Stat stat : ROWS) {
			if(!names.isEmpty()) names.append('|');
			names.append(Pattern.quote(stat.display()));
		}
		// apply always writes '+', so a negative renders "+-5"; hence the optional sign after the plus.
		return Pattern.compile("^(?:" + names + "): \\+-?[0-9][0-9,]*(?:\\.[0-9]+)?$");
	}

	/**
	 * Append stat rows if it's a registered stat item; returns the same stack for chaining. TEMPLATE rendering at the
	 * Golden Dragon, for no-player callers (the ~40 {@code Item.freshStack}s, {@code plugin/FakePlayerInventory},
	 * {@code plugin/Utils}); {@link #refreshChimeraLore(Player)} corrects it once a player holds it.
	 * <p>
	 * Factories build fresh stacks, so no double rows; calling it twice on one stack would duplicate them (see
	 * {@link #strip}). {@code loadout/ItemRefresh} re-matches saved kits on every editor open, so a stat change
	 * propagates with no migration as long as line 0 is untouched.
	 */
	public static ItemStack apply(ItemStack item) {
		return apply(item, Pet.GOLDEN_DRAGON);
	}

	/** Same at a given pet, which only reaches Chimera's copy. */
	public static ItemStack apply(ItemStack item, Pet pet) {
		ItemDef def = Items.of(item);
		if(def == null) return item;
		StatBlock stats = def.stats(pet);
		if(stats.isEmpty()) return item;

		ItemMeta meta = item.getItemMeta();
		if(meta == null) return item;
		List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(Objects.requireNonNull(meta.lore()));
		// No-ability items (armour, heads) have no lore, so their palette key's lore part is "". Appending straight
		// on would make the first stat row the key and orphan every saved loadout holding it. A blank line 0 keeps
		// the key "" and reads as a normal tooltip separator.
		if(lore.isEmpty()) lore.add(Component.empty());
		for(Stat stat : ROWS) {
			double value = stats.get(stat);
			if(value == 0) continue;
			// No glyph: ❁ ☠ ✎ come from a resource-pack font and render as tofu on vanilla.
			lore.add(Utils.mm("<gray>" + stat.display() + ": " + stat.colour() + "+" + trim(value)));
		}
		meta.lore(lore);
		item.setItemMeta(meta);
		return item;
	}

	/** For a stack ALREADY carrying rows: strip, then append. {@link #apply} alone would double them. */
	public static ItemStack rerender(ItemStack item, Pet pet) {
		strip(item);
		return apply(item, pet);
	}

	/**
	 * Remove trailing stat rows, nothing else. Two rules:
	 * <ol>
	 *   <li>TAIL only, stopping at the first non-row. {@link #apply} appends rows last in one run, so nothing above
	 *       them is reachable.</li>
	 *   <li>Never line 0: the ID (or armour's blank) is part of {@code Catalog.paletteKey}, and eating it silently and
	 *       permanently unmatches every saved loadout. That's why the bound is {@code > 1}; don't "simplify" to
	 *       {@code > 0}.</li>
	 * </ol>
	 */
	private static void strip(ItemStack item) {
		if(item == null) return;
		ItemMeta meta = item.getItemMeta();
		if(meta == null || meta.lore() == null) return;
		List<Component> lore = new ArrayList<>(Objects.requireNonNull(meta.lore()));
		int end = lore.size();
		while(end > 1 && ROW_SHAPE.matcher(Utils.plain(lore.get(end - 1))).matches()) end--;
		if(end == lore.size()) return; // un-rendered stack, leave it alone
		meta.lore(new ArrayList<>(lore.subList(0, end)));
		item.setItemMeta(meta);
	}

	/**
	 * Re-render the player's Chimera items for their current pet. Chimera ONLY (via {@link ItemDef#chimera()}, never
	 * a name list): other lore is pet-independent and rewriting a live inventory is risky
	 * ({@code loadout/LoadoutEditor} has the scars).
	 * <p>
	 * Skipped while the loadout editor is open: its 36 slots ARE the kit and {@code LoadoutEditor.finish} saves them
	 * verbatim, so a live-pet render would get baked in. Closing the editor refreshes anyway ({@code StatListener}).
	 */
	public static void refreshChimeraLore(Player p) {
		if(p == null) return;
		// NEXT TICK, always, as the method's contract: callers are mid-event (MONITOR on InventoryClickEvent before
		// its result applies, Pets.equip from a menu click), and writing slots now races that. isEnabled guards
		// shutdown, where the scheduler refuses tasks.
		if(!M7.getInstance().isEnabled()) return;
		Bukkit.getScheduler().runTask(M7.getInstance(), () -> rerenderChimeraNow(p));
	}

	/** {@link #refreshChimeraLore}'s deferred half. Never call directly. */
	private static void rerenderChimeraNow(Player p) {
		if(!p.isOnline()) return;
		if(p.getOpenInventory().getTopInventory().getHolder() instanceof loadout.LoadoutEditor.EditorHolder) return;
		// MELEE: lore has no hit to take a path from, and ABILITY would assume the Crow. Ignored in realistic, where
		// manualPets() short-circuits in Pet.forPlayer.
		Pet pet = Pet.forPlayer(p, DamagePath.MELEE);
		PlayerInventory inv = p.getInventory();
		for(int slot = 0; slot < inv.getSize(); slot++) {
			ItemStack before = inv.getItem(slot);
			ItemDef def = Items.of(before);
			if(def == null || !def.chimera()) continue;
			ItemStack after = rerender(before.clone(), pet);
			// isSimilar ignores amount, which the clone kept, so this is a full compare. Usually nothing changed;
			// skipping the write avoids resending a slot every click.
			if(!before.isSimilar(after)) inv.setItem(slot, after);
		}
	}

	/** {@code +999}, not {@code +999.0}. */
	private static String trim(double value) {
		String s = Utils.roundCommas(value, 2);
		if(s.endsWith(".00")) return s.substring(0, s.length() - 3);
		if(s.endsWith("0")) return s.substring(0, s.length() - 1);
		return s;
	}
}
