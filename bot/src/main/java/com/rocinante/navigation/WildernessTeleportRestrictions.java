package com.rocinante.navigation;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

import java.util.Set;

/**
 * Handles wilderness teleport level restrictions.
 * 
 * <p>Per OSRS rules:
 * <ul>
 *   <li><b>Grouping teleports: DO NOT WORK AT ALL in wilderness (any level)</b></li>
 *   <li>Standard teleports (spells, tablets, most jewelry, home teleport): blocked above level 20</li>
 *   <li>Special items work up to level 30: Amulet of glory, Combat bracelet, Skills necklace,
 *       Ring of wealth, Pharaoh's sceptre, Grand/Royal seed pod, Slayer ring, Ring of life,
 *       Escape crystal, Defence/Max cape (ring of life effect)</li>
 *   <li>No teleports work above level 30 wilderness</li>
 * </ul>
 *
 * <p>This class provides reusable methods for pathfinding to determine if a teleport
 * is available at a given location, ensuring the navigation graph correctly excludes
 * teleport edges when in the wilderness.
 */
public class WildernessTeleportRestrictions {

    /**
     * Standard teleport cutoff - most teleports blocked above this level.
     */
    public static final int STANDARD_TELEPORT_LIMIT = 20;

    /**
     * Enhanced teleport cutoff - special items work up to this level.
     */
    public static final int ENHANCED_TELEPORT_LIMIT = 30;

    /**
     * Wilderness Y coordinate threshold (overworld only, plane 0).
     */
    public static final int WILDERNESS_Y_START = 3520;

    /**
     * Maximum wilderness level (north edge of wilderness).
     */
    public static final int MAX_WILDERNESS_LEVEL = 56;

    /**
     * Item IDs that can teleport up to level 30 wilderness.
     * From RuneLite ItemID constants.
     */
    public static final Set<Integer> LEVEL_30_TELEPORT_ITEMS = Set.of(
            // Amulet of glory (all charge variants)
            1704, 1706, 1708, 1710, 1712,  // Regular glory
            11964, 11966,                   // Glory (t) trimmed
            10354, 10356, 10358, 10360, 10362, // Mounted glory charges
            
            // Amulet of eternal glory
            19707,
            
            // Combat bracelet (all charge variants)
            11118, 11120, 11122, 11124, 11126,
            11972, 11974,  // Combat bracelet (t)
            
            // Skills necklace (all charge variants)
            11105, 11107, 11109, 11111, 11113,
            11968, 11970,  // Skills necklace (t)
            
            // Ring of wealth (all charge variants)
            2572,
            11988, 11990, 11992, 11994,
            
            // Pharaoh's sceptre (all charge variants)
            9044, 9046, 9048, 9050,
            
            // Grand seed pod
            9469,
            
            // Royal seed pod
            19564,
            
            // Slayer ring (all charge variants)
            11866, 11867, 11868, 11869, 11870, 11871, 11872, 11873,
            
            // Slayer ring (eternal)
            21268,
            
            // Ring of life
            2570,
            
            // Escape crystal
            28523,
            
            // Defence cape / Max cape (acts as ring of life below 10% HP)
            9753, 9754,  // Defence cape, Defence cape (t)
            13280, 13329, 13331, 13333, 13335, 13337, 13339, 13341, 13343, 21776, // Max cape variants
            21898, 28578  // More max cape variants
    );

    /**
     * Teleport type classification for wilderness restrictions.
     * 
     * <p>IMPORTANT: In wilderness, ALL teleports are blocked above level 20 EXCEPT
     * the specific level-30 items. Above level 30, NOTHING works.
     * Grouping teleports and home teleports are NOT exempt - they follow standard rules.
     */
    public enum TeleportType {
        /**
         * Standard teleport - blocked above level 20.
         * Includes: Spells, tablets, most jewelry, fairy rings, spirit trees,
         * grouping/minigame teleports, home teleport.
         */
        STANDARD,

        /**
         * Enhanced teleport - works up to level 30.
         * ONLY these specific items: Amulet of glory, Combat bracelet, Skills necklace,
         * Ring of wealth, Pharaoh's sceptre, Grand/Royal seed pod, Slayer ring,
         * Ring of life, Escape crystal, Defence/Max cape (ring of life effect).
         */
        ENHANCED
    }

