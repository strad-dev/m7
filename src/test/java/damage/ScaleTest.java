package damage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Scale and BeamRange: the constants and curves the damage system is built on")
class ScaleTest {

	@Test
	@DisplayName("dungeon scaling is the reverted (pre-Medallion-nerf) 6.66 / 1.81")
	void dungeonScaling() {
		assertEquals(6.66, Scale.SB_CATA_MULT);
		assertEquals(1.81, Scale.SB_STAR_MULT);
	}

	@Test
	@DisplayName("defense divides by 1 + defense/100; negative defense counts as none")
	void defense() {
		assertEquals(1.0, Scale.defenseDivisor(0));
		assertEquals(2.0, Scale.defenseDivisor(100));
		assertEquals(1.0, Scale.defenseDivisor(-50));
	}

	@Test
	@DisplayName("beam: full damage to the cutoff, linear to zero at max range")
	void falloff() {
		Damage.BeamRange r = Damage.BeamRange.DEFAULT;
		assertEquals(1.0, r.falloff(0));
		assertEquals(1.0, r.falloff(10));
		assertEquals(0.5, r.falloff(17.5));
		assertEquals(0.0, r.falloff(25));
		assertEquals(0.0, r.falloff(40));
	}

	@Test
	@DisplayName("the boss-arena and Wither King tiers move the window out")
	void tiers() {
		assertEquals(1.0, Damage.BeamRange.BOSS_ARENA.falloff(35));
		assertEquals(0.5, Damage.BeamRange.BOSS_ARENA.falloff(42.5));
		assertEquals(1.0, Damage.BeamRange.WITHER_KING.falloff(70));
		assertEquals(0.5, Damage.BeamRange.WITHER_KING.falloff(85));
		assertEquals(0.0, Damage.BeamRange.WITHER_KING.falloff(100));
	}
}
