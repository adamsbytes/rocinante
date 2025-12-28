package com.rocinante.slayer;

import com.rocinante.combat.GearSwitcher;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.ItemID;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Manages slayer-specific equipment requirements and finish-off items.
 *
 * Per REQUIREMENTS.md Section 11.3, handles:
 * <ul>
 *   <li>Protective equipment validation (earmuffs, nose peg, face mask, mirror shield)</li>
 *   <li>Slayer helmet equivalence (replaces all protective items)</li>
 *   <li>Finish-off item management (rock hammer, ice coolers, salt, fungicide)</li>
 *   <li>Weapon restriction validation (leaf-bladed for turoth/kurask)</li>
 *   <li>Broad ammo validation for turoth/kurask</li>
 *   <li>Auto-smash unlock checking</li>
 * </ul>
 *
 * Integrates with existing {@link GearSwitcher} for equipment changes.
 * 
 * All item IDs use RuneLite's {@link ItemID} constants for accuracy and maintainability.
 */
@Slf4j
@Singleton
public class SlayerEquipmentManager {

    // ========================================================================
    // Item ID Constants - Using RuneLite ItemID for accuracy
    // ========================================================================

    // Protective Equipment
    public static final int EARMUFFS = ItemID.EARMUFFS;
    public static final int NOSE_PEG = ItemID.NOSE_PEG;
    public static final int FACE_MASK = ItemID.FACEMASK;
    public static final int MIRROR_SHIELD = ItemID.MIRROR_SHIELD;
    public static final int VS_SHIELD = ItemID.VS_SHIELD;
    public static final int WITCHWOOD_ICON = ItemID.WITCHWOOD_ICON;

    // Slayer Helmets (provide all protection)
    public static final int SLAYER_HELMET = ItemID.SLAYER_HELMET;
    public static final int SLAYER_HELMET_I = ItemID.SLAYER_HELMET_I;
    public static final int BLACK_SLAYER_HELMET = ItemID.BLACK_SLAYER_HELMET;
    public static final int BLACK_SLAYER_HELMET_I = ItemID.BLACK_SLAYER_HELMET_I;
    public static final int GREEN_SLAYER_HELMET = ItemID.GREEN_SLAYER_HELMET;
    public static final int GREEN_SLAYER_HELMET_I = ItemID.GREEN_SLAYER_HELMET_I;
    public static final int RED_SLAYER_HELMET = ItemID.RED_SLAYER_HELMET;
    public static final int RED_SLAYER_HELMET_I = ItemID.RED_SLAYER_HELMET_I;
    public static final int PURPLE_SLAYER_HELMET = ItemID.PURPLE_SLAYER_HELMET;
    public static final int PURPLE_SLAYER_HELMET_I = ItemID.PURPLE_SLAYER_HELMET_I;
    public static final int TURQUOISE_SLAYER_HELMET = ItemID.TURQUOISE_SLAYER_HELMET;
    public static final int TURQUOISE_SLAYER_HELMET_I = ItemID.TURQUOISE_SLAYER_HELMET_I;
    public static final int HYDRA_SLAYER_HELMET = ItemID.HYDRA_SLAYER_HELMET;
    public static final int HYDRA_SLAYER_HELMET_I = ItemID.HYDRA_SLAYER_HELMET_I;
    public static final int TWISTED_SLAYER_HELMET = ItemID.TWISTED_SLAYER_HELMET;
    public static final int TWISTED_SLAYER_HELMET_I = ItemID.TWISTED_SLAYER_HELMET_I;
    public static final int TZTOK_SLAYER_HELMET = ItemID.TZTOK_SLAYER_HELMET;
    public static final int TZTOK_SLAYER_HELMET_I = ItemID.TZTOK_SLAYER_HELMET_I;
    public static final int VAMPYRIC_SLAYER_HELMET = ItemID.VAMPYRIC_SLAYER_HELMET;
    public static final int VAMPYRIC_SLAYER_HELMET_I = ItemID.VAMPYRIC_SLAYER_HELMET_I;
    public static final int TZKAL_SLAYER_HELMET = ItemID.TZKAL_SLAYER_HELMET;
    public static final int TZKAL_SLAYER_HELMET_I = ItemID.TZKAL_SLAYER_HELMET_I;
    public static final int ARAXYTE_SLAYER_HELMET = ItemID.ARAXYTE_SLAYER_HELMET;
    public static final int ARAXYTE_SLAYER_HELMET_I = ItemID.ARAXYTE_SLAYER_HELMET_I;

