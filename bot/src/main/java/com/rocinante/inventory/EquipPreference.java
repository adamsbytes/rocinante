package com.rocinante.inventory;

/**
 * Specifies the preference for where an item should be placed during inventory preparation.
 *
 * <p>Used by {@link InventorySlotSpec} to indicate whether items should be equipped
 * or kept in inventory. The system will attempt to honor preferences while respecting
 * game constraints (e.g., Attack level requirements for equipping weapons).
 *
 * <p>Example usage:
 * <pre>{@code
 * // Tool that must be equipped (fail if can't)
 * InventorySlotSpec.builder()
 *     .itemId(ItemID.DRAGON_AXE)
 *     .equipPreference(EquipPreference.MUST_EQUIP)
 *     .build();
 *
 * // Food that should stay in inventory
 * InventorySlotSpec.builder()
 *     .itemCollection(ItemCollections.FOOD)
 *     .quantity(10)
 *     .equipPreference(EquipPreference.PREFER_INVENTORY)
 *     .build();
 * }</pre>
 */
public enum EquipPreference {

    /**
     * Item MUST be equipped. Task preparation fails if the player cannot equip the item
     * (e.g., insufficient Attack level for a weapon).
     *
     * <p>Use for gear that is essential to the task's function, such as:
     * <ul>
     *   <li>Weapons for combat tasks</li>
     *   <li>Slayer equipment (nose peg, earmuffs)</li>
     *   <li>Quest-required equipment</li>
     * </ul>
     */
    MUST_EQUIP,

    /**
     * Item should be equipped if possible, otherwise kept in inventory.
     * This is the default for tools like axes, pickaxes, and fishing rods.
     *
     * <p>The system will:
     * <ol>
     *   <li>Check if player meets equip requirements (Attack level, etc.)</li>
     *   <li>If yes, equip the item</li>
     *   <li>If no, keep in inventory (task continues normally)</li>
     * </ol>
     *
     * <p>This saves an inventory slot when possible while allowing tasks
     * to function even if the player can't equip the tool.
     */
    PREFER_EQUIP,

    /**
     * Item should stay in inventory even if it could be equipped.
     *
     * <p>Use for:
     * <ul>
     *   <li>Food and potions</li>
     *   <li>Teleport items that need to be clicked</li>
     *   <li>Items that will be used/consumed during the task</li>
     * </ul>
     */
    PREFER_INVENTORY,

    /**
     * Item can be in either equipment or inventory - the system will leave it
     * wherever it currently is, or place it wherever is most convenient.
     *
     * <p>Use when the location doesn't matter for task function.
     */
    EITHER,

    /**
     * Use the task's default behavior for this item type.
     *
     * <p>Default behaviors:
     * <ul>
     *   <li>Tools (axes, pickaxes, rods): {@link #PREFER_EQUIP}</li>
     *   <li>Weapons/armor: {@link #MUST_EQUIP} or {@link #PREFER_EQUIP} based on task</li>
     *   <li>Consumables: {@link #PREFER_INVENTORY}</li>
     * </ul>
     */
    TASK_DEFAULT
}

