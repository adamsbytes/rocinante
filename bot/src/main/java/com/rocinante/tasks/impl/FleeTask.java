package com.rocinante.tasks.impl;

import com.rocinante.navigation.NavigationService;
import com.rocinante.state.AggressorInfo;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Task to flee from combat by running away from attackers.
 * 
 * Strategy:
 * 1. If we were walking somewhere (commuting), flee TOWARD that destination
 * 2. Otherwise, determine a safe direction (away from attacker and similar NPCs)
 * 3. Walk ~40 tiles in that direction
 * 4. Optionally return to the original task location (not trigger location)
 */
@Slf4j
public class FleeTask extends AbstractTask {

    private static final int FLEE_DISTANCE = 40;
    private static final int SCAN_RADIUS = 15;
    private static final int DIRECTION_SAMPLES = 8; // Check 8 compass directions
    
    /**
     * Distance from flee destination at which we consider ourselves "safe enough" to stop,
     * as long as we're no longer in combat.
     */
    private static final int SAFE_ARRIVAL_DISTANCE = 7;

    @Getter
    private final AggressorInfo attacker;

    @Getter
    private final WorldPoint returnLocation;

    @Getter
    private final boolean shouldReturn;

    /**
     * If we were walking to a destination when attacked, flee toward it.
     * This is smarter than fleeing randomly when commuting.
     */
    @Getter
    private final WorldPoint fleeTowardDestination;

    private FleePhase phase = FleePhase.CALCULATE_DIRECTION;
    private WorldPoint fleeDestination;
    private WalkToTask walkTask;

    /**
     * Create a flee task.
     *
     * @param attacker       the NPC attacking us
     * @param returnLocation where to return after fleeing (null = don't return)
     */
    public FleeTask(AggressorInfo attacker, WorldPoint returnLocation) {
        this(attacker, returnLocation, null);
    }

    /**
     * Create a flee task with a preferred flee direction.
     *
     * @param attacker              the NPC attacking us
     * @param returnLocation        where to return after fleeing (null = don't return)
     * @param fleeTowardDestination if we were walking somewhere, flee toward that destination
     */
    public FleeTask(AggressorInfo attacker, WorldPoint returnLocation, WorldPoint fleeTowardDestination) {
        this.attacker = attacker;
        this.returnLocation = returnLocation;
        this.shouldReturn = returnLocation != null;
        this.fleeTowardDestination = fleeTowardDestination;
    }

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void resetImpl() {
        phase = FleePhase.CALCULATE_DIRECTION;
        fleeDestination = null;
        walkTask = null;
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        switch (phase) {
            case CALCULATE_DIRECTION:
                executeCalculateDirection(ctx);
                break;
            case FLEEING:
                executeFleeing(ctx);
                break;
            case RETURNING:
                executeReturning(ctx);
                break;
        }
    }

    private void executeCalculateDirection(TaskContext ctx) {
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (playerPos == null) {
            fail("Invalid player position");
            return;
        }

        // If we were walking somewhere, flee TOWARD that destination (continue commute)
        if (fleeTowardDestination != null) {
            fleeDestination = calculateFleeTowardDestination(playerPos, fleeTowardDestination);
            log.info("Fleeing TOWARD original destination {} (was commuting when attacked)", 
                    fleeTowardDestination);
        } else {
            // Find the best direction to flee (away from attacker)
            fleeDestination = calculateFleeDestination(ctx, playerPos);

            if (fleeDestination == null) {
                log.warn("Could not calculate flee destination, using fallback");
                // Fallback: just run opposite to attacker
                fleeDestination = calculateFallbackDestination(ctx, playerPos);
            }
        }

        if (fleeDestination == null) {
            fail("Cannot determine flee direction");
            return;
        }

        log.info("Fleeing from {} to {} (~{} tiles)", 
                attacker.getNpcName(), fleeDestination, playerPos.distanceTo(fleeDestination));

        walkTask = new WalkToTask(fleeDestination);
        walkTask.setDescription("Flee from " + attacker.getNpcName());
        phase = FleePhase.FLEEING;
    }

