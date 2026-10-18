package plugin;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a /m7practice run finishes: boss defeated (Wither King: AFTER the death dialogue). Fires into the void
 * when nothing listens, so the plugin stays standalone; a glue plugin may use it to free a network slot.
 * <br>
 * Carries a {@link RunResult}. Fires for a FAILED run too (the slot still needs freeing), so check
 * {@link RunResult#success}. A listener that doesn't compile against M7 reads {@link #json()}.
 */
public class RunCompleteEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();

	private final RunResult result;

	public RunCompleteEvent(RunResult result) {
		this.result = result;
	}

	public RunResult result() {
		return result;
	}

	/** Compact JSON. The network plugin listens reflectively and calls this instead of walking {@link RunResult}. */
	public String json() {
		return result.toJson();
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}

	public static @NotNull HandlerList getHandlerList() {
		return HANDLERS;
	}
}
