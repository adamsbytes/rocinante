package com.rocinante.quest.impl;

import com.rocinante.behavior.AccountType;
import com.rocinante.combat.AttackStyle;
import com.rocinante.combat.spell.StandardSpell;
import com.rocinante.quest.Quest;
import com.rocinante.quest.conditions.VarbitCondition;
import com.rocinante.quest.steps.*;
import com.rocinante.state.Conditions;
import com.rocinante.state.IronmanState;
import com.rocinante.state.StateCondition;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.DialogueTask;
import com.rocinante.tasks.impl.IronmanSelectionTask;
import com.rocinante.tasks.impl.SettingsTask;
import com.rocinante.util.ItemCollections;
import com.rocinante.util.ObjectCollections;
import net.runelite.api.ItemID;
import net.runelite.api.NpcID;
import net.runelite.api.ObjectID;
import net.runelite.api.coords.WorldPoint;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tutorial Island "quest" implementation.
 *
 * Tutorial Island uses varbit 281 to track progress through the tutorial.
 * While not a "true" quest in Quest Helper, it follows the same step-based
 * progression pattern and can be implemented using our quest framework.
 *
 * <p>Progress values (varbit 281):
 * <ul>
 *   <li>1: Character customization</li>
 *   <li>2: Talk to Gielinor Guide</li>
 *   <li>3: Open settings menu</li>
 *   <li>7: Talk to Gielinor Guide (after settings)</li>
 *   <li>10: Exit Gielinor Guide's house</li>
 *   <li>20: Talk to Survival Expert</li>
 *   <li>30: Open inventory</li>
 *   <li>40: Fish at fishing spot</li>
 *   <li>50: Open skills tab</li>
 *   <li>60: Talk to Survival Expert (after skills)</li>
 *   <li>70: Cut tree</li>
 *   <li>80: Make fire</li>
 *   <li>90: Cook raw shrimp</li>
 *   <li>120: Exit Survival Expert area</li>
 *   <li>... and so on</li>
 *   <li>1000: Tutorial complete</li>
 * </ul>
 */
public class TutorialIsland implements Quest {

    // ========================================================================
    // Dependencies
    // ========================================================================
    
    /**
     * IronmanState for reading intended account type.
     * If null, assumes NORMAL account (no ironman selection needed).
     */
    @Nullable
    private final IronmanState ironmanState;

    // ========================================================================
    // Constructors
    // ========================================================================
    
    /**
     * Create Tutorial Island quest with IronmanState support.
     * 
     * @param ironmanState ironman state (null = normal account)
     */
    public TutorialIsland(@Nullable IronmanState ironmanState) {
        this.ironmanState = ironmanState;
    }
    
    /**
     * Default constructor for normal accounts.
     */
    public TutorialIsland() {
        this(null);
    }

    // ========================================================================
    // Constants - Varp (VarPlayer)
    // ========================================================================

    /**
     * Tutorial Island progress varp (VarPlayer, NOT varbit).
     * Read with client.getVarpValue(281), not getVarbitValue().
     */
    public static final int VARP_TUTORIAL_PROGRESS = 281;

    /**
     * Value indicating tutorial is complete.
     */
    public static final int TUTORIAL_COMPLETE_VALUE = 1000;

    // ========================================================================
    // Constants - NPC Spawn Locations (for auto-walk navigation)
    // ========================================================================

    // These are the spawn/standing locations for Tutorial Island NPCs
    // Used by QuestExecutor's auto-walk logic when player is far from target
    private static final WorldPoint LOC_GIELINOR_GUIDE = new WorldPoint(3093, 3107, 0);
    private static final WorldPoint LOC_SURVIVAL_EXPERT = new WorldPoint(3103, 3095, 0);
    private static final WorldPoint LOC_MASTER_CHEF = new WorldPoint(3076, 3085, 0);
    private static final WorldPoint LOC_QUEST_GUIDE = new WorldPoint(3086, 3122, 0);
    private static final WorldPoint LOC_MINING_INSTRUCTOR = new WorldPoint(3081, 9504, 0);
    private static final WorldPoint LOC_COMBAT_INSTRUCTOR = new WorldPoint(3106, 9508, 0);
    private static final WorldPoint LOC_ACCOUNT_GUIDE = new WorldPoint(3124, 3124, 0);
    private static final WorldPoint LOC_BROTHER_BRACE = new WorldPoint(3127, 3107, 0);
    private static final WorldPoint LOC_IRONMAN_TUTOR = new WorldPoint(3127, 3088, 0);
    private static final WorldPoint LOC_MAGIC_INSTRUCTOR = new WorldPoint(3141, 3089, 0);


