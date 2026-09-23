package com.natesoftware.riftborder.api;

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

/**
 * Drives a {@link GameBorder} through a schedule of {@link BorderPhase}s: for each phase in turn it holds the border still for
 * the wait, then shrinks it over the shrink time to the phase's end radius, ceiling and floor at a centre chosen by a
 * {@link ShrinkTargetSelector}, applying the phase's damage rate from the moment the phase is entered. Optional: a host that
 * calls {@link GameBorder#moveTo(double, double, double, int)} itself needs none of this, and the two must not be mixed, since
 * the controller owns the shape while it runs.
 * <p>
 * Where each phase lands is resolved lazily, one phase at a time: under natural progression as the phase's wait begins, or on
 * demand by a resync for every not-yet-placed phase up to the one it lands in, in order. The selector's answer is clamped along
 * its ray from the circle the phase shrinks from so each circle nests in the previous one. Phase 1 shrinks from the border
 * centre snapshotted at {@link #start(int)} and the starting radius, later phases from the previous phase's resolved target and
 * end radius. The schedule is laid out against a game clock counting down in seconds from the value given to
 * {@link #start(int)}, which is what {@link #syncToGameTimer(int)} realigns against. {@link #start()} runs that clock for exactly
 * {@link BorderPhase#totalSeconds(List)}, so a host timer set to the same total stays one to one with the border.
 * <p>
 * Constructing one registers it on the border, displacing and stopping any controller registered there before it, so
 * {@link GameBorder#remove()} stops it and the border's shrink completion advances it through a slot separate from the host's
 * {@link GameBorder#onShrinkComplete(Runnable)}, which still fires after the advance. Everything runs on the main thread: a wait
 * ends in the controller's scheduled task, a shrink's completion arrives from the border's animation task, and everything else
 * happens inside the calling method. {@link NextBorderIndicator} can preview the current target.
 */
public class BorderPhaseController {

    private static final Logger log = LoggerFactory.getLogger(BorderPhaseController.class);

    private final Plugin plugin;
    private final GameBorder border;
    private final List<BorderPhase> phases;
    private final double mapCenterX;
    private final double mapCenterZ;
    // True for the short constructor: start() then reads the starting radius off the border instead of trusting a given one.
    private final boolean radiusFromBorder;
    private double initialRadius;

    private ShrinkTargetSelector targetSelector = ShrinkTargetSelector.RANDOM_INSIDE;
    private Random random = new Random();

    // Where the border sat when start() ran - the circle phase 1 shrinks from, and where a resync before phase 1 returns it to.
    private double startCenterX;
    private double startCenterZ;

    // Target centre per phase, resolved lazily in order as each phase is entered, so selectors see live state and each circle
    // nests in the last.
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

    /**
     * Creates a controller for border over a copy of phases, which are not validated. mapCenterX and mapCenterZ are the map centre
     * handed to selectors through {@link ShrinkContext} and the target of {@link ShrinkTargetSelector#FIXED_CENTER}.
     * initialRadius is the radius phase 1 shrinks from and the one a resync returns the border to before phase 1, so it should be
     * what the border is spawned at, a mismatch being only logged by {@link #start(int)}, and only while the border is active.
     * plugin owns the wait task. The controller registers itself on the border at once, so it may be built before or after
     * {@link GameBorder#spawn(double)}, though {@link #start(int)} belongs after the spawn. Any controller already registered
     * there is stopped as {@link #stop()} describes, so neither its pending wait nor its in-flight shrink keeps driving a border
     * it no longer owns; the border's shrink completion then advances only this controller, so the displaced one is not to be
     * started again. The selector defaults to {@link ShrinkTargetSelector#RANDOM_INSIDE} with an unseeded random. Nothing runs
     * until start. {@link #BorderPhaseController(Plugin, GameBorder, List)} takes all three values from the border instead.
     */
    public BorderPhaseController(
        Plugin plugin,
        GameBorder border,
        List<BorderPhase> phases,
        double mapCenterX,
        double mapCenterZ,
        double initialRadius) {
        this(plugin, border, phases, mapCenterX, mapCenterZ, initialRadius, false);
    }

    /**
     * Creates a controller that takes its map centre and starting radius from the border itself: the map centre is the centre the
     * border was constructed with, and the starting radius is the border's radius when {@link #start(int)} runs, so spawn the
     * border first. Otherwise identical to {@link #BorderPhaseController(Plugin, GameBorder, List, double, double, double)}.
     */
    public BorderPhaseController(Plugin plugin, GameBorder border, List<BorderPhase> phases) {
        this(plugin, border, phases, border.initialCenterX, border.initialCenterZ, 0, true);
    }

