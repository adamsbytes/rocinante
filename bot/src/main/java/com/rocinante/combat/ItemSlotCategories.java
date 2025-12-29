package com.rocinante.combat;

import net.runelite.api.ItemID;

import java.util.Set;

/**
 * Categories of items by equipment slot for automatic slot detection.
 * 
 * <p>This class provides lookup methods to determine what equipment slot
 * an item belongs to based on its item ID. It uses known item IDs from
 * RuneLite's ItemID constants.
 *
 * <p>The categorization covers common items. For items not in these lists,
 * callers should fall back to item name analysis or default to weapon slot.
 *
 * <p>Note: This is a static categorization. For dynamic lookup, use the
 * Wiki API via WikiDataService.
 */
public final class ItemSlotCategories {

    private ItemSlotCategories() {
        // Utility class
    }

    // ========================================================================
    // Helmets / Head Slot
    // ========================================================================

    private static final Set<Integer> HELMETS = Set.of(
            // Bronze - Dragon helms
            ItemID.BRONZE_FULL_HELM, ItemID.BRONZE_MED_HELM,
            ItemID.IRON_FULL_HELM, ItemID.IRON_MED_HELM,
            ItemID.STEEL_FULL_HELM, ItemID.STEEL_MED_HELM,
            ItemID.BLACK_FULL_HELM, ItemID.BLACK_MED_HELM,
            ItemID.MITHRIL_FULL_HELM, ItemID.MITHRIL_MED_HELM,
            ItemID.ADAMANT_FULL_HELM, ItemID.ADAMANT_MED_HELM,
            ItemID.RUNE_FULL_HELM, ItemID.RUNE_MED_HELM,
            ItemID.DRAGON_FULL_HELM, ItemID.DRAGON_MED_HELM,
            
            // Special helmets
            ItemID.HELM_OF_NEITIZNOT,
            ItemID.BERSERKER_HELM, ItemID.WARRIOR_HELM, ItemID.ARCHER_HELM, ItemID.FARSEER_HELM,
            ItemID.SERPENTINE_HELM, ItemID.TANZANITE_HELM, ItemID.MAGMA_HELM,
            ItemID.SLAYER_HELMET, ItemID.SLAYER_HELMET_I,
            ItemID.BLACK_SLAYER_HELMET, ItemID.BLACK_SLAYER_HELMET_I,
            ItemID.TORVA_FULL_HELM, ItemID.ANCESTRAL_HAT,
            ItemID.ARMADYL_HELMET, ItemID.INQUISITORS_GREAT_HELM,
            ItemID.JUSTICIAR_FACEGUARD, ItemID.VOID_MELEE_HELM,
            ItemID.VOID_MAGE_HELM, ItemID.VOID_RANGER_HELM,
            
            // Ranged helmets
            ItemID.ROBIN_HOOD_HAT, ItemID.RANGERS_TUNIC,
            ItemID.KARILS_COIF, ItemID.CRYSTAL_HELM,
            ItemID.MASORI_MASK, ItemID.MASORI_MASK_F,
            
            // Magic helmets
            ItemID.AHRIMS_HOOD, ItemID.INFINITY_HAT,
            ItemID.ENCHANTED_HAT, ItemID.MYSTIC_HAT,
            ItemID.ANCESTRAL_HAT
    );

    // ========================================================================
    // Body / Chest Slot
    // ========================================================================

    private static final Set<Integer> BODIES = Set.of(
            // Bronze - Dragon platebodies/chainbodies
            ItemID.BRONZE_PLATEBODY, ItemID.BRONZE_CHAINBODY,
            ItemID.IRON_PLATEBODY, ItemID.IRON_CHAINBODY,
            ItemID.STEEL_PLATEBODY, ItemID.STEEL_CHAINBODY,
            ItemID.BLACK_PLATEBODY, ItemID.BLACK_CHAINBODY,
            ItemID.MITHRIL_PLATEBODY, ItemID.MITHRIL_CHAINBODY,
            ItemID.ADAMANT_PLATEBODY, ItemID.ADAMANT_CHAINBODY,
            ItemID.RUNE_PLATEBODY, ItemID.RUNE_CHAINBODY,
            ItemID.DRAGON_PLATEBODY, ItemID.DRAGON_CHAINBODY,
            
            // Special bodies
            ItemID.FIGHTER_TORSO, ItemID.BANDOS_CHESTPLATE,
            ItemID.TORVA_PLATEBODY, ItemID.INQUISITORS_HAUBERK,
            ItemID.JUSTICIAR_CHESTGUARD, ItemID.VOID_KNIGHT_TOP,
            ItemID.ARMADYL_CHESTPLATE, ItemID.CRYSTAL_BODY,
            ItemID.MASORI_BODY, ItemID.MASORI_BODY_F,
            
            // Ranged bodies
            ItemID.BLACK_DHIDE_BODY, ItemID.RED_DHIDE_BODY,
            ItemID.BLUE_DHIDE_BODY, ItemID.GREEN_DHIDE_BODY,
            ItemID.KARILS_LEATHERTOP, ItemID.ARMADYL_CHESTPLATE,
            
            // Magic bodies
            ItemID.AHRIMS_ROBETOP, ItemID.ANCESTRAL_ROBE_TOP,
            ItemID.INFINITY_TOP, ItemID.MYSTIC_ROBE_TOP,
            ItemID.ENCHANTED_TOP
    );

