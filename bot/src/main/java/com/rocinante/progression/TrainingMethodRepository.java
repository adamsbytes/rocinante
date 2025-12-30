package com.rocinante.progression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
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
 * Repository for loading and querying training methods.
 *
 * <p>Methods are loaded from {@code data/training_methods.json} at startup.
 * The repository provides various query methods for finding appropriate
 * training methods based on skill, level, and other criteria.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Get all mining methods for level 15
 * List<TrainingMethod> methods = repo.getMethodsForSkill(Skill.MINING, 15);
 *
 * // Get a specific method by ID
 * Optional<TrainingMethod> method = repo.getMethodById("iron_ore_powermine");
 *
 * // Get the best method for a level
 * Optional<TrainingMethod> best = repo.getBestMethod(Skill.MINING, 15);
 * }</pre>
 */
@Slf4j
@Singleton
public class TrainingMethodRepository {

    private static final String DATA_FILE = "/data/training_methods.json";

    /**
     * All loaded training methods, keyed by ID.
     */
    private final Map<String, TrainingMethod> methodsById = new HashMap<>();

    /**
     * Methods indexed by skill for faster lookup.
     */
    private final Map<Skill, List<TrainingMethod>> methodsBySkill = new EnumMap<>(Skill.class);

    /**
     * Gson instance for JSON parsing.
     */
    private final Gson gson;

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public TrainingMethodRepository() {
        this.gson = new GsonBuilder().create();
        loadMethods();
    }

    // ========================================================================
    // Loading
    // ========================================================================

    /**
     * Load training methods from the JSON data file.
     */
    private void loadMethods() {
        try (InputStream is = getClass().getResourceAsStream(DATA_FILE)) {
            if (is == null) {
                log.warn("Training methods file not found: {}", DATA_FILE);
                return;
            }

            // Use older Gson API for compatibility with RuneLite's bundled Gson version
            JsonObject root = new JsonParser().parse(
                    new InputStreamReader(is, StandardCharsets.UTF_8)
            ).getAsJsonObject();

            JsonArray methodsArray = root.getAsJsonArray("methods");
            if (methodsArray == null) {
                log.warn("No 'methods' array in training methods file");
                return;
            }

            int loadedCount = 0;
            for (JsonElement element : methodsArray) {
                try {
                    TrainingMethod method = parseMethod(element.getAsJsonObject());
                    registerMethod(method);
                    loadedCount++;
                } catch (Exception e) {
                    log.error("Failed to parse training method: {}", element, e);
                }
            }

            log.info("Loaded {} training methods from {}", loadedCount, DATA_FILE);

        } catch (IOException e) {
            log.error("Failed to load training methods", e);
        }
    }

