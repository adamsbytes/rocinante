package com.rocinante.combat;

import java.util.Set;

/**
 * Shared weapon category constants for attack style detection.
 *
 * This class consolidates weapon IDs used by GearSet and EquipItemTask
 * to avoid duplication (DRY principle) and ensure consistency.
 *
 * <p>Weapon categories:
 * <ul>
 *   <li>RANGED_WEAPONS: Bows, crossbows, thrown weapons</li>
 *   <li>MAGIC_WEAPONS: Staffs, wands, powered staves</li>
 *   <li>AMMO_IDS: Arrows, bolts, darts</li>
 * </ul>
 *
 * <p>Note: Item IDs must be unique within each Set.of() call.
 */
public final class WeaponCategories {

    private WeaponCategories() {
        // Static utility class
    }

    // ========================================================================
    // Ranged Weapons
    // ========================================================================

    /**
     * Common ranged weapon IDs (bows, crossbows, thrown weapons).
     *
     * <p>Categories included:
     * <ul>
     *   <li>Shortbows: Normal, Oak, Willow, Maple, Yew, Magic, Dark</li>
     *   <li>Longbows: Normal</li>
     *   <li>Crossbows: Bronze through Dragon, Armadyl</li>
     *   <li>Thrown weapons: Knives, Darts, Javelins</li>
     *   <li>Special ranged: Blowpipe, Twisted Bow, Bow of Faerdhinen</li>
     * </ul>
     */
    public static final Set<Integer> RANGED_WEAPONS = Set.of(
            // Shortbows (normal through dark bow)
            841, 843, 845, 847, 849, 851, 853,
            // Longbows (normal longbow)
            839,
            // Crossbows (bronze through dragon, armadyl)
            767, 837, 9174, 9176, 9177, 9179, 9181, 9183, 9185,
            // Thrown weapons (knives - bronze through dragon)
            800, 802, 804, 806, 807, 809, 811, 813,
            // Thrown weapons (javelins)
            863, 864, 865, 866, 867, 868, 869,
            // Special ranged weapons
            11785,  // Armadyl crossbow
            12926,  // Toxic blowpipe
            19481,  // Heavy ballista
            20997,  // Twisted bow
            21902,  // Dragon hunter crossbow
            22550,  // Craws bow
            23987   // Bow of faerdhinen
    );

    // ========================================================================
    // Magic Weapons
    // ========================================================================

    /**
     * Common magic weapon IDs (staffs, wands, powered staves).
     *
     * <p>Categories included:
     * <ul>
     *   <li>Basic staves: Staff, elemental staves</li>
     *   <li>Battlestaves: Elemental battlestaves</li>
     *   <li>Special staves: Ancient, Kodai, Nightmare, Harmonised</li>
     * </ul>
     */
    public static final Set<Integer> MAGIC_WEAPONS = Set.of(
            // Basic staves (staff, air staff, water staff, earth staff, fire staff)
            1379, 1381, 1383, 1385, 1387, 1389, 1391, 1393, 1395, 1397, 1399, 1401, 1403, 1405,
            // Special staves and wands
            4675,   // Ancient staff
            4710,   // Slayer's staff
            6563,   // Mud battlestaff
            11791,  // Staff of the dead
            12899,  // Toxic staff of the dead
            21006,  // Kodai wand
            22296,  // Harmonised nightmare staff
            22323   // Volatile nightmare staff
    );

    // ========================================================================
    // Ammunition
    // ========================================================================

    /**
     * Common ammunition IDs (arrows, bolts, darts).
     *
     * <p>Categories included:
     * <ul>
     *   <li>Arrows: Bronze through Dragon, Amethyst</li>
     *   <li>Bolts: Bronze through Dragon, gem-tipped</li>
     *   <li>Darts: Bronze through Dragon</li>
     * </ul>
     */
    public static final Set<Integer> AMMO_IDS = Set.of(
            // Arrows (bronze through dragon)
            882, 884, 886, 888, 890, 892,
            // Bolts (bronze through dragon)
            877, 9140, 9141, 9142, 9143, 9144, 9145,
            // Gem-tipped bolts (enchanted)
            4740, 9236, 9237, 9238, 9239, 9240, 9241, 9242, 9243, 9244, 9245
    );

    // ========================================================================
    // Query Methods
    // ========================================================================

    /**
     * Check if an item ID is a ranged weapon.
     *
     * @param itemId the item ID to check
     * @return true if it's a ranged weapon
     */
    public static boolean isRangedWeapon(int itemId) {
        return itemId > 0 && RANGED_WEAPONS.contains(itemId);
    }