    // Finish-off Items
    public static final int ROCK_HAMMER = ItemID.ROCK_HAMMER;
    public static final int GRANITE_HAMMER = ItemID.GRANITE_HAMMER;
    public static final int BAG_OF_SALT = ItemID.BAG_OF_SALT;
    public static final int ICE_COOLER = ItemID.ICE_COOLER;
    // Fungicide spray - charges go from 0 (empty) to 10 (full)
    // RuneLite ItemID: FUNGICIDE_SPRAY_0 = 7431, FUNGICIDE_SPRAY_10 = 7421
    public static final int FUNGICIDE_SPRAY_0 = ItemID.FUNGICIDE_SPRAY_0;
    public static final int FUNGICIDE_SPRAY_1 = ItemID.FUNGICIDE_SPRAY_1;
    public static final int FUNGICIDE_SPRAY_2 = ItemID.FUNGICIDE_SPRAY_2;
    public static final int FUNGICIDE_SPRAY_3 = ItemID.FUNGICIDE_SPRAY_3;
    public static final int FUNGICIDE_SPRAY_4 = ItemID.FUNGICIDE_SPRAY_4;
    public static final int FUNGICIDE_SPRAY_5 = ItemID.FUNGICIDE_SPRAY_5;
    public static final int FUNGICIDE_SPRAY_6 = ItemID.FUNGICIDE_SPRAY_6;
    public static final int FUNGICIDE_SPRAY_7 = ItemID.FUNGICIDE_SPRAY_7;
    public static final int FUNGICIDE_SPRAY_8 = ItemID.FUNGICIDE_SPRAY_8;
    public static final int FUNGICIDE_SPRAY_9 = ItemID.FUNGICIDE_SPRAY_9;
    public static final int FUNGICIDE_SPRAY_10 = ItemID.FUNGICIDE_SPRAY_10;

    // Leaf-bladed Weapons (for Turoth/Kurask)
    public static final int LEAF_BLADED_SPEAR = ItemID.LEAFBLADED_SPEAR;
    public static final int LEAF_BLADED_SWORD = ItemID.LEAFBLADED_SWORD;
    public static final int LEAF_BLADED_BATTLEAXE = ItemID.LEAFBLADED_BATTLEAXE;

    // Broad Ammunition
    public static final int BROAD_ARROWS = ItemID.BROAD_ARROWS;
    public static final int BROAD_BOLTS = ItemID.BROAD_BOLTS;
    public static final int AMETHYST_BROAD_BOLTS = ItemID.AMETHYST_BROAD_BOLTS;

    // Magic Dart Staves (provide infinite death runes for Magic Dart)
    public static final int SLAYER_STAFF = ItemID.SLAYERS_STAFF;
    public static final int SLAYER_STAFF_E = ItemID.SLAYERS_STAFF_E;
    public static final int STAFF_OF_THE_DEAD = ItemID.STAFF_OF_THE_DEAD;
    public static final int TOXIC_STAFF_OF_THE_DEAD = ItemID.TOXIC_STAFF_OF_THE_DEAD;
    public static final int STAFF_OF_LIGHT = ItemID.STAFF_OF_LIGHT;
    public static final int STAFF_OF_BALANCE = ItemID.STAFF_OF_BALANCE;

