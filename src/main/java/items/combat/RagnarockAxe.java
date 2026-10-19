package items.combat;

import damage.Rarity;
import damage.ReforgeId;
import items.*;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import plugin.Utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>3s wind-up</b> (three lever clicks), then the buff. The axe must stay in the main hand the whole time; that's
 * the cost. Cooldown is NOT refunded on a dropped wind-up: spent at cast time, as on Hypixel.
 * <p>
 * Base name has a Greek omicron (U+03BF), on purpose, so it stops colliding with a client mod. Wrong spelling drops
 * it to the palette tail and freezes saved copies. {@code damage/Items}, {@code Stats.ragnarockStrength},
 * {@code Catalog.PALETTE_ORDER} and both {@code ItemRefresh.RENAMED} tables use it. Grep it, never type it.
 */
public final class RagnarockAxe implements Weapon, AbilityItem {
	public static final RagnarockAxe INSTANCE = new RagnarockAxe();

	private RagnarockAxe() {}

	@Override
	public String loreId() {
		return "skyblock/combat/rag";
	}

	@Override
	public Material material() {
		return Material.GOLDEN_AXE;
	}

	@Override
	public String baseName() {
		return "Ragnarοck Axe";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.RARE;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.WITHERED;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "RAGNAROCK_AXE");
	}

	@Override
	public boolean mageBeams() {
		return true;
	}

	@Override
	public boolean hasRightClick() {
		return true;
	}

	@Override
	public int cooldownTicks() {
		return 400; // 20s
	}

	/** The action-bar timer counts these down, so never write either figure out a second time. */
	public static final int WINDUP_TICKS = 60;
	public static final int BUFF_TICKS = 200;

	@Override
	public boolean onRightClick(Cast cast) {
		rag(cast.player());
		return true;
	}

	// Tick the buff expires, per player. Lands at +60, lasts 200 (10s); a re-cast pushes it out so the earlier
	// cast's removal no-ops.
	private static final Map<UUID, Integer> ragBuffExpiry = new ConcurrentHashMap<>();

	// Tick the in-flight cast started, per player. ragWindup polls the main hand every tick and drops the cast
	// once the axe leaves it. Also the chain's identity: a re-cast overwrites it, so an older chain stops.
	private static final Map<UUID, Integer> ragCastStart = new ConcurrentHashMap<>();

	/** See {@link #ragWindup}. */
	public static void rag(Player p) {
		Utils.playLocalSound(p, Sound.BLOCK_LEVER_CLICK, 1.0F, 2.0F);
		ragCastStart.put(p.getUniqueId(), MinecraftServer.currentTick);
		ragWindup(p, MinecraftServer.currentTick, 0);
	}

	/**
	 * One tick of the wind-up: lever clicks at +20/+40, buff at +60. Re-checks EVERY tick that the axe is still in
	 * the main hand, so swapping mid-wind-up can't land the buff. No cooldown refund.
	 */
	private static void ragWindup(Player p, int castStart, int elapsed) {
		Utils.scheduleTask(() -> {
			UUID uid = p.getUniqueId();
			// Superseded, cancelled or reset: this chain is dead.

			if(ragCastStart.getOrDefault(uid, Integer.MIN_VALUE) != castStart) return;
			if(!p.isOnline() || !ItemUtils.getID(p.getInventory().getItemInMainHand()).equals("skyblock/combat/rag")) {
				ragCastStart.remove(uid);
				Utils.debug(Utils.DebugType.SERVER, "Rag cast cancelled (axe left the hand) for " + p.getName());
				return;
			}
			int tick = elapsed + 1;
			if(tick == 20 || tick == 40) {
				Utils.playLocalSound(p, Sound.BLOCK_LEVER_CLICK, 1.0F, 2.0F);
			}
			if(tick < WINDUP_TICKS) {
				ragWindup(p, castStart, tick);
				return;
			}
			ragCastStart.remove(uid);
			Utils.playLocalSound(p, Sound.ENTITY_WOLF_WHINE, 1.0F, 1.5F);
			p.addScoreboardTag(damage.RagnarockBuff.TAG);
			// 200 ticks after THIS application; a later cast overwrites it.
			ragBuffExpiry.put(uid, MinecraftServer.currentTick + BUFF_TICKS);
			// +150% of the AXE'S Strength as a bonus stat (MAP.md §1.7), not the old Strength potion or flat
			// 220->250 swap. The stat layer reads the tag; damage/Stats.ragnarockStrength derives it from the axe's
			// terms, so it tracks a retune. Keeps applying after the axe leaves the hand: cast, then swap to a
			// hitting weapon is the point of the item.
			damage.Stats.invalidate(p);
			Utils.debug(Utils.DebugType.SERVER, "Rag Buff applied to " + p.getName());
			// Only at the latest expiry: after a re-cast this earlier removal no-ops and the later one clears it.
			Utils.scheduleTask(() -> {
				if(MinecraftServer.currentTick >= ragBuffExpiry.getOrDefault(uid, 0)) {
					p.removeScoreboardTag(damage.RagnarockBuff.TAG);
					ragBuffExpiry.remove(uid);
					damage.Stats.invalidate(p);
					Utils.debug(Utils.DebugType.SERVER, "Rag Buff expired for " + p.getName());
				}
			}, BUFF_TICKS);
		}, 1);
	}

	/**
	 * Ticks left on the live window: <b>wind-up</b>, then <b>buff</b>, 0 if neither. One number since the wind-up
	 * runs straight into the buff (60, then 200).
	 * <p>
	 * Same maps and clock the ability steers by, so it can't disagree with the axe. Both maps clear as their window
	 * ends, so an absent key means not running.
	 */
	public static int ticksLeft(Player p) {
		if(p == null) return 0;
		UUID uid = p.getUniqueId();
		Integer castStart = ragCastStart.get(uid);
		if(castStart != null) return Math.max(0, WINDUP_TICKS - (MinecraftServer.currentTick - castStart));
		Integer expiry = ragBuffExpiry.get(uid);
		if(expiry != null) return Math.max(0, expiry - MinecraftServer.currentTick);
		return 0;
	}

	/**
	 * {@code " | <red>Rag Axe <white>Nt"}, or "" when nothing is running. Appended by {@code Utils.sendActionBar},
	 * so every HUD gets it; {@code death/Deaths}' fallback draws it where no boss HUD owns the bar.
	 */
	public static String actionBarSegment(Player p) {
		int left = ticksLeft(p);
		return left <= 0 ? "" : Utils.ACTION_BAR_SEPARATOR + "<red>Rag Axe <white>" + left + "t";
	}

	/** Run reset. Clearing the cast stamp is what kills a live wind-up chain. The tag goes too: cancelAllScheduled
	 *  kills its expiry task, which left the buff on for good. */
	public static void reset() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(p.removeScoreboardTag(damage.RagnarockBuff.TAG)) damage.Stats.invalidate(p);
		}
		ragBuffExpiry.clear();
		ragCastStart.clear();
	}

}
