package com.example.borderdemo;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.natesoftware.riftborder.api.BorderEvents;
import com.natesoftware.riftborder.api.BorderPhase;
import com.natesoftware.riftborder.api.BorderPhaseController;
import com.natesoftware.riftborder.api.BorderTheme;
import com.natesoftware.riftborder.api.GameBorder;
import com.natesoftware.riftborder.api.NextBorderIndicator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

// A complete consumer: one border around the first world, three phases, a target preview, and a clean teardown.
// Needs the RiftBorder plugin on the server, which provides the library and picks each player's wall style - compile against it only.
public final class ExamplePlugin extends JavaPlugin {

    // wait, shrink, end radius, damage per second
    private static final List<BorderPhase> PHASES = List.of(
        new BorderPhase(60, 30, 100, 2.0),
        new BorderPhase(30, 30, 50, 3.0),
        new BorderPhase(15, 15, 0, 5.0));

    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();

    private GameBorder border;

    @Override
    public void onEnable() {
        World world = Bukkit.getWorlds().get(0);
        for (Player player : world.getPlayers()) participants.add(player.getUniqueId());

        border = new GameBorder(this, world, 0, 64, 0)
            .withTheme(new Theme())
            .withEvents(new Events())
            .withParticipants(() -> participants);
        border.spawn(200);

        BorderPhaseController controller = new BorderPhaseController(this, border, PHASES)
            .withFixedCenter(true)
            .onAllPhasesComplete(() -> getLogger().info("The border has closed"));
        controller.start();

        new NextBorderIndicator(controller).start();
    }

    @Override
    public void onDisable() {
        // stops the controller and the indicator too, and releases every chunk the wall pinned
        if (border != null) border.remove();
    }

    private static final class Theme implements BorderTheme {
        @Override
        public Component warningTitle() {
            return Component.text("Get back inside", NamedTextColor.RED);
        }
    }

    private static final class Events implements BorderEvents {
        @Override
        public void onPhaseStart(int phase, int total, int waitSeconds) {
            Bukkit.broadcast(Component.text("Border phase " + phase + "/" + total + " - closing in " + waitSeconds + "s"));
        }
    }
}
