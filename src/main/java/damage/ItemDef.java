package damage;

import java.util.*;

/**
 * One item's stat definition from independent terms, not totals (MAP.md §2.4). The four axes are separate fields
 * so each can gain a variant alone:
 * <ol>
 *   <li><b>Base stats</b> - {@link #terms}, authored once per item.</li>
 *   <li><b>Reforge</b> - id only; values from {@link Reforges} at {@code (reforge, category, rarity)}.</li>
 *   <li><b>Gemstones</b> - a slot LIST, never a scalar (1 Sapphire + 1 Onyx vs 2 Sapphire), via {@link Gemstones}.</li>
 *   <li><b>Upgrades</b> - {@link Upgrade} ids, WHICH not their values.</li>
 * </ol>
 * One base serves every reforge variant: Heroic and Withered Hyperion are one base with two reforges and gem sets.
 */
public final class ItemDef {
	private final String displayName;
	private final String loreId;
	private final ItemCategory category;
	private final Rarity baseRarity;
	private final boolean recombobulated;
	private final boolean dungeonItem;
	private final List<StatTerm> terms;
	private final Set<Upgrade> upgrades;
	private final ReforgeId reforge;
	private final List<Gemstones.Slot> gemSlots;
	private final boolean chimera;
	private final boolean swarm;
	private final double selfMultiplier;
	private final boolean shortbow;
	private final Ability ability;

	private ItemDef(Builder b) {
		this.displayName = b.displayName;
		this.loreId = b.loreId;
		this.category = b.category;
		this.baseRarity = b.baseRarity;
		this.recombobulated = b.recombobulated;
		this.dungeonItem = b.dungeonItem;
		this.terms = List.copyOf(b.terms);
		this.upgrades = b.upgrades.isEmpty() ? EnumSet.noneOf(Upgrade.class) : EnumSet.copyOf(b.upgrades);
		this.reforge = b.reforge;
		this.gemSlots = List.copyOf(b.gemSlots);
		this.chimera = b.chimera;
		this.swarm = b.swarm;
		this.selfMultiplier = b.selfMultiplier;
		this.shortbow = b.shortbow;
		this.ability = b.ability;
	}

	/** Right-click ability's base damage and per-ability Int scalar (§7). */
	public record Ability(double baseDamage, double intelligenceScaling) {}

	public String displayName() {
		return displayName;
	}

	public String loreId() {
		return loreId;
	}

	public ItemCategory category() {
		return category;
	}

	/** EFFECTIVE rarity, what reforge and gem tables are read at (§1.0.9). Derived, never stored. */
	public Rarity rarity() {
		return recombobulated ? baseRarity.recombobulated() : baseRarity;
	}

	public boolean dungeonItem() {
		return dungeonItem;
	}

	public ReforgeId reforge() {
		return reforge;
	}

	/** Never drawn (§1.2): every shot full damage and crits. */
	public boolean shortbow() {
		return shortbow;
	}

	/** Null if none the damage system computes. */
	public Ability ability() {
		return ability;
	}

	/**
	 * Finished stat contribution, dungeon-scaled.
	 *
	 * @param pet the pet Chimera copies; ignored without Chimera
	 */
	public StatBlock stats(Pet pet) {
		StatBlock sum = StatBlock.EMPTY;
		for(Map.Entry<String, StatBlock> e : breakdown(pet).entrySet()) sum = sum.plus(e.getValue());
		return finish(sum);
	}

	/**
	 * Same, itemised by source, for {@code /verbose super} and checking against §1. UNSCALED; {@link #finish} applies
	 * to the SUM, never a single term.
	 */
	public Map<String, StatBlock> breakdown(Pet pet) {
		Map<String, StatBlock> out = new LinkedHashMap<>();
		for(StatTerm t : terms) {
			out.merge(t.source().name().toLowerCase(java.util.Locale.ROOT),
					StatBlock.of(t.stat(), t.value()), StatBlock::plus);
		}
		for(Upgrade u : upgrades) out.merge(u.name().toLowerCase(java.util.Locale.ROOT), u.stats(), StatBlock::plus);
		StatBlock reforgeStats = Reforges.stats(reforge, category, rarity());
		if(!reforgeStats.isEmpty()) out.put("reforge (" + reforge.displayName() + ")", reforgeStats);
		StatBlock gems = StatBlock.EMPTY;
		for(Gemstones.Slot slot : gemSlots) gems = gems.plus(Gemstones.stats(slot, rarity()));
		if(!gems.isEmpty()) out.put("gemstones", gems);
		if(chimera && pet != null && !pet.chimeraCopy().isEmpty()) {
			out.put("chimera (" + pet + ")", pet.chimeraCopy());
		}
		return out;
	}

