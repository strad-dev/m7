package damage;

import instructions.clear.Room;
import instructions.clear.Rooms;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Wither;

import java.util.EnumSet;
import java.util.Set;

/**
 * HP, defense, mob types and inherent boss resistance for every target (MAP.md §5).
 * <p>
 * Difficulty lives in defense, not HP: Withered Dragon has less HP than Necron but 2500 defense makes it 84% as
 * tough per point. Every boss and mini-boss also takes a flat x0.1; without it every hit is 10x too strong.
 * <p>
 * Covers the whole floor, not just bosses: Watcher adds, Crypt Undeads, Princes, Wither Miners, Angry
 * Archaeologists, Shadow Assassins. Only non-combat entities stay out (Mort/Wizard NPCs, clear-phase props).
 */
public final class MobStats {
	private MobStats() {}

	/**
	 * @param id             readable name, for debug
	 * @param displayHealth  SkyBlock HP as shown under Paul (800M, 1.4B). Internal health derives from it
	 *                       ({@link MobStat#internalHealth}), so it's not a hand-kept constant.
	 * @param defense        real SkyBlock defense, BEFORE Lethality and Last Breath
	 * @param bossResistance carries the inherent boss / mini-boss x0.1
	 * @param elite          Elite's +30% applies (Bosses and Mini-Bosses)
	 * @param types          all matching buffs stack
	 */
	public record MobStat(String id, double displayHealth, double defense, boolean bossResistance, boolean elite,
			Set<MobType> types) {

		/**
		 * SkyBlock HP at {@code /1e6}, doubled under Derpy. The ONE place the mayor HP multiplier applies: bosses via
		 * {@code WitherLord.maxHealth}, everything else via {@link #apply}. Display follows: {@code Utils.changeName}
		 * rewrites from LIVE health and boss spawn names format off this.
		 */
		public double internalHealth() {
			return displayHealth * Mayor.healthMultiplier() / Scale.SB_PER_MC_HP;
		}

		/** HP scaled by room depth (§5, +10% per tier). */
		MobStat atDepth(int depth) {
			double m = depthMultiplier(depth);
			return m == 1.0 ? this : new MobStat(id, displayHealth * m, defense, bossResistance, elite, types);
		}

		/** Scaled by the depth of the room at a spawn location. */
		public MobStat atDepthOf(org.bukkit.Location location) {
			Room room = Rooms.roomAt(location);
			return atDepth(room == null ? 1 : Math.max(1, room.level));
		}
	}

	private static Set<MobType> types(MobType... t) {
		return t.length == 0 ? EnumSet.noneOf(MobType.class) : EnumSet.copyOf(java.util.List.of(t));
	}

	// ===================== the four Wither Lords and the Withered Dragons =====================
	// §7 calls Wither Lords "Arcane, Wither" but lists Undead Ruler +39% for them, so they must be Undead too (no
	// Wither Ruler exists). The three types reproduce §7: Smite +50%, Arcane Ruler +39%, Undead Ruler +39%,
	// Elite +30%, Hyperion x1.5.
	public static final MobStat MAXOR = new MobStat("Maxor", 800_000_000d, 1000, true, true,
			types(MobType.ARCANE, MobType.WITHER, MobType.UNDEAD));
	public static final MobStat STORM = new MobStat("Storm", 1_000_000_000d, 1200, true, true,
			types(MobType.ARCANE, MobType.WITHER, MobType.UNDEAD));
	public static final MobStat GOLDOR = new MobStat("Goldor", 1_200_000_000d, 1800, true, true,
			types(MobType.ARCANE, MobType.WITHER, MobType.UNDEAD));
	public static final MobStat NECRON = new MobStat("Necron", 1_400_000_000d, 2100, true, true,
			types(MobType.ARCANE, MobType.WITHER, MobType.UNDEAD));
	/**
	 * Arcane + Ender + Airborne, NOT Wither: no Hyperion x1.5, so Dark Claymore wins here. Three Rulers, Ender
	 * Slayer, Gravity, Draconic Artifact and Ender Dragon pet stack to ~+490% additive the Withers never see.
	 */
	public static final MobStat WITHERED_DRAGON = new MobStat("Withered Dragon", 1_000_000_000d, 2500, true, true,
			types(MobType.ARCANE, MobType.ENDER, MobType.AIRBORNE));

