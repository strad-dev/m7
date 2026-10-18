package plugin;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import damage.ReforgeId;
import items.armor.*;
import items.bows.ExplosiveBow;
import items.bows.LastBreath;
import items.bows.Terminator;
import items.combat.*;
import items.tools.Dungeonbreaker;
import items.tools.GyrokineticWand;
import items.tools.InfinityboomTNT;
import items.tools.TacticalInsertion;
import items.util.Infinileap;
import items.util.PitchinRod;
import items.util.SkyblockMenu;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import nms.NBT;
import org.bukkit.Material;
import org.bukkit.craftbukkit.profile.CraftPlayerProfile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

public class FakePlayerInventory {

	/**
	 * The ONE definition of a class's kit, as a 41-slot array: [0..35] main inventory, [36] helmet, [37] chestplate,
	 * [38] leggings, [39] boots, [40] off-hand. Read by the catalog export ({@link Catalog}).
	 */
	public static ItemStack[] classLoadoutContents(String role) {
		ItemStack[] arr = new ItemStack[41];

		// 16 vanilla ender pearls: the one kit stack that isn't an Item (no name, no lore ID).
		ItemStack pearls = new ItemStack(Material.ENDER_PEARL);
		pearls.setAmount(16);

		switch(role) {
			case "Archer", "Berserk", "Healer", "Tank" -> {
				arr[36] = NecronHead.INSTANCE.build();
				arr[37] = NecronChestplate.INSTANCE.build();
				arr[38] = NecronLeggings.INSTANCE.build();
				arr[39] = NecronBoots.INSTANCE.build();
			}
			case "Mage" -> {
				arr[36] = StormHelmet.INSTANCE.build();
				arr[37] = StormChestplate.INSTANCE.build();
				arr[38] = StormLeggings.INSTANCE.build();
				arr[39] = StormBoots.INSTANCE.build();
			}
		}

		arr[0] = Hyperion.INSTANCE.build(ReforgeId.HEROIC);
		arr[1] = AspectOfTheVoid.INSTANCE.build();
		arr[5] = Dungeonbreaker.INSTANCE.build();
		arr[6] = GyrokineticWand.INSTANCE.build();
		arr[7] = pearls;
		arr[8] = SkyblockMenu.INSTANCE.build();
		arr[9] = SpiritMask.INSTANCE.build();
		arr[10] = BonzoMask.INSTANCE.build();
		arr[11] = SpringBootsItem.INSTANCE.build();
		arr[12] = RacingHelmet.INSTANCE.build();
		arr[13] = CowHat.INSTANCE.build();
		arr[28] = BonzoStaff.INSTANCE.build();
		arr[29] = TacticalInsertion.INSTANCE.build();
		arr[34] = JerrychineGun.INSTANCE.build();

		switch(role) {
			case "Archer" -> {
				arr[2] = Infinileap.INSTANCE.build();
				arr[3] = InfinityboomTNT.INSTANCE.build();
				arr[4] = Terminator.INSTANCE.build();
				arr[18] = ThermodynamicHelmet.INSTANCE.build();
				arr[19] = ThermodynamicChestplate.INSTANCE.build();
				arr[20] = ThermodynamicLeggings.INSTANCE.build();
				arr[21] = ThermodynamicBoots.INSTANCE.build();
				// Slot 30 was the Rapid Bonemerang; dropped, it had no ability.
				arr[32] = LastBreath.INSTANCE.build();
				arr[33] = RagnarockAxe.INSTANCE.build();
				arr[35] = PitchinRod.INSTANCE.build();
			}
			case "Berserk" -> {
				arr[2] = Infinileap.INSTANCE.build();
				arr[3] = InfinityboomTNT.INSTANCE.build();
				arr[4] = Terminator.INSTANCE.build();
				arr[33] = RagnarockAxe.INSTANCE.build();
				arr[35] = PitchinRod.INSTANCE.build();
			}
			case "Healer" -> {
				arr[2] = IceSprayWand.INSTANCE.build();
				arr[3] = InfinityboomTNT.INSTANCE.build();
				arr[4] = Terminator.INSTANCE.build();
				arr[30] = Infinileap.INSTANCE.build();
				arr[32] = FlamingFlay.INSTANCE.build();
				arr[33] = LastBreath.INSTANCE.build();
				arr[35] = PitchinRod.INSTANCE.build();
			}
			case "Mage" -> {
				arr[2] = IceSprayWand.INSTANCE.build();
				arr[3] = DarkClaymore.INSTANCE.build();
				arr[4] = Infinileap.INSTANCE.build();
				// Storage row 2, above the Infinityboom in slot 31. No Power: Catalog.defaultFor's class bake only
				// touches slot 4, and the Mage's Terminator Power is 0 anyway.
				arr[22] = Terminator.INSTANCE.build();
				arr[30] = Hyperion.INSTANCE.build(ReforgeId.FABLED);
				arr[31] = InfinityboomTNT.INSTANCE.build();
				arr[32] = RagnarockAxe.INSTANCE.build();
				arr[33] = LastBreath.INSTANCE.build();
				arr[35] = ExplosiveBow.INSTANCE.build();
			}
			case "Tank" -> {
				arr[2] = IceSprayWand.INSTANCE.build();
				arr[3] = InfinityboomTNT.INSTANCE.build();
				arr[4] = Terminator.INSTANCE.build();
				arr[30] = Infinileap.INSTANCE.build();
				arr[31] = AxeOfTheShredded.INSTANCE.build();
				arr[32] = FlamingFlay.INSTANCE.build();
				arr[33] = LastBreath.INSTANCE.build();
			}
		}
		return arr;
	}

