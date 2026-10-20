package listeners;

import instructions.clear.ClearManager;
import instructions.clear.PuzzleQuiz;
import instructions.clear.Rooms;
import instructions.clear.Secret;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import plugin.Utils;

/**
 * Clear-phase interactions: chests, essence, Quiz, Wizard crystal hand-in, and miniboss / bat / crypt / mimic / Wizard mob deaths
 * into {@link ClearManager}. Every handler is gated on {@link ClearManager#isActive()}.
 */
public class ClearListener implements Listener {

	// Wizard's crystal ball; right-click picks up, ±1 block tolerance.
	private static final int[] CRYSTAL = {-100, 63, -111};

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDeath(EntityDeathEvent e) {
		if(!ClearManager.isActive()) return;
		if(instructions.Server.isCleanupInProgress()) return; // cleanup purges must not count as kills
		LivingEntity ent = e.getEntity();
		Location loc = ent.getLocation();
		Player killer = ent.getKiller() != null ? ent.getKiller() : ClearManager.nearestRealPlayer(loc);

		if(ent.getScoreboardTags().contains("ClearMiniboss")) {
			ClearManager.minibossKilled(Rooms.roomAt(loc), killer);
		}
		if(ent.getScoreboardTags().contains(ClearManager.TAG_BAT)) {
			ClearManager.noteBatKill();
			ClearManager.secretFound(killer, ClearManager.findSecretByEntity(ent.getUniqueId()));
			ent.remove(); // drop the hitbox now, no dying-bat corpse
		}
		if(ent.getScoreboardTags().contains(ClearManager.TAG_CRYPT)) {
			ClearManager.cryptKilled(ent.getScoreboardTags().contains("SecretPrince"));
		}
		if(ent.getScoreboardTags().contains(ClearManager.TAG_WIZARD_MOB)) {
			ClearManager.wizardMobKilled(ent.getUniqueId());
		}
		if(ent.getScoreboardTags().contains(ClearManager.TAG_MIMIC)) {
			ClearManager.mimicKilledEvent(killer, ClearManager.findSecretByEntity(ent.getUniqueId()));
		}
	}

	/**
	 * True if {@code p} may not progress the clear: a spectator (idle on m7, watching someone's run), or a click
	 * that just teleported them ({@code CustomItems.takeClickTeleport}). Shared predicate {@link Utils#isSpectator},
	 * like {@code GoldorListener.cannotSolve}.
	 * <p>
	 * Not redundant with vanilla, and placement matters. {@code ServerPlayerGameMode.useItemOn} no-ops a spectator's
	 * click only when the block has no {@code MenuProvider}; a CHEST has one, so a secret chest fires
	 * {@link PlayerInteractEvent} with use ALLOWED and vanilla opens the GUI. Other clear blocks arrive pre-cancelled,
	 * but this handler doesn't {@code ignoreCancelled}, so a spectator could take secrets, essence and the Quiz for
	 * the party. So: cancel FIRST (shuts the chest GUI), then check this.
	 */
	private static boolean cannotInteract(Player p) {
		return Utils.isSpectator(p) || CustomItems.takeClickTeleport(p);
	}

	@EventHandler
	public void onRightClickBlock(PlayerInteractEvent e) {
		if(!ClearManager.isActive()) return;
		if(e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		Block b = e.getClickedBlock();
		if(b == null) return;
		Player p = e.getPlayer();

		int btn = PuzzleQuiz.buttonIndex(b);
		if(btn >= 0) {
			e.setCancelled(true);
			if(cannotInteract(p)) return;
			PuzzleQuiz.answer(p, btn);
			return;
		}
		// Crystal ball
		if(Math.abs(b.getX() - CRYSTAL[0]) <= 1 && Math.abs(b.getY() - CRYSTAL[1]) <= 1 && Math.abs(b.getZ() - CRYSTAL[2]) <= 1) {
			e.setCancelled(true);
			if(cannotInteract(p)) return;
			ClearManager.pickUpCrystal(p);
			return;
		}
		// Secret chests (no GUI) and essence skulls.
		Secret s = ClearManager.findSecretAtBlock(b.getX(), b.getY(), b.getZ());
		if(s != null) {
			// ALWAYS cancel, so an opened chest never shows the GUI and a spectator (uncancelled here, see
			// cannotInteract) never gets it.
			e.setCancelled(true);
			if(cannotInteract(p)) return;
			if(!s.found) {
				if(s.isChest()) ClearManager.openChest(p, s);
				else ClearManager.secretFound(p, s); // essence
			}
		}
	}

	// Wizard hand-in via right-click. NOT ignoreCancelled: MiscListener cancels villager right-clicks at LOWEST to
	// block the trade GUI.
	@EventHandler(priority = EventPriority.NORMAL)
	public void onRightClickEntity(PlayerInteractEntityEvent e) {
		if(!ClearManager.isActive()) return;
		if(cannotInteract(e.getPlayer())) return; // a spectator can't hand the crystal in either
		if(e.getRightClicked() instanceof Villager v && ClearManager.hasCrystal() && isWizard(v)) {
			ClearManager.handInCrystal(e.getPlayer());
		}
	}

	// Wizard hand-in via left-click. PrePlayerAttackEntityEvent fires on the attack itself, so it works though the
	// Wizard takes no damage.
	@EventHandler(priority = EventPriority.NORMAL)
	public void onLeftClickEntity(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
		if(!ClearManager.isActive()) return;
		if(cannotInteract(e.getPlayer())) return;
		if(e.getAttacked() instanceof Villager v && ClearManager.hasCrystal() && isWizard(v)) {
			ClearManager.handInCrystal(e.getPlayer());
		}
	}

	/** The villager in the Wizard room, by room so it survives map re-labels. */
	private static boolean isWizard(Villager v) {
		String name = Utils.plain(v.customName());
		return Rooms.roomAt(v.getLocation()) == Rooms.WIZARD || name.contains("Wizard");
	}
}
