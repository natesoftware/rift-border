package com.natesoftware.riftborder;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;

/**
 * Branding and presentation hooks that a host plugin supplies when constructing a {@link GameBorder}. Every method has a default,
 * so {@code new BorderCallbacks() {}} is a complete implementation and a host overrides only what it wants to change. Passing one
 * to {@link GameBorder#withCallbacks(BorderCallbacks)} is mandatory before {@link GameBorder#spawn(double)}.
 * <p>
 * {@link #warningTitle()}, {@link #enterSoundKey()} and {@link #enterLongSoundKey()} are read during
 * {@link GameBorder#spawn(double)} and held until {@link GameBorder#remove()}; a re-spawn reads them again. {@link #wallColor()}
 * and {@link #shrinkColor()} are read every tick of a transition and every 10 ticks otherwise by the shader wall, and on every
 * particle pass while the radius is above 0 by the particle wall, and
 * {@link #indicatorColor()} on every pulse of a running
 * {@link NextBorderIndicator} that has a ring to draw. The event hooks run on the main thread: the tracker hooks from the
 * border's tick task, or from {@link GameBorder#remove()} for {@link #onWarningCleared(UUID)}, and the phase hooks from the
 * controller's wait task, from the border's animation task when a shrink lands, or synchronously inside the
 * {@link BorderPhaseController#start(int)} or {@link BorderPhaseController#syncToGameTimer(int)} call that triggers them.
 */
public interface BorderCallbacks {

    /**
     * Title shown to a player while they are outside the border, or null to skip the persistent title entirely. Shown with a
     * day-long stay the tick a player is first found outside, re-shown once a second while they remain outside, and cleared when
     * they step back inside, switch to creative or spectator, leave the world, drop out of the participant set, or the border is
     * removed. Defaults to null.
     */
    default Component warningTitle() {
        return null;
    }

    /**
     * Called by {@link BorderPhaseController} when a phase's wait begins, whether entered naturally or by a resync landing in the
     * wait. phase is one-based and total is the number of phases in the schedule. waitSeconds is how long the wait will last, to
     * the second: the phase's full wait when entered naturally, the remaining wait when a resync lands part-way through it, and
     * longer than the phase's own wait when a resync reads more time on the game clock than was given to
     * {@link BorderPhaseController#start(int)}, since phase 1's wait is stretched to absorb the surplus. Not called when a resync
     * lands mid-shrink; only {@link #shrinkStarted()} fires then. Does nothing by default.
     */
    default void phaseStarted(int phase, int total, int waitSeconds) {}

    /**
     * Called by {@link BorderPhaseController} when the current phase's wait is over and the border begins shrinking, and again
     * when a resync lands mid-shrink. Does nothing by default.
     */
    default void shrinkStarted() {}

    /**
     * Called the first tick a participating survival or adventure player is found outside the border, whether beyond the radius,
     * above the ceiling or below the floor. Fires before the enter sound and warning title, once per excursion. Does nothing by
     * default.
     */
    default void onWarningShown(UUID player) {}

    /**
     * Called when a player who was outside stops being tracked: they step back inside, switch to creative or spectator, leave the
     * world or server, drop out of the participant set, or the border is removed while they are still outside. For a player still
     * online the long-outside sound is stopped alongside it if it has played, and the title is cleared when a warning title is
     * configured, every path skipping the clear when none is. Does nothing by default.
     */
    default void onWarningCleared(UUID player) {}

    /**
     * Plays the notification sound for the start of a phase. Called by {@link BorderPhaseController} right after
     * {@link #phaseStarted(int, int, int)} when a phase is entered naturally, but not when a resync jumps into a phase. Does
     * nothing by default.
     */
    default void playPhaseSound() {}

    /**
     * Plays the ambient sound for a shrink getting under way. Called by {@link BorderPhaseController} right after
     * {@link #shrinkStarted()} when a shrink begins naturally, but not when a resync lands mid-shrink. Does nothing by default.
     */
    default void playShrinkSound() {}

    /**
     * Namespaced sound key played once, at volume 0.5 and pitch 1.0 in the master category, when a player first crosses outside
     * the border. Read once at spawn. Null plays nothing. Defaults to {@code minecraft:block.anvil.land}.
     */
    default String enterSoundKey() {
        return "minecraft:block.anvil.land";
    }

    /**
     * Namespaced sound key played while a player remains outside for a sustained interval: first once they have been outside for
     * five seconds, then every five seconds after that, at volume 0.5 and pitch 1.0 in the master category. Stopped for the player,
     * if it has played, when they stop being tracked as outside, as listed on {@link #onWarningCleared(UUID)}. Read once at spawn.
     * Null plays nothing. Defaults to {@code minecraft:entity.wither.spawn}.
     */
    default String enterLongSoundKey() {
        return "minecraft:entity.wither.spawn";
    }

    /** The wall colour {@link #wallColor()} answers by default, the rift-border pack's own aqua, {@code #55FFFF}. */
    Color DEFAULT_WALL_COLOR = Color.fromRGB(0x55, 0xFF, 0xFF);

    /**
     * Colour of the border wall in both render modes: the dust of the particle wall, and the tint of the shader wall, whose
     * display items carry it as their dyed colour for the pack to tint from. Both follow a change while the border is live: the
     * shader wall checks every 10 ticks and re-dyes its displays when the answer differs, the particle wall reads it on every
     * pass, every 40 ticks. Return a stored value rather than building one per call. A null answer draws
     * {@link #DEFAULT_WALL_COLOR}, which is also the default.
     */
    default Color wallColor() {
        return DEFAULT_WALL_COLOR;
    }

    /**
     * Colour the wall switches to while the border is moving - shrinking, growing or re-centring, from
     * {@link GameBorder#moveTo(double, double, double, int)} or a {@link BorderPhaseController} shrink - and back from once it
     * lands or pauses. The shader wall switches on the first tick of the move and back within 10 ticks of it ending; the
     * particle wall on its next pass, every 40 ticks. Null keeps {@link #wallColor()} throughout, which is the default.
     */
    default Color shrinkColor() {
        return null;
    }

    /**
     * Dust colour of the {@link NextBorderIndicator} ring previewing where the current phase's shrink will land. Read on every
     * pulse that has a ring to draw, every 40 ticks, so the colour may change while the indicator runs; a null answer draws
     * white. Defaults to white.
     */
    default Color indicatorColor() {
        return Color.WHITE;
    }
}
