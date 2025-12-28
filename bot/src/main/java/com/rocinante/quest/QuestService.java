package com.rocinante.quest;

import com.rocinante.quest.bridge.QuestHelperBridge;
import com.rocinante.quest.impl.TutorialIsland;
import com.rocinante.state.IronmanState;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing quest loading and Quest Helper plugin integration.
 *
 * <p>Per REQUIREMENTS.md Section 8.2.1, this service:
 * <ul>
 *   <li>Connects to Quest Helper plugin at runtime</li>
 *   <li>Translates Quest Helper quests to our Quest interface via QuestHelperBridge</li>
 *   <li>Maintains a registry of manually implemented quests (e.g., Tutorial Island)</li>
 *   <li>Provides unified quest lookup by ID or Quest Helper selection</li>
 * </ul>
 *
 * <p>Quest Resolution Order:
 * <ol>
 *   <li>Check manual quest registry first (for optimized implementations)</li>
 *   <li>Fall back to Quest Helper bridge for all other quests</li>
 * </ol>
 *
 * <p>Note: Quest Helper plugin dependency is declared at the plugin level
 * via {@code @PluginDependency(QuestHelperPlugin.class)} on RocinantePlugin.
 * The QuestHelperPlugin is injected here via Guice after it's initialized.
 */
@Slf4j
@Singleton
public class QuestService {

    /**
     * Quest Helper plugin instance, injected via Guice.
     * This is the entry point to Quest Helper's API.
     * May be null if Quest Helper plugin is not available.
     */
    @Nullable
    private Object questHelperPlugin;

    /**
     * Cached QuestManager reference from Quest Helper plugin.
     */
    @Nullable
    private Object questManager;

    /**
     * Manual quest implementations registry.
     * Key: quest ID (lowercase, underscores for spaces)
     * Value: Quest implementation
     */
    private final Map<String, Quest> manualQuests = new ConcurrentHashMap<>();

    /**
     * Cached Quest Helper bridges.
     * Key: Quest Helper instance hash
     * Value: QuestHelperBridge wrapper
     */
    private final Map<Integer, QuestHelperBridge> bridgeCache = new ConcurrentHashMap<>();

    /**
     * Provider for IronmanState, used when creating Tutorial Island quest.
     */
    private final Provider<IronmanState> ironmanStateProvider;

    /**
     * Flag indicating whether Quest Helper integration is available.
     */
    @Getter
    private boolean questHelperAvailable = false;

    @Inject
    public QuestService(Provider<IronmanState> ironmanStateProvider) {
        this.ironmanStateProvider = ironmanStateProvider;
        registerBuiltInQuests();
    }

    /**
     * Initialize Quest Helper plugin reference.
     * Called by RocinantePlugin after startup to inject the Quest Helper plugin.
     *
     * @param questHelperPlugin the Quest Helper plugin instance, or null if unavailable
     */
    public void initializeQuestHelper(@Nullable Object questHelperPlugin) {
        this.questHelperPlugin = questHelperPlugin;
        this.questManager = null; // Reset cached manager

        if (questHelperPlugin != null) {
            try {
                // Access QuestManager via questHelperPlugin.getQuestManager()
                java.lang.reflect.Method getQuestManager = questHelperPlugin.getClass()
                        .getMethod("getQuestManager");
                this.questManager = getQuestManager.invoke(questHelperPlugin);
                this.questHelperAvailable = true;
                log.info("Quest Helper integration initialized successfully");
            } catch (Exception e) {
                log.warn("Failed to access Quest Helper's QuestManager", e);
                this.questHelperAvailable = false;
            }
        } else {
            log.info("Quest Helper plugin not available - using manual quests only");
            this.questHelperAvailable = false;
        }
    }

    /**
     * Register built-in quest implementations.
     */
    private void registerBuiltInQuests() {
        // Register Tutorial Island with lazy initialization
        // (IronmanState provider allows deferred creation)
        log.debug("Registering built-in quests");
    }

