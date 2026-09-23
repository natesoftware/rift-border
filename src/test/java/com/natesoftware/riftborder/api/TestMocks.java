package com.natesoftware.riftborder.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

// Mockito stand-ins for the slice of Bukkit the library touches, plus a scheduler tests advance by hand one tick at a time.
// No server runs: anything that needs a live registry (ItemStack, Material, Particle) is out of reach, so never withShaderWall().
final class TestMocks {

    private TestMocks() {}

    // A 320-high overworld with nobody in it.
    static World world() {
        World world = mock(World.class);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getMinHeight()).thenReturn(-64);
        return world;
    }

    // A plugin whose server hands out the given scheduler and reports its tick. 1.21.11's NamespacedKey reads Plugin.namespace(),
    // a default method Mockito returns null for unless stubbed.
    static Plugin plugin(FakeScheduler scheduler) {
        Server server = mock(Server.class);
        when(server.getScheduler()).thenReturn(scheduler.bukkit);
        when(server.getCurrentTick()).thenAnswer(inv -> (int) scheduler.now);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("test");
        when(plugin.namespace()).thenReturn("test");
        when(plugin.getServer()).thenReturn(server);
        return plugin;
    }

    // Records runTaskLater / runTaskTimer and runs what is due as advance() moves the clock. Delay 0 means next tick, as in Bukkit.
    static final class FakeScheduler {

        final BukkitScheduler bukkit = mock(BukkitScheduler.class);
        long now;

        private final List<Scheduled> queue = new ArrayList<>();

        FakeScheduler() {
            when(bukkit.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong()))
                .thenAnswer(inv -> schedule(inv.getArgument(1), inv.getArgument(2), -1));
            when(bukkit.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(inv -> schedule(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        }

        // Moves the clock forward, running every due task in scheduling order. Repeating tasks re-arm, one-shots drop out.
        void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                now++;
                for (Scheduled s : new ArrayList<>(queue)) {
                    if (s.cancelled || s.due > now) continue;
                    s.runnable.run();
                    if (s.period > 0 && !s.cancelled) {
                        s.due += s.period;
                    } else {
                        queue.remove(s);
                    }
                }
            }
        }

        // Live (not cancelled) tasks, repeating or not.
        int pending() {
            return (int) queue.stream().filter(s -> !s.cancelled).count();
        }

        private BukkitTask schedule(Runnable runnable, long delay, long period) {
            Scheduled s = new Scheduled(runnable, now + Math.max(delay, 1), period);
            queue.add(s);
            BukkitTask task = mock(BukkitTask.class);
            doAnswer(inv -> {
                s.cancelled = true;
                queue.remove(s);
                return null;
            }).when(task).cancel();
            when(task.isCancelled()).thenAnswer(inv -> s.cancelled);
            return task;
        }

        private static final class Scheduled {
            final Runnable runnable;
            long due;
            final long period;
            boolean cancelled;

            Scheduled(Runnable runnable, long due, long period) {
                this.runnable = runnable;
                this.due = due;
                this.period = period;
            }
        }
    }
}
