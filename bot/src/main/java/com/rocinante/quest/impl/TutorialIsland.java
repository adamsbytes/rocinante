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
import com.rocinante.tasks.impl.DialogueTask;
import com.rocinante.tasks.impl.IronmanSelectionTask;

import javax.annotation.Nullable;
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
    // Constants - NPC IDs
    // ========================================================================

    // Using original IDs - game client reports these, not the TUT2_ variants
    public static final int NPC_GIELINOR_GUIDE = 3308;
    public static final int NPC_SURVIVAL_EXPERT = 8503;
    public static final int NPC_MASTER_CHEF = 3305;
    public static final int NPC_QUEST_GUIDE = 3312;
    public static final int NPC_MINING_INSTRUCTOR = 3311;
    public static final int NPC_COMBAT_INSTRUCTOR = 3307;
    public static final int NPC_ACCOUNT_GUIDE = 3310;
    public static final int NPC_BROTHER_BRACE = 3319;
    public static final int NPC_IRONMAN_TUTOR = 9486;  // Paul - between Prayer and Magic
    public static final int NPC_MAGIC_INSTRUCTOR = 3309;
    public static final int NPC_GIANT_RAT = 3313;
    public static final int NPC_CHICKEN = 3316;
    public static final int NPC_FISHING_SPOT = 3317;

    // ========================================================================
    // Constants - Object IDs
    // ========================================================================

    public static final int OBJECT_TREE = 37965;
    public static final int OBJECT_OAK = 37969;
    public static final int OBJECT_TIN_ROCK = 37945;
    public static final int OBJECT_COPPER_ROCK = 37944;
    public static final int OBJECT_FURNACE = 37947;
    public static final int OBJECT_ANVIL = 2097;
    public static final int OBJECT_RANGE = 37728;
    public static final int OBJECT_BANK_BOOTH = 37959;
    public static final int OBJECT_POLL_BOOTH = 26815;
    public static final int OBJECT_FIRE = 3769;

    // Doors
    public static final int OBJECT_GUIDE_DOOR = 9398;
    public static final int OBJECT_SURVIVAL_GATE = 30165;
    public static final int OBJECT_CHEF_DOOR_ENTER = 9709;
    public static final int OBJECT_CHEF_DOOR_EXIT = 9710;
    public static final int OBJECT_QUEST_DOOR = 30167;
    public static final int OBJECT_QUEST_LADDER = 37942;
    public static final int OBJECT_MINING_LADDER = 37943;
    public static final int OBJECT_MINING_EXIT = 37948;
    public static final int OBJECT_COMBAT_GATE = 37953;
    public static final int OBJECT_COMBAT_LADDER = 37955;
    public static final int OBJECT_RAT_GATE = 9721;
    public static final int OBJECT_COMBAT_EXIT = 9722;
    public static final int OBJECT_BANK_LADDER = 37956;
    public static final int OBJECT_BANK_EXIT = 37961;
    public static final int OBJECT_PRAYER_DOOR = 37962;
    public static final int OBJECT_PRAYER_EXIT = 37963;

    // ========================================================================
    // Constants - Item IDs
    // ========================================================================

    public static final int ITEM_BRONZE_AXE = 1351;
    public static final int ITEM_BRONZE_PICKAXE = 1265;
    public static final int ITEM_TINDERBOX = 590;
    public static final int ITEM_NET = 303;
    public static final int ITEM_RAW_SHRIMP = 317;
    public static final int ITEM_SHRIMP = 315;
    public static final int ITEM_LOGS = 1511;
    public static final int ITEM_BREAD_DOUGH = 2307;
    public static final int ITEM_BREAD = 2309;
    public static final int ITEM_POT_OF_FLOUR = 1933;
    public static final int ITEM_BUCKET_OF_WATER = 1929;
    public static final int ITEM_TIN_ORE = 438;
    public static final int ITEM_COPPER_ORE = 436;
    public static final int ITEM_BRONZE_BAR = 2349;
    public static final int ITEM_BRONZE_DAGGER = 1205;
    public static final int ITEM_BRONZE_SWORD = 1277;
    public static final int ITEM_WOODEN_SHIELD = 1171;
    public static final int ITEM_SHORTBOW = 841;
    public static final int ITEM_BRONZE_ARROW = 882;
    public static final int ITEM_AIR_RUNE = 556;
    public static final int ITEM_MIND_RUNE = 558;

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
        steps.put(2, new NpcQuestStep(NPC_GIELINOR_GUIDE, "Talk to the Gielinor Guide")
                .withMenuAction("Talk-to")
                .withDialogueExpected(true));

        // var 3: Open settings menu (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(3, WidgetQuestStep.openSettingsByClick("Open the settings menu"));

        // var 7: Talk to Gielinor Guide after opening settings
        steps.put(7, new NpcQuestStep(NPC_GIELINOR_GUIDE, "Talk to the Gielinor Guide again")
                .withDialogueExpected(true));

        // var 10: Exit Gielinor Guide's house
        steps.put(10, new ObjectQuestStep(OBJECT_GUIDE_DOOR, "Open", "Open the door to exit"));

        // ====================================================================
        // Section 2: Survival Expert (var 20-120)
        // ====================================================================

        // var 20: Talk to Survival Expert
        steps.put(20, new NpcQuestStep(NPC_SURVIVAL_EXPERT, "Talk to the Survival Expert")
                .withDialogueExpected(true));

        // var 30: Open inventory (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(30, WidgetQuestStep.openInventoryByClick("Open your inventory"));

        // var 40: Fish at fishing spot
        steps.put(40, new NpcQuestStep(NPC_FISHING_SPOT, "Net fish at the fishing spot")
                .withMenuAction("Net")
                .withDialogueExpected(false));

        // var 50: Open skills tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(50, WidgetQuestStep.openSkillsByClick("Open the skills tab"));

        // var 60: Talk to Survival Expert again
        steps.put(60, new NpcQuestStep(NPC_SURVIVAL_EXPERT, "Talk to the Survival Expert again")
                .withDialogueExpected(true));

        // var 70: Cut tree
        steps.put(70, new ObjectQuestStep(OBJECT_TREE, "Chop down", "Cut down a tree"));

        // var 80: Make fire
        steps.put(80, createMakeFireStep());

        // var 90: Cook raw shrimp
        steps.put(90, createCookShrimpStep());

        // var 120: Exit Survival Expert area
        steps.put(120, new ObjectQuestStep(OBJECT_SURVIVAL_GATE, "Open", "Go through the gate"));

        // ====================================================================
        // Section 3: Master Chef (var 130-170)
        // ====================================================================

        // var 130: Enter Chef's house
        steps.put(130, new ObjectQuestStep(OBJECT_CHEF_DOOR_ENTER, "Open", "Enter the cook's building"));

        // var 140: Talk to Master Chef
        steps.put(140, new NpcQuestStep(NPC_MASTER_CHEF, "Talk to the Master Chef")
                .withDialogueExpected(true));

        // var 150: Make bread dough
        steps.put(150, createMakeDoughStep());

        // var 160: Cook bread
        steps.put(160, ItemQuestStep.useOn(ITEM_BREAD_DOUGH, OBJECT_RANGE, "Cook the bread dough"));

        // var 170: Exit Chef's house
        steps.put(170, new ObjectQuestStep(OBJECT_CHEF_DOOR_EXIT, "Open", "Exit the cook's building"));

        // ====================================================================
        // Section 4: Quest Guide (var 200-250)
        // ====================================================================

        // var 200: Enter Quest Guide's building
        steps.put(200, new ObjectQuestStep(OBJECT_QUEST_DOOR, "Open", "Enter the quest guide's building"));

        // var 220: Talk to Quest Guide
        steps.put(220, new NpcQuestStep(NPC_QUEST_GUIDE, "Talk to the Quest Guide")
                .withDialogueExpected(true));

        // var 230: Open quest tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(230, WidgetQuestStep.openQuestsByClick("Open the quest journal"));

        // var 240: Talk to Quest Guide again
        steps.put(240, new NpcQuestStep(NPC_QUEST_GUIDE, "Talk to the Quest Guide again")
                .withDialogueExpected(true));

        // var 250: Go down ladder
        steps.put(250, new ObjectQuestStep(OBJECT_QUEST_LADDER, "Climb-down", "Climb down the ladder"));

        // ====================================================================
        // Section 5: Mining Instructor (var 260-360)
        // ====================================================================

        // var 260: Talk to Mining Instructor
        steps.put(260, new NpcQuestStep(NPC_MINING_INSTRUCTOR, "Talk to the Mining Instructor")
                .withDialogueExpected(true));

        // var 300: Mine tin
        steps.put(300, new ObjectQuestStep(OBJECT_TIN_ROCK, "Mine", "Mine some tin ore"));

        // var 310: Mine copper
        steps.put(310, new ObjectQuestStep(OBJECT_COPPER_ROCK, "Mine", "Mine some copper ore"));

        // var 320: Smelt bronze bar
        steps.put(320, new ObjectQuestStep(OBJECT_FURNACE, "Smelt", "Smelt a bronze bar"));

        // var 330: Talk to Mining Instructor
        steps.put(330, new NpcQuestStep(NPC_MINING_INSTRUCTOR, "Talk to the Mining Instructor")
                .withDialogueExpected(true));

        // var 340: Click anvil
        steps.put(340, new ObjectQuestStep(OBJECT_ANVIL, "Smith", "Click on the anvil"));

        // var 350: Smith bronze dagger
        steps.put(350, createSmithDaggerStep());

        // var 360: Enter combat area
        steps.put(360, new ObjectQuestStep(OBJECT_MINING_EXIT, "Open", "Go through the gate"));

        // ====================================================================
        // Section 6: Combat Instructor (var 370-500)
        // ====================================================================

        // var 370: Talk to Combat Instructor
        steps.put(370, new NpcQuestStep(NPC_COMBAT_INSTRUCTOR, "Talk to the Combat Instructor")
                .withDialogueExpected(true));

        // var 390: Open equipment tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(390, WidgetQuestStep.openEquipmentByClick("Open the equipment tab"));

        // var 400: Open equipment stats
        steps.put(400, new WidgetQuestStep(387, 17, "View equipment stats")
                .withAction(WidgetQuestStep.WidgetAction.CLICK));

        // var 405: Equip bronze dagger
        steps.put(405, ItemQuestStep.equip(ITEM_BRONZE_DAGGER, "Equip the bronze dagger"));

        // var 410: Talk to Combat Instructor
        steps.put(410, new NpcQuestStep(NPC_COMBAT_INSTRUCTOR, "Talk to the Combat Instructor again")
                .withDialogueExpected(true));

        // var 420: Equip sword and shield
        steps.put(420, createEquipSwordShieldStep());

        // var 430: Open combat styles tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(430, WidgetQuestStep.openCombatStylesByClick("Open the combat styles tab"));

        // var 440: Enter rat cage
        steps.put(440, new ObjectQuestStep(OBJECT_RAT_GATE, "Open", "Enter the rat cage"));

        // var 450: Attack rat (melee)
        steps.put(450, new CombatQuestStep(NPC_GIANT_RAT, "Attack the giant rat")
                .withAttackStyle(AttackStyle.MELEE));

        // var 460: Wait for rat to die - combat initiated by step 450 handles the fight
        steps.put(460, new WaitQuestStep("Waiting for the giant rat to die"));

        // var 470: Exit rat cage and talk to Combat Instructor
        steps.put(470, new NpcQuestStep(NPC_COMBAT_INSTRUCTOR, "Talk to the Combat Instructor")
                .withDialogueExpected(true));

        // var 480: Equip bow and arrows, attack rat with ranged
        steps.put(480, new CombatQuestStep(NPC_GIANT_RAT, "Equip bow and arrows, attack the rat")
                .withAttackStyle(AttackStyle.RANGED));

        // var 490: Wait for rat to die - combat initiated by step 480 handles the fight
        steps.put(490, new WaitQuestStep("Waiting for the rat to die"));

        // var 500: Exit combat area
        steps.put(500, new ObjectQuestStep(OBJECT_COMBAT_LADDER, "Climb-up", "Climb the ladder"));

        // ====================================================================
        // Section 7: Account Guide / Bank (var 510-540)
        // ====================================================================

        // var 510: Open bank
        steps.put(510, new ObjectQuestStep(OBJECT_BANK_BOOTH, "Use", "Use the bank booth"));

        // var 520: Close bank and open poll booth
        steps.put(520, new ObjectQuestStep(OBJECT_POLL_BOOTH, "Use", "Use the poll booth"));

        // var 530: Talk to Account Guide
        steps.put(530, new NpcQuestStep(NPC_ACCOUNT_GUIDE, "Talk to the Account Guide")
                .withDialogueExpected(true));

        // var 531: Open account management tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(531, WidgetQuestStep.openAccountManagementByClick("Open the account management tab"));

        // var 532: Talk to Account Guide again
        steps.put(532, new NpcQuestStep(NPC_ACCOUNT_GUIDE, "Talk to the Account Guide again")
                .withDialogueExpected(true));

        // var 540: Exit account guide's room
        steps.put(540, new ObjectQuestStep(OBJECT_BANK_EXIT, "Open", "Exit through the door"));

        // ====================================================================
        // Section 8: Brother Brace / Prayer (var 550-610)
        // ====================================================================

        // var 550: Talk to Brother Brace
        steps.put(550, new NpcQuestStep(NPC_BROTHER_BRACE, "Talk to Brother Brace")
                .withDialogueExpected(true));

        // var 560: Open prayer tab (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(560, WidgetQuestStep.openPrayerByClick("Open the prayer tab"));

        // var 570: Talk to Brother Brace again
        steps.put(570, new NpcQuestStep(NPC_BROTHER_BRACE, "Talk to Brother Brace again")
                .withDialogueExpected(true));

        // var 580: Open friends list (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(580, WidgetQuestStep.openFriendsListByClick("Open your friends list"));

        // var 600: Talk to Brother Brace again
        steps.put(600, new NpcQuestStep(NPC_BROTHER_BRACE, "Talk to Brother Brace")
                .withDialogueExpected(true));

        // var 610: Exit chapel
        steps.put(610, new ObjectQuestStep(OBJECT_PRAYER_EXIT, "Open", "Exit the chapel"));

        // ====================================================================
        // Section 8.5: Paul - Ironman Tutor (Optional - var 611-619)
        // ====================================================================
        // Paul appears on the path between Prayer chapel and Magic instructor.
        // Only talk to him if ironman mode is desired.
        //
        // Dialogue flow (from OSRS Wiki):
        // 1. Talk to Paul → "Hello, [player name]. I'm Paul, the Ironman tutor. What can I do for you?"
        // 2. Select an Option → "Tell me about Ironman"
        // 3. Ironman tutor: *tons of info about ironman mode*
        // 4. Select an Option → "I'd like to change my Ironman mode"
        // 5. (Ironman interface opens - group 890)
        // 6. Click appropriate button (Standard/Hardcore/Ultimate)
        // 7. Interface closes and varbit 1777 updates
        
        if (shouldSelectIronmanMode()) {
            // var 611: Talk to Paul
            steps.put(611, new NpcQuestStep(NPC_IRONMAN_TUTOR, "Talk to Paul, the Ironman tutor")
                    .withDialogueExpected(true));
            
            // var 612: Select "Tell me about Ironman"
            steps.put(612, new DialogueQuestStep("Select: Tell me about Ironman")
                    .withOptionText("Tell me about Ironman"));
            
            // var 613: Paul explains ironman mode (click through)
            steps.put(613, new DialogueQuestStep("Listen to Paul explain Ironman mode")
                    .withClickThrough(true));
            
            // var 614: Select "I'd like to change my Ironman mode"
            steps.put(614, new DialogueQuestStep("Select: I'd like to change my Ironman mode")
                    .withOptionText("change my Ironman mode"));
            
            // var 615: Select ironman type in interface (890)
            steps.put(615, new CustomQuestStep("Select ironman type in interface", 
                    ctx -> new IronmanSelectionTask(ironmanState)));
        }

        // ====================================================================
        // Section 9: Magic Instructor (var 620-1000)
        // ====================================================================

        // var 620: Talk to Magic Instructor
        steps.put(620, new NpcQuestStep(NPC_MAGIC_INSTRUCTOR, "Talk to the Magic Instructor")
                .withDialogueExpected(true));

        // var 630: Open spellbook (Tutorial Island teaches clicking tabs, not hotkeys)
        steps.put(630, WidgetQuestStep.openSpellbookByClick("Open your spellbook"));

        // var 640: Talk to Magic Instructor again
        steps.put(640, new NpcQuestStep(NPC_MAGIC_INSTRUCTOR, "Talk to the Magic Instructor again")
                .withDialogueExpected(true));

        // var 650: Kill chicken with Wind Strike
        steps.put(650, new CombatQuestStep(NPC_CHICKEN, "Cast Wind Strike on the chicken")
                .withSpells(StandardSpell.WIND_STRIKE)
                .withAutocast(false));

        // var 670: Talk to Magic Instructor to teleport off island
        steps.put(670, new NpcQuestStep(NPC_MAGIC_INSTRUCTOR, "Talk to the Magic Instructor to leave")
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
     */
    private QuestStep createMakeFireStep() {
        // Use tinderbox on logs in inventory
        return ItemQuestStep.useOnItem(ITEM_TINDERBOX, ITEM_LOGS, "Light a fire with the tinderbox and logs");
    }

    /**
     * Create the step for cooking shrimp.
     */
    private QuestStep createCookShrimpStep() {
        // Use raw shrimp on fire
        return ItemQuestStep.useOn(ITEM_RAW_SHRIMP, OBJECT_FIRE, "Cook the shrimp on the fire");
    }

    /**
     * Create the step for making bread dough.
     */
    private QuestStep createMakeDoughStep() {
        // Use bucket of water on pot of flour
        return ItemQuestStep.useOnItem(ITEM_BUCKET_OF_WATER, ITEM_POT_OF_FLOUR, "Make bread dough");
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
     * Create the step for equipping sword and shield.
     * Uses a CompositeQuestStep to ensure both items are equipped sequentially.
     * Each EquipItemTask handles the case where the item is already equipped.
     */
    private QuestStep createEquipSwordShieldStep() {
        ConditionalQuestStep.CompositeQuestStep equipStep =
                new ConditionalQuestStep.CompositeQuestStep("Equip the bronze sword and wooden shield");

        equipStep.addStep(ItemQuestStep.equip(ITEM_BRONZE_SWORD, "Equip the bronze sword"));
        equipStep.addStep(ItemQuestStep.equip(ITEM_WOODEN_SHIELD, "Equip the wooden shield"));

        return equipStep;
    }
}

