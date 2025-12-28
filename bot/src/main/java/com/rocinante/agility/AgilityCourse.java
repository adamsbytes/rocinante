package com.rocinante.agility;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Represents an agility course (typically a rooftop course).
 *
 * <p>Each course has:
 * <ul>
 *   <li>A sequence of obstacles to complete</li>
 *   <li>Level requirement and XP per lap</li>
 *   <li>Start area and end point for lap tracking</li>
 *   <li>Mark of grace spawn locations</li>
 * </ul>
 *
 * <p>Example course definition:
 * <pre>{@code
 * AgilityCourse draynorRooftop = AgilityCourse.builder()
 *     .id("draynor_rooftop")
 *     .name("Draynor Village Rooftop")
 *     .requiredLevel(10)
 *     .xpPerLap(120.0)
 *     .regionId(12338)
 *     .startArea(new WorldPoint(3103, 3277, 0))
 *     .startAreaRadius(5)
 *     .courseEndPoint(new WorldPoint(3103, 3261, 0))
 *     .obstacles(List.of(...))
 *     .markSpawnTiles(List.of(...))
 *     .build();
 * }</pre>
 *
 * @see AgilityObstacle
 * @see AgilityCourseRepository
 */
@Value
@Builder
public class AgilityCourse {

    // ========================================================================
    // Identification
    // ========================================================================

    /**
     * Unique identifier for this course.
     * Examples: "draynor_rooftop", "canifis_rooftop", "seers_rooftop"
     */
    String id;

    /**
     * Human-readable name of the course.
     * Examples: "Draynor Village Rooftop", "Canifis Rooftop Course"
     */
    String name;

    // ========================================================================
    // Requirements
    // ========================================================================

    /**
     * Minimum Agility level required to use this course.
     */
    int requiredLevel;

    /**
     * Maximum Agility level this course is efficient for.
     * -1 means no maximum (good for all levels above required).
     */
    @Builder.Default
    int maxLevel = -1;

    // ========================================================================
    // XP and Efficiency
    // ========================================================================

    /**
     * Total XP gained per complete lap.
     */
    double xpPerLap;

    /**
     * Average marks of grace per hour (for efficiency comparison).
     */
    @Builder.Default
    double marksPerHour = 0;

    /**
     * Estimated laps per hour for efficiency calculations.
     */
    @Builder.Default
    int lapsPerHour = 0;

    // ========================================================================
    // Location
    // ========================================================================

    /**
     * Region ID for this course (from RuneLite).
     * Used to detect if player is in the course area.
     */
    int regionId;

    /**
     * Starting point/area for the course (near first obstacle).
     */
    WorldPoint startArea;

    /**
     * Radius around startArea considered "at start" (in tiles).
     */
    @Builder.Default
    int startAreaRadius = 5;

    /**
     * Position where the course lap completes.
     * Used to detect lap completion and track progress.
     */
    WorldPoint courseEndPoint;

    // ========================================================================
    // Obstacle Sequence
    // ========================================================================

    /**
     * Ordered list of obstacles in the course.
     * Obstacles should be in the order they are completed.
     */
    @Builder.Default
    List<AgilityObstacle> obstacles = Collections.emptyList();

    // ========================================================================
    // Mark of Grace Spawns
    // ========================================================================

    /**
     * Possible tile locations where marks of grace can spawn, with obstacle association.
     *
     * <p>Each mark spawn tile is associated with a specific obstacle index via
     * {@link MarkSpawnTile#getAfterObstacle()}. This allows the bot to determine
     * which marks are actually reachable based on which obstacles have been completed
     * in the current lap.
     *
     * <p>On rooftop courses, multiple platforms may share the same plane but are
     * not walkable between each other. A mark visible on platform 3 is not reachable
     * from platform 1, even if both are on plane 3.
     */
    @Builder.Default
    List<MarkSpawnTile> markSpawnTiles = Collections.emptyList();

    // ========================================================================
    // Course-specific Features
    // ========================================================================

    /**
     * Whether this course has a shortcut (e.g., Seers teleport).
     */
    @Builder.Default
    boolean hasShortcut = false;

    /**
     * Level required to use the shortcut (if applicable).
     */
    @Builder.Default
    int shortcutLevelRequired = 0;

    /**
     * Hard Diary completion required for certain bonuses.
     */
    @Builder.Default
    boolean requiresDiary = false;

    /**
     * Name of the diary required (e.g., "Kandarin Hard").
     */
    String diaryName;

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get the total number of obstacles in this course.
     *
     * @return obstacle count
     */
    public int getObstacleCount() {
        return obstacles != null ? obstacles.size() : 0;
    }

    /**
     * Get an obstacle by its index.
     *
     * @param index the obstacle index (0-based)
     * @return Optional containing the obstacle, or empty if invalid index
     */
    public Optional<AgilityObstacle> getObstacle(int index) {
        if (obstacles == null || index < 0 || index >= obstacles.size()) {
            return Optional.empty();
        }
        return Optional.of(obstacles.get(index));
    }

    /**
     * Get the first obstacle of the course.
     *
     * @return Optional containing the first obstacle, or empty if no obstacles
     */
    public Optional<AgilityObstacle> getFirstObstacle() {
        return getObstacle(0);
    }

    /**
     * Get the last obstacle of the course.
     *
     * @return Optional containing the last obstacle, or empty if no obstacles
     */
    public Optional<AgilityObstacle> getLastObstacle() {
        return getObstacle(getObstacleCount() - 1);
    }

