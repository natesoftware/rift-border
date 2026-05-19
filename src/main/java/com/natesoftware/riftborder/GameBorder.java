package com.natesoftware.riftborder;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * A circular, volumetric border for a Minecraft world. The library renders the
 * border as a cylinder shader on grid-mounted ItemDisplay entities, animates
 * shrink/move transitions, and damages players who stray outside the radius
 * or beyond the optional ceiling/floor.
 *
 * <p>Construct, configure with the chained builder methods, then call
 * {@link #spawn(double)} to make it active. Drive the shape manually with
 * {@link #moveTo} / {@link #setPosition}, or hand it to a
 * {@link BorderPhaseController} for multi-phase orchestration.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * GameBorder border = new GameBorder(plugin, world, 0, 64, 0)
 *     .withCallbacks(new MyCallbacks())
 *     .withParticipants(() -> alivePlayerUuids);
 * border.spawn(200);
 *
 * // Shrink to (50, 50) / r=100 over 30 seconds:
 * border.moveTo(50, 50, 100, 20 * 30);
 * }</pre>
 */
public class GameBorder {

    final Plugin plugin;
    final World world;

    final double initialCenterX;
    final double initialCenterZ;
    final double centerY;

    private double centerX;
    private double centerZ;
    private double radius;
    private double damagePerSecond = 2.0;

    // Y of the volumetric ceiling. Players above are treated as outside the
    // border (same damage + warning as crossing the radius). NO_HEIGHT_LIMIT
    // means no ceiling. Mutated by BorderShrinkAnimator alongside center /
    // radius / minHeight.
    private double maxHeight = BorderPhaseController.Phase.NO_HEIGHT_LIMIT;

    // Y of the volumetric floor. Players below are treated as outside.
    // NO_MIN_HEIGHT means no floor.
    private double minHeight = BorderPhaseController.Phase.NO_MIN_HEIGHT;

    Supplier<Set<UUID>> participantSupplier;
    Runnable onShrinkComplete;
    BorderCallbacks callbacks;

    final BorderRenderer renderer;
    final BorderShrinkAnimator animator;
    final BorderDamageTracker damageTracker;

    private boolean active;

    /**
     * Creates a new border centred at {@code (centerX, centerY, centerZ)} in
     * the given world. The border is not active until {@link #spawn(double)}
     * is called.
     *
     * @param plugin host plugin (used to schedule Bukkit tasks)
     * @param world the world the border lives in
     * @param centerX initial X centre
     * @param centerY Y used for visual anchor of the wall shader
     * @param centerZ initial Z centre
     */
    public GameBorder(Plugin plugin, World world, double centerX, double centerY, double centerZ) {
        this.plugin = plugin;
        this.world = world;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.initialCenterX = centerX;
        this.initialCenterZ = centerZ;
        this.renderer = new BorderRenderer(this);
        this.animator = new BorderShrinkAnimator(this);
        this.damageTracker = new BorderDamageTracker(this);
    }

    /**
     * Restricts damage and warning titles to players whose UUIDs appear in the
     * supplied set (re-evaluated each tick). When not set, every player in the
     * world is tracked.
     *
     * @param supplier supplier of the currently-tracked UUIDs
     * @return this builder
     */
    public GameBorder withParticipants(Supplier<Set<UUID>> supplier) {
        this.participantSupplier = supplier;
        return this;
    }

    /**
     * Registers a callback fired when an in-progress shrink finishes naturally
     * (not when the border is removed or replaced by a new transition).
     *
     * @return this builder
     */
    public GameBorder onShrinkComplete(Runnable callback) {
        this.onShrinkComplete = callback;
        return this;
    }

    /**
     * Wires in the host's branding and presentation hooks. Required before
     * {@link #spawn(double)} - {@code spawn} throws if this is missing.
     *
     * @return this builder
     */
    public GameBorder withCallbacks(BorderCallbacks callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    /**
     * Sets the damage applied per second to a player who has been outside the
     * border longer than the grace period. {@code 0} disables damage entirely.
     */
    public void setDamagePerSecond(double damage) {
        this.damagePerSecond = damage;
    }

    /**
     * Returns {@code true} if {@code (x, z)} lies outside the current radius
     * (ignores ceiling/floor - use {@link #isAboveHeight} / {@link #isBelowMinHeight}
     * for those).
     */
    public boolean isOutside(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz > radius * radius;
    }

    /** @return current X centre. Updated live by shrink animations. */
    public double getCenterX() {
        return centerX;
    }

    /** @return current Z centre. Updated live by shrink animations. */
    public double getCenterZ() {
        return centerZ;
    }

    /** @return current radius. Updated live by shrink animations. */
    public double getRadius() {
        return radius;
    }

    /** @return damage applied per second to players outside the border. */
    public double getDamagePerSecond() {
        return damagePerSecond;
    }

    /** @return current ceiling Y, or {@link BorderPhaseController.Phase#NO_HEIGHT_LIMIT} if uncapped. */
    public double getMaxHeight() {
        return maxHeight;
    }

    /** @return current floor Y, or {@link BorderPhaseController.Phase#NO_MIN_HEIGHT} if uncapped. */
    public double getMinHeight() {
        return minHeight;
    }

    /** @return the world this border lives in. */
    public World getWorld() {
        return world;
    }

    /** Returns {@code true} if {@code y} is above the current ceiling. */
    public boolean isAboveHeight(double y) {
        return y > maxHeight;
    }

    /** Returns {@code true} if the border has a ceiling configured. */
    public boolean hasHeightLimit() {
        return maxHeight != BorderPhaseController.Phase.NO_HEIGHT_LIMIT;
    }

    /** Returns {@code true} if {@code y} is below the current floor. */
    public boolean isBelowMinHeight(double y) {
        return y < minHeight;
    }

    /** Returns {@code true} if the border has a floor configured. */
    public boolean hasMinHeight() {
        return minHeight != BorderPhaseController.Phase.NO_MIN_HEIGHT;
    }

    /**
     * Activates the border: spawns the wall display grid and starts the damage
     * tracker. Must be called exactly once per instance.
     *
     * @param initialRadius starting radius
     * @throws IllegalStateException if already spawned or {@link #withCallbacks} was not called
     */
    public void spawn(double initialRadius) {
        if (active) throw new IllegalStateException("GameBorder already spawned");
        if (callbacks == null) {
            throw new IllegalStateException("GameBorder.withCallbacks(...) must be called before spawn()");
        }
        this.radius = initialRadius;
        this.active = true;

        renderer.spawn();
        if (damagePerSecond > 0) damageTracker.start();
    }

    /**
     * Removes the border: cancels animations, despawns the wall grid, stops
     * damage tracking. Safe to call at any point in the lifecycle.
     */
    public void remove() {
        animator.reset();
        active = false;
        renderer.remove();
        damageTracker.stop();
    }

    /**
     * Shrinks to the given radius over {@code remainingTicks}, keeping the
     * current centre. Equivalent to {@link #moveTo(double, double, double, int)}
     * with the current centre.
     */
    public void startShrinking(double endRadius, int remainingTicks) {
        animator.startShrinking(endRadius, remainingTicks);
    }

    /**
     * Animates the border from its current shape to {@code (targetX, targetZ)}
     * with radius {@code endRadius} over {@code ticks} ticks. Ceiling and floor
     * are left unchanged.
     */
    public void moveTo(double targetX, double targetZ, double endRadius, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, maxHeight, minHeight, ticks);
    }

    /**
     * Height-aware variant of {@link #moveTo(double, double, double, int)} that
     * also interpolates the ceiling. Pass
     * {@link BorderPhaseController.Phase#NO_HEIGHT_LIMIT} for {@code endHeight}
     * to remove the ceiling.
     */
    public void moveTo(double targetX, double targetZ, double endRadius, double endHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, minHeight, ticks);
    }

    /**
     * Full-vertical variant of {@link #moveTo(double, double, double, int)}
     * that interpolates both ceiling and floor. Pass
     * {@link BorderPhaseController.Phase#NO_HEIGHT_LIMIT} or
     * {@link BorderPhaseController.Phase#NO_MIN_HEIGHT} to leave either side
     * uncapped.
     */
    public void moveTo(
        double targetX, double targetZ, double endRadius, double endHeight, double endMinHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, endMinHeight, ticks);
    }

    /** Rewrites the in-flight animation's remaining tick budget without changing endpoints. */
    public void setRemainingTicks(int remainingTicks) {
        animator.setRemainingTicks(remainingTicks);
    }

    /** Pauses the current shrink animation. Use {@link #resumeShrinking(int)} to continue. */
    public void pauseShrinking() {
        animator.pause();
    }

    /**
     * Resumes a paused shrink with a fresh tick budget. {@code remainingTicks}
     * is the time to spend completing the remaining interpolation distance.
     */
    public void resumeShrinking(int remainingTicks) {
        animator.resume(remainingTicks);
    }

    /** Snaps the border to a new shape immediately, cancelling any in-flight animation. */
    public void setPosition(double cx, double cz, double newRadius) {
        animator.setPosition(cx, cz, newRadius, maxHeight, minHeight);
    }

    /** Snap variant that also sets the ceiling. */
    public void setPosition(double cx, double cz, double newRadius, double newHeight) {
        animator.setPosition(cx, cz, newRadius, newHeight, minHeight);
    }

    /** Snap variant that also sets the ceiling and floor. */
    public void setPosition(
        double cx, double cz, double newRadius, double newHeight, double newMinHeight) {
        animator.setPosition(cx, cz, newRadius, newHeight, newMinHeight);
    }

    // Package-private write hook used by BorderShrinkAnimator to push interpolated values back.
    void setShape(double centerX, double centerZ, double radius, double maxHeight, double minHeight) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.maxHeight = maxHeight;
        this.minHeight = minHeight;
    }
}
