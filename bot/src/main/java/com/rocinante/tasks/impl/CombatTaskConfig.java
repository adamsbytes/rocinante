package com.rocinante.tasks.impl;

import com.rocinante.combat.TargetSelectorConfig;
import com.rocinante.combat.WeaponStyle;
import com.rocinante.combat.XpGoal;
import com.rocinante.combat.spell.CombatSpell;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Configuration for CombatTask behavior.
 *
 * Per REQUIREMENTS.md Section 5.4.9, configures:
 * <ul>
 *   <li>Target selection (via TargetSelectorConfig)</li>
 *   <li>Completion conditions (kill count, duration)</li>
 *   <li>Safe-spotting parameters</li>
 *   <li>Loot collection settings</li>
 *   <li>Resource management strategy</li>
 * </ul>
 */
@Value
@Builder
public class CombatTaskConfig {

    /**
     * Default configuration: basic melee combat with looting.
     */
    public static final CombatTaskConfig DEFAULT = CombatTaskConfig.builder()
            .targetConfig(TargetSelectorConfig.DEFAULT)
            .killCount(-1) // No limit
            .maxDuration(Duration.ofMinutes(30))
            .useSafeSpot(false)
            .lootEnabled(true)
            .lootMinValue(1000)
            .stopWhenOutOfFood(true)
            .stopWhenOutOfPrayerPotions(false)
            .stopWhenLowResources(false)
            .lowResourcesHpThreshold(0.30)
            .build();

    /**
     * Configuration for iron-man "bank when low" style.
     * Fights without food/potions until low HP, then banks to restore.
     */
    public static final CombatTaskConfig IRONMAN_BANK_WHEN_LOW = CombatTaskConfig.builder()
            .targetConfig(TargetSelectorConfig.DEFAULT)
            .killCount(-1)
            .maxDuration(Duration.ofMinutes(60))
            .useSafeSpot(false)
            .lootEnabled(true)
            .lootMinValue(500)
            .stopWhenOutOfFood(false) // Don't stop - we're not bringing food
            .stopWhenOutOfPrayerPotions(false)
            .stopWhenLowResources(true) // Stop when HP gets low
            .lowResourcesHpThreshold(0.35) // Bank at 35% HP
            .build();

    /**
     * Create a config for farming specific NPCs by ID.
     */
    public static CombatTaskConfig forNpcIds(int killCount, int... npcIds) {
        return CombatTaskConfig.builder()
                .targetConfig(TargetSelectorConfig.forNpcIds(npcIds))
                .killCount(killCount)
                .maxDuration(Duration.ofHours(2))
                .lootEnabled(true)
                .lootMinValue(1000)
                .build();
    }

    /**
     * Create a config for farming specific NPCs by name.
     */
    public static CombatTaskConfig forNpcNames(int killCount, String... npcNames) {
        return CombatTaskConfig.builder()
                .targetConfig(TargetSelectorConfig.forNpcNames(npcNames))
                .killCount(killCount)
                .maxDuration(Duration.ofHours(2))
                .lootEnabled(true)
                .lootMinValue(1000)
                .build();
    }

    // ========================================================================
    // Target Selection
    // ========================================================================

    /**
     * Configuration for how to select targets.
     * Delegates to TargetSelector for NPC selection.
     */
    @Builder.Default
    TargetSelectorConfig targetConfig = TargetSelectorConfig.DEFAULT;

    // ========================================================================
    // Completion Conditions
    // ========================================================================

    /**
     * Number of kills before task completes.
     * -1 means no limit (continue until other condition met).
     */
    @Builder.Default
    int killCount = -1;

    /**
     * Maximum duration before task completes.
     * Null means no time limit.
     */
    @Builder.Default
    Duration maxDuration = Duration.ofMinutes(30);

    // ========================================================================
    // Safe-Spotting (Section 5.4.9)
    // ========================================================================

    /**
     * Whether to use safe-spotting (maintain distance while attacking).
     * If true, requires safeSpotPosition to be set.
     */
    @Builder.Default
    boolean useSafeSpot = false;

