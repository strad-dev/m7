package instructions.bosses;

import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.craftbukkit.entity.CraftWither;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wither;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import plugin.M7;
import plugin.Utils;

@SuppressWarnings("DataFlowIssue")
public class CustomBossBar {
	private static BossBar activeWitherBossBar;
	private static Wither activeWither;
	private static BukkitTask bossBarUpdateTask;
	private static TextDisplay activeStunIndicator;

	public static void setupWitherBossBar(Wither wither, String witherName) {
		cleanupActiveBossBar();

		activeWither = wither;
		disableVanillaWitherBossBar(wither);
		createWitherBossBar(witherName);
	}

	private static void disableVanillaWitherBossBar(Wither wither) {
		if(!(wither instanceof CraftWither)) {
			return;
		}

		try {
			Utils.scheduleTask(() -> {
				WitherBoss nmsWither = ((CraftWither) wither).getHandle();
				nmsWither.bossEvent.removeAllPlayers();
			}, 1);
		} catch(Exception e) {
			Bukkit.getLogger().warning("Failed to disable vanilla wither bossbar");
		}
	}

	private static void createWitherBossBar(String witherName) {
		if(activeWither == null) {
			return;
		}

		double maxHealth = activeWither.getAttribute(Attribute.MAX_HEALTH).getValue();
		boolean exempt = activeWither.getScoreboardTags().contains("TASWitherKing");
		String healthStr = exempt ? String.valueOf((int) maxHealth) : Utils.formatHealthM(activeWither);

		// witherName is MiniMessage; <reset> closes any obfuscated/bold it carries.
		String title = "<gold><bold>﴾ <red><bold>" + witherName + "<reset><gold><bold> ﴿ <!bold><yellow>" + healthStr + "<red>❤";

		activeWitherBossBar = Bukkit.createBossBar(Utils.mmLegacy(title), BarColor.PURPLE, BarStyle.SOLID);
		activeWitherBossBar.setProgress(1.0);

		for(Player player : Bukkit.getOnlinePlayers()) {
			activeWitherBossBar.addPlayer(player);
		}

		startBossBarUpdateTask(witherName);
	}

	private static void startBossBarUpdateTask(String witherName) {
		bossBarUpdateTask = new BukkitRunnable() {
			@Override
			public void run() {
				if(activeWither == null || activeWither.isDead()) {
					cleanupActiveBossBar();
					cancel();
					return;
				}

				updateWitherBossBar(witherName);
				disableVanillaWitherBossBar(activeWither);
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	private static void updateWitherBossBar(String witherName) {
		if(activeWitherBossBar == null || activeWither == null) {
			return;
		}

		double currentHealth = activeWither.getHealth();
		double maxHealth = activeWither.getAttribute(Attribute.MAX_HEALTH).getValue();
		boolean exempt = activeWither.getScoreboardTags().contains("TASWitherKing");
		String healthStr = exempt ? String.valueOf((int) Math.floor(currentHealth)) : Utils.formatHealthM(activeWither);

		String title = "<gold><bold>﴾ <red><bold>" + witherName + "<reset><gold><bold> ﴿ <!bold><yellow>" + healthStr + "<red>❤";

		activeWitherBossBar.setTitle(Utils.mmLegacy(title));

		double progress = Math.clamp(currentHealth / maxHealth, 0.0, 1.0);
		activeWitherBossBar.setProgress(progress);

		for(Player player : Bukkit.getOnlinePlayers()) {
			if(!activeWitherBossBar.getPlayers().contains(player)) {
				activeWitherBossBar.addPlayer(player);
			}
		}
	}

	private static void cleanupActiveBossBar() {
		if(activeWitherBossBar != null) {
			activeWitherBossBar.removeAll();
			activeWitherBossBar = null;
		}
		if(bossBarUpdateTask != null) {
			bossBarUpdateTask.cancel();
			bossBarUpdateTask = null;
		}
		activeWither = null;
	}

	public static void forceCleanup() {
		if(activeWither != null && !activeWither.isDead()) {
			activeWither.remove();
		}

		cleanupActiveBossBar();
	}

	public static BossBar getActiveBossBar() {
		return activeWitherBossBar;
	}

	public static Wither getActiveWither() {
		return activeWither;
	}

	public static void spawnAnimatedStunnedIndicator(Wither wither, int duration) {
		removeStunIndicator();

		Location loc = wither.getLocation().add(0, wither.getHeight() + 0.5, 0);
		TextDisplay indicator = wither.getWorld().spawn(loc, TextDisplay.class);
		activeStunIndicator = indicator;

		indicator.setBillboard(Display.Billboard.CENTER);
		indicator.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
		indicator.setSeeThrough(true);
		indicator.setShadowed(true);

		// Runs every tick so the indicator follows a moving boss (Necron mid-chase); colours rotate every 5 ticks.
		new BukkitRunnable() {
			int colorOffset = 0;
			int tickCount = 0;

			@Override
			public void run() {
				if(!wither.isValid() || !indicator.isValid()) {
					indicator.remove();
					cancel();
					return;
				}

				indicator.teleport(wither.getLocation().add(0, wither.getHeight() + 0.5, 0));

				if(tickCount++ % 5 == 0) {
					String[] colors = {"<red>", "<yellow>", "<blue>"};
					StringBuilder text = new StringBuilder();
					for(int i = 0; i < 3; i++) {
						int colorIndex = (i + colorOffset) % 3;
						text.append(colors[colorIndex]).append("<bold>?");
					}
					indicator.text(Utils.msg(text.toString()));
					colorOffset = (colorOffset + 1) % 3;
				}
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);

		Utils.scheduleTask(() -> {
			if(activeStunIndicator == indicator) activeStunIndicator = null;
			indicator.remove();
		}, duration);

	}

	public static void removeStunIndicator() {
		if(activeStunIndicator != null) {
			if(activeStunIndicator.isValid()) activeStunIndicator.remove();
			activeStunIndicator = null;
		}
	}
}