package com.rocinante.tasks.impl;

import com.rocinante.state.AttackStyle;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.widgets.Widget;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reusable task for prayer management.
 *
 * <p>This task provides a unified interface for prayer operations that can be used by:
 * <ul>
 *   <li>Combat system (protection prayers, offensive prayers)</li>
 *   <li>Quest system (using specific prayers for puzzles/bosses)</li>
 *   <li>Achievement diary tasks</li>
 *   <li>General navigation (protect from range while running through aggro areas)</li>
 * </ul>
 *
 * <p>Operations supported:
 * <ul>
 *   <li>Activate a single prayer</li>
 *   <li>Activate multiple prayers at once</li>
 *   <li>Deactivate a specific prayer</li>
 *   <li>Deactivate all prayers</li>
 *   <li>Switch protection prayer based on attack style</li>
 *   <li>Quick-prayer activation</li>
 * </ul>
 *
 * <p>Per REQUIREMENTS.md Section 10.2 and 10.6.2:
 * <ul>
 *   <li>Protection prayers have 1-tick activation delay</li>
 *   <li>Must activate prayer 1 tick before attack lands</li>
 *   <li>Prayer flicking requires tick-perfect timing (600ms intervals)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Activate Protect from Melee
 * PrayerTask protectMelee = PrayerTask.activate(Prayer.PROTECT_FROM_MELEE);
 *
 * // Switch protection based on attack style
 * PrayerTask switchProtection = PrayerTask.protectFrom(AttackStyle.RANGED);
 *
 * // Deactivate all prayers
 * PrayerTask deactivateAll = PrayerTask.deactivateAll();
 *
 * // Activate offensive prayer for combat
 * PrayerTask piety = PrayerTask.activate(Prayer.PIETY);
 *
 * // Quick-prayers toggle
 * PrayerTask quickPrayers = PrayerTask.toggleQuickPrayers();
 * }</pre>
 */
@Slf4j
public class PrayerTask extends AbstractTask {

    // ========================================================================
    // Widget Constants
    // ========================================================================

    /**
     * Prayerbook interface group ID (541 = 0x21D).
     */
    public static final int PRAYERBOOK_GROUP_ID = 541;

    /**
     * Quick-prayer orb widget (Minimap orbs group 160, child 14).
     */
    public static final int QUICK_PRAYER_ORB_GROUP = 160;
    public static final int QUICK_PRAYER_ORB_CHILD = 14;

    /**
     * Protection prayer child IDs.
     * These are absolute child IDs within the prayerbook interface.
     */
    public static final int PRAYER_PROTECT_MAGIC_CHILD = 25;
    public static final int PRAYER_PROTECT_MISSILES_CHILD = 26;
    public static final int PRAYER_PROTECT_MELEE_CHILD = 27;

    /**
     * Base child ID for prayers (first prayer in list).
     * InterfaceID.Prayerbook.PRAYER1 = 0x021d_0009 (child 9).
     */
    public static final int PRAYER_BASE_CHILD = 9;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The operation to perform.
     */
    @Getter
    private final PrayerOperation operation;

    /**
     * Target prayers for the operation.
     */
    @Getter
    private final Set<Prayer> targetPrayers;

    /**
     * Attack style for protection prayer switching.
     */
    @Getter
    private final AttackStyle protectionStyle;

    /**
     * Custom description for logging.
     */
    @Setter
    private String description;

    // ========================================================================
    // State
    // ========================================================================

    private enum Phase {
        OPEN_PRAYER_TAB,
        EXECUTE_OPERATION,
        VERIFY,
        DONE
    }

    private Phase phase = Phase.OPEN_PRAYER_TAB;
    private boolean actionPending = false;
    private int prayerIndex = 0;  // For multi-prayer operations
    private int waitTicks = 0;
    private static final int MAX_WAIT_TICKS = 10;

    // ========================================================================
    // Prayer Operations
    // ========================================================================

