package com.rocinante.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rocinante.state.AttackStyle;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and provides NPC combat data from JSON resource file.
 *
 * This data includes:
 * - NPC attack speeds
 * - NPC attack styles (MELEE/RANGED/MAGIC)
 * - Attack animation IDs
 * - Multi-style attack info (e.g., dragons with melee + dragonfire)
 *
 * Data is loaded from resources/data/npc_combat_data.json at startup.
 * Format version 2 uses an array of NPC entries, each with an array of npcIds.
 */
@Slf4j
@Singleton
public class NpcCombatDataLoader {

    private static final String DATA_FILE = "/data/npc_combat_data.json";

    private final Map<Integer, NpcCombatData> npcData = new ConcurrentHashMap<>();
    private final Gson gson;
    private boolean loaded = false;

    @Inject
    public NpcCombatDataLoader(Gson gson) {
        this.gson = gson;
        loadData();
    }

    /**
     * Load NPC combat data from JSON resource.
     * Uses JsonResourceLoader for retry logic and error handling.
     * Supports both format version 1 (object with NPC ID keys) and
     * format version 2 (array with npcIds arrays).
     */
    private void loadData() {
        try {
            JsonObject root = JsonResourceLoader.load(gson, DATA_FILE);
            
            // Check format version
            int formatVersion = 1;
            if (root.has("_meta") && root.getAsJsonObject("_meta").has("format_version")) {
                formatVersion = root.getAsJsonObject("_meta").get("format_version").getAsInt();
            }
            
            if (formatVersion >= 2) {
                loadFormatV2(root);
            } else {
                loadFormatV1(root);
            }
            
            loaded = true;
            log.info("Loaded combat data for {} NPCs (format v{})", npcData.size(), formatVersion);
        } catch (JsonResourceLoader.JsonLoadException e) {
            log.error("Failed to load NPC combat data: {}", e.getMessage());
        }
    }

    /**
     * Load format version 1: object with NPC ID keys.
     */
    private void loadFormatV1(JsonObject root) {
        JsonObject npcs = JsonResourceLoader.getRequiredObject(root, "npcs");
        
        for (Map.Entry<String, JsonElement> entry : npcs.entrySet()) {
            try {
                int npcId = Integer.parseInt(entry.getKey());
                NpcCombatData data = parseNpcData(entry.getValue().getAsJsonObject());
                if (data != null) {
                    npcData.put(npcId, data);
                }
            } catch (NumberFormatException e) {
                log.debug("Skipping non-numeric NPC ID: {}", entry.getKey());
            }
        }
    }

    /**
     * Load format version 2: array of NPC entries with npcIds arrays.
     */
    private void loadFormatV2(JsonObject root) {
        JsonArray npcs = JsonResourceLoader.getRequiredArray(root, "npcs");
        int entriesLoaded = 0;
        
        for (JsonElement element : npcs) {
            try {
                JsonObject obj = element.getAsJsonObject();
                NpcCombatData data = parseNpcData(obj);
                
                if (data != null && obj.has("npcIds")) {
                    JsonArray npcIds = obj.getAsJsonArray("npcIds");
                    for (JsonElement idElement : npcIds) {
                        int npcId = idElement.getAsInt();
                        npcData.put(npcId, data);
                    }
                    entriesLoaded++;
                }
            } catch (Exception e) {
                log.debug("Failed to parse NPC entry: {}", e.getMessage());
            }
        }
        
        log.debug("Processed {} NPC combat data entries", entriesLoaded);
    }

    /**
     * Parse a single NPC's combat data from JSON.
     */
    @Nullable
    private NpcCombatData parseNpcData(JsonObject obj) {
        try {
            String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";
            int attackSpeed = obj.has("attackSpeed") ? obj.get("attackSpeed").getAsInt() : 4;

            AttackStyle attackStyle = AttackStyle.MELEE;
            if (obj.has("attackStyle")) {
                String styleStr = obj.get("attackStyle").getAsString();
                attackStyle = parseAttackStyle(styleStr);
            }

            int maxHit = obj.has("maxHit") ? obj.get("maxHit").getAsInt() : 0;

            int[] attackAnimations = new int[0];
            if (obj.has("attackAnimations")) {
                var arr = obj.getAsJsonArray("attackAnimations");
                attackAnimations = new int[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    attackAnimations[i] = arr.get(i).getAsInt();
                }
            }

            // Parse ranged attack info
            RangedAttackInfo rangedAttack = null;
            if (obj.has("rangedAttack")) {
                rangedAttack = parseRangedAttack(obj.getAsJsonObject("rangedAttack"));
            }

            // Parse magic attack info
            MagicAttackInfo magicAttack = null;
            if (obj.has("magicAttack")) {
                magicAttack = parseMagicAttack(obj.getAsJsonObject("magicAttack"));
            }

            return new NpcCombatData(name, attackSpeed, attackStyle, maxHit,
                    attackAnimations, rangedAttack, magicAttack);
        } catch (Exception e) {
            log.debug("Failed to parse NPC data: {}", e.getMessage());
            return null;
        }
    }

