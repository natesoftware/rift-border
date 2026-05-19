package com.natesoftware.riftborder;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;

/**
 * Branding and presentation hooks that a host plugin supplies when constructing
 * a {@link GameBorder}. The library invokes these to obtain the warning title,
 * the wall item-model key, phase/shrink sound cues, and similar customisation
 * points.
 *
 * <p>Every method except {@link #warningTitle()} and {@link #wallItemModel()}
 * has a sensible default, so most implementations only need to override what
 * they want to customise.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class MyCallbacks implements BorderCallbacks {
 *     public Component warningTitle() {
 *         return Component.text("Get back inside!", NamedTextColor.RED);
 *     }
 *     public NamespacedKey wallItemModel() {
 *         return new NamespacedKey("myplugin", "border_wall");
 *     }
 * }
 * }</pre>
 */
public interface BorderCallbacks {

    /**
     * Title shown to a player while they are outside the border. Stays on
     * screen until the player steps back inside.
     */
    Component warningTitle();

    /**
     * Resource-pack item-model key applied to the wall display entities. The
     * host's resource pack must register a model under this key that expands
     * into the border cylinder shader.
     */
    NamespacedKey wallItemModel();

    /**
     * Called when a new phase begins. The border will sit at its current size
     * for {@code waitSeconds} before it starts shrinking.
     *
     * @param phase 1-indexed phase number
     * @param total total number of phases
     * @param waitSeconds seconds remaining before this phase begins shrinking
     */
    void phaseStarted(int phase, int total, int waitSeconds);

    /** Called when the current phase's wait is over and the border begins shrinking. */
    void shrinkStarted();

    /**
     * Called the first tick a player is found outside the border. Useful for
     * suppressing other titles so the warning is not overwritten.
     */
    default void onWarningShown(UUID player) {}

    /** Called when the player steps back inside and the warning title is cleared. */
    default void onWarningCleared(UUID player) {}

    /** Plays the notification sound for the start of a phase. */
    default void playPhaseSound() {}

    /** Plays the ambient sound while the border is actively shrinking. */
    default void playShrinkSound() {}

    /**
     * Sound key played once when a player first crosses outside the border.
     * Defaults to {@code minecraft:block.anvil.land}; override to point at a
     * custom resource-pack key.
     */
    default String enterSoundKey() {
        return "minecraft:block.anvil.land";
    }

    /**
     * Sound key played periodically (every ~5s) while a player remains outside
     * for a sustained interval. Defaults to {@code minecraft:entity.wither.spawn}.
     */
    default String enterLongSoundKey() {
        return "minecraft:entity.wither.spawn";
    }
}
