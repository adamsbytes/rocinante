package com.rocinante.inventory;

import com.rocinante.state.BankState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.util.ItemCollections;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Helper for selecting the best tool from a collection based on player levels.
 *
 * <p>Handles the complex logic of selecting tools (axes, pickaxes, etc.) considering:
 * <ul>
 *   <li>Skill level requirements (e.g., 41 Woodcutting for rune axe)</li>
 *   <li>Attack level requirements for equipping (e.g., 40 Attack to wield rune axe)</li>
 *   <li>Item availability (inventory, equipment, bank)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Select best axe for player with 50 WC and 1 Attack
 * ToolSelectionResult result = ToolSelection.selectBestTool(
 *     ItemCollections.AXES,
 *     ItemCollections.AXE_LEVELS,
 *     50,  // WC level - can USE rune axe
 *     1,   // Attack level - can only EQUIP bronze/iron
 *     inventory::hasItem
 * );
 * // Result: Best usable axe, with info on whether it can be equipped
 * }</pre>
 *
 * @see InventorySlotSpec
 * @see IdealInventory
 */
@Slf4j
public final class ToolSelection {

    private ToolSelection() {
        // Utility class
    }

    // ========================================================================
    // Attack Level Requirements for Equipping Tools
    // ========================================================================

    /**
     * Attack level requirements for equipping axes.
     * In OSRS, axes require Attack to wield (weapon slot).
     */
    public static final Map<Integer, Integer> AXE_ATTACK_LEVELS = Map.ofEntries(
            Map.entry(ItemID.BRONZE_AXE, 1),
            Map.entry(ItemID.IRON_AXE, 1),
            Map.entry(ItemID.STEEL_AXE, 5),
            Map.entry(ItemID.BLACK_AXE, 10),
            Map.entry(ItemID.MITHRIL_AXE, 20),
            Map.entry(ItemID.ADAMANT_AXE, 30),
            Map.entry(ItemID.RUNE_AXE, 40),
            Map.entry(ItemID.DRAGON_AXE, 60),
            Map.entry(ItemID._3RD_AGE_AXE, 65),
            Map.entry(ItemID.INFERNAL_AXE, 60),
            Map.entry(ItemID.CRYSTAL_AXE, 70)
    );

    /**
     * Attack level requirements for equipping pickaxes.
     * In OSRS, pickaxes require Attack to wield (weapon slot).
     */
    public static final Map<Integer, Integer> PICKAXE_ATTACK_LEVELS = Map.ofEntries(
            Map.entry(ItemID.BRONZE_PICKAXE, 1),
            Map.entry(ItemID.IRON_PICKAXE, 1),
            Map.entry(ItemID.STEEL_PICKAXE, 5),
            Map.entry(ItemID.BLACK_PICKAXE, 10),
            Map.entry(ItemID.MITHRIL_PICKAXE, 20),
            Map.entry(ItemID.ADAMANT_PICKAXE, 30),
            Map.entry(ItemID.RUNE_PICKAXE, 40),
            Map.entry(ItemID.DRAGON_PICKAXE, 60),
            Map.entry(ItemID._3RD_AGE_PICKAXE, 65),
            Map.entry(ItemID.INFERNAL_PICKAXE, 60),
            Map.entry(ItemID.CRYSTAL_PICKAXE, 70)
    );

    /**
     * Agility level requirements for certain tools.
     * Crystal items require 50 Agility.
     */
    public static final Map<Integer, Integer> TOOL_AGILITY_LEVELS = Map.of(
            ItemID.CRYSTAL_AXE, 50,
            ItemID.CRYSTAL_PICKAXE, 50
    );

    // ========================================================================
    // Tool Selection Methods
    // ========================================================================

    /**
     * Select the best tool from a collection considering both skill and equip requirements.
     *
     * <p>This method returns the best tool that the player can USE (skill level),
     * along with information about whether they can EQUIP it (attack level).
     *
     * @param collection     items ordered worst→best
     * @param skillLevelReqs skill level requirements (WC level for axes, Mining for picks)
     * @param playerSkillLevel player's current skill level
     * @param playerAttackLevel player's attack level (for equip check)
     * @param isAvailable    predicate to check item availability
     * @return selection result with best item and equip capability
     */
    public static ToolSelectionResult selectBestTool(
            List<Integer> collection,
            Map<Integer, Integer> skillLevelReqs,
            int playerSkillLevel,
            int playerAttackLevel,
            Predicate<Integer> isAvailable) {
        
        return selectBestTool(collection, skillLevelReqs, playerSkillLevel,
                playerAttackLevel, 99, isAvailable); // 99 agility = ignore agility check
    }