	// ===================== the rest of the floor =====================
	// Wiki has no type for Sadan's Giants or Bonzo, so no Ruler or type enchant.
	public static final MobStat DIAMANTE_GIANT = new MobStat("Diamante Giant", 400_000_000d, 0, true, true, types());
	public static final MobStat BONZO = new MobStat("Bonzo", 300_000_000d, 0, true, true, types());
	/** Watcher's other adds. No published types; Undead is presumed. */
	public static final MobStat WATCHER_UNDEAD = new MobStat("Watcher Undead", 6_000_000d, 2000, true, false,
			types(MobType.UNDEAD));
	/** Undead + Subterranean: Smite +50, Undead Ruler +39 AND Subterranean Ruler +39. */
	public static final MobStat CRYPT_UNDEAD = new MobStat("Crypt Undead", 9_000_000d, 0, false, false,
			types(MobType.UNDEAD, MobType.SUBTERRANEAN));
	/**
	 * Wizard room mobs, live modes only (not the crypt ones above). 14.3M = 13M at depth II, flat: one group stands
	 * on Wizard's z -137 seam, where depth reads I. Zero defense.
	 */
	public static final MobStat CRYPT_LURKER = new MobStat("Crypt Lurker", 14_300_000d, 0, false, false,
			types(MobType.UNDEAD, MobType.SUBTERRANEAN));
	public static final MobStat PRINCE = new MobStat("Prince", 1_000_000d, 0, false, false,
			types(MobType.UNDEAD, MobType.SUBTERRANEAN));
	/**
	 * 300M, confirmed. Wither + Undead: Hyperion x1.5 on top of Smite and Undead Ruler. No Skeletal Ruler, that's
	 * Normal-mode only. Zero defense, no x0.1 (regular mob). Also covers Wither Guard, Husk and Apostle (see {@code of}).
	 * <p>
	 * Used to carry 1200 defense guessed off "the wiki's F7 Master Mode row" while §5 said [TBD]. That's a /13
	 * divisor: it threw away 92% of every hit on the most-hit trash mob and made RCM read ~1/10 of real damage.
	 * <b>Don't reintroduce a defense figure without measuring it.</b>
	 */
	public static final MobStat WITHER_MINER = new MobStat("Wither Miner", 300_000_000d, 0, false, false,
			types(MobType.WITHER, MobType.UNDEAD));
	/**
	 * Humanoid + Subterranean Mini-Boss, so Elite. HP scales with depth. 1200 defense, confirmed: the one non-boss
	 * with any. Wither trash and both Shadow Assassins were guessed off this row and are really 0.
	 */
	public static final MobStat ANGRY_ARCHAEOLOGIST = new MobStat("Angry Archaeologist", 12_000_000d, 1200, true, true,
			types(MobType.HUMANOID, MobType.SUBTERRANEAN));
	/**
	 * BOSS-FIGHT Shadow Assassins (Storm's four pad corners): flat 145M, no depth since the arena isn't on the grid.
	 * Humanoid + Arcane: those two Rulers, no Smite. Zero defense, measured; keeps the x0.1. Used to carry 1200
	 * guessed off the Archaeologist just for being a mini-boss, a /13 divisor on a mob burned down under a timer.
	 */
	public static final MobStat SHADOW_ASSASSIN = new MobStat("Shadow Assassin", 145_000_000d, 0, true, true,
			types(MobType.HUMANOID, MobType.ARCANE));
	/** CLEAR-phase Yellow room Shadow Assassin, a different mob: 140M base, depth II so 154M. Zero defense, x0.1. */
	public static final MobStat YELLOW_SHADOW_ASSASSIN = new MobStat("Shadow Assassin", 140_000_000d, 0, true, true,
			types(MobType.HUMANOID, MobType.ARCANE));

	/**
	 * {@code 1 + 0.10 x (depth - 1)}: depth I x1.00, depth V x1.40 (§5). {@code Room.level} IS the depth (Deathmite
	 * at level 2 gives the observed 13.2M). Unset level counts as I, since 0 would give a NEGATIVE buff.
	 */
	public static double depthMultiplier(int depth) {
		return 1.0 + 0.10 * (Math.max(1, depth) - 1);
	}

	/**
	 * Depth-scaled where it applies, or null if unmodelled. Bosses match on scoreboard tag, the rest on custom name
	 * (their only identity). {@code contains} because {@code Utils.changeName} rewrites the health suffix every hit.
	 */
	public static MobStat of(LivingEntity entity) {
		if(entity == null) return null;
		// Memoised per tick: each hit asks four times, and Cleave + a Terminator volley is dozens of lookups a tick.
		int now = MinecraftServer.currentTick;
		if(now != lookupTick) {
			lookupTick = now;
			lookupCache.clear();
		}
		java.util.UUID id = entity.getUniqueId();
		if(lookupCache.containsKey(id)) return lookupCache.get(id);
		MobStat resolved = resolve(entity);
		lookupCache.put(id, resolved);
		return resolved;
	}

	private static int lookupTick = -1;
	private static final java.util.Map<java.util.UUID, MobStat> lookupCache = new java.util.HashMap<>();

