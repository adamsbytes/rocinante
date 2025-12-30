package com.rocinante.tasks.impl.skills.slayer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rocinante.data.GsonFactory;
import com.rocinante.data.JsonResourceLoader;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Loads and caches slayer creature data from slayer_locations.json.
 *
 * Per REQUIREMENTS.md Section 11.2, provides:
 * <ul>
 *   <li>Creature data with locations, requirements, and equipment</li>
 *   <li>Lookup by task name (case-insensitive)</li>
 *   <li>Special item metadata (finish-off items, protective equipment)</li>
 * </ul>
 *
 * Uses {@link JsonResourceLoader} for consistent loading with retry logic.
 */
@Slf4j
@Singleton
public class SlayerDataLoader {

    private static final String RESOURCE_PATH = "/data/slayer_locations.json";

    private final Gson gson;

    // Cached data
    private Map<String, SlayerCreature> creaturesByName;
    private Map<Integer, SlayerCreature> creaturesByNpcId;
    private Map<String, SpecialItemInfo> finishOffItems;
    private Map<Integer, ProtectiveEquipmentInfo> protectiveEquipment;
    private boolean loaded = false;

    @Inject
    public SlayerDataLoader() {
        this.gson = GsonFactory.create();
    }

    /**
     * Load slayer data from JSON resource.
     * Called lazily on first access or can be called explicitly.
     */
    public synchronized void load() {
        if (loaded) {
            return;
        }

        log.info("Loading slayer data from {}", RESOURCE_PATH);

        try {
            JsonObject root = JsonResourceLoader.load(gson, RESOURCE_PATH);
            parseCreatures(root);
            parseSpecialItems(root);
            parseProtectiveEquipment(root);
            loaded = true;
            log.info("Loaded {} slayer creatures with {} locations",
                    creaturesByName.size(),
                    creaturesByName.values().stream()
                            .mapToInt(c -> c.getLocations().size())
                            .sum());
        } catch (JsonResourceLoader.JsonLoadException e) {
            log.error("Failed to load slayer data", e);
            // Initialize empty maps to prevent NPEs
            creaturesByName = Collections.emptyMap();
            creaturesByNpcId = Collections.emptyMap();
            finishOffItems = Collections.emptyMap();
            protectiveEquipment = Collections.emptyMap();
        }
    }

    /**
     * Force reload of slayer data.
     */
    public synchronized void reload() {
        loaded = false;
        load();
    }

    // ========================================================================
    // Creature Lookups
    // ========================================================================

    /**
     * Get a creature by task name (case-insensitive).
     *
     * @param taskName the task name from SlayerPluginService
     * @return the creature data, or null if not found
     */
    @Nullable
    public SlayerCreature getCreature(String taskName) {
        ensureLoaded();
        if (taskName == null) {
            return null;
        }
        return creaturesByName.get(taskName.toLowerCase());
    }

    /**
     * Get a creature by NPC ID.
     *
     * @param npcId the NPC ID
     * @return the creature data, or null if not found
     */
    @Nullable
    public SlayerCreature getCreatureByNpcId(int npcId) {
        ensureLoaded();
        return creaturesByNpcId.get(npcId);
    }

    /**
     * Get all loaded creatures.
     *
     * @return unmodifiable collection of creatures
     */
    public Collection<SlayerCreature> getAllCreatures() {
        ensureLoaded();
        return Collections.unmodifiableCollection(creaturesByName.values());
    }

    /**
     * Check if a creature requires special finish-off item.
     *
     * @param taskName the task name
     * @return true if finish-off item required
     */
    public boolean requiresFinishOffItem(String taskName) {
        SlayerCreature creature = getCreature(taskName);
        return creature != null && creature.requiresFinishOffItem();
    }

