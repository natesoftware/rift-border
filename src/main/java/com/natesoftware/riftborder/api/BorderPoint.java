package com.natesoftware.riftborder.api;

/**
 * A horizontal position in the border's world, as world coordinates on the X and Z axes, not block-aligned. Returned by a
 * {@link ShrinkTargetSelector} to propose where a phase should land, and by {@link BorderPhaseController#getTargetCenter()} for
 * the centre the current phase is heading to.
 */
public record BorderPoint(double x, double z) {
}
