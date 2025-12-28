package com.rocinante.progression;

import com.rocinante.core.GameStateService;
import com.rocinante.navigation.EdgeRequirement;
import com.rocinante.navigation.EdgeRequirementType;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Tracks unlocked features, abilities, and content.
 *
 * Per REQUIREMENTS.md Section 12.5, tracks:
 * <ul>
 *   <li>Prayer unlocks by level and quest</li>
 *   <li>Teleport methods: spells, jewelry, tablets, POH portals</li>
 *   <li>Transportation: fairy rings, spirit trees, gnome gliders</li>
 *   <li>Areas: quest-locked regions, skill-requirement areas</li>
 *   <li>Features: NPC contact, house tabs, etc.</li>
 * </ul>
 *
 * Navigation and planning systems query UnlockTracker to determine
 * available options for routing and method selection.
 */
@Slf4j
@Singleton
public class UnlockTracker {

    private final Client client;
    private final GameStateService gameStateService;

    // Cache of quest completion states (refreshed on demand)
    private final Map<Quest, Boolean> questCompletionCache = new HashMap<>();
    private int lastQuestCacheRefreshTick = -1;
    private static final int QUEST_CACHE_TTL_TICKS = 100; // Refresh every ~60 seconds

    // Cache of skill levels (refreshed from PlayerState)
    private final Map<Skill, Integer> skillLevelCache = new HashMap<>();

    @Inject
    public UnlockTracker(Client client, GameStateService gameStateService) {
        this.client = client;
        this.gameStateService = gameStateService;
        log.info("UnlockTracker initialized");
    }

    // ========================================================================
    // Prayer Unlocks
    // ========================================================================

    /**
     * Check if a specific prayer is unlocked for the player.
     *
     * @param prayer the RuneLite Prayer enum
     * @return true if the prayer is available
     */
    public boolean isPrayerUnlocked(Prayer prayer) {
        PrayerUnlock unlock = PrayerUnlock.forPrayer(prayer);
        if (unlock == null) {
            log.warn("Unknown prayer: {}", prayer);
            return false;
        }
        return isPrayerUnlockMet(unlock);
    }

    /**
     * Check if a PrayerUnlock's requirements are met.
     *
     * @param unlock the prayer unlock to check
     * @return true if requirements are met
     */
    public boolean isPrayerUnlockMet(PrayerUnlock unlock) {
        // Check Prayer level requirement
        int prayerLevel = getSkillLevel(Skill.PRAYER);
        if (prayerLevel < unlock.getRequiredLevel()) {
            log.debug("Prayer {} requires level {}, player has {}", 
                    unlock.name(), unlock.getRequiredLevel(), prayerLevel);
            return false;
        }

        // Check quest requirement if any
        if (unlock.requiresQuest()) {
            Quest requiredQuest = unlock.getRequiredQuest();
            if (!isQuestCompleted(requiredQuest)) {
                log.debug("Prayer {} requires quest {} to be completed", 
                        unlock.name(), requiredQuest.getName());
                return false;
            }
        }

        return true;
    }

    /**
     * Check if any protection prayer is unlocked.
     *
     * @return true if at least one protection prayer is available
     */
    public boolean hasAnyProtectionPrayer() {
        return isPrayerUnlockMet(PrayerUnlock.PROTECT_FROM_MAGIC) ||
               isPrayerUnlockMet(PrayerUnlock.PROTECT_FROM_MISSILES) ||
               isPrayerUnlockMet(PrayerUnlock.PROTECT_FROM_MELEE);
    }

    /**
     * Get all unlocked protection prayers.
     *
     * @return set of unlocked protection prayers
     */
    public Set<PrayerUnlock> getUnlockedProtectionPrayers() {
        Set<PrayerUnlock> unlocked = EnumSet.noneOf(PrayerUnlock.class);
        
        if (isPrayerUnlockMet(PrayerUnlock.PROTECT_FROM_MAGIC)) {
            unlocked.add(PrayerUnlock.PROTECT_FROM_MAGIC);
        }
        if (isPrayerUnlockMet(PrayerUnlock.PROTECT_FROM_MISSILES)) {
            unlocked.add(PrayerUnlock.PROTECT_FROM_MISSILES);
        }
        if (isPrayerUnlockMet(PrayerUnlock.PROTECT_FROM_MELEE)) {
            unlocked.add(PrayerUnlock.PROTECT_FROM_MELEE);
        }
        
        return unlocked;
    }