    private void executeFleeing(TaskContext ctx) {
        if (walkTask == null) {
            fail("Walk task not initialized");
            return;
        }

        // Check if we've fled far enough AND are no longer in combat
        // This allows early completion once we're safe, rather than walking all 40 tiles
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        boolean closeEnoughToDestination = playerPos != null && fleeDestination != null 
                && playerPos.distanceTo(fleeDestination) <= SAFE_ARRIVAL_DISTANCE;
        boolean notInCombat = ctx.getCombatState() == null || !ctx.getCombatState().isBeingAttacked();
        
        if (closeEnoughToDestination && notInCombat) {
            log.info("Fled successfully - within {} tiles of destination and no longer in combat", 
                    SAFE_ARRIVAL_DISTANCE);
            handleFleeComplete();
            return;
        }

        // Execute the walk task
        walkTask.execute(ctx);

        if (walkTask.getState().isTerminal()) {
            if (walkTask.getState() == TaskState.COMPLETED) {
                log.info("Fled successfully to {}", fleeDestination);
                handleFleeComplete();
            } else {
                // Flee failed but we're probably safer now anyway
                log.warn("Flee walk failed, but continuing (may have partially escaped)");
                handleFleeComplete();
            }
        }
    }
    
    /**
     * Handle flee completion - either return to original location or complete.
     */
    private void handleFleeComplete() {
                if (shouldReturn && returnLocation != null) {
            log.info("Will return to task location: {}", returnLocation);
                    walkTask = new WalkToTask(returnLocation);
                    walkTask.setDescription("Return after fleeing");
                    phase = FleePhase.RETURNING;
                } else {
                    complete();
        }
    }

    private void executeReturning(TaskContext ctx) {
        if (walkTask == null) {
            complete();
            return;
        }

        walkTask.execute(ctx);

        if (walkTask.getState().isTerminal()) {
            if (walkTask.getState() == TaskState.COMPLETED) {
                log.info("Returned to task location: {}", returnLocation);
            } else {
                log.warn("Failed to return to task location, but fleeing was successful");
            }
            complete();
        }
    }

    /**
     * Calculate flee destination toward an existing destination.
     * Used when we were walking somewhere and got attacked - continue toward destination.
     */
    private WorldPoint calculateFleeTowardDestination(WorldPoint playerPos, WorldPoint destination) {
        int dx = destination.getX() - playerPos.getX();
        int dy = destination.getY() - playerPos.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (distance <= FLEE_DISTANCE) {
            // Destination is within flee distance - just go there
            return destination;
        }

        // Scale to flee distance in the direction of destination
        double scale = FLEE_DISTANCE / distance;
        int fleeX = playerPos.getX() + (int) (dx * scale);
        int fleeY = playerPos.getY() + (int) (dy * scale);

        return new WorldPoint(fleeX, fleeY, playerPos.getPlane());
    }

    /**
     * Calculate the best destination to flee to.
     * Uses actual path cost estimation to avoid expensive routes (toll gates, long detours).
     * Avoids directions with many NPCs of the same type as the attacker.
     */
    private WorldPoint calculateFleeDestination(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        NavigationService navService = ctx.getNavigationService();
        int attackerNpcId = attacker.getNpcId();

        // Find all nearby NPCs of the same type
        List<WorldPoint> dangerousNpcPositions = new ArrayList<>();
        for (NPC npc : client.getNpcs()) {
            if (npc == null || npc.isDead()) continue;
            if (npc.getId() == attackerNpcId) {
                WorldPoint npcPos = npc.getWorldLocation();
                if (npcPos != null && playerPos.distanceTo(npcPos) <= SCAN_RADIUS) {
                    dangerousNpcPositions.add(npcPos);
                }
            }
        }

        // Score each direction at multiple distances (8 compass directions x 3 distances)
        // This helps find good intermediate points if full distance is unreachable/expensive
        int[] distances = {FLEE_DISTANCE, 25, 15}; // Try full, medium, short
        
        double bestScore = Double.NEGATIVE_INFINITY;
        WorldPoint bestDestination = null;
        int bestPathCost = Integer.MAX_VALUE;

        for (int i = 0; i < DIRECTION_SAMPLES; i++) {
            double angle = (2 * Math.PI * i) / DIRECTION_SAMPLES;
            
            for (int distance : distances) {
                int dx = (int) (Math.cos(angle) * distance);
                int dy = (int) (Math.sin(angle) * distance);

            WorldPoint candidate = new WorldPoint(
                    playerPos.getX() + dx,
                    playerPos.getY() + dy,
                    playerPos.getPlane()
            );

                // Get actual path cost - skip if unreachable
                OptionalInt pathCostOpt = navService.getPathCost(ctx, playerPos, candidate);
                if (pathCostOpt.isEmpty()) {
                    continue; // Unreachable - skip this candidate
                }
                
                int pathCost = pathCostOpt.getAsInt();
                int straightLineDistance = playerPos.distanceTo(candidate);
                
                // Penalize paths that are much longer than straight-line distance
                // This catches toll gates, long detours, etc.
                // Ratio > 2.0 means path is more than twice the straight-line distance
                double pathEfficiency = (double) straightLineDistance / Math.max(1, pathCost);
                if (pathEfficiency < 0.5) {
                    log.debug("Skipping {} - path cost {} is too high for distance {} (efficiency: {})",
                            candidate, pathCost, straightLineDistance, String.format("%.2f", pathEfficiency));
                    continue; // Path is too inefficient (probably involves toll gate or major detour)
                }

                double score = scoreFleeDirection(playerPos, candidate, dangerousNpcPositions, pathCost);

                // Prefer this candidate if score is better, or same score but shorter path
                if (score > bestScore || (score == bestScore && pathCost < bestPathCost)) {
                bestScore = score;
                bestDestination = candidate;
                    bestPathCost = pathCost;
                }
            }
        }

        if (bestDestination != null) {
            log.debug("Best flee destination: {} (score: {}, path cost: {})", 
                    bestDestination, String.format("%.1f", bestScore), bestPathCost);
        }

        return bestDestination;
    }

