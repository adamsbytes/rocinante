package com.rocinante.tasks.impl.skills.slayer;

import com.rocinante.navigation.NavigationPath;
import com.rocinante.navigation.WebWalker;
import com.rocinante.progression.UnlockTracker;
import com.rocinante.state.IronmanState;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves the optimal slayer location for a given creature and player context.
 *
 * Per REQUIREMENTS.md Section 11.2, implements:
 * <ul>
 *   <li>Location filtering by Konar region restrictions</li>
 *   <li>HCIM safety filtering (excludes dangerous locations)</li>
 *   <li>Quest/skill requirement validation</li>
 *   <li>Location scoring by distance, bank proximity, teleport access</li>
 *   <li>Required items list generation</li>
 * </ul>
 *
 * Location selection algorithm:
 * <ol>
 *   <li>Load creature from SlayerDataLoader</li>
 *   <li>Filter by Konar region (if applicable)</li>
 *   <li>Filter by HCIM safety threshold</li>
 *   <li>Filter by quest/skill requirements</li>
 *   <li>Score remaining locations</li>
 *   <li>Return highest-scoring location with required items</li>
 * </ol>
 */
@Slf4j
@Singleton
public class TaskLocationResolver {

    /**
     * Default HCIM minimum safety rating.
     * 6+ avoids multi-combat and high-risk areas.
     */
    public static final int DEFAULT_HCIM_MIN_SAFETY = 6;

    /**
     * Minimum safety rating that allows multi-combat.
     * Use 4+ if HCIM is comfortable with multi-combat.
     */
    public static final int HCIM_ALLOW_MULTI_MIN_SAFETY = 4;

    private final SlayerDataLoader dataLoader;
    private final UnlockTracker unlockTracker;
    private final WebWalker webWalker;

    @Inject
    public TaskLocationResolver(
            SlayerDataLoader dataLoader,
            UnlockTracker unlockTracker,
            WebWalker webWalker) {
        this.dataLoader = dataLoader;
        this.unlockTracker = unlockTracker;
        this.webWalker = webWalker;
    }

    /**
     * Resolve the optimal location for a slayer task.
     *
     * @param taskName    the creature/task name
     * @param konarRegion Konar location restriction, or null for non-Konar
     * @param ironmanState player's ironman state for safety checks
     * @return resolution result with location and requirements
     */
    public LocationResolution resolveLocation(
            String taskName,
            @Nullable String konarRegion,
            @Nullable IronmanState ironmanState) {
        
        return resolveLocation(taskName, konarRegion, ironmanState, null, ResolverOptions.DEFAULT);
    }

