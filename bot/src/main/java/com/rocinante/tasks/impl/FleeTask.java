package com.rocinante.tasks.impl;

import com.rocinante.state.AggressorInfo;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

        // Execute the walk task
        walkTask.execute(ctx);

        if (walkTask.getState().isTerminal()) {
            if (walkTask.getState() == TaskState.COMPLETED) {
                log.info("Fled successfully to {}", fleeDestination);

                if (shouldReturn && returnLocation != null) {
                    log.info("Will return to task location: {}", returnLocation);
                    walkTask = new WalkToTask(returnLocation);
                    walkTask.setDescription("Return after fleeing");
                    phase = FleePhase.RETURNING;
                } else {
                    complete();
                }
            } else {
                // Flee failed but we're probably safer now anyway
                log.warn("Flee walk failed, but continuing (may have partially escaped)");
                if (shouldReturn && returnLocation != null) {
                    walkTask = new WalkToTask(returnLocation);
                    walkTask.setDescription("Return after fleeing");
                    phase = FleePhase.RETURNING;
                } else {
                    complete();
                }
            }
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
     * Avoids directions with many NPCs of the same type as the attacker.
     */
    private WorldPoint calculateFleeDestination(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
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

        // Score each direction (8 compass directions)
        double bestScore = Double.NEGATIVE_INFINITY;
        WorldPoint bestDestination = null;

        for (int i = 0; i < DIRECTION_SAMPLES; i++) {
            double angle = (2 * Math.PI * i) / DIRECTION_SAMPLES;
            int dx = (int) (Math.cos(angle) * FLEE_DISTANCE);
            int dy = (int) (Math.sin(angle) * FLEE_DISTANCE);

            WorldPoint candidate = new WorldPoint(
                    playerPos.getX() + dx,
                    playerPos.getY() + dy,
                    playerPos.getPlane()
            );

            double score = scoreFleeDirection(playerPos, candidate, dangerousNpcPositions);

            if (score > bestScore) {
                bestScore = score;
                bestDestination = candidate;
            }
        }

        return bestDestination;
    }

    /**
     * Score a flee direction based on distance from dangerous NPCs.
     * Higher score = safer direction.
     */
    private double scoreFleeDirection(WorldPoint from, WorldPoint to, List<WorldPoint> dangerousNpcs) {
        double score = 0;

        // Base score: distance from starting position (we want to flee far)
        score += from.distanceTo(to) * 0.5;

        // Penalty for each dangerous NPC near the destination
        for (WorldPoint npcPos : dangerousNpcs) {
            int distFromDest = to.distanceTo(npcPos);
            if (distFromDest < 10) {
                // Heavy penalty for NPCs near destination
                score -= (10 - distFromDest) * 5;
            }

            // Bonus for moving away from NPCs
            int distFromStart = from.distanceTo(npcPos);
            if (distFromDest > distFromStart) {
                score += 5; // Moving away from this NPC
            } else {
                score -= 10; // Moving toward this NPC
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
