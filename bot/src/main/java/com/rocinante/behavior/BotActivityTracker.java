package com.rocinante.behavior;

import com.rocinante.state.CombatState;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskExecutor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tracks the current bot activity state for behavioral decision-making.
 * 
 * This class monitors:
 * - Current activity type (CRITICAL, HIGH, MEDIUM, LOW, AFK_COMBAT, IDLE)
 * - Account type detection (Normal, Ironman, HCIM, etc.)
 * - Dangerous area detection (Wilderness, etc.)
 * - Boss fight detection (based on NPC names)
 * 
 * Used by FatigueModel, AttentionModel, and BreakScheduler to determine
 * appropriate behavioral responses based on current game context.
 */
@Slf4j
@Singleton
public class BotActivityTracker {

    // Varbit IDs
    private static final int ACCOUNT_TYPE_VARBIT = 1777;
    private static final int WILDERNESS_LEVEL_VARBIT = 5963;
    
    /**
     * Boss NPC names for boss fight detection.
     * Extensible - add more bosses as needed.
     */
    private static final Set<String> BOSS_NAMES = Set.of(
            // GWD
            "General Graardor", "K'ril Tsutsaroth", "Commander Zilyana", "Kree'arra",
            // Wilderness bosses
            "Callisto", "Vet'ion", "Venenatis", "Scorpia", "Chaos Fanatic", "Crazy archaeologist",
            "King Black Dragon", "Chaos Elemental",
            // Solo bosses
            "Zulrah", "Vorkath", "The Nightmare", "Phosani's Nightmare",
            "Corporeal Beast", "Giant Mole", "Sarachnis", "Kalphite Queen",
            "Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme",
            "Cerberus", "Abyssal Sire", "Kraken", "Thermonuclear smoke devil",
            "Alchemical Hydra", "Grotesque Guardians",
            // Raids
            "Great Olm", "Verzik Vitur", "The Leviathan", "The Whisperer",
            "Vardorvis", "Duke Sucellus",
            // Slayer bosses
            "Skotizo",
            // Other
            "TzTok-Jad", "TzKal-Zuk", "Hespori", "Mimic", "The Gauntlet",
            "Crystalline Hunllef", "Corrupted Hunllef"
    );
    
    /**
     * Dangerous area regions (region IDs where extra caution is needed).
     * For now, we primarily use the wilderness varbit.
     */
    private static final Set<Integer> DANGEROUS_REGIONS = Set.of(
            // Deep wilderness regions could be added here
            // Revenant caves, etc.
    );

    private final Client client;
    private final Supplier<CombatState> combatStateSupplier;
    private final Supplier<Task> currentTaskSupplier;
    
    /**
     * IronmanState for authoritative account type.
     */
    @Nullable
    private final com.rocinante.state.IronmanState ironmanState;

    @Getter
    private volatile ActivityType currentActivity = ActivityType.IDLE;
    
    @Getter
    private volatile ActivityType explicitActivity = null;
    
    /**
     * Cached account type (updated from IronmanState or varbit).
     */
    @Getter
    private volatile AccountType accountType = AccountType.NORMAL;
    
    @Getter
    private volatile boolean inDangerousArea = false;
    
    @Getter
    private volatile boolean inBossFight = false;
    
    @Getter
    private volatile int wildernessLevel = 0;
    
    private Instant activityStartTime = Instant.now();
    private ActivityType lastActivity = ActivityType.IDLE;

    @Inject
    public BotActivityTracker(Client client,
                              @Nullable com.rocinante.state.IronmanState ironmanState,
                              @Nullable Provider<TaskExecutor> taskExecutorProvider,
                              @Nullable Provider<com.rocinante.core.GameStateService> gameStateServiceProvider) {
        this.client = client;
        this.ironmanState = ironmanState;
        
        // Create suppliers that safely handle null dependencies
        // Using Provider<T> breaks circular dependencies:
        // - TaskContext -> GameStateService -> FatigueModel -> BotActivityTracker -> TaskExecutor -> TaskContext
        // - GameStateService -> AttentionModel -> BotActivityTracker -> GameStateService
        this.combatStateSupplier = () -> {
            if (gameStateServiceProvider != null) {
                com.rocinante.core.GameStateService gss = gameStateServiceProvider.get();
                if (gss != null) {
                    return gss.getCombatState();
                }
            }
            return CombatState.EMPTY;
        };
        
        this.currentTaskSupplier = () -> {
            if (taskExecutorProvider != null) {
                TaskExecutor executor = taskExecutorProvider.get();
                if (executor != null) {
                    return executor.getCurrentTask();
                }
            }
            return null;
        };
        
        log.info("BotActivityTracker initialized");
    }

    /**
     * Constructor for testing with explicit suppliers.
     */
    public BotActivityTracker(Client client,
                              Supplier<CombatState> combatStateSupplier,
                              Supplier<Task> currentTaskSupplier) {
        this.client = client;
        this.ironmanState = null;
        this.combatStateSupplier = combatStateSupplier;
        this.currentTaskSupplier = currentTaskSupplier;
    }

    // ========================================================================
    // Game Tick Update
    // ========================================================================

    /**
     * Update activity tracking each game tick.
     * Called by the plugin's event handler.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        updateAccountType();
        updateDangerousAreaStatus();
        updateBossFightStatus();
        updateCurrentActivity();
    }

    /**
     * Manual tick update for when not using event subscription.
     */
    public void tick() {
        updateAccountType();
        updateDangerousAreaStatus();
        updateBossFightStatus();
        updateCurrentActivity();
    }

    // ========================================================================
    // State Updates
    // ========================================================================

