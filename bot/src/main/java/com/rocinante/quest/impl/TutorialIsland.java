package com.rocinante.quest.impl;

import com.rocinante.quest.Quest;
import com.rocinante.quest.conditions.VarbitCondition;
import com.rocinante.quest.steps.*;
import com.rocinante.state.Conditions;
import com.rocinante.state.StateCondition;

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

        // var 3: Open settings menu
        steps.put(3, WidgetQuestStep.openSettings("Open the settings menu"));

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

        // var 30: Open inventory
        steps.put(30, WidgetQuestStep.openInventory("Open your inventory"));

        // var 40: Fish at fishing spot
        steps.put(40, new NpcQuestStep(NPC_FISHING_SPOT, "Net fish at the fishing spot")
                .withMenuAction("Net")
                .withDialogueExpected(false));

        // var 50: Open skills tab
        steps.put(50, WidgetQuestStep.openSkills("Open the skills tab"));

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

        // var 230: Open quest tab
        steps.put(230, WidgetQuestStep.openQuests("Open the quest journal"));

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

        // var 390: Open equipment tab
        steps.put(390, WidgetQuestStep.openEquipment("Open the equipment tab"));

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

        // var 430: Open combat styles tab
        steps.put(430, WidgetQuestStep.openCombatStyles("Open the combat styles tab"));

        // var 440: Enter rat cage
        steps.put(440, new ObjectQuestStep(OBJECT_RAT_GATE, "Open", "Enter the rat cage"));

        // var 450: Attack rat (melee)
        steps.put(450, new CombatQuestStep(NPC_GIANT_RAT, "Attack the giant rat")
                .withWaitForDeath(true));

        // var 460: Wait for rat to die (handled by combat step)
        steps.put(460, new CombatQuestStep(NPC_GIANT_RAT, "Kill the giant rat")
                .withWaitForDeath(true));

        // var 470: Exit rat cage and talk to Combat Instructor
        steps.put(470, new NpcQuestStep(NPC_COMBAT_INSTRUCTOR, "Talk to the Combat Instructor")
                .withDialogueExpected(true));

        // var 480: Equip bow and arrows, attack rat with ranged
        steps.put(480, createRangedCombatStep());

        // var 490: Wait for rat to die
        steps.put(490, new CombatQuestStep(NPC_GIANT_RAT, "Kill the rat with ranged")
                .withWaitForDeath(true)
                .withAttackStyle(CombatQuestStep.AttackStyle.RANGED));

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

        // var 531: Open account management tab
        steps.put(531, WidgetQuestStep.openAccountManagement("Open the account management tab"));

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

        // var 560: Open prayer tab
        steps.put(560, WidgetQuestStep.openPrayer("Open the prayer tab"));

        // var 570: Talk to Brother Brace again
        steps.put(570, new NpcQuestStep(NPC_BROTHER_BRACE, "Talk to Brother Brace again")
                .withDialogueExpected(true));

        // var 580: Open friends list
        steps.put(580, WidgetQuestStep.openFriendsList("Open your friends list"));

        // var 600: Talk to Brother Brace again
        steps.put(600, new NpcQuestStep(NPC_BROTHER_BRACE, "Talk to Brother Brace")
                .withDialogueExpected(true));

        // var 610: Exit chapel
        steps.put(610, new ObjectQuestStep(OBJECT_PRAYER_EXIT, "Open", "Exit the chapel"));

        // ====================================================================
        // Section 9: Magic Instructor (var 620-1000)
        // ====================================================================

        // var 620: Talk to Magic Instructor
        steps.put(620, new NpcQuestStep(NPC_MAGIC_INSTRUCTOR, "Talk to the Magic Instructor")
                .withDialogueExpected(true));

        // var 630: Open spellbook
        steps.put(630, WidgetQuestStep.openSpellbook("Open your spellbook"));

        // var 640: Talk to Magic Instructor again
        steps.put(640, new NpcQuestStep(NPC_MAGIC_INSTRUCTOR, "Talk to the Magic Instructor again")
                .withDialogueExpected(true));

        // var 650: Kill chicken with Wind Strike
        steps.put(650, createMagicCombatStep());

        // var 670: Talk to Magic Instructor to teleport off island
        steps.put(670, new NpcQuestStep(NPC_MAGIC_INSTRUCTOR, "Talk to the Magic Instructor to leave")
                .withDialogueExpected(true)
                .withDialogueOptions("Yes", "No, I'm not ready"));

        return steps;
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
        // Note: Fire object ID may vary, this is a simplified version
        return new ItemQuestStep(ITEM_RAW_SHRIMP, "Cook the shrimp on the fire")
                .withAction(ItemQuestStep.ItemAction.USE);
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
     */
    private QuestStep createEquipSwordShieldStep() {
        // This is a conditional step - equip both items
        ConditionalQuestStep equipStep = new ConditionalQuestStep("Equip the bronze sword and wooden shield");

        // If don't have sword equipped, equip it
        equipStep.when(
                Conditions.hasEquipped(ITEM_BRONZE_SWORD).not(),
                ItemQuestStep.equip(ITEM_BRONZE_SWORD, "Equip the bronze sword")
        );

        // If don't have shield equipped, equip it
        equipStep.when(
                Conditions.hasEquipped(ITEM_WOODEN_SHIELD).not(),
                ItemQuestStep.equip(ITEM_WOODEN_SHIELD, "Equip the wooden shield")
        );

        return equipStep;
    }

    /**
     * Create the step for ranged combat (equip bow + arrows, attack rat).
     */
    private QuestStep createRangedCombatStep() {
        ConditionalQuestStep rangedStep = new ConditionalQuestStep("Equip bow and arrows, attack the rat");

        // Equip shortbow if not equipped
        rangedStep.when(
                Conditions.hasEquipped(ITEM_SHORTBOW).not(),
                ItemQuestStep.equip(ITEM_SHORTBOW, "Equip the shortbow")
        );

        // Equip arrows if not equipped
        rangedStep.when(
                Conditions.hasEquipped(ITEM_BRONZE_ARROW).not(),
                ItemQuestStep.equip(ITEM_BRONZE_ARROW, "Equip the bronze arrows")
        );

        // Attack rat
        rangedStep.otherwise(
                new CombatQuestStep(NPC_GIANT_RAT, "Attack the rat with ranged")
                        .withAttackStyle(CombatQuestStep.AttackStyle.RANGED)
        );

        return rangedStep;
    }

    /**
     * Create the step for magic combat (cast Wind Strike on chicken).
     */
    private QuestStep createMagicCombatStep() {
        // Select Wind Strike spell and cast on chicken
        // This would typically involve selecting the spell, then clicking the NPC
        return new CombatQuestStep(NPC_CHICKEN, "Cast Wind Strike on the chicken")
                .withAttackStyle(CombatQuestStep.AttackStyle.MAGIC)
                .withWaitForDeath(true);
    }
}

