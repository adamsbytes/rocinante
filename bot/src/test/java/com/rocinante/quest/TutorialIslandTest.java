package com.rocinante.quest;

import com.rocinante.quest.impl.TutorialIsland;
import com.rocinante.quest.steps.QuestStep;
import com.rocinante.quest.steps.QuestStep.StepType;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for the Tutorial Island quest implementation.
 *
 * These tests verify:
 * - Quest metadata is correct
 * - ALL expected varbit steps are defined
 * - Step types are appropriate for their actions
 * - No gaps in varbit coverage
 */
public class TutorialIslandTest {

    private TutorialIsland tutorialIsland;
    private Map<Integer, QuestStep> steps;

    /**
     * All required varbit values for Tutorial Island (except 1 which is character creation).
     * Varbit 1000 is the completion value, not a step.
     */
    private static final List<Integer> REQUIRED_VARBITS = Arrays.asList(
            // Section 1: Gielinor Guide (var 2-10)
            2,   // Talk to Gielinor Guide
            3,   // Open the settings menu
            7,   // Talk to Gielinor Guide (after opening settings menu)
            10,  // Open door to exit Gielinor Guide
            
            // Section 2: Survival Expert (var 20-120)
            20,  // Talk to Survival Expert
            30,  // Open inventory
            40,  // Fish at fishing spot
            50,  // Open skills tab
            60,  // Talk to Survival Expert (after opening skills tab)
            70,  // Cut Tree
            80,  // Make fire
            90,  // Cook Raw Shrimp
            120, // Exit Survival Expert area
            
            // Section 3: Master Chef (var 130-170)
            130, // Enter door to Master Chef
            140, // Talk to Master Chef
            150, // Make dough by using water and flour
            160, // Cook Bread
            170, // Exit Master Chef's house
            
            // Section 4: Quest Guide (var 200-250)
            200, // Enter Quest Guide's house
            220, // Talk to Quest Guide
            230, // Open Quests tab
            240, // Talk to Quest Guide (after opening Quests tab)
            250, // Take ladder to mining area
            
            // Section 5: Mining Instructor (var 260-360)
            260, // Talk to Mining Instructor
            300, // Mine tin
            310, // Mine copper
            320, // Click furnace to smelt Bronze Bar
            330, // Talk to Mining Instructor (after mining copper)
            340, // Click Anvil
            350, // Smith Bronze Dagger
            360, // Enter door to Vannaka
            
            // Section 6: Combat Instructor (var 370-500)
            370, // Talk to Vannaka
            390, // Open equipment tab
            400, // Open equipment stats
            405, // Equip Bronze dagger
            410, // Exit interface and talk to Vannaka
            420, // Equip Wooden Shield and Bronze Sword
            430, // Open combat styles tab
            440, // Enter Rat cage
            450, // Attack Rat with melee
            460, // Waiting for Rat to die
            470, // Exit Rat cage and talk to Vannaka
            480, // Equip Shortbow and Bronze Arrow and attack Rat
            490, // Waiting for Rat to die
            500, // Exit combat area
            
            // Section 7: Account Guide / Bank (var 510-540)
            510, // Open Bank
            520, // Close Bank and open Poll Booth
            530, // Talk to Account Guide
            531, // Open account management tab
            532, // Talk to Account Guide (after opening account management tab)
            540, // Exit Account Guide's room
            
            // Section 8: Brother Brace / Prayer (var 550-610)
            550, // Talk to Brother Brace
            560, // Open prayer tab
            570, // Talk to Brother Brace (after opening prayer tab)
            580, // Open friend's list
            600, // Talk to Brother Brace (after opening friend's list)
            610, // Exit chapel
            
            // Section 9: Magic Instructor (var 620-670)
            620, // Walk/talk to Magic Instructor
            630, // Open spell book
            640, // Talk to Magic Instructor (after opening spells)
            650, // Kill Chicken with Air Strike
            670  // Magic Instructor is ready to teleport the player off
    );

    @Before
    public void setUp() {
        tutorialIsland = new TutorialIsland();
        steps = tutorialIsland.loadSteps();
    }

    // ========================================================================
    // Metadata Tests
    // ========================================================================

    @Test
    public void testQuestId() {
        assertEquals("tutorial_island", tutorialIsland.getId());
    }

    @Test
    public void testQuestName() {
        assertEquals("Tutorial Island", tutorialIsland.getName());
    }

    @Test
    public void testProgressVarp() {
        assertEquals(281, tutorialIsland.getProgressVarbit());
        assertTrue("Tutorial Island should use Varp, not Varbit", tutorialIsland.usesVarp());
    }

    @Test
    public void testCompletionValue() {
        assertEquals(1000, tutorialIsland.getCompletionValue());
    }

