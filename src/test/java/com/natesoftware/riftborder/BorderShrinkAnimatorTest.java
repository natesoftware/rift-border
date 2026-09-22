package com.natesoftware.riftborder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Drives the animator through the fake scheduler and checks the shape it writes back each tick.
class BorderShrinkAnimatorTest {

    private static final double EPS = 1e-9;

    private TestMocks.FakeScheduler scheduler;
    private GameBorder border;

    @BeforeEach
    void setUp() {
        scheduler = new TestMocks.FakeScheduler();
        border = new GameBorder(TestMocks.plugin(scheduler), TestMocks.world(), 0, 64, 0)
            .withCallbacks(new BorderCallbacks() {});
        border.setPosition(0, 0, 200);
    }

    @Test
    void moveToLandsExactlyWhenTheTickBudgetRunsOut() {
        border.moveTo(50, -30, 100, 40);
        scheduler.advance(39);
        assertTrue(border.getRadius() > 100);
        assertTrue(border.getCenterX() < 50);
        scheduler.advance(1);
        assertEquals(100, border.getRadius(), EPS);
        assertEquals(50, border.getCenterX(), EPS);
        assertEquals(-30, border.getCenterZ(), EPS);
        assertEquals(0, scheduler.pending(), "the animator task should stop itself on arrival");
    }

    @Test
    void interpolationIsLinearInTicks() {
        border.moveTo(0, 0, 100, 100);
        scheduler.advance(25);
        assertEquals(175, border.getRadius(), EPS);
        scheduler.advance(25);
        assertEquals(150, border.getRadius(), EPS);
    }

    @Test
    void completionFiresTheControllerHookBeforeTheHostHook() {
        List<String> order = new ArrayList<>();
        border.onShrinkComplete(() -> order.add("host"));
        border.internalShrinkComplete = () -> order.add("internal");
        border.moveTo(0, 0, 100, 10);
        scheduler.advance(10);
        assertEquals(List.of("internal", "host"), order);
    }

    @Test
    void completionDoesNotFireWhenTheBorderIsRemovedMidShrink() {
        List<String> order = new ArrayList<>();
        border.onShrinkComplete(() -> order.add("host"));
        border.moveTo(0, 0, 100, 10);
        scheduler.advance(5);
        border.remove();
        scheduler.advance(10);
        assertTrue(order.isEmpty());
    }

    @Test
    void aCeilingLerpsDownFromTheWorldBuildLimitWhenStartingWithNone() {
        border.moveTo(0, 0, 100, 100, 100);
        scheduler.advance(50);
        assertEquals((320 + 100) / 2.0, border.getMaxHeight(), EPS);
        scheduler.advance(50);
        assertEquals(100, border.getMaxHeight(), EPS);
        assertTrue(border.hasHeightLimit());
    }

    @Test
    void endingWithNoCeilingRestoresTheSentinelNotTheWorldHeight() {
        border.setPosition(0, 0, 200, 100);
        border.moveTo(0, 0, 100, GameBorder.NO_HEIGHT_LIMIT, 10);
        scheduler.advance(10);
        assertEquals(GameBorder.NO_HEIGHT_LIMIT, border.getMaxHeight());
    }

    @Test
    void resumeReinterpolatesFromTheLiveShapeToTheSameTarget() {
        border.moveTo(0, 0, 100, 100);
        scheduler.advance(50);
        assertEquals(150, border.getRadius(), EPS);

        border.pauseShrinking();
        scheduler.advance(20);
        assertEquals(150, border.getRadius(), EPS);

        border.resumeShrinking(50);
        scheduler.advance(25);
        assertEquals(125, border.getRadius(), EPS);
        scheduler.advance(25);
        assertEquals(100, border.getRadius(), EPS);
    }

    @Test
    void setRemainingTicksRewindsOrFastForwardsWithinTheSameEndpoints() {
        border.moveTo(0, 0, 100, 100);
        scheduler.advance(10);
        border.setRemainingTicks(10);
        assertEquals(110, border.getRadius(), EPS);
        scheduler.advance(10);
        assertEquals(100, border.getRadius(), EPS);
    }

    @Test
    void aTransitionWithNoCeilingOrFloorAtEitherEndKeepsBothSentinelsThroughout() {
        border.moveTo(50, 50, 100, 100);
        scheduler.advance(50);
        // mid-shrink, with no ceiling at either end, the border must not report the world build height as a ceiling
        assertEquals(GameBorder.NO_HEIGHT_LIMIT, border.getMaxHeight());
        assertEquals(GameBorder.NO_MIN_HEIGHT, border.getMinHeight());
        assertFalse(border.hasHeightLimit());
        assertFalse(border.hasMinHeight());
        scheduler.advance(50);
        assertFalse(border.hasHeightLimit());
        assertFalse(border.hasMinHeight());
    }

    @Test
    void resumeAfterLandingDoesNothingAndDoesNotRefireCompletion() {
        List<String> order = new ArrayList<>();
        border.onShrinkComplete(() -> order.add("host"));
        border.moveTo(0, 0, 100, 10);
        scheduler.advance(10);
        assertEquals(List.of("host"), order);

        border.resumeShrinking(10);
        assertEquals(0, scheduler.pending());
        scheduler.advance(20);
        assertEquals(List.of("host"), order);
        assertEquals(100, border.getRadius(), EPS);
    }

    @Test
    void patternRadiusAnchorsToTheTargetWhileShrinkingAndTheLiveRadiusOtherwise() {
        assertEquals(200, border.getPatternRadius(), EPS);
        border.moveTo(0, 0, 100, 100);
        scheduler.advance(30);
        assertEquals(100, border.getPatternRadius(), EPS);
        scheduler.advance(70);
        assertEquals(100, border.getPatternRadius(), EPS);
    }
}
