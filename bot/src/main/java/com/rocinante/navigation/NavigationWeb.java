package com.rocinante.navigation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Container for the navigation web data.
 * Per REQUIREMENTS.md Section 7.2.1.
 *
 * <p>Loads and manages the navigation graph from web.json,
 * providing lookup methods for nodes, edges, and regions.
 */
@Slf4j
@Getter
public class NavigationWeb {

    /**
     * Default resource path for web.json.
     */
    private static final String DEFAULT_RESOURCE_PATH = "/data/web.json";

    /**
     * Schema version.
     */
    private final String version;

    /**
     * All nodes indexed by ID.
     */
    private final Map<String, WebNode> nodes;

    /**
     * All edges (including reverse edges for bidirectional).
     */
    private final List<WebEdge> edges;

    /**
     * Edges indexed by source node ID for fast lookup.
     */
    private final Map<String, List<WebEdge>> edgesBySource;

    /**
     * All regions indexed by ID.
     */
    private final Map<String, WebRegion> regions;

    /**
     * Gson instance for parsing.
     */
    private static final Gson GSON = createGson();

    // ========================================================================
    // Constructor
    // ========================================================================

    private NavigationWeb(String version,
                          Map<String, WebNode> nodes,
                          List<WebEdge> edges,
                          Map<String, WebRegion> regions) {
        this.version = version;
        this.nodes = Collections.unmodifiableMap(nodes);
        this.regions = Collections.unmodifiableMap(regions);

        // Build edge index by source
        Map<String, List<WebEdge>> edgeIndex = new HashMap<>();
        for (WebEdge edge : edges) {
            edgeIndex.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
        }

        // Make index immutable
        for (String key : edgeIndex.keySet()) {
            edgeIndex.put(key, Collections.unmodifiableList(edgeIndex.get(key)));
        }

        this.edgesBySource = Collections.unmodifiableMap(edgeIndex);
        this.edges = Collections.unmodifiableList(edges);

        log.info("NavigationWeb loaded: {} nodes, {} edges, {} regions",
                nodes.size(), edges.size(), regions.size());
    }

    // ========================================================================
    // Loading
    // ========================================================================

    /**
     * Load navigation web from the default resource path.
     *
     * @return the loaded NavigationWeb
     * @throws IOException if loading fails
     */
    public static NavigationWeb loadFromResources() throws IOException {
        return loadFromResources(DEFAULT_RESOURCE_PATH);
    }