    // Runes for Magic Dart
    public static final int MIND_RUNE = ItemID.MIND_RUNE;
    public static final int DEATH_RUNE = ItemID.DEATH_RUNE;

    // All slayer helmet variants
    private static final Set<Integer> SLAYER_HELMETS = Set.of(
            SLAYER_HELMET, SLAYER_HELMET_I,
            BLACK_SLAYER_HELMET, BLACK_SLAYER_HELMET_I,
            GREEN_SLAYER_HELMET, GREEN_SLAYER_HELMET_I,
            RED_SLAYER_HELMET, RED_SLAYER_HELMET_I,
            PURPLE_SLAYER_HELMET, PURPLE_SLAYER_HELMET_I,
            TURQUOISE_SLAYER_HELMET, TURQUOISE_SLAYER_HELMET_I,
            HYDRA_SLAYER_HELMET, HYDRA_SLAYER_HELMET_I,
            TWISTED_SLAYER_HELMET, TWISTED_SLAYER_HELMET_I,
            TZTOK_SLAYER_HELMET, TZTOK_SLAYER_HELMET_I,
            VAMPYRIC_SLAYER_HELMET, VAMPYRIC_SLAYER_HELMET_I,
            TZKAL_SLAYER_HELMET, TZKAL_SLAYER_HELMET_I,
            ARAXYTE_SLAYER_HELMET, ARAXYTE_SLAYER_HELMET_I
    );

    // All fungicide spray variants (from empty to full)
    private static final Set<Integer> FUNGICIDE_SPRAYS = Set.of(
            FUNGICIDE_SPRAY_0, FUNGICIDE_SPRAY_1, FUNGICIDE_SPRAY_2,
            FUNGICIDE_SPRAY_3, FUNGICIDE_SPRAY_4, FUNGICIDE_SPRAY_5,
            FUNGICIDE_SPRAY_6, FUNGICIDE_SPRAY_7, FUNGICIDE_SPRAY_8,
            FUNGICIDE_SPRAY_9, FUNGICIDE_SPRAY_10
    );

    // All leaf-bladed weapons
    private static final Set<Integer> LEAF_BLADED_WEAPONS = Set.of(
            LEAF_BLADED_SPEAR, LEAF_BLADED_SWORD, LEAF_BLADED_BATTLEAXE
    );

    // All broad ammo
    private static final Set<Integer> BROAD_AMMO = Set.of(
            BROAD_ARROWS, BROAD_BOLTS, AMETHYST_BROAD_BOLTS
    );

    // Mirror shield variants
    private static final Set<Integer> MIRROR_SHIELDS = Set.of(
            MIRROR_SHIELD, VS_SHIELD
    );

    // Rock hammer variants
    private static final Set<Integer> ROCK_HAMMERS = Set.of(
            ROCK_HAMMER, GRANITE_HAMMER
    );

    // Magic Dart staves (provide infinite death runes)
    private static final Set<Integer> MAGIC_DART_STAVES = Set.of(
            SLAYER_STAFF, SLAYER_STAFF_E,
            STAFF_OF_THE_DEAD, TOXIC_STAFF_OF_THE_DEAD,
            STAFF_OF_LIGHT, STAFF_OF_BALANCE
    );

    /**
     * Minimum recommended mind runes for a slayer task with Magic Dart.
     * Assumes ~100 casts needed for a typical task.
     */
    private static final int MIN_MIND_RUNES_FOR_TASK = 100;

    /**
     * Required Magic level for Magic Dart spell.
     */
    private static final int MAGIC_DART_LEVEL = 50;

    private final SlayerDataLoader dataLoader;
    private final GearSwitcher gearSwitcher;

    @Inject
    public SlayerEquipmentManager(SlayerDataLoader dataLoader, GearSwitcher gearSwitcher) {
        this.dataLoader = dataLoader;
        this.gearSwitcher = gearSwitcher;
    }

    // ========================================================================
    // Equipment Validation
    // ========================================================================

