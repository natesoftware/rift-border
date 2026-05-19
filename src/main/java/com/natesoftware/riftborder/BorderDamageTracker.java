package com.natesoftware.riftborder;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

// Periodically scans players in the border's world, classifies each as inside
// or outside the current shape, and applies HP damage + audio/visual feedback
// when outside. Maintains per-player state across ticks for the warning title,
// long-stay sound, and grace handling on re-entry / disconnect.
final class BorderDamageTracker {

    private static final int CHECK_INTERVAL_TICKS = 4;
    private static final int DAMAGE_EVERY_N_CHECKS = 5; // 4 * 5 = 20 ticks = 1s
    private static final long LONG_ENTER_INTERVAL_MS = 5_000;

    // Grace period from first crossing outside - the player has this long to
    // step back inside before any damage tick lands.
    private static final long DAMAGE_GRACE_MS = 1_000;

    // Per-player vertical-boundary indicator: a sparse grid of red dust on
    // the ceiling or floor plane around the player when within
    // VERTICAL_INDICATOR_RANGE blocks of it. Visible only to that player.
    private static final double VERTICAL_INDICATOR_RANGE = 10.0;
    private static final Particle.DustOptions VERTICAL_DUST =
            new Particle.DustOptions(Color.fromRGB(0xFF, 0x40, 0x40), 1.0f);