    private void updateAccountType() {
        // Delegate to IronmanState for authoritative account type
        if (ironmanState != null) {
            accountType = ironmanState.getEffectiveType();
        } else {
            // Fallback: read varbit directly
            try {
                int varbitValue = client.getVarbitValue(ACCOUNT_TYPE_VARBIT);
                accountType = AccountType.fromVarbit(varbitValue);
            } catch (Exception e) {
                log.trace("Could not read account type varbit: {}", e.getMessage());
            }
        }
    }

    private void updateDangerousAreaStatus() {
        try {
            wildernessLevel = client.getVarbitValue(WILDERNESS_LEVEL_VARBIT);
            inDangerousArea = wildernessLevel > 0;
            
            // Could add region-based checks here
            // int regionId = client.getLocalPlayer().getWorldLocation().getRegionID();
            // inDangerousArea |= DANGEROUS_REGIONS.contains(regionId);
        } catch (Exception e) {
            log.trace("Could not determine dangerous area status: {}", e.getMessage());
        }
    }

    private void updateBossFightStatus() {
        CombatState combatState = combatStateSupplier.get();
        if (combatState == null || !combatState.hasTarget()) {
            inBossFight = false;
            return;
        }
        
        NpcSnapshot target = combatState.getTargetNpc();
        if (target != null && target.getName() != null) {
            inBossFight = BOSS_NAMES.contains(target.getName());
        } else {
            inBossFight = false;
        }
    }

    private void updateCurrentActivity() {
        ActivityType newActivity = determineActivity();
        
        if (newActivity != lastActivity) {
            activityStartTime = Instant.now();
            log.debug("Activity changed: {} -> {}", lastActivity, newActivity);
        }
        
        lastActivity = newActivity;
        currentActivity = newActivity;
    }

    private ActivityType determineActivity() {
        // Explicit override takes precedence (e.g., AFK_COMBAT flag)
        if (explicitActivity != null) {
            return explicitActivity;
        }
        
        CombatState combatState = combatStateSupplier.get();
        
        // Check for critical situations
        if (inBossFight || (inDangerousArea && combatState.hasTarget())) {
            return ActivityType.CRITICAL;
        }
        
        // Check for high-intensity combat
        if (combatState.hasTarget() || combatState.isBeingAttacked()) {
            return ActivityType.HIGH;
        }
        
        // Check current task for activity hints
        Task currentTask = currentTaskSupplier.get();
        if (currentTask != null) {
            // Could inspect task type for more granular classification
            // For now, assume medium intensity if doing something
            return ActivityType.MEDIUM;
        }
        
        // Default to idle
        return ActivityType.IDLE;
    }

    // ========================================================================
    // Explicit Activity Control
    // ========================================================================

    /**
     * Set an explicit activity type override.
     * Used for tasks that know they are AFK combat, etc.
     * 
     * @param activity the activity type, or null to clear override
     */
    public void setExplicitActivity(@Nullable ActivityType activity) {
        this.explicitActivity = activity;
        log.debug("Explicit activity set: {}", activity);
    }

    /**
     * Clear explicit activity override, return to automatic detection.
     */
    public void clearExplicitActivity() {
        this.explicitActivity = null;
        log.debug("Explicit activity cleared");
    }

    // ========================================================================
    // Interruption Logic
    // ========================================================================

    /**
     * Check if the current activity can be interrupted by behavioral tasks.
     * Takes into account activity type and account type.
     * 
     * @return true if interruption is allowed
     */
    public boolean canInterrupt() {
        // Never interrupt critical activities
        if (!currentActivity.isInterruptible()) {
            return false;
        }
        
        // HCIM: stricter rules - no interruption during ANY combat
        if (accountType.isHardcore() && currentActivity.isCombat()) {
            return false;
        }
        
        return true;
    }

    /**
     * Check if AFK attention states are allowed.
     * More restrictive than general interruption.
     * 
     * @return true if AFK is safe
     */
    public boolean canEnterAFK() {
        // No AFK during high-stakes activities for anyone
        if (currentActivity.isHighStakes()) {
            return false;
        }
        
        // HCIM: no AFK during any combat
        if (accountType.isHardcore() && currentActivity.isCombat()) {
            return false;
        }
        
        // Everyone else: OK for AFK-style activities
        return currentActivity.isAfkStyle() || currentActivity == ActivityType.MEDIUM;
    }

    /**
     * Check if this is a safe context for breaks.
     * 
     * @return true if taking a break is safe
     */
    public boolean canTakeBreak() {
        // Can't break during critical/high activity
        if (!currentActivity.isInterruptible()) {
            return false;
        }
        
        // HCIM: no breaks during any combat
        if (accountType.isHardcore() && currentActivity.isCombat()) {
            return false;
        }
        
        // Normal/Ironman: OK to break during low-intensity or idle
        return true;
    }

    // ========================================================================
    // Time Tracking
    // ========================================================================

    /**
     * Get duration in current activity type.
     * 
     * @return duration since activity changed
     */
    public Duration getTimeInCurrentActivity() {
        return Duration.between(activityStartTime, Instant.now());
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Get a set of all known boss names (for extension/modification).
     * 
     * @return unmodifiable set of boss names
     */
    public static Set<String> getBossNames() {
        return Collections.unmodifiableSet(BOSS_NAMES);
    }

    /**
     * Check if a specific NPC name is considered a boss.
     * 
     * @param npcName the NPC name to check
     * @return true if it's a boss
     */
    public static boolean isBoss(String npcName) {
        return npcName != null && BOSS_NAMES.contains(npcName);
    }

    /**
     * Get a summary of current tracking state.
     * 
     * @return summary string for logging
     */
    public String getSummary() {
        return String.format(
                "ActivityTracker[activity=%s, account=%s, dangerous=%s, boss=%s, wildy=%d]",
                currentActivity, accountType, inDangerousArea, inBossFight, wildernessLevel
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

