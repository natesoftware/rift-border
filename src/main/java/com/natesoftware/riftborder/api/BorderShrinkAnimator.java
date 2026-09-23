package com.natesoftware.riftborder.api;

import org.bukkit.scheduler.BukkitTask;

// Drives the border's shape transition: linearly interpolates centerX/Z and radius from current values to target values over a tick budget.
final class BorderShrinkAnimator {

    private final GameBorder border;

    private double startSize;
    private double endSize;
    private double startCenterX;
    private double startCenterZ;
    private double endCenterX;
    private double endCenterZ;
    private double startHeight;
    private double endHeight;
    private double startMinHeight;
    private double endMinHeight;
    private int totalTicks;
    private int elapsedTicks;
    private boolean paused;
    private boolean started;

    private BukkitTask task;

    BorderShrinkAnimator(GameBorder border) {
        this.border = border;
    }

    void startShrinking(double endRadius, int remainingTicks) {
        moveTo(
            border.getCenterX(), border.getCenterZ(), endRadius,
            border.getMaxHeight(), border.getMinHeight(), remainingTicks);
    }

    void moveTo(
        double targetX, double targetZ, double endRadius,
        double targetHeight, double targetMinHeight, int ticks) {
        stop();
        startSize = border.getRadius();
        endSize = endRadius;
        startCenterX = border.getCenterX();
        startCenterZ = border.getCenterZ();
        endCenterX = targetX;
        endCenterZ = targetZ;
        startHeight = border.getMaxHeight();
        endHeight = targetHeight;
        startMinHeight = border.getMinHeight();
        endMinHeight = targetMinHeight;
        totalTicks = Math.max(ticks, 1);
        elapsedTicks = 0;
        paused = false;
        started = true;
        startTask();
    }

    void setRemainingTicks(int remainingTicks) {
        if (task == null) return;
        elapsedTicks = Math.max(0, totalTicks - remainingTicks);
        applyProgress();
    }

    void pause() {
        paused = true;
    }

    void resume(int remainingTicks) {
        if (!started) return;
        stop();
        startSize = border.getRadius();
        startCenterX = border.getCenterX();
        startCenterZ = border.getCenterZ();
        startHeight = border.getMaxHeight();
        startMinHeight = border.getMinHeight();
        totalTicks = Math.max(remainingTicks, 1);
        elapsedTicks = 0;
        paused = false;
        startTask();
    }

    void setPosition(double cx, double cz, double newRadius, double newHeight, double newMinHeight) {
        stop();
        started = false;
        border.setShape(cx, cz, newRadius, newHeight, newMinHeight);
        border.renderer.updateAllEntities();
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    void reset() {
        stop();
        started = false;
    }

    // End radius of the in-flight transition, or NaN when idle.
    double activeEndRadius() {
        return task != null ? endSize : Double.NaN;
    }

    // True while a transition is in flight and not paused - the border is visibly moving.
    boolean isMoving() {
        return task != null && !paused;
    }

    private void applyProgress() {
        double progress = Math.min(1.0, (double) elapsedTicks / totalTicks);
        double radius = startSize + (endSize - startSize) * progress;
        double cx = startCenterX + (endCenterX - startCenterX) * progress;
        double cz = startCenterZ + (endCenterZ - startCenterZ) * progress;
        border.setShape(cx, cz, radius, computeHeight(progress), computeMinHeight(progress));
        border.renderer.updateAllEntities();
    }

    // Lerp the ceiling Y.
    private double computeHeight(double progress) {
        if (progress >= 1.0) return endHeight;
        // unchanged at both ends means unchanged throughout - a border with no ceiling must not grow one for the transition's duration
        if (startHeight == endHeight) return endHeight;
        double noLimit = GameBorder.NO_HEIGHT_LIMIT;
        double effStart = startHeight == noLimit ? border.world.getMaxHeight() : startHeight;
        double effEnd = endHeight == noLimit ? border.world.getMaxHeight() : endHeight;
        if (effStart == effEnd) return effEnd;
        return effStart + (effEnd - effStart) * progress;
    }

    // Mirror of computeHeight for the floor: the world's min build height stands in for NO_MIN_HEIGHT, so the floor rises from below.
    private double computeMinHeight(double progress) {
        if (progress >= 1.0) return endMinHeight;
        if (startMinHeight == endMinHeight) return endMinHeight;
        double noFloor = GameBorder.NO_MIN_HEIGHT;
        double effStart = startMinHeight == noFloor ? border.world.getMinHeight() : startMinHeight;
        double effEnd = endMinHeight == noFloor ? border.world.getMinHeight() : endMinHeight;
        if (effStart == effEnd) return effEnd;
        return effStart + (effEnd - effStart) * progress;
    }

    private void startTask() {
        task = border.plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                border.plugin,
                () -> {
                    if (paused) return;
                    elapsedTicks += BorderRenderer.UPDATE_INTERVAL_TICKS;
                    applyProgress();
                    if (elapsedTicks >= totalTicks) {
                        stop();
                        started = false;
                        // controller first so a host callback that reads phase state sees the advanced phase
                        if (border.internalShrinkComplete != null) border.internalShrinkComplete.run();
                        if (border.onShrinkComplete != null) border.onShrinkComplete.run();
                    }
                },
                BorderRenderer.UPDATE_INTERVAL_TICKS,
                BorderRenderer.UPDATE_INTERVAL_TICKS);
    }
}
