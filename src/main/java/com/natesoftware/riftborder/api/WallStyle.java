package com.natesoftware.riftborder.api;

/**
 * Which wall a player sees. Decided per player on every visibility pass (every 10 ticks) and every particle pass (every 20
 * ticks): by the border's own {@link GameBorder#withWallStyleResolver} when it has one, otherwise by the player's choice in
 * {@link WallStyles}, and {@link #SHADER} when neither answers. While the shader wall is off, because the server has no
 * rift-border pack, every player gets {@link #PARTICLE} and neither is asked.
 */
public enum WallStyle {
    /**
     * The solid shader cylinder, drawn by the rift-border resource pack on the border's display anchors. Only possible for a
     * client that has loaded the pack, and every player's style until they choose otherwise.
     */
    SHADER,
    /**
     * A particle wall on the arc nearest the player, refreshed every 20 ticks, needing nothing beyond the server. For players
     * without the pack, and for anyone who prefers it.
     */
    PARTICLE
}
