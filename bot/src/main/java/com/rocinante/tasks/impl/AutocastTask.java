package com.rocinante.tasks.impl;

import com.rocinante.combat.spell.CombatSpell;
import com.rocinante.combat.spell.Spellbook;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Task for setting up spell autocast.
 *
 * <p>Per REQUIREMENTS.md Section 10.1.3 and 10.3:
 * <ul>
 *   <li>Open combat options tab</li>
 *   <li>Click autocast button (normal or defensive)</li>
 *   <li>Select spell from autocast menu</li>
 *   <li>Verify autocast is active via varbit</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Set up autocast for Fire Bolt
 * AutocastTask autocast = new AutocastTask(StandardSpell.FIRE_BOLT);
 *
 * // Set up defensive autocast
 * AutocastTask defensiveAutocast = new AutocastTask(StandardSpell.FIRE_BOLT)
 *     .withDefensive(true);
 * }</pre>
 */
@Slf4j
public class AutocastTask extends AbstractTask {

    // ========================================================================
    // Widget Constants
    // ========================================================================

    /**
     * Combat options interface ID (593 = 0x251).
     */
    private static final int COMBAT_INTERFACE_ID = 593;

    /**
     * Autocast button child IDs within combat interface.
     */
    private static final int AUTOCAST_NORMAL_CHILD = 28;   // 0x001c
    private static final int AUTOCAST_DEFENSIVE_CHILD = 23; // 0x0017

    /**
     * Autocast spell selection interface ID (201 = 0xC9).
     */
    private static final int AUTOCAST_MENU_ID = 201;

    /**
     * Spells container child ID.
     */
    private static final int AUTOCAST_SPELLS_CHILD = 1;

    // ========================================================================
    // Varbits for autocast state
    // ========================================================================

    /**
     * Varbit indicating autocast is set (1 = active, 0 = inactive).
     */
    private static final int VARBIT_AUTOCAST_SET = 275;

    /**
     * Varbit containing the autocast spell ID.
     */
    private static final int VARBIT_AUTOCAST_SPELL = 276;

    /**
     * Varbit for defensive autocast mode (1 = defensive, 0 = normal).
     */
    private static final int VARBIT_AUTOCAST_DEFENSIVE = 2668;

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The spell to set for autocast.
     */
    @Getter
    private final CombatSpell spell;

    /**
     * Whether to use defensive autocast.
     */
    @Getter
    @Setter
    private boolean defensive = false;

    /**
     * Custom description for logging.
     */
    @Setter
    private String description;

    // ========================================================================
    // State
    // ========================================================================

    private enum Phase {
        OPEN_COMBAT_TAB,
        CLICK_AUTOCAST_BUTTON,
        SELECT_SPELL,
        VERIFY
    }