    /**
     * Types of prayer operations this task can perform.
     */
    public enum PrayerOperation {
        /** Activate one or more prayers. */
        ACTIVATE,
        /** Deactivate one or more specific prayers. */
        DEACTIVATE,
        /** Deactivate all currently active prayers. */
        DEACTIVATE_ALL,
        /** Toggle a prayer (activate if off, deactivate if on). */
        TOGGLE,
        /** Switch protection prayer based on attack style. */
        PROTECTION_SWITCH,
        /** Toggle quick-prayers on/off. */
        QUICK_PRAYERS
    }

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a prayer task with specified operation and targets.
     */
    private PrayerTask(PrayerOperation operation, Set<Prayer> targetPrayers, AttackStyle protectionStyle) {
        this.operation = operation;
        this.targetPrayers = targetPrayers != null ? EnumSet.copyOf(targetPrayers) : EnumSet.noneOf(Prayer.class);
        this.protectionStyle = protectionStyle;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a task to activate a single prayer.
     *
     * @param prayer the prayer to activate
     * @return the task
     */
    public static PrayerTask activate(Prayer prayer) {
        return new PrayerTask(PrayerOperation.ACTIVATE, EnumSet.of(prayer), null);
    }

    /**
     * Create a task to activate multiple prayers.
     *
     * @param prayers the prayers to activate
     * @return the task
     */
    public static PrayerTask activate(Prayer... prayers) {
        return new PrayerTask(PrayerOperation.ACTIVATE, EnumSet.copyOf(Arrays.asList(prayers)), null);
    }

    /**
     * Create a task to activate multiple prayers.
     *
     * @param prayers the prayers to activate
     * @return the task
     */
    public static PrayerTask activate(Set<Prayer> prayers) {
        return new PrayerTask(PrayerOperation.ACTIVATE, prayers, null);
    }

    /**
     * Create a task to deactivate a single prayer.
     *
     * @param prayer the prayer to deactivate
     * @return the task
     */
    public static PrayerTask deactivate(Prayer prayer) {
        return new PrayerTask(PrayerOperation.DEACTIVATE, EnumSet.of(prayer), null);
    }

    /**
     * Create a task to deactivate multiple prayers.
     *
     * @param prayers the prayers to deactivate
     * @return the task
     */
    public static PrayerTask deactivate(Prayer... prayers) {
        return new PrayerTask(PrayerOperation.DEACTIVATE, EnumSet.copyOf(Arrays.asList(prayers)), null);
    }

    /**
     * Create a task to deactivate all active prayers.
     *
     * @return the task
     */
    public static PrayerTask deactivateAll() {
        return new PrayerTask(PrayerOperation.DEACTIVATE_ALL, null, null);
    }

    /**
     * Create a task to toggle a prayer.
     *
     * @param prayer the prayer to toggle
     * @return the task
     */
    public static PrayerTask toggle(Prayer prayer) {
        return new PrayerTask(PrayerOperation.TOGGLE, EnumSet.of(prayer), null);
    }

    /**
     * Create a task to switch protection prayer based on attack style.
     * This will activate the appropriate protection prayer.
     *
     * @param style the attack style to protect from
     * @return the task
     */
    public static PrayerTask protectFrom(AttackStyle style) {
        return new PrayerTask(PrayerOperation.PROTECTION_SWITCH, null, style);
    }

    /**
     * Create a task to toggle quick-prayers.
     *
     * @return the task
     */
    public static PrayerTask toggleQuickPrayers() {
        return new PrayerTask(PrayerOperation.QUICK_PRAYERS, null, null);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set a custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public PrayerTask withDescription(String description) {
        this.description = description;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        if (!ctx.isLoggedIn()) {
            return false;
        }

        PlayerState player = ctx.getPlayerState();

        // Check if player has prayer points
        if (operation == PrayerOperation.ACTIVATE || 
            operation == PrayerOperation.PROTECTION_SWITCH ||
            operation == PrayerOperation.QUICK_PRAYERS) {
            if (player.getCurrentPrayer() <= 0) {
                log.debug("Cannot execute prayer task: no prayer points");
                return false;
            }
        }

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (actionPending) {
            return;
        }

        waitTicks++;
        if (waitTicks > MAX_WAIT_TICKS) {
            waitTicks = 0;
            if (phase == Phase.VERIFY) {
                // Verification timeout - assume success
                complete();
                return;
            }
        }

        switch (phase) {
            case OPEN_PRAYER_TAB:
                executeOpenPrayerTab(ctx);
                break;
            case EXECUTE_OPERATION:
                executeOperation(ctx);
                break;
            case VERIFY:
                executeVerify(ctx);
                break;
            case DONE:
                complete();
                break;
        }
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private void executeOpenPrayerTab(TaskContext ctx) {
        // Quick prayers don't need the tab open
        if (operation == PrayerOperation.QUICK_PRAYERS) {
            phase = Phase.EXECUTE_OPERATION;
            waitTicks = 0;
            return;
        }

        Client client = ctx.getClient();

        // Check if prayer tab is already open
        Widget prayerTab = client.getWidget(PRAYERBOOK_GROUP_ID, 0);
        if (prayerTab != null && !prayerTab.isHidden()) {
            log.debug("Prayer tab already open");
            phase = Phase.EXECUTE_OPERATION;
            waitTicks = 0;
            return;
        }

        // Press F5 to open prayer tab
        log.debug("Opening prayer tab via F5");
        actionPending = true;
        ctx.getKeyboardController().pressKey(KeyEvent.VK_F5)
                .thenRun(() -> {
                    actionPending = false;
                    phase = Phase.EXECUTE_OPERATION;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    actionPending = false;
                    log.error("Failed to open prayer tab", e);
                    return null;
                });
    }

    private void executeOperation(TaskContext ctx) {
        switch (operation) {
            case ACTIVATE:
            case DEACTIVATE:
            case TOGGLE:
                executeClickPrayers(ctx);
                break;
            case DEACTIVATE_ALL:
                executeDeactivateAll(ctx);
                break;
            case PROTECTION_SWITCH:
                executeProtectionSwitch(ctx);
                break;
            case QUICK_PRAYERS:
                executeQuickPrayers(ctx);
                break;
        }
    }

    private void executeClickPrayers(TaskContext ctx) {
        if (targetPrayers == null || targetPrayers.isEmpty()) {
            log.warn("No target prayers specified");
            fail("No target prayers");
            return;
        }

        // Convert set to list for indexed access
        Prayer[] prayers = targetPrayers.toArray(new Prayer[0]);

        if (prayerIndex >= prayers.length) {
            // All prayers clicked
            phase = Phase.VERIFY;
            waitTicks = 0;
            return;
        }

        Prayer prayer = prayers[prayerIndex];
        int childId = getPrayerWidgetChild(prayer);

        clickPrayerWidget(ctx, childId, prayer.name(), () -> {
            prayerIndex++;
            // Small delay between multiple prayer clicks
            if (prayerIndex < prayers.length) {
                waitTicks = 0;
            }
        });
    }

    private void executeDeactivateAll(TaskContext ctx) {
        Client client = ctx.getClient();

        // Find all active prayers and click them to deactivate
        // Check each protection prayer
        boolean foundActive = false;

        for (Prayer prayer : Prayer.values()) {
            if (client.isPrayerActive(prayer)) {
                int childId = getPrayerWidgetChild(prayer);
                clickPrayerWidget(ctx, childId, "Deactivate " + prayer.name(), () -> {
                    // Continue checking for more
                });
                foundActive = true;
                return;  // Click one at a time
            }
        }

        if (!foundActive) {
            log.debug("No active prayers to deactivate");
            phase = Phase.DONE;
        }
    }

    private void executeProtectionSwitch(TaskContext ctx) {
        if (protectionStyle == null) {
            log.warn("No protection style specified");
            fail("No protection style");
            return;
        }

        Prayer targetPrayer = getProtectionPrayer(protectionStyle);
        if (targetPrayer == null) {
            log.warn("Unknown attack style for protection: {}", protectionStyle);
            fail("Unknown attack style");
            return;
        }

        Client client = ctx.getClient();

        // Check if already active
        if (client.isPrayerActive(targetPrayer)) {
            log.debug("Protection prayer {} already active", targetPrayer.name());
            phase = Phase.DONE;
            return;
        }

        int childId = getPrayerWidgetChild(targetPrayer);
        clickPrayerWidget(ctx, childId, targetPrayer.name(), () -> {
            phase = Phase.VERIFY;
            waitTicks = 0;
        });
    }

    private void executeQuickPrayers(TaskContext ctx) {
        // Click the quick-prayer orb
        Client client = ctx.getClient();
        Widget quickPrayerOrb = client.getWidget(QUICK_PRAYER_ORB_GROUP, QUICK_PRAYER_ORB_CHILD);

        if (quickPrayerOrb == null || quickPrayerOrb.isHidden()) {
            log.warn("Quick-prayer orb not visible");
            fail("Quick-prayer orb not visible");
            return;
        }

        Rectangle bounds = quickPrayerOrb.getBounds();
        if (bounds == null || bounds.width == 0) {
            log.warn("Quick-prayer orb has invalid bounds");
            fail("Invalid quick-prayer bounds");
            return;
        }

        int clickX = bounds.x + bounds.width / 2 + ThreadLocalRandom.current().nextInt(-3, 4);
        int clickY = bounds.y + bounds.height / 2 + ThreadLocalRandom.current().nextInt(-3, 4);

        log.debug("Clicking quick-prayer orb at ({}, {})", clickX, clickY);

        actionPending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    actionPending = false;
                    phase = Phase.DONE;
                })
                .exceptionally(e -> {
                    actionPending = false;
                    log.error("Failed to click quick-prayer orb", e);
                    return null;
                });
    }

    private void executeVerify(TaskContext ctx) {
        Client client = ctx.getClient();

        // Verify operation succeeded
        switch (operation) {
            case ACTIVATE:
                // Check if at least one target prayer is now active
                for (Prayer prayer : targetPrayers) {
                    if (client.isPrayerActive(prayer)) {
                        log.debug("Prayer {} successfully activated", prayer.name());
                        phase = Phase.DONE;
                        return;
                    }
                }
                break;

            case DEACTIVATE:
                // Check if all target prayers are now inactive
                boolean allInactive = true;
                for (Prayer prayer : targetPrayers) {
                    if (client.isPrayerActive(prayer)) {
                        allInactive = false;
                        break;
                    }
                }
                if (allInactive) {
                    log.debug("All target prayers deactivated");
                    phase = Phase.DONE;
                    return;
                }
                break;

            case PROTECTION_SWITCH:
                Prayer targetPrayer = getProtectionPrayer(protectionStyle);
                if (targetPrayer != null && client.isPrayerActive(targetPrayer)) {
                    log.debug("Protection prayer {} verified active", targetPrayer.name());
                    phase = Phase.DONE;
                    return;
                }
                break;

            default:
                phase = Phase.DONE;
                return;
        }

        // Still waiting for verification
        log.debug("Waiting for prayer state to update...");
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void clickPrayerWidget(TaskContext ctx, int childId, String prayerName, Runnable onSuccess) {
        Client client = ctx.getClient();
        Widget prayerWidget = client.getWidget(PRAYERBOOK_GROUP_ID, childId);

        if (prayerWidget == null || prayerWidget.isHidden()) {
            log.warn("Prayer widget not visible: {} (child {})", prayerName, childId);
            // Try again after opening tab
            phase = Phase.OPEN_PRAYER_TAB;
            return;
        }

        Rectangle bounds = prayerWidget.getBounds();
        if (bounds == null || bounds.width == 0) {
            log.warn("Prayer widget has invalid bounds: {}", prayerName);
            return;
        }

        int clickX = bounds.x + bounds.width / 2 + ThreadLocalRandom.current().nextInt(-5, 6);
        int clickY = bounds.y + bounds.height / 2 + ThreadLocalRandom.current().nextInt(-5, 6);

        log.debug("Clicking prayer {} at ({}, {})", prayerName, clickX, clickY);

        actionPending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    actionPending = false;
                    onSuccess.run();
                })
                .exceptionally(e -> {
                    actionPending = false;
                    log.error("Failed to click prayer {}", prayerName, e);
                    return null;
                });
    }

