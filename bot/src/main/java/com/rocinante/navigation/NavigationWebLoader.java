package com.rocinante.navigation;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Loader for the navigation web that supports merging multiple JSON sources.
 * Loads from:
 * - data/web.json (base navigation graph)
 * - data/regions/*.json (region-specific nodes and edges)
 * - data/transports/*.json (transport system definitions)
 *
 * <p>This loader allows the navigation data to be split into manageable,
 * domain-specific files while presenting a unified NavigationWeb at runtime.
 */
@Slf4j
public class NavigationWebLoader {

    private static final String BASE_WEB_PATH = "/data/web.json";
    private static final String REGIONS_PATH = "/data/regions/";
    private static final String TRANSPORTS_PATH = "/data/transports/";

    private static final Gson GSON = createGson();

    // ========================================================================
    // Public Loading Methods
    // ========================================================================

    /**
     * Load the complete navigation web from all sources.
     * Merges base web.json with region files and transport definitions.
     *
     * @return the merged NavigationWeb
     * @throws IOException if loading fails
     */
    public static NavigationWeb loadComplete() throws IOException {
        MergedWebData merged = new MergedWebData();

        // Load base web.json
        try {
            WebData baseData = loadWebDataFromResource(BASE_WEB_PATH);
            if (baseData != null) {
                merged.merge(baseData);
                log.info("Loaded base web.json: {} nodes, {} edges",
                        baseData.nodes != null ? baseData.nodes.size() : 0,
                        baseData.edges != null ? baseData.edges.size() : 0);
            }
        } catch (IOException e) {
            log.warn("Base web.json not found or failed to load: {}", e.getMessage());
        }

        // Load region files
        loadRegionFiles(merged);

        // Load transport files (for generating transport edges)
        loadTransportFiles(merged);

        return merged.build();
    }

    /**
     * Load only the base web.json without region/transport files.
     *
     * @return the base NavigationWeb
     * @throws IOException if loading fails
     */
    public static NavigationWeb loadBaseOnly() throws IOException {
        return NavigationWeb.loadFromResources(BASE_WEB_PATH);
    }

    /**
     * Load navigation web from a specific file path.
     *
     * @param path the file path
     * @return the loaded NavigationWeb
     * @throws IOException if loading fails
     */
    public static NavigationWeb loadFromPath(Path path) throws IOException {
        return NavigationWeb.loadFromFile(path);
    }

    // ========================================================================
    // Region Loading
    // ========================================================================

    private static void loadRegionFiles(MergedWebData merged) {
        String[] regionFiles = {
                "tutorial_island.json",
                "misthalin.json",
                "asgarnia.json",
                "kandarin.json",
                "morytania.json",
                "kourend.json",
                "varlamore.json",
                "fremennik.json",
                "karamja.json",
                "kharidian.json",
                "wilderness.json",
                "plane_transitions.json"  // Transition nodes for stairs/ladders
        };

        for (String filename : regionFiles) {
            try {
                String resourcePath = REGIONS_PATH + filename;
                RegionData regionData = loadRegionDataFromResource(resourcePath);
                if (regionData != null) {
                    merged.mergeRegion(regionData);
                    log.debug("Loaded region {}: {} nodes, {} edges",
                            filename,
                            regionData.nodes != null ? regionData.nodes.size() : 0,
                            regionData.edges != null ? regionData.edges.size() : 0);
                }
            } catch (Exception e) {
                log.warn("Failed to load region file {}: {}", filename, e.getMessage());
            }
        }
    }

    // ========================================================================
    // Transport Loading - Generic Loader
    // ========================================================================

    /**
     * Transport files to load. All use standard nodes/edges format.
     */
    private static final String[] TRANSPORT_FILES = {
            "quetzals.json",
            "spirit_trees.json",
            "gnome_gliders.json",
            "canoes.json",
            "charter_ships.json",
            "balloons.json",
            "fairy_rings.json",
            "ships.json",
            "magic_carpets.json",
            "minecarts.json",
            "teleports.json",
            "levers.json",
    };