    private BorderPhaseController(
        Plugin plugin,
        GameBorder border,
        List<BorderPhase> phases,
        double mapCenterX,
        double mapCenterZ,
        double initialRadius,
        boolean radiusFromBorder) {
        this.plugin = plugin;
        this.border = border;
        this.phases = List.copyOf(phases);
        this.mapCenterX = mapCenterX;
        this.mapCenterZ = mapCenterZ;
        this.initialRadius = initialRadius;
        this.radiusFromBorder = radiusFromBorder;
        // A controller already driving this border is stopped first, so its wait task cannot keep moving a border it no longer owns.
        if (border.controller != null && border.controller != this) border.controller.stop();
        border.controller = this;
        border.internalShrinkComplete = this::advancePhase;
    }

    /** The border this controller drives. */
    public GameBorder border() {
        return border;
    }

    /**
     * Picks where each phase shrinks to, as described on {@link ShrinkTargetSelector}. Null restores the default,
     * {@link ShrinkTargetSelector#RANDOM_INSIDE}. Only phases not yet placed consult it, so a change mid-schedule affects the
     * phases still to come. Returns this for chaining.
     */
    public BorderPhaseController withTargetSelector(ShrinkTargetSelector selector) {
        this.targetSelector = selector != null ? selector : ShrinkTargetSelector.RANDOM_INSIDE;
        return this;
    }

    /**
     * Shorthand for {@link #withTargetSelector(ShrinkTargetSelector)} with {@link ShrinkTargetSelector#FIXED_CENTER} when
     * fixedCenter is true, so every phase shrinks toward the map centre passed to the constructor, and with
     * {@link ShrinkTargetSelector#RANDOM_INSIDE} when false. Returns this for chaining.
     */
    public BorderPhaseController withFixedCenter(boolean fixedCenter) {
        return withTargetSelector(fixedCenter ? ShrinkTargetSelector.FIXED_CENTER : ShrinkTargetSelector.RANDOM_INSIDE);
    }

    /**
     * Source of randomness handed to selectors through {@link ShrinkContext#rng()}, shared by every phase of the schedule. Seed
     * it per round to reproduce the same zones from the same schedule and selector. Null restores an unseeded {@code Random}.
     * Returns this for chaining.
     */
    public BorderPhaseController withRandom(Random random) {
        this.random = random != null ? random : new Random();
        return this;
    }

    /**
     * Begins phase progression against a game clock exactly as long as the schedule, {@link BorderPhase#totalSeconds(List)}. The
     * usual start: give the host's own round timer the same total and the two stay one to one. Otherwise as {@link #start(int)}.
     */
    public void start() {
        start(BorderPhase.totalSeconds(phases));
    }

    /**
     * Begins phase progression against a game clock of gameDuration seconds, entering phase 1's wait immediately: its target is
     * resolved, its damage rate applied to the border, {@link BorderEvents#onPhaseStart(int, int, int)} and
     * {@link BorderEvents#playPhaseSound()} called synchronously, and the wait scheduled. The border's centre at this moment
     * is snapshotted as the circle phase 1 shrinks from, together with the starting radius: the border's live radius for a
     * controller built with the short constructor, otherwise the constructor's initialRadius, in which case a live radius that
     * differs from it while the border is active is logged as a warning, since the clamp uses initialRadius regardless. A game
     * clock longer than the schedule suits a host whose round outlasts the border's phases.
     * Calling it again, including after {@link #stop()}, restarts from phase 1 with fresh targets and an unpaused state, cancelling
     * the previous run's pending wait and freezing any transition still in flight, the previous run's shrink included, where it is
     * without firing its completion, but not moving the border back, so phase 1 then shrinks from wherever the border now sits. An
     * empty schedule completes at once, firing the all-phases callback. A gameDuration shorter than the schedule is allowed, later
     * boundaries simply falling below zero.
     */
    public void start(int gameDuration) {
        this.gameDuration = gameDuration;
        // Reset the cursor too, so a controller restarted after stop() begins at phase 1 rather than resuming stale state.
        stopped = false;
        currentPhase = -1;
        subPhase = null;
        paused = false;
        // A shrink still in flight from a previous run would land and advance the new one - freeze it where it is first.
        border.animator.reset();
        startCenterX = border.getCenterX();
        startCenterZ = border.getCenterZ();
        if (radiusFromBorder) {
            initialRadius = border.getRadius();
            if (initialRadius <= 0) log.warn("[BorderPhase] start() read a radius of 0 from the border - spawn it before starting");
        } else if (border.isActive() && border.getRadius() != initialRadius) {
            log.warn("[BorderPhase] initialRadius {} does not match the live border radius {} - phase 1 will clamp against {}",
                initialRadius, border.getRadius(), initialRadius);
        }
        precomputeTimeline();
        advancePhase();
    }

