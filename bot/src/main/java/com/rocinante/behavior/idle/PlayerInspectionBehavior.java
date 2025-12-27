package com.rocinante.behavior.idle;

import com.rocinante.behavior.PlayerProfile;
import com.rocinante.state.PlayerSnapshot;
import com.rocinante.state.WorldState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Player inspection behavior - simulates right-clicking nearby players to examine them.
 * 
 * Per REQUIREMENTS.md Section 3.5.2:
 * 
 * Right-Click Player Inspection:
 * - Frequency: 0-5 times per hour (per-account constant ±20% session variance)
 * - Target selection: 60% nearby players, 30% high-level players, 10% low-level players
 * - Inspection duration: 0.5-2 seconds (hover on examine)
 * 
 * This behavior should be periodically triggered during normal gameplay,
 * weighted by the player's profile preferences.
 */
@Slf4j
public class PlayerInspectionBehavior extends AbstractTask {

    // === Target selection probabilities (from PlayerProfile) ===
    // Now configurable per-account via PlayerProfile preferences
    // Nearby clamped to 30-80%, high/low can be any remaining distribution

    // === Inspection duration range (per REQUIREMENTS.md 3.5.2) ===
    private static final long MIN_INSPECT_DURATION_MS = 500;
    private static final long MAX_INSPECT_DURATION_MS = 2000;

    // === Phases ===
    private enum Phase {
        INIT,
        SELECTING_TARGET,
        MOVING_TO_TARGET,
        RIGHT_CLICKING,
        HOVERING_EXAMINE,
        DISMISSING,
        COMPLETED
    }

    // === Target selection category ===
    private enum TargetCategory {
        RANDOM_NEARBY,
        HIGH_LEVEL,
        LOW_LEVEL
    }

    private final PlayerProfile playerProfile;
    private final Randomization randomization;
    
    private Phase phase = Phase.INIT;
    private PlayerSnapshot selectedTarget = null;
    private TargetCategory targetCategory = TargetCategory.RANDOM_NEARBY;
    private long hoverDuration = 0;
    private long hoverStartTime = 0;
    
    private CompletableFuture<?> pendingOperation = null;
    
    public PlayerInspectionBehavior(PlayerProfile playerProfile, Randomization randomization) {
        this.playerProfile = playerProfile;
        this.randomization = randomization;
        this.timeout = Duration.ofSeconds(10);
    }

    /**
     * Simplified constructor for backwards compatibility.
     */
    public PlayerInspectionBehavior(PlayerProfile playerProfile) {
        this.playerProfile = playerProfile;
        this.randomization = new Randomization();
        this.timeout = Duration.ofSeconds(10);
    }

    @Override
    public String getDescription() {
        if (selectedTarget != null && selectedTarget.getName() != null) {
            return "Inspecting player: " + selectedTarget.getName();
        }
        return "Inspect nearby player";
    }

    @Override
    public boolean canExecute(TaskContext ctx) {
        // Don't inspect players during combat or critical activities
        if (ctx.getCombatState() != null && ctx.getCombatState().hasTarget()) {
            return false;
        }
        
        // Need to be logged in
        if (!ctx.isLoggedIn()) {
            return false;
        }
        
        // Need nearby players to inspect
        WorldState worldState = ctx.getWorldState();
        if (worldState == null || worldState.getNearbyPlayers().isEmpty()) {
            return false;
        }
        
        return true;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        // Wait for pending operations
        if (pendingOperation != null && !pendingOperation.isDone()) {
            return;
        }
        
        switch (phase) {
            case INIT:
                initInspection(ctx);
                break;
                
            case SELECTING_TARGET:
                selectTarget(ctx);
                break;
                
            case MOVING_TO_TARGET:
                moveToTarget(ctx);
                break;
                
            case RIGHT_CLICKING:
                rightClickTarget(ctx);
                break;
                
            case HOVERING_EXAMINE:
                hoverExamine(ctx);
                break;
                
            case DISMISSING:
                dismissMenu(ctx);
                break;
                
            case COMPLETED:
                transitionTo(TaskState.COMPLETED);
                break;
        }
    }

