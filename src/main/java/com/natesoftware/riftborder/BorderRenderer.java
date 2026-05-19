package com.natesoftware.riftborder;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

// Renders the zone border as a grid of invisible ItemDisplay entities. A
// single closest entity is shown to each player; the others stay hidden. The
// custom item model expands into a cylinder shader at render time. Owns
// entity lifecycle, visibility, maintenance respawn, and chunk force-loading.
final class BorderRenderer {

    private static final Logger log = LoggerFactory.getLogger(BorderRenderer.class);

    static final int UPDATE_INTERVAL_TICKS = 2;
    private static final int MAINTENANCE_INTERVAL_TICKS = 60;
    private static final int VISIBILITY_INTERVAL_TICKS = 10;

    // Grid spacing in blocks (5 chunks).
    private static final int GRID_SPACING = 80;
    // Half-extent of the grid area (1000x1000 play area).
    private static final int GRID_EXTENT = 500;
    // Y level to spawn grid entities - above terrain to avoid block occlusion.
    private static final int GRID_SPAWN_Y = 319;

    private final GameBorder border;

    // PDC tag for orphan cleanup. Self-namespaced under the host plugin so
    // each consumer plugin owns its own tag and we never collide.
    private final NamespacedKey borderTag;

    // Grid position (packed long) -> entity.
    private final Map<Long, ItemDisplay> gridEntities = new LinkedHashMap<>();
    private final Set<Long> forceLoadedChunks = new HashSet<>();

    private ItemStack borderItem;
    private BukkitTask maintenanceTask;
    private BukkitTask visibilityTask;
    // Flipped by remove() so async chunk-load callbacks from spawnGridEntities
    // that resolve after teardown don't spawn orphan entities or leak chunks
    // into forceLoadedChunks past the release pass.
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
    }

    void updateAllEntities() {
        float rf = (float) border.getRadius();
        for (ItemDisplay entity : gridEntities.values()) {
            updateEntityTransform(entity, rf);
        }
    }

    // Each grid point requires its chunk loaded so we can spawn an ItemDisplay
    // in it. Doing this synchronously with setChunkForceLoaded freezes the
    // main thread when many chunks need fresh generation (e.g. after MCA
    // Selector trimmed surrounding regions out of the saved template). Instead
    // we kick off async chunk loads in parallel; each per-chunk callback runs
    // on the main thread when the chunk reaches FULL status and then spawns
    // the entity + marks the chunk force-loaded. Entities pop in over ~1-2s
    // as chunks come ready; the closest-wins picker handles that gracefully.
    private void spawnGridEntities() {
        removeAllEntities();

        int startX = alignToGrid((int) Math.floor(border.initialCenterX) - GRID_EXTENT);
        int startZ = alignToGrid((int) Math.floor(border.initialCenterZ) - GRID_EXTENT);
        int endX = (int) Math.floor(border.initialCenterX) + GRID_EXTENT;
        int endZ = (int) Math.floor(border.initialCenterZ) + GRID_EXTENT;

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

        ItemDisplay entity = border.world.spawn(loc, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.getPersistentDataContainer().set(borderTag, PersistentDataType.BYTE, (byte) 1);
            e.setBillboard(Display.Billboard.FIXED);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setViewRange(128f);
            e.setItemStack(borderItem.clone());
            e.setTransformation(new Transformation(
                new Vector3f(tx, ty, tz), new Quaternionf(), new Vector3f(rf, rf, rf), new Quaternionf()));
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

    private void updateEntityTransform(ItemDisplay entity, float rf) {
        if (entity == null || entity.isDead()) return;
        Location eLoc = entity.getLocation();
        float tx = (float) (border.getCenterX() - eLoc.getX());
        float ty = (float) (border.centerY - eLoc.getY());
        float tz = (float) (border.getCenterZ() - eLoc.getZ());
        entity.setInterpolationDelay(0);
        entity.setTransformation(new Transformation(
            new Vector3f(tx, ty, tz), new Quaternionf(), new Vector3f(rf, rf, rf), new Quaternionf()));
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

    // Distance check is XZ-only - the shader renders the full cylinder
    // regardless of the entity's vertical position.
    private void updateVisibility() {
        Collection<ItemDisplay> all = gridEntities.values();
        if (all.isEmpty()) return;

        for (Player player : border.world.getPlayers()) {
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

            for (ItemDisplay entity : all) {
                if (entity.isDead()) continue;
                if (entity == bestEntity) {
                    player.showEntity(border.plugin, entity);
                } else {
                    player.hideEntity(border.plugin, entity);
                }
            }
        }
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