    /**
     * Get the best available protection prayer for an attack style.
     * Returns null if no suitable protection prayer is available.
     *
     * @param attackStyle the incoming attack style
     * @return the protection prayer to use, or null if none available
     */
    @Nullable
    public PrayerUnlock getBestProtectionPrayer(com.rocinante.state.AttackStyle attackStyle) {
        PrayerUnlock recommended = PrayerUnlock.getProtectionPrayer(attackStyle);
        
        if (isPrayerUnlockMet(recommended)) {
            return recommended;
        }
        
        // No protection prayer available for this style
        log.debug("Protection prayer for {} not available (requires level {})", 
                attackStyle, recommended.getRequiredLevel());
        return null;
    }

    /**
     * Get all unlocked offensive prayers.
     *
     * @return set of unlocked offensive prayers
     */
    public Set<PrayerUnlock> getUnlockedOffensivePrayers() {
        Set<PrayerUnlock> unlocked = EnumSet.noneOf(PrayerUnlock.class);
        
        for (PrayerUnlock prayer : PrayerUnlock.values()) {
            if (prayer.isOffensivePrayer() && isPrayerUnlockMet(prayer)) {
                unlocked.add(prayer);
            }
        }
        
        return unlocked;
    }

    /**
     * Get the best available offensive prayer for a combat style.
     *
     * @param attackStyle melee, ranged, or magic
     * @return the best offensive prayer available, or null
     */
    @Nullable
    public PrayerUnlock getBestOffensivePrayer(com.rocinante.state.AttackStyle attackStyle) {
        // Check from highest to lowest tier
        switch (attackStyle) {
            case MELEE:
                if (isPrayerUnlockMet(PrayerUnlock.PIETY)) return PrayerUnlock.PIETY;
                if (isPrayerUnlockMet(PrayerUnlock.CHIVALRY)) return PrayerUnlock.CHIVALRY;
                if (isPrayerUnlockMet(PrayerUnlock.ULTIMATE_STRENGTH)) return PrayerUnlock.ULTIMATE_STRENGTH;
                if (isPrayerUnlockMet(PrayerUnlock.SUPERHUMAN_STRENGTH)) return PrayerUnlock.SUPERHUMAN_STRENGTH;
                if (isPrayerUnlockMet(PrayerUnlock.BURST_OF_STRENGTH)) return PrayerUnlock.BURST_OF_STRENGTH;
                break;
            case RANGED:
                if (isPrayerUnlockMet(PrayerUnlock.RIGOUR)) return PrayerUnlock.RIGOUR;
                if (isPrayerUnlockMet(PrayerUnlock.EAGLE_EYE)) return PrayerUnlock.EAGLE_EYE;
                if (isPrayerUnlockMet(PrayerUnlock.HAWK_EYE)) return PrayerUnlock.HAWK_EYE;
                if (isPrayerUnlockMet(PrayerUnlock.SHARP_EYE)) return PrayerUnlock.SHARP_EYE;
                break;
            case MAGIC:
                if (isPrayerUnlockMet(PrayerUnlock.AUGURY)) return PrayerUnlock.AUGURY;
                if (isPrayerUnlockMet(PrayerUnlock.MYSTIC_MIGHT)) return PrayerUnlock.MYSTIC_MIGHT;
                if (isPrayerUnlockMet(PrayerUnlock.MYSTIC_LORE)) return PrayerUnlock.MYSTIC_LORE;
                if (isPrayerUnlockMet(PrayerUnlock.MYSTIC_WILL)) return PrayerUnlock.MYSTIC_WILL;
                break;
            default:
                break;
        }
        return null;
    }

    // ========================================================================
    // Skill Level Tracking
    // ========================================================================

    /**
     * Get the player's level in a skill.
     *
     * @param skill the skill to check
     * @return the real (not boosted) level
     */
    public int getSkillLevel(Skill skill) {
        // Try to get from client directly first
        if (client.getGameState().getState() >= net.runelite.api.GameState.LOGGED_IN.getState()) {
            try {
                int level = client.getRealSkillLevel(skill);
                skillLevelCache.put(skill, level);
                return level;
            } catch (Exception e) {
                // Fall back to cache
            }
        }
        
        // Use cached value
        return skillLevelCache.getOrDefault(skill, 1);
    }

