package com.rocinante.quest.bridge;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data class capturing item requirement information extracted from Quest Helper.
 *
 * <p>This class represents a single item requirement for a quest, including:
 * <ul>
 *   <li>Primary and alternate item IDs</li>
 *   <li>Required quantity</li>
 *   <li>Whether it can be obtained during the quest</li>
 *   <li>Whether it must be equipped</li>
 *   <li>Source hints for procurement</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * ItemRequirementInfo bucket = ItemRequirementInfo.builder()
 *     .itemId(ItemID.BUCKET_EMPTY)
 *     .name("Bucket")
 *     .quantity(1)
 *     .obtainableDuringQuest(false)
 *     .shopNpcId(NpcID.SHOP_KEEPER)
 *     .build();
 * }</pre>
 */
@Getter
@Builder
@ToString
public class ItemRequirementInfo {

    /**
     * The primary item ID.
     */
    private final int itemId;

    /**
     * Display name for the item.
     */
    private final String name;

    /**
     * Required quantity.
     */
    @Builder.Default
    private final int quantity = 1;

    /**
     * Alternate item IDs that also satisfy this requirement.
     */
    @Builder.Default
    private final List<Integer> alternateIds = new ArrayList<>();

    /**
     * Whether this item can be obtained during the quest.
     * If true, the planner should NOT try to acquire it beforehand.
     */
    @Builder.Default
    private final boolean obtainableDuringQuest = false;

    /**
     * Whether this item must be equipped (not just in inventory).
     */
    @Builder.Default
    private final boolean mustBeEquipped = false;

    /**
     * Whether this item is consumed during the quest.
     */
    @Builder.Default
    private final boolean consumed = true;

    /**
     * NPC ID of a shop that sells this item (if known).
     * -1 if unknown or not available from shops.
     */
    @Builder.Default
    private final int shopNpcId = -1;

    /**
     * Object ID of a spawn location for this item (if it spawns on ground).
     * -1 if unknown or doesn't spawn.
     */
    @Builder.Default
    private final int groundSpawnObjectId = -1;

    /**
     * Tooltip or additional info from Quest Helper.
     */
    private final String tooltip;

    /**
     * Whether this is a "recommended" item rather than required.
     */
    @Builder.Default
    private final boolean recommended = false;

    /**
     * Get all valid item IDs (primary + alternates).
     *
     * @return list of all item IDs that satisfy this requirement
     */
    public List<Integer> getAllIds() {
        List<Integer> allIds = new ArrayList<>();
        allIds.add(itemId);
        allIds.addAll(alternateIds);
        return Collections.unmodifiableList(allIds);
    }

    /**
     * Check if a specific item ID satisfies this requirement.
     *
     * @param id the item ID to check
     * @return true if the ID matches primary or any alternate
     */
    public boolean matchesId(int id) {
        return itemId == id || alternateIds.contains(id);
    }

    /**
     * Check if this item needs to be acquired before starting the quest.
     *
     * @return true if item must be acquired pre-quest
     */
    public boolean needsPreQuestAcquisition() {
        return !obtainableDuringQuest && !recommended;
    }

    /**
     * Check if this item can be purchased from a known shop.
     *
     * @return true if shopNpcId is set
     */
    public boolean hasKnownShop() {
        return shopNpcId > 0;
    }

    /**
     * Check if this item has a known ground spawn.
     *
     * @return true if groundSpawnObjectId is set
     */
    public boolean hasGroundSpawn() {
        return groundSpawnObjectId > 0;
    }

    /**
     * Create a simple requirement for an item by ID.
     *
     * @param itemId the item ID
     * @param name   the item name
     * @return a basic ItemRequirementInfo
     */
    public static ItemRequirementInfo simple(int itemId, String name) {
        return ItemRequirementInfo.builder()
                .itemId(itemId)
                .name(name)
                .build();
    }

    /**
     * Create a requirement for an item obtainable during quest.
     *
     * @param itemId the item ID
     * @param name   the item name
     * @return an ItemRequirementInfo marked as quest-obtainable
     */
    public static ItemRequirementInfo questObtainable(int itemId, String name) {
        return ItemRequirementInfo.builder()
                .itemId(itemId)
                .name(name)
                .obtainableDuringQuest(true)
                .build();
    }

    /**
     * Create a requirement for an item available from a shop.
     *
     * @param itemId    the item ID
     * @param name      the item name
     * @param shopNpcId the NPC ID of the shopkeeper
     * @return an ItemRequirementInfo with shop info
     */
    public static ItemRequirementInfo fromShop(int itemId, String name, int shopNpcId) {
        return ItemRequirementInfo.builder()
                .itemId(itemId)
                .name(name)
                .shopNpcId(shopNpcId)
                .build();
    }
}

