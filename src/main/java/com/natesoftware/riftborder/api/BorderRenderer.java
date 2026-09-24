package com.natesoftware.riftborder.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Renders the shader wall on one invisible ItemDisplay per viewer. Each display is spawned where its viewer stands, shown to
// them alone, and replaced by a fresh one once they wander off, so one is always within tracking range and nothing ever moves.
// Any display draws the whole cylinder, which is why each viewer sees exactly one.
final class BorderRenderer {

    private static final Logger log = LoggerFactory.getLogger(BorderRenderer.class);

    static final int UPDATE_INTERVAL_TICKS = 1;
    private static final int VISIBILITY_INTERVAL_TICKS = 10;
    // Horizontal distance from their display past which a viewer gets a fresh one where they stand. Well inside the entity
    // tracking range, and far enough that the handoff's one-frame blip stays rare.
    static final double REANCHOR_DISTANCE = 32.0;
    // The old display outlives its replacement's arrival by one tick, so the handoff never shows a frame without a wall.
    private static final int HANDOFF_OVERLAP_TICKS = 1;

    // Ids of every border this copy of the library has spawned and not yet removed. The orphan sweep leaves their entities alone,
    // so two live borders in one world never delete each other's walls. Per-classloader, so bundled copies never share it.
    private static final Set<UUID> LIVE = ConcurrentHashMap.newKeySet();

    // The rift-border pack's wall model. There is one supported pack, so the key belongs to the library, not the host.
    static final NamespacedKey WALL_MODEL = new NamespacedKey("rift-border", "border");

    private final GameBorder border;

    // PDC tag for orphan cleanup. The value is the owning border's id.
    private final NamespacedKey borderTag;

    // Each viewer's current display.
    private final Map<UUID, ItemDisplay> anchors = new HashMap<>();

    private ItemStack borderItem;
    // The colour the displays are currently dyed, so a change to the host's wallColor() can be spotted and re-applied.
    private Color bakedColor;
    private BukkitTask visibilityTask;

    BorderRenderer(GameBorder border) {
        this.border = border;
        this.borderTag = new NamespacedKey(border.plugin, "border_entity");
    }

    void spawn() {
        borderItem = new ItemStack(Material.PAPER);
        borderItem.editMeta(m -> m.setItemModel(WALL_MODEL));
        // set after editMeta so no later meta write drops it - the pack tints from this and the shader colours the wall from the tint
        bakedColor = border.wallColor();
        borderItem.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(bakedColor));

        removeOrphanedEntities();
        LIVE.add(border.id);
        visibilityTask = border.plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(border.plugin, this::updateVisibility, VISIBILITY_INTERVAL_TICKS, VISIBILITY_INTERVAL_TICKS);
        // the viewers already in the world get their displays now, not on the first pass
        updateVisibility();