    /**
     * Cancels the controller: the pending wait is cancelled, the border's transition is reset so an in-flight shrink freezes
     * where it is rather than landing on a target the controller no longer owns, and neither the shrink-completion hook nor
     * {@link #syncToGameTimer(int)} does anything until the next {@link #start(int)}. The damage rate stays at the current
     * phase's, and the phase index, target and shrinking flag keep their last values. Neither the all-phases callback nor
     * {@link GameBorder#onShrinkComplete(Runnable)} fires for the frozen shrink. Called by {@link GameBorder#remove()} and by the
     * constructor of a controller that replaces this one on the same border. Safe to repeat.
     */
    public void stop() {
        stopped = true;
        cancelWait();
        border.animator.reset();
    }

    /**
     * Sets the callback run once every phase has landed and the border sits at the last phase's shape, replacing any earlier
     * one, with null clearing it. It runs on the main thread: from the final shrink's completion under natural progression, or
     * synchronously inside a {@link #syncToGameTimer(int)} that snaps past the end of the schedule, but not again from a repeat
     * resync past the end while the schedule is already complete. On an empty schedule it fires from {@link #start(int)}.
     * Returns this for chaining.
     */
    public BorderPhaseController onAllPhasesComplete(Runnable callback) {
        this.onAllPhasesComplete = callback;
        return this;
    }

    /**
     * Freezes the current wait or shrink where it is: a pending wait is cancelled with its remaining ticks remembered, an
     * in-flight shrink is paused on the border, and {@link #getSubPhaseRemaining()} holds at the frozen value. Damage keeps
     * applying, since the tracker belongs to the border. A resync while paused repositions the border and leaves it frozen
     * there for {@link #resume()}. Does nothing when already paused, and before {@link #start(int)} or after the schedule ends
     * it only records the flag, which start clears.
     */
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

    /**
     * Continues from where {@link #pause()} froze things. A wait is rescheduled for its remembered remainder, and a shrink resumes
     * on the border over its remembered remainder, re-interpolating from the frozen shape to the same target. In both cases the
     * countdown from {@link #getSubPhaseRemaining()} restarts from that remainder rather than counting the paused ticks as
     * elapsed. Does nothing when not paused.
     */
    public void resume() {
        if (!paused) return;
        paused = false;
        if (subPhase == SubPhase.WAIT) {
            subPhaseStartTick = currentTick();
            subPhaseDurationTicks = pausedRemainingTicks;
            scheduleWaitTicks(pausedRemainingTicks);
        } else if (subPhase == SubPhase.SHRINK) {
            // the HUD countdown restarts from the frozen remainder too, or the paused ticks would read as elapsed
            subPhaseStartTick = currentTick();
            subPhaseDurationTicks = pausedRemainingTicks;
            border.resumeShrinking(pausedRemainingTicks);
        }
    }

    /**
     * Realigns the controller with an external game clock reading gameSecondsRemaining, assuming that clock's next decrement is a
     * full second away. Equivalent to {@link #syncToGameTimer(int, long)} with an offset of 20 ticks.
     */
    public void syncToGameTimer(int gameSecondsRemaining) {
        syncToGameTimer(gameSecondsRemaining, 20L);
    }

