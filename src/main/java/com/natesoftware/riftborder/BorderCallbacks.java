package com.natesoftware.riftborder;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;

// Branding and presentation hooks that a host plugin supplies when constructing a GameBorder.
public interface BorderCallbacks {

    // Title shown to a player while they are outside the border - null skips the persistent title entirely.
    default Component warningTitle() {
        return null;
    }

    // Item-model key for the wall display entities. Null means no pack is installed - the border then renders as particles for everyone.
    default NamespacedKey wallItemModel() {
        return null;
    }

    // Called when a new phase begins.
    default void phaseStarted(int phase, int total, int waitSeconds) {}

    // Called when the current phase's wait is over and the border begins shrinking.
    default void shrinkStarted() {}

    // Called the first tick a player is found outside the border.
    default void onWarningShown(UUID player) {}

    // Called when the player steps back inside and the warning title is cleared.
    default void onWarningCleared(UUID player) {}

    // Plays the notification sound for the start of a phase.
    default void playPhaseSound() {}

    // Plays the ambient sound while the border is actively shrinking.
    default void playShrinkSound() {}

    // Sound key played once when a player first crosses outside the border.
    default String enterSoundKey() {
        return "minecraft:block.anvil.land";
    }

    // Sound key played periodically (every ~5s) while a player remains outside for a sustained interval.
    default String enterLongSoundKey() {
        return "minecraft:entity.wither.spawn";
    }

    // Dust color of the particle wall shown to players in PARTICLE render mode.
    default Color particleColor() {
        return Color.WHITE;
    }
}
