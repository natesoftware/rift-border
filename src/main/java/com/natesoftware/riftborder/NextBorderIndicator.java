package com.natesoftware.riftborder;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Pulses a faint white particle ring along the boundary of a {@link BorderPhaseController}'s current phase target, so players
 * can see where the border will land before it moves. Every 40 ticks it sends one white dust particle per block of circumference,
 * at least 16 in all, to every player in the world, at that player's own Y and only for ring points within 80 blocks of them on
 * the horizontal plane. The ring is rebuilt whenever the controller's phase index changes, so it appears as a phase's wait begins,
 * stays through the shrink, and shows nothing while no phase is active or the target radius is 0 or less. A schedule restarted
 * on the same index keeps the old ring until the index changes or {@link #stop()} clears it. It ignores the border's participant
 * set and render modes.
 */
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

    /**
     * Creates an indicator that reads its target from phaseController and shows it to the players of world, which should be the
     * world the controller's border lives in. plugin owns the pulse task. Nothing runs until {@link #start()}.
     */
    public NextBorderIndicator(Plugin plugin, World world, BorderPhaseController phaseController) {
        this.plugin = plugin;
        this.world = world;
        this.phaseController = phaseController;
    }

    /** Begins pulsing the indicator, with the first pulse 40 ticks from now. Calling it while running restarts the timer. */
    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PULSE_TICKS, PULSE_TICKS);
    }

    /**
     * Stops the indicator and clears its cached ring geometry, so the next {@link #start()} rebuilds it. Safe to call when not
     * running. Particles already sent fade on their own.
     */
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
        BorderPoint target = phaseController.getTargetCenter();
        double radius = phaseController.getTargetRadius();
        if (target == null || radius <= 0) {
            ringX = null;
            ringZ = null;
            return;
        }

        double cx = target.x();
        double cz = target.z();
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