    /**
     * Check if player has correct protective equipment for a creature.
     *
     * @param taskName  the task/creature name
     * @param equipment current equipment state
     * @return validation result
     */
    public EquipmentValidation validateEquipment(String taskName, EquipmentState equipment) {
        SlayerCreature creature = dataLoader.getCreature(taskName);
        if (creature == null || !creature.requiresEquipment()) {
            return EquipmentValidation.valid();
        }

        Set<Integer> requiredIds = creature.getRequiredEquipmentIds();
        List<String> missingItems = new ArrayList<>();

        for (int itemId : requiredIds) {
            if (!hasRequiredEquipment(itemId, equipment)) {
                String itemName = getEquipmentName(itemId);
                missingItems.add(itemName);
            }
        }

        if (missingItems.isEmpty()) {
            return EquipmentValidation.valid();
        }

        return EquipmentValidation.invalid(missingItems, requiredIds);
    }

    /**
     * Check if player is wearing a slayer helmet (any variant).
     *
     * @param equipment current equipment state
     * @return true if wearing slayer helmet
     */
    public boolean isWearingSlayerHelmet(EquipmentState equipment) {
        int headItem = equipment.getHelmet().map(item -> item.getId()).orElse(-1);
        return SLAYER_HELMETS.contains(headItem);
    }

    /**
     * Check if player has the required equipment item or valid substitute.
     */
    private boolean hasRequiredEquipment(int requiredItemId, EquipmentState equipment) {
        // Slayer helmet substitutes for all protective equipment
        if (isProtectiveEquipment(requiredItemId) && isWearingSlayerHelmet(equipment)) {
            return true;
        }

        // Check specific slots based on item type
        if (requiredItemId == EARMUFFS || requiredItemId == NOSE_PEG || 
            requiredItemId == FACE_MASK || requiredItemId == WITCHWOOD_ICON) {
            int headItem = equipment.getHelmet().map(item -> item.getId()).orElse(-1);
            return headItem == requiredItemId || SLAYER_HELMETS.contains(headItem);
        }

        if (MIRROR_SHIELDS.contains(requiredItemId)) {
            int shieldItem = equipment.getShield().map(item -> item.getId()).orElse(-1);
            return MIRROR_SHIELDS.contains(shieldItem);
        }

        // Generic check - item ID matches in any slot
        return equipment.hasEquipped(requiredItemId);
    }

    private boolean isProtectiveEquipment(int itemId) {
        return itemId == EARMUFFS || itemId == NOSE_PEG || 
               itemId == FACE_MASK || itemId == WITCHWOOD_ICON ||
               MIRROR_SHIELDS.contains(itemId);
    }

    // ========================================================================
    // Weapon Validation (for Turoth/Kurask)
    // ========================================================================

    /**
     * Check if player has valid weapon for creatures with weapon restrictions.
     *
     * @param taskName  the task/creature name
     * @param equipment current equipment state
     * @param inventory current inventory state
     * @return validation result
     */
    public WeaponValidation validateWeapon(
            String taskName, EquipmentState equipment, InventoryState inventory) {
        
        SlayerCreature creature = dataLoader.getCreature(taskName);
        if (creature == null || !creature.hasWeaponRestrictions()) {
            return WeaponValidation.valid();
        }

        // Check equipped weapon
        int weaponId = equipment.getWeaponId();
        if (creature.getValidWeaponIds().contains(weaponId) ||
            LEAF_BLADED_WEAPONS.contains(weaponId)) {
            return WeaponValidation.valid();
        }

        // Check for broad ammo (ranged alternative)
        if (creature.isBroadAmmoValid()) {
            int ammoId = equipment.getAmmoId();
            if (BROAD_AMMO.contains(ammoId)) {
                return WeaponValidation.valid();
            }
        }

        // Magic Dart spell check
        if (creature.isMagicDartValid()) {
            MagicDartValidation dartValidation = canCastMagicDart(equipment, inventory);
            if (dartValidation.isValid()) {
                log.debug("Magic Dart validation passed: {}", dartValidation.getReason());
                return WeaponValidation.valid();
            } else {
                log.debug("Magic Dart validation failed: {}", dartValidation.getReason());
            }
        }

        return WeaponValidation.invalid(
                "Requires leaf-bladed weapon, broad ammo, or Magic Dart",
                creature.getValidWeaponIds()
        );
    }

