package com.natesoftware.riftborder;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A circular, volumetric border for one Minecraft world: a cylinder of a given radius around a centre, optionally capped by a
 * ceiling and a floor, that can be shrunk or moved over time and hurts players who stay outside it. Construct one, chain
 * {@link #withCallbacks(BorderCallbacks)} and whichever other builders apply, then {@link #spawn(double)} to bring it up and
 * {@link #remove()} to tear it down. The shape is driven either by hand, through {@link #moveTo(double, double, double, int)},
 * {@link #setPosition(double, double, double)} and the pause and resume methods, or by a {@link BorderPhaseController} that walks
 * it through a schedule of {@link BorderPhase}s and owns the shape while it runs.
 * <p>
 * While active the border renders its wall to every player in the world and tracks the players it applies to. With a resource
 * pack, signalled by a non-null {@link BorderCallbacks#wallItemModel()}, the wall is a shader cylinder mounted on a grid of
 * invisible {@code ItemDisplay} anchors around the construction centre, each player being shown the anchor nearest them, and a
 * per-player resolver may switch individuals to a particle wall instead. Without a pack everyone gets particles. The damage
 * tracker runs every tick from spawn to removal regardless of the damage rate, warning, sounding and hurting anyone outside the
 * radius, above the ceiling or below the floor, with the branding and hooks supplied through {@link BorderCallbacks}. Every task
 * runs on the main thread and every method expects to be called from it.
 */
public class GameBorder {

    private static final Logger log = LoggerFactory.getLogger(GameBorder.class);

    /**
     * Sentinel ceiling meaning the border has no upper bound, {@code Double.MAX_VALUE}. It is what {@link #getMaxHeight()} reports
     * until a ceiling is set and the end height to pass to {@link #moveTo(double, double, double, double, int)} or a
     * {@link BorderPhase} to take one away. While a transition animates between this and a real ceiling the world's max build
     * height stands in for it, so the ceiling visibly descends from the top of the world or rises back to it before the sentinel
     * is restored on the final tick.
     */
    public static final double NO_HEIGHT_LIMIT = Double.MAX_VALUE;

    /**
     * Sentinel floor meaning the border has no lower bound, negative {@code Double.MAX_VALUE}. It is what {@link #getMinHeight()}
     * reports until a floor is set and the end height to pass to {@link #moveTo(double, double, double, double, double, int)} or
     * a {@link BorderPhase} to take one away. While a transition animates between this and a real floor the world's min build
     * height stands in for it, so the floor visibly rises from the bottom of the world or sinks back to it before the sentinel is
     * restored on the final tick.
     */
    public static final double NO_MIN_HEIGHT = -Double.MAX_VALUE;

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
    private double maxHeight = NO_HEIGHT_LIMIT;

    // Y of the volumetric floor.
    private double minHeight = NO_MIN_HEIGHT;

    // Stamped on this border's wall entities so an orphan sweep can tell them apart from another live border's.
    final UUID id = UUID.randomUUID();

    // Wall anchor grid: spacing between the display entities, the hard cap on the grid's half-extent, and the Y they sit at.
    int gridSpacing = 80;
    int gridMaxExtent = 500;
    int anchorY;

    Supplier<Set<UUID>> participantSupplier;
    Runnable onShrinkComplete;
    // The phase controller's advance hook - its own slot, so it and the host's onShrinkComplete never overwrite each other.
    Runnable internalShrinkComplete;
    BorderCallbacks callbacks;
    // Registered by BorderPhaseController so remove() stops phase progression - teardown order stops being load-bearing on the caller.
    BorderPhaseController controller;
    Function<UUID, BorderRenderMode> renderModeResolver = uuid -> BorderRenderMode.SHADER;

    final BorderRenderer renderer;
    final ParticleBorderRenderer particleRenderer;
    final BorderShrinkAnimator animator;
    final BorderDamageTracker damageTracker;

    private boolean active;

    // Resolved at spawn from callbacks.wallItemModel(). False means there is no pack, so the shader wall has nothing to draw.
    private boolean packConfigured;

    /**
     * Creates an inactive border for world centred at (centerX, centerZ). centerY is the height the wall's display geometry is
     * centred on and is fixed for the border's life, while the horizontal centre moves with every transition and returns to
     * these values on each {@link #spawn(double)}. plugin owns every task and wall entity the border creates. The radius is 0
     * until spawn or a shape call sets it, and there is no ceiling or floor until a shape call sets one. Wall anchors default to
     * a Y of 319 or the world's max build height minus one, whichever is lower, and the anchor grid to 80-block spacing capped at
     * 500 blocks from the centre. Nothing is scheduled or spawned here.
     */
    public GameBorder(Plugin plugin, World world, double centerX, double centerY, double centerZ) {
        this.plugin = plugin;
        this.world = world;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.initialCenterX = centerX;
        this.initialCenterZ = centerZ;
        // Above terrain so blocks don't occlude the anchors, but never above what this world can hold.
        this.anchorY = Math.min(319, world.getMaxHeight() - 1);
        this.renderer = new BorderRenderer(this);
        this.particleRenderer = new ParticleBorderRenderer(this);
        this.animator = new BorderShrinkAnimator(this);
        this.damageTracker = new BorderDamageTracker(this);
    }

    /**
     * Restricts damage, warning titles, sounds and the height indicators to players whose UUIDs appear in the set supplier
     * returns, queried every tick by the damage tracker and again by {@link BorderPhaseController} when it snapshots participants
     * for a {@link ShrinkTargetSelector}. Without one, or whenever the supplier returns null, every player in the world is a
     * candidate. A player who drops out of the set while outside is cleared as listed on
     * {@link BorderCallbacks#onWarningCleared(UUID)}. Wall rendering ignores the set. Returns this for chaining.
     */
    public GameBorder withParticipants(Supplier<Set<UUID>> supplier) {
        this.participantSupplier = supplier;
        return this;
    }

    /**
     * Registers the host's hook for a shrink or move finishing naturally, replacing any earlier one, with null clearing it. It
     * runs on the main thread from the animation task on the tick the interpolation lands, after an attached
     * {@link BorderPhaseController} has already advanced to its next phase through a separate slot, so a hook that reads
     * controller state sees the new phase. It does not fire when the border is removed mid-transition, when a new
     * {@link #moveTo(double, double, double, int)} or {@link #setPosition(double, double, double)} replaces the transition, or
     * when the controller stops. Returns this for chaining.
     */
    public GameBorder onShrinkComplete(Runnable callback) {
        this.onShrinkComplete = callback;
        return this;
    }

    /**
     * Wires in the host's branding and presentation hooks. Mandatory: {@link #spawn(double)} throws without one. When each hook
     * is read is described on {@link BorderCallbacks}, and {@code new BorderCallbacks() {}} is a complete implementation. Returns
     * this for chaining.
     */
    public GameBorder withCallbacks(BorderCallbacks callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    /**
     * Per-player choice between the shader wall and the particle wall, consulted with the player's UUID on every visibility pass,
     * every 10 ticks, and every particle pass, every 40 ticks, so a player can switch mid-game. A null answer means
     * {@link BorderRenderMode#SHADER}, which is also what the default resolver answers for everyone. resolver itself must not be
     * null. It is never consulted while no pack is configured, since {@link BorderRenderMode#PARTICLE} is then forced for every
     * player. Returns this for chaining.
     */
    public GameBorder withRenderModeResolver(Function<UUID, BorderRenderMode> resolver) {
        this.renderModeResolver = resolver;
        return this;
    }

    /**
     * Geometry of the anchor grid the shader wall is mounted on: spacing is the distance in blocks between neighbouring display
     * entities and maxExtent the hard cap on how far, in blocks, the grid reaches from the construction centre on each axis. The
     * grid actually spawned reaches the initial radius plus one spacing, capped at maxExtent, so a small arena never pays for a
     * full-map grid, and each anchor's chunk is kept force-loaded while the border is active. The wall only renders where an
     * anchor is near enough to track, so a radius past maxExtent silently loses its far side. {@link #spawn(double)} logs a
     * warning whenever the cap truncates the grid, that is when the initial radius plus one spacing exceeds maxExtent, and only
     * with a pack configured, since without one no grid is spawned. Defaults to 80 and 500. Throws IllegalArgumentException when
     * either value is not positive. Takes effect on the next spawn. Returns this for chaining.
     */
    public GameBorder withGrid(int spacing, int maxExtent) {
        if (spacing <= 0 || maxExtent <= 0) throw new IllegalArgumentException("grid spacing and max extent must be positive");
        this.gridSpacing = spacing;
        this.gridMaxExtent = maxExtent;
        return this;
    }

    /**
     * Y the wall anchors sit at. Defaults to 319 or the world's max build height minus one, whichever is lower, so terrain does
     * not occlude the entities. The shader draws the full cylinder whatever the anchor height, and the nearest-anchor choice is
     * made on the horizontal plane only. Read whenever an anchor is spawned, so it takes effect on the next spawn and on any
     * anchor the maintenance task respawns after a change. Returns this for chaining.
     */
    public GameBorder withAnchorY(int y) {
        this.anchorY = y;
        return this;
    }

    // Render mode for a player, treating a null resolver result as SHADER. Without a pack there is no shader wall, so everyone gets particles.
    BorderRenderMode renderModeFor(UUID uuid) {
        if (!packConfigured) return BorderRenderMode.PARTICLE;
        BorderRenderMode mode = renderModeResolver.apply(uuid);
        return mode != null ? mode : BorderRenderMode.SHADER;
    }

    /**
     * Sets the damage dealt to a player who has been outside the border for at least the one-second grace period, applied once
     * every 20 ticks, to absorption first and then to health, with a tick that would reach zero health killing through an
     * {@code OUTSIDE_BORDER} damage source instead. A value of 0 or less keeps the warning title, the sounds and the tracking but
     * deals no damage and plays no hurt feedback. Defaults to 2.0. An attached {@link BorderPhaseController} overwrites it with
     * each phase's rate as the phase is entered. Takes effect on the next damage check.
     */
    public void setDamagePerSecond(double damage) {
        this.damagePerSecond = damage;
    }

    /** Returns true from the moment {@link #spawn(double)} succeeds until {@link #remove()} runs, and false before and after. */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns true when (x, z) lies strictly beyond the current radius on the horizontal plane, ignoring the ceiling and floor,
     * which {@link #isAboveHeight(double)} and {@link #isBelowMinHeight(double)} cover. A point exactly on the circle is inside.
     */
    public boolean isOutside(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz > radius * radius;
    }

    /** Current X of the centre in world coordinates, moving every tick while a transition animates. */
    public double getCenterX() {
        return centerX;
    }

    /** Current Z of the centre in world coordinates, moving every tick while a transition animates. */
    public double getCenterZ() {
        return centerZ;
    }

    /** Current radius in blocks, changing every tick while a transition animates, and 0 before the first spawn or shape call. */
    public double getRadius() {
        return radius;
    }

    /**
     * Radius the shader wall's pattern is anchored to: the end radius of the in-flight transition while it shrinks, otherwise the
     * live radius, so a growing transition anchors to the live radius throughout. A fixed anchor keeps the pattern from sliding
     * along the wall mid-shrink and its density converges as the wall lands. Carried to the wall entities as their Z scale.
     */
    public double getPatternRadius() {
        double end = animator.activeEndRadius();
        return !Double.isNaN(end) && end < radius ? end : radius;
    }

    /** Damage per second currently configured, from {@link #setDamagePerSecond(double)} or the active phase. Defaults to 2.0. */
    public double getDamagePerSecond() {
        return damagePerSecond;
    }

    /**
     * Current ceiling Y, or {@link #NO_HEIGHT_LIMIT} when there is none. While a transition animates a ceiling in or out, this is
     * the interpolated value with the world's max build height standing in for the sentinel at whichever end has no ceiling. A
     * transition that keeps the same ceiling at both ends, including none at all, reports that value unchanged throughout.
     */
    public double getMaxHeight() {
        return maxHeight;
    }

    /**
     * Current floor Y, or {@link #NO_MIN_HEIGHT} when there is none. While a transition animates a floor in or out, this is the
     * interpolated value with the world's min build height standing in for the sentinel at whichever end has no floor. A
     * transition that keeps the same floor at both ends, including none at all, reports that value unchanged throughout.
     */
    public double getMinHeight() {
        return minHeight;
    }

    /** World the border lives in, fixed at construction. */
    public World getWorld() {
        return world;
    }

    /** Returns true when y is strictly above the current ceiling, and never for a finite y while there is no ceiling. */
    public boolean isAboveHeight(double y) {
        return y > maxHeight;
    }

    /**
     * Returns true while the ceiling is anything other than {@link #NO_HEIGHT_LIMIT}, including mid-transition while a ceiling
     * animates in or out. A border with no ceiling reports false throughout any transition that does not introduce one.
     */
    public boolean hasHeightLimit() {
        return maxHeight != NO_HEIGHT_LIMIT;
    }

    /** Returns true when y is strictly below the current floor, and never for a finite y while there is no floor. */
    public boolean isBelowMinHeight(double y) {
        return y < minHeight;
    }

    /**
     * Returns true while the floor is anything other than {@link #NO_MIN_HEIGHT}, including mid-transition while a floor animates
     * in or out. A border with no floor reports false throughout any transition that does not introduce one.
     */
    public boolean hasMinHeight() {
        return minHeight != NO_MIN_HEIGHT;
    }

    /**
     * Activates the border at initialRadius. The centre returns to the one given at construction and the ceiling and floor to
     * none, so a border spawned again after {@link #remove()} does not keep the last phase's shape. Whether a pack is configured
     * is then read from {@link BorderCallbacks#wallItemModel()} at spawn and held until removal. A non-null key spawns the anchor
     * grid around the construction centre, one invisible {@code ItemDisplay} per grid point with its chunk force-loaded, arriving
     * over the following ticks as chunks load, after sweeping wall entities left behind by a border of this plugin that was never
     * removed, and starts showing each player their nearest anchor. A null key skips the grid entirely, logs one line, and forces
     * {@link BorderRenderMode#PARTICLE} for everyone without consulting the resolver. The particle renderer starts either way,
     * serving players in particle mode every 40 ticks. The damage tracker also always starts, reading the warning title and sound
     * keys from the callbacks once: from then on every tick classifies each participating survival or adventure player as inside
     * or outside, warns, plays the sounds, every 40 ticks pulses red dust on the ceiling or floor plane around participants
     * inside the radius and within 10 blocks of that plane in any game mode, and deals the configured damage every 20 ticks after
     * a one-second grace, as described on {@link #setDamagePerSecond(double)} and {@link BorderCallbacks}. With a pack configured,
     * logs a warning when initialRadius plus one grid spacing exceeds the grid cap, as described on {@link #withGrid(int, int)}.
     * Throws IllegalStateException when already active or when no callbacks have been supplied, changing nothing.
     */
    public void spawn(double initialRadius) {
        if (active) throw new IllegalStateException("GameBorder already spawned");
        if (callbacks == null) {
            throw new IllegalStateException("GameBorder.withCallbacks(...) must be called before spawn()");
        }
        // Reset the whole shape, not just the radius - a border re-spawned after remove() would otherwise keep the last phase's centre and ceiling.
        this.radius = initialRadius;
        this.centerX = initialCenterX;
        this.centerZ = initialCenterZ;
        this.maxHeight = NO_HEIGHT_LIMIT;
        this.minHeight = NO_MIN_HEIGHT;
        this.active = true;
        this.packConfigured = callbacks.wallItemModel() != null;

        // No item-model key means no resource pack, so the display grid would render nothing - skip it and leave every player on particles.
        if (packConfigured) {
            renderer.spawn();
        } else {
            log.info("[GameBorder] No wallItemModel configured - rendering the border as particles for every player");
        }
        particleRenderer.start();
        // Always tracked: the tracker owns the warning title, the enter sounds and the height indicators, not just damage.
        damageTracker.start();
    }

    /**
     * Tears the border down: stops an attached {@link BorderPhaseController} first, so its pending wait is cancelled and an
     * in-flight shrink freezes where it is, then cancels any transition of its own without firing
     * {@link #onShrinkComplete(Runnable)}, marks the border inactive, removes the wall entities and releases their force-loaded
     * chunks, stops the particle wall, and stops the damage tracker, which calls {@link BorderCallbacks#onWarningCleared(UUID)}
     * for everyone still outside, clears their warning title when one is configured, and stops the long-outside sound for those
     * it had played to. The shape is left as it was. Safe to call when not active, and the border can be spawned again after.
     */
    public void remove() {
        if (controller != null) controller.stop();
        animator.reset();
        active = false;
        renderer.remove();
        particleRenderer.stop();
        damageTracker.stop();
    }

    /**
     * Shrinks, or grows, to endRadius over remainingTicks, keeping the current centre, ceiling and floor. Equivalent to
     * {@link #moveTo(double, double, double, int)} at the current centre, with the same completion and replacement rules.
     */
    public void startShrinking(double endRadius, int remainingTicks) {
        animator.startShrinking(endRadius, remainingTicks);
    }

    /**
     * Animates the border from its current shape to a centre of (targetX, targetZ) and a radius of endRadius over ticks ticks,
     * keeping the current ceiling and floor untouched throughout. The interpolation is linear, advanced by a task every tick, and
     * lands exactly on the end values when the budget runs out, whereupon an attached controller's hook and then
     * {@link #onShrinkComplete(Runnable)} fire. A budget of 0 or less is treated as 1 tick. Replaces any transition in flight,
     * whose completion then never fires. Works before {@link #spawn(double)} too, moving the shape without activating anything.
     * Not for use while a {@link BorderPhaseController} is running, since the controller owns the shape.
     */
    public void moveTo(double targetX, double targetZ, double endRadius, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, maxHeight, minHeight, ticks);
    }

    /**
     * Variant of {@link #moveTo(double, double, double, int)} that also interpolates the ceiling to endHeight, keeping the current
     * floor. A ceiling coming in from {@link #NO_HEIGHT_LIMIT} descends from the world's max build height, and one going out
     * rises to it before the sentinel is restored on the final tick.
     */
    public void moveTo(double targetX, double targetZ, double endRadius, double endHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, minHeight, ticks);
    }

    /**
     * Variant of {@link #moveTo(double, double, double, int)} that interpolates the ceiling to endHeight and the floor to
     * endMinHeight, the floor rising from the world's min build height when it comes in from {@link #NO_MIN_HEIGHT} and sinking
     * back to it before the sentinel is restored. This is the form {@link BorderPhaseController} drives.
     */
    public void moveTo(
        double targetX, double targetZ, double endRadius, double endHeight, double endMinHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, endMinHeight, ticks);
    }

    /**
     * Rewrites how many ticks the in-flight transition has left without changing its endpoints and re-applies the shape at the
     * matching point along the same path immediately: fewer ticks fast-forward, more rewind, and more than the original budget
     * clamps to the start. Works on a paused transition as well. Does nothing while no transition task is running.
     */
    public void setRemainingTicks(int remainingTicks) {
        animator.setRemainingTicks(remainingTicks);
    }

    /**
     * Freezes the in-flight transition where it is. The task keeps running but stops advancing, so the shape holds and
     * {@link #getPatternRadius()} keeps anchoring to the target. Continue with {@link #resumeShrinking(int)}. Has no lasting
     * effect when nothing is in flight.
     */
    public void pauseShrinking() {
        animator.pause();
    }

    /**
     * Continues a paused, or still running, transition toward the same target, re-interpolating from the live shape over a fresh
     * budget of remainingTicks, so the remaining distance is covered in exactly that time whatever fraction was done before. A
     * budget of 0 or less is treated as 1 tick. Completion fires as for {@link #moveTo(double, double, double, int)}. Does nothing
     * when no transition is in progress: before any has started, after one has landed, or since the last
     * {@link #setPosition(double, double, double)}, {@link #remove()} or {@link BorderPhaseController#stop()}.
     */
    public void resumeShrinking(int remainingTicks) {
        animator.resume(remainingTicks);
    }

    /**
     * Snaps the centre to (cx, cz) and the radius to newRadius immediately, keeping the current ceiling and floor, cancelling any
     * transition in flight without firing its completion, and pushing the new shape to the wall entities the same tick. Leaves
     * {@link #resumeShrinking(int)} nothing to resume until the next transition starts.
     */
    public void setPosition(double cx, double cz, double newRadius) {
        animator.setPosition(cx, cz, newRadius, maxHeight, minHeight);
    }

    /**
     * Snap variant of {@link #setPosition(double, double, double)} that also sets the ceiling to newHeight, with
     * {@link #NO_HEIGHT_LIMIT} meaning none. The floor is kept.
     */
    public void setPosition(double cx, double cz, double newRadius, double newHeight) {
        animator.setPosition(cx, cz, newRadius, newHeight, minHeight);
    }

    /**
     * Snap variant of {@link #setPosition(double, double, double)} that sets the ceiling to newHeight and the floor to
     * newMinHeight, with the no-limit sentinels meaning none. This is the form {@link BorderPhaseController} uses when a resync
     * repositions the border.
     */
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
