package com.rocinante.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rocinante.state.AttackStyle;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and provides projectile data from JSON resource file.
 *
 * Used to determine the attack style of incoming projectiles for
 * proper prayer switching. Maps projectile ID to attack style.
 *
 * Data is loaded from resources/data/projectile_data.json at startup.
 */
@Slf4j
@Singleton
public class ProjectileDataLoader {

    private static final String DATA_FILE = "/data/projectile_data.json";

    private final Map<Integer, ProjectileData> projectileData = new ConcurrentHashMap<>();
    private final Gson gson;
    private boolean loaded = false;

    @Inject
    public ProjectileDataLoader(Gson gson) {
        this.gson = gson;
        loadData();
    }

    /**
     * Load projectile data from JSON resource.
     * Uses JsonResourceLoader for retry logic and error handling.
     */
    private void loadData() {
        try {
            JsonObject root = JsonResourceLoader.load(gson, DATA_FILE);
            JsonObject projectiles = JsonResourceLoader.getRequiredObject(root, "projectiles");
            
            for (Map.Entry<String, JsonElement> entry : projectiles.entrySet()) {
                try {
                    int projectileId = Integer.parseInt(entry.getKey());
                    ProjectileData data = parseProjectileData(entry.getValue().getAsJsonObject());
                    if (data != null) {
                        projectileData.put(projectileId, data);
                    }
                } catch (NumberFormatException e) {
                    log.debug("Skipping non-numeric projectile ID: {}", entry.getKey());
                }
            }
            
            loaded = true;
            log.info("Loaded data for {} projectiles", projectileData.size());
        } catch (JsonResourceLoader.JsonLoadException e) {
            log.error("Failed to load projectile data: {}", e.getMessage());
        }
    }

    /**
     * Parse a single projectile's data from JSON.
     */
    @Nullable
    private ProjectileData parseProjectileData(JsonObject obj) {
        try {
            String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";

            AttackStyle attackStyle = AttackStyle.UNKNOWN;
            if (obj.has("attackStyle")) {
                String styleStr = obj.get("attackStyle").getAsString();
                attackStyle = parseAttackStyle(styleStr);
            }

            boolean isSpecial = obj.has("isSpecial") && obj.get("isSpecial").getAsBoolean();

            return new ProjectileData(name, attackStyle, isSpecial);
        } catch (Exception e) {
            log.debug("Failed to parse projectile data: {}", e.getMessage());
            return null;
        }
    }

    private AttackStyle parseAttackStyle(String styleStr) {
        return switch (styleStr.toUpperCase()) {
            case "MELEE" -> AttackStyle.MELEE;
            case "RANGED" -> AttackStyle.RANGED;
            case "MAGIC" -> AttackStyle.MAGIC;
            default -> AttackStyle.UNKNOWN;
        };
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Get projectile data by ID.
     *
     * @param projectileId the projectile ID
     * @return ProjectileData, or null if not found
     */
    @Nullable
    public ProjectileData getProjectileData(int projectileId) {
        return projectileData.get(projectileId);
    }

    /**
     * Get the attack style for a projectile.
     *
     * @param projectileId the projectile ID
     * @return attack style, or UNKNOWN if not found
     */
    public AttackStyle getAttackStyle(int projectileId) {
        ProjectileData data = projectileData.get(projectileId);
        return data != null ? data.attackStyle() : AttackStyle.UNKNOWN;
    }

    /**
     * Check if a projectile is a special attack (not blockable with normal prayer).
     *
     * @param projectileId the projectile ID
     * @return true if this is a special projectile
     */
    public boolean isSpecialProjectile(int projectileId) {
        ProjectileData data = projectileData.get(projectileId);
        return data != null && data.isSpecial();
    }

    /**
     * Check if a projectile ID is known.
     *
     * @param projectileId the projectile ID
     * @return true if we have data for this projectile
     */
    public boolean isKnownProjectile(int projectileId) {
        return projectileData.containsKey(projectileId);
    }

    /**
     * Check if data has been loaded.
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Get the count of loaded projectiles.
     */
    public int getLoadedCount() {
        return projectileData.size();
    }

    // ========================================================================
    // Data Records
    // ========================================================================

    public record ProjectileData(String name, AttackStyle attackStyle, boolean isSpecial) {}
}