    /**
     * Check if the player meets a skill level requirement.
     *
     * @param skill the skill to check
     * @param requiredLevel the minimum level required
     * @return true if requirement is met
     */
    public boolean meetsSkillRequirement(Skill skill, int requiredLevel) {
        return getSkillLevel(skill) >= requiredLevel;
    }

    /**
     * Check if the player has Prayer unlocked at all (level 1+).
     * This is always true for accounts that have completed Tutorial Island.
     *
     * @return true if Prayer skill is available
     */
    public boolean hasPrayerSkill() {
        return getSkillLevel(Skill.PRAYER) >= 1;
    }

    /**
     * Get the player's combat level.
     *
     * @return combat level, or 3 if unavailable
     */
    public int getCombatLevel() {
        try {
            if (client.getLocalPlayer() != null) {
                return client.getLocalPlayer().getCombatLevel();
            }
        } catch (Exception e) {
            log.trace("Error getting combat level: {}", e.getMessage());
        }
        return 3; // Default minimum combat level
    }

    // ========================================================================
    // Quest Tracking
    // ========================================================================

    /**
     * Check if a quest has been completed.
     *
     * @param quest the quest to check
     * @return true if completed
     */
    public boolean isQuestCompleted(Quest quest) {
        refreshQuestCacheIfNeeded();
        
        // Check cache first
        Boolean cached = questCompletionCache.get(quest);
        if (cached != null) {
            return cached;
        }
        
        // Query client
        try {
            QuestState state = quest.getState(client);
            boolean completed = state == QuestState.FINISHED;
            questCompletionCache.put(quest, completed);
            return completed;
        } catch (Exception e) {
            log.warn("Failed to check quest state for {}: {}", quest.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Refresh quest cache if TTL has expired.
     */
    private void refreshQuestCacheIfNeeded() {
        int currentTick = gameStateService.getCurrentTick();
        if (currentTick - lastQuestCacheRefreshTick > QUEST_CACHE_TTL_TICKS) {
            questCompletionCache.clear();
            lastQuestCacheRefreshTick = currentTick;
        }
    }

    /**
     * Force refresh of quest completion cache.
     */
    public void refreshQuestCache() {
        questCompletionCache.clear();
        lastQuestCacheRefreshTick = -1;
    }

    // ========================================================================
    // Edge Requirement Checking (for WebWalker integration)
    // ========================================================================

    /**
     * Check if an edge requirement is met.
     * Used by WebWalker for pathfinding to determine if an edge can be traversed.
     *
     * @param requirement the edge requirement to check
     * @return true if the requirement is met
     */
    public boolean isEdgeRequirementMet(EdgeRequirement requirement) {
        if (requirement == null) {
            return true;
        }

        EdgeRequirementType type = requirement.getType();
        if (type == null) {
            log.warn("EdgeRequirement has null type");
            return false;
        }

        switch (type) {
            case MAGIC_LEVEL:
                return getSkillLevel(Skill.MAGIC) >= requirement.getValue();

            case AGILITY_LEVEL:
                return getSkillLevel(Skill.AGILITY) >= requirement.getValue();

            case COMBAT_LEVEL:
                return getCombatLevel() >= requirement.getValue();

            case SKILL:
                return checkSkillRequirement(requirement);

            case QUEST:
                return checkQuestRequirement(requirement);

            case ITEM:
                return checkItemRequirement(requirement);

            case RUNES:
                return checkRuneRequirement(requirement);

            case IRONMAN_RESTRICTION:
                // Ironman restrictions are handled by WebWalker itself
                // based on account type, not by UnlockTracker
                return true;

            default:
                log.warn("Unknown EdgeRequirementType: {}", type);
                return false;
        }
    }

    /**
     * Check all requirements for an edge.
     *
     * @param requirements list of requirements
     * @return true if all requirements are met
     */
    public boolean areAllRequirementsMet(List<EdgeRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return true;
        }
        return requirements.stream().allMatch(this::isEdgeRequirementMet);
    }

    /**
     * Check a SKILL type requirement.
     */
    private boolean checkSkillRequirement(EdgeRequirement requirement) {
        String skillName = requirement.getIdentifier();
        if (skillName == null || skillName.isEmpty()) {
            log.warn("SKILL requirement missing identifier");
            return false;
        }

        try {
            Skill skill = Skill.valueOf(skillName.toUpperCase());
            return getSkillLevel(skill) >= requirement.getValue();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown skill in requirement: {}", skillName);
            return false;
        }
    }

    /**
     * Check a QUEST type requirement.
     */
    private boolean checkQuestRequirement(EdgeRequirement requirement) {
        String questId = requirement.getIdentifier();
        if (questId == null || questId.isEmpty()) {
            log.warn("QUEST requirement missing identifier");
            return false;
        }

        try {
            Quest quest = Quest.valueOf(questId.toUpperCase());
            
            // Check for specific quest state requirement
            String requiredState = requirement.getQuestState();
            if (requiredState != null && !requiredState.isEmpty()) {
                // Allow partial completion checks (e.g., started but not finished)
                QuestState state = quest.getState(client);
                if ("STARTED".equalsIgnoreCase(requiredState)) {
                    return state == QuestState.IN_PROGRESS || state == QuestState.FINISHED;
                } else if ("FINISHED".equalsIgnoreCase(requiredState)) {
                    return state == QuestState.FINISHED;
                }
                // Default: check if finished
                return state == QuestState.FINISHED;
            }
            
            // Default: check if quest is completed
            return isQuestCompleted(quest);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown quest in requirement: {}", questId);
            return false;
        } catch (Exception e) {
            log.warn("Error checking quest requirement for {}: {}", questId, e.getMessage());
            return false;
        }
    }

    /**
     * Check an ITEM type requirement.
     */
    private boolean checkItemRequirement(EdgeRequirement requirement) {
        int itemId = requirement.getItemId();
        int quantity = Math.max(1, requirement.getValue());

        InventoryState inventory = gameStateService.getInventoryState();
        if (inventory == null) {
            log.debug("Inventory state not available for item requirement check");
            return false;
        }

        int count = inventory.countItem(itemId);
        boolean met = count >= quantity;
        
        if (!met) {
            log.trace("Item requirement not met: need {} of item {}, have {}", 
                    quantity, itemId, count);
        }
        
        return met;
    }

    /**
     * Check a RUNES type requirement.
     */
    private boolean checkRuneRequirement(EdgeRequirement requirement) {
        List<EdgeRequirement.RuneCost> runeCosts = requirement.getRuneCosts();
        if (runeCosts == null || runeCosts.isEmpty()) {
            return true;
        }

        InventoryState inventory = gameStateService.getInventoryState();
        if (inventory == null) {
            log.debug("Inventory state not available for rune requirement check");
            return false;
        }

        for (EdgeRequirement.RuneCost cost : runeCosts) {
            int count = inventory.countItem(cost.getItemId());
            if (count < cost.getQuantity()) {
                log.trace("Rune requirement not met: need {} of rune {}, have {}", 
                        cost.getQuantity(), cost.getItemId(), count);
                return false;
            }
        }

        return true;
    }

    /**
     * Check if player has a specific item in inventory.
     *
     * @param itemId the item ID
     * @param quantity minimum quantity required
     * @return true if player has enough of the item
     */
    public boolean hasItem(int itemId, int quantity) {
        InventoryState inventory = gameStateService.getInventoryState();
        if (inventory == null) {
            return false;
        }
        return inventory.countItem(itemId) >= quantity;
    }

    /**
     * Check if player has a specific item in inventory.
     *
     * @param itemId the item ID
     * @return true if player has at least one of the item
     */
    public boolean hasItem(int itemId) {
        return hasItem(itemId, 1);
    }

    // ========================================================================
    // Generic Unlock Checks
    // ========================================================================

    /**
     * Check if a feature is unlocked.
     * This is a generic method for checking various unlock types.
     *
     * @param type the type of unlock
     * @param identifier the specific feature identifier
     * @return true if unlocked
     */
    public boolean isUnlocked(UnlockType type, String identifier) {
        switch (type) {
            case PRAYER:
                try {
                    Prayer prayer = Prayer.valueOf(identifier);
                    return isPrayerUnlocked(prayer);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown prayer identifier: {}", identifier);
                    return false;
                }
            case QUEST:
                try {
                    Quest quest = Quest.valueOf(identifier);
                    return isQuestCompleted(quest);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown quest identifier: {}", identifier);
                    return false;
                }
            case SKILL:
                // Format: "SKILL_NAME:LEVEL" e.g., "PRAYER:43"
                String[] parts = identifier.split(":");
                if (parts.length == 2) {
                    try {
                        Skill skill = Skill.valueOf(parts[0]);
                        int level = Integer.parseInt(parts[1]);
                        return meetsSkillRequirement(skill, level);
                    } catch (Exception e) {
                        log.warn("Invalid skill requirement format: {}", identifier);
                    }
                }
                return false;
            case TELEPORT:
                return isTeleportUnlocked(identifier);
            case TRANSPORTATION:
                return isTransportationUnlocked(identifier);
            case AREA:
                return isAreaUnlocked(identifier);
            case FEATURE:
                return isFeatureUnlocked(identifier);
            default:
                return false;
        }
    }

    // ========================================================================
    // Teleport Unlock Checks
    // ========================================================================

    /**
     * Teleport spell requirements: spell name -> (magic level, quest name or null).
     */
    private static final Map<String, int[]> TELEPORT_SPELL_REQUIREMENTS = new HashMap<>();
    static {
        // Format: magic level, then 0 = no quest, or varbit/quest ID
        TELEPORT_SPELL_REQUIREMENTS.put("VARROCK_TELEPORT", new int[]{25});
        TELEPORT_SPELL_REQUIREMENTS.put("LUMBRIDGE_TELEPORT", new int[]{31});
        TELEPORT_SPELL_REQUIREMENTS.put("FALADOR_TELEPORT", new int[]{37});
        TELEPORT_SPELL_REQUIREMENTS.put("HOUSE_TELEPORT", new int[]{40});
        TELEPORT_SPELL_REQUIREMENTS.put("CAMELOT_TELEPORT", new int[]{45});
        TELEPORT_SPELL_REQUIREMENTS.put("ARDOUGNE_TELEPORT", new int[]{51}); // Requires Plague City
        TELEPORT_SPELL_REQUIREMENTS.put("WATCHTOWER_TELEPORT", new int[]{58}); // Requires Watchtower
        TELEPORT_SPELL_REQUIREMENTS.put("TROLLHEIM_TELEPORT", new int[]{61}); // Requires Eadgar's Ruse
        TELEPORT_SPELL_REQUIREMENTS.put("APE_ATOLL_TELEPORT", new int[]{64}); // Requires RFD
        TELEPORT_SPELL_REQUIREMENTS.put("KOUREND_TELEPORT", new int[]{69}); // Requires Client of Kourend favor
    }

    /**
     * Check if a teleport method is unlocked.
     *
     * @param identifier teleport identifier (spell name, jewelry type, etc.)
     * @return true if the teleport is available
     */
    private boolean isTeleportUnlocked(String identifier) {
        // Check spell teleports
        int[] spellReqs = TELEPORT_SPELL_REQUIREMENTS.get(identifier.toUpperCase());
        if (spellReqs != null) {
            int magicLevel = getSkillLevel(Skill.MAGIC);
            return magicLevel >= spellReqs[0];
        }

        // Check jewelry teleports (requires having the jewelry)
        // Format: JEWELRY_<TYPE>_<DESTINATION> e.g., JEWELRY_RING_OF_DUELING_DUEL_ARENA
        if (identifier.startsWith("JEWELRY_")) {
            // For jewelry, we just need to own it - specific check would require item IDs
            log.debug("Jewelry teleport {} check - assuming available if player has item", identifier);
            return true; // Would need item ID lookup
        }

        // Check tablet teleports
        if (identifier.startsWith("TABLET_")) {
            log.debug("Tablet teleport {} check - assuming available if player has item", identifier);
            return true; // Would need item ID lookup
        }

        // Check POH portal teleports (requires house and portal room)
        if (identifier.startsWith("POH_PORTAL_")) {
            // Requires construction level and materials to build
            int constructionLevel = getSkillLevel(Skill.CONSTRUCTION);
            return constructionLevel >= 50; // Basic portal room requirement
        }

        log.debug("Unknown teleport identifier: {}", identifier);
        return false;
    }

    // ========================================================================
    // Transportation Unlock Checks
    // ========================================================================

    /**
     * Check if a transportation method is unlocked.
     *
     * @param identifier transportation identifier
     * @return true if the transport is available
     */
    private boolean isTransportationUnlocked(String identifier) {
        String upper = identifier.toUpperCase();

        // Fairy rings - requires started Fairy Tale II or Lumbridge Elite diary
        if (upper.startsWith("FAIRY_RING")) {
            return isQuestStarted(Quest.FAIRYTALE_II__CURE_A_QUEEN)
                    || isDiaryComplete("LUMBRIDGE_ELITE");
        }

        // Spirit trees - requires started Tree Gnome Village
        if (upper.startsWith("SPIRIT_TREE")) {
            return isQuestStarted(Quest.TREE_GNOME_VILLAGE);
        }

        // Gnome gliders - requires completion of The Grand Tree
        if (upper.startsWith("GNOME_GLIDER")) {
            return isQuestCompleted(Quest.THE_GRAND_TREE);
        }

        // Canoes - requires woodcutting level (12+ for basic)
        if (upper.startsWith("CANOE")) {
            int wcLevel = getSkillLevel(Skill.WOODCUTTING);
            if (upper.contains("LOG")) return wcLevel >= 12;
            if (upper.contains("DUGOUT")) return wcLevel >= 27;
            if (upper.contains("STABLE")) return wcLevel >= 42;
            if (upper.contains("WAKA")) return wcLevel >= 57;
            return wcLevel >= 12;
        }

        // Charter ships - generally always available
        if (upper.startsWith("CHARTER_SHIP")) {
            return true;
        }

        // Balloon transport - requires Enlightened Journey
        if (upper.startsWith("BALLOON")) {
            return isQuestCompleted(Quest.ENLIGHTENED_JOURNEY);
        }

        log.debug("Unknown transportation identifier: {}", identifier);
        return false;
    }

    /**
     * Check if a quest is started (but not necessarily completed).
     */
    private boolean isQuestStarted(Quest quest) {
        QuestState state = quest.getState(client);
        return state == QuestState.IN_PROGRESS || state == QuestState.FINISHED;
    }

    /**
     * Check if an achievement diary tier is complete.
     * Placeholder - would need varbit checks for actual implementation.
     */
    private boolean isDiaryComplete(String diaryTier) {
        // TODO: Implement diary completion checks via varbits
        log.debug("Diary completion check not implemented: {}", diaryTier);
        return false;
    }

    // ========================================================================
    // Area Unlock Checks
    // ========================================================================

    /**
     * Quest requirements for area access.
     */
    private static final Map<String, Quest> AREA_QUEST_REQUIREMENTS = new HashMap<>();
    static {
        AREA_QUEST_REQUIREMENTS.put("MORYTANIA", Quest.PRIEST_IN_PERIL);
        AREA_QUEST_REQUIREMENTS.put("KOUREND", null); // No quest required, just get there
        AREA_QUEST_REQUIREMENTS.put("PRIFDDINAS", Quest.SONG_OF_THE_ELVES);
        AREA_QUEST_REQUIREMENTS.put("ZANARIS", Quest.LOST_CITY);
        AREA_QUEST_REQUIREMENTS.put("APE_ATOLL", Quest.MONKEY_MADNESS_I);
        AREA_QUEST_REQUIREMENTS.put("TROLL_STRONGHOLD", Quest.TROLL_STRONGHOLD);
        AREA_QUEST_REQUIREMENTS.put("WATERBIRTH_ISLAND", null); // Requires boat but no quest
    }

    /**
     * Check if an area is unlocked for the player.
     *
     * @param identifier area identifier
     * @return true if the area is accessible
     */
    private boolean isAreaUnlocked(String identifier) {
        String upper = identifier.toUpperCase();

        // Check quest-gated areas
        Quest requiredQuest = AREA_QUEST_REQUIREMENTS.get(upper);
        if (requiredQuest != null) {
            return isQuestCompleted(requiredQuest);
        }

        // If area is known but has no quest requirement, it's unlocked
        if (AREA_QUEST_REQUIREMENTS.containsKey(upper)) {
            return true;
        }

        // Check skill-gated areas
        if (upper.contains("AGILITY_SHORTCUT")) {
            // Format: AGILITY_SHORTCUT_XX where XX is the level
            String[] parts = upper.split("_");
            if (parts.length >= 3) {
                try {
                    int level = Integer.parseInt(parts[parts.length - 1]);
                    return getSkillLevel(Skill.AGILITY) >= level;
                } catch (NumberFormatException e) {
                    // Not a level-based check
                }
            }
        }

        // Unknown area - assume accessible (conservative default)
        log.debug("Unknown area identifier: {}, assuming accessible", identifier);
        return true;
    }

    // ========================================================================
    // Feature Unlock Checks
    // ========================================================================

    /**
     * Check if a game feature is unlocked.
     *
     * @param identifier feature identifier
     * @return true if the feature is available
     */
    private boolean isFeatureUnlocked(String identifier) {
        String upper = identifier.toUpperCase();

        // NPC Contact spell - requires Lunar Diplomacy and 67 Magic
        if (upper.equals("NPC_CONTACT")) {
            return isQuestCompleted(Quest.LUNAR_DIPLOMACY) && getSkillLevel(Skill.MAGIC) >= 67;
        }

        // House tabs - requires 40 Construction
        if (upper.equals("HOUSE_TABS") || upper.equals("TELEPORT_TO_HOUSE_TABS")) {
            return getSkillLevel(Skill.CONSTRUCTION) >= 40;
        }

        // Fairy ring without staff - requires Lumbridge Elite diary
        if (upper.equals("FAIRY_RING_NO_STAFF")) {
            return isDiaryComplete("LUMBRIDGE_ELITE");
        }

        // Spirit tree planting - requires 83 Farming
        if (upper.equals("PLANT_SPIRIT_TREE")) {
            return getSkillLevel(Skill.FARMING) >= 83;
        }

        // Deposit box access - generally available
        if (upper.equals("DEPOSIT_BOX")) {
            return true;
        }

        // GE access - requires completion of tutorial
        if (upper.equals("GRAND_EXCHANGE")) {
            return true; // Would need to check tutorial completion
        }

        // Blast furnace - requires 60 Smithing or payment
        if (upper.equals("BLAST_FURNACE")) {
            return getSkillLevel(Skill.SMITHING) >= 60;
        }

        // Farming guild - requires 45/65/85 Farming for tiers
        if (upper.startsWith("FARMING_GUILD")) {
            if (upper.contains("ADVANCED")) return getSkillLevel(Skill.FARMING) >= 85;
            if (upper.contains("INTERMEDIATE")) return getSkillLevel(Skill.FARMING) >= 65;
            return getSkillLevel(Skill.FARMING) >= 45;
        }

        // Warrior's guild - requires 130 combined Attack + Strength
        if (upper.equals("WARRIORS_GUILD")) {
            return getSkillLevel(Skill.ATTACK) + getSkillLevel(Skill.STRENGTH) >= 130;
        }

        log.debug("Unknown feature identifier: {}", identifier);
        return false;
    }

    // ========================================================================
    // Summary Methods
    // ========================================================================

    /**
     * Get a summary of prayer availability.
     *
     * @return summary string
     */
    public String getPrayerSummary() {
        int prayerLevel = getSkillLevel(Skill.PRAYER);
        Set<PrayerUnlock> protections = getUnlockedProtectionPrayers();
        
        StringBuilder sb = new StringBuilder("Prayer Summary: Level ");
        sb.append(prayerLevel);
        sb.append(", Protection Prayers: ");
        
        if (protections.isEmpty()) {
            sb.append("NONE");
        } else {
            sb.append(protections);
        }
        
        return sb.toString();
    }

    /**
     * Log current unlock status for debugging.
     */
    public void logUnlockStatus() {
        log.info("=== Unlock Status ===");
        log.info("Prayer Level: {}", getSkillLevel(Skill.PRAYER));
        log.info("Has Protection Prayers: {}", hasAnyProtectionPrayer());
        log.info("Unlocked Protections: {}", getUnlockedProtectionPrayers());
        log.info("Best Melee Offensive: {}", getBestOffensivePrayer(com.rocinante.state.AttackStyle.MELEE));
        log.info("Best Ranged Offensive: {}", getBestOffensivePrayer(com.rocinante.state.AttackStyle.RANGED));
        log.info("Best Magic Offensive: {}", getBestOffensivePrayer(com.rocinante.state.AttackStyle.MAGIC));
        log.info("====================");
    }
}