    private AttackStyle parseAttackStyle(String styleStr) {
        return switch (styleStr.toUpperCase()) {
            case "RANGED" -> AttackStyle.RANGED;
            case "MAGIC" -> AttackStyle.MAGIC;
            default -> AttackStyle.MELEE;
        };
    }

    @Nullable
    private RangedAttackInfo parseRangedAttack(JsonObject obj) {
        int projectileId = obj.has("projectileId") ? obj.get("projectileId").getAsInt() : -1;
        AttackStyle style = obj.has("attackStyle")
                ? parseAttackStyle(obj.get("attackStyle").getAsString())
                : AttackStyle.RANGED;
        int maxHit = obj.has("maxHit") ? obj.get("maxHit").getAsInt() : 0;
        return new RangedAttackInfo(projectileId, style, maxHit);
    }

    @Nullable
    private MagicAttackInfo parseMagicAttack(JsonObject obj) {
        int projectileId = obj.has("projectileId") ? obj.get("projectileId").getAsInt() : -1;
        AttackStyle style = AttackStyle.MAGIC;
        int maxHit = obj.has("maxHit") ? obj.get("maxHit").getAsInt() : 0;
        return new MagicAttackInfo(projectileId, style, maxHit);
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Get combat data for an NPC by ID.
     *
     * @param npcId the NPC ID
     * @return NpcCombatData, or null if not found
     */
    @Nullable
    public NpcCombatData getNpcData(int npcId) {
        return npcData.get(npcId);
    }

    /**
     * Get the attack speed for an NPC.
     *
     * @param npcId the NPC ID
     * @return attack speed in ticks, or 4 if unknown
     */
    public int getAttackSpeed(int npcId) {
        NpcCombatData data = npcData.get(npcId);
        return data != null ? data.attackSpeed() : 4;
    }

    /**
     * Get the primary attack style for an NPC.
     *
     * @param npcId the NPC ID
     * @return attack style, or MELEE if unknown
     */
    public AttackStyle getAttackStyle(int npcId) {
        NpcCombatData data = npcData.get(npcId);
        return data != null ? data.attackStyle() : AttackStyle.MELEE;
    }

    /**
     * Check if an animation ID is an attack animation for the NPC.
     *
     * @param npcId       the NPC ID
     * @param animationId the animation ID
     * @return true if this is a known attack animation
     */
    public boolean isAttackAnimation(int npcId, int animationId) {
        NpcCombatData data = npcData.get(npcId);
        if (data == null || data.attackAnimations() == null) {
            return false;
        }
        for (int anim : data.attackAnimations()) {
            if (anim == animationId) return true;
        }
        return false;
    }

    /**
     * Get the attack style associated with a projectile from this NPC.
     *
     * @param npcId        the NPC ID
     * @param projectileId the projectile ID
     * @return attack style, or null if not a known projectile
     */
    @Nullable
    public AttackStyle getProjectileAttackStyle(int npcId, int projectileId) {
        NpcCombatData data = npcData.get(npcId);
        if (data == null) return null;

        if (data.rangedAttack() != null && data.rangedAttack().projectileId() == projectileId) {
            return data.rangedAttack().style();
        }
        if (data.magicAttack() != null && data.magicAttack().projectileId() == projectileId) {
            return data.magicAttack().style();
        }
        return null;
    }

    /**
     * Check if data has been loaded.
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Get the count of loaded NPCs.
     */
    public int getLoadedCount() {
        return npcData.size();
    }

    // ========================================================================
    // Data Records
    // ========================================================================

    public record NpcCombatData(
            String name,
            int attackSpeed,
            AttackStyle attackStyle,
            int maxHit,
            int[] attackAnimations,
            @Nullable RangedAttackInfo rangedAttack,
            @Nullable MagicAttackInfo magicAttack
    ) {
        public boolean hasMultipleStyles() {
            return rangedAttack != null || magicAttack != null;
        }
    }

    public record RangedAttackInfo(int projectileId, AttackStyle style, int maxHit) {}

    public record MagicAttackInfo(int projectileId, AttackStyle style, int maxHit) {}
}
