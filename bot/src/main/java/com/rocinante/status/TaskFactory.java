package com.rocinante.status;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rocinante.combat.SelectionPriority;
import com.rocinante.combat.TargetSelectorConfig;
import com.rocinante.combat.WeaponStyle;
import com.rocinante.combat.XpGoal;
import com.rocinante.progression.TrainingMethod;
import com.rocinante.progression.TrainingMethodRepository;
import com.rocinante.quest.Quest;
import com.rocinante.quest.QuestExecutor;
import com.rocinante.quest.QuestService;
import com.rocinante.combat.CombatManager;
import com.rocinante.combat.TargetSelector;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.impl.CombatTask;
import com.rocinante.tasks.impl.CombatTaskConfig;
import com.rocinante.tasks.impl.SkillTask;
import com.rocinante.tasks.impl.SkillTaskConfig;
import com.rocinante.tasks.impl.WalkToTask;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.Optional;

/**
 * Factory for creating executable Task objects from JSON specifications.
 *
 * <p>Supports the following task types:
 * <ul>
 *   <li>SKILL - Skill training tasks using SkillTaskConfig</li>
 *   <li>COMBAT - Combat tasks using CombatTaskConfig</li>
 *   <li>NAVIGATION - Walk to a location using WalkToTask</li>
 *   <li>QUEST - Quest execution tasks</li>
 * </ul>
 *
 * <p>Task specifications are JSON objects with a "taskType" field and type-specific parameters.
 */
@Slf4j
@Singleton
public class TaskFactory {

    private final TrainingMethodRepository trainingMethodRepository;
    
    @Nullable
    private QuestExecutor questExecutor;

    @Nullable
    private TargetSelector targetSelector;

    @Nullable
    private CombatManager combatManager;

    @Nullable
    private QuestService questService;

    @Inject
    public TaskFactory(TrainingMethodRepository trainingMethodRepository) {
        this.trainingMethodRepository = trainingMethodRepository;
        log.info("TaskFactory initialized");
    }

    /**
     * Set the quest executor for quest task creation.
     *
     * @param questExecutor the quest executor
     */
    public void setQuestExecutor(@Nullable QuestExecutor questExecutor) {
        this.questExecutor = questExecutor;
    }

    /**
     * Set the target selector for combat task creation.
     *
     * @param targetSelector the target selector
     */
    public void setTargetSelector(@Nullable TargetSelector targetSelector) {
        this.targetSelector = targetSelector;
    }

    /**
     * Set the combat manager for combat task creation.
     *
     * @param combatManager the combat manager
     */
    public void setCombatManager(@Nullable CombatManager combatManager) {
        this.combatManager = combatManager;
    }

    /**
     * Set the quest service for quest loading.
     *
     * @param questService the quest service
     */
    public void setQuestService(@Nullable QuestService questService) {
        this.questService = questService;
    }

    /**
     * Create a task from a JSON specification.
     *
     * @param taskSpec the JSON task specification
     * @return the created task, or empty if creation failed
     */
    public Optional<Task> createTask(JsonObject taskSpec) {
        if (taskSpec == null) {
            log.warn("Cannot create task from null spec");
            return Optional.empty();
        }

        String taskType = taskSpec.has("taskType") ? taskSpec.get("taskType").getAsString() : null;
        if (taskType == null || taskType.isEmpty()) {
            log.warn("Task spec missing 'taskType' field");
            return Optional.empty();
        }

        try {
            switch (taskType.toUpperCase()) {
                case "SKILL":
                    return createSkillTask(taskSpec);
                case "COMBAT":
                    return createCombatTask(taskSpec);
                case "NAVIGATION":
                    return createNavigationTask(taskSpec);
                case "QUEST":
                    return createQuestTask(taskSpec);
                default:
                    log.warn("Unknown task type: {}", taskType);
                    return Optional.empty();
            }
        } catch (Exception e) {
            log.error("Failed to create task from spec: {}", taskSpec, e);
            return Optional.empty();
        }
    }

    // ========================================================================
    // Skill Task Creation
    // ========================================================================

