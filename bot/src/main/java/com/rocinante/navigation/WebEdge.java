package com.rocinante.navigation;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents an edge (connection) in the navigation web.
 * Per REQUIREMENTS.md Section 7.2.1 - Edge Definition.
 *
 * <p>Edges connect nodes and represent:
 * <ul>
 *   <li>Walking paths</li>
 *   <li>Teleports (spells, jewelry, tablets)</li>
 *   <li>Transports (ships, spirit trees, fairy rings)</li>
 *   <li>Agility shortcuts</li>
 *   <li>Quest-locked paths</li>
 *   <li>Doors/gates requiring interaction</li>
 * </ul>
 */
@Value
@Builder(toBuilder = true)
public class WebEdge {

    /**
     * Source node ID.
     */
    String from;

    /**
     * Destination node ID.
     */
    String to;

    /**
     * Edge type.
     */
    WebEdgeType type;

    /**
     * Estimated travel cost in game ticks.
     * Used as weight for pathfinding.
     */
    @SerializedName("cost_ticks")
    int costTicks;

    /**
     * Whether this edge can be traversed in both directions.
     * If true, creates an implicit reverse edge.
     */
    boolean bidirectional;

    /**
     * Requirements to traverse this edge.
     */
    @Builder.Default
    List<EdgeRequirement> requirements = Collections.emptyList();

    /**
     * Additional metadata specific to edge type.
     * Examples:
     * - spell_id: magic spell to cast
     * - item_id: jewelry/tablet to use
     * - npc_id: NPC to interact with
     * - object_id: object to interact with
     * - action: menu action to use
     * - agility_success_rate: success rate for shortcuts
     */
    Map<String, String> metadata;

    /**
     * Check if this edge has any requirements.
     *
     * @return true if there are requirements
     */
    public boolean hasRequirements() {
        return requirements != null && !requirements.isEmpty();
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

    /**
     * Get metadata value as integer.
     *
     * @param key the metadata key
     * @param defaultValue default if not present or not parseable
     * @return the integer value
     */
    public int getMetadataInt(String key, int defaultValue) {
        String value = getMetadata(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Check if this edge requires magic.
     *
     * @return true if this is a teleport edge
     */
    public boolean requiresMagic() {
        return type == WebEdgeType.TELEPORT;
    }

    /**
     * Check if this edge requires items.
     *
     * @return true if requirements include items or runes
     */
    public boolean requiresItems() {
        if (requirements == null) {
            return false;
        }
        return requirements.stream().anyMatch(r ->
                r.getType() == EdgeRequirementType.ITEM ||
                r.getType() == EdgeRequirementType.RUNES);
    }

    /**
     * Check if this edge requires a quest.
     *
     * @return true if requirements include quest
     */
    public boolean requiresQuest() {
        if (requirements == null) {
            return false;
        }
        return requirements.stream().anyMatch(r ->
                r.getType() == EdgeRequirementType.QUEST);
    }

    /**
     * Get the minimum magic level required.
     *
     * @return magic level or 0 if none required
     */
    public int getRequiredMagicLevel() {
        if (requirements == null) {
            return 0;
        }
        return requirements.stream()
                .filter(r -> r.getType() == EdgeRequirementType.MAGIC_LEVEL)
                .mapToInt(EdgeRequirement::getValue)
                .max()
                .orElse(0);
    }

    /**
     * Get the minimum agility level required.
     *
     * @return agility level or 0 if none required
     */
    public int getRequiredAgilityLevel() {
        if (requirements == null) {
            return 0;
        }
        return requirements.stream()
                .filter(r -> r.getType() == EdgeRequirementType.AGILITY_LEVEL)
                .mapToInt(EdgeRequirement::getValue)
                .max()
                .orElse(0);
    }

    @Override
    public String toString() {
        return String.format("WebEdge[%s -> %s, %s, %d ticks%s]",
                from, to, type, costTicks,
                bidirectional ? ", bidirectional" : "");
    }
}

