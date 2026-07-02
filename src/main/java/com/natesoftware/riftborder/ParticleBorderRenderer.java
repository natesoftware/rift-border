package com.natesoftware.riftborder;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

// Renders the border wall as a per-player particle patch for players in PARTICLE render mode.
// Unlike the shader cylinder this only shows the arc of wall near the player - geometry is recomputed
// every pass because the radius and center animate continuously during shrinks.
final class ParticleBorderRenderer {

    // Matches the 20-tick pulse of NextBorderIndicator and the vertical indicator dust.
    private static final int UPDATE_INTERVAL_TICKS = 20;
    // The wall renders while the player is within this XZ distance of the boundary, inside or outside.
    private static final double VIEW_DISTANCE = 48.0;
    // Arc length in blocks rendered to each side of the wall point nearest the player.
    private static final double ARC_HALF_LENGTH = 32.0;
    // Block spacing between particle columns along the arc.
    private static final double ARC_SPACING = 2.0;
    // Vertical half-extent of the patch around the player's Y.
    private static final double COLUMN_HALF_HEIGHT = 8.0;
    // Block spacing between particles within a column.
    private static final double COLUMN_SPACING = 2.0;
    // Hard cap on particles sent to one player in one pass.
    private static final int MAX_PARTICLES_PER_PASS = 300;
    private static final float DUST_SIZE = 1.5f;

    private final GameBorder border;

    private BukkitTask task;

    ParticleBorderRenderer(GameBorder border) {
        this.border = border;
    }

    void start() {
        stop();
        task = border.plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(border.plugin, this::tick, UPDATE_INTERVAL_TICKS, UPDATE_INTERVAL_TICKS);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        double radius = border.getRadius();
        if (radius <= 0) return;
        Particle.DustOptions dust = new Particle.DustOptions(particleColor(), DUST_SIZE);
        for (Player player : border.world.getPlayers()) {
            if (border.renderModeFor(player.getUniqueId()) != BorderRenderMode.PARTICLE) continue;
            renderPatchFor(player, radius, dust);
        }
    }

    private Color particleColor() {
        return border.callbacks != null ? border.callbacks.particleColor() : Color.WHITE;
    }

    private void renderPatchFor(Player player, double radius, Particle.DustOptions dust) {
        double px = player.getLocation().getX();
        double py = player.getLocation().getY();
        double pz = player.getLocation().getZ();
        double cx = border.getCenterX();
        double cz = border.getCenterZ();

        double dx = px - cx;
        double dz = pz - cz;
        double distFromCenter = Math.sqrt(dx * dx + dz * dz);
        if (Math.abs(distFromCenter - radius) > VIEW_DISTANCE) return;

        double nearestAngle = Math.atan2(dz, dx);
        double angleStep = ARC_SPACING / radius;
        int steps = (int) Math.ceil(ARC_HALF_LENGTH / ARC_SPACING);

        double yMin = py - COLUMN_HALF_HEIGHT;
        double yMax = py + COLUMN_HALF_HEIGHT;
        if (border.hasMinHeight()) yMin = Math.max(yMin, border.getMinHeight());
        if (border.hasHeightLimit()) yMax = Math.min(yMax, border.getMaxHeight());
        if (yMin > yMax) return;

        int sent = 0;
        for (int i = -steps; i <= steps && sent < MAX_PARTICLES_PER_PASS; i++) {
            double angle = nearestAngle + i * angleStep;
            double wx = cx + radius * Math.cos(angle);
            double wz = cz + radius * Math.sin(angle);
            for (double y = yMin; y <= yMax && sent < MAX_PARTICLES_PER_PASS; y += COLUMN_SPACING) {
                // force=true so the far edges of the patch aren't culled by the client's 32-block particle limit.
                player.spawnParticle(Particle.DUST, wx, y, wz, 1, 0, 0, 0, 0, dust, true);
                sent++;
            }
        }
    }
}
