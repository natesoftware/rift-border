package com.natesoftware.riftborder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Target resolution and clamping in BorderPhaseController, driven through a real controller on a real GameBorder.
// The border is never spawned: the controller only needs callbacks, the scheduler and the live centre, so no registry is touched.
class BorderPhaseControllerTargetTest {

    private static final double EPS = 1e-9;
    private static final double INITIAL_RADIUS = 200;
    private static final int WAIT_SECONDS = 10;
    private static final int SHRINK_SECONDS = 5;
    private static final int PHASE_TICKS = (WAIT_SECONDS + SHRINK_SECONDS) * 20;

    // Two damage-free phases: 200 -> 100 (max offset 100), then 100 -> 40 (max offset 60).
    private static final List<BorderPhase> TWO_PHASES = List.of(
        new BorderPhase(WAIT_SECONDS, SHRINK_SECONDS, 100, 0),
        new BorderPhase(WAIT_SECONDS, SHRINK_SECONDS, 40, 0));

    private TestMocks.FakeScheduler scheduler;
    private Plugin plugin;
    private GameBorder border;

    @BeforeEach
    void setUp() {
        scheduler = new TestMocks.FakeScheduler();
        plugin = TestMocks.plugin(scheduler);
        border = newBorder();
    }

    private GameBorder newBorder() {
        GameBorder b = new GameBorder(plugin, TestMocks.world(), 0, 64, 0).withCallbacks(new BorderCallbacks() {});
        b.setPosition(0, 0, INITIAL_RADIUS);
        return b;
    }

    private BorderPhaseController controller(GameBorder on, List<BorderPhase> phases, ShrinkTargetSelector selector) {
        return new BorderPhaseController(plugin, on, phases, 0, 0, INITIAL_RADIUS).withTargetSelector(selector);
    }

    private static double distance(BorderPoint from, BorderPoint to) {
        return Math.hypot(to.x() - from.x(), to.z() - from.z());
    }

    @Test
    void aPointFarOutsideTheOffsetIsPulledBackAlongItsRayToExactlyMaxOffset() {
        // Off-origin from-point so the ray is proven to start at the border centre rather than at (0, 0).
        border.setPosition(10, 20, INITIAL_RADIUS);
        BorderPoint proposed = new BorderPoint(310, 420);
        BorderPhaseController controller = controller(border, TWO_PHASES, ctx -> proposed);
        controller.start(60);

        BorderPoint from = new BorderPoint(10, 20);
        BorderPoint target = controller.getTargetCenter();
        double maxOffset = INITIAL_RADIUS - TWO_PHASES.get(0).endRadius();
        assertEquals(maxOffset, distance(from, target), EPS);

        // Same unit direction as the proposal: (300, 400) / 500.
        double proposedDist = distance(from, proposed);
        assertEquals((proposed.x() - from.x()) / proposedDist, (target.x() - from.x()) / maxOffset, EPS);
        assertEquals((proposed.z() - from.z()) / proposedDist, (target.z() - from.z()) / maxOffset, EPS);
        assertEquals(70, target.x(), EPS);
        assertEquals(100, target.z(), EPS);
    }

    @Test
    void aNullSelectionLandsOnTheFromPoint() {
        border.setPosition(12.5, -7.25, INITIAL_RADIUS);
        BorderPhaseController controller = controller(border, TWO_PHASES, ctx -> null);
        controller.start(60);

        BorderPoint target = controller.getTargetCenter();
        assertNotNull(target);
        assertEquals(12.5, target.x(), EPS);
        assertEquals(-7.25, target.z(), EPS);
    }

    @Test
    void aPointWithinTheOffsetIsUsedVerbatim() {
        BorderPoint proposed = new BorderPoint(30, -40);
        BorderPhaseController controller = controller(border, TWO_PHASES, ctx -> proposed);
        controller.start(60);

        // The selector's own instance comes back, not a copy.
        assertSame(proposed, controller.getTargetCenter());
    }

    @Test
    void aPointExactlyAtMaxOffsetIsNotClamped() {
        // 3-4-5 triangle: distance is exactly 100, the max offset for a 200 -> 100 shrink.
        BorderPoint proposed = new BorderPoint(60, 80);
        BorderPhaseController controller = controller(border, TWO_PHASES, ctx -> proposed);
        controller.start(60);

        assertSame(proposed, controller.getTargetCenter());
    }

