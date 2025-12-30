package com.rocinante.inventory;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declarative specification of the ideal inventory state for a task.
 *
 * <p>Defines what items should be present in inventory and equipment before
 * a task begins execution. The {@link InventoryPreparation} service uses this
 * specification to generate the necessary banking, withdrawing, and equipping tasks.
 *
 * <p>Key features:
 * <ul>
 *   <li>Declarative: specify WHAT you want, not HOW to get it</li>
 *   <li>Flexible: supports specific items, collections, and level-based selection</li>
 *   <li>Override-friendly: planners can provide custom specs or null to skip</li>
 *   <li>Composable: combine multiple specs for complex inventory setups</li>
 * </ul>
 *
 * <p>Example for woodcutting (power chopping):
 * <pre>{@code
 * IdealInventory.builder()
 *     .depositInventoryFirst(true)
 *     .requiredItem(InventorySlotSpec.forToolCollection(
 *         ItemCollections.AXES,
 *         ItemCollections.AXE_LEVELS,
 *         Skill.WOODCUTTING,
 *         EquipPreference.PREFER_EQUIP
 *     ))
 *     .build();
 * }</pre>
 *
 * <p>Example for combat:
 * <pre>{@code
 * IdealInventory.builder()
 *     .depositInventoryFirst(true)
 *     .requiredItem(InventorySlotSpec.forItem(ItemID.RUNE_SCIMITAR, EquipPreference.MUST_EQUIP))
 *     .requiredItem(InventorySlotSpec.forItems(ItemID.SHARK, 10))
 *     .fillRestWithItemId(ItemID.SHARK)
 *     .build();
 * }</pre>
 *
 * @see InventorySlotSpec
 * @see InventoryPreparation
 * @see IdealInventoryFactory
 */
@Value
@Builder(toBuilder = true)
public class IdealInventory {

    // ========================================================================
    // Required Items
    // ========================================================================

    /**
     * List of items that should be present in inventory or equipment.
     * Items are processed in order - earlier items take priority for equip slots.
     */
    @Singular
    List<InventorySlotSpec> requiredItems;

    // ========================================================================
    // Deposit Behavior
    // ========================================================================

    /**
     * Whether to deposit current inventory contents before withdrawing items.
     * Default is true - ensures a clean slate for the task.
     *
     * <p>Set to false if:
     * <ul>
     *   <li>Adding items to existing inventory (e.g., restocking food mid-task)</li>
     *   <li>Planner has already prepared inventory</li>
     * </ul>
     */
    @Builder.Default
    boolean depositInventoryFirst = true;

    /**
     * Whether to deposit current equipment before re-equipping.
     * Default is false - keeps existing equipment unless explicitly replacing.
     *
     * <p>Set to true for:
     * <ul>
     *   <li>Complete gear switches</li>
     *   <li>Tasks that require specific gear only</li>
     * </ul>
     */
    @Builder.Default
    boolean depositEquipmentFirst = false;

    // ========================================================================
    // Item Handling Behavior
    // ========================================================================

    /**
     * Whether to keep items not specified in requiredItems.
     * Default is false - non-specified items are deposited.
     *
     * <p>Set to true for:
     * <ul>
     *   <li>Adding to existing inventory (mid-task restock)</li>
     *   <li>Tasks that don't care about extra items</li>
     * </ul>
     */
    @Builder.Default
    boolean keepExistingItems = false;

    /**
     * Item ID to fill remaining inventory slots with.
     * Null means leave slots empty.
     *
     * <p>Useful for:
     * <ul>
     *   <li>Combat: fill with food after gear/supplies</li>
     *   <li>Processing: fill with materials after tools</li>
     * </ul>
     */
    @Nullable
    Integer fillRestWithItemId;

    /**
     * Maximum quantity of fill item to withdraw.
     * Only used when {@link #fillRestWithItemId} is set.
     * Default is -1 (fill all remaining slots).
     */
    @Builder.Default
    int fillRestMaxQuantity = -1;

    // ========================================================================
    // Validation Behavior
    // ========================================================================

    /**
     * Whether to fail if any non-optional item cannot be obtained.
     * Default is true - strict validation.
     *
     * <p>Set to false for graceful degradation (try to get as much as possible).
     */
    @Builder.Default
    boolean failOnMissingItems = true;