    /**
     * Register a manual quest implementation.
     *
     * <p>Manual implementations take precedence over Quest Helper bridge
     * for quests with the same ID.
     *
     * @param quest the quest to register
     */
    public void registerQuest(Quest quest) {
        String id = normalizeQuestId(quest.getId());
        manualQuests.put(id, quest);
        log.debug("Registered manual quest: {} ({})", quest.getName(), id);
    }

    /**
     * Get a quest by its ID.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Check manual quest registry</li>
     *   <li>Search Quest Helper quests if available</li>
     * </ol>
     *
     * @param questId the quest ID (case-insensitive)
     * @return the quest, or null if not found
     */
    @Nullable
    public Quest getQuestById(String questId) {
        if (questId == null || questId.isEmpty()) {
            return null;
        }

        String normalizedId = normalizeQuestId(questId);

        // Check manual registry first
        Quest manual = manualQuests.get(normalizedId);
        if (manual != null) {
            log.debug("Found manual quest implementation for: {}", questId);
            return manual;
        }

        // Special case: Tutorial Island needs IronmanState
        if ("tutorial_island".equals(normalizedId)) {
            TutorialIsland tutorialIsland = new TutorialIsland(ironmanStateProvider.get());
            manualQuests.put(normalizedId, tutorialIsland);
            return tutorialIsland;
        }

        // Try Quest Helper bridge
        if (questHelperAvailable && questManager != null) {
            return getQuestFromQuestHelper(questId);
        }

        log.debug("Quest not found: {}", questId);
        return null;
    }

    /**
     * Get the currently selected quest from Quest Helper.
     *
     * @return the selected quest wrapped in our Quest interface, or null if none selected
     */
    @Nullable
    public Quest getSelectedQuestFromHelper() {
        if (!questHelperAvailable || questManager == null) {
            return null;
        }

        try {
            // Call questManager.getSelectedQuest()
            java.lang.reflect.Method getSelectedQuest = questManager.getClass()
                    .getMethod("getSelectedQuest");
            Object questHelper = getSelectedQuest.invoke(questManager);

            if (questHelper == null) {
                return null;
            }

            // Get or create bridge for this quest helper
            return getOrCreateBridge(questHelper);
        } catch (Exception e) {
            log.warn("Failed to get selected quest from Quest Helper", e);
            return null;
        }
    }

    /**
     * Get all available quests.
     *
     * <p>Returns a combination of:
     * <ul>
     *   <li>All registered manual quests</li>
     *   <li>All Quest Helper quests (if available)</li>
     * </ul>
     *
     * @return list of available quests
     */
    public List<Quest> getAvailableQuests() {
        List<Quest> quests = new ArrayList<>(manualQuests.values());

        // Add Quest Helper quests if available
        if (questHelperAvailable) {
            List<Quest> helperQuests = getQuestHelperQuests();
            // Filter out duplicates (manual implementations take precedence)
            for (Quest helperQuest : helperQuests) {
                String normalizedId = normalizeQuestId(helperQuest.getId());
                if (!manualQuests.containsKey(normalizedId)) {
                    quests.add(helperQuest);
                }
            }
        }

        return Collections.unmodifiableList(quests);
    }

    /**
     * Check if a quest is available (either manual or via Quest Helper).
     *
     * @param questId the quest ID
     * @return true if the quest is available
     */
    public boolean isQuestAvailable(String questId) {
        return getQuestById(questId) != null;
    }

