package com.natesoftware.riftborder;

import java.util.UUID;

import org.bukkit.Bukkit;

/**
 * The server's side of the border wall, provided by the RiftBorder plugin through Bukkit's services manager: whether players
 * here can render the shader wall at all, and which wall each of them sees. Every {@link GameBorder} looks it up once at
 * {@link GameBorder#spawn(double)}, so a host never wires it. A border works without one, rendering particles for everyone,
 * and a host's own {@link GameBorder#withShaderWall()} or {@link GameBorder#withRenderModeResolver} still overrides what it
 * says. Hosts read it to show or change a player's choice, for instance from a settings menu of their own.
 */
public interface BorderEnvironment {

    /** The environment registered on this server, or null when the RiftBorder plugin is not installed. */
    static BorderEnvironment get() {
        return Bukkit.getServicesManager().load(BorderEnvironment.class);
    }

    /** Whether players on this server can have the rift-border pack at all, delivered by the plugin or by another plugin's pack. */
    boolean shaderWallAvailable();

    /**
     * The wall this player sees right now: their own choice, except the particle wall whenever their client has not loaded the
     * pack. Consulted by every border on every visibility pass, so it must be cheap and main-thread safe.
     */
    BorderRenderMode renderModeFor(UUID player);

    /** The wall this player chose, {@link BorderRenderMode#SHADER} unless they switched. */
    BorderRenderMode preference(UUID player);

    /** Stores this player's choice, kept across restarts. Live borders pick it up on their next visibility pass. */
    void setPreference(UUID player, BorderRenderMode mode);
}
