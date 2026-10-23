package items;

/**
 * Item with a CLICK ability, like {@code SkyBlock in Vanilla}'s {@code items/AbilityItem}. Replaced the two
 * {@code switch(id)} blocks in {@code CustomItems.handleCustomItems}.
 * <p>
 * A hook returns <b>true only if it fired</b>; that spends the cooldown, so declining costs nothing.
 * <p>
 * Click gates stay in {@code CustomItems}: spectator, trap room, drop-key overlap, two-tick right-click gate,
 * {@code lastRightClick} (drops the AIR trailing a BLOCK click), one-per-tick left-click cap. Cooldowns are in
 * {@code plugin/Cooldowns}, keyed on {@link #cooldownKey()}.
 * <p>
 * Arena/mode restrictions stay INSIDE the ability (AOTV and Tactical Insertion refuse in the boss arena in
 * adventure). Not hoisted: those still report fired, so the click is consumed and the rate gate stamped.
 */
public interface AbilityItem extends Item {

	// ===== the hooks =====

	default boolean hasRightClick() {
		return false;
	}

	default boolean hasLeftClick() {
		return false;
	}

	/** @return true if fired, which spends {@link #cooldownTicks()}. */
	default boolean onRightClick(Cast cast) {
		return false;
	}

	/** @return true if fired, which spends {@link #cooldownTicks()}. */
	default boolean onLeftClick(Cast cast) {
		return false;
	}

	// ===== cooldown =====

	/** Ticks, BEFORE Mage reduction. 0 = only the dispatcher's rate gate. */
	default int cooldownTicks() {
		return 0;
	}

	/** Takes the Mage reduction. False for Terminator and Salvation, which are WEAPONS, not abilities. */
	default boolean mageReduced() {
		return true;
	}

	/** Lore ID where there is one, so it survives renames. */
	default String cooldownKey() {
		String id = loreId();
		return id.isEmpty() ? getClass().getName() : id;
	}

	/** False for rate caps that aren't really cooldowns, or every click would spam a message. */
	default boolean announcesCooldown() {
		return true;
	}
}
