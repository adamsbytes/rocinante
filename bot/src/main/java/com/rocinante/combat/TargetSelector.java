package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.navigation.Reachability;
import com.rocinante.navigation.ShortestPathBridge;
import com.rocinante.state.AggressorInfo;
import com.rocinante.state.NpcSnapshot;
import com.rocinante.state.PlayerSnapshot;
import com.rocinante.state.PlayerState;
import com.rocinante.state.WorldState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Intelligent target selection for combat.
 *
 * Per REQUIREMENTS.md Section 10.5, provides:
 * <ul>
 *   <li>Priority-based target selection (Section 10.5.1)</li>
 *   <li>Avoidance rules for invalid targets (Section 10.5.2)</li>
 *   <li>Integration with WorldState and PathFinder</li>
 * </ul>
 *
 * <p><b>Performance Note:</b> This class passes WorldState through the method chain
 * to avoid redundant getWorldState() calls. All filtering uses manual iteration
 * instead of streams to minimize allocations on the critical path.
 *
 * <p>Usage:
 * <pre>{@code
 * // Basic usage - find any valid target
 * Optional<NpcSnapshot> target = targetSelector.selectTarget();
 *
 * // Configure for specific NPCs
 * targetSelector.setConfig(TargetSelectorConfig.forNpcIds(2097, 2098)); // Cows
 * Optional<NpcSnapshot> cow = targetSelector.selectTarget();
 * }</pre>
 */
@Slf4j
@Singleton
public class TargetSelector {

    private final Client client;
    private final GameStateService gameStateService;
    private final ShortestPathBridge shortestPathBridge;
    private final Reachability reachability;

    @Getter
    @Setter
    private TargetSelectorConfig config = TargetSelectorConfig.DEFAULT;

    @Inject
    public TargetSelector(Client client, GameStateService gameStateService, ShortestPathBridge shortestPathBridge,
                          Reachability reachability) {
        this.client = client;
        this.gameStateService = gameStateService;
        this.shortestPathBridge = shortestPathBridge;
        this.reachability = reachability;
        log.info("TargetSelector initialized with reachability support");
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Select the best target according to current configuration.
     *
     * @return Optional containing the selected NPC, or empty if no valid target found
     */
    public Optional<NpcSnapshot> selectTarget() {
        WorldState worldState = gameStateService.getWorldState();
        PlayerState playerState = gameStateService.getPlayerState();

        if (!playerState.isValid()) {
            log.debug("TargetSelector: Player state invalid, cannot select target");
            return Optional.empty();
        }

        WorldPoint playerPos = playerState.getWorldPosition();

        // Get all nearby NPCs within search radius
        List<NpcSnapshot> candidates = worldState.getNpcsWithinDistance(playerPos, config.getSearchRadius());

        if (candidates.isEmpty()) {
            log.debug("TargetSelector: No NPCs within {} tiles", config.getSearchRadius());
            return Optional.empty();
        }

        // Apply avoidance filters (pass worldState through)
        List<NpcSnapshot> validTargets = filterCandidates(candidates, playerPos, worldState);

        if (validTargets.isEmpty()) {
            log.debug("TargetSelector: All {} candidates filtered out by avoidance rules", candidates.size());
            return Optional.empty();
        }

        // Select based on priority order
        return selectByPriority(validTargets, playerPos);
    }

    /**
     * Select a target with a custom config (does not change the stored config).
     *
     * @param customConfig the configuration to use for this selection
     * @return Optional containing the selected NPC, or empty if no valid target found
     */
    public Optional<NpcSnapshot> selectTarget(TargetSelectorConfig customConfig) {
        TargetSelectorConfig originalConfig = this.config;
        try {
            this.config = customConfig;
            return selectTarget();
        } finally {
            this.config = originalConfig;
        }
    }

    /**
     * Get all valid targets (after filtering) sorted by current priority.
     * Useful for debugging or displaying target options.
     *
     * @return list of valid targets in priority order
     */
    public List<NpcSnapshot> getValidTargets() {
        WorldState worldState = gameStateService.getWorldState();
        PlayerState playerState = gameStateService.getPlayerState();

        if (!playerState.isValid()) {
            log.debug("TargetSelector: player state invalid, returning empty target list");
            return Collections.emptyList();
        }

        WorldPoint playerPos = playerState.getWorldPosition();
        List<NpcSnapshot> candidates = worldState.getNpcsWithinDistance(playerPos, config.getSearchRadius());
        List<NpcSnapshot> validTargets = filterCandidates(candidates, playerPos, worldState);

        // Sort by first applicable priority
        if (!validTargets.isEmpty() && !config.getPriorities().isEmpty()) {
            sortByPriority(validTargets, playerPos, config.getPriorities().get(0));
        }

        return validTargets;
    }

    /**
     * Check if a specific NPC would be a valid target.
     *
     * @param npc       the NPC to check
     * @param playerPos the player's position
     * @return true if the NPC passes all avoidance filters
     */
    public boolean isValidTarget(NpcSnapshot npc, WorldPoint playerPos) {
        WorldState worldState = gameStateService.getWorldState();
        return passesAvoidanceFilters(npc, playerPos, worldState);
    }

    // ========================================================================
    // Filtering (Section 10.5.2)
    // ========================================================================

    /**
     * Filter candidates through all avoidance rules.
     * Uses manual iteration instead of streams to minimize allocations.
     */
    private List<NpcSnapshot> filterCandidates(List<NpcSnapshot> candidates, WorldPoint playerPos, WorldState worldState) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<NpcSnapshot> result = new ArrayList<>(candidates.size());
        for (NpcSnapshot npc : candidates) {
            if (passesAvoidanceFilters(npc, playerPos, worldState)) {
                result.add(npc);
            }
        }
        return result;
    }

