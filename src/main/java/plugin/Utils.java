package plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.advancements.predicates.BlockPredicate; // 26.2: moved from advancements.criterion
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.component.TooltipDisplay;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;


public class Utils {
	/**
	 * Pending one-shot tasks from {@link #scheduleTask}, for {@link #cancelAllScheduled()}. Repeating tasks
	 * (boss tickers, aggro, spectator sync) use runTaskTimer and aren't tracked.
	 * <p>
	 * MUST self-prune: {@code CraftScheduler.cancelTask} walks the whole queue and enqueues a cancel task per call,
	 * so N cancels is O(N * queue). This used to be append-only (CustomItems schedules one per damage event,
	 * hundreds/second) and cancelling ~100k dead entries froze the main thread past the 60s watchdog on the next
	 * {@code /m7practice}. Main-thread only.
	 */
	private static final Map<Integer, org.bukkit.scheduler.BukkitTask> scheduledTasks = new LinkedHashMap<>();

	/** Bumped by {@link #cancelAllScheduled()}. A task no-ops if its captured generation is stale, so choreography
	 *  can't bleed into the next run even if its cancel didn't land. */
	private static int scheduleGeneration = 0;

	// Adventure name/lore helpers (26.2: ItemMeta's String name/lore methods are deprecated)
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
	private static final MiniMessage MM = MiniMessage.miniMessage();

	/** Item name/lore line from MiniMessage, default item italic suppressed. */
	public static Component mm(String s) {
		return MM.deserialize("<!italic>" + s);
	}

	/** Chat / entity custom name from MiniMessage, no forced italic. */
	public static Component msg(String s) {
		return MM.deserialize(s);
	}

	/** Use Placeholder.unparsed(...) for untrusted input (player names, chat) so it can't inject tags. */
	public static Component msg(String template, TagResolver... resolvers) {
		return MM.deserialize(template, resolvers);
	}

	/** Unstyled text of a component. */

	public static String plain(Component c) {
		return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
	}

	/** Legacy §-string of a component, for String-only Bukkit APIs (boss bar titles). */
	public static String legacyString(Component c) {
		return c == null ? "" : LEGACY.serialize(c);
	}

	public static String mmString(Component c) {
		return c == null ? "" : MM.serialize(c);
	}

	/** MiniMessage to legacy §-string, for String-only Bukkit APIs without deprecated ChatColor. */
	public static String mmLegacy(String miniMessage) {
		return LEGACY.serialize(MM.deserialize(miniMessage));
	}

	/** Replaces {@code meta.setDisplayName(s)}: use {@code meta.displayName(Utils.nameComponent(s))}. */
	public static Component nameComponent(String legacy) {
		// Vanilla italicises custom names; legacy setDisplayName didn't. Non-italic unless there's an explicit §o.
		return LEGACY.deserialize(legacy).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
	}