    // ========================================================================
    // Quest Interface
    // ========================================================================

    @Override
    public String getId() {
        return "tutorial_island";
    }

    @Override
    public String getName() {
        return "Tutorial Island";
    }

    @Override
    public int getProgressVarbit() {
        return VARP_TUTORIAL_PROGRESS;
    }

    @Override
    public boolean usesVarp() {
        return true; // Tutorial Island uses VarPlayer, not Varbit
    }

    @Override
    public int getCompletionValue() {
        return TUTORIAL_COMPLETE_VALUE;
    }

    @Override
    public String getDifficulty() {
        return "Tutorial";
    }

    @Override
    public int getEstimatedMinutes() {
        return 15;
    }

    @Override
    public boolean hasCombat() {
        return true;
    }

    @Override
    public String getDescription() {
        return "Complete the Tutorial Island to learn the basics of Old School RuneScape.";
    }

    // ========================================================================
    // Step Definitions
    // ========================================================================

    @Override
    public Map<Integer, QuestStep> loadSteps() {
        Map<Integer, QuestStep> steps = new HashMap<>();

        // ====================================================================
        // Section 1: Gielinor Guide (var 2-10)
        // ====================================================================

        // var 2: Talk to Gielinor Guide
        steps.put(2, new NpcQuestStep(NpcID.GIELINOR_GUIDE, "Talk to the Gielinor Guide")
                .withMenuAction("Talk-to")
                .withDialogueExpected(true));

        // var 3: Open settings menu, then set to fixed mode
        // This is a composite step: first open settings (tutorial requirement), then ensure fixed mode
        steps.put(3, createOpenSettingsAndSetFixedModeStep());

        // var 7: Talk to Gielinor Guide after opening settings
        steps.put(7, new NpcQuestStep(NpcID.GIELINOR_GUIDE, "Talk to the Gielinor Guide again")
                .withDialogueExpected(true));

        // var 10: Exit Gielinor Guide's house
        steps.put(10, new ObjectQuestStep(ObjectID.DOOR_9398, "Open", "Open the door to exit"));

        // ====================================================================
        // Section 2: Survival Expert (var 20-120)
        // ====================================================================

        // var 20: Talk to Survival Expert
        steps.put(20, new NpcQuestStep(NpcID.SURVIVAL_EXPERT, "Talk to the Survival Expert")
                .withDialogueExpected(true));

        // var 30: Open inventory (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(30, WidgetQuestStep.openInventoryByClick("Open your inventory"));

        // var 40: Fish at fishing spot
        steps.put(40, new NpcQuestStep(NpcID.FISHING_SPOT_3317, "Net fish at the fishing spot")
                .withMenuAction("Net")
                .withDialogueExpected(false));

        // var 50: Open skills tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(50, WidgetQuestStep.openSkillsByClick("Open the skills tab"));

        // var 60: Talk to Survival Expert again
        steps.put(60, new NpcQuestStep(NpcID.SURVIVAL_EXPERT, "Talk to the Survival Expert again")
                .withDialogueExpected(true));

        // var 70: Cut tree
        steps.put(70, new ObjectQuestStep(ObjectCollections.TREES, "Chop down", "Cut down a tree"));

        // var 80: Make fire
        steps.put(80, createMakeFireStep());

        // var 90: Cook raw shrimp
        steps.put(90, createCookShrimpStep());

        // Note: Varp gap 90→120 is intentional. Successfully cooking shrimp may
        // advance varp through intermediate values as the game processes the action.

        // var 120: Exit Survival Expert area (NEWBIEGATECLOSEDL2)
        steps.put(120, new ObjectQuestStep(ObjectID.GATE_9470, "Open", "Go through the gate"));

        // Note: Varp gap 120→130 is intentional. Walking through the gate advances varp.

        // ====================================================================
        // Section 3: Master Chef (var 130-170)
        // ====================================================================

        // var 130: Enter Chef's house
        steps.put(130, new ObjectQuestStep(ObjectID.DOOR_9709, "Open", "Enter the cook's building"));

        // var 140: Talk to Master Chef
        steps.put(140, new NpcQuestStep(NpcID.MASTER_CHEF, "Talk to the Master Chef")
                .withDialogueExpected(true));

        // var 150: Make bread dough
        steps.put(150, createMakeDoughStep());

        // var 160: Cook bread (NEWBIERANGE)
        steps.put(160, ItemQuestStep.useOn(ItemID.BREAD_DOUGH, ObjectID.RANGE_9736, "Cook the bread dough"));

        // var 170: Exit Chef's house
        steps.put(170, new ObjectQuestStep(ObjectID.DOOR_9710, "Open", "Exit the cook's building"));

        // Note: Varp gap 170→200 is intentional. Walking between the Chef's house
        // and Quest Guide's house may advance varp through intermediate values.

        // ====================================================================
        // Section 4: Quest Guide (var 200-250)
        // ====================================================================

        // var 200: Enter Quest Guide's building (NEWBIE_DOOR4)
        // Quest Guide building is north of cook's house - need to walk there first
        steps.put(200, new ObjectQuestStep(ObjectID.DOOR_9716, "Open", "Enter the quest guide's building")
                .withWalkTo(new WorldPoint(3086, 3119, 0))
                .withSearchRadius(20));

        // var 220: Talk to Quest Guide
        steps.put(220, new NpcQuestStep(NpcID.QUEST_GUIDE, "Talk to the Quest Guide")
                .withDialogueExpected(true));

        // var 230: Open quest tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(230, WidgetQuestStep.openQuestsByClick("Open the quest journal"));

        // var 240: Talk to Quest Guide again
        steps.put(240, new NpcQuestStep(NpcID.QUEST_GUIDE, "Talk to the Quest Guide again")
                .withDialogueExpected(true));

        // var 250: Go down ladder (NEWBIELADDER1 - actually 9726 in game)
        steps.put(250, new ObjectQuestStep(ObjectID.LADDER_9726, "Climb-down", "Climb down the ladder"));

        // Note: Varp gap 250→260 is intentional. Climbing the ladder advances varp.

        // ====================================================================
        // Section 5: Mining Instructor (var 260-360)
        // ====================================================================

        // var 260: Talk to Mining Instructor
        steps.put(260, new NpcQuestStep(NpcID.MINING_INSTRUCTOR, "Talk to the Mining Instructor")
                .withDialogueExpected(true));

        // Note: Varp gap 260→300 is intentional. The dialogue with Mining Instructor
        // automatically advances varp through intermediate values (270, 280, 290) as
        // the instructor gives items and explains mining. These don't require bot action.

        // var 300: Mine tin
        steps.put(300, new ObjectQuestStep(ObjectCollections.TIN_ROCKS, "Mine", "Mine some tin ore"));

        // var 310: Mine copper
        steps.put(310, new ObjectQuestStep(ObjectCollections.COPPER_ROCKS, "Mine", "Mine some copper ore"));

        // var 320: Smelt bronze bar (Tutorial Island furnace uses "Use" not "Smelt")
        steps.put(320, new ObjectQuestStep(ObjectCollections.FURNACES, "Use", "Smelt a bronze bar"));

        // var 330: Talk to Mining Instructor
        steps.put(330, new NpcQuestStep(NpcID.MINING_INSTRUCTOR, "Talk to the Mining Instructor")
                .withDialogueExpected(true));

        // var 340: Click anvil
        steps.put(340, new ObjectQuestStep(ObjectCollections.ANVILS, "Smith", "Click on the anvil"));

        // var 350: Smith bronze dagger
        steps.put(350, createSmithDaggerStep());

        // var 360: Enter combat area (NEWBIEDOOR4L/R - the gate between mining and combat areas)
        // Note: 9719/9720 is NEWBIEDOOR5 which is a different gate. The gate here is 9717/9718.
        steps.put(360, new ObjectQuestStep(Arrays.asList(ObjectID.GATE_9717, ObjectID.GATE_9718), "Open", "Go through the gate"));

        // ====================================================================
        // Section 6: Combat Instructor (var 370-500)
        // ====================================================================

        // var 370: Talk to Combat Instructor
        steps.put(370, new NpcQuestStep(NpcID.COMBAT_INSTRUCTOR, "Talk to the Combat Instructor")
                .withDialogueExpected(true));

        // var 390: Open equipment tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(390, WidgetQuestStep.openEquipmentByClick("Open the equipment tab"));

        // var 400: Open equipment stats (387:1 = EQUIPMENT button, NOT 387:17 which is SLOT2)
        steps.put(400, new WidgetQuestStep(387, 1, "View equipment stats")
                .withAction(WidgetQuestStep.WidgetAction.CLICK));

        // var 405: Equip bronze dagger, then close the equipment stats interface
        steps.put(405, createEquipDaggerAndCloseStep());

        // var 410: Talk to Combat Instructor
        steps.put(410, new NpcQuestStep(NpcID.COMBAT_INSTRUCTOR, "Talk to the Combat Instructor again")
                .withDialogueExpected(true));

        // var 420: Equip sword and shield
        steps.put(420, createEquipSwordShieldStep());

        // var 430: Open combat styles tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(430, WidgetQuestStep.openCombatStylesByClick("Open the combat styles tab"));

        // var 440: Enter rat cage (NEWBIEDOOR5_L/R at 9519/9520)
        // Note: Despite the name, NEWBIEDOOR5 (9719/9720) is the rat cage gate, not NEWBIE_DOOR6 (9721)
        steps.put(440, new ObjectQuestStep(Arrays.asList(ObjectID.GATE_9719, ObjectID.GATE_9720), "Open", "Enter the rat cage"));

        // var 450: Attack rat (melee)
        steps.put(450, new CombatQuestStep(NpcID.GIANT_RAT_3313, "Attack the giant rat")
                .withAttackStyle(AttackStyle.MELEE));

        // var 460: Wait for rat to die - combat initiated by step 450 handles the fight
        steps.put(460, new WaitQuestStep("Waiting for the giant rat to die"));

        // var 470: Exit rat cage and talk to Combat Instructor
        // Must go through the gate first, then talk to instructor
        steps.put(470, createExitRatCageAndTalkStep());

        // var 480: Equip bow and arrows, attack rat with ranged
        steps.put(480, new CombatQuestStep(NpcID.GIANT_RAT_3313, "Equip bow and arrows, attack the rat")
                .withAttackStyle(AttackStyle.RANGED));

        // var 490: Wait for rat to die - combat initiated by step 480 handles the fight
        steps.put(490, new WaitQuestStep("Waiting for the rat to die"));

        // var 500: Exit combat area - climb UP from bottom of ladder (NEWBIELADDER2)
        // Note: 9727 is the ladder at the BOTTOM (climb up), 9728 is at the TOP (climb down)
        steps.put(500, new ObjectQuestStep(ObjectID.LADDER_9727, "Climb-up", "Climb the ladder"));

        // ====================================================================
        // Section 7: Account Guide / Bank (var 510-540)
        // ====================================================================

        // var 510: Open bank, then close it (varbit advances after bank interface is used)
        steps.put(510, createUseBankAndCloseStep());

        // var 520: Use poll booth, then click through the MESBOX dialogue
        // The poll booth shows an info message that needs to be dismissed with spacebar
        steps.put(520, createUsePollBoothStep());

        // var 525: Go through the door to the Account Guide's room (NEWBIE_DOOR6)
        steps.put(525, new ObjectQuestStep(ObjectID.DOOR_9721, "Open", "Go through the door to the Account Guide"));

        // var 530: Talk to Account Guide
        steps.put(530, new NpcQuestStep(NpcID.ACCOUNT_GUIDE, "Talk to the Account Guide")
                .withDialogueExpected(true));

        // var 531: Open account management tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(531, WidgetQuestStep.openAccountManagementByClick("Open the account management tab"));

        // var 532: Talk to Account Guide again
        steps.put(532, new NpcQuestStep(NpcID.ACCOUNT_GUIDE, "Talk to the Account Guide again")
                .withDialogueExpected(true));

        // var 540: Exit account guide's room (NEWBIE_DOOR7)
        steps.put(540, new ObjectQuestStep(ObjectID.DOOR_9722, "Open", "Exit through the door"));

        // ====================================================================
        // Section 8: Brother Brace / Prayer (var 550-610)
        // ====================================================================

        // var 550: Talk to Brother Brace (in the chapel south of the bank)
        // Need to walk there first since he's outside render range from the Account Guide area
        steps.put(550, new NpcQuestStep(NpcID.BROTHER_BRACE, "Talk to Brother Brace")
                .withWalkTo(new WorldPoint(3127, 3107, 0))
                .withDialogueExpected(true));

        // var 560: Open prayer tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(560, WidgetQuestStep.openPrayerByClick("Open the prayer tab"));

        // var 570: Talk to Brother Brace again
        steps.put(570, new NpcQuestStep(NpcID.BROTHER_BRACE, "Talk to Brother Brace again")
                .withDialogueExpected(true));

        // var 580: Open friends list (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(580, WidgetQuestStep.openFriendsListByClick("Open your friends list"));

        // var 600: Talk to Brother Brace again
        steps.put(600, new NpcQuestStep(NpcID.BROTHER_BRACE, "Talk to Brother Brace")
                .withDialogueExpected(true));

        // var 610: Exit chapel (and optionally select ironman mode)
        // Note: The game's varp 281 jumps directly from 610 to 620, so ironman selection
        // must happen as part of this step before reaching the Magic Instructor.
        // Paul (Ironman tutor) is on the path between the chapel and Magic instructor.
        steps.put(610, createExitChapelStep());

        // ====================================================================
        // Section 9: Magic Instructor (var 620-1000)
        // ====================================================================

        // var 620: Talk to Magic Instructor
        steps.put(620, new NpcQuestStep(NpcID.MAGIC_INSTRUCTOR, "Talk to the Magic Instructor")
                .withWalkTo(LOC_MAGIC_INSTRUCTOR)
                .withDialogueExpected(true));

        // var 630: Open spellbook (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(630, WidgetQuestStep.openSpellbookByClick("Open your spellbook"));

        // var 640: Talk to Magic Instructor again
        steps.put(640, new NpcQuestStep(NpcID.MAGIC_INSTRUCTOR, "Talk to the Magic Instructor again")
                .withWalkTo(LOC_MAGIC_INSTRUCTOR)
                .withDialogueExpected(true));

        // var 650: Cast Wind Strike on chicken (just need to cast once, not kill)
        steps.put(650, new CombatQuestStep(NpcID.CHICKEN_3316, "Cast Wind Strike on the chicken")
                .withSpells(StandardSpell.WIND_STRIKE)
                .withAutocast(false)
                .withAttackCount(1)  // Just cast once, don't need to kill
                .withCombatLocation(LOC_MAGIC_INSTRUCTOR));

        // var 670: Talk to Magic Instructor to teleport off island
        steps.put(670, new NpcQuestStep(NpcID.MAGIC_INSTRUCTOR, "Talk to the Magic Instructor to leave")
                .withWalkTo(LOC_MAGIC_INSTRUCTOR)
                .withDialogueExpected(true)
                .withDialogueOptions("Yes", "No, I'm not ready"));
        
        // var 1000: Tutorial complete - mark in IronmanState
        steps.put(1000, new CustomQuestStep("Tutorial Island complete", ctx -> {
            if (ironmanState != null) {
                ironmanState.markTutorialCompleted();
            }
            // Return a no-op task that completes immediately
            return new com.rocinante.tasks.AbstractTask() {
                {
                    this.timeout = java.time.Duration.ofSeconds(1);
                }
                
                @Override
                public String getDescription() {
                    return "Mark tutorial complete";
                }
                
                @Override
                public boolean canExecute(com.rocinante.tasks.TaskContext context) {
                    return true;
                }
                
                @Override
                protected void executeImpl(com.rocinante.tasks.TaskContext context) {
                    complete();
                }
            };
        }));

        return steps;
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    /**
     * Check if ironman mode should be selected during tutorial.
     * 
     * @return true if player should talk to Paul and select ironman mode
     */
    private boolean shouldSelectIronmanMode() {
        if (ironmanState == null) {
            return false;
        }
        
        AccountType intendedType = ironmanState.getIntendedType();
        
        // Only select ironman if intended type is an ironman variant
        return intendedType.isIronman();
    }

    // ========================================================================
    // Complex Step Builders
    // ========================================================================

    /**
     * Create the step for making a fire (use tinderbox on logs).
     * Uses ItemCollections to accept any tinderbox variant and any burnable logs.
     */
    private QuestStep createMakeFireStep() {
        return ItemQuestStep.useOnItem(
                ItemCollections.TINDERBOXES,
                ItemCollections.BURNABLE_LOGS,
                "Light a fire with the tinderbox and logs");
    }

    /**
     * Create the step for cooking shrimp.
     * Uses ItemCollections for raw shrimp variants and ObjectCollections for any cooking fire.
     */
    private QuestStep createCookShrimpStep() {
        return ItemQuestStep.useOn(ItemCollections.RAW_SHRIMP, ObjectCollections.COOKING_FIRES, "Cook the shrimp on the fire");
    }

    /**
     * Create the step for making bread dough.
     * Uses ItemCollections to accept any water container and Tutorial Island flour variant.
     */
    private QuestStep createMakeDoughStep() {
        return ItemQuestStep.useOnItem(
                ItemCollections.WATER_CONTAINERS,
                ItemCollections.POTS_OF_FLOUR,
                "Make bread dough");
    }

    /**
     * Create the step for smithing a bronze dagger.
     */
    private QuestStep createSmithDaggerStep() {
        // This involves clicking on the smithing interface
        // Widget-based interaction for smithing
        return new WidgetQuestStep(312, 9, "Smith a bronze dagger")
                .withAction(WidgetQuestStep.WidgetAction.CLICK);
    }

    /**
     * Create the step for equipping the bronze dagger and then closing the
     * equipment stats interface by pressing Escape.
     * 
     * The equipment stats interface opens for this tutorial step, and the bot
     * needs to close it after equipping to allow subsequent steps to execute.
     */
    private QuestStep createEquipDaggerAndCloseStep() {
        return new QuestStep("Equip the bronze dagger") {
            @Override
            public StepType getType() {
                return StepType.CUSTOM;
            }

            @Override
            public List<Task> toTasks(TaskContext ctx) {
                List<Task> tasks = new java.util.ArrayList<>();
                
                // First, equip the dagger
                tasks.add(new com.rocinante.tasks.impl.EquipItemTask(ItemID.BRONZE_DAGGER)
                        .withDescription("Equip the bronze dagger"));
                
                // Then, close the equipment stats interface by pressing Escape
                tasks.add(new com.rocinante.tasks.AbstractTask() {
                    @Override
                    public String getDescription() {
                        return "Close equipment stats interface";
                    }

                    @Override
                    public boolean canExecute(TaskContext ctx) {
                        return true;
                    }

                    @Override
                    protected void executeImpl(TaskContext ctx) {
                        ctx.getKeyboardController().pressEscape()
                            .thenRun(this::complete)
                            .exceptionally(e -> {
                                fail("Failed to press Escape: " + e.getMessage());
                                return null;
                            });
                    }
                });
                
                return tasks;
            }
        };
    }

    /**
     * Create the step for equipping sword and shield.
     * Uses a CompositeQuestStep to ensure both items are equipped sequentially.
     * Each EquipItemTask handles the case where the item is already equipped.
     */
    private QuestStep createEquipSwordShieldStep() {
        ConditionalQuestStep.CompositeQuestStep equipStep =
                new ConditionalQuestStep.CompositeQuestStep("Equip the bronze sword and wooden shield");

        equipStep.addStep(ItemQuestStep.equip(ItemID.BRONZE_SWORD, "Equip the bronze sword"));
        equipStep.addStep(ItemQuestStep.equip(ItemID.WOODEN_SHIELD, "Equip the wooden shield"));

        return equipStep;
    }

    /**
     * Create the step for exiting the rat cage and talking to the Combat Instructor.
     * After killing the rat with melee, the player must exit through the gate and
     * return to the Combat Instructor.
     */
    private QuestStep createExitRatCageAndTalkStep() {
        ConditionalQuestStep.CompositeQuestStep composite =
                new ConditionalQuestStep.CompositeQuestStep("Exit rat cage and talk to Combat Instructor");

        // Step 1: Exit through the gate (NEWBIEDOOR5_L/R)
        composite.addStep(new ObjectQuestStep(
                Arrays.asList(ObjectID.GATE_9719, ObjectID.GATE_9720), 
                "Open", 
                "Exit the rat cage"));

        // Step 2: Talk to Combat Instructor
        // Note: withDialogueExpected adds a DialogueTask as a 3rd child
        composite.addStep(new NpcQuestStep(NpcID.COMBAT_INSTRUCTOR, "Talk to the Combat Instructor")
                .withDialogueExpected(true));

        // Partial retry is correct here: if gate succeeds but NPC talk fails,
        // player is already OUTSIDE the cage - retrying just the NPC task is right.
        // Full retry would send them back INTO the cage, making things worse.

        return composite;
    }

    /**
     * Create the step for exiting the chapel and optionally selecting ironman mode.
     * 
     * The game's varp 281 jumps directly from 610 to 620, so ironman selection
     * must happen within this step. Paul (Ironman tutor) is on the path between
     * the chapel exit and the Magic Instructor.
     * 
     * Dialogue flow for ironman selection (from OSRS Wiki):
     * 1. Talk to Paul -> "Hello, [player name]. I'm Paul, the Ironman tutor."
     * 2. Select "Tell me about Ironman"
     * 3. Paul explains ironman mode (click through)
     * 4. Select "I'd like to change my Ironman mode"
     * 5. Ironman interface opens (widget group 890)
     * 6. Click appropriate button (Standard/Hardcore/Ultimate)
     * 7. Interface closes and varbit 1777 updates with ironman status
     */
    private QuestStep createExitChapelStep() {
        QuestStep exitChapel = new ObjectQuestStep(ObjectID.DOOR_9723, "Open", "Exit the chapel");
        
        if (!shouldSelectIronmanMode()) {
            // Normal account - just exit chapel
            return exitChapel;
        }
        
        // Ironman account - exit chapel then talk to Paul and select ironman mode
        ConditionalQuestStep.CompositeQuestStep withIronman = 
                new ConditionalQuestStep.CompositeQuestStep("Exit chapel and select ironman mode");
        
        // Step 1: Exit the chapel
        withIronman.addStep(exitChapel);
        
        // Step 2: Talk to Paul (Ironman tutor)
        withIronman.addStep(new NpcQuestStep(NpcID.IRONMAN_TUTOR_9486, "Talk to Paul, the Ironman tutor")
                .withDialogueExpected(true));
        
        // Step 3: Select "Tell me about Ironman" dialogue option
        withIronman.addStep(new DialogueQuestStep("Select: Tell me about Ironman")
                .withOptionText("Tell me about Ironman"));
        
        // Step 4: Click through Paul's explanation
        withIronman.addStep(new DialogueQuestStep("Listen to Paul explain Ironman mode")
                .withClickThrough(true));
        
        // Step 5: Select "I'd like to change my Ironman mode"
        withIronman.addStep(new DialogueQuestStep("Select: I'd like to change my Ironman mode")
                .withOptionText("change my Ironman mode"));
        
        // Step 6: Click the appropriate button in the ironman interface (890)
        withIronman.addStep(new CustomQuestStep("Select ironman type in interface", 
                ctx -> new IronmanSelectionTask(ironmanState)));
        
        return withIronman;
    }
    
    /**
     * Create the step for opening settings and setting fixed mode.
     * 
     * The tutorial instructs the player to open the settings tab at step 3.
     * We use this opportunity to also ensure fixed mode is set, which prevents
     * UI occlusion issues in resizable modes.
     * 
     * This is a composite step:
     * 1. Open settings by click (tutorial requirement)
     * 2. Set to fixed mode via SettingsTask (if not already in fixed mode)
     */
    private QuestStep createOpenSettingsAndSetFixedModeStep() {
        ConditionalQuestStep.CompositeQuestStep composite = 
                new ConditionalQuestStep.CompositeQuestStep("Open settings and set fixed mode");
        
        // Step 1: Open settings tab by clicking (tutorial requirement)
        composite.addStep(WidgetQuestStep.openSettingsByClick("Open the settings menu"));
        
        // Step 2: Set to fixed mode (SettingsTask handles check for already in fixed mode)
        composite.addStep(new CustomQuestStep("Set interface to fixed mode",
                ctx -> SettingsTask.setFixedMode()));
        
        return composite;
    }

    /**
     * Create the step for using the poll booth, clicking through the dialogue, and closing the interface.
     * 
     * The poll booth:
     * 0. First close any lingering interface (e.g., from a previous run)
     * 1. Opens a poll interface
     * 2. Shows a MESBOX dialogue that needs to be dismissed with spacebar
     * 3. The poll interface needs to be closed with Escape
     */
    private QuestStep createUsePollBoothStep() {
        ConditionalQuestStep.CompositeQuestStep composite =
                new ConditionalQuestStep.CompositeQuestStep("Use the poll booth");

        // Step 0: Close any lingering interface from a previous run/crash
        composite.addStep(new CustomQuestStep("Clear any open interface",
                ctx -> new com.rocinante.tasks.AbstractTask() {
                    @Override
                    public String getDescription() {
                        return "Clear any open interface";
                    }

                    @Override
                    public boolean canExecute(TaskContext taskCtx) {
                        return true;
                    }

                    @Override
                    protected void executeImpl(TaskContext taskCtx) {
                        taskCtx.getKeyboardController().pressEscape()
                            .thenCompose(v -> taskCtx.getHumanTimer().sleep(200))
                            .thenRun(this::complete)
                            .exceptionally(e -> {
                                // Even if escape fails, continue anyway
                                complete();
                                return null;
                            });
                    }
                    
                    @Override
                    protected void resetImpl() {
                        // No state to reset
                    }
                }));

        // Step 1: Use the poll booth (opens a MESBOX dialogue, not animation)
        composite.addStep(new ObjectQuestStep(ObjectCollections.POLL_BOOTHS, "Use", "Use the poll booth")
                .withDialogueExpected(true));

        // Step 2: Click through the dialogue (spacebar)
        composite.addStep(new DialogueQuestStep("Click through poll booth message")
                .withClickThrough(true));

        // Step 3: Close the poll interface (Escape)
        composite.addStep(new CustomQuestStep("Close poll interface",
                ctx -> new com.rocinante.tasks.AbstractTask() {
                    @Override
                    public String getDescription() {
                        return "Close poll interface";
                    }

                    @Override
                    public boolean canExecute(TaskContext taskCtx) {
                        return true;
                    }

                    @Override
                    protected void executeImpl(TaskContext taskCtx) {
                        taskCtx.getKeyboardController().pressEscape()
                            .thenCompose(v -> taskCtx.getHumanTimer().sleep(300))
                            .thenRun(this::complete)
                            .exceptionally(e -> {
                                fail("Failed to press Escape: " + e.getMessage());
                                return null;
                            });
                    }
                    
                    @Override
                    protected void resetImpl() {
                        // No state to reset
                    }
                }));

        return composite;
    }

    /**
     * Create the step for using the bank booth and then closing the bank interface.
     * 
     * The tutorial just wants you to open the bank to see it, then close it.
     * Varbit advances to 520 after you close the bank.
     */
    private QuestStep createUseBankAndCloseStep() {
        ConditionalQuestStep.CompositeQuestStep composite =
                new ConditionalQuestStep.CompositeQuestStep("Use the bank booth and close it");

        // Step 1: Use the bank booth to open it
        composite.addStep(new ObjectQuestStep(ObjectID.BANK_BOOTH_10083, "Use", "Use the bank booth"));

        // Step 2: Press Escape to close the bank interface
        composite.addStep(new CustomQuestStep("Close the bank interface",
                ctx -> new com.rocinante.tasks.AbstractTask() {
                    @Override
                    public String getDescription() {
                        return "Press Escape to close bank";
                    }

                    @Override
                    public boolean canExecute(TaskContext taskCtx) {
                        return true;
                    }

                    @Override
                    protected void executeImpl(TaskContext taskCtx) {
                        taskCtx.getKeyboardController().pressEscape()
                            .thenCompose(v -> taskCtx.getHumanTimer().sleep(300))  // Small delay to let interface close
                            .thenRun(this::complete)
                            .exceptionally(e -> {
                                fail("Failed to press Escape: " + e.getMessage());
                                return null;
                            });
                    }
                    
                    @Override
                    protected void resetImpl() {
                        // No state to reset
                    }
                }));

        return composite;
    }
}