    /**
     * Get the widget child ID for a prayer.
     *
     * @param prayer the prayer
     * @return the widget child ID
     */
    public static int getPrayerWidgetChild(Prayer prayer) {
        if (prayer == null) {
            return PRAYER_PROTECT_MELEE_CHILD;
        }

        // Special cases for protection prayers (fixed IDs)
        switch (prayer) {
            case PROTECT_FROM_MAGIC:
                return PRAYER_PROTECT_MAGIC_CHILD;
            case PROTECT_FROM_MISSILES:
                return PRAYER_PROTECT_MISSILES_CHILD;
            case PROTECT_FROM_MELEE:
                return PRAYER_PROTECT_MELEE_CHILD;
            default:
                // Calculate from ordinal
                // InterfaceID.Prayerbook.PRAYER1 = 0x021d_0009 (child 9)
                return PRAYER_BASE_CHILD + prayer.ordinal();
        }
    }

    /**
     * Get the protection prayer for an attack style.
     *
     * @param style the attack style to protect from
     * @return the corresponding protection prayer, or null if unknown
     */
    public static Prayer getProtectionPrayer(AttackStyle style) {
        if (style == null) {
            return null;
        }
        switch (style) {
            case MELEE:
                return Prayer.PROTECT_FROM_MELEE;
            case RANGED:
                return Prayer.PROTECT_FROM_MISSILES;
            case MAGIC:
                return Prayer.PROTECT_FROM_MAGIC;
            default:
                return null;
        }
    }

