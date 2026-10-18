package instructions.bosses.goldor;

import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import plugin.Utils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** One Goldor section gate (S1→S2, S2→S3, S3→S4). */
public final class GoldorGate {
	/** Ticks from blocks removed to blocks back ({@link #removeBlocksNow}). */
	private static final long REGEN_TICKS = 200L;

	private final World world;
	/** 0=S1, 1=S2, 2=S3. Reported to Goldor on destruction. */
	private final int sectionIdx;
	private final BoundingBox bounds;
	private final BoundingBox expandedBounds;
	private final Map<Location, BlockData> snapshot = new HashMap<>();

	/** Goldor-relative tick this gate's section became active (0 for S1, set by Goldor for S2/S3). */
	private int sectionStartTick = 0;

	private boolean sectionComplete = false;
	private boolean explosionMarked = false;
	private boolean blocksRemoved = false;
	/** "Gate destroyed" broadcast has fired, at early explosion or block removal. */
	private boolean destroyedAnnounced = false;
	private BukkitTask pendingDelayedRemoval;
	private BukkitTask pendingRegen;

	public GoldorGate(World world, int sectionIdx, BoundingBox bounds) {
		this.world = world;
		this.sectionIdx = sectionIdx;
		this.bounds = bounds;
		this.expandedBounds = bounds.clone().expand(1.0, 1.0, 1.0);
		snapshotBlocks();
	}

	private void snapshotBlocks() {
		// Box max is exclusive (Goldor.makeBox stores blockMax+1), so strict <. <= grabbed an extra layer on every max side.
		int minX = (int) Math.floor(bounds.getMinX());
		int minY = (int) Math.floor(bounds.getMinY());
		int minZ = (int) Math.floor(bounds.getMinZ());
		int maxX = (int) Math.floor(bounds.getMaxX());
		int maxY = (int) Math.floor(bounds.getMaxY());
		int maxZ = (int) Math.floor(bounds.getMaxZ());
		for(int x = minX; x < maxX; x++) {
			for(int y = minY; y < maxY; y++) {
				for(int z = minZ; z < maxZ; z++) {
					Block b = world.getBlockAt(x, y, z);
					if(b.getType() != Material.AIR) {
						snapshot.put(b.getLocation(), b.getBlockData().clone());
					}
				}
			}
		}
	}

	public BoundingBox getExpandedBounds() {
		return expandedBounds;
	}

	/** So the "Gate destroyed" line reports ticks since THIS gate's section began, not the current section's. */
	public void setSectionStartTick(int t) {
		this.sectionStartTick = t;
	}

	/** Explosion landed on/near this gate. */
	public void onExplosion() {
		if(blocksRemoved) return;
		if(sectionComplete) {
			// Section done, gate was in its 100t auto-destruct window: skip the wait.
			removeBlocksNow();
		} else {
			// Pre-completion hit: gate stays up but "destroyed" is announced now (per user spec).
			if(!explosionMarked) {
				explosionMarked = true;
				announceDestroyed();
			}
		}
	}

	/** This gate's section just completed. */
	public void onSectionComplete() {
		if(sectionComplete) return;
		sectionComplete = true;
		if(explosionMarked) {
			removeBlocksNow();
		} else {
			// Not blown early, so it auto-destructs 100t later, matching Hypixel's "The gate will open in 5 seconds!".
			Bukkit.broadcast(Utils.msg("<green>The gate will open in 5 seconds!"));
			pendingDelayedRemoval = Bukkit.getScheduler().runTaskLater(plugin.M7.getInstance(), () -> {
				if(!blocksRemoved) removeBlocksNow();
			}, 100L);
		}
	}

	private void removeBlocksNow() {
		if(blocksRemoved) return;
		blocksRemoved = true;
		if(pendingDelayedRemoval != null && !pendingDelayedRemoval.isCancelled()) {
			pendingDelayedRemoval.cancel();
		}
		for(Location loc : snapshot.keySet()) {
			loc.getBlock().setType(Material.AIR, false);
		}
		// No-op if the early-explosion path already announced.
		announceDestroyed();
		// The true section-complete moment: Goldor reports the section timing and advances.
		Goldor.INSTANCE.onGateDestroyed(sectionIdx);
		pendingRegen = Bukkit.getScheduler().runTaskLater(plugin.M7.getInstance(), this::regenerate, REGEN_TICKS);
	}

	private void announceDestroyed() {
		if(destroyedAnnounced) return;
		destroyedAnnounced = true;
		broadcastDestroyed();
	}

	private void regenerate() {
		for(Map.Entry<Location, BlockData> entry : snapshot.entrySet()) {
			entry.getKey().getBlock().setBlockData(entry.getValue(), false);
		}
		blocksRemoved = false;
		explosionMarked = false;
		destroyedAnnounced = false;
	}

	private void broadcastDestroyed() {
		String msg = "<green>The gate has been destroyed!";
		Bukkit.broadcast(Utils.msg(msg));
		for(Player pl : Bukkit.getOnlinePlayers()) {
			pl.showTitle(Title.title(Utils.msg(""), Utils.msg(msg),
					Title.Times.times(Duration.ofMillis(0L), Duration.ofMillis(40 * 50L), Duration.ofMillis(0L))));
		}
		Utils.playGlobalSound(Sound.BLOCK_NOTE_BLOCK_PLING, 2.0F, 2.0F);
		Utils.timer(Goldor.INSTANCE.gateDestroyedLine(sectionStartTick));
	}

	/** Called from resetState(): restore blocks and cancel pending tasks. */
	public void cleanup() {
		if(pendingDelayedRemoval != null && !pendingDelayedRemoval.isCancelled()) pendingDelayedRemoval.cancel();
		if(pendingRegen != null && !pendingRegen.isCancelled()) pendingRegen.cancel();
		if(blocksRemoved) regenerate();
	}
}