    /**
     * Load navigation web from a resource path.
     *
     * @param resourcePath path to the resource
     * @return the loaded NavigationWeb
     * @throws IOException if loading fails
     */
    public static NavigationWeb loadFromResources(String resourcePath) throws IOException {
        try (InputStream is = NavigationWeb.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return parse(reader);
            }
        }
    }

    /**
     * Load navigation web from a file path.
     *
     * @param path path to the JSON file
     * @return the loaded NavigationWeb
     * @throws IOException if loading fails
     */
    public static NavigationWeb loadFromFile(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    /**
     * Parse navigation web from a reader.
     *
     * @param reader the input reader
     * @return the parsed NavigationWeb
     */
    public static NavigationWeb parse(Reader reader) {
        WebData data = GSON.fromJson(reader, WebData.class);
        return fromWebData(data);
    }

    /**
     * Parse navigation web from a JSON string.
     *
     * @param json the JSON string
     * @return the parsed NavigationWeb
     */
    public static NavigationWeb parse(String json) {
        WebData data = GSON.fromJson(json, WebData.class);
        return fromWebData(data);
    }

    private static NavigationWeb fromWebData(WebData data) {
        // Index nodes
        Map<String, WebNode> nodeMap = new HashMap<>();
        if (data.nodes != null) {
            for (WebNode node : data.nodes) {
                nodeMap.put(node.getId(), node);
            }
        }

        // Process edges (expand bidirectional)
        List<WebEdge> allEdges = new ArrayList<>();
        if (data.edges != null) {
            for (WebEdge edge : data.edges) {
                allEdges.add(edge);
                if (edge.isBidirectional()) {
                    // Create reverse edge
                    WebEdge reverse = edge.toBuilder()
                            .from(edge.getTo())
                            .to(edge.getFrom())
                            .build();
                    allEdges.add(reverse);
                }
            }
        }

        // Index regions
        Map<String, WebRegion> regionMap = new HashMap<>();
        if (data.regions != null) {
            for (WebRegion region : data.regions) {
                regionMap.put(region.getId(), region);
            }
        }

        return new NavigationWeb(
                data.version != null ? data.version : "1.0",
                nodeMap,
                allEdges,
                regionMap
        );
    }

    // ========================================================================
    // Node Queries
    // ========================================================================

    /**
     * Get a node by ID.
     *
     * @param id the node ID
     * @return the node or null if not found
     */
    public WebNode getNode(String id) {
        return nodes.get(id);
    }

    /**
     * Check if a node exists.
     *
     * @param id the node ID
     * @return true if node exists
     */
    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    /**
     * Get all nodes of a specific type.
     *
     * @param type the node type
     * @return list of matching nodes
     */
    public List<WebNode> getNodesByType(WebNodeType type) {
        return nodes.values().stream()
                .filter(n -> n.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Get all bank nodes.
     *
     * @return list of bank nodes
     */
    public List<WebNode> getBanks() {
        return getNodesByType(WebNodeType.BANK);
    }

    /**
     * Get all nodes with a specific tag.
     *
     * @param tag the tag to filter by
     * @return list of matching nodes
     */
    public List<WebNode> getNodesWithTag(String tag) {
        return nodes.values().stream()
                .filter(n -> n.hasTag(tag))
                .collect(Collectors.toList());
    }

    /**
     * Find the nearest node to a world point.
     *
     * @param point the world point
     * @return the nearest node, or null if no nodes exist
     */
    public WebNode findNearestNode(WorldPoint point) {
        return nodes.values().stream()
                .filter(n -> n.getPlane() == point.getPlane())
                .min(Comparator.comparingInt(n -> n.distanceTo(point)))
                .orElse(null);
    }

    /**
     * Find the nearest node of a specific type.
     *
     * @param point the world point
     * @param type  the required node type
     * @return the nearest matching node, or null if none found
     */
    public WebNode findNearestNode(WorldPoint point, WebNodeType type) {
        return nodes.values().stream()
                .filter(n -> n.getPlane() == point.getPlane())
                .filter(n -> n.getType() == type)
                .min(Comparator.comparingInt(n -> n.distanceTo(point)))
                .orElse(null);
    }

    /**
     * Find nodes within a distance of a point.
     *
     * @param point    the world point
     * @param maxDistance maximum distance in tiles
     * @return list of nodes within distance
     */
    public List<WebNode> findNodesWithinDistance(WorldPoint point, int maxDistance) {
        return nodes.values().stream()
                .filter(n -> n.distanceTo(point) <= maxDistance)
                .sorted(Comparator.comparingInt(n -> n.distanceTo(point)))
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Edge Queries
    // ========================================================================

    /**
     * Get all edges from a source node.
     *
     * @param sourceNodeId the source node ID
     * @return list of edges from this node (empty if none)
     */
    public List<WebEdge> getEdgesFrom(String sourceNodeId) {
        return edgesBySource.getOrDefault(sourceNodeId, Collections.emptyList());
    }

    /**
     * Get all edges of a specific type.
     *
     * @param type the edge type
     * @return list of matching edges
     */
    public List<WebEdge> getEdgesByType(WebEdgeType type) {
        return edges.stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Get direct edge between two nodes.
     *
     * @param fromId source node ID
     * @param toId   destination node ID
     * @return the edge or null if no direct connection
     */
    public WebEdge getEdge(String fromId, String toId) {
        return getEdgesFrom(fromId).stream()
                .filter(e -> e.getTo().equals(toId))
                .findFirst()
                .orElse(null);
    }

    // ========================================================================
    // Region Queries
    // ========================================================================

    /**
     * Get a region by ID.
     *
     * @param id the region ID
     * @return the region or null if not found
     */
    public WebRegion getRegion(String id) {
        return regions.get(id);
    }

    /**
     * Find the region containing a node.
     *
     * @param nodeId the node ID
     * @return the region or null if node not in any region
     */
    public WebRegion findRegionForNode(String nodeId) {
        return regions.values().stream()
                .filter(r -> r.containsNode(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if a node is in the wilderness.
     *
     * @param nodeId the node ID
     * @return true if in wilderness
     */
    public boolean isInWilderness(String nodeId) {
        WebRegion region = findRegionForNode(nodeId);
        return region != null && region.isWilderness();
    }

    // ========================================================================
    // Filtering
    // ========================================================================

    /**
     * Get F2P-accessible nodes only.
     *
     * @return list of F2P nodes
     */
    public List<WebNode> getF2PNodes() {
        return nodes.values().stream()
                .filter(WebNode::isF2P)
                .collect(Collectors.toList());
    }

    /**
     * Filter edges that a player can traverse based on requirements.
     *
     * @param sourceNodeId the source node ID
     * @param checker      requirement checker callback
     * @return list of traversable edges
     */
    public List<WebEdge> getTraversableEdges(String sourceNodeId, RequirementChecker checker) {
        return getEdgesFrom(sourceNodeId).stream()
                .filter(edge -> !edge.hasRequirements() || checker.canTraverse(edge))
                .collect(Collectors.toList());
    }

    /**
     * Callback interface for checking edge requirements.
     */
    @FunctionalInterface
    public interface RequirementChecker {
        boolean canTraverse(WebEdge edge);
    }

    // ========================================================================
    // JSON Data Classes
    // ========================================================================

    /**
     * Raw JSON data structure.
     */
    @Value
    private static class WebData {
        String version;
        List<WebNode> nodes;
        List<WebEdge> edges;
        List<WebRegion> regions;
    }

    // ========================================================================
    // Gson Configuration
    // ========================================================================

    private static Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapter(WebNodeType.class, new EnumIgnoreCaseDeserializer<>(WebNodeType.class))
                .registerTypeAdapter(WebEdgeType.class, new EnumIgnoreCaseDeserializer<>(WebEdgeType.class))
                .registerTypeAdapter(EdgeRequirementType.class, new EnumIgnoreCaseDeserializer<>(EdgeRequirementType.class))
                .create();
    }

    /**
     * Case-insensitive enum deserializer.
     */
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

