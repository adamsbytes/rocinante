package com.rocinante.quest;

import com.rocinante.core.GameStateService;
import com.rocinante.quest.bridge.ItemRequirementInfo;
import com.rocinante.quest.bridge.QuestHelperBridge;
import com.rocinante.quest.bridge.RequirementStatus;
import com.rocinante.quest.impl.TutorialIsland;
import com.rocinante.state.BankState;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.IronmanState;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing quest loading and Quest Helper plugin integration.
 * Single source of truth for quest data AND player requirement status.
 * 
 * Quest Helper is REQUIRED. RocinantePlugin ensures Quest Helper is loaded
 * before any systems that depend on QuestService are started.
 */
@Slf4j
@Singleton
public class QuestService {

    private Object questHelperPlugin;
    private Object questManager;

    private final Map<String, Quest> manualQuests = new ConcurrentHashMap<>();
    private final Map<Integer, QuestHelperBridge> bridgeCache = new ConcurrentHashMap<>();
    private final Provider<IronmanState> ironmanStateProvider;
    private final Provider<GameStateService> gameStateServiceProvider;
    private final Provider<Client> clientProvider;

    @Inject
    public QuestService(
            Provider<IronmanState> ironmanStateProvider,
            Provider<GameStateService> gameStateServiceProvider,
            Provider<Client> clientProvider) {
        this.ironmanStateProvider = ironmanStateProvider;
        this.gameStateServiceProvider = gameStateServiceProvider;
        this.clientProvider = clientProvider;
    }

    /**
     * Initialize Quest Helper plugin reference.
     * Called by RocinantePlugin AFTER Quest Helper has loaded.
     * @param questHelperPlugin the Quest Helper plugin instance (must not be null)
     */
    public void initializeQuestHelper(Object questHelperPlugin) {
        if (questHelperPlugin == null) {
            throw new IllegalArgumentException("questHelperPlugin cannot be null");
        }

        this.questHelperPlugin = questHelperPlugin;

            try {
            var getQuestManager = questHelperPlugin.getClass().getMethod("getQuestManager");
                this.questManager = getQuestManager.invoke(questHelperPlugin);
            log.info("Quest Helper integration initialized");
            } catch (Exception e) {
            throw new IllegalStateException("Failed to access Quest Helper's QuestManager", e);
        }
    }

    /**
     * Register a manual quest implementation. Manual quests take precedence over Quest Helper.
     */
    public void registerQuest(Quest quest) {
        String id = normalizeQuestId(quest.getId());
        manualQuests.put(id, quest);
        log.debug("Registered manual quest: {} ({})", quest.getName(), id);
    }

    /**
     * Get a quest by ID. Manual quests first, then Quest Helper.
     */
    @Nullable
    public Quest getQuestById(String questId) {
        if (questId == null || questId.isEmpty()) {
            return null;
        }

        String normalizedId = normalizeQuestId(questId);

        // Manual registry first
        Quest manual = manualQuests.get(normalizedId);
        if (manual != null) {
            return manual;
        }

        // Special case: Tutorial Island needs IronmanState
        if ("tutorial_island".equals(normalizedId)) {
            TutorialIsland tutorialIsland = new TutorialIsland(ironmanStateProvider.get());
            manualQuests.put(normalizedId, tutorialIsland);
            return tutorialIsland;
        }

        // Quest Helper
            return getQuestFromQuestHelper(questId);
    }

    /**
     * Get the currently selected quest from Quest Helper.
     */
    @Nullable
    public Quest getSelectedQuestFromHelper() {
        try {
            var getSelectedQuest = questManager.getClass().getMethod("getSelectedQuest");
            Object questHelper = getSelectedQuest.invoke(questManager);
            if (questHelper == null) {
                return null;
            }
            return getOrCreateBridge(questHelper).toQuest();
        } catch (Exception e) {
            log.error("Failed to get selected quest from Quest Helper - Quest Helper may be incompatible", e);
            return null;
        }
    }

    /**
     * Get all available quests.
     */
    public List<Quest> getAvailableQuests() {
        List<Quest> quests = new ArrayList<>(manualQuests.values());

        for (Quest helperQuest : getQuestHelperQuests()) {
                String normalizedId = normalizeQuestId(helperQuest.getId());
                if (!manualQuests.containsKey(normalizedId)) {
                    quests.add(helperQuest);
            }
        }

        return Collections.unmodifiableList(quests);
    }

    public boolean isQuestAvailable(String questId) {
        return getQuestById(questId) != null;
    }

    // ========================================================================
    // Quest Data API - all data comes from Quest Helper
    // ========================================================================

    public int getQuestPoints(String questId) {
        return getBridge(questId).getQuestPoints();
    }

    public boolean canStartQuest(String questId) {
        return getBridge(questId).clientMeetsRequirements();
    }

