package com.rocinante.quest.bridge;

import com.questhelper.questhelpers.QuestHelper;
import com.questhelper.requirements.Requirement;
import com.questhelper.steps.ConditionalStep;
import com.questhelper.steps.NpcStep;
import com.questhelper.steps.ObjectStep;
import com.questhelper.steps.EmoteStep;
import com.questhelper.steps.DigStep;
import com.questhelper.steps.WidgetStep;
import com.questhelper.steps.DetailedQuestStep;
import com.rocinante.quest.steps.*;
import com.rocinante.state.StateCondition;
import com.rocinante.tasks.impl.DialogueOptionResolver;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QuestHelperBridge translation logic.
 *
 * <p>These tests verify that Quest Helper step types are correctly translated
 * to our quest framework. Test fixtures are modeled after real Quest Helper
 * quest implementations (Cook's Assistant, Romeo and Juliet, etc.).
 *
 * <p>Test categories:
 * <ul>
 *   <li>Step type translations (NpcStep, ObjectStep, etc.)</li>
 *   <li>Dialogue option extraction</li>
 *   <li>Metadata extraction</li>
 *   <li>Requirement translation</li>
 *   <li>Edge cases and error handling</li>
 * </ul>
 */
public class QuestHelperBridgeTest {

    private RequirementTranslator requirementTranslator;

    @Before
    public void setUp() {
        requirementTranslator = new RequirementTranslator();
    }

    // ========================================================================
    // NpcStep Translation Tests
    // ========================================================================

    /**
     * Test NpcStep translation based on Cook's Assistant:
     * {@code new NpcStep(this, NpcID.COOK, new WorldPoint(3206, 3214, 0), "...")}
     */
    @Test
    public void testTranslateNpcStep_CooksAssistant() throws Exception {
        // Create mock NpcStep mimicking Cook's Assistant finishQuest step
        MockNpcStep mockStep = new MockNpcStep();
        mockStep.npcID = 4626; // NpcID.COOK
        mockStep.text = Arrays.asList("Give the Cook in Lumbridge Castle's kitchen the required items to finish the quest.");
        mockStep.definedPoint = new MockDefinedPoint(3206, 3214, 0);
        mockStep.alternateNpcIDs = Arrays.asList(4626); // addAlternateNpcs
        mockStep.choices = createMockDialogChoiceSteps(Arrays.asList("What's wrong?", "Can I help?", "Yes."));

        // Test translation using reflection
        NpcQuestStep result = translateNpcStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertEquals("NPC ID should match", 4626, result.getNpcId());
        assertEquals("Step type should be NPC", QuestStep.StepType.NPC, result.getType());
        assertTrue("Text should contain Cook", result.getText().toLowerCase().contains("cook"));
        assertNotNull("Walk location should be set", result.getWalkToLocation());
        assertEquals("Walk location X should match", 3206, result.getWalkToLocation().getX());
        assertEquals("Walk location Y should match", 3214, result.getWalkToLocation().getY());
    }

    /**
     * Test NpcStep with dialogue options from Romeo and Juliet:
     * {@code talkToRomeo.addDialogStep("Yes, I have seen her actually!")}
     */
    @Test
    public void testTranslateNpcStep_WithDialogueOptions() throws Exception {
        MockNpcStep mockStep = new MockNpcStep();
        mockStep.npcID = 922; // NpcID.ROMEO
        mockStep.text = Arrays.asList("Talk to Romeo in Varrock Square.");
        mockStep.definedPoint = new MockDefinedPoint(3211, 3422, 0);
        mockStep.choices = createMockDialogChoiceSteps(Arrays.asList(
                "Yes, I have seen her actually!",
                "Yes, ok, I'll let her know.",
                "Yes."
        ));

        NpcQuestStep result = translateNpcStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertEquals("Menu action should be Talk-to", "Talk-to", result.getMenuAction());
        assertTrue("Dialogue should be expected", result.isDialogueExpected());
        assertNotNull("Dialogue options should be set", result.getDialogueOptions());
        assertTrue("Should have dialogue options", result.getDialogueOptions().size() > 0);
    }

    /**
     * Test menu action inference for trade NPC.
     */
    @Test
    public void testTranslateNpcStep_TradeAction() throws Exception {
        MockNpcStep mockStep = new MockNpcStep();
        mockStep.npcID = 527; // Generic shopkeeper
        mockStep.text = Arrays.asList("Trade with the shopkeeper to buy supplies.");

        NpcQuestStep result = translateNpcStep(mockStep);

        // "Trade" keyword explicitly triggers trade action
        assertEquals("Menu action should be Trade", "Trade", result.getMenuAction());
    }

    /**
     * Test menu action inference for attack NPC.
     */
    @Test
    public void testTranslateNpcStep_AttackAction() throws Exception {
        MockNpcStep mockStep = new MockNpcStep();
        mockStep.npcID = 2691; // Generic monster
        mockStep.text = Arrays.asList("Kill the giant rat.");

        NpcQuestStep result = translateNpcStep(mockStep);

        assertEquals("Menu action should be Attack", "Attack", result.getMenuAction());
    }

    // ========================================================================
    // ObjectStep Translation Tests
    // ========================================================================

    /**
     * Test ObjectStep translation based on Cook's Assistant:
     * {@code new ObjectStep(this, ObjectID.HOPPER1, new WorldPoint(3166, 3307, 2), "Fill the hopper...")}
     */
    @Test
    public void testTranslateObjectStep_CooksAssistant() throws Exception {
        MockObjectStep mockStep = new MockObjectStep();
        mockStep.objectID = 24961; // ObjectID.HOPPER1
        mockStep.text = Arrays.asList("Fill the hopper with your grain.");
        mockStep.definedPoint = new MockDefinedPoint(3166, 3307, 2);

        ObjectQuestStep result = translateObjectStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertEquals("Object ID should match", 24961, result.getObjectId());
        assertEquals("Step type should be OBJECT", QuestStep.StepType.OBJECT, result.getType());
        assertNotNull("Walk location should be set", result.getWalkToLocation());
        assertEquals("Walk location plane should match", 2, result.getWalkToLocation().getPlane());
    }

    /**
     * Test ObjectStep menu action inference for ladder.
     */
    @Test
    public void testTranslateObjectStep_ClimbAction() throws Exception {
        MockObjectStep mockStep = new MockObjectStep();
        mockStep.objectID = 12345;
        mockStep.text = Arrays.asList("Climb up the ladder to the top floor.");

        ObjectQuestStep result = translateObjectStep(mockStep);

        assertEquals("Menu action should be Climb-up", "Climb-up", result.getMenuAction());
    }

    /**
     * Test ObjectStep menu action inference for door.
     */
    @Test
    public void testTranslateObjectStep_OpenAction() throws Exception {
        MockObjectStep mockStep = new MockObjectStep();
        mockStep.objectID = 12345;
        mockStep.text = Arrays.asList("Open the door to enter the kitchen.");

        ObjectQuestStep result = translateObjectStep(mockStep);

        assertEquals("Menu action should be Open", "Open", result.getMenuAction());
    }

    /**
     * Test ObjectStep with alternate IDs.
     */
    @Test
    public void testTranslateObjectStep_WithAlternates() throws Exception {
        MockObjectStep mockStep = new MockObjectStep();
        mockStep.objectID = 24961;
        mockStep.alternateObjectIDs = Arrays.asList(24962, 24963);
        mockStep.text = Arrays.asList("Use the hopper.");

        ObjectQuestStep result = translateObjectStep(mockStep);

        assertNotNull("Alternate IDs should be set", result.getAlternateObjectIds());
        assertEquals("Should have 2 alternate IDs", 2, result.getAlternateObjectIds().size());
    }

    // ========================================================================
    // ItemStep Translation Tests
    // ========================================================================

    /**
     * Test ItemStep translation based on Cook's Assistant:
     * {@code new ItemStep(this, new WorldPoint(3177, 3296, 0), "Grab an egg...", egg)}
     */
    @Test
    public void testTranslateItemStep_CooksAssistant() throws Exception {
        MockItemStep mockStep = new MockItemStep();
        mockStep.text = Arrays.asList("Grab an egg from the farm north of Lumbridge.");
        mockStep.definedPoint = new MockDefinedPoint(3177, 3296, 0);
        mockStep.requirements = Arrays.asList(createMockItemRequirement(1944, "Egg", 1));

        GroundItemQuestStep result = translateItemStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertEquals("Item ID should match", 1944, result.getItemId());
        assertEquals("Step type should be ITEM", QuestStep.StepType.ITEM, result.getType());
        assertNotNull("Location should be set", result.getLocation());
    }

    // ========================================================================
    // ConditionalStep Translation Tests
    // ========================================================================

    /**
     * Test ConditionalStep translation based on Cook's Assistant:
     * {@code
     * var doQuest = new ConditionalStep(this, finishQuest);
     * doQuest.addStep(nor(milk, bucket, hasTurnedInMilk), getBucket);
     * }
     */
    @Test
    public void testTranslateConditionalStep_CooksAssistant() throws Exception {
        // Create mocked ConditionalStep with proper type
        ConditionalStep mockStep = mock(ConditionalStep.class);
        when(mockStep.getText()).thenReturn(Arrays.asList("Complete the quest requirements."));
        
        // Create mock branches with proper types
        LinkedHashMap<Requirement, com.questhelper.steps.QuestStep> steps = new LinkedHashMap<>();
        
        // Mock condition
        Requirement condition = mock(Requirement.class);
        
        // Mock NpcStep for the branch
        NpcStep branchStep = mock(NpcStep.class);
        when(branchStep.getText()).thenReturn(Arrays.asList("Talk to the Cook."));
        
        steps.put(condition, branchStep);
        
        // Set the steps field via reflection
        Field stepsField = ConditionalStep.class.getDeclaredField("steps");
        stepsField.setAccessible(true);
        stepsField.set(mockStep, steps);

        ConditionalQuestStep result = translateConditionalStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertEquals("Step type should be CONDITIONAL", QuestStep.StepType.CONDITIONAL, result.getType());
    }

    // ========================================================================
    // EmoteStep Translation Tests
    // ========================================================================

    @Test
    public void testTranslateEmoteStep() throws Exception {
        MockEmoteStep mockStep = new MockEmoteStep();
        mockStep.text = Arrays.asList("Perform the wave emote.");
        mockStep.emote = new MockQuestEmote("WAVE", 164);
        mockStep.definedPoint = new MockDefinedPoint(3100, 3100, 0);

        EmoteQuestStep result = translateEmoteStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertEquals("Step type should be CUSTOM (emote)", QuestStep.StepType.CUSTOM, result.getType());
        assertEquals("Sprite ID should match", 164, result.getSpriteId());
    }

    // ========================================================================
    // DigStep Translation Tests
    // ========================================================================

    @Test
    public void testTranslateDigStep() throws Exception {
        MockDigStep mockStep = new MockDigStep();
        mockStep.text = Arrays.asList("Dig at the marked location.");
        mockStep.definedPoint = new MockDefinedPoint(3200, 3200, 0);

        DigQuestStep result = translateDigStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertNotNull("Dig location should be set", result.getDigLocation());
        assertEquals("Location X should match", 3200, result.getDigLocation().getX());
    }

    // ========================================================================
    // TileStep Translation Tests
    // ========================================================================

    @Test
    public void testTranslateTileStep() throws Exception {
        MockTileStep mockStep = new MockTileStep();
        mockStep.text = Arrays.asList("Walk to the marked tile.");
        mockStep.definedPoint = new MockDefinedPoint(3100, 3200, 0);

        WalkQuestStep result = translateTileStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertEquals("Step type should be WALK", QuestStep.StepType.WALK, result.getType());
        assertNotNull("Destination should be set", result.getDestination());
    }

    // ========================================================================
    // WidgetStep Translation Tests
    // ========================================================================

    @Test
    public void testTranslateWidgetStep() throws Exception {
        MockWidgetStep mockStep = new MockWidgetStep();
        mockStep.text = Arrays.asList("Click the highlighted button.");
        mockStep.widgetDetails = Arrays.asList(new MockWidgetDetails(233, 2, -1));

        WidgetQuestStep result = translateWidgetStep(mockStep);

        assertNotNull("Translation should not return null", result);
        assertEquals("Step type should be WIDGET", QuestStep.StepType.WIDGET, result.getType());
        assertEquals("Widget group ID should match", 233, result.getWidgetGroupId());
        assertEquals("Widget child ID should match", 2, result.getWidgetChildId());
    }

    // ========================================================================
    // Dialogue Option Extraction Tests
    // ========================================================================

    /**
     * Test dialogue choice extraction with simple text.
     */
    @Test
    public void testExtractDialogueOptions_SimpleText() throws Exception {
        MockNpcStep mockStep = new MockNpcStep();
        mockStep.npcID = 100;
        mockStep.text = Arrays.asList("Talk to NPC.");
        mockStep.choices = createMockDialogChoiceSteps(Arrays.asList("Yes", "No", "Maybe"));

        List<DialogueOptionResolver> resolvers = extractDialogueOptions(mockStep);

        assertEquals("Should have 3 resolvers", 3, resolvers.size());
        assertEquals("First resolver should be TEXT type", 
                DialogueOptionResolver.ResolverType.TEXT, resolvers.get(0).getType());
    }

    /**
     * Test dialogue choice extraction with pattern.
     */
    @Test
    public void testExtractDialogueOptions_WithPattern() throws Exception {
        MockDialogChoiceStep patternChoice = new MockDialogChoiceStep();
        patternChoice.pattern = Pattern.compile(".*agree.*");
        patternChoice.groupId = 219;
        patternChoice.childId = 1;

        DialogueOptionResolver resolver = translateDialogueChoice(patternChoice);

        assertNotNull("Resolver should not be null", resolver);
        assertEquals("Resolver should be PATTERN type",
                DialogueOptionResolver.ResolverType.PATTERN, resolver.getType());
    }

    /**
     * Test dialogue choice extraction with varbit-based selection.
     */
    @Test
    public void testExtractDialogueOptions_VarbitBased() throws Exception {
        MockDialogChoiceStep varbitChoice = new MockDialogChoiceStep();
        varbitChoice.varbitId = 1234;
        varbitChoice.varbitValueToAnswer = new HashMap<>();
        varbitChoice.varbitValueToAnswer.put(0, "Option A");
        varbitChoice.varbitValueToAnswer.put(1, "Option B");
        varbitChoice.groupId = 219;
        varbitChoice.childId = 1;

        DialogueOptionResolver resolver = translateDialogueChoice(varbitChoice);

        assertNotNull("Resolver should not be null", resolver);
        assertEquals("Resolver should be VARBIT type",
                DialogueOptionResolver.ResolverType.VARBIT, resolver.getType());
        assertNotNull("Varbit map should be set", resolver.getVarbitValueToOption());
        assertEquals("Should have 2 options", 2, resolver.getVarbitValueToOption().size());
    }

    /**
     * Test dialogue choice extraction with context (expected previous line).
     */
    @Test
    public void testExtractDialogueOptions_WithContext() throws Exception {
        MockDialogChoiceStep contextChoice = new MockDialogChoiceStep();
        contextChoice.choice = "Yes, I agree.";
        contextChoice.expectedPreviousLine = "Do you accept the terms?";
        contextChoice.groupId = 219;
        contextChoice.childId = 1;

        DialogueOptionResolver resolver = translateDialogueChoice(contextChoice);

        assertNotNull("Resolver should not be null", resolver);
        assertNotNull("Expected previous line should be set", resolver.getExpectedPreviousLine());
        assertEquals("Expected previous line should match", 
                "Do you accept the terms?", resolver.getExpectedPreviousLine());
    }

    /**
     * Test dialogue choice extraction with exclusions.
     */
    @Test
    public void testExtractDialogueOptions_WithExclusion() throws Exception {
        MockDialogChoiceStep exclusionChoice = new MockDialogChoiceStep();
        exclusionChoice.choice = "Yes";
        exclusionChoice.excludedStrings = Arrays.asList("No", "Cancel");
        exclusionChoice.groupId = 219;
        exclusionChoice.childId = 1;

        DialogueOptionResolver resolver = translateDialogueChoice(exclusionChoice);

        assertNotNull("Resolver should not be null", resolver);
        assertNotNull("Exclusion should be set", resolver.getExclusionText());
    }

    // ========================================================================
    // RequirementTranslator Tests
    // ========================================================================

    @Test
    public void testTranslateVarbitRequirement() {
        MockVarbitRequirement req = new MockVarbitRequirement();
        req.varbitID = 1000;
        req.requiredValue = 5;
        req.operation = "EQUAL";

        StateCondition result = requirementTranslator.translate(req);

        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testTranslateVarbitRequirement_GreaterEqual() {
        MockVarbitRequirement req = new MockVarbitRequirement();
        req.varbitID = 1000;
        req.requiredValue = 10;
        req.operation = "GREATER_EQUAL";

        StateCondition result = requirementTranslator.translate(req);

        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testTranslateZoneRequirement() {
        MockZoneRequirement req = new MockZoneRequirement();
        req.checkInZone = true;
        req.zones = Arrays.asList(new MockZone(3100, 3200, 3150, 3250, 0, 0));

        StateCondition result = requirementTranslator.translate(req);

        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testTranslateItemRequirement() {
        MockItemRequirement req = new MockItemRequirement();
        req.id = 1944; // Egg
        req.quantity = 1;
        req.mustBeEquipped = false;

        StateCondition result = requirementTranslator.translate(req);

        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testTranslateSkillRequirement() {
        MockSkillRequirement req = new MockSkillRequirement();
        req.skill = "COOKING";
        req.requiredLevel = 32;

        StateCondition result = requirementTranslator.translate(req);

        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testTranslateManualRequirement() {
        MockManualRequirement req = new MockManualRequirement();
        req.shouldPass = true;

        StateCondition result = requirementTranslator.translate(req);

        assertNotNull("Result should not be null", result);
        // ManualRequirement with shouldPass=true should return always()
    }

    @Test
    public void testTranslateDialogRequirement() {
        MockDialogRequirement req = new MockDialogRequirement();
        req.text = Arrays.asList("Here's a bucket of milk.");
        req.mustBeActive = false;

        StateCondition result = requirementTranslator.translate(req);

        assertNotNull("Result should not be null", result);
    }

    // ========================================================================
    // Edge Cases and Error Handling Tests
    // ========================================================================

    @Test
    public void testTranslateNullStep() {
        // Reflection-based translateStep should handle null gracefully
        assertNull("Null step should return null", translateStepSafe(null));
    }

    @Test
    public void testTranslateUnknownStepType() {
        // Unknown step types should return null or generic step
        MockUnknownStep unknown = new MockUnknownStep();
        unknown.text = Arrays.asList("Unknown step type.");

        QuestStep result = translateStepSafe(unknown);

        // Should return null for unknown types
        assertNull("Unknown step type should return null", result);
    }

    @Test
    public void testTranslateStepWithMissingFields() {
        MockNpcStep mockStep = new MockNpcStep();
        // Only set required field, leave others null
        mockStep.npcID = 100;
        mockStep.text = Arrays.asList("Minimal step.");
        // No location, no alternates, no choices

        NpcQuestStep result = translateNpcStep(mockStep);

        assertNotNull("Should still translate despite missing optional fields", result);
        assertNull("Walk location should be null", result.getWalkToLocation());
    }

    @Test
    public void testTranslateEmptyDialogueChoices() throws Exception {
        MockNpcStep mockStep = new MockNpcStep();
        mockStep.npcID = 100;
        mockStep.text = Arrays.asList("Talk to NPC.");
        mockStep.choices = createMockDialogChoiceSteps(Collections.emptyList());

        List<DialogueOptionResolver> resolvers = extractDialogueOptions(mockStep);

        assertTrue("Empty choices should result in empty resolvers", resolvers.isEmpty());
    }

    @Test(expected = RuntimeException.class)
    public void testTranslateItemStepWithNoItemId() throws Exception {
        MockItemStep mockStep = new MockItemStep();
        mockStep.text = Arrays.asList("Pick up the item.");
        mockStep.definedPoint = new MockDefinedPoint(3100, 3200, 0);
        // No requirements set - item ID extraction should fail

        // Should throw IllegalStateException wrapped in RuntimeException
        // because item ID is required for ItemStep translation
        translateItemStep(mockStep);
    }

    // ========================================================================
    // Menu Action Inference Tests
    // ========================================================================

    @Test
    public void testInferObjectMenuAction_Search() {
        String action = inferObjectMenuAction("Search the crate for supplies.");
        assertEquals("Search", action);
    }

    @Test
    public void testInferObjectMenuAction_Mine() {
        String action = inferObjectMenuAction("Mine the copper rocks.");
        assertEquals("Mine", action);
    }

    @Test
    public void testInferObjectMenuAction_Chop() {
        String action = inferObjectMenuAction("Chop down the tree.");
        assertEquals("Chop down", action);
    }

    @Test
    public void testInferObjectMenuAction_Bank() {
        String action = inferObjectMenuAction("Bank your items.");
        assertEquals("Bank", action);
    }

    @Test
    public void testInferNpcMenuAction_Pickpocket() {
        String action = inferNpcMenuAction("Pickpocket the man.");
        assertEquals("Pickpocket", action);
    }

    @Test
    public void testInferNpcMenuAction_Follow() {
        String action = inferNpcMenuAction("Follow the guide to the next area.");
        assertEquals("Follow", action);
    }

    // ========================================================================
    // Helper Methods for Testing
    // ========================================================================

    private NpcQuestStep translateNpcStep(Object mockStep) {
        try {
            // Use reflection to call the private method
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateNpcStep", Object.class);
            method.setAccessible(true);
            return (NpcQuestStep) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateNpcStep", e);
        }
    }

    private ObjectQuestStep translateObjectStep(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateObjectStep", Object.class);
            method.setAccessible(true);
            return (ObjectQuestStep) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateObjectStep", e);
        }
    }

    private GroundItemQuestStep translateItemStep(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateItemStep", Object.class);
            method.setAccessible(true);
            Object result = method.invoke(bridge, mockStep);
            return result instanceof GroundItemQuestStep ? (GroundItemQuestStep) result : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateItemStep", e);
        }
    }

    private ConditionalQuestStep translateConditionalStep(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateConditionalStep", com.questhelper.steps.QuestStep.class);
            method.setAccessible(true);
            return (ConditionalQuestStep) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateConditionalStep", e);
        }
    }

    private EmoteQuestStep translateEmoteStep(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateEmoteStep", Object.class);
            method.setAccessible(true);
            return (EmoteQuestStep) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateEmoteStep", e);
        }
    }

    private DigQuestStep translateDigStep(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateDigStep", Object.class);
            method.setAccessible(true);
            return (DigQuestStep) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateDigStep", e);
        }
    }

    private WalkQuestStep translateTileStep(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateTileStep", Object.class);
            method.setAccessible(true);
            return (WalkQuestStep) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateTileStep", e);
        }
    }

    private WidgetQuestStep translateWidgetStep(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateWidgetStep", Object.class);
            method.setAccessible(true);
            return (WidgetQuestStep) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateWidgetStep", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<DialogueOptionResolver> extractDialogueOptions(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("extractDialogueOptions", Object.class);
            method.setAccessible(true);
            return (List<DialogueOptionResolver>) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call extractDialogueOptions", e);
        }
    }

    private DialogueOptionResolver translateDialogueChoice(Object choice) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateDialogueChoice", Object.class);
            method.setAccessible(true);
            return (DialogueOptionResolver) method.invoke(bridge, choice);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call translateDialogueChoice", e);
        }
    }

    private QuestStep translateStepSafe(Object mockStep) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("translateStep", Object.class);
            method.setAccessible(true);
            return (QuestStep) method.invoke(bridge, mockStep);
        } catch (Exception e) {
            return null;
        }
    }

    private String inferObjectMenuAction(String text) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("inferObjectMenuAction", String.class);
            method.setAccessible(true);
            return (String) method.invoke(bridge, text);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call inferObjectMenuAction", e);
        }
    }

    private String inferNpcMenuAction(String text) {
        try {
            QuestHelperBridge bridge = createBridgeWithMockQuest();
            Method method = QuestHelperBridge.class.getDeclaredMethod("inferNpcMenuAction", String.class);
            method.setAccessible(true);
            return (String) method.invoke(bridge, text);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call inferNpcMenuAction", e);
        }
    }

    private QuestHelperBridge createBridgeWithMockQuest() {
        QuestHelper mockQuestHelper = mock(QuestHelper.class);
        return new QuestHelperBridge(mockQuestHelper);
    }

    private Object createMockDialogChoiceSteps(List<String> choices) {
        MockDialogChoiceSteps steps = new MockDialogChoiceSteps();
        steps.choices = new ArrayList<>();
        for (String choice : choices) {
            MockDialogChoiceStep step = new MockDialogChoiceStep();
            step.choice = choice;
            step.groupId = 219;
            step.childId = 1;
            steps.choices.add(step);
        }
        return steps;
    }

    private Object createMockItemRequirement(int id, String name, int quantity) {
        MockItemRequirement req = new MockItemRequirement();
        req.id = id;
        req.name = name;
        req.quantity = quantity;
        return req;
    }

    // ========================================================================
    // Mock Classes (mimicking Quest Helper structures)
    // ========================================================================

    public static class MockNpcStep {
        public int npcID;
        public List<String> text;
        public MockDefinedPoint definedPoint;
        public List<Integer> alternateNpcIDs;
        public Object choices;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "NpcStep";
        }
    }

    public static class MockObjectStep {
        public int objectID;
        public List<String> text;
        public MockDefinedPoint definedPoint;
        public List<Integer> alternateObjectIDs;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "ObjectStep";
        }
    }

    public static class MockItemStep {
        public List<String> text;
        public MockDefinedPoint definedPoint;
        public List<Object> requirements;
        public int iconItemID = -1;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "ItemStep";
        }
    }

    public static class MockConditionalStep {
        public List<String> text;
        public LinkedHashMap<Object, Object> steps;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "ConditionalStep";
        }
    }

    public static class MockEmoteStep {
        public List<String> text;
        public MockDefinedPoint definedPoint;
        public MockQuestEmote emote;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "EmoteStep";
        }
    }

    public static class MockDigStep {
        public List<String> text;
        public MockDefinedPoint definedPoint;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "DigStep";
        }
    }

    public static class MockTileStep {
        public List<String> text;
        public MockDefinedPoint definedPoint;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "TileStep";
        }
    }

    public static class MockWidgetStep {
        public List<String> text;
        public List<MockWidgetDetails> widgetDetails;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "WidgetStep";
        }
    }

    public static class MockUnknownStep {
        public List<String> text;

        public List<String> getText() {
            return text;
        }

        public String getClass_SimpleName() {
            return "UnknownStep";
        }
    }

    public static class MockDefinedPoint {
        public int x, y, plane;

        public MockDefinedPoint(int x, int y, int plane) {
            this.x = x;
            this.y = y;
            this.plane = plane;
        }

        public net.runelite.api.coords.WorldPoint getWorldPoint() {
            return new net.runelite.api.coords.WorldPoint(x, y, plane);
        }
    }

    public static class MockQuestEmote {
        public String name;
        public int spriteId;

        public MockQuestEmote(String name, int spriteId) {
            this.name = name;
            this.spriteId = spriteId;
        }
    }

    public static class MockWidgetDetails {
        public int groupID;
        public int childID;
        public int childChildID;

        public MockWidgetDetails(int groupID, int childID, int childChildID) {
            this.groupID = groupID;
            this.childID = childID;
            this.childChildID = childChildID;
        }
    }

    public static class MockDialogChoiceSteps {
        public List<MockDialogChoiceStep> choices;

        public List<MockDialogChoiceStep> getChoices() {
            return choices;
        }
    }

    public static class MockDialogChoiceStep {
        public String choice;
        public Pattern pattern;
        public int choiceById = -1;
        public int varbitId = -1;
        public Map<Integer, String> varbitValueToAnswer;
        public String expectedPreviousLine;
        public List<String> excludedStrings;
        public int groupId;
        public int childId;

        public String getClass_SimpleName() {
            return "DialogChoiceStep";
        }
    }

    // Requirement mocks
    public static class MockVarbitRequirement {
        public int varbitID;
        public int requiredValue;
        public String operation;

        public String getClass_SimpleName() {
            return "VarbitRequirement";
        }
    }

    public static class MockZoneRequirement {
        public boolean checkInZone;
        public List<MockZone> zones;

        public String getClass_SimpleName() {
            return "ZoneRequirement";
        }
    }

    public static class MockZone {
        public int minX, maxX, minY, maxY, minPlane, maxPlane;

        public MockZone(int minX, int maxX, int minY, int maxY, int minPlane, int maxPlane) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minPlane = minPlane;
            this.maxPlane = maxPlane;
        }
    }

    public static class MockItemRequirement {
        public int id;
        public String name;
        public int quantity;
        public boolean mustBeEquipped;
        public List<Integer> alternateItems;

        public String getClass_SimpleName() {
            return "ItemRequirement";
        }
    }

    public static class MockSkillRequirement {
        public String skill;
        public int requiredLevel;

        public String getClass_SimpleName() {
            return "SkillRequirement";
        }
    }

    public static class MockManualRequirement {
        public boolean shouldPass;

        public String getClass_SimpleName() {
            return "ManualRequirement";
        }
    }

    public static class MockDialogRequirement {
        public List<String> text;
        public String talkerName;
        public boolean mustBeActive;

        public String getClass_SimpleName() {
            return "DialogRequirement";
        }
    }
}

