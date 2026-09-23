package com.natesoftware.riftborder.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// How a border consults the RiftBorder plugin's WallStyles. Spawning with the shader wall on would reach the ItemStack registry,
// so the shader-on cases set packConfigured and wallStyles directly, exactly as spawn would have left them.
class WallStylesTest {

    private TestMocks.FakeScheduler scheduler;
    private Plugin plugin;
    private WallStyles styles;
    private GameBorder border;

    @BeforeEach
    void setUp() {
        scheduler = new TestMocks.FakeScheduler();
        plugin = TestMocks.plugin(scheduler);
        styles = mock(WallStyles.class);
        border = new GameBorder(plugin, TestMocks.world(), 0, 64, 0);
    }

    @AfterEach
    void tearDown() {
        border.remove();
    }

    private void registerWallStyles() {
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(WallStyles.class)).thenReturn(styles);
        when(plugin.getServer().getServicesManager()).thenReturn(services);
    }

    @Test
    void withoutThePluginTheBorderSpawnsOnParticles() {
        border.spawn(100);
        assertNull(border.wallStyles);
        assertFalse(border.packConfigured);
        assertEquals(WallStyle.PARTICLE, border.styleFor(UUID.randomUUID()));
    }

    @Test
    void wallStylesWithoutThePackKeepEveryoneOnParticlesAndAreNeverAskedPerPlayer() {
        registerWallStyles();
        when(styles.shaderWallAvailable()).thenReturn(false);
        border.spawn(100);
        assertSame(styles, border.wallStyles);
        assertFalse(border.packConfigured);
        assertEquals(WallStyle.PARTICLE, border.styleFor(UUID.randomUUID()));
        verify(styles, never()).styleFor(any());
    }

    @Test
    void eachPlayersChosenStyleDecidesWhenTheHostSetNoResolver() {
        UUID onParticles = UUID.randomUUID();
        UUID onShader = UUID.randomUUID();
        when(styles.styleFor(onParticles)).thenReturn(WallStyle.PARTICLE);
        when(styles.styleFor(onShader)).thenReturn(WallStyle.SHADER);
        border.wallStyles = styles;
        border.packConfigured = true;
        assertEquals(WallStyle.PARTICLE, border.styleFor(onParticles));
        assertEquals(WallStyle.SHADER, border.styleFor(onShader));
    }

    @Test
    void aHostResolverOverridesChosenStylesAndNullRestoresThem() {
        UUID player = UUID.randomUUID();
        when(styles.styleFor(player)).thenReturn(WallStyle.PARTICLE);
        border.wallStyles = styles;
        border.packConfigured = true;

        border.withWallStyleResolver(uuid -> WallStyle.SHADER);
        assertEquals(WallStyle.SHADER, border.styleFor(player));
        verify(styles, never()).styleFor(any());

        border.withWallStyleResolver(null);
        assertEquals(WallStyle.PARTICLE, border.styleFor(player));
    }

    @Test
    void aNullAnswerFromWallStylesMeansShader() {
        border.wallStyles = styles;
        border.packConfigured = true;
        assertEquals(WallStyle.SHADER, border.styleFor(UUID.randomUUID()));
    }

    @Test
    void aSpawnedBorderIsListedUntilRemoved() {
        assertFalse(GameBorder.activeBorders().contains(border));
        border.spawn(100);
        assertTrue(GameBorder.activeBorders().contains(border));
        assertSame(plugin, border.getPlugin());
        border.remove();
        assertFalse(GameBorder.activeBorders().contains(border));
    }

    @Test
    void theListIsASnapshot() {
        border.spawn(100);
        var snapshot = GameBorder.activeBorders();
        border.remove();
        assertTrue(snapshot.contains(border));
    }
}
