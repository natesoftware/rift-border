package com.natesoftware.riftborder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Drives BorderPhaseController's wait/shrink schedule through the fake scheduler: tick-exact transitions, the HUD countdown, resyncs to an
// external game timer, pause/resume and teardown. FIXED_CENTER pins every target to the map centre so the numbers are deterministic.
class BorderPhaseControllerTimelineTest {

    private static final double EPS = 1e-9;
    private static final double MAP_X = 30;
    private static final double MAP_Z = 40;
    private static final double INITIAL_RADIUS = 200;
    private static final int GAME_DURATION = 180;
    // Game-seconds-remaining boundaries (wait start / shrink start / end): 180-120-90, 90-60-30, 30-15-0.
    private static final List<BorderPhase> PHASES = List.of(
        new BorderPhase(60, 30, 100, 0.0),
        new BorderPhase(30, 30, 50, 0.0),
        new BorderPhase(15, 15, 0, 0.0));

    private TestMocks.FakeScheduler scheduler;
    private Plugin plugin;
    private GameBorder border;
    private RecordingCallbacks callbacks;
    private BorderPhaseController controller;
    private int completions;

    @BeforeEach
    void setUp() {
        scheduler = new TestMocks.FakeScheduler();
        plugin = TestMocks.plugin(scheduler);
        callbacks = new RecordingCallbacks();
        border = new GameBorder(plugin, TestMocks.world(), 0, 64, 0).withCallbacks(callbacks);
        border.setPosition(0, 0, INITIAL_RADIUS);
        controller = new BorderPhaseController(plugin, border, PHASES, MAP_X, MAP_Z, INITIAL_RADIUS).withFixedCenter(true);
        controller.setOnAllPhasesComplete(() -> completions++);
    }

    @Test
    void startEntersPhaseOneWaitAndCountsDownByCeilingDivision() {
        controller.start(GAME_DURATION);
        assertEquals(0, controller.getCurrentPhase());
        assertFalse(controller.isShrinking());
        assertEquals(List.of(new PhaseStart(1, 3, 60)), callbacks.phaseStarts);
        assertEquals(0, callbacks.shrinkStarts);
        assertEquals(new BorderPoint(MAP_X, MAP_Z), controller.getTargetCenter());
        assertEquals(100, controller.getTargetRadius(), EPS);
        assertEquals(1, scheduler.pending(), "one wait task and nothing else");

        assertEquals(60, controller.getSubPhaseRemaining());
        scheduler.advance(1);
        assertEquals(60, controller.getSubPhaseRemaining(), "the displayed second holds at N for its full 20 ticks");
        scheduler.advance(19);
        assertEquals(59, controller.getSubPhaseRemaining());
        assertEquals(INITIAL_RADIUS, border.getRadius(), EPS);
    }

    @Test
    void waitFlipsToShrinkOnTheExactTickAndLandsOnTheNextWait() {
        controller.start(GAME_DURATION);
        scheduler.advance(1199);
        assertFalse(controller.isShrinking());
        assertEquals(0, callbacks.shrinkStarts);

        scheduler.advance(1);
        assertTrue(controller.isShrinking());
        assertEquals(1, callbacks.shrinkStarts);
        assertEquals(30, controller.getSubPhaseRemaining());
        assertEquals(INITIAL_RADIUS, border.getRadius(), EPS, "the animator writes its first step on the following tick");

        scheduler.advance(300);
        assertEquals(150, border.getRadius(), EPS);
        assertEquals(15, controller.getSubPhaseRemaining());
        assertEquals(0, controller.getCurrentPhase());

        scheduler.advance(300);
        assertEquals(100, border.getRadius(), EPS);
        assertEquals(MAP_X, border.getCenterX(), EPS);
        assertEquals(MAP_Z, border.getCenterZ(), EPS);
        assertEquals(1, controller.getCurrentPhase());
        assertFalse(controller.isShrinking());
        assertEquals(List.of(new PhaseStart(1, 3, 60), new PhaseStart(2, 3, 30)), callbacks.phaseStarts);
        assertEquals(30, controller.getSubPhaseRemaining());
        assertEquals(50, controller.getTargetRadius(), EPS);
    }

    @Test
    void theWholeScheduleCompletesOnceAndClearsTheTarget() {
        controller.start(GAME_DURATION);
        scheduler.advance(GAME_DURATION * 20 - 1);
        assertEquals(0, completions);
        assertTrue(controller.isShrinking());

        scheduler.advance(1);
        assertEquals(1, completions);
        assertEquals(3, controller.getCurrentPhase());
        assertFalse(controller.isShrinking());
        assertNull(controller.getTargetCenter());
        assertEquals(-1, controller.getTargetRadius(), EPS);
        assertEquals(0, controller.getSubPhaseRemaining());
        assertEquals(0, border.getRadius(), EPS);
        assertEquals(MAP_X, border.getCenterX(), EPS);
        assertEquals(
            List.of(new PhaseStart(1, 3, 60), new PhaseStart(2, 3, 30), new PhaseStart(3, 3, 15)), callbacks.phaseStarts);
        assertEquals(3, callbacks.shrinkStarts);
        assertEquals(0, scheduler.pending());

        scheduler.advance(2000);
        assertEquals(1, completions);
    }

