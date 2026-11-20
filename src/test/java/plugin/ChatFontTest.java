package plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatFont: chat-line pixel widths and centring")
class ChatFontTest {

	@Test
	@DisplayName("glyph plus 1px spacing")
	void glyphs() {
		assertEquals(6, ChatFont.width("a"));
		assertEquals(2, ChatFont.width("i"));
		assertEquals(3, ChatFont.width("l"));
		assertEquals(4, ChatFont.width(" "));
		assertEquals(7, ChatFont.width("@"));
	}

	@Test
	@DisplayName("section codes take no width")
	void codesSkipped() {
		assertEquals(6, ChatFont.width("§ca"));
		assertEquals(0, ChatFont.width("§c§l"));
	}

	@Test
	@DisplayName("bold adds 1px except on spaces; a colour or reset ends it")
	void bold() {
		assertEquals(7, ChatFont.width("§la"));
		assertEquals(4, ChatFont.width("§l "));
		assertEquals(6, ChatFont.width("§l§ca"));
		assertEquals(13, ChatFont.width("§lA§rA"));
	}

	@Test
	@DisplayName("empty text pads to the middle of the 320px box")
	void centreEmpty() {
		assertEquals(" ".repeat(40), ChatFont.centerPad(""));
	}

	@Test
	@DisplayName("a near-full line pads only as far as the wrap width allows")
	void padCapped() {
		String text = "a".repeat(50); // 300px; centring wants 3 spaces, only 2 fit under 310
		assertEquals("  " + text, ChatFont.centerPad(text));
		assertTrue(ChatFont.width(ChatFont.centerPad(text)) <= ChatFont.WRAP_WIDTH);
	}

	@Test
	@DisplayName("nothing to centre gives no lines")
	void blank() {
		assertTrue(ChatFont.centerLines(null).isEmpty());
		assertTrue(ChatFont.centerLines("   ").isEmpty());
	}

	@Test
	@DisplayName("long text wraps into lines that each fit, padding included, and keep every word")
	void wraps() {
		String text = String.join(" ", java.util.Collections.nCopies(30, "aaaaa"));
		List<String> lines = ChatFont.centerLines(text);
		assertTrue(lines.size() > 1);
		for(String line : lines) assertTrue(ChatFont.width(line) <= ChatFont.WRAP_WIDTH, line);
		assertEquals(text, String.join(" ", lines.stream().map(String::strip).toList()));
	}

	@Test
	@DisplayName("a word wider than the wrap width gets its own unpadded line")
	void overlongWord() {
		String word = "a".repeat(60); // 360px
		assertEquals(List.of(word), ChatFont.centerLines(word));
	}
}
