package com.rocinante.combat.spell;

/**
 * Interface for combat spells.
 *
 * Combat spells can be cast on NPCs during magic combat.
 * Implementations provide spell metadata including widget IDs,
 * required magic level, max hit, and rune costs.
 */
public interface CombatSpell {

    /**
     * Get the display name of the spell.
     *
     * @return spell name as shown in game
     */
    String getSpellName();

    /**
     * Get the widget ID for clicking the spell in the spellbook.
     * Uses the packed widget format (group << 16 | child).
     *
     * @return packed widget ID
     */
    int getWidgetId();

    /**
     * Get the required magic level to cast this spell.
     *
     * @return minimum magic level
     */
    int getRequiredLevel();

    /**
     * Get the base max hit of this spell (without bonuses).
     *
     * @return base max hit, or 0 for non-combat spells
     */
    int getBaseMaxHit();

    /**
     * Get the spellbook this spell belongs to.
     *
     * @return the spellbook
     */
    Spellbook getSpellbook();

    /**
     * Check if this spell can be autocast.
     *
     * @return true if autocastable
     */
    boolean isAutocastable();

    /**
     * Check if this is an area-of-effect spell.
     *
     * @return true if AoE
     */
    default boolean isAreaOfEffect() {
        return false;
    }

    /**
     * Check if this spell has a special effect (freeze, poison, etc.).
     *
     * @return true if has special effect
     */
    default boolean hasSpecialEffect() {
        return false;
    }

    /**
     * Get the attack range of this spell in tiles.
     *
     * @return attack range (default 10)
     */
    default int getRange() {
        return 10;
    }
}

