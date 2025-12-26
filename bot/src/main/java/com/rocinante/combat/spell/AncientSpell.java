package com.rocinante.combat.spell;

import lombok.Getter;

/**
 * Ancient Magicks combat spells.
 *
 * Ancient Magicks are unlocked after completing Desert Treasure.
 * All spells have special effects (freeze, poison, heal, reduce stats).
 */
@Getter
public enum AncientSpell implements CombatSpell {

    // ========================================================================
    // Smoke Spells (Poison effect)
    // ========================================================================

    SMOKE_RUSH("Smoke Rush", 0x00da_0059, 50, 13, true, false, SpellEffect.POISON),
    SMOKE_BURST("Smoke Burst", 0x00da_005b, 62, 17, true, true, SpellEffect.POISON),
    SMOKE_BLITZ("Smoke Blitz", 0x00da_005a, 74, 23, true, false, SpellEffect.POISON),
    SMOKE_BARRAGE("Smoke Barrage", 0x00da_005c, 86, 27, true, true, SpellEffect.POISON),

    // ========================================================================
    // Shadow Spells (Reduce attack level)
    // ========================================================================

    SHADOW_RUSH("Shadow Rush", 0x00da_005d, 52, 14, true, false, SpellEffect.REDUCE_ATTACK),
    SHADOW_BURST("Shadow Burst", 0x00da_005f, 64, 18, true, true, SpellEffect.REDUCE_ATTACK),
    SHADOW_BLITZ("Shadow Blitz", 0x00da_005e, 76, 24, true, false, SpellEffect.REDUCE_ATTACK),
    SHADOW_BARRAGE("Shadow Barrage", 0x00da_0060, 88, 28, true, true, SpellEffect.REDUCE_ATTACK),

    // ========================================================================
    // Blood Spells (Heal caster)
    // ========================================================================

    BLOOD_RUSH("Blood Rush", 0x00da_0055, 56, 15, true, false, SpellEffect.HEAL),
    BLOOD_BURST("Blood Burst", 0x00da_0057, 68, 21, true, true, SpellEffect.HEAL),
    BLOOD_BLITZ("Blood Blitz", 0x00da_0056, 80, 25, true, false, SpellEffect.HEAL),
    BLOOD_BARRAGE("Blood Barrage", 0x00da_0058, 92, 29, true, true, SpellEffect.HEAL),

    // ========================================================================
    // Ice Spells (Freeze effect)
    // ========================================================================

    ICE_RUSH("Ice Rush", 0x00da_0051, 58, 16, true, false, SpellEffect.FREEZE),
    ICE_BURST("Ice Burst", 0x00da_0053, 70, 22, true, true, SpellEffect.FREEZE),
    ICE_BLITZ("Ice Blitz", 0x00da_0052, 82, 26, true, false, SpellEffect.FREEZE),
    ICE_BARRAGE("Ice Barrage", 0x00da_0054, 94, 30, true, true, SpellEffect.FREEZE);

    // ========================================================================
    // Spell Effect Types
    // ========================================================================

    public enum SpellEffect {
        POISON,        // Smoke - poisons target
        REDUCE_ATTACK, // Shadow - reduces target's attack level
        HEAL,          // Blood - heals caster for 25% of damage dealt
        FREEZE         // Ice - freezes target in place
    }

    // ========================================================================
    // Fields
    // ========================================================================

    private final String spellName;
    private final int widgetId;
    private final int requiredLevel;
    private final int baseMaxHit;
    private final boolean autocastable;
    private final boolean areaOfEffect;
    private final SpellEffect effect;

    AncientSpell(String spellName, int widgetId, int requiredLevel, int baseMaxHit,
                 boolean autocastable, boolean areaOfEffect, SpellEffect effect) {
        this.spellName = spellName;
        this.widgetId = widgetId;
        this.requiredLevel = requiredLevel;
        this.baseMaxHit = baseMaxHit;
        this.autocastable = autocastable;
        this.areaOfEffect = areaOfEffect;
        this.effect = effect;
    }

    @Override
    public Spellbook getSpellbook() {
        return Spellbook.ANCIENT;
    }

    @Override
    public boolean isAreaOfEffect() {
        return areaOfEffect;
    }

    @Override
    public boolean hasSpecialEffect() {
        return true; // All ancient spells have effects
    }

    /**
     * Get the freeze duration in ticks (if this is an ice spell).
     *
     * @return freeze duration in game ticks, or 0 if not ice spell
     */
    public int getFreezeDurationTicks() {
        if (effect != SpellEffect.FREEZE) {
            return 0;
        }
        switch (this) {
            case ICE_RUSH: return 8;   // 4.8 seconds
            case ICE_BURST: return 16;  // 9.6 seconds
            case ICE_BLITZ: return 24;  // 14.4 seconds
            case ICE_BARRAGE: return 32; // 19.2 seconds
            default: return 0;
        }
    }

    /**
     * Check if this is a smoke spell (poison effect).
     *
     * @return true if smoke spell
     */
    public boolean isSmokeSpell() {
        return effect == SpellEffect.POISON;
    }

    /**
     * Check if this is a blood spell (heal effect).
     *
     * @return true if blood spell
     */
    public boolean isBloodSpell() {
        return effect == SpellEffect.HEAL;
    }

    /**
     * Check if this is an ice spell (freeze effect).
     *
     * @return true if ice spell
     */
    public boolean isIceSpell() {
        return effect == SpellEffect.FREEZE;
    }

    /**
     * Check if this is a shadow spell (reduce attack effect).
     *
     * @return true if shadow spell
     */
    public boolean isShadowSpell() {
        return effect == SpellEffect.REDUCE_ATTACK;
    }

    /**
     * Get the spell tier (1=rush, 2=burst, 3=blitz, 4=barrage).
     *
     * @return spell tier
     */
    public int getTier() {
        String name = name();
        if (name.contains("RUSH")) return 1;
        if (name.contains("BURST")) return 2;
        if (name.contains("BLITZ")) return 3;
        if (name.contains("BARRAGE")) return 4;
        return 0;
    }

    /**
     * Get the spell by name (case-insensitive).
     *
     * @param name the spell name
     * @return the spell, or null if not found
     */
    public static AncientSpell fromName(String name) {
        for (AncientSpell spell : values()) {
            if (spell.spellName.equalsIgnoreCase(name)) {
                return spell;
            }
        }
        return null;
    }

    /**
     * Get all barrage spells (highest tier AoE).
     *
     * @return array of barrage spells
     */
    public static AncientSpell[] getBarrageSpells() {
        return new AncientSpell[] {
                SMOKE_BARRAGE, SHADOW_BARRAGE, BLOOD_BARRAGE, ICE_BARRAGE
        };
    }

    /**
     * Get all single-target spells (rush and blitz).
     *
     * @return array of single-target spells
     */
    public static AncientSpell[] getSingleTargetSpells() {
        return new AncientSpell[] {
                SMOKE_RUSH, SMOKE_BLITZ,
                SHADOW_RUSH, SHADOW_BLITZ,
                BLOOD_RUSH, BLOOD_BLITZ,
                ICE_RUSH, ICE_BLITZ
        };
    }

    /**
     * Get all spells with a specific effect.
     *
     * @param effect the effect type
     * @return array of spells with that effect
     */
    public static AncientSpell[] getByEffect(SpellEffect effect) {
        return java.util.Arrays.stream(values())
                .filter(s -> s.effect == effect)
                .toArray(AncientSpell[]::new);
    }
}

