package damage;

import net.minecraft.server.MinecraftServer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The player stat aggregate (MAP.md §2's {@code aggregate(player, path)}).
 * <pre>
 * aggregate(player, path) = stats(weapon) + stats(helmet) + stats(chest) + stats(legs) + stats(boots)
 *                         + Equipment(class, path)      // ASSUMED, never items   (§1.11)
 *                         + Power(powerId, magicalPower) + Tunings(magicalPower)  (§1.12)
 *                         + Profile(player)                                       (§1.13)
 *                         + ClassBonuses(player, soloOnClass)                     (§1.14)
 * then per stat: x (1 + sum additive%) x product(multiplicative)                   (§1.13)
 * </pre>
 * Cache key includes PATH, not just player (§7): a Mage's equipment and Accessory Power are path-dependent, so beam
 * and cast stats differ.
 * <p>
 * Invalidated on EQUIPMENT change too (a helmet swap is thousands of Int, §1.10), and self-expires since Legion, the
 * Berserk stack/combo and the Ragnarock buff move with no inventory event.
 */
public final class Stats {
	private Stats() {}

	/** Short enough for Legion / Ragnarock to show promptly, long enough that a Terminator volley doesn't recompute per arrow. */
	private static final int CACHE_TICKS = 5;

	/**
	 * {@code weapon} null = UNARMED aggregate (punch), minus the main hand. In the key, not an uncached call, since
	 * someone hitting with a bow punches every click. The DEF, not "the main hand", so a Duplex or Archer arrow
	 * stamped after a swap still reads the bow that fired it.
	 */
	private record Key(UUID player, DamagePath path, ItemDef weapon) {}

	private record Cached(int tick, StatBlock stats) {}

	private static final Map<Key, Cached> CACHE = new HashMap<>();

	/** Cheap; the safe response to anything that might have changed a stat. */
	public static void invalidateAll() {
		CACHE.clear();
	}

	/** Every path. */
	public static void invalidate(Player p) {
		if(p == null) return;
		CACHE.keySet().removeIf(k -> k.player().equals(p.getUniqueId()));
	}

	public static StatBlock of(Player p, DamagePath path) {
		if(p == null) return StatBlock.EMPTY;
		return of(p, path, Items.of(p.getInventory().getItemInMainHand()));
	}

	/** Aggregate with an EMPTY main hand, for a punch. Everything else still counts, as in SkyBlock. */
	public static StatBlock unarmed(Player p) {
		return of(p, DamagePath.MELEE, (ItemDef) null);
	}

	/** With {@code weapon} in place of the main hand: a bow's arrows stamped ticks after the shot. */
	public static StatBlock of(Player p, DamagePath path, ItemDef weapon) {
		if(p == null) return StatBlock.EMPTY;
		Key key = new Key(p.getUniqueId(), path, weapon);
		Cached hit = CACHE.get(key);
		int now = MinecraftServer.currentTick;
		if(hit != null && now - hit.tick() < CACHE_TICKS) return hit.stats();
		StatBlock computed = compute(p, path, weapon);
		CACHE.put(key, new Cached(now, computed));
		return computed;
	}

	/** Itemised by source for {@code /verbose super} and {@code /eq}. Uncached; only built when someone looks. */
	public static Map<String, StatBlock> breakdown(Player p, DamagePath path) {
		if(p == null) return new LinkedHashMap<>();
		return breakdown(p, path, Items.of(p.getInventory().getItemInMainHand()));
	}

	private static Map<String, StatBlock> breakdown(Player p, DamagePath path, ItemDef weapon) {
		Map<String, StatBlock> out = new LinkedHashMap<>();
		if(p == null) return out;
		DungeonClass clazz = DungeonClass.of(p);
		boolean solo = DungeonClass.isSoloOnClass(p);
		Pet pet = Pet.forPlayer(p, path);
		PlayerInventory inv = p.getInventory();

		if(weapon != null) put(out, "weapon", weapon.stats(pet));
		put(out, "helmet", itemStats(inv.getHelmet(), pet));
		put(out, "chestplate", itemStats(inv.getChestplate(), pet));
		put(out, "leggings", itemStats(inv.getLeggings(), pet));
		put(out, "boots", itemStats(inv.getBoots(), pet));
		put(out, "equipment", Equipment.forClass(clazz, path));
		put(out, "power + tunings", Powers.forClass(clazz, path));
		put(out, "profile", Profile.base());
		put(out, "pet (" + pet + ")", pet.ownStats());
		put(out, "class bonus", ClassBonuses.stats(clazz, solo));
		put(out, "ragnarock buff", StatBlock.of(Stat.STRENGTH, ragnarockStrength(p, pet)));
		return out;
	}

	private static void put(Map<String, StatBlock> out, String label, StatBlock stats) {
		if(stats != null && !stats.isEmpty()) out.put(label, stats);
	}

	private static StatBlock compute(Player p, DamagePath path, ItemDef weapon) {
		StatBlock sum = StatBlock.EMPTY;
		for(StatBlock part : breakdown(p, path, weapon).values()) sum = sum.plus(part);

		// Stat stage, per WHOLE stat: the full sum gets multiplied, not just the profile half.
		StatBlock staged = StatBlock.EMPTY;
		Pet pet = Pet.forPlayer(p, path);
		for(Stat stat : Stat.values()) {
			double value = sum.get(stat)
					* (1.0 + Profile.additivePercent(p, stat, pet) / 100.0)
					* Profile.multiplicative(stat);
			// Blessings LAST as (stat + flat) x percent: flat inside their percent, outside every other multiplier,
			// Hypixel's order (§1.13). Earlier would expose the flat to additivePercent and the Master Skull.
			value = (value + Blessings.flat(stat)) * Blessings.multiplier(stat);
			staged = staged.plus(stat, value);
		}
		return staged;
	}

	/** Empty if not a registered stat item. */
	private static StatBlock itemStats(ItemStack item, Pet pet) {
		ItemDef def = Items.of(item);
		return def == null ? StatBlock.EMPTY : def.stats(pet);
	}

	/**
	 * Ragnarock: +150% of the axe's own Strength for 10s (§1.7). The plan's "+939" is what this evaluates to, never a
	 * constant. The full 696 counts, Chimera's +370 included (an ITEM source). Read off the axe's definition, not the
	 * hand, since the buff must keep applying after swapping to a hitting weapon.
	 */
	public static double ragnarockStrength(Player p, Pet pet) {
		if(p == null || !RagnarockBuff.isActive(p)) return 0;
		ItemDef axe = Items.byName("Withered Ragnarοck Axe");
		if(axe == null) return 0;
		return 1.5 * axe.stats(pet).get(Stat.STRENGTH);
	}
}