    @Test
    void syncBeforeStartIsANoOp() {
        controller.syncToGameTimer(100);
        assertEquals(-1, controller.getCurrentPhase());
        assertEquals(0, controller.getSubPhaseRemaining());
        assertNull(controller.getTargetCenter());
        assertTrue(callbacks.phaseStarts.isEmpty());
        assertEquals(0, scheduler.pending());
        assertEquals(INITIAL_RADIUS, border.getRadius(), EPS);
    }

    @Test
    void syncWithMoreTimeThanTheScheduleStretchesTheFirstWait() {
        controller.start(GAME_DURATION);
        scheduler.advance(1500);
        assertEquals(150, border.getRadius(), EPS);

        controller.syncToGameTimer(250);
        assertEquals(0, controller.getCurrentPhase());
        assertFalse(controller.isShrinking());
        assertEquals(250 - 120, controller.getSubPhaseRemaining());
        assertEquals(INITIAL_RADIUS, border.getRadius(), EPS);
        assertEquals(0, border.getCenterX(), EPS);
        assertEquals(0, border.getCenterZ(), EPS);
        assertEquals(List.of(new PhaseStart(1, 3, 60), new PhaseStart(1, 3, 130)), callbacks.phaseStarts);
        assertEquals(1, scheduler.pending(), "the in-flight shrink is cancelled and only the stretched wait remains");

        scheduler.advance(130 * 20 - 1);
        assertFalse(controller.isShrinking());
        scheduler.advance(1);
        assertTrue(controller.isShrinking());
    }

    @Test
    void syncMidWaitSnapsToThePreviousPhaseEndRadius() {
        controller.start(GAME_DURATION);
        // 75s left is 15s into phase 2's 30s wait (90 -> 60).
        controller.syncToGameTimer(75);
        assertEquals(1, controller.getCurrentPhase());
        assertFalse(controller.isShrinking());
        assertEquals(15, controller.getSubPhaseRemaining());
        assertEquals(100, border.getRadius(), EPS);
        assertEquals(MAP_X, border.getCenterX(), EPS);
        assertEquals(MAP_Z, border.getCenterZ(), EPS);
        assertEquals(List.of(new PhaseStart(1, 3, 60), new PhaseStart(2, 3, 15)), callbacks.phaseStarts);
        assertEquals(0, callbacks.shrinkStarts);

        scheduler.advance(300);
        assertTrue(controller.isShrinking());
        assertEquals(1, callbacks.shrinkStarts);
        assertEquals(1, controller.getCurrentPhase());
    }

    @Test
    void syncMidShrinkInterpolatesTheRadiusAndKeepsShrinking() {
        controller.start(GAME_DURATION);
        // 50s left is 10s into phase 2's 30s shrink (60 -> 30), a third of the way from radius 100 to 50.
        controller.syncToGameTimer(50);
        assertEquals(1, controller.getCurrentPhase());
        assertTrue(controller.isShrinking());
        assertEquals(100 + (50 - 100) * (10.0 / 30), border.getRadius(), EPS);
        assertEquals(MAP_X, border.getCenterX(), EPS);
        assertEquals(20, controller.getSubPhaseRemaining());
        assertEquals(1, callbacks.shrinkStarts);
        assertEquals(List.of(new PhaseStart(1, 3, 60)), callbacks.phaseStarts, "a shrink resync announces no new phase");

        scheduler.advance(200);
        assertEquals(100 + (50 - 100) * (20.0 / 30), border.getRadius(), EPS);
        scheduler.advance(200);
        assertEquals(50, border.getRadius(), EPS);
        assertEquals(2, controller.getCurrentPhase());
        assertEquals(List.of(new PhaseStart(1, 3, 60), new PhaseStart(3, 3, 15)), callbacks.phaseStarts);
    }

