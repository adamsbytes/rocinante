package com.rocinante.inventory;

import com.rocinante.progression.MethodType;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.state.PlayerState;
import com.rocinante.util.ItemCollections;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * Add tool requirements based on training method configuration.
     *
     * <p>This method scans the method's requiredItemIds list and identifies tool collections
     * (axes, pickaxes, fishing equipment, etc.) to use level-based best-item selection.
     * Items that don't match any known tool collection are added as specific items.
     *
     * <p>Key features:
     * <ul>
     *   <li>Handles compound methods (e.g., chop+fletch needs axe AND knife)</li>
     *   <li>Uses collection-based selection for levelable tools (best axe for WC level)</li>
     *   <li>Falls back to skill-based inference if no requiredItemIds specified</li>
     *   <li>Handles consumables (feathers, bait) with appropriate quantities</li>
     * </ul>
     */
    private static void addToolsForSkill(
            IdealInventory.IdealInventoryBuilder builder,
            TrainingMethod method,
            PlayerState player) {

        Skill skill = method.getSkill();
        List<Integer> requiredItems = method.getRequiredItemIds();

        // If method has explicit required items, process ALL of them
        if (requiredItems != null && !requiredItems.isEmpty()) {
            addToolsFromRequiredItems(builder, requiredItems);
            return;
        }

        // No explicit required items - infer from skill
        addToolsFromSkill(builder, skill);
    }

    /**
     * Process explicit requiredItemIds from method config.
     * Identifies tool collections and adds appropriate specs for each.
     */
    private static void addToolsFromRequiredItems(
            IdealInventory.IdealInventoryBuilder builder,
            List<Integer> requiredItems) {

        // Track which items have been handled via collections
        Set<Integer> handledItems = new HashSet<>();

        // Check for axes (woodcutting tools) - equippable, level-based selection
        if (hasAnyMatch(requiredItems, ItemCollections.AXES)) {
            builder.requiredItem(InventorySlotSpec.forToolCollection(
                    ItemCollections.AXES,
                    ItemCollections.AXE_LEVELS,
                    Skill.WOODCUTTING,
                    EquipPreference.PREFER_EQUIP
            ));
            handledItems.addAll(ItemCollections.AXES);
        }

        // Check for pickaxes (mining tools) - equippable, level-based selection
        if (hasAnyMatch(requiredItems, ItemCollections.PICKAXES)) {
            builder.requiredItem(InventorySlotSpec.forToolCollection(
                    ItemCollections.PICKAXES,
                    ItemCollections.PICKAXE_LEVELS,
                    Skill.MINING,
                    EquipPreference.PREFER_EQUIP
            ));
            handledItems.addAll(ItemCollections.PICKAXES);
        }

        // Check for harpoons (fishing tools) - some equippable, level-based selection
        if (hasAnyMatch(requiredItems, ItemCollections.HARPOONS)) {
            builder.requiredItem(InventorySlotSpec.forToolCollection(
                    ItemCollections.HARPOONS,
                    ItemCollections.HARPOON_LEVELS,
                    Skill.FISHING,
                    EquipPreference.PREFER_EQUIP  // Dragon/infernal/crystal harpoons can be equipped
            ));
            handledItems.addAll(ItemCollections.HARPOONS);
        }

        // Check for tinderboxes (firemaking)
        if (hasAnyMatch(requiredItems, ItemCollections.TINDERBOXES)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.TINDERBOXES, 1));
            handledItems.addAll(ItemCollections.TINDERBOXES);
        }

        // Check for knives (fletching, crafting)
        if (hasAnyMatch(requiredItems, ItemCollections.KNIVES)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.KNIVES, 1));
            handledItems.addAll(ItemCollections.KNIVES);
        }

        // Check for hammers (smithing, construction)
        if (hasAnyMatch(requiredItems, ItemCollections.HAMMERS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.HAMMERS, 1));
            handledItems.addAll(ItemCollections.HAMMERS);
        }

        // Check for chisels (crafting)
        if (hasAnyMatch(requiredItems, ItemCollections.CHISELS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.CHISELS, 1));
            handledItems.addAll(ItemCollections.CHISELS);
        }

        // Check for fishing rods (bait fishing)
        if (hasAnyMatch(requiredItems, ItemCollections.FISHING_RODS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.FISHING_RODS, 1));
            handledItems.addAll(ItemCollections.FISHING_RODS);
        }

        // Check for fly fishing rods
        if (hasAnyMatch(requiredItems, ItemCollections.FLY_FISHING_RODS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.FLY_FISHING_RODS, 1));
            handledItems.addAll(ItemCollections.FLY_FISHING_RODS);
        }

        // Check for barbarian rods
        if (hasAnyMatch(requiredItems, ItemCollections.BARBARIAN_RODS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.BARBARIAN_RODS, 1));
            handledItems.addAll(ItemCollections.BARBARIAN_RODS);
        }

        // Check for small fishing nets
        if (hasAnyMatch(requiredItems, ItemCollections.SMALL_FISHING_NETS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.SMALL_FISHING_NETS, 1));
            handledItems.addAll(ItemCollections.SMALL_FISHING_NETS);
        }

        // Check for big fishing nets
        if (hasAnyMatch(requiredItems, ItemCollections.BIG_FISHING_NETS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.BIG_FISHING_NETS, 1));
            handledItems.addAll(ItemCollections.BIG_FISHING_NETS);
        }

        // Check for lobster pots
        if (hasAnyMatch(requiredItems, ItemCollections.LOBSTER_POTS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.LOBSTER_POTS, 1));
            handledItems.addAll(ItemCollections.LOBSTER_POTS);
        }

        // Check for fishing bait (consumable - need quantity)
        if (hasAnyMatch(requiredItems, ItemCollections.FISHING_BAIT)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.FISHING_BAIT, 1000)
                    .toBuilder().optional(false).build());
            handledItems.addAll(ItemCollections.FISHING_BAIT);
        }

        // Check for feathers (consumable - need quantity)
        if (hasAnyMatch(requiredItems, ItemCollections.FEATHERS)) {
            builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                    ItemCollections.FEATHERS, 1000)
                    .toBuilder().optional(false).build());
            handledItems.addAll(ItemCollections.FEATHERS);
        }

        // Add any remaining items that weren't matched to a collection
        for (int itemId : requiredItems) {
            if (!handledItems.contains(itemId)) {
                builder.requiredItem(InventorySlotSpec.forItem(itemId));
            }
        }
    }

    /**
     * Infer tool requirements from skill type when no explicit requiredItemIds.
     */
    private static void addToolsFromSkill(
            IdealInventory.IdealInventoryBuilder builder,
            Skill skill) {

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
                builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                        ItemCollections.TINDERBOXES, 1));
                break;

            case FLETCHING:
                builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                        ItemCollections.KNIVES, 1));
                break;

            case CRAFTING:
                // Crafting can need various tools - knife is common default
                builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                        ItemCollections.KNIVES, 1));
                break;

            case SMITHING:
                builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                        ItemCollections.HAMMERS, 1));
                break;

            case FISHING:
                // Without explicit equipment, can't determine type
                // Caller should use forFishing() with explicit type
                log.warn("Fishing skill without explicit equipment - use forFishing() factory");
                break;

            case COOKING:
                // Cooking typically doesn't need tools (fire/range interaction)
                break;

            case PRAYER:
                // Prayer typically doesn't need tools (altar interaction)
                break;

            case AGILITY:
                // Agility doesn't need tools
                break;

            case THIEVING:
                // Thieving typically doesn't need tools (NPC interaction)
                break;

            case CONSTRUCTION:
                builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                        ItemCollections.HAMMERS, 1));
                builder.requiredItem(InventorySlotSpec.forAnyFromCollection(
                        ItemCollections.SAWS, 1));
                break;

            default:
                // Other skills don't have standard tool requirements
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

