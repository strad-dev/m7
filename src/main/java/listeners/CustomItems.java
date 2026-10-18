package listeners;

import abilities.ClassAbilities;
import damage.DamageKind;
import damage.DamagePath;
import instructions.Server;
import instructions.bosses.goldor.Goldor;
import instructions.bosses.maxor.Maxor;
import items.*;
import items.Item;
import items.bows.Terminator;
import items.combat.AxeOfTheShredded;
import items.combat.RagnarockAxe;
import net.minecraft.server.MinecraftServer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import plugin.Cooldowns;
import plugin.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ability dispatcher: resolves the held stack to an {@code items.Item}, applies the gates that belong to the
 * CLICK rather than the item, and calls the item's hook. Abilities live under {@code items/}, cooldowns in
 * {@code plugin/Cooldowns}, drop-key abilities in {@code abilities/}. What stays here: spectator gate (vanilla
 * doesn't do it; a ghost is a spectator), Trap room right-click ban, {@link #rightClickGate},
 * {@link #lastRightBlockTick}, {@link #lastLeftClickAbilityTick}, and the melee/mage-beam tests, which depend on
 * the holder's CLASS.
 *
 * <h2>ONE CLICK, TWO DISPATCHES</h2>
 * A real player's right-click reaches {@link #handleCustomItems} twice, from {@code PlayerPacketInterceptor}
 * and from {@code PlayerInteractEvent} (or {@code PlayerInteractAtEntityEvent}), as two main-thread tasks. On a
 * lagging tick the server stops draining its queue and the pair straddles a boundary: a DOUBLE etherwarp. They are
 * at most ONE tick apart (adjacent in one FIFO queue), so {@link #rightClickGate} drops a dispatch within a tick of
 * one from the OTHER source. Its own source is capped at one per tick only: a flat 2-tick cap halved anyone
 * clicking faster than 10 CPS (the Jerry-chine "fires too slowly").
 * <p>
 * The pair can PRINT the same {@code [tick: N]}: {@code Utils.debug} stamps {@code getTickCount()}, bumped in
 * {@code tickServer} AFTER the task drain, while {@code currentTick} is bumped at the top of {@code runServer}.
 * A doubled ability on "one tick" is that, not a bypassed gate.
 */
public class CustomItems implements Listener {

	/** Last right-click dispatch per player: [0] packet (no event), [1] Bukkit event. See class javadoc. */
	private static final Map<UUID, int[]> rightClickGates = new ConcurrentHashMap<>();

	/**
	 * Tick of last RIGHT_CLICK_BLOCK per player. A block right-click sends UseItemOn (BLOCK) then UseItem (AIR);
	 * this drops the trailing AIR so the ability fires once even across a tick boundary.
	 */
	private static final Map<UUID, Integer> lastRightBlockTick = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> lastLeftClickAbilityTick = new ConcurrentHashMap<>();
	private static final Set<UUID> droppingPlayers = new HashSet<>();

	/** Melee's 5-tick cooldown (4 with full Thermodynamic), same rule as the beam: hit on tick 0, denied 1-4, hit on 5 ({@link #meleeAttack}). */
	private static final String MELEE_KEY = "melee";

	/**
	 * Mage beam's own 5-tick cooldown (the left-click guard only caps to one per tick). A rate cap, not an ability
	 * cooldown: never messages, never takes the Mage reduction.
	 */
	private static final int MAGE_BEAM_COOLDOWN_TICKS = 5;
	private static final String MAGE_BEAM_KEY = "mage-beam";

	public static boolean abilityFiredThisTick(Player p) {
		return lastLeftClickAbilityTick.getOrDefault(p.getUniqueId(), -1) == MinecraftServer.currentTick;
	}

	/**
	 * True if this item in this player's hand beams on left-click. The CLASS gate matters: the same weapon in a
	 * Berserk's hand swings. Uses {@link ItemUtils#isMageClass}, not an inline name test: a Berserk named Mage-
	 * something used to beam with a Hyperion. Which weapons beam is {@code Weapon.mageBeams()}; an unregistered
	 * iron/stone sword no longer beams (intended).
	 */
	private static boolean isMageBeamItem(Player p, ItemStack stack) {
		if(stack == null) return false;
		return ItemUtils.isMageClass(p) && ItemRegistry.of(stack) instanceof Weapon weapon && weapon.mageBeams();
	}

	/**
	 * True if this item's left click is an ability, not a swing. Read by the block-break suppression in
	 * {@link #handleCustomItems} (never breaks a block, even on cooldown) and by {@link #meleeAttack}, so a beam or
	 * Terminator volley never also lands a melee hit.
	 */
	private static boolean leftClickIsAbility(Player p, ItemStack stack) {
		Item item = ItemRegistry.of(stack);
		return isMageBeamItem(p, stack) || (item != null && item.suppressesBlockBreak());
	}

	/**
	 * Melee swing on a mob: <b>the plugin's one melee damage path</b>, from {@code PlayerPacketInterceptor}'s
	 * attack-packet branch. Before it, non-Mage swings fell through to vanilla (not a participant, MAP.md §7): a
	 * few hearts against SB/1e6 HP, and nothing on a boss since {@code MiscListener.onWitherLordDamage} cancels
	 * non-plugin hits. {@code Damage.deal} drives the repeated-hit stack, post-kill buff, Fire Aspect / Venomous /
	 * Thunderlord and Cleave.
	 */
	public static void meleeAttack(Player p, org.bukkit.entity.Entity hit) {
		if(p == null || !(hit instanceof LivingEntity target)) return;
		if(p.getGameMode() == GameMode.SPECTATOR) return;
		// Players are invulnerable.
		if(target instanceof Player) return;
		ItemStack held = p.getInventory().getItemInMainHand();
		if(leftClickIsAbility(p, held)) return;
		// Also one hit per swing: the drop-key path also swings, and a duplicate packet would double damage AND the
		// repeated-hit stack.
		if(!Cooldowns.ready(p, MELEE_KEY)) return;
		Cooldowns.start(p, MELEE_KEY, ItemUtils.isThermoSet(p) ? 4 : 5);

		// Only a melee weapon swings. Anything else (bow, held head, vanilla stack) lands a PUNCH: melee formula,
		// held item contributes nothing. Before, a Precise Terminator punch folded in its 310 Damage, Strength and
		// 250 Crit Damage.
		Item item = ItemRegistry.of(held);
		double sbDamage = item instanceof Weapon weapon && weapon.swingsMelee()
				? damage.Damage.melee(p, target, held)
				: damage.Damage.punch(p, target);
		damage.Damage.deal(target, sbDamage, DamageKind.NORMAL, p, DamagePath.MELEE);
	}

	// ===================================== listeners =====================================

	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent e) {
		// A button/lever owns the click: skip entirely so the ability doesn't fire and the event isn't cancelled.
		if(e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
				&& (e.getClickedBlock().getType() == Material.LEVER || Tag.BUTTONS.isTagged(e.getClickedBlock().getType()))) {
			return;
		}
		// Same for a clear-phase secret (chest/essence), and any block vanilla would use (chest, door...).
		if(e.getAction() == Action.RIGHT_CLICK_BLOCK && (instructions.clear.ClearManager.isSecretBlock(e.getClickedBlock())
				|| Utils.usesBlock(e.getPlayer(), e.getClickedBlock()))) {
			return;
		}
		// getClickedBlock() is vanilla's hit result (null for air); block abilities use it as their reach.
		handleCustomItems(e, e.getHand(), e.getItem(), e.getAction(), e.getPlayer(), e.getClickedBlock());
	}

	/**
	 * Cancel entity interaction for custom items (bow drawing etc). The ability fires from
	 * {@code PlayerInteractAtEntityEvent} or the use-item packet (fake players). Gated on the raw lore ID, so any
	 * ID'd item is cancelled even if unregistered; {@code Item.allowsEntityInteract()} opts out (Gyrokinetic Wand,
	 * Dungeonbreaker, the two drawn bows, so they can use item frames).
	 */
	@EventHandler
	public void onPlayerInteractEntity(PlayerInteractEntityEvent e) {
		ItemStack held = e.getPlayer().getInventory().getItemInMainHand();
		if(ItemUtils.getID(held).isEmpty()) return;
		Item item = ItemRegistry.of(held);
		if(item == null || !item.allowsEntityInteract()) e.setCancelled(true);
	}

	// ignoreCancelled: a mechanic that took the click cancelled it first (Mort/Wizard at LOWEST, terminals at LOW).
	@EventHandler(ignoreCancelled = true)
	public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent e) {
		// Item frames and interaction entities own the click.
		if(e.getRightClicked() instanceof ItemFrame || e.getRightClicked() instanceof Interaction) return;
		// So does a pickupable Energy Crystal (Maxor).
		if(e.getRightClicked() instanceof EnderCrystal crystal && !Maxor.INSTANCE.notEnergyCrystal(crystal)) return;
		handleCustomItems(e, e.getHand(), e.getPlayer().getInventory().getItemInMainHand(), Action.RIGHT_CLICK_AIR, e.getPlayer());
	}

	@EventHandler
	public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
		if(e.getEntity() instanceof LivingEntity entity && !entity.getScoreboardTags().contains("TASNoName")) {
			Utils.scheduleTask(() -> Utils.changeName(entity), 1);
		}
		// Ability dispatch and the melee hit for real players come from PlayerPacketInterceptor (every attack
		// packet, even on iframed/dying mobs). Routing EDBEE through handleCustomItems double-fired: the
		// interceptor's runTask landed on T+1, EDBEE on T, bypassing the same-tick dedupe.
		// Vanilla melee damage is cancelled outright since meleeAttack applies every swing at SkyBlock scale
		// (MAP.md §7). Knockback goes too; nothing in the floor needs it.
		if(e.getDamager() instanceof Player) e.setCancelled(true);
	}

	@EventHandler
	public void onBlockBreak(BlockBreakEvent e) {
		Player p = e.getPlayer();
		Item held = ItemRegistry.of(p.getInventory().getItemInMainHand());
		// Superboom TNT has can_break only so the adventure client reports the clicked block
		// (Utils.placeAndBreakAnythingInAdventure); it must never break anything. Backstop for handleCustomItems'
		// cancel, since the fall-through below would remove the block PERMANENTLY.
		if(held instanceof Tool tool && tool.neverBreaks()) {
			e.setCancelled(true);
			return;
		}
		// CREATIVE BYPASS: breaks anything past every protection below, through vanilla (with physics), as a map
		// editor expects. Same bypass as GoldorListener's S3 frames. Below the Superboom check on purpose: that is
		// item behaviour, not a protection.
		if(p.getGameMode() == GameMode.CREATIVE) return;
		// Goldor interactables and Maxor crystal plates: unbreakable with any tool, any phase.
		if(Goldor.INSTANCE.isProtected(e.getBlock()) || Maxor.INSTANCE.isProtected(e.getBlock())) {
			e.setCancelled(true);
			return;
		}
		// Map fixtures (secret chests, Quiz buttons, Wither Essence skulls, Ice Fill ice + polished andesite).
		// Ungated: a Dungeonbreaker break outside a run writes AIR for good and removes the block from every later run.
		if(instructions.clear.ClearManager.isMapFixture(e.getBlock())) {
			e.setCancelled(true);
			return;
		}
		// Once the run starts, every door (all 15 plus frames, Rooms.inDoor) and every ceiling lock. Open during
		// prep, same deal as the out-of-bounds kill: position freely, no shortcuts in the run. MiscListener's key +
		// door-click path is the only way to open a door.
		Block b = e.getBlock();
		if(Server.isRunStarted()
				&& (instructions.clear.Rooms.inDoor(b.getX(), b.getY(), b.getZ())
				|| instructions.clear.Rooms.isCeiling(b.getX(), b.getY(), b.getZ()))) {
			e.setCancelled(true);
			return;
		}
		// Room perimeter walls can't be broken; floor and interior can. Tested on the horizontal perimeter at any
		// Y since room heights vary. Multi-cell rooms (2x2 Museum) protect only their OUTER perimeter. Start /
		// wither / blood doors sit in these walls and stay stonkable pre-run.
		if(instructions.clear.Rooms.isRoomFace(b.getX(), b.getZ())
				&& !Server.inStartDoor(b) && !Server.inWitherDoor(b) && !Server.inBloodDoor(b)) {
			e.setCancelled(true);
			return;
		}
		// Remove it ourselves WITHOUT physics. A vanilla break runs updateNeighbourShapes, which pops carpets,
		// torches, flowers, rails, redstone and cascades nether portals; that path (updateShape -> destroyBlock)
		// fires no BlockPhysicsEvent, so it can only be stopped here.
		e.setCancelled(true);
		// A Tool does its own removal (Dungeonbreaker's is restored after 200 ticks); else a permanent break.
		if(!(held instanceof Tool tool && tool.onBreak(p, b))) b.setType(Material.AIR, false);
	}

	/**
	 * Catch-all so a never-a-block item is never placed. Superboom TNT has can-place-on-anything for adventure,
	 * so any click the ability doesn't consume places it (sneak-click on lever/button/secret, Trap room, off-hand,
	 * ...). {@code BlockPlaceEvent} fires exactly then, so veto here and let the item act. Cancelling keeps the
	 * stack intact. Creative is left alone so building can place real TNT.
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCustomBlockPlace(BlockPlaceEvent e) {
		if(!(ItemRegistry.of(e.getItemInHand()) instanceof Tool tool) || !tool.vetoesPlacement()) return;
		Player p = e.getPlayer();
		GameMode gm = p.getGameMode();
		if(gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return;
		e.setCancelled(true);
		p.updateInventory(); // client predicted the placement; resend the stack so it won't ghost
		// Mirrors the Trap-room ban in handleCustomItems.
		if(instructions.clear.ClearManager.isActive()
				&& instructions.clear.Rooms.roomAt(p.getLocation()) == instructions.clear.Rooms.TRAP) return;
		tool.onPlace(p, e.getBlockAgainst());
	}

	@EventHandler
	public void onPlayerDropItem(PlayerDropItemEvent e) {
		Player p = e.getPlayer();
		droppingPlayers.add(p.getUniqueId());
		Utils.scheduleTask(() -> droppingPlayers.remove(p.getUniqueId()), 1);
		// Drop is an ABILITY key, never a way to lose an item: cancel first, for everyone. Gating it on Archer/Mage
		// used to let other classes throw their kit on the floor. abilities/ClassAbilities decides if one fires.
		// Abilities fire from the interceptor's DROP_ITEM/DROP_ALL_ITEMS path (not rate-limited by vanilla); here we
		// only cancel.
		e.setCancelled(true);
	}

	/** Drop-key entry for a real player, from the packet interceptor. */
	public static void handleDrop(Player p, boolean ultimate) {
		droppingPlayers.add(p.getUniqueId());
		Utils.scheduleTask(() -> droppingPlayers.remove(p.getUniqueId()), 1);
		ClassAbilities.dispatch(p, ultimate);
	}

	/** Drawn bow released. The Terminator is a shortbow and fires from its own poller instead. */
	@EventHandler
	public void onEntityShootBow(EntityShootBowEvent e) {
		if(!(e.getEntity() instanceof Player p)) return;
		ItemStack bow = p.getInventory().getItemInMainHand();
		if(!(ItemRegistry.of(bow) instanceof items.Bow custom) || custom.shortbow()) return;
		if(!(e.getProjectile() instanceof org.bukkit.entity.Arrow primary)) return;
		// Vanilla charge, min(useTicks/20, 1). A drawn bow scales damage by it AND loses the crit term below full
		// draw (MAP.md §1.4).
		custom.onShoot(p, bow, primary, Math.clamp(e.getForce(), 0f, 1f));
	}

	/** A {@code ProjectileItem}'s projectile landed. Matched on its scoreboard tag, since the shooter may have swapped. */
	@EventHandler
	public void onProjectileHit(ProjectileHitEvent e) {
		Projectile projectile = e.getEntity();
		items.ProjectileItem source = ItemRegistry.projectileSource(projectile.getScoreboardTags());
		if(source == null) return;
		source.onProjectileHit(e, projectile.getShooter() instanceof Player p ? p : null);
	}

	// ===================================== dispatch =====================================

	public static void handleCustomItems(Cancellable e, EquipmentSlot hand, ItemStack stack, Action action, Player p) {
		handleCustomItems(e, hand, stack, action, p, null);
	}

	/**
	 * @param clickedBlock block vanilla reported ({@code getClickedBlock()} or the action packet's pos); null for
	 *                     air, entity or fake-player clicks. Block abilities (Superboom) use it instead of their
	 *                     own ray trace, so range and target match vanilla's.
	 */
	public static void handleCustomItems(Cancellable e, EquipmentSlot hand, ItemStack stack, Action action, Player p, Block clickedBlock) {
		if(p.getGameMode() == GameMode.SPECTATOR) return; // spectators never fire item abilities
		if(!Objects.equals(hand, EquipmentSlot.HAND)) return;

		boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
		boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
		Item item = ItemRegistry.of(stack);

		// Trap room bans right-click abilities except ender pearls and the Dungeonbreaker.
		if(isRightClick && instructions.clear.ClearManager.isActive()
				&& instructions.clear.Rooms.roomAt(p.getLocation()) == instructions.clear.Rooms.TRAP) {
			boolean allowed = stack != null && (stack.getType() == Material.ENDER_PEARL
					|| (item != null && item.usableInTrapRoom()));
			if(!allowed) return;
		}
		if(action == Action.LEFT_CLICK_AIR && droppingPlayers.contains(p.getUniqueId())) return;

		// Only a stack with an item ID is custom here. Armour, heads, SkyBlock Menu and Pitchin' Rod have none.
		if(stack == null || !ItemUtils.getID(stack).startsWith("skyblock/")) return;

		int tick = MinecraftServer.currentTick;
		boolean fired = false;

		// Cancel right-clicks early to stop vanilla use (bow draw, TNT place, Infinileap's pearl), unless the
		// item's right-click isn't an ability.
		if(e != null && isRightClick && (item == null || item.cancelsInteract())) e.setCancelled(true);

		AbilityItem ability = item instanceof AbilityItem a ? a : null;

		if(isLeftClick) {
			if(tick > lastLeftClickAbilityTick.getOrDefault(p.getUniqueId(), -1)) {
				if(isMageBeamItem(p, stack)) {
					if(Cooldowns.ready(p, MAGE_BEAM_KEY)) {
						ItemUtils.mageBeam(p);
						Cooldowns.start(p, MAGE_BEAM_KEY, MAGE_BEAM_COOLDOWN_TICKS);
						fired = true;
					}
				} else if(ability != null && ability.hasLeftClick()) {
					fired = fire(p, ability, new Cast(p, stack, clickedBlock, action, tick), false);
				}
				if(fired) lastLeftClickAbilityTick.put(p.getUniqueId(), tick);
			}
			// No block breaking for these, fired or not.
			if(e != null && leftClickIsAbility(p, stack)) e.setCancelled(true);
		}

		if(isRightClick) {
			// A block right-click sends BLOCK then AIR. Usually same tick and the rate gate collapses them, but
			// warmup lag after a restart can split them and fire twice. Drop an AIR trailing a BLOCK by <= 1 tick;
			// standalone air-clicks and fake-player spam have no recent BLOCK.
			if(action == Action.RIGHT_CLICK_BLOCK) {
				lastRightBlockTick.put(p.getUniqueId(), tick);
			} else {
				int blockTick = lastRightBlockTick.getOrDefault(p.getUniqueId(), Integer.MIN_VALUE);
				if(tick == blockTick || tick == blockTick + 1) return;
			}
			if(ability != null && ability.hasRightClick() && rightClickGate(p, e != null, tick)) {
				fired = fire(p, ability, new Cast(p, stack, clickedBlock, action, tick), true);
			}
		}

		// Cancel left-click events only if an ability actually fired
		if(e != null && fired) e.setCancelled(true);
	}

	/** One per tick per source, and none within a tick of the other source's (the same click seen twice). */
	private static boolean rightClickGate(Player p, boolean fromEvent, int tick) {
		int[] last = rightClickGates.computeIfAbsent(p.getUniqueId(), k -> new int[]{Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2});
		int self = fromEvent ? 1 : 0;
		if(tick <= last[self] || tick - last[1 - self] <= 1) return false;
		last[self] = tick;
		return true;
	}

	/**
	 * Run one ability hook. <b>The one place a cooldown is checked, reported and spent.</b>
	 *
	 * @return whether it fired; the caller cancels the event on this
	 */
	private static boolean fire(Player p, AbilityItem ability, Cast cast, boolean rightClick) {
		int cooldown = ability.cooldownTicks();
		if(cooldown > 0) {
			int remaining = Cooldowns.remaining(p, ability.cooldownKey());
			if(remaining > 0) {
				if(ability.announcesCooldown()) ItemUtils.sendCooldownMessage(p, remaining);
				return false;
			}
		}
		boolean fired = rightClick ? ability.onRightClick(cast) : ability.onLeftClick(cast);
		if(fired && cooldown > 0) {
			Cooldowns.start(p, ability.cooldownKey(),
					ability.mageReduced() ? ItemUtils.effectiveCooldown(p, cooldown) : cooldown);
		}
		return fired;
	}

	// ===================================== run resets =====================================

	/**
	 * Clear per-run combat state (repeated-hit stack, post-kill/combo windows, damage history, target debuffs,
	 * procs, floating damage numbers). Called at every run start.
	 */
	public static void resetBerserkDamage() {
		damage.CombatState.reset();
		damage.TargetDebuffs.reset();
		damage.Procs.reset();
		damage.DamageNumbers.reset();
		damage.Stats.invalidateAll();
	}

	/** Clear Terminator volley clocks. Called at every practice run start. */
	public static void resetTerminatorCooldowns() {
		Terminator.reset();
	}

	/** Reset every ability cooldown, plus the two abilities with extra state. Called at run start. */
	public static void resetAbilityCooldowns() {
		Cooldowns.clearAll();
		RagnarockAxe.reset(); // also kills any wind-up chain still in flight
		AxeOfTheShredded.reset();
		items.tools.Dungeonbreaker.reset();
	}

	/** Class (drop-key) cooldowns only. Called on boss start ({@code WitherLord.start}); item cooldowns carry over. */
	public static void resetClassAbilityCooldowns() {
		Cooldowns.clearPrefix(abilities.ClassAbility.COOLDOWN_PREFIX);
	}
}
