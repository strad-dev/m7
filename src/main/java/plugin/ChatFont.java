package plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Default-font pixel metrics for measuring and centering chat lines: glyph width plus 1px spacing, bold +1px per
 * glyph except space. Chat box is {@value #MAX_WIDTH}px wide.
 */
public final class ChatFont {
	private ChatFont() {}

	/** Width of an unscaled chat line. */
	public static final int MAX_WIDTH = 320;

	/** Wrap threshold for a CENTRED line, padding included, 10px under {@link #MAX_WIDTH}: some clients wrap at ~312px. */
	public static final int WRAP_WIDTH = 310;

	private static final char SECTION_CHAR = '§';

	/** Pixel width of {@code text}. Skips § codes; bold is §l, reset by §r or a colour. */
	public static int width(String text) {
		int px = 0;
		boolean afterCode = false;
		boolean bold = false;
		for(char c : text.toCharArray()) {
			if(c == SECTION_CHAR) {
				afterCode = true;
				continue;
			}
			if(afterCode) {
				afterCode = false;
				char l = Character.toLowerCase(c);
				if(l == 'l') bold = true;
				else if(l == 'r' || (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f')) bold = false;
				// k/m/n/o don't affect width
				continue;
			}
			int advance = glyphWidth(c) + 1; // glyph + 1px spacing
			if(bold && c != ' ') advance += 1;
			px += advance;
		}
		return px;
	}

	/**
	 * {@code text} wrapped at {@link #WRAP_WIDTH}, each line {@link #centerPad}ed, so text of unknown width (a name, a
	 * live number) still centres. Pass plain text or a legacy §-string; MiniMessage tags count as characters. A word
	 * wider than {@link #WRAP_WIDTH} gets its own line and overflows.
	 */
	public static List<String> centerLines(String text) {
		List<String> lines = new ArrayList<>();
		if(text == null || text.isBlank()) return lines;
		StringBuilder line = new StringBuilder();
		for(String word : text.trim().split("\\s+")) {
			if(line.isEmpty()) {
				line.append(word);
			} else if(fits(line + " " + word)) {
				line.append(' ').append(word);
			} else {
				lines.add(centerPad(line.toString()));
				line.setLength(0);
				line.append(word);
			}
		}
		if(!line.isEmpty()) lines.add(centerPad(line.toString()));
		return lines;
	}

	/** {@code text} still fits {@link #WRAP_WIDTH} once {@link #centerPad}ed. */
	public static boolean fits(String text) {
		return width(centerPad(text)) <= WRAP_WIDTH;
	}

	/** {@code text} with leading spaces that centre it in {@link #MAX_WIDTH}. */
	public static String centerPad(String text) {
		int toCompensate = (MAX_WIDTH / 2) - (width(text) / 2);
		int spaceAdvance = glyphWidth(' ') + 1; // 4px
		StringBuilder pad = new StringBuilder();
		for(int compensated = 0; compensated < toCompensate; compensated += spaceAdvance) {
			pad.append(' ');
		}
		return pad + text;
	}

	/** Glyph width without the spacing pixel. */
	private static int glyphWidth(char c) {
		return switch(c) {
			case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 1;
			case 'l', '`' -> 2;
			case ' ', 'I', 't', '[', ']', '"' -> 3;
			case 'f', 'k', '(', ')', '{', '}', '<', '>' -> 4;
			case '@', '~' -> 6;
			default -> 5;
		};
	}
}