    // ========================================================================
    // Legs Slot
    // ========================================================================

    private static final Set<Integer> LEGS = Set.of(
            // Bronze - Dragon platelegs/plateskirt
            ItemID.BRONZE_PLATELEGS, ItemID.BRONZE_PLATESKIRT,
            ItemID.IRON_PLATELEGS, ItemID.IRON_PLATESKIRT,
            ItemID.STEEL_PLATELEGS, ItemID.STEEL_PLATESKIRT,
            ItemID.BLACK_PLATELEGS, ItemID.BLACK_PLATESKIRT,
            ItemID.MITHRIL_PLATELEGS, ItemID.MITHRIL_PLATESKIRT,
            ItemID.ADAMANT_PLATELEGS, ItemID.ADAMANT_PLATESKIRT,
            ItemID.RUNE_PLATELEGS, ItemID.RUNE_PLATESKIRT,
            ItemID.DRAGON_PLATELEGS, ItemID.DRAGON_PLATESKIRT,
            
            // Special legs
            ItemID.BANDOS_TASSETS, ItemID.TORVA_PLATELEGS,
            ItemID.INQUISITORS_PLATESKIRT, ItemID.JUSTICIAR_LEGGUARDS,
            ItemID.VOID_KNIGHT_ROBE, ItemID.OBSIDIAN_PLATELEGS,
            ItemID.ARMADYL_CHAINSKIRT, ItemID.CRYSTAL_LEGS,
            ItemID.MASORI_CHAPS, ItemID.MASORI_CHAPS_F,
            
            // Ranged legs
            ItemID.BLACK_DHIDE_CHAPS, ItemID.RED_DHIDE_CHAPS,
            ItemID.BLUE_DHIDE_CHAPS, ItemID.GREEN_DHIDE_CHAPS,
            ItemID.KARILS_LEATHERSKIRT,
            
            // Magic legs
            ItemID.AHRIMS_ROBESKIRT, ItemID.ANCESTRAL_ROBE_BOTTOM,
            ItemID.INFINITY_BOTTOMS, ItemID.MYSTIC_ROBE_BOTTOM,
            ItemID.ENCHANTED_ROBE
    );

    // ========================================================================
    // Capes
    // ========================================================================

    private static final Set<Integer> CAPES = Set.of(
            // Skillcapes (just a few examples)
            ItemID.ATTACK_CAPE, ItemID.STRENGTH_CAPE, ItemID.DEFENCE_CAPE,
            ItemID.HITPOINTS_CAPE, ItemID.PRAYER_CAPE, ItemID.MAGIC_CAPE,
            ItemID.RANGING_CAPE, ItemID.MAX_CAPE,
            
            // Combat capes
            ItemID.FIRE_CAPE, ItemID.INFERNAL_CAPE,
            ItemID.ARDOUGNE_CLOAK_4, ItemID.ARDOUGNE_CLOAK_3,
            ItemID.AVAS_ACCUMULATOR, ItemID.AVAS_ASSEMBLER,
            ItemID.MYTHICAL_CAPE, ItemID.OBSIDIAN_CAPE,
            
            // God capes
            ItemID.SARADOMIN_CAPE, ItemID.ZAMORAK_CAPE, ItemID.GUTHIX_CAPE,
            ItemID.IMBUED_SARADOMIN_CAPE, ItemID.IMBUED_ZAMORAK_CAPE, ItemID.IMBUED_GUTHIX_CAPE
    );

