package com.rocinante.slayer;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;

/**
 * Data class representing a slayer creature location.
 *
 * Per REQUIREMENTS.md Section 11.2, contains:
 * <ul>
 *   <li>Location name and center point</li>
 *   <li>Search radius for targets</li>
 *   <li>Nearest bank and teleport</li>
 *   <li>Combat zone type (multi vs single)</li>
 *   <li>HCIM safety rating</li>
 *   <li>Required items for access (rope, etc.)</li>
 *   <li>Konar region identifier</li>
 *   <li>Optional cannon spot</li>
 * </ul>
 *
 * Loaded from slayer_locations.json data file.
 */
@Value
@Builder
public class SlayerLocation {

    /**
     * Human-readable name of the location.
     */
    String name;

    /**
     * Center point of the slayer area.
     */
    WorldPoint center;

    /**
     * Radius in tiles from center to search for targets.
     * Default: 15 tiles.
     */
    @Builder.Default
    int radius = 15;

    /**
     * Web node ID for the nearest bank.
     * Used by navigation system.
     */
    @Nullable
    String nearestBank;

    /**
     * Web node ID for nearest teleport destination.
     * Used by navigation system for optimal routing.
     */
    @Nullable
    String nearestTeleport;

    /**
     * Whether this is a multi-combat area.
     * Important for HCIM safety and pile-up avoidance.
     */
    boolean multiCombat;

    /**
     * HCIM safety rating from 1 (very dangerous) to 10 (very safe).
     * <ul>
     *   <li>1-3: Dangerous (wilderness, multi with aggro, boss areas)</li>
     *   <li>4-5: Risky (multi combat, high damage mobs)</li>
     *   <li>6-7: Moderate (single combat with some risk)</li>
     *   <li>8-10: Safe (single combat, easy escape, low damage)</li>
     * </ul>
     */
    @Builder.Default
    int hcimSafetyRating = 5;

    /**
     * Items required to access this location.
     * Examples: rope for Kalphite Lair, light source for caves.
     */
    @Builder.Default
    Set<Integer> requiredItems = Collections.emptySet();

    /**
     * Items recommended but not required.
     * Examples: antipoison, food, prayer potions.
     */
    @Builder.Default
    Set<Integer> recommendedItems = Collections.emptySet();

    /**
     * Optimal spot for placing a cannon, if applicable.
     * Null if cannon is not recommended or not possible.
     */
    @Nullable
    WorldPoint cannonSpot;

    /**
     * Konar region identifier for location-restricted tasks.
     * Must match the taskLocation from SlayerPluginService.
     * Null for non-Konar locations.
     */
    @Nullable
    String konarRegion;

    /**
     * Whether this location requires completion of a quest.
     * The quest name is stored for display purposes.
     */
    @Nullable
    String requiredQuest;

    /**
     * Slayer level required for this specific location.
     * May differ from creature requirement (e.g., Catacombs vs Slayer Tower).
     */
    @Builder.Default
    int slayerLevelRequired = 1;

    /**
     * Whether this location is in the wilderness.
     */
    boolean wilderness;

    /**
     * Average monster density (NPCs per area).
     * Higher = faster tasks but more competition.
     */
    @Builder.Default
    int monsterDensity = 5;

    /**
     * Brief description or notes about the location.
     */
    @Nullable
    String notes;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if this location is safe for HCIM at a given threshold.
     *
     * @param minimumRating minimum acceptable safety rating (1-10)
     * @return true if location meets safety requirement
     */
    public boolean isSafeForHcim(int minimumRating) {
        return hcimSafetyRating >= minimumRating;
    }

    /**
     * Check if this location requires items to access.
     *
     * @return true if required items are specified
     */
    public boolean hasRequiredItems() {
        return !requiredItems.isEmpty();
    }

    /**
     * Check if this location supports cannon placement.
     *
     * @return true if cannon spot is defined
     */
    public boolean supportsCanon() {
        return cannonSpot != null;
    }

    /**
     * Check if this location is valid for Konar tasks.
     *
     * @param konarTaskRegion the region from the task assignment
     * @return true if this location matches the required region
     */
    public boolean matchesKonarRegion(String konarTaskRegion) {
        if (konarRegion == null || konarTaskRegion == null) {
            return konarRegion == null; // Non-Konar location matches any non-Konar task
        }
        return konarRegion.equalsIgnoreCase(konarTaskRegion);
    }

    /**
     * Check if this location requires a specific quest.
     *
     * @return true if quest is required
     */
    public boolean requiresQuest() {
        return requiredQuest != null && !requiredQuest.isEmpty();
    }

    /**
     * Get estimated kills per hour based on monster density.
     * This is a rough estimate for task duration calculation.
     *
     * @return estimated kills per hour
     */
    public int getEstimatedKillsPerHour() {
        // Base rate modified by density and multi-combat
        int baseRate = 60; // Kills per hour base
        int densityBonus = monsterDensity * 5;
        int multiBonus = multiCombat ? 30 : 0;
        return baseRate + densityBonus + multiBonus;
    }

    /**
     * Get a WorldPoint area defined by center and radius.
     *
     * @return bounding box corners [sw, ne] or array with center if radius is 0
     */
    public WorldPoint[] getBoundingBox() {
        if (radius <= 0) {
            return new WorldPoint[]{center};
        }
        WorldPoint sw = new WorldPoint(
                center.getX() - radius,
                center.getY() - radius,
                center.getPlane()
        );
        WorldPoint ne = new WorldPoint(
                center.getX() + radius,
                center.getY() + radius,
                center.getPlane()
        );
        return new WorldPoint[]{sw, ne};
    }

    /**
     * Check if a point is within this location's area.
     *
     * @param point the point to check
     * @return true if within radius of center
     */
    public boolean contains(WorldPoint point) {
        if (point == null || center == null) {
            return false;
        }
        if (point.getPlane() != center.getPlane()) {
            return false;
        }
        return center.distanceTo(point) <= radius;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        if (konarRegion != null) {
            sb.append(" (").append(konarRegion).append(")");
        }
        if (multiCombat) {
            sb.append(" [MULTI]");
        }
        sb.append(" safety=").append(hcimSafetyRating);
        return sb.toString();
    }
}