    /**
     * Resolve the optimal location with custom options.
     *
     * @param taskName      the creature/task name
     * @param konarRegion   Konar location restriction, or null
     * @param ironmanState  player's ironman state
     * @param playerLocation current player location for distance scoring
     * @param options       resolver options
     * @return resolution result
     */
    public LocationResolution resolveLocation(
            String taskName,
            @Nullable String konarRegion,
            @Nullable IronmanState ironmanState,
            @Nullable WorldPoint playerLocation,
            ResolverOptions options) {

        log.debug("Resolving location for task: {}, konarRegion: {}, options: {}",
                taskName, konarRegion, options);

        // Load creature data
        SlayerCreature creature = dataLoader.getCreature(taskName);
        if (creature == null) {
            log.warn("No creature data found for task: {}", taskName);
            return LocationResolution.failure("Unknown creature: " + taskName);
        }

        List<SlayerLocation> locations = new ArrayList<>(creature.getLocations());
        if (locations.isEmpty()) {
            log.warn("No locations defined for creature: {}", taskName);
            return LocationResolution.failure("No locations available for: " + taskName);
        }

        log.debug("Found {} locations for {}", locations.size(), taskName);

        // Step 1: Filter by Konar region
        if (konarRegion != null && !konarRegion.isEmpty()) {
            locations = filterByKonarRegion(locations, konarRegion);
            if (locations.isEmpty()) {
                return LocationResolution.failure(
                        "No locations match Konar region: " + konarRegion);
            }
            log.debug("After Konar filter: {} locations", locations.size());
        }

        // Step 2: Filter by HCIM safety
        if (shouldApplyHcimFilter(ironmanState, options)) {
            int minSafety = options.getMinHcimSafety();
            boolean allowMulti = options.isAllowMultiCombat();
            locations = filterByHcimSafety(locations, minSafety, allowMulti);
            if (locations.isEmpty()) {
                return LocationResolution.failure(
                        "No safe locations for HCIM (min safety: " + minSafety + ")");
            }
            log.debug("After HCIM safety filter: {} locations", locations.size());
        }

        // Step 3: Filter by quest requirements
        locations = filterByQuestRequirements(locations);
        if (locations.isEmpty()) {
            return LocationResolution.failure("No locations accessible (missing quest)");
        }
        log.debug("After quest filter: {} locations", locations.size());

        // Step 4: Filter by slayer level requirements
        int slayerLevel = unlockTracker.getSkillLevel(net.runelite.api.Skill.SLAYER);
        locations = filterBySlayerLevel(locations, slayerLevel);
        if (locations.isEmpty()) {
            return LocationResolution.failure("No locations accessible (slayer level too low)");
        }
        log.debug("After slayer level filter: {} locations", locations.size());

        // Step 5: Score and select best location
        SlayerLocation bestLocation = selectBestLocation(locations, playerLocation, options);

        // Step 6: Gather required items
        Set<Integer> requiredItems = gatherRequiredItems(creature, bestLocation);
        Set<Integer> recommendedItems = gatherRecommendedItems(creature, bestLocation);

        log.info("Resolved location for {}: {} (safety={}, multi={})",
                taskName, bestLocation.getName(), 
                bestLocation.getHcimSafetyRating(), bestLocation.isMultiCombat());

        return LocationResolution.success(creature, bestLocation, requiredItems, recommendedItems);
    }

    // ========================================================================
    // Filtering Methods
    // ========================================================================

    private List<SlayerLocation> filterByKonarRegion(
            List<SlayerLocation> locations, String konarRegion) {
        return locations.stream()
                .filter(loc -> loc.matchesKonarRegion(konarRegion))
                .collect(Collectors.toList());
    }

    private List<SlayerLocation> filterByHcimSafety(
            List<SlayerLocation> locations, int minSafety, boolean allowMulti) {
        return locations.stream()
                .filter(loc -> loc.getHcimSafetyRating() >= minSafety)
                .filter(loc -> allowMulti || !loc.isMultiCombat())
                .collect(Collectors.toList());
    }

    private List<SlayerLocation> filterByQuestRequirements(List<SlayerLocation> locations) {
        return locations.stream()
                .filter(this::meetsQuestRequirement)
                .collect(Collectors.toList());
    }

    private List<SlayerLocation> filterBySlayerLevel(
            List<SlayerLocation> locations, int playerSlayerLevel) {
        return locations.stream()
                .filter(loc -> loc.getSlayerLevelRequired() <= playerSlayerLevel)
                .collect(Collectors.toList());
    }

    private boolean meetsQuestRequirement(SlayerLocation location) {
        if (!location.requiresQuest()) {
            return true;
        }
        
        String questName = location.getRequiredQuest();
        try {
            // Try to match quest by name
            Quest quest = findQuestByName(questName);
            if (quest != null) {
                return unlockTracker.isQuestCompleted(quest);
            }
            // Unknown quest - assume accessible (conservative)
            log.debug("Unknown quest requirement: {}", questName);
            return true;
        } catch (Exception e) {
            log.warn("Error checking quest requirement {}: {}", questName, e.getMessage());
            return false;
        }
    }

