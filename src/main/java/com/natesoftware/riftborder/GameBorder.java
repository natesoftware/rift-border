package com.natesoftware.riftborder;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

// A circular, volumetric border for a Minecraft world.
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

    // Y of the volumetric ceiling.
    private double maxHeight = BorderPhaseController.Phase.NO_HEIGHT_LIMIT;

    // Y of the volumetric floor.
    private double minHeight = BorderPhaseController.Phase.NO_MIN_HEIGHT;

    Supplier<Set<UUID>> participantSupplier;
    Runnable onShrinkComplete;
    BorderCallbacks callbacks;
    Function<UUID, BorderRenderMode> renderModeResolver = uuid -> BorderRenderMode.SHADER;

    final BorderRenderer renderer;
    final ParticleBorderRenderer particleRenderer;
    final BorderShrinkAnimator animator;
    final BorderDamageTracker damageTracker;

    private boolean active;

    // Creates a new border centred at (centerX, centerY, centerZ) in the given world.
    public GameBorder(Plugin plugin, World world, double centerX, double centerY, double centerZ) {
        this.plugin = plugin;
        this.world = world;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.initialCenterX = centerX;
        this.initialCenterZ = centerZ;
        this.renderer = new BorderRenderer(this);
        this.particleRenderer = new ParticleBorderRenderer(this);
        this.animator = new BorderShrinkAnimator(this);
        this.damageTracker = new BorderDamageTracker(this);
    }

    // Restricts damage and warning titles to players whose UUIDs appear in the supplied set (re-evaluated each tick).
    public GameBorder withParticipants(Supplier<Set<UUID>> supplier) {
        this.participantSupplier = supplier;
        return this;
    }

    // Registers a callback fired when an in-progress shrink finishes naturally (not when the border is removed or replaced by a new transition).
    public GameBorder onShrinkComplete(Runnable callback) {
        this.onShrinkComplete = callback;
        return this;
    }

    // Wires in the host's branding and presentation hooks.
    public GameBorder withCallbacks(BorderCallbacks callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    // Per-player render mode lookup, re-evaluated on every visibility and particle pass. Defaults everyone to SHADER.
    public GameBorder withRenderModeResolver(Function<UUID, BorderRenderMode> resolver) {
        this.renderModeResolver = resolver;
        return this;
    }

    // Render mode for a player, treating a null resolver result as SHADER.
    BorderRenderMode renderModeFor(UUID uuid) {
        BorderRenderMode mode = renderModeResolver.apply(uuid);
        return mode != null ? mode : BorderRenderMode.SHADER;
    }

    // Sets the damage applied per second to a player who has been outside the border longer than the grace period. 0 disables damage entirely.
    public void setDamagePerSecond(double damage) {
        this.damagePerSecond = damage;
    }

    // Returns true if (x, z) lies outside the current radius (ignores ceiling/floor - use isAboveHeight / isBelowMinHeight for those).
    public boolean isOutside(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz > radius * radius;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterZ() {
        return centerZ;
    }

    public double getRadius() {
        return radius;
    }

    public double getDamagePerSecond() {
        return damagePerSecond;
    }

    public double getMaxHeight() {
        return maxHeight;
    }

    public double getMinHeight() {
        return minHeight;
    }

    public World getWorld() {
        return world;
    }

    // Returns true if y is above the current ceiling.
    public boolean isAboveHeight(double y) {
        return y > maxHeight;
    }

    // Returns true if the border has a ceiling configured.
    public boolean hasHeightLimit() {
        return maxHeight != BorderPhaseController.Phase.NO_HEIGHT_LIMIT;
    }

    // Returns true if y is below the current floor.
    public boolean isBelowMinHeight(double y) {
        return y < minHeight;
    }

    // Returns true if the border has a floor configured.
    public boolean hasMinHeight() {
        return minHeight != BorderPhaseController.Phase.NO_MIN_HEIGHT;
    }

    // Activates the border: spawns the wall display grid and starts the damage tracker.
    public void spawn(double initialRadius) {
        if (active) throw new IllegalStateException("GameBorder already spawned");
        if (callbacks == null) {
            throw new IllegalStateException("GameBorder.withCallbacks(...) must be called before spawn()");
        }
        this.radius = initialRadius;
        this.active = true;

        renderer.spawn();
        particleRenderer.start();
        if (damagePerSecond > 0) damageTracker.start();
    }

    // Removes the border: cancels animations, despawns the wall grid, stops damage tracking.
    public void remove() {
        animator.reset();
        active = false;
        renderer.remove();
        particleRenderer.stop();
        damageTracker.stop();
    }

    // Shrinks to the given radius over remainingTicks, keeping the current centre.
    public void startShrinking(double endRadius, int remainingTicks) {
        animator.startShrinking(endRadius, remainingTicks);
    }

    // Animates the border from its current shape to (targetX, targetZ) with radius endRadius over ticks ticks.
    public void moveTo(double targetX, double targetZ, double endRadius, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, maxHeight, minHeight, ticks);
    }

    // Height-aware variant of moveTo that also interpolates the ceiling.
    public void moveTo(double targetX, double targetZ, double endRadius, double endHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, minHeight, ticks);
    }

    // Full-vertical variant of moveTo that interpolates both ceiling and floor.
    public void moveTo(
        double targetX, double targetZ, double endRadius, double endHeight, double endMinHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, endMinHeight, ticks);
    }

    // Rewrites the in-flight animation's remaining tick budget without changing endpoints.
    public void setRemainingTicks(int remainingTicks) {
        animator.setRemainingTicks(remainingTicks);
    }

    // Pauses the current shrink animation.
    public void pauseShrinking() {
        animator.pause();
    }

    // Resumes a paused shrink with a fresh tick budget. remainingTicks is the time to spend completing the remaining interpolation distance.
    public void resumeShrinking(int remainingTicks) {
        animator.resume(remainingTicks);
    }

    // Snaps the border to a new shape immediately, cancelling any in-flight animation.
    public void setPosition(double cx, double cz, double newRadius) {
        animator.setPosition(cx, cz, newRadius, maxHeight, minHeight);
    }

    // Snap variant that also sets the ceiling.
    public void setPosition(double cx, double cz, double newRadius, double newHeight) {
        animator.setPosition(cx, cz, newRadius, newHeight, minHeight);
    }

    // Snap variant that also sets the ceiling and floor.
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
