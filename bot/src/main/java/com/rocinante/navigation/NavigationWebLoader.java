package com.rocinante.navigation;

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
                "kharidian.json"
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
    // Transport Loading
    // ========================================================================

    private static void loadTransportFiles(MergedWebData merged) {
        // Load fairy ring locations and generate nodes/edges
        try {
            loadFairyRingTransports(merged);
        } catch (Exception e) {
            log.warn("Failed to load fairy ring transports: {}", e.getMessage());
        }

        // Load spirit tree locations
        try {
            loadSpiritTreeTransports(merged);
        } catch (Exception e) {
            log.warn("Failed to load spirit tree transports: {}", e.getMessage());
        }

        // Load gnome glider locations
        try {
            loadGnomeGliderTransports(merged);
        } catch (Exception e) {
            log.warn("Failed to load gnome glider transports: {}", e.getMessage());
        }

        // Load canoe stations
        try {
            loadCanoeTransports(merged);
        } catch (Exception e) {
            log.warn("Failed to load canoe transports: {}", e.getMessage());
        }

        // Load charter ship ports
        try {
            loadCharterShipTransports(merged);
        } catch (Exception e) {
            log.warn("Failed to load charter ship transports: {}", e.getMessage());
        }

        // Load quetzal locations
        try {
            loadQuetzalTransports(merged);
        } catch (Exception e) {
            log.warn("Failed to load quetzal transports: {}", e.getMessage());
        }

        // Load grouping (minigame) teleports
        try {
            loadGroupingTeleports(merged);
        } catch (Exception e) {
            log.warn("Failed to load grouping teleports: {}", e.getMessage());
        }

        // Load home teleport edges
        try {
            loadHomeTeleportEdges(merged);
        } catch (Exception e) {
            log.warn("Failed to load home teleport edges: {}", e.getMessage());
        }
    }

    private static void loadFairyRingTransports(MergedWebData merged) throws IOException {
        String resourcePath = TRANSPORTS_PATH + "fairy_rings.json";
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) return;
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                FairyRingData data = GSON.fromJson(reader, FairyRingData.class);
                if (data != null && data.locations != null) {
                    // Create nodes for each fairy ring
                    for (FairyRingLocation loc : data.locations) {
                        if (loc.x == null || loc.y == null) continue; // Skip unmapped locations

                        WebNode node = WebNode.builder()
                                .id("fairy_ring_" + loc.code.toLowerCase())
                                .name("Fairy Ring " + loc.code + " - " + loc.name)
                                .x(loc.x)
                                .y(loc.y)
                                .plane(loc.plane != null ? loc.plane : 0)
                                .type(WebNodeType.TRANSPORT)
                                .tags(buildTags(loc.tags, "fairy_ring", "members"))
                                .metadata(Map.of(
                                        "fairy_ring_code", loc.code,
                                        "travel_type", "fairy_ring"
                                ))
                                .build();
                        merged.addNode(node);
                    }

                    // Create edges between all fairy rings (through BKS - Zanaris hub)
                    // Each fairy ring connects to all others with a fixed cost
                    for (FairyRingLocation from : data.locations) {
                        if (from.x == null || from.y == null) continue;
                        for (FairyRingLocation to : data.locations) {
                            if (to.x == null || to.y == null) continue;
                            if (from.code.equals(to.code)) continue;

                            WebEdge edge = WebEdge.builder()
                                    .from("fairy_ring_" + from.code.toLowerCase())
                                    .to("fairy_ring_" + to.code.toLowerCase())
                                    .type(WebEdgeType.TRANSPORT)
                                    .costTicks(6) // Fairy ring teleport is ~6 ticks
                                    .bidirectional(false) // We create both directions explicitly
                                    .requirements(List.of(
                                            EdgeRequirement.quest("Fairytale II - Cure a Queen"),
                                            EdgeRequirement.item(772, 1, false) // Dramen staff
                                    ))
                                    .metadata(Map.of(
                                            "travel_type", "fairy_ring",
                                            "destination_code", to.code
                                    ))
                                    .build();
                            merged.addEdge(edge);
                        }
                    }
                    log.info("Loaded {} fairy ring locations", data.locations.size());
                }
            }
        }
    }

    private static void loadSpiritTreeTransports(MergedWebData merged) throws IOException {
        String resourcePath = TRANSPORTS_PATH + "spirit_trees.json";
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) return;
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                SpiritTreeData data = GSON.fromJson(reader, SpiritTreeData.class);
                if (data != null && data.locations != null) {
                    for (SpiritTreeLocation loc : data.locations) {
                        WebNode node = WebNode.builder()
                                .id(loc.id)
                                .name("Spirit Tree - " + loc.name)
                                .x(loc.x)
                                .y(loc.y)
                                .plane(loc.plane != null ? loc.plane : 0)
                                .type(WebNodeType.TRANSPORT)
                                .tags(buildTags(loc.tags, "spirit_tree", "members"))
                                .metadata(Map.of("travel_type", "spirit_tree"))
                                .build();
                        merged.addNode(node);
                    }

                    // Create edges between all spirit trees
                    for (SpiritTreeLocation from : data.locations) {
                        for (SpiritTreeLocation to : data.locations) {
                            if (from.id.equals(to.id)) continue;

                            List<EdgeRequirement> reqs = new ArrayList<>();
                            reqs.add(EdgeRequirement.quest("Tree Gnome Village"));

                            if (to.requirements != null && to.requirements.quest != null) {
                                reqs.add(EdgeRequirement.quest(to.requirements.quest));
                            }

                            WebEdge edge = WebEdge.builder()
                                    .from(from.id)
                                    .to(to.id)
                                    .type(WebEdgeType.TRANSPORT)
                                    .costTicks(5)
                                    .bidirectional(false)
                                    .requirements(reqs)
                                    .metadata(Map.of("travel_type", "spirit_tree"))
                                    .build();
                            merged.addEdge(edge);
                        }
                    }
                    log.info("Loaded {} spirit tree locations", data.locations.size());
                }
            }
        }
    }

    private static void loadGnomeGliderTransports(MergedWebData merged) throws IOException {
        String resourcePath = TRANSPORTS_PATH + "gnome_gliders.json";
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) return;
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                GnomeGliderData data = GSON.fromJson(reader, GnomeGliderData.class);
                if (data != null && data.locations != null) {
                    for (GnomeGliderLocation loc : data.locations) {
                        WebNode node = WebNode.builder()
                                .id(loc.id)
                                .name("Gnome Glider - " + loc.name)
                                .x(loc.x)
                                .y(loc.y)
                                .plane(0)
                                .type(WebNodeType.TRANSPORT)
                                .tags(buildTags(loc.tags, "gnome_glider", "members"))
                                .metadata(Map.of(
                                        "travel_type", "gnome_glider",
                                        "npc_name", loc.npcName != null ? loc.npcName : ""
                                ))
                                .build();
                        merged.addNode(node);
                    }

                    // Create edges between all gliders
                    for (GnomeGliderLocation from : data.locations) {
                        for (GnomeGliderLocation to : data.locations) {
                            if (from.id.equals(to.id)) continue;

                            List<EdgeRequirement> reqs = new ArrayList<>();
                            if (from.requirements != null && from.requirements.quest != null) {
                                reqs.add(EdgeRequirement.quest(from.requirements.quest));
                            }
                            if (to.requirements != null && to.requirements.quest != null) {
                                reqs.add(EdgeRequirement.quest(to.requirements.quest));
                            }

                            WebEdge edge = WebEdge.builder()
                                    .from(from.id)
                                    .to(to.id)
                                    .type(WebEdgeType.TRANSPORT)
                                    .costTicks(8)
                                    .bidirectional(false)
                                    .requirements(reqs)
                                    .metadata(Map.of("travel_type", "gnome_glider"))
                                    .build();
                            merged.addEdge(edge);
                        }
                    }
                    log.info("Loaded {} gnome glider locations", data.locations.size());
                }
            }
        }
    }

    private static void loadCanoeTransports(MergedWebData merged) throws IOException {
        String resourcePath = TRANSPORTS_PATH + "canoes.json";
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) return;
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                CanoeData data = GSON.fromJson(reader, CanoeData.class);
                if (data != null && data.stations != null) {
                    for (CanoeStation station : data.stations) {
                        WebNode node = WebNode.builder()
                                .id(station.id)
                                .name("Canoe Station - " + station.name)
                                .x(station.x)
                                .y(station.y)
                                .plane(0)
                                .type(WebNodeType.TRANSPORT)
                                .tags(buildTags(station.tags, "canoe", "f2p"))
                                .metadata(Map.of(
                                        "travel_type", "canoe",
                                        "station_index", String.valueOf(station.stationIndex)
                                ))
                                .build();
                        merged.addNode(node);
                    }

                    // Create edges between canoe stations (based on station index distance)
                    for (CanoeStation from : data.stations) {
                        for (CanoeStation to : data.stations) {
                            if (from.id.equals(to.id)) continue;

                            int distance = Math.abs(from.stationIndex - to.stationIndex);
                            // Waka can go anywhere, other canoes limited by distance
                            int requiredWoodcutting = distance <= 1 ? 12 :
                                    distance <= 2 ? 27 :
                                            distance <= 3 ? 42 : 57;

                            WebEdge edge = WebEdge.builder()
                                    .from(from.id)
                                    .to(to.id)
                                    .type(WebEdgeType.TRANSPORT)
                                    .costTicks(10)
                                    .bidirectional(false)
                                    .requirements(List.of(
                                            EdgeRequirement.skill("Woodcutting", requiredWoodcutting)
                                    ))
                                    .metadata(Map.of(
                                            "travel_type", "canoe",
                                            "min_woodcutting", String.valueOf(requiredWoodcutting)
                                    ))
                                    .build();
                            merged.addEdge(edge);
                        }
                    }
                    log.info("Loaded {} canoe stations", data.stations.size());
                }
            }
        }
    }

    private static void loadCharterShipTransports(MergedWebData merged) throws IOException {
        String resourcePath = TRANSPORTS_PATH + "charter_ships.json";
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) return;
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                CharterShipData data = GSON.fromJson(reader, CharterShipData.class);
                if (data != null && data.ports != null) {
                    for (CharterShipPort port : data.ports) {
                        WebNode node = WebNode.builder()
                                .id(port.id)
                                .name("Charter Ship - " + port.name)
                                .x(port.x)
                                .y(port.y)
                                .plane(0)
                                .type(WebNodeType.TRANSPORT)
                                .tags(buildTags(port.tags, "charter_ship", "members"))
                                .metadata(Map.of("travel_type", "charter_ship"))
                                .build();
                        merged.addNode(node);
                    }

                    // Create edges between all charter ship ports (fully connected)
                    for (CharterShipPort from : data.ports) {
                        for (CharterShipPort to : data.ports) {
                            if (from.id.equals(to.id)) continue;

                            List<EdgeRequirement> reqs = new ArrayList<>();
                            if (to.requirements != null && to.requirements.quest != null) {
                                reqs.add(EdgeRequirement.quest(to.requirements.quest));
                            }

                            // Estimate cost based on distance
                            int distance = (int) Math.sqrt(
                                    Math.pow(to.x - from.x, 2) + Math.pow(to.y - from.y, 2)
                            );
                            int estimatedFare = Math.min(4100, Math.max(480, distance * 2));

                            WebEdge edge = WebEdge.builder()
                                    .from(from.id)
                                    .to(to.id)
                                    .type(WebEdgeType.TRANSPORT)
                                    .costTicks(15)
                                    .bidirectional(false)
                                    .requirements(reqs)
                                    .metadata(Map.of(
                                            "travel_type", "charter_ship",
                                            "estimated_fare", String.valueOf(estimatedFare)
                                    ))
                                    .build();
                            merged.addEdge(edge);
                        }
                    }
                    log.info("Loaded {} charter ship ports", data.ports.size());
                }
            }
        }
    }

    private static void loadQuetzalTransports(MergedWebData merged) throws IOException {
        String resourcePath = TRANSPORTS_PATH + "quetzals.json";
        try (InputStream is = NavigationWebLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) return;
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                QuetzalData data = GSON.fromJson(reader, QuetzalData.class);
                if (data != null) {
                    // Add Varrock entry point
                    if (data.varrockEntry != null) {
                        QuetzalLocation entry = data.varrockEntry;
                        WebNode node = WebNode.builder()
                                .id(entry.id)
                                .name("Quetzal - " + entry.name)
                                .x(entry.x)
                                .y(entry.y)
                                .plane(0)
                                .type(WebNodeType.TRANSPORT)
                                .tags(buildTags(entry.tags, "quetzal", "members"))
                                .metadata(Map.of("travel_type", "quetzal"))
                                .build();
                        merged.addNode(node);
                    }

                    // Add Varlamore network locations
                    if (data.varlamoreNetwork != null) {
                        for (QuetzalLocation loc : data.varlamoreNetwork) {
                            WebNode node = WebNode.builder()
                                    .id(loc.id)
                                    .name("Quetzal - " + loc.name)
                                    .x(loc.x)
                                    .y(loc.y)
                                    .plane(0)
                                    .type(WebNodeType.TRANSPORT)
                                    .tags(buildTags(loc.tags, "quetzal", "members"))
                                    .metadata(Map.of("travel_type", "quetzal"))
                                    .build();
                            merged.addNode(node);
                        }
                    }

                    // Create edges: Varrock <-> Varlamore entry, and within Varlamore network
                    EdgeRequirement questReq = EdgeRequirement.quest("Twilight's Promise");

                    // Varrock to Varlamore
                    if (data.varrockEntry != null && data.varlamoreExit != null) {
                        merged.addEdge(WebEdge.builder()
                                .from(data.varrockEntry.id)
                                .to(data.varlamoreExit.id)
                                .type(WebEdgeType.TRANSPORT)
                                .costTicks(10)
                                .bidirectional(true)
                                .requirements(List.of(questReq))
                                .metadata(Map.of("travel_type", "quetzal"))
                                .build());
                    }

                    // Within Varlamore network (fully connected)
                    if (data.varlamoreNetwork != null) {
                        for (QuetzalLocation from : data.varlamoreNetwork) {
                            for (QuetzalLocation to : data.varlamoreNetwork) {
                                if (from.id.equals(to.id)) continue;

                                merged.addEdge(WebEdge.builder()
                                        .from(from.id)
                                        .to(to.id)
                                        .type(WebEdgeType.TRANSPORT)
                                        .costTicks(8)
                                        .bidirectional(false)
                                        .requirements(List.of(questReq))
                                        .metadata(Map.of("travel_type", "quetzal"))
                                        .build());
                            }
                        }
                    }

                    int totalLocations = (data.varlamoreNetwork != null ? data.varlamoreNetwork.size() : 0) +
                            (data.varrockEntry != null ? 1 : 0);
                    log.info("Loaded {} quetzal locations", totalLocations);
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
            
            WebNode node = WebNode.builder()
                    .id(nodeId)
                    .name(teleport.getDisplayName() + " (Grouping Teleport)")
                    .x(dest.getX())
                    .y(dest.getY())
                    .plane(dest.getPlane())
                    .type(WebNodeType.TELEPORT)
                    .tags(buildTags(null, "grouping_teleport", "minigame", "free_teleport"))
                    .metadata(Map.of(
                            "teleport_type", "grouping",
                            "teleport_id", teleport.getEdgeId(),
                            "minigame", teleport.getDisplayName()
                    ))
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
            WebEdge edge = WebEdge.builder()
                    .from("any_location")
                    .to(nodeId)
                    .type(WebEdgeType.FREE_TELEPORT)
                    .costTicks(20) // Grouping teleport takes ~12 seconds = 20 ticks
                    .bidirectional(false)
                    .requirements(reqs)
                    .metadata(Map.of(
                            "teleport_type", "grouping",
                            "teleport_id", teleport.getEdgeId(),
                            "cooldown_minutes", "20"
                    ))
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

    private static class FairyRingData {
        List<FairyRingLocation> locations;
    }

    private static class FairyRingLocation {
        String code;
        String name;
        Integer x;
        Integer y;
        Integer plane;
        List<String> tags;
    }

    private static class SpiritTreeData {
        List<SpiritTreeLocation> locations;
    }

    private static class SpiritTreeLocation {
        String id;
        String name;
        int x;
        int y;
        Integer plane;
        List<String> tags;
        RequirementInfo requirements;
    }

    private static class GnomeGliderData {
        List<GnomeGliderLocation> locations;
    }

    private static class GnomeGliderLocation {
        String id;
        String name;
        int x;
        int y;
        String npcName;
        List<String> tags;
        RequirementInfo requirements;
    }

    private static class CanoeData {
        List<CanoeStation> stations;
    }

    private static class CanoeStation {
        String id;
        String name;
        int x;
        int y;
        List<String> tags;
        int stationIndex;
    }

    private static class CharterShipData {
        List<CharterShipPort> ports;
    }

    private static class CharterShipPort {
        String id;
        String name;
        int x;
        int y;
        List<String> tags;
        RequirementInfo requirements;
    }

    private static class QuetzalData {
        QuetzalLocation varrockEntry;
        QuetzalLocation varlamoreExit;
        List<QuetzalLocation> varlamoreNetwork;
    }

    private static class QuetzalLocation {
        String id;
        String name;
        int x;
        int y;
        List<String> tags;
    }

    private static class RequirementInfo {
        String quest;
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

