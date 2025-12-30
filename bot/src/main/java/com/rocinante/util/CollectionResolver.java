package com.rocinante.util;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Resolves object and NPC IDs to their collections via runtime reflection.
 * Automatically expands training method IDs to include all collection variants.
 * 
 * <p>Strategy: Check each input ID against all collections. If found in a collection,
 * merge that entire collection into the result. This makes training_methods.json
 * maintainable - only one representative ID is needed per collection.
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Single copper rock ID expands to all 4 variants
 * List<Integer> expanded = CollectionResolver.expandObjectIds(List.of(10079));
 * // Result: [10079, 10943, 11161, 37944]
 * 
 * // Mixed IDs from different collections merge all matches
 * List<Integer> mixed = CollectionResolver.expandObjectIds(List.of(10079, 10080));
 * // Result: [10079, 10943, 11161, 37944, 10080, 11360, 11361, 37945]
 * }</pre>
 * 
 * <p>Fallback behavior: If no input ID matches any collection, returns the original
 * list unchanged. This ensures backwards compatibility with custom or rare IDs.
 */
@Slf4j
public final class CollectionResolver {
    
    /**
     * Maps each object ID to the full collection it belongs to.
     * Built once at class load via reflection on ObjectCollections.
     */
    private static final Map<Integer, List<Integer>> OBJECT_COLLECTIONS_MAP;
    
    /**
     * Maps each NPC ID to the full collection it belongs to.
     * Built once at class load via reflection on NpcCollections.
     */
    private static final Map<Integer, List<Integer>> NPC_COLLECTIONS_MAP;
    
    static {
        OBJECT_COLLECTIONS_MAP = buildCollectionMap(ObjectCollections.class);
        NPC_COLLECTIONS_MAP = buildCollectionMap(NpcCollections.class);
        
        log.debug("CollectionResolver initialized: {} object IDs mapped, {} NPC IDs mapped",
                OBJECT_COLLECTIONS_MAP.size(), NPC_COLLECTIONS_MAP.size());
    }
    
    private CollectionResolver() {
        // Utility class
    }
    
    /**
     * Scans a class for public static final List<Integer> fields and builds
     * a reverse lookup map from each ID to its containing collection.
     * 
     * @param collectionClass the class containing collection fields (ObjectCollections or NpcCollections)
     * @return map from ID to full collection list
     */
    private static Map<Integer, List<Integer>> buildCollectionMap(Class<?> collectionClass) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        
        for (Field field : collectionClass.getDeclaredFields()) {
            // Only process public static final List fields
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || 
                !Modifier.isStatic(modifiers) || 
                !Modifier.isFinal(modifiers)) {
                continue;
            }
            
