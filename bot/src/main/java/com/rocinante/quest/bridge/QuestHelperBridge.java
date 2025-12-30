package com.rocinante.quest.bridge;

import com.rocinante.puzzle.PuzzleType;
import com.rocinante.quest.Quest;
import com.rocinante.quest.steps.*;
import com.rocinante.state.StateCondition;
import com.rocinante.tasks.impl.DialogueOptionResolver;
import com.rocinante.tasks.impl.EmoteTask;
import com.rocinante.tasks.impl.TravelTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.QuestState;
import net.runelite.api.coords.WorldPoint;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bridge to translate Quest Helper quest definitions into our quest framework.
 *
 * <p>Quest Helper is a community-maintained RuneLite plugin that provides step-by-step
 * guidance for completing quests. This bridge allows us to leverage their accurate
 * quest data and translate it into bot actions at runtime.
 *
 * <p>Key translation aspects:
 * <ul>
 *   <li>NpcStep → NpcQuestStep (infers "Talk-to" action by default)</li>
 *   <li>ObjectStep → ObjectQuestStep (infers action from context)</li>
 *   <li>ItemStep → ItemQuestStep</li>
 *   <li>ConditionalStep → ConditionalQuestStep</li>
 *   <li>DialogChoiceSteps → DialogueOptionResolvers</li>
 * </ul>
 *
 * <p>The bridge operates at runtime, translating steps on-demand as the quest
 * progresses. This allows for dynamic dialogue options that depend on game state.
 *
 * <p>Usage:
 * <pre>{@code
 * // Create bridge for a Quest Helper quest
 * QuestHelperBridge bridge = new QuestHelperBridge(questHelperInstance);
 *
 * // Get translated step for current varbit value
 * QuestStep step = bridge.getStep(currentVarbitValue);
 *
 * // Or get all steps
 * Map<Integer, QuestStep> allSteps = bridge.getAllSteps();
 * }</pre>
 */
@Slf4j
public class QuestHelperBridge {

    /**
     * The Quest Helper quest instance being bridged.
     */
    private final Object questHelperInstance;

    /**
     * Cached translated steps (varbit value → our QuestStep).
     */
    private final Map<Integer, QuestStep> translatedSteps = new HashMap<>();

    /**
     * Quest metadata extracted from the Quest Helper.
     */
    @Getter
    private QuestMetadata metadata;

    /**
     * Cached QuestHelperQuest enum entry for this helper (if available).
     */
    private Object questEnumEntry;

    /**
     * Create a bridge for a Quest Helper quest.
     *
     * @param questHelperInstance the Quest Helper quest instance (e.g., CooksAssistant)
     */
    public QuestHelperBridge(Object questHelperInstance) {
        this.questHelperInstance = questHelperInstance;
        extractMetadata();
    }

    /**
     * Extract quest metadata from the Quest Helper instance.
     *
     * <p>Metadata includes:
     * <ul>
     *   <li>Quest name (from QuestHelperQuest enum or class name)</li>
     *   <li>Varbit or varp ID for progress tracking</li>
     *   <li>Whether the quest uses varp instead of varbit</li>
     *   <li>Completion value (varbit/varp value when quest is complete)</li>
     * </ul>
     */
    private void extractMetadata() {
        try {
            // Try to get quest name from QuestHelperQuest enum first
            Object questEnum = extractQuestEnum();
            this.questEnumEntry = questEnum;

            String questName = extractQuestName(questEnum);
            
            // Fall back to class name parsing if enum not available
            if (questName == null || questName.isEmpty()) {
                String className = questHelperInstance.getClass().getSimpleName();
                questName = className.replaceAll("([A-Z])", " $1").trim();
            }

            // Extract varbit/varp ID
            int varId = extractVarId(questEnum);
            
            // Check if uses varp
            boolean usesVarp = questUsesVarp(questEnum);
            
            // Extract completion value
            int completionValue = extractCompletionValue(questEnum);

            this.metadata = new QuestMetadata(questName, varId, usesVarp, completionValue);
            log.debug("Extracted metadata for quest: {} (varId: {}, usesVarp: {}, completionValue: {})", 
                    questName, varId, usesVarp, completionValue);
        } catch (Exception e) {
            log.warn("Failed to extract quest metadata", e);
            this.metadata = new QuestMetadata("Unknown Quest", -1);
        }
    }
    
    /**
     * Extract the quest name from the QuestHelperQuest enum.
     *
     * @return the quest name, or null if not found
     */
    private String extractQuestName(Object questEnum) {
        try {
            if (questEnum != null) {
                Method getNameMethod = questEnum.getClass().getMethod("getName");
                return (String) getNameMethod.invoke(questEnum);
            }
        } catch (Exception e) {
            log.trace("Could not extract quest name from enum", e);
        }
        return null;
    }

