package items;

import damage.Arrows;
import damage.DungeonClass;
import damage.ItemCategory;
import damage.ItemDef;
import damage.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import plugin.Utils;

import java.util.List;

/**
 * RANGED reforge table, Duplex instead of Chimera (§7), missing most sword damage enchants.
 * <p>
 * Passives are interface CONSTANTS, not per-bow knobs. They were three copy-pasted blocks that had drifted (the
 * Terminator tested Archer with {@code startsWith}, the others with {@code contains}); {@link #onShoot} is now the
 * one shot. {@link #ARCHER_BONUS_DELAYS} is a {@code List.of} because an array constant's elements stay writable.
 */
public interface Bow extends Item {

	/**
	 * Duplex V: ONE extra arrow at x0.2 damage, {@link #DUPLEX_DELAY} ticks behind. Its own damage instance, not a
	 * multiplier. Every bow but the Death Bow (Swarm) runs it, so none carries Chimera (§7).
	 */
	double DUPLEX_SHARE = 0.2;
	int DUPLEX_DELAY = 3;

	/**
	 * Archer passive: two extra arrows at FULL damage. These never build Last Breath stacks, which is what
	 * {@link Arrows#stamp(AbstractArrow, Player, ItemStack, double, double, boolean)}'s flag is for.
	 */
	double ARCHER_BONUS_SHARE = 1.0;
	List<Integer> ARCHER_BONUS_DELAYS = List.of(5, 10);

	/** Bonus arrow speed off a SHORTBOW. A drawn bow reuses its primary's speed. */
	float SHORTBOW_SPEED = 3.175f;

	default ItemCategory category() {
		return ItemCategory.RANGED;
	}

	/** Never drawn (§1.2): every shot is full damage and crits. */
	default boolean shortbow() {
		return false;
	}

	/** Right-click starts a vanilla draw instead of firing. */
	default boolean holdToDraw() {
		return false;
	}

	/** Tags on every arrow this bow fires, primary and bonus. */
	List<String> arrowTags();

	/**
	 * Shot and Duplex arrow may build Last Breath stacks. True on every bow: the weapon still has to BE a Last
	 * Breath for a stack to land, so it's harmless on the others.
	 */
	default boolean duplexBuildsLastBreathStacks() {
		return true;
	}

	/**
	 * The one bow shot. Re-aims the vanilla primary IN PLACE (no cancel, no second entity) with the clean eye
	 * direction, stripping the random spread, then tags, stamps and fires the passives.
	 * <p>
	 * {@code aimFrom} and {@code speed} are captured once so every staggered bonus arrow leaves the same way,
	 * however the shooter has turned since.
	 *
	 * @param charge {@code min(useTicks/20, 1)}. A drawn bow scales by this and loses the whole crit term below a
	 *               full draw (§1.4), so a partial draw is much worse than the fraction suggests.
	 */
	default void onShoot(Player p, ItemStack bow, Arrow primary, double charge) {
		Location aimFrom = p.getEyeLocation().clone();
		float speed = (float) primary.getVelocity().length();
		primary.setVelocity(aimFrom.getDirection().multiply(speed));
		for(String tag : arrowTags()) primary.addScoreboardTag(tag);
		primary.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
		Arrows.stamp(primary, p, bow, charge, 1.0);
		fireBonusArrows(p, aimFrom, bow, speed, charge);
	}

	/**
	 * Defaults to the deterministic spawner, so drawn bows' bonus arrows have no spread either. The Terminator
	 * overrides it for its own muzzle and pierce.
	 */
	default Arrow spawnBonusArrow(Player p, Location aimFrom, ItemStack bow, float speed) {
		return fireDeterministicArrow(p, aimFrom, speed, 1.0);
	}

	/** Drawn bows pitch the Archer's two to 1.2 at the shooter; the Terminator plays each locally at 1.0. */
	default void bonusArrowSound(Player p, boolean archerBonus) {
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0F, archerBonus ? 1.2F : 1.0F);
	}

	/** Remove a bonus arrow spawned inside a solid block a tick later (Terminator's rule). */
	default boolean removesArrowInSolid() {
		return false;
	}

	/** Duplex arrow unless the bow runs Swarm (Death Bow), plus the two Archer arrows for an Archer. */
	default void fireBonusArrows(Player p, Location aimFrom, ItemStack bow, float speed, double charge) {
		ItemDef def = Items.of(bow);
		if(def == null || !def.swarm()) {
			Utils.scheduleTask(() -> fireBonusArrow(p, aimFrom, bow, speed, charge, DUPLEX_SHARE,
					duplexBuildsLastBreathStacks(), false), DUPLEX_DELAY);
		}
		if(DungeonClass.of(p) != DungeonClass.ARCHER) return;
		for(int delay : ARCHER_BONUS_DELAYS) {
			Utils.scheduleTask(() -> fireBonusArrow(p, aimFrom, bow, speed, charge, ARCHER_BONUS_SHARE,
					false, true), delay);
		}
	}

	/** Spawn, tag, stamp damage (§1.0.5), sound. */
	private void fireBonusArrow(Player p, Location aimFrom, ItemStack bow, float speed, double charge,
			double share, boolean buildsLastBreath, boolean archerBonus) {
		Arrow arrow = spawnBonusArrow(p, aimFrom, bow, speed);
		for(String tag : arrowTags()) arrow.addScoreboardTag(tag);
		Arrows.stamp(arrow, p, bow, charge, share, buildsLastBreath);
		bonusArrowSound(p, archerBonus);
		if(removesArrowInSolid()) {
			Utils.scheduleTask(() -> {
				if(arrow.isValid() && arrow.getLocation().getBlock().getType().isSolid()) arrow.remove();
			}, 1);
		}
	}

	/**
	 * Arrow with no random spread. {@code launchProjectile} and the vanilla bow go through
	 * {@code shootFromRotation} with inaccuracy 1.0F; this uses NMS {@code shoot(..., 0)}, like
	 * {@code items.bows.Terminator}'s shotgun arrows.
	 * <br>
	 * Spawn (with vanilla's -0.1 Y) and direction both come from {@code aimFrom}, so delayed bonus arrows reuse the
	 * fire-time aim rather than where the shooter moved.
	 */
	private static Arrow fireDeterministicArrow(Player p, Location aimFrom, float speed, double damage) {
		ServerLevel nmsWorld = ((CraftWorld) p.getWorld()).getHandle();
		ServerPlayer nmsPlayer = ((CraftPlayer) p).getHandle();
		Vector dir = aimFrom.getDirection();

		net.minecraft.world.entity.projectile.arrow.Arrow nmsArrow = new net.minecraft.world.entity.projectile.arrow.Arrow(
				nmsWorld, 0, 0, 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
		nmsArrow.setPos(aimFrom.getX(), aimFrom.getY() - 0.1, aimFrom.getZ());
		nmsArrow.shoot(dir.getX(), dir.getY(), dir.getZ(), speed, 0);
		nmsArrow.setOwner(nmsPlayer);
		nmsWorld.addFreshEntity(nmsArrow);

		Arrow arrow = (Arrow) nmsArrow.getBukkitEntity();
		arrow.setDamage(damage);
		arrow.setShooter(p);
		arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
		arrow.setWeapon(p.getInventory().getItemInMainHand());
		return arrow;
	}
}