	// --- Item Creators ---

	public static ItemStack getCustomHead(String displayName, String identifier, String textureValue, String textureSignature) {
		Multimap<String, Property> props = HashMultimap.create();
		props.put("textures", new Property("textures", textureValue, textureSignature));
		PropertyMap propertyMap = new PropertyMap(props);
		// Profile id from the identifier, NOT random: it's in the item's NBT, and a random one made every copy of a
		// head byte-different, so the palette listed shared heads (Spirit Mask, Bonzo Mask, ...) once per class.
		GameProfile gp = new GameProfile(UUID.nameUUIDFromBytes(identifier.getBytes(StandardCharsets.UTF_8)), identifier, propertyMap);

		CraftPlayerProfile profile = new CraftPlayerProfile(gp);

		ItemStack helmet = new ItemStack(Material.PLAYER_HEAD);
		SkullMeta meta = (SkullMeta) helmet.getItemMeta();
		assert meta != null;
		meta.displayName(Utils.mm(displayName));
		meta.setPlayerProfile(profile);
		helmet.setItemMeta(meta);
		helmet.addUnsafeEnchantment(Enchantment.PROTECTION, 5);
		return damage.StatLore.apply(helmet);
	}

	/** Wearables identified by sight (no lore ID), via the item registry so a rename can't make one match nothing. */
	public static boolean isRacingHelmet(ItemStack item) {
		return RacingHelmet.INSTANCE.matches(item);
	}

	public static boolean isCowHat(ItemStack item) {
		return CowHat.INSTANCE.matches(item);
	}

	public static boolean isSpiritMask(ItemStack item) {
		return SpiritMask.INSTANCE.matches(item);
	}

	public static boolean isBonzoMask(ItemStack item) {
		return BonzoMask.INSTANCE.matches(item);
	}

	/** The SkyBlock Menu (nether star in hotbar slot 8). */
	public static boolean isSkyblockMenu(ItemStack item) {
		return SkyblockMenu.INSTANCE.matches(item);
	}

	/**
	 * {@code id} becomes lore line 0 and must stay there: {@code items.ItemUtils.getID} and {@code Catalog.paletteKey}
	 * read it. {@code damage.StatLore} appends stat rows below from the damage math's own term lists (MAP.md §7b).
	 */
	public static ItemStack getSkyBlockItem(Material material, String name, String id, String sbId) {
        return getSkyBlockItem(material, name, id, nbt -> nbt.putString("id", sbId));
	}

    public static ItemStack getSkyBlockItem(Material material, String name, String id, Consumer<CompoundTag> nbtConsumer) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        assert meta != null;
        meta.itemName(Utils.mm(name));
        meta.setUnbreakable(true);
        meta.displayName(Utils.mm(name));
        List<Component> lore = new ArrayList<>();
        lore.add(Utils.mm(id));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);

        return damage.StatLore.apply(NBT.modify(item, nbtConsumer));
    }
}