    // ========================================================================
    // Amulets / Necklaces
    // ========================================================================

    private static final Set<Integer> AMULETS = Set.of(
            // Common amulets
            ItemID.AMULET_OF_GLORY, ItemID.AMULET_OF_GLORY4,
            ItemID.AMULET_OF_FURY, ItemID.AMULET_OF_TORTURE,
            ItemID.AMULET_OF_STRENGTH, ItemID.AMULET_OF_POWER,
            ItemID.NECKLACE_OF_ANGUISH, ItemID.OCCULT_NECKLACE,
            ItemID.AMULET_OF_THE_DAMNED,
            
            // Utility amulets
            ItemID.GAMES_NECKLACE8, ItemID.RING_OF_DUELING8,
            ItemID.DIGSITE_PENDANT_5, ItemID.SKILLS_NECKLACE,
            
            // High-tier
            ItemID.ZENYTE_SHARD
    );

    // ========================================================================
    // Gloves
    // ========================================================================

    private static final Set<Integer> GLOVES = Set.of(
            // Barrows gloves and variants
            ItemID.BARROWS_GLOVES, ItemID.DRAGON_GLOVES,
            ItemID.RUNE_GLOVES, ItemID.ADAMANT_GLOVES,
            ItemID.MITHRIL_GLOVES, ItemID.BLACK_GLOVES,
            ItemID.STEEL_GLOVES, ItemID.IRON_GLOVES,
            ItemID.BRONZE_GLOVES, ItemID.LEATHER_GLOVES,
            
            // Combat gloves
            ItemID.FEROCIOUS_GLOVES, ItemID.TORMENTED_BRACELET,
            ItemID.VOID_KNIGHT_GLOVES, ItemID.GRANITE_GLOVES,
            ItemID.COMBAT_BRACELET, ItemID.BRACELET_OF_ETHEREUM
    );

    // ========================================================================
    // Boots
    // ========================================================================

    private static final Set<Integer> BOOTS = Set.of(
            // Metal boots
            ItemID.BRONZE_BOOTS, ItemID.IRON_BOOTS,
            ItemID.STEEL_BOOTS, ItemID.BLACK_BOOTS,
            ItemID.MITHRIL_BOOTS, ItemID.ADAMANT_BOOTS,
            ItemID.RUNE_BOOTS, ItemID.DRAGON_BOOTS,
            
            // Special boots
            ItemID.PRIMORDIAL_BOOTS, ItemID.PEGASIAN_BOOTS,
            ItemID.ETERNAL_BOOTS, ItemID.GUARDIAN_BOOTS,
            ItemID.RANGER_BOOTS, ItemID.HOLY_SANDALS,
            ItemID.INFINITY_BOOTS, ItemID.CLIMBING_BOOTS
    );

    // ========================================================================
    // Rings
    // ========================================================================

    private static final Set<Integer> RINGS = Set.of(
            // Combat rings
            ItemID.BERSERKER_RING, ItemID.BERSERKER_RING_I,
            ItemID.WARRIOR_RING, ItemID.WARRIOR_RING_I,
            ItemID.ARCHERS_RING, ItemID.ARCHERS_RING_I,
            ItemID.SEERS_RING, ItemID.SEERS_RING_I,
            ItemID.TYRANNICAL_RING, ItemID.TYRANNICAL_RING_I,
            ItemID.TREASONOUS_RING, ItemID.TREASONOUS_RING_I,
            ItemID.RING_OF_SUFFERING, ItemID.RING_OF_SUFFERING_I,
            ItemID.BELLATOR_RING, ItemID.ULTOR_RING,
            ItemID.MAGUS_RING, ItemID.VENATOR_RING,
            
            // Utility rings
            ItemID.RING_OF_WEALTH, ItemID.RING_OF_WEALTH_5,
            ItemID.EXPLORERS_RING_4, ItemID.RING_OF_LIFE,
            ItemID.RING_OF_RECOIL
    );

    // ========================================================================
    // Shields / Off-hand
    // ========================================================================