    /**
     * Extract the varbit/varp ID used by this quest.
     *
     * <p>Quest Helper stores quest progress tracking info in the QuestHelperQuest enum.
     * Each quest has either a varbit or varp (varPlayer) ID that tracks progress.
     * We need to find the enum entry that corresponds to this quest helper instance.
     */
    private Object extractQuestEnum() {
        try {
            // Try quest field
            Field questField = findField(questHelperInstance.getClass(), "quest");
            if (questField != null) {
                questField.setAccessible(true);
                Object questEnum = questField.get(questHelperInstance);
                if (questEnum != null) {
                    return questEnum;
                }
            }

            // Try getQuest() method
            Method getQuestMethod = findMethod(questHelperInstance.getClass(), "getQuest");
            if (getQuestMethod != null) {
                getQuestMethod.setAccessible(true);
                Object questEnum = getQuestMethod.invoke(questHelperInstance);
                if (questEnum != null) {
                    return questEnum;
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract quest enum", e);
        }
        return null;
    }

    private int extractVarId(Object questEnum) {
        try {
            if (questEnum != null) {
                return extractVarIdFromQuestEnum(questEnum);
            }
        } catch (Exception e) {
            log.debug("Could not extract varId from quest helper: {}", e.getMessage());
        }
        
        return -1;
    }
    
    /**
     * Extract varbit or varp ID from a QuestHelperQuest enum entry.
     *
     * <p>QuestHelperQuest enum has fields:
     * <ul>
     *   <li>varbit: Varbit enum (has getId() method) - for varbit-based quests</li>
     *   <li>varPlayer: VarPlayer enum (has getId() method) - for varp-based quests</li>
     *   <li>completeValue: int - the value indicating quest completion</li>
     * </ul>
     *
     * @param questEnum the QuestHelperQuest enum entry
     * @return the varbit or varp ID, or -1 if not found
     */
    private int extractVarIdFromQuestEnum(Object questEnum) {
        try {
            // Try varbit field first (most quests use varbit)
            Field varbitField = findField(questEnum.getClass(), "varbit");
            if (varbitField != null) {
                varbitField.setAccessible(true);
                Object varbitEnum = varbitField.get(questEnum);
                if (varbitEnum != null) {
                    // Varbit enum has getId() method
                    Method getIdMethod = varbitEnum.getClass().getMethod("getId");
                    int varbitId = (int) getIdMethod.invoke(varbitEnum);
                    log.debug("Extracted varbit ID: {}", varbitId);
                    return varbitId;
                }
            }
            
            // Try varPlayer field (some older quests use varp)
            Field varPlayerField = findField(questEnum.getClass(), "varPlayer");
            if (varPlayerField != null) {
                varPlayerField.setAccessible(true);
                Object varPlayerEnum = varPlayerField.get(questEnum);
                if (varPlayerEnum != null) {
                    // VarPlayer enum has getId() method
                    Method getIdMethod = varPlayerEnum.getClass().getMethod("getId");
                    int varpId = (int) getIdMethod.invoke(varPlayerEnum);
                    log.debug("Extracted varp ID: {}", varpId);
                    // Mark that this uses varp - update metadata
                    return varpId;
                }
            }
            
        } catch (Exception e) {
            log.trace("Could not extract varId from QuestHelperQuest enum: {}", e.getMessage());
        }
        
        return -1;
    }
    
    /**
     * Check if the quest uses varp (varPlayer) instead of varbit.
     *
     * @return true if quest uses varp, false if uses varbit (default)
     */
    private boolean questUsesVarp(Object questEnum) {
        try {
            if (questEnum != null) {
                Field varPlayerField = findField(questEnum.getClass(), "varPlayer");
                if (varPlayerField != null) {
                    varPlayerField.setAccessible(true);
                    return varPlayerField.get(questEnum) != null;
                }
            }
        } catch (Exception e) {
            log.trace("Could not determine var type", e);
        }
        return false;
    }
    
    /**
     * Extract the completion value for the quest.
     *
     * @return the varbit/varp value that indicates quest completion, or -1 if unknown
     */
    private int extractCompletionValue(Object questEnum) {
        try {
            if (questEnum != null) {
                Field completeValueField = findField(questEnum.getClass(), "completeValue");
                if (completeValueField != null) {
                    completeValueField.setAccessible(true);
                    int completeValue = completeValueField.getInt(questEnum);
                    log.debug("Extracted completion value: {}", completeValue);
                    return completeValue;
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract completion value", e);
        }
        return -1;
    }

    /**
     * Load and translate all steps from the Quest Helper.
     *
     * @return map of varbit values to translated steps
     */
    public Map<Integer, QuestStep> loadAllSteps() {
        if (!translatedSteps.isEmpty()) {
            return translatedSteps;
        }

        try {
            // Call loadSteps() on the Quest Helper instance
            Method loadStepsMethod = findMethod(questHelperInstance.getClass(), "loadSteps");
            if (loadStepsMethod != null) {
                loadStepsMethod.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Integer, Object> qhSteps = (Map<Integer, Object>) loadStepsMethod.invoke(questHelperInstance);

                for (Map.Entry<Integer, Object> entry : qhSteps.entrySet()) {
                    int varValue = entry.getKey();
                    Object qhStep = entry.getValue();

                    QuestStep translated = translateStep(qhStep);
                    if (translated != null) {
                        translatedSteps.put(varValue, translated);
                    }
                }

                log.info("Loaded {} steps from Quest Helper", translatedSteps.size());
            }
        } catch (Exception e) {
            log.error("Failed to load steps from Quest Helper", e);
        }

        return translatedSteps;
    }

    /**
     * Get a specific step by varbit value.
     *
     * @param varbitValue the current quest varbit value
     * @return the translated step, or null if not found
     */
    public QuestStep getStep(int varbitValue) {
        if (translatedSteps.isEmpty()) {
            loadAllSteps();
        }
        return translatedSteps.get(varbitValue);
    }

    /**
     * Translate a Quest Helper step to our framework.
     *
     * @param qhStep the Quest Helper step object
     * @return our translated QuestStep, or null if unsupported
     */
    private QuestStep translateStep(Object qhStep) {
        if (qhStep == null) {
            return null;
        }

        String stepClassName = qhStep.getClass().getSimpleName();
        log.debug("Translating step type: {} ({})", stepClassName, qhStep.getClass().getName());

        try {
            // Ship travel (non-sailing skill)
            if (isInstanceOf(qhStep, "com.questhelper.steps.BoardShipStep")
                    || isInstanceOf(qhStep, "com.questhelper.steps.SailStep")
                    || "BoardShipStep".equals(stepClassName)
                    || "SailStep".equals(stepClassName)) {
                return translateShipStep(qhStep);
            }

            // NPC-based steps (specific first)
            if ("NpcEmoteStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.NpcEmoteStep")) {
                return translateNpcEmoteStep(qhStep);
            }
            if ("NpcFollowerStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.NpcFollowerStep")) {
                return translateNpcFollowerStep(qhStep);
            }
            if ("MultiNpcStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.MultiNpcStep")) {
                return translateMultiNpcStep(qhStep);
            }
            if ("NpcStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.NpcStep")) {
                return translateNpcStep(qhStep);
            }

            // Object / item / conditional
            if ("ObjectStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.ObjectStep")) {
                return translateObjectStep(qhStep);
            }
            if ("ItemStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.ItemStep")) {
                return translateItemStep(qhStep);
            }
            if ("ReorderableConditionalStep".equals(stepClassName)
                    || "ConditionalStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.ReorderableConditionalStep")
                    || isInstanceOf(qhStep, "com.questhelper.steps.ConditionalStep")) {
                return translateConditionalStep(qhStep);
            }

            // Other typed steps
            if ("EmoteStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.EmoteStep")) {
                return translateEmoteStep(qhStep);
            }
            if ("DigStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.DigStep")) {
                return translateDigStep(qhStep);
            }
            if ("TileStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.TileStep")) {
                return translateTileStep(qhStep);
            }
            if ("WidgetStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.WidgetStep")) {
                return translateWidgetStep(qhStep);
            }
            if ("PuzzleWrapperStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.PuzzleWrapperStep")) {
                return translatePuzzleWrapperStep(qhStep);
            }
            if ("PuzzleStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.PuzzleStep")) {
                return translatePuzzleStep(qhStep);
            }
            if ("QuestSyncStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.QuestSyncStep")) {
                return translateQuestSyncStep(qhStep);
            }
            if ("DetailedOwnerStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.DetailedOwnerStep")) {
                return translateDetailedOwnerStep(qhStep);
            }
            if ("DetailedQuestStep".equals(stepClassName)
                    || isInstanceOf(qhStep, "com.questhelper.steps.DetailedQuestStep")) {
                return translateDetailedStep(qhStep);
            }

            // Simple wait/instruction-only steps used in fixtures
            if ("WaitStep".equals(stepClassName)) {
                return new WaitQuestStep(getStepText(qhStep));
            }

            // OwnerStep implementations
            if (implementsOwnerStep(qhStep)) {
                return translateOwnerStep(qhStep);
            }

            // Unknown/unsupported
            log.warn("Unknown Quest Helper step type: {}, returning null", stepClassName);
            return null;
        } catch (UnsupportedOperationException e) {
            // Re-throw UnsupportedOperationException as-is (intentional failures)
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to translate Quest Helper step type: " + stepClassName, e);
        }
    }

    /**
     * Translate an NpcStep to NpcQuestStep.
     */
    private NpcQuestStep translateNpcStep(Object qhStep) throws Exception {
        int npcId = getIntField(qhStep, "npcID");
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        // Prefer explicit action if Quest Helper provides one
        String menuAction = extractMenuAction(qhStep);
        if (menuAction == null) {
            // Infer menu action from step text
            menuAction = inferNpcMenuAction(text);
        }
        boolean dialogueExpected = "Talk-to".equals(menuAction);

        NpcQuestStep step = new NpcQuestStep(npcId, text)
                .withMenuAction(menuAction)
                .withDialogueExpected(dialogueExpected);

        if (location != null) {
            step.withWalkTo(location);
        }

        // Extract alternate NPC IDs
        List<Integer> alternateNpcs = extractAlternateIds(qhStep, "alternateNpcIDs", "alternateNpcs");
        if (!alternateNpcs.isEmpty()) {
            step.withAlternateIds(alternateNpcs);
            log.debug("Found {} alternate NPC IDs for npcId={}", alternateNpcs.size(), npcId);
        }

        // Extract dialogue options
        List<DialogueOptionResolver> dialogueResolvers = extractDialogueOptions(qhStep);
        if (!dialogueResolvers.isEmpty()) {
            // Store resolvers for later use when creating DialogueTask
            step.withDialogueOptions(extractDialogueTexts(dialogueResolvers).toArray(new String[0]));
        }

        log.debug("Translated NpcStep: npcId={}, text={}", npcId, text);
        return step;
    }

    /**
     * Translate BoardShipStep / SailStep to ShipQuestStep.
     */
    private QuestStep translateShipStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        List<Integer> objectIds = extractShipObjectIds(qhStep);
        if (location == null) {
            location = extractFirstChildLocation(qhStep);
        }

        ShipQuestStep step = new ShipQuestStep(objectIds, location, text)
                .withMenuAction("Board");

        if (location == null && objectIds.isEmpty()) {
            log.debug("Ship step missing location/object ids, will wait: {}", text);
        } else {
            log.debug("Translated ship step: location={}, objects={}", location, objectIds);
        }
        return step;
    }

    /**
     * Translate an ObjectStep to ObjectQuestStep.
     */
    private ObjectQuestStep translateObjectStep(Object qhStep) throws Exception {
        int objectId = getIntField(qhStep, "objectID");
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        String menuAction = extractMenuAction(qhStep);
        if (menuAction == null) {
            // Infer menu action from step text
            menuAction = inferObjectMenuAction(text);
        }

        ObjectQuestStep step = new ObjectQuestStep(objectId, menuAction, text);

        if (location != null) {
            step.withWalkTo(location);
        }

        // Extract alternate object IDs
        List<Integer> alternateObjects = extractAlternateIds(qhStep, "alternateObjectIDs", "alternateObjects");
        if (!alternateObjects.isEmpty()) {
            step.withAlternateIds(alternateObjects);
            log.debug("Found {} alternate object IDs for objectId={}", alternateObjects.size(), objectId);
        }

        // Extract dialogue options (some object interactions have dialogue)
        List<DialogueOptionResolver> dialogueResolvers = extractDialogueOptions(qhStep);
        // Note: ObjectQuestStep doesn't have dialogue options by default,
        // but the executor can queue a DialogueTask if needed

        log.debug("Translated ObjectStep: objectId={}, action={}, text={}", objectId, menuAction, text);
        return step;
    }

    /**
     * Translate an ItemStep to GroundItemQuestStep.
     * ItemStep in Quest Helper is typically for picking up ground items.
     */
    private QuestStep translateItemStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        // Extract item information from the step
        int itemId = extractItemIdFromStep(qhStep);
        String itemName = extractItemNameFromStep(qhStep, text);

        if (itemId <= 0) {
            throw new IllegalStateException(
                "ItemStep missing item ID - Quest Helper data incomplete: " + text);
        }

        // Create GroundItemQuestStep
        GroundItemQuestStep step = new GroundItemQuestStep(itemId, itemName, text);

        if (location != null) {
            step.withWalkTo(location);
        } else {
            // No specific location - search nearby
            step.withSearchRadius(15);
        }

        log.debug("Translated ItemStep: itemId={}, itemName={}, location={}, text={}", 
                itemId, itemName, location, text);
        return step;
    }

    /**
     * Translate a BoardShipStep to a walk/wait quest step.
     */
    private QuestStep translateBoardShipStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);
        if (location != null) {
            return new WalkQuestStep(location, text);
        }
        return new WaitQuestStep(text);
    }

    /**
     * Translate a SailStep to a walk/wait quest step (non-sailing fallback).
     */
    private QuestStep translateSailStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);
        if (location != null) {
            return new WalkQuestStep(location, text);
        }
        return new WaitQuestStep(text);
    }

    /**
     * Extract item ID from a Quest Helper ItemStep.
     *
     * <p>ItemStep (and its parent DetailedQuestStep) stores items in the
     * 'requirements' list as ItemRequirement objects. At runtime, Quest Helper
     * populates tileHighlights based on these requirements when items spawn.
     *
     * @param qhStep the ItemStep object
     * @return the first item ID found, or -1 if none
     */
    private int extractItemIdFromStep(Object qhStep) {
        // ItemStep uses the 'requirements' list inherited from DetailedQuestStep
        // to track which items should be picked up
        try {
            Object requirements = getFieldValue(qhStep, "requirements");
            if (requirements instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> reqList = (List<Object>) requirements;
                for (Object req : reqList) {
                    if (req == null) continue;

                    // Check if this is an ItemRequirement
                    String reqClassName = req.getClass().getSimpleName();
                    if (reqClassName.equals("ItemRequirement") ||
                        reqClassName.equals("ItemRequirements") ||
                        reqClassName.contains("Item")) {

                        // Try to get the item ID
                        int itemId = getIntFieldSafe(req, "id", -1);
                        if (itemId > 0) {
                            return itemId;
                        }

                        // Try getAllIds() method for items with alternates
                        try {
                            Method getAllIds = findMethod(req.getClass(), "getAllIds");
                            if (getAllIds != null) {
                                getAllIds.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                List<Integer> ids = (List<Integer>) getAllIds.invoke(req);
                                if (ids != null && !ids.isEmpty()) {
                                    return ids.get(0);
                                }
                            }
                        } catch (Exception e) {
                            log.trace("Could not call getAllIds on requirement", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract itemId from requirements list", e);
        }

        // Fallback: try iconItemID from DetailedQuestStep (often set to the item)
        int iconItemId = getIntFieldSafe(qhStep, "iconItemID", -1);
        if (iconItemId > 0) {
            return iconItemId;
        }

        return -1;
    }

    /**
     * Extract item name from a Quest Helper ItemStep.
     */
    private String extractItemNameFromStep(Object qhStep, String fallbackText) {
        // Try to get name from requirements list
        try {
            Object requirements = getFieldValue(qhStep, "requirements");
            if (requirements instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> reqList = (List<Object>) requirements;
                for (Object req : reqList) {
                    if (req == null) continue;

                    String reqClassName = req.getClass().getSimpleName();
                    if (reqClassName.contains("Item")) {
                        String name = getStringField(req, "name");
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract itemName from requirements", e);
        }

        // Parse from step text (e.g., "Grab an egg from the farm" -> "egg")
        if (fallbackText != null) {
            String lower = fallbackText.toLowerCase();
            // Common patterns
            if (lower.contains("grab") || lower.contains("pick up") || lower.contains("take") ||
                lower.contains("get") || lower.contains("collect")) {
                // Try to extract item name after common verbs
                String[] patterns = {
                    "grab an ", "grab a ", "grab the ", "grab ",
                    "pick up an ", "pick up a ", "pick up the ", "pick up ",
                    "take an ", "take a ", "take the ", "take ",
                    "get an ", "get a ", "get the ", "get ",
                    "collect an ", "collect a ", "collect the ", "collect "
                };
                for (String pattern : patterns) {
                    int idx = lower.indexOf(pattern);
                    if (idx >= 0) {
                        String after = fallbackText.substring(idx + pattern.length()).trim();
                        // Take first word or phrase before preposition
                        String[] words = after.split("\\s+(from|at|in|on|near|and)\\s+");
                        if (words.length > 0 && !words[0].isEmpty()) {
                            return words[0].trim();
                        }
                    }
                }
            }
        }

        return "item";
    }

    /**
     * Translate a ConditionalStep to ConditionalQuestStep.
     */
    private ConditionalQuestStep translateConditionalStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);

        // ConditionalStep has a LinkedHashMap of conditions to steps
        // We need to translate each branch

        ConditionalQuestStep step = new ConditionalQuestStep(text);
        RequirementTranslator translator = new RequirementTranslator();

        // Extract the steps map using reflection
        try {
            Field stepsField = findField(qhStep.getClass(), "steps");
            if (stepsField != null) {
                stepsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                LinkedHashMap<Object, Object> branches = (LinkedHashMap<Object, Object>) stepsField.get(qhStep);

                for (Map.Entry<Object, Object> entry : branches.entrySet()) {
                    Object condition = entry.getKey();
                    Object branchStep = entry.getValue();

                    // Translate the branch step
                    QuestStep translatedBranch = translateStep(branchStep);

                    if (translatedBranch != null) {
                        if (condition != null) {
                            // Translate the condition using RequirementTranslator
                            StateCondition translatedCondition = translator.translate(condition);
                            step.when(translatedCondition, translatedBranch);
                        } else {
                            // Null condition is the default/fallback step
                            step.otherwise(translatedBranch);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract ConditionalStep branches", e);
        }

        log.debug("Translated ConditionalStep: text={}", text);
        return step;
    }

    /**
     * Translate a generic/detailed step.
     */
    private QuestStep translateDetailedStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        // Check if this is a teleport-related step
        TeleportQuestStep teleportStep = detectTeleportStep(text, location);
        if (teleportStep != null) {
            log.debug("Translated DetailedQuestStep as teleport: {}", text);
            return teleportStep;
        }

        // Detailed steps are often just instructions, may need WalkQuestStep
        if (location != null) {
            return new WalkQuestStep(location, text);
        }

        // DetailedQuestStep without location or teleport: treat as wait/instruction
        log.debug("DetailedQuestStep without location - creating WaitQuestStep: {}", text);
        return new WaitQuestStep(text);
    }

    /**
     * Generic fallback translation.
     */
    private QuestStep translateGenericStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);
        
        // Check if this is a teleport-related step
        TeleportQuestStep teleportStep = detectTeleportStep(text, location);
        if (teleportStep != null) {
            log.debug("Translated generic step as teleport: {}", text);
            return teleportStep;
        }
        
        // Try walk step if location available
        if (location != null) {
            log.debug("Generic step translation with location: {}", text);
            return new WalkQuestStep(location, text);
        }
        
        // Generic step without usable data - fall back to wait
        log.debug("Generic step translation (no location, no teleport): {}", text);
        return new WaitQuestStep(text);
    }
    
    // ========================================================================
    // Teleport Detection for Fallback Steps
    // ========================================================================
    
    /**
     * Detect if a step is teleport-related and create appropriate TeleportQuestStep.
     * This provides fallback handling when Quest Helper steps mention teleportation
     * but aren't explicitly typed as such.
     *
     * @param text     the step text
     * @param location optional expected destination
     * @return TeleportQuestStep if teleport detected, null otherwise
     */
    private TeleportQuestStep detectTeleportStep(String text, WorldPoint location) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        String lowerText = text.toLowerCase();
        
        // Detect home teleport
        if (lowerText.contains("home teleport") || lowerText.contains("teleport home")) {
            TeleportQuestStep step = TeleportQuestStep.homeTeleport(text);
            if (location != null) {
                step.withExpectedDestination(location);
            }
            return step;
        }
        
        // Detect fairy ring usage
        if (lowerText.contains("fairy ring")) {
            // Try to extract fairy ring code from text (e.g., "AIQ", "CKR")
            String code = extractFairyRingCode(text);
            if (code != null) {
                TeleportQuestStep step = TeleportQuestStep.fairyRing(code, text);
                if (location != null) {
                    step.withExpectedDestination(location);
                }
                return step;
            }
        }
        
        // Detect spirit tree usage
        if (lowerText.contains("spirit tree")) {
            String destination = extractSpiritTreeDestination(text);
            TeleportQuestStep step = TeleportQuestStep.spiritTree(destination, text);
            if (location != null) {
                step.withExpectedDestination(location);
            }
            return step;
        }
        
        // Detect specific spell teleports
        String spellName = detectSpellTeleport(lowerText);
        if (spellName != null) {
            TeleportQuestStep step = TeleportQuestStep.spell(spellName, text);
            if (location != null) {
                step.withExpectedDestination(location);
            }
            return step;
        }
        
        // Detect jewelry teleports
        if (lowerText.contains("ring of wealth") || lowerText.contains("glory") ||
            lowerText.contains("games necklace") || lowerText.contains("ring of dueling") ||
            lowerText.contains("combat bracelet") || lowerText.contains("skills necklace")) {
            // Generic jewelry teleport - caller should configure with specific item ID
            log.debug("Detected jewelry teleport mention, needs specific item ID: {}", text);
            // Return null to let caller handle with more context
            return null;
        }
        
        // Detect tablet teleports
        if (lowerText.contains("teleport tablet") || lowerText.contains("teletab")) {
            log.debug("Detected teleport tablet mention, needs specific item ID: {}", text);
            return null;
        }
        
        // Generic teleport mentions without specifics - try to infer destination
        if (lowerText.contains("teleport to") || lowerText.contains("teleport back")) {
            // Try to detect destination from text
            if (lowerText.contains("varrock")) {
                return TeleportQuestStep.spell("Varrock Teleport", text);
            } else if (lowerText.contains("lumbridge")) {
                return TeleportQuestStep.spell("Lumbridge Teleport", text);
            } else if (lowerText.contains("falador")) {
                return TeleportQuestStep.spell("Falador Teleport", text);
            } else if (lowerText.contains("camelot")) {
                return TeleportQuestStep.spell("Camelot Teleport", text);
            } else if (lowerText.contains("ardougne")) {
                return TeleportQuestStep.spell("Ardougne Teleport", text);
            } else if (lowerText.contains("watchtower")) {
                return TeleportQuestStep.spell("Watchtower Teleport", text);
            } else if (lowerText.contains("trollheim")) {
                return TeleportQuestStep.spell("Trollheim Teleport", text);
            } else if (lowerText.contains("kourend")) {
                return TeleportQuestStep.spell("Teleport to Kourend", text);
            } else if (lowerText.contains("house") || lowerText.contains("poh")) {
                return TeleportQuestStep.spell("Teleport to House", text);
            }
        }
        
        return null;
    }
    
    /**
     * Extract fairy ring code from text (e.g., "Use fairy ring AIQ").
     */
    private String extractFairyRingCode(String text) {
        // Fairy ring codes are 3 uppercase letters
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b([A-Z]{3})\\b")
                .matcher(text);
        if (matcher.find()) {
            String code = matcher.group(1);
            // Validate it looks like a fairy ring code (A/B/C/D for each dial)
            if (isFairyRingCode(code)) {
                return code;
            }
        }
        // Try lowercase
        matcher = java.util.regex.Pattern
                .compile("\\b([a-z]{3})\\b")
                .matcher(text.toLowerCase());
        while (matcher.find()) {
            String code = matcher.group(1).toUpperCase();
            if (isFairyRingCode(code)) {
                return code;
            }
        }
        return null;
    }
    
    /**
     * Check if a string is a valid fairy ring code.
     * Fairy rings use letters A, B, C, D, I, J, K, L, P, Q, R, S.
     */
    private boolean isFairyRingCode(String code) {
        if (code == null || code.length() != 3) {
            return false;
        }
        String validChars = "ABCDIJKLPQRS";
        for (char c : code.toCharArray()) {
            if (validChars.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Extract spirit tree destination from text.
     */
    private String extractSpiritTreeDestination(String text) {
        String lowerText = text.toLowerCase();
        if (lowerText.contains("tree gnome village")) {
            return "Tree Gnome Village";
        } else if (lowerText.contains("tree gnome stronghold") || lowerText.contains("gnome stronghold")) {
            return "Tree Gnome Stronghold";
        } else if (lowerText.contains("grand exchange")) {
            return "Grand Exchange";
        } else if (lowerText.contains("battlefield of khazard") || lowerText.contains("khazard")) {
            return "Battlefield of Khazard";
        } else if (lowerText.contains("port sarim")) {
            return "Port Sarim";
        } else if (lowerText.contains("brimhaven")) {
            return "Brimhaven";
        } else if (lowerText.contains("hosidius")) {
            return "Hosidius";
        } else if (lowerText.contains("farming guild")) {
            return "Farming Guild";
        }
        return "Tree Gnome Stronghold"; // Default
    }
    
    /**
     * Detect specific spell teleport from text.
     */
    private String detectSpellTeleport(String lowerText) {
        // Check for explicit spell mentions
        if (lowerText.contains("cast varrock teleport") || 
            lowerText.contains("varrock teleport spell")) {
            return "Varrock Teleport";
        }
        if (lowerText.contains("cast lumbridge teleport") || 
            lowerText.contains("lumbridge teleport spell")) {
            return "Lumbridge Teleport";
        }
        if (lowerText.contains("cast falador teleport") || 
            lowerText.contains("falador teleport spell")) {
            return "Falador Teleport";
        }
        if (lowerText.contains("cast camelot teleport") || 
            lowerText.contains("camelot teleport spell")) {
            return "Camelot Teleport";
        }
        if (lowerText.contains("cast ardougne teleport") || 
            lowerText.contains("ardougne teleport spell")) {
            return "Ardougne Teleport";
        }
        return null;
    }

    // ========================================================================
    // Additional Step Type Translations
    // ========================================================================

    /**
     * Translate an EmoteStep to EmoteQuestStep.
     */
    private EmoteQuestStep translateEmoteStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        // Extract emote from the step
        Object emoteObj = getFieldValue(qhStep, "emote");
        int spriteId = -1;
        String emoteName = null;

        if (emoteObj != null) {
            // QuestEmote has name and spriteId fields
            emoteName = getStringField(emoteObj, "name");
            spriteId = getIntFieldSafe(emoteObj, "spriteId", -1);
        }

        EmoteQuestStep step;
        if (emoteName != null) {
            step = new EmoteQuestStep(emoteName, text);
        } else if (spriteId > 0) {
            step = EmoteQuestStep.fromSpriteId(spriteId, text);
        } else {
            throw new IllegalStateException(
                "EmoteStep missing emote data - Quest Helper data incomplete: " + text);
        }

        if (location != null) {
            step.withWalkTo(location);
        }

        if (spriteId > 0) {
            step.withSpriteId(spriteId);
        }

        log.debug("Translated EmoteStep: emote={}, spriteId={}, text={}", emoteName, spriteId, text);
        return step;
    }

    /**
     * Translate a DigStep to DigQuestStep.
     */
    private DigQuestStep translateDigStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        if (location == null) {
            throw new IllegalStateException(
                "DigStep missing location - Quest Helper data incomplete: " + text);
        }

        DigQuestStep step = new DigQuestStep(location, text);
        log.debug("Translated DigStep: location={}, text={}", location, text);
        return step;
    }

    /**
     * Translate a TileStep to WalkQuestStep.
     * TileStep is essentially "walk to this tile".
     */
    private WalkQuestStep translateTileStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        if (location == null) {
            throw new IllegalStateException(
                "TileStep missing location - Quest Helper data incomplete: " + text);
        }

        WalkQuestStep step = new WalkQuestStep(location, text);
        log.debug("Translated TileStep: location={}, text={}", location, text);
        return step;
    }

    /**
     * Translate a WidgetStep to WidgetQuestStep.
     */
    private WidgetQuestStep translateWidgetStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);

        // Extract widget details from the step
        // WidgetStep has List<WidgetDetails> widgetDetails
        Object widgetDetailsList = getFieldValue(qhStep, "widgetDetails");
        if (widgetDetailsList instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> details = (List<Object>) widgetDetailsList;
            if (!details.isEmpty()) {
                Object firstDetail = details.get(0);
                int groupId = getIntFieldSafe(firstDetail, "groupID", -1);
                int childId = getIntFieldSafe(firstDetail, "childID", -1);
                int childChildId = getIntFieldSafe(firstDetail, "childChildID", -1);

                if (groupId > 0) {
                    WidgetQuestStep step = new WidgetQuestStep(groupId, childId, text);
                    if (childChildId >= 0) {
                        step.withDynamicChild(childChildId);
                    }
                    log.debug("Translated WidgetStep: groupId={}, childId={}, text={}", groupId, childId, text);
                    return step;
                }
            }
        }

        throw new IllegalStateException(
            "WidgetStep missing widget details - Quest Helper data incomplete: " + text);
    }

    /**
     * Translate an NpcEmoteStep to an NpcQuestStep followed by EmoteQuestStep.
     * NpcEmoteStep extends NpcStep and requires performing an emote near an NPC.
     */
    private ConditionalQuestStep translateNpcEmoteStep(Object qhStep) throws Exception {
        // NpcEmoteStep has both NPC interaction and emote
        // We create a conditional step that first does the NPC interaction, then the emote
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        // Get NPC data (inherited from NpcStep)
        int npcId = getIntField(qhStep, "npcID");

        // Get emote data
        Object emoteObj = getFieldValue(qhStep, "emote");
        String emoteName = null;
        int spriteId = -1;

        if (emoteObj != null) {
            emoteName = getStringField(emoteObj, "name");
            spriteId = getIntFieldSafe(emoteObj, "spriteId", -1);
        }

        // Create a sequence: NPC interaction is typically not needed for emote clues,
        // so we create an emote step that walks to the NPC location
        if (emoteName != null || spriteId > 0) {
            EmoteQuestStep emoteStep;
            if (emoteName != null) {
                emoteStep = new EmoteQuestStep(emoteName, text);
            } else {
                emoteStep = EmoteQuestStep.fromSpriteId(spriteId, text);
            }

            if (location != null) {
                emoteStep.withWalkTo(location);
            }

            // Wrap in conditional step for flexibility
            ConditionalQuestStep wrapper = new ConditionalQuestStep(text);
            wrapper.otherwise(emoteStep);

            log.debug("Translated NpcEmoteStep: npcId={}, emote={}, location={}", npcId, emoteName, location);
            return wrapper;
        }

        throw new IllegalStateException(
            "NpcEmoteStep translation failed - Quest Helper data incomplete: " + text);
    }

    /**
     * Translate a MultiNpcStep to NpcQuestStep.
     * MultiNpcStep is like NpcStep but can interact with multiple NPCs.
     */
    private NpcQuestStep translateMultiNpcStep(Object qhStep) throws Exception {
        // MultiNpcStep extends NpcStep, so we can use similar logic
        int npcId = getIntField(qhStep, "npcID");
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        NpcQuestStep step = new NpcQuestStep(npcId, text)
                .withMenuAction("Talk-to")
                .withDialogueExpected(true);

        if (location != null) {
            step.withWalkTo(location);
        }

        // Extract all NPC IDs (MultiNpcStep typically has a list)
        List<Integer> allNpcIds = extractAlternateIds(qhStep, "npcIDs", "alternateNpcIDs", "alternateNpcs");
        if (!allNpcIds.isEmpty()) {
            step.withAlternateIds(allNpcIds);
        }

        // Extract dialogue options
        List<DialogueOptionResolver> dialogueResolvers = extractDialogueOptions(qhStep);
        if (!dialogueResolvers.isEmpty()) {
            step.withDialogueOptions(extractDialogueTexts(dialogueResolvers).toArray(new String[0]));
        }

        log.debug("Translated MultiNpcStep: npcId={}, alternates={}, text={}", npcId, allNpcIds.size(), text);
        return step;
    }

    /**
     * Translate a PuzzleWrapperStep.
     * PuzzleWrapperStep extends ConditionalStep and wraps puzzle logic.
     * Since we can't auto-solve puzzles, we translate it as a conditional step.
     */
    private ConditionalQuestStep translatePuzzleWrapperStep(Object qhStep) throws Exception {
        // PuzzleWrapperStep is a ConditionalStep, so use the same logic
        return translateConditionalStep(qhStep);
    }

    /**
     * Translate a PuzzleStep.
     * PuzzleStep extends DetailedQuestStep and highlights widgets that need to be clicked.
     * Since puzzles require algorithmic solving, we delegate to AI Director at runtime.
     */
    /**
     * Translate a Quest Helper PuzzleStep to our PuzzleQuestStep.
     *
     * <p>Quest Helper's PuzzleStep uses a ButtonHighlightCalculator interface to determine
     * which widgets to highlight. Our PuzzleQuestStep uses algorithmic solvers for
     * sliding puzzles and light boxes, with auto-detection of puzzle type.
     *
     * <p>For puzzles not handled by our solvers, auto-detection will allow the
     * PuzzleTask to wait for a supported puzzle interface to appear.
     *
     * @param qhStep the Quest Helper PuzzleStep
     * @return a PuzzleQuestStep for solving the puzzle
     */
    private QuestStep translatePuzzleStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);

        // Try to detect puzzle type from step context
        PuzzleType puzzleType = detectPuzzleType(qhStep, text);

        log.info("PuzzleStep encountered - type: {}, text: {}", 
                puzzleType != null ? puzzleType.getDisplayName() : "auto-detect", text);

        // Create PuzzleQuestStep with detected type (or auto-detect)
        return new PuzzleQuestStep(puzzleType, text);
    }

    /**
     * Detect puzzle type from Quest Helper step context and text hints.
     *
     * @param qhStep the Quest Helper step
     * @param text the step text
     * @return detected puzzle type, or null for auto-detection
     */
    private PuzzleType detectPuzzleType(Object qhStep, String text) {
        if (text == null) return null;

        String lowerText = text.toLowerCase();

        // Check for sliding puzzle indicators
        if (lowerText.contains("sliding") || lowerText.contains("slide") ||
            lowerText.contains("tile") || lowerText.contains("picture puzzle") ||
            lowerText.contains("puzzle box")) {
            return PuzzleType.SLIDING_PUZZLE;
        }

        // Check for light box indicators
        if (lowerText.contains("light") || lowerText.contains("lamp") ||
            lowerText.contains("bulb")) {
            return PuzzleType.LIGHT_BOX;
        }

        // Try to detect from Quest Helper's ButtonHighlightCalculator widget ID
        try {
            Field calculatorField = qhStep.getClass().getDeclaredField("highlightCalculator");
            calculatorField.setAccessible(true);
            Object calculator = calculatorField.get(qhStep);

            if (calculator != null) {
                // Try to invoke getHighlightedButtons and check widget IDs
                Method getButtonsMethod = calculator.getClass().getMethod("getHighlightedButtons");
                Object buttons = getButtonsMethod.invoke(calculator);

                if (buttons instanceof Set) {
                    Set<?> buttonSet = (Set<?>) buttons;
                    for (Object widgetDetails : buttonSet) {
                        // Extract widget group ID
                        int groupId = extractWidgetGroupId(widgetDetails);
                        PuzzleType type = PuzzleType.fromWidgetGroupId(groupId);
                        if (type != PuzzleType.UNKNOWN) {
                            return type;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Calculator not available or failed to extract - fall back to auto-detect
            log.debug("Could not extract puzzle type from calculator: {}", e.getMessage());
        }

        // Auto-detect at runtime
        return null;
    }

    /**
     * Extract widget group ID from a WidgetDetails object.
     */
    private int extractWidgetGroupId(Object widgetDetails) {
        try {
            Field groupIdField = widgetDetails.getClass().getDeclaredField("groupID");
            groupIdField.setAccessible(true);
            return groupIdField.getInt(widgetDetails);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Translate an NpcFollowerStep to NpcQuestStep with follower verification.
     * NpcFollowerStep extends NpcStep and only highlights NPCs that are currently following the player.
     */
    private NpcQuestStep translateNpcFollowerStep(Object qhStep) throws Exception {
        // NpcFollowerStep extends NpcStep, so get the base NPC data
        int npcId = getIntField(qhStep, "npcID");
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);
        
        NpcQuestStep step = new NpcQuestStep(npcId, text)
                .withMenuAction(inferNpcMenuAction(text))
                .withDialogueExpected(true);
        
        if (location != null) {
            step.withWalkTo(location);
        }
        
        // Extract alternate NPC IDs
        List<Integer> alternateNpcs = extractAlternateIds(qhStep, "alternateNpcIDs", "alternateNpcs");
        if (!alternateNpcs.isEmpty()) {
            step.withAlternateIds(alternateNpcs);
        }
        
        // Add a condition that checks if the NPC follower matches expected IDs
        // Varp 447 lower 16 bits stores the follower NPC index.
        List<Integer> expectedIds = new ArrayList<>();
        expectedIds.add(npcId);
        expectedIds.addAll(alternateNpcs);

        StateCondition followerCondition = ctx -> {
            int followerVarp = ctx.getClient().getVarpValue(447);
            int followerIndex = followerVarp & 0x0000FFFF;
            if (followerIndex <= 0) {
                return false;
            }
            java.util.List<NPC> npcs = ctx.getClient().getNpcs();
            for (NPC npc : npcs) {
                if (npc == null) continue;
                if (npc.getIndex() == followerIndex) {
                    return expectedIds.contains(npc.getId());
                }
            }
            // If we can't resolve the NPC, be conservative and fail
            return false;
        };
        step.withCondition(followerCondition);
        
        // Extract dialogue options
        List<DialogueOptionResolver> dialogueResolvers = extractDialogueOptions(qhStep);
        if (!dialogueResolvers.isEmpty()) {
            step.withDialogueOptions(extractDialogueTexts(dialogueResolvers).toArray(new String[0]));
        }
        
        log.debug("Translated NpcFollowerStep: npcId={}, text={}", npcId, text);
        return step;
    }

    /**
     * Translate a QuestSyncStep.
     * QuestSyncStep is used to prompt the player to select a quest in the quest list.
     * For the bot, we translate this as a widget step that opens the quest tab and selects the quest.
     */
    private WidgetQuestStep translateQuestSyncStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        
        // QuestSyncStep has a 'quest' field (QuestHelperQuest enum)
        Object questObj = getFieldValue(qhStep, "quest");
        String questName = "Unknown Quest";
        if (questObj != null) {
            try {
                Method getNameMethod = questObj.getClass().getMethod("getName");
                questName = (String) getNameMethod.invoke(questObj);
            } catch (Exception e) {
                log.trace("Could not get quest name from QuestSyncStep", e);
            }
        }
        
        // QuestSyncStep highlights the quest in the quest list widget
        // Widget 399 is the quest list, child 7 is the container
        String fullText = text + " (Select quest: " + questName + ")";
        WidgetQuestStep step = new WidgetQuestStep(399, 7, fullText);
        
        log.debug("Translated QuestSyncStep: quest={}, text={}", questName, text);
        return step;
    }

    /**
     * Translate a DetailedOwnerStep.
     * DetailedOwnerStep extends QuestStep and implements OwnerStep to contain child steps.
     * It delegates to a currentStep based on some internal logic.
     */
    private QuestStep translateDetailedOwnerStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        
        // DetailedOwnerStep has getSteps() that returns child steps
        // We need to translate all child steps and wrap in a conditional
        Collection<Object> childSteps = extractOwnerSteps(qhStep);
        
        if (childSteps == null || childSteps.isEmpty()) {
            log.debug("DetailedOwnerStep has no child steps: {}", text);
            return translateDetailedStep(qhStep);
        }
        
        // Create a conditional step that will evaluate child steps
        ConditionalQuestStep wrapper = new ConditionalQuestStep(text);
        
        for (Object childStep : childSteps) {
            if (childStep != null) {
                QuestStep translated = translateStep(childStep);
                if (translated != null) {
                    // DetailedOwnerStep doesn't expose conditions, so add as alternatives
                    wrapper.otherwise(translated);
                    break; // Use first valid step as default
                }
            }
        }
        
        log.debug("Translated DetailedOwnerStep: text={}, childSteps={}", text, childSteps.size());
        return wrapper;
    }

    /**
     * Translate any step that implements the OwnerStep interface.
     * OwnerStep provides getSteps() to access child steps.
     */
    private QuestStep translateOwnerStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        Collection<Object> childSteps = extractOwnerSteps(qhStep);
        
        if (childSteps == null || childSteps.isEmpty()) {
            log.debug("OwnerStep has no child steps: {}", text);
            return null;
        }
        
        // Translate child steps
        List<QuestStep> translatedSteps = new ArrayList<>();
        for (Object childStep : childSteps) {
            if (childStep != null) {
                QuestStep translated = translateStep(childStep);
                if (translated != null) {
                    translatedSteps.add(translated);
                }
            }
        }
        
        if (translatedSteps.isEmpty()) {
            return null;
        }
        
        // If only one step, return it directly
        if (translatedSteps.size() == 1) {
            return translatedSteps.get(0);
        }
        
        // Multiple steps - wrap in conditional
        ConditionalQuestStep wrapper = new ConditionalQuestStep(text);
        wrapper.otherwise(translatedSteps.get(0));
        
        log.debug("Translated OwnerStep: text={}, childSteps={}", text, translatedSteps.size());
        return wrapper;
    }

    /**
     * Extract child steps from an OwnerStep implementation.
     */
    @SuppressWarnings("unchecked")
    private Collection<Object> extractOwnerSteps(Object ownerStep) {
        try {
            Method getStepsMethod = findMethod(ownerStep.getClass(), "getSteps");
            if (getStepsMethod != null) {
                getStepsMethod.setAccessible(true);
                Object result = getStepsMethod.invoke(ownerStep);
                if (result instanceof Collection) {
                    return (Collection<Object>) result;
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract steps from OwnerStep", e);
        }
        return null;
    }

    /**
     * Check if a step implements the OwnerStep interface.
     */
    private boolean implementsOwnerStep(Object step) {
        for (Class<?> iface : step.getClass().getInterfaces()) {
            if (iface.getSimpleName().equals("OwnerStep")) {
                return true;
            }
        }
        // Check superclasses too
        Class<?> superClass = step.getClass().getSuperclass();
        while (superClass != null) {
            for (Class<?> iface : superClass.getInterfaces()) {
                if (iface.getSimpleName().equals("OwnerStep")) {
                    return true;
                }
            }
            superClass = superClass.getSuperclass();
        }
        return false;
    }

    // ========================================================================
    // Dialogue Option Extraction
    // ========================================================================

    /**
     * Extract dialogue options from a Quest Helper step.
     *
     * <p>Quest Helper stores dialogue choices in the 'choices' field which is a
     * DialogChoiceSteps object containing a list of DialogChoiceStep instances.
     * Additionally, some steps use addDialogStep() which appends to this list.
     *
     * @param qhStep the step to extract from
     * @return list of DialogueOptionResolvers
     */
    private List<DialogueOptionResolver> extractDialogueOptions(Object qhStep) {
        List<DialogueOptionResolver> resolvers = new ArrayList<>();

        try {
            // Get the DialogChoiceSteps from the step
            Field choicesField = findField(qhStep.getClass(), "choices");
            if (choicesField != null) {
                choicesField.setAccessible(true);
                Object dialogChoiceSteps = choicesField.get(qhStep);

                if (dialogChoiceSteps != null) {
                    // DialogChoiceSteps has a 'choices' field that is a List<DialogChoiceStep>
                    // Try getChoices() method first
                    List<Object> choices = null;
                    Method getChoicesMethod = findMethod(dialogChoiceSteps.getClass(), "getChoices");
                    if (getChoicesMethod != null) {
                        getChoicesMethod.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        List<Object> result = (List<Object>) getChoicesMethod.invoke(dialogChoiceSteps);
                        choices = result;
                    }
                    
                    // If method not found, try direct field access
                    if (choices == null) {
                        Field innerChoicesField = findField(dialogChoiceSteps.getClass(), "choices");
                        if (innerChoicesField != null) {
                            innerChoicesField.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            List<Object> result = (List<Object>) innerChoicesField.get(dialogChoiceSteps);
                            choices = result;
                        }
                    }

                    if (choices != null) {
                        for (Object choice : choices) {
                            DialogueOptionResolver resolver = translateDialogueChoice(choice);
                            if (resolver != null) {
                                resolvers.add(resolver);
                            }
                        }
                    }
                }
            }
            
            // Also check widgetChoices field for widget-based choices
            Field widgetChoicesField = findField(qhStep.getClass(), "widgetChoices");
            if (widgetChoicesField != null) {
                widgetChoicesField.setAccessible(true);
                Object widgetChoiceSteps = widgetChoicesField.get(qhStep);
                
                if (widgetChoiceSteps != null) {
                    // Similar extraction for WidgetChoiceSteps
                    List<Object> widgetChoices = extractChoicesFromStepsObject(widgetChoiceSteps);
                    for (Object choice : widgetChoices) {
                        DialogueOptionResolver resolver = translateDialogueChoice(choice);
                        if (resolver != null) {
                            resolvers.add(resolver);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract dialogue options from step", e);
        }

        return resolvers;
    }
    
    /**
     * Extract choices list from a DialogChoiceSteps or WidgetChoiceSteps object.
     */
    @SuppressWarnings("unchecked")
    private List<Object> extractChoicesFromStepsObject(Object stepsObject) {
        try {
            // Try getChoices() method first
            Method getChoicesMethod = findMethod(stepsObject.getClass(), "getChoices");
            if (getChoicesMethod != null) {
                getChoicesMethod.setAccessible(true);
                Object result = getChoicesMethod.invoke(stepsObject);
                if (result instanceof List) {
                    return (List<Object>) result;
                }
            }
            
            // Try direct field access
            Field choicesField = findField(stepsObject.getClass(), "choices");
            if (choicesField != null) {
                choicesField.setAccessible(true);
                Object result = choicesField.get(stepsObject);
                if (result instanceof List) {
                    return (List<Object>) result;
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract choices from steps object", e);
        }
        return Collections.emptyList();
    }

    /**
     * Translate a Quest Helper DialogChoiceStep/WidgetChoiceStep to our DialogueOptionResolver.
     *
     * <p>WidgetChoiceStep (parent of DialogChoiceStep) supports multiple ways to select an option:
     * <ul>
     *   <li>Text match: 'choice' field contains exact text to match</li>
     *   <li>Pattern match: 'pattern' field contains regex Pattern</li>
     *   <li>Index match: 'choiceById' field contains 0-based option index</li>
     *   <li>Varbit-based: 'varbitId' + 'varbitValueToAnswer' map for dynamic selection</li>
     * </ul>
     *
     * <p>DialogChoiceStep adds:
     * <ul>
     *   <li>Context matching: 'expectedPreviousLine' for context-dependent options</li>
     * </ul>
     *
     * <p>Both support exclusions via 'excludedStrings' field.
     */
    private DialogueOptionResolver translateDialogueChoice(Object dialogChoiceStep) {
        try {
            // Get the choice text (direct text match)
            String choiceText = getStringField(dialogChoiceStep, "choice");

            // Get regex pattern if present
            Object patternObj = getFieldValue(dialogChoiceStep, "pattern");
            Pattern pattern = null;
            if (patternObj instanceof Pattern) {
                pattern = (Pattern) patternObj;
            } else if (patternObj != null) {
                // It might be stored as a string pattern
                String patternStr = patternObj.toString();
                if (patternStr != null && !patternStr.isEmpty()) {
                    try {
                        pattern = Pattern.compile(patternStr);
                    } catch (Exception e) {
                        log.trace("Invalid pattern string: {}", patternStr);
                    }
                }
            }

            // Get choice by ID (0-based index)
            int choiceById = getIntFieldSafe(dialogChoiceStep, "choiceById", -1);

            // Get varbit-based options (dynamic selection based on game state)
            int varbitId = getIntFieldSafe(dialogChoiceStep, "varbitId", -1);
            @SuppressWarnings("unchecked")
            Map<Integer, String> varbitValueToAnswer = (Map<Integer, String>) 
                    getFieldValue(dialogChoiceStep, "varbitValueToAnswer");
            
            // Also check for single varbit value (some steps use this pattern)
            int varbitValue = getIntFieldSafe(dialogChoiceStep, "varbitValue", -1);

            // Get expected previous line for context-dependent options (DialogChoiceStep specific)
            String expectedPreviousLine = getStringField(dialogChoiceStep, "expectedPreviousLine");

            // Get exclusions - text that should NOT trigger this choice
            @SuppressWarnings("unchecked")
            List<String> excludedStrings = (List<String>) getFieldValue(dialogChoiceStep, "excludedStrings");

            // Get widget group/child IDs for validation
            int groupId = getIntFieldSafe(dialogChoiceStep, "groupId", -1);
            int childId = getIntFieldSafe(dialogChoiceStep, "childId", -1);

            // Determine the resolver type based on available data
            // Priority: varbit > pattern > index+text > index > text
            DialogueOptionResolver resolver = null;

            if (varbitId != -1 && varbitValueToAnswer != null && !varbitValueToAnswer.isEmpty()) {
                // Varbit-based dynamic selection
                resolver = DialogueOptionResolver.varbitBased(varbitId, varbitValueToAnswer);
                log.trace("Created varbit-based resolver: varbitId={}, options={}", varbitId, varbitValueToAnswer.size());
            } else if (pattern != null) {
                // Regex pattern matching
                resolver = DialogueOptionResolver.pattern(pattern);
                log.trace("Created pattern resolver: {}", pattern.pattern());
            } else if (choiceById != -1 && choiceText != null) {
                // Index + text verification (most precise)
                // Use text-based but with index hint - for now just use text
                resolver = DialogueOptionResolver.text(choiceText);
                log.trace("Created text resolver with index hint: text={}, index={}", choiceText, choiceById);
            } else if (choiceById != -1) {
                // Pure index-based selection
                resolver = DialogueOptionResolver.index(choiceById);
                log.trace("Created index resolver: {}", choiceById);
            } else if (choiceText != null && !choiceText.isEmpty()) {
                // Simple text matching
                resolver = DialogueOptionResolver.text(choiceText);
                log.trace("Created text resolver: {}", choiceText);
            } else {
                // No valid selection criteria
                log.trace("DialogChoiceStep has no valid selection criteria");
                return null;
            }

            // Apply context requirements (DialogChoiceStep specific)
            if (expectedPreviousLine != null && !expectedPreviousLine.isEmpty()) {
                resolver = resolver.withExpectedPreviousLine(expectedPreviousLine);
                log.trace("Added context requirement: {}", expectedPreviousLine);
            }

            // Apply exclusions - if multiple, apply each
            if (excludedStrings != null && !excludedStrings.isEmpty()) {
                for (String exclusion : excludedStrings) {
                    if (exclusion != null && !exclusion.isEmpty()) {
                        resolver = resolver.withExclusion(exclusion);
                        log.trace("Added exclusion: {}", exclusion);
                    }
                }
            }

            return resolver;

        } catch (Exception e) {
            log.debug("Could not translate dialogue choice: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract simple text strings from resolvers (for basic dialogue options).
     */
    private List<String> extractDialogueTexts(List<DialogueOptionResolver> resolvers) {
        List<String> texts = new ArrayList<>();
        for (DialogueOptionResolver resolver : resolvers) {
            if (resolver.getType() == DialogueOptionResolver.ResolverType.TEXT) {
                texts.add(resolver.getOptionText());
            }
        }
        return texts;
    }

    // ========================================================================
    // Menu Action Inference
    // ========================================================================

    /**
     * Infer the menu action from step text.
     */
    private String inferObjectMenuAction(String text) {
        if (text == null) {
            return "Interact";
        }

        String lowerText = text.toLowerCase();

        // Common action keywords
        if (lowerText.contains("climb") && lowerText.contains("up")) {
            return "Climb-up";
        }
        if (lowerText.contains("climb") && lowerText.contains("down")) {
            return "Climb-down";
        }
        if (lowerText.contains("climb")) {
            return "Climb";
        }
        if (lowerText.contains("open")) {
            return "Open";
        }
        if (lowerText.contains("close")) {
            return "Close";
        }
        if (lowerText.contains("search")) {
            return "Search";
        }
        if (lowerText.contains("examine")) {
            return "Examine";
        }
        if (lowerText.contains("enter")) {
            return "Enter";
        }
        if (lowerText.contains("exit")) {
            return "Exit";
        }
        if (lowerText.contains("use") || lowerText.contains("operate")) {
            return "Use";
        }
        if (lowerText.contains("take") || lowerText.contains("pick")) {
            return "Take";
        }
        if (lowerText.contains("read")) {
            return "Read";
        }
        if (lowerText.contains("mine")) {
            return "Mine";
        }
        if (lowerText.contains("chop")) {
            return "Chop down";
        }
        if (lowerText.contains("fish")) {
            return "Fish";
        }
        if (lowerText.contains("cook")) {
            return "Cook";
        }
        if (lowerText.contains("smelt")) {
            return "Smelt";
        }
        if (lowerText.contains("bank")) {
            return "Bank";
        }
        if (lowerText.contains("pray") || lowerText.contains("altar")) {
            return "Pray";
        }
        if (lowerText.contains("milk")) {
            return "Milk";
        }
        if (lowerText.contains("fill") && lowerText.contains("hopper")) {
            return "Fill";
        }
        if (lowerText.contains("operate") || lowerText.contains("control")) {
            return "Operate";
        }
        if (lowerText.contains("collect") || lowerText.contains("flour") || lowerText.contains("bin")) {
            return "Empty";
        }

        // Default
        return "Interact";
    }

    /**
     * Infer NPC menu action from step text.
     *
     * @param text the step description text
     * @return the inferred menu action
     */
    private String inferNpcMenuAction(String text) {
        if (text == null) {
            return "Talk-to";
        }

        String lowerText = text.toLowerCase();

        // Pickpocket actions
        if (lowerText.contains("pickpocket") || lowerText.contains("pick pocket") ||
            lowerText.contains("steal from") || lowerText.contains("thieve")) {
            return "Pickpocket";
        }

        // Attack/combat actions
        if (lowerText.contains("attack") || lowerText.contains("kill") ||
            lowerText.contains("fight") || lowerText.contains("slay")) {
            return "Attack";
        }

        // Trade/shop actions
        if (lowerText.contains("trade") || lowerText.contains("shop") ||
            lowerText.contains("buy from") || lowerText.contains("sell to")) {
            return "Trade";
        }

        // Bank actions
        if (lowerText.contains("bank") && !lowerText.contains("talk")) {
            return "Bank";
        }

        // Examine actions
        if (lowerText.contains("examine") && !lowerText.contains("talk")) {
            return "Examine";
        }

        // Follow actions
        if (lowerText.contains("follow") && !lowerText.contains("talk")) {
            return "Follow";
        }

        // Use item on NPC (covered by different task, but may appear in text)
        if (lowerText.contains("use") && lowerText.contains("on")) {
            return "Use";
        }

        // Default to Talk-to for NPC interactions
        return "Talk-to";
    }

    // ========================================================================
    // Reflection Utilities
    // ========================================================================

    private String getStepText(Object step) {
        try {
            Method getTextMethod = findMethod(step.getClass(), "getText");
            if (getTextMethod != null) {
                getTextMethod.setAccessible(true);
                Object result = getTextMethod.invoke(step);
                if (result instanceof List) {
                    List<?> texts = (List<?>) result;
                    return texts.isEmpty() ? "" : String.valueOf(texts.get(0));
                }
                return result != null ? result.toString() : "";
            }
        } catch (Exception e) {
            log.trace("Could not get step text", e);
        }
        return "";
    }

    private WorldPoint getWorldPoint(Object step) {
        String stepClassName = step.getClass().getSimpleName();
        try {
            // Try definedPoint first (new QH style)
            Field definedPointField = findField(step.getClass(), "definedPoint");
            if (definedPointField != null) {
                definedPointField.setAccessible(true);
                Object definedPoint = definedPointField.get(step);
                if (definedPoint != null) {
                    Method getWorldPointMethod = findMethod(definedPoint.getClass(), "getWorldPoint");
                    if (getWorldPointMethod != null) {
                        getWorldPointMethod.setAccessible(true);
                        return (WorldPoint) getWorldPointMethod.invoke(definedPoint);
                    }
                }
            }

            // Try direct worldPoint field
            Field worldPointField = findField(step.getClass(), "worldPoint");
            if (worldPointField != null) {
                worldPointField.setAccessible(true);
                return (WorldPoint) worldPointField.get(step);
            }
        } catch (Exception e) {
            log.warn("Failed to extract WorldPoint from {} step - Quest Helper API may have changed", 
                    stepClassName, e);
            return null;
        }
        // No location field found - this is normal for some step types
        log.trace("No WorldPoint field found in {} step", stepClassName);
        return null;
    }

    /**
    * Extract an explicit menu action from Quest Helper step if provided.
    */
    @SuppressWarnings("unchecked")
    private String extractMenuAction(Object step) {
        // Common field names used in Quest Helper steps
        String[] fieldNames = new String[] { "action", "menuAction" };
        for (String name : fieldNames) {
            String value = getStringField(step, name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }

        try {
            Field actionsField = findField(step.getClass(), "actions");
            if (actionsField != null) {
                actionsField.setAccessible(true);
                Object value = actionsField.get(step);
                if (value instanceof List) {
                    List<Object> list = (List<Object>) value;
                    for (Object item : list) {
                        if (item != null) {
                            String val = item.toString();
                            if (!val.isEmpty()) {
                                return val;
                            }
                        }
                    }
                } else if (value != null) {
                    String val = value.toString();
                    if (!val.isEmpty()) {
                        return val;
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract explicit menu action: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Safe instanceof by class name to avoid class loading failures.
     */
    private boolean isInstanceOf(Object obj, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return clazz.isAssignableFrom(obj.getClass());
        } catch (ClassNotFoundException e) {
            log.trace("Quest Helper class {} not found on classpath", className);
            return false;
        } catch (Exception e) {
            log.trace("Instanceof check failed for {}: {}", className, e.getMessage());
            return false;
        }
    }

    /**
     * Extract object IDs for ship steps from the step itself or its Conditional children.
     */
    private List<Integer> extractShipObjectIds(Object qhStep) {
        List<Integer> ids = new ArrayList<>();

        // Direct objectID field
        int directId = getIntFieldSafe(qhStep, "objectID", -1);
        if (directId > 0) {
            ids.add(directId);
        }

        // Alternate object IDs
        ids.addAll(extractAlternateIds(qhStep, "alternateObjectIDs", "alternateObjects"));

        // Inspect conditional children if present
        try {
            Field stepsField = findField(qhStep.getClass(), "steps");
            if (stepsField != null) {
                stepsField.setAccessible(true);
                Object value = stepsField.get(qhStep);
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> branches = (Map<Object, Object>) value;
                    for (Object child : branches.values()) {
                        if (child == null) continue;
                        int childId = getIntFieldSafe(child, "objectID", -1);
                        if (childId > 0) {
                            ids.add(childId);
                        }
                        ids.addAll(extractAlternateIds(child, "alternateObjectIDs", "alternateObjects"));
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract ship object IDs: {}", e.getMessage());
        }

        // Deduplicate
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    /**
     * Extract first child location from ConditionalStep branches.
     */
    private WorldPoint extractFirstChildLocation(Object qhStep) {
        try {
            Field stepsField = findField(qhStep.getClass(), "steps");
            if (stepsField != null) {
                stepsField.setAccessible(true);
                Object value = stepsField.get(qhStep);
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> branches = (Map<Object, Object>) value;
                    for (Object child : branches.values()) {
                        if (child == null) continue;
                        WorldPoint wp = getWorldPoint(child);
                        if (wp != null) {
                            return wp;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract child location: {}", e.getMessage());
        }
        return null;
    }

    private int getIntField(Object obj, String fieldName) throws Exception {
        Field field = findField(obj.getClass(), fieldName);
        if (field != null) {
            field.setAccessible(true);
            return field.getInt(obj);
        }
        throw new NoSuchFieldException(fieldName);
    }

    private int getIntFieldSafe(Object obj, String fieldName, int defaultValue) {
        try {
            return getIntField(obj, fieldName);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String getStringField(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(obj);
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            log.trace("Could not get string field: {}", fieldName);
        }
        return null;
    }

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(obj);
            }
        } catch (Exception e) {
            log.trace("Could not get field value: {}", fieldName);
        }
        return null;
    }

    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String methodName) {
        Class<?> current = clazz;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    // ========================================================================
    // Item Requirement Extraction
    // ========================================================================

    /**
     * Cached item requirements.
     */
    private List<ItemRequirementInfo> cachedItemRequirements;

    /**
     * Extract all item requirements from the Quest Helper quest.
     *
     * <p>This calls getItemRequirements() and getItemRecommended() on the quest
     * and translates them to our ItemRequirementInfo format.
     *
     * @return list of all item requirements
     */
    public List<ItemRequirementInfo> extractItemRequirements() {
        if (cachedItemRequirements != null) {
            return cachedItemRequirements;
        }

        cachedItemRequirements = new ArrayList<>();

        // Extract required items
        List<Object> requiredItems = extractItemList("getItemRequirements");
        for (Object itemReq : requiredItems) {
            ItemRequirementInfo info = translateItemRequirement(itemReq, false);
            if (info != null) {
                cachedItemRequirements.add(info);
            }
        }

        // Extract recommended items
        List<Object> recommendedItems = extractItemList("getItemRecommended");
        for (Object itemReq : recommendedItems) {
            ItemRequirementInfo info = translateItemRequirement(itemReq, true);
            if (info != null) {
                cachedItemRequirements.add(info);
            }
        }

        log.info("Extracted {} item requirements from Quest Helper", cachedItemRequirements.size());
        return cachedItemRequirements;
    }

    /**
     * Get items that need to be acquired before starting the quest.
     *
     * <p>This filters out items that can be obtained during the quest,
     * returning only those that must be procured beforehand.
     *
     * @return list of pre-quest item requirements
     */
    public List<ItemRequirementInfo> getPreQuestItems() {
        return extractItemRequirements().stream()
                .filter(ItemRequirementInfo::needsPreQuestAcquisition)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get items that can be obtained during the quest.
     *
     * @return list of quest-obtainable item requirements
     */
    public List<ItemRequirementInfo> getQuestObtainableItems() {
        return extractItemRequirements().stream()
                .filter(ItemRequirementInfo::isObtainableDuringQuest)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Extract a list of item requirements by calling a method on the quest helper.
     */
    private List<Object> extractItemList(String methodName) {
        try {
            Method method = findMethod(questHelperInstance.getClass(), methodName);
            if (method != null) {
                method.setAccessible(true);
                Object result = method.invoke(questHelperInstance);
                if (result instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> items = (List<Object>) result;
                    return items;
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract item list via {}: {}", methodName, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Translate a Quest Helper ItemRequirement to our ItemRequirementInfo.
     */
    private ItemRequirementInfo translateItemRequirement(Object itemReq, boolean isRecommended) {
        if (itemReq == null) {
            return null;
        }

        try {
            // Get primary item ID
            int itemId = getIntFieldSafe(itemReq, "id", -1);
            if (itemId <= 0) {
                return null;
            }

            // Get name
            String name = getStringField(itemReq, "name");
            if (name == null) {
                name = "item_" + itemId;
            }

            // Get quantity
            int quantity = getIntFieldSafe(itemReq, "quantity", 1);

            // Get alternate IDs
            List<Integer> alternateIds = new ArrayList<>();
            Object alternatesObj = getFieldValue(itemReq, "alternateItems");
            if (alternatesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Integer> alts = (List<Integer>) alternatesObj;
                alternateIds.addAll(alts);
            }

            // Check if must be equipped
            boolean mustBeEquipped = getBoolFieldSafe(itemReq, "mustBeEquipped", false);

            // Check if consumed
            boolean consumed = getBoolFieldSafe(itemReq, "isConsumedItem", true);

            // Check tooltip for "obtained during quest"
            String tooltip = getStringField(itemReq, "tooltip");
            boolean obtainableDuringQuest = false;
            if (tooltip != null) {
                String lower = tooltip.toLowerCase();
                obtainableDuringQuest = lower.contains("can be obtained during the quest.");
            }

            return ItemRequirementInfo.builder()
                    .itemId(itemId)
                    .name(name)
                    .quantity(quantity)
                    .alternateIds(alternateIds)
                    .mustBeEquipped(mustBeEquipped)
                    .consumed(consumed)
                    .obtainableDuringQuest(obtainableDuringQuest)
                    .recommended(isRecommended)
                    .tooltip(tooltip)
                    .build();

        } catch (Exception e) {
            log.debug("Could not translate item requirement: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract alternate IDs from a Quest Helper step.
     * Quest Helper stores alternates in fields like alternateNpcIDs, alternateObjects, etc.
     *
     * @param step       the Quest Helper step object
     * @param fieldNames possible field names to check (checked in order)
     * @return list of alternate IDs, or empty list if none found
     */
    private List<Integer> extractAlternateIds(Object step, String... fieldNames) {
        List<Integer> alternates = new ArrayList<>();

        for (String fieldName : fieldNames) {
            Object value = getFieldValue(step, fieldName);
            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<?> list = (List<?>) value;
                for (Object item : list) {
                    if (item instanceof Integer) {
                        alternates.add((Integer) item);
                    }
                }
                if (!alternates.isEmpty()) {
                    return alternates;
                }
            } else if (value instanceof int[]) {
                int[] arr = (int[]) value;
                for (int id : arr) {
                    alternates.add(id);
                }
                if (!alternates.isEmpty()) {
                    return alternates;
                }
            }
        }

        return alternates;
    }

    /**
     * Safe boolean field getter.
     */
    private boolean getBoolFieldSafe(Object obj, String fieldName, boolean defaultValue) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.getBoolean(obj);
            }
        } catch (Exception e) {
            log.trace("Could not get boolean field: {}", fieldName);
        }
        return defaultValue;
    }

    // ========================================================================
    // Quest Points and Requirements
    // ========================================================================

    /**
     * Get the quest point reward for completing this quest.
     *
     * @return quest points
     * @throws IllegalStateException if unable to determine
     */
    public int getQuestPoints() {
        try {
            Method method = findMethod(questHelperInstance.getClass(), "getQuestPointReward");
            if (method != null) {
                method.setAccessible(true);
                Object reward = method.invoke(questHelperInstance);
                if (reward != null) {
                    Method getPoints = reward.getClass().getMethod("getPoints");
                    return (int) getPoints.invoke(reward);
                }
            }
            // No quest point reward defined = 0 points (some quests give 0)
            return 0;
        } catch (Exception e) {
            log.warn("Failed to get quest points from Quest Helper", e);
            throw new IllegalStateException("Cannot get quest points from Quest Helper", e);
        }
    }

    /**
     * Check if this is a members-only quest.
     *
     * @return true if members quest, false if F2P
     */
    public boolean isMembers() {
        try {
            // Get the quest enum from the helper (it has getQuest())
            Method getQuestMethod = findMethod(questHelperInstance.getClass(), "getQuest");
            if (getQuestMethod != null) {
                getQuestMethod.setAccessible(true);
                Object questEnum = getQuestMethod.invoke(questHelperInstance);
                if (questEnum != null) {
                    // QuestHelperQuest has getQuestType() which returns QuestDetails.Type
                    Method getQuestType = questEnum.getClass().getMethod("getQuestType");
                    Object questType = getQuestType.invoke(questEnum);
                    if (questType != null) {
                        String typeName = questType.toString();
                        // F2P, SKILL_F2P are free; P2P, SKILL_P2P are members
                        return !typeName.contains("F2P");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to determine if quest is members from Quest Helper", e);
        }
        // Default to members if we can't determine (safer assumption)
        return true;
    }

    /**
     * Get the quest difficulty.
     *
     * @return difficulty string
     */
    public String getDifficulty() {
        try {
            Method getQuestMethod = findMethod(questHelperInstance.getClass(), "getQuest");
            if (getQuestMethod != null) {
                getQuestMethod.setAccessible(true);
                Object questEnum = getQuestMethod.invoke(questHelperInstance);
                if (questEnum != null) {
                    Method getDifficulty = questEnum.getClass().getMethod("getDifficulty");
                    Object difficulty = getDifficulty.invoke(questEnum);
                    if (difficulty != null) {
                        return difficulty.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not get difficulty: {}", e.getMessage());
        }
        return "Unknown";
    }

    /**
     * Get the general requirements for this quest.
     *
     * @return list of requirement descriptions, or empty list if unable to determine
     */
    public List<String> getGeneralRequirementDescriptions() {
        List<String> descriptions = new ArrayList<>();
        try {
            Method method = findMethod(questHelperInstance.getClass(), "getGeneralRequirements");
            if (method != null) {
                method.setAccessible(true);
                Object result = method.invoke(questHelperInstance);
                if (result instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> reqs = (List<Object>) result;
                    for (Object req : reqs) {
                        if (req == null) continue;
                        try {
                            // Requirement has getDisplayText() method
                            Method getDisplayText = req.getClass().getMethod("getDisplayText");
                            String text = (String) getDisplayText.invoke(req);
                            if (text != null && !text.isEmpty()) {
                                descriptions.add(text);
                            }
                        } catch (Exception e) {
                            // Try toString as fallback
                            descriptions.add(req.toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not get general requirements: {}", e.getMessage());
        }
        return descriptions;
    }

    /**
     * Check if the client currently meets all quest requirements.
     *
     * @return true if requirements are met, false otherwise
     */
    public boolean clientMeetsRequirements() {
        try {
            Method method = findMethod(questHelperInstance.getClass(), "clientMeetsRequirements");
            if (method != null) {
                method.setAccessible(true);
                Object result = method.invoke(questHelperInstance);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            }
        } catch (Exception e) {
            log.trace("Could not check requirements: {}", e.getMessage());
        }
        // Default to true if we can't determine
        return true;
    }

    /**
     * Get skill requirements for this quest.
     *
     * @return list of SkillRequirementInfo objects
     */
    public List<SkillRequirementInfo> getSkillRequirements() {
        List<SkillRequirementInfo> skills = new ArrayList<>();
        try {
            Method method = findMethod(questHelperInstance.getClass(), "getGeneralRequirements");
            if (method != null) {
                method.setAccessible(true);
                Object result = method.invoke(questHelperInstance);
                if (result instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> reqs = (List<Object>) result;
                    for (Object req : reqs) {
                        if (req == null) continue;
                        String className = req.getClass().getSimpleName();
                        // SkillRequirement has getSkill() and getRequiredLevel()
                        if (className.contains("SkillRequirement")) {
                            try {
                                Method getSkill = req.getClass().getMethod("getSkill");
                                Method getRequiredLevel = req.getClass().getMethod("getRequiredLevel");
                                Method check = req.getClass().getMethod("check", net.runelite.api.Client.class);
                                
                                Object skill = getSkill.invoke(req);
                                int level = (int) getRequiredLevel.invoke(req);
                                String skillName = skill != null ? skill.toString() : "Unknown";
                                
                                // We can't easily check if met without a client reference
                                // For now, just capture the requirement
                                skills.add(new SkillRequirementInfo(skillName, level, false));
                            } catch (Exception e) {
                                log.trace("Could not extract skill requirement details", e);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not get skill requirements: {}", e.getMessage());
        }
        return skills;
    }

    /**
     * Get quest requirements for this quest.
     *
     * @return list of QuestRequirementInfo objects
     */
    public List<QuestRequirementInfo> getQuestRequirements() {
        List<QuestRequirementInfo> quests = new ArrayList<>();
        try {
            Method method = findMethod(questHelperInstance.getClass(), "getGeneralRequirements");
            if (method != null) {
                method.setAccessible(true);
                Object result = method.invoke(questHelperInstance);
                if (result instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> reqs = (List<Object>) result;
                    for (Object req : reqs) {
                        if (req == null) continue;
                        String className = req.getClass().getSimpleName();
                        // QuestRequirement has getQuest()
                        if (className.contains("QuestRequirement")) {
                            try {
                                Method getQuest = req.getClass().getMethod("getQuest");
                                Object quest = getQuest.invoke(req);
                                if (quest != null) {
                                    // QuestHelperQuest enum has getName()
                                    Method getName = quest.getClass().getMethod("getName");
                                    String questName = (String) getName.invoke(quest);
                                    String questId = quest.toString();
                                    quests.add(new QuestRequirementInfo(questId, questName, false));
                                }
                            } catch (Exception e) {
                                log.trace("Could not extract quest requirement details", e);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Could not get quest requirements: {}", e.getMessage());
        }
        return quests;
    }

    /**
     * Skill requirement info extracted from Quest Helper.
     */
    @Getter
    public static class SkillRequirementInfo {
        private final String skillName;
        private final int requiredLevel;
        private final boolean met;

        public SkillRequirementInfo(String skillName, int requiredLevel, boolean met) {
            this.skillName = skillName;
            this.requiredLevel = requiredLevel;
            this.met = met;
        }
    }

    /**
     * Quest requirement info extracted from Quest Helper.
     */
    @Getter
    public static class QuestRequirementInfo {
        private final String questId;
        private final String questName;
        private final boolean completed;

        public QuestRequirementInfo(String questId, String questName, boolean completed) {
            this.questId = questId;
            this.questName = questName;
            this.completed = completed;
        }
    }

    // ========================================================================
    // Metadata Class
    // ========================================================================

    /**
     * Quest metadata extracted from Quest Helper.
     */
    @Getter
    public static class QuestMetadata {
        private final String name;
        private final int varId;
        private final boolean usesVarp; // true for varp, false for varbit
        private final int completionValue;

        public QuestMetadata(String name, int varId) {
            this(name, varId, false, -1);
        }

        public QuestMetadata(String name, int varId, boolean usesVarp, int completionValue) {
            this.name = name;
            this.varId = varId;
            this.usesVarp = usesVarp;
            this.completionValue = completionValue;
        }
    }

    /**
     * Extract more detailed metadata from a QuestHelperQuest enum entry.
     *
     * @param questEnumEntry the QuestHelperQuest enum value
     * @return extracted metadata or null if extraction fails
     */
    public static QuestMetadata extractMetadataFromEnum(Object questEnumEntry) {
        if (questEnumEntry == null) {
            return null;
        }

        try {
            // Get name
            Method getNameMethod = questEnumEntry.getClass().getMethod("getName");
            String name = (String) getNameMethod.invoke(questEnumEntry);

            // Try to get varbit field
            Field varbitField = questEnumEntry.getClass().getDeclaredField("varbit");
            varbitField.setAccessible(true);
            Object varbit = varbitField.get(questEnumEntry);

            // Try to get varPlayer field
            Field varPlayerField = questEnumEntry.getClass().getDeclaredField("varPlayer");
            varPlayerField.setAccessible(true);
            Object varPlayer = varPlayerField.get(questEnumEntry);

            // Get completeValue
            Field completeValueField = questEnumEntry.getClass().getDeclaredField("completeValue");
            completeValueField.setAccessible(true);
            int completeValue = completeValueField.getInt(questEnumEntry);

            int varId = -1;
            boolean usesVarp = false;

            if (varbit != null) {
                // Get varbit ID via getId()
                Method getId = varbit.getClass().getMethod("getId");
                varId = (int) getId.invoke(varbit);
                usesVarp = false;
            } else if (varPlayer != null) {
                // Get varp ID via getId()
                Method getId = varPlayer.getClass().getMethod("getId");
                varId = (int) getId.invoke(varPlayer);
                usesVarp = true;
            }

            return new QuestMetadata(name, varId, usesVarp, completeValue);
        } catch (Exception e) {
            log.debug("Could not extract metadata from QuestHelperQuest enum", e);
            return null;
        }
    }

    // ========================================================================
    // Quest Interface Adapter
    // ========================================================================

    /**
     * Create a Quest implementation that wraps this bridge.
     *
     * @return a Quest that delegates to this bridge
     */
    public Quest toQuest() {
        QuestHelperBridge bridge = this;

        return new Quest() {
            @Override
            public String getId() {
                return bridge.getMetadata().getName().toUpperCase().replace(" ", "_");
            }

            @Override
            public String getName() {
                return bridge.getMetadata().getName();
            }

            @Override
            public int getProgressVarbit() {
                return bridge.getMetadata().getVarId();
            }

            @Override
            public boolean usesVarp() {
                return bridge.getMetadata().isUsesVarp();
            }

            @Override
            public int getCompletionValue() {
                return bridge.getMetadata().getCompletionValue();
            }

            @Override
            public Map<Integer, QuestStep> loadSteps() {
                return bridge.loadAllSteps();
            }

            @Override
            public boolean isComplete(int currentVarbitValue, Client client) {
                // Prefer Quest Helper's quest state; fall back to RuneLite Quest state; finally completionValue
                if (bridge.isQuestHelperComplete(client)) {
                    return true;
                }
                if (bridge.isRuneLiteQuestComplete(client)) {
                    return true;
                }

                int completionValue = bridge.getMetadata().getCompletionValue();
                return completionValue >= 0 && currentVarbitValue >= completionValue;
            }
        };
    }

    /**
     * Determine completion using Quest Helper's QuestHelperQuest state.
     */
    public boolean isQuestHelperComplete(Client client) {
        if (client == null || questEnumEntry == null) {
            return false;
        }
        try {
            Method getState = questEnumEntry.getClass().getMethod("getState", Client.class);
            Object state = getState.invoke(questEnumEntry, client);
            if (state != null) {
                return "FINISHED".equalsIgnoreCase(state.toString());
            }
        } catch (Exception e) {
            log.trace("Quest Helper completion check failed", e);
        }
        return false;
    }

    /**
     * Determine completion using RuneLite Quest enum when available.
     */
    public boolean isRuneLiteQuestComplete(Client client) {
        if (client == null) {
            return false;
        }
        try {
            String questId = normalizeQuestId(getMetadata().getName());
            net.runelite.api.Quest quest = net.runelite.api.Quest.valueOf(questId.toUpperCase());
            QuestState state = quest.getState(client);
            return state == QuestState.FINISHED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Normalize quest identifiers to match RuneLite Quest enum naming.
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
}

