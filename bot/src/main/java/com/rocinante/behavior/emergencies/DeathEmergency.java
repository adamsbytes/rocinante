package com.rocinante.behavior.emergencies;

import com.rocinante.behavior.EmergencyCondition;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

/**
 * Emergency condition for detecting player death and Death's Office.
 * 
 * Triggers when:
 * - Player is in Death's Office region
 * - OR Death dialogue widget is open
 * - OR Death's Office interface is open
 * 
 * This handles both regular deaths and first-death tutorial.
 */
@Slf4j
public class DeathEmergency implements EmergencyCondition {

    /**
     * Death's Office region ID.
     * The "waiting room" area where Death explains mechanics.
     */
    private static final int DEATHS_OFFICE_REGION = 12106;

    /**
     * Death's Office interface ID (main interface).
     */
    private static final int DEATH_OFFICE_INTERFACE_ID = 669;

    /**
     * Death's Coffer interface ID.
     */
    private static final int DEATH_COFFER_INTERFACE_ID = 670;

    /**
     * NPC dialogue widget group (generic dialogue).
     */
    private static final int DIALOGUE_NPC_HEAD_GROUP = 231;

    /**
     * Click to continue widget group.
     */
    private static final int DIALOGUE_SPRITE_GROUP = 193;

    private final ResponseTaskFactory responseTaskFactory;

    public DeathEmergency(ResponseTaskFactory responseTaskFactory) {
        this.responseTaskFactory = responseTaskFactory;
    }

    @Override
    public boolean isTriggered(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if Death's Office interface is open
        Widget deathOfficeWidget = client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0);
        if (deathOfficeWidget != null && !deathOfficeWidget.isHidden()) {
            log.info("Death detected: Death's Office interface is open");
            return true;
        }

        // Check if Death's Coffer interface is open
        Widget cofferWidget = client.getWidget(DEATH_COFFER_INTERFACE_ID, 0);
        if (cofferWidget != null && !cofferWidget.isHidden()) {
            log.info("Death detected: Death's Coffer interface is open");
            return true;
        }

        // Check if we're in Death's Office region
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        if (playerPos != null) {
            int regionId = playerPos.getRegionID();
            if (regionId == DEATHS_OFFICE_REGION) {
                log.info("Death detected: Player is in Death's Office region ({})", regionId);
                return true;
            }
        }

        // Check for Death NPC dialogue (first death tutorial)
        if (isDeathDialogueOpen(client)) {
            log.info("Death detected: Death tutorial dialogue is open");
            return true;
        }

        return false;
    }

    /**
     * Check if Death's dialogue is open (NPC name = "Death").
     */
    private boolean isDeathDialogueOpen(Client client) {
        // Check NPC dialogue head widget (shows NPC name)
        Widget npcDialogue = client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 0);
        if (npcDialogue != null && !npcDialogue.isHidden()) {
            // Try to find the NPC name child
            Widget nameWidget = client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 4);
            if (nameWidget != null) {
                String npcName = nameWidget.getText();
                if (npcName != null && npcName.equalsIgnoreCase("Death")) {
                    return true;
                }
            }
        }

        // Also check sprite dialogue (some Death dialogues use this)
        Widget spriteDialogue = client.getWidget(DIALOGUE_SPRITE_GROUP, 0);
        if (spriteDialogue != null && !spriteDialogue.isHidden()) {
            // Check if any child contains "Death" text
            Widget[] children = spriteDialogue.getStaticChildren();
            if (children != null) {
                for (Widget child : children) {
                    String text = child.getText();
                    if (text != null && text.contains("Death")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @Override
    public Task createResponseTask(TaskContext ctx) {
        log.warn("Creating death recovery task");
        return responseTaskFactory.create(ctx);
    }

    @Override
    public String getDescription() {
        return "Player died and needs to recover items";
    }

    @Override
    public String getId() {
        return "DEATH_EMERGENCY";
    }

    @Override
    public long getCooldownMs() {
        return 30000; // 30 second cooldown (death handling takes time)
    }

    @Override
    public int getSeverity() {
        return 80; // High priority (but below immediate health emergencies)
    }

    /**
     * Factory interface for creating death recovery tasks.
     */
    @FunctionalInterface
    public interface ResponseTaskFactory {
        Task create(TaskContext ctx);
    }
}
