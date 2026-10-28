package death;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import plugin.Utils;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What stops an instakill: Bonzo's Mask, Spirit Mask, Phoenix pet.
 * <p>
 * {@link #tryProc(Player)} is called only by {@link Deaths#kill}, so every kill site gets the same precedence and
 * immunity window. It answers "death cheated", not "item consumed": an already-immune player returns true without
 * spending, so a burst of hits in one window costs one proc.
 * <p>
 * Worn mask beats pet; the masks never compete (both helmets). Phoenix is the fallback, assumed out when no mask
 * is available, so no item and no durability bar. In realistic mode it only procs if actually summoned in
 * {@code /pets} ({@link #phoenixOut}); that changes availability, not order.
 * <p>
 * A proc starts two clocks: immunity (next hit free) and cooldown. One bar segment shows immunity first, then
 * cooldown ({@link #segmentTicks}). Both on absolute {@code Utils.serverTick()}, not {@code Utils.runTick()}, so a
 * cooldown survives a phase change and doesn't jump when the run clock re-anchors. {@link Deaths#reset()} clears them.
 */
public final class CheatDeath {
	private CheatDeath() {}

	/**
	 * Declaration order is the precedence in {@link #pick} and the segment order in {@link #actionBarSuffix}, so
	 * segments never swap places as timers run out (same as Storm's armed-pillar segments).
	 */
	public enum Saver {
		/** Bonzo's Mask, worn: 180s cooldown, 3s immune. */
		BONZO("Bonzo", 3600, 60, "<blue>",
				"<green>Your <blue>Bonzo's Mask</blue> saved your life!"),
		/** Spirit Mask, worn: 30s cooldown, 3s immune. */
		SPIRIT("Spirit", 600, 60, "<dark_purple>",
				"<gold>Second Wind Activated<green>!  Your Spirit Mask saved your life!"),
		/** Phoenix pet, assumed out: 60s cooldown, immune for the death tick only, no item so no durability bar. */
		PHOENIX("Phoenix", 1200, 1, "<gold>",
				"<yellow>Your <red>Phoenix Pet</red> saved you from certain death!");

		/** Short, to sit next to a boss HUD's segments. */
		public final String label;
		public final int cooldownTicks;
		public final int immuneTicks;
		/** Label colour; the countdown is always white. */
		public final String colour;
		/** Hypixel's wording verbatim. Full line per saver, not a template: the three don't share a shape. */
		public final String chatLine;

		Saver(String label, int cooldownTicks, int immuneTicks, String colour, String chatLine) {
			this.label = label;
			this.cooldownTicks = cooldownTicks;
			this.immuneTicks = immuneTicks;
			this.colour = colour;
			this.chatLine = chatLine;
		}

		/** Worn item, so the cooldown shows on its durability bar. */
		boolean isWorn() {
			return this != PHOENIX;
		}
	}

	/** Absolute server tick each saver is ready. Absent = ready. */
	private static final Map<UUID, EnumMap<Saver, Integer>> readyAt = new HashMap<>();

	/**
	 * Saver is kept because the bar counts immunity down on that saver's segment ({@link #segmentTicks}). Only one
	 * window at a time (no proc inside one), so one entry per player.
	 */
	private record Immunity(Saver from, int untilTick) {}

	/** Absent = not immune. */
	private static final Map<UUID, Immunity> immunity = new HashMap<>();

	/** @return true if the death was cheated: a proc landed, or {@code p} is still immune from an earlier one. */
	public static boolean tryProc(Player p) {
		int now = Utils.serverTick();
		// Immune: refused, nothing spent, so a second bolt or same-tick pair costs one proc.
		if(liveImmunity(p, now) != null) return true;

		Saver used = pick(p, now);
		if(used == null) return false;

		readyAt.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Saver.class))
				.put(used, now + used.cooldownTicks);
		immunity.put(p.getUniqueId(), new Immunity(used, now + used.immuneTicks));
		if(used.isWorn()) writeDurability(p, used, used.cooldownTicks);

		// Saved player only, Hypixel words it in second person.
		p.sendMessage(Utils.msg(used.chatLine));
		Utils.playLocalSound(p, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0f, 2.0f);
		Utils.debug(Utils.DebugType.BOSS, p, p.getName() + " cheated death with " + used.label);
		return true;
	}

	/** First available saver in declaration order, or null. */
	private static Saver pick(Player p, int now) {
		ItemStack helmet = p.getInventory().getHelmet();
		for(Saver s : Saver.values()) {
			if(onCooldown(p, s, now)) continue;
			if(s.isWorn() ? !isSaverItem(s, helmet) : !phoenixOut(p)) continue;
			return s;
		}
		return null;
	}

	/**
	 * In classic, yes by assumption: {@code damage/Pet.forPlayer}'s table never returns the Phoenix, so a
	 * mask-less player is taken to have it.
	 * <p>
	 * The live modes ({@link damage.Difficulty#manualPets()}) require it summoned: a Phoenix trades the Golden Dragon's
	 * stats and +250% for this, and giving it free would make that choice meaningless.
	 */
	private static boolean phoenixOut(Player p) {
		if(!damage.Difficulty.manualPets()) return true;
		return pets.Pets.equippedDamagePet(p) == damage.Pet.PHOENIX;
	}

	/** Via {@link #remaining} so one place handles absent entries; a second {@code MIN_VALUE} lookup invites the overflow back. */
	private static boolean onCooldown(Player p, Saver s, int now) {
		return remaining(p, s, now) > 0;
	}

	/**
	 * Asked of the helmet for availability and of every slot when stripping a bar (the item may have moved). Mapping
	 * lives on the items ({@code Wearable.saver}), not here. Phoenix is always false, it has no item.
	 */
	private static boolean isSaverItem(Saver s, ItemStack stack) {
		items.Wearable worn = items.ItemRegistry.wearable(stack);
		return worn != null && worn.saver() == s;
	}

	/** Null if shut or never opened. */
	private static Immunity liveImmunity(Player p, int now) {
		Immunity live = immunity.get(p.getUniqueId());
		return live != null && now < live.untilTick() ? live : null;
	}

	/**
	 * Immunity first, then cooldown, so the number jumps up at the changeover (Bonzo's 60t immunity → ~3540t
	 * cooldown). Only the saver that opened the window; others show cooldowns throughout.
	 * @return 0 when nothing to show
	 */
	private static int segmentTicks(Player p, Saver s, int now) {
		Immunity live = liveImmunity(p, now);
		if(live != null && live.from() == s) return live.untilTick() - now;
		return remaining(p, s, now);
	}

	/**
	 * 0 when ready. No sentinel: {@code Integer.MIN_VALUE - now} overflows positive, which made every unused saver
	 * show a huge remaining (all three on the bar, empty durability on a ready mask).
	 */
	private static int remaining(Player p, Saver s, int now) {
		EnumMap<Saver, Integer> mine = readyAt.get(p.getUniqueId());
		if(mine == null) return 0;
		Integer readyTick = mine.get(s);
		return readyTick == null ? 0 : Math.max(0, readyTick - now);
	}

	// ==================== the durability bar ====================

	/**
	 * Called every tick by {@link Deaths}; drawing is throttled to 20 ticks, the clear isn't: it fires on the exact
	 * expiry tick, else a mask ready at 613 shows part-empty until 620. The entry is then dropped so the
	 * whole-inventory clear runs once.
	 * <p>
	 * {@code max_damage} only renders on unstackable items and heads stack, so the write also sets
	 * {@code max_stack_size} 1 and {@link #clearDurability} removes both (or the mask stays one-per-slot). Damage
	 * clamped one below max so a fresh cooldown never reads as broken.
	 */
	static void refreshDurability(Player p) {
		EnumMap<Saver, Integer> mine = readyAt.get(p.getUniqueId());
		if(mine == null) return;
		int now = Utils.serverTick();
		for(Saver s : Saver.values()) {
			if(!s.isWorn()) continue;
			Integer readyTick = mine.get(s);
			if(readyTick == null) continue; // never used or already cleared
			int left = readyTick - now;
			if(left > 0) {
				if(now % DRAW_INTERVAL_TICKS == 0) writeDurability(p, s, left);
			} else {
				clearDurability(p, s);
				mine.remove(s);
			}
		}
	}

	/** Redraw interval; the clear ignores it ({@link #refreshDurability}). */
	private static final int DRAW_INTERVAL_TICKS = 20;

	private static void writeDurability(Player p, Saver s, int remainingTicks) {
		ItemStack helmet = p.getInventory().getHelmet();
		if(!isSaverItem(s, helmet)) return;
		// Every CraftMetaItem is Damageable; the pattern guards a future meta type that isn't.
		if(!(helmet.getItemMeta() instanceof Damageable m)) return;
		m.setMaxStackSize(1);
		m.setMaxDamage(s.cooldownTicks);
		m.setDamage(Math.min(remainingTicks, s.cooldownTicks - 1));
		helmet.setItemMeta(m);
	}

	/**
	 * Whole inventory, not just the helmet: a mask taken off mid-cooldown would keep a half-empty bar, for the whole
	 * session if {@link #reset} wipes the cooldown first.
	 * <p>
	 * Storage/armour/offhand accessors, not indices 0..40: {@code getContents}' span has changed between versions.
	 */
	private static void clearDurability(Player p, Saver s) {
		PlayerInventory inv = p.getInventory();
		ItemStack[] storage = inv.getStorageContents();
		if(stripBar(s, storage)) inv.setStorageContents(storage);
		ItemStack[] armour = inv.getArmorContents();
		if(stripBar(s, armour)) inv.setArmorContents(armour);
		ItemStack[] offhand = {inv.getItemInOffHand()};
		if(stripBar(s, offhand)) inv.setItemInOffHand(offhand[0]);
	}

	/** @return whether anything changed */
	private static boolean stripBar(Saver s, ItemStack[] items) {
		boolean changed = false;
		for(ItemStack it : items) {
			if(!isSaverItem(s, it)) continue;
			if(!(it.getItemMeta() instanceof Damageable m)) continue;
			if(!m.hasMaxDamage() && !m.hasDamage()) continue;
			m.resetDamage();
			m.setMaxDamage(null);
			m.setMaxStackSize(null);
			it.setItemMeta(m);
			changed = true;
		}
		return changed;
	}

	// ==================== the action bar ====================

	/**
	 * Segments {@code " | <colour>Label <white>Nt"} in declaration order, empty when all ready. Appended by
	 * {@code Utils.sendActionBar}, so every HUD gets them. Empty in classic (no deaths).
	 */
	public static String actionBarSuffix(Player p) {
		return segments(p);
	}

	/** Whether {@link #actionBarSuffix} is non-empty, without building the string ({@code Deaths}' per-tick fallback). */
	public static boolean hasCooldowns(Player p) {
		if(!damage.Difficulty.deathsEnabled()) return false;
		int now = Utils.serverTick();
		for(Saver s : Saver.values()) if(segmentTicks(p, s, now) > 0) return true;
		return false;
	}

	private static String segments(Player p) {
		if(!damage.Difficulty.deathsEnabled()) return "";
		int now = Utils.serverTick();
		StringBuilder sb = new StringBuilder();
		for(Saver s : Saver.values()) {
			int left = segmentTicks(p, s, now);
			if(left <= 0) continue;
			sb.append(Utils.ACTION_BAR_SEPARATOR);
			sb.append(s.colour).append(s.label).append(" <white>").append(left).append("t");
		}
		return sb.toString();
	}

	// ==================== lifecycle ====================

	/** Clears cooldowns, immunity and durability bars. Run start and end. */
	static void reset() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			for(Saver s : Saver.values()) if(s.isWorn()) clearDurability(p, s);
		}
		readyAt.clear();
		immunity.clear();
	}
}