            // Must be a List type
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            
            try {
                @SuppressWarnings("unchecked")
                List<Integer> collection = (List<Integer>) field.get(null);
                
                if (collection == null || collection.isEmpty()) {
                    continue;
                }
                
                // Map each ID in this collection to the full collection
                for (Integer id : collection) {
                    // If an ID appears in multiple collections, first one wins
                    // This shouldn't happen in practice with well-organized collections
                    if (!map.containsKey(id)) {
                        map.put(id, collection);
                    } else {
                        log.trace("ID {} appears in multiple collections, using first match", id);
                    }
                }
                
                log.trace("Mapped {} IDs from collection: {}", collection.size(), field.getName());
                
            } catch (IllegalAccessException e) {
                log.warn("Failed to access collection field: {}", field.getName(), e);
            } catch (ClassCastException e) {
                log.trace("Skipping non-Integer list field: {}", field.getName());
            }
        }
        
        return Collections.unmodifiableMap(map);
    }
    
    /**
     * Expands object IDs by checking each against ObjectCollections.
     * Merges all matching collections, preserves order, removes duplicates.
     * 
     * <p>If any input ID is found in a collection, that entire collection is
     * included in the result. Multiple collections can be merged if input IDs
     * span different collections.
     * 
     * @param inputIds list of object IDs to expand
     * @return expanded list with all collection variants, or original if no matches
     */
    public static List<Integer> expandObjectIds(List<Integer> inputIds) {
        return expandIds(inputIds, OBJECT_COLLECTIONS_MAP, "object");
    }
    
    /**
     * Expands NPC IDs by checking each against NpcCollections.
     * Merges all matching collections, preserves order, removes duplicates.
     * 
     * @param inputIds list of NPC IDs to expand
     * @return expanded list with all collection variants, or original if no matches
     */
    public static List<Integer> expandNpcIds(List<Integer> inputIds) {
        return expandIds(inputIds, NPC_COLLECTIONS_MAP, "NPC");
    }
    
    /**
     * Core expansion logic shared by object and NPC expansion.
     * 
     * @param inputIds IDs to expand
     * @param collectionMap ID-to-collection mapping
     * @param type description for logging (object/NPC)
     * @return expanded or original list
     */
    private static List<Integer> expandIds(
            List<Integer> inputIds, 
            Map<Integer, List<Integer>> collectionMap,
            String type) {
        
        if (inputIds == null || inputIds.isEmpty()) {
            return inputIds;
        }
        
        // Use LinkedHashSet to preserve order and remove duplicates
        Set<Integer> expanded = new LinkedHashSet<>();
        Set<List<Integer>> processedCollections = new HashSet<>();
        boolean foundAnyCollection = false;
        
        for (Integer id : inputIds) {
            List<Integer> collection = collectionMap.get(id);
            
            if (collection != null && !processedCollections.contains(collection)) {
                // Found a collection - add all its IDs
                expanded.addAll(collection);
                processedCollections.add(collection);
                foundAnyCollection = true;
                
                log.trace("Expanded {} ID {} to collection with {} variants", 
                        type, id, collection.size());
            } else if (collection == null) {
                // ID not in any collection - add it directly
                expanded.add(id);
            }
            // If collection already processed, skip to avoid duplicates
        }
        
        if (!foundAnyCollection) {
            // No collections matched - return original unchanged
            return inputIds;
        }
        
        List<Integer> result = new ArrayList<>(expanded);
        
        if (result.size() > inputIds.size()) {
            log.debug("Expanded {} {} IDs to {} via collections", 
                    inputIds.size(), type, result.size());
        }
        
        return result;
    }
    
    /**
     * Checks if an object ID belongs to any known collection.
     * 
     * @param objectId the object ID to check
     * @return true if the ID is part of an ObjectCollections field
     */
    public static boolean isInObjectCollection(int objectId) {
        return OBJECT_COLLECTIONS_MAP.containsKey(objectId);
    }
    
    /**
     * Checks if an NPC ID belongs to any known collection.
     * 
     * @param npcId the NPC ID to check
     * @return true if the ID is part of an NpcCollections field
     */
    public static boolean isInNpcCollection(int npcId) {
        return NPC_COLLECTIONS_MAP.containsKey(npcId);
    }
    
    /**
     * Gets the full collection containing the given object ID.
     * 
     * @param objectId the object ID to look up
     * @return the full collection list, or null if not in any collection
     */
    public static List<Integer> getObjectCollection(int objectId) {
        return OBJECT_COLLECTIONS_MAP.get(objectId);
    }
    
    /**
     * Gets the full collection containing the given NPC ID.
     * 
     * @param npcId the NPC ID to look up
     * @return the full collection list, or null if not in any collection
     */
    public static List<Integer> getNpcCollection(int npcId) {
        return NPC_COLLECTIONS_MAP.get(npcId);
    }
    
    /**
     * Returns statistics about the loaded collections for debugging/monitoring.
     * 
     * @return map with collection stats (objectIdCount, npcIdCount)
     */
    public static Map<String, Integer> getStats() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("objectIdCount", OBJECT_COLLECTIONS_MAP.size());
        stats.put("npcIdCount", NPC_COLLECTIONS_MAP.size());
        return stats;
    }
}