    /**
     * Get the attack style that a protection prayer protects from.
     *
     * @param prayer the protection prayer
     * @return the attack style it protects from, or null if not a protection prayer
     */
    public static AttackStyle getProtectedStyle(Prayer prayer) {
        if (prayer == null) {
            return null;
        }
        switch (prayer) {
            case PROTECT_FROM_MELEE:
                return AttackStyle.MELEE;
            case PROTECT_FROM_MISSILES:
                return AttackStyle.RANGED;
            case PROTECT_FROM_MAGIC:
                return AttackStyle.MAGIC;
            default:
                return null;
        }
    }

    /**
     * Check if a prayer is a protection prayer.
     *
     * @param prayer the prayer to check
     * @return true if it's a protection prayer
     */
    public static boolean isProtectionPrayer(Prayer prayer) {
        return prayer == Prayer.PROTECT_FROM_MAGIC ||
               prayer == Prayer.PROTECT_FROM_MISSILES ||
               prayer == Prayer.PROTECT_FROM_MELEE;
    }

    /**
     * Check if a prayer is an overhead prayer (protection prayers + smites).
     *
     * @param prayer the prayer to check
     * @return true if it's an overhead prayer
     */
    public static boolean isOverheadPrayer(Prayer prayer) {
        return isProtectionPrayer(prayer) ||
               prayer == Prayer.RETRIBUTION ||
               prayer == Prayer.REDEMPTION ||
               prayer == Prayer.SMITE;
    }

    // ========================================================================
    // Task Metadata
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }

        switch (operation) {
            case ACTIVATE:
                return "Activate prayer: " + formatPrayers();
            case DEACTIVATE:
                return "Deactivate prayer: " + formatPrayers();
            case DEACTIVATE_ALL:
                return "Deactivate all prayers";
            case TOGGLE:
                return "Toggle prayer: " + formatPrayers();
            case PROTECTION_SWITCH:
                return "Protect from " + protectionStyle;
            case QUICK_PRAYERS:
                return "Toggle quick-prayers";
            default:
                return "Prayer task";
        }
    }

    private String formatPrayers() {
        if (targetPrayers == null || targetPrayers.isEmpty()) {
            return "none";
        }
        if (targetPrayers.size() == 1) {
            return targetPrayers.iterator().next().name();
        }
        return targetPrayers.size() + " prayers";
    }
}

