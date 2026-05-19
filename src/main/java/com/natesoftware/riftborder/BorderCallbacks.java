package com.natesoftware.riftborder;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;

// Host-plugin integration surface for the border. Every visual, text, and
// sound hook the border needs flows through here, which is the only knob the
// host has to brand or rewire it. Lets the rest of this library stay free of
// any project-specific assumptions about messages, sounds, or item models.
public interface BorderCallbacks {

    // Title shown to players outside the border. Stays up with effectively
    // infinite duration until the player steps back inside.
    Component warningTitle();

    // Item-model key applied to the wall ItemDisplay entities. The host's
    // resource pack must register a model that expands into the border
    // cylinder shader for this key.
    NamespacedKey wallItemModel();

    // A new phase has begun and the border will start shrinking in
    // waitSeconds. phase and total are 1-indexed.
    void phaseStarted(int phase, int total, int waitSeconds);

    // The border is now actively shrinking in the current phase.
    void shrinkStarted();

    // Fired when the warning title is first shown / cleared for a player.
    // Hosts with a title-broadcast system use this to suppress competing
    // titles so the warning is not overwritten while the player is in the
    // danger zone.
    default void onWarningShown(UUID player) {}

    default void onWarningCleared(UUID player) {}

    // Notification sound at each phase start.
    default void playPhaseSound() {}

    // Ambient sound while the border is shrinking.
    default void playShrinkSound() {}

    // Sound key played to a player the first tick they cross outside.
    // Default is the vanilla anvil land. Override to point at a custom
    // resource-pack key (e.g. "myplugin:border.enter").
    default String enterSoundKey() {
        return "minecraft:block.anvil.land";
    }

    // Sound key played periodically while a player has been outside for a
    // sustained interval. Default is the vanilla wither spawn for urgency.
    default String enterLongSoundKey() {
        return "minecraft:entity.wither.spawn";
    }
}
