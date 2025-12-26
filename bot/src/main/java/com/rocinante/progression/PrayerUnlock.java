package com.rocinante.progression;

import lombok.Getter;
import net.runelite.api.Prayer;
import net.runelite.api.Quest;

import javax.annotation.Nullable;

/**
 * Represents a prayer and its unlock requirements.
 *
 * Based on RuneLite Prayer.java enum with added unlock requirement data.
 * Prayer level requirements from OSRS Wiki.
 */
@Getter
public enum PrayerUnlock {

    // ========================================================================
    // Basic Prayers (No quest requirements)
    // ========================================================================

    THICK_SKIN(Prayer.THICK_SKIN, 1, null),
    BURST_OF_STRENGTH(Prayer.BURST_OF_STRENGTH, 4, null),
    CLARITY_OF_THOUGHT(Prayer.CLARITY_OF_THOUGHT, 7, null),
    SHARP_EYE(Prayer.SHARP_EYE, 8, null),
    MYSTIC_WILL(Prayer.MYSTIC_WILL, 9, null),
    ROCK_SKIN(Prayer.ROCK_SKIN, 10, null),
    SUPERHUMAN_STRENGTH(Prayer.SUPERHUMAN_STRENGTH, 13, null),
    IMPROVED_REFLEXES(Prayer.IMPROVED_REFLEXES, 16, null),
    RAPID_RESTORE(Prayer.RAPID_RESTORE, 19, null),
    RAPID_HEAL(Prayer.RAPID_HEAL, 22, null),
    PROTECT_ITEM(Prayer.PROTECT_ITEM, 25, null),
    HAWK_EYE(Prayer.HAWK_EYE, 26, null),
    MYSTIC_LORE(Prayer.MYSTIC_LORE, 27, null),
    STEEL_SKIN(Prayer.STEEL_SKIN, 28, null),
    ULTIMATE_STRENGTH(Prayer.ULTIMATE_STRENGTH, 31, null),
    INCREDIBLE_REFLEXES(Prayer.INCREDIBLE_REFLEXES, 34, null),

    // ========================================================================
    // Protection Prayers (Critical for combat)
    // ========================================================================

    PROTECT_FROM_MAGIC(Prayer.PROTECT_FROM_MAGIC, 37, null),
    PROTECT_FROM_MISSILES(Prayer.PROTECT_FROM_MISSILES, 40, null),
    PROTECT_FROM_MELEE(Prayer.PROTECT_FROM_MELEE, 43, null),

    // ========================================================================
    // Higher Level Prayers
    // ========================================================================

    EAGLE_EYE(Prayer.EAGLE_EYE, 44, null),
    MYSTIC_MIGHT(Prayer.MYSTIC_MIGHT, 45, null),
    RETRIBUTION(Prayer.RETRIBUTION, 46, null),
    REDEMPTION(Prayer.REDEMPTION, 49, null),
    SMITE(Prayer.SMITE, 52, null),
    PRESERVE(Prayer.PRESERVE, 55, null),

    // ========================================================================
    // Quest-Locked Prayers
    // ========================================================================

    /**
     * Chivalry requires completion of King's Ransom and Knight Waves training.
     */
    CHIVALRY(Prayer.CHIVALRY, 60, Quest.KINGS_RANSOM),

    /**
     * Piety requires completion of King's Ransom and Knight Waves training.
     */
    PIETY(Prayer.PIETY, 70, Quest.KINGS_RANSOM),

    /**
     * Rigour requires 74 Prayer and a Rigour scroll (no quest).
     */
    RIGOUR(Prayer.RIGOUR, 74, null),

    /**
     * Augury requires 77 Prayer and an Augury scroll (no quest).
     */
    AUGURY(Prayer.AUGURY, 77, null),

    // ========================================================================
    // Ruinous Powers (Ancient prayers - require completion of Desert Treasure II)
    // ========================================================================

