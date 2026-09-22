package com.natesoftware.riftborder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BorderPhaseTest {

    @Test
    void fourArgConstructorHasNeitherCeilingNorFloor() {
        BorderPhase phase = new BorderPhase(60, 30, 100, 2.0);
        assertEquals(60, phase.waitSeconds());
        assertEquals(30, phase.shrinkSeconds());
        assertEquals(100, phase.endRadius());
        assertEquals(2.0, phase.damage());
        assertEquals(GameBorder.NO_HEIGHT_LIMIT, phase.endHeight());
        assertEquals(GameBorder.NO_MIN_HEIGHT, phase.endMinHeight());
    }

    @Test
    void fiveArgConstructorHasCeilingButNoFloor() {
        BorderPhase phase = new BorderPhase(60, 30, 100, 2.0, 120);
        assertEquals(120, phase.endHeight());
        assertEquals(GameBorder.NO_MIN_HEIGHT, phase.endMinHeight());
    }

    @Test
    void sentinelsAreAtTheDoubleExtremesAndDistinct() {
        assertEquals(Double.MAX_VALUE, GameBorder.NO_HEIGHT_LIMIT);
        assertEquals(-Double.MAX_VALUE, GameBorder.NO_MIN_HEIGHT);
        assertTrue(GameBorder.NO_HEIGHT_LIMIT > GameBorder.NO_MIN_HEIGHT);
        assertFalse(Double.isInfinite(GameBorder.NO_HEIGHT_LIMIT));
    }
}
