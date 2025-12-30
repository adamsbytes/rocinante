package com.rocinante.quest;

import com.rocinante.quest.Quest;
import com.rocinante.quest.steps.QuestStep;
import com.rocinante.quest.bridge.QuestHelperBridge;
import com.rocinante.quest.bridge.ItemRequirementInfo;
import com.rocinante.quest.steps.ConditionalQuestStep;
import com.rocinante.quest.steps.DigQuestStep;
import com.rocinante.quest.steps.GroundItemQuestStep;
import com.rocinante.quest.steps.NpcQuestStep;
import com.rocinante.quest.steps.ObjectQuestStep;
import com.rocinante.quest.steps.WaitQuestStep;
import com.rocinante.quest.steps.WalkQuestStep;
import com.rocinante.quest.steps.ShipQuestStep;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * Slim fixtures that emulate Quest Helper quest data without pulling quest-helper
 * onto the test classpath. These stubs mirror the reflection surface expected by
 * QuestHelperBridge (fields/methods used in translation).
 *
 * The goal is to validate that translation succeeds and produces non-empty steps
 * for complex-style quests (Monkey Madness I, Desert Treasure I) with representative
 * step types: NPC, object, item, conditional, dig, board/sail, and instruction-only.
 */
public class QuestHelperBridgeFixtureTest {

    @Test
    public void translateMonkeyMadnessFixture() {
        QuestHelperBridge bridge = new QuestHelperBridge(new FakeQuestHelper(
                "Monkey Madness I",
                /*varbit*/ -1,
                /*varPlayer*/ 365, // VarPlayerID.MM_MAIN
                /*completion*/ 200,
                monkeyMadnessSteps(),
                List.of(sampleItemReq())
        ));

        Map<Integer, QuestStep> steps = bridge.loadAllSteps();
        assertFalse("Steps should not be empty", steps.isEmpty());
        steps.values().forEach(step -> assertNotNull("Step should be translated", step));
        System.out.println("Monkey step classes: " + steps.values().stream().map(s -> s.getClass().getSimpleName()).toList());

        assertTrue("Expected at least one NPC step", hasInstance(steps, NpcQuestStep.class));
        assertTrue("Expected at least one object step", hasInstance(steps, ObjectQuestStep.class));
        assertTrue("Expected at least one ground item step", hasInstance(steps, GroundItemQuestStep.class));
        assertTrue("Expected at least one dig step", hasInstance(steps, DigQuestStep.class));
        assertTrue("Expected at least one conditional step", hasInstance(steps, ConditionalQuestStep.class));
        assertTrue("Expected at least one wait step", hasInstance(steps, WaitQuestStep.class));
        assertTrue("Expected at least one ship step", hasInstanceDeep(steps, ShipQuestStep.class));

        // Item requirements propagate flags
        List<ItemRequirementInfo> items = bridge.extractItemRequirements();
        assertFalse(items.isEmpty());
        ItemRequirementInfo req = items.get(0);
        assertTrue(req.isMustBeEquipped());
        assertFalse(req.isConsumed());
        assertTrue(req.isObtainableDuringQuest());
        assertEquals(2, req.getAlternateIds().size());

        Quest quest = bridge.toQuest();
        assertNotNull("Quest wrapper should be created", quest);
        assertNotNull("Quest steps should load", quest.loadSteps());
    }

    /**
     * Sample item requirement stub matching QuestHelper ItemRequirement fields.
     */
    private static Object sampleItemReq() {
        return new Object() {
            @SuppressWarnings("unused")
            public int id = 1115;
            @SuppressWarnings("unused")
            public String name = "Iron platebody";
            @SuppressWarnings("unused")
            public int quantity = 1;
            @SuppressWarnings("unused")
            public boolean mustBeEquipped = true;
            @SuppressWarnings("unused")
            public boolean isConsumedItem = false;
            @SuppressWarnings("unused")
            public List<Integer> alternateItems = List.of(1127, 3140);
            @SuppressWarnings("unused")
            public String tooltip = "Can be obtained during the quest.";
        };
    }

