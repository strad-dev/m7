package instructions.bosses.goldor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RubixSolver: Same Color (Rubix) target and per-pane clicks, matching Odin")
class RubixSolverTest {

	@Test
	@DisplayName("forward clicks wrap around the five-colour cycle")
	void forward() {
		assertEquals(1, RubixSolver.forward(4, 0));
		assertEquals(4, RubixSolver.forward(0, 4));
		assertEquals(0, RubixSolver.forward(2, 2));
	}

	@Test
	@DisplayName("signed clicks take the short way: never more than 2, and the same colour either way")
	void signed() {
		assertEquals(-2, RubixSolver.signed(0, 3));
		assertEquals(-1, RubixSolver.signed(0, 4));
		assertEquals(2, RubixSolver.signed(0, 2));
		for(int from = 0; from < RubixSolver.CYCLE; from++) {
			for(int to = 0; to < RubixSolver.CYCLE; to++) {
				int s = RubixSolver.signed(from, to);
				assertTrue(Math.abs(s) <= 2, from + " -> " + to);
				assertEquals(RubixSolver.forward(from, to), Math.floorMod(s, RubixSolver.CYCLE), from + " -> " + to);
			}
		}
	}

	@Test
	@DisplayName("target is the colour with the fewest total clicks")
	void cheapest() {
		assertEquals(0, RubixSolver.bestTarget(new int[]{0, 0, 0, 0, 0, 0, 0, 0, 1}));
		assertEquals(3, RubixSolver.bestTarget(new int[]{3, 3, 3, 3, 3, 3, 3, 3, 3}));
	}

	@Test
	@DisplayName("a tie goes to the first colour in cycle order, like Odin's minBy")
	void tie() {
		// 0 and 1 both cost 2 clicks; everything else costs more
		assertEquals(0, RubixSolver.bestTarget(new int[]{0, 0, 1, 1}));
		assertEquals(2, RubixSolver.bestTarget(new int[]{2, 2, 3, 3}));
	}
}
