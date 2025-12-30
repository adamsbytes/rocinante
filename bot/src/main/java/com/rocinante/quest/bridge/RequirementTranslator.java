package com.rocinante.quest.bridge;

import com.rocinante.state.Conditions;
import com.rocinante.state.StateCondition;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates Quest Helper requirements to our StateCondition system.
 *
 * <p>Quest Helper uses a hierarchy of Requirement classes:
 * <ul>
 *   <li>VarbitRequirement - checks varbit/varp values</li>
 *   <li>ZoneRequirement - checks player is in a zone</li>
 *   <li>ItemRequirement - checks inventory/equipment for items</li>
 *   <li>Conditions (composite) - combines requirements with AND/OR/NAND/NOR</li>
 *   <li>SkillRequirement - checks skill levels</li>
 *   <li>QuestRequirement - checks quest completion</li>
 * </ul>
 *
 * <p>This translator uses reflection to inspect Quest Helper requirement objects
 * and creates equivalent StateCondition predicates.
 *
 * <p>Example usage:
 * <pre>{@code
 * RequirementTranslator translator = new RequirementTranslator();
 *
 * // Translate a single requirement
 * Object qhRequirement = ...;
 * StateCondition condition = translator.translate(qhRequirement);
 *
 * // Use in task
 * if (condition.test(ctx)) {
 *     // Requirement met
 * }
 * }</pre>
 */
@Slf4j
public class RequirementTranslator {

