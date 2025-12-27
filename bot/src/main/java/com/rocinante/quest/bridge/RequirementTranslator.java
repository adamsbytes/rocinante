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
     * @return a StateCondition equivalent, or always-true if unsupported
     */
    public StateCondition translate(Object qhRequirement) {
        if (qhRequirement == null) {
            return StateCondition.always();
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
                    return translateNpcCondition(qhRequirement);
                case "ChatMessageCondition":
                    // Chat conditions need special runtime handling
                    return StateCondition.always();
                case "WidgetTextRequirement":
                case "WidgetPresenceRequirement":
                    return translateWidgetRequirement(qhRequirement);
                default:
                    log.debug("Unsupported requirement type: {}", className);
                    return StateCondition.always();
            }
        } catch (Exception e) {
            log.warn("Failed to translate requirement {}: {}", className, e.getMessage());
            return StateCondition.always();
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
        boolean checkInZone = getBoolField(req, "checkInZone");

        // Get zones list
        Object zonesObj = getFieldValue(req, "zones");
        if (!(zonesObj instanceof List)) {
            log.debug("ZoneRequirement has no zones list");
            return StateCondition.always();
        }

        @SuppressWarnings("unchecked")
        List<Object> zones = (List<Object>) zonesObj;
        if (zones.isEmpty()) {
            return StateCondition.always();
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
        // Zone has minX, maxX, minY, maxY, minPlane, maxPlane
        int minX = getIntField(zone, "minX");
        int maxX = getIntField(zone, "maxX");
        int minY = getIntField(zone, "minY");
        int maxY = getIntField(zone, "maxY");
        int minPlane = getIntFieldSafe(zone, "minPlane", 0);
        int maxPlane = getIntFieldSafe(zone, "maxPlane", 3);

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
        boolean mustBeEquipped = getBoolFieldSafe(req, "mustBeEquipped", false);
        boolean equip = getBoolFieldSafe(req, "equip", false);

        // Handle alternate items
        List<Integer> allIds = new ArrayList<>();
        allIds.add(itemId);

        Object alternatesObj = getFieldValue(req, "alternateItems");
        if (alternatesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Integer> alternates = (List<Integer>) alternatesObj;
            allIds.addAll(alternates);
        }

        if (mustBeEquipped || equip) {
            // Check equipment
            int[] idArray = allIds.stream().mapToInt(i -> i).toArray();
            return Conditions.hasAnyEquipped(idArray);
        } else {
            // Check inventory
            return ctx -> {
                for (int id : allIds) {
                    if (ctx.getInventoryState().hasItem(id, quantity)) {
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
            return StateCondition.always();
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
            return StateCondition.always();
        }

        String skillName = skillObj.toString().toLowerCase();

        return ctx -> {
            // Need to check skill level via client
            // This is simplified - would need proper skill ID mapping
            int skillId = getSkillId(skillName);
            if (skillId < 0) return true; // Unknown skill, assume met

            return ctx.getClient().getRealSkillLevel(net.runelite.api.Skill.values()[skillId]) >= requiredLevel;
        };
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
            return StateCondition.always();
        }

        // Quest Helper's Quest enum has a getState() method or similar
        // For now, return always-true since we'd need the quest's varbit ID
        log.debug("Quest requirement translation not fully implemented: {}", questObj);
        return StateCondition.always();
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
            return StateCondition.always();
        }
        return Conditions.widgetVisible(groupId);
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
}

