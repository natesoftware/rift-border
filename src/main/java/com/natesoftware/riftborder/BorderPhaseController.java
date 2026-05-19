package com.natesoftware.riftborder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Drives the multi-phase zone border: each phase waits, then shrinks to a smaller random center inside the current
// zone.
public class BorderPhaseController {

    private static final Logger log = LoggerFactory.getLogger(BorderPhaseController.class);

    private final Plugin plugin;
    private final GameBorder border;
    private final List<Phase> phases;
    private final double mapCenterX;
    private final double mapCenterZ;
    private final double initialRadius;

    // When true, every phase shrinks toward (mapCenterX, mapCenterZ) instead of a random point inside the current zone.
    private boolean fixedCenter;

    // Pre-computed target center for each phase, determined at start().
    private final List<double[]> phaseTargets = new ArrayList<>();

    // Absolute game-timer boundaries for each phase in seconds remaining.
    private final List<Integer> phaseWaitStart = new ArrayList<>();
    private final List<Integer> phaseShrinkStart = new ArrayList<>();
    private final List<Integer> phaseEnd = new ArrayList<>();

    private int gameDuration;

    private int currentPhase = -1;

    private SubPhase subPhase;
    private long subPhaseStartTick;
    private int subPhaseDurationTicks;

    private boolean paused;
    private int pausedRemainingTicks;
    private boolean stopped;

    private BukkitTask waitTask;
    private Runnable onAllPhasesComplete;

    public BorderPhaseController(
        Plugin plugin,
        GameBorder border,
        List<Phase> phases,
        double mapCenterX,
        double mapCenterZ,
        double initialRadius) {
        this.plugin = plugin;
        this.border = border;
        this.phases = List.copyOf(phases);
        this.mapCenterX = mapCenterX;
        this.mapCenterZ = mapCenterZ;
        this.initialRadius = initialRadius;
    }

    // When enabled, the border always shrinks toward the map center rather than a random point in the current zone.
    public BorderPhaseController withFixedCenter(boolean fixedCenter) {
        this.fixedCenter = fixedCenter;
        return this;
    }

    // Starts the phase controller. Must be called after the border is
    // spawned. Pre-computes all phase targets and time boundaries based on
    // the game duration.
    public void start(int gameDuration) {
        this.gameDuration = gameDuration;
        stopped = false;
        precomputePhases();
        advancePhase();
    }

    public void stop() {
        stopped = true;
        cancelWait();
    }

    // Registers a callback invoked when all phases have completed (border fully closed).
    public void setOnAllPhasesComplete(Runnable callback) {
        this.onAllPhasesComplete = callback;
    }

    public void pause() {
        if (paused) return;
        paused = true;
        int elapsed = (int) (currentTick() - subPhaseStartTick);
        pausedRemainingTicks = Math.max(0, subPhaseDurationTicks - elapsed);
        if (subPhase == SubPhase.WAIT) {
            cancelWait();
        } else if (subPhase == SubPhase.SHRINK) {
            border.pauseShrinking();
        }
    }

    public void resume() {
        if (!paused) return;
        paused = false;
        if (subPhase == SubPhase.WAIT) {
            subPhaseStartTick = currentTick();
            subPhaseDurationTicks = pausedRemainingTicks;
            scheduleWaitTicks(pausedRemainingTicks);
        } else if (subPhase == SubPhase.SHRINK) {
            border.resumeShrinking(pausedRemainingTicks);
        }
    }

    // Jumps the border to wherever it would be at the given game-timer
    // value. Cancels current wait/shrink, sets border position, and
    // resumes from there.
    public void syncToGameTimer(int gameSecondsRemaining) {
        if (stopped) return;
        cancelWait();
        paused = false;

        // Find which phase and sub-phase this time falls in.
        for (int i = 0; i < phases.size(); i++) {
            int waitStart = phaseWaitStart.get(i);
            int shrinkStart = phaseShrinkStart.get(i);
            int end = phaseEnd.get(i);

            if (gameSecondsRemaining > waitStart) continue;
            if (gameSecondsRemaining > shrinkStart) {
                int waitRemaining = gameSecondsRemaining - shrinkStart;
                jumpToWait(i, waitRemaining);
                return;
            }
            if (gameSecondsRemaining > end) {
                int shrinkTotal = phases.get(i).shrinkSeconds;
                int shrinkElapsed = shrinkStart - gameSecondsRemaining;
                jumpToShrink(i, shrinkTotal, shrinkElapsed);
                return;
            }
        }

        // Past all phases - border fully closed.
        if (!phases.isEmpty()) {
            int lastIdx = phases.size() - 1;
            double[] target = phaseTargets.get(lastIdx);
            Phase last = phases.get(lastIdx);
            border.setPosition(target[0], target[1], last.endRadius, last.endHeight, last.endMinHeight);
            currentPhase = phases.size();
            subPhase = null;
        }
    }

