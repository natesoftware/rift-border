package com.natesoftware.riftborder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Spawn and remove without the shader wall: the display grid is skipped and only the particle
// and damage tasks run. Nothing here reaches a Bukkit registry.
class GameBorderLifecycleTest {

    private static final double EPS = 1e-9;

    private TestMocks.FakeScheduler scheduler;
    private Plugin plugin;
    private GameBorder border;

    @BeforeEach
    void setUp() {
        scheduler = new TestMocks.FakeScheduler();
        plugin = TestMocks.plugin(scheduler);
        border = new GameBorder(plugin, TestMocks.world(), 10, 64, -20)
            .withCallbacks(new BorderCallbacks() {});
    }

    @Test
    void spawnWithoutCallbacksThrowsAndNamesWithCallbacks() {
        GameBorder bare = new GameBorder(plugin, TestMocks.world(), 0, 64, 0);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bare.spawn(100));
        assertTrue(e.getMessage().contains("withCallbacks"), e.getMessage());
        assertFalse(bare.isActive());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void spawningTwiceThrowsAndLeavesTheFirstSpawnRunning() {
        border.spawn(100);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> border.spawn(100));
        assertTrue(e.getMessage().contains("already spawned"), e.getMessage());
        assertTrue(border.isActive());
        assertEquals(2, scheduler.pending(), "the refused second spawn must not start extra tasks");
    }

    @Test
    void isActiveFollowsSpawnAndRemove() {
        assertFalse(border.isActive());
        border.spawn(100);
        assertTrue(border.isActive());
        border.remove();
        assertFalse(border.isActive());
    }

    @Test
    void spawnStartsOnlyTheParticleAndDamageTasksAndRemoveCancelsBoth() {
        assertEquals(0, scheduler.pending());
        border.spawn(100);
        assertEquals(2, scheduler.pending());
        border.remove();
        assertEquals(0, scheduler.pending());
    }

    @Test
    void withoutTheShaderWallEveryPlayerIsParticleModeAndTheResolverIsNeverAsked() {
        AtomicInteger asked = new AtomicInteger();
        border.withRenderModeResolver(uuid -> {
            asked.incrementAndGet();
            return BorderRenderMode.SHADER;
        });
        border.spawn(100);
        assertEquals(BorderRenderMode.PARTICLE, border.renderModeFor(UUID.randomUUID()));
        assertEquals(0, asked.get(), "without the shader wall the resolver is short-circuited, not consulted");
    }

    @Test
    void optingIntoTheShaderWallAfterSpawnChangesNothingUntilTheNextSpawn() {
        border.withRenderModeResolver(uuid -> BorderRenderMode.SHADER);
        border.spawn(100);
        border.withShaderWall();
        // no display grid was spawned, so the live border must keep everyone on particles
        assertEquals(BorderRenderMode.PARTICLE, border.renderModeFor(UUID.randomUUID()));
    }

    @Test
    void respawnAfterRemoveResetsTheWholeShape() {
        border.spawn(200);
        border.setPosition(40, 40, 30, 100, 20);
        assertTrue(border.hasHeightLimit());
        assertTrue(border.hasMinHeight());

        border.remove();
        border.spawn(150);
        assertEquals(10, border.getCenterX(), EPS);
        assertEquals(-20, border.getCenterZ(), EPS);
        assertEquals(150, border.getRadius(), EPS);
        assertFalse(border.hasHeightLimit());
        assertFalse(border.hasMinHeight());
        assertEquals(GameBorder.NO_HEIGHT_LIMIT, border.getMaxHeight());
        assertEquals(GameBorder.NO_MIN_HEIGHT, border.getMinHeight());
    }

    @Test
    void removeStopsAnAttachedControllerBeforeItsWaitEnds() {
        AtomicInteger shrinks = new AtomicInteger();
        border.withCallbacks(new BorderCallbacks() {
            @Override
            public void shrinkStarted() {
                shrinks.incrementAndGet();
            }
        });
        border.spawn(200);
        BorderPhaseController controller = new BorderPhaseController(
            plugin, border, List.of(new BorderPhase(1, 1, 100, 0)), 10, -20, 200);
        controller.start(60);
        assertEquals(0, controller.getCurrentPhase());
        assertEquals(3, scheduler.pending(), "particle, damage and the phase wait");

        border.remove();
        assertEquals(0, scheduler.pending(), "remove() cancels the controller's wait along with its own tasks");
        scheduler.advance(60);
        assertEquals(0, shrinks.get());
        assertEquals(200, border.getRadius(), EPS);
    }

    // The mirror of the test above, so a controller that never fired cannot be mistaken for one that was stopped.
    @Test
    void anAttachedControllerLeftAloneShrinksOnceItsWaitEnds() {
        AtomicInteger shrinks = new AtomicInteger();
        border.withCallbacks(new BorderCallbacks() {
            @Override
            public void shrinkStarted() {
                shrinks.incrementAndGet();
            }
        });
        border.spawn(200);
        BorderPhaseController controller = new BorderPhaseController(
            plugin, border, List.of(new BorderPhase(1, 1, 100, 0)), 10, -20, 200);
        controller.start(60);

        scheduler.advance(19);
        assertEquals(0, shrinks.get());
        scheduler.advance(1);
        assertEquals(1, shrinks.get());
        assertTrue(controller.isShrinking());
        scheduler.advance(20);
        assertEquals(100, border.getRadius(), EPS);
        assertFalse(controller.isShrinking());
    }

    @Test
    void zeroDamageBeforeSpawnStillStartsTheDamageTracker() {
        border.setDamagePerSecond(0);
        border.spawn(100);
        assertEquals(0, border.getDamagePerSecond(), EPS);
        assertEquals(2, scheduler.pending(), "the tracker owns warnings and sounds too, so rate 0 does not skip it");
    }

    @Test
    void moveToBeforeSpawnAnimatesWithoutActivatingTheBorder() {
        border.setPosition(0, 0, 200);
        border.moveTo(0, 0, 100, 10);
        assertFalse(border.isActive());
        assertEquals(1, scheduler.pending(), "only the animator task, no particle or damage task");
        scheduler.advance(5);
        assertEquals(150, border.getRadius(), EPS);
        scheduler.advance(5);
        assertEquals(100, border.getRadius(), EPS);
        assertEquals(0, scheduler.pending());
        assertFalse(border.isActive());
    }

    // The indicator's 40-tick pulse builds Particle.DustOptions, which needs a live registry, so nothing below advances the
    // clock while one is running. Registration and task counts are all these tests need.
    private BorderPhaseController attachController() {
        return new BorderPhaseController(plugin, border, List.of(new BorderPhase(1, 1, 50, 0)), 10, -20, 100);
    }

    @Test
    void constructingAnIndicatorRegistersItOnTheControllersBorderWithoutStartingAnything() {
        border.spawn(100);
        BorderPhaseController controller = attachController();
        NextBorderIndicator indicator = new NextBorderIndicator(controller);
        assertSame(indicator, border.indicator);
        assertEquals(2, scheduler.pending(), "construction schedules nothing, only start() does");
    }

    @Test
    void removeStopsAnAttachedIndicator() {
        border.spawn(100);
        NextBorderIndicator indicator = new NextBorderIndicator(attachController());
        assertEquals(2, scheduler.pending(), "particle and damage");
        indicator.start();
        assertEquals(3, scheduler.pending(), "start() adds exactly the pulse task");

        border.remove();
        assertEquals(0, scheduler.pending(), "remove() cancels the pulse along with its own two tasks");
    }

    @Test
    void removeOnANeverSpawnedBorderStillStopsItsIndicator() {
        NextBorderIndicator indicator = new NextBorderIndicator(attachController());
        indicator.start();
        assertEquals(1, scheduler.pending(), "only the pulse task, the border itself started nothing");
        border.remove();
        assertEquals(0, scheduler.pending());
        assertFalse(border.isActive());
    }

    @Test
    void removeStopsARunningControllerAndItsIndicatorTogether() {
        border.spawn(100);
        BorderPhaseController controller = attachController();
        NextBorderIndicator indicator = new NextBorderIndicator(controller);
        controller.start(60);
        assertEquals(3, scheduler.pending(), "particle, damage and the phase wait");
        indicator.start();
        assertEquals(4, scheduler.pending(), "plus the pulse");

        border.remove();
        assertEquals(0, scheduler.pending());
        assertEquals(0, controller.getCurrentPhase(), "stop() leaves the phase index where it was");
        assertEquals(100, border.getRadius(), EPS);
    }

    @Test
    void constructingASecondIndicatorOnTheSameControllerStopsTheFirst() {
        border.spawn(100);
        BorderPhaseController controller = attachController();
        NextBorderIndicator first = new NextBorderIndicator(controller);
        first.start();
        assertEquals(3, scheduler.pending());

        NextBorderIndicator second = new NextBorderIndicator(controller);
        assertEquals(2, scheduler.pending(), "construction alone stops the indicator it replaces");
        assertSame(second, border.indicator);
        second.start();
        assertEquals(3, scheduler.pending());

        border.remove();
        assertEquals(0, scheduler.pending(), "remove() stops the replacement");
    }

    // Registration is a single slot: the border only ever stops the indicator registered last. A replaced indicator started
    // again by hand runs untracked, so remove() leaves it pulsing and only its own stop() ends it. Current behaviour, documented.
    @Test
    void aReplacedIndicatorRestartedByHandOutlivesRemove() {
        border.spawn(100);
        BorderPhaseController controller = attachController();
        NextBorderIndicator first = new NextBorderIndicator(controller);
        NextBorderIndicator second = new NextBorderIndicator(controller);
        second.start();
        first.start();
        assertEquals(4, scheduler.pending());

        border.remove();
        assertEquals(1, scheduler.pending(), "the orphaned first indicator's pulse survives remove()");
        first.stop();
        assertEquals(0, scheduler.pending());
    }

    @Test
    void theThreeArgConstructorRegistersTheSameWayAndSchedulesOnTheBordersPlugin() {
        border.spawn(100);
        BorderPhaseController controller = attachController();
        NextBorderIndicator first = new NextBorderIndicator(controller);
        first.start();
        assertEquals(3, scheduler.pending());

        // A foreign plugin and world are accepted for source compatibility but ignored: the task lands on the border's scheduler.
        TestMocks.FakeScheduler other = new TestMocks.FakeScheduler();
        NextBorderIndicator indicator = new NextBorderIndicator(TestMocks.plugin(other), TestMocks.world(), controller);
        assertSame(indicator, border.indicator);
        assertEquals(2, scheduler.pending(), "the 3-arg constructor replaces and stops the 1-arg indicator too");
        indicator.start();
        assertEquals(3, scheduler.pending());
        assertEquals(0, other.pending());

        border.remove();
        assertEquals(0, scheduler.pending());
        assertEquals(0, other.pending());
    }

    @Test
    void stopIsSafeBeforeStartAndWhenRepeated() {
        border.spawn(100);
        NextBorderIndicator indicator = new NextBorderIndicator(attachController());
        indicator.stop();
        assertEquals(2, scheduler.pending(), "stop() before start() touches nothing");

        indicator.start();
        assertEquals(3, scheduler.pending());
        indicator.stop();
        assertEquals(2, scheduler.pending());
        indicator.stop();
        assertEquals(2, scheduler.pending(), "a second stop() is a no-op");

        indicator.start();
        assertEquals(3, scheduler.pending(), "an indicator can be started again after stop()");
        border.remove();
        assertEquals(0, scheduler.pending());
    }

    @Test
    void startWhileRunningRestartsThePulseWithoutStackingTasks() {
        border.spawn(100);
        NextBorderIndicator indicator = new NextBorderIndicator(attachController());
        indicator.start();
        indicator.start();
        assertEquals(3, scheduler.pending(), "the second start() replaces the first task rather than adding one");
        border.remove();
        assertEquals(0, scheduler.pending());
    }
}