    /**
     * Whether to verify inventory state matches spec after preparation.
     * Default is true - ensures preparation was successful.
     */
    @Builder.Default
    boolean validateAfterPreparation = true;

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create an empty ideal inventory (do nothing).
     * Use when planner has already prepared inventory.
     *
     * @return spec that performs no preparation
     */
    public static IdealInventory none() {
        return IdealInventory.builder()
                .depositInventoryFirst(false)
                .depositEquipmentFirst(false)
                .keepExistingItems(true)
                .failOnMissingItems(false)
                .validateAfterPreparation(false)
                .build();
    }

    /**
     * Create an ideal inventory with just deposit (empty inventory).
     * Useful before power training tasks.
     *
     * @return spec that deposits everything
     */
    public static IdealInventory emptyInventory() {
        return IdealInventory.builder()
                .depositInventoryFirst(true)
                .depositEquipmentFirst(false)
                .keepExistingItems(false)
                .build();
    }

    /**
     * Create an ideal inventory for a single tool.
     *
     * @param toolSpec the tool specification
     * @return spec with just that tool
     */
    public static IdealInventory forTool(InventorySlotSpec toolSpec) {
        return IdealInventory.builder()
                .requiredItem(toolSpec)
                .depositInventoryFirst(true)
                .build();
    }

    /**
     * Create an ideal inventory for combat.
     *
     * @param weaponSpec weapon specification
     * @param foodItemId food item to fill with
     * @param foodCount minimum food count before filling
     * @return spec for combat inventory
     */
    public static IdealInventory forCombat(
            InventorySlotSpec weaponSpec,
            int foodItemId,
            int foodCount) {
        return IdealInventory.builder()
                .requiredItem(weaponSpec)
                .requiredItem(InventorySlotSpec.forItems(foodItemId, foodCount))
                .fillRestWithItemId(foodItemId)
                .depositInventoryFirst(true)
                .build();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if this spec has any required items.
     *
     * @return true if requiredItems is not empty
     */
    public boolean hasRequiredItems() {
        return requiredItems != null && !requiredItems.isEmpty();
    }

    /**
     * Check if this spec will fill remaining slots.
     *
     * @return true if fillRestWithItemId is set
     */
    public boolean hasFillItem() {
        return fillRestWithItemId != null;
    }

    /**
     * Get the total minimum inventory slots needed (excluding fill).
     *
     * @return slot count needed
     */
    public int getMinimumSlotsNeeded() {
        if (requiredItems == null) {
            return 0;
        }
        return requiredItems.stream()
                .filter(spec -> spec.getEquipPreference() != EquipPreference.MUST_EQUIP)
                .mapToInt(InventorySlotSpec::getQuantity)
                .sum();
    }

    /**
     * Get items that must be equipped.
     *
     * @return list of specs with MUST_EQUIP preference
     */
    public List<InventorySlotSpec> getEquipmentItems() {
        if (requiredItems == null) {
            return Collections.emptyList();
        }
        List<InventorySlotSpec> equipment = new ArrayList<>();
        for (InventorySlotSpec spec : requiredItems) {
            if (spec.getEquipPreference() == EquipPreference.MUST_EQUIP ||
                spec.getEquipPreference() == EquipPreference.PREFER_EQUIP) {
                equipment.add(spec);
            }
        }
        return equipment;
    }

    /**
     * Get items that go in inventory.
     *
     * @return list of specs with inventory preference
     */
    public List<InventorySlotSpec> getInventoryItems() {
        if (requiredItems == null) {
            return Collections.emptyList();
        }
        List<InventorySlotSpec> inventory = new ArrayList<>();
        for (InventorySlotSpec spec : requiredItems) {
            if (spec.getEquipPreference() == EquipPreference.PREFER_INVENTORY ||
                spec.getEquipPreference() == EquipPreference.EITHER) {
                inventory.add(spec);
            }
        }
        return inventory;
    }

    /**
     * Create a new spec with an additional required item.
     *
     * @param spec the item spec to add
     * @return new IdealInventory with the additional item
     */
    public IdealInventory withAdditionalItem(InventorySlotSpec spec) {
        List<InventorySlotSpec> newItems = new ArrayList<>(requiredItems);
        newItems.add(spec);
        return this.toBuilder()
                .clearRequiredItems()
                .requiredItems(newItems)
                .build();
    }

    /**
     * Get a summary description for logging.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        int itemCount = requiredItems != null ? requiredItems.size() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("IdealInventory[");
        sb.append(itemCount).append(" items");
        if (depositInventoryFirst) {
            sb.append(", deposit first");
        }
        if (hasFillItem()) {
            sb.append(", fill with ").append(fillRestWithItemId);
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

