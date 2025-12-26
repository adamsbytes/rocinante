package com.rocinante.combat.spell;

import lombok.Getter;

/**
 * Standard spellbook combat spells.
 *
 * These are the basic combat spells available to all accounts.
 * Widget IDs use the format from InterfaceID.MagicSpellbook.
 */
@Getter
public enum StandardSpell implements CombatSpell {

    // ========================================================================
    // Strike Spells (Tier 1)
    // ========================================================================

    WIND_STRIKE("Wind Strike", 0x00da_0008, 1, 2, true),
    WATER_STRIKE("Water Strike", 0x00da_000b, 5, 4, true),
    EARTH_STRIKE("Earth Strike", 0x00da_000e, 9, 6, true),
    FIRE_STRIKE("Fire Strike", 0x00da_0010, 13, 8, true),

    // ========================================================================
    // Bolt Spells (Tier 2)
    // ========================================================================

    WIND_BOLT("Wind Bolt", 0x00da_0012, 17, 9, true),
    WATER_BOLT("Water Bolt", 0x00da_0016, 23, 10, true),
    EARTH_BOLT("Earth Bolt", 0x00da_0019, 29, 11, true),
    FIRE_BOLT("Fire Bolt", 0x00da_001c, 35, 12, true),

    // ========================================================================
    // Blast Spells (Tier 3)
    // ========================================================================

    WIND_BLAST("Wind Blast", 0x00da_0020, 41, 13, true),
    WATER_BLAST("Water Blast", 0x00da_0023, 47, 14, true),
    EARTH_BLAST("Earth Blast", 0x00da_002a, 53, 15, true),
    FIRE_BLAST("Fire Blast", 0x00da_0030, 59, 16, true),

    // ========================================================================
    // Wave Spells (Tier 4)
    // ========================================================================

    WIND_WAVE("Wind Wave", 0x00da_0037, 62, 17, true),
    WATER_WAVE("Water Wave", 0x00da_003a, 65, 18, true),
    EARTH_WAVE("Earth Wave", 0x00da_003e, 70, 19, true),
    FIRE_WAVE("Fire Wave", 0x00da_0041, 75, 20, true),

    // ========================================================================
    // Surge Spells (Tier 5)
    // ========================================================================

    WIND_SURGE("Wind Surge", 0x00da_0045, 81, 21, true),
    WATER_SURGE("Water Surge", 0x00da_0047, 85, 22, true),
    EARTH_SURGE("Earth Surge", 0x00da_004c, 90, 23, true),
    FIRE_SURGE("Fire Surge", 0x00da_004e, 95, 24, true),

    // ========================================================================
    // God Spells (Charge required for full damage)
    // ========================================================================

    SARADOMIN_STRIKE("Saradomin Strike", 0x00da_0033, 60, 20, false),
    CLAWS_OF_GUTHIX("Claws of Guthix", 0x00da_0034, 60, 20, false),
    FLAMES_OF_ZAMORAK("Flames of Zamorak", 0x00da_0035, 60, 20, false),

    // ========================================================================
    // Special Combat Spells
    // ========================================================================

    CRUMBLE_UNDEAD("Crumble Undead", 0x00da_001e, 39, 15, false),
    IBAN_BLAST("Iban Blast", 0x00da_0026, 50, 25, true),
    MAGIC_DART("Magic Dart", 0x00da_0028, 50, 10, true), // Max hit scales with magic level

    // ========================================================================
    // Bind Spells (for kiting/safespotting)
    // ========================================================================

    BIND("Bind", 0x00da_0014, 20, 0, false),
    SNARE("Snare", 0x00da_0027, 50, 0, false),
    ENTANGLE("Entangle", 0x00da_0042, 79, 0, false);

    // ========================================================================
    // Fields
    // ========================================================================

    private final String spellName;
    private final int widgetId;
    private final int requiredLevel;
    private final int baseMaxHit;
    private final boolean autocastable;

    StandardSpell(String spellName, int widgetId, int requiredLevel, int baseMaxHit, boolean autocastable) {
        this.spellName = spellName;
        this.widgetId = widgetId;
        this.requiredLevel = requiredLevel;
        this.baseMaxHit = baseMaxHit;
        this.autocastable = autocastable;
    }

    @Override
    public Spellbook getSpellbook() {
        return Spellbook.STANDARD;
    }

    @Override
    public boolean hasSpecialEffect() {
        // Bind spells have freeze effect
        return this == BIND || this == SNARE || this == ENTANGLE;
    }

    /**
     * Get the freeze duration in ticks (if this is a bind spell).
     *
     * @return freeze duration in game ticks, or 0 if not a bind spell
     */
    public int getFreezeDurationTicks() {
        switch (this) {
            case BIND: return 8; // 4.8 seconds
            case SNARE: return 16; // 9.6 seconds
            case ENTANGLE: return 24; // 14.4 seconds
            default: return 0;
        }
    }

    /**
     * Check if this spell works on undead only.
     *
     * @return true if only affects undead
     */
    public boolean isUndeadOnly() {
        return this == CRUMBLE_UNDEAD;
    }

    /**
     * Get the spell by name (case-insensitive).
     *
     * @param name the spell name
     * @return the spell, or null if not found
     */
    public static StandardSpell fromName(String name) {
        for (StandardSpell spell : values()) {
            if (spell.spellName.equalsIgnoreCase(name)) {
                return spell;
            }
        }
        return null;
    }

    /**
     * Get all combat spells (excludes bind spells).
     *
     * @return array of combat spells
     */
    public static StandardSpell[] getCombatSpells() {
        return new StandardSpell[] {
                WIND_STRIKE, WATER_STRIKE, EARTH_STRIKE, FIRE_STRIKE,
                WIND_BOLT, WATER_BOLT, EARTH_BOLT, FIRE_BOLT,
                WIND_BLAST, WATER_BLAST, EARTH_BLAST, FIRE_BLAST,
                WIND_WAVE, WATER_WAVE, EARTH_WAVE, FIRE_WAVE,
                WIND_SURGE, WATER_SURGE, EARTH_SURGE, FIRE_SURGE,
                SARADOMIN_STRIKE, CLAWS_OF_GUTHIX, FLAMES_OF_ZAMORAK,
                CRUMBLE_UNDEAD, IBAN_BLAST, MAGIC_DART
        };
    }

    /**
     * Get all elemental spells of a specific tier.
     *
     * @param tier 1=strike, 2=bolt, 3=blast, 4=wave, 5=surge
     * @return array of spells in that tier
     */
    public static StandardSpell[] getElementalTier(int tier) {
        switch (tier) {
            case 1: return new StandardSpell[] { WIND_STRIKE, WATER_STRIKE, EARTH_STRIKE, FIRE_STRIKE };
            case 2: return new StandardSpell[] { WIND_BOLT, WATER_BOLT, EARTH_BOLT, FIRE_BOLT };
            case 3: return new StandardSpell[] { WIND_BLAST, WATER_BLAST, EARTH_BLAST, FIRE_BLAST };
            case 4: return new StandardSpell[] { WIND_WAVE, WATER_WAVE, EARTH_WAVE, FIRE_WAVE };
            case 5: return new StandardSpell[] { WIND_SURGE, WATER_SURGE, EARTH_SURGE, FIRE_SURGE };
            default: return new StandardSpell[0];
        }
    }
}

