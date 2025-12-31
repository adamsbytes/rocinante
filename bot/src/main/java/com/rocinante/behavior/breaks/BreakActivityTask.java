package com.rocinante.behavior.breaks;

import com.rocinante.behavior.BreakType;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.input.CameraController;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.tasks.TaskState;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Break activity task - performs humanized behaviors during breaks.
 * 
 * Per REQUIREMENTS.md Section 4.3.2:
 * 
 * Break Behavior:
 * - Short and long breaks may include:
 *   - Opening random tabs (skills, quest log)
 *   - Moving camera idly
 *   - Typing partial messages then deleting
 *   - Hovering over inventory/equipment
 *   - Scrolling through chat
 *   - Pure AFK (no actions)
 * 
 * Break Activities (from PlayerProfile.breakActivityWeights):
 * - SKILLS_TAB_HOVER: Open skills tab, hover, close
 * - INVENTORY_HOVER: Move mouse around inventory area
 * - EQUIPMENT_CHECK: Open equipment tab, examine items
 * - FRIENDS_LIST_CHECK: Check friends online
 * - CAMERA_DRIFT: Slow camera rotation
 * - PURE_AFK: No actions, just wait
 * - XP_TRACKER_HOVER: Hover over XP tracker
 * - CHAT_SCROLL: Scroll through game messages
 * - MINIMAP_DRAG: Drag minimap view around
 * 
 * During MICRO_PAUSE: Only stationary or small mouse drift
 * During SHORT_BREAK: 1-3 activities from above
 * During LONG_BREAK: 3-6 activities, may include logout
 */
@Slf4j
public class BreakActivityTask extends AbstractTask {

    // === Break phases ===
    private enum Phase {
        INIT,
        SELECTING_ACTIVITIES,
        EXECUTING_ACTIVITY,
        WAITING,
        COMPLETED
    }

    private final BreakType breakType;
    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    private final Duration breakDuration;
    
    private long startTime = 0;
    private Phase phase = Phase.INIT;
    
    private List<String> activitiesToExecute = new ArrayList<>();
    private int currentActivityIndex = 0;
    private long activityStartTime = 0;
    private long currentActivityDuration = 0;
    
    private CompletableFuture<?> pendingOperation = null;

    public BreakActivityTask(BreakType breakType, PlayerProfile playerProfile) {
        this(breakType, playerProfile, new Randomization());
    }

    public BreakActivityTask(BreakType breakType, PlayerProfile playerProfile, Randomization randomization) {
        this.breakType = breakType;
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        
        // Calculate break duration
        long minMs = breakType.getMinDuration().toMillis();
        long maxMs = breakType.getMaxDuration().toMillis();
        
        if (minMs == 0 && maxMs == 0) {
            // SESSION_END has no duration (logout)
            this.breakDuration = Duration.ZERO;
        } else {
            // Random duration in range
            long durationMs = minMs + randomization.uniformRandomLong(0, maxMs - minMs);
            this.breakDuration = Duration.ofMillis(durationMs);
        }
        
        this.timeout = breakDuration.plusSeconds(30); // Extra time for activity execution
        this.priority = TaskPriority.BEHAVIORAL;
    }

    @Override
    public String getDescription() {
        if (phase == Phase.EXECUTING_ACTIVITY && currentActivityIndex < activitiesToExecute.size()) {
            return "Break: " + activitiesToExecute.get(currentActivityIndex);
        }
        return "Break: " + breakType.getDisplayName();
    }

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Wait for pending operations
        if (pendingOperation != null && !pendingOperation.isDone()) {
            return;
        }
        
