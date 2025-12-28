package com.rocinante.navigation;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.AgilityShortcut;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Wraps RuneLite's {@link AgilityShortcut} enum to provide obstacle definitions
 * for all agility shortcuts in OSRS.
 *
 * <p>This class:
 * <ul>
 *   <li>Converts all AgilityShortcut entries to ObstacleDefinition instances</li>
 *   <li>Provides lookup methods by object ID and location</li>
 *   <li>Calculates dynamic failure rates based on player's agility level</li>
 *   <li>Supports special condition checking for quest-locked shortcuts</li>
 * </ul>
 *
 * <p>RuneLite's AgilityShortcut enum contains 150+ shortcuts with:
 * <ul>
 *   <li>Required agility levels</li>
 *   <li>Object IDs for interaction</li>
 *   <li>World locations</li>
 *   <li>Descriptions</li>
 * </ul>
 */
@Slf4j
@Singleton
public class AgilityShortcutData {

    /**
     * Default base success rate for shortcuts at their required level.
     * Most shortcuts have approximately 90% success at the required level.
     */
    private static final double DEFAULT_BASE_SUCCESS_RATE = 0.90;

    /**
     * Base success rate for "safe" shortcuts (level 1 or no failure possible).
     */
    private static final double SAFE_SUCCESS_RATE = 1.0;

    /**
     * Risk threshold below which shortcuts are considered risky.
     * Default: 10% failure rate is the threshold.
     */
    private static final double DEFAULT_RISK_THRESHOLD = 0.10;

    private final Client client;

    /**
     * Map of object ID to AgilityShortcut entries.
     * Multiple shortcuts may share object IDs.
     */
    private final Map<Integer, List<AgilityShortcut>> shortcutsByObjectId;

    /**
     * Map of object ID to ObstacleDefinition.
     */
    private final Map<Integer, ObstacleDefinition> definitionsByObjectId;

    /**
     * All obstacle definitions created from AgilityShortcut.
     */
    private final List<ObstacleDefinition> allDefinitions;

