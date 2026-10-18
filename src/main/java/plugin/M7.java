/*
 * MIT License
 *
 * Copyright ©2025 Stradivarius Violin
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package plugin;

import commands.*;
import instructions.Server;
import instructions.bosses.goldor.Goldor;
import listeners.*;
import loadout.ClassCommand;
import loadout.LoadoutEditor;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

public final class M7 extends JavaPlugin {
	private static Plugin plugin;

	/** Held so {@link #onDisable} can hand back the real inventory an open editor parks in a field. */
	private LoadoutEditor loadoutEditor;

	/** Held likewise: an arranging session parks a pet on the cursor. */
	private pets.PetMenu petMenu;

	/**
	 * {@code minecraft:max_health} ceiling. HP is divided by {@code damage.Scale.SB_PER_MC_HP}, so the largest set is
	 * Necron's 1400; this is headroom, not a target.
	 */
	private static final double MAX_HEALTH_CEILING = 1_000_000.0;

	/**
	 * Raise {@code minecraft:max_health}'s ceiling before any world or entity loads.
	 * <p>
	 * Paper defaults to 1024 and only {@code spigot.yml}'s {@code max-health} changes it, so boss HP
	 * ({@code damage/MobStats}) would silently clamp on any un-edited config. {@code RangedAttribute.maxValue} is public
	 * and non-final (Spigot sets the key the same way), so the plugin sets it itself.
	 * <p>
	 * Must be {@code onLoad}: {@code AttributeInstance} clamps a base value at set time. Entities persisted under the
	 * old cap reload clamped; bosses set HP in {@code onSpawn}, so only a boss standing in the world needs a re-spawn.
	 */
	@Override
	public void onLoad() {
		net.minecraft.world.entity.ai.attributes.Attribute maxHealth =
				net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH.value();
		if(maxHealth instanceof net.minecraft.world.entity.ai.attributes.RangedAttribute ranged) {
			if(ranged.maxValue < MAX_HEALTH_CEILING) ranged.maxValue = MAX_HEALTH_CEILING;
		} else {
			getLogger().warning("minecraft:max_health is not a RangedAttribute; boss HP may clamp.");
		}
	}

	@Override
	public void onEnable() {
		plugin = this;

		// FIRST, so its task id is lowest and boss tickers run before per-run choreography (BossScheduler).
		BossScheduler.start();

		PlayerCollision.setupNoCollisionTeam();

		loadoutEditor = new LoadoutEditor();
		petMenu = new pets.PetMenu();
		commands.SettingsMenu settingsMenu = new commands.SettingsMenu();
		for(String cmd : List.of("setup", "m7practice", "eq", "reset", "verbose", "setspeed",
				"class", "m7loadout", "dungeonsettings", "pets", "petloadout")) {
			PluginCommand command = getCommand(cmd);
			switch(cmd) {
				case "setup" -> command.setExecutor(new Setup());
				case "m7practice" -> command.setExecutor(new Practice());
				case "reset" -> command.setExecutor(new Reset());
				case "eq" -> command.setExecutor(new Eq());
				case "verbose" -> command.setExecutor(new Verbose());
				case "setspeed" -> command.setExecutor(new SetSpeed());
				case "class" -> command.setExecutor(new ClassCommand());
				case "m7loadout" -> command.setExecutor(loadoutEditor);
				case "dungeonsettings" -> command.setExecutor(new DungeonSettings(settingsMenu));
				// One menu in two modes; shares the instance the shutdown holds.
				case "pets", "petloadout" -> command.setExecutor(petMenu);
			}
			command.setTabCompleter(new TabCompletor());
		}
		getServer().getPluginManager().registerEvents(new JoinListener(), this);
		getServer().getPluginManager().registerEvents(new WithersNotImmuneToArrows(), this);
		getServer().getPluginManager().registerEvents(new PearlHelper(), this);
		getServer().getPluginManager().registerEvents(new MiscListener(), this);
		getServer().getPluginManager().registerEvents(new CustomItems(), this);
		getServer().getPluginManager().registerEvents(new AllMobsHaveNames(), this);
		getServer().getPluginManager().registerEvents(new StormCrushExplosion(), this);
		getServer().getPluginManager().registerEvents(new GoldorListener(), this);
		getServer().getPluginManager().registerEvents(instructions.bosses.necron.NecronDrag.LISTENER, this);
		getServer().getPluginManager().registerEvents(new WitherKingListener(), this);
		getServer().getPluginManager().registerEvents(new SpiritLeapListener(), this);
		getServer().getPluginManager().registerEvents(new Eq(), this);
		getServer().getPluginManager().registerEvents(new LinkedSlots(), this);
		getServer().getPluginManager().registerEvents(new listeners.ClearListener(), this);
		getServer().getPluginManager().registerEvents(new death.GenericKill(), this);
		getServer().getPluginManager().registerEvents(loadoutEditor, this);
		getServer().getPluginManager().registerEvents(settingsMenu, this);
		// First two MUST be petMenu's own instance: it holds the carried pet onDisable hands back.
		getServer().getPluginManager().registerEvents(petMenu, this);
		getServer().getPluginManager().registerEvents(petMenu.autopetMenu(), this);
		getServer().getPluginManager().registerEvents(new pets.Autopet(), this);
		// Invalidates the (player, path) stat cache on equipment changes (MAP.md §7).
		getServer().getPluginManager().registerEvents(new damage.StatListener(), this);

		// One driver for every DoT chain (Fire Aspect, Venomous), not a task per proc.
		damage.Procs.start();
		items.tools.Dungeonbreaker.start();

		MaxSpeedSync.start();
		// Terminator fire poller (5-tick cooldown, 4 with Thermodynamic).
		getServer().getScheduler().runTaskTimer(this, items.bows.Terminator::pollAll, 1L, 1L);
		// Boss-movement driver (BossScheduler's movement lane).
		getServer().getScheduler().runTaskTimer(this, BossScheduler::runMovementTickers, 1L, 1L);
		SpringBoots.start();
		LavaJump.start();
		listeners.OutOfBounds.start();
		// S4 Sharp Shooter plate: no "stepped off" event, so the reset is polled.
		listeners.GoldorListener.startSharpPlatePoll();
		// Death/revival driver (countdowns, saver durability bars, action-bar fallback). Raw and untracked, so a
		// scheduler flush can't strand a ghost in spectator (death.Deaths.start).
		death.Deaths.start();

		// Registry vs palette order and damage/Items' rarities. Warns only; before the export so it logs first.
		Catalog.verify();

		// Palette + default kits to the shared folder for the lobby loadout editor. M7 is the sole writer.
		Catalog.export();

		// /class and /m7loadout exist in BOTH plugins; M7 owns them here. A bare label goes to whoever registers
		// first; the network softdepends on us, so this is a backstop (load order changed, reload). The network no
		// longer claims them on m7, and with our lower task id we'd lose a tug-of-war anyway.
		getServer().getScheduler().runTask(this, () -> {
			forceOwnLabel("class");
			forceOwnLabel("m7loadout");
		});
	}

	/**
	 * Take a bare label back from another plugin; the loser keeps its prefixed form
	 * ({@code /stradnetworkplugin:class}). Mirrors the network's {@code Main.forceOwnLabel}.
	 */
	private void forceOwnLabel(String name) {
		PluginCommand ours = getCommand(name);
		if(ours == null) return;
		if(!(getServer().getCommandMap() instanceof SimpleCommandMap map)) return;
		Map<String, Command> known = map.getKnownCommands();
		if(known.get(name) == ours) return;
		known.remove(name);
		known.put(name, ours);
		ours.register(map);
		getLogger().info("Claimed /" + name + " for M7 (was another plugin's).");
	}

	@Override
	public void onDisable() {
		// Hand back the real inventory of anyone in the editor, or they keep palette copies. First, before the run
		// teardown.
		if(loadoutEditor != null) loadoutEditor.restoreAll();
		// Same for a /petloadout pet on the cursor.
		if(petMenu != null) petMenu.restoreAll();
		SpringBoots.stop();
		LavaJump.stop();
		listeners.OutOfBounds.stop();
		listeners.GoldorListener.stopSharpPlatePoll();
		death.Deaths.stop();
		BossScheduler.stop();

		// Both flushes: the superboom/crypt regen is a raw runTaskLater, so a disable in its 100-tick window saved
		// the hole into the world.
		items.ItemUtils.flushStonkRestorations();
		items.ItemUtils.flushBlockRestorations();

		// Clear HUD/map loop (hardMobCleanup removes the secret entities).
		if(!org.bukkit.Bukkit.getWorlds().isEmpty()) instructions.clear.ClearManager.stop(org.bukkit.Bukkit.getWorlds().getFirst());

		Goldor.INSTANCE.shutdownRegenerateGates();
		// Same for the S1 device: a mid-sequence disable would SAVE a sea lantern or the 16 buttons (and the missing
		// "i1" sign) into the world, and no boot restores them. Its tracked timer can't cover this.
		instructions.bosses.goldor.GoldorSimonSays.INSTANCE.cleanup();

		// No restore of their own: a stop mid-boss-chain saved open transition walls and a frozen Storm pillar.
		// serverSetup does both too.
		instructions.bosses.BossTransition.resetAll();
		if(!org.bukkit.Bukkit.getWorlds().isEmpty()) {
			instructions.bosses.WitherSpawn.restoreStormPillars(org.bukkit.Bukkit.getWorlds().getFirst());
		}

		PlayerCollision.cleanup();

		Server.hardMobCleanup();
	}

	public static Plugin getInstance() {
		return plugin;
	}
}
