package com.natesoftware.riftborder;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Renders the zone border as a grid of invisible ItemDisplay entities.
final class BorderRenderer {

    private static final Logger log = LoggerFactory.getLogger(BorderRenderer.class);

    static final int UPDATE_INTERVAL_TICKS = 1;
    private static final int MAINTENANCE_INTERVAL_TICKS = 60;
    private static final int VISIBILITY_INTERVAL_TICKS = 10;
    // Old anchor stays visible this long after a switch so the new one is fully rendered client-side first.
    // Just one tick: long enough to cover client spawn processing, short enough that the double-blend darkening is a single frame blip.
    private static final int HANDOFF_OVERLAP_TICKS = 1;

    // Grid spacing in blocks (5 chunks).
    private static final int GRID_SPACING = 80;
    // Hard ceiling on the grid half-extent (1000x1000 play area).
    private static final int MAX_GRID_EXTENT = 500;
    // Y level to spawn grid entities - above terrain to avoid block occlusion.
    private static final int GRID_SPAWN_Y = 319;

    private final GameBorder border;

    // PDC tag for orphan cleanup.
    private final NamespacedKey borderTag;

    // Grid position (packed long) -> entity.
    private final Map<Long, ItemDisplay> gridEntities = new LinkedHashMap<>();
    private final Set<Long> forceLoadedChunks = new HashSet<>();

    // Which anchor each player currently sees.
    private final Map<UUID, ItemDisplay> shownByPlayer = new HashMap<>();

    private ItemStack borderItem;
    private BukkitTask maintenanceTask;
    private BukkitTask visibilityTask;
    // Flipped by remove() so async chunk-load callbacks from spawnGridEntities that resolve after teardown don't spawn orphan entities or leak...
    private volatile boolean disposed;

    BorderRenderer(GameBorder border) {
        this.border = border;
        this.borderTag = new NamespacedKey(border.plugin, "border_entity");
    }

    void spawn() {
        borderItem = new ItemStack(Material.PAPER);
        borderItem.editMeta(m -> m.setItemModel(border.callbacks.wallItemModel()));

        removeOrphanedEntities();
        spawnGridEntities();
        startMaintenanceTask();
        startVisibilityTask();

        log.info("[GameBorder] Border active with {} grid entities", gridEntities.size());
    }

    void remove() {
        disposed = true;
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
        removeAllEntities();
        releaseForceLoadedChunks();
        shownByPlayer.clear();
    }

    void updateAllEntities() {
        float rf = (float) border.getRadius();
        float pf = (float) border.getPatternRadius();
        for (ItemDisplay entity : gridEntities.values()) {
            updateEntityTransform(entity, rf, pf);
        }
    }

    // Sized to the initial radius - every later phase stays inside the initial zone, so small arenas skip the full-map grid.
    private int gridExtent() {
        return (int) Math.min(MAX_GRID_EXTENT, Math.ceil(border.getRadius()) + GRID_SPACING);
    }

    // Each grid point requires its chunk loaded so we can spawn an ItemDisplay in it.
    private void spawnGridEntities() {
        removeAllEntities();

        int extent = gridExtent();
        int startX = alignToGrid((int) Math.floor(border.initialCenterX) - extent);
        int startZ = alignToGrid((int) Math.floor(border.initialCenterZ) - extent);
        int endX = (int) Math.floor(border.initialCenterX) + extent;
        int endZ = (int) Math.floor(border.initialCenterZ) + extent;

        for (int gx = startX; gx <= endX; gx += GRID_SPACING) {
            for (int gz = startZ; gz <= endZ; gz += GRID_SPACING) {
                final int fx = gx;
                final int fz = gz;
                int cx = gx >> 4;
                int cz = gz >> 4;
                border.world.getChunkAtAsync(cx, cz, true).thenAccept(chunk -> {
                    if (disposed) return;
                    long chunkKey = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                    if (forceLoadedChunks.add(chunkKey)) {
                        border.world.setChunkForceLoaded(cx, cz, true);
                    }
                    long gridKey = packGrid(fx, fz);
                    gridEntities.put(gridKey, spawnEntityAt(fx, GRID_SPAWN_Y, fz));
                });
            }
        }
    }

    private ItemDisplay spawnEntityAt(double x, double y, double z) {
        Location loc = new Location(border.world, x, y, z);
        float tx = (float) (border.getCenterX() - x);
        float ty = (float) (border.centerY - y);
        float tz = (float) (border.getCenterZ() - z);
        float rf = (float) border.getRadius();
        float pf = (float) border.getPatternRadius();

        ItemDisplay entity = border.world.spawn(loc, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.getPersistentDataContainer().set(borderTag, PersistentDataType.BYTE, (byte) 1);
            e.setBillboard(Display.Billboard.FIXED);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setViewRange(128f);
            e.setItemStack(borderItem.clone());
            // X scale carries the live radius, Z the pattern-anchor radius - the shader reads both from the quad edges
            e.setTransformation(new Transformation(
                new Vector3f(tx, ty, tz), new Quaternionf(), new Vector3f(rf, rf, pf), new Quaternionf()));
            e.setInterpolationDuration(0);
            e.setInterpolationDelay(-1);
        });

        border.plugin
            .getServer()
            .getScheduler()
            .runTaskLater(
                border.plugin,
                () -> {
                    if (!entity.isDead()) {
                        entity.setInterpolationDuration(UPDATE_INTERVAL_TICKS + 1);
                    }
                },
                1L);

