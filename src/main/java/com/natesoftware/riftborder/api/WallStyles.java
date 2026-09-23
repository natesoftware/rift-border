package com.natesoftware.riftborder.api;

import java.util.UUID;

import org.bukkit.Bukkit;

/**
 * Each player's {@link WallStyle} on this server, and whether the shader wall can be shown here at all. Provided by the
 * RiftBorder plugin through Bukkit's services manager, which knows who loaded the pack and what each player chose. Every
 * {@link GameBorder} looks it up once at {@link GameBorder#spawn(double)}, so a host never wires it. A border works without
 * one, rendering particles for everyone, and a border's own {@link GameBorder#withShaderWall()} or
 * {@link GameBorder#withWallStyleResolver} still overrides what it says. Hosts read it to show or change a player's style, for
 * instance from a settings menu of their own.
 */
public interface WallStyles {

    /** The instance registered on this server, or null when the RiftBorder plugin is not installed. */
    static WallStyles get() {
        return Bukkit.getServicesManager().load(WallStyles.class);
    }

    /** Whether players on this server can have the rift-border pack at all, delivered by the plugin or by another plugin's pack. */
    boolean shaderWallAvailable();

    /**
     * The style this player sees right now: their chosen style, except the particle wall whenever their client has not loaded
     * the pack. Consulted by every border on every visibility pass, so it must be cheap and main-thread safe.
     */
    WallStyle styleFor(UUID player);

    /** The style this player chose, {@link WallStyle#SHADER} unless they switched. */
    WallStyle chosenStyle(UUID player);

    /** Stores this player's chosen style, kept across restarts. Live borders pick it up on their next visibility pass. */
    void setChosenStyle(UUID player, WallStyle style);
}
