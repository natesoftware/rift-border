package com.natesoftware.riftborder;

/**
 * How the border wall is rendered to a given player. Resolved per player by the function passed to
 * {@link GameBorder#withRenderModeResolver}, re-evaluated on every visibility pass (every 10 ticks) and every particle pass
 * (every 40 ticks). When {@link BorderCallbacks#wallItemModel()} returns null no pack is available, the resolver is never
 * consulted and every player gets {@link #PARTICLE}.
 */
public enum BorderRenderMode {
    /**
     * Shader cylinder on the wall display entities. Requires the client to render the resource pack's core shader, so it is only
     * offered while a pack is configured. The default for every player whenever a pack is configured and the resolver returns null.
     */
    SHADER,
    /**
     * Server-driven particle wall patch near the player, for clients that can't render the shader cylinder. Dust on the arc of
     * wall nearest the player, refreshed every 40 ticks, needing nothing beyond the jar. Forced for everyone when no pack is
     * configured.
     */
    PARTICLE
}