    private static void loadTransportFiles(MergedWebData merged) {
        // Load all transport files that use standard nodes/edges format
        for (String filename : TRANSPORT_FILES) {
            try {
                loadTransportFile(merged, filename);
            } catch (Exception e) {
                log.warn("Failed to load transport file {}: {}", filename, e.getMessage());
            }
        }

        // Load grouping (minigame) teleports - these come from enum, not JSON
        try {
            loadGroupingTeleports(merged);
        } catch (Exception e) {
            log.warn("Failed to load grouping teleports: {}", e.getMessage());
        }

        // Load home teleport edges - these come from enum, not JSON
        try {
            loadHomeTeleportEdges(merged);
        } catch (Exception e) {
            log.warn("Failed to load home teleport edges: {}", e.getMessage());
        }
    }

    /**
     * Load a transport file using the standard nodes/edges format.
     * Transport files use the exact same format as web.json and region files.
     */
    private static void loadTransportFile(MergedWebData merged, String filename) throws IOException {
        String resourcePath = TRANSPORTS_PATH + filename;
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.debug("Transport file not found: {}", resourcePath);
                return;
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                WebData data = GSON.fromJson(reader, WebData.class);
                if (data != null) {
                    int nodeCount = data.nodes != null ? data.nodes.size() : 0;
                    int edgeCount = data.edges != null ? data.edges.size() : 0;
                    merged.merge(data);
                    log.info("Loaded transport file {}: {} nodes, {} edges", filename, nodeCount, edgeCount);
                }
            }
        }
    }

    /**
     * Load grouping (minigame) teleport nodes and edges from GroupingTeleport enum.
     * Creates nodes at each destination and edges from a virtual "any_location" source.
     */
    private static void loadGroupingTeleports(MergedWebData merged) {
        int nodesCreated = 0;
        int edgesCreated = 0;

        for (GroupingTeleport teleport : GroupingTeleport.values()) {
            // Create a node at the destination (if one doesn't already exist)
            String nodeId = "grouping_" + teleport.getEdgeId();
            WorldPoint dest = teleport.getDestination();
            
            Map<String, String> nodeMetadata = new HashMap<>();
            nodeMetadata.put("teleport_type", "grouping");
            nodeMetadata.put("teleport_id", teleport.getEdgeId());
            nodeMetadata.put("minigame", teleport.getDisplayName());
            if (teleport.requiresBossCount()) {
                nodeMetadata.put("required_boss_count", String.valueOf(teleport.getRequiredBossCount()));
            }

            WebNode node = WebNode.builder()
                    .id(nodeId)
                    .name(teleport.getDisplayName() + " (Grouping Teleport)")
                    .x(dest.getX())
                    .y(dest.getY())
                    .plane(dest.getPlane())
                    .type(WebNodeType.TELEPORT)
                    .tags(buildTags(null, "grouping_teleport", "minigame", "free_teleport"))
                    .metadata(nodeMetadata)
                    .build();
            merged.addNode(node);
            nodesCreated++;

            // Create requirements list
            List<EdgeRequirement> reqs = new ArrayList<>();
            
            // Quest requirement
            if (teleport.requiresQuest()) {
                String questName = teleport.getRequiredQuest().getName();
                // Some teleports only need quest started (not completed)
                if (teleport == GroupingTeleport.BLAST_FURNACE || teleport == GroupingTeleport.RAT_PITS) {
                    reqs.add(EdgeRequirement.builder()
                            .type(EdgeRequirementType.QUEST)
                            .identifier(teleport.getRequiredQuest().name())
                            .questState("STARTED")
                            .build());
                } else {
                    reqs.add(EdgeRequirement.quest(questName));
                }
            }
            
            // Combat level requirement
            if (teleport.requiresCombatLevel()) {
                reqs.add(EdgeRequirement.combatLevel(teleport.getRequiredCombatLevel()));
            }
            
            // Skill level requirement
            if (teleport.requiresSkillLevel()) {
                reqs.add(EdgeRequirement.skill(
                        teleport.getRequiredSkill().name(),
                        teleport.getRequiredSkillLevel()));
            }
            
            // Favour requirement
            if (teleport.requiresFavour()) {
                reqs.add(EdgeRequirement.favour(
                        teleport.getRequiredFavourHouse(),
                        teleport.getRequiredFavourPercent()));
            }

            // Create edge from "any_location" to this teleport destination
            // This edge is special - it represents teleporting from anywhere
            // The pathfinding system handles this as a "from anywhere" edge
            Map<String, String> edgeMetadata = new HashMap<>();
            edgeMetadata.put("teleport_type", "grouping");
            edgeMetadata.put("teleport_id", teleport.getEdgeId());
            edgeMetadata.put("cooldown_minutes", "20");
            if (teleport.requiresBossCount()) {
                edgeMetadata.put("required_boss_count", String.valueOf(teleport.getRequiredBossCount()));
            }

            WebEdge edge = WebEdge.builder()
                    .from("any_location")
                    .to(nodeId)
                    .type(WebEdgeType.FREE_TELEPORT)
                    .costTicks(20) // Grouping teleport takes ~12 seconds = 20 ticks
                    .bidirectional(false)
                    .requirements(reqs)
                    .metadata(edgeMetadata)
                    .build();
            merged.addEdge(edge);
            edgesCreated++;
        }

        log.info("Loaded {} grouping teleport destinations, {} edges", nodesCreated, edgesCreated);
    }

    /**
     * Load home teleport edges for each respawn point.
     * Home teleport destination depends on player's active respawn point.
     */
    private static void loadHomeTeleportEdges(MergedWebData merged) {
        int edgesCreated = 0;

        for (RespawnPoint respawn : RespawnPoint.values()) {
            WorldPoint dest = respawn.getDestination();
            
            // Check if a node exists near this destination, otherwise create one
            String nodeId = respawn.getEdgeId();
            
            // Create a node if it doesn't already exist
            WebNode node = WebNode.builder()
                    .id(nodeId)
                    .name(respawn.getDisplayName() + " Spawn")
                    .x(dest.getX())
                    .y(dest.getY())
                    .plane(dest.getPlane())
                    .type(WebNodeType.TELEPORT)
                    .tags(buildTags(null, "home_teleport", "spawn_point", "free_teleport"))
                    .metadata(Map.of(
                            "teleport_type", "home",
                            "respawn_point", respawn.name()
                    ))
                    .build();
            merged.addNode(node);

            // Create requirements - none for Lumbridge, quest for others
            List<EdgeRequirement> reqs = new ArrayList<>();
            if (respawn.requiresQuest()) {
                // Map unlock requirement to quest if possible
                String requirement = respawn.getUnlockRequirement();
                if (requirement != null) {
                    // Try to map to a quest enum
                    try {
                        String questEnumName = requirement.toUpperCase()
                                .replace(" ", "_")
                                .replace("'", "")
                                .replace("-", "_");
                        reqs.add(EdgeRequirement.quest(requirement));
                    } catch (Exception e) {
                        // Not a direct quest mapping - respawn might use other unlock methods
                    }
                }
            }

            // Create edge from "any_location" to this respawn point
            // Only one of these edges will be valid at a time (based on active respawn)
            WebEdge edge = WebEdge.builder()
                    .from("any_location")
                    .to(nodeId)
                    .type(WebEdgeType.FREE_TELEPORT)
                    .costTicks(RespawnPoint.HOME_TELEPORT_ANIMATION_TICKS) // ~16 seconds
                    .bidirectional(false)
                    .requirements(reqs)
                    .metadata(Map.of(
                            "teleport_type", "home",
                            "respawn_point", respawn.name(),
                            "cooldown_minutes", String.valueOf(RespawnPoint.HOME_TELEPORT_COOLDOWN_MINUTES)
                    ))
                    .build();
            merged.addEdge(edge);
            edgesCreated++;
        }

        log.info("Loaded {} home teleport edges", edgesCreated);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private static WebData loadWebDataFromResource(String resourcePath) throws IOException {
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, WebData.class);
            }
        }
    }

    private static RegionData loadRegionDataFromResource(String resourcePath) throws IOException {
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, RegionData.class);
            }
        }
    }

    private static List<String> buildTags(List<String> existingTags, String... additionalTags) {
        Set<String> tags = new LinkedHashSet<>();
        if (existingTags != null) {
            tags.addAll(existingTags);
        }
        tags.addAll(Arrays.asList(additionalTags));
        return new ArrayList<>(tags);
    }

    private static Gson createGson() {
        return new GsonBuilder()
                // Use snake_case in JSON, camelCase in Java
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerTypeAdapter(WebNodeType.class, new EnumIgnoreCaseDeserializer<>(WebNodeType.class))
                .registerTypeAdapter(WebEdgeType.class, new EnumIgnoreCaseDeserializer<>(WebEdgeType.class))
                .registerTypeAdapter(EdgeRequirementType.class, new EnumIgnoreCaseDeserializer<>(EdgeRequirementType.class))
                .create();
    }

    // ========================================================================
    // Data Classes for JSON Parsing
    // ========================================================================

    private static class WebData {
        String version;
        List<WebNode> nodes;
        List<WebEdge> edges;
        List<WebRegion> regions;
    }

    private static class RegionData {
        RegionInfo region;
        List<WebNode> nodes;
        List<WebEdge> edges;
    }

    private static class RegionInfo {
        String id;
        String name;
    }

    // ========================================================================
    // Merged Web Data Builder
    // ========================================================================

    private static class MergedWebData {
        private String version = "1.0";
        private final Map<String, WebNode> nodes = new HashMap<>();
        private final List<WebEdge> edges = new ArrayList<>();
        private final Map<String, WebRegion> regions = new HashMap<>();

        void merge(WebData data) {
            if (data.version != null) {
                this.version = data.version;
            }
            if (data.nodes != null) {
                for (WebNode node : data.nodes) {
                    nodes.put(node.getId(), node);
                }
            }
            if (data.edges != null) {
                edges.addAll(data.edges);
            }
            if (data.regions != null) {
                for (WebRegion region : data.regions) {
                    regions.put(region.getId(), region);
                }
            }
        }

        void mergeRegion(RegionData regionData) {
            if (regionData.nodes != null) {
                for (WebNode node : regionData.nodes) {
                    nodes.put(node.getId(), node);
                }
            }
            if (regionData.edges != null) {
                edges.addAll(regionData.edges);
            }
            if (regionData.region != null) {
                // Create a WebRegion from the region info
                List<String> nodeIds = regionData.nodes != null ?
                        regionData.nodes.stream().map(WebNode::getId).toList() :
                        List.of();

                WebRegion region = WebRegion.builder()
                        .id(regionData.region.id)
                        .name(regionData.region.name)
                        .nodes(nodeIds)
                        .build();
                regions.put(region.getId(), region);
            }
        }

        void addNode(WebNode node) {
            nodes.put(node.getId(), node);
        }

        void addEdge(WebEdge edge) {
            edges.add(edge);
        }

        NavigationWeb build() {
            // Expand bidirectional edges
            List<WebEdge> expandedEdges = new ArrayList<>();
            for (WebEdge edge : edges) {
                expandedEdges.add(edge);
                if (edge.isBidirectional()) {
                    WebEdge reverse = edge.toBuilder()
                            .from(edge.getTo())
                            .to(edge.getFrom())
                            .build();
                    expandedEdges.add(reverse);
                }
            }

            // Build edge index
            Map<String, List<WebEdge>> edgesBySource = new HashMap<>();
            for (WebEdge edge : expandedEdges) {
                edgesBySource.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
            }

            log.info("NavigationWebLoader: Merged {} nodes, {} edges ({} expanded), {} regions",
                    nodes.size(), edges.size(), expandedEdges.size(), regions.size());

            // Use reflection or factory method to create NavigationWeb
            // For now, we'll create a JSON string and parse it
            StringBuilder json = new StringBuilder();
            json.append("{\"version\":\"").append(version).append("\",");
            json.append("\"nodes\":[");
            boolean first = true;
            for (WebNode node : nodes.values()) {
                if (!first) json.append(",");
                json.append(GSON.toJson(node));
                first = false;
            }
            json.append("],\"edges\":[");
            first = true;
            for (WebEdge edge : edges) {
                if (!first) json.append(",");
                json.append(GSON.toJson(edge));
                first = false;
            }
            json.append("],\"regions\":[");
            first = true;
            for (WebRegion region : regions.values()) {
                if (!first) json.append(",");
                json.append(GSON.toJson(region));
                first = false;
            }
            json.append("]}");

            return NavigationWeb.parse(json.toString());
        }
    }

    // ========================================================================
    // Enum Deserializer
    // ========================================================================

    private static class EnumIgnoreCaseDeserializer<T extends Enum<T>> implements JsonDeserializer<T> {
        private final Class<T> enumClass;

        EnumIgnoreCaseDeserializer(Class<T> enumClass) {
            this.enumClass = enumClass;
        }

        @Override
        public T deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            String value = json.getAsString();
            for (T constant : enumClass.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(value)) {
                    return constant;
                }
            }
            throw new JsonParseException("Unknown enum value: " + value + " for " + enumClass.getSimpleName());
        }
    }
}

