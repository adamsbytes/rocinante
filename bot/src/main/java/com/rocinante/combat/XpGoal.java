package com.rocinante.combat;

import lombok.Getter;

/**
 * XP training goals for combat.
 *
 * Determines which skill(s) receive XP from combat actions.
 * The XP goal must be compatible with the weapon style being used.
 */
@Getter
public enum XpGoal {

    // ========================================================================
    // Melee XP Goals
    // ========================================================================

    /**
     * Train Attack XP - improves accuracy.
     * Available with: Accurate stance on most melee weapons.
     */
    ATTACK("Attack", false),

    /**
     * Train Strength XP - improves max hit.
     * Available with: Aggressive stance on most melee weapons.
     */
    STRENGTH("Strength", false),

    /**
     * Train Defence XP - improves defense.
     * Available with: Defensive/Block stance on most weapons.
     */
    DEFENCE("Defence", false),

    /**
     * Train Attack, Strength, and Defence equally (shared XP).
     * Available with: Controlled stance on swords, scimitars, etc.
     */
    SHARED("Shared", false),

    // ========================================================================
    // Magic XP Goals
    // ========================================================================

    /**
     * Train Magic XP only.
     * Default for spellcasting.
     */
    MAGIC("Magic", false),

    /**
     * Train Magic and Defence XP.
     * Available with: Defensive casting.
     */
    MAGIC_DEFENCE("Magic + Defence", false),

    // ========================================================================
    // Ranged XP Goals
    // ========================================================================

    /**
     * Train Ranged XP only.
     * Available with: Accurate/Rapid stance on ranged weapons.
     */
    RANGED("Ranged", false),

    /**
     * Train Ranged and Defence XP.
     * Available with: Longrange stance on ranged weapons.
     */
    RANGED_DEFENCE("Ranged + Defence", false),

    // ========================================================================
    // Meta Goals
    // ========================================================================

    /**
     * No preference - use default for weapon/style.
     */
    ANY("Any", true);

    private final String displayName;
    private final boolean isDefault;

    XpGoal(String displayName, boolean isDefault) {
        this.displayName = displayName;
        this.isDefault = isDefault;
    }

    /**
     * Check if this XP goal is compatible with a weapon style.
     *
     * @param style the weapon style
     * @return true if compatible
     */
    public boolean isCompatibleWith(WeaponStyle style) {
        if (this == ANY || style == WeaponStyle.ANY) {
            return true;
        }

        switch (style) {
            case SLASH:
            case STAB:
            case CRUSH:
                // Melee styles work with Attack, Strength, Defence, or Shared
                return this == ATTACK || this == STRENGTH || this == DEFENCE || this == SHARED;
            case MAGIC:
                // Magic works with Magic or Magic+Defence
                return this == MAGIC || this == MAGIC_DEFENCE || this == DEFENCE;
            case RANGED:
                // Ranged works with Ranged or Ranged+Defence
                return this == RANGED || this == RANGED_DEFENCE || this == DEFENCE;
            default:
                return false;
        }
    }

    /**
     * Get the best XP goal for a given weapon style.
     *
     * @param style the weapon style
     * @param preferDefence if true, prefer defensive options
     * @return the best XP goal
     */
    public static XpGoal getDefaultFor(WeaponStyle style, boolean preferDefence) {
        switch (style) {
            case SLASH:
            case STAB:
            case CRUSH:
                return preferDefence ? DEFENCE : STRENGTH;
            case MAGIC:
                return preferDefence ? MAGIC_DEFENCE : MAGIC;
            case RANGED:
                return preferDefence ? RANGED_DEFENCE : RANGED;
            default:
                return ANY;
        }
    }

    /**
     * Get the combat interface slot index for this XP goal with a given weapon style.
     * Returns the button to click in the combat options interface.
     *
     * Note: Actual slot depends on the weapon type. This provides a general mapping.
     * For exact behavior, would need weapon-specific lookup.
     *
     * @param style the weapon style being used
     * @return slot index (0-3), or -1 if not applicable
     */
    public int getCombatSlotFor(WeaponStyle style) {
        if (!isCompatibleWith(style)) {
            return -1;
        }

        // General mapping for most weapons:
        // Slot 0: Accurate (Attack XP)
        // Slot 1: Aggressive (Strength XP)  
        // Slot 2: Controlled (Shared XP) OR another style
        // Slot 3: Defensive (Defence XP)

        switch (this) {
            case ATTACK:
                return 0; // Accurate
            case STRENGTH:
                return 1; // Aggressive
            case SHARED:
                return 2; // Controlled
            case DEFENCE:
                return 3; // Defensive/Block
            case MAGIC:
                return 0; // Standard casting
            case MAGIC_DEFENCE:
                return 1; // Defensive casting
            case RANGED:
                return 0; // Accurate or Rapid (depends on weapon)
            case RANGED_DEFENCE:
                return 3; // Longrange
            default:
                return -1;
        }
    }
}