    /**
     * Parse a single training method from JSON.
     */
    private TrainingMethod parseMethod(JsonObject obj) {
        TrainingMethod.TrainingMethodBuilder builder = TrainingMethod.builder();

        // Required fields
        builder.id(obj.get("id").getAsString());
        builder.name(obj.get("name").getAsString());
        builder.skill(Skill.valueOf(obj.get("skill").getAsString().toUpperCase()));
        builder.methodType(MethodType.valueOf(obj.get("methodType").getAsString().toUpperCase()));
        builder.minLevel(obj.get("minLevel").getAsInt());

        // XP configuration
        if (obj.has("xpPerAction")) {
            builder.xpPerAction(obj.get("xpPerAction").getAsDouble());
        }
        if (obj.has("xpMultiplier")) {
            builder.xpMultiplier(obj.get("xpMultiplier").getAsDouble());
        }
        if (obj.has("gpPerHour")) {
            builder.gpPerHour(obj.get("gpPerHour").getAsInt());
        }

        // Locations (new multi-location format)
        if (obj.has("locations")) {
            builder.locations(parseLocations(obj.getAsJsonArray("locations")));
        }

        // Method-level requirements
        if (obj.has("requirements")) {
            builder.requirements(parseRequirements(obj.getAsJsonObject("requirements")));
        }

        // Gathering configuration
        if (obj.has("targetObjectIds")) {
            builder.targetObjectIds(parseIntList(obj.getAsJsonArray("targetObjectIds")));
        }
        if (obj.has("targetNpcIds")) {
            builder.targetNpcIds(parseIntList(obj.getAsJsonArray("targetNpcIds")));
        }
        if (obj.has("menuAction")) {
            builder.menuAction(obj.get("menuAction").getAsString());
        }
        if (obj.has("successAnimationIds")) {
            builder.successAnimationIds(parseIntList(obj.getAsJsonArray("successAnimationIds")));
        }

        // Inventory handling
        if (obj.has("requiresInventorySpace")) {
            builder.requiresInventorySpace(obj.get("requiresInventorySpace").getAsBoolean());
        }
        if (obj.has("dropWhenFull")) {
            builder.dropWhenFull(obj.get("dropWhenFull").getAsBoolean());
        }
        if (obj.has("productItemIds")) {
            builder.productItemIds(parseIntList(obj.getAsJsonArray("productItemIds")));
        }

        // Processing configuration
        if (obj.has("sourceItemId")) {
            builder.sourceItemId(obj.get("sourceItemId").getAsInt());
        }
        if (obj.has("targetItemId")) {
            builder.targetItemId(obj.get("targetItemId").getAsInt());
        }
        if (obj.has("processingObjectId")) {
            builder.processingObjectId(obj.get("processingObjectId").getAsInt());
        }
        if (obj.has("outputItemId")) {
            builder.outputItemId(obj.get("outputItemId").getAsInt());
        }
        if (obj.has("outputPerAction")) {
            builder.outputPerAction(obj.get("outputPerAction").getAsInt());
        }
        if (obj.has("makeAllWidgetId")) {
            builder.makeAllWidgetId(obj.get("makeAllWidgetId").getAsInt());
        }
        if (obj.has("makeAllChildId")) {
            builder.makeAllChildId(obj.get("makeAllChildId").getAsInt());
        }

        // Tool requirements
        if (obj.has("requiredItemIds")) {
            builder.requiredItemIds(parseIntList(obj.getAsJsonArray("requiredItemIds")));
        }
        if (obj.has("ironmanViable")) {
            builder.ironmanViable(obj.get("ironmanViable").getAsBoolean());
        }

        // Ground item watching
        if (obj.has("watchedGroundItems")) {
            builder.watchedGroundItems(parseWatchedGroundItems(obj.getAsJsonArray("watchedGroundItems")));
        }

        // Agility configuration
        if (obj.has("courseId")) {
            builder.courseId(obj.get("courseId").getAsString());
        }

        // Minigame configuration
        if (obj.has("minigameId")) {
            builder.minigameId(obj.get("minigameId").getAsString());
        }
        if (obj.has("minigameStrategy")) {
            builder.minigameStrategy(obj.get("minigameStrategy").getAsString());
        }

        // Firemaking configuration
        if (obj.has("logItemId")) {
            builder.logItemId(obj.get("logItemId").getAsInt());
        }

        // Notes
        if (obj.has("notes")) {
            builder.notes(obj.get("notes").getAsString());
        }

        return builder.build();
    }

    /**
     * Parse locations array from JSON.
     */
    private List<MethodLocation> parseLocations(JsonArray array) {
        List<MethodLocation> locations = new ArrayList<>(array.size());
        for (JsonElement elem : array) {
            JsonObject locObj = elem.getAsJsonObject();
            MethodLocation.MethodLocationBuilder builder = MethodLocation.builder();

            // Required fields
            builder.id(locObj.get("id").getAsString());
            builder.name(locObj.get("name").getAsString());
            builder.actionsPerHour(locObj.get("actionsPerHour").getAsInt());

            // Optional fields
            if (locObj.has("locationId")) {
                builder.locationId(locObj.get("locationId").getAsString());
            }
            if (locObj.has("exactPosition")) {
                JsonObject pos = locObj.getAsJsonObject("exactPosition");
                builder.exactPosition(new WorldPoint(
                        pos.get("x").getAsInt(),
                        pos.get("y").getAsInt(),
                        pos.has("plane") ? pos.get("plane").getAsInt() : 0
                ));
            }
            if (locObj.has("bankLocationId")) {
                builder.bankLocationId(locObj.get("bankLocationId").getAsString());
            }
            if (locObj.has("requirements")) {
                builder.requirements(parseRequirements(locObj.getAsJsonObject("requirements")));
            }
            if (locObj.has("notes")) {
                builder.notes(locObj.get("notes").getAsString());
            }

            locations.add(builder.build());
        }
        return locations;
    }

