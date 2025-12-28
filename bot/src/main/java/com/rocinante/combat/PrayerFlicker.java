package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.progression.PrayerUnlock;
import com.rocinante.progression.UnlockTracker;
import com.rocinante.state.AggressorInfo;
import com.rocinante.state.CombatState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Prayer;

import com.rocinante.util.Randomization;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;

// Use explicit import to avoid conflict with com.rocinante.combat.AttackStyle
import com.rocinante.state.AttackStyle;

/**
 * Manages prayer flicking and protection prayer switching during combat.
 *
 * Per REQUIREMENTS.md Section 10.2 and 10.1.3:
 * <ul>
 *   <li>Attack detection via NPC animation ID to determine attack style</li>
 *   <li>Protection prayer switching based on detected incoming attacks</li>
 *   <li>Three flicking modes: perfect, lazy, and always-on</li>
 *   <li>Humanization via timing variance and occasional missed flicks</li>
 *   <li>Offensive prayer support</li>
 *   <li>Graceful degradation when prayer not unlocked</li>
 * </ul>
 *
 * Per REQUIREMENTS.md Section 10.6.2:
 * <ul>
 *   <li>Protection prayers have 1-tick activation delay</li>
 *   <li>Must activate prayer 1 tick before attack lands</li>
 *   <li>Prayer flicking requires tick-perfect timing (600ms intervals)</li>
 * </ul>
 */
@Slf4j
@Singleton
public class PrayerFlicker {

    private final Client client;
    private final GameStateService gameStateService;
    private final UnlockTracker unlockTracker;
    private final Randomization randomization;

    // ========================================================================
    // Configuration
    // ========================================================================

    @Getter
    @Setter
    private PrayerConfig config = PrayerConfig.DEFAULT;

    // ========================================================================
    // State
    // ========================================================================

    /** Currently active protection prayer, or null if none. */
    @Getter
    @Nullable
    private volatile Prayer activeProtectionPrayer = null;

    /** Currently active offensive prayer, or null if none. */
    @Getter
    @Nullable
    private volatile Prayer activeOffensivePrayer = null;

    /** Tick when protection prayer was last activated. */
    @Getter
    private volatile int lastActivationTick = -1;

    /** Tick when protection prayer should be deactivated. */
    @Getter
    private volatile int scheduledDeactivationTick = -1;

    /** Whether we're currently in flicking mode. */
    @Getter
    private volatile boolean flicking = false;

    /** Count of successful flicks this session. */
    @Getter
    private volatile int successfulFlicks = 0;

    /** Count of missed flicks this session. */
    @Getter
    private volatile int missedFlicks = 0;

    /** Whether prayer functionality is available. */
    @Getter
    private volatile boolean prayerAvailable = true;

    /** Reason prayer is unavailable (for logging). */
    @Getter
    @Nullable
    private volatile String prayerUnavailableReason = null;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public PrayerFlicker(Client client, GameStateService gameStateService, UnlockTracker unlockTracker,
                         Randomization randomization) {
        this.client = client;
        this.gameStateService = gameStateService;
        this.unlockTracker = unlockTracker;
        this.randomization = randomization;
        log.info("PrayerFlicker initialized");
    }

    // ========================================================================
    // Main Check Method
    // ========================================================================

    /**
     * Check prayer state and return action if prayer switch/flick needed.
     *
     * @param currentTick current game tick
     * @return CombatAction for prayer management, or null if none needed
     */
    @Nullable
    public CombatAction checkAndFlick(int currentTick) {
        if (!config.isUseProtectionPrayers()) {
            return null;
        }

        // Check if prayer is available at all
        if (!checkPrayerAvailability()) {
            return null;
        }

        PlayerState playerState = gameStateService.getPlayerState();
        CombatState combatState = gameStateService.getCombatState();
        InventoryState inventoryState = gameStateService.getInventoryState();

        if (!playerState.isValid()) {
            return null;
        }

        // Check prayer points
        CombatAction restoreAction = checkPrayerPoints(playerState, inventoryState);
        if (restoreAction != null) {
            return restoreAction;
        }

        // Handle deactivation for flicking modes
        if (shouldDeactivatePrayer(currentTick)) {
            return createDeactivatePrayerAction();
        }

        // Check for incoming attacks and switch if needed
        if (combatState.hasIncomingAttack()) {
            return handleIncomingAttack(combatState, currentTick);
        }

        // Handle always-on mode during combat
        if (config.getFlickMode().isAlwaysOn() && combatState.isBeingAttacked()) {
            return ensureProtectionPrayerActive(combatState);
        }

        // Handle offensive prayers
        if (config.isUseOffensivePrayers() && combatState.hasTarget()) {
            return checkOffensivePrayer(combatState);
        }

        return null;
    }