    /**
     * Check if an item ID is a magic weapon.
     *
     * @param itemId the item ID to check
     * @return true if it's a magic weapon
     */
    public static boolean isMagicWeapon(int itemId) {
        return itemId > 0 && MAGIC_WEAPONS.contains(itemId);
    }

    /**
     * Check if an item ID is ammunition.
     *
     * @param itemId the item ID to check
     * @return true if it's ammunition
     */
    public static boolean isAmmo(int itemId) {
        return itemId > 0 && AMMO_IDS.contains(itemId);
    }

    /**
     * Check if an item is any type of weapon (melee, ranged, or magic).
     *
     * @param itemId the item ID to check
     * @return true if it's a weapon
     */
    public static boolean isWeapon(int itemId) {
        return isRangedWeapon(itemId) || isMagicWeapon(itemId) || isMeleeWeapon(itemId);
    }

    /**
     * Check if an item ID is a melee weapon.
     * This is a subset of known melee weapons - not exhaustive.
     *
     * @param itemId the item ID to check
     * @return true if it's a melee weapon
     */
    public static boolean isMeleeWeapon(int itemId) {
        return itemId > 0 && MELEE_WEAPONS.contains(itemId);
    }

    /**
     * Common melee weapons by item ID.
     * This covers popular melee weapons but is not exhaustive.
     */
    public static final Set<Integer> MELEE_WEAPONS = Set.of(
            // Swords
            net.runelite.api.ItemID.BRONZE_SWORD, net.runelite.api.ItemID.IRON_SWORD,
            net.runelite.api.ItemID.STEEL_SWORD, net.runelite.api.ItemID.BLACK_SWORD,
            net.runelite.api.ItemID.MITHRIL_SWORD, net.runelite.api.ItemID.ADAMANT_SWORD,
            net.runelite.api.ItemID.RUNE_SWORD, net.runelite.api.ItemID.DRAGON_SWORD,
            
            // Scimitars
            net.runelite.api.ItemID.BRONZE_SCIMITAR, net.runelite.api.ItemID.IRON_SCIMITAR,
            net.runelite.api.ItemID.STEEL_SCIMITAR, net.runelite.api.ItemID.BLACK_SCIMITAR,
            net.runelite.api.ItemID.MITHRIL_SCIMITAR, net.runelite.api.ItemID.ADAMANT_SCIMITAR,
            net.runelite.api.ItemID.RUNE_SCIMITAR, net.runelite.api.ItemID.DRAGON_SCIMITAR,
            
            // Longswords
            net.runelite.api.ItemID.BRONZE_LONGSWORD, net.runelite.api.ItemID.IRON_LONGSWORD,
            net.runelite.api.ItemID.STEEL_LONGSWORD, net.runelite.api.ItemID.BLACK_LONGSWORD,
            net.runelite.api.ItemID.MITHRIL_LONGSWORD, net.runelite.api.ItemID.ADAMANT_LONGSWORD,
            net.runelite.api.ItemID.RUNE_LONGSWORD, net.runelite.api.ItemID.DRAGON_LONGSWORD,
            
            // 2H swords
            net.runelite.api.ItemID.BRONZE_2H_SWORD, net.runelite.api.ItemID.IRON_2H_SWORD,
            net.runelite.api.ItemID.STEEL_2H_SWORD, net.runelite.api.ItemID.BLACK_2H_SWORD,
            net.runelite.api.ItemID.MITHRIL_2H_SWORD, net.runelite.api.ItemID.ADAMANT_2H_SWORD,
            net.runelite.api.ItemID.RUNE_2H_SWORD, net.runelite.api.ItemID.DRAGON_2H_SWORD,
            
            // Special weapons
            net.runelite.api.ItemID.ABYSSAL_WHIP, net.runelite.api.ItemID.ABYSSAL_TENTACLE,
            net.runelite.api.ItemID.BLADE_OF_SAELDOR, net.runelite.api.ItemID.GHRAZI_RAPIER,
            net.runelite.api.ItemID.INQUISITORS_MACE, net.runelite.api.ItemID.OSMUMTENS_FANG,
            net.runelite.api.ItemID.DRAGON_DAGGER, net.runelite.api.ItemID.DRAGON_DAGGERP,
            net.runelite.api.ItemID.DRAGON_CLAWS, net.runelite.api.ItemID.DRAGON_MACE,
            net.runelite.api.ItemID.SARADOMIN_SWORD, net.runelite.api.ItemID.ZAMORAKIAN_SPEAR,
            net.runelite.api.ItemID.GUTHANS_WARSPEAR, net.runelite.api.ItemID.TORAGS_HAMMERS,
            net.runelite.api.ItemID.DHAROKS_GREATAXE, net.runelite.api.ItemID.VERACS_FLAIL
    );
}