    /**
     * Get the finish-off item info for a creature.
     *
     * @param taskName the task name
     * @return special item info, or null if not applicable
     */
    @Nullable
    public SpecialItemInfo getFinishOffItemInfo(String taskName) {
        ensureLoaded();
        if (taskName == null) {
            return null;
        }
        return finishOffItems.values().stream()
                .filter(info -> info.creatures.stream()
                        .anyMatch(c -> c.equalsIgnoreCase(taskName)))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get protective equipment info for an item ID.
     *
     * @param itemId the equipment item ID
     * @return equipment info, or null if not protective
     */
    @Nullable
    public ProtectiveEquipmentInfo getProtectiveEquipmentInfo(int itemId) {
        ensureLoaded();
        return protectiveEquipment.get(itemId);
    }

    /**
     * Get all protective equipment item IDs for a creature.
     *
     * @param taskName the task name
     * @return set of valid protective equipment IDs
     */
    public Set<Integer> getProtectiveEquipmentIds(String taskName) {
        ensureLoaded();
        if (taskName == null) {
            return Collections.emptySet();
        }
        
        Set<Integer> result = new HashSet<>();
        for (Map.Entry<Integer, ProtectiveEquipmentInfo> entry : protectiveEquipment.entrySet()) {
            if (entry.getValue().creatures.stream()
                    .anyMatch(c -> c.equalsIgnoreCase(taskName))) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    // ========================================================================
    // Parsing
    // ========================================================================

    private void parseCreatures(JsonObject root) {
        creaturesByName = new HashMap<>();
        creaturesByNpcId = new HashMap<>();

        JsonArray creatures = root.getAsJsonArray("creatures");
        if (creatures == null) {
            log.warn("No 'creatures' array in slayer data");
            return;
        }

        for (JsonElement elem : creatures) {
            try {
                SlayerCreature creature = parseCreature(elem.getAsJsonObject());
                creaturesByName.put(creature.getName().toLowerCase(), creature);
                
                // Index by NPC IDs
                for (int npcId : creature.getNpcIds()) {
                    creaturesByNpcId.put(npcId, creature);
                }
                for (int npcId : creature.getSuperiorNpcIds()) {
                    creaturesByNpcId.put(npcId, creature);
                }
            } catch (Exception e) {
                log.warn("Failed to parse creature: {}", e.getMessage());
            }
        }
    }

    private SlayerCreature parseCreature(JsonObject obj) {
        SlayerCreature.SlayerCreatureBuilder builder = SlayerCreature.builder()
                .name(obj.get("name").getAsString())
                .slayerLevelRequired(getIntOrDefault(obj, "slayerLevel", 1))
                .npcIds(parseIntSet(obj.getAsJsonArray("npcIds")))
                .hasSuperior(getBoolOrDefault(obj, "hasSuperior", false))
                .superiorNpcIds(parseIntSet(obj.getAsJsonArray("superiorNpcIds")))
                .superiorName(getStringOrNull(obj, "superiorName"))
                .requiredEquipmentIds(parseIntSet(obj.getAsJsonArray("requiredEquipment")))
                .finishOffItemIds(parseIntSet(obj.getAsJsonArray("finishOffItems")))
                .finishOffThreshold(getIntOrDefault(obj, "finishOffThreshold", 0))
                .validWeaponIds(parseIntSet(obj.getAsJsonArray("validWeapons")))
                .broadAmmoValid(getBoolOrDefault(obj, "broadAmmoValid", false))
                .magicDartValid(getBoolOrDefault(obj, "magicDartValid", false))
                .weakness(getStringOrNull(obj, "weakness"))
                .attackStyles(parseStringSet(obj.getAsJsonArray("attackStyles")))
                .canPoison(getBoolOrDefault(obj, "canPoison", false))
                .canVenom(getBoolOrDefault(obj, "canVenom", false))
                .combatLevelMin(getIntOrDefault(obj, "combatLevelMin", 1))
                .combatLevelMax(getIntOrDefault(obj, "combatLevelMax", 500))
                .baseSlayerXp(getDoubleOrDefault(obj, "baseSlayerXp", 0))
                .taskSizeMin(getIntOrDefault(obj, "taskSizeMin", 0))
                .taskSizeMax(getIntOrDefault(obj, "taskSizeMax", 0))
                .taskWeight(getIntOrDefault(obj, "taskWeight", 0))
                .notes(getStringOrNull(obj, "notes"));

        // Parse locations
        JsonArray locationsArray = obj.getAsJsonArray("locations");
        if (locationsArray != null) {
            for (JsonElement locElem : locationsArray) {
                builder.location(parseLocation(locElem.getAsJsonObject()));
            }
        }

        return builder.build();
    }

    private SlayerLocation parseLocation(JsonObject obj) {
        JsonObject centerObj = obj.getAsJsonObject("center");
        WorldPoint center = new WorldPoint(
                centerObj.get("x").getAsInt(),
                centerObj.get("y").getAsInt(),
                getIntOrDefault(centerObj, "plane", 0)
        );

        WorldPoint cannonSpot = null;
        JsonObject cannonObj = obj.getAsJsonObject("cannonSpot");
        if (cannonObj != null) {
            cannonSpot = new WorldPoint(
                    cannonObj.get("x").getAsInt(),
                    cannonObj.get("y").getAsInt(),
                    getIntOrDefault(cannonObj, "plane", 0)
            );
        }

        return SlayerLocation.builder()
                .name(obj.get("name").getAsString())
                .center(center)
                .radius(getIntOrDefault(obj, "radius", 15))
                .nearestBank(getStringOrNull(obj, "nearestBank"))
                .nearestTeleport(getStringOrNull(obj, "nearestTeleport"))
                .multiCombat(getBoolOrDefault(obj, "multiCombat", false))
                .hcimSafetyRating(getIntOrDefault(obj, "hcimSafetyRating", 5))
                .requiredItems(parseIntSet(obj.getAsJsonArray("requiredItems")))
                .recommendedItems(parseIntSet(obj.getAsJsonArray("recommendedItems")))
                .cannonSpot(cannonSpot)
                .konarRegion(getStringOrNull(obj, "konarRegion"))
                .requiredQuest(getStringOrNull(obj, "requiredQuest"))
                .slayerLevelRequired(getIntOrDefault(obj, "slayerLevelRequired", 1))
                .wilderness(getBoolOrDefault(obj, "wilderness", false))
                .monsterDensity(getIntOrDefault(obj, "monsterDensity", 5))
                .notes(getStringOrNull(obj, "notes"))
                .build();
    }

    private void parseSpecialItems(JsonObject root) {
        finishOffItems = new HashMap<>();

        JsonObject specialItems = root.getAsJsonObject("specialItems");
        if (specialItems == null) {
            return;
        }

        for (String key : specialItems.keySet()) {
            try {
                JsonObject itemObj = specialItems.getAsJsonObject(key);
                SpecialItemInfo info = new SpecialItemInfo(
                        itemObj.get("itemId").getAsInt(),
                        parseStringList(itemObj.getAsJsonArray("creatures")),
                        getIntOrDefault(itemObj, "threshold", 0),
                        getStringOrNull(itemObj, "description")
                );
                finishOffItems.put(key, info);
            } catch (Exception e) {
                log.warn("Failed to parse special item {}: {}", key, e.getMessage());
            }
        }
    }

    private void parseProtectiveEquipment(JsonObject root) {
        protectiveEquipment = new HashMap<>();

        JsonObject equipmentObj = root.getAsJsonObject("protectiveEquipment");
        if (equipmentObj == null) {
            return;
        }

        for (String key : equipmentObj.keySet()) {
            try {
                JsonObject itemObj = equipmentObj.getAsJsonObject(key);
                int itemId = itemObj.get("itemId").getAsInt();
                ProtectiveEquipmentInfo info = new ProtectiveEquipmentInfo(
                        itemId,
                        parseStringList(itemObj.getAsJsonArray("creatures")),
                        getStringOrNull(itemObj, "description")
                );
                protectiveEquipment.put(itemId, info);
            } catch (Exception e) {
                log.warn("Failed to parse protective equipment {}: {}", key, e.getMessage());
            }
        }
    }

    // ========================================================================
    // Parsing Helpers
    // ========================================================================

    private Set<Integer> parseIntSet(@Nullable JsonArray array) {
        if (array == null || array.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Integer> result = new HashSet<>();
        for (JsonElement elem : array) {
            result.add(elem.getAsInt());
        }
        return result;
    }

    private Set<String> parseStringSet(@Nullable JsonArray array) {
        if (array == null || array.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (JsonElement elem : array) {
            result.add(elem.getAsString());
        }
        return result;
    }

    private List<String> parseStringList(@Nullable JsonArray array) {
        if (array == null || array.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement elem : array) {
            result.add(elem.getAsString());
        }
        return result;
    }

    private int getIntOrDefault(JsonObject obj, String field, int defaultValue) {
        JsonElement elem = obj.get(field);
        return elem != null && !elem.isJsonNull() ? elem.getAsInt() : defaultValue;
    }

    private double getDoubleOrDefault(JsonObject obj, String field, double defaultValue) {
        JsonElement elem = obj.get(field);
        return elem != null && !elem.isJsonNull() ? elem.getAsDouble() : defaultValue;
    }

    private boolean getBoolOrDefault(JsonObject obj, String field, boolean defaultValue) {
        JsonElement elem = obj.get(field);
        return elem != null && !elem.isJsonNull() ? elem.getAsBoolean() : defaultValue;
    }

    @Nullable
    private String getStringOrNull(JsonObject obj, String field) {
        JsonElement elem = obj.get(field);
        return elem != null && !elem.isJsonNull() ? elem.getAsString() : null;
    }

    private void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    // ========================================================================
    // Data Classes for Special Items
    // ========================================================================

    /**
     * Information about a finish-off item (rock hammer, ice coolers, etc.).
     */
    public static class SpecialItemInfo {
        public final int itemId;
        public final List<String> creatures;
        public final int threshold;
        public final String description;

        public SpecialItemInfo(int itemId, List<String> creatures, int threshold, String description) {
            this.itemId = itemId;
            this.creatures = creatures;
            this.threshold = threshold;
            this.description = description;
        }
    }

    /**
     * Information about protective equipment.
     */
    public static class ProtectiveEquipmentInfo {
        public final int itemId;
        public final List<String> creatures;
        public final String description;

        public ProtectiveEquipmentInfo(int itemId, List<String> creatures, String description) {
            this.itemId = itemId;
            this.creatures = creatures;
            this.description = description;
        }
    }
}