    /**
     * Check if an NPC passes all avoidance filters.
     * Note: NPC ID/name filtering is handled separately to allow self-defense against other NPCs.
     */
    private boolean passesAvoidanceFilters(NpcSnapshot npc, WorldPoint playerPos, WorldState worldState) {
        // Skip dead NPCs
        if (config.isSkipDead() && npc.isDead()) {
            log.debug("Skipping {} - dead", npc.getName());
            return false;
        }

        // Skip NPCs above max combat level
        if (config.hasMaxCombatLevel() && !config.isCombatLevelAcceptable(npc.getCombatLevel())) {
            log.debug("Skipping {} - combat level {} > max {}", 
                    npc.getName(), npc.getCombatLevel(), config.getMaxCombatLevel());
            return false;
        }

        // Skip NPCs in combat with other players (pass worldState through)
        if (config.isSkipInCombatWithOthers() && isInCombatWithOtherPlayer(npc, worldState)) {
            log.debug("Skipping {} - in combat with another player", npc.getName());
            return false;
        }

        // Skip unreachable NPCs
        if (config.isSkipUnreachable() && !isReachable(npc, playerPos)) {
            log.debug("Skipping {} - unreachable", npc.getName());
            return false;
        }

        return true;
    }

    /**
     * Check if an NPC matches the configured target ID/name filters.
     */
    private boolean matchesTargetFilter(NpcSnapshot npc) {
        // If no target filter configured, all NPCs match
        if (!config.hasTargetNpcIds() && !config.hasTargetNpcNames()) {
            return true;
        }

        // Check ID match
        if (config.hasTargetNpcIds() && config.isTargetNpcId(npc.getId())) {
            return true;
        }

        // Check name match
        if (config.hasTargetNpcNames() && config.isTargetNpcName(npc.getName())) {
            return true;
        }

        return false;
    }

    /**
     * Check if an NPC is in combat with another player (not the local player).
     * Per Section 10.5.2: Skip NPCs in combat with other players.
     */
    private boolean isInCombatWithOtherPlayer(NpcSnapshot npc, WorldState worldState) {
        int interactingIndex = npc.getInteractingIndex();
        
        // Not interacting with anyone
        if (interactingIndex == -1) {
            return false;
        }

        // If targeting the local player, that's fine (reactive targeting)
        if (npc.isTargetingPlayer()) {
            return false;
        }

        // Check if interacting with another player
        // In RuneLite, player indices are negative (offset by 32768)
        // Positive values are NPC indices
        // A negative interactingIndex (other than -1) indicates a player
        if (interactingIndex < -1) {
            // This is another player (not us, since isTargetingPlayer() was false)
            return true;
        }

        // Could also check if another player has this NPC targeted
        // by checking all nearby players' target indices (pass worldState)
        return isTargetedByOtherPlayer(npc, worldState);
    }