    /**
     * Select the best tool considering skill, attack, and agility requirements.
     *
     * @param collection        items ordered worst→best
     * @param skillLevelReqs    skill level requirements
     * @param playerSkillLevel  player's current skill level
     * @param playerAttackLevel player's attack level
     * @param playerAgilityLevel player's agility level
     * @param isAvailable       predicate to check item availability
     * @return selection result
     */
    public static ToolSelectionResult selectBestTool(
            List<Integer> collection,
            Map<Integer, Integer> skillLevelReqs,
            int playerSkillLevel,
            int playerAttackLevel,
            int playerAgilityLevel,
            Predicate<Integer> isAvailable) {

        if (collection == null || collection.isEmpty()) {
            return ToolSelectionResult.notFound();
        }

        // Determine which equip level map to use based on items in collection
        Map<Integer, Integer> equipLevelReqs = getEquipLevelMap(collection);

        Integer bestUsable = null;
        Integer bestEquippable = null;

        // Iterate from best to worst (reverse order)
        List<Integer> bestFirst = ItemCollections.bestFirst(collection);
        
        for (int itemId : bestFirst) {
            // Check if player can USE this item (skill level)
            int requiredSkillLevel = skillLevelReqs.getOrDefault(itemId, 1);
            if (playerSkillLevel < requiredSkillLevel) {
                continue; // Can't use this item
            }

            // Check if item is available
            if (!isAvailable.test(itemId)) {
                continue; // Don't have this item
            }

            // This is the best usable item we have
            if (bestUsable == null) {
                bestUsable = itemId;
            }

            // Check if player can EQUIP this item
            int requiredAttackLevel = equipLevelReqs.getOrDefault(itemId, 1);
            int requiredAgilityLevel = TOOL_AGILITY_LEVELS.getOrDefault(itemId, 1);
            
            if (playerAttackLevel >= requiredAttackLevel && 
                playerAgilityLevel >= requiredAgilityLevel) {
                // This is the best equippable item
                if (bestEquippable == null) {
                    bestEquippable = itemId;
                    // Found best equippable - if it's also the best usable, we're done
                    if (bestEquippable.equals(bestUsable)) {
                        break;
                    }
                }
            }
        }

        if (bestUsable == null) {
            log.debug("No usable tool found in collection (skill level {} too low or items unavailable)",
                    playerSkillLevel);
            return ToolSelectionResult.notFound();
        }

        boolean canEquipBest = bestUsable.equals(bestEquippable);
        
        log.debug("Tool selection: best usable={}, best equippable={}, can equip best={}",
                bestUsable, bestEquippable, canEquipBest);

        return ToolSelectionResult.builder()
                .itemId(bestUsable)
                .canEquip(canEquipBest)
                .bestEquippableAlternative(canEquipBest ? null : bestEquippable)
                .build();
    }