    @Nullable
    private Quest findQuestByName(String name) {
        if (name == null) {
            return null;
        }
        // Normalize name for matching
        String normalized = name.toUpperCase()
                .replace(" ", "_")
                .replace("'", "")
                .replace("-", "_");
        
        try {
            return Quest.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            // Try fuzzy matching
            for (Quest quest : Quest.values()) {
                if (quest.getName().equalsIgnoreCase(name)) {
                    return quest;
                }
            }
            return null;
        }
    }

    private boolean shouldApplyHcimFilter(
            IronmanState ironmanState, ResolverOptions options) {
        if (!options.isApplyHcimSafety()) {
            return false;
        }
        return ironmanState.isHardcore();
    }

    // ========================================================================
    // Location Scoring
    // ========================================================================

    private SlayerLocation selectBestLocation(
            List<SlayerLocation> locations,
            @Nullable WorldPoint playerLocation,
            ResolverOptions options) {

        if (locations.size() == 1) {
            return locations.get(0);
        }

        SlayerLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (SlayerLocation location : locations) {
            int score = scoreLocation(location, playerLocation, options);
            log.debug("Location {} score: {}", location.getName(), score);
            
            if (score > bestScore) {
                bestScore = score;
                best = location;
            }
        }

        return best != null ? best : locations.get(0);
    }

    /**
     * Score a location based on multiple factors.
     * Higher score = better location.
     */
    private int scoreLocation(
            SlayerLocation location,
            @Nullable WorldPoint playerLocation,
            ResolverOptions options) {
        
        int score = 0;

        // Safety rating (0-10) * 10 = 0-100 points
        score += location.getHcimSafetyRating() * 10;

        // Monster density (0-20) * 3 = 0-60 points
        score += location.getMonsterDensity() * 3;

        // Non-multi combat bonus (safer, no pile-ups)
        if (!location.isMultiCombat()) {
            score += 30;
        }

        // Bank proximity bonus (if teleport exists)
        if (location.getNearestBank() != null && !location.getNearestBank().isEmpty()) {
            score += 20;
        }

        // Teleport proximity bonus
        if (location.getNearestTeleport() != null && !location.getNearestTeleport().isEmpty()) {
            score += 25;
        }

        // Distance penalty (if player location known)
        if (playerLocation != null && options.isConsiderDistance()) {
            int distance = estimateDistance(playerLocation, location.getCenter());
            
            // Unreachable locations get massive penalty
            if (distance == Integer.MAX_VALUE) {
                score -= 1000;
                log.debug("Location {} is unreachable - applying max penalty", location.getName());
            } else {
                // Penalty: -1 point per 10 ticks travel time, max -50
                score -= Math.min(50, distance / 10);
            }
        }

        // Wilderness penalty (significant risk)
        if (location.isWilderness()) {
            score -= 100;
        }

        // No required items bonus
        if (!location.hasRequiredItems()) {
            score += 10;
        }

        return score;
    }

    /**
     * Estimate walking distance between two points using WebWalker pathfinding.
     * 
     * <p>This provides accurate distance calculations that account for:
     * <ul>
     *   <li>Obstacles and blocked paths</li>
     *   <li>Shortcuts (agility, etc.)</li>
     *   <li>Multi-plane traversal (stairs, ladders)</li>
     * </ul>
     *
     * @param from starting point
     * @param to destination point
     * @return estimated distance in game ticks, or Integer.MAX_VALUE if unreachable
     */
    private int estimateDistance(WorldPoint from, WorldPoint to) {
        if (from == null || to == null) {
            return Integer.MAX_VALUE;
        }

        // Use WebWalker for accurate pathfinding distance
        NavigationPath path = webWalker.findUnifiedPath(from, to);
        
        if (path.isEmpty()) {
            log.debug("No path found from {} to {} - marking as unreachable", from, to);
            return Integer.MAX_VALUE;
        }

        // Return total cost in ticks as distance estimate
        int cost = path.getTotalCostTicks();
        log.debug("Path distance from {} to {}: {} ticks ({} edges)", 
                from, to, cost, path.size());
        
        return cost;
    }

