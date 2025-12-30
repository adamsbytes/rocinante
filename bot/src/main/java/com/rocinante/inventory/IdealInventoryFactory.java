package com.rocinante.inventory;

import com.rocinante.progression.MethodType;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.PlayerState;
import com.rocinante.util.ItemCollections;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory methods for creating {@link IdealInventory} specifications from common patterns.
 *
 * <p>Provides pre-built ideal inventories for:
 * <ul>
 *   <li>Training methods (derived from {@link TrainingMethod} configuration)</li>
 *   <li>Gathering skills (woodcutting, mining, fishing)</li>
 *   <li>Processing skills (smithing, cooking, fletching)</li>
 *   <li>Combat scenarios</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // From training method
 * TrainingMethod method = repo.getMethodById("willow_trees_powerchop").get();
 * IdealInventory ideal = IdealInventoryFactory.fromTrainingMethod(method, player);
 *
 * // For woodcutting directly
 * IdealInventory ideal = IdealInventoryFactory.forWoodcutting(true); // powerchop
 *
 * // For combat
 * IdealInventory ideal = IdealInventoryFactory.forCombat(
 *     ItemID.RUNE_SCIMITAR, ItemID.SHARK, 20);
 * }</pre>
 *
 * @see IdealInventory
 * @see TrainingMethod
 */
@Slf4j
public final class IdealInventoryFactory {

    private IdealInventoryFactory() {
        // Utility class
    }

    // ========================================================================
    // From Training Method
    // ========================================================================

    /**
     * Create an ideal inventory from a training method definition.
     *
     * <p>Automatically handles:
     * <ul>
     *   <li>Tool selection (axes for WC, pickaxes for mining, etc.)</li>
     *   <li>Source/target items for processing methods</li>
     *   <li>Deposit behavior based on method type</li>
     * </ul>
     *
     * @param method the training method
     * @param player current player state (for skill levels)
     * @return ideal inventory for this method
     */
    public static IdealInventory fromTrainingMethod(TrainingMethod method, PlayerState player) {
        if (method == null) {
            return IdealInventory.emptyInventory();
        }

        IdealInventory.IdealInventoryBuilder builder = IdealInventory.builder()
                .depositInventoryFirst(true);

        // Add required tools based on skill
        addToolsForSkill(builder, method, player);

        // Handle method-type specific items
        switch (method.getMethodType()) {
            case GATHER:
                configureForGathering(builder, method);
                break;
            case PROCESS:
                configureForProcessing(builder, method);
                break;
            case COMBAT:
                configureForCombat(builder, method);
                break;
            default:
                // Other types don't need special configuration
                break;
        }

        return builder.build();
    }

    /**
     * Add tool requirements based on skill type.
     */
    private static void addToolsForSkill(
            IdealInventory.IdealInventoryBuilder builder,
            TrainingMethod method,
            PlayerState player) {

        Skill skill = method.getSkill();
        List<Integer> requiredItems = method.getRequiredItemIds();

        // If method has explicit required items, use those
        if (requiredItems != null && !requiredItems.isEmpty()) {
            // Check if these are tools (single quantity) or materials
            // Tools are typically collections like axes, pickaxes
            List<Integer> axes = ItemCollections.AXES;
            List<Integer> pickaxes = ItemCollections.PICKAXES;

            // See if required items match a tool collection
            if (hasAnyMatch(requiredItems, axes)) {
                builder.requiredItem(InventorySlotSpec.forToolCollection(
                        axes,
                        ItemCollections.AXE_LEVELS,
                        Skill.WOODCUTTING,
                        EquipPreference.PREFER_EQUIP
                ));
                return;
            }

            if (hasAnyMatch(requiredItems, pickaxes)) {
                builder.requiredItem(InventorySlotSpec.forToolCollection(
                        pickaxes,
                        ItemCollections.PICKAXE_LEVELS,
                        Skill.MINING,
                        EquipPreference.PREFER_EQUIP
                ));
                return;
            }

            // For other required items, add them directly
            for (int itemId : requiredItems) {
                builder.requiredItem(InventorySlotSpec.forItem(itemId));
            }
            return;
        }

        // No explicit required items - infer from skill
        switch (skill) {
            case WOODCUTTING:
                builder.requiredItem(InventorySlotSpec.forToolCollection(
                        ItemCollections.AXES,
                        ItemCollections.AXE_LEVELS,
                        Skill.WOODCUTTING,
                        EquipPreference.PREFER_EQUIP
                ));
                break;

            case MINING:
                builder.requiredItem(InventorySlotSpec.forToolCollection(
                        ItemCollections.PICKAXES,
                        ItemCollections.PICKAXE_LEVELS,
                        Skill.MINING,
                        EquipPreference.PREFER_EQUIP
                ));
                break;

            case FIREMAKING:
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.TINDERBOX));
                break;