    /**
     * The position to stand at for safe-spotting.
     * Only used if useSafeSpot is true.
     */
    WorldPoint safeSpotPosition;

    /**
     * Maximum distance from safe spot before re-positioning.
     * If player strays further than this, move back to safe spot.
     */
    @Builder.Default
    int safeSpotMaxDistance = 2;

    /**
     * Minimum attack distance for safe-spotting (ranged/magic).
     * Determines if player needs to move closer or can attack from safe spot.
     */
    @Builder.Default
    int attackRange = 1; // 1 = melee, higher = ranged/magic

    // ========================================================================
    // Weapon Style & XP Configuration
    // ========================================================================

    /**
     * Required weapon attack style (Slash, Stab, Crush, etc.).
     * This is the SOURCE OF TRUTH for combat style selection.
     * 
     * If specified, will:
     * 1. Ensure equipped weapon supports this style
     * 2. Select combat option that uses this style
     * 
     * Some enemies are weak to specific styles (e.g., Kalphite Queen is weak to stab).
     */
    @Builder.Default
    WeaponStyle weaponStyle = WeaponStyle.ANY;

    /**
     * Desired XP training goal.
     * MUST be compatible with weaponStyle - if not, weaponStyle takes precedence.
     * 
     * For example:
     * - weaponStyle=SLASH, xpGoal=STRENGTH -> OK, uses aggressive slash stance
     * - weaponStyle=SLASH, xpGoal=DEFENCE -> OK, uses defensive slash stance  
     * - weaponStyle=SLASH, xpGoal=MAGIC -> INVALID, falls back to default for slash
     * - weaponStyle=MAGIC, xpGoal=MAGIC_DEFENCE -> OK, uses defensive casting
     */
    @Builder.Default
    XpGoal xpGoal = XpGoal.ANY;

    // ========================================================================
    // Magic Combat / Spell Configuration
    // ========================================================================

    /**
     * Spells to use for magic combat.
     * If multiple spells are specified, they will be used in order or cycled based on spellCycleMode.
     * For special cases like Dagannoth Mother, a custom spell selection callback can be used.
     */
    @Singular("spell")
    List<CombatSpell> spells;

    /**
     * Mode for cycling through multiple spells.
     */
    @Builder.Default
    SpellCycleMode spellCycleMode = SpellCycleMode.IN_ORDER;

    /**
     * Whether to autocast the first spell (if autocastable).
     * If false, manually cast each spell by clicking spellbook then target.
     */
    @Builder.Default
    boolean useAutocast = true;

    /**
     * Whether to use defensive autocast mode.
     * When true, XP goes to Defence+Magic instead of just Magic.
     */
    @Builder.Default
    boolean defensiveAutocast = false;

    /**
     * Spell cycle modes for when multiple spells are configured.
     */
    public enum SpellCycleMode {
        /**
         * Use spells in order, one per kill/cast, then repeat.
         * Useful for quests requiring specific spell usage.
         */
        IN_ORDER,

        /**
         * Randomly select from available spells each cast.
         */
        RANDOM,

        /**
         * Use the first spell until out of runes, then move to next.
         */
        SEQUENTIAL,

        /**
         * Use the highest-tier spell the player can cast.
         */
        HIGHEST_AVAILABLE
    }

    // ========================================================================
    // Loot Configuration (Section 10.7)
    // ========================================================================

    /**
     * Whether to loot drops after kills.
     */
    @Builder.Default
    boolean lootEnabled = true;

    /**
     * Minimum GE value for auto-looting (in gp).
     * Items worth less than this are ignored.
     */
    @Builder.Default
    int lootMinValue = 1000;

    /**
     * Whitelist of item IDs to always loot regardless of value.
     * Useful for quest items, clue scrolls, etc.
     */
    @Singular("lootWhitelistId")
    Set<Integer> lootWhitelist;

    /**
     * Whether to loot all items at once after multi-kill before resuming combat.
     * Per Section 10.7.2: Area looting after multi-kill.
     */
    @Builder.Default
    boolean areaLootAfterMultiKill = true;