	private static MobStat resolve(LivingEntity entity) {
		Set<String> tags = entity.getScoreboardTags();
		if(tags.contains("TASMaxor")) return MAXOR;
		if(tags.contains("TASStorm")) return STORM;
		if(tags.contains("TASGoldor")) return GOLDOR;
		if(tags.contains("TASNecron")) return NECRON;
		if(tags.contains("WitherKingDragon")) return WITHERED_DRAGON;

		String name = displayName(entity);
		if(tags.contains("WatcherMob")) {
			if(name.contains("Diamante") || name.contains("Giant")) return DIAMANTE_GIANT;
			if(name.contains("Bonzo")) return BONZO;
			return WATCHER_UNDEAD;
		}
		if(tags.contains("SecretPrince")) return PRINCE.atDepth(depthAt(entity));
		if(tags.contains(instructions.clear.ClearManager.TAG_CRYPT)) return CRYPT_UNDEAD.atDepth(depthAt(entity));
		if(tags.contains(instructions.clear.ClearManager.TAG_WIZARD_MOB)) return CRYPT_LURKER;
		if(name.contains("Angry Archaeologist")) return ANGRY_ARCHAEOLOGIST.atDepth(depthAt(entity));
		if(name.contains("Shadow Assassin")) {
			// Two mobs share the name: Yellow-room ClearMiniboss (140M, depth-scaled) and Storm's pad-corner ones
			// (flat 145M, arena isn't on the grid).
			return tags.contains("ClearMiniboss")
					? YELLOW_SHADOW_ASSASSIN.atDepth(depthAt(entity))
					: SHADOW_ASSASSIN;
		}
		// Maxor/Storm wither trash. §5 leaves Guard, Husk and Apostle HP [TBD], so they share the Miner's block.
		if(name.contains("Wither Miner") || name.contains("Wither Guard") || name.contains("Wither Husk")
				|| name.contains("Apostle")) {
			return WITHER_MINER;
		}
		return null;
	}

	/** Plain custom name, else type name. */
	private static String displayName(LivingEntity entity) {
		return entity.customName() == null ? entity.getName() : plugin.Utils.plain(entity.customName());
	}

	/**
	 * Real HP on spawn, vanilla armour cleared. Mobs used to carry hand-picked HP and negative {@code minecraft:armor}
	 * to undo vanilla's reduction. Now HP is {@code SB/1e6} and {@link Damage} applies defense, so armor stays 0 (§5).
	 */
	public static void apply(LivingEntity mob, MobStat stat) {
		if(mob == null || stat == null) return;
		var maxHealth = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
		if(maxHealth != null) {
			maxHealth.setBaseValue(stat.internalHealth());
			mob.setHealth(stat.internalHealth());
		}
		var armor = mob.getAttribute(org.bukkit.attribute.Attribute.ARMOR);
		if(armor != null) armor.setBaseValue(0);
		var toughness = mob.getAttribute(org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS);
		if(toughness != null) toughness.setBaseValue(0);
		// Zero i-frames (§7), so every hit lands in full.
		mob.setMaximumNoDamageTicks(0);
		mob.setNoDamageTicks(0);
	}

	/** 1 outside a mapped room. */
	private static int depthAt(Entity entity) {
		Room room = Rooms.roomAt(entity.getLocation());
		return room == null ? 1 : Math.max(1, room.level);
	}

	/** Before Lethality and Last Breath. Unmodelled targets have none. */
	public static double defenseOf(LivingEntity entity) {
		MobStat stat = of(entity);
		return stat == null ? 0 : stat.defense();
	}

	/** x0.1 for bosses and mini-bosses, else 1.0. */
	public static double resistanceOf(LivingEntity entity) {
		MobStat stat = of(entity);
		return stat != null && stat.bossResistance() ? Scale.BOSS_RESISTANCE : 1.0;
	}

	/** Empty if unmodelled, so no type buff matches. */
	public static Set<MobType> typesOf(LivingEntity entity) {
		MobStat stat = of(entity);
		return stat == null ? java.util.Set.of() : stat.types();
	}

	/** Elite's +30% (Bosses and Mini-Bosses). */
	public static boolean isElite(LivingEntity entity) {
		MobStat stat = of(entity);
		return stat != null && stat.elite();
	}

	// ===================== Wither King phase detection =====================
	// Cached per tick: the beam's range tier asks every shot and it's a world entity scan.

	private static int wkCheckedTick = -1;
	private static boolean wkActive = false;

	/**
	 * PHASE check, not location: {@code LavaJump.isInBossArena} is one box that already contains the WK arena. The
	 * {@code TASWitherKing} tag only exists while the boss is alive, the more precise signal (§7).
	 */
	public static boolean witherKingPhaseActive() {
		int now = MinecraftServer.currentTick;
		if(now == wkCheckedTick) return wkActive;
		wkCheckedTick = now;
		wkActive = false;
		for(World w : Bukkit.getWorlds()) {
			for(Wither wither : w.getEntitiesByClass(Wither.class)) {
				if(wither.getScoreboardTags().contains("TASWitherKing")) {
					wkActive = true;
					return true;
				}
			}
		}
		return false;
	}
}