    @Test
    void aPhaseThatDoesNotShrinkPinsAnyProposalToTheFromPoint() {
        // endRadius above the from radius: max offset clamps to 0, so a proposal off the centre scales down to the centre.
        List<BorderPhase> growing = List.of(new BorderPhase(WAIT_SECONDS, SHRINK_SECONDS, INITIAL_RADIUS + 50, 0));
        border.setPosition(5, -5, INITIAL_RADIUS);
        BorderPhaseController controller = controller(border, growing, ctx -> new BorderPoint(35, 35));
        controller.start(60);

        BorderPoint target = controller.getTargetCenter();
        assertEquals(5, target.x(), EPS);
        assertEquals(-5, target.z(), EPS);
    }

    @Test
    void phaseTwoShrinksFromPhaseOnesClampedTargetAndEndRadius() {
        List<ShrinkContext> contexts = new ArrayList<>();
        BorderPhaseController controller = controller(border, TWO_PHASES, ctx -> {
            contexts.add(ctx);
            // Far outside on phase 1 (clamped to (60, 80)), then anywhere for phase 2.
            return ctx.phaseIndex() == 0 ? new BorderPoint(300, 400) : null;
        });
        controller.start(60);
        assertEquals(1, contexts.size());

        // Wait, then shrink to completion: the animator's completion hook enters phase 2 and resolves its target.
        scheduler.advance(PHASE_TICKS);
        assertEquals(2, contexts.size());
        assertEquals(1, controller.getCurrentPhase());

        ShrinkContext second = contexts.get(1);
        assertEquals(60, second.currentX(), EPS);
        assertEquals(80, second.currentZ(), EPS);
        assertEquals(TWO_PHASES.get(0).endRadius(), second.currentRadius(), EPS);
        assertEquals(TWO_PHASES.get(1).endRadius(), second.targetRadius(), EPS);
        assertEquals(1, second.phaseIndex());
        assertEquals(2, second.phaseCount());

        // Phase 1 landed where phase 2 shrinks from.
        assertEquals(60, border.getCenterX(), EPS);
        assertEquals(80, border.getCenterZ(), EPS);
        assertEquals(TWO_PHASES.get(0).endRadius(), border.getRadius(), EPS);
    }

    @Test
    void phaseOneShrinksFromTheBorderCentreAtStartTimeAndTheConstructorsInitialRadius() {
        List<ShrinkContext> contexts = new ArrayList<>();
        Random rng = new Random(7);
        BorderPhaseController controller = new BorderPhaseController(plugin, border, TWO_PHASES, 1.5, -2.5, INITIAL_RADIUS)
            .withTargetSelector(ctx -> {
                contexts.add(ctx);
                return null;
            })
            .withRandom(rng);
        // Moved after construction but before start(): the controller reads the centre when start() runs, not in the constructor.
        // The radius is NOT read live - the live 150 is ignored in favour of the constructor's initialRadius (current behaviour).
        border.setPosition(30, -45, 150);
        controller.start(60);

        ShrinkContext first = contexts.get(0);
        assertEquals(30, first.currentX(), EPS);
        assertEquals(-45, first.currentZ(), EPS);
        assertEquals(INITIAL_RADIUS, first.currentRadius(), EPS);
        assertEquals(TWO_PHASES.get(0).endRadius(), first.targetRadius(), EPS);
        assertEquals(0, first.phaseIndex());
        assertEquals(2, first.phaseCount());
        assertEquals(1.5, first.mapCenterX(), EPS);
        assertEquals(-2.5, first.mapCenterZ(), EPS);
        assertSame(border.getWorld(), first.world());
        assertSame(rng, first.rng());
    }