    @Test
    void syncToZeroClosesTheBorderAndFiresCompletionOnce() {
        controller.start(GAME_DURATION);
        scheduler.advance(100);
        controller.syncToGameTimer(0);
        assertEquals(0, border.getRadius(), EPS);
        assertEquals(MAP_X, border.getCenterX(), EPS);
        assertEquals(MAP_Z, border.getCenterZ(), EPS);
        assertEquals(3, controller.getCurrentPhase());
        assertNull(controller.getTargetCenter());
        assertFalse(controller.isShrinking());
        assertEquals(0, controller.getSubPhaseRemaining());
        assertEquals(0, scheduler.pending());
        // landing in the closed state by resync counts as finishing every phase
        assertEquals(1, completions);
        // and a second resync into the same state does not fire it again
        controller.syncToGameTimer(0);
        scheduler.advance(5000);
        assertEquals(1, completions);
    }

    @Test
    void pauseFreezesTheWaitAndResumeFinishesIt() {
        controller.start(GAME_DURATION);
        scheduler.advance(200);
        assertEquals(50, controller.getSubPhaseRemaining());

        controller.pause();
        assertEquals(0, scheduler.pending(), "a paused wait holds no task");
        scheduler.advance(500);
        assertEquals(50, controller.getSubPhaseRemaining());
        assertFalse(controller.isShrinking());

        controller.resume();
        assertEquals(50, controller.getSubPhaseRemaining());
        scheduler.advance(999);
        assertFalse(controller.isShrinking());
        scheduler.advance(1);
        assertTrue(controller.isShrinking());
        assertEquals(1, callbacks.shrinkStarts);
    }

    @Test
    void pauseMidShrinkFreezesTheRadiusAndResumeRestartsTheHudFromTheFrozenRemainder() {
        controller.start(GAME_DURATION);
        scheduler.advance(1500);
        assertEquals(150, border.getRadius(), EPS);
        assertEquals(15, controller.getSubPhaseRemaining());

        controller.pause();
        scheduler.advance(100);
        assertEquals(150, border.getRadius(), EPS);
        assertEquals(15, controller.getSubPhaseRemaining());

        controller.resume();
        // the 100 paused ticks must not read as elapsed - the countdown resumes from the frozen 15s
        assertEquals(15, controller.getSubPhaseRemaining());
        scheduler.advance(200);
        assertEquals(5, controller.getSubPhaseRemaining());
        assertTrue(controller.isShrinking());
        assertEquals(0, controller.getCurrentPhase());
        scheduler.advance(100);
        assertEquals(100, border.getRadius(), EPS);
        assertEquals(1, controller.getCurrentPhase());
    }

    @Test
    void stopCancelsThePendingWaitAndStartRestartsAtPhaseOne() {
        controller.start(GAME_DURATION);
        scheduler.advance(100);
        assertEquals(1, scheduler.pending());

        controller.stop();
        assertEquals(0, scheduler.pending());
        scheduler.advance(5000);
        assertEquals(1, callbacks.phaseStarts.size());
        assertEquals(0, callbacks.shrinkStarts);
        assertEquals(0, completions);
        assertEquals(0, controller.getCurrentPhase(), "stop leaves the cursor where it was");

        controller.syncToGameTimer(50);
        assertEquals(0, controller.getCurrentPhase(), "a stopped controller ignores resyncs");
        assertEquals(INITIAL_RADIUS, border.getRadius(), EPS);

        controller.start(GAME_DURATION);
        assertEquals(0, controller.getCurrentPhase());
        assertFalse(controller.isShrinking());
        assertEquals(60, controller.getSubPhaseRemaining());
        assertEquals(List.of(new PhaseStart(1, 3, 60), new PhaseStart(1, 3, 60)), callbacks.phaseStarts);
        assertEquals(1, scheduler.pending());
        scheduler.advance(1200);
        assertTrue(controller.isShrinking());
    }

    @Test
    void stopMidShrinkFreezesTheBorderWhereItIs() {
        controller.start(GAME_DURATION);
        scheduler.advance(1500);
        assertEquals(150, border.getRadius(), EPS);
        controller.stop();
        // the in-flight shrink is cancelled with the wait, so nothing is left running and the border stays put
        assertEquals(0, scheduler.pending());
        scheduler.advance(300);
        assertEquals(150, border.getRadius(), EPS);
        assertEquals(0, controller.getCurrentPhase());
        scheduler.advance(5000);
        assertEquals(1, callbacks.phaseStarts.size());
        assertEquals(0, completions);
    }

    @Test
    void removeStopsAnAttachedControllerTheSameWayAsStop() {
        controller.start(GAME_DURATION);
        scheduler.advance(100);
        assertEquals(1, scheduler.pending());

        border.remove();
        assertEquals(0, scheduler.pending());
        scheduler.advance(5000);
        assertEquals(INITIAL_RADIUS, border.getRadius(), EPS);
        assertEquals(1, callbacks.phaseStarts.size());
        assertEquals(0, callbacks.shrinkStarts);
        assertEquals(0, completions);
        assertEquals(0, controller.getCurrentPhase());
    }

