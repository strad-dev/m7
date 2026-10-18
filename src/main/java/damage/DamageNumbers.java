package damage;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import plugin.M7;
import plugin.Utils;

import java.util.*;

/**
 * Floating damage numbers (MAP.md §7a).
 * <p>
 * A {@link TextDisplay} that spawns near the hit, holds still, then is removed. No drift or interpolation: a
 * number sliding up among a dozen others reads as smearing, so the spread is spatial, a random point within
 * {@value #SPAWN_RADIUS} blocks of the target's eyes.
 * <p>
 * Shows what you HIT FOR: after boss resistance and defense divisor, but BEFORE any boss clamp and quantisation. A
 * clamp decides how much health moves, not how hard you hit, so a killing blow reads as the whole hit, a stun-capped
 * hit reads full, and Goldor mid-terminals or Necron mid-interlude show a real number while losing nothing. Health
 * quantisation to {@code HP_STEP} never reaches the display. {@code Damage.deal} names all three figures:
 * {@code preClamp} (shown here), {@code mcDamage} (clamp allowed) and {@code applied} (quantised, what health sees).
 * <p>
 * Never rounded or abbreviated: {@code 726,525,143}, never {@code 726.5M}. Crit format {@code ✧<digits>✧❤}, digit
 * colours from a FIXED cadence so identical hits render identically; a KIND colour overrides it and grey magic
 * numbers are bare digits.
 * <p>
 * See-through (not depth-tested) so a boss model can't hide it; there is no render-priority knob, see the flag.
 */
public final class DamageNumbers {
	private DamageNumbers() {}

	/** Short: a Terminator volley plus Cleave is dozens of hits a tick. */
	private static final int LIFETIME_TICKS = 20;
	/**
	 * Cap on a player's concurrent displays. Every instance gets a number (a Berserk swing is hit + Cleave + ten Fire
	 * Aspect/Venomous ticks), so at 20t life the steady state is dozens; the old cap of 24 evicted a volley's own
	 * numbers before anyone read them. Still capped since it's an entity count.
	 */
	private static final int MAX_PER_PLAYER = 100;
	/** Spawn distance from the target's EYES, any direction. */
	private static final double SPAWN_RADIUS = 1.0;
	/** Random points to try before falling back to the eyes. */
	private static final int SPAWN_ATTEMPTS = 12;

	/**
	 * Crit digit cadence, one entry per DIGIT, wrapping. No named orange in the 16 colours so {@code gold} stands in;
	 * {@code green} is the bright one.
	 */
	private static final String[] CRIT_CADENCE = {"<white>", "<green>", "<gold>", "<red>", "<red>", "<gold>",
			"<green>", "<white>", "<green>", "<gold>", "<red>"};

	private static final Random RANDOM = new Random();
	private static final Map<UUID, Deque<TextDisplay>> LIVE = new HashMap<>();

	/** Called at run start so a previous run's stragglers don't linger. */
	public static void reset() {
		for(Deque<TextDisplay> q : LIVE.values()) {
			for(TextDisplay d : q) if(d.isValid()) d.remove();
		}
		LIVE.clear();
	}

	/** @param sbDamage SkyBlock units, post-resistance and post-defense */
	public static void show(LivingEntity target, double sbDamage, DamageKind kind, Player attacker) {
		if(target == null || sbDamage <= 0 || attacker == null) return;
		Location at = spawnPoint(target);

		Deque<TextDisplay> q = LIVE.computeIfAbsent(attacker.getUniqueId(), k -> new ArrayDeque<>());
		while(q.size() >= MAX_PER_PLAYER) {
			TextDisplay oldest = q.pollFirst();
			if(oldest != null && oldest.isValid()) oldest.remove();
		}

		TextDisplay display = target.getWorld().spawn(at, TextDisplay.class, d -> {
			d.text(Utils.msg(format(sbDamage, kind)));
			d.setBillboard(Display.Billboard.CENTER);
			// SEE THROUGH: a wither is a big model and numbers spawn within a block of its eyes, so depth testing ate them.
			// It's a DEPTH TEST switch, not a sorting hint (verified in 26.2 client): DisplayRenderer$TextDisplayRenderer
			// picks Font.DisplayMode.SEE_THROUGH and RenderTypes.textBackgroundSeeThrough, and
			// RenderPipelines.TEXT_SEE_THROUGH has withDepthStencilState(Optional.empty()). No priority to raise.
			// Cost: draws through walls and floor too. Bounded by 20t life and 0.4 view range (~25 blocks).
			d.setSeeThrough(true);
			d.setShadowed(true);
			d.setViewRange(0.4f);
			d.addScoreboardTag("TASNoName");
			d.addScoreboardTag("TASDamageNumber");
		});
		q.addLast(display);

		// Raw runTaskLater on purpose: Utils.scheduleTask is nuked by a run reset and would leave the display forever.
		org.bukkit.Bukkit.getScheduler().runTaskLater(M7.getInstance(), () -> {
			if(display.isValid()) display.remove();
			Deque<TextDisplay> live = LIVE.get(attacker.getUniqueId());
			if(live != null) live.remove(display);
		}, LIFETIME_TICKS);
	}

	/** Any point within {@link #SPAWN_RADIUS} of the eyes that isn't inside a block, where nobody could read it. */
	private static Location spawnPoint(LivingEntity target) {
		Location eyes = target.getEyeLocation();
		for(int i = 0; i < SPAWN_ATTEMPTS; i++) {
			// Uniform in the sphere: cube-root radius so points don't bunch at the centre.
			double theta = RANDOM.nextDouble() * Math.PI * 2;
			double y = RANDOM.nextDouble() * 2 - 1;
			double ring = Math.sqrt(1 - y * y);
			double r = SPAWN_RADIUS * Math.cbrt(RANDOM.nextDouble());
			Location at = eyes.clone().add(r * ring * Math.cos(theta), r * y, r * ring * Math.sin(theta));
			if(at.getBlock().isPassable()) return at;
		}
		return eyes;
	}

	private static String format(double sbDamage, DamageKind kind) {
		String digits = Damage.integer(sbDamage);
		StringBuilder sb = new StringBuilder();
		if(kind.crit()) sb.append("<white>✧");
		String forced = kind.colour();
		int step = 0;
		for(int i = 0; i < digits.length(); i++) {
			char c = digits.charAt(i);
			// Cadence advances on DIGITS only, so commas can't shift the pattern.
			if(c != ',') {
				sb.append(forced != null ? forced : CRIT_CADENCE[step % CRIT_CADENCE.length]);
				step++;
			}
			sb.append(c);
		}
		if(kind.crit()) sb.append("<white>✧<light_purple>❤");
		return sb.toString();
	}
}
