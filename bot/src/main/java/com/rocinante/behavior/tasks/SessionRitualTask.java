package com.rocinante.behavior.tasks;

import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import java.awt.Rectangle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes session start rituals based on player profile.
 * 
 * Per REQUIREMENTS.md Section 3.5.1:
 * - 2-4 account-specific actions at session start
 * - 80% probability to execute each ritual
 * 
 * Common rituals:
 * - BANK_CHECK: Open bank, scan tabs, close (simplified to inventory hover)
 * - SKILL_TAB_CHECK: Open skills tab, check specific skills, close after 1-3s
 * - FRIENDS_LIST_CHECK: Open friends list, scan for online friends, close
 * - EQUIPMENT_REVIEW: Open equipment tab, hover over worn items, close
 * - INVENTORY_ORGANIZE: Move mouse around inventory area (no rearrangement)
 * - WORLD_CHECK: Click logout tab, hover world switcher, close
 * 
 * Only executed on fresh sessions (>15 min since last logout).
 */
@Slf4j
public class SessionRitualTask extends BehavioralTask {

    // === Ritual phases ===
    private enum RitualPhase {
        INIT,
        OPENING_TAB,
        VIEWING_CONTENT,
        CLOSING_TAB,
        NEXT_RITUAL,
        COMPLETED
    }

    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    private final HumanTimer humanTimer;
    
    private Instant startTime;
    private boolean started = false;
    
    private List<String> ritualsToExecute;
    private int currentRitualIndex = 0;
    private RitualPhase phase = RitualPhase.INIT;
    
    // Timing for current ritual
    private long ritualStartTime = 0;
    private long ritualDuration = 0;
    private int hoverCount = 0;
    private int maxHovers = 0;
    
    // Async operation tracking
    private CompletableFuture<?> pendingOperation = null;

    public SessionRitualTask(PlayerProfile playerProfile,
                             Randomization randomization,
                             HumanTimer humanTimer) {
        super(BreakType.MICRO_PAUSE, Duration.ofSeconds(60)); // Allow time for all rituals
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        this.humanTimer = humanTimer;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Wait for pending operations
        if (pendingOperation != null && !pendingOperation.isDone()) {
            return;
        }
        
        if (!started) {
            startRituals();
        }
        
        switch (phase) {
            case INIT:
                if (ritualsToExecute.isEmpty()) {
                    phase = RitualPhase.COMPLETED;
                } else {
                    initCurrentRitual();
                    phase = RitualPhase.OPENING_TAB;
                }
                break;
                
            case OPENING_TAB:
                executeOpenTab(ctx);
                break;
                
            case VIEWING_CONTENT:
                executeViewContent(ctx);
                break;
                
            case CLOSING_TAB:
                executeCloseTab(ctx);
                break;
                
            case NEXT_RITUAL:
                currentRitualIndex++;
                if (currentRitualIndex >= ritualsToExecute.size()) {
                    phase = RitualPhase.COMPLETED;
                } else {
                    initCurrentRitual();
                    phase = RitualPhase.OPENING_TAB;
                }
                break;
                
            case COMPLETED:
                transitionTo(TaskState.COMPLETED);
                break;
        }
    }

    private void startRituals() {
        startTime = Instant.now();
        started = true;
        
        // Filter rituals by execution probability
        ritualsToExecute = new ArrayList<>();
        double executionProb = playerProfile.getRitualExecutionProbability();
        
        for (String ritual : playerProfile.getSessionRituals()) {
            if (randomization.chance(executionProb)) {
                ritualsToExecute.add(ritual);
            }
        }
        
        log.info("Starting session rituals: {} of {} selected ({})",
                ritualsToExecute.size(), playerProfile.getSessionRituals().size(), ritualsToExecute);
    }

    private void initCurrentRitual() {
        String ritual = ritualsToExecute.get(currentRitualIndex);
        log.debug("Initializing ritual: {}", ritual);
        
        ritualStartTime = System.currentTimeMillis();
        hoverCount = 0;
        
        // Set ritual-specific parameters
        switch (ritual) {
            case "BANK_CHECK":
                ritualDuration = randomization.uniformRandomLong(2000, 6000);
                maxHovers = randomization.uniformRandomInt(2, 4);
                break;
            case "SKILL_TAB_CHECK":
                ritualDuration = randomization.uniformRandomLong(1000, 3000);
                maxHovers = randomization.uniformRandomInt(2, 5);
                break;
            case "FRIENDS_LIST_CHECK":
                ritualDuration = randomization.uniformRandomLong(1000, 2000);
                maxHovers = randomization.uniformRandomInt(1, 3);
                break;
            case "EQUIPMENT_REVIEW":
                ritualDuration = randomization.uniformRandomLong(1000, 2500);
                maxHovers = randomization.uniformRandomInt(2, 4);
                break;
            case "INVENTORY_ORGANIZE":
                ritualDuration = randomization.uniformRandomLong(2000, 5000);
                maxHovers = randomization.uniformRandomInt(3, 6);
                break;
            case "WORLD_CHECK":
                ritualDuration = randomization.uniformRandomLong(1000, 3000);
                maxHovers = randomization.uniformRandomInt(1, 2);
                break;
            default:
                ritualDuration = randomization.uniformRandomLong(500, 1500);
                maxHovers = 0;
                break;
        }
    }

