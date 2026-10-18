package plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a real player's <b>Max Speed</b> in step with their helmet and pet.
 * <p>
 * Models the SkyBlock Max Speed <i>cap</i> and assumes they're always at it. A sum of parts:
 * <pre>
 *   {@link #BASE_MAX_SPEED} 400
 * + {@link #ALPHA_SHARD} 50
 * + {@code damage.Pet.maxSpeedBonus} 150 for the Black Cat (100 pet + 50 Unalloyed Speed), else 0
 * + {@code items.Wearable.maxSpeedBonus} 100 for the Racing Helmet, else 0
 * </pre>
 * The old model read one figure off the helmet (400 bare, 550 Cow Hat, 650 Racing Helmet), but both hats force the
 * Black Cat in the assumed modes: 550 was 400 + cat, 650 was 400 + cat + 100. The sum gets those back, plus a Black
 * Cat in a Bonzo Mask in realistic (old: 400) and a Racing Helmet without the cat (500).
 * <p>
 * No mode branch: {@code damage.Pet.forPlayer} already answers "assumed from the hat" or "what they summoned".
 * <p>
 * Per-tick poll, since vanilla helmet swaps and the four autopet triggers call nothing. Applied
 * only on a transition so {@code /setspeed} survives; a {@code /dungeonsettings} change
 * reads as an ordinary transition.
 * <p>
 * The poll also applies a separate 50% debuff while carrying a Wither-King relic without a Cow Hat.
 */
public final class MaxSpeedSync {
	private MaxSpeedSync() {}

	private static BukkitTask task;

	/** No pet bonus, no speed helmet. */
	private static final int BASE_MAX_SPEED = 400;
	/** Alpha-exclusive shard. */
	private static final int ALPHA_SHARD = 50;

	/** Max Speed as of last tick, for transitions. */
	private static final Map<UUID, Integer> lastMaxSpeed = new HashMap<>();
	/** Relic debuff as of last tick, for transitions. */
	private static final Map<UUID, Boolean> lastRelicDebuff = new HashMap<>();

	public static void start() {
		if(task != null && !task.isCancelled()) task.cancel();
		task = new BukkitRunnable() {
			@Override
			public void run() {
				for(Player p : Bukkit.getOnlinePlayers()) {
					int now = maxSpeed(p);
					Integer prev = lastMaxSpeed.put(p.getUniqueId(), now);
					// Transitions only, never first observation, so a manual speed survives.
					if(prev != null && prev != now) {
						Utils.setSpeed(p, now);
					}

					// Relic without a Cow Hat: 50% debuff until placed.
					boolean debuff = carryingRelic(p) && !exemptsRelicDebuff(p.getInventory().getHelmet());
					Boolean prevDebuff = lastRelicDebuff.put(p.getUniqueId(), debuff);
					if(prevDebuff == null || prevDebuff != debuff) {
						Utils.setRelicDebuff(p, debuff);
					}
				}
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	/** The sum in the class note. Public so mc-tas-lang's fake helmet swap uses the same formula. */
	public static int maxSpeed(Player p) {
		int total = BASE_MAX_SPEED + ALPHA_SHARD;
		// MELEE is a placeholder, as in StatLore: the assumed table branches on path only after the hat check, and
		// the Black Cat is the only Max Speed pet. A second one would need a real path here.
		total += damage.Pet.forPlayer(p, damage.DamagePath.MELEE).maxSpeedBonus();
		items.Wearable worn = items.ItemRegistry.wearable(p.getInventory().getHelmet());
		if(worn != null) total += worn.maxSpeedBonus();
		return total;
	}

	/** Apply the sum and seed the transition map. Called on join. */
	public static void initSpeed(Player p) {
		int now = maxSpeed(p);
		Utils.setSpeed(p, now);
		lastMaxSpeed.put(p.getUniqueId(), now);
	}

	/** On quit, so a relog re-evaluates speed and relic debuff. */
	public static void forget(UUID id) {
		lastMaxSpeed.remove(id);
		lastRelicDebuff.remove(id);
	}

	/** Any inventory slot holds a real Wither-King relic. */
	private static boolean carryingRelic(Player p) {
		for(ItemStack item : p.getInventory().getContents()) {
			if(instructions.bosses.witherking.WitherKing.relicColorOfItem(item) != null) return true;
		}
		return false;
	}

	/** Helmet cancels the relic debuff; only the Cow Hat does. */
	private static boolean exemptsRelicDebuff(ItemStack helmet) {
		items.Wearable worn = items.ItemRegistry.wearable(helmet);
		return worn != null && worn.exemptsRelicDebuff();
	}
}
