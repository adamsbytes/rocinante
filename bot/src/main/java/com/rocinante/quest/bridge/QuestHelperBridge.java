package com.rocinante.quest.bridge;

import com.rocinante.quest.Quest;
import com.rocinante.quest.steps.*;
import com.rocinante.state.StateCondition;
import com.rocinante.tasks.impl.DialogueOptionResolver;
import com.rocinante.tasks.impl.EmoteTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
            String questName = extractQuestName();
            
            // Fall back to class name parsing if enum not available
            if (questName == null || questName.isEmpty()) {
                String className = questHelperInstance.getClass().getSimpleName();
                questName = className.replaceAll("([A-Z])", " $1").trim();
            }

            // Extract varbit/varp ID
            int varId = extractVarId();
            
            // Check if uses varp
            boolean usesVarp = questUsesVarp();
            
            // Extract completion value
            int completionValue = extractCompletionValue();

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
    private String extractQuestName() {
        try {
            // Try to get the quest enum
            Field questField = findField(questHelperInstance.getClass(), "quest");
            if (questField != null) {
                questField.setAccessible(true);
                Object questEnum = questField.get(questHelperInstance);
                if (questEnum != null) {
                    // QuestHelperQuest has getName() method
                    Method getNameMethod = questEnum.getClass().getMethod("getName");
                    return (String) getNameMethod.invoke(questEnum);
                }
            }
            
            // Alternative: try getQuest().getName()
            Method getQuestMethod = findMethod(questHelperInstance.getClass(), "getQuest");
            if (getQuestMethod != null) {
                getQuestMethod.setAccessible(true);
                Object questEnum = getQuestMethod.invoke(questHelperInstance);
                if (questEnum != null) {
                    Method getNameMethod = questEnum.getClass().getMethod("getName");
                    return (String) getNameMethod.invoke(questEnum);
                }
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
    private int extractVarId() {
        try {
            // First, try to get the quest field from the helper instance
            // BasicQuestHelper and its subclasses have a 'quest' field that is a QuestHelperQuest enum
            Field questField = findField(questHelperInstance.getClass(), "quest");
            if (questField != null) {
                questField.setAccessible(true);
                Object questEnum = questField.get(questHelperInstance);
                if (questEnum != null) {
                    return extractVarIdFromQuestEnum(questEnum);
                }
            }
            
            // Alternative: try to get quest via getQuest() method
            Method getQuestMethod = findMethod(questHelperInstance.getClass(), "getQuest");
            if (getQuestMethod != null) {
                getQuestMethod.setAccessible(true);
                Object questEnum = getQuestMethod.invoke(questHelperInstance);
                if (questEnum != null) {
                    return extractVarIdFromQuestEnum(questEnum);
                }
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
    private boolean questUsesVarp() {
        try {
            // Get the quest enum
            Field questField = findField(questHelperInstance.getClass(), "quest");
            if (questField != null) {
                questField.setAccessible(true);
                Object questEnum = questField.get(questHelperInstance);
                if (questEnum != null) {
                    // Check if varPlayer field is populated
                    Field varPlayerField = findField(questEnum.getClass(), "varPlayer");
                    if (varPlayerField != null) {
                        varPlayerField.setAccessible(true);
                        return varPlayerField.get(questEnum) != null;
                    }
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
    private int extractCompletionValue() {
        try {
            Field questField = findField(questHelperInstance.getClass(), "quest");
            if (questField != null) {
                questField.setAccessible(true);
                Object questEnum = questField.get(questHelperInstance);
                if (questEnum != null) {
                    Field completeValueField = findField(questEnum.getClass(), "completeValue");
                    if (completeValueField != null) {
                        completeValueField.setAccessible(true);
                        int completeValue = completeValueField.getInt(questEnum);
                        log.debug("Extracted completion value: {}", completeValue);
                        return completeValue;
                    }
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
        log.debug("Translating step type: {}", stepClassName);

        try {
            switch (stepClassName) {
                case "NpcStep":
                    return translateNpcStep(qhStep);
                case "NpcEmoteStep":
                    return translateNpcEmoteStep(qhStep);
                case "NpcFollowerStep":
                    return translateNpcFollowerStep(qhStep);
                case "MultiNpcStep":
                    return translateMultiNpcStep(qhStep);
                case "ObjectStep":
                    return translateObjectStep(qhStep);
                case "ItemStep":
                    return translateItemStep(qhStep);
                case "ConditionalStep":
                case "ReorderableConditionalStep":
                    return translateConditionalStep(qhStep);
                case "EmoteStep":
                    return translateEmoteStep(qhStep);
                case "DigStep":
                    return translateDigStep(qhStep);
                case "TileStep":
                    return translateTileStep(qhStep);
                case "WidgetStep":
                    return translateWidgetStep(qhStep);
                case "PuzzleWrapperStep":
                    return translatePuzzleWrapperStep(qhStep);
                case "PuzzleStep":
                    return translatePuzzleStep(qhStep);
                case "QuestSyncStep":
                    return translateQuestSyncStep(qhStep);
                case "DetailedOwnerStep":
                    return translateDetailedOwnerStep(qhStep);
                case "BoardShipStep":
                case "SailStep":
                    // These are for the Sailing skill (released December 2024) - intentionally skipped
                    log.debug("Skipping {} - Sailing skill not yet supported", stepClassName);
                    return null;
                case "DetailedQuestStep":
                    return translateDetailedStep(qhStep);
                default:
                    // Check if step implements OwnerStep interface for recursive extraction
                    if (implementsOwnerStep(qhStep)) {
                        return translateOwnerStep(qhStep);
                    }
                    log.debug("Unsupported step type: {}", stepClassName);
                    return translateGenericStep(qhStep);
            }
        } catch (Exception e) {
            log.warn("Failed to translate step: {}", stepClassName, e);
            return null;
        }
    }

    /**
     * Translate an NpcStep to NpcQuestStep.
     */
    private NpcQuestStep translateNpcStep(Object qhStep) throws Exception {
        int npcId = getIntField(qhStep, "npcID");
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        // Infer menu action from step text
        String menuAction = inferNpcMenuAction(text);
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
     * Translate an ObjectStep to ObjectQuestStep.
     */
    private ObjectQuestStep translateObjectStep(Object qhStep) throws Exception {
        int objectId = getIntField(qhStep, "objectID");
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);

        // Infer menu action from step text
        String menuAction = inferObjectMenuAction(text);

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
            log.warn("Could not extract item ID from ItemStep: text={}", text);
            // Fall back to walk step if we have a location
            if (location != null) {
                return new WalkQuestStep(location, text);
            }
            return null;
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

        // Detailed steps are often just instructions, may need WalkQuestStep
        if (location != null) {
            return new WalkQuestStep(location, text);
        }

        // Return a generic widget step that does nothing specific
        // This would be a placeholder that needs manual handling
        log.debug("Translated DetailedQuestStep as placeholder: {}", text);
        return null;
    }

    /**
     * Generic fallback translation.
     */
    private QuestStep translateGenericStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        log.debug("Generic step translation (unsupported type): {}", text);
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
            log.warn("Could not extract emote from EmoteStep: {}", text);
            // Fall back to walk step if we have location
            if (location != null) {
                return null; // Let caller handle with WalkQuestStep
            }
            return null;
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
            log.warn("DigStep has no location: {}", text);
            return null;
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
            log.warn("TileStep has no location: {}", text);
            return null;
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

        log.warn("Could not extract widget details from WidgetStep: {}", text);
        return null;
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

        log.warn("Could not translate NpcEmoteStep: {}", text);
        return null;
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
    private QuestStep translatePuzzleStep(Object qhStep) throws Exception {
        String text = getStepText(qhStep);
        WorldPoint location = getWorldPoint(qhStep);
        
        // PuzzleStep uses ButtonHighlightCalculator to determine which buttons to click
        // We can't extract this logic statically, so we create a placeholder step
        // The AI Director will handle puzzle solving at runtime
        
        log.info("PuzzleStep encountered - puzzle solving requires AI Director: {}", text);
        
        // Create a widget step placeholder that indicates a puzzle needs solving
        // The quest executor should detect this and engage the AI Director
        if (location != null) {
            return new WalkQuestStep(location, text + " (puzzle - requires AI Director)");
        }
        
        // Return null to indicate this step needs special handling
        return null;
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
        
        // Add a condition that checks if the NPC is a follower
        // NpcFollowerStep uses varp 447 to check follower status
        // The lower 16 bits of varp 447 contain the follower's NPC index
        StateCondition followerCondition = ctx -> {
            int followerVarp = ctx.getClient().getVarpValue(447);
            int followerId = followerVarp & 0x0000FFFF;
            // Follower ID > 0 means we have a follower
            return followerId > 0;
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
            log.trace("Could not get world point", e);
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
                obtainableDuringQuest = tooltip.toLowerCase().contains("obtained during");
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
                int value = bridge.getMetadata().getCompletionValue();
                if (value == -1) {
                    // Completion value not specified - use RuneLite's Quest enum
                    // which has proper completion values for standard quests
                    // For now, return a sentinel that means "check via client script"
                    return -1;
                }
                return value;
            }

            @Override
            public Map<Integer, QuestStep> loadSteps() {
                return bridge.loadAllSteps();
            }
        };
    }
}

