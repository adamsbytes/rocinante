package com.rocinante.inventory;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Specification for a single item or item type that should be present in the player's
 * inventory or equipment during task execution.
 *
 * <p>Supports two modes of item specification:
 * <ul>
 *   <li><b>Specific item:</b> A single item ID (e.g., {@code ItemID.DRAGON_AXE})</li>
 *   <li><b>Collection-based:</b> A collection of acceptable items with level requirements
 *       (e.g., {@code ItemCollections.AXES} with {@code ItemCollections.AXE_LEVELS})</li>
 * </ul>
 *
 * <p>For collection-based specs, the system automatically selects the best item the player
 * can use based on their skill level and what's available in inventory/bank.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Specific item
 * InventorySlotSpec axe = InventorySlotSpec.forItem(ItemID.RUNE_AXE);
 *
 * // Best available from collection
 * InventorySlotSpec bestAxe = InventorySlotSpec.forToolCollection(
 *     ItemCollections.AXES,
 *     ItemCollections.AXE_LEVELS,
 *     Skill.WOODCUTTING,
 *     EquipPreference.PREFER_EQUIP
 * );
 *
 * // Multiple food items
 * InventorySlotSpec food = InventorySlotSpec.builder()
 *     .itemId(ItemID.SHARK)
 *     .quantity(15)
 *     .equipPreference(EquipPreference.PREFER_INVENTORY)
 *     .build();
 * }</pre>
 *
 * @see IdealInventory
 * @see EquipPreference
 */
@Value
@Builder(toBuilder = true)
public class InventorySlotSpec {

    // ========================================================================
    // Item Identification
    // ========================================================================

    /**
     * Specific item ID to use. If set, this takes precedence over collection.
     * Set to null to use collection-based selection.
     */
    @Nullable
    Integer itemId;

    /**
     * Collection of acceptable item IDs ordered worst→best.
     * Used with {@link #levelRequirements} for automatic best-item selection.
     * Only used if {@link #itemId} is null.
     *
     * <p>Example: {@code ItemCollections.AXES} = [BRONZE_AXE, IRON_AXE, ..., CRYSTAL_AXE]
     */
    @Nullable
    List<Integer> itemCollection;

    /**
     * Level requirements for items in the collection.
     * Maps item ID to required skill level.
     * Only used when {@link #itemCollection} is set.
     *
     * <p>Example: {@code ItemCollections.AXE_LEVELS} = {BRONZE_AXE=1, IRON_AXE=1, ..., CRYSTAL_AXE=71}
     */
    @Nullable
    Map<Integer, Integer> levelRequirements;

    /**
     * Skill to check for level-based item selection.
     * Used to filter collection items to those the player can use.
     *
     * <p>Example: {@code Skill.WOODCUTTING} for axes, {@code Skill.MINING} for pickaxes.
     */
    @Nullable
    Skill skillForLevelCheck;

    // ========================================================================
    // Quantity and Placement
    // ========================================================================

    /**
     * Number of this item required.
     * Default is 1 (for tools, weapons).
     * Higher values for consumables (food, potions).
     */
    @Builder.Default
    int quantity = 1;

    /**
     * Preference for where the item should be placed.
     * See {@link EquipPreference} for options.
     */
    @Builder.Default
    EquipPreference equipPreference = EquipPreference.TASK_DEFAULT;

    // ========================================================================
    // Optional Metadata
    // ========================================================================

    /**
     * Human-readable name for logging/debugging.
     * Auto-generated from item ID if not specified.
     */
    @Nullable
    String displayName;

    /**
     * Whether this item is optional (task can proceed without it).
     * Default is false (item is required).
     */
    @Builder.Default
    boolean optional = false;

    /**
     * If true and item is not available, select next best from collection.
     * If false and item is not available, fail preparation.
     * Only applicable when using collection-based selection.
     */
    @Builder.Default
    boolean allowFallback = true;

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a spec for a specific item ID with default settings.
     *
     * @param itemId the item ID
     * @return spec for that item with quantity 1 and TASK_DEFAULT equip preference
     */
    public static InventorySlotSpec forItem(int itemId) {
        return InventorySlotSpec.builder()
                .itemId(itemId)
                .quantity(1)
                .equipPreference(EquipPreference.TASK_DEFAULT)
                .build();
    }