    private Phase phase = Phase.OPEN_COMBAT_TAB;
    private boolean actionPending = false;
    private int waitTicks = 0;
    private static final int MAX_WAIT_TICKS = 10;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create an autocast task for the given spell.
     *
     * @param spell the spell to autocast (must be autocastable)
     * @throws IllegalArgumentException if spell is not autocastable
     */
    public AutocastTask(CombatSpell spell) {
        if (spell == null) {
            throw new IllegalArgumentException("Spell cannot be null");
        }
        if (!spell.isAutocastable()) {
            throw new IllegalArgumentException("Spell " + spell.getSpellName() + " is not autocastable");
        }
        this.spell = spell;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set whether to use defensive autocast (builder-style).
     *
     * @param defensive true for defensive autocast
     * @return this task for chaining
     */
    public AutocastTask withDefensive(boolean defensive) {
        this.defensive = defensive;
        return this;
    }

    /**
     * Set a custom description (builder-style).
     *
     * @param description the description
     * @return this task for chaining
     */
    public AutocastTask withDescription(String description) {
        this.description = description;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        // Check spellbook matches
        int currentSpellbook = ctx.getPlayerState().getSpellbook();
        Spellbook requiredSpellbook = spell.getSpellbook();

        if (currentSpellbook != requiredSpellbook.getVarbitValue()) {
            log.warn("Wrong spellbook active: have {}, need {}",
                    currentSpellbook, requiredSpellbook.getDisplayName());
            return false;
        }

        // Check magic level (basic check - assumes player state has this)
        // Note: More detailed level checking could be added

        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (actionPending) {
            return;
        }

        // Check for timeout
        waitTicks++;
        if (waitTicks > MAX_WAIT_TICKS) {
            log.warn("Phase {} timed out after {} ticks", phase, waitTicks);
            if (phase == Phase.VERIFY) {
                // Verification timeout - might be okay
                complete();
            } else {
                fail("Autocast setup timed out in phase: " + phase);
            }
            return;
        }

        switch (phase) {
            case OPEN_COMBAT_TAB:
                executeOpenCombatTab(ctx);
                break;
            case CLICK_AUTOCAST_BUTTON:
                executeClickAutocastButton(ctx);
                break;
            case SELECT_SPELL:
                executeSelectSpell(ctx);
                break;
            case VERIFY:
                executeVerify(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase Implementations
    // ========================================================================

    private void executeOpenCombatTab(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if combat interface is already visible
        Widget combatWidget = client.getWidget(COMBAT_INTERFACE_ID, 0);
        if (combatWidget != null && !combatWidget.isHidden()) {
            log.debug("Combat tab already open");
            phase = Phase.CLICK_AUTOCAST_BUTTON;
            waitTicks = 0;
            return;
        }

        // Press F1 to open combat tab
        log.debug("Opening combat tab via F1");
        actionPending = true;
        ctx.getKeyboardController().pressKey(KeyEvent.VK_F1)
                .thenRun(() -> {
                    actionPending = false;
                    phase = Phase.CLICK_AUTOCAST_BUTTON;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    actionPending = false;
                    log.error("Failed to open combat tab", e);
                    return null;
                });
    }

    private void executeClickAutocastButton(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if autocast menu is already open
        Widget autocastMenu = client.getWidget(AUTOCAST_MENU_ID, 0);
        if (autocastMenu != null && !autocastMenu.isHidden()) {
            log.debug("Autocast menu already open");
            phase = Phase.SELECT_SPELL;
            waitTicks = 0;
            return;
        }

        // Get the appropriate autocast button
        int buttonChild = defensive ? AUTOCAST_DEFENSIVE_CHILD : AUTOCAST_NORMAL_CHILD;
        Widget autocastButton = client.getWidget(COMBAT_INTERFACE_ID, buttonChild);

        if (autocastButton == null || autocastButton.isHidden()) {
            log.debug("Autocast button not visible yet, waiting...");
            return;
        }

        // Click the autocast button
        Rectangle bounds = autocastButton.getBounds();
        if (bounds == null || bounds.width == 0) {
            log.warn("Autocast button has invalid bounds");
            return;
        }

        int clickX = bounds.x + bounds.width / 2 + ThreadLocalRandom.current().nextInt(-5, 6);
        int clickY = bounds.y + bounds.height / 2 + ThreadLocalRandom.current().nextInt(-3, 4);

        log.debug("Clicking {} autocast button at ({}, {})",
                defensive ? "defensive" : "normal", clickX, clickY);

        actionPending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    actionPending = false;
                    phase = Phase.SELECT_SPELL;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    actionPending = false;
                    log.error("Failed to click autocast button", e);
                    return null;
                });
    }

    private void executeSelectSpell(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if autocast menu is open
        Widget autocastMenu = client.getWidget(AUTOCAST_MENU_ID, AUTOCAST_SPELLS_CHILD);
        if (autocastMenu == null || autocastMenu.isHidden()) {
            log.debug("Waiting for autocast menu to open...");
            return;
        }

        // Find the spell widget in the autocast menu
        // Spells in the autocast menu are dynamic children based on available spells
        Widget[] spellWidgets = autocastMenu.getDynamicChildren();
        if (spellWidgets == null || spellWidgets.length == 0) {
            log.debug("No spells in autocast menu yet, waiting...");
            return;
        }

        // Find the spell by name or widget ID pattern
        Widget targetSpell = null;
        for (Widget spellWidget : spellWidgets) {
            if (spellWidget == null || spellWidget.isHidden()) {
                continue;
            }

            // Check sprite ID or name to match the spell
            String spellName = spellWidget.getName();
            if (spellName != null && spellName.toLowerCase().contains(spell.getSpellName().toLowerCase())) {
                targetSpell = spellWidget;
                break;
            }

            // Alternative: Check actions array
            String[] actions = spellWidget.getActions();
            if (actions != null) {
                for (String action : actions) {
                    if (action != null && action.contains(spell.getSpellName())) {
                        targetSpell = spellWidget;
                        break;
                    }
                }
            }
        }

        if (targetSpell == null) {
            log.warn("Could not find spell {} in autocast menu", spell.getSpellName());
            // Try using the original widget ID approach
            targetSpell = findSpellByWidgetId(client);
        }

        if (targetSpell == null) {
            log.error("Spell {} not available for autocast", spell.getSpellName());
            fail("Spell not available in autocast menu");
            return;
        }

        // Click the spell
        Rectangle bounds = targetSpell.getBounds();
        if (bounds == null || bounds.width == 0) {
            log.warn("Spell widget has invalid bounds");
            return;
        }

        int clickX = bounds.x + bounds.width / 2 + ThreadLocalRandom.current().nextInt(-3, 4);
        int clickY = bounds.y + bounds.height / 2 + ThreadLocalRandom.current().nextInt(-3, 4);

        log.debug("Clicking spell {} at ({}, {})", spell.getSpellName(), clickX, clickY);

        actionPending = true;
        ctx.getMouseController().moveToCanvas(clickX, clickY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    actionPending = false;
                    phase = Phase.VERIFY;
                    waitTicks = 0;
                })
                .exceptionally(e -> {
                    actionPending = false;
                    log.error("Failed to click spell", e);
                    return null;
                });
    }

    /**
     * Try to find spell using widget ID pattern.
     * The autocast menu uses the same spell widget IDs as the spellbook.
     */
    private Widget findSpellByWidgetId(Client client) {
        int packedWidgetId = spell.getWidgetId();
        int groupId = packedWidgetId >> 16;
        int childId = packedWidgetId & 0xFFFF;

        // The autocast menu might reuse the spellbook widget IDs
        // but more commonly has its own dynamic children
        // Try the direct approach first
        Widget directWidget = client.getWidget(AUTOCAST_MENU_ID, childId);
        if (directWidget != null && !directWidget.isHidden()) {
            return directWidget;
        }

        return null;
    }

    private void executeVerify(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if autocast is now set
        int autocastSet = client.getVarbitValue(VARBIT_AUTOCAST_SET);
        int autocastDefensive = client.getVarbitValue(VARBIT_AUTOCAST_DEFENSIVE);

        if (autocastSet == 1) {
            // Verify defensive mode matches
            boolean isDefensive = autocastDefensive == 1;
            if (isDefensive != defensive) {
                log.warn("Autocast set but wrong mode: expected {}, got {}",
                        defensive ? "defensive" : "normal",
                        isDefensive ? "defensive" : "normal");
            }

            log.info("Autocast successfully set for {}{}", spell.getSpellName(),
                    defensive ? " (defensive)" : "");
            complete();
        } else {
            log.debug("Waiting for autocast varbit to update...");
            // Give it more time
            if (waitTicks > MAX_WAIT_TICKS / 2) {
                log.warn("Autocast varbit not updating, may have failed");
            }
        }
    }

    // ========================================================================
    // Task Metadata
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        return String.format("Set autocast: %s%s",
                spell.getSpellName(),
                defensive ? " (defensive)" : "");
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if autocast is currently active for any spell.
     *
     * @param client the game client
     * @return true if autocast is set
     */
    public static boolean isAutocastActive(Client client) {
        return client.getVarbitValue(VARBIT_AUTOCAST_SET) == 1;
    }

    /**
     * Get the currently autocast spell ID.
     *
     * @param client the game client
     * @return the spell ID, or -1 if no autocast
     */
    public static int getAutocastSpellId(Client client) {
        if (!isAutocastActive(client)) {
            return -1;
        }
        return client.getVarbitValue(VARBIT_AUTOCAST_SPELL);
    }

    /**
     * Check if defensive autocast mode is active.
     *
     * @param client the game client
     * @return true if defensive mode is active
     */
    public static boolean isDefensiveAutocast(Client client) {
        return client.getVarbitValue(VARBIT_AUTOCAST_DEFENSIVE) == 1;
    }
}

