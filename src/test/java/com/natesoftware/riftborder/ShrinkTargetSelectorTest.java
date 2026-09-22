package com.natesoftware.riftborder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

// Exercises the two built-in selectors directly through a ShrinkContext. Neither touches the world, so it is left null.
class ShrinkTargetSelectorTest {

    private static final double EPS = 1e-9;
    private static final long SEED = 42L;
    private static final int SAMPLES = 1000;

    private static ShrinkContext context(double currentX, double currentZ, double currentRadius, double targetRadius,
                                         double mapCenterX, double mapCenterZ, Random rng) {
        return new ShrinkContext(null, currentX, currentZ, currentRadius, targetRadius, 2, 5, mapCenterX, mapCenterZ,
            Set.of(UUID.randomUUID()), rng);
    }

    private static double distance(BorderPoint point, double x, double z) {
        return Math.hypot(point.x() - x, point.z() - z);
    }

    @Test
    void randomInsideStaysWithinMaxOffsetOfTheCurrentCentreAndVaries() {
        Random rng = new Random(SEED);
        double maxOffset = 150 - 40;
        List<BorderPoint> points = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            BorderPoint point = ShrinkTargetSelector.RANDOM_INSIDE.select(context(300, -120, 150, 40, 0, 0, rng));
            assertTrue(distance(point, 300, -120) <= maxOffset + EPS, "sample " + i + " landed outside the offset disc: " + point);
            points.add(point);
        }
        long distinct = points.stream().distinct().count();
        assertTrue(distinct > 1, "1000 samples collapsed onto a single point");
        assertTrue(points.stream().anyMatch(p -> distance(p, 300, -120) > maxOffset / 2), "no sample ever reached the outer half");
    }

    @Test
    void randomInsideOffsetsFromTheCurrentCentreNotTheMapCentre() {
        Random rng = new Random(SEED);
        for (int i = 0; i < SAMPLES; i++) {
            BorderPoint point = ShrinkTargetSelector.RANDOM_INSIDE.select(context(1000, 1000, 50, 20, 0, 0, rng));
            assertTrue(distance(point, 1000, 1000) <= 30 + EPS);
            assertTrue(distance(point, 0, 0) > 30, "sample " + i + " sat inside a disc around the map centre: " + point);
        }
    }

    @Test
    void randomInsideDrawsAngleThenSqrtRadiusFromTheRng() {
        BorderPoint point = ShrinkTargetSelector.RANDOM_INSIDE.select(context(10, -20, 100, 25, 0, 0, new Random(SEED)));

        // replay the same seed the way the selector consumes it: one draw for the angle, one for the area-uniform radius
        Random replay = new Random(SEED);
        double angle = replay.nextDouble() * 2 * Math.PI;
        double dist = Math.sqrt(replay.nextDouble()) * 75;
        assertEquals(10 + dist * Math.cos(angle), point.x(), EPS);
        assertEquals(-20 + dist * Math.sin(angle), point.z(), EPS);
        assertTrue(dist > 0, "the seeded draw should have moved the centre so the formula is actually exercised");
    }

    @Test
    void randomInsideRadiusIsAreaUniformNotRadiusUniform() {
        Random rng = new Random(SEED);
        double sumOfSquares = 0;
        for (int i = 0; i < SAMPLES; i++) {
            BorderPoint point = ShrinkTargetSelector.RANDOM_INSIDE.select(context(0, 0, 100, 0, 0, 0, rng));
            double normalised = distance(point, 0, 0) / 100;
            sumOfSquares += normalised * normalised;
        }
        // sqrt(uniform) makes the squared normalised distance uniform on [0, 1), so its mean sits near 0.5 rather than 1/3
        assertEquals(0.5, sumOfSquares / SAMPLES, 0.05);
    }

    @Test
    void randomInsideReturnsTheCurrentCentreWhenTheShrinkIsUnderOneBlock() {
        Random rng = new Random(SEED);
        BorderPoint point = ShrinkTargetSelector.RANDOM_INSIDE.select(context(33.5, -7.25, 100, 99.5, 0, 0, rng));
        assertEquals(33.5, point.x(), EPS);
        assertEquals(-7.25, point.z(), EPS);
        // the early return happens before any draw, so the rng is left untouched
        assertEquals(new Random(SEED).nextDouble(), rng.nextDouble(), 0);
    }

    @Test
    void randomInsideMovesOnceTheShrinkReachesExactlyOneBlock() {
        BorderPoint point = ShrinkTargetSelector.RANDOM_INSIDE.select(context(0, 0, 100, 99, 0, 0, new Random(SEED)));
        double dist = distance(point, 0, 0);
        assertTrue(dist > 0, "a 1.0 block shrink is not under the threshold and should draw a target");
        assertTrue(dist <= 1 + EPS);
    }

    @Test
    void randomInsideStaysPutOnAGrowingPhase() {
        Random rng = new Random(SEED);
        BorderPoint point = ShrinkTargetSelector.RANDOM_INSIDE.select(context(12, 34, 50, 80, 0, 0, rng));
        assertEquals(12, point.x(), EPS);
        assertEquals(34, point.z(), EPS);
        assertEquals(new Random(SEED).nextDouble(), rng.nextDouble(), 0);
    }

    @Test
    void randomInsideIsReproducibleFromTheSeed() {
        Random first = new Random(SEED);
        Random second = new Random(SEED);
        for (int i = 0; i < SAMPLES; i++) {
            BorderPoint a = ShrinkTargetSelector.RANDOM_INSIDE.select(context(5, 5, 200, 120, 0, 0, first));
            BorderPoint b = ShrinkTargetSelector.RANDOM_INSIDE.select(context(5, 5, 200, 120, 0, 0, second));
            assertEquals(a, b, "sample " + i + " diverged between two Randoms seeded identically");
        }
        BorderPoint next = ShrinkTargetSelector.RANDOM_INSIDE.select(context(5, 5, 200, 120, 0, 0, first));
        BorderPoint previous = ShrinkTargetSelector.RANDOM_INSIDE.select(context(5, 5, 200, 120, 0, 0, new Random(SEED)));
        assertNotEquals(previous, next, "a fresh seed should restart the sequence, not continue it");
    }

    @Test
    void fixedCenterReturnsTheMapCentreRegardlessOfTheRestOfTheContext() {
        BorderPoint point = ShrinkTargetSelector.FIXED_CENTER.select(context(500, -500, 300, 10, -64.5, 128.25, new Random(SEED)));
        assertEquals(-64.5, point.x(), EPS);
        assertEquals(128.25, point.z(), EPS);
    }

    @Test
    void fixedCenterIgnoresTheRngAndGrowingPhases() {
        // no rng, no participants: FIXED_CENTER reads nothing but the two map centre fields
        ShrinkContext ctx = new ShrinkContext(null, 0, 0, 10, 400, 0, 1, 77, -77, null, null);
        BorderPoint point = ShrinkTargetSelector.FIXED_CENTER.select(ctx);
        assertEquals(new BorderPoint(77, -77), point);
    }
}