    /**
     * Get a quest from Quest Helper by ID.
     *
     * @param questId the quest ID
     * @return the quest wrapped in our Quest interface, or null if not found
     */
    @Nullable
    private Quest getQuestFromQuestHelper(String questId) {
        if (!questHelperAvailable || questManager == null) {
            return null;
        }

        try {
            // Quest Helper uses QuestHelperQuest enum to look up quests
            // We need to find the quest by iterating available quests or using reflection
            List<Object> helpers = getQuestHelpersFromPlugin();
            
            for (Object helper : helpers) {
                // Check if this helper matches the quest ID
                String helperId = extractQuestHelperId(helper);
                if (normalizeQuestId(helperId).equals(normalizeQuestId(questId))) {
                    return getOrCreateBridge(helper);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get quest {} from Quest Helper", questId, e);
        }

        return null;
    }

    /**
     * Get all Quest Helper quests.
     *
     * @return list of quests from Quest Helper
     */
    private List<Quest> getQuestHelperQuests() {
        List<Quest> quests = new ArrayList<>();

        if (!questHelperAvailable) {
            return quests;
        }

        try {
            List<Object> helpers = getQuestHelpersFromPlugin();
            for (Object helper : helpers) {
                Quest quest = getOrCreateBridge(helper);
                if (quest != null) {
                    quests.add(quest);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enumerate Quest Helper quests", e);
        }

        return quests;
    }

    /**
     * Get list of QuestHelper instances from Quest Helper plugin.
     */
    @SuppressWarnings("unchecked")
    private List<Object> getQuestHelpersFromPlugin() {
        List<Object> helpers = new ArrayList<>();

        try {
            // Try to get quest helpers via QuestHelperQuest.getQuestHelpers()
            Class<?> questHelperQuestClass = Class.forName("com.questhelper.questinfo.QuestHelperQuest");
            java.lang.reflect.Method getQuestHelpers = questHelperQuestClass
                    .getMethod("getQuestHelpers", boolean.class);
            
            // Pass false to exclude developer quests
            Object result = getQuestHelpers.invoke(null, false);
            if (result instanceof List) {
                helpers.addAll((List<Object>) result);
            }
        } catch (ClassNotFoundException e) {
            log.debug("QuestHelperQuest class not found - Quest Helper may not be loaded");
        } catch (Exception e) {
            log.warn("Failed to get quest helpers from plugin", e);
        }

        return helpers;
    }

    /**
     * Extract quest ID from a Quest Helper instance.
     */
    private String extractQuestHelperId(Object questHelper) {
        try {
            // Try getQuest().name() for enum name
            java.lang.reflect.Method getQuest = questHelper.getClass().getMethod("getQuest");
            Object questEnum = getQuest.invoke(questHelper);
            if (questEnum != null) {
                return questEnum.toString();
            }
        } catch (Exception e) {
            log.trace("Could not extract quest ID via getQuest()", e);
        }

        // Fall back to class name
        return questHelper.getClass().getSimpleName();
    }

    /**
     * Get or create a QuestHelperBridge for a Quest Helper instance.
     * Returns the Quest interface wrapper via bridge.toQuest().
     */
    private Quest getOrCreateBridge(Object questHelper) {
        int hash = System.identityHashCode(questHelper);
        
        QuestHelperBridge bridge = bridgeCache.get(hash);
        if (bridge == null) {
            bridge = new QuestHelperBridge(questHelper);
            bridgeCache.put(hash, bridge);
            log.debug("Created bridge for quest: {}", bridge.getMetadata().getName());
        }
        
        // Return the Quest interface wrapper
        return bridge.toQuest();
    }

    /**
     * Normalize a quest ID to lowercase with underscores.
     *
     * @param questId the quest ID
     * @return normalized ID
     */
    private String normalizeQuestId(String questId) {
        if (questId == null) {
            return "";
        }
        return questId.toLowerCase()
                .replace(" ", "_")
                .replace("-", "_")
                .replace("'", "")
                .replace(".", "");
    }

    /**
     * Clear cached bridges.
     * Call this when Quest Helper state may have changed significantly.
     */
    public void clearBridgeCache() {
        bridgeCache.clear();
        log.debug("Cleared quest bridge cache");
    }

    /**
     * Get statistics about the quest service.
     *
     * @return map of statistic name to value
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("questHelperAvailable", questHelperAvailable);
        stats.put("manualQuestCount", manualQuests.size());
        stats.put("bridgeCacheSize", bridgeCache.size());
        stats.put("manualQuestIds", new ArrayList<>(manualQuests.keySet()));
        return stats;
    }
}

