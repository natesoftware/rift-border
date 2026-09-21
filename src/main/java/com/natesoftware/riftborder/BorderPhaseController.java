package com.natesoftware.riftborder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Convenience orchestrator that drives a GameBorder through a sequence of BorderPhase. Optional - drive moveTo yourself to skip it.
public class BorderPhaseController {

    private static final Logger log = LoggerFactory.getLogger(BorderPhaseController.class);

    private final Plugin plugin;
    private final GameBorder border;
    private final List<BorderPhase> phases;
    private final double mapCenterX;
    private final double mapCenterZ;
    private final double initialRadius;

    private ShrinkTargetSelector targetSelector = ShrinkTargetSelector.RANDOM_INSIDE;
    private Random random = new Random();

    // Where the border sat when start() ran - the circle phase 1 shrinks from, and where a resync before phase 1 returns it to.
    private double startCenterX;
    private double startCenterZ;

    // Target centre per phase, resolved lazily in order as each phase is entered so selectors see live state and each circle nests in the last.
    private final List<BorderPoint> phaseTargets = new ArrayList<>();

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
        List<BorderPhase> phases,
        double mapCenterX,
        double mapCenterZ,
        double initialRadius) {
        this.plugin = plugin;
        this.border = border;
        this.phases = List.copyOf(phases);
        this.mapCenterX = mapCenterX;
        this.mapCenterZ = mapCenterZ;
        this.initialRadius = initialRadius;
        border.controller = this;
    }

    // Picks where each phase shrinks to. Defaults to a random point inside the current zone.
    public BorderPhaseController withTargetSelector(ShrinkTargetSelector selector) {
        this.targetSelector = selector != null ? selector : ShrinkTargetSelector.RANDOM_INSIDE;
        return this;
    }

    // Shorthand for withTargetSelector(FIXED_CENTER): every phase shrinks toward the map centre passed to the constructor.
    public BorderPhaseController withFixedCenter(boolean fixedCenter) {
        return withTargetSelector(fixedCenter ? ShrinkTargetSelector.FIXED_CENTER : ShrinkTargetSelector.RANDOM_INSIDE);
    }

    // Source of randomness handed to selectors - seed it per round for reproducible zones.
    public BorderPhaseController withRandom(Random random) {
        this.random = random != null ? random : new Random();
        return this;
    }

    // Begins phase progression.
    public void start(int gameDuration) {
        this.gameDuration = gameDuration;
        // Reset the cursor too, so a controller restarted after stop() begins at phase 1 rather than resuming stale state.
        stopped = false;
        currentPhase = -1;
        subPhase = null;
        paused = false;
        startCenterX = border.getCenterX();
        startCenterZ = border.getCenterZ();
        precomputeTimeline();
        advancePhase();
    }

    // Cancels the controller.
    public void stop() {
        stopped = true;
        cancelWait();
    }

    // Sets a callback invoked when all phases finish (the border has fully closed).
    public void setOnAllPhasesComplete(Runnable callback) {
        this.onAllPhasesComplete = callback;
    }

    // Pauses the current sub-phase (WAIT or SHRINK).
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

    // Resumes a paused sub-phase from where pause froze it.
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

    public void syncToGameTimer(int gameSecondsRemaining) {
        syncToGameTimer(gameSecondsRemaining, 20L);
    }

    // firstDecrementOffset: ticks from now until the sub-phase display should next drop, so the phase HUD timer stays aligned with an external
    // game timer that may already be partway through its current second.
    public void syncToGameTimer(int gameSecondsRemaining, long firstDecrementOffset) {
        if (stopped) return;
        // The timeline only exists after start() - syncing before that would index an empty list.
        if (phaseWaitStart.isEmpty()) return;
        cancelWait();

        int offsetCompensation = (int) Math.max(0L, Math.min(19L, 20L - firstDecrementOffset));

        // More time on the clock than the schedule covers: stretch phase 1's wait to absorb it, rather than
        // falling past every phase into the fully-closed branch below and slamming the wall shut.
        if (gameSecondsRemaining > phaseWaitStart.get(0)) {
            jumpToWait(0, gameSecondsRemaining - phaseShrinkStart.get(0), offsetCompensation);
            return;
        }

        for (int i = 0; i < phases.size(); i++) {
            int waitStart = phaseWaitStart.get(i);
            int shrinkStart = phaseShrinkStart.get(i);
            int end = phaseEnd.get(i);

            if (gameSecondsRemaining > waitStart) continue;
            if (gameSecondsRemaining > shrinkStart) {
                int waitRemaining = gameSecondsRemaining - shrinkStart;
                jumpToWait(i, waitRemaining, offsetCompensation);
                return;
            }
            if (gameSecondsRemaining > end) {
                int shrinkTotal = phases.get(i).shrinkSeconds();
                int shrinkElapsed = shrinkStart - gameSecondsRemaining;
                jumpToShrink(i, shrinkTotal, shrinkElapsed, offsetCompensation);
                return;
            }
        }

        // Past all phases - border fully closed.
        if (!phases.isEmpty()) {
            int lastIdx = phases.size() - 1;
            BorderPoint target = targetFor(lastIdx);
            BorderPhase last = phases.get(lastIdx);
            border.setPosition(target.x(), target.z(), last.endRadius(), last.endHeight(), last.endMinHeight());
            currentPhase = phases.size();
            subPhase = null;
        }
    }

    public void setRemainingTime(int totalSeconds) {
        syncToGameTimer(totalSeconds, 20L);
    }

    public void setRemainingTime(int totalSeconds, long firstDecrementOffset) {
        syncToGameTimer(totalSeconds, firstDecrementOffset);
    }

    // Returns true while the border is mid-shrink (as opposed to waiting).
    public boolean isShrinking() {
        return subPhase == SubPhase.SHRINK;
    }

    public int getCurrentPhase() {
        return currentPhase;
    }

    // Centre the current phase is shrinking toward (or about to, during WAIT), or null when no phase is active.
    public BorderPoint getTargetCenter() {
        if (currentPhase < 0 || currentPhase >= phases.size()) return null;
        return currentPhase < phaseTargets.size() ? phaseTargets.get(currentPhase) : null;
    }

    public double getTargetRadius() {
        if (currentPhase < 0 || currentPhase >= phases.size()) return -1;
        return phases.get(currentPhase).endRadius();
    }

    public int getSubPhaseRemaining() {
        if (currentPhase < 0 || currentPhase >= phases.size() || subPhase == null) return 0;
        // Ceiling division so the displayed second stays at N for the full first second, matching CountdownTimer's once-per-second decrement.
        // Floor was off-by-one: at elapsed = 1 tick, floor((1200 - 1) / 20) = 59 immediately, putting this 1s behind the game timer.
        if (paused) return Math.max(0, (pausedRemainingTicks + 19) / 20);
        int elapsed = (int) (currentTick() - subPhaseStartTick);
        return Math.max(0, (subPhaseDurationTicks - elapsed + 19) / 20);
    }

    // Lays the wait/shrink boundaries out against the game clock. Targets are not picked here - see targetFor.
    private void precomputeTimeline() {
        phaseTargets.clear();
        phaseWaitStart.clear();
        phaseShrinkStart.clear();
        phaseEnd.clear();

        int cursor = gameDuration; // game seconds remaining
        for (BorderPhase phase : phases) {
            phaseWaitStart.add(cursor);
            cursor -= phase.waitSeconds();
            phaseShrinkStart.add(cursor);
            cursor -= phase.shrinkSeconds();
            phaseEnd.add(cursor);
        }
    }

    // Target centre for phase i, resolving every earlier phase first so each circle is placed inside the one before it.
    private BorderPoint targetFor(int i) {
        while (phaseTargets.size() <= i) {
            phaseTargets.add(resolveTarget(phaseTargets.size()));
        }
        return phaseTargets.get(i);
    }

    // Asks the selector where phase i should land, then pulls the answer back along its ray so the new circle fits inside the previous one.
    private BorderPoint resolveTarget(int i) {
        double fromX;
        double fromZ;
        double fromRadius;
        if (i > 0) {
            BorderPoint prev = phaseTargets.get(i - 1);
            fromX = prev.x();
            fromZ = prev.z();
            fromRadius = phases.get(i - 1).endRadius();
        } else {
            fromX = startCenterX;
            fromZ = startCenterZ;
            fromRadius = initialRadius;
        }
        BorderPhase phase = phases.get(i);
        ShrinkContext ctx = new ShrinkContext(
            border.getWorld(), fromX, fromZ, fromRadius, phase.endRadius(), i, phases.size(),
            mapCenterX, mapCenterZ, participants(), random);
        BorderPoint proposed = targetSelector.select(ctx);
        if (proposed == null) return new BorderPoint(fromX, fromZ);

        double maxOffset = Math.max(0, fromRadius - phase.endRadius());
        double dx = proposed.x() - fromX;
        double dz = proposed.z() - fromZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= maxOffset) return proposed;
        double scale = maxOffset / dist;
        return new BorderPoint(fromX + dx * scale, fromZ + dz * scale);
    }

    // Snapshot of the border's participants for a selector, empty when none are configured.
    private Set<UUID> participants() {
        Supplier<Set<UUID>> supplier = border.participantSupplier;
        Set<UUID> set = supplier != null ? supplier.get() : null;
        return set != null ? Set.copyOf(set) : Set.of();
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

        BorderPhase phase = phases.get(currentPhase);
        // Resolved on entering the wait so the next-phase indicator can preview it and selectors see the world as it is now.
        targetFor(currentPhase);
        border.setDamagePerSecond(phase.damage());
        int phaseNum = currentPhase + 1;
        border.callbacks.phaseStarted(phaseNum, phases.size(), phase.waitSeconds());
        border.callbacks.playPhaseSound();

        subPhase = SubPhase.WAIT;
        subPhaseStartTick = currentTick();
        subPhaseDurationTicks = phase.waitSeconds() * 20;
        scheduleWaitTicks(subPhaseDurationTicks);
    }

    private void beginShrink() {
        if (stopped || currentPhase < 0 || currentPhase >= phases.size()) return;
        BorderPhase phase = phases.get(currentPhase);
        BorderPoint target = targetFor(currentPhase);

        subPhase = SubPhase.SHRINK;
        subPhaseStartTick = currentTick();
        subPhaseDurationTicks = phase.shrinkSeconds() * 20;

        border.callbacks.shrinkStarted();
        border.callbacks.playShrinkSound();
        border.onShrinkComplete(this::advancePhase);
        border.moveTo(
            target.x(), target.z(), phase.endRadius(), phase.endHeight(), phase.endMinHeight(), subPhaseDurationTicks);
    }

    private void jumpToWait(int phaseIndex, int waitSecondsRemaining, int offsetCompensation) {
        // Set border to the end state of the previous phase (or the start state if phase 0).
        if (phaseIndex > 0) {
            BorderPoint prevTarget = targetFor(phaseIndex - 1);
            BorderPhase prev = phases.get(phaseIndex - 1);
            border.setPosition(
                prevTarget.x(), prevTarget.z(), prev.endRadius(), prev.endHeight(), prev.endMinHeight());
        } else {
            border.setPosition(
                startCenterX, startCenterZ, initialRadius,
                GameBorder.NO_HEIGHT_LIMIT, GameBorder.NO_MIN_HEIGHT);
        }
        targetFor(phaseIndex);

        currentPhase = phaseIndex;
        border.setDamagePerSecond(phases.get(phaseIndex).damage());
        subPhase = SubPhase.WAIT;
        subPhaseStartTick = currentTick();
        subPhaseDurationTicks = waitSecondsRemaining * 20 - offsetCompensation;

        int phaseNum = currentPhase + 1;
        border.callbacks.phaseStarted(phaseNum, phases.size(), waitSecondsRemaining);
        if (paused) {
            // Stay frozen at the new position; resume() will reschedule when called.
            pausedRemainingTicks = subPhaseDurationTicks;
        } else {
            scheduleWaitTicks(subPhaseDurationTicks);
        }
    }

    private void jumpToShrink(int phaseIndex, int shrinkTotal, int shrinkElapsed, int offsetCompensation) {
        BorderPhase phase = phases.get(phaseIndex);
        BorderPoint target = targetFor(phaseIndex);

        // Compute the start state for this phase's shrink.
        double startCx;
        double startCz;
        double startRadius;
        double startHeight;
        double startMinHeight;
        if (phaseIndex > 0) {
            BorderPoint prevTarget = targetFor(phaseIndex - 1);
            BorderPhase prev = phases.get(phaseIndex - 1);
            startCx = prevTarget.x();
            startCz = prevTarget.z();
            startRadius = prev.endRadius();
            startHeight = prev.endHeight();
            startMinHeight = prev.endMinHeight();
        } else {
            startCx = startCenterX;
            startCz = startCenterZ;
            startRadius = initialRadius;
            startHeight = GameBorder.NO_HEIGHT_LIMIT;
            startMinHeight = GameBorder.NO_MIN_HEIGHT;
        }

        // Interpolate to the current position within the shrink.
        double progress = (double) shrinkElapsed / shrinkTotal;
        double cx = startCx + (target.x() - startCx) * progress;
        double cz = startCz + (target.z() - startCz) * progress;
        double r = startRadius + (phase.endRadius() - startRadius) * progress;
        double h = interpolateBound(
            startHeight, phase.endHeight(), progress, GameBorder.NO_HEIGHT_LIMIT, border.getWorld().getMaxHeight());
        double mh = interpolateBound(
            startMinHeight, phase.endMinHeight(), progress, GameBorder.NO_MIN_HEIGHT, border.getWorld().getMinHeight());
        border.setPosition(cx, cz, r, h, mh);

        // Resume shrinking for the remaining time.
        int remainingSeconds = shrinkTotal - shrinkElapsed;
        currentPhase = phaseIndex;
        border.setDamagePerSecond(phases.get(phaseIndex).damage());
        subPhase = SubPhase.SHRINK;
        subPhaseStartTick = currentTick();
        subPhaseDurationTicks = remainingSeconds * 20 - offsetCompensation;

        border.callbacks.shrinkStarted();
        border.onShrinkComplete(this::advancePhase);
        border.moveTo(
            target.x(), target.z(), phase.endRadius(), phase.endHeight(), phase.endMinHeight(), subPhaseDurationTicks);
        if (paused) {
            // moveTo() unfroze the animator.
            border.pauseShrinking();
            pausedRemainingTicks = subPhaseDurationTicks;
        }
    }

    // Lerps a ceiling or floor Y, substituting the world bound for the no-limit sentinel first - Double.MAX_VALUE arithmetic
    // lands on ~9e307 rather than a usable height, so an un-substituted resync leaves the ceiling parked in orbit.
    private static double interpolateBound(
        double start, double end, double progress, double sentinel, double worldBound) {
        if (start == end) return end;
        double effStart = start == sentinel ? worldBound : start;
        double effEnd = end == sentinel ? worldBound : end;
        if (effStart == effEnd) return effEnd;
        return effStart + (effEnd - effStart) * progress;
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
}
