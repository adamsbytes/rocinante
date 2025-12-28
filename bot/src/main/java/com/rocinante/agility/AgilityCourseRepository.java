package com.rocinante.agility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Repository for loading and querying agility courses.
 *
 * <p>Courses are loaded from {@code data/agility_courses.json} at startup.
 * The repository provides various query methods for finding appropriate
 * courses based on level and region.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Get a specific course
 * Optional<AgilityCourse> course = repo.getCourseById("draynor_rooftop");
 *
 * // Get courses available at a level
 * List<AgilityCourse> courses = repo.getCoursesForLevel(40);
 *
 * // Find course by region (player is on course)
 * Optional<AgilityCourse> current = repo.getCourseByRegion(12338);
 * }</pre>
 *
 * @see AgilityCourse
 * @see AgilityObstacle
 */
@Slf4j
@Singleton
public class AgilityCourseRepository {

    private static final String DATA_FILE = "/data/agility_courses.json";

    /**
     * All loaded courses, keyed by ID.
     */
    private final Map<String, AgilityCourse> coursesById = new HashMap<>();

    /**
     * Courses indexed by region ID for fast lookup.
     */
    private final Map<Integer, AgilityCourse> coursesByRegion = new HashMap<>();

    /**
     * Courses sorted by required level.
     */
    private final List<AgilityCourse> coursesByLevel = new ArrayList<>();

    /**
     * Gson instance for JSON parsing.
     */
    private final Gson gson;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public AgilityCourseRepository() {
        this.gson = new GsonBuilder().create();
        loadCourses();
    }

    // ========================================================================
    // Loading
    // ========================================================================

    /**
     * Load agility courses from the JSON data file.
     */
    private void loadCourses() {
        try (InputStream is = getClass().getResourceAsStream(DATA_FILE)) {
            if (is == null) {
                log.warn("Agility courses file not found: {}", DATA_FILE);
                return;
            }

            // Use older Gson API for compatibility with RuneLite's bundled Gson version
            JsonObject root = new JsonParser().parse(
                    new InputStreamReader(is, StandardCharsets.UTF_8)
            ).getAsJsonObject();

            JsonArray coursesArray = root.getAsJsonArray("courses");
            if (coursesArray == null) {
                log.warn("No 'courses' array in agility courses file");
                return;
            }

            int loadedCount = 0;
            for (JsonElement element : coursesArray) {
                try {
                    AgilityCourse course = parseCourse(element.getAsJsonObject());
                    registerCourse(course);
                    loadedCount++;
                } catch (Exception e) {
                    log.error("Failed to parse agility course: {}", element, e);
                }
            }

            // Sort courses by level
            coursesByLevel.sort(Comparator.comparingInt(AgilityCourse::getRequiredLevel));

            log.info("Loaded {} agility courses from {}", loadedCount, DATA_FILE);

        } catch (IOException e) {
            log.error("Failed to load agility courses", e);
        }
    }

    /**
     * Parse a single agility course from JSON.
     */
    private AgilityCourse parseCourse(JsonObject obj) {
        AgilityCourse.AgilityCourseBuilder builder = AgilityCourse.builder();

        // Required fields
        builder.id(obj.get("id").getAsString());
        builder.name(obj.get("name").getAsString());
        builder.requiredLevel(obj.get("requiredLevel").getAsInt());
        builder.xpPerLap(obj.get("xpPerLap").getAsDouble());
        builder.regionId(obj.get("regionId").getAsInt());

        // Optional level cap
        if (obj.has("maxLevel")) {
            builder.maxLevel(obj.get("maxLevel").getAsInt());
        }

        // Efficiency stats
        if (obj.has("marksPerHour")) {
            builder.marksPerHour(obj.get("marksPerHour").getAsDouble());
        }
        if (obj.has("lapsPerHour")) {
            builder.lapsPerHour(obj.get("lapsPerHour").getAsInt());
        }

        // Start area
        if (obj.has("startArea")) {
            builder.startArea(parseWorldPoint(obj.getAsJsonObject("startArea")));
        }
        if (obj.has("startAreaRadius")) {
            builder.startAreaRadius(obj.get("startAreaRadius").getAsInt());
        }

        // Course end point
        if (obj.has("courseEndPoint")) {
            builder.courseEndPoint(parseWorldPoint(obj.getAsJsonObject("courseEndPoint")));
        }

        // Obstacles
        if (obj.has("obstacles")) {
            builder.obstacles(parseObstacles(obj.getAsJsonArray("obstacles")));
        }

        // Mark spawn tiles
        if (obj.has("markSpawnTiles")) {
            builder.markSpawnTiles(parseMarkSpawnTiles(obj.getAsJsonArray("markSpawnTiles")));
        }

        // Course-specific features
        if (obj.has("hasShortcut")) {
            builder.hasShortcut(obj.get("hasShortcut").getAsBoolean());
        }
        if (obj.has("shortcutLevelRequired")) {
            builder.shortcutLevelRequired(obj.get("shortcutLevelRequired").getAsInt());
        }
        if (obj.has("requiresDiary")) {
            builder.requiresDiary(obj.get("requiresDiary").getAsBoolean());
        }
        if (obj.has("diaryName")) {
            builder.diaryName(obj.get("diaryName").getAsString());
        }

        return builder.build();
    }

