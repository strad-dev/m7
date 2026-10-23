package items.tools;

import damage.Rarity;
import damage.ReforgeId;
import items.AbilityItem;
import items.Cast;
import items.ItemFactory;
import listeners.LavaJump;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import plugin.Utils;

/**
 * Marks the caster's position and returns them to it 3s later. Like AOTV, the boss-arena adventure refusal stays
 * INSIDE the ability so the click is still consumed.
 */
public final class TacticalInsertion implements AbilityItem {
	public static final TacticalInsertion INSTANCE = new TacticalInsertion();

	private TacticalInsertion() {}

	@Override
	public String loreId() {
		return "skyblock/combat/tac";
	}

	@Override
	public Material material() {
		return Material.BLAZE_ROD;
	}

	@Override
	public String baseName() {
		return "Tactical Insertion";
	}

	@Override
	public Rarity baseRarity() {
		return Rarity.EPIC;
	}

	@Override
	public ReforgeId defaultReforge() {
		return ReforgeId.NONE;
	}

	@Override
	public ItemStack build(ReforgeId reforge) {
		return ItemFactory.item(material(), colouredName(reforge), loreId(), "TACTICAL_INSERTION");
	}

	@Override
	public boolean hasRightClick() {
		return true;
	}

	@Override
	public int cooldownTicks() {
		return 400; // 20s
	}

	@Override
	public boolean onRightClick(Cast cast) {
		tac(cast.player());
		return true;
	}

	public static void tac(Player p) {
		// Disabled only in the boss arena in adventure (practice default), so it can't cheat boss mechanics.
		if(p.getGameMode() == org.bukkit.GameMode.ADVENTURE && LavaJump.isInBossArena(p.getLocation())) return;
		Location l = p.getLocation();
		Utils.debug(Utils.DebugType.SERVER, p, "Activating Tactical Insertion at " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
		Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 0.707107F);
		Utils.playLocalSound(p, Sound.ITEM_FLINTANDSTEEL_USE, 1.0F, 1.0F);
		Utils.scheduleTask(() -> Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 0.793701F), 10);
		Utils.scheduleTask(() -> Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 0.890899F), 20);
		Utils.scheduleTask(() -> Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 0.943874F), 30);
		Utils.scheduleTask(() -> Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 1F), 40);
		Utils.scheduleTask(() -> Utils.playLocalSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 1.059463F), 50);
		Utils.scheduleTask(() -> {
			Utils.playLocalSound(p, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0F, 1.0F);
			p.teleport(l);
			Utils.debug(Utils.DebugType.SERVER, p, "Returning " + p.getName() + " to " + Utils.round(l.getX(), 3) + " " + Utils.round(l.getY(), 5) + " " + Utils.round(l.getZ(), 3));
			p.setVelocity(new Vector(0, 0, 0));
			Utils.scheduleTask(() -> p.getWorld().spawnParticle(Particle.FLAME, p.getLocation(), 128), 1);
		}, 60);
		Utils.scheduleTask(() -> Utils.playLocalSound(p, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0F, 1.0F), 63);
		Utils.scheduleTask(() -> Utils.playLocalSound(p, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0F, 1.0F), 66);
	}
}