    /**
     * Create a skill training task from JSON spec.
     *
     * Expected fields:
     * - methodId: String - Training method ID (required)
     * - targetType: String - "LEVEL", "XP", or "DURATION" (required)
     * - targetValue: Number - Target level, XP, or duration in minutes (required)
     * - bankInsteadOfDrop: Boolean - Override banking behavior (optional)
     * - useWorldHopping: Boolean - Enable world hopping (optional)
     * - worldHopThreshold: Number - Players to trigger hop (optional)
     */
    private Optional<Task> createSkillTask(JsonObject spec) {
        // Required: methodId
        if (!spec.has("methodId")) {
            log.warn("Skill task missing 'methodId'");
            return Optional.empty();
        }
        String methodId = spec.get("methodId").getAsString();

        // Resolve training method
        Optional<TrainingMethod> methodOpt = trainingMethodRepository.getMethodById(methodId);
        if (methodOpt.isEmpty()) {
            log.warn("Unknown training method ID: {}", methodId);
            return Optional.empty();
        }
        TrainingMethod method = methodOpt.get();

        // Required: targetType and targetValue
        if (!spec.has("targetType") || !spec.has("targetValue")) {
            log.warn("Skill task missing 'targetType' or 'targetValue'");
            return Optional.empty();
        }
        String targetType = spec.get("targetType").getAsString().toUpperCase();
        long targetValue = spec.get("targetValue").getAsLong();

        // Build config
        SkillTaskConfig.SkillTaskConfigBuilder builder = SkillTaskConfig.builder()
                .skill(method.getSkill())
                .method(method);

        // Set target based on type
        switch (targetType) {
            case "LEVEL":
                if (targetValue < 1 || targetValue > 99) {
                    log.warn("Invalid target level: {}", targetValue);
                    return Optional.empty();
                }
                builder.targetLevel((int) targetValue);
                break;
            case "XP":
                if (targetValue < 0) {
                    log.warn("Invalid target XP: {}", targetValue);
                    return Optional.empty();
                }
                builder.targetXp(targetValue);
                break;
            case "DURATION":
                builder.targetLevel(99); // Train indefinitely until time limit
                builder.maxDuration(Duration.ofMinutes(targetValue));
                break;
            default:
                log.warn("Unknown target type: {}", targetType);
                return Optional.empty();
        }

        // Optional: banking behavior override
        if (spec.has("bankInsteadOfDrop")) {
            builder.bankInsteadOfDrop(spec.get("bankInsteadOfDrop").getAsBoolean());
        }

        // Optional: world hopping
        if (spec.has("useWorldHopping")) {
            builder.useWorldHopping(spec.get("useWorldHopping").getAsBoolean());
        }
        if (spec.has("worldHopThreshold")) {
            builder.worldHopThreshold(spec.get("worldHopThreshold").getAsInt());
        }

        // Optional: food management
        if (spec.has("minFoodCount")) {
            builder.minFoodCount(spec.get("minFoodCount").getAsInt());
        }
        if (spec.has("foodItemId")) {
            builder.foodItemId(spec.get("foodItemId").getAsInt());
        }

        // Optional: specific location within the method
        if (spec.has("locationId")) {
            builder.locationId(spec.get("locationId").getAsString());
        }

        SkillTaskConfig config = builder.build();
        
        try {
            config.validate();
        } catch (IllegalStateException e) {
            log.warn("Invalid skill task config: {}", e.getMessage());
            return Optional.empty();
        }

        log.info("Created skill task: {}", config.getSummary());
        return Optional.of(new SkillTask(config));
    }

    // ========================================================================
    // Combat Task Creation
    // ========================================================================

