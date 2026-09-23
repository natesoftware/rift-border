package com.natesoftware.riftborder.api;

import java.util.UUID;

/**
 * What happens during a {@link GameBorder}'s life, for a host that wants to react: a phase or a shrink starting, and players
 * crossing out of the border and back. Every method does nothing by default, so {@code new BorderEvents() {}} is complete and a
 * host overrides only the events it cares about. A border given none through {@link GameBorder#withEvents(BorderEvents)} tells
 * no one. How the border looks and sounds is {@link BorderTheme}; one class may implement both.
 * <p>
 * Every event runs on the main thread: the warning events from the border's tick task, or from {@link GameBorder#remove()} for
 * {@link #onWarningCleared(UUID)}, and the phase events from the controller's wait task, from the border's animation task when
 * a shrink lands, or synchronously inside the {@link BorderPhaseController#start(int)} or
 * {@link BorderPhaseController#syncToGameTimer(int)} call that triggers them.
 */
public interface BorderEvents {

    /**
     * Called by {@link BorderPhaseController} when a phase's wait begins, whether entered naturally or by a resync landing in the
     * wait. phase is one-based and total is the number of phases in the schedule. waitSeconds is how long the wait will last, to
     * the second: the phase's full wait when entered naturally, the remaining wait when a resync lands part-way through it, and
     * longer than the phase's own wait when a resync reads more time on the game clock than was given to
     * {@link BorderPhaseController#start(int)}, since phase 1's wait is stretched to absorb the surplus. Not called when a resync
     * lands mid-shrink; only {@link #onShrinkStart()} fires then.
     */
    default void onPhaseStart(int phase, int total, int waitSeconds) {}

    /**
     * Called by {@link BorderPhaseController} when the current phase's wait is over and the border begins shrinking, and again
     * when a resync lands mid-shrink.
     */
    default void onShrinkStart() {}

    /**
     * Called the first tick a participating survival or adventure player is found outside the border, whether beyond the radius,
     * above the ceiling or below the floor. Fires before the enter sound and warning title, once per excursion.
     */
    default void onWarningShown(UUID player) {}

    /**
     * Called when a player who was outside stops being tracked: they step back inside, switch to creative or spectator, leave the
     * world or server, drop out of the participant set, or the border is removed while they are still outside. For a player still
     * online the long-outside sound is stopped alongside it if it has played, and the title is cleared when a warning title is
     * configured, every path skipping the clear when none is.
     */
    default void onWarningCleared(UUID player) {}

    /**
     * Plays the notification sound for the start of a phase. Called by {@link BorderPhaseController} right after
     * {@link #onPhaseStart(int, int, int)} when a phase is entered naturally, but not when a resync jumps into a phase, so a
     * host re-syncing to its own clock re-announces the phase without replaying the sound.
     */
    default void playPhaseSound() {}

    /**
     * Plays the ambient sound for a shrink getting under way. Called by {@link BorderPhaseController} right after
     * {@link #onShrinkStart()} when a shrink begins naturally, but not when a resync lands mid-shrink.
     */
    default void playShrinkSound() {}
}
