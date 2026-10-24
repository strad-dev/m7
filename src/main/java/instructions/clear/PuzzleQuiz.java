package instructions.clear;

import instructions.Server;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import plugin.Utils;

import java.util.List;

/**
 * Oruo quiz (grid 0,0). Intro fires on first entry; each question uses the TAS animation
 * ({@link Server.Quiz#animateQuestion}) and answers are accepted only after option (c) appears. Answer by
 * right-clicking button A/B/C (±1 block); B is always correct. Wrong answer: Oruo mocks, quiz restarts from the
 * intro. Three right → green check + score now, Time V after Oruo's reward line.
 * <p>Q3 asks whether whoever opened the room is bald. "Yes" for everyone but {@code Beethoven_}, so for him A/B
 * swap and B stays correct.
 */
public final class PuzzleQuiz {
	private PuzzleQuiz() {
	}

	// A, B, C, ±1 block tolerance.
	private static final int[][] BUTTONS = {{-20, 70, -34}, {-25, 70, -31}, {-30, 70, -34}};
	private static final int CORRECT = 1; // B

	// Unpadded: animateQuestion wraps and centers via ChatFont.centerLines. Index 2 is filled by questionText().
	private static final String[] QUESTIONS = {
			"How is the run going so far?",
			"Did you know that you can sub scribe to Stradivarius Violin to see more content like this?!",
			"Is %s bald?"
	};
	private static final String[][] ANSWERS = {
			{"Alright", "Trash", "Literally tick-perfect"},
			{"Oh wow, I should sub scribe!", "Oh wow, I should sub scribe!!", "Oh wow, I should sub scribe!!!"},
			{"No", "Yes", "Decline to Answer"}
	};
	/** Q3 for the non-bald player: A/B swapped so B ("No") is still correct. */
	private static final String[] ANSWERS_NOT_BALD = {"Yes", "No", "Decline to Answer"};
	/** Matched against the opener's name. */
	private static final String NOT_BALD = "Beethoven_";
	/** Q3 subject if no opener was recorded. Shouldn't happen: the quiz starts on entry. */
	private static final String FALLBACK_OPENER = "akc0303";
	private static final String ORUO = "<dark_red>[STATUE] Oruo the Omniscient<white>: ";

	private static final TextDisplay[] options = new TextDisplay[3];
	private static World world;
	private static boolean started, solved, awaiting;
	private static int question; // 0-based
	private static int gen;       // generation guard: restart/stop cancels stale scheduled tasks
	/** Who opened the room, Q3's subject. Null until entry. */
	private static String opener;

	public static void reset() {
		gen++;
		started = solved = awaiting = false;
		question = 0;
		opener = null;
		Server.Quiz.removeOptions(options);
	}

	public static void stop() {
		gen++;
		started = awaiting = false;
		Server.Quiz.removeOptions(options);
	}

	/** Starts the intro the first tick a real player is in the room. */
	public static void tick(World w, List<Player> players) {
		if(started || solved || w == null) return;
		for(Player p : players) {
			if(Rooms.roomAt(p.getLocation()) == Rooms.QUIZ) {
				world = w;
				opener = p.getName(); // question 3 is about whoever walked in first
				begin();
				return;
			}
		}
	}

	/** Whole quiz from Oruo's intro; also the restart after a wrong answer. */
	private static void begin() {
		started = true;
		awaiting = false;
		question = 0;
		final int g = ++gen;
		Server.Quiz.removeOptions(options);
		Server.Quiz.oruoMessage("I am <dark_red>Oruo the Omniscient<white>.  I have lived many lives.  I have learned all there is to know.");
		Utils.playGlobalSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
		Utils.scheduleTask(() -> {
			if(g != gen) return;
			Server.Quiz.oruoMessage("Though I sit stationary in this prison that is <red>The Catacombs<white>, my knowledge knows no bounds.");
		}, 40);
		Utils.scheduleTask(() -> {
			if(g != gen) return;
			Server.Quiz.oruoMessage("Prove your knowledge by answering 3 questions and I shall reward you in ways that transcend time!");
		}, 80);
		Utils.scheduleTask(() -> {
			if(g != gen) return;
			Server.Quiz.oruoMessage("Answer incorrectly, and your moment of ineptitude will live on for generations.");
		}, 120);
		Utils.scheduleTask(() -> {
			if(g != gen) return;
			ask();
		}, 160);
	}

