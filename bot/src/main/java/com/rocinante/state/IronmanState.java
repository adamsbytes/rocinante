package com.rocinante.state;

import com.rocinante.behavior.AccountType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Varbits;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Tracks ironman account type and restrictions.
 * 
 * This is the single source of truth for:
 * - Intended account type (from WebUI config via environment variables)
 * - Actual account type (from varbit 1777 after game login)
 * - Ironman restrictions (GE blocking, trade restrictions, etc.)
 * - HCIM safety level configuration
 * 
 * Architecture:
 * - Environment variables set by WebUI/Docker: IRONMAN_MODE, IRONMAN_TYPE, HCIM_SAFETY_LEVEL
 * - PlayerProfile uses intendedType for profile generation before Tutorial Island
 * - After Tutorial Island, actualType is synced from varbit 1777
 * - All behavioral systems query this class for account type
 * 
 * State reconciliation:
 * - Before Tutorial Island: Use intendedType (actualType is NORMAL until Paul dialogue)
 * - During Tutorial Island: TutorialIsland talks to Paul based on intendedType
 * - After Tutorial Island: actualType matches intendedType (or user chose different)
 * - Post-tutorial: actualType is authoritative, intendedType is historical
 */
@Slf4j
@Singleton
public class IronmanState {

    /**
     * Safety level for HCIM accounts.
     */
    public enum HCIMSafetyLevel {
        /** Normal caution - standard flee thresholds */
        NORMAL(1.0),
        
        /** Extra cautious - flee earlier, avoid more risks */
        CAUTIOUS(1.3),
        
        /** Maximum paranoia - flee very early, avoid all avoidable risks */
        PARANOID(1.6);
        
        @Getter
        private final double fleeThresholdMultiplier;
        
        HCIMSafetyLevel(double fleeThresholdMultiplier) {
            this.fleeThresholdMultiplier = fleeThresholdMultiplier;
        }
        