    /**
     * Check if a position is near the start of the course.
     *
     * @param position the position to check
     * @return true if within startAreaRadius of startArea
     */
    public boolean isAtStart(WorldPoint position) {
        if (startArea == null || position == null) {
            return false;
        }
        return position.distanceTo(startArea) <= startAreaRadius;
    }

    /**
     * Check if a position is at the end of the course (lap complete point).
     *
     * @param position the position to check
     * @return true if at or very near the course end point
     */
    public boolean isAtEnd(WorldPoint position) {
        if (courseEndPoint == null || position == null) {
            return false;
        }
        return position.distanceTo(courseEndPoint) <= 2;
    }

    /**
     * Check if this course is valid for a given Agility level.
     *
     * @param level the player's Agility level
     * @return true if level is within [requiredLevel, maxLevel]
     */
    public boolean isValidForLevel(int level) {
        if (level < requiredLevel) {
            return false;
        }
        return maxLevel < 0 || level <= maxLevel;
    }

    /**
     * Check if a position matches any known mark spawn tile.
     *
     * @param position the position to check
     * @return true if position is a potential mark spawn location
     */
    public boolean isMarkSpawnTile(WorldPoint position) {
        if (markSpawnTiles == null || position == null) {
            return false;
        }
        return markSpawnTiles.stream()
                .anyMatch(tile -> tile.matchesPosition(position));
    }

    /**
     * Get mark spawn tiles that are reachable after completing a given obstacle.
     *
     * <p>On rooftop courses, marks can only be picked up if they're on a platform
     * the player has access to. After completing obstacle N, only marks with
     * {@code afterObstacle <= N} are reachable.
     *
     * @param lastCompletedObstacleIndex the index of the last completed obstacle (-1 if none)
     * @return list of reachable mark spawn tiles
     */
    public List<MarkSpawnTile> getReachableMarkSpawnTiles(int lastCompletedObstacleIndex) {
        if (markSpawnTiles == null) {
            return Collections.emptyList();
        }
        return markSpawnTiles.stream()
                .filter(tile -> tile.isReachableAfterObstacle(lastCompletedObstacleIndex))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Check if a mark at a given position is reachable based on completed obstacles.
     *
     * <p>This method checks if the mark's position corresponds to a spawn tile
     * associated with an obstacle the player has already completed.
     *
     * @param markPosition the position of the mark
     * @param lastCompletedObstacleIndex the index of the last completed obstacle
     * @param tolerance how close the mark needs to be to a spawn tile (in tiles)
     * @return true if the mark is on a reachable platform
     */
    public boolean isMarkReachable(WorldPoint markPosition, int lastCompletedObstacleIndex, int tolerance) {
        if (markSpawnTiles == null || markPosition == null) {
            return false;
        }

        // Check if the mark is near any reachable spawn tile
        return markSpawnTiles.stream()
                .filter(tile -> tile.isReachableAfterObstacle(lastCompletedObstacleIndex))
                .anyMatch(tile -> tile.isNearPosition(markPosition, tolerance));
    }

    /**
     * Determine which obstacle a mark spawn tile is associated with.
     *
     * @param markPosition the position of the mark
     * @param tolerance how close to match (in tiles)
     * @return the obstacle index this mark is associated with, or -1 if unknown
     */
    public int getMarkObstacleIndex(WorldPoint markPosition, int tolerance) {
        if (markSpawnTiles == null || markPosition == null) {
            return -1;
        }

        return markSpawnTiles.stream()
                .filter(tile -> tile.isNearPosition(markPosition, tolerance))
                .mapToInt(MarkSpawnTile::getAfterObstacle)
                .min()
                .orElse(-1);
    }

    /**
     * Determine which obstacle the player should do next based on their position.
     *
     * <p>This checks each obstacle's expected landing position to see if the player
     * is standing there. If at an obstacle's landing, the next obstacle is returned.
     * If at the course end, returns the first obstacle (new lap).
     *
     * @param playerPosition the player's current position
     * @return Optional containing the next obstacle to do, or empty if unclear
     */
    public Optional<AgilityObstacle> determineNextObstacle(WorldPoint playerPosition) {
        if (obstacles == null || obstacles.isEmpty() || playerPosition == null) {
            return Optional.empty();
        }

        // Check if at course end (completed lap, do first obstacle)
        if (isAtEnd(playerPosition)) {
            return getFirstObstacle();
        }

        // Check if at start area (begin course)
        if (isAtStart(playerPosition)) {
            return getFirstObstacle();
        }

        // Check each obstacle's landing position
        for (int i = 0; i < obstacles.size(); i++) {
            AgilityObstacle obstacle = obstacles.get(i);
            if (obstacle.isAtLanding(playerPosition)) {
                // Player completed this obstacle, return the next one
                int nextIndex = (i + 1) % obstacles.size();
                return getObstacle(nextIndex);
            }
        }

        // Check if player is in any obstacle's interaction area
        for (AgilityObstacle obstacle : obstacles) {
            if (obstacle.canInteractFrom(playerPosition)) {
                return Optional.of(obstacle);
            }
        }

        return Optional.empty();
    }

    /**
     * Calculate XP per hour based on laps per hour.
     *
     * @return estimated XP per hour
     */
    public double getXpPerHour() {
        return xpPerLap * lapsPerHour;
    }

    /**
     * Get a summary string for logging.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        return String.format("AgilityCourse[%s, lvl %d+, %.0f xp/lap, %d obstacles]",
                name, requiredLevel, xpPerLap, getObstacleCount());
    }
}

