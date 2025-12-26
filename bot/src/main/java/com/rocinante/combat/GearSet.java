package com.rocinante.combat;

import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import net.runelite.api.EquipmentInventorySlot;

import java.util.*;

/**
 * Defines a set of equipment for a specific purpose.
 *
 * Per REQUIREMENTS.md Section 10.3.1:
 * <ul>
 *   <li>Define gear sets by name with item IDs</li>
 *   <li>Each set: up to 11 equipment slots</li>
 *   <li>Partial sets: only specified slots switch</li>
 *   <li>Pre-validation: verify all items present before switching</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Full melee gear set
 * GearSet meleeSet = GearSet.builder()
 *     .name("Melee")
 *     .attackStyle(AttackStyle.MELEE)
 *     .weapon(ItemID.ABYSSAL_WHIP)
 *     .shield(ItemID.DRAGON_DEFENDER)
 *     .helmet(ItemID.HELM_OF_NEITIZNOT)
 *     .body(ItemID.FIGHTER_TORSO)
 *     .legs(ItemID.OBSIDIAN_PLATELEGS)
 *     .build();
 *
 * // Partial set - just weapon switch
 * GearSet specWeapon = GearSet.builder()
 *     .name("DDS Spec")
 *     .weapon(ItemID.DRAGON_DAGGER_P_PLUS_PLUS)
 *     .build();
 *
 * // Auto-detect from inventory
 * GearSet rangedFromInventory = GearSet.autoDetect(AttackStyle.RANGED, inventory, equipment);
 * }</pre>
 */
@Value
@Builder
public class GearSet {

    /**
     * Predefined gear set for "no equipment" (unequip all).
     */
    public static final GearSet EMPTY = GearSet.builder()
            .name("Empty")
            .build();

    /**
     * Name of this gear set for logging/identification.
     */
    @Builder.Default
    String name = "Unnamed";

    /**
     * The attack style this gear set is for.
     */
    AttackStyle attackStyle;

    /**
     * Equipment items by slot index.
     * Only slots with items defined will be switched.
     */
    @Singular("slot")
    Map<Integer, Integer> slots;

    // ========================================================================
    // Builder Helper Methods (slot-specific)
    // ========================================================================

    public static class GearSetBuilder {
        
        public GearSetBuilder weapon(int itemId) {
            return slot(EquipmentState.SLOT_WEAPON, itemId);
        }

        public GearSetBuilder shield(int itemId) {
            return slot(EquipmentState.SLOT_SHIELD, itemId);
        }

        public GearSetBuilder helmet(int itemId) {
            return slot(EquipmentState.SLOT_HEAD, itemId);
        }

        public GearSetBuilder body(int itemId) {
            return slot(EquipmentState.SLOT_BODY, itemId);
        }

        public GearSetBuilder legs(int itemId) {
            return slot(EquipmentState.SLOT_LEGS, itemId);
        }

        public GearSetBuilder cape(int itemId) {
            return slot(EquipmentState.SLOT_CAPE, itemId);
        }

        public GearSetBuilder amulet(int itemId) {
            return slot(EquipmentState.SLOT_AMULET, itemId);
        }

        public GearSetBuilder gloves(int itemId) {
            return slot(EquipmentState.SLOT_GLOVES, itemId);
        }

        public GearSetBuilder boots(int itemId) {
            return slot(EquipmentState.SLOT_BOOTS, itemId);
        }

        public GearSetBuilder ring(int itemId) {
            return slot(EquipmentState.SLOT_RING, itemId);
        }

        public GearSetBuilder ammo(int itemId) {
            return slot(EquipmentState.SLOT_AMMO, itemId);
        }
    }

    // ========================================================================
    // Auto-Detection
    // ========================================================================

    /**
     * Common ranged weapon IDs (bows, crossbows, thrown weapons).
     * Note: IDs must be unique for Set.of() - no duplicates allowed.
     */
    private static final Set<Integer> RANGED_WEAPONS = Set.of(
            // Shortbows
            841, 843, 845, 847, 849, 851, 853,
            // Longbows
            839,
            // Crossbows
            767, 837, 9174, 9176, 9177, 9179, 9181, 9183, 9185,
            // Thrown weapons (knives, darts, javelins)
            800, 802, 804, 806, 807, 809, 811, 813, 863, 864, 865, 866, 867, 868, 869,
            // Special ranged weapons (blowpipe, twisted bow, etc.)
            11785, 12926, 19481, 20997, 21902, 22550, 23987
    );

    /**
     * Common magic weapon IDs (staffs, wands).
     * Note: IDs must be unique for Set.of() - no duplicates allowed.
     */
    private static final Set<Integer> MAGIC_WEAPONS = Set.of(
            // Basic staves (staff, air staff, water staff, etc.)
            1379, 1381, 1383, 1385, 1387, 1389, 1391, 1393, 1395, 1397, 1399, 1401, 1403, 1405,
            // Special staves (ancient staff, kodai wand, etc.)
            4675, 4710, 6563, 11791, 12899, 21006, 22296, 22323
    );

    /**
     * Common ammo IDs (arrows, bolts, runes for blowpipe).
     */
    private static final Set<Integer> AMMO_IDS = Set.of(
            // Bronze to Dragon arrows
            882, 884, 886, 888, 890, 892,
            // Bronze to Dragon bolts
            877, 9140, 9141, 9142, 9143, 9144, 9145,
            // Special arrows/bolts
            4740, 9236, 9237, 9238, 9239, 9240, 9241, 9242, 9243, 9244, 9245,
            // Dart tips
            806, 807, 809, 811, 813
    );

