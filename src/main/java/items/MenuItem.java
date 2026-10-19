package items;

import org.bukkit.entity.Player;

/** Right-click opens a GUI: Infinileap (Spirit Leap) and SkyBlock Menu. */
public interface MenuItem extends Item {

	/** Only called once the caller has decided the menu is available. */
	void open(Player p);

	/**
	 * Suppress vanilla use outright. The Infinileap IS an ender pearl and leaps via {@code Infinileap.leap}, so it must
	 * never be thrown; {@code listeners/PearlHelper} backstops the interact cancel.
	 */
	default boolean blocksVanillaUse() {
		return true;
	}
}
