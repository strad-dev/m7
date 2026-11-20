package instructions.clear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Rooms: the F7 grid, room footprints, doors and checkmarks")
class RoomsTest {

	@AfterEach
	void clearRunState() {
		Rooms.reset();
	}

	@Test
	@DisplayName("16 rooms, 15 doors, 47 scored secrets; Ice Fill's two reward chests don't count")
	void counts() {
		assertEquals(16, Rooms.all().size());
		assertEquals(15, Rooms.DOORS.length);
		assertEquals(47, Rooms.all().stream().mapToInt(Room::countedSecretTotal).sum());
		assertEquals(2, Rooms.ICE_FILL.secrets.size());
		assertEquals(0, Rooms.ICE_FILL.countedSecretTotal());
	}

	@Test
	@DisplayName("every one of the 36 cells belongs to exactly one room")
	void gridCovered() {
		int cells = 0;
		for(Room r : Rooms.all()) cells += r.cells.length;
		assertEquals(36, cells);
		for(int gx = 0; gx <= 5; gx++) {
			for(int gz = 0; gz <= 5; gz++) {
				assertNotNull(Rooms.byCell(gx, gz), gx + "," + gz);
			}
		}
	}

	@Test
	@DisplayName("a multi-cell room's cells are connected")
	void roomsContiguous() {
		for(Room r : Rooms.all()) {
			Set<String> reached = new HashSet<>();
			Deque<int[]> todo = new ArrayDeque<>();
			todo.add(r.cells[0]);
			while(!todo.isEmpty()) {
				int[] c = todo.poll();
				if(Rooms.byCell(c[0], c[1]) != r || !reached.add(c[0] + "," + c[1])) continue;
				todo.add(new int[]{c[0] + 1, c[1]});
				todo.add(new int[]{c[0] - 1, c[1]});
				todo.add(new int[]{c[0], c[1] + 1});
				todo.add(new int[]{c[0], c[1] - 1});
			}
			assertEquals(r.cells.length, reached.size(), r.name);
		}
	}

	@Test
	@DisplayName("each door joins two grid neighbours in different rooms")
	void doors() {
		for(int[][] d : Rooms.DOORS) {
			int[] a = d[0], b = d[1];
			assertEquals(1, Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]), "not adjacent");
			assertNotSame(Rooms.byCell(a[0], a[1]), Rooms.byCell(b[0], b[1]), "door inside one room");
		}
	}

	@Test
	@DisplayName("cell (0,0) is X/Z -40..-10; the 32nd block is the buffer")
	void cellAt() {
		assertArrayEquals(new int[]{0, 0}, Rooms.cellAt(-10, -10));
		assertArrayEquals(new int[]{0, 0}, Rooms.cellAt(-40.9, -10));
		assertNull(Rooms.cellAt(-41, -10));
		assertArrayEquals(new int[]{1, 0}, Rooms.cellAt(-42, -10));
		assertNull(Rooms.cellAt(-5, -10));
		assertNull(Rooms.cellAt(-202, -10));
		assertEquals(-10, Rooms.cellMaxX(0));
		assertEquals(-40, Rooms.cellMinX(0));
		assertEquals(-106, Rooms.cellMaxX(3));
		assertEquals(-136, Rooms.cellMinX(3));
	}

	@Test
	@DisplayName("a seam inside one room is that room; a seam between rooms is no room")
	void seams() {
		assertSame(Rooms.byName("Hallway"), Rooms.roomAtBlock(-73, -20));
		assertNull(Rooms.roomAtBlock(-41, -20));
	}

	@Test
	@DisplayName("roomAtBlock goes by block: x -168.5 is in a cell, its block x -169 is a seam")
	void blockNotPosition() {
		assertArrayEquals(new int[]{4, 0}, Rooms.cellAt(-168.5, -20));
		assertNull(Rooms.roomAtBlock(-169, -20));
	}

	@Test
	@DisplayName("Start runs off the grid to Z -213, where players spawn")
	void startAnnex() {
		assertSame(Rooms.START, Rooms.roomAtBlock(-121, -184));
		assertSame(Rooms.START, Rooms.roomAtBlock(-121, -213));
		assertNull(Rooms.roomAtBlock(-121, -214));
	}

	@Test
	@DisplayName("door boxes match Server's fill regions, frame included")
	void doorBoxes() {
		// Start {3,5}-{3,4}: x -122..-120, z -170..-168, y 69..72, plus a one-block frame
		assertTrue(Rooms.inDoor(-121, 70, -169));
		assertTrue(Rooms.inDoor(-123, 68, -170));
		assertFalse(Rooms.inDoor(-124, 70, -169));
		assertFalse(Rooms.inDoor(-121, 74, -169));
		assertFalse(Rooms.inDoor(-121, 70, -160));
		// wither {3,2}-{3,3} at z -106..-104, blood {3,1}-{3,2} at z -74..-72
		assertTrue(Rooms.inDoor(-121, 70, -105));
		assertTrue(Rooms.inDoor(-121, 70, -73));
	}

	@Test
	@DisplayName("Start and Fairy are always green; a puzzle is green once solved")
	void freeChecks() {
		assertEquals(Room.Check.GREEN, Rooms.START.check());
		assertEquals(Room.Check.GREEN, Rooms.byName("Fairy").check());
		assertEquals(Room.Check.NONE, Rooms.QUIZ.check());
		Rooms.QUIZ.solved = true;
		assertEquals(Room.Check.GREEN, Rooms.QUIZ.check());
	}

	@Test
	@DisplayName("a normal room: white on its miniboss, green once every counted secret is found")
	void normalRoom() {
		Room hallway = Rooms.byName("Hallway");
		assertEquals(Room.Check.NONE, hallway.check());
		hallway.cleared = true;
		assertEquals(Room.Check.WHITE, hallway.check());
		for(Secret s : hallway.secrets) s.found = true;
		assertEquals(Room.Check.GREEN, hallway.check());
	}

	@Test
	@DisplayName("Blood has no secrets, so clearing the camp is green")
	void blood() {
		Rooms.BLOOD.cleared = true;
		assertEquals(Room.Check.GREEN, Rooms.BLOOD.check());
	}
}
