package com.natesoftware.riftborder.api;

import java.util.List;

/**
 * One step of a border schedule: wait, then shrink to endRadius over shrinkSeconds, dealing damage per second to anyone outside.
 * Both durations are in seconds, unlike {@link GameBorder#moveTo(double, double, double, int)} which takes ticks. The wait holds
 * the border still for waitSeconds, then the shrink interpolates centre, radius, ceiling and floor to this phase's end values over
 * shrinkSeconds. The damage rate is applied to the border as soon as the phase is entered, so it covers the wait as well as the
 * shrink, and 0 warns without hurting. endHeight and endMinHeight are the ceiling and floor Y once the shrink lands, with
 * {@link GameBorder#NO_HEIGHT_LIMIT} and {@link GameBorder#NO_MIN_HEIGHT} meaning none. Values are not validated.
 */
public record BorderPhase(
    int waitSeconds, int shrinkSeconds, double endRadius, double damage,
    double endHeight, double endMinHeight) {

    /** Convenience constructor with a ceiling but no floor, so endMinHeight is {@link GameBorder#NO_MIN_HEIGHT}. */
    public BorderPhase(int waitSeconds, int shrinkSeconds, double endRadius, double damage, double endHeight) {
        this(waitSeconds, shrinkSeconds, endRadius, damage, endHeight, GameBorder.NO_MIN_HEIGHT);
    }

    /** Convenience constructor with neither ceiling nor floor, so both end heights are the no-limit sentinels. */
    public BorderPhase(int waitSeconds, int shrinkSeconds, double endRadius, double damage) {
        this(waitSeconds, shrinkSeconds, endRadius, damage, GameBorder.NO_HEIGHT_LIMIT, GameBorder.NO_MIN_HEIGHT);
    }

    /**
     * The whole schedule's length in seconds, every phase's wait plus its shrink. The clock {@link BorderPhaseController#start()}
     * runs, and the round length a host timer should use to stay in step with the border.
     */
    public static int totalSeconds(List<BorderPhase> phases) {
        int total = 0;
        for (BorderPhase phase : phases) total += phase.waitSeconds() + phase.shrinkSeconds();
        return total;
    }
}