    public boolean isMembers(String questId) {
        return getBridge(questId).isMembers();
    }

    public String getDifficulty(String questId) {
        return getBridge(questId).getDifficulty();
    }

    public List<QuestHelperBridge.SkillRequirementInfo> getSkillRequirements(String questId) {
        return getBridge(questId).getSkillRequirements();
    }

    public List<QuestHelperBridge.QuestRequirementInfo> getQuestRequirements(String questId) {
        return getBridge(questId).getQuestRequirements();
    }

    public List<com.rocinante.quest.bridge.ItemRequirementInfo> getItemRequirements(String questId) {
        return getBridge(questId).extractItemRequirements();
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private QuestHelperBridge getBridge(String questId) {
        List<Object> helpers = getQuestHelpersFromPlugin();
        for (Object helper : helpers) {
            String helperId = extractQuestHelperId(helper);
            if (normalizeQuestId(helperId).equals(normalizeQuestId(questId))) {
                return getOrCreateBridge(helper);
            }
        }
        throw new IllegalStateException("Quest not found in Quest Helper: " + questId);
    }

    @Nullable
    private Quest getQuestFromQuestHelper(String questId) {
        try {
            List<Object> helpers = getQuestHelpersFromPlugin();
            for (Object helper : helpers) {
                String helperId = extractQuestHelperId(helper);
                if (normalizeQuestId(helperId).equals(normalizeQuestId(questId))) {
                    return getOrCreateBridge(helper).toQuest();
                }
            }
        } catch (Exception e) {
            log.error("Failed to get quest {} from Quest Helper - reflection may have failed", questId, e);
        }
        return null;
    }

    private List<Quest> getQuestHelperQuests() {
        List<Quest> quests = new ArrayList<>();
        try {
            for (Object helper : getQuestHelpersFromPlugin()) {
                Quest quest = getOrCreateBridge(helper).toQuest();
                if (quest != null) {
                    quests.add(quest);
                }
            }
        } catch (Exception e) {
            log.error("Failed to enumerate Quest Helper quests - Quest Helper integration broken", e);
        }
        return quests;
    }

    @SuppressWarnings("unchecked")
    private List<Object> getQuestHelpersFromPlugin() {
        try {
            Class<?> questHelperQuestClass = Class.forName("com.questhelper.questinfo.QuestHelperQuest");
            var getQuestHelpers = questHelperQuestClass.getMethod("getQuestHelpers", boolean.class);
            Object result = getQuestHelpers.invoke(null, false);
            if (result instanceof List) {
                return (List<Object>) result;
            }
        } catch (Exception e) {
            log.error("Failed to get quest helpers from plugin - Quest Helper API may have changed", e);
        }
        return Collections.emptyList();
    }

    private String extractQuestHelperId(Object questHelper) {
        try {
            var getQuest = questHelper.getClass().getMethod("getQuest");
            Object questEnum = getQuest.invoke(questHelper);
            if (questEnum != null) {
                return questEnum.toString();
            }
        } catch (Exception e) {
            log.trace("Could not extract quest ID", e);
        }
        return questHelper.getClass().getSimpleName();
    }

    private QuestHelperBridge getOrCreateBridge(Object questHelper) {
        int hash = System.identityHashCode(questHelper);
        return bridgeCache.computeIfAbsent(hash, k -> new QuestHelperBridge(questHelper));
    }

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

    public void clearBridgeCache() {
        bridgeCache.clear();
    }

    /**
     * Get statistics about the quest service.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("questHelperAvailable", questManager != null);
        stats.put("manualQuestCount", manualQuests.size());
        stats.put("bridgeCacheSize", bridgeCache.size());
        stats.put("manualQuestIds", new ArrayList<>(manualQuests.keySet()));
        return stats;
    }

    // ========================================================================
    // Complete Requirements Status API
    // ========================================================================

    /**
     * Get complete requirements status for a quest with ACTUAL player state.
     * This is the primary API for getting quest requirement info.
     */
    public RequirementStatus.QuestRequirementsStatus getRequirementsStatus(String questId) {
        Client client = clientProvider.get();
        GameStateService gss = gameStateServiceProvider.get();
        
        // Get basic quest info from Quest Helper
        QuestHelperBridge bridge = getBridge(questId);
        String questName = bridge.getMetadata().getName();
        String difficulty = bridge.getDifficulty();
        boolean members = bridge.isMembers();
        int questPoints = bridge.getQuestPoints();
        
        // Get quest state
        String state = getQuestStateString(questId, client);
        boolean canStart = "NOT_STARTED".equals(state) && bridge.clientMeetsRequirements();
        
        // Build skill requirements with CURRENT player levels
        List<RequirementStatus.SkillStatus> skillReqs = new ArrayList<>();
        for (var skillReq : bridge.getSkillRequirements()) {
            int currentLevel = getSkillLevel(client, skillReq.getSkillName());
            skillReqs.add(RequirementStatus.SkillStatus.builder()
                    .skillName(skillReq.getSkillName())
                    .required(skillReq.getRequiredLevel())
                    .current(currentLevel)
                    .met(currentLevel >= skillReq.getRequiredLevel())
                    .boostable(false) // Would need more reflection to get this
                    .build());
        }
        
        // Build quest requirements with ACTUAL completion status
        List<RequirementStatus.QuestStatus> questReqs = new ArrayList<>();
        for (var questReq : bridge.getQuestRequirements()) {
            boolean completed = isQuestCompleted(questReq.getQuestId(), client);
            questReqs.add(RequirementStatus.QuestStatus.builder()
                    .questId(questReq.getQuestId())
                    .questName(questReq.getQuestName())
                    .met(completed)
                    .build());
        }
        
        // Build item requirements with ACTUAL inventory/equipment/bank counts
        InventoryState inventory = gss.getInventoryState();
        EquipmentState equipment = gss.getEquipmentState();
        BankState bank = gss.getBankState();
        
        List<RequirementStatus.ItemStatus> itemReqs = new ArrayList<>();
        for (ItemRequirementInfo itemReq : bridge.extractItemRequirements()) {
            List<Integer> allIds = itemReq.getAllIds();
            
            int inInventory = countItemsInInventory(inventory, allIds);
            int equipped = countItemsEquipped(equipment, allIds);
            int inBank = countItemsInBank(bank, allIds);
            int total = inInventory + equipped + inBank;
            
            itemReqs.add(RequirementStatus.ItemStatus.builder()
                    .itemId(itemReq.getItemId())
                    .name(itemReq.getName())
                    .quantityRequired(itemReq.getQuantity())
                    .inInventory(inInventory)
                    .equipped(equipped)
                    .inBank(inBank)
                    .met(total >= itemReq.getQuantity())
                    .obtainableDuringQuest(itemReq.isObtainableDuringQuest())
                    .recommended(itemReq.isRecommended())
                    .alternateIds(itemReq.getAlternateIds())
                    .build());
        }
        
        return RequirementStatus.QuestRequirementsStatus.builder()
                .questId(questId)
                .questName(questName)
                .difficulty(difficulty)
                .members(members)
                .questPoints(questPoints)
                .state(state)
                .canStart(canStart)
                .skillRequirements(skillReqs)
                .questRequirements(questReqs)
                .itemRequirements(itemReqs)
                .build();
    }

    /**
     * Get requirements status for all quests (for status display).
     */
    public List<RequirementStatus.QuestRequirementsStatus> getAllQuestRequirementsStatus() {
        List<RequirementStatus.QuestRequirementsStatus> results = new ArrayList<>();
        Client client = clientProvider.get();
        
        // Iterate through all RuneLite quests
        for (net.runelite.api.Quest quest : net.runelite.api.Quest.values()) {
            try {
                RequirementStatus.QuestRequirementsStatus status = getRequirementsStatus(quest.name());
                results.add(status);
            } catch (Exception e) {
                log.trace("Could not get requirements for quest {}: {}", quest.name(), e.getMessage());
            }
        }
        
        return results;
    }

    // ========================================================================
    // Helper methods for requirement checking
    // ========================================================================

    private int getSkillLevel(Client client, String skillName) {
        try {
            Skill skill = Skill.valueOf(skillName.toUpperCase());
            return client.getRealSkillLevel(skill);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isQuestCompleted(String questId, Client client) {
        try {
            net.runelite.api.Quest quest = net.runelite.api.Quest.valueOf(questId);
            return quest.getState(client) == QuestState.FINISHED;
        } catch (Exception e) {
            return false;
        }
    }

    private String getQuestStateString(String questId, Client client) {
        try {
            net.runelite.api.Quest quest = net.runelite.api.Quest.valueOf(questId);
            QuestState state = quest.getState(client);
            return state != null ? state.name() : "NOT_STARTED";
        } catch (Exception e) {
            return "NOT_STARTED";
        }
    }

    private int countItemsInInventory(InventoryState inventory, List<Integer> itemIds) {
        int count = 0;
        for (int id : itemIds) {
            count += inventory.countItem(id);
        }
        return count;
    }

    private int countItemsEquipped(EquipmentState equipment, List<Integer> itemIds) {
        int count = 0;
        for (int id : itemIds) {
            if (equipment.hasEquipped(id)) {
                count++;
            }
        }
        return count;
    }

    private int countItemsInBank(BankState bank, List<Integer> itemIds) {
        if (bank.isUnknown()) {
            return 0; // Bank not observed yet
        }
        int count = 0;
        for (int id : itemIds) {
            count += bank.countItem(id);
        }
        return count;
    }
}
