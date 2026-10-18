package items.tools;

import damage.Difficulty;
import damage.Rarity;
import damage.ReforgeId;
import items.AbilityItem;
import items.ItemFactory;
import items.ItemUtils;
import items.Tool;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import plugin.M7;
import plugin.Utils;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * No ability: a broken block is removed TEMPORARILY without physics and restored 200 ticks later
 * ({@code ItemUtils.stonk}). As a pickaxe its right-click isn't cancelled, it may right-click entities, and it's the
 * one item that works in the Trap room.
 * <p>
 * Lore ID is {@code skyblock/combat/stonk}. The {@code .../dungeonbreaker} ID in two old exemption lists was never
 * carried by any item and is dropped.
 * <p>
 * <b>Live modes only:</b> {@link #MAX_CHARGES} charges per player, one per break, {@link #REGEN_PER_SECOND}/s back
 * and {@link #CHARGES_PER_SECRET} per secret, full at run start; drawn as the durability bar, 5% a charge, 1 left at
 * empty. {@link #IMMUNE} blocks (storage, pistons, redstone, doors and the like) never break.
 */
public final class Dungeonbreaker implements Tool, AbilityItem {
	public static final Dungeonbreaker INSTANCE = new Dungeonbreaker();

	private Dungeonbreaker() {}

	@Override
	public String loreId() {
		return "skyblock/combat/stonk";
	}

	@Override
	public Material material() {
		return Material.DIAMOND_PICKAXE;
	}

	@Override
	public String baseName() {
		return "Dungeonbreaker";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.SPECIAL;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.NONE;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return buildPickaxe(colouredName(reforge));
	}

	@Override
	public boolean cancelsInteract() {
		return false;
	}

	@Override
	public boolean allowsEntityInteract() {
		return true;
	}

	@Override
	public boolean usableInTrapRoom() {
		return true;
	}

	@Override
	public boolean onBreak(Player p, Block block) {
		if(Difficulty.liveInputs()) {
			// Refused breaks still return true: the event is already cancelled, so the block stays.
			if(IMMUNE.contains(block.getType())) return true;
			int left = charges(p);
			if(left <= 0) return true;
			charges.put(p.getUniqueId(), left - 1);
			drawCharges(p);
		}
		ItemUtils.stonk(p, block); // no-physics removal, restored after 200 ticks
		return true;
	}

	public static final int MAX_CHARGES = 20;
	private static final int REGEN_PER_SECOND = 2;
	private static final int CHARGES_PER_SECRET = 5;

	/** Missing = full: everyone spawns in with {@link #MAX_CHARGES}. */
	private static final Map<UUID, Integer> charges = new HashMap<>();

	/** Blocks with an inventory, pistons, redstone parts, and the rest the owner listed. Live modes only. */
	private static final Set<Material> IMMUNE = EnumSet.noneOf(Material.class);

	static {
		for(Material m : Material.values()) {
			if(!m.name().startsWith("LEGACY_") && m.isBlock() && isImmune(m)) IMMUNE.add(m);
		}
	}

	private static boolean isImmune(Material m) {
		if(Tag.SHULKER_BOXES.isTagged(m) || Tag.BUTTONS.isTagged(m) || Tag.PRESSURE_PLATES.isTagged(m)
				|| Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
				|| Tag.RAILS.isTagged(m) || Tag.CAMPFIRES.isTagged(m) || Tag.FLOWER_POTS.isTagged(m)) {
			return true;
		}
		// Families whose tags are newer than our API certainty: copper chests, shelves, copper bulbs, lightning rods.
		String n = m.name();
		if(n.contains("COPPER_CHEST") || n.endsWith("_SHELF") || n.endsWith("COPPER_BULB") || n.contains("LIGHTNING_ROD")) {
			return true;
		}
		return switch(m) {
			case CHEST, TRAPPED_CHEST, ENDER_CHEST, BARREL, HOPPER, DROPPER, DISPENSER, CRAFTER, FURNACE,
					BLAST_FURNACE, SMOKER, BREWING_STAND, CHISELED_BOOKSHELF, DECORATED_POT, LECTERN, JUKEBOX, BEACON,
					PISTON, STICKY_PISTON, PISTON_HEAD, MOVING_PISTON,
					REDSTONE_WIRE, REDSTONE_TORCH, REDSTONE_WALL_TORCH, REPEATER, COMPARATOR, OBSERVER, LEVER,
					TRIPWIRE_HOOK, TRIPWIRE, DAYLIGHT_DETECTOR, TARGET, REDSTONE_LAMP, NOTE_BLOCK, REDSTONE_BLOCK,
					SCULK_SENSOR, CALIBRATED_SCULK_SENSOR, TNT, BELL, SPAWNER, TRIAL_SPAWNER, VAULT -> true;
			default -> false;
		};
	}

	public static int charges(Player p) {
		return charges.getOrDefault(p.getUniqueId(), MAX_CHARGES);
	}

	/** From {@code ClearManager.secretFound}. */
	public static void onSecret(Player p) {
		if(p == null || !Difficulty.liveInputs()) return;
		charges.put(p.getUniqueId(), Math.min(MAX_CHARGES, charges(p) + CHARGES_PER_SECRET));
		drawCharges(p);
	}

	/** Run start: everyone back to full. */
	public static void reset() {
		charges.clear();
		for(Player p : Bukkit.getOnlinePlayers()) drawCharges(p);
	}

	/** Regen ticker, one charge per player every {@code 20 / REGEN_PER_SECOND} ticks. Started from {@code M7}. */
	public static void start() {
		Bukkit.getScheduler().runTaskTimer(M7.getInstance(), () -> {
			for(Player p : Bukkit.getOnlinePlayers()) {
				int left = charges(p);
				if(left < MAX_CHARGES) charges.put(p.getUniqueId(), left + 1);
				drawCharges(p);
			}
		}, 20L / REGEN_PER_SECOND, 20L / REGEN_PER_SECOND);
	}

	/**
	 * Bar on every Dungeonbreaker the player carries: max damage {@link #MAX_CHARGES}, so a charge is 5%, and never
	 * past max - 1 so an empty pick reads 1 durability. Classic is always full (no bar). Writes only on a change.
	 */
	private static void drawCharges(Player p) {
		int left = Difficulty.liveInputs() ? charges(p) : MAX_CHARGES;
		int damage = Math.min(MAX_CHARGES - left, MAX_CHARGES - 1);
		ItemStack[] storage = p.getInventory().getStorageContents();
		boolean changed = false;
		for(ItemStack it : storage) {
			if(!INSTANCE.matches(it) || !(it.getItemMeta() instanceof Damageable m)) continue;
			if(m.hasMaxDamage() && m.getMaxDamage() == MAX_CHARGES && m.getDamage() == damage) continue;
			m.setMaxDamage(MAX_CHARGES);
			m.setDamage(damage);
			it.setItemMeta(m);
			changed = true;
		}
		if(changed) p.getInventory().setStorageContents(storage);
	}

	/**
	 * Efficiency 255 + flat +1024 break speed, so stonks are instant. Can-break stamp LAST: it mutates the NMS copy
	 * and must follow every {@code setItemMeta}.
	 */
	private static ItemStack buildPickaxe(String name) {
		ItemStack pickaxe = ItemFactory.item(Material.DIAMOND_PICKAXE, name, "skyblock/combat/stonk", "DUNGEONBREAKER");
		pickaxe.addUnsafeEnchantment(Enchantment.EFFICIENCY, 255);
		ItemMeta meta = pickaxe.getItemMeta();
		meta.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(
				new NamespacedKey(M7.getInstance(), "stonk"), 1024,
				AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
		pickaxe.setItemMeta(meta);
		return Utils.breakAnyBlockInAdventure(pickaxe);
	}

}