    /**
     * Maximum loot items to pick up per kill before resuming combat.
     * -1 means no limit.
     */
    @Builder.Default
    int maxLootPerKill = -1;

    // ========================================================================
    // Resource Management
    // ========================================================================

    /**
     * Whether to stop combat when out of food.
     * If false, continues fighting without food (useful for low-level mobs or prayer flicking).
     */
    @Builder.Default
    boolean stopWhenOutOfFood = true;

    /**
     * Whether to stop combat when out of prayer potions.
     * If false, continues without prayer restore (useful for prayer flicking or no-prayer combat).
     */
    @Builder.Default
    boolean stopWhenOutOfPrayerPotions = false;

    /**
     * Whether to stop combat when resources are low (no food + low HP).
     * Used for "bank when low" strategy (ironman mode).
     */
    @Builder.Default
    boolean stopWhenLowResources = false;

    /**
     * HP threshold for "low resources" check (0.0-1.0).
     * If stopWhenLowResources is true and HP falls below this with no food, stop.
     */
    @Builder.Default
    double lowResourcesHpThreshold = 0.30;

    // ========================================================================
    // Resupply Configuration
    // ========================================================================

    /**
     * Whether to enable resupply via banking when out of supplies.
     * If true, will bank and resupply before ending task.
     * If false, task ends when supplies are depleted.
     */
    @Builder.Default
    boolean enableResupply = false;

    /**
     * Bank location for resupply.
     * If null, will use nearest bank.
     */
    WorldPoint resupplyBankLocation;

    /**
     * Items to withdraw from bank during resupply.
     * Maps item ID to quantity desired.
     * Example: {LOBSTER_ID -> 20, PRAYER_POTION_4 -> 4}
     */
    @Singular("resupplyItem")
    java.util.Map<Integer, Integer> resupplyItems;

    /**
     * Minimum food count before triggering resupply.
     * Resupply when food count drops to this value or below.
     */
    @Builder.Default
    int minFoodToResupply = 3;

    /**
     * Minimum prayer potions before triggering resupply.
     * Only applies if prayer potions are in resupply items.
     */
    @Builder.Default
    int minPrayerPotionsToResupply = 1;

    /**
     * Whether to return to the same position after resupply.
     * If true, stores position before banking and returns after.
     */
    @Builder.Default
    boolean returnToSameSpot = true;

    /**
     * Maximum number of resupply trips before ending task.
     * -1 means unlimited resupply trips.
     */
    @Builder.Default
    int maxResupplyTrips = -1;

    // ========================================================================
    // Humanization
    // ========================================================================

    /**
     * Minimum delay between finding a target and attacking (ms).
     * Adds human-like hesitation.
     */
    @Builder.Default
    int minAttackDelay = 200;

    /**
     * Maximum delay between finding a target and attacking (ms).
     */
    @Builder.Default
    int maxAttackDelay = 800;

    /**
     * Minimum delay between loot clicks (ms).
     */
    @Builder.Default
    int minLootDelay = 100;

    /**
     * Maximum delay between loot clicks (ms).
     */
    @Builder.Default
    int maxLootDelay = 400;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if a kill count limit is set.
     *
     * @return true if kill count completion is enabled
     */
    public boolean hasKillCountLimit() {
        return killCount > 0;
    }

    /**
     * Check if a duration limit is set.
     *
     * @return true if duration completion is enabled
     */
    public boolean hasDurationLimit() {
        return maxDuration != null && !maxDuration.isZero();
    }

    /**
     * Check if safe-spotting is properly configured.
     *
     * @return true if safe spot is enabled and position is set
     */
    public boolean isSafeSpotConfigured() {
        return useSafeSpot && safeSpotPosition != null;
    }

    /**
     * Check if magic combat is configured (spells are specified).
     *
     * @return true if spells are configured
     */
    public boolean isMagicCombat() {
        return spells != null && !spells.isEmpty();
    }

