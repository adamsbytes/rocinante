package com.rocinante.combat;

import lombok.Getter;
import net.runelite.api.ItemID;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Special attack weapon data.
 *
 * Per REQUIREMENTS.md Section 10.4 and 10.6.3:
 * <ul>
 *   <li>Special attacks consume 25%, 50%, or 100% depending on weapon</li>
 *   <li>Each weapon has specific special attack effects</li>
 * </ul>
 *
 * Note: This is a fallback for when WikiDataService is not available.
 * TODO: Query WikiDataService for spec costs when service is implemented.
 */
@Getter
public enum SpecialWeapon {

    // ========================================================================
    // 25% Special Attack Cost
    // ========================================================================

    DRAGON_DAGGER("Dragon Dagger", 25, SpecialEffect.DOUBLE_HIT,
            ItemID.DRAGON_DAGGER, ItemID.DRAGON_DAGGERP, 
            ItemID.DRAGON_DAGGERP_5680, ItemID.DRAGON_DAGGERP_5698),
    
    ABYSSAL_DAGGER("Abyssal Dagger", 25, SpecialEffect.DOUBLE_HIT,
            ItemID.ABYSSAL_DAGGER, ItemID.ABYSSAL_DAGGER_P,
            ItemID.ABYSSAL_DAGGER_P_13269, ItemID.ABYSSAL_DAGGER_P_13271),

    DRAGON_CLAWS("Dragon Claws", 50, SpecialEffect.QUAD_HIT,
            ItemID.DRAGON_CLAWS),

    DRAGON_SWORD("Dragon Sword", 40, SpecialEffect.ACCURACY_BOOST,
            ItemID.DRAGON_SWORD),

    DRAGON_LONGSWORD("Dragon Longsword", 25, SpecialEffect.ACCURACY_BOOST,
            ItemID.DRAGON_LONGSWORD),

    DRAGON_SCIMITAR("Dragon Scimitar", 55, SpecialEffect.DEFENCE_REDUCTION,
            ItemID.DRAGON_SCIMITAR, ItemID.DRAGON_SCIMITAR_OR),

    // ========================================================================
    // 50% Special Attack Cost
    // ========================================================================

    DRAGON_WARHAMMER("Dragon Warhammer", 50, SpecialEffect.DEFENCE_DRAIN,
            ItemID.DRAGON_WARHAMMER),

    BANDOS_GODSWORD("Bandos Godsword", 50, SpecialEffect.STAT_DRAIN,
            ItemID.BANDOS_GODSWORD, ItemID.BANDOS_GODSWORD_OR),

    ARMADYL_GODSWORD("Armadyl Godsword", 50, SpecialEffect.HIGH_DAMAGE,
            ItemID.ARMADYL_GODSWORD, ItemID.ARMADYL_GODSWORD_OR),

    SARADOMIN_GODSWORD("Saradomin Godsword", 50, SpecialEffect.HEAL,
            ItemID.SARADOMIN_GODSWORD, ItemID.SARADOMIN_GODSWORD_OR),

    ZAMORAK_GODSWORD("Zamorak Godsword", 50, SpecialEffect.FREEZE,
            ItemID.ZAMORAK_GODSWORD, ItemID.ZAMORAK_GODSWORD_OR),

    ANCIENT_GODSWORD("Ancient Godsword", 50, SpecialEffect.BLEED,
            ItemID.ANCIENT_GODSWORD),

    DRAGON_HALBERD("Dragon Halberd", 30, SpecialEffect.DOUBLE_HIT,
            ItemID.DRAGON_HALBERD),

    CRYSTAL_HALBERD("Crystal Halberd", 30, SpecialEffect.DOUBLE_HIT,
            ItemID.CRYSTAL_HALBERD, ItemID.CRYSTAL_HALBERD_24125),

    GRANITE_MAUL("Granite Maul", 50, SpecialEffect.INSTANT_HIT,
            ItemID.GRANITE_MAUL, ItemID.GRANITE_MAUL_12848, ItemID.GRANITE_MAUL_24225),

    ELDER_MAUL("Elder Maul", 50, SpecialEffect.DEFENCE_DRAIN,
            ItemID.ELDER_MAUL),

    // ========================================================================
    // Ranged Weapons
    // ========================================================================