    private static final Title.Times WARNING_TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofDays(1), Duration.ZERO);

    private final GameBorder border;

    private final Set<UUID> outsidePlayers = new HashSet<>();
    private final Map<UUID, Long> outsideSinceMs = new HashMap<>();
    private final Map<UUID, Long> lastLongPlayedMs = new HashMap<>();

    private Title warningTitle;
    private String enterSoundKey;
    private String enterLongSoundKey;
    private BukkitTask task;
    private int checkCounter;

    BorderDamageTracker(GameBorder border) {
        this.border = border;
    }

    void start() {
        checkCounter = 0;
        warningTitle = Title.title(border.callbacks.warningTitle(), Component.empty(), WARNING_TIMES);
        enterSoundKey = border.callbacks.enterSoundKey();
        enterLongSoundKey = border.callbacks.enterLongSoundKey();
        task = border.plugin
                .getServer()
                .getScheduler()
                .runTaskTimer(border.plugin, this::tick, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID uuid : outsidePlayers) {
            border.callbacks.onWarningCleared(uuid);
            Player p = border.plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline() && enterLongSoundKey != null) {
                p.stopSound(enterLongSoundKey, SoundCategory.MASTER);
            }
        }
        outsidePlayers.clear();
        outsideSinceMs.clear();
        lastLongPlayedMs.clear();
    }

    private void tick() {
        checkCounter++;
        boolean shouldDamage = checkCounter >= DAMAGE_EVERY_N_CHECKS;
        if (shouldDamage) checkCounter = 0;

        Supplier<Set<UUID>> supplier = border.participantSupplier;
        Set<UUID> participantUuids = supplier != null ? supplier.get() : null;

        Collection<? extends Player> candidates = border.world.getPlayers();
        if (participantUuids != null) {
            candidates = candidates.stream()
                    .filter(p -> participantUuids.contains(p.getUniqueId()))
                    .toList();
        }
        for (Player player : candidates) {
            handlePlayer(player, shouldDamage);
        }
        purgeStalePlayers(participantUuids);
    }

    private void handlePlayer(Player player, boolean shouldDamage) {
        Location loc = Objects.requireNonNull(player.getLocation());
        boolean outsideRadius = border.isOutside(loc.getX(), loc.getZ());
        boolean aboveCeiling = border.isAboveHeight(loc.getY());
        boolean belowFloor = border.isBelowMinHeight(loc.getY());
        boolean outside = outsideRadius || aboveCeiling || belowFloor;
        boolean was = outsidePlayers.contains(player.getUniqueId());

        // Subtle visual cue: scatter red dust on the ceiling or floor plane
        // when the player is inside the radius and within the indicator
        // range of either boundary. Only that player sees it. Skips entirely
        // if the phase has no cap on the relevant side.
        if (!outsideRadius) {
            if (border.hasHeightLimit()
                    && Math.abs(border.getMaxHeight() - loc.getY()) <= VERTICAL_INDICATOR_RANGE) {
                spawnVerticalIndicator(player, loc, border.getMaxHeight());
            }
            if (border.hasMinHeight()
                    && Math.abs(border.getMinHeight() - loc.getY()) <= VERTICAL_INDICATOR_RANGE) {
                spawnVerticalIndicator(player, loc, border.getMinHeight());
            }
        }

        if (outside) {
            if (!was) {
                outsidePlayers.add(player.getUniqueId());
                outsideSinceMs.put(player.getUniqueId(), System.currentTimeMillis());
                border.callbacks.onWarningShown(player.getUniqueId());
                if (enterSoundKey != null) {
                    player.playSound(loc, enterSoundKey, SoundCategory.MASTER, 0.5f, 1.0f);
                }
                player.showTitle(warningTitle);
            } else {
                long now = System.currentTimeMillis();
                Long enteredAt = outsideSinceMs.get(player.getUniqueId());
                Long lastPlayed = lastLongPlayedMs.get(player.getUniqueId());
                boolean dueForLong = enteredAt != null
                        && now - enteredAt >= LONG_ENTER_INTERVAL_MS
                        && (lastPlayed == null || now - lastPlayed >= LONG_ENTER_INTERVAL_MS);
                if (dueForLong && enterLongSoundKey != null) {
                    player.playSound(loc, enterLongSoundKey, SoundCategory.MASTER, 0.5f, 1.0f);
                    lastLongPlayedMs.put(player.getUniqueId(), now);
                }
                if (shouldDamage) {
                    player.showTitle(warningTitle);
                }
            }
            if (shouldDamage) {
                long now = System.currentTimeMillis();
                Long enteredAt = outsideSinceMs.get(player.getUniqueId());
                if (enteredAt != null && now - enteredAt >= DAMAGE_GRACE_MS) {
                    applyBorderDamage(player);
                }
            }
        } else if (was) {
            outsidePlayers.remove(player.getUniqueId());
            outsideSinceMs.remove(player.getUniqueId());
            if (lastLongPlayedMs.remove(player.getUniqueId()) != null && enterLongSoundKey != null) {
                player.stopSound(enterLongSoundKey, SoundCategory.MASTER);
            }
            border.callbacks.onWarningCleared(player.getUniqueId());
            player.clearTitle();
        }
    }

    // 3x3 grid of red dust particles spaced 3 blocks apart on the given
    // horizontal plane (ceiling Y or floor Y) around the player. Per-viewer
    // so other players in the same world don't see another player's marker.
    private void spawnVerticalIndicator(Player viewer, Location playerLoc, double planeY) {
        double cx = playerLoc.getX();
        double cz = playerLoc.getZ();
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                Location at = new Location(playerLoc.getWorld(), cx + dx, planeY, cz + dz);
                viewer.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, VERTICAL_DUST);
            }
        }
    }

    private void purgeStalePlayers(Set<UUID> participantUuids) {
        outsidePlayers.removeIf(uuid -> {
            Player p = border.plugin.getServer().getPlayer(uuid);
            boolean gone = p == null || !p.isOnline() || !p.getWorld().equals(border.world);
            boolean noLongerParticipant = participantUuids != null && !participantUuids.contains(uuid);
            if (gone || noLongerParticipant) {
                border.callbacks.onWarningCleared(uuid);
                if (p != null && p.isOnline()) {
                    p.clearTitle();
                    if (lastLongPlayedMs.containsKey(uuid) && enterLongSoundKey != null) {
                        p.stopSound(enterLongSoundKey, SoundCategory.MASTER);
                    }
                }
                outsideSinceMs.remove(uuid);
                lastLongPlayedMs.remove(uuid);
                return true;
            }
            return false;
        });
    }

    private void applyBorderDamage(Player player) {
        double remaining = border.getDamagePerSecond();

        // Hit absorption first, mirroring vanilla's damage order.
        double absorption = player.getAbsorptionAmount();
        if (absorption > 0) {
            double absorb = Math.min(absorption, remaining);
            player.setAbsorptionAmount(absorption - absorb);
            remaining -= absorb;
        }

        // Apply leftover to HP. A fatal tick routes through vanilla damage()
        // so the death event fires with proper attribution and the vanilla
        // death animation/sound play - we skip our own feedback in that
        // case so they don't stack.
        if (remaining > 0) {
            double newHealth = player.getHealth() - remaining;
            if (newHealth <= 0) {
                DamageSource source =
                        DamageSource.builder(DamageType.OUTSIDE_BORDER).build();
                player.damage(1000.0, source);
                return;
            }
            player.setHealth(newHealth);
        }

        playHurtFeedback(player);
    }

    // Paper's directional hurt animation + the vanilla hurt sound. No
    // velocity/knockback because we never call damage(), so the server-side
    // hit handler doesn't run. Sound goes only to the player taking damage -
    // border damage is a personal warning, not a battlefield cue.
    private void playHurtFeedback(Player player) {
        player.playHurtAnimation(0f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.MASTER, 1.0f, 1.0f);
    }
}