    // ========================================================================
    // Required Items
    // ========================================================================

    private Set<Integer> gatherRequiredItems(SlayerCreature creature, SlayerLocation location) {
        Set<Integer> items = new HashSet<>();

        // Creature equipment requirements
        items.addAll(creature.getRequiredEquipmentIds());

        // Creature finish-off items
        items.addAll(creature.getFinishOffItemIds());

        // Location-specific items (rope, light source, etc.)
        items.addAll(location.getRequiredItems());

        return items;
    }

    private Set<Integer> gatherRecommendedItems(SlayerCreature creature, SlayerLocation location) {
        Set<Integer> items = new HashSet<>();

        // Location recommended items
        items.addAll(location.getRecommendedItems());

        // Add antipoison if creature can poison
        if (creature.isCanPoison()) {
            items.add(ItemID.ANTIPOISON4);
        }

        // Add antivenom if creature can venom
        if (creature.isCanVenom()) {
            items.add(ItemID.ANTIVENOM4_12913);
        }

        return items;
    }

    // ========================================================================
    // Resolution Result
    // ========================================================================

    /**
     * Result of location resolution.
     */
    @Value
    @Builder
    public static class LocationResolution {
        boolean success;
        @Nullable String failureReason;
        @Nullable SlayerCreature creature;
        @Nullable SlayerLocation location;
        Set<Integer> requiredItems;
        Set<Integer> recommendedItems;

        public static LocationResolution success(
                SlayerCreature creature,
                SlayerLocation location,
                Set<Integer> requiredItems,
                Set<Integer> recommendedItems) {
            return LocationResolution.builder()
                    .success(true)
                    .creature(creature)
                    .location(location)
                    .requiredItems(requiredItems != null ? requiredItems : Collections.emptySet())
                    .recommendedItems(recommendedItems != null ? recommendedItems : Collections.emptySet())
                    .build();
        }

        public static LocationResolution failure(String reason) {
            return LocationResolution.builder()
                    .success(false)
                    .failureReason(reason)
                    .requiredItems(Collections.emptySet())
                    .recommendedItems(Collections.emptySet())
                    .build();
        }
    }

    /**
     * Options for location resolution.
     */
    @Value
    @Builder
    public static class ResolverOptions {
        public static final ResolverOptions DEFAULT = ResolverOptions.builder()
                .applyHcimSafety(true)
                .minHcimSafety(DEFAULT_HCIM_MIN_SAFETY)
                .allowMultiCombat(false)
                .considerDistance(true)
                .preferHighDensity(true)
                .build();

        public static final ResolverOptions AGGRESSIVE = ResolverOptions.builder()
                .applyHcimSafety(true)
                .minHcimSafety(HCIM_ALLOW_MULTI_MIN_SAFETY)
                .allowMultiCombat(true)
                .considerDistance(true)
                .preferHighDensity(true)
                .build();

        public static final ResolverOptions IGNORE_SAFETY = ResolverOptions.builder()
                .applyHcimSafety(false)
                .minHcimSafety(1)
                .allowMultiCombat(true)
                .considerDistance(true)
                .preferHighDensity(true)
                .build();

        /**
         * Whether to apply HCIM safety filtering.
         */
        @Builder.Default
        boolean applyHcimSafety = true;

        /**
         * Minimum HCIM safety rating (1-10).
         */
        @Builder.Default
        int minHcimSafety = DEFAULT_HCIM_MIN_SAFETY;

        /**
         * Whether to allow multi-combat locations for HCIM.
         */
        @Builder.Default
        boolean allowMultiCombat = false;

        /**
         * Whether to factor in distance from current location.
         */
        @Builder.Default
        boolean considerDistance = true;

        /**
         * Whether to prefer high monster density locations.
         */
        @Builder.Default
        boolean preferHighDensity = true;
    }
}