    @Test
    void targetCentreIsNullBeforeStartResolvedAtWaitStartAndNullAfterTheLastPhase() {
        List<BorderPhase> single = List.of(new BorderPhase(WAIT_SECONDS, SHRINK_SECONDS, 100, 0));
        AtomicInteger selections = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        BorderPhaseController controller = controller(border, single, ctx -> {
            selections.incrementAndGet();
            return new BorderPoint(30, 40);
        });
        controller.setOnAllPhasesComplete(completions::incrementAndGet);

        assertNull(controller.getTargetCenter());
        assertEquals(-1, controller.getTargetRadius(), EPS);
        assertEquals(0, selections.get());

        controller.start(60);
        // Resolved synchronously on entering the wait, before a single tick has run.
        assertEquals(1, selections.get());
        BorderPoint target = controller.getTargetCenter();
        assertNotNull(target);
        assertEquals(30, target.x(), EPS);
        assertEquals(40, target.z(), EPS);
        assertEquals(100, controller.getTargetRadius(), EPS);

        // Entering the shrink reuses the cached target rather than asking the selector again.
        scheduler.advance(WAIT_SECONDS * 20);
        assertTrue(controller.isShrinking());
        assertEquals(1, selections.get());
        assertSame(target, controller.getTargetCenter());

        scheduler.advance(SHRINK_SECONDS * 20);
        assertEquals(1, completions.get());
        assertNull(controller.getTargetCenter());
        assertEquals(-1, controller.getTargetRadius(), EPS);
        assertEquals(1, selections.get());
    }

    @Test
    void twoControllersSeededWithTheSameRandomProduceIdenticalRandomInsideTargets() {
        GameBorder other = newBorder();
        BorderPhaseController a = controller(border, TWO_PHASES, ShrinkTargetSelector.RANDOM_INSIDE).withRandom(new Random(42));
        BorderPhaseController b = controller(other, TWO_PHASES, ShrinkTargetSelector.RANDOM_INSIDE).withRandom(new Random(42));
        a.start(60);
        b.start(60);

        // The controller hands the rng to the selector untouched, so the target follows RANDOM_INSIDE's two draws exactly.
        Random expected = new Random(42);
        double angle = expected.nextDouble() * 2 * Math.PI;
        double dist = Math.sqrt(expected.nextDouble()) * (INITIAL_RADIUS - TWO_PHASES.get(0).endRadius());
        BorderPoint first = a.getTargetCenter();
        assertEquals(dist * Math.cos(angle), first.x(), EPS);
        assertEquals(dist * Math.sin(angle), first.z(), EPS);
        assertEquals(first.x(), b.getTargetCenter().x(), EPS);
        assertEquals(first.z(), b.getTargetCenter().z(), EPS);
        assertTrue(dist > 0 && dist <= 100);

        // Phase 2 chains off the same seeded stream on both sides.
        scheduler.advance(PHASE_TICKS);
        assertEquals(1, a.getCurrentPhase());
        assertEquals(1, b.getCurrentPhase());
        BorderPoint second = a.getTargetCenter();
        assertNotNull(second);
        assertEquals(second.x(), b.getTargetCenter().x(), EPS);
        assertEquals(second.z(), b.getTargetCenter().z(), EPS);
        assertTrue(distance(first, second) <= TWO_PHASES.get(0).endRadius() - TWO_PHASES.get(1).endRadius() + EPS);
    }

    @Test
    void participantsAreASnapshotTakenAtSelectionTime() {
        UUID original = UUID.randomUUID();
        Set<UUID> live = new HashSet<>(Set.of(original));
        border.withParticipants(() -> live);
        List<Set<UUID>> seen = new ArrayList<>();
        controller(border, TWO_PHASES, ctx -> {
            seen.add(ctx.participants());
            return null;
        }).start(60);

        live.add(UUID.randomUUID());
        Set<UUID> snapshot = seen.get(0);
        assertNotSame(live, snapshot);
        assertEquals(Set.of(original), snapshot);
        // Set.copyOf: the selector cannot write back into the border's roster through it.
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(UUID.randomUUID()));
    }

    @Test
    void participantsAreEmptyWithoutASupplierOrWhenTheSupplierReturnsNull() {
        List<Set<UUID>> seen = new ArrayList<>();
        ShrinkTargetSelector capture = ctx -> {
            seen.add(ctx.participants());
            return null;
        };
        controller(border, TWO_PHASES, capture).start(60);

        GameBorder nullSupplied = newBorder().withParticipants(() -> null);
        controller(nullSupplied, TWO_PHASES, capture).start(60);

        assertEquals(2, seen.size());
        assertNotNull(seen.get(0));
        assertTrue(seen.get(0).isEmpty());
        assertNotNull(seen.get(1));
        assertTrue(seen.get(1).isEmpty());
    }
}