    // ========================================================================
    // Magic Dart Validation
    // ========================================================================

    /**
     * Check if player can cast Magic Dart spell.
     * 
     * <p>Magic Dart requires:
     * <ul>
     *   <li>50 Magic level (not validated here - assume checked elsewhere)</li>
     *   <li>Slayer staff or equivalent equipped (provides death runes)</li>
     *   <li>Mind runes in inventory (at least enough for task)</li>
     * </ul>
     *
     * @param equipment current equipment state
     * @param inventory current inventory state
     * @return validation result
     */
    public MagicDartValidation canCastMagicDart(EquipmentState equipment, InventoryState inventory) {
        // Check for Magic Dart staff equipped
        int weaponId = equipment.getWeaponId();
        if (!MAGIC_DART_STAVES.contains(weaponId)) {
            return MagicDartValidation.invalid(
                    "Not wielding Magic Dart staff",
                    SLAYER_STAFF, // Primary staff to recommend
                    0
            );
        }

        // Check for mind runes
        int mindRuneCount = inventory.countItem(MIND_RUNE);
        if (mindRuneCount < MIN_MIND_RUNES_FOR_TASK) {
            return MagicDartValidation.invalid(
                    "Insufficient mind runes (have " + mindRuneCount + ", need " + MIN_MIND_RUNES_FOR_TASK + ")",
                    MIND_RUNE,
                    MIN_MIND_RUNES_FOR_TASK - mindRuneCount
            );
        }

        // All checks passed
        return MagicDartValidation.valid(
                "Staff equipped with " + mindRuneCount + " mind runes"
        );
    }

    /**
     * Check if a weapon is a valid Magic Dart staff.
     *
     * @param weaponId the weapon item ID
     * @return true if weapon can cast Magic Dart
     */
    public static boolean isMagicDartStaff(int weaponId) {
        return MAGIC_DART_STAVES.contains(weaponId);
    }

    /**
     * Result of Magic Dart spell validation.
     */
    @Value
    @Builder
    public static class MagicDartValidation {
        boolean valid;
        String reason;
        int missingItemId;
        int missingQuantity;

        public static MagicDartValidation valid(String reason) {
            return MagicDartValidation.builder()
                    .valid(true)
                    .reason(reason)
                    .missingItemId(-1)
                    .missingQuantity(0)
                    .build();
        }

        public static MagicDartValidation invalid(String reason, int missingItemId, int missingQuantity) {
            return MagicDartValidation.builder()
                    .valid(false)
                    .reason(reason)
                    .missingItemId(missingItemId)
                    .missingQuantity(missingQuantity)
                    .build();
        }
    }

    // ========================================================================
    // Finish-off Item Management
    // ========================================================================

    /**
     * Check if player has finish-off items for a creature.
     *
     * @param taskName  the task/creature name
     * @param inventory current inventory state
     * @param slayerState slayer state for auto-kill unlock checking
     * @return validation result
     */
    public FinishOffValidation validateFinishOffItems(
            String taskName, InventoryState inventory, SlayerState slayerState) {
        
        SlayerCreature creature = dataLoader.getCreature(taskName);
        if (creature == null || !creature.requiresFinishOffItem()) {
            return FinishOffValidation.notRequired();
        }

        // Check for auto-kill unlock
        SlayerUnlock autoKillUnlock = getAutoKillUnlock(taskName);
        if (autoKillUnlock != null && slayerState.hasUnlock(autoKillUnlock)) {
            return FinishOffValidation.autoKillEnabled(autoKillUnlock);
        }

        // Check inventory for finish-off items
        Set<Integer> requiredIds = creature.getFinishOffItemIds();
        for (int itemId : requiredIds) {
            if (hasFinishOffItem(itemId, inventory)) {
                return FinishOffValidation.hasItem(itemId, creature.getFinishOffThreshold());
            }
        }

        // Get the primary finish-off item for error message
        int primaryItemId = requiredIds.iterator().next();
        return FinishOffValidation.missing(
                getFinishOffItemName(primaryItemId),
                primaryItemId,
                creature.getFinishOffThreshold()
        );
    }

