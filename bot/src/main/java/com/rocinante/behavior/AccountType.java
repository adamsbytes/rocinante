package com.rocinante.behavior;

import lombok.Getter;

/**
 * OSRS account types with their behavioral implications.
 * 
 * Account type affects:
 * - Behavioral interruption rules (HCIM more conservative)
 * - Break patterns (HCIM shorter sessions)
 * - Risk tolerance (HCIM flee earlier)
 * - Feature availability (Ironman no GE)
 * 
 * Detected via Varbits.ACCOUNT_TYPE (varbit 1777).
 */
@Getter
public enum AccountType {
    
    /**
     * Standard account with no restrictions.
     */
    NORMAL(false, false, false, "Normal account"),
    
    /**
     * Ironman - self-sufficient, no trading/GE.
     */
    IRONMAN(true, false, false, "Ironman"),
    
    /**
     * Hardcore Ironman - one life, permanent death.
     * Requires extra safety measures.
     */
    HARDCORE_IRONMAN(true, true, false, "Hardcore Ironman"),
    
    /**
     * Ultimate Ironman - no banking.
     */
    ULTIMATE_IRONMAN(true, false, true, "Ultimate Ironman"),
    
    /**
     * Group Ironman - shared ironman group.
     */
    GROUP_IRONMAN(true, false, false, "Group Ironman"),
    
    /**
     * Hardcore Group Ironman - group with shared lives.
     */
    HARDCORE_GROUP_IRONMAN(true, true, false, "Hardcore Group Ironman"),
    
    /**
     * Unranked Group Ironman - group without highscores.
     */
    UNRANKED_GROUP_IRONMAN(true, false, false, "Unranked Group Ironman");
    
    /**
     * Whether this account type has ironman restrictions (no GE/trading).
     */
    private final boolean ironman;
    
    /**
     * Whether this account type has permadeath (extra safety needed).
     */
    private final boolean hardcore;
    
    /**
     * Whether this account type cannot use banks (UIM).
     */
    private final boolean ultimate;
    
    /**
     * Human-readable account type name.
     */
    private final String displayName;
    
    AccountType(boolean ironman, boolean hardcore, boolean ultimate, String displayName) {
        this.ironman = ironman;
        this.hardcore = hardcore;
        this.ultimate = ultimate;
        this.displayName = displayName;
    }
    
    /**
     * Check if this account type is any form of ironman.
     * 
     * @return true if ironman restrictions apply
     */
    public boolean isIronman() {
        return ironman;
    }
    
    /**
     * Check if this account has permadeath (HCIM or HCGIM).
     * These accounts require extra safety measures:
     * - No attention lapses during combat
     * - Lower flee thresholds
     * - No risky activities
     * 
     * @return true if permadeath applies
     */
    public boolean isHardcore() {
        return hardcore;
    }
    
    /**
     * Check if this account cannot use banks (UIM).
     * 
     * @return true if banking is restricted
     */
    public boolean isUltimate() {
        return ultimate;
    }
    
    /**
     * Check if this is a group ironman mode.
     * 
     * @return true if group ironman
     */
    public boolean isGroup() {
        return this == GROUP_IRONMAN || this == HARDCORE_GROUP_IRONMAN || this == UNRANKED_GROUP_IRONMAN;
    }
    
    /**
     * Detect account type from varbit value.
     * Varbit 1777 (ACCOUNT_TYPE):
     * 0 = Normal
     * 1 = Ironman
     * 2 = Ultimate Ironman
     * 3 = Hardcore Ironman
     * 4 = Group Ironman
     * 5 = Hardcore Group Ironman
     * 6 = Unranked Group Ironman
     * 
     * @param varbitValue the value of varbit 1777
     * @return the corresponding AccountType
     */
    public static AccountType fromVarbit(int varbitValue) {
        return switch (varbitValue) {
            case 0 -> NORMAL;
            case 1 -> IRONMAN;
            case 2 -> ULTIMATE_IRONMAN;
            case 3 -> HARDCORE_IRONMAN;
            case 4 -> GROUP_IRONMAN;
            case 5 -> HARDCORE_GROUP_IRONMAN;
            case 6 -> UNRANKED_GROUP_IRONMAN;
            default -> IRONMAN; // Unknown future types, treat as ironman to be safe
        };
    }
    
    /**
     * Get the break fatigue threshold modifier for this account type.
     * HCIM takes breaks earlier (more conservative).
     * 
     * @return multiplier for break threshold (lower = earlier breaks)
     */
    public double getBreakThresholdModifier() {
        if (hardcore) {
            return 0.85; // HCIM breaks 15% earlier
        }
        return 1.0;
    }
    
    /**
     * Get the flee health threshold modifier for this account type.
     * HCIM flees at higher health.
     * 
     * @return multiplier for flee threshold (higher = flee earlier)
     */
    public double getFleeThresholdModifier() {
        if (hardcore) {
            return 1.5; // HCIM flees at 50% higher HP threshold
        }
        return 1.0;
    }
}