    /**
     * Get the first/primary spell for combat.
     *
     * @return the first spell, or null if none configured
     */
    public CombatSpell getPrimarySpell() {
        if (spells == null || spells.isEmpty()) {
            return null;
        }
        return spells.get(0);
    }

    /**
     * Get the number of spells configured.
     *
     * @return spell count
     */
    public int getSpellCount() {
        return spells == null ? 0 : spells.size();
    }

    /**
     * Get the effective XP goal, considering weapon style compatibility.
     * If xpGoal is incompatible with weaponStyle, returns the default for that style.
     *
     * @return the effective XP goal
     */
    public XpGoal getEffectiveXpGoal() {
        if (xpGoal == XpGoal.ANY) {
            return XpGoal.getDefaultFor(weaponStyle, false);
        }
        if (xpGoal.isCompatibleWith(weaponStyle)) {
            return xpGoal;
        }
        // XP goal incompatible with weapon style - weapon style wins
        return XpGoal.getDefaultFor(weaponStyle, false);
    }

    /**
     * Check if a specific weapon style is required.
     *
     * @return true if weapon style is specified (not ANY)
     */
    public boolean hasWeaponStyleRequirement() {
        return weaponStyle != WeaponStyle.ANY;
    }

    /**
     * Get the combat slot to select for current style/xp configuration.
     *
     * @return combat slot index (0-3), or -1 if no specific slot needed
     */
    public int getRequiredCombatSlot() {
        XpGoal effectiveGoal = getEffectiveXpGoal();
        return effectiveGoal.getCombatSlotFor(weaponStyle);
    }

    /**
     * Check if an item should be looted (by value or whitelist).
     *
     * @param itemId the item ID
     * @param geValue the item's GE value
     * @return true if the item should be looted
     */
    public boolean shouldLootItem(int itemId, int geValue) {
        if (!lootEnabled) {
            return false;
        }
        if (lootWhitelist.contains(itemId)) {
            return true;
        }
        return geValue >= lootMinValue;
    }

    /**
     * Check if resupply is properly configured.
     *
     * @return true if resupply is enabled with items specified
     */
    public boolean isResupplyConfigured() {
        return enableResupply && resupplyItems != null && !resupplyItems.isEmpty();
    }

    /**
     * Check if we should trigger resupply based on current food count.
     *
     * @param currentFoodCount current food in inventory
     * @return true if resupply should be triggered
     */
    public boolean shouldResupply(int currentFoodCount) {
        if (!enableResupply) {
            return false;
        }
        return currentFoodCount <= minFoodToResupply;
    }

    /**
     * Check if max resupply trips has been reached.
     *
     * @param tripCount current number of trips completed
     * @return true if max trips reached
     */
    public boolean hasReachedMaxResupplyTrips(int tripCount) {
        if (maxResupplyTrips < 0) {
            return false;  // Unlimited
        }
        return tripCount >= maxResupplyTrips;
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this configuration
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder("CombatTaskConfig[");
        if (hasKillCountLimit()) {
            sb.append("kills=").append(killCount);
        } else if (hasDurationLimit()) {
            sb.append("duration=").append(maxDuration.toMinutes()).append("m");
        } else {
            sb.append("unlimited");
        }
        if (hasWeaponStyleRequirement()) {
            sb.append(", style=").append(weaponStyle.getDisplayName());
            if (xpGoal != XpGoal.ANY) {
                sb.append("/").append(getEffectiveXpGoal().getDisplayName());
            }
        }
        if (isMagicCombat()) {
            sb.append(", spells=").append(getSpellCount());
            if (useAutocast && getPrimarySpell() != null && getPrimarySpell().isAutocastable()) {
                sb.append("(autocast)");
            }
        }
        if (useSafeSpot) {
            sb.append(", safespot=").append(safeSpotPosition);
        }
        if (lootEnabled) {
            sb.append(", loot>=").append(lootMinValue).append("gp");
        }
        if (stopWhenLowResources) {
            sb.append(", bankWhenLow");
        }
        sb.append("]");
        return sb.toString();
    }
}

