package com.natesoftware.riftborder;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

// Pulses a faint white particle ring once per second along the boundary of the BorderPhaseController's next phase target, so players can see...
public final class NextBorderIndicator {

    private static final int PULSE_TICKS = 40; // single-tick flash every two seconds
    private static final double PARTICLE_SPACING = 1.0;
    private static final double Y_OFFSET = 0.0;
    private static final double MAX_VIEW_DISTANCE_SQ = 80 * 80;

    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.WHITE, 1.0f);

    private final Plugin plugin;
    private final World world;
    private final BorderPhaseController phaseController;

    private double[] ringX;
    private double[] ringZ;
    private int lastPhase = -2;

    private BukkitTask task;

    public NextBorderIndicator(Plugin plugin, World world, BorderPhaseController phaseController) {
        this.plugin = plugin;
        this.world = world;
        this.phaseController = phaseController;
    }

    // Begins pulsing the indicator.
    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PULSE_TICKS, PULSE_TICKS);
    }

    // Stops the indicator and clears its cached ring geometry.
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ringX = null;
        ringZ = null;
        lastPhase = -2;
    }

    private void tick() {
        int phase = phaseController.getCurrentPhase();
        if (phase != lastPhase) {
            lastPhase = phase;
            rebuildRing();
        }
        if (ringX == null) return;

        for (Player p : world.getPlayers()) {
            double px = p.getLocation().getX();
            double pz = p.getLocation().getZ();
            double py = p.getLocation().getY() + Y_OFFSET;
            for (int i = 0; i < ringX.length; i++) {
                double dx = ringX[i] - px;
                double dz = ringZ[i] - pz;
                if (dx * dx + dz * dz <= MAX_VIEW_DISTANCE_SQ) {
                    p.spawnParticle(Particle.DUST, ringX[i], py, ringZ[i], 1, 0, 0, 0, 0, DUST);
                }
            }
        }
    }

    private void rebuildRing() {
        double[] target = phaseController.getTargetCenter();
        double radius = phaseController.getTargetRadius();
        if (target == null || radius <= 0) {
            ringX = null;
            ringZ = null;
            return;
        }

        double cx = target[0];
        double cz = target[1];
        int points = Math.max(16, (int) (2 * Math.PI * radius / PARTICLE_SPACING));

        ringX = new double[points];
        ringZ = new double[points];
        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI * i) / points;
            ringX[i] = cx + radius * Math.cos(angle);
            ringZ[i] = cz + radius * Math.sin(angle);
        }
    }
}