    @Test
    public void testHasCombat() {
        assertTrue(tutorialIsland.hasCombat());
    }

    @Test
    public void testDifficulty() {
        assertEquals("Tutorial", tutorialIsland.getDifficulty());
    }

    // ========================================================================
    // Complete Varbit Coverage Test
    // ========================================================================

    @Test
    public void testAllRequiredVarbitsAreCovered() {
        // This is the CRITICAL test - ensures every single required varbit has a step
        for (Integer varbit : REQUIRED_VARBITS) {
            assertTrue("Missing step for varbit " + varbit + " - Check REQUIRED_VARBITS list for description",
                    steps.containsKey(varbit));
        }
    }

    @Test
    public void testStepCountMatchesRequiredVarbits() {
        // Ensure we have exactly the number of steps we expect
        assertEquals("Step count should match required varbits",
                REQUIRED_VARBITS.size(), steps.size());
    }

    @Test
    public void testNoExtraStepsExist() {
        // Ensure no steps exist that aren't in our required list
        for (Integer varbit : steps.keySet()) {
            assertTrue("Unexpected step for varbit " + varbit + " - not in REQUIRED_VARBITS",
                    REQUIRED_VARBITS.contains(varbit));
        }
    }

    // ========================================================================
    // Step Type Tests
    // ========================================================================

    @Test
    public void testGielinorGuideStepTypes() {
        assertEquals(StepType.NPC, steps.get(2).getType());    // Talk to Gielinor Guide
        assertEquals(StepType.WIDGET, steps.get(3).getType()); // Open settings
        assertEquals(StepType.NPC, steps.get(7).getType());    // Talk again
        assertEquals(StepType.OBJECT, steps.get(10).getType()); // Open door
    }

    @Test
    public void testSurvivalExpertStepTypes() {
        assertEquals(StepType.NPC, steps.get(20).getType());    // Talk to Survival Expert
        assertEquals(StepType.WIDGET, steps.get(30).getType()); // Open inventory
        assertEquals(StepType.NPC, steps.get(40).getType());    // Fish (fishing spot is NPC)
        assertEquals(StepType.WIDGET, steps.get(50).getType()); // Open skills tab
        assertEquals(StepType.NPC, steps.get(60).getType());    // Talk again
        assertEquals(StepType.OBJECT, steps.get(70).getType()); // Cut tree
        assertEquals(StepType.ITEM, steps.get(80).getType());   // Make fire (use tinderbox)
        assertEquals(StepType.ITEM, steps.get(90).getType());   // Cook shrimp
        assertEquals(StepType.OBJECT, steps.get(120).getType()); // Exit gate
    }

    @Test
    public void testMasterChefStepTypes() {
        assertEquals(StepType.OBJECT, steps.get(130).getType()); // Enter door
        assertEquals(StepType.NPC, steps.get(140).getType());    // Talk to Chef
        assertEquals(StepType.ITEM, steps.get(150).getType());   // Make dough
        assertEquals(StepType.ITEM, steps.get(160).getType());   // Cook bread
        assertEquals(StepType.OBJECT, steps.get(170).getType()); // Exit door
    }

    @Test
    public void testQuestGuideStepTypes() {
        assertEquals(StepType.OBJECT, steps.get(200).getType()); // Enter building
        assertEquals(StepType.NPC, steps.get(220).getType());    // Talk to Quest Guide
        assertEquals(StepType.WIDGET, steps.get(230).getType()); // Open quests tab
        assertEquals(StepType.NPC, steps.get(240).getType());    // Talk again
        assertEquals(StepType.OBJECT, steps.get(250).getType()); // Climb ladder
    }

    @Test
    public void testMiningStepTypes() {
        assertEquals(StepType.NPC, steps.get(260).getType());    // Talk to Mining Instructor
        assertEquals(StepType.OBJECT, steps.get(300).getType()); // Mine tin
        assertEquals(StepType.OBJECT, steps.get(310).getType()); // Mine copper
        assertEquals(StepType.OBJECT, steps.get(320).getType()); // Smelt at furnace
        assertEquals(StepType.NPC, steps.get(330).getType());    // Talk again
        assertEquals(StepType.OBJECT, steps.get(340).getType()); // Click anvil
        assertEquals(StepType.WIDGET, steps.get(350).getType()); // Smith dagger (widget)
        assertEquals(StepType.OBJECT, steps.get(360).getType()); // Enter gate
    }