    private void initInspection(TaskContext ctx) {
        // Set hover duration for this inspection
        hoverDuration = randomization.uniformRandomLong(MIN_INSPECT_DURATION_MS, MAX_INSPECT_DURATION_MS);
        
        // Use per-account preferences from PlayerProfile
        double nearbyProb = playerProfile.getInspectionNearbyProbability();
        double highLevelProb = playerProfile.getInspectionHighLevelProbability();
        // Low-level gets the remainder
        
        double roll = randomization.uniformRandom(0, 1);
        if (roll < nearbyProb) {
            targetCategory = TargetCategory.RANDOM_NEARBY;
        } else if (roll < nearbyProb + highLevelProb) {
            targetCategory = TargetCategory.HIGH_LEVEL;
        } else {
            targetCategory = TargetCategory.LOW_LEVEL;
        }
        
        log.debug("Initiating player inspection (category: {}, duration: {}ms, probs: nearby={}%, high={}%, low={}%)", 
                 targetCategory, hoverDuration,
                 String.format("%.0f", nearbyProb * 100), 
                 String.format("%.0f", highLevelProb * 100), 
                 String.format("%.0f", (1.0 - nearbyProb - highLevelProb) * 100));
        
        phase = Phase.SELECTING_TARGET;
    }

    private void selectTarget(TaskContext ctx) {
        WorldState worldState = ctx.getWorldState();
        List<PlayerSnapshot> candidates = worldState.getNearbyPlayers();
        
        if (candidates.isEmpty()) {
            log.debug("No nearby players to inspect, completing");
            phase = Phase.COMPLETED;
            return;
        }
        
        // Get local player combat level for comparison
        int localCombatLevel = 0;
        if (ctx.getClient().getLocalPlayer() != null) {
            localCombatLevel = ctx.getClient().getLocalPlayer().getCombatLevel();
        }
        
        selectedTarget = selectTargetByCategory(candidates, localCombatLevel);
        
        if (selectedTarget == null) {
            log.debug("Could not select target player, completing");
            phase = Phase.COMPLETED;
            return;
        }
        
        log.trace("Selected target: {} (lvl {})", 
                 selectedTarget.getName(), selectedTarget.getCombatLevel());
        
        phase = Phase.MOVING_TO_TARGET;
    }

    private PlayerSnapshot selectTargetByCategory(List<PlayerSnapshot> candidates, int localCombatLevel) {
        switch (targetCategory) {
            case HIGH_LEVEL:
                return selectHighLevelPlayer(candidates, localCombatLevel);
            case LOW_LEVEL:
                return selectLowLevelPlayer(candidates, localCombatLevel);
            case RANDOM_NEARBY:
            default:
                return selectRandomPlayer(candidates);
        }
    }

    /**
     * Select a random player from the candidates.
     */
    private PlayerSnapshot selectRandomPlayer(List<PlayerSnapshot> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        int index = randomization.uniformRandomInt(0, candidates.size() - 1);
        return candidates.get(index);
    }

    /**
     * Select a high-level player (from top 25% by combat level).
     */
    private PlayerSnapshot selectHighLevelPlayer(List<PlayerSnapshot> candidates, int localCombatLevel) {
        // Sort by combat level descending
        List<PlayerSnapshot> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(PlayerSnapshot::getCombatLevel).reversed())
                .collect(Collectors.toList());
        
        if (sorted.isEmpty()) {
            return null;
        }
        
