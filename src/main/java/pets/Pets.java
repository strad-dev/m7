package pets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import damage.Difficulty;
import damage.Pet;
import org.bukkit.entity.Player;
import plugin.Catalog;
import plugin.M7;
import plugin.Utils;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who has which pet out, menu layout, autopet rules: all of the live modes' pet state, in memory and on disk.
 *
 * <h2>The damage pipeline's entry point</h2>
 * {@code damage/Pet.forPlayer} calls {@link #equippedDamagePet(Player)} whenever {@link Difficulty#manualPets()}
 * is on, <b>per stat aggregate</b> (several times a swing). So it's a map read: the file loads once per player
 * (join or first miss), then {@link #CACHE}. Nothing here may touch disk on a read path.
 *
 * <h2>Its own file, on purpose</h2>
 * Writes {@code <data>/pets/&lt;uuid&gt;.json} via {@link Catalog#dataDir()}, like {@code loadout/Loadouts}.
 * <b>Must not be extra fields on the loadout file</b>: StradDevHub reads that into its own twin
 * {@code LoadoutFile} with no pet fields, so its next lobby write would silently drop them. StradDevHub's
 * {@code pets/PetLayouts} does write this file too, but edits the raw JSON tree and keeps members it didn't touch.
 *
 * <h2>Every mutator saves</h2>
 * One small write per choice; save-on-quit loses everything to a crash or a {@code /stop} that outruns the
 * handler. Writes are temp+rename, like {@code Loadouts.save}.
 */
public final class Pets {
	private Pets() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/**
	 * Pet out before the menu is ever opened. Golden Dragon, same as the assumed fallthrough in
	 * {@code damage/Pet.forPlayer}, so switching to a live mode changes nobody's damage until they pick.
	 */
	public static final PetType DEFAULT_PET = PetType.GOLDEN_DRAGON;

	/** Loaded profiles by uuid. The only thing a read path may consult. */
	private static final Map<UUID, Profile> CACHE = new HashMap<>();

	/**
	 * One player's pet state. Mutable, owned by {@link Pets}: every change goes through a setter here, the one
	 * place that also writes the file and drops the stat cache.
	 */
	public static final class Profile {
		/** Menu slot -> pet. Each {@link PetType} at most once. */
		final Map<Integer, PetType> layout = new LinkedHashMap<>();
		/** Pet that is out. Never null. */
		PetType equipped = DEFAULT_PET;
		/**
		 * Pet a run BEGINS with, the loadout editor's slot 51. Never null.
		 * <p>
		 * <b>Not {@link #equipped} on purpose.</b> That's live state moved by {@code /pets} and every autopet rule,
		 * so a starting pet stored there was whatever the last run left out. This is a preference; only slot 51
		 * writes it.
		 */
		PetType startingPet = DEFAULT_PET;
		/** Per-trigger rules. Absent is off, same as a null-pet rule. */
		final EnumMap<Autopet.Trigger, Autopet.Rule> rules = new EnumMap<>(Autopet.Trigger.class);
		/**
		 * Rod Swap cycle in order. Empty is off. <b>Repeats are legal</b>: [Golden Dragon, Crow, Golden Dragon,
		 * Phoenix] is a four-throw rotation, so a List and nothing dedupes it.
		 */
		final List<PetType> rodCycle = new ArrayList<>();
	}

	// ==================== reads ====================

	/**
	 * {@link Pet} out, for the damage pipeline. <b>Never null</b> (would NPE every stat aggregate): no file, bad
	 * file or a deleted pet all give {@link #DEFAULT_PET}.
	 */
	public static Pet equippedDamagePet(Player p) {
		return equipped(p).pet();
	}

	/** Pet out. Never null. */
	public static PetType equipped(Player p) {
		return p == null ? DEFAULT_PET : profile(p.getUniqueId()).equipped;
	}

	/** Pet runs begin with. Never null; missing or bad file is {@link #DEFAULT_PET}. */
	public static PetType startingPet(Player p) {
		return p == null ? DEFAULT_PET : profile(p.getUniqueId()).startingPet;
	}

	/**
	 * Action-bar segment {@code " | [Lvl N] Name"}, appended by {@code Utils.sendActionBar} and drawn by
	 * {@code death/Deaths}' fallback when no HUD owns the bar. <b>Live modes only</b>, classic assumes the pet.
	 * Nothing for spectators.
	 */
	public static String actionBarSegment(Player p) {
		if(!Difficulty.manualPets() || Utils.isSpectator(p)) return "";
		return Utils.ACTION_BAR_SEPARATOR + equipped(p).colouredName();
	}

	/** Menu layout, slot -> pet. Live view, don't mutate. */
	public static Map<Integer, PetType> layout(Player p) {
		return profile(p.getUniqueId()).layout;
	}

	/** Rule for a trigger, or null if off. */
	public static Autopet.Rule rule(Player p, Autopet.Trigger t) {
		return profile(p.getUniqueId()).rules.get(t);
	}

	/** Rod Swap cycle in order, repeats included. Live view, don't mutate. */
	public static List<PetType> rodCycle(Player p) {
		return profile(p.getUniqueId()).rodCycle;
	}

	// ==================== writes ====================

	/**
	 * Summon a pet, replacing whatever was out.
	 * <p>
	 * <b>The one place a pet changes</b>, so the one place that drops the stat cache. Most equips aren't inventory
	 * events: {@code damage/StatListener} invalidates on click, close, held-slot change etc., covering a
	 * {@code /pets} click but none of the four autopet triggers. Without it a rod-swap equip serves the old pet's
	 * Strength for up to {@code Stats.CACHE_TICKS}, through the first swing after.
	 *
	 * @return true if the pet changed, so the caller can stay quiet otherwise.
	 */
	public static boolean equip(Player p, PetType pet, String announcement) {
		if(p == null || pet == null) return false;
		Profile prof = profile(p.getUniqueId());
		if(prof.equipped == pet) return false;
		prof.equipped = pet;
		save(p.getUniqueId(), prof);
		damage.Stats.invalidate(p);
		// Goes with the cache drop: that fixes what the next hit is worth, this fixes what Chimera weapons say.
		damage.StatLore.refreshChimeraLore(p);
		// Caller owns the line except the pet name (rarity colour). No punctuation added: the old trailing "!"
		// landed mid-sentence for autopet.
		if(announcement != null) p.sendMessage(Utils.msg(announcement + pet.colouredName()));
		return true;
	}

	/** Set the starting pet. Nothing else writes it; nothing during a run touches it. */
	public static void setStartingPet(Player p, PetType pet) {
		if(p == null || pet == null) return;
		Profile prof = profile(p.getUniqueId());
		if(prof.startingPet == pet) return;
		prof.startingPet = pet;
		save(p.getUniqueId(), prof);
	}

	/**
	 * Put everyone on their starting pet, silently (it's run setup like the kit, not autopet). Called from
	 * {@code Server.serverInstructions} at SETUP for every section; the countdown's end left the warp-in on the
	 * last run's pet. <b>An autopet {@code RUN_START} rule still wins</b>: it fires later (door opens) and equip is
	 * last-write-wins.
	 */
	public static void applyStartingPets() {
		for(Player p : org.bukkit.Bukkit.getOnlinePlayers()) applyStartingPet(p);
	}

	/**
	 * One player's part of {@link #applyStartingPets}; also used by {@code PetMenu.onJoin} for late arrivals.
	 * Spectators skipped: not in the run.
	 */
	public static void applyStartingPet(Player p) {
		if(!Difficulty.manualPets() || Utils.isSpectator(p)) return;
		equip(p, startingPet(p), null);
	}

	/** Replace the layout (arranging menu's save). Slots not validated here; the menu owns that. */
	public static void setLayout(Player p, Map<Integer, PetType> layout) {
		Profile prof = profile(p.getUniqueId());
		prof.layout.clear();
		prof.layout.putAll(layout);
		save(p.getUniqueId(), prof);
	}

	/** Put the five pets back on the first five valid slots. */
	public static void resetLayout(Player p) {
		Profile prof = profile(p.getUniqueId());
		prof.layout.clear();
		prof.layout.putAll(defaultLayout());
		save(p.getUniqueId(), prof);
	}

	public static void setRule(Player p, Autopet.Trigger t, Autopet.Rule rule) {
		Profile prof = profile(p.getUniqueId());
		if(rule == null || rule.isOff()) prof.rules.remove(t);
		else prof.rules.put(t, rule);
		save(p.getUniqueId(), prof);
	}

	public static void setRodCycle(Player p, List<PetType> cycle) {
		Profile prof = profile(p.getUniqueId());
		prof.rodCycle.clear();
		prof.rodCycle.addAll(cycle);
		save(p.getUniqueId(), prof);
	}

	// ==================== the default layout ====================

	/**
	 * Five pets on the first five valid slots: 10-14. Derived from {@link PetMenu#petSlots()} so moving a pane moves
	 * the defaults too.
	 */
	static Map<Integer, PetType> defaultLayout() {
		Map<Integer, PetType> out = new LinkedHashMap<>();
		List<Integer> slots = PetMenu.petSlots();
		PetType[] order = PetType.values();
		for(int i = 0; i < order.length && i < slots.size(); i++) out.put(slots.get(i), order[i]);
		return out;
	}

	// ==================== lifecycle ====================

	/**
	 * Load a player's file into the cache on join so the damage path never has to. {@link #profile} loads on a
	 * miss anyway, but that miss is reachable mid damage calc, breaking {@code Pet.forPlayer}'s "map read, not
	 * file read" contract.
	 */
	public static void preload(UUID uuid) {
		profile(uuid);
	}

	/** Drop profile on quit. Every mutator already saved, nothing to flush. */
	public static void unload(UUID uuid) {
		CACHE.remove(uuid);
	}

	/** Forget all profiles. Plugin disable only; files are current. */
	public static void clearCache() {
		CACHE.clear();
	}

	static Profile profile(UUID uuid) {
		Profile cached = CACHE.get(uuid);
		if(cached != null) return cached;
		Profile fresh = read(uuid);
		CACHE.put(uuid, fresh);
		return fresh;
	}

	// ==================== file I/O ====================

	private static Path file(UUID uuid) throws Exception {
		return Catalog.dataDir().resolve("pets/" + uuid + ".json");
	}

	private static Profile read(UUID uuid) {
		PetsFile f = null;
		try {
			Path p = file(uuid);
			if(Files.exists(p)) {
				try(Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					f = GSON.fromJson(r, PetsFile.class);
				}
			}
		} catch(Exception e) {
			M7.getInstance().getLogger().warning("Failed to read pets for " + uuid + ": " + e);
		}
		Profile prof = new Profile();
		if(f == null) {
			prof.layout.putAll(defaultLayout());
			return prof;
		}
		// Values that no longer resolve are DROPPED, not defaulted: a pre-rename file must read as an empty slot,
		// never a different pet. Slots are checked against the menu's list too: a pre-pane-move file could name a
		// square the menu no longer draws, hiding that pet for good.
		if(f.layout != null) {
			for(Map.Entry<String, String> e : f.layout.entrySet()) {
				PetType pet = PetType.parse(e.getValue());
				Integer slot = parseSlot(e.getKey());
				if(pet == null || slot == null || !PetMenu.petSlots().contains(slot)) continue;
				if(!prof.layout.containsValue(pet)) prof.layout.put(slot, pet);
			}
		}
		// Resolved to nothing (empty, or all pets deleted): same as a first open.
		if(prof.layout.isEmpty()) prof.layout.putAll(defaultLayout());
		// A pet missing from the layout needs a home or it's unreachable until a reset.
		for(PetType pet : PetType.values()) if(!prof.layout.containsValue(pet)) placeInFirstFree(prof.layout, pet);

		PetType equipped = PetType.parse(f.equipped);
		prof.equipped = equipped == null ? DEFAULT_PET : equipped;
		PetType starting = PetType.parse(f.startingPet);
		prof.startingPet = starting == null ? DEFAULT_PET : starting;

		if(f.autopet != null) {
			for(Map.Entry<String, RuleEntry> e : f.autopet.entrySet()) {
				Autopet.Trigger t = Autopet.Trigger.parse(e.getKey());
				if(t == null || e.getValue() == null) continue;
				Autopet.Rule rule = new Autopet.Rule(PetType.parse(e.getValue().pet), readExceptions(e.getValue()));
				if(!rule.isOff()) prof.rules.put(t, rule);
			}
		}
		// REPEATS ARE KEPT. This used to drop duplicates when the menu appended each pet once; the editor now
		// allows repeats, so a filter would silently shorten a saved rotation on load.
		if(f.rodCycle != null) {
			for(String name : f.rodCycle) {
				PetType pet = PetType.parse(name);
				if(pet != null) prof.rodCycle.add(pet);
			}
		}
		return prof;
	}

	/**
	 * A saved rule's exceptions, folding in the pre-list scalar field.
	 * <p>
	 * <b>Old {@code exception} field is read forever.</b> Rules had one optional exception before the list, and
	 * a backup or rolled-back server can hand over such a file any time (same reason StradDevHub's
	 * {@code m7/lb/Leaderboards} keeps its migrations). Costs one null check per rule.
	 * <p>
	 * Never written again: {@link #save} leaves it null and Gson omits nulls, so the first save moves it into
	 * {@code exceptions}. {@code Autopet.Rule} dedupes, so folding a value already in the list is harmless.
	 */
	private static List<PetType> readExceptions(RuleEntry entry) {
		List<PetType> out = new ArrayList<>();
		PetType legacy = PetType.parse(entry.exception);
		if(legacy != null) out.add(legacy);
		if(entry.exceptions != null) {
			for(String name : entry.exceptions) {
				PetType pet = PetType.parse(name);
				if(pet != null) out.add(pet);
			}
		}
		return out;
	}

	/** Put a pet on the lowest free valid slot. 28 slots, five pets, so one exists. */
	static void placeInFirstFree(Map<Integer, PetType> layout, PetType pet) {
		for(int slot : PetMenu.petSlots()) {
			if(!layout.containsKey(slot)) {
				layout.put(slot, pet);
				return;
			}
		}
	}

	private static Integer parseSlot(String s) {
		try {
			return Integer.valueOf(s);
		} catch(NumberFormatException e) {
			return null;
		}
	}

	private static void save(UUID uuid, Profile prof) {
		PetsFile f = new PetsFile();
		f.layout = new LinkedHashMap<>();
		for(Map.Entry<Integer, PetType> e : prof.layout.entrySet()) f.layout.put(String.valueOf(e.getKey()), e.getValue().name());
		f.equipped = prof.equipped.name();
		f.startingPet = prof.startingPet.name();
		f.autopet = new LinkedHashMap<>();
		for(Map.Entry<Autopet.Trigger, Autopet.Rule> e : prof.rules.entrySet()) {
			RuleEntry entry = new RuleEntry();
			entry.pet = e.getValue().pet() == null ? null : e.getValue().pet().name();
			// entry.exception left null on purpose, see readExceptions.
			entry.exceptions = new ArrayList<>();
			for(PetType except : e.getValue().exceptions()) entry.exceptions.add(except.name());
			f.autopet.put(e.getKey().name(), entry);
		}
		f.rodCycle = new ArrayList<>();
		for(PetType pet : prof.rodCycle) f.rodCycle.add(pet.name());

		try {
			Path file = file(uuid);
			Path parent = file.getParent();
			if(parent != null) Files.createDirectories(parent);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try(Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(f, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch(AtomicMoveNotSupportedException ex) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch(Exception e) {
			M7.getInstance().getLogger().warning("Failed to save pets for " + uuid + ": " + e);
		}
	}

	/**
	 * On-disk shape. Enum NAMES, not ordinals, so reordering {@link PetType} or {@link Autopet.Trigger} doesn't
	 * repoint saved rules.
	 */
	private static final class PetsFile {
		Map<String, String> layout;
		String equipped;
		/** Starting pet. Absent in files from before slot 51, reads as the default. */
		String startingPet;
		Map<String, RuleEntry> autopet;
		List<String> rodCycle;
	}

	/**
	 * One rule on disk. {@code exception} is the PRE-LIST field, read-only: {@link #readExceptions} folds it into
	 * {@code exceptions}, {@link #save} never writes it. Deleting it silently turns every old rule into "no exception".
	 */
	private static final class RuleEntry {
		String pet;
		String exception;
		List<String> exceptions;
	}
}