    /**
     * Get the auto-kill unlock for a creature type.
     */
    @Nullable
    private SlayerUnlock getAutoKillUnlock(String taskName) {
        String lower = taskName.toLowerCase();
        if (lower.contains("gargoyle")) return SlayerUnlock.AUTOKILL_GARGOYLES;
        if (lower.contains("rockslug")) return SlayerUnlock.AUTOKILL_ROCKSLUGS;
        if (lower.contains("desert lizard") || lower.contains("lizard")) return SlayerUnlock.AUTOKILL_DESERT_LIZARDS;
        if (lower.contains("zygomite")) return SlayerUnlock.AUTOKILL_ZYGOMITES;
        return null;
    }

    /**
     * Check if inventory contains a finish-off item (including variants).
     */
    private boolean hasFinishOffItem(int itemId, InventoryState inventory) {
        // Handle rock hammer variants
        if (itemId == ROCK_HAMMER) {
            return ROCK_HAMMERS.stream().anyMatch(inventory::hasItem);
        }

        // Handle fungicide spray variants
        if (FUNGICIDE_SPRAYS.contains(itemId) || itemId == FUNGICIDE_SPRAY_10) {
            return FUNGICIDE_SPRAYS.stream().anyMatch(inventory::hasItem);
        }

        return inventory.hasItem(itemId);
    }

    /**
     * Get the finish-off item ID to use for a creature.
     *
     * @param taskName  the creature name
     * @param inventory current inventory
     * @return the item ID to use, or -1 if none available
     */
    public int getFinishOffItemToUse(String taskName, InventoryState inventory) {
        SlayerCreature creature = dataLoader.getCreature(taskName);
        if (creature == null || !creature.requiresFinishOffItem()) {
            return -1;
        }

        for (int itemId : creature.getFinishOffItemIds()) {
            // Check for variants
            if (itemId == ROCK_HAMMER) {
                for (int variant : ROCK_HAMMERS) {
                    if (inventory.hasItem(variant)) return variant;
                }
            } else if (FUNGICIDE_SPRAYS.contains(itemId)) {
                // Find spray with most charges remaining (10 is full, 0 is empty)
                // Higher charge number = more charges
                for (int i = FUNGICIDE_SPRAY_10; i >= FUNGICIDE_SPRAY_1; i--) {
                    if (inventory.hasItem(i)) return i;
                }
            } else if (inventory.hasItem(itemId)) {
                return itemId;
            }
        }

        return -1;
    }

    // ========================================================================
    // Required Items List
    // ========================================================================