    /**
     * Check if any other player is targeting this NPC.
     * Uses manual iteration to avoid stream allocations.
     */
    private boolean isTargetedByOtherPlayer(NpcSnapshot npc, WorldState worldState) {
        int npcIndex = npc.getIndex();
        for (PlayerSnapshot player : worldState.getNearbyPlayers()) {
            if (player.isInCombat() && player.getInteractingIndex() == npcIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if an NPC is reachable considering weapon range.
     * Per Section 10.5.2: Skip NPCs that are unreachable.
     *
     * <p>For melee combat, the player must be able to reach an adjacent tile.
     * For ranged/magic combat, the player only needs line of sight within weapon range.
     */
    private boolean isReachable(NpcSnapshot npc, WorldPoint playerPos) {
        WorldPoint npcPos = npc.getWorldPosition();
        if (npcPos == null) {
            return false;
        }

        int weaponRange = config.getWeaponRange();
        int distance = playerPos.distanceTo(npcPos);

        // Melee combat (range = 1 or 2 for halberds)
        if (weaponRange <= 2) {
            // Adjacent - check if boundary is clear (not blocked by fence/river)
            if (distance <= 1) {
                return reachability.canInteract(playerPos, npcPos);
            }
            // Not adjacent - check if target isn't blocked
            return !shortestPathBridge.isBlocked(npcPos);
        }

        // Ranged/Magic combat - check if we're within range with line of sight
        if (distance <= weaponRange) {
            // Within weapon range - check line of sight
            if (reachability.hasLineOfSight(playerPos, npcPos)) {
                return true;
            }
            // No direct line of sight - try to find attackable position nearby
            Optional<WorldPoint> attackPos = reachability.findAttackablePosition(playerPos, npcPos, weaponRange);
            return attackPos.isPresent();
        }

        // Outside weapon range - check if target isn't blocked
        return !shortestPathBridge.isBlocked(npcPos);
    }

    // ========================================================================
    // Priority Selection (Section 10.5.1)
    // ========================================================================

    /**
     * Select the best target based on priority order.
     */
    private Optional<NpcSnapshot> selectByPriority(List<NpcSnapshot> validTargets, WorldPoint playerPos) {
        for (SelectionPriority priority : config.getPriorities()) {
            Optional<NpcSnapshot> result = selectByPriorityType(validTargets, playerPos, priority);
            if (result.isPresent()) {
                log.debug("TargetSelector: Selected {} using {} priority", 
                        result.get().getName(), priority);
                return result;
            }
        }

        // No target found by any priority - return first valid target as fallback
        if (!validTargets.isEmpty()) {
            NpcSnapshot fallback = validTargets.get(0);
            log.debug("TargetSelector: Using fallback target {}", fallback.getName());
            return Optional.of(fallback);
        }

        return Optional.empty();
    }

    /**
     * Select target using a specific priority type.
     * Uses manual iteration instead of streams to minimize allocations.
     */
    private Optional<NpcSnapshot> selectByPriorityType(List<NpcSnapshot> targets, 
                                                        WorldPoint playerPos, 
                                                        SelectionPriority priority) {
        switch (priority) {
            case TARGETING_PLAYER:
                return selectTargetingPlayer(targets, playerPos);

            case LOWEST_HP:
                return selectByHealth(targets, playerPos, true);

            case HIGHEST_HP:
                return selectByHealth(targets, playerPos, false);

            case NEAREST:
                return selectNearest(targets, playerPos);

            case SPECIFIC_ID:
                return selectBySpecificId(targets, playerPos);

            case SPECIFIC_NAME:
                return selectBySpecificName(targets, playerPos);

            default:
                log.warn("Unknown selection priority: {}", priority);
                return Optional.empty();
        }
    }

    /**
     * Select NPCs that are ACTUALLY ATTACKING us (not just interacting/talking).
     */
    private Optional<NpcSnapshot> selectTargetingPlayer(List<NpcSnapshot> targets, WorldPoint playerPos) {
        // Get NPCs that are ACTUALLY ATTACKING us (not just interacting/talking)
        // CombatState.aggressiveNpcs only contains NPCs that have attacked us
        var combatState = gameStateService.getCombatState();
        List<AggressorInfo> aggressors = combatState.getAggressiveNpcs();
        
        // Build set of actual attacker indices
        Set<Integer> actualAttackerIndices = new HashSet<>(aggressors.size());
        for (AggressorInfo a : aggressors) {
            actualAttackerIndices.add(a.getNpcIndex());
        }
        
        // Filter to NPCs that are both targeting us AND have actually attacked
        List<NpcSnapshot> attackingUs = new ArrayList<>();
        for (NpcSnapshot npc : targets) {
            if (npc.isTargetingPlayer() && actualAttackerIndices.contains(npc.getIndex())) {
                attackingUs.add(npc);
            }
        }
        
        if (attackingUs.isEmpty()) {
            return Optional.empty();
        }
        
        // PREFER NPCs that match our target filter (e.g., if hunting chickens and a chicken attacks)
        List<NpcSnapshot> matchingTargets = new ArrayList<>();
        for (NpcSnapshot npc : attackingUs) {
            if (matchesTargetFilter(npc)) {
                matchingTargets.add(npc);
            }
        }
        
        if (!matchingTargets.isEmpty()) {
            // Fight the matching target that's attacking us
            sortByDistance(matchingTargets, playerPos);
            log.debug("TARGETING_PLAYER: Found {} matching target(s) attacking us", matchingTargets.size());
            return Optional.of(matchingTargets.get(0));
        }
        
        // SELF-DEFENSE: No matching targets attacking, but something else is
        // Fight back against whatever is attacking us
        sortByDistance(attackingUs, playerPos);
        log.debug("TARGETING_PLAYER: Self-defense against {} (not a configured target)", 
                attackingUs.get(0).getName());
        return Optional.of(attackingUs.get(0));
    }

    /**
     * Select by health (lowest or highest).
     */
    private Optional<NpcSnapshot> selectByHealth(List<NpcSnapshot> targets, WorldPoint playerPos, boolean lowest) {
        // Filter to configured targets with visible health bars
        List<NpcSnapshot> filtered = new ArrayList<>();
        for (NpcSnapshot npc : targets) {
            if (matchesTargetFilter(npc) && npc.isHealthBarVisible()) {
                filtered.add(npc);
            }
        }
        
        if (filtered.isEmpty()) {
            if (!lowest) {
                // For highest HP, also include full-health NPCs (no health bar)
                List<NpcSnapshot> matchingFull = new ArrayList<>();
                for (NpcSnapshot npc : targets) {
                    if (matchesTargetFilter(npc)) {
                        matchingFull.add(npc);
                    }
                }
                if (!matchingFull.isEmpty()) {
                    sortByDistance(matchingFull, playerPos);
                    return Optional.of(matchingFull.get(0));
                }
            }
            return Optional.empty();
        }
        
        if (lowest) {
            filtered.sort(Comparator.comparingDouble(NpcSnapshot::getHealthPercent));
        } else {
            filtered.sort(Comparator.comparingDouble(NpcSnapshot::getHealthPercent).reversed());
        }
        return Optional.of(filtered.get(0));
    }

    /**
     * Select nearest matching target.
     */
    private Optional<NpcSnapshot> selectNearest(List<NpcSnapshot> targets, WorldPoint playerPos) {
        List<NpcSnapshot> filtered = new ArrayList<>();
        for (NpcSnapshot npc : targets) {
            if (matchesTargetFilter(npc)) {
                filtered.add(npc);
            }
        }
        if (filtered.isEmpty()) {
            return Optional.empty();
        }
        sortByDistance(filtered, playerPos);
        return Optional.of(filtered.get(0));
    }

    /**
     * Select by specific NPC ID.
     */
    private Optional<NpcSnapshot> selectBySpecificId(List<NpcSnapshot> targets, WorldPoint playerPos) {
        if (!config.hasTargetNpcIds()) {
            return Optional.empty();
        }
        List<NpcSnapshot> filtered = new ArrayList<>();
        for (NpcSnapshot npc : targets) {
            if (config.isTargetNpcId(npc.getId())) {
                filtered.add(npc);
            }
        }
        if (filtered.isEmpty()) {
            return Optional.empty();
        }
        sortByDistance(filtered, playerPos);
        return Optional.of(filtered.get(0));
    }

    /**
     * Select by specific NPC name.
     */
    private Optional<NpcSnapshot> selectBySpecificName(List<NpcSnapshot> targets, WorldPoint playerPos) {
        if (!config.hasTargetNpcNames()) {
            return Optional.empty();
        }
        List<NpcSnapshot> filtered = new ArrayList<>();
        for (NpcSnapshot npc : targets) {
            if (config.isTargetNpcName(npc.getName())) {
                filtered.add(npc);
            }
        }
        if (filtered.isEmpty()) {
            return Optional.empty();
        }
        sortByDistance(filtered, playerPos);
        return Optional.of(filtered.get(0));
    }

    /**
     * Sort NPCs by distance to player (ascending).
     */
    private void sortByDistance(List<NpcSnapshot> npcs, WorldPoint playerPos) {
        npcs.sort(Comparator.comparingInt(npc -> npc.distanceTo(playerPos)));
    }

    /**
     * Sort NPCs by a specific priority (for getValidTargets()).
     */
    private void sortByPriority(List<NpcSnapshot> npcs, WorldPoint playerPos, SelectionPriority priority) {
        switch (priority) {
            case TARGETING_PLAYER:
                // Targeting player first, then by distance
                npcs.sort((a, b) -> {
                    if (a.isTargetingPlayer() != b.isTargetingPlayer()) {
                        return a.isTargetingPlayer() ? -1 : 1;
                    }
                    return Integer.compare(a.distanceTo(playerPos), b.distanceTo(playerPos));
                });
                break;

            case LOWEST_HP:
                npcs.sort(Comparator.comparingDouble(NpcSnapshot::getHealthPercent));
                break;

            case HIGHEST_HP:
                npcs.sort(Comparator.comparingDouble(NpcSnapshot::getHealthPercent).reversed());
                break;

            case NEAREST:
            case SPECIFIC_ID:
            case SPECIFIC_NAME:
            default:
                sortByDistance(npcs, playerPos);
                break;
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get summary for logging.
     */
    public String getSummary() {
        return String.format("TargetSelector[config=%s]", config.getSummary());
    }
}
