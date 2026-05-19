package com.natesoftware.riftborder;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

// Circular zone border: the public surface for host code. Owns shape state
// (center, radius) and composes three helpers - rendering, shrink animation,
// and damage tracking - each with one clear job. All three live in the same
// package and share state through this class.
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

    public GameBorder withParticipants(Supplier<Set<UUID>> supplier) {
        this.participantSupplier = supplier;
        return this;
    }

    public GameBorder onShrinkComplete(Runnable callback) {
        this.onShrinkComplete = callback;
        return this;
    }

    // Required before spawn(). All host-plugin integration (warning title,
    // wall item model, phase broadcasts, sounds) flows through this object.
    public GameBorder withCallbacks(BorderCallbacks callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    public void setDamagePerSecond(double damage) {
        this.damagePerSecond = damage;
    }

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

    public boolean isAboveHeight(double y) {
        return y > maxHeight;
    }

    public boolean hasHeightLimit() {
        return maxHeight != BorderPhaseController.Phase.NO_HEIGHT_LIMIT;
    }

    public boolean isBelowMinHeight(double y) {
        return y < minHeight;
    }

    public boolean hasMinHeight() {
        return minHeight != BorderPhaseController.Phase.NO_MIN_HEIGHT;
    }

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

    public void remove() {
        animator.reset();
        active = false;
        renderer.remove();
        damageTracker.stop();
    }

    public void startShrinking(double endRadius, int remainingTicks) {
        animator.startShrinking(endRadius, remainingTicks);
    }

    public void moveTo(double targetX, double targetZ, double endRadius, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, maxHeight, minHeight, ticks);
    }

    // Height-aware variant: interpolates the volumetric ceiling alongside
    // the radius. Pass NO_HEIGHT_LIMIT for endHeight to leave the ceiling
    // at "no cap".
    public void moveTo(double targetX, double targetZ, double endRadius, double endHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, minHeight, ticks);
    }

    // Full-vertical variant: interpolates ceiling and floor alongside the
    // radius. Pass NO_HEIGHT_LIMIT / NO_MIN_HEIGHT to leave either side
    // uncapped.
    public void moveTo(
        double targetX, double targetZ, double endRadius, double endHeight, double endMinHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, endMinHeight, ticks);
    }

    public void setRemainingTicks(int remainingTicks) {
        animator.setRemainingTicks(remainingTicks);
    }

    public void pauseShrinking() {
        animator.pause();
    }

    public void resumeShrinking(int remainingTicks) {
        animator.resume(remainingTicks);
    }

    public void setPosition(double cx, double cz, double newRadius) {
        animator.setPosition(cx, cz, newRadius, maxHeight, minHeight);
    }

    public void setPosition(double cx, double cz, double newRadius, double newHeight) {
        animator.setPosition(cx, cz, newRadius, newHeight, minHeight);
    }

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