    /**
     * Create a combat task from JSON spec.
     *
     * Expected fields:
     * - targetNpcs: Array of String - NPC names to target (required if no targetNpcIds)
     * - targetNpcIds: Array of Number - NPC IDs to target (optional)
     * - completionType: String - "KILL_COUNT" or "DURATION" (required)
     * - completionValue: Number - Kill count or duration in minutes (required)
     * - lootEnabled: Boolean - Enable looting (optional, default true)
     * - lootMinValue: Number - Minimum loot value (optional, default 1000)
     * - weaponStyle: String - SLASH, STAB, CRUSH, RANGED, MAGIC, or ANY (optional)
     * - xpGoal: String - ATTACK, STRENGTH, DEFENCE, etc. (optional)
     * - useSafeSpot: Boolean - Enable safe-spotting (optional)
     * - safeSpotX, safeSpotY, safeSpotPlane: Numbers - Safe spot position (optional)
     * - buryBonesEnabled: Boolean - Enable bone burying while fighting (optional)
     * - buryBonesMinKills: Number - Min kills before burying (optional, default 2)
     * - buryBonesMinSeconds: Number - Min seconds since kill before burying (optional, default 60)
     * - buryBonesMaxRatio: Number - Max bones to bury per kill (optional, default 2)
     */
    private Optional<Task> createCombatTask(JsonObject spec) {
        CombatTaskConfig.CombatTaskConfigBuilder configBuilder = CombatTaskConfig.builder();
        TargetSelectorConfig.TargetSelectorConfigBuilder targetBuilder = TargetSelectorConfig.builder();

        // Target selection - require at least one of names or IDs
        boolean hasTargets = false;
        
        if (spec.has("targetNpcs")) {
            JsonArray npcs = spec.getAsJsonArray("targetNpcs");
            for (JsonElement elem : npcs) {
                targetBuilder.targetNpcName(elem.getAsString());
                hasTargets = true;
            }
            targetBuilder.priority(SelectionPriority.SPECIFIC_NAME);
        }
        
        if (spec.has("targetNpcIds")) {
            JsonArray ids = spec.getAsJsonArray("targetNpcIds");
            for (JsonElement elem : ids) {
                targetBuilder.targetNpcId(elem.getAsInt());
                hasTargets = true;
            }
            targetBuilder.priority(SelectionPriority.SPECIFIC_ID);
        }

        if (!hasTargets) {
            log.warn("Combat task missing target NPCs");
            return Optional.empty();
        }

        // Add default priorities
        targetBuilder.priority(SelectionPriority.TARGETING_PLAYER);
        targetBuilder.priority(SelectionPriority.NEAREST);
        targetBuilder.searchRadius(15);
        targetBuilder.skipInCombatWithOthers(true);
        targetBuilder.skipUnreachable(true);

        configBuilder.targetConfig(targetBuilder.build());

        // Completion conditions
        if (!spec.has("completionType") || !spec.has("completionValue")) {
            log.warn("Combat task missing 'completionType' or 'completionValue'");
            return Optional.empty();
        }
        String completionType = spec.get("completionType").getAsString().toUpperCase();
        int completionValue = spec.get("completionValue").getAsInt();

        switch (completionType) {
            case "KILL_COUNT":
                configBuilder.killCount(completionValue);
                configBuilder.maxDuration(Duration.ofHours(4)); // Safety limit
                break;
            case "DURATION":
                configBuilder.killCount(-1);
                configBuilder.maxDuration(Duration.ofMinutes(completionValue));
                break;
            default:
                log.warn("Unknown completion type: {}", completionType);
                return Optional.empty();
        }

        // Loot settings
        if (spec.has("lootEnabled")) {
            configBuilder.lootEnabled(spec.get("lootEnabled").getAsBoolean());
        } else {
            configBuilder.lootEnabled(true);
        }
        if (spec.has("lootMinValue")) {
            configBuilder.lootMinValue(spec.get("lootMinValue").getAsInt());
        } else {
            configBuilder.lootMinValue(1000);
        }

        // Weapon style
        if (spec.has("weaponStyle")) {
            try {
                WeaponStyle style = WeaponStyle.valueOf(spec.get("weaponStyle").getAsString().toUpperCase());
                configBuilder.weaponStyle(style);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown weapon style: {}", spec.get("weaponStyle").getAsString());
            }
        }

        // XP goal
        if (spec.has("xpGoal")) {
            try {
                XpGoal goal = XpGoal.valueOf(spec.get("xpGoal").getAsString().toUpperCase());
                configBuilder.xpGoal(goal);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown XP goal: {}", spec.get("xpGoal").getAsString());
            }
        }

        // Safe-spotting
        if (spec.has("useSafeSpot") && spec.get("useSafeSpot").getAsBoolean()) {
            configBuilder.useSafeSpot(true);
            if (spec.has("safeSpotX") && spec.has("safeSpotY")) {
                int x = spec.get("safeSpotX").getAsInt();
                int y = spec.get("safeSpotY").getAsInt();
                int plane = spec.has("safeSpotPlane") ? spec.get("safeSpotPlane").getAsInt() : 0;
                configBuilder.safeSpotPosition(new WorldPoint(x, y, plane));
            }
        }

        // Resource management
        if (spec.has("stopWhenOutOfFood")) {
            configBuilder.stopWhenOutOfFood(spec.get("stopWhenOutOfFood").getAsBoolean());
        }
        if (spec.has("enableResupply")) {
            configBuilder.enableResupply(spec.get("enableResupply").getAsBoolean());
        }

        // Bone burying configuration
        if (spec.has("buryBonesEnabled")) {
            configBuilder.buryBonesEnabled(spec.get("buryBonesEnabled").getAsBoolean());
        }
        if (spec.has("buryBonesMinKills")) {
            configBuilder.buryBonesMinKillsBeforeBury(spec.get("buryBonesMinKills").getAsInt());
        }
        if (spec.has("buryBonesMinSeconds")) {
            configBuilder.buryBonesMinSecondsBeforeBury(spec.get("buryBonesMinSeconds").getAsInt());
        }
        if (spec.has("buryBonesMaxRatio")) {
            configBuilder.buryBonesMaxRatio(spec.get("buryBonesMaxRatio").getAsInt());
        }

        // Check for required dependencies
        if (targetSelector == null || combatManager == null) {
            log.warn("Cannot create combat task: TargetSelector or CombatManager not set");
            return Optional.empty();
        }

        CombatTaskConfig config = configBuilder.build();
        log.info("Created combat task: {}", config.getSummary());
        
        return Optional.of(new CombatTask(config, targetSelector, combatManager));
    }