    /**
     * Get all items needed for a slayer task.
     *
     * @param taskName the task/creature name
     * @param location the target location
     * @return items needed with quantities
     */
    public RequiredItemsList getRequiredItems(String taskName, @Nullable SlayerLocation location) {
        List<RequiredItem> items = new ArrayList<>();

        SlayerCreature creature = dataLoader.getCreature(taskName);
        if (creature != null) {
            // Protective equipment
            for (int itemId : creature.getRequiredEquipmentIds()) {
                items.add(new RequiredItem(itemId, 1, ItemType.EQUIPMENT, getEquipmentName(itemId)));
            }

            // Finish-off items
            for (int itemId : creature.getFinishOffItemIds()) {
                // Estimate quantity needed based on task size
                int quantity = itemId == ICE_COOLER || itemId == BAG_OF_SALT ? 50 : 1;
                items.add(new RequiredItem(itemId, quantity, ItemType.CONSUMABLE, getFinishOffItemName(itemId)));
            }
        }

        // Location-specific items
        if (location != null) {
            for (int itemId : location.getRequiredItems()) {
                items.add(new RequiredItem(itemId, 1, ItemType.ACCESS, "Location access item"));
            }
        }

        return new RequiredItemsList(items);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private String getEquipmentName(int itemId) {
        if (itemId == EARMUFFS) return "Earmuffs";
        if (itemId == NOSE_PEG) return "Nose peg";
        if (itemId == FACE_MASK) return "Face mask";
        if (itemId == MIRROR_SHIELD) return "Mirror shield";
        if (itemId == VS_SHIELD) return "V's shield";
        if (itemId == WITCHWOOD_ICON) return "Witchwood icon";
        if (SLAYER_HELMETS.contains(itemId)) return "Slayer helmet";
        return "Unknown equipment (" + itemId + ")";
    }

    private String getFinishOffItemName(int itemId) {
        if (ROCK_HAMMERS.contains(itemId)) return "Rock hammer";
        if (itemId == BAG_OF_SALT) return "Bag of salt";
        if (itemId == ICE_COOLER) return "Ice coolers";
        if (FUNGICIDE_SPRAYS.contains(itemId)) return "Fungicide spray";
        return "Finish-off item (" + itemId + ")";
    }

    // ========================================================================
    // Result Classes
    // ========================================================================

    @Value
    public static class EquipmentValidation {
        boolean valid;
        List<String> missingItems;
        Set<Integer> requiredItemIds;

        public static EquipmentValidation valid() {
            return new EquipmentValidation(true, Collections.emptyList(), Collections.emptySet());
        }

        public static EquipmentValidation invalid(List<String> missing, Set<Integer> required) {
            return new EquipmentValidation(false, missing, required);
        }
    }

    @Value
    public static class WeaponValidation {
        boolean valid;
        @Nullable String reason;
        Set<Integer> validWeaponIds;

        public static WeaponValidation valid() {
            return new WeaponValidation(true, null, Collections.emptySet());
        }

        public static WeaponValidation invalid(String reason, Set<Integer> validIds) {
            return new WeaponValidation(false, reason, validIds);
        }
    }

    @Value
    public static class FinishOffValidation {
        boolean required;
        boolean hasItem;
        boolean autoKillEnabled;
        @Nullable SlayerUnlock autoKillUnlock;
        @Nullable String missingItemName;
        int itemId;
        int threshold;

        public static FinishOffValidation notRequired() {
            return new FinishOffValidation(false, true, false, null, null, -1, 0);
        }

        public static FinishOffValidation autoKillEnabled(SlayerUnlock unlock) {
            return new FinishOffValidation(true, true, true, unlock, null, -1, 0);
        }

        public static FinishOffValidation hasItem(int itemId, int threshold) {
            return new FinishOffValidation(true, true, false, null, null, itemId, threshold);
        }

        public static FinishOffValidation missing(String itemName, int itemId, int threshold) {
            return new FinishOffValidation(true, false, false, null, itemName, itemId, threshold);
        }
    }

    @Value
    public static class RequiredItemsList {
        List<RequiredItem> items;

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public Set<Integer> getItemIds() {
            Set<Integer> ids = new HashSet<>();
            for (RequiredItem item : items) {
                ids.add(item.itemId);
            }
            return ids;
        }
    }

    @Value
    public static class RequiredItem {
        int itemId;
        int quantity;
        ItemType type;
        String description;
    }

    public enum ItemType {
        EQUIPMENT,   // Must be worn
        CONSUMABLE,  // Used during task
        ACCESS       // Required for location access
    }
}
