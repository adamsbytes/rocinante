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
 * // Get the best method for a level range
 * Optional<TrainingMethod> best = repo.getBestMethodForLevel(Skill.MINING, 15, 45);
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

            JsonObject root = JsonParser.parseReader(
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

        // Optional level cap
        if (obj.has("maxLevel")) {
            builder.maxLevel(obj.get("maxLevel").getAsInt());
        }

        // XP and efficiency
        builder.xpPerAction(obj.get("xpPerAction").getAsDouble());
        builder.actionsPerHour(obj.get("actionsPerHour").getAsInt());
        if (obj.has("gpPerHour")) {
            builder.gpPerHour(obj.get("gpPerHour").getAsInt());
        }

        // Location
        if (obj.has("locationId")) {
            builder.locationId(obj.get("locationId").getAsString());
        }
        if (obj.has("exactPosition")) {
            JsonObject pos = obj.getAsJsonObject("exactPosition");
            builder.exactPosition(new WorldPoint(
                    pos.get("x").getAsInt(),
                    pos.get("y").getAsInt(),
                    pos.has("plane") ? pos.get("plane").getAsInt() : 0
            ));
        }
        if (obj.has("bankLocationId")) {
            builder.bankLocationId(obj.get("bankLocationId").getAsString());
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
        if (obj.has("successAnimationId")) {
            builder.successAnimationId(obj.get("successAnimationId").getAsInt());
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

        // Requirements
        if (obj.has("requiredItemIds")) {
            builder.requiredItemIds(parseIntList(obj.getAsJsonArray("requiredItemIds")));
        }
        if (obj.has("ironmanViable")) {
            builder.ironmanViable(obj.get("ironmanViable").getAsBoolean());
        }
        if (obj.has("questRequirements")) {
            builder.questRequirements(parseIntList(obj.getAsJsonArray("questRequirements")));
        }

        // Ground item watching
        if (obj.has("watchedGroundItems")) {
            builder.watchedGroundItems(parseWatchedGroundItems(obj.getAsJsonArray("watchedGroundItems")));
        }

        // Agility configuration
        if (obj.has("courseId")) {
            builder.courseId(obj.get("courseId").getAsString());
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
     * Get the best (highest XP/hr) method for a skill at a given level.
     *
     * @param skill the skill
     * @param level the player's current level
     * @return optional containing the best method, or empty if none available
     */
    public Optional<TrainingMethod> getBestMethod(Skill skill, int level) {
        return getMethodsForSkill(skill, level).stream()
                .max(Comparator.comparingDouble(TrainingMethod::getXpPerHour));
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
                .max(Comparator.comparingDouble(TrainingMethod::getXpPerHour));
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

