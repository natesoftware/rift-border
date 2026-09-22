package com.natesoftware.riftborder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Drives the damage tracker through the fake scheduler with a hand-turned clock and checks the warning machinery around one player.
// Damage stays at 0 throughout: the hurt path reaches Sound.ENTITY_PLAYER_HURT, a registry-backed constant no test server can load.
class BorderDamageTrackerTest {

    private static final String ENTER_SOUND = "minecraft:block.anvil.land";
    private static final String LONG_SOUND = "minecraft:entity.wither.spawn";
    private static final UUID ID = UUID.randomUUID();

    private final long[] clockMs = {0};
    private final List<UUID> shown = new ArrayList<>();
    private final List<UUID> cleared = new ArrayList<>();

    private TestMocks.FakeScheduler scheduler;
    private World world;
    private GameBorder border;
    private BorderDamageTracker tracker;
    private Player player;
    private Location location;
    private Set<UUID> participants;

    @BeforeEach
    void setUp() {
        scheduler = new TestMocks.FakeScheduler();
        world = TestMocks.world();
        Plugin plugin = TestMocks.plugin(scheduler);
        Server server = plugin.getServer();

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ID);
        when(player.getLocation()).thenAnswer(inv -> location);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getPlayers()).thenReturn(List.of(player));
        when(server.getPlayer(ID)).thenReturn(player);

        border = new GameBorder(plugin, world, 0, 64, 0).withCallbacks(new BorderCallbacks() {
            @Override
            public Component warningTitle() {
                return Component.text("Outside the border");
            }

            @Override
            public void onWarningShown(UUID uuid) {
                shown.add(uuid);
            }

            @Override
            public void onWarningCleared(UUID uuid) {
                cleared.add(uuid);
            }
        });
        border.setDamagePerSecond(0);
        tracker = border.damageTracker;
        tracker.clock = () -> clockMs[0];
        moveTo(0, 64, 0);
        border.spawn(100);
        // the particle wall is not under test, and its 40-tick pass would reach Particle.DUST, a registry-backed enum
        border.particleRenderer.stop();
    }

    private Location moveTo(double x, double y, double z) {
        location = new Location(world, x, y, z);
        return location;
    }

    @Test
    void theFirstTickOutsideWarnsOnceAndTheTitleOnlyRepeatsOnTheDamageCheck() {
        Location outside = moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        verify(player).playSound(same(outside), eq(ENTER_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));
        verify(player).showTitle(any(Title.class));

        scheduler.advance(18);
        assertEquals(List.of(ID), shown);
        verify(player).playSound(any(Location.class), eq(ENTER_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));
        verify(player).showTitle(any(Title.class));

        // tick 20 is a damage check, which re-shows the day-long title without re-firing the callback or the sound
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        verify(player).playSound(any(Location.class), eq(ENTER_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));
        verify(player, times(2)).showTitle(any(Title.class));
        assertTrue(cleared.isEmpty());
    }

    @Test
    void steppingBackInsideClearsTheWarningAndTheNextCrossingIsAFreshEntry() {
        moveTo(150, 64, 0);
        scheduler.advance(1);
        moveTo(0, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        assertEquals(List.of(ID), cleared);
        verify(player).clearTitle();
        // the long sound never played, so there is nothing to stop
        verify(player, never()).stopSound(anyString(), any(SoundCategory.class));

        moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID, ID), shown);
        verify(player, times(2)).playSound(any(Location.class), eq(ENTER_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));
        verify(player, times(2)).showTitle(any(Title.class));
    }

    @Test
    void aCreativePlayerOutsideIsNeverWarned() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        moveTo(150, 64, 0);
        scheduler.advance(30);
        assertTrue(shown.isEmpty());
        assertTrue(cleared.isEmpty());
        verify(player, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        verify(player, never()).showTitle(any(Title.class));
    }

    @Test
    void aSpectatorWhoWasWarnedInSurvivalIsClearedOnTheModeSwitch() {
        moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);

        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
        verify(player).clearTitle();

        // still outside, still spectating: nothing re-warns
        scheduler.advance(30);
        assertEquals(List.of(ID), shown);
        assertEquals(List.of(ID), cleared);
    }

    @Test
    void aPlayerOutsideTheParticipantSetIsIgnored() {
        participants = Set.of(UUID.randomUUID());
        border.withParticipants(() -> participants);
        moveTo(150, 64, 0);
        scheduler.advance(30);
        assertTrue(shown.isEmpty());
        assertTrue(cleared.isEmpty());
        verify(player, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        verify(player, never()).showTitle(any(Title.class));
    }

    @Test
    void aWarnedPlayerDroppedFromTheParticipantSetIsPurged() {
        participants = Set.of(ID);
        border.withParticipants(() -> participants);
        moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);

        participants = Set.of();
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
        verify(player).clearTitle();
        verify(player, never()).stopSound(anyString(), any(SoundCategory.class));
    }

    @Test
    void aWarnedPlayerWhoGoesOfflineIsPurgedWithoutTouchingTheirScreen() {
        moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);

        // a quit player drops out of the world's list, and the server still resolves them but reports them offline
        when(player.isOnline()).thenReturn(false);
        when(world.getPlayers()).thenReturn(List.of());
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
        verify(player, never()).clearTitle();
        verify(player, never()).stopSound(anyString(), any(SoundCategory.class));
    }

    @Test
    void theLongOutsideSoundFiresOnceEveryFiveClockSecondsAndStopsOnReentry() {
        moveTo(150, 64, 0);
        clockMs[0] = 1_000;
        scheduler.advance(1);

        clockMs[0] = 5_999;
        scheduler.advance(1);
        verify(player, never()).playSound(any(Location.class), eq(LONG_SOUND), any(SoundCategory.class), anyFloat(), anyFloat());

        clockMs[0] = 6_000;
        scheduler.advance(1);
        verify(player).playSound(any(Location.class), eq(LONG_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));

        // the next one is measured from the last play, not from entry
        clockMs[0] = 10_999;
        scheduler.advance(1);
        verify(player).playSound(any(Location.class), eq(LONG_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));

        clockMs[0] = 11_000;
        scheduler.advance(1);
        verify(player, times(2)).playSound(any(Location.class), eq(LONG_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));

        moveTo(0, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
        verify(player).stopSound(LONG_SOUND, SoundCategory.MASTER);
    }

    @Test
    void stopClearsAPlayerStillOutsideAndCancelsTheScan() {
        moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(1, scheduler.pending());

        tracker.stop();
        assertEquals(List.of(ID), cleared);
        verify(player).clearTitle();
        // the long-outside sound never played, so there is nothing to stop
        verify(player, never()).stopSound(LONG_SOUND, SoundCategory.MASTER);
        assertEquals(0, scheduler.pending());

        // nothing scans any more, so the player still outside is not re-warned
        scheduler.advance(20);
        assertEquals(List.of(ID), shown);
    }

    @Test
    void zeroDamageNeverTouchesHealthOrTheHurtAnimationPastTheGrace() {
        moveTo(150, 64, 0);
        scheduler.advance(1);
        clockMs[0] = 60_000;
        // ticks 20 and 40 are damage checks, both well past the 1s grace
        scheduler.advance(41);
        assertEquals(List.of(ID), shown);
        assertTrue(cleared.isEmpty());
        verify(player, never()).getAbsorptionAmount();
        verify(player, never()).getHealth();
        verify(player, never()).setHealth(anyDouble());
        verify(player, never()).playHurtAnimation(anyFloat());
        // the title is still re-shown on each damage check even though no damage lands
        verify(player, times(3)).showTitle(any(Title.class));
    }

    @Test
    void aPlayerAboveTheCeilingIsWarnedInsideTheRadius() {
        border.setPosition(0, 0, 100, 80, GameBorder.NO_MIN_HEIGHT);
        moveTo(10, 90, 10);
        assertFalse(border.isOutside(10, 10));
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        verify(player).playSound(any(Location.class), eq(ENTER_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));

        // two ticks in total, so the 40-tick vertical indicator pass and its Particle.DUST never run
        moveTo(10, 70, 10);
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
    }

    @Test
    void aPlayerBelowTheFloorIsWarnedInsideTheRadius() {
        border.setPosition(0, 0, 100, GameBorder.NO_HEIGHT_LIMIT, 50);
        moveTo(10, 40, 10);
        assertFalse(border.isOutside(10, 10));
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);

        moveTo(10, 50, 10);
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
    }
}