    DARK_BOW("Dark Bow", 55, SpecialEffect.DOUBLE_HIT,
            ItemID.DARK_BOW),

    DRAGON_CROSSBOW("Dragon Crossbow", 60, SpecialEffect.ENCHANTED_BOLT,
            ItemID.DRAGON_CROSSBOW),

    ARMADYL_CROSSBOW("Armadyl Crossbow", 40, SpecialEffect.ENCHANTED_BOLT,
            ItemID.ARMADYL_CROSSBOW),

    TOXIC_BLOWPIPE("Toxic Blowpipe", 50, SpecialEffect.HEAL,
            ItemID.TOXIC_BLOWPIPE),

    DRAGON_THROWNAXE("Dragon Thrownaxe", 25, SpecialEffect.GUARANTEED_HIT,
            ItemID.DRAGON_THROWNAXE),

    BALLISTA("Heavy Ballista", 65, SpecialEffect.HIGH_DAMAGE,
            ItemID.HEAVY_BALLISTA),

    MORRIGANS_JAVELIN("Morrigan's Javelin", 50, SpecialEffect.BLEED,
            ItemID.MORRIGANS_JAVELIN),

    // ========================================================================
    // Magic Weapons
    // ========================================================================

    STAFF_OF_THE_DEAD("Staff of the Dead", 100, SpecialEffect.DAMAGE_REDUCTION,
            ItemID.STAFF_OF_THE_DEAD, ItemID.TOXIC_STAFF_OF_THE_DEAD),

    VOLATILE_NIGHTMARE_STAFF("Volatile Nightmare Staff", 55, SpecialEffect.HIGH_DAMAGE,
            ItemID.VOLATILE_NIGHTMARE_STAFF),

    ELDRITCH_NIGHTMARE_STAFF("Eldritch Nightmare Staff", 55, SpecialEffect.PRAYER_DRAIN,
            ItemID.ELDRITCH_NIGHTMARE_STAFF),

    // ========================================================================
    // Other Notable Weapons
    // ========================================================================

    ARCLIGHT("Arclight", 50, SpecialEffect.STAT_DRAIN,
            ItemID.ARCLIGHT),

    DARKLIGHT("Darklight", 50, SpecialEffect.STAT_DRAIN,
            ItemID.DARKLIGHT),

    BONE_DAGGER("Bone Dagger", 75, SpecialEffect.DEFENCE_IGNORE,
            ItemID.BONE_DAGGER, ItemID.BONE_DAGGER_P, 
            ItemID.BONE_DAGGER_P_8876, ItemID.BONE_DAGGER_P_8878),

    DINH_BULWARK("Dinh's Bulwark", 50, SpecialEffect.AOE_DAMAGE,
            ItemID.DINHS_BULWARK),

    DRAGON_MACE("Dragon Mace", 25, SpecialEffect.STRENGTH_BOOST,
            ItemID.DRAGON_MACE),

    DRAGON_BATTLEAXE("Dragon Battleaxe", 100, SpecialEffect.STRENGTH_BOOST,
            ItemID.DRAGON_BATTLEAXE),

    SARADOMIN_SWORD("Saradomin Sword", 100, SpecialEffect.MAGIC_DAMAGE,
            ItemID.SARADOMIN_SWORD, ItemID.SARADOMINS_BLESSED_SWORD),

    ABYSSAL_WHIP("Abyssal Whip", 50, SpecialEffect.ENERGY_DRAIN,
            ItemID.ABYSSAL_WHIP, ItemID.VOLCANIC_ABYSSAL_WHIP, ItemID.FROZEN_ABYSSAL_WHIP),

    ABYSSAL_TENTACLE("Abyssal Tentacle", 50, SpecialEffect.ENERGY_DRAIN,
            ItemID.ABYSSAL_TENTACLE);

    /**
     * Human-readable weapon name.
     */
    private final String name;

    /**
     * Special attack energy cost (0-100).
     */
    private final int energyCost;

    /**
     * The effect of the special attack.
     */
    private final SpecialEffect effect;

    /**
     * Item IDs that match this weapon (including variants).
     */
    private final int[] itemIds;

    // Static lookup map for fast item ID -> weapon resolution
    private static final Map<Integer, SpecialWeapon> ITEM_ID_MAP = new HashMap<>();