        return entity;
    }

    private void updateEntityTransform(ItemDisplay entity, float rf, float pf) {
        if (entity == null || entity.isDead()) return;
        Location eLoc = entity.getLocation();
        float tx = (float) (border.getCenterX() - eLoc.getX());
        float ty = (float) (border.centerY - eLoc.getY());
        float tz = (float) (border.getCenterZ() - eLoc.getZ());
        entity.setInterpolationDelay(0);
        entity.setTransformation(new Transformation(
            new Vector3f(tx, ty, tz), new Quaternionf(), new Vector3f(rf, rf, pf), new Quaternionf()));
    }

    private void removeAllEntities() {
        for (ItemDisplay entity : gridEntities.values()) {
            if (!entity.isDead()) entity.remove();
        }
        gridEntities.clear();
    }

    private void removeOrphanedEntities() {
        int removed = 0;
        for (ItemDisplay entity : border.world.getEntitiesByClass(ItemDisplay.class)) {
            if (entity.getPersistentDataContainer().has(borderTag)) {
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[GameBorder] Removed {} orphaned border entities", removed);
        }
    }

    private void startVisibilityTask() {
        visibilityTask = border.plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                border.plugin, this::updateVisibility, VISIBILITY_INTERVAL_TICKS, VISIBILITY_INTERVAL_TICKS);
    }

    // Distance check is XZ-only - the shader renders the full cylinder regardless of the entity's vertical position.
    private void updateVisibility() {
        Collection<ItemDisplay> all = gridEntities.values();
        if (all.isEmpty()) return;

        shownByPlayer.keySet().removeIf(uuid -> {
            Player p = border.plugin.getServer().getPlayer(uuid);
            return p == null || !p.isOnline() || !p.getWorld().equals(border.world);
        });

        for (Player player : border.world.getPlayers()) {
            // Particle-mode players get the particle wall instead - hide any anchor they were shown.
            if (border.renderModeFor(player.getUniqueId()) == BorderRenderMode.PARTICLE) {
                ItemDisplay shown = shownByPlayer.remove(player.getUniqueId());
                if (shown != null && !shown.isDead()) player.hideEntity(border.plugin, shown);
                continue;
            }
            Location pLoc = java.util.Objects.requireNonNull(player.getLocation());
            double px = pLoc.getX();
            double pz = pLoc.getZ();

            double bestDist = Double.MAX_VALUE;
            ItemDisplay bestEntity = null;

            for (ItemDisplay entity : all) {
                if (entity.isDead()) continue;
                Location eLoc = entity.getLocation();
                double dx = px - eLoc.getX();
                double dz = pz - eLoc.getZ();
                double dist = dx * dx + dz * dz;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestEntity = entity;
                }
            }
            if (bestEntity == null) continue;

            ItemDisplay current = shownByPlayer.get(player.getUniqueId());
            if (current != null && current.isDead()) current = null;

            if (current == bestEntity) continue;
            if (current == null) {
                player.showEntity(border.plugin, bestEntity);
            } else {
                handoff(player, current, bestEntity);
            }
            shownByPlayer.put(player.getUniqueId(), bestEntity);
        }
    }

    // Show the new anchor first, keep the old one rendering underneath, then drop it once the new one is on screen.
    private void handoff(Player player, ItemDisplay oldEntity, ItemDisplay newEntity) {
        player.showEntity(border.plugin, newEntity);
        border.plugin
            .getServer()
            .getScheduler()
            .runTaskLater(
                border.plugin,
                () -> {
                    if (disposed) return;
                    if (player.isOnline() && !oldEntity.isDead()) {
                        player.hideEntity(border.plugin, oldEntity);
                    }
                },
                HANDOFF_OVERLAP_TICKS);
    }

    private void startMaintenanceTask() {
        maintenanceTask = border.plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                border.plugin,
                () -> {
                    boolean respawned = false;
                    for (var it = gridEntities.entrySet().iterator(); it.hasNext(); ) {
                        var entry = it.next();
                        ItemDisplay entity = entry.getValue();
                        if (entity.isDead()) {
                            int gx = unpackGridX(entry.getKey());
                            int gz = unpackGridZ(entry.getKey());
                            forceLoadChunkAt(gx, gz);
                            entry.setValue(spawnEntityAt(gx, GRID_SPAWN_Y, gz));
                            respawned = true;
                        }
                    }
                    if (respawned) updateVisibility();
                },
                MAINTENANCE_INTERVAL_TICKS,
                MAINTENANCE_INTERVAL_TICKS);
    }

    private void forceLoadChunkAt(double x, double z) {
        int cx = (int) Math.floor(x) >> 4;
        int cz = (int) Math.floor(z) >> 4;
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        if (forceLoadedChunks.add(key)) {
            border.world.setChunkForceLoaded(cx, cz, true);
        }
    }

    private void releaseForceLoadedChunks() {
        for (long key : forceLoadedChunks) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            border.world.setChunkForceLoaded(cx, cz, false);
        }
        forceLoadedChunks.clear();
    }

    private static int alignToGrid(int value) {
        return value - Math.floorMod(value, GRID_SPACING);
    }

    private static long packGrid(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackGridX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackGridZ(long key) {
        return (int) key;
    }
}
