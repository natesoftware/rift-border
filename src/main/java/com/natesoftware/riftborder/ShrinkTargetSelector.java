package com.natesoftware.riftborder;

// Decides where each phase shrinks to. The controller clamps the answer so the new circle always fits inside the current one.
@FunctionalInterface
public interface ShrinkTargetSelector {

    // A uniformly random point inside the current zone, constrained so the new circle stays inside. Nudges under a block are skipped.
    ShrinkTargetSelector RANDOM_INSIDE = ctx -> {
        double maxOffset = Math.max(0, ctx.currentRadius() - ctx.targetRadius());
        if (maxOffset < 1.0) return new BorderPoint(ctx.currentX(), ctx.currentZ());
        double angle = ctx.rng().nextDouble() * 2 * Math.PI;
        double dist = Math.sqrt(ctx.rng().nextDouble()) * maxOffset;
        return new BorderPoint(ctx.currentX() + dist * Math.cos(angle), ctx.currentZ() + dist * Math.sin(angle));
    };

    // Every phase shrinks toward the map centre.
    ShrinkTargetSelector FIXED_CENTER = ctx -> new BorderPoint(ctx.mapCenterX(), ctx.mapCenterZ());

    BorderPoint select(ShrinkContext ctx);
}
