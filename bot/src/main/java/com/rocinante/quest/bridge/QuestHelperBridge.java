package com.rocinante.quest.bridge;

import com.rocinante.quest.Quest;
import com.rocinante.quest.steps.*;
import com.rocinante.state.StateCondition;
import com.rocinante.tasks.impl.DialogueOptionResolver;
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
     */
    private void extractMetadata() {
        try {
            // Try to get quest name from class name
            String className = questHelperInstance.getClass().getSimpleName();
            String questName = className.replaceAll("([A-Z])", " $1").trim();

            // Try to find varbit/varp ID via reflection
            int varId = extractVarId();

            this.metadata = new QuestMetadata(questName, varId);
            log.debug("Extracted metadata for quest: {} (varId: {})", questName, varId);
        } catch (Exception e) {
            log.warn("Failed to extract quest metadata", e);
            this.metadata = new QuestMetadata("Unknown Quest", -1);
        }
    }

    /**
     * Extract the varbit/varp ID used by this quest.
     */
    private int extractVarId() {
        // This would need to be implemented based on how Quest Helper
        // stores the var ID. For now, return -1 to indicate unknown.
        // The actual implementation would use reflection to access
        // the QuestHelperQuest enum that references this quest.
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
                case "ObjectStep":
                    return translateObjectStep(qhStep);
                case "ItemStep":
                    return translateItemStep(qhStep);
                case "ConditionalStep":
                    return translateConditionalStep(qhStep);
                case "DetailedQuestStep":
                    return translateDetailedStep(qhStep);
                default:
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

        NpcQuestStep step = new NpcQuestStep(npcId, text)
                .withMenuAction("Talk-to") // Default for NPC interactions
                .withDialogueExpected(true);

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
     */
    private int extractItemIdFromStep(Object qhStep) {
        // Try direct itemId field first
        int itemId = getIntFieldSafe(qhStep, "itemId", -1);
        if (itemId > 0) {
            return itemId;
        }

        // Try to get from item requirements
        try {
            Object itemReq = getFieldValue(qhStep, "itemRequirement");
            if (itemReq != null) {
                itemId = getIntFieldSafe(itemReq, "id", -1);
                if (itemId > 0) {
                    return itemId;
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract itemId from itemRequirement", e);
        }

        // Try items list/array
        try {
            Object items = getFieldValue(qhStep, "items");
            if (items instanceof int[]) {
                int[] itemIds = (int[]) items;
                if (itemIds.length > 0) {
                    return itemIds[0];
                }
            } else if (items instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> itemList = (List<Object>) items;
                if (!itemList.isEmpty()) {
                    Object first = itemList.get(0);
                    if (first instanceof Integer) {
                        return (Integer) first;
                    }
                    // Might be an ItemRequirement object
                    return getIntFieldSafe(first, "id", -1);
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract itemId from items list", e);
        }

        return -1;
    }

    /**
     * Extract item name from a Quest Helper ItemStep.
     */
    private String extractItemNameFromStep(Object qhStep, String fallbackText) {
        // Try itemName field
        String itemName = getStringField(qhStep, "itemName");
        if (itemName != null && !itemName.isEmpty()) {
            return itemName;
        }

        // Try to get from item requirement
        try {
            Object itemReq = getFieldValue(qhStep, "itemRequirement");
            if (itemReq != null) {
                itemName = getStringField(itemReq, "name");
                if (itemName != null && !itemName.isEmpty()) {
                    return itemName;
                }
            }
        } catch (Exception e) {
            log.trace("Could not extract itemName from itemRequirement", e);
        }

        // Parse from step text (e.g., "Grab an egg from the farm" -> "egg")
        if (fallbackText != null) {
            String lower = fallbackText.toLowerCase();
            // Common patterns
            if (lower.contains("grab") || lower.contains("pick up") || lower.contains("take")) {
                // Try to extract item name after common verbs
                String[] patterns = {"grab an? ", "grab the ", "pick up an? ", "pick up the ", "take an? ", "take the "};
                for (String pattern : patterns) {
                    int idx = lower.indexOf(pattern.replace("?", ""));
                    if (idx >= 0) {
                        String after = fallbackText.substring(idx + pattern.length() - 1).trim();
                        // Take first word or phrase before preposition
                        String[] words = after.split("\\s+(from|at|in|on)\\s+");
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
    // Dialogue Option Extraction
    // ========================================================================

    /**
     * Extract dialogue options from a Quest Helper step.
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
                    // Get the list of DialogChoiceStep from DialogChoiceSteps
                    Method getChoicesMethod = findMethod(dialogChoiceSteps.getClass(), "getChoices");
                    if (getChoicesMethod != null) {
                        getChoicesMethod.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        List<Object> choices = (List<Object>) getChoicesMethod.invoke(dialogChoiceSteps);

                        for (Object choice : choices) {
                            DialogueOptionResolver resolver = translateDialogueChoice(choice);
                            if (resolver != null) {
                                resolvers.add(resolver);
                            }
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
     * Translate a Quest Helper DialogChoiceStep to our DialogueOptionResolver.
     */
    private DialogueOptionResolver translateDialogueChoice(Object dialogChoiceStep) {
        try {
            // Get the choice text
            String choiceText = getStringField(dialogChoiceStep, "choice");

            // Get regex pattern if present
            Object patternObj = getFieldValue(dialogChoiceStep, "pattern");
            Pattern pattern = patternObj instanceof Pattern ? (Pattern) patternObj : null;

            // Get choice by ID
            int choiceById = getIntFieldSafe(dialogChoiceStep, "choiceById", -1);

            // Get varbit-based options
            int varbitId = getIntFieldSafe(dialogChoiceStep, "varbitId", -1);
            @SuppressWarnings("unchecked")
            Map<Integer, String> varbitValueToAnswer = (Map<Integer, String>) 
                    getFieldValue(dialogChoiceStep, "varbitValueToAnswer");

            // Get expected previous line for context-dependent options
            String expectedPreviousLine = getStringField(dialogChoiceStep, "expectedPreviousLine");

            // Get exclusions
            String exclusionText = null;
            @SuppressWarnings("unchecked")
            List<String> excludedStrings = (List<String>) getFieldValue(dialogChoiceStep, "excludedStrings");
            if (excludedStrings != null && !excludedStrings.isEmpty()) {
                exclusionText = excludedStrings.get(0); // Use first exclusion
            }

            // Create appropriate resolver
            DialogueOptionResolver resolver;

            if (varbitId != -1 && varbitValueToAnswer != null) {
                resolver = DialogueOptionResolver.varbitBased(varbitId, varbitValueToAnswer);
            } else if (pattern != null) {
                resolver = DialogueOptionResolver.pattern(pattern);
            } else if (choiceById != -1) {
                resolver = DialogueOptionResolver.index(choiceById);
            } else if (choiceText != null) {
                resolver = DialogueOptionResolver.text(choiceText);
            } else {
                return null;
            }

            // Apply context requirements
            if (expectedPreviousLine != null && !expectedPreviousLine.isEmpty()) {
                resolver.withExpectedPreviousLine(expectedPreviousLine);
            }

            // Apply exclusions
            if (exclusionText != null && !exclusionText.isEmpty()) {
                resolver.withExclusion(exclusionText);
            }

            return resolver;

        } catch (Exception e) {
            log.debug("Could not translate dialogue choice", e);
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

        public QuestMetadata(String name, int varId) {
            this.name = name;
            this.varId = varId;
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
                // Quest Helper quests typically use varps for progress
                return true;
            }

            @Override
            public int getCompletionValue() {
                // Would need to determine from quest data
                // For now, return a high value that likely indicates completion
                return 100;
            }

            @Override
            public Map<Integer, QuestStep> loadSteps() {
                return bridge.loadAllSteps();
            }
        };
    }
}