    /**
     * Create a spec for a specific item ID with explicit equip preference.
     *
     * @param itemId the item ID
     * @param preference where to place the item
     * @return spec for that item
     */
    public static InventorySlotSpec forItem(int itemId, EquipPreference preference) {
        return InventorySlotSpec.builder()
                .itemId(itemId)
                .quantity(1)
                .equipPreference(preference)
                .build();
    }

    /**
     * Create a spec for multiple of the same item (e.g., food).
     *
     * @param itemId the item ID
     * @param quantity how many to have
     * @return spec for those items in inventory
     */
    public static InventorySlotSpec forItems(int itemId, int quantity) {
        return InventorySlotSpec.builder()
                .itemId(itemId)
                .quantity(quantity)
                .equipPreference(EquipPreference.PREFER_INVENTORY)
                .build();
    }

    /**
     * Create a spec for a tool collection with level-based selection.
     *
     * <p>The system will automatically select the best tool from the collection
     * that the player can use based on their skill level.
     *
     * @param collection items ordered worst→best (e.g., {@code ItemCollections.AXES})
     * @param levelReqs level requirements map (e.g., {@code ItemCollections.AXE_LEVELS})
     * @param skill skill to check level for
     * @param preference equip preference
     * @return spec for best available tool from collection
     */
    public static InventorySlotSpec forToolCollection(
            List<Integer> collection,
            Map<Integer, Integer> levelReqs,
            Skill skill,
            EquipPreference preference) {
        return InventorySlotSpec.builder()
                .itemCollection(collection)
                .levelRequirements(levelReqs)
                .skillForLevelCheck(skill)
                .quantity(1)
                .equipPreference(preference)
                .allowFallback(true)
                .build();
    }

    /**
     * Create a spec for a tool collection with default PREFER_EQUIP behavior.
     *
     * @param collection items ordered worst→best
     * @param levelReqs level requirements map
     * @param skill skill to check level for
     * @return spec for best available tool, equipped if possible
     */
    public static InventorySlotSpec forToolCollection(
            List<Integer> collection,
            Map<Integer, Integer> levelReqs,
            Skill skill) {
        return forToolCollection(collection, levelReqs, skill, EquipPreference.PREFER_EQUIP);
    }

    /**
     * Create a spec for any item from a collection (no level check).
     * Useful for food, where any from the collection is acceptable.
     *
     * @param collection items to accept
     * @param quantity how many needed
     * @return spec for items from collection in inventory
     */
    public static InventorySlotSpec forAnyFromCollection(List<Integer> collection, int quantity) {
        return InventorySlotSpec.builder()
                .itemCollection(collection)
                .quantity(quantity)
                .equipPreference(EquipPreference.PREFER_INVENTORY)
                .allowFallback(true)
                .build();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if this spec uses collection-based item selection.
     *
     * @return true if itemCollection is set and itemId is null
     */
    public boolean isCollectionBased() {
        return itemId == null && itemCollection != null && !itemCollection.isEmpty();
    }

    /**
     * Check if this spec is for a specific item.
     *
     * @return true if itemId is set
     */
    public boolean isSpecificItem() {
        return itemId != null;
    }

    /**
     * Check if this spec has level requirements for selection.
     *
     * @return true if levelRequirements is set
     */
    public boolean hasLevelRequirements() {
        return levelRequirements != null && !levelRequirements.isEmpty();
    }

    /**
     * Get the effective equip preference, resolving TASK_DEFAULT based on item type.
     *
     * @param isToolType true if this is a tool (axe, pickaxe, etc.)
     * @return resolved preference
     */
    public EquipPreference getEffectiveEquipPreference(boolean isToolType) {
        if (equipPreference != EquipPreference.TASK_DEFAULT) {
            return equipPreference;
        }
        // Default behavior based on item type
        if (isToolType) {
            return EquipPreference.PREFER_EQUIP;
        }
        if (quantity > 1) {
            return EquipPreference.PREFER_INVENTORY;
        }
        return EquipPreference.EITHER;
    }

    /**
     * Get a human-readable description for logging.
     *
     * @return description string
     */
    public String getDescription() {
        if (displayName != null) {
            return displayName + " x" + quantity;
        }
        if (itemId != null) {
            return "Item " + itemId + " x" + quantity;
        }
        if (itemCollection != null) {
            return "Best from " + itemCollection.size() + " items x" + quantity;
        }
        return "Unknown item spec";
    }

    @Override
    public String toString() {
        return getDescription();
    }
}

