package com.rocinante.state;

import lombok.Value;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;

import java.util.*;

/**
 * Immutable snapshot of equipment state as specified in REQUIREMENTS.md Section 6.2.3.
 *
 * This class represents a point-in-time snapshot of the player's equipped items,
 * providing helper methods for slot queries and gear set comparisons.
 * Instances are created by {@link com.rocinante.core.GameStateService} when equipment changes.
 *
 * All fields are immutable to ensure thread-safety and prevent accidental modification.
 */
@Value
public class EquipmentState {

    /**
     * Total number of equipment slots.
     */
    public static final int EQUIPMENT_SIZE = 14;

    // Equipment slot indices (matching EquipmentInventorySlot ordinals)
    public static final int SLOT_HEAD = 0;
    public static final int SLOT_CAPE = 1;
    public static final int SLOT_AMULET = 2;
    public static final int SLOT_WEAPON = 3;
    public static final int SLOT_BODY = 4;
    public static final int SLOT_SHIELD = 5;
    public static final int SLOT_LEGS = 7;
    public static final int SLOT_GLOVES = 9;
    public static final int SLOT_BOOTS = 10;
    public static final int SLOT_RING = 12;
    public static final int SLOT_AMMO = 13;

    /**
     * Empty equipment state.
     */
    public static final EquipmentState EMPTY = new EquipmentState(new HashMap<>());

    /**
     * Equipment items mapped by slot index.
     * Unmodifiable map to ensure immutability.
     */
    Map<Integer, Item> equipment;

    /**
     * Create an EquipmentState from a map of equipment (integer keys).
     * The map is defensively copied.
     *
     * @param equipment the equipment map (slot index -> item)
     */
    public EquipmentState(Map<Integer, Item> equipment) {
        if (equipment == null) {
            this.equipment = Collections.emptyMap();
        } else {
            // Defensive copy
            this.equipment = Collections.unmodifiableMap(new HashMap<>(equipment));
        }
    }

    /**
     * Create an EquipmentState from an array of items.
     * Array indices correspond to equipment slot indices.
     *
     * @param items the equipment items array
     * @return new EquipmentState
     */
    public static EquipmentState fromArray(Item[] items) {
        Map<Integer, Item> map = new HashMap<>();
        if (items != null) {
            for (int i = 0; i < Math.min(items.length, EQUIPMENT_SIZE); i++) {
                if (items[i] != null && items[i].getId() != -1) {
                    map.put(i, items[i]);
                }
            }
        }
        return new EquipmentState(map);
    }

    // ========================================================================
    // Slot Query Methods (by slot index)
    // ========================================================================

    /**
     * Get the item equipped in a specific slot.
     *
     * @param slotIndex the equipment slot index
     * @return Optional containing the item, or empty if slot is empty
     */
    public Optional<Item> getEquippedItem(int slotIndex) {
        return Optional.ofNullable(equipment.get(slotIndex));
    }

    /**
     * Get the item equipped in a specific slot (using RuneLite enum).
     *
     * @param slot the equipment slot
     * @return Optional containing the item, or empty if slot is empty
     */
    public Optional<Item> getEquippedItem(EquipmentInventorySlot slot) {
        return getEquippedItem(slot.getSlotIdx());
    }

    /**
     * Check if an item is equipped in a specific slot.
     *
     * @param slotIndex the equipment slot index to check
     * @return true if something is equipped in that slot
     */
    public boolean hasEquippedSlot(int slotIndex) {
        return equipment.containsKey(slotIndex);
    }

    /**
     * Check if an item is equipped in a specific slot (using RuneLite enum).
     *
     * @param slot the equipment slot to check
     * @return true if something is equipped in that slot
     */
    public boolean hasEquippedSlot(EquipmentInventorySlot slot) {
        return hasEquippedSlot(slot.getSlotIdx());
    }

    /**
     * Check if a specific item ID is equipped anywhere.
     *
     * @param itemId the item ID to check for
     * @return true if the item is equipped
     */
    public boolean hasEquipped(int itemId) {
        return equipment.values().stream()
                .anyMatch(item -> item.getId() == itemId);
    }

    /**
     * Check if any of the specified items are equipped.
     *
     * @param itemIds the item IDs to check for
     * @return true if any of the items are equipped
     */
    public boolean hasAnyEquipped(int... itemIds) {
        Set<Integer> idSet = new HashSet<>();
        for (int id : itemIds) {
            idSet.add(id);
        }
        return equipment.values().stream()
                .anyMatch(item -> idSet.contains(item.getId()));
    }