    @Test
    public void translateDesertTreasureFixture() {
        QuestHelperBridge bridge = new QuestHelperBridge(new FakeQuestHelper(
                "Desert Treasure I",
                /*varbit*/ 358, // representative quest varbit
                /*varPlayer*/ -1,
                /*completion*/ 300,
                desertTreasureSteps(),
                List.of()
        ));

        Map<Integer, QuestStep> steps = bridge.loadAllSteps();
        assertFalse("Steps should not be empty", steps.isEmpty());
        steps.values().forEach(step -> assertNotNull("Step should be translated", step));
        System.out.println("Desert step classes: " + steps.values().stream().map(s -> s.getClass().getSimpleName()).toList());

        assertTrue("Expected at least one NPC step", hasInstance(steps, NpcQuestStep.class));
        assertTrue("Expected at least one object step", hasInstance(steps, ObjectQuestStep.class));
        assertTrue("Expected at least one ground item step", hasInstance(steps, GroundItemQuestStep.class));
        assertTrue("Expected at least one conditional step", hasInstance(steps, ConditionalQuestStep.class));
        assertTrue("Expected at least one dig step", hasInstance(steps, DigQuestStep.class));
        assertTrue("Expected at least one wait step", hasInstance(steps, WaitQuestStep.class));

        Quest quest = bridge.toQuest();
        assertNotNull("Quest wrapper should be created", quest);
        assertNotNull("Quest steps should load", quest.loadSteps());
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static Map<Integer, Object> monkeyMadnessSteps() {
        Map<Integer, Object> steps = new HashMap<>();
        steps.put(0, new NpcStep(546, new WorldPoint(2465, 3496, 0), "Talk to King Narnode Shareen"));
        steps.put(5, new ConditionalStep(
                new RequirementStub(false),
                new NpcStep(548, new WorldPoint(2945, 3156, 0), "Talk to Caranock at the shipyard"),
                new ObjectStep(16683, new WorldPoint(2461, 3493, 0), "Climb the ladder in the Grand Tree")
        ));
        steps.put(10, new ObjectStep(29624, new WorldPoint(2763, 3216, 0), "Click the Puzzle panel"));
        steps.put(20, new NpcStep(1441, new WorldPoint(2800, 2700, 0), "Talk to Daero in the hangar"));
        steps.put(30, new ItemStep("Pick up monkey bones", 3185));
        steps.put(40, new DigStep(new WorldPoint(2440, 2831, 0), "Dig at the crash site for the sigil"));
        steps.put(50, new ConditionalStep(
                new RequirementStub(true),
                new BoardShipStep(new WorldPoint(3029, 3217, 0), "Board your ship in Port Sarim"),
                new SailStep(new WorldPoint(2955, 3144, 0), "Sail to Ape Atoll")
        ));
        steps.put(60, new WaitStep("Wait for cutscene to finish"));
        return steps;
    }

    private static Map<Integer, Object> desertTreasureSteps() {
        Map<Integer, Object> steps = new HashMap<>();
        steps.put(0, new NpcStep(197, new WorldPoint(3305, 2780, 0), "Speak to the Archaeologist by the Bedabin camp"));
        steps.put(5, new ObjectStep(6434, new WorldPoint(3308, 2756, 0), "Enter the ancient tomb"));
        steps.put(15, new ItemStep("Retrieve the ancient key", 1590));
        steps.put(25, new ConditionalStep(
                new RequirementStub(false),
                new ObjectStep(6435, new WorldPoint(3305, 2750, 0), "Open the Scarab door"),
                new NpcStep(1977, new WorldPoint(3323, 2740, 0), "Fight the guardian mummy")
        ));
        steps.put(35, new DigStep(new WorldPoint(3350, 2830, 0), "Dig in the desert for the diamond"));
        steps.put(45, new WaitStep("Inspect the altar and wait"));
        steps.put(55, new SailStep(new WorldPoint(3046, 3201, 0), "Sail back to Port Sarim"));
        return steps;
    }

    // -------------------------------------------------------------------------
    // Stubs matching QuestHelperBridge reflection expectations
    // -------------------------------------------------------------------------

    /**
     * Minimal quest helper stub exposing quest metadata and step map.
     */
    private static class FakeQuestHelper {
        // QuestHelperBridge looks for a "quest" field or getQuest()
        public final FakeQuestEnum quest;
        private final Map<Integer, Object> steps;
        private final List<Object> itemRequirements;

        FakeQuestHelper(String name, int varbitId, int varPlayerId, int completionValue, Map<Integer, Object> steps, List<Object> itemRequirements) {
            this.quest = new FakeQuestEnum(name, varbitId, varPlayerId, completionValue);
            this.steps = steps;
            this.itemRequirements = itemRequirements;
        }

        public FakeQuestEnum getQuest() {
            return quest;
        }

        @SuppressWarnings("unused")
        public Map<Integer, Object> loadSteps() {
            return steps;
        }

        // Item requirements stubs
        @SuppressWarnings("unused")
        public List<Object> getItemRequirements() {
            return itemRequirements;
        }

        @SuppressWarnings("unused")
        public List<Object> getItemRecommended() {
            return List.of();
        }

        @SuppressWarnings("unused")
        public List<Object> getGeneralRequirements() {
            return List.of();
        }
    }

    /**
     * Minimal QuestHelperQuest-like enum entry.
     */
    private static class FakeQuestEnum {
        public final String name;
        public final FakeVarbit varbit;
        public final FakeVarPlayer varPlayer;
        public final int completeValue;

        FakeQuestEnum(String name, int varbitId, int varPlayerId, int completeValue) {
            this.name = name;
            this.varbit = varbitId >= 0 ? new FakeVarbit(varbitId) : null;
            this.varPlayer = varPlayerId >= 0 ? new FakeVarPlayer(varPlayerId) : null;
            this.completeValue = completeValue;
        }

        public String getName() {
            return name;
        }

        public FakeVarbit getVarbit() {
            return varbit;
        }

        public int getCompleteValue() {
            return completeValue;
        }

        @SuppressWarnings("unused")
        public net.runelite.api.QuestState getState(net.runelite.api.Client client) {
            return net.runelite.api.QuestState.IN_PROGRESS;
        }
    }

    private static class FakeVarbit {
        private final int id;

        FakeVarbit(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    private static class FakeVarPlayer {
        private final int id;

        FakeVarPlayer(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    private static class RequirementStub {
        private final boolean value;

        RequirementStub(boolean value) {
            this.value = value;
        }

        @SuppressWarnings("unused")
        public boolean check(net.runelite.api.Client client) {
            return value;
        }
    }

    private static class NpcStep {
        public final int npcID;
        public final WorldPoint worldPoint;
        private final String text;

        NpcStep(int npcID, WorldPoint worldPoint, String text) {
            this.npcID = npcID;
            this.worldPoint = worldPoint;
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    private static class ObjectStep {
        public final int objectID;
        public final WorldPoint worldPoint;
        private final String text;

        ObjectStep(int objectID, WorldPoint worldPoint, String text) {
            this.objectID = objectID;
            this.worldPoint = worldPoint;
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    private static class ItemStep {
        private final String text;
        public final int iconItemID;

        ItemStep(String text, int itemId) {
            this.text = text;
            this.iconItemID = itemId;
        }

        public String getText() {
            return text;
        }
    }

    private static class DigStep {
        public final WorldPoint worldPoint;
        private final String text;

        DigStep(WorldPoint worldPoint, String text) {
            this.worldPoint = worldPoint;
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    private static class SailStep {
        public final WorldPoint worldPoint;
        private final String text;

        SailStep(WorldPoint worldPoint, String text) {
            this.worldPoint = worldPoint;
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    private static class BoardShipStep {
        public final WorldPoint worldPoint;
        private final String text;

        BoardShipStep(WorldPoint worldPoint, String text) {
            this.worldPoint = worldPoint;
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    private static class WaitStep {
        private final String text;

        WaitStep(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    private static class ConditionalStep {
        // LinkedHashMap<Requirement, QuestStep>
        public final LinkedHashMap<Object, Object> steps = new LinkedHashMap<>();
        private final String text = "Conditional step";

        ConditionalStep(Object requirement, Object trueStep, Object defaultStep) {
            steps.put(requirement, trueStep);
            steps.put(null, defaultStep);
        }

        public String getText() {
            return text;
        }
    }

    private static boolean hasInstance(Map<Integer, QuestStep> steps, Class<?> clazz) {
        return steps.values().stream().anyMatch(step -> clazz.isAssignableFrom(step.getClass()));
    }

    private static boolean hasInstanceDeep(Map<Integer, QuestStep> steps, Class<?> clazz) {
        for (QuestStep step : steps.values()) {
            if (clazz.isAssignableFrom(step.getClass())) {
                return true;
            }
            if (step instanceof ConditionalQuestStep) {
                if (conditionalContains((ConditionalQuestStep) step, clazz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean conditionalContains(ConditionalQuestStep conditional, Class<?> clazz) {
        // Branches are private; use getters available on inner Branch
        for (ConditionalQuestStep.Branch branch : conditional.getBranches()) {
            QuestStep child = branch.getStep();
            if (child != null) {
                if (clazz.isAssignableFrom(child.getClass())) {
                    return true;
                }
                if (child instanceof ConditionalQuestStep && conditionalContains((ConditionalQuestStep) child, clazz)) {
                    return true;
                }
            }
        }
        QuestStep defaultStep = conditional.getDefaultStep();
        if (defaultStep != null) {
            if (clazz.isAssignableFrom(defaultStep.getClass())) {
                return true;
            }
            if (defaultStep instanceof ConditionalQuestStep && conditionalContains((ConditionalQuestStep) defaultStep, clazz)) {
                return true;
            }
        }
        return false;
    }
}

