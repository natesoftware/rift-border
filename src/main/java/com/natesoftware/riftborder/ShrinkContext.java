package com.natesoftware.riftborder;

import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.World;

// Everything a ShrinkTargetSelector may want to know about the phase it is placing. current* is the circle the phase shrinks from.
public record ShrinkContext(
    World world,
    double currentX, double currentZ, double currentRadius,
    double targetRadius,
    int phaseIndex, int phaseCount,
    double mapCenterX, double mapCenterZ,
    Set<UUID> participants,
    Random rng) {
}
