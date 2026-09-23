package com.natesoftware.riftborder;

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

// How a border consults the RiftBorder plugin's environment. Spawning with the shader wall on would reach the ItemStack registry,
// so the shader-on cases set packConfigured and environment directly, exactly as spawn would have left them.
class BorderEnvironmentTest {

    private TestMocks.FakeScheduler scheduler;
    private Plugin plugin;
    private BorderEnvironment environment;
    private GameBorder border;

    @BeforeEach
    void setUp() {
        scheduler = new TestMocks.FakeScheduler();
        plugin = TestMocks.plugin(scheduler);
        environment = mock(BorderEnvironment.class);
        border = new GameBorder(plugin, TestMocks.world(), 0, 64, 0).withCallbacks(new BorderCallbacks() {});
    }

    @AfterEach
    void tearDown() {
        border.remove();
    }

    private void registerEnvironment() {
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(BorderEnvironment.class)).thenReturn(environment);
        when(plugin.getServer().getServicesManager()).thenReturn(services);
    }

    @Test
    void withoutTheEnvironmentTheBorderSpawnsOnParticles() {
        border.spawn(100);
        assertNull(border.environment);
        assertFalse(border.packConfigured);
        assertEquals(BorderRenderMode.PARTICLE, border.renderModeFor(UUID.randomUUID()));
    }

    @Test
    void anEnvironmentWithoutThePackKeepsEveryoneOnParticlesAndIsNeverAskedPerPlayer() {
        registerEnvironment();
        when(environment.shaderWallAvailable()).thenReturn(false);
        border.spawn(100);
        assertSame(environment, border.environment);
        assertFalse(border.packConfigured);
        assertEquals(BorderRenderMode.PARTICLE, border.renderModeFor(UUID.randomUUID()));
        verify(environment, never()).renderModeFor(any());
    }

    @Test
    void theEnvironmentDecidesEachPlayersWallWhenTheHostSetNoResolver() {
        UUID onParticles = UUID.randomUUID();
        UUID onShader = UUID.randomUUID();
        when(environment.renderModeFor(onParticles)).thenReturn(BorderRenderMode.PARTICLE);
        when(environment.renderModeFor(onShader)).thenReturn(BorderRenderMode.SHADER);
        border.environment = environment;
        border.packConfigured = true;
        assertEquals(BorderRenderMode.PARTICLE, border.renderModeFor(onParticles));
        assertEquals(BorderRenderMode.SHADER, border.renderModeFor(onShader));
    }

    @Test
    void aHostResolverOverridesTheEnvironmentAndNullRestoresIt() {
        UUID player = UUID.randomUUID();
        when(environment.renderModeFor(player)).thenReturn(BorderRenderMode.PARTICLE);
        border.environment = environment;
        border.packConfigured = true;

        border.withRenderModeResolver(uuid -> BorderRenderMode.SHADER);
        assertEquals(BorderRenderMode.SHADER, border.renderModeFor(player));
        verify(environment, never()).renderModeFor(any());

        border.withRenderModeResolver(null);
        assertEquals(BorderRenderMode.PARTICLE, border.renderModeFor(player));
    }

    @Test
    void aNullAnswerFromTheEnvironmentMeansShader() {
        border.environment = environment;
        border.packConfigured = true;
        assertEquals(BorderRenderMode.SHADER, border.renderModeFor(UUID.randomUUID()));
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