    // ========================================================================
    // Navigation Task Creation
    // ========================================================================

    /**
     * Create a navigation task from JSON spec.
     *
     * Expected fields:
     * - locationId: String - Named location ID from web.json (required if no coords)
     * - x, y, plane: Numbers - Exact coordinates (required if no locationId)
     * - description: String - Task description (optional)
     */
    private Optional<Task> createNavigationTask(JsonObject spec) {
        WalkToTask task;

        // Named location or coordinates
        if (spec.has("locationId")) {
            String locationId = spec.get("locationId").getAsString();
            task = WalkToTask.toLocation(locationId);
            log.debug("Navigation task to named location: {}", locationId);
        } else if (spec.has("x") && spec.has("y")) {
            int x = spec.get("x").getAsInt();
            int y = spec.get("y").getAsInt();
            int plane = spec.has("plane") ? spec.get("plane").getAsInt() : 0;
            task = new WalkToTask(new WorldPoint(x, y, plane));
            log.debug("Navigation task to coordinates: {},{},{}", x, y, plane);
        } else {
            log.warn("Navigation task missing location (need locationId or x,y)");
            return Optional.empty();
        }

        // Optional description
        if (spec.has("description")) {
            task.setDescription(spec.get("description").getAsString());
        }

        log.info("Created navigation task: {}", task.getDescription());
        return Optional.of(task);
    }

    // ========================================================================
    // Quest Task Creation
    // ========================================================================

    /**
     * Create a quest task from JSON spec.
     *
     * Expected fields:
     * - questId: String - Quest identifier (required)
     *
     * Note: Quest tasks are handled by QuestExecutor, so this returns a wrapper
     * task that triggers quest execution.
     */
    private Optional<Task> createQuestTask(JsonObject spec) {
        if (!spec.has("questId")) {
            log.warn("Quest task missing 'questId'");
            return Optional.empty();
        }

        String questId = spec.get("questId").getAsString();
        
        if (questExecutor == null) {
            log.warn("QuestExecutor not available, cannot create quest task");
            return Optional.empty();
        }

        // Use QuestService to resolve questId to Quest object
        Quest quest = null;
        if (questService != null) {
            quest = questService.getQuestById(questId);
            if (quest == null) {
                log.warn("Quest not found: {}. Trying Quest Helper selected quest.", questId);
                // Fall back to currently selected Quest Helper quest
                quest = questService.getSelectedQuestFromHelper();
            }
        }
        
        if (quest == null) {
            log.warn("Cannot resolve quest: {} - neither QuestService nor Quest Helper available", questId);
            return Optional.empty();
        }

        // Create a wrapper task that initiates quest execution
        log.info("Created quest task for: {} ({})", quest.getName(), quest.getId());
        return Optional.of(new QuestTriggerTask(quest, questExecutor));
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Task that triggers quest execution via QuestExecutor.
     * 
     * This task resolves the quest from QuestService and starts execution
     * via QuestExecutor. The actual quest steps run asynchronously via
     * game tick events.
     */
    private static class QuestTriggerTask extends com.rocinante.tasks.AbstractTask {
        private final Quest quest;
        private final QuestExecutor questExecutor;
        private boolean started = false;

        QuestTriggerTask(Quest quest, QuestExecutor questExecutor) {
            this.quest = quest;
            this.questExecutor = questExecutor;
        }

        @Override
        public boolean canExecute(com.rocinante.tasks.TaskContext ctx) {
            return true;
        }

        @Override
        protected void executeImpl(com.rocinante.tasks.TaskContext ctx) {
            if (!started) {
                // Start quest execution via QuestExecutor
                log.info("Starting quest: {} ({})", quest.getName(), quest.getId());
                questExecutor.startQuest(quest);
                started = true;
                // Quest execution is handled by QuestExecutor via game tick events
                // This task completes after initiating the quest
                transitionTo(com.rocinante.tasks.TaskState.COMPLETED);
            }
        }

        @Override
        public String getDescription() {
            return "Start quest: " + quest.getName();
        }
    }
}

