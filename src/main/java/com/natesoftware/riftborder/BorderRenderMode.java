package com.natesoftware.riftborder;

// How the border wall is rendered to a given player.
public enum BorderRenderMode {
    // Shader cylinder on the wall display entities. Requires the client to render the resource pack's core shader.
    SHADER,
    // Server-driven particle wall patch near the player, for clients that can't render the shader cylinder.
    PARTICLE
}
