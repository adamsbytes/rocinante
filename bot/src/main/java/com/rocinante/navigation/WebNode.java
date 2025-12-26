package com.rocinante.navigation;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

import java.util.List;
import java.util.Map;

/**
 * Represents a node in the navigation web.
 * Per REQUIREMENTS.md Section 7.2.1 - Node Definition.
 *
 * <p>Nodes represent key locations in the game world:
 * banks, teleport spots, quest areas, slayer locations, etc.
 */
@Value
@Builder(toBuilder = true)
public class WebNode {

    /**
     * Unique identifier for this node.
     * Format: lowercase_with_underscores (e.g., "varrock_west_bank")
     */
    String id;

    /**
     * Human-readable name.
     */
    String name;

    /**
     * World X coordinate.
     */
    int x;

    /**
     * World Y coordinate.
     */
    int y;

    /**
     * Plane (0-3).
     */
    int plane;

    /**
     * Node type category.
     */
    WebNodeType type;

    /**
     * Tags for filtering/categorization.
     * Examples: "members", "safe", "bank_nearby", "f2p"
     */
    List<String> tags;

    /**
     * Additional metadata specific to node type.
     * Examples:
     * - bank_type: "booth", "chest", "npc"
     * - slayer_master: "vannaka"
     * - teleport_spell: "VARROCK_TELEPORT"
     */
    Map<String, String> metadata;

    /**
     * Get the world point for this node.
     *
     * @return WorldPoint at node coordinates
     */
    public WorldPoint getWorldPoint() {
        return new WorldPoint(x, y, plane);
    }

    /**
     * Calculate distance to another node.
     *
     * @param other the other node
     * @return Chebyshev distance in tiles, or Integer.MAX_VALUE if different planes
     */
    public int distanceTo(WebNode other) {
        if (this.plane != other.plane) {
            return Integer.MAX_VALUE;
        }
        return Math.max(Math.abs(this.x - other.x), Math.abs(this.y - other.y));
    }

    /**
     * Calculate distance to a world point.
     *
     * @param point the world point
     * @return Chebyshev distance in tiles, or Integer.MAX_VALUE if different planes
     */
    public int distanceTo(WorldPoint point) {
        if (this.plane != point.getPlane()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(Math.abs(this.x - point.getX()), Math.abs(this.y - point.getY()));
    }

    /**
     * Check if this node has a specific tag.
     *
     * @param tag the tag to check
     * @return true if node has the tag
     */
    public boolean hasTag(String tag) {
        return tags != null && tags.contains(tag);
    }

    /**
     * Check if this node is members-only.
     *
     * @return true if members-only
     */
    public boolean isMembersOnly() {
        return hasTag("members");
    }

    /**
     * Check if this node is F2P accessible.
     *
     * @return true if F2P accessible
     */
    public boolean isF2P() {
        return hasTag("f2p");
    }

    /**
     * Check if this node is in a safe area (no PvP).
     *
     * @return true if safe
     */
    public boolean isSafe() {
        return hasTag("safe");
    }

    /**
     * Get metadata value.
     *
     * @param key the metadata key
     * @return the value or null if not present
     */
    public String getMetadata(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    @Override
    public String toString() {
        return String.format("WebNode[%s, (%d,%d,%d), %s]", id, x, y, plane, type);
    }
}

