package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.TravelTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step for teleporting to a location.
 *
 * <p>This step generates a {@link TravelTask} configured for the specified teleport method.
 * Supports various teleport types:
 * <ul>
 *   <li>Spellbook teleports (Varrock, Lumbridge, etc.)</li>
 *   <li>Home teleport</li>
 *   <li>Teleport tablets</li>
 *   <li>Jewelry teleports (equipped or inventory)</li>
 *   <li>Fairy rings</li>
 *   <li>Spirit trees</li>
 *   <li>POH portals</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Teleport using a spell
 * TeleportQuestStep toVarrock = TeleportQuestStep.spell("Varrock Teleport", "Teleport to Varrock");
 *
 * // Home teleport
 * TeleportQuestStep homeTeleport = TeleportQuestStep.homeTeleport("Return to home location");
 *
 * // Use fairy ring
 * TeleportQuestStep fairyRing = TeleportQuestStep.fairyRing("AIQ", "Teleport to Mudskipper Point");
 *
 * // Use teleport tablet
 * TeleportQuestStep tablet = TeleportQuestStep.tablet(8007, "Use Varrock teleport tablet");
 * }</pre>
 */
@Getter
@Setter
@Accessors(chain = true)
public class TeleportQuestStep extends QuestStep {

    /**
     * The teleport method to use.
     */
    private TravelTask.TravelMethod method;

    /**
     * Spell name for spell teleports.
     */
    private String spellName;

    /**
     * Teleport tablet item ID.
     */
    private int tabletItemId = -1;

    /**
     * Jewelry item ID.
     */
    private int jewelryItemId = -1;

    /**
     * Teleport destination option (for jewelry, POH portals).
     */
    private String teleportOption;

    /**
     * Fairy ring code (e.g., "AIQ").
     */
    private String fairyRingCode;

    /**
     * Spirit tree destination name.
     */
    private String spiritTreeDestination;

    /**
     * Expected destination after teleport (for verification).
     */
    private WorldPoint expectedDestination;

    /**
     * Whether jewelry is equipped (vs in inventory).
     */
    private boolean jewelryEquipped = false;

    /**
     * Create a teleport quest step with text.
     *
     * @param text instruction text
     */
    public TeleportQuestStep(String text) {
        super(text);
    }

    /**
     * Create a teleport quest step with method and text.
     *
     * @param method teleport method
     * @param text   instruction text
     */
    public TeleportQuestStep(TravelTask.TravelMethod method, String text) {
        super(text);
        this.method = method;
    }

    @Override
    public StepType getType() {
        return StepType.CUSTOM; // Could add TELEPORT to StepType enum
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        TravelTask travelTask = buildTravelTask();
        if (travelTask != null) {
            tasks.add(travelTask);
        }

        return tasks;
    }