    static {
        for (SpecialWeapon weapon : values()) {
            for (int itemId : weapon.itemIds) {
                ITEM_ID_MAP.put(itemId, weapon);
            }
        }
    }

    SpecialWeapon(String name, int energyCost, SpecialEffect effect, int... itemIds) {
        this.name = name;
        this.energyCost = energyCost;
        this.effect = effect;
        this.itemIds = itemIds;
    }

    /**
     * Special attack effect types.
     */
    public enum SpecialEffect {
        /** Hits twice in one attack (DDS, Dragon Halberd) */
        DOUBLE_HIT,
        /** Hits four times (Dragon Claws) */
        QUAD_HIT,
        /** Increased accuracy */
        ACCURACY_BOOST,
        /** Reduces target's defence level */
        DEFENCE_DRAIN,
        /** Drains multiple target stats */
        STAT_DRAIN,
        /** High damage hit */
        HIGH_DAMAGE,
        /** Heals the player */
        HEAL,
        /** Freezes the target */
        FREEZE,
        /** Applies bleed damage over time */
        BLEED,
        /** Instant hit with no delay */
        INSTANT_HIT,
        /** Triggers enchanted bolt effect */
        ENCHANTED_BOLT,
        /** Guaranteed to hit */
        GUARANTEED_HIT,
        /** Reduces incoming damage */
        DAMAGE_REDUCTION,
        /** Drains target's prayer */
        PRAYER_DRAIN,
        /** Ignores target's defence */
        DEFENCE_IGNORE,
        /** Area of effect damage */
        AOE_DAMAGE,
        /** Boosts strength temporarily */
        STRENGTH_BOOST,
        /** Magic-based special attack */
        MAGIC_DAMAGE,
        /** Drains target's run energy */
        ENERGY_DRAIN,
        /** Reduces target's defence stat (not level) */
        DEFENCE_REDUCTION
    }

    /**
     * Find the special weapon for an item ID.
     *
     * @param itemId the item ID to look up
     * @return the SpecialWeapon, or null if not found
     */
    @Nullable
    public static SpecialWeapon forItemId(int itemId) {
        return ITEM_ID_MAP.get(itemId);
    }

    /**
     * Check if an item ID has a special attack.
     *
     * @param itemId the item ID to check
     * @return true if the item has a special attack
     */
    public static boolean hasSpecialAttack(int itemId) {
        return ITEM_ID_MAP.containsKey(itemId);
    }

    /**
     * Get the energy cost for an item ID.
     *
     * @param itemId the item ID
     * @return energy cost, or -1 if not found
     */
    public static int getEnergyCost(int itemId) {
        SpecialWeapon weapon = forItemId(itemId);
        return weapon != null ? weapon.energyCost : -1;
    }

    /**
     * Check if this weapon's special can be stacked (used multiple times quickly).
     * Per Section 10.6.3: some specs like DDS can be stacked.
     *
     * @return true if spec can be stacked
     */
    public boolean canStack() {
        // Weapons with low cost that benefit from multiple specs
        return this == DRAGON_DAGGER || 
               this == ABYSSAL_DAGGER ||
               this == DRAGON_THROWNAXE ||
               this == GRANITE_MAUL;
    }

    /**
     * Get the maximum number of specs that can be stacked.
     *
     * @return max stack count (usually 2-4 depending on energy)
     */
    public int getMaxStackCount() {
        return 100 / energyCost;
    }

    /**
     * Check if this weapon is primarily a DPS weapon (vs utility).
     *
     * @return true if weapon is used for damage dealing
     */
    public boolean isDpsWeapon() {
        return effect == SpecialEffect.DOUBLE_HIT ||
               effect == SpecialEffect.QUAD_HIT ||
               effect == SpecialEffect.HIGH_DAMAGE ||
               effect == SpecialEffect.INSTANT_HIT;
    }

    /**
     * Check if this weapon is utility-focused (debuff, heal, etc).
     *
     * @return true if weapon has utility effect
     */
    public boolean isUtilityWeapon() {
        return effect == SpecialEffect.DEFENCE_DRAIN ||
               effect == SpecialEffect.STAT_DRAIN ||
               effect == SpecialEffect.HEAL ||
               effect == SpecialEffect.FREEZE;
    }
}