    @Test
    public void testCombatStepTypes() {
        assertEquals(StepType.NPC, steps.get(370).getType());    // Talk to Combat Instructor
        assertEquals(StepType.WIDGET, steps.get(390).getType()); // Open equipment tab
        assertEquals(StepType.WIDGET, steps.get(400).getType()); // Open equipment stats
        assertEquals(StepType.ITEM, steps.get(405).getType());   // Equip dagger
        assertEquals(StepType.NPC, steps.get(410).getType());    // Talk again
        assertEquals(StepType.COMPOSITE, steps.get(420).getType()); // Equip sword+shield (composite)
        assertEquals(StepType.WIDGET, steps.get(430).getType()); // Open combat tab
        assertEquals(StepType.OBJECT, steps.get(440).getType()); // Enter rat cage
        assertEquals(StepType.COMBAT, steps.get(450).getType()); // Attack rat
        assertEquals(StepType.CUSTOM, steps.get(460).getType()); // Wait for rat to die (WaitQuestStep)
        assertEquals(StepType.NPC, steps.get(470).getType());    // Talk to Combat Instructor
        assertEquals(StepType.COMBAT, steps.get(480).getType()); // Equip bow (via AttackStyle) + attack
        assertEquals(StepType.CUSTOM, steps.get(490).getType()); // Wait for rat to die (WaitQuestStep)
        assertEquals(StepType.OBJECT, steps.get(500).getType()); // Exit combat area
    }

    @Test
    public void testBankStepTypes() {
        assertEquals(StepType.OBJECT, steps.get(510).getType()); // Open bank
        assertEquals(StepType.OBJECT, steps.get(520).getType()); // Open poll booth
        assertEquals(StepType.NPC, steps.get(530).getType());    // Talk to Account Guide
        assertEquals(StepType.WIDGET, steps.get(531).getType()); // Open account tab
        assertEquals(StepType.NPC, steps.get(532).getType());    // Talk again
        assertEquals(StepType.OBJECT, steps.get(540).getType()); // Exit door
    }

    @Test
    public void testPrayerStepTypes() {
        assertEquals(StepType.NPC, steps.get(550).getType());    // Talk to Brother Brace
        assertEquals(StepType.WIDGET, steps.get(560).getType()); // Open prayer tab
        assertEquals(StepType.NPC, steps.get(570).getType());    // Talk again
        assertEquals(StepType.WIDGET, steps.get(580).getType()); // Open friends list
        assertEquals(StepType.NPC, steps.get(600).getType());    // Talk again
        assertEquals(StepType.OBJECT, steps.get(610).getType()); // Exit chapel
    }

    @Test
    public void testMagicStepTypes() {
        assertEquals(StepType.NPC, steps.get(620).getType());    // Talk to Magic Instructor
        assertEquals(StepType.WIDGET, steps.get(630).getType()); // Open spellbook
        assertEquals(StepType.NPC, steps.get(640).getType());    // Talk again
        assertEquals(StepType.COMBAT, steps.get(650).getType()); // Kill chicken with magic
        assertEquals(StepType.NPC, steps.get(670).getType());    // Final dialogue
    }

    // ========================================================================
    // Completion Tests
    // ========================================================================

    @Test
    public void testIsComplete() {
        assertTrue(tutorialIsland.isComplete(1000));
        assertTrue(tutorialIsland.isComplete(1001));
        assertFalse(tutorialIsland.isComplete(999));
        assertFalse(tutorialIsland.isComplete(670));
    }

    @Test
    public void testIsStarted() {
        assertTrue(tutorialIsland.isStarted(1));
        assertTrue(tutorialIsland.isStarted(2));
        assertFalse(tutorialIsland.isStarted(0));
    }

    // ========================================================================
    // NPC ID Tests
    // ========================================================================

    @Test
    public void testNpcIdsPositive() {
        assertTrue(TutorialIsland.NPC_GIELINOR_GUIDE > 0);
        assertTrue(TutorialIsland.NPC_SURVIVAL_EXPERT > 0);
        assertTrue(TutorialIsland.NPC_MASTER_CHEF > 0);
        assertTrue(TutorialIsland.NPC_QUEST_GUIDE > 0);
        assertTrue(TutorialIsland.NPC_MINING_INSTRUCTOR > 0);
        assertTrue(TutorialIsland.NPC_COMBAT_INSTRUCTOR > 0);
        assertTrue(TutorialIsland.NPC_ACCOUNT_GUIDE > 0);
        assertTrue(TutorialIsland.NPC_BROTHER_BRACE > 0);
        assertTrue(TutorialIsland.NPC_MAGIC_INSTRUCTOR > 0);
        assertTrue(TutorialIsland.NPC_GIANT_RAT > 0);
        assertTrue(TutorialIsland.NPC_CHICKEN > 0);
        assertTrue(TutorialIsland.NPC_FISHING_SPOT > 0);
    }

    // ========================================================================
    // Object ID Tests
    // ========================================================================

