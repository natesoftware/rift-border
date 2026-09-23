package com.natesoftware.riftborder.api;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Pulses a faint particle ring along the boundary of a {@link BorderPhaseController}'s current phase target, so players can
 * see where the border will land before it moves. Every 40 ticks it sends one dust particle per block of circumference, at
 * least 16 in all, to every player in the border's world, at that player's own Y and only for ring points within 80 blocks of
 * them on the horizontal plane, forced past the client's own particle distance cull. The ring is rebuilt whenever the
 * controller's phase index changes, so it appears on the first pulse after a phase's wait begins, stays through the shrink, and
 * shows nothing while no phase is active or the target radius is 0 or less. A schedule restarted on the same index keeps the old
 * ring until the index changes or {@link #stop()} clears it. The colour is {@link BorderTheme#indicatorColor()}, read on every
 * pulse that draws, with null drawn as white. It ignores the border's participant set and wall styles. Constructing one
 * registers it on the controller's border in place of any indicator registered before, so {@link GameBorder#remove()} stops it,
 * after which only {@link #start()} runs it again.
 */
public final class NextBorderIndicator {

    private static final int PULSE_TICKS = 40; // single-tick flash every two seconds
    private static final double PARTICLE_SPACING = 1.0;
    private static final double Y_OFFSET = 0.0;
    private static final double MAX_VIEW_DISTANCE_SQ = 80 * 80;

    private final Plugin plugin;
    private final GameBorder border;
    private final BorderPhaseController phaseController;

    private double[] ringX;
    private double[] ringZ;
    private int lastPhase = -2;

    private BukkitTask task;

    /**
     * Creates an indicator that reads its target from phaseController and shows it to the players of the controller's border's
     * world, with that border's plugin owning the pulse task. Registers itself on the border in place of any indicator already
     * registered there, which is stopped, and from then on only this one is stopped by {@link GameBorder#remove()}. Nothing runs
     * until {@link #start()}.
     */
    public NextBorderIndicator(BorderPhaseController phaseController) {
        this.phaseController = phaseController;
        this.border = phaseController.border();
        this.plugin = border.plugin;
        if (border.indicator != null && border.indicator != this) border.indicator.stop();
        border.indicator = this;
    }

    /**
     * Creates an indicator exactly as {@link #NextBorderIndicator(BorderPhaseController)} does. plugin and world are ignored, kept
     * only for source compatibility: the pulse task runs on the controller's border's plugin and the ring is shown in that
     * border's world whatever is passed here.
     */
    public NextBorderIndicator(Plugin plugin, World world, BorderPhaseController phaseController) {
        this(phaseController);
    }

    /**
     * Begins pulsing the indicator, with the first pulse 40 ticks from now, which rebuilds the ring before drawing it. Calling it
     * while running restarts the timer and discards the cached ring the same way.
     */
    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PULSE_TICKS, PULSE_TICKS);
    }

    /**
     * Stops the indicator and clears its cached ring geometry, so the next {@link #start()} rebuilds it. Called by
     * {@link GameBorder#remove()} and by the constructor of an indicator that replaces this one on the same border. Safe to call
     * when not running. Particles already sent fade on their own.
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

        Color color = border.theme.indicatorColor();
        Particle.DustOptions dust = new Particle.DustOptions(color != null ? color : Color.WHITE, 1.0f);
        for (Player p : border.world.getPlayers()) {
            double px = p.getLocation().getX();
            double pz = p.getLocation().getZ();
            double py = p.getLocation().getY() + Y_OFFSET;
            for (int i = 0; i < ringX.length; i++) {
                double dx = ringX[i] - px;
                double dz = ringZ[i] - pz;
                if (dx * dx + dz * dz <= MAX_VIEW_DISTANCE_SQ) {
                    // force=true, or the client drops everything past its own 32-block particle limit and the far arc never shows
                    p.spawnParticle(Particle.DUST, ringX[i], py, ringZ[i], 1, 0, 0, 0, 0, dust, true);
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