        // Pick from top 25%
        int topQuartileSize = Math.max(1, sorted.size() / 4);
        int index = randomization.uniformRandomInt(0, topQuartileSize - 1);
        return sorted.get(index);
    }

    /**
     * Select a low-level player (from bottom 25% by combat level).
     */
    private PlayerSnapshot selectLowLevelPlayer(List<PlayerSnapshot> candidates, int localCombatLevel) {
        // Sort by combat level ascending
        List<PlayerSnapshot> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(PlayerSnapshot::getCombatLevel))
                .collect(Collectors.toList());
        
        if (sorted.isEmpty()) {
            return null;
        }
        
        // Pick from bottom 25%
        int bottomQuartileSize = Math.max(1, sorted.size() / 4);
        int index = randomization.uniformRandomInt(0, bottomQuartileSize - 1);
        return sorted.get(index);
    }

    private void moveToTarget(TaskContext ctx) {
        // Get canvas position for the target player
        java.awt.Point canvasPoint = getPlayerCanvasPoint(ctx, selectedTarget);
        
        if (canvasPoint == null) {
            log.trace("Could not get canvas point for target player, completing");
            phase = Phase.COMPLETED;
            return;
        }
        
        log.trace("Moving mouse to player at ({}, {})", canvasPoint.x, canvasPoint.y);
        
        pendingOperation = ctx.getMouseController().moveToCanvas(canvasPoint.x, canvasPoint.y)
            .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(100, 300)))
            .thenRun(() -> phase = Phase.RIGHT_CLICKING);
    }

    private void rightClickTarget(TaskContext ctx) {
        // Get current position and right-click
        java.awt.Point canvasPoint = getPlayerCanvasPoint(ctx, selectedTarget);
        
        if (canvasPoint == null) {
            log.trace("Lost target player position, completing");
            phase = Phase.COMPLETED;
            return;
        }
        
        log.trace("Right-clicking player at ({}, {})", canvasPoint.x, canvasPoint.y);
        
        // Create a small hitbox around the target point for right-click
        Rectangle clickBox = new Rectangle(canvasPoint.x - 5, canvasPoint.y - 5, 10, 10);
        
        pendingOperation = ctx.getMouseController().rightClick(clickBox)
            .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(200, 400)))
            .thenRun(() -> {
                hoverStartTime = System.currentTimeMillis();
                phase = Phase.HOVERING_EXAMINE;
            });
    }

    private void hoverExamine(TaskContext ctx) {
        // The right-click menu should now be open
        // We want to hover over "Examine" but not click it (simulating curiosity)
        
        long elapsed = System.currentTimeMillis() - hoverStartTime;
        
        if (elapsed >= hoverDuration) {
            phase = Phase.DISMISSING;
            return;
        }
        
        // Try to find and hover over the "Examine" menu option
        // Menu options are typically displayed in a widget after right-click
        // For simplicity, we'll just wait for the hover duration
        // The mouse is already over the menu area from the right-click
        
        // Small random mouse drift while "reading" the menu
        if (randomization.chance(0.3)) { // 30% chance to drift slightly
            int driftX = randomization.uniformRandomInt(-10, 10);
            int driftY = randomization.uniformRandomInt(-5, 5);
            
            java.awt.Point currentPos = ctx.getMouseController().getCurrentPosition();
            if (currentPos != null) {
                int newX = currentPos.x + driftX;
                int newY = currentPos.y + driftY;
                
                long remainingDuration = hoverDuration - elapsed;
                pendingOperation = ctx.getMouseController().moveToCanvas(newX, newY)
                    .thenCompose(v -> ctx.getHumanTimer().sleep(Math.min(remainingDuration, 500)))
                    .thenRun(() -> {
                        // Check if we should continue hovering or dismiss
                        if (System.currentTimeMillis() - hoverStartTime >= hoverDuration) {
                            phase = Phase.DISMISSING;
                        }
                    });
            }
        } else {
            // Just wait
            long remainingDuration = hoverDuration - elapsed;
            pendingOperation = ctx.getHumanTimer().sleep(Math.min(remainingDuration, 500));
        }
    }

    private void dismissMenu(TaskContext ctx) {
        // Dismiss the menu by clicking elsewhere (left click away)
        java.awt.Point currentPos = ctx.getMouseController().getCurrentPosition();
        
        if (currentPos != null) {
            // Click somewhere nearby to dismiss
            int dismissX = currentPos.x + randomization.uniformRandomInt(-30, 30);
            int dismissY = currentPos.y + randomization.uniformRandomInt(30, 60); // Move down a bit
            
            // Ensure we're within reasonable screen bounds
            dismissX = Math.max(50, Math.min(dismissX, 700));
            dismissY = Math.max(50, Math.min(dismissY, 450));
            
            log.trace("Dismissing menu by clicking at ({}, {})", dismissX, dismissY);
            
            pendingOperation = ctx.getMouseController().moveToCanvas(dismissX, dismissY)
                .thenCompose(v -> ctx.getMouseController().click())
                .thenCompose(v -> ctx.getHumanTimer().sleep(randomization.uniformRandomLong(100, 300)))
                .thenRun(() -> {
                    log.debug("Player inspection completed: {} (lvl {})", 
                             selectedTarget.getName(), selectedTarget.getCombatLevel());
                    phase = Phase.COMPLETED;
                });
        } else {
            // Just complete without dismissing
            phase = Phase.COMPLETED;
        }
    }

    /**
     * Get the canvas point for a player based on their world position.
     */
    private java.awt.Point getPlayerCanvasPoint(TaskContext ctx, PlayerSnapshot player) {
        if (player == null || player.getWorldPosition() == null) {
            return null;
        }
        
        Client client = ctx.getClient();
        WorldPoint worldPoint = player.getWorldPosition();
        
        // Convert world point to local point
        LocalPoint localPoint = LocalPoint.fromWorld(client, worldPoint);
        if (localPoint == null) {
            return null;
        }
        
        // Get canvas point - use a height offset to target the player model
        Point canvasPoint = Perspective.localToCanvas(client, localPoint, client.getPlane(), 100);
        if (canvasPoint == null) {
            return null;
        }
        
        // Add some randomization to avoid clicking exactly the same spot
        int x = canvasPoint.getX() + randomization.uniformRandomInt(-5, 5);
        int y = canvasPoint.getY() + randomization.uniformRandomInt(-10, 5);
        
        return new java.awt.Point(x, y);
    }
}