    /**
     * Build the appropriate TravelTask based on configuration.
     */
    private TravelTask buildTravelTask() {
        if (method == null) {
            // Try to infer from available data
            if (spellName != null) {
                method = TravelTask.TravelMethod.SPELL;
            } else if (tabletItemId > 0) {
                method = TravelTask.TravelMethod.TABLET;
            } else if (fairyRingCode != null) {
                method = TravelTask.TravelMethod.FAIRY_RING;
            } else if (spiritTreeDestination != null) {
                method = TravelTask.TravelMethod.SPIRIT_TREE;
            } else if (jewelryItemId > 0) {
                method = jewelryEquipped ? 
                        TravelTask.TravelMethod.JEWELRY_EQUIPPED : 
                        TravelTask.TravelMethod.JEWELRY_INVENTORY;
            } else {
                // Default to home teleport
                method = TravelTask.TravelMethod.HOME_TELEPORT;
            }
        }

        switch (method) {
            case SPELL:
                return TravelTask.spell(spellName);

            case HOME_TELEPORT:
                return TravelTask.homeTeleport();

            case TABLET:
                return TravelTask.tablet(tabletItemId);

            case JEWELRY_EQUIPPED:
                return TravelTask.jewelry(jewelryItemId, teleportOption);

            case JEWELRY_INVENTORY:
                return TravelTask.jewelryFromInventory(jewelryItemId, teleportOption);

            case FAIRY_RING:
                return TravelTask.fairyRing(fairyRingCode);

            case SPIRIT_TREE:
                return TravelTask.spiritTree(spiritTreeDestination);

            case POH_PORTAL:
                return TravelTask.pohPortal(teleportOption);

            default:
                return TravelTask.homeTeleport();
        }
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a spell teleport step.
     *
     * @param spellName the spell name (e.g., "Varrock Teleport")
     * @param text      instruction text
     * @return teleport step
     */
    public static TeleportQuestStep spell(String spellName, String text) {
        TeleportQuestStep step = new TeleportQuestStep(TravelTask.TravelMethod.SPELL, text);
        step.spellName = spellName;
        return step;
    }

    /**
     * Create a home teleport step.
     *
     * @param text instruction text
     * @return teleport step
     */
    public static TeleportQuestStep homeTeleport(String text) {
        return new TeleportQuestStep(TravelTask.TravelMethod.HOME_TELEPORT, text);
    }

    /**
     * Create a teleport tablet step.
     *
     * @param tabletItemId the tablet item ID
     * @param text         instruction text
     * @return teleport step
     */
    public static TeleportQuestStep tablet(int tabletItemId, String text) {
        TeleportQuestStep step = new TeleportQuestStep(TravelTask.TravelMethod.TABLET, text);
        step.tabletItemId = tabletItemId;
        return step;
    }

    /**
     * Create a fairy ring teleport step.
     *
     * @param code the fairy ring code (e.g., "AIQ")
     * @param text instruction text
     * @return teleport step
     */
    public static TeleportQuestStep fairyRing(String code, String text) {
        TeleportQuestStep step = new TeleportQuestStep(TravelTask.TravelMethod.FAIRY_RING, text);
        step.fairyRingCode = code;
        return step;
    }

    /**
     * Create a spirit tree teleport step.
     *
     * @param destination the destination name
     * @param text        instruction text
     * @return teleport step
     */
    public static TeleportQuestStep spiritTree(String destination, String text) {
        TeleportQuestStep step = new TeleportQuestStep(TravelTask.TravelMethod.SPIRIT_TREE, text);
        step.spiritTreeDestination = destination;
        return step;
    }

    /**
     * Create an equipped jewelry teleport step.
     *
     * @param jewelryItemId the jewelry item ID
     * @param destination   the teleport destination option
     * @param text          instruction text
     * @return teleport step
     */
    public static TeleportQuestStep jewelryEquipped(int jewelryItemId, String destination, String text) {
        TeleportQuestStep step = new TeleportQuestStep(TravelTask.TravelMethod.JEWELRY_EQUIPPED, text);
        step.jewelryItemId = jewelryItemId;
        step.teleportOption = destination;
        step.jewelryEquipped = true;
        return step;
    }

    /**
     * Create an inventory jewelry teleport step.
     *
     * @param jewelryItemId the jewelry item ID
     * @param destination   the teleport destination option
     * @param text          instruction text
     * @return teleport step
     */
    public static TeleportQuestStep jewelryInventory(int jewelryItemId, String destination, String text) {
        TeleportQuestStep step = new TeleportQuestStep(TravelTask.TravelMethod.JEWELRY_INVENTORY, text);
        step.jewelryItemId = jewelryItemId;
        step.teleportOption = destination;
        step.jewelryEquipped = false;
        return step;
    }

    /**
     * Create a POH portal teleport step.
     *
     * @param destination the portal destination
     * @param text        instruction text
     * @return teleport step
     */
    public static TeleportQuestStep pohPortal(String destination, String text) {
        TeleportQuestStep step = new TeleportQuestStep(TravelTask.TravelMethod.POH_PORTAL, text);
        step.teleportOption = destination;
        return step;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set the expected destination for verification.
     *
     * @param destination the expected destination WorldPoint
     * @return this step for chaining
     */
    public TeleportQuestStep withExpectedDestination(WorldPoint destination) {
        this.expectedDestination = destination;
        return this;
    }

    /**
     * Set the teleport option/destination name.
     *
     * @param option the teleport option
     * @return this step for chaining
     */
    public TeleportQuestStep withTeleportOption(String option) {
        this.teleportOption = option;
        return this;
    }
}