    // ========================================================================
    // Prayer Availability
    // ========================================================================

    /**
     * Check if prayer functionality is available for the player.
     * Updates prayerAvailable and prayerUnavailableReason.
     *
     * @return true if prayer can be used
     */
    private boolean checkPrayerAvailability() {
        // Check if any protection prayer is unlocked
        if (!unlockTracker.hasAnyProtectionPrayer()) {
            int prayerLevel = unlockTracker.getSkillLevel(net.runelite.api.Skill.PRAYER);
            if (prayerLevel < 37) {
                prayerAvailable = false;
                prayerUnavailableReason = "Prayer level too low (" + prayerLevel + "/37 for Protect from Magic)";
                log.debug("Prayer unavailable: {}", prayerUnavailableReason);
                return false;
            }
        }

        prayerAvailable = true;
        prayerUnavailableReason = null;
        return true;
    }

    /**
     * Check if prayer is unlocked for a specific protection type.
     *
     * @param attackStyle the incoming attack style
     * @return true if protection prayer is available
     */
    public boolean canProtectAgainst(AttackStyle attackStyle) {
        PrayerUnlock protection = unlockTracker.getBestProtectionPrayer(attackStyle);
        return protection != null;
    }

    // ========================================================================
    // Prayer Point Management
    // ========================================================================

    /**
     * Check prayer points and create restore action if needed.
     */
    @Nullable
    private CombatAction checkPrayerPoints(PlayerState playerState, InventoryState inventoryState) {
        double prayerPercent = playerState.getPrayerPercent();

        // Check if critically low
        if (config.isDisableWhenLowPoints() && prayerPercent < config.getLowPointsThreshold()) {
            // Check if we have restores
            if (!hasPrayerRestore(inventoryState)) {
                log.warn("Prayer points critically low ({}%) with no restore - disabling prayers",
                        prayerPercent * 100);
                return createDisablePrayersAction();
            }
        }

        // Check if should restore
        if (prayerPercent < config.getPrayerRestoreThreshold()) {
            int restoreSlot = getPrayerRestoreSlot(inventoryState);
            if (restoreSlot >= 0) {
                log.debug("Prayer low ({}%), restoring", prayerPercent * 100);
                return CombatAction.builder()
                        .type(CombatAction.Type.DRINK_POTION)
                        .primarySlot(restoreSlot)
                        .potionId(inventoryState.getItemInSlot(restoreSlot)
                                .map(item -> item.getId()).orElse(-1))
                        .priority(CombatAction.Priority.HIGH)
                        .build();
            }
        }

        return null;
    }

    /**
     * Check if player has a prayer restore potion.
     */
    private boolean hasPrayerRestore(InventoryState inventory) {
        return getPrayerRestoreSlot(inventory) >= 0;
    }