    /**
     * Select best tool considering inventory, equipment, and bank.
     *
     * @param spec              the inventory slot spec
     * @param playerSkillLevel  player's skill level
     * @param playerAttackLevel player's attack level
     * @param inventory         current inventory
     * @param equipment         current equipment (nullable)
     * @param bank              current bank state (nullable)
     * @return selection result
     */
    public static ToolSelectionResult selectBestToolFromSpec(
            InventorySlotSpec spec,
            int playerSkillLevel,
            int playerAttackLevel,
            InventoryState inventory,
            @Nullable EquipmentState equipment,
            @Nullable BankState bank) {

        // If spec has specific itemId, just check availability
        if (spec.isSpecificItem()) {
            int itemId = spec.getItemId();
            boolean available = isItemAvailable(itemId, inventory, equipment, bank);
            if (!available) {
                return ToolSelectionResult.notFound();
            }
            boolean canEquip = canEquipItem(itemId, playerAttackLevel, 99);
            return ToolSelectionResult.builder()
                    .itemId(itemId)
                    .canEquip(canEquip)
                    .build();
        }

        // Collection-based selection
        if (!spec.isCollectionBased()) {
            return ToolSelectionResult.notFound();
        }

        Predicate<Integer> isAvailable = id -> 
                isItemAvailable(id, inventory, equipment, bank);

        Map<Integer, Integer> levelReqs = spec.hasLevelRequirements()
                ? spec.getLevelRequirements()
                : Map.of(); // No level requirements = all items valid

        return selectBestTool(
                spec.getItemCollection(),
                levelReqs,
                playerSkillLevel,
                playerAttackLevel,
                isAvailable
        );
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if a specific item can be equipped based on player's attack level.
     *
     * @param itemId            the item ID
     * @param playerAttackLevel player's attack level
     * @param playerAgilityLevel player's agility level
     * @return true if player can equip this item
     */
    public static boolean canEquipItem(int itemId, int playerAttackLevel, int playerAgilityLevel) {
        // Check attack requirement
        int requiredAttack = getAttackLevelForEquip(itemId);
        if (playerAttackLevel < requiredAttack) {
            return false;
        }

        // Check agility requirement (crystal items)
        int requiredAgility = TOOL_AGILITY_LEVELS.getOrDefault(itemId, 1);
        if (playerAgilityLevel < requiredAgility) {
            return false;
        }

        return true;
    }

    /**
     * Get the attack level required to equip an item.
     *
     * @param itemId the item ID
     * @return attack level required, or 1 if no requirement
     */
    public static int getAttackLevelForEquip(int itemId) {
        // Check axes
        Integer axeLevel = AXE_ATTACK_LEVELS.get(itemId);
        if (axeLevel != null) {
            return axeLevel;
        }

        // Check pickaxes
        Integer pickLevel = PICKAXE_ATTACK_LEVELS.get(itemId);
        if (pickLevel != null) {
            return pickLevel;
        }

        // Default: no attack requirement
        return 1;
    }

    /**
     * Check if an item is available in inventory, equipment, or bank.
     *
     * @param itemId    the item ID
     * @param inventory inventory state
     * @param equipment equipment state (nullable)
     * @param bank      bank state (nullable)
     * @return true if item is available somewhere
     */
    public static boolean isItemAvailable(
            int itemId,
            InventoryState inventory,
            @Nullable EquipmentState equipment,
            @Nullable BankState bank) {
        
        if (inventory != null && inventory.hasItem(itemId)) {
            return true;
        }
        if (equipment != null && equipment.hasEquipped(itemId)) {
            return true;
        }
        if (bank != null && bank.hasItem(itemId)) {
            return true;
        }
        return false;
    }

    /**
     * Find where an item is currently located.
     *
     * @param itemId    the item ID
     * @param inventory inventory state
     * @param equipment equipment state (nullable)
     * @param bank      bank state (nullable)
     * @return location enum
     */
    public static ItemLocation findItemLocation(
            int itemId,
            InventoryState inventory,
            @Nullable EquipmentState equipment,
            @Nullable BankState bank) {

        if (equipment != null && equipment.hasEquipped(itemId)) {
            return ItemLocation.EQUIPPED;
        }
        if (inventory != null && inventory.hasItem(itemId)) {
            return ItemLocation.INVENTORY;
        }
        if (bank != null && bank.hasItem(itemId)) {
            return ItemLocation.BANK;
        }
        return ItemLocation.NOT_FOUND;
    }

    /**
     * Determine the appropriate equip level map based on items in collection.
     */
    private static Map<Integer, Integer> getEquipLevelMap(List<Integer> collection) {
        if (collection.isEmpty()) {
            return Map.of();
        }
        
        int firstItem = collection.get(0);
        
        // Check if it's an axe collection
        if (AXE_ATTACK_LEVELS.containsKey(firstItem)) {
            return AXE_ATTACK_LEVELS;
        }
        
        // Check if it's a pickaxe collection
        if (PICKAXE_ATTACK_LEVELS.containsKey(firstItem)) {
            return PICKAXE_ATTACK_LEVELS;
        }
        
        // Default: no equip requirements
        return Map.of();
    }

    // ========================================================================
    // Helper Classes
    // ========================================================================

    /**
     * Where an item is currently located.
     */
    public enum ItemLocation {
        EQUIPPED,
        INVENTORY,
        BANK,
        NOT_FOUND
    }

    /**
     * Result of tool selection operation.
     */
    @lombok.Value
    @lombok.Builder
    public static class ToolSelectionResult {
        /**
         * The selected item ID, or null if no suitable item found.
         */
        @Nullable
        Integer itemId;

        /**
         * Whether the player can equip this item (attack level check).
         */
        boolean canEquip;

        /**
         * If the best usable item can't be equipped, this is the best
         * alternative that CAN be equipped (may be null if none available).
         */
        @Nullable
        Integer bestEquippableAlternative;

        /**
         * Check if a valid tool was found.
         *
         * @return true if itemId is set
         */
        public boolean isFound() {
            return itemId != null;
        }

        /**
         * Get the item to use - prefers best equippable if available and
         * the main item can't be equipped.
         *
         * @param preferEquippable if true and best can't be equipped, use alternative
         * @return item ID to use
         */
        public Optional<Integer> getItemToUse(boolean preferEquippable) {
            if (itemId == null) {
                return Optional.empty();
            }
            if (preferEquippable && !canEquip && bestEquippableAlternative != null) {
                return Optional.of(bestEquippableAlternative);
            }
            return Optional.of(itemId);
        }

        /**
         * Create a "not found" result.
         */
        public static ToolSelectionResult notFound() {
            return ToolSelectionResult.builder()
                    .itemId(null)
                    .canEquip(false)
                    .build();
        }
    }
}

