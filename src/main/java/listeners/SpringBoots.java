package listeners;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import plugin.M7;
import plugin.MovementAudit;
import plugin.Utils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpringBoots {
	/** The boots this drives; the lore ID lives on the item. */
	private static final items.Item BOOTS = items.armor.SpringBootsItem.INSTANCE;

	// pitch values for BLOCK_NOTE_BLOCK_PLING (F#3=0 → F#5=24; pitch = 2^((n-12)/12))
	private static final float PITCH_C4    = 0.7087f; // note 6  (existing code)
	private static final float PITCH_EB4   = 0.8428f; // note 9  (existing code)
	private static final float PITCH_E4    = 0.8929f; // note 10 (existing code)
	private static final float PITCH_F4    = 0.9439f; // note 11
	private static final float PITCH_G4    = 1.0595f; // note 13
	private static final float PITCH_A4    = 1.1892f; // note 15
	private static final float PITCH_B4    = 1.3348f; // note 17
	private static final float PITCH_E5    = 1.7818f; // note 22

	// Per-note schedule for charge-up sounds.
	private static final int[]   NOTE_TICK;
	private static final float[] NOTE_PITCH;

	static {
		java.util.List<int[]> ticks = new java.util.ArrayList<>();
		java.util.List<Float> pitches = new java.util.ArrayList<>();

		record Level(float pitch, int count, int gapInto, int gapWithin) {}
		Level[] levels = new Level[] {
				new Level(PITCH_C4,  2, 0, 2),
				new Level(PITCH_EB4, 6, 2, 2),
				new Level(PITCH_E4,  8, 2, 4),
				new Level(PITCH_F4,  7, 6, 6),
				new Level(PITCH_G4,  7, 6, 8),
				new Level(PITCH_A4, 10, 7,10),
				new Level(PITCH_B4,  5,30,30),
				new Level(PITCH_E5,  1,10,40),
		};

		int t = 0;
		boolean first = true;
		for(Level lvl : levels) {
			for(int i = 0; i < lvl.count; i++) {
				if(first) { t = 0; first = false; }
				else t += (i == 0 ? lvl.gapInto : lvl.gapWithin);
				ticks.add(new int[]{t});
				pitches.add(lvl.pitch);
			}
		}

		NOTE_TICK = ticks.stream().mapToInt(a -> a[0]).toArray();
		NOTE_PITCH = new float[pitches.size()];
		for(int i = 0; i < pitches.size(); i++) NOTE_PITCH[i] = pitches.get(i);
	}

	private static final int E5_FIRST_TICK = 397;
	private static final int E5_PERIOD = 40;

	private static final double MAX_HEIGHT = 61.0;
	private static final double V_CAP = heightToVelocity();

	private static final Map<UUID, ChargeState> states = new HashMap<>();
	private static BukkitTask poller;

	private static class ChargeState {
		boolean sneakPrev;
		boolean onGroundPrev;
		boolean charging;
		int ticks;
		int nextNoteIndex;
	}

	public static void start() {
		if(poller != null) return;
		poller = new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		}.runTaskTimer(M7.getInstance(), 0L, 1L);
	}

	public static void stop() {
		if(poller != null) {
			poller.cancel();
			poller = null;
		}
		states.clear();
	}

	private static void tick() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			ItemStack boots = p.getInventory().getBoots();
			boolean wearing = BOOTS.matches(boots);
			ChargeState st = states.get(p.getUniqueId());
			if(!wearing) {
				if(st != null) {
					st.charging = false;
					st.sneakPrev = p.isSneaking();
				}
				continue;
			}
			if(st == null) {
				st = new ChargeState();
				st.sneakPrev = p.isSneaking();
				st.onGroundPrev = isOnGround(p);
				states.put(p.getUniqueId(), st);
			}

			boolean sneak = p.isSneaking();
			boolean onGround = isOnGround(p);

			if(st.charging) {
				if(!sneak) {
					launch(p, st.ticks);
					st.charging = false;
				} else if(!onGround) {
					st.charging = false;
				} else {
					st.ticks++;
					maybePlayNote(p, st);
				}
			} else {
				boolean sneakEdge = sneak && !st.sneakPrev;
				boolean landEdge = onGround && !st.onGroundPrev;
				if(sneak && onGround && (sneakEdge || landEdge)) {
					st.charging = true;
					st.ticks = 0;
					st.nextNoteIndex = 0;
					maybePlayNote(p, st);
				}
			}

			st.sneakPrev = sneak;
			st.onGroundPrev = onGround;
		}
	}

	private static boolean isOnGround(Player p) {
		// Server-authoritative NMS onGround(), not the deprecated client-driven Player#isOnGround().
		return ((CraftPlayer) p).getHandle().onGround();
	}

	private static void maybePlayNote(Player p, ChargeState st) {
		while(st.nextNoteIndex < NOTE_TICK.length && st.ticks >= NOTE_TICK[st.nextNoteIndex]) {
			if(st.ticks == NOTE_TICK[st.nextNoteIndex]) {
				float pitch = NOTE_PITCH[st.nextNoteIndex];
				Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
				Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, pitch);
			}
			st.nextNoteIndex++;
		}
		if(st.nextNoteIndex >= NOTE_TICK.length && st.ticks >= E5_FIRST_TICK + E5_PERIOD
				&& (st.ticks - E5_FIRST_TICK) % E5_PERIOD == 0) {
			Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, PITCH_E5);
			Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, PITCH_E5);
		}
	}

	private static void launch(Player p, int chargeTicks) {
		if(!(p instanceof CraftPlayer cp)) return;
		ServerPlayer sp = cp.getHandle();
		if(!sp.onGround()) return;

		double v = Math.min(V_CAP, 0.7 + 0.65 * Math.log(1 + chargeTicks / 2.5));
		v = (int)(v * 8000.0) / 8000.0;
		Vec3 m = sp.getDeltaMovement();
		sp.setDeltaMovement(new Vec3(m.x(), v, m.z()));
		sp.hurtMarked = true;
		Utils.playLocalSound(p, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);
		Utils.debug(Utils.DebugType.SERVER, p, p.getName() + " Spring Boots launched after " + chargeTicks + " sneak ticks with upwards velocity " + Utils.round(v, 4));
		MovementAudit.startAirborneAudit(p, "springboots");
	}

	// invert Minecraft player vertical physics. per-tick: pos += v; v -= 0.08; v *= 0.98.
	private static double heightToVelocity() {
		double lo = 0.0, hi = 4.0;
		for(int i = 0; i < 40; i++) {
			double mid = (lo + hi) / 2.0;
			if(simulatePeak(mid) < SpringBoots.MAX_HEIGHT) lo = mid;
			else hi = mid;
		}
		return (lo + hi) / 2.0;
	}

	private static double simulatePeak(double v0) {
		double y = 0, v = v0, peak = 0;
		for(int i = 0; i < 400 && v > 0; i++) {
			y += v;
			if(y > peak) peak = y;
			v = (v - 0.08) * 0.98;
		}
		return peak;
	}
}