    /**
     * Parse a list of obstacles from JSON.
     */
    private List<AgilityObstacle> parseObstacles(JsonArray array) {
        List<AgilityObstacle> obstacles = new ArrayList<>(array.size());
        for (JsonElement elem : array) {
            obstacles.add(parseObstacle(elem.getAsJsonObject()));
        }
        return obstacles;
    }

    /**
     * Parse a single obstacle from JSON.
     */
    private AgilityObstacle parseObstacle(JsonObject obj) {
        AgilityObstacle.AgilityObstacleBuilder builder = AgilityObstacle.builder();

        // Required fields
        builder.index(obj.get("index").getAsInt());
        builder.name(obj.get("name").getAsString());
        builder.objectId(obj.get("objectId").getAsInt());
        builder.action(obj.get("action").getAsString());

        // Alternate IDs
        if (obj.has("alternateIds")) {
            builder.alternateIds(parseIntList(obj.getAsJsonArray("alternateIds")));
        }

        // Interaction area
        if (obj.has("interactArea")) {
            builder.interactArea(parseWorldArea(obj.getAsJsonObject("interactArea")));
        }

        // Landing positions
        if (obj.has("expectedLanding")) {
            builder.expectedLanding(parseWorldPoint(obj.getAsJsonObject("expectedLanding")));
        }
        if (obj.has("landingTolerance")) {
            builder.landingTolerance(obj.get("landingTolerance").getAsInt());
        }

        // Failure handling
        if (obj.has("canFail")) {
            builder.canFail(obj.get("canFail").getAsBoolean());
        }
        if (obj.has("failureLanding")) {
            builder.failureLanding(parseWorldPoint(obj.getAsJsonObject("failureLanding")));
        }
        if (obj.has("failureDamage")) {
            builder.failureDamage(obj.get("failureDamage").getAsInt());
        }

        // Timing
        if (obj.has("expectedTicks")) {
            builder.expectedTicks(obj.get("expectedTicks").getAsInt());
        }
        if (obj.has("animationId")) {
            builder.animationId(obj.get("animationId").getAsInt());
        }

        return builder.build();
    }

    /**
     * Parse a WorldPoint from JSON.
     */
    private WorldPoint parseWorldPoint(JsonObject obj) {
        return new WorldPoint(
                obj.get("x").getAsInt(),
                obj.get("y").getAsInt(),
                obj.has("plane") ? obj.get("plane").getAsInt() : 0
        );
    }

    /**
     * Parse a WorldArea from JSON.
     */
    private WorldArea parseWorldArea(JsonObject obj) {
        return new WorldArea(
                obj.get("x").getAsInt(),
                obj.get("y").getAsInt(),
                obj.get("width").getAsInt(),
                obj.get("height").getAsInt(),
                obj.has("plane") ? obj.get("plane").getAsInt() : 0
        );
    }

    /**
     * Parse a list of WorldPoints from JSON.
     */
    private List<WorldPoint> parseWorldPointList(JsonArray array) {
        List<WorldPoint> points = new ArrayList<>(array.size());
        for (JsonElement elem : array) {
            points.add(parseWorldPoint(elem.getAsJsonObject()));
        }
        return points;
    }