    private static final Set<Integer> SHIELDS = Set.of(
            // Metal shields
            ItemID.BRONZE_SQ_SHIELD, ItemID.BRONZE_KITESHIELD,
            ItemID.IRON_SQ_SHIELD, ItemID.IRON_KITESHIELD,
            ItemID.STEEL_SQ_SHIELD, ItemID.STEEL_KITESHIELD,
            ItemID.BLACK_SQ_SHIELD, ItemID.BLACK_KITESHIELD,
            ItemID.MITHRIL_SQ_SHIELD, ItemID.MITHRIL_KITESHIELD,
            ItemID.ADAMANT_SQ_SHIELD, ItemID.ADAMANT_KITESHIELD,
            ItemID.RUNE_SQ_SHIELD, ItemID.RUNE_KITESHIELD,
            ItemID.DRAGON_SQ_SHIELD, ItemID.DRAGON_KITESHIELD,
            
            // Defenders
            ItemID.BRONZE_DEFENDER, ItemID.IRON_DEFENDER,
            ItemID.STEEL_DEFENDER, ItemID.BLACK_DEFENDER,
            ItemID.MITHRIL_DEFENDER, ItemID.ADAMANT_DEFENDER,
            ItemID.RUNE_DEFENDER, ItemID.DRAGON_DEFENDER,
            ItemID.AVERNIC_DEFENDER,
            
            // Special shields
            ItemID.DRAGONFIRE_SHIELD, ItemID.ANCIENT_WYVERN_SHIELD,
            ItemID.DRAGONFIRE_WARD, ItemID.SPECTRAL_SPIRIT_SHIELD,
            ItemID.ARCANE_SPIRIT_SHIELD, ItemID.ELYSIAN_SPIRIT_SHIELD,
            ItemID.MALEDICTION_WARD, ItemID.ODIUM_WARD,
            ItemID.CRYSTAL_SHIELD, ItemID.TOME_OF_FIRE,
            ItemID.BOOK_OF_DARKNESS, ItemID.UNHOLY_BOOK,
            ItemID.BOOK_OF_WAR, ItemID.BOOK_OF_LAW
    );

    // ========================================================================
    // Lookup Methods
    // ========================================================================

    /**
     * Check if item is a helmet (head slot).
     */
    public static boolean isHelmet(int itemId) {
        return HELMETS.contains(itemId);
    }

    /**
     * Check if item is body armor (chest slot).
     */
    public static boolean isBody(int itemId) {
        return BODIES.contains(itemId);
    }

    /**
     * Check if item is leg armor.
     */
    public static boolean isLegs(int itemId) {
        return LEGS.contains(itemId);
    }

    /**
     * Check if item is a cape.
     */
    public static boolean isCape(int itemId) {
        return CAPES.contains(itemId);
    }

    /**
     * Check if item is an amulet/necklace.
     */
    public static boolean isAmulet(int itemId) {
        return AMULETS.contains(itemId);
    }

    /**
     * Check if item is gloves.
     */
    public static boolean isGloves(int itemId) {
        return GLOVES.contains(itemId);
    }

    /**
     * Check if item is boots.
     */
    public static boolean isBoots(int itemId) {
        return BOOTS.contains(itemId);
    }

    /**
     * Check if item is a ring.
     */
    public static boolean isRing(int itemId) {
        return RINGS.contains(itemId);
    }

    /**
     * Check if item is a shield/off-hand.
     */
    public static boolean isShield(int itemId) {
        return SHIELDS.contains(itemId);
    }

    /**
     * Get the equipment slot for an item, or -1 if unknown.
     *
     * @param itemId the item ID
     * @return equipment slot index, or -1 if unknown
     */
    public static int getSlot(int itemId) {
        if (isHelmet(itemId)) return com.rocinante.state.EquipmentState.SLOT_HEAD;
        if (isBody(itemId)) return com.rocinante.state.EquipmentState.SLOT_BODY;
        if (isLegs(itemId)) return com.rocinante.state.EquipmentState.SLOT_LEGS;
        if (isCape(itemId)) return com.rocinante.state.EquipmentState.SLOT_CAPE;
        if (isAmulet(itemId)) return com.rocinante.state.EquipmentState.SLOT_AMULET;
        if (isGloves(itemId)) return com.rocinante.state.EquipmentState.SLOT_GLOVES;
        if (isBoots(itemId)) return com.rocinante.state.EquipmentState.SLOT_BOOTS;
        if (isRing(itemId)) return com.rocinante.state.EquipmentState.SLOT_RING;
        if (isShield(itemId)) return com.rocinante.state.EquipmentState.SLOT_SHIELD;
        if (WeaponCategories.isAmmo(itemId)) return com.rocinante.state.EquipmentState.SLOT_AMMO;
        if (WeaponCategories.isWeapon(itemId)) return com.rocinante.state.EquipmentState.SLOT_WEAPON;
        return -1;
    }
}

