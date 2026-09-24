package com.natesoftware.riftborder.api;

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

import java.time.Duration;
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
import org.mockito.ArgumentCaptor;

// Drives the damage tracker through the fake scheduler with a hand-turned clock and checks the warning machinery around one player.
// Damage stays at 0 throughout: the hurt path reaches Sound.ENTITY_PLAYER_HURT, a registry-backed constant no test server can load.
class BorderDamageTrackerTest {

    // Set explicitly and distinct, so these tests follow the tracker rather than the library's default sounds.
    private static final String ENTER_SOUND = "test:border.enter";
    private static final String LONG_SOUND = "test:border.long";
    private static final UUID ID = UUID.randomUUID();
    private static final Component SUBTITLE = Component.text("Outside the border");

    private final long[] clockMs = {0};
    private final List<UUID> shown = new ArrayList<>();
    private final List<UUID> cleared = new ArrayList<>();
    private final BorderEvents recorder = new BorderEvents() {
        @Override
        public void onWarningShown(UUID uuid) {
            shown.add(uuid);
        }

        @Override
        public void onWarningCleared(UUID uuid) {
            cleared.add(uuid);
        }
    };

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

        border = new GameBorder(plugin, world, 0, 64, 0)
            .withTheme(new TestTheme(SUBTITLE))
            .withEvents(recorder);
        border.setDamagePerSecond(0);
        tracker = border.damageTracker;
        tracker.clock = () -> clockMs[0];
        moveTo(0, 64, 0);
        border.spawn(100);
        // the particle wall is not under test, and its 20-tick pass would reach Particle.DUST, a registry-backed enum
        border.particleRenderer.stop();
    }

    private Location moveTo(double x, double y, double z) {
        location = new Location(world, x, y, z);
        return location;
    }

    // Tears the setUp border down and spawns a second one on the same world whose theme has no warning subtitle,
    // so exactly one tracker scans the player and it never sends a title.
    private GameBorder spawnBorderWithoutWarningSubtitle() {
        border.remove();
        GameBorder bare = new GameBorder(border.plugin, world, 0, 64, 0).withTheme(new TestTheme(null)).withEvents(recorder);
        bare.setDamagePerSecond(0);
        bare.damageTracker.clock = () -> clockMs[0];
        bare.spawn(100);
        bare.particleRenderer.stop();
        return bare;
    }

    @Test
    void theFirstTickOutsideFlashesTheSubtitleOnceAndNeverResendsIt() {
        Location outside = moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        verify(player).playSound(same(outside), eq(ENTER_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));
        ArgumentCaptor<Title> sent = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(sent.capture());
        // the warning is the subtitle under an empty title: straight in, one second on screen, a half-second fade
        assertEquals(Component.empty(), sent.getValue().title());
        assertEquals(SUBTITLE, sent.getValue().subtitle());
        Title.Times times = sent.getValue().times();
        assertEquals(Duration.ZERO, times.fadeIn());
        assertEquals(Duration.ofSeconds(1), times.stay());
        assertEquals(Duration.ofMillis(500), times.fadeOut());

        // ticks 20, 40 and 60 are damage checks, and staying outside never sends it again
        scheduler.advance(60);
        assertEquals(List.of(ID), shown);
        verify(player).playSound(any(Location.class), eq(ENTER_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));
        verify(player, times(1)).showTitle(any(Title.class));
        assertTrue(cleared.isEmpty());
    }

    @Test
    void steppingBackInsideEndsTheWarningAndTheNextCrossingIsAFreshEntry() {
        moveTo(150, 64, 0);
        scheduler.advance(1);
        moveTo(0, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        assertEquals(List.of(ID), cleared);
        // the subtitle fades on its own, so coming back inside leaves the screen alone
        verify(player, never()).clearTitle();
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
        verify(player, never()).clearTitle();

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
        verify(player, never()).clearTitle();
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
        verify(player, never()).clearTitle();
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
        // the subtitle went out once, on the crossing, and the damage checks never re-send it
        verify(player, times(1)).showTitle(any(Title.class));
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

    @Test
    void withNoWarningSubtitleAParticipantDropoutIsPurgedWithoutClearingTheTitle() {
        GameBorder bare = spawnBorderWithoutWarningSubtitle();
        participants = Set.of(ID);
        bare.withParticipants(() -> participants);
        moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        verify(player).playSound(any(Location.class), eq(ENTER_SOUND), eq(SoundCategory.MASTER), eq(0.5f), eq(1.0f));
        verify(player, never()).showTitle(any(Title.class));

        participants = Set.of();
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
        verify(player, never()).clearTitle();
        verify(player, never()).stopSound(anyString(), any(SoundCategory.class));

        // purged and filtered out from then on, so a later scan neither re-warns nor clears again
        scheduler.advance(20);
        assertEquals(List.of(ID), shown);
        assertEquals(List.of(ID), cleared);
        verify(player, never()).clearTitle();
    }

    @Test
    void withNoWarningSubtitleAnOfflinePlayerIsPurgedWithoutClearingTheTitle() {
        spawnBorderWithoutWarningSubtitle();
        moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        verify(player, never()).showTitle(any(Title.class));

        when(player.isOnline()).thenReturn(false);
        when(world.getPlayers()).thenReturn(List.of());
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
        verify(player, never()).clearTitle();
        verify(player, never()).stopSound(anyString(), any(SoundCategory.class));
    }

    @Test
    void withAWarningSubtitleAParticipantDropoutLeavesTheScreenAlone() {
        participants = Set.of(ID);
        border.withParticipants(() -> participants);
        moveTo(150, 64, 0);
        scheduler.advance(1);
        assertEquals(List.of(ID), shown);
        verify(player).showTitle(any(Title.class));

        participants = Set.of();
        scheduler.advance(1);
        assertEquals(List.of(ID), cleared);
        verify(player, never()).clearTitle();

        // the player is no longer tracked, so later scans neither re-send the subtitle nor clear anything
        scheduler.advance(20);
        assertEquals(List.of(ID), shown);
        assertEquals(List.of(ID), cleared);
        verify(player, never()).clearTitle();
        verify(player).showTitle(any(Title.class));
    }

    // The test sounds, and the given warning subtitle, which may be null for none.
    private record TestTheme(Component warningSubtitle) implements BorderTheme {
        @Override
        public String enterSound() {
            return ENTER_SOUND;
        }

        @Override
        public String enterLongSound() {
            return LONG_SOUND;
        }
    }
}