    @Test
    public void testObjectIdsPositive() {
        assertTrue(TutorialIsland.OBJECT_TREE > 0);
        assertTrue(TutorialIsland.OBJECT_TIN_ROCK > 0);
        assertTrue(TutorialIsland.OBJECT_COPPER_ROCK > 0);
        assertTrue(TutorialIsland.OBJECT_FURNACE > 0);
        assertTrue(TutorialIsland.OBJECT_ANVIL > 0);
        assertTrue(TutorialIsland.OBJECT_RANGE > 0);
        assertTrue(TutorialIsland.OBJECT_BANK_BOOTH > 0);
        assertTrue(TutorialIsland.OBJECT_POLL_BOOTH > 0);
        assertTrue(TutorialIsland.OBJECT_GUIDE_DOOR > 0);
        assertTrue(TutorialIsland.OBJECT_SURVIVAL_GATE > 0);
        assertTrue(TutorialIsland.OBJECT_CHEF_DOOR_ENTER > 0);
        assertTrue(TutorialIsland.OBJECT_CHEF_DOOR_EXIT > 0);
        assertTrue(TutorialIsland.OBJECT_QUEST_DOOR > 0);
        assertTrue(TutorialIsland.OBJECT_QUEST_LADDER > 0);
        assertTrue(TutorialIsland.OBJECT_MINING_EXIT > 0);
        assertTrue(TutorialIsland.OBJECT_RAT_GATE > 0);
        assertTrue(TutorialIsland.OBJECT_COMBAT_LADDER > 0);
        assertTrue(TutorialIsland.OBJECT_BANK_EXIT > 0);
        assertTrue(TutorialIsland.OBJECT_PRAYER_EXIT > 0);
    }

    // ========================================================================
    // Item ID Tests
    // ========================================================================

    @Test
    public void testItemIdsPositive() {
        assertTrue(TutorialIsland.ITEM_BRONZE_AXE > 0);
        assertTrue(TutorialIsland.ITEM_BRONZE_PICKAXE > 0);
        assertTrue(TutorialIsland.ITEM_TINDERBOX > 0);
        assertTrue(TutorialIsland.ITEM_NET > 0);
        assertTrue(TutorialIsland.ITEM_RAW_SHRIMP > 0);
        assertTrue(TutorialIsland.ITEM_SHRIMP > 0);
        assertTrue(TutorialIsland.ITEM_LOGS > 0);
        assertTrue(TutorialIsland.ITEM_BREAD_DOUGH > 0);
        assertTrue(TutorialIsland.ITEM_BREAD > 0);
        assertTrue(TutorialIsland.ITEM_POT_OF_FLOUR > 0);
        assertTrue(TutorialIsland.ITEM_BUCKET_OF_WATER > 0);
        assertTrue(TutorialIsland.ITEM_TIN_ORE > 0);
        assertTrue(TutorialIsland.ITEM_COPPER_ORE > 0);
        assertTrue(TutorialIsland.ITEM_BRONZE_BAR > 0);
        assertTrue(TutorialIsland.ITEM_BRONZE_DAGGER > 0);
        assertTrue(TutorialIsland.ITEM_BRONZE_SWORD > 0);
        assertTrue(TutorialIsland.ITEM_WOODEN_SHIELD > 0);
        assertTrue(TutorialIsland.ITEM_SHORTBOW > 0);
        assertTrue(TutorialIsland.ITEM_BRONZE_ARROW > 0);
        assertTrue(TutorialIsland.ITEM_AIR_RUNE > 0);
        assertTrue(TutorialIsland.ITEM_MIND_RUNE > 0);
    }

    // ========================================================================
    // Step Text Tests
    // ========================================================================

    @Test
    public void testStepTexts() {
        for (Map.Entry<Integer, QuestStep> entry : steps.entrySet()) {
            QuestStep step = entry.getValue();
            assertNotNull("Step at varbit " + entry.getKey() + " has null text", step.getText());
            assertFalse("Step at varbit " + entry.getKey() + " has blank text", 
                    step.getText().isEmpty());
        }
    }

    // ========================================================================
    // First Step Content Tests
    // ========================================================================

    @Test
    public void testFirstStepContent() {
        QuestStep firstStep = steps.get(2);
        assertNotNull("Step at varbit 2 should exist", firstStep);
        assertTrue("First step text should mention Gielinor Guide",
                firstStep.getText().toLowerCase().contains("gielinor guide"));
    }

    @Test
    public void testFinalStepContent() {
        QuestStep finalStep = steps.get(670);
        assertNotNull("Step at varbit 670 should exist", finalStep);
        assertTrue("Final step text should mention Magic Instructor or leave",
                finalStep.getText().toLowerCase().contains("magic instructor") ||
                        finalStep.getText().toLowerCase().contains("leave"));
    }
}