    /**
     * Calculate the wilderness level at a given world point.
     * 
     * @param point the world point
     * @return wilderness level (0 if not in wilderness)
     */
    public static int getWildernessLevel(WorldPoint point) {
        if (point == null) {
            return 0;
        }
        
        // Only overworld (plane 0) has wilderness
        // Exception: Some underground areas like Rev caves are wilderness
        if (point.getPlane() != 0) {
            // Check for underground wilderness areas (Rev caves, etc.)
            // Rev caves are at various Y coordinates but have specific region IDs
            // For simplicity, we'll just check plane 0 - underground areas should have
            // their own region-specific handling
            return 0;
        }
        
        int y = point.getY();
        
        // Wilderness starts at Y=3520
        if (y < WILDERNESS_Y_START) {
            return 0;
        }
        
        // Calculate level: every 8 tiles north = 1 wilderness level
        int level = (y - WILDERNESS_Y_START) / 8 + 1;
        
        return Math.min(level, MAX_WILDERNESS_LEVEL);
    }

    /**
     * Check if standard teleports are available at a location.
     * 
     * @param point the world point
     * @return true if standard teleports can be used
     */
    public static boolean canUseStandardTeleport(WorldPoint point) {
        return getWildernessLevel(point) <= STANDARD_TELEPORT_LIMIT;
    }

    /**
     * Check if enhanced teleports (glory, etc.) are available at a location.
     * 
     * @param point the world point
     * @return true if enhanced teleports can be used
     */
    public static boolean canUseEnhancedTeleport(WorldPoint point) {
        return getWildernessLevel(point) <= ENHANCED_TELEPORT_LIMIT;
    }

    /**
     * Check if a teleport can be used at a location based on its type.
     * 
     * <p>In wilderness:
     * <ul>
     *   <li>Level 1-20: All teleports work</li>
     *   <li>Level 21-30: ONLY enhanced items work (glory, combat bracelet, etc.)</li>
     *   <li>Level 31+: NO teleports work at all</li>
     * </ul>
     * 
     * @param point        the world point
     * @param teleportType the type of teleport
     * @return true if the teleport can be used
     */
    public static boolean canUseTeleport(WorldPoint point, TeleportType teleportType) {
        int level = getWildernessLevel(point);
        
        switch (teleportType) {
            case ENHANCED:
                return level <= ENHANCED_TELEPORT_LIMIT;
                
            case STANDARD:
            default:
                return level <= STANDARD_TELEPORT_LIMIT;
        }
    }

    /**
     * Determine the teleport type for an item ID.
     * 
     * @param itemId the item ID
     * @return the teleport type (ENHANCED if level 30 item, otherwise STANDARD)
     */
    public static TeleportType getTeleportTypeForItem(int itemId) {
        if (LEVEL_30_TELEPORT_ITEMS.contains(itemId)) {
            return TeleportType.ENHANCED;
        }
        return TeleportType.STANDARD;
    }

    /**
     * Check if an item can teleport from a given location.
     * 
     * @param point  the world point
     * @param itemId the teleport item ID
     * @return true if the item's teleport can be used at this location
     */
    public static boolean canItemTeleportFrom(WorldPoint point, int itemId) {
        TeleportType type = getTeleportTypeForItem(itemId);
        return canUseTeleport(point, type);
    }

    /**
     * Get the maximum wilderness level a player can teleport from.
     * 
     * <p>Returns 30 if player has any level-30 teleport items, otherwise 20.
     * This is useful for pathfinding to determine edge traversability.
     *
     * @param hasLevel30Item whether the player has any level-30 teleport item
     * @return maximum wilderness level for teleportation
     */
    public static int getMaxTeleportLevel(boolean hasLevel30Item) {
        return hasLevel30Item ? ENHANCED_TELEPORT_LIMIT : STANDARD_TELEPORT_LIMIT;
    }

    /**
     * Check if the player has any level-30 wilderness teleport item.
     * 
     * @param inventoryItemIds set of item IDs in inventory
     * @param equipmentItemIds set of item IDs equipped
     * @return true if player has at least one level-30 teleport item
     */
    public static boolean hasLevel30TeleportItem(Set<Integer> inventoryItemIds, 
                                                   Set<Integer> equipmentItemIds) {
        // Check inventory
        for (int itemId : inventoryItemIds) {
            if (LEVEL_30_TELEPORT_ITEMS.contains(itemId)) {
                return true;
            }
        }
        
        // Check equipment
        for (int itemId : equipmentItemIds) {
            if (LEVEL_30_TELEPORT_ITEMS.contains(itemId)) {
                return true;
            }
        }
        
        return false;
    }
}