    @Test
    void constructingASecondControllerOnTheBorderStopsTheFirstWithoutStartingItself() {
        controller.start(GAME_DURATION);
        scheduler.advance(100);
        assertEquals(1, scheduler.pending());

        BorderPhaseController second = new BorderPhaseController(plugin, border, PHASES, MAP_X, MAP_Z, INITIAL_RADIUS)
            .withFixedCenter(true);
        assertEquals(0, scheduler.pending(), "the constructor stops the first controller, dropping its pending wait");
        assertEquals(-1, second.getCurrentPhase());
        assertFalse(second.isShrinking());
        assertNull(second.getTargetCenter());
        assertEquals(-1, second.getTargetRadius(), EPS);
        assertEquals(0, second.getSubPhaseRemaining());

        scheduler.advance(5000);
        assertEquals(0, callbacks.shrinkStarts, "the first controller's wait never lands, so it never begins its shrink");
        assertEquals(1, callbacks.phaseStarts.size());
        assertEquals(0, completions);
        assertEquals(INITIAL_RADIUS, border.getRadius(), EPS);
        assertEquals(0, controller.getCurrentPhase(), "the stopped first controller keeps its cursor");
        assertEquals(-1, second.getCurrentPhase(), "construction alone starts nothing");

        controller.syncToGameTimer(50);
        assertEquals(0, controller.getCurrentPhase(), "the first controller is stopped, so it ignores resyncs");
        assertEquals(INITIAL_RADIUS, border.getRadius(), EPS);

        second.start(GAME_DURATION);
        assertEquals(0, second.getCurrentPhase());
        assertEquals(1, scheduler.pending());
        assertEquals(List.of(new PhaseStart(1, 3, 60), new PhaseStart(1, 3, 60)), callbacks.phaseStarts);
        scheduler.advance(1200);
        assertTrue(second.isShrinking());
        assertEquals(1, callbacks.shrinkStarts);
        assertEquals(0, controller.getCurrentPhase());
        assertFalse(controller.isShrinking(), "the border now belongs to the second controller");
    }

    @Test
    void startWithoutStopFreezesTheInFlightShrinkAndRestartsAtPhaseOneWait() {
        controller.start(GAME_DURATION);
        scheduler.advance(1500);
        assertEquals(150, border.getRadius(), EPS);
        assertTrue(controller.isShrinking());
        assertEquals(1, scheduler.pending(), "the shrink's animator task is the only thing running");
        assertEquals(100, border.getPatternRadius(), EPS, "an in-flight shrink anchors the pattern to its end radius");

        controller.start(GAME_DURATION);
        assertEquals(1, scheduler.pending(), "the animator task is gone and only the new wait remains");
        assertEquals(150, border.getPatternRadius(), EPS, "nothing in flight, so the pattern anchors to the live radius");
        assertEquals(150, border.getRadius(), EPS);
        assertEquals(15, border.getCenterX(), EPS);
        assertEquals(20, border.getCenterZ(), EPS);
        assertEquals(0, controller.getCurrentPhase());
        assertFalse(controller.isShrinking());
        assertEquals(60, controller.getSubPhaseRemaining());
        assertEquals(new BorderPoint(MAP_X, MAP_Z), controller.getTargetCenter());
        assertEquals(List.of(new PhaseStart(1, 3, 60), new PhaseStart(1, 3, 60)), callbacks.phaseStarts);
        assertEquals(1, callbacks.shrinkStarts);

        // the old shrink had 300 ticks left, so this crosses where it would have landed and advanced the phase - the new wait has 600 more
        scheduler.advance(600);
        assertEquals(150, border.getRadius(), EPS);
        assertEquals(15, border.getCenterX(), EPS);
        assertEquals(20, border.getCenterZ(), EPS);
        assertEquals(0, controller.getCurrentPhase());
        assertFalse(controller.isShrinking());
        assertEquals(30, controller.getSubPhaseRemaining());
        assertEquals(2, callbacks.phaseStarts.size());
        assertEquals(1, callbacks.shrinkStarts);
        assertEquals(0, completions);

        scheduler.advance(600);
        assertTrue(controller.isShrinking());
        assertEquals(2, callbacks.shrinkStarts);
        assertEquals(0, controller.getCurrentPhase());
    }

    // Phase and shrink notifications in the order the controller fired them.
    private static final class RecordingCallbacks implements BorderCallbacks {

        final List<PhaseStart> phaseStarts = new ArrayList<>();
        int shrinkStarts;

        @Override
        public void phaseStarted(int phase, int total, int waitSeconds) {
            phaseStarts.add(new PhaseStart(phase, total, waitSeconds));
        }

        @Override
        public void shrinkStarted() {
            shrinkStarts++;
        }
    }

    private record PhaseStart(int phase, int total, int waitSeconds) {}
}
