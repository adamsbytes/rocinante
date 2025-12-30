package com.rocinante.combat;

import net.runelite.api.ItemID;
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
            ItemID.SHORTBOW, ItemID.OAK_SHORTBOW, ItemID.WILLOW_SHORTBOW, ItemID.MAPLE_SHORTBOW, 
            ItemID.YEW_SHORTBOW, ItemID.MAGIC_SHORTBOW, ItemID.DARK_BOW,
            // Longbows (normal longbow)
            ItemID.LONGBOW, ItemID.OAK_LONGBOW, ItemID.WILLOW_LONGBOW, ItemID.MAPLE_LONGBOW,
            ItemID.YEW_LONGBOW, ItemID.MAGIC_LONGBOW,
            // Crossbows (bronze through dragon, armadyl)
            ItemID.PHOENIX_CROSSBOW, ItemID.CROSSBOW, ItemID.BRONZE_CROSSBOW, ItemID.BLURITE_CROSSBOW,
            ItemID.IRON_CROSSBOW, ItemID.STEEL_CROSSBOW, ItemID.MITHRIL_CROSSBOW, ItemID.ADAMANT_CROSSBOW,
            ItemID.RUNE_CROSSBOW, ItemID.DRAGON_CROSSBOW, ItemID.ARMADYL_CROSSBOW, ItemID.DRAGON_HUNTER_CROSSBOW,
            // Thrown weapons (knives)
            ItemID.BRONZE_KNIFE, ItemID.IRON_KNIFE, ItemID.STEEL_KNIFE, ItemID.BLACK_KNIFE,
            ItemID.MITHRIL_KNIFE, ItemID.ADAMANT_KNIFE, ItemID.RUNE_KNIFE, ItemID.DRAGON_KNIFE,
            // Thrown weapons (darts)
            ItemID.BRONZE_DART, ItemID.IRON_DART, ItemID.STEEL_DART, ItemID.BLACK_DART,
            ItemID.MITHRIL_DART, ItemID.ADAMANT_DART, ItemID.RUNE_DART, ItemID.DRAGON_DART,
            // Thrown weapons (javelins)
            ItemID.BRONZE_JAVELIN, ItemID.IRON_JAVELIN, ItemID.STEEL_JAVELIN, ItemID.MITHRIL_JAVELIN,
            ItemID.ADAMANT_JAVELIN, ItemID.RUNE_JAVELIN, ItemID.DRAGON_JAVELIN,
            // Special ranged weapons
            ItemID.TOXIC_BLOWPIPE,
            ItemID.HEAVY_BALLISTA,
            ItemID.TWISTED_BOW,
            ItemID.CRAWS_BOW,
            ItemID.BOW_OF_FAERDHINEN
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
            // Basic staves
            ItemID.STAFF, ItemID.STAFF_OF_AIR, ItemID.STAFF_OF_WATER, ItemID.STAFF_OF_EARTH, ItemID.STAFF_OF_FIRE,
            ItemID.MAGIC_STAFF,
            // Battlestaves
            ItemID.BATTLESTAFF, ItemID.AIR_BATTLESTAFF, ItemID.WATER_BATTLESTAFF, ItemID.EARTH_BATTLESTAFF, ItemID.FIRE_BATTLESTAFF,
            // Mystic staves
            ItemID.MYSTIC_AIR_STAFF, ItemID.MYSTIC_WATER_STAFF, ItemID.MYSTIC_EARTH_STAFF, ItemID.MYSTIC_FIRE_STAFF,
            // Special staves and wands
            ItemID.ANCIENT_STAFF,
            ItemID.AHRIMS_STAFF,   // Slayer's staff is 4170, Ahrim's is 4710
            ItemID.SLAYERS_STAFF,
            ItemID.MYSTIC_MUD_STAFF,
            ItemID.STAFF_OF_THE_DEAD,
            ItemID.TOXIC_STAFF_OF_THE_DEAD,
            ItemID.KODAI_WAND,
            ItemID.HARMONISED_NIGHTMARE_STAFF,
            ItemID.VOLATILE_NIGHTMARE_STAFF,
            ItemID.ELDRITCH_NIGHTMARE_STAFF,
            ItemID.TRIDENT_OF_THE_SEAS,
            ItemID.TRIDENT_OF_THE_SWAMP,
            ItemID.SANGUINESTI_STAFF
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
            // Arrows
            ItemID.BRONZE_ARROW, ItemID.IRON_ARROW, ItemID.STEEL_ARROW, ItemID.MITHRIL_ARROW,
            ItemID.ADAMANT_ARROW, ItemID.RUNE_ARROW, ItemID.AMETHYST_ARROW, ItemID.DRAGON_ARROW,
            // Bolts
            ItemID.BRONZE_BOLTS, ItemID.IRON_BOLTS, ItemID.STEEL_BOLTS, ItemID.MITHRIL_BOLTS,
            ItemID.ADAMANT_BOLTS, ItemID.RUNITE_BOLTS, ItemID.DRAGON_BOLTS,
            // Gem-tipped bolts (enchanted)
            ItemID.OPAL_BOLTS_E, ItemID.JADE_BOLTS_E, ItemID.PEARL_BOLTS_E, ItemID.TOPAZ_BOLTS_E,
            ItemID.SAPPHIRE_BOLTS_E, ItemID.EMERALD_BOLTS_E, ItemID.RUBY_BOLTS_E, ItemID.DIAMOND_BOLTS_E,
            ItemID.DRAGONSTONE_BOLTS_E, ItemID.ONYX_BOLTS_E,
            // Other
            ItemID.BOLT_RACK
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