    /**
     * Parse requirements from JSON.
     */
    private MethodRequirements parseRequirements(JsonObject obj) {
        MethodRequirements.MethodRequirementsBuilder builder = MethodRequirements.builder();

        if (obj.has("quests")) {
            builder.quests(parseStringList(obj.getAsJsonArray("quests")));
        }
        if (obj.has("diaries")) {
            builder.diaries(parseStringList(obj.getAsJsonArray("diaries")));
        }
        if (obj.has("skills")) {
            JsonObject skillsObj = obj.getAsJsonObject("skills");
            Map<String, Integer> skills = new HashMap<>();
            for (String key : skillsObj.keySet()) {
                skills.put(key, skillsObj.get(key).getAsInt());
            }
            builder.skills(skills);
        }
        if (obj.has("members")) {
            builder.members(obj.get("members").getAsBoolean());
        }
        if (obj.has("areas")) {
            builder.areas(parseStringList(obj.getAsJsonArray("areas")));
        }

        return builder.build();
    }

    /**
     * Parse watched ground items from JSON.
     */
    private List<GroundItemWatch> parseWatchedGroundItems(JsonArray array) {
        List<GroundItemWatch> items = new ArrayList<>(array.size());
        for (JsonElement elem : array) {
            JsonObject itemObj = elem.getAsJsonObject();
            GroundItemWatch.GroundItemWatchBuilder builder = GroundItemWatch.builder();

            builder.itemId(itemObj.get("itemId").getAsInt());
            if (itemObj.has("itemName")) {
                builder.itemName(itemObj.get("itemName").getAsString());
            }
            if (itemObj.has("priority")) {
                builder.priority(itemObj.get("priority").getAsInt());
            }
            if (itemObj.has("maxPickupDistance")) {
                builder.maxPickupDistance(itemObj.get("maxPickupDistance").getAsInt());
            }
            if (itemObj.has("interruptAction")) {
                builder.interruptAction(itemObj.get("interruptAction").getAsBoolean());
            }

            items.add(builder.build());
        }
        return items;
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
     * Parse a JSON array to a list of strings.
     */
    private List<String> parseStringList(JsonArray array) {
        List<String> list = new ArrayList<>(array.size());
        for (JsonElement elem : array) {
            list.add(elem.getAsString());
        }
        return list;
    }

    /**
     * Register a method in the lookup maps.
     */
    private void registerMethod(TrainingMethod method) {
        methodsById.put(method.getId(), method);
        methodsBySkill.computeIfAbsent(method.getSkill(), k -> new ArrayList<>())
                .add(method);
    }

    // ========================================================================
    // Query Methods
    // ========================================================================

    /**
     * Get a training method by its ID.
     *
     * @param id the method ID
     * @return optional containing the method, or empty if not found
     */
    public Optional<TrainingMethod> getMethodById(String id) {
        return Optional.ofNullable(methodsById.get(id));
    }

    /**
     * Get all training methods for a skill.
     *
     * @param skill the skill
     * @return list of methods (may be empty)
     */
    public List<TrainingMethod> getMethodsForSkill(Skill skill) {
        return methodsBySkill.getOrDefault(skill, Collections.emptyList());
    }

    /**
     * Get training methods for a skill valid at a specific level.
     *
     * @param skill the skill
     * @param level the player's current level
     * @return list of valid methods
     */
    public List<TrainingMethod> getMethodsForSkill(Skill skill, int level) {
        return getMethodsForSkill(skill).stream()
                .filter(m -> m.isValidForLevel(level))
                .collect(Collectors.toList());
    }

    /**
     * Get training methods for a skill, filtered by ironman viability.
     *
     * @param skill      the skill
     * @param level      the player's current level
     * @param ironman    if true, only return ironman-viable methods
     * @return list of valid methods
     */
    public List<TrainingMethod> getMethodsForSkill(Skill skill, int level, boolean ironman) {
        return getMethodsForSkill(skill, level).stream()
                .filter(m -> !ironman || m.isIronmanViable())
                .collect(Collectors.toList());
    }

    /**
     * Get training methods for a skill, filtered by membership.
     *
     * @param skill   the skill
     * @param level   the player's current level
     * @param members if true, include members methods; if false, F2P only
     * @return list of valid methods
     */
    public List<TrainingMethod> getMethodsForSkill(Skill skill, int level, boolean ironman, boolean members) {
        return getMethodsForSkill(skill, level, ironman).stream()
                .filter(m -> members || !m.requiresMembership())
                .collect(Collectors.toList());
    }

    /**
     * Get the best (highest XP/hr) method for a skill at a given level.
     * Uses the best location for each method when comparing.
     *
     * @param skill the skill
     * @param level the player's current level
     * @return optional containing the best method, or empty if none available
     */
    public Optional<TrainingMethod> getBestMethod(Skill skill, int level) {
        return getMethodsForSkill(skill, level).stream()
                .max(Comparator.comparingDouble(m -> m.getXpPerHour(level)));
    }

    /**
     * Get the best method for a skill, filtered by ironman viability.
     *
     * @param skill   the skill
     * @param level   the player's current level
     * @param ironman if true, only consider ironman-viable methods
     * @return optional containing the best method
     */
    public Optional<TrainingMethod> getBestMethod(Skill skill, int level, boolean ironman) {
        return getMethodsForSkill(skill, level, ironman).stream()
                .max(Comparator.comparingDouble(m -> m.getXpPerHour(level)));
    }

    /**
     * Get all gathering methods (mining, woodcutting, fishing).
     *
     * @return list of gathering methods
     */
    public List<TrainingMethod> getGatheringMethods() {
        return methodsById.values().stream()
                .filter(TrainingMethod::isGatheringMethod)
                .collect(Collectors.toList());
    }

    /**
     * Get all processing methods (fletching, crafting, etc.).
     *
     * @return list of processing methods
     */
    public List<TrainingMethod> getProcessingMethods() {
        return methodsById.values().stream()
                .filter(TrainingMethod::isProcessingMethod)
                .collect(Collectors.toList());
    }

    /**
     * Get all methods of a specific type.
     *
     * @param type the method type
     * @return list of matching methods
     */
    public List<TrainingMethod> getMethodsByType(MethodType type) {
        return methodsById.values().stream()
                .filter(m -> m.getMethodType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Get all power training methods (drop when full).
     *
     * @return list of power training methods
     */
    public List<TrainingMethod> getPowerMethods() {
        return methodsById.values().stream()
                .filter(TrainingMethod::isDropWhenFull)
                .collect(Collectors.toList());
    }

    /**
     * Get all banking methods.
     *
     * @return list of banking methods
     */
    public List<TrainingMethod> getBankingMethods() {
        return methodsById.values().stream()
                .filter(TrainingMethod::requiresBanking)
                .collect(Collectors.toList());
    }

    /**
     * Get the total number of loaded methods.
     *
     * @return method count
     */
    public int getMethodCount() {
        return methodsById.size();
    }

    /**
     * Get all method IDs.
     *
     * @return set of method IDs
     */
    public Set<String> getAllMethodIds() {
        return Collections.unmodifiableSet(methodsById.keySet());
    }

    /**
     * Check if a method exists.
     *
     * @param id the method ID
     * @return true if method exists
     */
    public boolean hasMethod(String id) {
        return methodsById.containsKey(id);
    }
}