    /**
     * Parse a list of MarkSpawnTiles from JSON.
     *
     * <p>Each tile object must have x, y, plane coordinates and an afterObstacle index.
     * The afterObstacle index determines which obstacle must be completed before
     * marks at this tile become reachable.
     */
    private List<MarkSpawnTile> parseMarkSpawnTiles(JsonArray array) {
        List<MarkSpawnTile> tiles = new ArrayList<>(array.size());
        for (JsonElement elem : array) {
            JsonObject obj = elem.getAsJsonObject();
            WorldPoint position = parseWorldPoint(obj);
            int afterObstacle = obj.has("afterObstacle") ? obj.get("afterObstacle").getAsInt() : 0;
            String description = obj.has("description") ? obj.get("description").getAsString() : null;

            tiles.add(MarkSpawnTile.builder()
                    .position(position)
                    .afterObstacle(afterObstacle)
                    .description(description)
                    .build());
        }
        return tiles;
    }

    /**
     * Parse a JSON array to a list of integers.
     */
    private List<Integer> parseIntList(JsonArray array) {
        List<Integer> list = new ArrayList<>(array.size());
        for (JsonElement elem : array) {
            list.add(elem.getAsInt());
        }
        return list;
    }

    /**
     * Register a course in the lookup maps.
     */
    private void registerCourse(AgilityCourse course) {
        coursesById.put(course.getId(), course);
        coursesByRegion.put(course.getRegionId(), course);
        coursesByLevel.add(course);
    }

    // ========================================================================
    // Query Methods
    // ========================================================================

    /**
     * Get an agility course by its ID.
     *
     * @param id the course ID
     * @return optional containing the course, or empty if not found
     */
    public Optional<AgilityCourse> getCourseById(String id) {
        return Optional.ofNullable(coursesById.get(id));
    }

    /**
     * Get an agility course by region ID.
     * Useful for detecting which course a player is currently on.
     *
     * @param regionId the game region ID
     * @return optional containing the course, or empty if no course in that region
     */
    public Optional<AgilityCourse> getCourseByRegion(int regionId) {
        return Optional.ofNullable(coursesByRegion.get(regionId));
    }

    /**
     * Get all courses available at a given Agility level.
     *
     * @param level the player's Agility level
     * @return list of courses valid for that level (sorted by level requirement)
     */
    public List<AgilityCourse> getCoursesForLevel(int level) {
        return coursesByLevel.stream()
                .filter(c -> c.isValidForLevel(level))
                .collect(Collectors.toList());
    }

    /**
     * Get the best course for a given level (highest XP/hr).
     *
     * @param level the player's Agility level
     * @return optional containing the best course, or empty if none available
     */
    public Optional<AgilityCourse> getBestCourseForLevel(int level) {
        return getCoursesForLevel(level).stream()
                .max(Comparator.comparingDouble(AgilityCourse::getXpPerHour));
    }

    /**
     * Get the best course for a given level considering mark of grace efficiency.
     * This prefers courses with both good XP and mark rates.
     *
     * @param level the player's Agility level
     * @return optional containing the best course
     */
    public Optional<AgilityCourse> getBestCourseForLevelWithMarks(int level) {
        return getCoursesForLevel(level).stream()
                .max(Comparator.comparingDouble(c -> 
                        c.getXpPerHour() + (c.getMarksPerHour() * 1000) // Weight marks highly
                ));
    }

    /**
     * Get all rooftop courses (typically all courses with marks of grace).
     *
     * @return list of all rooftop courses
     */
    public List<AgilityCourse> getRooftopCourses() {
        return coursesByLevel.stream()
                .filter(c -> c.getId().contains("rooftop"))
                .collect(Collectors.toList());
    }

    /**
     * Get the total number of loaded courses.
     *
     * @return course count
     */
    public int getCourseCount() {
        return coursesById.size();
    }

    /**
     * Get all course IDs.
     *
     * @return set of course IDs
     */
    public Set<String> getAllCourseIds() {
        return Collections.unmodifiableSet(coursesById.keySet());
    }

    /**
     * Check if a course exists.
     *
     * @param id the course ID
     * @return true if course exists
     */
    public boolean hasCourse(String id) {
        return coursesById.containsKey(id);
    }
}

