package com.natesoftware.riftborder;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;

/**
 * Branding and presentation hooks that a host plugin supplies when constructing a {@link GameBorder}. Every method has a default,
 * so {@code new BorderCallbacks() {}} is a complete implementation and a host overrides only what it wants to change. Passing one
 * to {@link GameBorder#withCallbacks(BorderCallbacks)} is mandatory before {@link GameBorder#spawn(double)}.
 * <p>
 * {@link #wallItemModel()}, {@link #warningTitle()}, {@link #enterSoundKey()} and {@link #enterLongSoundKey()} are read during
 * {@link GameBorder#spawn(double)} and held until {@link GameBorder#remove()}; a re-spawn reads them again. {@link #particleColor()}
 * is read on every particle pass while the radius is above 0. The event hooks run on the main thread: the tracker hooks from the
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
     * Item-model key for the wall display entities, or null when no resource pack is installed. Non-null makes the border spawn
     * its {@code ItemDisplay} anchor grid carrying a {@code Material.PAPER} stack with this item model, and every player then
     * defaults to {@link BorderRenderMode#SHADER}. Null skips the grid entirely, never consults the render-mode resolver and renders
     * the border as particles for everyone. Defaults to null.
     */
    default NamespacedKey wallItemModel() {
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
     * online the long-outside sound is stopped alongside it if it has played, and the title is cleared, except that stepping back
     * inside and removal skip the clear when no warning title is configured. Does nothing by default.
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

    /**
     * Dust colour of the particle wall shown to players in {@link BorderRenderMode#PARTICLE}. Read on every particle pass, every
     * 40 ticks, so the colour may change while the border is live. Defaults to white.
     */
    default Color particleColor() {
        return Color.WHITE;
    }
}