    /**
     * Realigns the controller with an external game clock that reads gameSecondsRemaining, jumping the border and the phase
     * cursor to wherever the schedule laid out by {@link #start(int)} places them and resolving targets, in order, for every
     * phase up to that point that has none yet. Landing in a phase's wait snaps the border to the previous phase's end shape, or
     * before phase 1 to the start centre, initialRadius and no ceiling or floor, applies that phase's damage rate, calls
     * {@link BorderEvents#onPhaseStart(int, int, int)} with the remaining wait, and schedules it. Landing mid-shrink snaps
     * the border to the interpolated point along that shrink, applies the damage rate, calls
     * {@link BorderEvents#onShrinkStart()}, and resumes the shrink over the remaining seconds. No phase or shrink sound plays
     * on a resync. A reading above the gameDuration given to {@link #start(int)}, whatever the schedule adds up to, stretches
     * phase 1's wait to absorb the surplus rather than snapping the border shut. A reading at or below the end of the last shrink
     * snaps the border closed on the last phase's target, radius, ceiling and floor, applies its damage rate, and fires the
     * all-phases callback unless the schedule was already complete. While paused the border is repositioned but stays frozen for
     * {@link #resume()}. firstDecrementOffset is how many ticks until the game clock next drops a second: the part of the current
     * second it has already spent, at most 19 ticks, is taken off the remaining wait or shrink itself, and so off
     * {@link #getSubPhaseRemaining()}, so both stay in step with the host's timer. A no-op before {@link #start(int)}, after
     * {@link #stop()} and on an empty schedule.
     */
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
            boolean wasComplete = currentPhase >= phases.size();
            int lastIdx = phases.size() - 1;
            BorderPoint target = targetFor(lastIdx);
            BorderPhase last = phases.get(lastIdx);
            border.setPosition(target.x(), target.z(), last.endRadius(), last.endHeight(), last.endMinHeight());
            border.setDamagePerSecond(last.damage());
            currentPhase = phases.size();
            subPhase = null;
            // landing here by resync still means every phase has finished - fire the hook once, as advancePhase would have
            if (!wasComplete && onAllPhasesComplete != null) onAllPhasesComplete.run();
        }
    }

    /** Alias of {@link #syncToGameTimer(int)} for hosts that phrase the resync as setting the time remaining. */
    public void setRemainingSeconds(int totalSeconds) {
        syncToGameTimer(totalSeconds, 20L);
    }

    /** Alias of {@link #syncToGameTimer(int, long)}. */
    public void setRemainingSeconds(int totalSeconds, long firstDecrementOffset) {
        syncToGameTimer(totalSeconds, firstDecrementOffset);
    }

    /**
     * Returns true while the current phase is in its shrink rather than its wait, paused mid-shrink included, and false before
     * {@link #start(int)}, during a wait and once every phase has landed. {@link #stop()} leaves it at its last value.
     */
    public boolean isShrinking() {
        return subPhase == SubPhase.SHRINK;
    }

    /**
     * Zero-based index of the phase in progress: -1 before {@link #start(int)}, 0 up to the schedule length minus one while a
     * phase waits or shrinks, and the schedule length once every phase has landed. {@link BorderEvents} receive the one-based number.
     */
    public int getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Centre the current phase is shrinking toward, or will shrink toward once its wait ends, after the controller's clamp. Null
     * before {@link #start(int)} and once every phase has landed.
     */
    public BorderPoint getTargetCenter() {
        if (currentPhase < 0 || currentPhase >= phases.size()) return null;
        return currentPhase < phaseTargets.size() ? phaseTargets.get(currentPhase) : null;
    }

    /**
     * End radius of the current phase in blocks, whether it is waiting or shrinking, or -1 before {@link #start(int)} and once
     * every phase has landed. {@link NextBorderIndicator} draws nothing for 0 or less.
     */
    public double getTargetRadius() {
        if (currentPhase < 0 || currentPhase >= phases.size()) return -1;
        return phases.get(currentPhase).endRadius();
    }

    /**
     * Whole seconds left in the current wait or shrink for a HUD countdown, rounded up, so a sub-phase of N seconds reads N for
     * its entire first second and reaches 0 only on the tick it ends, matching a once-per-second game timer. Holds at the frozen
     * remainder while paused. 0 before {@link #start(int)} and once every phase has landed, and never negative. After a resync
     * with a first-decrement offset the value is already shortened to match the host clock.
     */
    public int getSubPhaseRemaining() {
        if (currentPhase < 0 || currentPhase >= phases.size() || subPhase == null) return 0;
        // Ceiling division so the displayed second stays at N for the full first second, matching a once-per-second game timer.
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
        border.events.onPhaseStart(phaseNum, phases.size(), phase.waitSeconds());
        border.events.playPhaseSound();

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

        border.events.onShrinkStart();
        border.events.playShrinkSound();
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
        border.events.onPhaseStart(phaseNum, phases.size(), waitSecondsRemaining);
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

        border.events.onShrinkStart();
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