    @Inject
    public AgilityShortcutData(Client client) {
        this.client = client;
        this.shortcutsByObjectId = new HashMap<>();
        this.definitionsByObjectId = new HashMap<>();
        this.allDefinitions = new ArrayList<>();

        initializeShortcuts();
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Initialize all shortcuts from RuneLite's AgilityShortcut enum.
     */
    private void initializeShortcuts() {
        log.info("Initializing agility shortcut data from RuneLite's AgilityShortcut enum");

        for (AgilityShortcut shortcut : AgilityShortcut.values()) {
            // Index by object IDs
            for (int objectId : shortcut.getObstacleIds()) {
                shortcutsByObjectId
                        .computeIfAbsent(objectId, k -> new ArrayList<>())
                        .add(shortcut);
            }

            // Create obstacle definition
            ObstacleDefinition def = createDefinition(shortcut);
            allDefinitions.add(def);

            // Index definition by object IDs
            for (int objectId : shortcut.getObstacleIds()) {
                definitionsByObjectId.put(objectId, def);
            }
        }

        log.info("Loaded {} agility shortcuts with {} unique object IDs",
                allDefinitions.size(), definitionsByObjectId.size());
    }

    /**
     * Create an ObstacleDefinition from an AgilityShortcut.
     */
    private ObstacleDefinition createDefinition(AgilityShortcut shortcut) {
        int[] obstacleIds = shortcut.getObstacleIds();
        List<Integer> objectIdList = Arrays.stream(obstacleIds)
                .boxed()
                .collect(Collectors.toList());

        // Determine base success rate
        // Level 1 shortcuts and generic shortcuts typically always succeed
        double baseSuccessRate = shortcut.getLevel() <= 1 ? 
                SAFE_SUCCESS_RATE : DEFAULT_BASE_SUCCESS_RATE;

        // Determine action from description
        String action = inferAction(shortcut.getDescription());

        // Get world location
        WorldPoint worldLocation = shortcut.getWorldLocation();
        WorldPoint worldMapLocation = shortcut.getWorldMapLocation();

        return ObstacleDefinition.builder()
                .name(formatName(shortcut))
                .type(ObstacleDefinition.ObstacleType.AGILITY_SHORTCUT)
                .objectIds(objectIdList)
                .blockedStateId(objectIdList.isEmpty() ? -1 : objectIdList.get(0))
                .passableStateId(-1)
                .action(action)
                .requiredAgilityLevel(shortcut.getLevel())
                .baseSuccessRate(baseSuccessRate)
                .traversalCostTicks(calculateTraversalCost(shortcut))
                .worldLocation(worldLocation)
                .worldMapLocation(worldMapLocation)
                .build();
    }

    /**
     * Infer the menu action from the shortcut description.
     */
    private String inferAction(String description) {
        if (description == null) {
            return "Use";
        }

        String desc = description.toLowerCase();

        // Map common descriptions to actions
        if (desc.contains("pipe") || desc.contains("squeeze")) {
            return "Squeeze-through";
        }
        if (desc.contains("rock") || desc.contains("climb")) {
            return "Climb";
        }
        if (desc.contains("rope") || desc.contains("swing")) {
            return "Swing-across";
        }
        if (desc.contains("stepping") || desc.contains("stone")) {
            return "Cross";
        }
        if (desc.contains("jump") || desc.contains("leap") || desc.contains("gap")) {
            return "Jump";
        }
        if (desc.contains("balance") || desc.contains("log") || desc.contains("ledge")) {
            return "Cross";
        }
        if (desc.contains("tunnel") || desc.contains("underwall")) {
            return "Enter";
        }
        if (desc.contains("grapple")) {
            return "Grapple";
        }
        if (desc.contains("wall") || desc.contains("fence") || desc.contains("crumbling")) {
            return "Climb-over";
        }
        if (desc.contains("crevice") || desc.contains("crack")) {
            return "Squeeze-through";
        }
        if (desc.contains("vine") || desc.contains("ivy")) {
            return "Climb";
        }
        if (desc.contains("chain")) {
            return "Climb-up";
        }
        if (desc.contains("zipline")) {
            return "Ride";
        }
        if (desc.contains("bridge")) {
            return "Cross";
        }
        if (desc.contains("trellis")) {
            return "Climb";
        }
        if (desc.contains("hole")) {
            return "Enter";
        }
        if (desc.contains("window")) {
            return "Climb-through";
        }

        // Default action
        return "Use";
    }

    /**
     * Format the shortcut name for display.
     */
    private String formatName(AgilityShortcut shortcut) {
        // Convert enum name to readable format
        String enumName = shortcut.name();
        
        // Replace underscores with spaces and title case
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        
        for (char c : enumName.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }

        return sb.toString();
    }

    /**
     * Calculate traversal cost based on shortcut complexity.
     */
    private int calculateTraversalCost(AgilityShortcut shortcut) {
        String desc = shortcut.getDescription();
        if (desc == null) {
            return 3;
        }

        desc = desc.toLowerCase();

        // Higher cost for complex shortcuts
        if (desc.contains("grapple")) {
            return 6; // Grapple shortcuts take longer
        }
        if (desc.contains("pipe") || desc.contains("tunnel")) {
            return 4; // Pipe squeezes are slower
        }
        if (desc.contains("monkey") || desc.contains("balance")) {
            return 4; // Balance obstacles take time
        }
        if (desc.contains("jump") || desc.contains("leap")) {
            return 2; // Jumps are quick
        }
        if (desc.contains("stepping")) {
            return 3; // Stepping stones are moderate
        }

        return 3; // Default
    }

    // ========================================================================
    // Lookup Methods
    // ========================================================================

    /**
     * Get all agility shortcut definitions.
     *
     * @return unmodifiable list of all shortcuts
     */
    public List<ObstacleDefinition> getAllShortcuts() {
        return Collections.unmodifiableList(allDefinitions);
    }

    /**
     * Get shortcut definition by object ID.
     *
     * @param objectId the object ID to look up
     * @return the obstacle definition, or null if not found
     */
    @Nullable
    public ObstacleDefinition getDefinitionByObjectId(int objectId) {
        return definitionsByObjectId.get(objectId);
    }

    /**
     * Check if an object ID is a known agility shortcut.
     *
     * @param objectId the object ID to check
     * @return true if this is a known shortcut
     */
    public boolean isKnownShortcut(int objectId) {
        return definitionsByObjectId.containsKey(objectId);
    }

    /**
     * Get the AgilityShortcut enum values for an object ID.
     *
     * @param objectId the object ID
     * @return list of matching shortcuts (may be empty)
     */
    public List<AgilityShortcut> getShortcutsForObjectId(int objectId) {
        return shortcutsByObjectId.getOrDefault(objectId, Collections.emptyList());
    }

    /**
     * Find shortcuts near a world point.
     *
     * @param point       the reference point
     * @param maxDistance maximum distance in tiles
     * @return list of shortcuts within range
     */
    public List<ObstacleDefinition> findShortcutsNear(WorldPoint point, int maxDistance) {
        return allDefinitions.stream()
                .filter(def -> {
                    WorldPoint loc = def.getWorldLocation();
                    if (loc == null) {
                        loc = def.getWorldMapLocation();
                    }
                    if (loc == null) {
                        return false;
                    }
                    return loc.distanceTo(point) <= maxDistance;
                })
                .collect(Collectors.toList());
    }

    /**
     * Get shortcuts usable by a player at a given agility level.
     *
     * @param agilityLevel the player's agility level
     * @return list of shortcuts the player can use
     */
    public List<ObstacleDefinition> getUsableShortcuts(int agilityLevel) {
        return allDefinitions.stream()
                .filter(def -> def.canAttempt(agilityLevel))
                .collect(Collectors.toList());
    }

    /**
     * Get shortcuts that are safe (low failure rate) for a player.
     *
     * @param agilityLevel  the player's agility level
     * @param riskThreshold maximum acceptable failure rate
     * @return list of safe shortcuts
     */
    public List<ObstacleDefinition> getSafeShortcuts(int agilityLevel, double riskThreshold) {
        return allDefinitions.stream()
                .filter(def -> def.canAttempt(agilityLevel))
                .filter(def -> !def.isRisky(agilityLevel, riskThreshold))
                .collect(Collectors.toList());
    }

    /**
     * Get safe shortcuts using the default risk threshold (10%).
     *
     * @param agilityLevel the player's agility level
     * @return list of safe shortcuts
     */
    public List<ObstacleDefinition> getSafeShortcuts(int agilityLevel) {
        return getSafeShortcuts(agilityLevel, DEFAULT_RISK_THRESHOLD);
    }

    // ========================================================================
    // Condition Checking
    // ========================================================================

    /**
     * Check if a shortcut can be used based on special conditions.
     * Some shortcuts have quest or varbit requirements that are checked dynamically.
     *
     * @param shortcut the shortcut to check
     * @param object   the tile object being interacted with
     * @return true if the shortcut can be used
     */
    public boolean canUseShortcut(AgilityShortcut shortcut, TileObject object) {
        // Use RuneLite's built-in matches method for special conditions
        return shortcut.matches(client, object);
    }

    /**
     * Check if a shortcut has special conditions beyond just agility level.
     *
     * @param shortcut the shortcut to check
     * @return true if the shortcut has special conditions
     */
    public boolean hasSpecialConditions(AgilityShortcut shortcut) {
        // These shortcuts have special matches() implementations
        switch (shortcut) {
            case WEISS_BROKEN_FENCE:
            case VARROCK_CASTLE_GARDEN_TRELLIS:
            case AL_KHARID_WINDOW:
            case FENKENSTRAIN_MAUSOLEUM_BRIDGE:
            case DARKMEYER_WALL_ROCKS:
                return true;
            default:
                return false;
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get the total number of registered shortcuts.
     *
     * @return number of shortcuts
     */
    public int getShortcutCount() {
        return allDefinitions.size();
    }

    /**
     * Get the number of unique object IDs registered.
     *
     * @return number of unique object IDs
     */
    public int getObjectIdCount() {
        return definitionsByObjectId.size();
    }

    /**
     * Get shortcuts grouped by required level.
     *
     * @return map of level to shortcuts requiring that level
     */
    public Map<Integer, List<ObstacleDefinition>> getShortcutsByLevel() {
        return allDefinitions.stream()
                .collect(Collectors.groupingBy(ObstacleDefinition::getRequiredAgilityLevel));
    }

    /**
     * Get the maximum agility level required by any shortcut.
     *
     * @return highest required agility level
     */
    public int getMaxRequiredLevel() {
        return allDefinitions.stream()
                .mapToInt(ObstacleDefinition::getRequiredAgilityLevel)
                .max()
                .orElse(0);
    }
}