    private void executeOpenTab(TaskContext ctx) {
        String ritual = ritualsToExecute.get(currentRitualIndex);
        Client client = ctx.getClient();
        
        Widget tabWidget = null;
        final String tabName;
        
        switch (ritual) {
            case "SKILL_TAB_CHECK":
                tabWidget = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_STATS_TAB, 
                                        WidgetInfo.RESIZABLE_VIEWPORT_STATS_TAB);
                tabName = "Stats";
                break;
                
            case "FRIENDS_LIST_CHECK":
                tabWidget = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_FRIENDS_TAB,
                                        WidgetInfo.RESIZABLE_VIEWPORT_FRIENDS_TAB);
                tabName = "Friends";
                break;
                
            case "EQUIPMENT_REVIEW":
                tabWidget = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_EQUIPMENT_TAB,
                                        WidgetInfo.RESIZABLE_VIEWPORT_EQUIPMENT_TAB);
                tabName = "Equipment";
                break;
                
            case "INVENTORY_ORGANIZE":
                tabWidget = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
                                        WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB);
                tabName = "Inventory";
                break;
                
            case "WORLD_CHECK":
                tabWidget = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_LOGOUT_TAB,
                                        WidgetInfo.RESIZABLE_VIEWPORT_LOGOUT_TAB);
                tabName = "Logout";
                break;
                
            case "BANK_CHECK":
                // Bank check just does inventory hover without opening bank
                tabWidget = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
                                        WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB);
                tabName = "Inventory";
                break;
                
            default:
                // Unknown ritual, skip to viewing
                phase = RitualPhase.VIEWING_CONTENT;
                return;
        }
        
        if (tabWidget != null && !tabWidget.isHidden()) {
            log.trace("Clicking {} tab for ritual {}", tabName, ritual);
            pendingOperation = clickWidget(ctx, tabWidget)
                .thenRun(() -> {
                    log.trace("{} tab clicked", tabName);
                    phase = RitualPhase.VIEWING_CONTENT;
                });
        } else {
            // Tab not available, skip to viewing
            log.trace("Tab widget not available for {}, skipping to view", ritual);
            phase = RitualPhase.VIEWING_CONTENT;
        }
    }

    private void executeViewContent(TaskContext ctx) {
        String ritual = ritualsToExecute.get(currentRitualIndex);
        long elapsed = System.currentTimeMillis() - ritualStartTime;
        
        // Check if ritual duration has elapsed
        if (elapsed >= ritualDuration) {
            phase = RitualPhase.CLOSING_TAB;
            return;
        }
        
        // Check if we've done enough hovers
        if (hoverCount >= maxHovers) {
            // Just wait for duration to complete
            return;
        }
        
        // Perform hover based on ritual type
        Widget hoverTarget = selectHoverTarget(ctx, ritual);
        
        if (hoverTarget != null && !hoverTarget.isHidden()) {
            hoverCount++;
            Rectangle bounds = hoverTarget.getBounds();
            if (bounds != null && bounds.width > 0) {
                int x = bounds.x + randomization.uniformRandomInt(5, bounds.width - 5);
                int y = bounds.y + randomization.uniformRandomInt(5, bounds.height - 5);
                
                log.trace("Hovering over {} target at ({}, {})", ritual, x, y);
                pendingOperation = ctx.getMouseController().moveToCanvas(x, y)
                    .thenCompose(v -> humanTimer.sleep(randomization.uniformRandomLong(200, 600)));
            }
        } else {
            // No valid target, perform idle behavior instead
            pendingOperation = ctx.getMouseController().performIdleBehavior()
                .thenCompose(v -> humanTimer.sleep(randomization.uniformRandomLong(300, 800)));
            hoverCount++;
        }
    }

    private Widget selectHoverTarget(TaskContext ctx, String ritual) {
        Client client = ctx.getClient();
        
        switch (ritual) {
            case "SKILL_TAB_CHECK":
                // Hover over a random skill in the skills container
                Widget skillsContainer = client.getWidget(WidgetInfo.SKILLS_CONTAINER);
                if (skillsContainer != null && !skillsContainer.isHidden()) {
                    Widget[] children = skillsContainer.getStaticChildren();
                    if (children != null && children.length > 0) {
                        // Skills are indexed 1-23 typically
                        int skillIndex = randomization.uniformRandomInt(0, Math.min(22, children.length - 1));
                        return children[skillIndex];
                    }
                }
                break;
                
            case "EQUIPMENT_REVIEW":
                // Hover over equipment slots
                Widget equipmentContainer = client.getWidget(WidgetInfo.EQUIPMENT);
                if (equipmentContainer != null && !equipmentContainer.isHidden()) {
                    Widget[] children = equipmentContainer.getDynamicChildren();
                    if (children != null && children.length > 0) {
                        int slotIndex = randomization.uniformRandomInt(0, children.length - 1);
                        return children[slotIndex];
                    }
                }
                break;
                
            case "INVENTORY_ORGANIZE":
            case "BANK_CHECK":
                // Hover over inventory slots
                Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
                if (inventory != null && !inventory.isHidden()) {
                    Widget[] children = inventory.getDynamicChildren();
                    if (children != null && children.length > 0) {
                        int slotIndex = randomization.uniformRandomInt(0, Math.min(27, children.length - 1));
                        return children[slotIndex];
                    }
                }
                break;
                
            case "FRIENDS_LIST_CHECK":
                // Hover over friends list entries
                Widget friendsList = client.getWidget(WidgetInfo.FRIEND_LIST_NAMES_CONTAINER);
                if (friendsList != null && !friendsList.isHidden()) {
                    Widget[] children = friendsList.getDynamicChildren();
                    if (children != null && children.length > 0) {
                        int index = randomization.uniformRandomInt(0, children.length - 1);
                        return children[index];
                    }
                }
                break;
                
            case "WORLD_CHECK":
                // Hover over world switcher button
                Widget worldSwitcher = client.getWidget(WidgetInfo.WORLD_SWITCHER_BUTTON);
                if (worldSwitcher != null && !worldSwitcher.isHidden()) {
                    return worldSwitcher;
                }
                break;
        }
        
        return null;
    }

    private void executeCloseTab(TaskContext ctx) {
        String ritual = ritualsToExecute.get(currentRitualIndex);
        
        // For most rituals, we just click the inventory tab to "close"
        // since that's the most common resting state
        Widget inventoryTab = getTabWidget(ctx.getClient(), 
                                           WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
                                           WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB);
        
        if (inventoryTab != null && !inventoryTab.isHidden() && 
            !ritual.equals("INVENTORY_ORGANIZE") && !ritual.equals("BANK_CHECK")) {
            log.trace("Returning to inventory tab after {}", ritual);
            pendingOperation = clickWidget(ctx, inventoryTab)
                .thenCompose(v -> humanTimer.sleep(randomization.uniformRandomLong(200, 500)))
                .thenRun(() -> {
                    log.debug("Ritual {} completed", ritual);
                    phase = RitualPhase.NEXT_RITUAL;
                });
        } else {
            // Just add a brief pause between rituals
            pendingOperation = humanTimer.sleep(randomization.uniformRandomLong(300, 800))
                .thenRun(() -> {
                    log.debug("Ritual {} completed", ritual);
                    phase = RitualPhase.NEXT_RITUAL;
                });
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Get tab widget trying fixed viewport first, then resizable.
     */
    private Widget getTabWidget(Client client, WidgetInfo fixed, WidgetInfo resizable) {
        Widget widget = client.getWidget(fixed);
        if (widget == null || widget.isHidden()) {
            widget = client.getWidget(resizable);
        }
        return widget;
    }

    /**
     * Click a widget and return a future.
     */
    private CompletableFuture<Void> clickWidget(TaskContext ctx, Widget widget) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            return CompletableFuture.completedFuture(null);
        }
        
        int x = bounds.x + randomization.uniformRandomInt(5, Math.max(6, bounds.width - 5));
        int y = bounds.y + randomization.uniformRandomInt(5, Math.max(6, bounds.height - 5));
        
        return ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenCompose(v -> humanTimer.sleep(randomization.uniformRandomLong(100, 300)));
    }

    @Override
    public void onComplete(TaskContext ctx) {
        Duration actualDuration = Duration.between(startTime, Instant.now());
        log.info("Session rituals completed: {} rituals in {} seconds", 
                currentRitualIndex, actualDuration.toSeconds());
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        log.debug("Session rituals interrupted after {} rituals", currentRitualIndex);
    }

    @Override
    public String getDescription() {
        if (ritualsToExecute != null && currentRitualIndex < ritualsToExecute.size()) {
            return "Session ritual: " + ritualsToExecute.get(currentRitualIndex);
        }
        return "Session start rituals";
    }
}
