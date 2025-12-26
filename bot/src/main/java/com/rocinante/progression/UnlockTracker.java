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
            case TRANSPORTATION:
            case AREA:
            case FEATURE:
                // TODO: Implement these unlock types as needed
                log.debug("Unlock type {} not yet implemented for: {}", type, identifier);
                return false;
            default:
                return false;
        }
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

