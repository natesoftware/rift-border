package com.natesoftware.riftborder;

/**
 * Decides where each phase shrinks to. The controller clamps the answer so the new circle always fits inside the current one,
 * pulling a point that lies too far out back along its ray from the current centre. Consulted once per phase per run of the
 * schedule, a new {@link BorderPhaseController#start(int)} discarding the cached answers, and cached for the shrink and for
 * {@link NextBorderIndicator}: under natural progression as the phase's wait begins, while a resync consults it for
 * every not-yet-placed phase up to the one it lands in, in order, inside the {@link BorderPhaseController#syncToGameTimer(int)}
 * call. Runs on whichever thread drives the controller, the main thread in normal use. Install one with
 * {@link BorderPhaseController#withTargetSelector(ShrinkTargetSelector)} or {@link BorderPhaseController#withFixedCenter(boolean)}.
 */
@FunctionalInterface
public interface ShrinkTargetSelector {

    /**
     * A uniformly random point inside the current zone, constrained so the new circle stays inside. Draws an angle and then an
     * area-uniform distance from {@link ShrinkContext#rng()}, in that order, so a seeded random reproduces the same target. When
     * the shrink is under a block, including a phase that grows, it returns the current centre without touching the random. The
     * default selector.
     */
    ShrinkTargetSelector RANDOM_INSIDE = ctx -> {
        double maxOffset = Math.max(0, ctx.currentRadius() - ctx.targetRadius());
        if (maxOffset < 1.0) return new BorderPoint(ctx.currentX(), ctx.currentZ());
        double angle = ctx.rng().nextDouble() * 2 * Math.PI;
        double dist = Math.sqrt(ctx.rng().nextDouble()) * maxOffset;
        return new BorderPoint(ctx.currentX() + dist * Math.cos(angle), ctx.currentZ() + dist * Math.sin(angle));
    };

    /**
     * Every phase shrinks toward the map centre passed to the controller's constructor, reading nothing else from the context.
     * The controller's clamp still applies, so a map centre further away than one shrink allows is approached over several phases.
     */
    ShrinkTargetSelector FIXED_CENTER = ctx -> new BorderPoint(ctx.mapCenterX(), ctx.mapCenterZ());

    /**
     * Returns the proposed centre for the phase described by ctx, or null to keep the current centre. The controller clamps the
     * point so the phase's end circle fits inside the circle it shrinks from.
     */
    BorderPoint select(ShrinkContext ctx);
}