	/** Replaces {@code meta.setLore(list)}: use {@code meta.lore(Utils.loreComponents(list))}. */
	public static List<Component> loreComponents(List<String> legacy) {
		List<Component> out = new ArrayList<>(legacy.size());
		for(String s : legacy) out.add(LEGACY.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
		return out;
	}

	/** Replaces {@code meta.getDisplayName()}; "" if none. */
	public static String displayName(ItemMeta meta) {
		Component c = meta.displayName();
		return c == null ? "" : LEGACY.serialize(c);
	}

	/** Replaces {@code meta.getLore()}; empty list if none. */
	public static List<String> lore(ItemMeta meta) {
		List<Component> l = meta.lore();
		if(l == null) return new ArrayList<>();
		List<String> out = new ArrayList<>(l.size());
		for(Component c : l) out.add(LEGACY.serialize(c));
		return out;
	}

	/** Unstyled first lore line (the custom-item ID), so lore formatting can't break the ID lookup.
	 *  Replaces getLore().getFirst(). */
	public static String firstLorePlain(ItemMeta meta) {
		List<Component> l = meta.lore();
		return l == null || l.isEmpty() ? "" : PlainTextComponentSerializer.plainText().serialize(l.getFirst());
	}


	/** Tracked runTaskLater; delay in ticks. */
	public static void scheduleTask(Runnable task, long delay) {
		if(!M7.getInstance().isEnabled()) return;
		final int generation = scheduleGeneration;
		final int[] id = new int[1]; // set below; runTaskLater defers at least a tick, so it's set by then
		org.bukkit.scheduler.BukkitTask handle = Bukkit.getScheduler().runTaskLater(M7.getInstance(), () -> {
			scheduledTasks.remove(id[0]);
			if(generation != scheduleGeneration) return; // a run started or ended since this was queued

			task.run();
		}, delay);
		id[0] = handle.getTaskId();
		scheduledTasks.put(id[0], handle);
	}

	/**
	 * Cancel every pending {@link #scheduleTask} task. Called at run start so the last run's queued choreography
	 * can't fire into it. The generation bump is what guarantees that; the cancels just keep the queue small.
	 */
	public static void cancelAllScheduled() {
		scheduleGeneration++;
		List<org.bukkit.scheduler.BukkitTask> live = new ArrayList<>(scheduledTasks.values());
		scheduledTasks.clear(); // before cancelling, so a task firing mid-loop can't mutate the map
		for(org.bukkit.scheduler.BukkitTask t : live) {
			if(t != null && !t.isCancelled()) t.cancel();
		}
	}

	public static void setSpeed(Player p, int speed) {
		var instance = p.getAttribute(Attribute.MOVEMENT_SPEED);
		NamespacedKey key = new NamespacedKey(M7.getInstance(), "speed");
		instance.removeModifier(new AttributeModifier(key, 0, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
		double modifier = (speed - 100) / 100.0;
		instance.addModifier(new AttributeModifier(key, modifier, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
	}

	/** 50% speed debuff, separate modifier that composes with {@link #setSpeed}. For carrying a Wither King relic
	 *  without a Cow Hat. */
	public static void setRelicDebuff(Player p, boolean active) {
		var instance = p.getAttribute(Attribute.MOVEMENT_SPEED);
		if(instance == null) return;
		NamespacedKey key = new NamespacedKey(M7.getInstance(), "relic_debuff");
		instance.removeModifier(new AttributeModifier(key, 0, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
		if(active) {
			instance.addModifier(new AttributeModifier(key, -0.5, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
		}
	}

	// Spigot-mojang compile name is detectEquipmentUpdatesPublic; Paper runtime has only detectEquipmentUpdates.
	// Resolve whichever exists at class-load time.
	private static final java.lang.reflect.Method DETECT_EQUIPMENT_UPDATES = resolveDetectEquipmentUpdates();

	private static java.lang.reflect.Method resolveDetectEquipmentUpdates() {
		for(String name : new String[]{"detectEquipmentUpdates", "detectEquipmentUpdatesPublic"}) {
			try {
				java.lang.reflect.Method m = net.minecraft.world.entity.LivingEntity.class.getMethod(name);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {
			}
		}
		throw new IllegalStateException("LivingEntity.detectEquipmentUpdates[Public] not found on runtime");
	}

	/**
	 * Change which hotbar slot (0-8) the player is “holding”,
	 * and broadcast that slot change.
	 *
	 * @param p           The player whose hotbar slot is to be changed.
	 * @param hotbarIndex The index of the new hotbar slot (0-8).
	 */
	public static void setHotbarSlot(Player p, int hotbarIndex) {
		if(!(p instanceof CraftPlayer cp)) {
			return;
		}
		ServerPlayer npc = cp.getHandle();

		// Update the NMS held-slot index
		npc.getInventory().setSelectedSlot(hotbarIndex);

		// Apply item attribute modifiers (Dungeonbreaker's BLOCK_BREAK_SPEED) this frame, not next entity tick.
		try {
			DETECT_EQUIPMENT_UPDATES.invoke(npc);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}

		// Tell that player's client "your held slot is now hotbarIndex"
		ClientboundSetHeldSlotPacket heldPkt = new ClientboundSetHeldSlotPacket(hotbarIndex);
		cp.getHandle().connection.send(heldPkt);

		// Broadcast the new main-hand item to all viewers
		ClientboundSetEquipmentPacket equipPkt = new ClientboundSetEquipmentPacket(npc.getId(), List.of(Pair.of(EquipmentSlot.MAINHAND, npc.getInventory().getSelectedItem())));
		broadcastPacket(equipPkt);
	}

	public static void broadcastPacket(Packet<?> pkt) {
		for(Player p : Bukkit.getOnlinePlayers()) {
			((CraftPlayer) p).getHandle().connection.send(pkt);
		}
	}

	/**
	 * Action bar to every player, spectators included. Shared home of the boss
	 * tick-timer HUDs (Storm pad/crush, Maxor laser/stun, Necron interludes): there's one action-bar slot, so
	 * every per-tick writer has to agree who it writes to. {@link Component#empty()} clears it.
	 */
	public static void broadcastActionBar(Component bar) {
		for(Player p : Bukkit.getOnlinePlayers()) {
			sendActionBar(p, bar);
		}
	}

	/** Server tick each player's bar was last written, so a low-priority writer can see the slot is taken this
	 *  tick. See {@link #actionBarOwnedThisTick}. */
	private static final Map<UUID, Integer> actionBarTick = new HashMap<>();

	/** Every per-player segment leads with this; {@link #sendActionBar} drops the first when the segments are the
	 *  whole bar, so a provider never needs to know what's in front of it. */
	public static final String ACTION_BAR_SEPARATOR = " <dark_gray>| ";

	/**
	 * <b>The one action-bar send.</b> Every HUD goes through here: {@link #broadcastActionBar} for the boss bars,
	 * directly for the per-player ones (Storm pad colour, clear HUD).
	 * <p>
	 * Appends the per-player segments (pet in Realistic, Rag Axe, cheat-death cooldowns): one slot, so the only way
	 * two writers coexist is for one to own the append. Stamps the tick so {@code Deaths}' fallback won't overwrite
	 * a live HUD; boss bars draw at tick start, ahead of it. A cleared bar still shows the cooldowns, so clearing a
	 * boss HUD doesn't blank a timer the player is reading.
	 */
	public static void sendActionBar(Player p, Component bar) {
		// Fixed order so segments don't swap places as timers run out. Pet leads since it never runs out.
		String extra = pets.Pets.actionBarSegment(p) + items.combat.RagnarockAxe.actionBarSegment(p)
				+ death.CheatDeath.actionBarSuffix(p);
		// Segments are the whole bar when nothing else owns it (Goldor has no HUD); a leading "| " looks cut off.
		if(!extra.isEmpty() && plain(bar).isEmpty()) extra = extra.substring(ACTION_BAR_SEPARATOR.length());
		p.sendActionBar(extra.isEmpty() ? bar : bar.append(msg(extra)));
		actionBarTick.put(p.getUniqueId(), nmsServer().getTickCount());
	}

	/** True if something already wrote {@code p}'s action bar on the current tick. */
	public static boolean actionBarOwnedThisTick(Player p) {
		return actionBarTick.getOrDefault(p.getUniqueId(), Integer.MIN_VALUE) == serverTick();
	}

	/**
	 * Absolute server tick: the clock {@link #runTick()} and {@link #phaseTick()} use, un-anchored. Use it for
	 * anything that outlives a phase (cheat-death cooldowns, revival in {@code death/}) instead of
	 * {@code MinecraftServer.currentTick}, so there's one answer to "what tick is it".
	 */
	public static int serverTick() {
		return nmsServer().getTickCount();
	}

	/** Same instance as the deprecated {@code MinecraftServer.getServer()}. */
	private static MinecraftServer nmsServer() {
		return ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer();
	}

	/**
	 * Runs a command (no leading slash) with no output. Vanilla spams ops with "[Server: ...]";
	 * {@code withSuppressedOutput()} short-circuits that, which the logAdminCommands gamerule doesn't reliably do.
	 * Use instead of {@code Bukkit.dispatchCommand(console, ...)} for our own setblock/fill/clone/tag/kill.
	 */
	public static void runCommand(String command) {
		MinecraftServer server = nmsServer();
		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, command);
	}

	public static ItemStack createLeatherArmor(Material material, Color color, String name) {
		ItemStack item = new ItemStack(material);
		LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
		assert meta != null;
		meta.setColor(color);
		meta.setUnbreakable(true);
		meta.displayName(Utils.nameComponent(name));
		meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
		item.setItemMeta(meta);
		item.addUnsafeEnchantment(Enchantment.PROTECTION, 5);
		// No lore ID, so StatLore keeps lore line 0 blank and appends below it; that keeps Catalog.paletteKey's
		// third component "" (§7b).
		return damage.StatLore.apply(item);
	}

	/**
	 * Copy that can break ANY block in adventure: {@code can_break} with one empty predicate, which matches
	 * everything (how SkyBlock's Dungeonbreaker does it). Apply LAST, after any setItemMeta; it mutates the NMS copy.
	 */
	public static ItemStack breakAnyBlockInAdventure(ItemStack item) {
		net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(item);
		nms.set(DataComponents.CAN_BREAK, new AdventureModePredicate(List.of(BlockPredicate.Builder.block().build())));
		// Empty predicate names no block, so the client shows "Can Break: Unknown". Hide it.
		nms.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.CAN_BREAK, true));
		return CraftItemStack.asBukkitCopy(nms);
	}

	/** Copy placeable on Stone Bricks in adventure (practice default) via {@code can_place_on}. Apply LAST, after
	 *  any setItemMeta; it mutates the NMS copy. */
	public static ItemStack placeOnStoneBricksInAdventure(ItemStack item) {
		net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(item);
		// Built-in registry as HolderGetter: RegistryAccess.lookupOrThrow's signature differs between the
		// Spigot-mojang compile target and Paper runtime (NoSuchMethodError).
		BlockPredicate stoneBricks = BlockPredicate.Builder.block()
				.of(net.minecraft.core.registries.BuiltInRegistries.BLOCK, net.minecraft.world.level.block.Blocks.STONE_BRICKS)
				.build();
		nms.set(DataComponents.CAN_PLACE_ON, new AdventureModePredicate(List.of(stoneBricks)));
		return CraftItemStack.asBukkitCopy(nms);
	}

	/** Copy placeable on anvils and cauldrons in adventure, so the client outlines a relic's altar. Apply LAST, after
	 *  any setItemMeta. */
	public static ItemStack placeOnAltarsInAdventure(ItemStack item) {
		net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(item);
		BlockPredicate altars = BlockPredicate.Builder.block()
				.of(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
						net.minecraft.world.level.block.Blocks.ANVIL,
						net.minecraft.world.level.block.Blocks.CHIPPED_ANVIL,
						net.minecraft.world.level.block.Blocks.DAMAGED_ANVIL,
						net.minecraft.world.level.block.Blocks.CAULDRON,
						net.minecraft.world.level.block.Blocks.WATER_CAULDRON,
						net.minecraft.world.level.block.Blocks.LAVA_CAULDRON,
						net.minecraft.world.level.block.Blocks.POWDER_SNOW_CAULDRON)
				.build();
		nms.set(DataComponents.CAN_PLACE_ON, new AdventureModePredicate(List.of(altars)));
		return CraftItemStack.asBukkitCopy(nms);
	}

	/** Copy placeable on ANY block in adventure, same empty-predicate trick as {@link #breakAnyBlockInAdventure}.
	 *  Apply LAST, after any setItemMeta. */
	public static ItemStack placeOnAnythingInAdventure(ItemStack item) {
		net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(item);
		nms.set(DataComponents.CAN_PLACE_ON, new AdventureModePredicate(List.of(BlockPredicate.Builder.block().build())));
		// Empty predicate names no block, so the client shows "Can be placed on: Unknown". Hide it.
		nms.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.CAN_PLACE_ON, true));
		return CraftItemStack.asBukkitCopy(nms);
	}

	/**
	 * {@link #placeOnAnythingInAdventure} + {@link #breakAnyBlockInAdventure} in one stamp. Must be one call: each
	 * writes its own {@code TOOLTIP_DISPLAY}, so chaining clobbers the first and un-hides its "Unknown" line.
	 * <p>
	 * can_break is NOT for breaking (Superboom's interact is always cancelled). It makes the CLIENT report which
	 * block was left-clicked: in the 26.2 client {@code MultiPlayerGameMode.startDestroyBlock} skips
	 * {@code ServerboundPlayerActionPacket} when {@code Player.blockActionRestricted}, which in adventure is true
	 * unless can_break matches. Without it a left-click is only a swing and we'd have to ray-trace.
	 * Apply LAST, after any setItemMeta.
	 */
	public static ItemStack placeAndBreakAnythingInAdventure(ItemStack item) {
		ItemStack copy = withoutBlockBreakSpeed(item);
		net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(copy);
		nms.set(DataComponents.CAN_PLACE_ON, new AdventureModePredicate(List.of(BlockPredicate.Builder.block().build())));
		nms.set(DataComponents.CAN_BREAK, new AdventureModePredicate(List.of(BlockPredicate.Builder.block().build())));
		// Hide both "Unknown" lines, and the attributes too or the break-speed modifier shows "-1024 Block Break Speed".
		nms.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT
				.withHidden(DataComponents.CAN_PLACE_ON, true)
				.withHidden(DataComponents.CAN_BREAK, true)
				.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true));
		return CraftItemStack.asBukkitCopy(nms);
	}

	/**
	 * Copy that can't break blocks: -1024 MAINHAND BLOCK_BREAK_SPEED cancels {@code JoinListener}'s 1024 base
	 * (destroy speed is multiplied by it), so held it's 0. Used by {@link #placeAndBreakAnythingInAdventure} for
	 * every can_break item except Dungeonbreaker, which adds +1024 instead. The left-click packet is gated on the
	 * predicate, not speed, so Superboom keeps its targeting and the break is impossible client-side too, rather
	 * than refused after the fact by {@code CustomItems.onBlockBreak}.
	 * <p>
	 * NOTE: any explicit attribute modifier drops the item's DEFAULT ones (vanilla replaces the component), so a
	 * weapon routed through this loses its damage.
	 */
	public static ItemStack withoutBlockBreakSpeed(ItemStack item) {
		ItemStack copy = item.clone();
		ItemMeta meta = copy.getItemMeta();
		if(meta == null) return copy;
		meta.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(
				new NamespacedKey(M7.getInstance(), "no_break_speed"), -1024,
				AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
		copy.setItemMeta(meta);
		return copy;
	}

	@SuppressWarnings("unused")
	public static void playGlobalSound(Sound s) {
		Bukkit.getOnlinePlayers().forEach(player -> player.playSound(player, s, 1.0F, 1.0F));
	}

	public static void playGlobalSound(Sound s, float volume, float pitch) {
		Bukkit.getOnlinePlayers().forEach(player -> player.playSound(player, s, volume, pitch));
	}

	public static void playLocalSound(Player p, Sound s) {
		p.playSound(p, s, 1.0f, 1.0f);
	}

	public static void playLocalSound(Player p, Sound s, float volume, float pitch) {
		p.playSound(p, s, volume, pitch);
	}

	/** Same, by namespaced key ("minecraft:entity.wither.hurt"). */
	public static void playLocalSound(Player p, String s, float volume, float pitch) {
		p.playSound(p.getLocation(), s, volume, pitch);
	}

	// LivingEntity#getHurtSound is protected, so reflected lazily. invoke dispatches virtually, so subclass
	// overrides return their own sounds.
	private static java.lang.reflect.Method getHurtSoundMethod;

	/** Namespaced key of an entity's hurt sound, or null if unresolvable. */
	@Nullable
	public static String getHurtSoundKey(LivingEntity entity) {
		try {
			net.minecraft.world.entity.LivingEntity nmsEntity = ((CraftLivingEntity) entity).getHandle();
			if(getHurtSoundMethod == null) {
				getHurtSoundMethod = net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("getHurtSound", net.minecraft.world.damagesource.DamageSource.class);
				getHurtSoundMethod.setAccessible(true);
			}
			Object soundEvent = getHurtSoundMethod.invoke(nmsEntity, nmsEntity.damageSources().genericKill());
			if(soundEvent == null) return null;
			// SoundEvent's ResourceLocation accessor is location() on record builds, getLocation() on older ones
			for(String name : new String[]{"location", "getLocation"}) {
				try {
					return soundEvent.getClass().getMethod(name).invoke(soundEvent).toString();
				} catch(NoSuchMethodException ignored) {
				}
			}
			return null;
		} catch(ReflectiveOperationException e) {
			return null;
		}
	}

	public enum SecretType {
		CHEST, BLESSING_CHEST, ITEM, BAT, ESSENCE
	}

	public static void playSecretFoundSound(Player p, SecretType type) {
		Sound sound;
		switch(type) {
			case CHEST, BLESSING_CHEST -> sound = Sound.BLOCK_CHEST_OPEN;
			case ITEM -> sound = Sound.ENTITY_ITEM_PICKUP;
			case BAT -> sound = Sound.ENTITY_BAT_DEATH;
			case ESSENCE -> sound = Sound.BLOCK_NOTE_BLOCK_PLING;
			default -> {
				Bukkit.broadcast(msg("<red>Error: Invalid secret type " + type));
				return;
			}
		}
		playLocalSound(p, sound, 2.0f, type == SecretType.ESSENCE ? 2.0f : 1.0f);
		if(type == SecretType.BLESSING_CHEST || type == SecretType.ESSENCE) {
			playRewardSequence(p);
		}
	}

	public static void playRewardSequence(Player p) {
		playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 0.793685f);
		scheduleTask(() -> playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 0.891f), 5);
		scheduleTask(() -> playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1f), 10);
		scheduleTask(() -> playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.12284f), 15);
		scheduleTask(() -> playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.18945f), 20);
	}

	/** Nearest player to l. */
	public static @Nullable Player getNearestPlayer(Location l) {
		ArrayList<Player> playersInWorld = new ArrayList<>(l.getWorld().getEntitiesByClass(Player.class));
		if(playersInWorld.isEmpty()) {
			return null;
		}
		for(int i = 0; i < playersInWorld.size(); i++) {
			Player p = playersInWorld.get(i);
			if(isSpectator(p) && playersInWorld.size() > 1) {
				playersInWorld.remove(i);
				i--;
			}
		}
		playersInWorld.sort(Comparator.comparingDouble(o -> o.getLocation().distanceSquared(l)));
		return playersInWorld.getFirst();
	}

	/**
	 * True if p is WATCHING, not running: vanilla spectator (m7's idle state; the network parks non-party players
	 * there), or tagged {@link #WATCHING_TAG} by another plugin to watch from adventure (mc-tas-lang's /spectate).
	 * <p>
	 * <b>Every player-driven dungeon mechanic must check this; vanilla won't.</b> Bukkit still fires interact events
	 * for spectators, and clicks on a {@code MenuProvider} block or an entity hitbox aren't pre-cancelled, so a
	 * spectator could take a secret, solve a device, or pocket a relic or Energy Crystal. The last two are permanent:
	 * the item ends up where the party can't reach it. Our packet interceptor bypasses vanilla anyway. Check at the
	 * chokepoint ({@code Maxor.pickUp}, {@code WitherKing.pickUpRelic}) so future callers inherit it.
	 */
	public static boolean isSpectator(Player p) {
		return p == null || p.getGameMode() == GameMode.SPECTATOR || p.getScoreboardTags().contains(WATCHING_TAG);
	}

	/**
	 * A projectile hitting a watcher, which {@code MiscListener.onProjectileHitWatcher} cancels so it flies on. Hit
	 * listeners that act on a cancelled event too check this first.
	 */
	public static boolean hitsWatcher(org.bukkit.event.entity.ProjectileHitEvent e) {
		return e.getHitEntity() instanceof Player p && isSpectator(p);
	}

	/** Scoreboard tag: this player watches and is not in the run, whatever their game mode. */
	public static final String WATCHING_TAG = "m7_watching";

	public enum DebugType {
		CLIENT, SERVER, BOSS, ERROR
	}

	/**
	 * Ascending, each a superset of the last. OFF: silent. TIMER: tick-timer lines. ON: plus [Client]/[Server]/[Game]
	 * debug and movement audit. SUPER: plus packet coordinates and movement residuals.
	 */
	public enum VerboseLevel {OFF, TIMER, ON, SUPER}

	private static VerboseLevel verboseLevel = VerboseLevel.TIMER;

	public static VerboseLevel getVerboseLevel() {
		return verboseLevel;
	}

	public static void setVerboseLevel(VerboseLevel level) {
		verboseLevel = level;
	}

	/** ON and SUPER. */
	public static boolean isVerbose() {
		return verboseLevel.ordinal() >= VerboseLevel.ON.ordinal();
	}

	/** TIMER and above. */
	public static boolean showTimers() {
		return verboseLevel.ordinal() >= VerboseLevel.TIMER.ordinal();
	}

	public static boolean isSuperVerbose() {
		return verboseLevel == VerboseLevel.SUPER;
	}

	/** Server tick the current phase began; basis for the {@code [tick: N]} prefix. */
	private static int phaseStartTick = 0;
	/** Server tick the live overall-run timer was anchored at. */
	private static int runStartTick = 0;
	/** False from {@link #markRunStart()} until the next {@link #markPhaseStart()} anchors the run timer. */
	private static boolean runStarted = false;

	/** Resets the {@code [tick: N]} counter. The first call after {@link #markRunStart()} also anchors the run timer. */
	public static void markPhaseStart() {
		int now = nmsServer().getTickCount();
		phaseStartTick = now;
		if(!runStarted) {
			runStartTick = now;
			runStarted = true;
		}
	}

	/** Arms a fresh run timer; the next {@link #markPhaseStart()} anchors it. /m7practice's "Overall" timer is live,
	 *  not the hardcoded per-phase offset. */
	public static void markRunStart() {
		runStarted = false;
	}

	public static int runTick() {
		return nmsServer().getTickCount() - runStartTick;
	}

	public static int phaseTick() {
		return nmsServer().getTickCount() - phaseStartTick;
	}

	/** Tick-timer line (MiniMessage), TIMER level and above. */
	public static void timer(String message) {
		if(showTimers()) Bukkit.broadcast(msg(message));
	}

	public static void debug(DebugType type, String message) {
		// Unparsed so a stray '<' in the payload can't break parsing or inject tags.
		TagResolver m = Placeholder.unparsed("m", message);
		// ERROR is a bug, not routine output: always fires, always tick-stamped.
		if(type == DebugType.ERROR) {
			Bukkit.broadcast(msg("<gray>[tick: " + phaseTick() + "] <red>[Error] <m>", m));
			return;
		}
		if(!isVerbose()) return;
		String prefix = isSuperVerbose() ? "<gray>[tick: " + phaseTick() + "] " : "";
		switch(type) {
			case CLIENT -> Bukkit.broadcast(msg(prefix + "<dark_aqua>[Client] <m>", m));
			case SERVER -> Bukkit.broadcast(msg(prefix + "<green>[Server] <m>", m));
			case BOSS -> Bukkit.broadcast(msg(prefix + "<light_purple>[Game] <m>", m));
			case ERROR -> { /* handled above */ }
		}
	}

	/**
	 * Nameplate / boss bar HP, e.g. {@code "1.4B"}. Internal HP is SkyBlock / {@link damage.Scale#SB_PER_MC_HP}, so
	 * display is internal x 1M. The old per-boss display table and x2 fudge (Maxor at 300 showing 800M) are gone, MAP.md §8.
	 */
	public static String formatHealthM(LivingEntity entity) {
		// Dying withers always show "1".
		if(entity.getScoreboardTags().contains("TASDying")) return "1";
		double hp = entity.getHealth() + entity.getAbsorptionAmount();
		// 1M..100M non-withers get a tenth (14.3M); withers stay whole millions.
		if(!(entity instanceof org.bukkit.entity.Wither) && hp >= 1 && hp < 100) {
			int tenths = (int) Math.round(hp * 10);
			if(tenths < 1000) return tenths % 10 == 0 ? (tenths / 10) + "M" : (tenths / 10) + "." + (tenths % 10) + "M";
		}
		return formatHealthM(hp);
	}

	/** One internal health point is 1M SkyBlock HP. */
	public static String formatHealthM(double rawHealth) {
		return formatDisplayM(rawHealth);
	}

	private static String formatDisplayM(double displayM) {
		if(displayM >= 1000) {
			int tenths = (int) Math.round(displayM / 100.0); // round to nearest 0.1B
			if(tenths % 10 == 0) return (tenths / 10) + "B";
			return (tenths / 10) + "." + (tenths % 10) + "B";
		}
		return (int) Math.round(displayM) + "M"; // round to nearest 1M
	}

	// hurtEntity moved to damage/Damage.deal, the one damage path (MAP.md §7). It replaced three paths
	// (hurtServer(genericKill), setHealth for dragons, wither.damage() for arrows) with different i-frame, armor,
	// event and aggro behaviour. The worn-head multiplier (Cow Hat x0.70, masks x0.85) is deleted: helmet-slot
	// exclusivity in the stat layer models it (§1.10, §8).

	public static void changeName(LivingEntity entity) {
		if(!(entity instanceof Player)) {
			double health = entity.getHealth() + entity.getAbsorptionAmount();
			boolean exempt = entity.getScoreboardTags().stream().anyMatch(t -> t.equals("TASWitherKing") || t.equals("TASWatcher"));
			String healthStr = exempt ? String.valueOf(health) : formatHealthM(entity);
			// <!bold>: legacy §e/§c reset bold, MiniMessage colour tags don't.
			String healthTag = "<!bold><yellow>" + healthStr + "<red>❤";
			Component current = entity.customName();
			if(current == null) {
				entity.customName(msg(entity.getName() + " " + healthTag));
				return;
			}
			// Replace the last token (health suffix), keeping the coloured base name.
			String[] parts = MM.serialize(current).split(" ");
			parts[parts.length - 1] = healthTag;
			entity.customName(msg(String.join(" ", parts)));
		}
	}

	public enum BlessingType {
		LIFE, POWER, STONE, WISDOM, TIME
	}

	public static String round(double value, int places) {
		BigDecimal bd = new BigDecimal(Double.toString(value));
		bd = bd.setScale(places, RoundingMode.HALF_UP);
		return bd.toPlainString();
	}

	/** {@link #round(double, int)} with thousands separators ({@code "3,729.6"}), for lore and {@code /eq}. Grouped by
	 *  hand, not DecimalFormat, so it keeps round's BigDecimal half-up rule. */
	public static String roundCommas(double value, int places) {
		String s = round(value, places);
		boolean negative = s.startsWith("-");
		if(negative) s = s.substring(1);
		int dot = s.indexOf('.');
		String whole = dot < 0 ? s : s.substring(0, dot);
		String frac = dot < 0 ? "" : s.substring(dot);
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < whole.length(); i++) {
			if(i > 0 && (whole.length() - i) % 3 == 0) sb.append(',');
			sb.append(whole.charAt(i));
		}
		return (negative ? "-" : "") + sb + frac;
	}

	/**
	 * "DUNGEON BUFF!" in Hypixel's two-line shape: who found what, then what it granted. The stat line comes from
	 * {@code damage/Blessings}: it used to hard-code 7.26 / 3.63% / 10.89 / 5.445% (the maxed figures), which lied
	 * under Derpy once the mayor became a setting.
	 */
	public static void broadcastBlessing(Player p, BlessingType type, int level) {
		String romanLevel;
		switch(level) {
			case 1 -> romanLevel = "I";
			case 2 -> romanLevel = "II";
			case 5 -> romanLevel = "V";
			default -> {
				Bukkit.broadcast(msg("<red>Error: Invalid level " + level));
				return;
			}
		}
		// Blessing of Time is Trivia's reward and only drops at V.
		if(type == BlessingType.TIME && level != 5) {
			Bukkit.broadcast(msg("<red>Error: Blessing of Time can only be level 5"));
			return;
		}
		String name = switch(type) {
			case LIFE -> "Life";
			case POWER -> "Power";
			case STONE -> "Stone";
			case WISDOM -> "Wisdom";
			case TIME -> "Time";
		};
		Bukkit.broadcast(msg("<gold><bold>DUNGEON BUFF!<reset><gold> " + p.getName()
				+ "<white> found a <light_purple>Blessing of " + name + " " + romanLevel + "<white>!"));
		Bukkit.broadcast(msg(damage.Blessings.describe(type, level)));
	}

	private static final java.util.Map<Material, Boolean> USES_BLOCK = new java.util.EnumMap<>(Material.class);

	/**
	 * Whether right-clicking {@code b} uses the block (chest, door, lever...) rather than the held item, so an item
	 * ability stands down. Vanilla's rule: the block's class overrides {@code useWithoutItem} and the player isn't
	 * sneaking. Fences (leads only), redstone dust and iron doors/trapdoors override it but almost never act on a
	 * click, so they don't count; nor do anvils, which never open here ({@code MiscListener.onAnvilInteract}).
	 */
	public static boolean usesBlock(Player p, org.bukkit.block.Block b) {
		if(b == null || p.isSneaking()) return false;
		Material m = b.getType();
		if(m == Material.IRON_DOOR || m == Material.IRON_TRAPDOOR || Tag.ANVIL.isTagged(m)) return false;
		return USES_BLOCK.computeIfAbsent(m, k ->
				overridesUseWithoutItem(org.bukkit.craftbukkit.block.CraftBlockType.bukkitToMinecraft(k).getClass()));
	}

	private static boolean overridesUseWithoutItem(Class<?> c) {
		for(; c != null && c != net.minecraft.world.level.block.state.BlockBehaviour.class; c = c.getSuperclass()) {
			if(c == net.minecraft.world.level.block.FenceBlock.class
					|| c == net.minecraft.world.level.block.RedStoneWireBlock.class) return false;
			try {
				c.getDeclaredMethod("useWithoutItem", net.minecraft.world.level.block.state.BlockState.class,
						net.minecraft.world.level.Level.class, net.minecraft.core.BlockPos.class,
						net.minecraft.world.entity.player.Player.class, net.minecraft.world.phys.BlockHitResult.class);
				return true;
			} catch(NoSuchMethodException ignored) {
			}
		}
		return false;
	}
}
