package plugin;

import com.google.gson.Gson;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired once when a /m7scenario ends: completed, failed, or abandoned (/m7practice end, a new run). A scenario never
 * fires {@link RunCompleteEvent}. Fires into the void when nothing listens; a listener that doesn't compile against
 * M7 reads {@link #json()}.
 */
public class ScenarioCompleteEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();
	private static final Gson GSON = new Gson();

	/**
	 * @param ticks from the countdown's end to the ending, null if it never started
	 * @param reason why it failed, null on success
	 */
	public record Result(String scenario, List<String> requirements, boolean success, String reason, Integer ticks,
	                     String difficulty, List<Member> players) {}

	public record Member(String uuid, String name) {}

	private final Result result;

	public ScenarioCompleteEvent(Result result) {
		this.result = result;
	}

	public Result result() {
		return result;
	}

	public String json() {
		return GSON.toJson(result);
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}

	public static @NotNull HandlerList getHandlerList() {
		return HANDLERS;
	}
}