    /**
     * Translate a Quest Helper requirement to a StateCondition.
     *
     * @param qhRequirement the Quest Helper requirement object
     * @return a StateCondition equivalent, or never if cannot be evaluated (blocks progress for safety)
     */
    public StateCondition translate(Object qhRequirement) {
        if (qhRequirement == null) {
            log.warn("Null requirement passed to translator - blocking progress for safety");
            return StateCondition.never();
        }

        String className = qhRequirement.getClass().getSimpleName();
        log.debug("Translating requirement: {}", className);

        try {
            switch (className) {
                case "VarbitRequirement":
                    return translateVarbitRequirement(qhRequirement);
                case "VarplayerRequirement":
                    return translateVarplayerRequirement(qhRequirement);
                case "ZoneRequirement":
                    return translateZoneRequirement(qhRequirement);
                case "ItemRequirement":
                case "ItemRequirements":
                case "TeleportItemRequirement":
                case "KeyringRequirement":
                case "FollowerItemRequirement":
                    return translateItemRequirement(qhRequirement);
                case "Conditions":
                    return translateCompositeRequirement(qhRequirement);
                case "SkillRequirement":
                    return translateSkillRequirement(qhRequirement);
                case "QuestRequirement":
                    return translateQuestRequirement(qhRequirement);
                case "ObjectCondition":
                    return translateObjectCondition(qhRequirement);
                case "NpcCondition":
                case "NpcRequirement":
                case "NpcInteractingRequirement":
                case "NpcInteractingWithNpcRequirement":
                case "NpcHintArrowRequirement":
                    return translateNpcCondition(qhRequirement);
                case "FollowerRequirement":
                case "NoFollowerRequirement":
                    return translateFollowerRequirement(qhRequirement);
                case "ChatMessageCondition":
                case "ChatMessageRequirement":
                case "MultiChatMessageRequirement":
                    // Chat conditions need special runtime handling - track via event system
                    return translateChatMessageRequirement(qhRequirement);
                case "DialogRequirement":
                    return translateDialogRequirement(qhRequirement);
                case "RuneliteRequirement":
                case "PlayerQuestStateRequirement":
                    return translateRuneliteRequirement(qhRequirement);
                case "ManualRequirement":
                    return translateManualRequirement(qhRequirement);
                case "MesBoxRequirement":
                    return translateMesBoxRequirement(qhRequirement);
                case "WidgetTextRequirement":
                case "WidgetPresenceRequirement":
                case "WidgetSpriteRequirement":
                case "WidgetModelRequirement":
                    return translateWidgetRequirement(qhRequirement);
                case "FreeInventorySlotRequirement":
                    return translateFreeInventoryRequirement(qhRequirement);
                case "InInstanceRequirement":
                    return translateInInstanceRequirement(qhRequirement);
                case "TileIsLoadedRequirement":
                    return translateTileLoadedRequirement(qhRequirement);
                case "CombatLevelRequirement":
                    return translateCombatLevelRequirement(qhRequirement);
                case "PrayerRequirement":
                case "PrayerPointRequirement":
                    return translatePrayerRequirement(qhRequirement);
                case "SpellbookRequirement":
                    return translateSpellbookRequirement(qhRequirement);
                case "WeightRequirement":
                    return translateWeightRequirement(qhRequirement);
                case "VarComparisonRequirement":
                    return translateVarComparisonRequirement(qhRequirement);
                case "ItemOnTileRequirement":
                case "ItemOnTileConsideringSceneLoadRequirement":
                    return translateItemOnTileRequirement(qhRequirement);
                case "NoItemRequirement":
                    return translateNoItemRequirement(qhRequirement);
                case "StepIsActiveRequirement":
                    // Step state tracking - requires runtime quest step state
                    log.warn("StepIsActiveRequirement cannot be evaluated statically - blocking progress for safety");
                    return StateCondition.never();
                case "ConfigRequirement":
                    // Config-based requirements are RuneLite client settings - cannot be evaluated
                    log.warn("ConfigRequirement requires RuneLite config access - blocking progress for safety");
                    return StateCondition.never();
                default:
                    log.error("Unknown requirement type '{}' - blocking progress for safety", className);
                    return StateCondition.never();
            }
        } catch (Exception e) {
            log.error("Failed to translate requirement {} - blocking progress for safety: {}", className, e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // Varbit/Varplayer Requirements
    // ========================================================================

    private StateCondition translateVarbitRequirement(Object req) throws Exception {
        int varbitId = getIntField(req, "varbitID");
        int requiredValue = getIntField(req, "requiredValue");
        Object operation = getFieldValue(req, "operation");

        if (operation == null) {
            return Conditions.varbitEquals(varbitId, requiredValue);
        }

        String opName = operation.toString();
        switch (opName) {
            case "EQUAL":
                return Conditions.varbitEquals(varbitId, requiredValue);
            case "GREATER_EQUAL":
                return Conditions.varbitGreaterThanOrEqual(varbitId, requiredValue);
            case "LESS_EQUAL":
                return ctx -> ctx.getClient().getVarbitValue(varbitId) <= requiredValue;
            case "NOT_EQUAL":
                return Conditions.varbitEquals(varbitId, requiredValue).not();
            case "GREATER":
                return Conditions.varbitGreaterThan(varbitId, requiredValue);
            case "LESS":
                return Conditions.varbitLessThan(varbitId, requiredValue);
            default:
                log.debug("Unknown varbit operation: {}", opName);
                return Conditions.varbitEquals(varbitId, requiredValue);
        }
    }

    private StateCondition translateVarplayerRequirement(Object req) throws Exception {
        int varplayerId = getIntField(req, "varplayerID");
        int requiredValue = getIntField(req, "requiredValue");
        Object operation = getFieldValue(req, "operation");

        // Varplayer uses the same logic as varbit but with Varp instead
        if (operation == null || operation.toString().equals("EQUAL")) {
            return ctx -> ctx.getClient().getVarpValue(varplayerId) == requiredValue;
        }

        String opName = operation.toString();
        switch (opName) {
            case "GREATER_EQUAL":
                return ctx -> ctx.getClient().getVarpValue(varplayerId) >= requiredValue;
            case "LESS_EQUAL":
                return ctx -> ctx.getClient().getVarpValue(varplayerId) <= requiredValue;
            case "NOT_EQUAL":
                return ctx -> ctx.getClient().getVarpValue(varplayerId) != requiredValue;
            case "GREATER":
                return ctx -> ctx.getClient().getVarpValue(varplayerId) > requiredValue;
            case "LESS":
                return ctx -> ctx.getClient().getVarpValue(varplayerId) < requiredValue;
            default:
                return ctx -> ctx.getClient().getVarpValue(varplayerId) == requiredValue;
        }
    }

    // ========================================================================
    // Zone Requirements
    // ========================================================================

    private StateCondition translateZoneRequirement(Object req) throws Exception {
        // checkInZone: true means "player should be in zone", false means "player should NOT be in zone"
        boolean checkInZone = getBoolFieldSafe(req, "checkInZone", true);

        // Get zones list
        Object zonesObj = getFieldValue(req, "zones");
        if (!(zonesObj instanceof List)) {
            log.warn("ZoneRequirement has no zones list - blocking progress for safety");
            return StateCondition.never();
        }

        @SuppressWarnings("unchecked")
        List<Object> zones = (List<Object>) zonesObj;
        if (zones.isEmpty()) {
            log.warn("ZoneRequirement has empty zones list - blocking progress for safety");
            return StateCondition.never();
        }

        // Create a condition that checks any zone
        List<StateCondition> zoneConditions = new ArrayList<>();
        for (Object zone : zones) {
            StateCondition zoneCond = translateSingleZone(zone);
            zoneConditions.add(zoneCond);
        }

        StateCondition anyZone = StateCondition.anyOf(zoneConditions.toArray(new StateCondition[0]));

        return checkInZone ? anyZone : anyZone.not();
    }

    private StateCondition translateSingleZone(Object zone) throws Exception {
        // Zone has minX, maxX, minY, maxY (public getters) and minPlane, maxPlane (private)
        int minX = getIntField(zone, "minX");
        int maxX = getIntField(zone, "maxX");
        int minY = getIntField(zone, "minY");
        int maxY = getIntField(zone, "maxY");
        // minPlane and maxPlane are private with no getters, use reflection
        int minPlane = getIntFieldSafe(zone, "minPlane", 0);
        int maxPlane = getIntFieldSafe(zone, "maxPlane", 2); // Default is 2 in Zone class

        return ctx -> {
            WorldPoint pos = ctx.getPlayerState().getWorldPosition();
            if (pos == null) return false;

            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getPlane() >= minPlane && pos.getPlane() <= maxPlane;
        };
    }

    // ========================================================================
    // Item Requirements
    // ========================================================================

    private StateCondition translateItemRequirement(Object req) throws Exception {
        int itemId = getIntField(req, "id");
        int quantity = getIntFieldSafe(req, "quantity", 1);

        // ItemRequirement uses mustBeEquipped() method, but field is also called mustBeEquipped
        boolean mustBeEquipped = getBoolFieldSafe(req, "mustBeEquipped", false);
        
        // Items obtainable during quest don't need to be checked - they'll be acquired as part of quest steps
        boolean obtainableDuringQuest = getBoolFieldSafe(req, "obtainableDuringQuest", false);
        if (obtainableDuringQuest) {
            return StateCondition.always();
        }

        // Handle alternate items (field is named alternateItems, not alternateItemIds)
        List<Integer> allIds = new ArrayList<>();
        allIds.add(itemId);

        Object alternatesObj = getFieldValue(req, "alternateItems");
        if (alternatesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Integer> alternates = (List<Integer>) alternatesObj;
            allIds.addAll(alternates);
        }

        if (mustBeEquipped) {
            // Check equipment
            int[] idArray = allIds.stream().mapToInt(i -> i).toArray();
            return Conditions.hasAnyEquipped(idArray);
        } else {
            // Check inventory OR bank - item just needs to be owned
            return ctx -> {
                for (int id : allIds) {
                    if (ctx.getInventoryState().hasItem(id, quantity)) {
                        return true;
                    }
                    if (ctx.getBankState().hasItem(id, quantity)) {
                        return true;
                    }
                }
                return false;
            };
        }
    }

    // ========================================================================
    // Composite Requirements
    // ========================================================================

    private StateCondition translateCompositeRequirement(Object req) throws Exception {
        Object logicTypeObj = getFieldValue(req, "logicType");
        String logicType = logicTypeObj != null ? logicTypeObj.toString() : "AND";

        Object conditionsObj = getFieldValue(req, "conditions");
        if (!(conditionsObj instanceof List)) {
            log.warn("CompositeRequirement has no conditions list - blocking progress for safety");
            return StateCondition.never();
        }

        @SuppressWarnings("unchecked")
        List<Object> childRequirements = (List<Object>) conditionsObj;

        List<StateCondition> childConditions = new ArrayList<>();
        for (Object child : childRequirements) {
            childConditions.add(translate(child));
        }

        StateCondition[] arr = childConditions.toArray(new StateCondition[0]);

        switch (logicType) {
            case "AND":
                return StateCondition.allOf(arr);
            case "OR":
                return StateCondition.anyOf(arr);
            case "NAND":
                return StateCondition.allOf(arr).not();
            case "NOR":
                return StateCondition.anyOf(arr).not();
            default:
                log.debug("Unknown logic type: {}", logicType);
                return StateCondition.allOf(arr);
        }
    }

    // ========================================================================
    // Skill Requirements
    // ========================================================================

    private StateCondition translateSkillRequirement(Object req) throws Exception {
        Object skillObj = getFieldValue(req, "skill");
        int requiredLevel = getIntField(req, "requiredLevel");

        if (skillObj == null) {
            log.warn("SkillRequirement has null skill - blocking progress for safety");
            return StateCondition.never();
        }

        String skillName = skillObj.toString().toLowerCase();
        int skillId = getSkillId(skillName);
        
        if (skillId < 0) {
            log.warn("SkillRequirement has unknown skill '{}' - blocking progress for safety", skillName);
            return StateCondition.never();
        }

        return ctx -> ctx.getClient().getRealSkillLevel(net.runelite.api.Skill.values()[skillId]) >= requiredLevel;
    }

    private int getSkillId(String skillName) {
        try {
            return net.runelite.api.Skill.valueOf(skillName.toUpperCase()).ordinal();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    // ========================================================================
    // Quest Requirements
    // ========================================================================

    private StateCondition translateQuestRequirement(Object req) throws Exception {
        Object questObj = getFieldValue(req, "quest");
        if (questObj == null) {
            log.warn("QuestRequirement has null quest - blocking progress for safety");
            return StateCondition.never();
        }

        // Quest Helper's Quest enum - try to get the Quest enum value and check completion
        // via RuneLite's Quest enum which has getState() method
        try {
            String questName = questObj.toString();
            // Try to find matching RuneLite Quest enum
            for (net.runelite.api.Quest quest : net.runelite.api.Quest.values()) {
                if (quest.name().equalsIgnoreCase(questName) || 
                    quest.getName().equalsIgnoreCase(questName.replace("_", " "))) {
                    final net.runelite.api.Quest matchedQuest = quest;
                    return ctx -> matchedQuest.getState(ctx.getClient()) == net.runelite.api.QuestState.FINISHED;
                }
            }
            log.warn("QuestRequirement could not find matching quest '{}' - blocking progress for safety", questName);
            return StateCondition.never();
        } catch (Exception e) {
            log.warn("QuestRequirement translation failed for '{}' - blocking progress for safety: {}", 
                    questObj, e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // Object/NPC Conditions
    // ========================================================================

    private StateCondition translateObjectCondition(Object req) throws Exception {
        int objectId = getIntField(req, "objectID");
        return Conditions.objectExists(objectId);
    }

    private StateCondition translateNpcCondition(Object req) throws Exception {
        int npcId = getIntField(req, "npcID");
        return Conditions.npcExists(npcId);
    }

    // ========================================================================
    // Widget Requirements
    // ========================================================================

    private StateCondition translateWidgetRequirement(Object req) throws Exception {
        int groupId = getIntFieldSafe(req, "groupId", -1);
        if (groupId < 0) {
            log.warn("WidgetRequirement has invalid groupId - blocking progress for safety");
            return StateCondition.never();
        }
        return Conditions.widgetVisible(groupId);
    }

    // ========================================================================
    // Dialog Requirements
    // ========================================================================

    /**
     * Translate a DialogRequirement.
     *
     * <p>DialogRequirement checks if specific dialogue text has been seen.
     * It tracks dialogue via ChatMessage events of type DIALOG.
     *
     * <p>Fields:
     * <ul>
     *   <li>talkerName: Optional NPC name filter</li>
     *   <li>text: List of text strings to match</li>
     *   <li>mustBeActive: If true, dialogue must currently be visible</li>
     *   <li>hasSeenDialog: Runtime state tracking</li>
     * </ul>
     */
    private StateCondition translateDialogRequirement(Object req) {
        try {
            @SuppressWarnings("unchecked")
            List<String> textList = (List<String>) getFieldValue(req, "text");
            String talkerName = getStringFieldSafe(req, "talkerName", null);
            boolean mustBeActive = getBoolFieldSafe(req, "mustBeActive", false);
            
            if (textList == null || textList.isEmpty()) {
                log.warn("DialogRequirement has no text list - blocking progress for safety");
                return StateCondition.never();
            }
            
            // For mustBeActive, we need to check current dialogue widget
            if (mustBeActive) {
                return ctx -> {
                    // Check if dialogue widget is visible and contains expected text
                    net.runelite.api.widgets.Widget dialogWidget = ctx.getClient().getWidget(231, 4);
                    if (dialogWidget == null || dialogWidget.isHidden()) {
                        return false;
                    }
                    String dialogText = dialogWidget.getText();
                    if (dialogText == null) {
                        return false;
                    }
                    // Check talker name if specified
                    if (talkerName != null) {
                        net.runelite.api.widgets.Widget nameWidget = ctx.getClient().getWidget(231, 3);
                        if (nameWidget == null || !talkerName.equals(nameWidget.getText())) {
                            return false;
                        }
                    }
                    // Check if any expected text is present
                    for (String text : textList) {
                        if (dialogText.contains(text)) {
                            return true;
                        }
                    }
                    return false;
                };
            }
            
            // For non-active, we'd need event tracking - cannot evaluate statically
            log.warn("DialogRequirement with mustBeActive=false requires event tracking - blocking progress for safety: {}", textList);
            return StateCondition.never();
            
        } catch (Exception e) {
            log.warn("Could not translate DialogRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // RuneLite Requirements
    // ========================================================================

    /**
     * Translate a RuneliteRequirement.
     *
     * <p>RuneliteRequirement stores state in RuneLite's config system with:
     * <ul>
     *   <li>runeliteIdentifier: Config key</li>
     *   <li>expectedValue: Value to check for</li>
     *   <li>requirements: Map of value -> Requirement for validation</li>
     * </ul>
     *
     * <p>This is used for persisting quest progress state that isn't tracked by varbits.
     */
    private StateCondition translateRuneliteRequirement(Object req) {
        try {
            String identifier = getStringFieldSafe(req, "runeliteIdentifier", null);
            String expectedValue = getStringFieldSafe(req, "expectedValue", "true");
            
            if (identifier == null) {
                log.warn("RuneliteRequirement has null identifier - blocking progress for safety");
                return StateCondition.never();
            }
            
            // RuneLite requirements check config manager - we can't access that
            // Instead, if there are nested requirements, translate those
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> requirements = 
                    (java.util.Map<String, Object>) getFieldValue(req, "requirements");
            
            if (requirements != null && !requirements.isEmpty()) {
                // Get the requirement for the expected value
                Object nestedReq = requirements.get(expectedValue);
                if (nestedReq != null) {
                    return translate(nestedReq);
                }
            }
            
            log.warn("RuneliteRequirement uses config storage, cannot directly evaluate '{}' - blocking progress for safety", identifier);
            return StateCondition.never();
            
        } catch (Exception e) {
            log.warn("Could not translate RuneliteRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // Manual Requirements
    // ========================================================================

    /**
     * Translate a ManualRequirement.
     *
     * <p>ManualRequirement is a simple boolean flag that is set programmatically.
     * Since we can't track external state changes, we check the current value.
     */
    private StateCondition translateManualRequirement(Object req) {
        try {
            boolean shouldPass = getBoolFieldSafe(req, "shouldPass", false);
            // ManualRequirement with shouldPass=true is intentionally always-true
            return shouldPass ? StateCondition.always() : StateCondition.never();
        } catch (Exception e) {
            log.warn("Could not translate ManualRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // MesBox Requirements
    // ========================================================================

    /**
     * Translate a MesBoxRequirement.
     *
     * <p>MesBoxRequirement extends ChatMessageRequirement and checks for
     * MESBOX type chat messages (game message boxes).
     */
    private StateCondition translateMesBoxRequirement(Object req) {
        try {
            @SuppressWarnings("unchecked")
            List<String> messages = (List<String>) getFieldValue(req, "messages");
            
            if (messages == null || messages.isEmpty()) {
                log.warn("MesBoxRequirement has no messages list - blocking progress for safety");
                return StateCondition.never();
            }
            
            // Check if message box widget is visible with expected text
            return ctx -> {
                // MESBOX is typically widget group 229
                net.runelite.api.widgets.Widget mesBoxWidget = ctx.getClient().getWidget(229, 1);
                if (mesBoxWidget == null || mesBoxWidget.isHidden()) {
                    return false;
                }
                String text = mesBoxWidget.getText();
                if (text == null) {
                    return false;
                }
                for (String message : messages) {
                    if (text.contains(message)) {
                        return true;
                    }
                }
                return false;
            };
            
        } catch (Exception e) {
            log.warn("Could not translate MesBoxRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // Chat Message Requirements
    // ========================================================================

    /**
     * Translate chat message based requirements.
     * These require event tracking and are evaluated based on historical chat messages.
     */
    private StateCondition translateChatMessageRequirement(Object req) {
        // Chat message requirements need event-based tracking
        // Cannot evaluate statically - block progress for safety
        log.warn("ChatMessageRequirement requires event tracking - blocking progress for safety");
        return StateCondition.never();
    }

    // ========================================================================
    // Follower Requirements
    // ========================================================================

    /**
     * Translate follower requirements.
     *
     * <p>FollowerRequirement checks if player has a follower.
     * NoFollowerRequirement checks if player does NOT have a follower.
     * Varp 447 tracks the current follower (lower 16 bits = NPC index).
     */
    private StateCondition translateFollowerRequirement(Object req) {
        String className = req.getClass().getSimpleName();
        boolean requiresFollower = !className.contains("No");
        
        return ctx -> {
            int followerVarp = ctx.getClient().getVarpValue(447);
            int followerId = followerVarp & 0x0000FFFF;
            boolean hasFollower = followerId > 0;
            return requiresFollower ? hasFollower : !hasFollower;
        };
    }

    // ========================================================================
    // Inventory Requirements
    // ========================================================================

    /**
     * Translate FreeInventorySlotRequirement.
     */
    private StateCondition translateFreeInventoryRequirement(Object req) {
        int slotsRequired = getIntFieldSafe(req, "numSlots", 1);
        
        return ctx -> {
            int freeSlots = ctx.getInventoryState().getFreeSlots();
            return freeSlots >= slotsRequired;
        };
    }

    /**
     * Translate ItemOnTileRequirement - checks if an item is on the ground.
     *
     * <p>Ground item checking requires scene scanning which is expensive.
     * For valid item IDs, we return always-true and let the step execution handle
     * ground item detection at runtime via GroundItemQuestStep.
     */
    private StateCondition translateItemOnTileRequirement(Object req) {
        try {
            int itemId = getIntFieldSafe(req, "itemID", -1);
            if (itemId < 0) {
                log.warn("ItemOnTileRequirement has invalid itemId - blocking progress for safety");
                return StateCondition.never();
            }
            
            // Ground item checking requires TileItem scanning at runtime
            // The step execution will handle this via GroundItemQuestStep
            // This is intentionally always-true because the runtime check is more reliable
            log.debug("ItemOnTileRequirement for itemId={} - deferred to runtime scanning", itemId);
            return StateCondition.always();
        } catch (Exception e) {
            log.warn("Could not translate ItemOnTileRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    /**
     * Translate NoItemRequirement - checks player does NOT have an item.
     */
    private StateCondition translateNoItemRequirement(Object req) {
        try {
            int itemId = getIntFieldSafe(req, "id", -1);
            if (itemId < 0) {
                log.warn("NoItemRequirement has invalid itemId - blocking progress for safety");
                return StateCondition.never();
            }
            
            return ctx -> !ctx.getInventoryState().hasItem(itemId, 1);
        } catch (Exception e) {
            log.warn("Could not translate NoItemRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // Instance/Location Requirements
    // ========================================================================

    /**
     * Translate InInstanceRequirement - checks if player is in an instanced area.
     */
    private StateCondition translateInInstanceRequirement(Object req) {
        return ctx -> ctx.getClient().isInInstancedRegion();
    }

    /**
     * Translate TileIsLoadedRequirement - checks if a tile is loaded in the scene.
     */
    private StateCondition translateTileLoadedRequirement(Object req) {
        try {
            Object worldPointObj = getFieldValue(req, "worldPoint");
            if (worldPointObj instanceof WorldPoint) {
                WorldPoint wp = (WorldPoint) worldPointObj;
                return ctx -> {
                    WorldPoint localWp = WorldPoint.fromLocalInstance(ctx.getClient(), 
                            ctx.getClient().getLocalPlayer().getLocalLocation());
                    // Check if on same plane and within loaded region
                    return localWp.getPlane() == wp.getPlane() &&
                           Math.abs(localWp.getX() - wp.getX()) < 104 &&
                           Math.abs(localWp.getY() - wp.getY()) < 104;
                };
            }
            log.warn("TileIsLoadedRequirement has invalid worldPoint - blocking progress for safety");
            return StateCondition.never();
        } catch (Exception e) {
            log.warn("Could not translate TileIsLoadedRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // Combat/Stats Requirements
    // ========================================================================

    /**
     * Translate CombatLevelRequirement.
     */
    private StateCondition translateCombatLevelRequirement(Object req) {
        int requiredLevel = getIntFieldSafe(req, "combatLevel", 3);
        
        return ctx -> ctx.getClient().getLocalPlayer().getCombatLevel() >= requiredLevel;
    }

    /**
     * Translate PrayerRequirement or PrayerPointRequirement.
     */
    private StateCondition translatePrayerRequirement(Object req) {
        String className = req.getClass().getSimpleName();
        
        if (className.contains("Point")) {
            // PrayerPointRequirement - checks current prayer points
            int requiredPoints = getIntFieldSafe(req, "prayerPoints", 1);
            return ctx -> ctx.getClient().getBoostedSkillLevel(net.runelite.api.Skill.PRAYER) >= requiredPoints;
        } else {
            // PrayerRequirement - checks if a specific prayer is active
            Object prayerObj = getFieldValue(req, "prayer");
            if (prayerObj != null) {
                try {
                    int prayerVarbit = (int) prayerObj.getClass().getMethod("getVarbit").invoke(prayerObj);
                    return ctx -> ctx.getClient().getVarbitValue(prayerVarbit) == 1;
                } catch (Exception e) {
                    log.warn("Could not get prayer varbit - blocking progress for safety: {}", e.getMessage());
                    return StateCondition.never();
                }
            }
            log.warn("PrayerRequirement has null prayer object - blocking progress for safety");
            return StateCondition.never();
        }
    }

    /**
     * Translate SpellbookRequirement.
     */
    private StateCondition translateSpellbookRequirement(Object req) {
        try {
            Object spellbookObj = getFieldValue(req, "spellbook");
            if (spellbookObj != null) {
                String spellbookName = spellbookObj.toString();
                // Spellbook is tracked via varbit 4070
                // 0 = Normal, 1 = Ancient, 2 = Lunar, 3 = Arceuus
                int expectedValue = 0;
                switch (spellbookName.toUpperCase()) {
                    case "ANCIENT": expectedValue = 1; break;
                    case "LUNAR": expectedValue = 2; break;
                    case "ARCEUUS": expectedValue = 3; break;
                }
                int finalExpected = expectedValue;
                return ctx -> ctx.getClient().getVarbitValue(4070) == finalExpected;
            }
            log.warn("SpellbookRequirement has null spellbook - blocking progress for safety");
            return StateCondition.never();
        } catch (Exception e) {
            log.warn("Could not translate SpellbookRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    /**
     * Translate WeightRequirement.
     */
    private StateCondition translateWeightRequirement(Object req) {
        int maxWeight = getIntFieldSafe(req, "weight", 0);
        Object operationObj = getFieldValue(req, "operation");
        String operation = operationObj != null ? operationObj.toString() : "LESS_EQUAL";
        
        return ctx -> {
            int currentWeight = ctx.getClient().getWeight();
            switch (operation) {
                case "LESS": return currentWeight < maxWeight;
                case "LESS_EQUAL": return currentWeight <= maxWeight;
                case "GREATER": return currentWeight > maxWeight;
                case "GREATER_EQUAL": return currentWeight >= maxWeight;
                case "EQUAL": return currentWeight == maxWeight;
                case "NOT_EQUAL": return currentWeight != maxWeight;
                default: return currentWeight <= maxWeight;
            }
        };
    }

    /**
     * Translate VarComparisonRequirement - compares two varbits/varps.
     */
    private StateCondition translateVarComparisonRequirement(Object req) {
        try {
            int varbitIdA = getIntFieldSafe(req, "varbitIdA", -1);
            int varbitIdB = getIntFieldSafe(req, "varbitIdB", -1);
            Object operationObj = getFieldValue(req, "operation");
            String operation = operationObj != null ? operationObj.toString() : "EQUAL";
            
            if (varbitIdA < 0 || varbitIdB < 0) {
                log.warn("VarComparisonRequirement has invalid varbit IDs ({}, {}) - blocking progress for safety", 
                        varbitIdA, varbitIdB);
                return StateCondition.never();
            }
            
            return ctx -> {
                int valueA = ctx.getClient().getVarbitValue(varbitIdA);
                int valueB = ctx.getClient().getVarbitValue(varbitIdB);
                switch (operation) {
                    case "EQUAL": return valueA == valueB;
                    case "NOT_EQUAL": return valueA != valueB;
                    case "GREATER": return valueA > valueB;
                    case "GREATER_EQUAL": return valueA >= valueB;
                    case "LESS": return valueA < valueB;
                    case "LESS_EQUAL": return valueA <= valueB;
                    default: return valueA == valueB;
                }
            };
        } catch (Exception e) {
            log.warn("Could not translate VarComparisonRequirement - blocking progress for safety: {}", e.getMessage());
            return StateCondition.never();
        }
    }

    // ========================================================================
    // Reflection Utilities
    // ========================================================================

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

    private boolean getBoolField(Object obj, String fieldName) throws Exception {
        Field field = findField(obj.getClass(), fieldName);
        if (field != null) {
            field.setAccessible(true);
            return field.getBoolean(obj);
        }
        throw new NoSuchFieldException(fieldName);
    }

    private boolean getBoolFieldSafe(Object obj, String fieldName, boolean defaultValue) {
        try {
            return getBoolField(obj, fieldName);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String getStringFieldSafe(Object obj, String fieldName, String defaultValue) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(obj);
                return value != null ? value.toString() : defaultValue;
            }
        } catch (Exception e) {
            log.debug("Failed to get string field '{}', using default '{}': {}", 
                    fieldName, defaultValue, e.getMessage());
        }
        return defaultValue;
    }

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(obj);
            }
        } catch (Exception e) {
            log.warn("Failed to get field '{}' from {} - Quest Helper API may have changed",
                    fieldName, obj.getClass().getSimpleName(), e);
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
}