    // Backwards-compatible alias for syncToGameTimer(int).
    public void setRemainingTime(int totalSeconds) {
        syncToGameTimer(totalSeconds);
    }

    public boolean isShrinking() {
        return subPhase == SubPhase.SHRINK;
    }

    public int getCurrentPhase() {
        return currentPhase;
    }

    // Returns the center [x, z] of the zone the border is currently
    // shrinking toward (or about to shrink toward during WAIT), or null if
    // all phases are complete.
    public double[] getTargetCenter() {
        if (currentPhase < 0 || currentPhase >= phases.size()) return null;
        return phaseTargets.get(currentPhase);
    }

    // Returns the end radius of the current phase target, or -1 if all phases are complete.
    public double getTargetRadius() {
        if (currentPhase < 0 || currentPhase >= phases.size()) return -1;
        return phases.get(currentPhase).endRadius;
    }

    public int getSubPhaseRemaining() {
        if (currentPhase < 0 || currentPhase >= phases.size() || subPhase == null) return 0;
        if (paused) return Math.max(0, pausedRemainingTicks / 20);
        int elapsed = (int) (currentTick() - subPhaseStartTick);
        return Math.max(0, (subPhaseDurationTicks - elapsed) / 20);
    }

    private void precomputePhases() {
        phaseTargets.clear();
        phaseWaitStart.clear();
        phaseShrinkStart.clear();
        phaseEnd.clear();

        double cx = border.getCenterX();
        double cz = border.getCenterZ();
        double r = initialRadius;

        int cursor = gameDuration; // game seconds remaining

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (Phase phase : phases) {
            phaseWaitStart.add(cursor);
            cursor -= phase.waitSeconds;
            phaseShrinkStart.add(cursor);
            cursor -= phase.shrinkSeconds;
            phaseEnd.add(cursor);

            double targetCx;
            double targetCz;
            if (fixedCenter) {
                targetCx = mapCenterX;
                targetCz = mapCenterZ;
            } else {
                // Random target inside the current zone, constrained so the new circle stays inside.
                double maxOffset = Math.max(0, r - phase.endRadius);
                if (maxOffset < 1.0) {
                    targetCx = cx;
                    targetCz = cz;
                } else {
                    double angle = rng.nextDouble() * 2 * Math.PI;
                    double dist = Math.sqrt(rng.nextDouble()) * maxOffset;
                    targetCx = cx + dist * Math.cos(angle);
                    targetCz = cz + dist * Math.sin(angle);
                }
            }
            phaseTargets.add(new double[]{targetCx, targetCz});

            // Next phase starts from this phase's end state.
            cx = targetCx;
            cz = targetCz;
            r = phase.endRadius;
        }
    }

    private void advancePhase() {
        if (stopped) return;
        currentPhase++;
        if (currentPhase >= phases.size()) {
            log.info("[BorderPhase] All {} phases complete.", phases.size());
            subPhase = null;
            if (onAllPhasesComplete != null) onAllPhasesComplete.run();
            return;
        }

        Phase phase = phases.get(currentPhase);
        border.setDamagePerSecond(phase.damage);
        int phaseNum = currentPhase + 1;
        border.callbacks.phaseStarted(phaseNum, phases.size(), phase.waitSeconds);
        border.callbacks.playPhaseSound();

        subPhase = SubPhase.WAIT;
        subPhaseStartTick = currentTick();
        subPhaseDurationTicks = phase.waitSeconds * 20;
        scheduleWaitTicks(subPhaseDurationTicks);
    }

    private void beginShrink() {
        if (stopped || currentPhase < 0 || currentPhase >= phases.size()) return;
        Phase phase = phases.get(currentPhase);
        double[] target = phaseTargets.get(currentPhase);

        subPhase = SubPhase.SHRINK;
        subPhaseStartTick = currentTick();
        subPhaseDurationTicks = phase.shrinkSeconds * 20;

        border.callbacks.shrinkStarted();
        border.callbacks.playShrinkSound();
        border.onShrinkComplete(this::advancePhase);
        border.moveTo(
            target[0], target[1], phase.endRadius, phase.endHeight, phase.endMinHeight, subPhaseDurationTicks);
    }

