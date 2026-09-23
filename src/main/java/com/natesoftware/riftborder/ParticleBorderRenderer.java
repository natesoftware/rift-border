package com.natesoftware.riftborder;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

// Renders the border wall as a per-player particle patch for players in PARTICLE render mode.
// Unlike the shader cylinder this only shows the arc of wall near the player - geometry is recomputed
// every pass because the radius and center animate continuously during shrinks.
final class ParticleBorderRenderer {

    // Refresh cadence, shared with the other border particle cues; dust lives about a second, so
    // the wall pulses gently between passes.
    private static final int UPDATE_INTERVAL_TICKS = 40;
    // The grid is tuned by the border's current radius alone: tight endgame zones get a dense fine
    // grid, wide early-game zones a sparse bold one. Uniform square grid per pass, both axes.
    private static final double RADIUS_DENSE = 32.0;    // at or below this radius the grid is densest
    private static final double RADIUS_SPARSE = 256.0;  // at or above this radius it is sparsest
    private static final double SPACING_DENSE = 2.0;
    private static final double SPACING_SPARSE = 12.0;
    private static final float DUST_SIZE_DENSE = 2.0f;
    private static final float DUST_SIZE_SPARSE = 4.0f;
    // Vertical half-extent of the patch around the player's Y.
    private static final double COLUMN_HALF_HEIGHT = 32.0;
    // Hard cap on particles sent to one player in one pass.
    private static final int MAX_PARTICLES_PER_PASS = 3000;

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
        Color color = border.wallColor();
        for (Player player : border.world.getPlayers()) {
            if (border.renderModeFor(player.getUniqueId()) != BorderRenderMode.PARTICLE) continue;
            renderPatchFor(player, radius, color);
        }
    }

    private void renderPatchFor(Player player, double radius, Color color) {
        double px = player.getLocation().getX();
        double py = player.getLocation().getY();
        double pz = player.getLocation().getZ();
        double cx = border.getCenterX();
        double cz = border.getCenterZ();

        double f = Math.clamp((radius - RADIUS_DENSE) / (RADIUS_SPARSE - RADIUS_DENSE), 0.0, 1.0);
        double spacing = SPACING_DENSE + (SPACING_SPARSE - SPACING_DENSE) * f;
        float size = (float) (DUST_SIZE_DENSE + (DUST_SIZE_SPARSE - DUST_SIZE_DENSE) * f);
        Particle.DustOptions dust = new Particle.DustOptions(color, size);

        // The lattice is fixed to the border, not the viewer: columns sit at whole multiples of the
        // angle step around the circle, rows at whole multiples of the spacing in world Y. Only the
        // vertical window and the near-first spawn order follow the player, so the budget is spent
        // on the wall they are facing.
        double angleStep = spacing / radius;
        int totalColumns = Math.max(1, (int) Math.floor(2.0 * Math.PI / angleStep));
        int centerColumn = (int) Math.round(Math.atan2(pz - cz, px - cx) / angleStep);

        double yMin = py - COLUMN_HALF_HEIGHT;
        double yMax = py + COLUMN_HALF_HEIGHT;
        double yStart = Math.ceil(yMin / spacing) * spacing;

        int sent = 0;
        for (int off = 0; off <= totalColumns / 2 && sent < MAX_PARTICLES_PER_PASS; off++) {
            sent += spawnColumn(player, cx, cz, radius, (centerColumn + off) * angleStep, yStart, yMax, spacing, dust, MAX_PARTICLES_PER_PASS - sent);
            if (off > 0 && off * 2 < totalColumns && sent < MAX_PARTICLES_PER_PASS) {
                sent += spawnColumn(player, cx, cz, radius, (centerColumn - off) * angleStep, yStart, yMax, spacing, dust, MAX_PARTICLES_PER_PASS - sent);
            }
        }
    }

    private int spawnColumn(
        Player player, double cx, double cz, double radius, double angle,
        double yStart, double yMax, double spacing, Particle.DustOptions dust, int budget) {
        double wx = cx + radius * Math.cos(angle);
        double wz = cz + radius * Math.sin(angle);
        int sent = 0;
        for (double y = yStart; y <= yMax && sent < budget; y += spacing) {
            // force=true so the far parts of the wall aren't culled by the client's 32-block particle limit.
            player.spawnParticle(Particle.DUST, wx, y, wz, 1, 0, 0, 0, 0, dust, true);
            sent++;
        }
        return sent;
    }
}