    /**
     * Get slot of a prayer restore potion.
     */
    private int getPrayerRestoreSlot(InventoryState inventory) {
        // Prayer potions
        int[] prayerPotionIds = {2434, 139, 141, 143}; // Prayer potion (4) through (1)
        for (int id : prayerPotionIds) {
            int slot = inventory.getSlotOf(id);
            if (slot >= 0) return slot;
        }
        // Super restores
        int[] superRestoreIds = {3024, 3026, 3028, 3030}; // Super restore (4) through (1)
        for (int id : superRestoreIds) {
            int slot = inventory.getSlotOf(id);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    // ========================================================================
    // Incoming Attack Handling
    // ========================================================================

    /**
     * Handle an incoming attack by switching protection prayer.
     */
    @Nullable
    private CombatAction handleIncomingAttack(CombatState combatState, int currentTick) {
        AttackStyle incomingStyle = combatState.getIncomingAttackStyle();
        if (incomingStyle == AttackStyle.UNKNOWN) {
            return null;
        }

        // Check if we can protect against this style
        PrayerUnlock protection = unlockTracker.getBestProtectionPrayer(incomingStyle);
        if (protection == null) {
            log.debug("Cannot protect against {} - prayer not unlocked", incomingStyle);
            return null;
        }

        Prayer requiredPrayer = protection.getPrayer();
        int ticksUntilAttack = combatState.getTicksUntilAttackLands();

        // Check if we should activate now based on flick mode
        FlickMode mode = config.getFlickMode();
        int activationTick = mode.getActivationTick(currentTick + ticksUntilAttack);

        if (currentTick >= activationTick) {
            // Time to activate
            return createSwitchPrayerAction(requiredPrayer, incomingStyle, currentTick);
        }

        return null;
    }

    /**
     * Create action to switch to a protection prayer.
     */
    private CombatAction createSwitchPrayerAction(Prayer prayer, AttackStyle style, int currentTick) {
        // Check for missed flick (humanization)
        if (shouldMissFlick()) {
            missedFlicks++;
            log.debug("Missed flick (humanization) - total misses: {}", missedFlicks);
            return null;
        }

        successfulFlicks++;
        lastActivationTick = currentTick;
        activeProtectionPrayer = prayer;
        flicking = true;

        // Schedule deactivation for flicking modes
        FlickMode mode = config.getFlickMode();
        if (mode.isFlicking()) {
            scheduledDeactivationTick = mode.getDeactivationTick(currentTick);
        } else {
            scheduledDeactivationTick = -1;
        }

        log.debug("Switching to {} protection (style: {}, mode: {})",
                prayer, style, mode);

        return CombatAction.builder()
                .type(CombatAction.Type.PRAYER_SWITCH)
                .attackStyle(style)
                .priority(CombatAction.Priority.HIGH)
                .build();
    }

    /**
     * Check if we should miss this flick (humanization).
     */
    private boolean shouldMissFlick() {
        double missProbability = config.getEffectiveMissProbability();
        return randomization.chance(missProbability);
    }

    // ========================================================================
    // Flick Deactivation
    // ========================================================================

    /**
     * Check if prayer should be deactivated.
     */
    private boolean shouldDeactivatePrayer(int currentTick) {
        if (!flicking || scheduledDeactivationTick < 0) {
            return false;
        }
        return currentTick >= scheduledDeactivationTick;
    }

    /**
     * Create action to deactivate protection prayer.
     */
    private CombatAction createDeactivatePrayerAction() {
        Prayer toDeactivate = activeProtectionPrayer;
        activeProtectionPrayer = null;
        flicking = false;
        scheduledDeactivationTick = -1;

        log.debug("Deactivating protection prayer: {}", toDeactivate);

        // Return a PRAYER_SWITCH with UNKNOWN style to indicate deactivation
        return CombatAction.builder()
                .type(CombatAction.Type.PRAYER_SWITCH)
                .attackStyle(AttackStyle.UNKNOWN)
                .priority(CombatAction.Priority.NORMAL)
                .build();
    }

    /**
     * Create action to disable all prayers.
     */
    private CombatAction createDisablePrayersAction() {
        activeProtectionPrayer = null;
        activeOffensivePrayer = null;
        flicking = false;
        scheduledDeactivationTick = -1;

        log.debug("Disabling all prayers due to low points");

        return CombatAction.builder()
                .type(CombatAction.Type.PRAYER_SWITCH)
                .attackStyle(AttackStyle.UNKNOWN)
                .priority(CombatAction.Priority.HIGH)
                .build();
    }

    // ========================================================================
    // Always-On Mode
    // ========================================================================

    /**
     * Ensure protection prayer is active (for always-on mode).
     */
    @Nullable
    private CombatAction ensureProtectionPrayerActive(CombatState combatState) {
        // Get the most dangerous incoming attack style
        Optional<AggressorInfo> mostDangerous = combatState.getMostDangerousAggressor();
        if (mostDangerous.isEmpty()) {
            return null;
        }

        AttackStyle dangerStyle = AttackStyle.fromId(mostDangerous.get().getAttackStyle());
        if (dangerStyle == AttackStyle.UNKNOWN) {
            // Default to melee if unknown
            dangerStyle = AttackStyle.MELEE;
        }

        PrayerUnlock protection = unlockTracker.getBestProtectionPrayer(dangerStyle);
        if (protection == null) {
            return null;
        }

        Prayer requiredPrayer = protection.getPrayer();

        // Check if already active
        if (requiredPrayer == activeProtectionPrayer) {
            return null;
        }

        activeProtectionPrayer = requiredPrayer;
        log.debug("Ensuring {} protection is active", requiredPrayer);

        return CombatAction.builder()
                .type(CombatAction.Type.PRAYER_SWITCH)
                .attackStyle(dangerStyle)
                .priority(CombatAction.Priority.HIGH)
                .build();
    }

    // ========================================================================
    // Offensive Prayers
    // ========================================================================

    /**
     * Check and activate offensive prayer if configured.
     */
    @Nullable
    private CombatAction checkOffensivePrayer(CombatState combatState) {
        if (!config.isUseOffensivePrayers()) {
            return null;
        }

        // Determine attack style from combat state
        AttackStyle style = combatState.getCurrentAttackStyle();
        if (style == AttackStyle.UNKNOWN) {
            return null;
        }

        // Get best offensive prayer
        PrayerUnlock offensive;
        if (config.hasSpecificOffensivePrayer()) {
            offensive = config.getOffensivePrayer();
        } else {
            offensive = unlockTracker.getBestOffensivePrayer(style);
        }

        if (offensive == null) {
            return null;
        }

        Prayer requiredPrayer = offensive.getPrayer();

        // Check if already active
        if (requiredPrayer == activeOffensivePrayer) {
            return null;
        }

        // Check if we can unlock it
        if (!unlockTracker.isPrayerUnlockMet(offensive)) {
            log.debug("Offensive prayer {} not unlocked", offensive);
            return null;
        }

        activeOffensivePrayer = requiredPrayer;
        log.debug("Activating offensive prayer: {}", requiredPrayer);

        // Create a prayer switch action for the offensive prayer
        // We use PRAYER_SWITCH with UNKNOWN style to indicate this is an offensive prayer activation
        // CombatManager will need to check the action and handle offensive prayers specially
        activeOffensivePrayer = requiredPrayer;
        
        return CombatAction.builder()
                .type(CombatAction.Type.PRAYER_SWITCH)
                .attackStyle(style) // Keep attack style for reference
                .priority(CombatAction.Priority.NORMAL)
                .primarySlot(requiredPrayer.ordinal()) // Store prayer ordinal in slot for lookup
                .build();
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    /**
     * Get the flick success rate.
     *
     * @return success rate as percentage (0.0-100.0)
     */
    public double getFlickSuccessRate() {
        int total = successfulFlicks + missedFlicks;
        if (total == 0) {
            return 100.0;
        }
        return (successfulFlicks * 100.0) / total;
    }

    /**
     * Get a summary of flicking statistics.
     *
     * @return summary string
     */
    public String getFlickStats() {
        return String.format("Flicks: %d successful, %d missed (%.1f%% success rate)",
                successfulFlicks, missedFlicks, getFlickSuccessRate());
    }

    // ========================================================================
    // Reset
    // ========================================================================

    /**
     * Reset state (e.g., on logout or combat end).
     */
    public void reset() {
        activeProtectionPrayer = null;
        activeOffensivePrayer = null;
        lastActivationTick = -1;
        scheduledDeactivationTick = -1;
        flicking = false;
        log.debug("PrayerFlicker reset");
    }

    /**
     * Reset statistics.
     */
    public void resetStats() {
        successfulFlicks = 0;
        missedFlicks = 0;
        log.debug("PrayerFlicker stats reset");
    }

    /**
     * Called when combat ends to clean up.
     */
    public void onCombatEnd() {
        // Deactivate prayers on combat end
        if (activeProtectionPrayer != null || activeOffensivePrayer != null) {
            log.debug("Combat ended - deactivating prayers");
        }
        reset();
    }
}

