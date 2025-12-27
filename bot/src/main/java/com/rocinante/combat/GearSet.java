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
                for (int weaponId : WeaponCategories.RANGED_WEAPONS) {
                    if (inventory.hasItem(weaponId)) {
                        builder.weapon(weaponId);
                        found = true;
                        break;
                    }
                }
                // Look for ammo
                for (int ammoId : WeaponCategories.AMMO_IDS) {
                    if (inventory.hasItem(ammoId)) {
                        builder.ammo(ammoId);
                        break;
                    }
                }
                break;

            case MAGIC:
                // Look for magic weapon in inventory
                for (int weaponId : WeaponCategories.MAGIC_WEAPONS) {
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
            if (WeaponCategories.isRangedWeapon(itemId)) {
                builder.weapon(itemId);
                detectedStyle = AttackStyle.RANGED;
            } else if (WeaponCategories.isMagicWeapon(itemId)) {
                builder.weapon(itemId);
                detectedStyle = AttackStyle.MAGIC;
            } else if (WeaponCategories.isAmmo(itemId)) {
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