        public static HCIMSafetyLevel fromString(@Nullable String value) {
            if (value == null || value.isEmpty()) {
                return NORMAL;
            }
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown HCIM safety level: {}, defaulting to NORMAL", value);
                return NORMAL;
            }
        }
    }

    private final Client client;

    /**
     * Intended account type from WebUI configuration.
     * Set at bot creation, used for Tutorial Island and profile generation.
     * After tutorial, this becomes historical/informational only.
     */
    @Getter
    private AccountType intendedType = AccountType.NORMAL;

    /**
     * Actual account type detected from game varbit 1777.
     * This is the authoritative value once logged in.
     */
    @Getter
    private AccountType actualType = AccountType.NORMAL;

    /**
     * Safety level for HCIM accounts (from config).
     */
    @Getter
    private HCIMSafetyLevel hcimSafetyLevel = HCIMSafetyLevel.NORMAL;

    /**
     * Whether Tutorial Island has been completed.
     * After tutorial, actualType becomes authoritative.
     */
    @Getter
    private boolean tutorialCompleted = false;

    /**
     * Last known varbit value for change detection.
     */
    private int lastVarbitValue = -1;

    @Inject
    public IronmanState(Client client) {
        this.client = client;
        loadFromEnvironment();
        log.info("IronmanState initialized: intended={}, safety={}", intendedType, hcimSafetyLevel);
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Load intended account type from environment variables.
     * Called at plugin startup, reads configuration from WebUI/Docker.
     * 
     * Environment variables:
     * - IRONMAN_MODE: "true" or "false"
     * - IRONMAN_TYPE: "STANDARD_IRONMAN", "HARDCORE_IRONMAN", or "ULTIMATE_IRONMAN"
     * - HCIM_SAFETY_LEVEL: "NORMAL", "CAUTIOUS", or "PARANOID"
     */
    private void loadFromEnvironment() {
        String ironmanMode = System.getenv("IRONMAN_MODE");
        String ironmanType = System.getenv("IRONMAN_TYPE");
        String safetyLevel = System.getenv("HCIM_SAFETY_LEVEL");

        if ("true".equalsIgnoreCase(ironmanMode)) {
            // Parse ironman type
            if ("STANDARD_IRONMAN".equalsIgnoreCase(ironmanType)) {
                intendedType = AccountType.IRONMAN;
            } else if ("HARDCORE_IRONMAN".equalsIgnoreCase(ironmanType)) {
                intendedType = AccountType.HARDCORE_IRONMAN;
                hcimSafetyLevel = HCIMSafetyLevel.fromString(safetyLevel);
            } else if ("ULTIMATE_IRONMAN".equalsIgnoreCase(ironmanType)) {
                intendedType = AccountType.ULTIMATE_IRONMAN;
            } else {
                log.warn("IRONMAN_MODE=true but invalid IRONMAN_TYPE: {}, defaulting to STANDARD_IRONMAN", 
                        ironmanType);
                intendedType = AccountType.IRONMAN;
            }
        } else {
            // Normal account
            intendedType = AccountType.NORMAL;
        }

        log.info("Loaded ironman config from environment: intended={}, safety={}", 
                intendedType, hcimSafetyLevel);
    }

    // ========================================================================
    // Varbit Synchronization
    // ========================================================================

    /**
     * Update actual account type from varbit 1777.
     * Should be called each game tick when logged in.
     */
    public void updateFromVarbit() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        try {
            int varbitValue = client.getVarbitValue(Varbits.ACCOUNT_TYPE);
            
            // Only log when value changes
            if (varbitValue != lastVarbitValue) {
                AccountType newActualType = AccountType.fromVarbit(varbitValue);
                
                if (newActualType != actualType) {
                    log.info("Account type changed: {} -> {} (varbit: {})", 
                            actualType, newActualType, varbitValue);
                    actualType = newActualType;
                    
                    // Check if there's a mismatch with intended type
                    if (!tutorialCompleted && newActualType != intendedType && intendedType != AccountType.NORMAL) {
                        log.warn("Account type mismatch detected: intended={}, actual={}", 
                                intendedType, newActualType);
                    }
                }
                
                lastVarbitValue = varbitValue;
            }
        } catch (Exception e) {
            log.debug("Could not read account type varbit: {}", e.getMessage());
        }
    }
    
    /**
     * Check if there's a mismatch between intended and actual account types.
     * This can happen if:
     * - User selected wrong option when talking to Paul
     * - Varbit didn't update correctly  
     * - Account was already configured before bot took over
     * - Player manually changed the selection
     * 
     * @return true if mismatch exists and needs correction
     */
    public boolean hasMismatch() {
        // No mismatch if intended is normal (default/don't care)
        if (intendedType == AccountType.NORMAL) {
            return false;
        }
        
        // After tutorial, some mismatch is expected if user chose differently
        // But before tutorial completion, mismatch means we need to fix it
        return !tutorialCompleted && actualType != intendedType;
    }
    
    /**
     * Get the mismatch description for logging/debugging.
     * 
     * @return description of the mismatch, or null if no mismatch
     */
    @Nullable
    public String getMismatchDescription() {
        if (!hasMismatch()) {
            return null;
        }
        return String.format("Intended: %s, Actual: %s", intendedType, actualType);
    }
    
    /**
     * Correct the intended type to match actual type.
     * Used when player manually chooses different mode than configured.
     * This overwrites the config to match reality.
     * 
     * After Tutorial Island, this ensures intendedType reflects what actually happened.
     */
    public void reconcileToActual() {
        if (actualType != intendedType) {
            log.info("Reconciling intended type to match actual: {} -> {}", intendedType, actualType);
            intendedType = actualType;
        }
    }
    
    /**
     * Override the actual type to match intended.
     * Used when re-selecting after a mistake or when account was pre-configured wrong.
     * This is what IronmanSelectionTask tries to achieve.
     * 
     * @param newActualType the new actual type (after selection)
     */
    public void setActualType(AccountType newActualType) {
        if (newActualType != actualType) {
            log.info("Actual account type updated: {} -> {}", actualType, newActualType);
            actualType = newActualType;
        }
    }

    /**
     * Mark Tutorial Island as completed.
     * After this point, actualType becomes the authoritative source.
     */
    public void markTutorialCompleted() {
        tutorialCompleted = true;
        log.info("Tutorial Island completed - actualType is now authoritative: {}", actualType);
    }

    // ========================================================================
    // Account Type Queries
    // ========================================================================

    /**
     * Get the account type to use for behavioral decisions.
     * 
     * Before tutorial completion: Uses intendedType (for profile gen, Paul dialogue)
     * After tutorial completion: Uses actualType (authoritative from game state)
     * 
     * @return the account type to use
     */
    public AccountType getEffectiveType() {
        return tutorialCompleted ? actualType : intendedType;
    }

    /**
     * Check if this is any form of ironman account.
     * 
     * @return true if ironman restrictions apply
     */
    public boolean isIronman() {
        return getEffectiveType().isIronman();
    }

    /**
     * Check if this is a hardcore ironman account (permadeath).
     * 
     * @return true if hardcore restrictions apply
     */
    public boolean isHardcore() {
        return getEffectiveType().isHardcore();
    }

    /**
     * Check if this is an ultimate ironman account (no banking).
     * 
     * @return true if ultimate restrictions apply
     */
    public boolean isUltimate() {
        return getEffectiveType().isUltimate();
    }

    /**
     * Check if this is a group ironman account.
     * 
     * @return true if group ironman
     */
    public boolean isGroup() {
        return getEffectiveType().isGroup();
    }

    // ========================================================================
    // Ironman Restrictions
    // ========================================================================

    /**
     * Check if Grand Exchange is available.
     * Ironman accounts cannot use GE.
     * 
     * @return true if GE can be used
     */
    public boolean canUseGrandExchange() {
        return !isIronman();
    }

    /**
     * Check if trading with other players is allowed.
     * Ironman accounts cannot trade.
     * 
     * @return true if trading is allowed
     */
    public boolean canTrade() {
        return !isIronman();
    }

    /**
     * Check if banking is allowed.
     * Ultimate Ironman cannot use banks.
     * 
     * @return true if banking is allowed
     */
    public boolean canUseBank() {
        return !isUltimate();
    }

    // ========================================================================
    // HCIM Safety Configuration
    // ========================================================================

    /**
     * Get the flee health threshold multiplier for HCIM safety level.
     * Higher values = flee earlier.
     * 
     * @return multiplier (1.0 = normal, 1.3 = cautious, 1.6 = paranoid)
     */
    public double getFleeThresholdMultiplier() {
        if (!isHardcore()) {
            return 1.0;
        }
        return hcimSafetyLevel.getFleeThresholdMultiplier();
    }

    /**
     * Check if an activity is too risky for the current HCIM safety level.
     * 
     * @param activityRiskLevel estimated death probability (0.0-1.0)
     * @return true if activity should be blocked
     */
    public boolean isTooRisky(double activityRiskLevel) {
        if (!isHardcore()) {
            return false; // Normal/Ironman don't care about death
        }

        // Risk thresholds by safety level
        double maxRisk = switch (hcimSafetyLevel) {
            case NORMAL -> 0.05;    // Accept up to 5% death risk
            case CAUTIOUS -> 0.02;  // Accept up to 2% death risk
            case PARANOID -> 0.005; // Accept only 0.5% death risk
        };

        return activityRiskLevel > maxRisk;
    }

    // ========================================================================
    // Status & Debugging
    // ========================================================================

    /**
     * Get a summary of ironman state for logging/debugging.
     * 
     * @return summary string
     */
    public String getSummary() {
        return String.format(
                "IronmanState[intended=%s, actual=%s, effective=%s, tutorialDone=%s, safety=%s]",
                intendedType, actualType, getEffectiveType(), tutorialCompleted, hcimSafetyLevel
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