    /**
     * Score a flee direction based on path cost and distance from dangerous NPCs.
     * Higher score = safer direction.
     * 
     * @param from starting position
     * @param to candidate destination
     * @param dangerousNpcs positions of dangerous NPCs
     * @param pathCost actual path cost in tiles
     * @return score (higher is better)
     */
    private double scoreFleeDirection(WorldPoint from, WorldPoint to, 
                                       List<WorldPoint> dangerousNpcs, int pathCost) {
        double score = 0;

        // Penalize path cost - we want efficient routes
        // Lower path cost = better (so subtract it)
        score -= pathCost * 0.3;

        // Score based on distance from dangerous NPCs at destination
        for (WorldPoint npcPos : dangerousNpcs) {
            int distFromDest = to.distanceTo(npcPos);
            
            // Reward being far from NPCs at destination (this is actual safety distance)
            score += distFromDest * 0.5;
            
            // Extra penalty if NPCs are very close to destination
            if (distFromDest < 10) {
                score -= (10 - distFromDest) * 3;
            }

            // Bonus for moving away from NPCs (destination further than start)
            int distFromStart = from.distanceTo(npcPos);
            if (distFromDest > distFromStart) {
                score += 5; // Moving away from this NPC
            } else {
                score -= 8; // Moving toward this NPC - bad
            }
        }

        return score;
    }

    /**
     * Fallback: just run in the opposite direction of the attacker.
     */
    private WorldPoint calculateFallbackDestination(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();

        // Find the attacker NPC's current position
        WorldPoint attackerPos = null;
        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getIndex() == attacker.getNpcIndex()) {
                attackerPos = npc.getWorldLocation();
                break;
            }
        }

        if (attackerPos == null) {
            // Attacker not found, pick a random direction
            double angle = Math.random() * 2 * Math.PI;
            int dx = (int) (Math.cos(angle) * FLEE_DISTANCE);
            int dy = (int) (Math.sin(angle) * FLEE_DISTANCE);
            return new WorldPoint(
                    playerPos.getX() + dx,
                    playerPos.getY() + dy,
                    playerPos.getPlane()
            );
        }

        // Calculate opposite direction
        int dx = playerPos.getX() - attackerPos.getX();
        int dy = playerPos.getY() - attackerPos.getY();

        // Normalize and scale to flee distance
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < 1) {
            distance = 1;
        }

        int fleeX = playerPos.getX() + (int) ((dx / distance) * FLEE_DISTANCE);
        int fleeY = playerPos.getY() + (int) ((dy / distance) * FLEE_DISTANCE);

        return new WorldPoint(fleeX, fleeY, playerPos.getPlane());
    }

    @Override
    public String getDescription() {
        return "Flee from " + (attacker != null ? attacker.getNpcName() : "attacker");
    }

    private enum FleePhase {
        CALCULATE_DIRECTION,
        FLEEING,
        RETURNING
    }
}