	/**
	 * Copies the pet's base stats (Chimera V), the one thing making item stats depend on the PET.
	 * {@code StatLore.refreshChimeraLore} asks this instead of listing weapon names.
	 */
	public boolean chimera() {
		return chimera;
	}

	/** Swarm V in the ultimate slot, so on a bow no Duplex: no extra arrow, no fire debuff. */
	public boolean swarm() {
		return swarm;
	}

	/** Flame I, on every bow: each arrow hit burns for 6% of it, {@code Procs.flame}. */
	public boolean flame() {
		return category == ItemCategory.RANGED;
	}

	/** Item-wide multiplier and dungeon stage, applied once to the summed terms. */
	private StatBlock finish(StatBlock sum) {
		StatBlock scaled = sum.times(selfMultiplier);
		return dungeonItem ? scaled.scaled(Scale.SB_CATA_MULT, Scale.SB_STAR_MULT) : scaled;
	}

	public static Builder of(String displayName, ItemCategory category) {
		return new Builder(displayName, category);
	}

	public static final class Builder {
		private final String displayName;
		private final ItemCategory category;
		private String loreId = "";
		private Rarity baseRarity = Rarity.COMMON;
		private boolean recombobulated = true; // everything in this plugin is recombed (§1.0.9)
		private boolean dungeonItem = true;
		private final List<StatTerm> terms = new ArrayList<>();
		private final Set<Upgrade> upgrades = EnumSet.noneOf(Upgrade.class);
		private ReforgeId reforge = ReforgeId.NONE;
		private final List<Gemstones.Slot> gemSlots = new ArrayList<>();
		private boolean chimera = false;
		private boolean swarm = false;
		private double selfMultiplier = 1.0;
		private boolean shortbow = false;
		private ItemDef.Ability ability = null;

		private Builder(String displayName, ItemCategory category) {
			this.displayName = displayName;
			this.category = category;
		}

		public Builder loreId(String id) {
			this.loreId = id;
			return this;
		}

		/** BASE rarity. Recombobulated by default, so effective is one tier up. */
		public Builder rarity(Rarity base) {
			this.baseRarity = base;
			return this;
		}

		public Builder notRecombobulated() {
			this.recombobulated = false;
			return this;
		}

		/** Non-dungeon: neither x6.65 nor x1.80 applies (§1.0.3). */
		public Builder notDungeon() {
			this.dungeonItem = false;
			return this;
		}

		public Builder base(Stat stat, double value) {
			terms.add(StatTerm.base(stat, value));
			return this;
		}

		public Builder cataLevel(Stat stat, double value) {
			terms.add(StatTerm.cataLevel(stat, value));
			return this;
		}

		public Builder stars(Stat stat, double value) {
			terms.add(StatTerm.stars(stat, value));
			return this;
		}

		public Builder with(Upgrade... u) {
			java.util.Collections.addAll(upgrades, u);
			return this;
		}

		public Builder reforge(ReforgeId r) {
			this.reforge = r;
			return this;
		}

		/** Combat slot holding a Perfect gem. */
		public Builder combatGem(Gemstones.Type gem) {
			gemSlots.add(Gemstones.Slot.combat(gem, Gemstones.Quality.PERFECT));
			return this;
		}

		/** Slot typed to one gem, e.g. Hyperion's Sapphire slot (§2.3). Perfect. */
		public Builder typedGem(Gemstones.Type gem) {
			gemSlots.add(Gemstones.Slot.typed(gem, Gemstones.Quality.PERFECT));
			return this;
		}

		/** Chimera V: copies the pet's base stats onto ITSELF (§2). */
		public Builder chimera() {
			this.chimera = true;
			return this;
		}

		/** Swarm V: +10% additive per enemy within 10 blocks, 10 max. Replaces Duplex on a bow. */
		public Builder swarm() {
			this.swarm = true;
			return this;
		}

		/** Multiplier on this item's OWN stats only. For the Necron Head Bonus (§1.10): x2 helmet stats in M7. */
		public Builder selfMultiplier(double m) {
			this.selfMultiplier = m;
			return this;
		}

		public Builder shortbow() {
			this.shortbow = true;
			return this;
		}

		public Builder ability(double baseDamage, double intelligenceScaling) {
			this.ability = new ItemDef.Ability(baseDamage, intelligenceScaling);
			return this;
		}

		public ItemDef build() {
			return new ItemDef(this);
		}
	}
}