        switch (phase) {
            case INIT:
                initBreak();
                break;
                
            case SELECTING_ACTIVITIES:
                selectActivities();
                break;
                
            case EXECUTING_ACTIVITY:
                executeCurrentActivity(ctx);
                break;
                
            case WAITING:
                waitForBreakEnd(ctx);
                break;
                
            case COMPLETED:
                transitionTo(TaskState.COMPLETED);
                break;
        }
    }

    private void initBreak() {
        startTime = System.currentTimeMillis();
        log.info("Starting {} for {}", breakType.getDisplayName(), breakDuration);
        phase = Phase.SELECTING_ACTIVITIES;
    }

    private void selectActivities() {
        activitiesToExecute.clear();
        
        // Determine number of activities based on break type
        int numActivities;
        switch (breakType) {
            case MICRO_PAUSE:
                // Micro-pause: 0-1 activities (usually just stationary)
                numActivities = randomization.chance(0.5) ? 0 : 1;
                if (numActivities == 1) {
                    // Only allow simple activities for micro-pause
                    if (randomization.chance(0.7)) {
                        activitiesToExecute.add("PURE_AFK");
                    } else {
                        activitiesToExecute.add("CAMERA_DRIFT");
                    }
                }
                break;
                
            case SHORT_BREAK:
                // Short break: 1-3 activities
                numActivities = randomization.uniformRandomInt(1, 3);
                for (int i = 0; i < numActivities; i++) {
                    String activity = playerProfile.selectBreakActivity();
                    if (!activitiesToExecute.contains(activity) || activity.equals("PURE_AFK")) {
                        activitiesToExecute.add(activity);
                    }
                }
                break;
                
            case LONG_BREAK:
                // Long break: 3-6 activities
                numActivities = randomization.uniformRandomInt(3, 6);
                for (int i = 0; i < numActivities; i++) {
                    String activity = playerProfile.selectBreakActivity();
                    if (!activitiesToExecute.contains(activity) || activity.equals("PURE_AFK")) {
                        activitiesToExecute.add(activity);
                    }
                }
                break;
                
            case SESSION_END:
                // Session end: Just logout (handled elsewhere via LogoutHandler)
                // Add a brief AFK before the logout occurs
                activitiesToExecute.add("PURE_AFK");
                break;
                
            default:
                activitiesToExecute.add("PURE_AFK");
                break;
        }
        
        log.debug("Selected {} break activities: {}", activitiesToExecute.size(), activitiesToExecute);
        
        currentActivityIndex = 0;
        if (activitiesToExecute.isEmpty()) {
            phase = Phase.WAITING;
        } else {
            initCurrentActivity();
            phase = Phase.EXECUTING_ACTIVITY;
        }
    }

    private void initCurrentActivity() {
        activityStartTime = System.currentTimeMillis();
        
        // Activity duration varies by type
        String activity = activitiesToExecute.get(currentActivityIndex);
        switch (activity) {
            case "PURE_AFK":
                // AFK duration fills more of the break time
                long remainingTime = breakDuration.toMillis() - (System.currentTimeMillis() - startTime);
                currentActivityDuration = Math.max(1000, remainingTime / Math.max(1, activitiesToExecute.size() - currentActivityIndex));
                break;
            case "CAMERA_DRIFT":
                currentActivityDuration = randomization.uniformRandomLong(1000, 3000);
                break;
            case "SKILLS_TAB_HOVER":
            case "EQUIPMENT_CHECK":
            case "FRIENDS_LIST_CHECK":
                currentActivityDuration = randomization.uniformRandomLong(1000, 3000);
                break;
            case "INVENTORY_HOVER":
                currentActivityDuration = randomization.uniformRandomLong(1500, 4000);
                break;
            case "XP_TRACKER_HOVER":
                currentActivityDuration = randomization.uniformRandomLong(500, 1500);
                break;
            case "CHAT_SCROLL":
            case "MINIMAP_DRAG":
                currentActivityDuration = randomization.uniformRandomLong(1000, 2500);
                break;
            default:
                currentActivityDuration = randomization.uniformRandomLong(1000, 2000);
                break;
        }
        
        log.debug("Starting activity {} for {}ms", activity, currentActivityDuration);
    }

    private void executeCurrentActivity(TaskContext ctx) {
        if (currentActivityIndex >= activitiesToExecute.size()) {
            phase = Phase.WAITING;
            return;
        }
        
        // Check if current activity duration has elapsed
        long activityElapsed = System.currentTimeMillis() - activityStartTime;
        if (activityElapsed >= currentActivityDuration) {
            // Move to next activity
            currentActivityIndex++;
            if (currentActivityIndex < activitiesToExecute.size()) {
                initCurrentActivity();
            } else {
                phase = Phase.WAITING;
            }
            return;
        }
        
        String activity = activitiesToExecute.get(currentActivityIndex);
        
        switch (activity) {
            case "PURE_AFK":
                executePureAfk(ctx);
                break;
            case "CAMERA_DRIFT":
                executeCameraDrift(ctx);
                break;
            case "SKILLS_TAB_HOVER":
                executeSkillsTabHover(ctx);
                break;
            case "INVENTORY_HOVER":
                executeInventoryHover(ctx);
                break;
            case "EQUIPMENT_CHECK":
                executeEquipmentCheck(ctx);
                break;
            case "FRIENDS_LIST_CHECK":
                executeFriendsListCheck(ctx);
                break;
            case "XP_TRACKER_HOVER":
                executeXpTrackerHover(ctx);
                break;
            case "CHAT_SCROLL":
                executeChatScroll(ctx);
                break;
            case "MINIMAP_DRAG":
                executeMinimapDrag(ctx);
                break;
            default:
                // Unknown activity, just wait
                executePureAfk(ctx);
                break;
        }
    }

    private void executePureAfk(TaskContext ctx) {
        // Pure AFK: No actions, just wait
        // Small chance of tiny mouse drift
        if (randomization.chance(0.1)) {
            pendingOperation = ctx.getMouseController().performIdleBehavior();
        } else {
            pendingOperation = ctx.getHumanTimer().sleep(randomization.uniformRandomLong(500, 1500));
        }
    }

    private void executeCameraDrift(TaskContext ctx) {
        CameraController camera = ctx.getCameraController();
        if (camera != null && !camera.isRotating()) {
            pendingOperation = camera.performIdleDrift();
        } else {
            pendingOperation = ctx.getHumanTimer().sleep(500);
        }
    }

    private void executeSkillsTabHover(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Click stats tab
        Widget statsTab = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_STATS_TAB, 
                                       WidgetInfo.RESIZABLE_VIEWPORT_STATS_TAB);
        
        if (statsTab != null && !statsTab.isHidden()) {
            pendingOperation = clickWidget(ctx, statsTab)
                .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(200, 400)))
                .thenCompose(v -> {
                    // Hover over skills
                    Widget skillsContainer = client.getWidget(WidgetInfo.SKILLS_CONTAINER);
                    if (skillsContainer != null && !skillsContainer.isHidden()) {
                        Widget[] children = skillsContainer.getStaticChildren();
                        if (children != null && children.length > 0) {
                            int skillIndex = randomization.uniformRandomInt(0, Math.min(22, children.length - 1));
                            return hoverWidget(ctx, children[skillIndex]);
                        }
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(500, 1500)))
                .thenCompose(v -> {
                    // Return to inventory
                    Widget invTab = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
                                                 WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB);
                    if (invTab != null) return clickWidget(ctx, invTab);
                    return CompletableFuture.completedFuture(null);
                });
        } else {
            pendingOperation = ctx.getHumanTimer().sleep(500);
        }
    }

    private void executeInventoryHover(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
        
        if (inventory != null && !inventory.isHidden()) {
            Widget[] children = inventory.getDynamicChildren();
            if (children != null && children.length > 0) {
                // Hover over a few random slots
                int numHovers = randomization.uniformRandomInt(2, 4);
                CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
                
                for (int i = 0; i < numHovers; i++) {
                    int slotIndex = randomization.uniformRandomInt(0, Math.min(27, children.length - 1));
                    final Widget slot = children[slotIndex];
                    chain = chain
                        .thenCompose(v -> hoverWidget(ctx, slot))
                        .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(300, 800)));
                }
                
                pendingOperation = chain;
            } else {
                pendingOperation = ctx.getHumanTimer().sleep(500);
            }
        } else {
            pendingOperation = ctx.getHumanTimer().sleep(500);
        }
    }

    private void executeEquipmentCheck(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Click equipment tab
        Widget equipTab = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_EQUIPMENT_TAB,
                                       WidgetInfo.RESIZABLE_VIEWPORT_EQUIPMENT_TAB);
        
        if (equipTab != null && !equipTab.isHidden()) {
            pendingOperation = clickWidget(ctx, equipTab)
                .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(200, 400)))
                .thenCompose(v -> {
                    // Hover over equipment
                    Widget equipment = client.getWidget(WidgetInfo.EQUIPMENT);
                    if (equipment != null && !equipment.isHidden()) {
                        Widget[] children = equipment.getDynamicChildren();
                        if (children != null && children.length > 0) {
                            int slotIndex = randomization.uniformRandomInt(0, children.length - 1);
                            return hoverWidget(ctx, children[slotIndex]);
                        }
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(500, 1500)))
                .thenCompose(v -> {
                    // Return to inventory
                    Widget invTab = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
                                                 WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB);
                    if (invTab != null) return clickWidget(ctx, invTab);
                    return CompletableFuture.completedFuture(null);
                });
        } else {
            pendingOperation = ctx.getHumanTimer().sleep(500);
        }
    }

    private void executeFriendsListCheck(TaskContext ctx) {
        Client client = ctx.getClient();
        
        // Click friends tab
        Widget friendsTab = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_FRIENDS_TAB,
                                         WidgetInfo.RESIZABLE_VIEWPORT_FRIENDS_TAB);
        
        if (friendsTab != null && !friendsTab.isHidden()) {
            pendingOperation = clickWidget(ctx, friendsTab)
                .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(500, 1500)))
                .thenCompose(v -> {
                    // Return to inventory
                    Widget invTab = getTabWidget(client, WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
                                                 WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB);
                    if (invTab != null) return clickWidget(ctx, invTab);
                    return CompletableFuture.completedFuture(null);
                });
        } else {
            pendingOperation = ctx.getHumanTimer().sleep(500);
        }
    }

    private void executeXpTrackerHover(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget xpTracker = client.getWidget(WidgetInfo.EXPERIENCE_TRACKER_WIDGET);
        
        if (xpTracker != null && !xpTracker.isHidden()) {
            pendingOperation = hoverWidget(ctx, xpTracker)
                .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(300, 800)));
        } else {
            // Fall back to XP orb
            Widget xpOrb = client.getWidget(WidgetInfo.MINIMAP_XP_ORB);
            if (xpOrb != null && !xpOrb.isHidden()) {
                pendingOperation = hoverWidget(ctx, xpOrb)
                    .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(300, 800)));
            } else {
                pendingOperation = ctx.getHumanTimer().sleep(500);
            }
        }
    }

    private void executeChatScroll(TaskContext ctx) {
        // Simulate chat scrolling - move mouse to chat area
        // Chat is typically in the bottom-left
        int chatX = randomization.uniformRandomInt(50, 200);
        int chatY = randomization.uniformRandomInt(400, 480);
        
        pendingOperation = ctx.getMouseController().moveToCanvas(chatX, chatY)
            .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(500, 1500)));
    }

    private void executeMinimapDrag(TaskContext ctx) {
        Client client = ctx.getClient();
        Widget minimap = client.getWidget(WidgetInfo.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
        if (minimap == null || minimap.isHidden()) {
            minimap = client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA);
        }
        
        if (minimap != null && !minimap.isHidden()) {
            Rectangle bounds = minimap.getBounds();
            if (bounds != null && bounds.width > 0) {
                int x = bounds.x + randomization.uniformRandomInt(10, bounds.width - 10);
                int y = bounds.y + randomization.uniformRandomInt(10, bounds.height - 10);
                
                pendingOperation = ctx.getMouseController().moveToCanvas(x, y)
                    .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(500, 1500)));
            } else {
                pendingOperation = ctx.getHumanTimer().sleep(500);
            }
        } else {
            pendingOperation = ctx.getHumanTimer().sleep(500);
        }
    }

    private void waitForBreakEnd(TaskContext ctx) {
        // Check if break duration has elapsed
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed >= breakDuration.toMillis()) {
            log.info("Break completed after {}ms", elapsed);
            phase = Phase.COMPLETED;
            return;
        }
        
        // Wait with occasional idle behaviors
        long remaining = breakDuration.toMillis() - elapsed;
        if (remaining > 1000 && randomization.chance(0.2)) {
            // 20% chance for idle behavior during wait
            pendingOperation = ctx.getMouseController().performIdleBehavior()
                .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(500, 1500)));
        } else {
            pendingOperation = ctx.getHumanTimer().sleep(Math.min(remaining, 1000));
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Widget getTabWidget(Client client, WidgetInfo fixed, WidgetInfo resizable) {
        Widget widget = client.getWidget(fixed);
        if (widget == null || widget.isHidden()) {
            widget = client.getWidget(resizable);
        }
        return widget;
    }

    private CompletableFuture<Void> clickWidget(TaskContext ctx, Widget widget) {
        if (widget == null || widget.isHidden()) {
            return CompletableFuture.completedFuture(null);
        }
        
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            return CompletableFuture.completedFuture(null);
        }
        
        int x = bounds.x + randomization.uniformRandomInt(5, Math.max(6, bounds.width - 5));
        int y = bounds.y + randomization.uniformRandomInt(5, Math.max(6, bounds.height - 5));
        
        return ctx.getMouseController().moveToCanvas(x, y)
                .thenCompose(v -> ctx.getMouseController().click());
    }

    private CompletableFuture<Void> hoverWidget(TaskContext ctx, Widget widget) {
        if (widget == null || widget.isHidden()) {
            return CompletableFuture.completedFuture(null);
        }
        
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            return CompletableFuture.completedFuture(null);
        }
        
        int x = bounds.x + randomization.uniformRandomInt(3, Math.max(4, bounds.width - 3));
        int y = bounds.y + randomization.uniformRandomInt(3, Math.max(4, bounds.height - 3));
        
        return ctx.getMouseController().moveToCanvas(x, y);
    }

    @Override
    public void onComplete(TaskContext ctx) {
        long actualDuration = System.currentTimeMillis() - startTime;
        log.debug("Break completed: {} ({}ms)", breakType, actualDuration);
    }

    @Override
    public void onFail(TaskContext ctx, Exception e) {
        log.debug("Break interrupted: {}", breakType);
    }
}
