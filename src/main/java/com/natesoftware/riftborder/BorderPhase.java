package com.natesoftware.riftborder;

// One step of a border schedule: wait, then shrink to endRadius over shrinkSeconds, dealing damage/s to anyone outside.
// endHeight and endMinHeight are the ceiling and floor Y once the shrink lands - GameBorder.NO_HEIGHT_LIMIT / NO_MIN_HEIGHT for none.
public record BorderPhase(
    int waitSeconds, int shrinkSeconds, double endRadius, double damage,
    double endHeight, double endMinHeight) {

    // Convenience constructor with a ceiling but no floor.
    public BorderPhase(int waitSeconds, int shrinkSeconds, double endRadius, double damage, double endHeight) {
        this(waitSeconds, shrinkSeconds, endRadius, damage, endHeight, GameBorder.NO_MIN_HEIGHT);
    }

    // Convenience constructor with neither ceiling nor floor.
    public BorderPhase(int waitSeconds, int shrinkSeconds, double endRadius, double damage) {
        this(waitSeconds, shrinkSeconds, endRadius, damage, GameBorder.NO_HEIGHT_LIMIT, GameBorder.NO_MIN_HEIGHT);
    }
}