	private static void ask() {
		final int g = gen;
		awaiting = false;
		Server.Quiz.removeOptions(options);
		Player p = ClearManager.nearestRealPlayer(new Location(world, -25, 71, -31));
		Server.Quiz.animateQuestion(world, p, question + 1, questionText(question), answers(question), options);
		// Only after option (c) shows (+60t).
		Utils.scheduleTask(() -> {
			if(g == gen) awaiting = true;
		}, 60);
	}

	/** Unpadded, {@code animateQuestion} centers it. Q3 names {@link #opener}. */
	private static String questionText(int index) {
		if(index != 2) return QUESTIONS[index];
		return String.format(QUESTIONS[2], opener == null ? FALLBACK_OPENER : opener);
	}

	/** Q3 flips to {@link #ANSWERS_NOT_BALD} when {@link #NOT_BALD} opened the room. */
	private static String[] answers(int index) {
		if(index == 2 && NOT_BALD.equals(opener)) return ANSWERS_NOT_BALD;
		return ANSWERS[index];
	}

	/** {@code index} 0=A, 1=B, 2=C. */
	public static boolean acceptsAnswer() {
		return started && !solved && awaiting;
	}

	public static void answer(Player p, int index) {
		if(!acceptsAnswer()) return;
		awaiting = false;
		Server.Quiz.removeOptions(options);
		if(index == CORRECT) {
			Bukkit.broadcast(Utils.msg(ORUO + "<gold>" + p.getName() + " <green>answered <gold>Question #" + (question + 1) + "<green> correctly!"));
			Utils.playGlobalSound(Sound.ENTITY_PLAYER_LEVELUP, 2.0f, 0.75f);
			if(question < 2) {
				final int g = ++gen;
				String line = question == 0 ? "2 question left... then you will have proven your worth to me!" : "One more question!";
				Utils.scheduleTask(() -> Server.Quiz.oruoMessage(line), 40);
				Utils.scheduleTask(() -> {
					if(g != gen) return;
					question++;
					ask();
				}, 80);
			} else {
				complete(p);
			}
		} else {
			final int g = ++gen;
			Utils.playGlobalSound(Sound.ENTITY_CAT_AMBIENT, 2.0f, 0.5f);
			Server.Quiz.oruoMessage("<dark_red>Y<red>i<gold>k<yellow>e<green>s");
			Utils.scheduleTask(() -> {
				if(g != gen) return;
				Bukkit.broadcast(Utils.msg(ORUO + "<gold><name><red> chose the wrong answer!  I shall never forget this moment of misrememberance.",
						Placeholder.unparsed("name", p.getName())));
				Utils.playGlobalSound(Sound.ENTITY_GUARDIAN_HURT, 2.0f, 0.5f);
			}, 20);
			Utils.scheduleTask(() -> {
				if(g != gen) return;
				begin();
			}, 60);
		}
	}

	private static void complete(Player p) {
		solved = true;
		Server.Quiz.removeOptions(options);
		final int g = ++gen;
		// Check and score now; waiting for Oruo's dialogue only delayed the score 40 ticks.
		ClearManager.puzzleSolved(Rooms.QUIZ, p, false);
		// TAS timing: Q3 answered → (+20t) Oruo's reward line → (+20t) the blessing.
		Utils.scheduleTask(() -> {
			if(g != gen) return;
			Server.Quiz.oruoMessage("I bestow upon you all the power of a hundred years!");
		}, 20);
		Utils.scheduleTask(() -> {
			if(g != gen) return;
			ClearManager.awardRoomBlessings(Rooms.QUIZ, p); // Time V
		}, 40);
	}

	/** Within 1 block of an answer button (Dungeonbreaker immunity). */
	public static boolean isButtonArea(Block b) {
		return buttonIndex(b) >= 0;
	}

	/** Button index (0=A, 1=B, 2=C) for a clicked block, ±1 tolerance, or -1. */
	public static int buttonIndex(Block b) {
		for(int i = 0; i < BUTTONS.length; i++) {
			int[] btn = BUTTONS[i];
			if(Math.abs(b.getX() - btn[0]) <= 1 && Math.abs(b.getY() - btn[1]) <= 1 && Math.abs(b.getZ() - btn[2]) <= 1) {
				return i;
			}
		}
		return -1;
	}
}
