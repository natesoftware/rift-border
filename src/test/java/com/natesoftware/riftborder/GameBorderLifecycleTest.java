package com.natesoftware.riftborder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Spawn and remove without a resource pack: wallItemModel stays null, so the display grid is skipped and only the particle
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
    void withoutAPackEveryPlayerIsParticleModeAndTheResolverIsNeverAsked() {
        AtomicInteger asked = new AtomicInteger();
        border.withRenderModeResolver(uuid -> {
            asked.incrementAndGet();
            return BorderRenderMode.SHADER;
        });
        border.spawn(100);
        assertEquals(BorderRenderMode.PARTICLE, border.renderModeFor(UUID.randomUUID()));
        assertEquals(0, asked.get(), "with no pack the resolver is short-circuited, not consulted");
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
}