            case FLETCHING:
            case CRAFTING:
                // These often need a knife
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.KNIFE));
                break;

            case SMITHING:
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.HAMMER));
                break;

            // Add more skills as needed
            default:
                break;
        }
    }

    /**
     * Configure for gathering methods (woodcutting, mining, fishing).
     */
    private static void configureForGathering(
            IdealInventory.IdealInventoryBuilder builder,
            TrainingMethod method) {
        // Gathering methods usually just need tools + empty inventory
        // The task will handle dropping/banking when full
        // No additional items needed by default
    }

    /**
     * Configure for processing methods (smithing, cooking, fletching).
     */
    private static void configureForProcessing(
            IdealInventory.IdealInventoryBuilder builder,
            TrainingMethod method) {

        int sourceId = method.getSourceItemId();
        int targetId = method.getTargetItemId();

        // Source item (usually a tool like knife, hammer)
        if (sourceId > 0) {
            builder.requiredItem(InventorySlotSpec.forItem(sourceId));
        }

        // Target item (usually the material to process)
        if (targetId > 0) {
            // Fill inventory with the material
            builder.fillRestWithItemId(targetId);
        }
    }

    /**
     * Configure for combat methods.
     */
    private static void configureForCombat(
            IdealInventory.IdealInventoryBuilder builder,
            TrainingMethod method) {
        // Combat methods need food - will be handled by CombatTask's config
        // This is just a placeholder for base combat setup
    }

    // ========================================================================
    // Skill-Specific Factories
    // ========================================================================

    /**
     * Create ideal inventory for woodcutting.
     *
     * @param powerChop if true, empty inventory for power-chopping; if false, allows banking
     * @return ideal inventory for woodcutting
     */
    public static IdealInventory forWoodcutting(boolean powerChop) {
        return IdealInventory.builder()
                .depositInventoryFirst(powerChop)
                .requiredItem(InventorySlotSpec.forToolCollection(
                        ItemCollections.AXES,
                        ItemCollections.AXE_LEVELS,
                        Skill.WOODCUTTING,
                        EquipPreference.PREFER_EQUIP
                ))
                .build();
    }

    /**
     * Create ideal inventory for mining.
     *
     * @param powerMine if true, empty inventory for power-mining; if false, allows banking
     * @return ideal inventory for mining
     */
    public static IdealInventory forMining(boolean powerMine) {
        return IdealInventory.builder()
                .depositInventoryFirst(powerMine)
                .requiredItem(InventorySlotSpec.forToolCollection(
                        ItemCollections.PICKAXES,
                        ItemCollections.PICKAXE_LEVELS,
                        Skill.MINING,
                        EquipPreference.PREFER_EQUIP
                ))
                .build();
    }

    /**
     * Create ideal inventory for firemaking.
     *
     * @param logItemId the logs to burn
     * @return ideal inventory for firemaking
     */
    public static IdealInventory forFiremaking(int logItemId) {
        return IdealInventory.builder()
                .depositInventoryFirst(true)
                .requiredItem(InventorySlotSpec.forItem(ItemID.TINDERBOX))
                .fillRestWithItemId(logItemId)
                .build();
    }

    /**
     * Create ideal inventory for fishing.
     *
     * @param fishingType type of fishing determining equipment needs
     * @param powerFish if true, empty inventory for power-fishing
     * @return ideal inventory for fishing
     */
    public static IdealInventory forFishing(FishingType fishingType, boolean powerFish) {
        IdealInventory.IdealInventoryBuilder builder = IdealInventory.builder()
                .depositInventoryFirst(powerFish);

        // Add appropriate fishing equipment
        switch (fishingType) {
            case SMALL_NET:
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.SMALL_FISHING_NET));
                break;
            case FLY_FISHING:
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.FLY_FISHING_ROD));
                builder.requiredItem(InventorySlotSpec.forItems(ItemID.FEATHER, 1000)
                        .toBuilder().optional(false).build());
                break;
            case BAIT:
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.FISHING_ROD));
                builder.requiredItem(InventorySlotSpec.forItems(ItemID.FISHING_BAIT, 1000)
                        .toBuilder().optional(false).build());
                break;
            case HARPOON:
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.HARPOON,
                        EquipPreference.PREFER_INVENTORY));
                break;
            case LOBSTER_POT:
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.LOBSTER_POT));
                break;
            case BARBARIAN_ROD:
                builder.requiredItem(InventorySlotSpec.forItem(ItemID.BARBARIAN_ROD));
                builder.requiredItem(InventorySlotSpec.forItems(ItemID.FEATHER, 1000)
                        .toBuilder().optional(false).build());
                break;
        }

        return builder.build();
    }

    /**
     * Create ideal inventory for processing (fletching, cooking, etc.).
     *
     * @param toolItemId the tool to use (knife, hammer, etc.), or -1 if none
     * @param materialItemId the material to process
     * @return ideal inventory for processing
     */
    public static IdealInventory forProcessing(int toolItemId, int materialItemId) {
        IdealInventory.IdealInventoryBuilder builder = IdealInventory.builder()
                .depositInventoryFirst(true);

        if (toolItemId > 0) {
            builder.requiredItem(InventorySlotSpec.forItem(toolItemId));
        }

        if (materialItemId > 0) {
            builder.fillRestWithItemId(materialItemId);
        }

        return builder.build();
    }

    // ========================================================================
    // Combat Factories
    // ========================================================================

    /**
     * Create ideal inventory for combat.
     *
     * @param weaponItemId weapon to equip
     * @param foodItemId food to bring
     * @param foodCount minimum food count
     * @return ideal inventory for combat
     */
    public static IdealInventory forCombat(int weaponItemId, int foodItemId, int foodCount) {
        return IdealInventory.builder()
                .depositInventoryFirst(true)
                .requiredItem(InventorySlotSpec.forItem(weaponItemId, EquipPreference.MUST_EQUIP))
                .requiredItem(InventorySlotSpec.forItems(foodItemId, foodCount))
                .fillRestWithItemId(foodItemId)
                .build();
    }

    /**
     * Create ideal inventory for combat with potions.
     *
     * @param weaponItemId weapon to equip
     * @param foodItemId food to bring
     * @param foodCount minimum food count
     * @param potionItemId potion to bring
     * @param potionCount number of potions
     * @return ideal inventory for combat with potions
     */
    public static IdealInventory forCombatWithPotions(
            int weaponItemId,
            int foodItemId,
            int foodCount,
            int potionItemId,
            int potionCount) {

        return IdealInventory.builder()
                .depositInventoryFirst(true)
                .requiredItem(InventorySlotSpec.forItem(weaponItemId, EquipPreference.MUST_EQUIP))
                .requiredItem(InventorySlotSpec.forItems(potionItemId, potionCount))
                .requiredItem(InventorySlotSpec.forItems(foodItemId, foodCount))
                .fillRestWithItemId(foodItemId)
                .build();
    }

    /**
     * Create ideal inventory for ranged combat.
     *
     * @param weaponItemId ranged weapon
     * @param ammoItemId ammunition
     * @param ammoCount number of ammo
     * @param foodItemId food
     * @param foodCount food amount
     * @return ideal inventory for ranged combat
     */
    public static IdealInventory forRangedCombat(
            int weaponItemId,
            int ammoItemId,
            int ammoCount,
            int foodItemId,
            int foodCount) {

        return IdealInventory.builder()
                .depositInventoryFirst(true)
                .requiredItem(InventorySlotSpec.forItem(weaponItemId, EquipPreference.MUST_EQUIP))
                .requiredItem(InventorySlotSpec.builder()
                        .itemId(ammoItemId)
                        .quantity(ammoCount)
                        .equipPreference(EquipPreference.MUST_EQUIP) // Ammo goes in ammo slot
                        .build())
                .requiredItem(InventorySlotSpec.forItems(foodItemId, foodCount))
                .fillRestWithItemId(foodItemId)
                .build();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if any items in list1 are present in list2.
     */
    private static boolean hasAnyMatch(List<Integer> list1, List<Integer> list2) {
        for (int item : list1) {
            if (list2.contains(item)) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // Supporting Types
    // ========================================================================

    /**
     * Types of fishing for equipment determination.
     */
    public enum FishingType {
        /** Small net fishing (shrimp, anchovies) */
        SMALL_NET,
        /** Fly fishing (trout, salmon) */
        FLY_FISHING,
        /** Bait fishing (sardine, herring, pike) */
        BAIT,
        /** Harpoon fishing (tuna, swordfish, shark) */
        HARPOON,
        /** Lobster pot fishing */
        LOBSTER_POT,
        /** Barbarian rod fishing */
        BARBARIAN_ROD
    }
}