    /**
     * Check if all of the specified items are equipped.
     *
     * @param itemIds the item IDs that must be equipped
     * @return true if all items are equipped
     */
    public boolean hasAllEquipped(int... itemIds) {
        Set<Integer> equippedIds = new HashSet<>();
        for (Item item : equipment.values()) {
            equippedIds.add(item.getId());
        }
        for (int id : itemIds) {
            if (!equippedIds.contains(id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the slot index where a specific item is equipped.
     *
     * @param itemId the item ID to find
     * @return Optional containing the slot index, or empty if not equipped
     */
    public Optional<Integer> getSlotOf(int itemId) {
        for (Map.Entry<Integer, Item> entry : equipment.entrySet()) {
            if (entry.getValue().getId() == itemId) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Get all equipped item IDs as a set.
     *
     * @return set of equipped item IDs
     */
    public Set<Integer> getEquippedIds() {
        Set<Integer> ids = new HashSet<>();
        for (Item item : equipment.values()) {
            ids.add(item.getId());
        }
        return ids;
    }

    // ========================================================================
    // Specific Slot Accessors
    // ========================================================================

    /**
     * Get the equipped weapon.
     *
     * @return Optional containing the weapon, or empty if no weapon equipped
     */
    public Optional<Item> getWeapon() {
        return getEquippedItem(SLOT_WEAPON);
    }

    /**
     * Get the weapon ID, or -1 if no weapon equipped.
     *
     * @return weapon item ID or -1
     */
    public int getWeaponId() {
        return getWeapon().map(Item::getId).orElse(-1);
    }

    /**
     * Get the equipped shield.
     *
     * @return Optional containing the shield, or empty if no shield equipped
     */
    public Optional<Item> getShield() {
        return getEquippedItem(SLOT_SHIELD);
    }

    /**
     * Get the equipped helmet.
     *
     * @return Optional containing the helmet, or empty if no helmet equipped
     */
    public Optional<Item> getHelmet() {
        return getEquippedItem(SLOT_HEAD);
    }

    /**
     * Get the equipped body armor.
     *
     * @return Optional containing the body, or empty if no body equipped
     */
    public Optional<Item> getBody() {
        return getEquippedItem(SLOT_BODY);
    }

    /**
     * Get the equipped leg armor.
     *
     * @return Optional containing the legs, or empty if no legs equipped
     */
    public Optional<Item> getLegs() {
        return getEquippedItem(SLOT_LEGS);
    }

    /**
     * Get the equipped cape.
     *
     * @return Optional containing the cape, or empty if no cape equipped
     */
    public Optional<Item> getCape() {
        return getEquippedItem(SLOT_CAPE);
    }

    /**
     * Get the equipped amulet.
     *
     * @return Optional containing the amulet, or empty if no amulet equipped
     */
    public Optional<Item> getAmulet() {
        return getEquippedItem(SLOT_AMULET);
    }

    /**
     * Get the equipped gloves.
     *
     * @return Optional containing the gloves, or empty if no gloves equipped
     */
    public Optional<Item> getGloves() {
        return getEquippedItem(SLOT_GLOVES);
    }

    /**
     * Get the equipped boots.
     *
     * @return Optional containing the boots, or empty if no boots equipped
     */
    public Optional<Item> getBoots() {
        return getEquippedItem(SLOT_BOOTS);
    }

    /**
     * Get the equipped ring.
     *
     * @return Optional containing the ring, or empty if no ring equipped
     */
    public Optional<Item> getRing() {
        return getEquippedItem(SLOT_RING);
    }

    /**
     * Get the equipped ammo.
     *
     * @return Optional containing the ammo, or empty if no ammo equipped
     */
    public Optional<Item> getAmmo() {
        return getEquippedItem(SLOT_AMMO);
    }

    /**
     * Get the ammo count (quantity of ammo equipped).
     *
     * @return ammo quantity, or 0 if no ammo equipped
     */
    public int getAmmoCount() {
        return getAmmo().map(Item::getQuantity).orElse(0);
    }

    /**
     * Get the ammo ID, or -1 if no ammo equipped.
     *
     * @return ammo item ID or -1
     */
    public int getAmmoId() {
        return getAmmo().map(Item::getId).orElse(-1);
    }

    // ========================================================================
    // Gear Set Comparison
    // ========================================================================

    /**
     * Check if the current equipment matches a gear set (all expected items equipped).
     *
     * @param expectedIds the set of item IDs that should be equipped
     * @return true if all expected items are currently equipped
     */
    public boolean matchesGearSet(Set<Integer> expectedIds) {
        Set<Integer> currentIds = getEquippedIds();
        return currentIds.containsAll(expectedIds);
    }

    /**
     * Check if the current equipment exactly matches a gear set.
     * No extra items and no missing items.
     *
     * @param expectedIds the set of item IDs that should be equipped
     * @return true if equipment exactly matches the expected set
     */
    public boolean exactlyMatchesGearSet(Set<Integer> expectedIds) {
        return getEquippedIds().equals(expectedIds);
    }

    /**
     * Get the items missing from a gear set.
     *
     * @param expectedIds the set of item IDs that should be equipped
     * @return set of item IDs that are expected but not equipped
     */
    public Set<Integer> getMissingFromGearSet(Set<Integer> expectedIds) {
        Set<Integer> missing = new HashSet<>(expectedIds);
        missing.removeAll(getEquippedIds());
        return missing;
    }

    /**
     * Get the extra items not in a gear set.
     *
     * @param expectedIds the set of item IDs that should be equipped
     * @return set of item IDs that are equipped but not expected
     */
    public Set<Integer> getExtraFromGearSet(Set<Integer> expectedIds) {
        Set<Integer> extra = getEquippedIds();
        extra.removeAll(expectedIds);
        return extra;
    }

    // ========================================================================
    // Safety Equipment Checks (for HCIM)
    // ========================================================================

    /**
     * Ring of Life item IDs.
     */
    private static final int RING_OF_LIFE_ID = 2570;
    private static final int RING_OF_LIFE_I_ID = 21129;

    /**
     * Check if a Ring of Life is equipped.
     * Important for HCIM safety per REQUIREMENTS.md Section 6.2.8.
     *
     * @return true if Ring of Life is equipped
     */
    public boolean hasRingOfLife() {
        return getRing()
                .map(item -> item.getId() == RING_OF_LIFE_ID || item.getId() == RING_OF_LIFE_I_ID)
                .orElse(false);
    }

    /**
     * Phoenix necklace item ID.
     */
    private static final int PHOENIX_NECKLACE_ID = 11090;

    /**
     * Check if a Phoenix Necklace is equipped.
     *
     * @return true if Phoenix Necklace is equipped
     */
    public boolean hasPhoenixNecklace() {
        return getAmulet()
                .map(item -> item.getId() == PHOENIX_NECKLACE_ID)
                .orElse(false);
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get the count of equipped items.
     *
     * @return number of equipment slots filled
     */
    public int getEquippedCount() {
        return equipment.size();
    }

    /**
     * Check if any equipment is worn.
     *
     * @return true if at least one item is equipped
     */
    public boolean hasAnyEquipment() {
        return !equipment.isEmpty();
    }

    /**
     * Check if the player is fully unequipped.
     *
     * @return true if no items are equipped
     */
    public boolean isEmpty() {
        return equipment.isEmpty();
    }

    /**
     * Get all equipment as an unmodifiable map.
     *
     * @return unmodifiable map of equipment
     */
    public Map<Integer, Item> getAllEquipment() {
        return equipment; // Already unmodifiable from constructor
    }

    /**
     * Get a summary string of equipped items for logging.
     *
     * @return summary string
     */
    public String getSummary() {
        if (equipment.isEmpty()) {
            return "No equipment";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Item> entry : equipment.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(getSlotName(entry.getKey())).append("=").append(entry.getValue().getId());
        }
        return sb.toString();
    }

    /**
     * Get slot name from slot index for logging.
     */
    private String getSlotName(int slotIndex) {
        switch (slotIndex) {
            case SLOT_HEAD: return "HEAD";
            case SLOT_CAPE: return "CAPE";
            case SLOT_AMULET: return "AMULET";
            case SLOT_WEAPON: return "WEAPON";
            case SLOT_BODY: return "BODY";
            case SLOT_SHIELD: return "SHIELD";
            case SLOT_LEGS: return "LEGS";
            case SLOT_GLOVES: return "GLOVES";
            case SLOT_BOOTS: return "BOOTS";
            case SLOT_RING: return "RING";
            case SLOT_AMMO: return "AMMO";
            default: return "SLOT_" + slotIndex;
        }
    }
}