    private void jumpToWait(int phaseIndex, int waitSecondsRemaining) {
        // Set border to the end state of the previous phase (or initial if phase 0).
        if (phaseIndex > 0) {
            double[] prevTarget = phaseTargets.get(phaseIndex - 1);
            Phase prev = phases.get(phaseIndex - 1);
            border.setPosition(
                prevTarget[0], prevTarget[1], prev.endRadius, prev.endHeight, prev.endMinHeight);
        } else {
            border.setPosition(
                border.getCenterX(), border.getCenterZ(), initialRadius,
                Phase.NO_HEIGHT_LIMIT, Phase.NO_MIN_HEIGHT);
        }

        currentPhase = phaseIndex;
        border.setDamagePerSecond(phases.get(phaseIndex).damage);
        subPhase = SubPhase.WAIT;
        subPhaseStartTick = currentTick();
        subPhaseDurationTicks = waitSecondsRemaining * 20;

        int phaseNum = currentPhase + 1;
        border.callbacks.phaseStarted(phaseNum, phases.size(), waitSecondsRemaining);
        scheduleWaitTicks(subPhaseDurationTicks);
    }

    private void jumpToShrink(int phaseIndex, int shrinkTotal, int shrinkElapsed) {
        Phase phase = phases.get(phaseIndex);
        double[] target = phaseTargets.get(phaseIndex);

        // Compute the start state for this phase's shrink.
        double startCx;
        double startCz;
        double startRadius;
        double startHeight;
        double startMinHeight;
        if (phaseIndex > 0) {
            double[] prevTarget = phaseTargets.get(phaseIndex - 1);
            Phase prev = phases.get(phaseIndex - 1);
            startCx = prevTarget[0];
            startCz = prevTarget[1];
            startRadius = prev.endRadius;
            startHeight = prev.endHeight;
            startMinHeight = prev.endMinHeight;
        } else {
            startCx = border.getCenterX();
            startCz = border.getCenterZ();
            startRadius = initialRadius;
            startHeight = Phase.NO_HEIGHT_LIMIT;
            startMinHeight = Phase.NO_MIN_HEIGHT;
        }

        // Interpolate to the current position within the shrink.
        double progress = (double) shrinkElapsed / shrinkTotal;
        double cx = startCx + (target[0] - startCx) * progress;
        double cz = startCz + (target[1] - startCz) * progress;
        double r = startRadius + (phase.endRadius - startRadius) * progress;
        double h = startHeight == phase.endHeight ? phase.endHeight
            : startHeight + (phase.endHeight - startHeight) * progress;
        double mh = startMinHeight == phase.endMinHeight ? phase.endMinHeight
            : startMinHeight + (phase.endMinHeight - startMinHeight) * progress;
        border.setPosition(cx, cz, r, h, mh);

        // Resume shrinking for the remaining time.
        int remainingSeconds = shrinkTotal - shrinkElapsed;
        currentPhase = phaseIndex;
        border.setDamagePerSecond(phases.get(phaseIndex).damage);
        subPhase = SubPhase.SHRINK;
        subPhaseStartTick = currentTick();
        subPhaseDurationTicks = remainingSeconds * 20;

        border.callbacks.shrinkStarted();
        border.onShrinkComplete(this::advancePhase);
        border.moveTo(
            target[0], target[1], phase.endRadius, phase.endHeight, phase.endMinHeight, subPhaseDurationTicks);
    }

    private void scheduleWaitTicks(int ticks) {
        cancelWait();
        waitTask = plugin.getServer()
            .getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                    waitTask = null;
                    if (stopped) return;
                    beginShrink();
                },
                ticks);
    }

    private void cancelWait() {
        if (waitTask != null) {
            waitTask.cancel();
            waitTask = null;
        }
    }

    private long currentTick() {
        return plugin.getServer().getCurrentTick();
    }

    private enum SubPhase {
        WAIT,
        SHRINK
    }

    // Immutable phase definition.
    //
    // endHeight is the Y of the volumetric "ceiling" - players above it get
    // treated as outside the border. endMinHeight is the Y of the floor -
    // players below it get the same treatment. Either NO_HEIGHT_LIMIT /
    // NO_MIN_HEIGHT means "no cap" on that side.
    public record Phase(
        int waitSeconds, int shrinkSeconds, double endRadius, double damage,
        double endHeight, double endMinHeight) {

        public static final double NO_HEIGHT_LIMIT = Double.MAX_VALUE;
        public static final double NO_MIN_HEIGHT = -Double.MAX_VALUE;

        public Phase(int waitSeconds, int shrinkSeconds, double endRadius, double damage, double endHeight) {
            this(waitSeconds, shrinkSeconds, endRadius, damage, endHeight, NO_MIN_HEIGHT);
        }

        public Phase(int waitSeconds, int shrinkSeconds, double endRadius, double damage) {
            this(waitSeconds, shrinkSeconds, endRadius, damage, NO_HEIGHT_LIMIT, NO_MIN_HEIGHT);
        }
    }
}
