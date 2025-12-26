package com.rocinante.navigation;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Represents a geographic region in the navigation web.
 * Per REQUIREMENTS.md Section 7.2.1.
 *
 * <p>Regions group nodes for organizational purposes and
 * can be used to filter navigation (e.g., avoid members areas).
 */
@Value
@Builder
public class WebRegion {

    /**
     * Unique identifier for this region.
     */
    String id;

    /**
     * Human-readable name.
     */
    String name;

    /**
     * List of node IDs in this region.
     */
    List<String> nodes;

    /**
     * Whether this region is members-only.
     */
    @SerializedName("members_only")
    boolean membersOnly;

    /**
     * Whether this region contains wilderness areas.
     */
    @Builder.Default
    boolean wilderness = false;

    /**
     * Check if a node belongs to this region.
     *
     * @param nodeId the node ID to check
     * @return true if node is in this region
     */
    public boolean containsNode(String nodeId) {
        return nodes != null && nodes.contains(nodeId);
    }

    @Override
    public String toString() {
        return String.format("WebRegion[%s, %d nodes, %s]",
                id,
                nodes != null ? nodes.size() : 0,
                membersOnly ? "members" : "f2p");
    }
}

