package commands;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.NonNull;
import plugin.M7;
import plugin.Menus;
import plugin.Utils;

import java.util.*;

/*
 * /eq, a real Hypixel command. 1-row GUI: worn armor in slots 0-3, speed sugar cane in slot 8 (count scheme at
 * applySpeedCane, exact speed on tooltip). Clicking an armor piece in your own inventory while open swaps it
 * onto your body and into the matching GUI slot.
 *
 * Registered as /eq executor and as listener, two stateless instances: the GUI is identified by {@link EqHolder},
 * not instance state.
 */
public class Eq implements CommandExecutor, Listener {

	private static final Component TITLE = Utils.msg("<dark_gray>Equipment");
	private static final int SPEED_SLOT = 8;
	/** Speed modifiers {@link #currentSpeed} leaves out: vanilla mechanics that aren't part of the SkyBlock speed
	 *  stat. Speed/Soul Speed and our own modifiers are absent on purpose, they belong in the stat. */
	private static final Set<NamespacedKey> IGNORED_SPEED_MODIFIERS = Set.of(NamespacedKey.minecraft("sprinting"));
	/** Last tick a swap ran per player; collapses a double-click's burst of events into one swap. */
	private static final Map<UUID, Integer> lastSwapTick = new HashMap<>();

	public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
		if(!(sender instanceof Player p)) {
			sender.sendMessage(Utils.msg("Only players can run this"));
			return true;
		}
		open(p);
		return true;
	}

	private static void open(Player p) {
		EqHolder holder = new EqHolder();
		Inventory gui = Bukkit.createInventory(holder, 9, TITLE);
		holder.setInventory(gui);
		refresh(p, gui);
		p.openInventory(gui);
		applySpeedCane(p); // after the menu exists, it writes the cane via NMS
	}

	/** Armor into slots 0-3, pet into 4, aggregate stats into 6-7. Slot 8's cane is set separately via NMS. */
	private static void refresh(Player p, Inventory gui) {
		for(int i = 0; i < 4; i++) {
			ItemStack worn = getArmor(p, i);
			gui.setItem(i, worn == null ? null : worn.clone());
		}
		gui.setItem(PET_SLOT, petItem(p));
		gui.setItem(MELEE_STATS_SLOT, meleeStatsItem(p));
		gui.setItem(MAGIC_STATS_SLOT, magicStatsItem(p));
	}

	// =================== Aggregate stat readout (MAP.md §7b) ===================
	// Lore only shows one item's contribution. These two slots show the whole aggregate: equipment, Accessory
	// Power, tunings, profile sources and class bonus. Generated from the stat layer, never authored, like lore.

	private static final int PET_SLOT = 4;
	private static final int MELEE_STATS_SLOT = 6;
	private static final int MAGIC_STATS_SLOT = 7;

	/**
	 * Slot 4: the pet the damage model is using, and Max Speed.
	 * <p>
	 * {@code Pet.forPlayer}, not {@code Pets.equipped}: same in realistic, but in classic and Perfect RNG the pet
	 * is assumed from what the player does and wears, and this menu shows what the model really runs on. It says
	 * which, so an assumed pet isn't mistaken for a choice.
	 * <p>
	 * Max Speed is a sum of four terms ({@code plugin/MaxSpeedSync}), one being the pet (Black Cat's +150), so
	 * "my cat is out but I'm on 450" had no in-game answer before.
	 * <p>
	 * Head is {@code PetType.icon}; only its trailing summon-menu action line is swapped for the two lines below.
	 */
	private static ItemStack petItem(Player p) {
		damage.DungeonClass clazz = damage.DungeonClass.of(p);
		damage.DamagePath path = clazz.primaryPath();
		pets.PetType type = pets.PetType.of(damage.Pet.forPlayer(p, path));
		if(type == null) return null; // damage-side pet with no menu twin
		ItemStack item = type.icon(true, false);
		ItemMeta meta = item.getItemMeta();
		if(meta == null) return item;
		java.util.List<net.kyori.adventure.text.Component> lore = meta.lore();
		java.util.List<net.kyori.adventure.text.Component> out =
				lore == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(lore);
		if(!out.isEmpty()) out.removeLast(); // "CURRENTLY SUMMONED", which belongs to /pets
		out.add(Utils.mm("<gray>Max Speed: <white>" + plugin.MaxSpeedSync.maxSpeed(p)));
		out.add(net.kyori.adventure.text.Component.empty());
		out.add(Utils.mm(damage.Difficulty.manualPets()
				? "<dark_gray>Yours, picked in /pets"
				: "<dark_gray>Assumed from your class, gear and phase"));
		meta.lore(out);
		item.setItemMeta(meta);
		return item;
	}

	/** Slot 6: Damage, Strength, Crit Damage (melee/beam half). */
	private static ItemStack meleeStatsItem(Player p) {
		return statsItem(p, Material.DIAMOND_SWORD, "<red>Offensive Stats",
				damage.Stat.DAMAGE, damage.Stat.STRENGTH, damage.Stat.CRIT_DAMAGE);
	}

	/** Slot 7: Intelligence and Ability Damage, read by the beam multiplier and ability formula. */
	private static ItemStack magicStatsItem(Player p) {
		return statsItem(p, Material.POTION, "<aqua>Magic Stats",
				damage.Stat.INTELLIGENCE, damage.Stat.ABILITY_DAMAGE);
	}

	private static ItemStack statsItem(Player p, Material material, String title, damage.Stat... stats) {
		// Aggregate is path-resolved, so pick a path and say which: a Mage's ability-path Intelligence differs from
		// their beam-path one.
		damage.DungeonClass clazz = damage.DungeonClass.of(p);
		damage.DamagePath path = clazz.primaryPath();
		damage.StatBlock block = damage.Stats.of(p, path);

		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if(meta == null) return item;
		meta.displayName(Utils.mm(title));
		java.util.List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
		for(damage.Stat stat : stats) {
			// No glyph, no leading "+": it's a total, not a bonus ("+3729.6 on top of what?"). Item lore keeps the
			// "+" because there it is the item's contribution.
			lore.add(Utils.mm("<gray>" + stat.display() + ": " + stat.colour()
					+ Utils.roundCommas(block.get(stat), 1)));
		}
		lore.add(net.kyori.adventure.text.Component.empty());
		lore.add(Utils.mm("<dark_gray>" + clazz + ", " + path.name().toLowerCase(java.util.Locale.ROOT) + " path"));
		meta.lore(lore);
		item.setItemMeta(meta);
		// Magic Stats is a POTION, so vanilla appends potion-effect lines. Replaces the deprecated
		// ItemFlag.HIDE_ADDITIONAL_TOOLTIP, which covered a dozen unrelated sources. Set AFTER setItemMeta, which
		// writes the whole component patch. Harmless on the Offensive Stats sword.
		item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
				.addHiddenComponents(DataComponentTypes.POTION_CONTENTS));
		return item;
	}

	/**
	 * Speed cane into slot 8 via NMS. Count is speed/10 (650 -> 65): the client clamps count to max_stack_size,
	 * which is hard-capped at 99, so a 3-digit speed can't be a count. Exact speed stays on the tooltip.
	 * MAX_STACK_SIZE is raised to the count since sugar cane's cap of 64 would clamp 65..99. NMS because the
	 * Bukkit ItemStack path clamps count to max stack.
	 */
	private static void applySpeedCane(Player p) {
		ServerPlayer sp = ((CraftPlayer) p).getHandle();
		AbstractContainerMenu menu = sp.containerMenu;
		if(SPEED_SLOT >= menu.slots.size()) return;
		int amount = Math.clamp(currentSpeed(p) / 10, 1, 99); // speed/10, within the 99 stack-count ceiling
		net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(speedItem(p));
		nms.set(DataComponents.MAX_STACK_SIZE, amount); // raise the cap so the client renders counts of 65..99
		nms.setCount(amount);
		menu.getSlot(SPEED_SLOT).set(nms);
		sp.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), SPEED_SLOT, nms));
	}

	private static ItemStack speedItem(Player p) {
		int speed = currentSpeed(p);
		ItemStack cane = new ItemStack(Material.SUGAR_CANE);
		ItemMeta meta = cane.getItemMeta();
		if(meta != null) {
			meta.displayName(Utils.mm("<aqua>Speed: <white>" + speed));
			cane.setItemMeta(meta);
		}
		return cane;
	}

	/**
	 * Current movement speed on the 100-based scale (100 = vanilla default).
	 * <p>
	 * Resolves the attribute itself instead of {@code getValue()} so {@link #IGNORED_SPEED_MODIFIERS} are left out
	 * rather than undone. {@code LivingEntity.setSprinting} adds {@code minecraft:sprinting} (+0.3
	 * ADD_MULTIPLIED_TOTAL) as a transient modifier while sprinting, and {@code getValue()} counts it, so a
	 * sprinting player read 30% high (400 showed as 520). Transient modifiers do appear in {@code getModifiers()},
	 * so no NMS needed.
	 * <p>
	 * Three passes mirror {@code AttributeInstance.calculateValue}: base + every ADD_NUMBER, each ADD_SCALAR against
	 * that base, then each MULTIPLY_SCALAR_1. Each pass is order-independent, so this matches vanilla exactly.
	 * Negatives clamp to 0 like vanilla's {@code sanitizeValue}; the Spigot ceiling is out of reach, so ignored.
	 */
	private static int currentSpeed(Player p) {
		var attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
		if(attr == null || attr.getBaseValue() == 0) return 100;
		double vanillaBase = attr.getBaseValue(); // 100-point scale is relative to this
		Collection<AttributeModifier> mods = attr.getModifiers();

		double base = vanillaBase;
		for(AttributeModifier mod : mods) {
			if(counts(mod, AttributeModifier.Operation.ADD_NUMBER)) base += mod.getAmount();
		}
		double value = base;
		for(AttributeModifier mod : mods) {
			if(counts(mod, AttributeModifier.Operation.ADD_SCALAR)) value += base * mod.getAmount();
		}
		for(AttributeModifier mod : mods) {
			if(counts(mod, AttributeModifier.Operation.MULTIPLY_SCALAR_1)) value *= 1 + mod.getAmount();
		}
		return (int) Math.round(Math.max(0, value) / vanillaBase * 100);
	}

	/** Whether a modifier belongs in {@code op}'s pass of {@link #currentSpeed}. */
	private static boolean counts(AttributeModifier mod, AttributeModifier.Operation op) {
		return mod.getOperation() == op && !IGNORED_SPEED_MODIFIERS.contains(mod.getKey());
	}

	// =================== Click handling: swap armor from the player's inventory ===================

	@EventHandler
	public void onInventoryClick(InventoryClickEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof EqHolder)) return;
		if(Menus.ignoreDoubleClick(e)) return;
		e.setCancelled(true); // only the armor swap below changes anything
		if(!(e.getWhoClicked() instanceof Player p)) return;
		Inventory clicked = e.getClickedInventory();
		if(clicked == null || !clicked.equals(p.getInventory())) return; // only bottom-inventory clicks act

		ItemStack item = e.getCurrentItem();
		if(item == null || item.getType() == Material.AIR) return;
		int idx = armorSlotIndex(item.getType());
		if(idx < 0) return;

		// Double-click fires several events in one tick, so max one swap per player per tick.
		int now = MinecraftServer.currentTick;
		if(lastSwapTick.getOrDefault(p.getUniqueId(), -1) == now) return;
		lastSwapTick.put(p.getUniqueId(), now);

		// Equip the clicked piece, old piece goes to the clicked slot.
		ItemStack worn = getArmor(p, idx);
		setArmor(p, idx, item.clone());
		e.setCurrentItem(worn); // null clears the slot
		Inventory gui = e.getView().getTopInventory();
		gui.setItem(idx, item.clone());
		// Helmet swap changes Max Speed next tick (MaxSpeedSync poll) and the aggregate (thousands of Intelligence
		// for Storm's helmet), so redraw both after.
		Bukkit.getScheduler().runTaskLater(M7.getInstance(), () -> {
			if(p.getOpenInventory().getTopInventory().getHolder() instanceof EqHolder) {
				gui.setItem(PET_SLOT, petItem(p));
				gui.setItem(MELEE_STATS_SLOT, meleeStatsItem(p));
				gui.setItem(MAGIC_STATS_SLOT, magicStatsItem(p));
				applySpeedCane(p);
			}
		}, 2L);
	}

	@EventHandler
	public void onInventoryDrag(InventoryDragEvent e) {
		if(!(e.getView().getTopInventory().getHolder() instanceof EqHolder)) return;
		int topSize = e.getView().getTopInventory().getSize();
		for(int slot : e.getRawSlots()) {
			if(slot < topSize) { // any drag touching the GUI is blocked
				e.setCancelled(true);
				return;
			}
		}
	}

	// =================== Armor helpers ===================

	/** GUI/equipment slot for an armor material: 0 helmet, 1 chestplate, 2 leggings, 3 boots, else -1. */
	private static int armorSlotIndex(Material m) {
		String n = m.name();
		if(n.endsWith("_HELMET") || m == Material.PLAYER_HEAD || m == Material.CARVED_PUMPKIN) return 0;
		if(n.endsWith("_CHESTPLATE") || m == Material.ELYTRA) return 1;
		if(n.endsWith("_LEGGINGS")) return 2;
		if(n.endsWith("_BOOTS")) return 3;
		return -1;
	}

	private static ItemStack getArmor(Player p, int idx) {
		PlayerInventory inv = p.getInventory();
		return switch(idx) {
			case 0 -> inv.getHelmet();
			case 1 -> inv.getChestplate();
			case 2 -> inv.getLeggings();
			case 3 -> inv.getBoots();
			default -> null;
		};
	}

	private static void setArmor(Player p, int idx, ItemStack item) {
		PlayerInventory inv = p.getInventory();
		switch(idx) {
			case 0 -> inv.setHelmet(item);
			case 1 -> inv.setChestplate(item);
			case 2 -> inv.setLeggings(item);
			case 3 -> inv.setBoots(item);
		}
	}

	/** Marker holder for the /eq GUI. */
	public static final class EqHolder implements InventoryHolder {
		private Inventory inv;
		void setInventory(Inventory inv) { this.inv = inv; }
		@Override public @NonNull Inventory getInventory() { return inv; }
	}
}