        log.info("[GameBorder] Border active - shader wall on one display per viewer");
    }

    void remove() {
        LIVE.remove(border.id);
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
        for (ItemDisplay entity : anchors.values()) {
            if (!entity.isDead()) entity.remove();
        }
        anchors.clear();
    }

    void updateAllEntities() {
        // runs every tick of a transition, so a shrink colour takes over on the first tick rather than at the next pass
        syncColor();
        float rf = (float) border.getRadius();
        float pf = (float) border.getPatternRadius();
        for (ItemDisplay entity : anchors.values()) {
            updateEntityTransform(entity, rf, pf);
        }
    }

    // True once a viewer at (px, pz) has walked far enough from their display at (ax, az) to need a fresh one.
    static boolean needsNewAnchor(double ax, double az, double px, double pz) {
        double dx = px - ax;
        double dz = pz - az;
        return dx * dx + dz * dz > REANCHOR_DISTANCE * REANCHOR_DISTANCE;
    }

    private void updateVisibility() {
        syncColor();

        // viewers who left the world or the server take their display with them
        anchors.entrySet().removeIf(entry -> {
            Player p = border.plugin.getServer().getPlayer(entry.getKey());
            boolean gone = p == null || !p.isOnline() || !p.getWorld().equals(border.world);
            if (gone && !entry.getValue().isDead()) entry.getValue().remove();
            return gone;
        });

        for (Player player : border.world.getPlayers()) {
            UUID id = player.getUniqueId();
            ItemDisplay current = anchors.get(id);
            // particle-style viewers get the particle wall instead, and no display at all
            if (border.styleFor(id) == WallStyle.PARTICLE) {
                if (current != null) {
                    anchors.remove(id);
                    if (!current.isDead()) current.remove();
                }
                continue;
            }
            Location at = player.getLocation();
            if (current != null && !current.isDead()) {
                Location anchor = current.getLocation();
                if (!needsNewAnchor(anchor.getX(), anchor.getZ(), at.getX(), at.getZ())) continue;
            }
            anchors.put(id, spawnAnchorFor(player, at.getX(), at.getZ()));
            if (current != null) retire(current);
        }
    }

    // Shown to its viewer only - every other player has a display of their own.
    private ItemDisplay spawnAnchorFor(Player viewer, double x, double z) {
        Location loc = new Location(border.world, x, border.anchorY, z);
        float tx = (float) (border.getCenterX() - x);
        float ty = (float) (border.centerY - border.anchorY);
        float tz = (float) (border.getCenterZ() - z);
        float rf = (float) border.getRadius();
        float pf = (float) border.getPatternRadius();

        ItemDisplay entity = border.world.spawn(loc, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.getPersistentDataContainer().set(borderTag, PersistentDataType.STRING, border.id.toString());
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
        viewer.showEntity(border.plugin, entity);

        // arrives at its current shape instantly, then eases between the per-tick updates like the rest of the wall
        border.plugin
            .getServer()
            .getScheduler()
            .runTaskLater(
                border.plugin,
                () -> {
                    if (!entity.isDead()) entity.setInterpolationDuration(UPDATE_INTERVAL_TICKS + 1);
                },
                1L);

        return entity;
    }

    private void retire(ItemDisplay old) {
        if (old.isDead()) return;
        border.plugin
            .getServer()
            .getScheduler()
            .runTaskLater(
                border.plugin,
                () -> {
                    if (!old.isDead()) old.remove();
                },
                HANDOFF_OVERLAP_TICKS);
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

    // Sweeps wall entities left behind by a border that was never removed - a plugin disabled mid-round, a /reload - and nothing else.
    private void removeOrphanedEntities() {
        int removed = 0;
        for (ItemDisplay entity : border.world.getEntitiesByClass(ItemDisplay.class)) {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            if (!pdc.has(borderTag, PersistentDataType.STRING)) continue;
            if (isLive(pdc.get(borderTag, PersistentDataType.STRING))) continue;
            // releases the force-load flag a pre-4.1.0 grid anchor may still hold on its chunk
            Location at = entity.getLocation();
            border.world.setChunkForceLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4, false);
            entity.remove();
            removed++;
        }
        if (removed > 0) {
            log.info("[GameBorder] Removed {} orphaned border entities", removed);
        }
    }

    // A tag naming a border that is still spawned marks a live wall, not an orphan. Anything malformed counts as an orphan.
    private static boolean isLive(String tag) {
        if (tag == null) return false;
        try {
            return LIVE.contains(UUID.fromString(tag));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Re-dyes the wall when the colour it should show changes - the host's wallColor(), or its shrinkColor() while moving.
    // Displays spawned later clone borderItem, so updating it covers them too. A no-op until spawn builds the item.
    void syncColor() {
        if (borderItem == null) return;
        Color now = border.wallColor();
        if (now.equals(bakedColor)) return;
        bakedColor = now;
        borderItem.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(now));
        for (ItemDisplay entity : anchors.values()) {
            if (!entity.isDead()) entity.setItemStack(borderItem.clone());
        }
    }
}