    /**
     * Auto-detect gear from inventory for a given attack style.
     * Finds appropriate weapons and ammo in inventory/equipment.
     *
     * @param style      the desired attack style
     * @param inventory  current inventory
     * @param equipment  current equipment
     * @return GearSet with detected items, or EMPTY if nothing found
     */
    public static GearSet autoDetect(AttackStyle style, InventoryState inventory, EquipmentState equipment) {
        GearSetBuilder builder = GearSet.builder()
                .name("Auto-" + style.name())
                .attackStyle(style);

        boolean found = false;

        switch (style) {
            case RANGED:
                // Look for ranged weapon in inventory
                for (int weaponId : RANGED_WEAPONS) {
                    if (inventory.hasItem(weaponId)) {
                        builder.weapon(weaponId);
                        found = true;
                        break;
                    }
                }
                // Look for ammo
                for (int ammoId : AMMO_IDS) {
                    if (inventory.hasItem(ammoId)) {
                        builder.ammo(ammoId);
                        break;
                    }
                }
                break;

            case MAGIC:
                // Look for magic weapon in inventory
                for (int weaponId : MAGIC_WEAPONS) {
                    if (inventory.hasItem(weaponId)) {
                        builder.weapon(weaponId);
                        found = true;
                        break;
                    }
                }
                break;

            case MELEE:
            default:
                // For melee, look for any non-ranged, non-magic weapon
                // This is a fallback - usually melee is explicitly configured
                found = true; // Melee doesn't require switching typically
                break;
        }

        return found ? builder.build() : EMPTY;
    }

    /**
     * Create a gear set for specific items (auto-detects attack style).
     *
     * @param itemIds the item IDs to include in the set
     * @return GearSet with the specified items
     */
    public static GearSet forItems(int... itemIds) {
        GearSetBuilder builder = GearSet.builder().name("Custom");
        AttackStyle detectedStyle = AttackStyle.MELEE;

        for (int itemId : itemIds) {
            // Detect slot based on item (would need item definitions in real implementation)
            // For now, assume weapon slot for simplicity
            if (RANGED_WEAPONS.contains(itemId)) {
                builder.weapon(itemId);
                detectedStyle = AttackStyle.RANGED;
            } else if (MAGIC_WEAPONS.contains(itemId)) {
                builder.weapon(itemId);
                detectedStyle = AttackStyle.MAGIC;
            } else if (AMMO_IDS.contains(itemId)) {
                builder.ammo(itemId);
            }
            // Additional slot detection would go here
        }

        return builder.attackStyle(detectedStyle).build();
    }

    // ========================================================================
    // Query Methods
    // ========================================================================

    /**
     * Get all item IDs in this gear set.
     *
     * @return set of item IDs
     */
    public Set<Integer> getItemIds() {
        return new HashSet<>(slots.values());
    }

    /**
     * Get the weapon item ID, or -1 if no weapon specified.
     *
     * @return weapon ID or -1
     */
    public int getWeaponId() {
        return slots.getOrDefault(EquipmentState.SLOT_WEAPON, -1);
    }

    /**
     * Get the number of items in this gear set.
     *
     * @return item count
     */
    public int size() {
        return slots.size();
    }

    /**
     * Check if this is an empty gear set.
     *
     * @return true if no items defined
     */
    public boolean isEmpty() {
        return slots.isEmpty();
    }

    /**
     * Check if this gear set includes a weapon.
     *
     * @return true if weapon slot is defined
     */
    public boolean hasWeapon() {
        return slots.containsKey(EquipmentState.SLOT_WEAPON);
    }

    /**
     * Check if this gear set includes ammo.
     *
     * @return true if ammo slot is defined
     */
    public boolean hasAmmo() {
        return slots.containsKey(EquipmentState.SLOT_AMMO);
    }

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * Check if all items in this gear set are available (in inventory or already equipped).
     *
     * @param inventory current inventory
     * @param equipment current equipment
     * @return true if all items are available
     */
    public boolean isAvailable(InventoryState inventory, EquipmentState equipment) {
        for (Map.Entry<Integer, Integer> entry : slots.entrySet()) {
            int slotIndex = entry.getKey();
            int itemId = entry.getValue();

            // Check if already equipped in correct slot
            Optional<net.runelite.api.Item> equipped = equipment.getEquippedItem(slotIndex);
            if (equipped.isPresent() && equipped.get().getId() == itemId) {
                continue; // Already equipped
            }

            // Check if in inventory
            if (!inventory.hasItem(itemId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the items that need to be equipped (not already in correct slot).
     *
     * @param equipment current equipment
     * @return map of slot index -> item ID for items that need equipping
     */
    public Map<Integer, Integer> getItemsToEquip(EquipmentState equipment) {
        Map<Integer, Integer> toEquip = new LinkedHashMap<>();

        for (Map.Entry<Integer, Integer> entry : slots.entrySet()) {
            int slotIndex = entry.getKey();
            int itemId = entry.getValue();

            // Check if already equipped in correct slot
            Optional<net.runelite.api.Item> equipped = equipment.getEquippedItem(slotIndex);
            if (equipped.isEmpty() || equipped.get().getId() != itemId) {
                toEquip.put(slotIndex, itemId);
            }
        }

        return toEquip;
    }

    /**
     * Check if this gear set is currently fully equipped.
     *
     * @param equipment current equipment
     * @return true if all items are equipped in correct slots
     */
    public boolean isEquipped(EquipmentState equipment) {
        return getItemsToEquip(equipment).isEmpty();
    }

    /**
     * Get summary for logging.
     *
     * @return summary string
     */
    public String getSummary() {
        return String.format("GearSet[%s, %d items, style=%s]", 
                name, slots.size(), attackStyle);
    }
}

