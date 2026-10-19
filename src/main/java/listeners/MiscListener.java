package listeners;

import instructions.Server;
import instructions.bosses.Watcher;
import instructions.bosses.WitherActions;
import instructions.bosses.maxor.Maxor;
import instructions.bosses.witherking.WitherKing;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import plugin.MovementAudit;
import plugin.Utils;

public class MiscListener implements Listener {
	@EventHandler
	public void onStoneButtonInArena(PlayerInteractEvent e) {
		if(e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		if(e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.STONE_BUTTON) return;
		if(LavaJump.isInBossArena(e.getClickedBlock().getLocation())) {
			e.setCancelled(true);
		}
	}

	// Anvils never open their menu. Only the block use is denied; item use in hand still works.
	@EventHandler
	public void onAnvilInteract(PlayerInteractEvent e) {
		if(e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		Block b = e.getClickedBlock();
		if(b == null) return;
		Material m = b.getType();
		if(m == Material.ANVIL || m == Material.CHIPPED_ANVIL || m == Material.DAMAGED_ANVIL) {
			e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
		}
	}

	// The Watcher is never damaged; you kill its 19 blood mobs. RESISTANCE 255 doesn't stop our damage path, so
	// Damage.deal refuses a TASWatcher and this cancels all vanilla damage.
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onWatcherDamage(EntityDamageEvent e) {
		if(e.getEntity().getScoreboardTags().contains("TASWatcher")) {
			e.setCancelled(true);
		}
	}

	// Key archaeologist deaths grant the global Wither / Blood keys (door gates).
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onKeyMobDeath(EntityDeathEvent e) {
		if(Server.isCleanupInProgress()) return; // a cleanup purge must not grant keys
		boolean witherMob = e.getEntity().getScoreboardTags().contains("WitherKeyMob");
		boolean bloodMob = e.getEntity().getScoreboardTags().contains("BloodKeyMob");
		if(!witherMob && !bloodMob) return;
		// Key goes to (and message names) the nearest non-spectator.
		Player picker = null;
		double best = Double.MAX_VALUE;
		Location deathLoc = e.getEntity().getLocation();
		for(Player pl : Bukkit.getOnlinePlayers()) {
			if(Utils.isSpectator(pl)) continue;
			double d = pl.getLocation().distanceSquared(deathLoc);
			if(d < best) { best = d; picker = pl; }
		}
		if(witherMob) Server.grantWitherKey(picker);
		else Server.grantBloodKey(picker);
	}

	// Clicking a Wither/Blood door opens it if the key is obtained. The click is always cancelled so the block
	// can't be broken. Exception: before the run starts, a stonk click passes through so the break reaches
	// CustomItems.onBlockBreak, which only protects doors once the run is live.
	@EventHandler
	public void onDoorClick(PlayerInteractEvent e) {
		if(e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
		Block b = e.getClickedBlock();
		if(b == null) return;
		if(!Server.inWitherDoor(b) && !Server.inBloodDoor(b)) return;
		if(!Server.isRunStarted() && items.ItemUtils.getID(e.getPlayer().getInventory().getItemInMainHand()).equals("skyblock/combat/stonk")) return;
		// Spectator clicks are consumed, never open anything. The key check is team-wide, so otherwise an idle
		// watcher could open a door for the party (and the blood door starts the Watcher).
		if(Utils.isSpectator(e.getPlayer())) {
			e.setCancelled(true);
			return;
		}
		if(Server.inWitherDoor(b)) {
			e.setCancelled(true);
			Server.tryOpenWitherDoor(e.getPlayer());
		} else {
			e.setCancelled(true);
			Server.tryOpenBloodDoor();
		}
	}

	// No vanilla pearls in the boss arena (no pearl skips). Infinileap is exempt.
	@EventHandler
	public void onPearlInBossArena(PlayerInteractEvent e) {
		if(e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		ItemStack item = e.getItem();
		if(item == null || item.getType() != Material.ENDER_PEARL) return;
		if(items.ItemUtils.getID(item).equals("skyblock/utility/infinileap")) return; // leap ability is allowed
		if(LavaJump.isInBossArena(e.getPlayer().getLocation())) e.setCancelled(true);
	}

	// Chat handler removed: the network chat plugin owns chat, and both would double-broadcast.

	@EventHandler
	public void onEntityExplode(EntityExplodeEvent e) {
		if(e.getEntity() instanceof WindCharge windCharge && windCharge.getScoreboardTags().contains("Bonzo")) {
			e.setCancelled(true);
		}
	}

	/**
	 * Bonzo charges can't be punched. {@code BonzoStaff.makeUndeflectable} stops the server redirecting a
	 * {@code minecraft:redirectable_projectile}, but that flag isn't synched, so the puncher's client still
	 * predicts the deflection. Refuse the attack and send the charge's real position + motion to that player only,
	 * immediately: waiting for the tracker costs a visible tick.
	 */
	@EventHandler
	public void onPunchBonzoCharge(PrePlayerAttackEntityEvent e) {
		if(!(e.getAttacked() instanceof WindCharge windCharge)) return;
		if(!windCharge.getScoreboardTags().contains("Bonzo")) return;
		e.setCancelled(true);
		if(!(e.getPlayer() instanceof CraftPlayer cp)) return;
		net.minecraft.world.entity.Entity nms = ((CraftEntity) windCharge).getHandle();
		cp.getHandle().connection.send(ClientboundEntityPositionSyncPacket.of(nms));
		cp.getHandle().connection.send(new ClientboundSetEntityMotionPacket(nms));
	}

	@EventHandler
	public void onEntitySpawn(EntitySpawnEvent e) {
		// Every entity joins the no-collision team so nothing pushes. NOT setCollidable(false): that makes
		// canBeCollidedWith() false, so arrows phase through. The team keeps it arrow-hittable.
		plugin.PlayerCollision.addEntityToNoCollisionTeam(e.getEntity());
	}

	// Counterpart to onEntitySpawn's add, so the team doesn't grow unbounded.
	@EventHandler
	public void onEntityRemove(EntityRemoveEvent e) {
		plugin.PlayerCollision.removeEntityFromNoCollisionTeam(e.getEntity());
	}

	// 26.2: Paper's EntityKnockbackEvent. The by-entity case is the subclass EntityPushedByEntityAttackEvent
	// (same HandlerList); getPushedBy() replaces EntityKnockbackByEntityEvent#getSourceEntity().
	@EventHandler
	public void onKnockback(EntityKnockbackEvent e) {
		// Bonzo's charge doesn't knock back who it hits.
		if(e instanceof EntityPushedByEntityAttackEvent pushed
				&& pushed.getPushedBy() instanceof WindCharge windCharge
				&& windCharge.getScoreboardTags().contains("Bonzo")) {
			e.setCancelled(true);
		}
	}

	@EventHandler
	public void onProjectileHit(ProjectileHitEvent e) {
		// Handle arrows hitting blocks, and remove Terminator arrows
		if(e.getEntity() instanceof Arrow arrow) {
			// Super-verbose: log Last Breath hits on a boss (wither, or WK dragon via its EnderDragonPart parent)
			// to correlate with the vulnerability window. Uses the event's hit ref, so it's right even if
			// WithersNotImmuneToArrows (LOWEST) already removed the arrow.
			if(Utils.isSuperVerbose() && arrow.getScoreboardTags().contains("LastBreathArrow")) {
				Entity rawHit = e.getHitEntity();
				Entity boss = rawHit instanceof Wither || rawHit instanceof EnderDragon ? rawHit
						: rawHit instanceof EnderDragonPart part ? part.getParent() : null;
				if(boss != null) {
					String shooter = arrow.getShooter() instanceof Player sp ? sp.getName() : "?";
					Utils.debug(Utils.DebugType.SERVER, "Last Breath arrow (" + shooter + ") hit " + boss.getName());
				}
			}
			if(e.getHitBlock() != null) {
				if(arrow.getScoreboardTags().contains("TerminatorArrow")) {
					arrow.remove();
				}
			} else if(e.getHitEntity() != null) {
				// Resolve EnderDragonPart to its parent EnderDragon (EnderDragonPart is not a LivingEntity)
				Entity rawHit = e.getHitEntity();

				// Phase through falling blocks. They're legal arrow targets, so a Gyrokinetic Wand's 64-block swarm
				// eats a pierce level per block (pierce 4 dies after 5). Cancelling before onHitEntity keeps the pierce.
				if(rawHit instanceof FallingBlock && arrow.getScoreboardTags().contains("TerminatorArrow")) {
					e.setCancelled(true);
					return;
				}

				LivingEntity hitEntity = rawHit instanceof LivingEntity le ? le
						: rawHit instanceof EnderDragonPart part ? part.getParent() : null;
				if(hitEntity == null) return;

				// Phase through all players; an arrow must never hit one (bypasses Creative invulnerability via
				// genericKill).
				if(hitEntity instanceof Player) {
					e.setCancelled(true);
				}
				// Our arrows: cancel to keep pierce, apply damage ourselves. Withers are WithersNotImmuneToArrows'.
				// Gated on "we stamped it", not a tag, so it covers Terminator, Last Breath, Explosive Bow and Rapid
				// Fire and never a vanilla arrow.
				else if(damage.Arrows.isStamped(arrow) && arrow.getShooter() instanceof Player p && !(hitEntity instanceof Wither)) {
					e.setCancelled(true);
					// Read dead/dying BEFORE damage: a killing blow dings, a hit on an already-dead target doesn't. A
					// dying WK dragon keeps HP at 1, so check WitherKing's dying set too.
					boolean targetDead = hitEntity.isDead() || hitEntity.getHealth() <= 0
							|| hitEntity.getScoreboardTags().contains("TASDying") || WitherKing.isDyingDragon(hitEntity);
					// Arrows.hit resolves debuffs, the target half and Piercing's 25% past the first mob, then deals
					// it (DERIVED entry for Rapid Fire, so it can't read its own output back).
					damage.Arrows.hit(arrow, p, hitEntity);
					if(!targetDead) Utils.playLocalSound(p, Sound.ENTITY_ARROW_HIT_PLAYER, 0.75f, 0.79368752611448590621283707774885f);
					int newPierce = arrow.getPierceLevel() - 1;
					if(newPierce <= 0) arrow.remove();
					else arrow.setPierceLevel(newPierce);
				}
			}
		} else if(e.getEntity() instanceof WindCharge windCharge && windCharge.getScoreboardTags().contains("Bonzo")) {
			e.setCancelled(true);
			windCharge.remove();

			if(windCharge.getShooter() instanceof Player p) {
				double distance = p.getLocation().distanceSquared(windCharge.getLocation());
				p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, windCharge.getLocation(), 64, 0, 0, 0, 0.75);
				p.getWorld().spawnParticle(Particle.CRIT, windCharge.getLocation(), 32, 0, 0, 0, 2);
				p.getWorld().playSound(windCharge.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0F, 1.0F);

				if(distance <= 12.25) {
					if(!(p instanceof CraftPlayer craftPlayer)) return;
					ServerPlayer serverPlayer = craftPlayer.getHandle();

					Vector direction = p.getLocation().toVector().subtract(windCharge.getLocation().toVector()).normalize();
					direction.setY(0);
					direction.normalize();
					direction.multiply(1.52552);
					direction.setY(0.5);

					if(!Double.isFinite(direction.getX())) {
						direction.setX(0);
					}
					if(!Double.isFinite(direction.getZ())) {
						direction.setZ(0);
					}

					serverPlayer.setOnGround(false);
					p.setVelocity(direction);
					// Send motion now, not via hurtMarked next aiStep: that's a tick late and loses the first-tick
					// rise. Immediate matches Hypixel, full 0.5 on tick 1.
					serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
					serverPlayer.hurtMarked = false;
					Integer fireTick = items.combat.BonzoStaff.bonzoFireTick.remove(windCharge.getEntityId());
					int travelTicks = fireTick != null ? MinecraftServer.currentTick - fireTick : -1;
					Location loc = p.getLocation();
					Utils.debug(Utils.DebugType.SERVER, p.getName() + " bonzostaff launched from " + Utils.round(loc.getX(), 2) + " " + Utils.round(loc.getY(), 2) + " " + Utils.round(loc.getZ(), 2) + " with velocity " + Utils.round(direction.getX(), 4) + " " + Utils.round(direction.getY(), 4) + " " + Utils.round(direction.getZ(), 4) + " after " + travelTicks + " ticks");
					MovementAudit.startAirborneAudit(p, "bonzostaff");
				}
			}
		}
	}

	// Any game-mode change in a run costs the gold scoreboard name (minor anticheat). Except death/revival flips,
	// which death.Deaths announces ahead and this consumes; a /gamemode still costs it.
	@EventHandler
	public void onGameModeChange(PlayerGameModeChangeEvent e) {
		if(death.Deaths.ownsGameModeChange(e.getPlayer().getUniqueId())) return;
		WitherActions.noteGameModeChange(e.getPlayer().getUniqueId());
	}

	/**
	 * Soul sand is the deleted lava-jump block; any left must not be placed.
	 * <p><b>Cancel, never place-then-revert.</b> Reverting acks a SUCCESS and corrects a tick + round trip later, so
	 * on a lava MLG the client stands on a deleted block over lava: StradDevHub's Jesus check. At 84 ms that's the
	 * three ticks it needs (two flags, two kills, innocent player). Cancelling acks a failure, so no ghost and no
	 * refund. Same as {@code CustomItems.onCustomBlockPlace}.
	 */
	@EventHandler
	public void onBlockPlace(BlockPlaceEvent e) {
		if(e.getBlockPlaced().getType() != Material.SOUL_SAND) return;
		// Only creative places freely (building).
		GameMode gm = e.getPlayer().getGameMode();
		if(gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return;
		if(!LavaJump.isInBossArena(e.getBlockPlaced().getLocation())) return;
		e.setCancelled(true);
		e.getPlayer().updateInventory(); // client predicted the placement; resend the stack so it won't ghost
	}

	@EventHandler
	public void onEnderCrystalDamage(EntityDamageEvent e) {
		if(e.getEntity() instanceof EnderCrystal) {
			e.setCancelled(true);
		}
	}

	/**
	 * Our boss hits go through {@code damage.Damage.deal} (writes health, calls {@code clampDamage}), so any
	 * {@code EntityDamageEvent} on a TASWither isn't ours (explosion, fire, fall): cancel it so it can't skip the
	 * clamps. Replaced four {@code handleDamage} interceptors (MAP.md §7).
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onWitherLordDamage(EntityDamageEvent e) {
		if(e.getEntity() instanceof Wither w && w.getScoreboardTags().contains("TASWither")) {
			e.setCancelled(true);
		}
	}

	// onBossDamager (MONITOR, no ignoreCancelled, made any player damager a TASWither's aggro target) is DELETED.
	// Aggro now needs the hit to take health (Damage.deal), or one of three abilities that note it through a full
	// shield (mage beam, thrown axe, Flaming Flay). It fired on every cancelled vanilla swing and undid that rule.

	// Blood-Mob deaths drive the Watcher's kill lines + portal progression.
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onWatcherMobDeath(EntityDeathEvent e) {
		Watcher.INSTANCE.handleMobDeath(e);
	}

	// The Watcher's portal is a teleport trigger handled by our own detection; vanilla must not send anyone to the Nether.
	@EventHandler(ignoreCancelled = true)
	public void onWatcherPortal(PlayerPortalEvent e) {
		if(e.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
			e.setCancelled(true);
		}
	}

	// Runners stack on each other, so no player-vs-player hits. Projectiles are unwrapped too, or Archer arrows land.
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onPlayerVsPlayer(EntityDamageByEntityEvent e) {
		if(!(e.getEntity() instanceof Player)) return;
		if(e.getDamager() instanceof Player
				|| (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player)) {
			e.setCancelled(true);
		}
	}

	// Fire resistance stops damage but players still visibly burn, so cancel combustion for all players.
	// Server half only: the client predicts lava ignition, so BURNING_TIME = 0 in JoinListener.applyPlayerSetup
	// does the real work. This stays since it stops the burn before NMS asks the attribute, and the bypass paths
	// (lavaIgnite when remainingFireTicks > 0) need the player already lit.
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onPlayerCombust(EntityCombustEvent e) {
		if(e.getEntity() instanceof Player) {
			e.setCancelled(true);
		}
	}

	// Players never get the vanilla WITHER effect (from bosses, skulls, skeletons). Only ADDED/CHANGED are
	// blocked so removals still work.
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onWitherEffect(EntityPotionEffectEvent e) {
		if(e.getEntity() instanceof Player
				&& e.getModifiedType() == PotionEffectType.WITHER
				&& (e.getAction() == EntityPotionEffectEvent.Action.ADDED
					|| e.getAction() == EntityPotionEffectEvent.Action.CHANGED)) {
			e.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onWitherSuffocation(EntityDamageEvent e) {
		if(e.getEntity() instanceof Wither && e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
			e.setCancelled(true);
		}
	}

	// Refresh the HP nametag; MONITOR + 1 tick so it reads post-damage HP.
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onWitherDamageNameRefresh(EntityDamageEvent e) {
		if(!(e.getEntity() instanceof Wither wither)) return;
		if(e.getFinalDamage() <= 0) return;
		Utils.scheduleTask(() -> { if(wither.isValid()) Utils.changeName(wither); }, 1);
	}

	// Wither hurt sound moved to damage/Damage.witherHurtSound, since the unified damage path (MAP.md §7) no
	// longer fires EntityDamageEvent.

	@EventHandler
	public void onEnergyCrystalRightClick(PlayerInteractAtEntityEvent e) {
		if(!(e.getRightClicked() instanceof EnderCrystal crystal)) return;
		if(Maxor.INSTANCE.notEnergyCrystal(crystal)) return;
		e.setCancelled(true);
		Maxor.INSTANCE.pickUp(e.getPlayer(), crystal);
	}

	// Attacking a pickupable crystal collects it too (it takes no damage, see onEnderCrystalDamage).
	@EventHandler
	public void onEnergyCrystalLeftClick(EntityDamageByEntityEvent e) {
		if(!(e.getEntity() instanceof EnderCrystal crystal)) return;
		if(!(e.getDamager() instanceof Player p)) return;
		if(Maxor.INSTANCE.notEnergyCrystal(crystal)) return;
		e.setCancelled(true);
		Maxor.INSTANCE.pickUp(p, crystal);
	}

	@EventHandler
	public void onPlatePromptRightClick(PlayerInteractAtEntityEvent e) {
		if(!(e.getRightClicked() instanceof Interaction)) return;
		if(Maxor.INSTANCE.clickPlatePrompt(e.getPlayer(), e.getRightClicked())) e.setCancelled(true);
	}

	@EventHandler
	public void onPlatePromptLeftClick(EntityDamageByEntityEvent e) {
		if(!(e.getEntity() instanceof Interaction)) return;
		if(!(e.getDamager() instanceof Player p)) return;
		if(Maxor.INSTANCE.clickPlatePrompt(p, e.getEntity())) e.setCancelled(true);
	}

	// Mort and the Wizard are villagers, so block right-clicks and the vanilla trade GUI won't open.
	@EventHandler(priority = EventPriority.LOWEST)
	public void onVillagerInteract(PlayerInteractEntityEvent e) {
		if(e.getRightClicked() instanceof Villager) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onVillagerInteractAt(PlayerInteractAtEntityEvent e) {
		if(e.getRightClicked() instanceof Villager) e.setCancelled(true);
	}

	// Mort and the Wizard are invulnerable to everything except /kill (DamageCause.KILL). Damage.deal refuses
	// villagers outright, so a KILL hit can only be a real /kill. LOWEST so nothing else acts on the hit.
	@EventHandler(priority = EventPriority.LOWEST)
	public void onVillagerDamage(EntityDamageEvent e) {
		if(!(e.getEntity() instanceof Villager)) return;
		if(e.getCause() == EntityDamageEvent.DamageCause.KILL) return; // /kill still removes them
		e.setCancelled(true);
	}

	@EventHandler
	public void onPlayerPickupArrow(PlayerPickupArrowEvent e) {
		if(e.getArrow().getScoreboardTags().contains("TerminatorArrow")) {
			e.setCancelled(true);
		}
	}

}