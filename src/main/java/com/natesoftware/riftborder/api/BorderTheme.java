package com.natesoftware.riftborder.api;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;

/**
 * How a {@link GameBorder} looks and sounds: the wall colours, the warning subtitle and the sounds a player hears outside it. Every
 * method has a default, so {@code new BorderTheme() {}} is a complete theme and a host overrides only what it wants to change. A
 * border given none through {@link GameBorder#withTheme(BorderTheme)} uses the defaults. What happens during a border's life,
 * phases starting and players crossing out, is {@link BorderEvents}; one class may implement both.
 * <p>
 * {@link #warningSubtitle()}, {@link #enterSound()} and {@link #enterLongSound()} are read during {@link GameBorder#spawn(double)}
 * and held until {@link GameBorder#remove()}; a re-spawn reads them again. {@link #wallColor()} and {@link #shrinkColor()} are
 * read every tick of a transition and every 10 ticks otherwise by the shader wall, and on every particle pass while the radius
 * is above 0 by the particle wall, and {@link #indicatorColor()} on every pulse of a running {@link NextBorderIndicator} that
 * has a ring to draw.
 */
public interface BorderTheme {

    /** The subtitle {@link #warningSubtitle()} shows by default: Border Warning in red small caps. */
    Component DEFAULT_WARNING_SUBTITLE = Component.text("ʙᴏʀᴅᴇʀ ᴡᴀʀɴɪɴɢ", NamedTextColor.RED);

    /** The wall colour {@link #wallColor()} answers by default, the rift-border pack's own aqua, {@code #55FFFF}. */
    Color DEFAULT_WALL_COLOR = Color.fromRGB(0x55, 0xFF, 0xFF);

    /**
     * Subtitle flashed to a player the tick they cross out of the border, or null for none. Shown once per crossing for one
     * second, then it fades out over half a second. It is never re-sent while the player stays outside and never cleared when
     * they come back, so it simply runs its course. A subtitle only displays alongside a title, so it is sent under an empty
     * title, which replaces any title on the player's screen at that moment. Defaults to {@link #DEFAULT_WARNING_SUBTITLE}.
     */
    default Component warningSubtitle() {
        return DEFAULT_WARNING_SUBTITLE;
    }

    /**
     * Namespaced sound key played once, at volume 0.5 and pitch 1.0 in the master category, when a player first crosses outside
     * the border. Read once at spawn. Null plays nothing. Defaults to {@code minecraft:block.note_block.bass}, a low note that
     * flags the crossing without drowning out the game.
     */
    default String enterSound() {
        return "minecraft:block.note_block.bass";
    }

    /**
     * Namespaced sound key played while a player remains outside for a sustained interval: first once they have been outside for
     * five seconds, then every five seconds after that, at volume 0.5 and pitch 1.0 in the master category. Stopped for the player,
     * if it has played, when they stop being tracked as outside, as listed on {@link BorderEvents#onWarningCleared(UUID)}.
     * Read once at spawn. Null plays nothing. Defaults to whatever {@link #enterSound()} returns, so overriding only the crossing
     * sound changes both, and a null crossing sound silences both.
     */
    default String enterLongSound() {
        return enterSound();
    }

    /**
     * Colour of the border wall in both wall styles: the dust of the particle wall, and the tint of the shader wall, whose
     * display items carry it as their dyed colour for the pack to tint from. Both follow a change while the border is live: the
     * shader wall checks every 10 ticks and re-dyes its displays when the answer differs, the particle wall reads it on every
     * pass, every 20 ticks. Return a stored value rather than building one per call. A null answer draws
     * {@link #DEFAULT_WALL_COLOR}, which is also the default.
     */
    default Color wallColor() {
        return DEFAULT_WALL_COLOR;
    }

    /**
     * Colour the wall switches to while the border is moving - shrinking, growing or re-centring, from
     * {@link GameBorder#moveTo(double, double, double, int)} or a {@link BorderPhaseController} shrink - and back from once it
     * lands or pauses. The shader wall switches on the first tick of the move and back within 10 ticks of it ending; the
     * particle wall on its next pass, every 20 ticks. Null keeps {@link #wallColor()} throughout, which is the default.
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
