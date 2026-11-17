package pets;

import damage.Pet;
import damage.Rarity;
import items.ItemFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import plugin.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * The five pets a live-mode player can summon, and how their heads draw in {@code /pets}.
 * <p>
 * <b>Presentation only.</b> Lines are transcribed from the real item; nothing reads a number back. A pet's worth
 * to the damage pipeline is {@link Pet} alone (MAP.md §1.13), so stat lines are authored strings, not a
 * {@code StatBlock}: Golden Dragon's Attack Speed and Magic Find are on its tooltip but not modelled.
 * <p>
 * Stats shown already include the held item, as in game. Four of five hold a Hephaestus Relic (base x 1.5,
 * matching {@link Pet}: Crow's +225 Intelligence is 150 x 1.5, Ender Dragon's +90 Crit Damage is 60 x 1.5).
 * Black Cat's Unalloyed Speed multiplies nothing, so raw. Change a held item and both this and {@link Pet} move.
 * <p>
 * SkyBlock glyphs are stripped (PUA codepoints behind ✦ ✯ ♣ ❁ ☠ ⊙ ❈): they come from a resource-pack font and
 * show as tofu on vanilla, same call as {@code damage/Stat}.
 * <p>
 * Each constant carries its {@link Pet} so the menu and damage pipeline agree on what "the Phoenix" is.
 * {@link Pet#BLACK_CAT} is reachable two ways without conflict: a hat forces it in assumed modes, and it's
 * pickable here in the live modes.
 */
public enum PetType {
	/**
	 * <b>Level 200, not 100.</b> Golden Dragon hatches at 100 and levels to 200; every figure here is level 200
	 * ({@link Pet#GOLDEN_DRAGON}'s +370 Strength), so a level-100 tooltip would show a pet the model doesn't use.
	 */
	GOLDEN_DRAGON(Pet.GOLDEN_DRAGON, "Golden Dragon", Rarity.LEGENDARY, 200,
			"ewogICJ0aW1lc3RhbXAiIDogMTYyMDM1MDA5ODgyNiwKICAicHJvZmlsZUlkIiA6ICJiNWRkZTVmODJlYjM0OTkzYmMwN2Q0MGFiNWY2ODYyMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJsdXhlbWFuIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlOWY5YjFmYzAxNDE2NmNiNDZhMDkzZTUzNDliMmJmNmVkZDIwMWI2ODBkNjJlNDhkYmYzYWY5YjA0NTkxMTYiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==",
			HeldPetItem.HEPHAESTUS_RELIC,
			List.of("<gray>Strength: <red>+370", "<gray>Attack Speed: <yellow>+50%", "<gray>Magic Find: <aqua>+30"),
			List.of(
					new Ability("Gold's Power", List.of(
							"Increase the potency of Midas'",
							"Sword and Midas Staff's Greed Ability",
							"by 10%.")),
					new Ability("Shining Scales", List.of(
							"Grants +11.1 Strength and +2.2",
							"Magic Find to your pet for each digit",
							"in your Gold Collection.",
							"(Max 100M collection)")),
					new Ability("Dragon's Greed", List.of(
							"Grants +0.5% Strength per 5",
							"Magic Find. (Max +5%)")),
					new Ability("Legendary Treasure", List.of(
							"Gain 0.25% damage for every million",
							"coins in your bank. (Max 250%)")),
					new Ability("Symbiosis", List.of(
							"If you own a level 200 Golden",
							"Dragon, gain +5 coins per monster",
							"kill for every other unique maxed",
							"Combat Pet that you own.")))),

	ENDER_DRAGON(Pet.ENDER_DRAGON, "Ender Dragon", Rarity.LEGENDARY, 100,
			"eyJ0aW1lc3RhbXAiOjE1MTg5ODE1ODYzNTksInByb2ZpbGVJZCI6ImQxY2VjOWFkMWRhODQxNzliMWU1NjA0ZjcyYmZiMjI2IiwicHJvZmlsZU5hbWUiOiJydXRnZXI0NjUiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FlYzNmZjU2MzI5MGIxM2ZmM2JjYzM2ODk4YWY3ZWFhOTg4YjZjYzE4ZGMyNTQxNDdmNTgzNzRhZmU5YjIxYjkifX19",
			HeldPetItem.HEPHAESTUS_RELIC,
			List.of("<gray>Strength: <red>+75", "<gray>Crit Chance: <blue>+15%", "<gray>Crit Damage: <blue>+90%"),
			List.of(
					new Ability("End Strike", List.of(
							"Deal 200% more damage to Ender",
							"mobs.")),
					new Ability("One With The Dragon", List.of(
							"Buffs the Aspect of the Dragons",
							"sword by 50 Damage and 30",
							"Strength.")),
					new Ability("Superior", List.of(
							"Increases all Combat stats and",
							"Magic Find by 10%.")))),

	/**
	 * <b>VERY SPECIAL on purpose, though live SkyBlock still shows LEGENDARY.</b> The wiki's Alpha Hypixel Network
	 * changelog of 2026-09-21 says "Changed rarity of the Phoenix Pet from Epic/Legendary to Special/Very Special",
	 * and it's the tier the owner asked for. <b>Don't "fix" it back to LEGENDARY.</b> Stats below are still the
	 * LEGENDARY ones; the change carried them over unaltered.
	 * <p>
	 * {@code Rarity.SPECIAL}/{@code VERY_SPECIAL} never recombobulate and need their own reforge row; neither
	 * matters here since pets aren't reforgeable.
	 */
	PHOENIX(Pet.PHOENIX, "Phoenix", Rarity.VERY_SPECIAL, 100,
			"ewogICJ0aW1lc3RhbXAiIDogMTY0Mjg2NTc3MTM5MSwKICAicHJvZmlsZUlkIiA6ICJiYjdjY2E3MTA0MzQ0NDEyOGQzMDg5ZTEzYmRmYWI1OSIsCiAgInByb2ZpbGVOYW1lIiA6ICJsYXVyZW5jaW8zMDMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjZiMWI1OWJjODkwYzljOTc1Mjc3ODdkZGUyMDYwMGM4Yjg2ZjZiOTkxMmQ1MWE2YmZjZGIwZTRjMmFhM2M5NyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
			HeldPetItem.HEPHAESTUS_RELIC,
			List.of("<gray>Strength: <red>+90", "<gray>Intelligence: <aqua>+225"),
			List.of(
					new Ability("Rekindle", List.of(
							"Before death, become immune and",
							"gain 30 Strength for 4 seconds",
							"60s cooldown")),
					new Ability("Fourth Flare", List.of(
							"On 4th melee strike, ignite mobs,",
							"dealing 15x your Crit Damage",
							"each second for 5 seconds")),
					new Ability("Magic Bird", List.of(
							"You may always fly on your private",
							"island and garden")),
					new Ability("Eternal Coins", List.of(
							"Don't lose coins from death.")))),

	CROW(Pet.CROW, "Crow", Rarity.LEGENDARY, 100,
			"ewogICJ0aW1lc3RhbXAiIDogMTc2MjgwMjExODUxMCwKICAicHJvZmlsZUlkIiA6ICJmODJmNTQ1MDIzZDA0MTFkYmVlYzU4YWI4Y2JlMTNjNyIsCiAgInByb2ZpbGVOYW1lIiA6ICJSZXNwb25kZW50cyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMzFlN2UxYzYxNDYzOWJiYzk1YTBkODFmZjdlNWY3ZjYwNmQ5YjMzMTE1Y2YyYmYyYTQwY2RjYmJiNTMxMzI0IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
			HeldPetItem.HEPHAESTUS_RELIC,
			List.of("<gray>Intelligence: <aqua>+225", "<gray>Ability Damage: <red>+30%"),
			List.of(
					new Ability("Quick Hands", List.of(
							"Lowers the cooldown of your Weapon",
							"abilities by 15%.")),
					new Ability("Camouflage", List.of(
							"After casting an ability, increase",
							"your Defense by +20 for 20",
							"seconds.",
							"Capped at 500 Defense")),
					new Ability("Insightful", List.of(
							"Gives a 15% chance to not consume",
							"Mana when using an ability.")))),

	/**
	 * Only pet here that isn't a damage pick. Intelligence is real ({@link Pet#BLACK_CAT}); Speed, Magic Find, Pet
	 * Luck and the speed cap from its held item aren't modelled, so it trades a dragon's Strength for mana.
	 * <p>
	 * <b>Speed +125 already contains Hunter's +100</b>, don't add them: Hypixel folded Hunter, Omen and
	 * Supernatural into the displayed base stats in 0.18.5, so ability lines restate them.
	 */
	BLACK_CAT(Pet.BLACK_CAT, "Black Cat", Rarity.MYTHIC, 100,
			"ewogICJ0aW1lc3RhbXAiIDogMTcwODczNzEyMTIzNSwKICAicHJvZmlsZUlkIiA6ICJmY2ZhYTg0MzA0YjE0NDUxOThkNWYxNzQ3ZjI0Y2Q5MCIsCiAgInByb2ZpbGVOYW1lIiA6ICJTdGV3eVdvbGZ5IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzgyODJiNWE5YmJlMmNkMzIyMzcyNDAyM2NkNGY2YWQ0MTNmNWJiOWUwZWRlZjgxNzAwYjhhZmMzMDcyZDA0YTUiCiAgICB9CiAgfQp9",
			HeldPetItem.UNALLOYED_SPEED,
			List.of("<gray>Intelligence: <aqua>+100", "<gray>Speed: <white>+125",
					"<gray>Magic Find: <aqua>+15", "<gray>Pet Luck: <light_purple>+15"),
			List.of(
					new Ability("Hunter", List.of(
							"Increases your Speed and speed",
							"cap by +100.")),
					new Ability("Omen", List.of(
							"Grants +15 Pet Luck.")),
					new Ability("Supernatural", List.of(
							"Grants +15 Magic Find.")),
					new Ability("Looting", List.of(
							"Gain 15% more collection items from",
							"monsters!"))));

	/**
	 * One pet ability: gold header line and grey lines under it.
	 * <p>
	 * Pre-wrapped by hand: the server can't measure tooltip width (client font and GUI scale decide it), same as
	 * all multi-line lore in the plugin.
	 */
	public record Ability(String name, List<String> lines) {}

	/** Every pet here is a Combat Pet, the real tooltip's first line. */
	private static final String PET_TYPE_LINE = "Combat Pet";

	private final Pet pet;
	private final String displayName;
	private final Rarity rarity;
	/** Displayed level. 100 for four; {@link #GOLDEN_DRAGON} is 200. */
	private final int level;
	private final String texture;
	private final HeldPetItem held;
	private final List<String> statLines;
	private final List<Ability> abilities;

	PetType(Pet pet, String displayName, Rarity rarity, int level, String texture, HeldPetItem held,
			List<String> statLines, List<Ability> abilities) {
		this.pet = pet;
		this.displayName = displayName;
		this.rarity = rarity;
		this.level = level;
		this.texture = texture;
		this.held = held;
		this.statLines = statLines;
		this.abilities = abilities;
	}

	/** Damage-side constant for this pet. {@link #of(Pet)} goes back. */
	public Pet pet() {
		return pet;
	}

	public String displayName() {
		return displayName;
	}

	public Rarity rarity() {
		return rarity;
	}

	/**
	 * Name everywhere a pet is mentioned: {@code [Lvl N] Name}. Level bracket is always grey, rarity colour is on
	 * the name only ({@code <gray>[Lvl 200] <gold>Golden Dragon}); colouring the whole string made the level read
	 * as part of the name.
	 */
	public String colouredName() {
		return "<gray>[Lvl " + level + "] <" + rarity.colour() + ">" + displayName;
	}

	/** Menu head. {@code equipped} decides the glint and last lore line. */
	public ItemStack icon(boolean equipped, boolean editing) {
		// ItemFactory.head, not a second copy of the profile/NBT code: CLAUDE.md warns that duplication makes saved
		// stacks stop matching. What it does for a wearable head that matters here:
		//   - adds Protection 5, which would print an enchant line and glint every pet;
		//   - runs StatLore.apply, a no-op since no pet is a registered stat item.
		// Null signature: this server never authenticates profiles, so the texture value alone renders.
		ItemStack head = ItemFactory.head(colouredName(), "pet" + name(), texture, null);
		for(Enchantment e : new ArrayList<>(head.getEnchantments().keySet())) head.removeEnchantment(e);

		ItemMeta meta = head.getItemMeta();
		if(meta == null) return head;
		// Component glint, as elsewhere for a chosen item (GoldorTerminalGui.glint; loadout/ItemRefresh tests for
		// it). Set either way so it's never left over from a stack built elsewhere.
		meta.setEnchantmentGlintOverride(equipped);
		meta.lore(lore(equipped, editing));
		head.setItemMeta(meta);
		return head;
	}

	/** Whole tooltip: type, stats, abilities, held item, rarity footer, click action. */
	private List<Component> lore(boolean equipped, boolean editing) {
		List<String> out = new ArrayList<>();
		out.add("<dark_gray>" + PET_TYPE_LINE);
		out.add("");
		out.addAll(statLines);
		for(Ability a : abilities) {
			out.add("");
			out.add("<gold>" + a.name());
			for(String line : a.lines()) out.add("<gray>" + line);
		}
		out.add("");
		// Gold label, item name in its own rarity colour, not the pet's: MYTHIC Black Cat with EPIC Unalloyed
		// Speed prints light_purple over dark_purple.
		out.add("<gold>Held Item: " + held.colouredName());
		out.add("<gray>" + held.effect());
		out.add("");
		// Rarity footer with PET on the end, like a real pet tooltip. The real item uses a tooltip_style component
		// instead, which a server can't set per stack.
		out.add("<" + rarity.colour() + "><bold>" + rarity.display() + " PET");
		out.add("");
		if(editing) {
			out.add("<yellow>Click to pick this pet up");
		} else if(equipped) {
			out.add("<green><bold>CURRENTLY SUMMONED");
		} else {
			out.add("<yellow>Click to summon!");
		}

		List<Component> rendered = new ArrayList<>(out.size());
		for(String line : out) rendered.add(Utils.mm(line));
		return rendered;
	}

	/** Menu pet for a damage-side {@link Pet}, or null if none. */
	public static PetType of(Pet pet) {
		for(PetType t : values()) if(t.pet == pet) return t;
		return null;
	}

	/** Parse a stored enum name, or null if that pet no longer exists. */
	public static PetType parse(String name) {
		if(name == null) return null;
		for(PetType t : values()) if(t.name().equals(name)) return t;
		return null;
	}
}