    RP_REJUVENATION(Prayer.RP_REJUVENATION, 60, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_ANCIENT_STRENGTH(Prayer.RP_ANCIENT_STRENGTH, 61, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_ANCIENT_SIGHT(Prayer.RP_ANCIENT_SIGHT, 62, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_ANCIENT_WILL(Prayer.RP_ANCIENT_WILL, 63, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_PROTECT_ITEM(Prayer.RP_PROTECT_ITEM, 65, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_RUINOUS_GRACE(Prayer.RP_RUINOUS_GRACE, 66, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_DAMPEN_MAGIC(Prayer.RP_DAMPEN_MAGIC, 67, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_DAMPEN_RANGED(Prayer.RP_DAMPEN_RANGED, 69, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_DAMPEN_MELEE(Prayer.RP_DAMPEN_MELEE, 71, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_TRINITAS(Prayer.RP_TRINITAS, 72, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_BERSERKER(Prayer.RP_BERSERKER, 74, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_PURGE(Prayer.RP_PURGE, 75, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_METABOLISE(Prayer.RP_METABOLISE, 77, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_REBUKE(Prayer.RP_REBUKE, 78, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_VINDICATION(Prayer.RP_VINDICATION, 80, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_DECIMATE(Prayer.RP_DECIMATE, 82, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_ANNIHILATE(Prayer.RP_ANNIHILATE, 84, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_VAPORISE(Prayer.RP_VAPORISE, 86, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_FUMUS_VOW(Prayer.RP_FUMUS_VOW, 87, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_UMBRA_VOW(Prayer.RP_UMBRA_VOW, 88, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_CRUORS_VOW(Prayer.RP_CRUORS_VOW, 89, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_GLACIES_VOW(Prayer.RP_GLACIES_VOW, 90, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_WRATH(Prayer.RP_WRATH, 91, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
    RP_INTENSIFY(Prayer.RP_INTENSIFY, 92, Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE);

    /**
     * The RuneLite Prayer enum value.
     */
    private final Prayer prayer;

    /**
     * Required Prayer level.
     */
    private final int requiredLevel;

    /**
     * Required quest, or null if no quest required.
     */
    @Nullable
    private final Quest requiredQuest;

    PrayerUnlock(Prayer prayer, int requiredLevel, @Nullable Quest requiredQuest) {
        this.prayer = prayer;
        this.requiredLevel = requiredLevel;
        this.requiredQuest = requiredQuest;
    }

    /**
     * Check if this prayer requires a quest completion.
     *
     * @return true if quest is required
     */
    public boolean requiresQuest() {
        return requiredQuest != null;
    }

    /**
     * Find the PrayerUnlock for a given RuneLite Prayer.
     *
     * @param prayer the RuneLite Prayer enum
     * @return PrayerUnlock or null if not found
     */
    @Nullable
    public static PrayerUnlock forPrayer(Prayer prayer) {
        for (PrayerUnlock unlock : values()) {
            if (unlock.prayer == prayer) {
                return unlock;
            }
        }
        return null;
    }

    /**
     * Get the protection prayer for a specific attack style.
     *
     * @param style melee, ranged, or magic
     * @return the appropriate protection prayer unlock
     */
    public static PrayerUnlock getProtectionPrayer(com.rocinante.state.AttackStyle style) {
        switch (style) {
            case MELEE:
                return PROTECT_FROM_MELEE;
            case RANGED:
                return PROTECT_FROM_MISSILES;
            case MAGIC:
                return PROTECT_FROM_MAGIC;
            default:
                return PROTECT_FROM_MELEE; // Default to melee protection
        }
    }

    /**
     * Check if this is a protection prayer.
     *
     * @return true if this is a protection prayer
     */
    public boolean isProtectionPrayer() {
        return this == PROTECT_FROM_MAGIC || 
               this == PROTECT_FROM_MISSILES || 
               this == PROTECT_FROM_MELEE;
    }

    /**
     * Check if this is an offensive prayer (stat boosting).
     *
     * @return true if this boosts offensive stats
     */
    public boolean isOffensivePrayer() {
        switch (this) {
            case BURST_OF_STRENGTH:
            case SUPERHUMAN_STRENGTH:
            case ULTIMATE_STRENGTH:
            case CLARITY_OF_THOUGHT:
            case IMPROVED_REFLEXES:
            case INCREDIBLE_REFLEXES:
            case SHARP_EYE:
            case HAWK_EYE:
            case EAGLE_EYE:
            case MYSTIC_WILL:
            case MYSTIC_LORE:
            case MYSTIC_MIGHT:
            case CHIVALRY:
            case PIETY:
            case RIGOUR:
            case AUGURY:
                return true;
            default:
                return false;
        }
    }
}

