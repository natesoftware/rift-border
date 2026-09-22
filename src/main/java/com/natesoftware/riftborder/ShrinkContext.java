package com.natesoftware.riftborder;

import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.World;

/**
 * Everything a {@link ShrinkTargetSelector} may want to know about the phase it is placing. Built by
 * {@link BorderPhaseController} once per phase, lazily: under natural progression as that phase's wait begins, while a resync that
 * jumps ahead builds one for every not-yet-placed phase up to the one it lands in, in order, at that moment. The current values
 * describe the circle the phase shrinks from: for phase 1 that is the border centre captured by
 * {@link BorderPhaseController#start(int)} and the initial radius passed to the controller's constructor, for later phases the
 * previous phase's resolved target and end radius. targetRadius is the phase's end radius, so the selector's answer is clamped to
 * within currentRadius minus targetRadius of the current centre, or to the current centre when that is negative. phaseIndex is
 * zero-based and phaseCount is the schedule length. mapCenterX and mapCenterZ are the map centre given to the controller.
 * participants is an immutable snapshot of the border's participant set at selection time, empty when none is configured or the
 * supplier returned null. rng is the controller's {@code Random}, shared across phases so a seeded instance reproduces a whole
 * schedule.
 */
public record ShrinkContext(
    World world,
    double currentX, double currentZ, double currentRadius,
    double targetRadius,
    int phaseIndex, int phaseCount,
    double mapCenterX, double mapCenterZ,
    Set<UUID> participants,
    Random rng) {
}
