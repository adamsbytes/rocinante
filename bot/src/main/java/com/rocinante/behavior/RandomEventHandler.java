package com.rocinante.behavior;

import com.rocinante.tasks.CompositeTask;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.tasks.impl.DialogueTask;
import com.rocinante.tasks.impl.InteractNpcTask;
import com.rocinante.tasks.impl.WaitForConditionTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles OSRS random events targeting the local player.
 *
 * Per REQUIREMENTS.md §13.6:
 * - Detect random event NPCs targeting the player
 * - Dismiss by default
 * - Engage Genie for lamp rewards (Prayer XP as configured)
 *
 * Detection is driven from InteractingChanged to react immediately when an
 * event NPC targets the player. Responses are queued as URGENT tasks to
 * pre-empt other automation.
 */
@Slf4j
@Singleton
public class RandomEventHandler {

    /**
     * Random event NPC IDs (RuneLite macro IDs).
     * Sourced from RuneLite RandomEventPlugin.
     */
    private static final Set<Integer> RANDOM_EVENT_NPC_IDS = Set.of(
            NpcID.MACRO_BEEKEEPER_INVITATION,
            NpcID.MACRO_COMBILOCK_PIRATE,
            NpcID.MACRO_JEKYLL, NpcID.MACRO_JEKYLL_UNDERWATER,
            NpcID.MACRO_DWARF,
            NpcID.PATTERN_INVITATION,
            NpcID.MACRO_EVIL_BOB_OUTSIDE, NpcID.MACRO_EVIL_BOB_PRISON,
            NpcID.PINBALL_INVITATION,
            NpcID.MACRO_FORESTER_INVITATION,
            NpcID.MACRO_FROG_CRIER,
            NpcID.MACRO_GENI, NpcID.MACRO_GENI_UNDERWATER,
            NpcID.MACRO_GILES, NpcID.MACRO_GILES_UNDERWATER,
            NpcID.MACRO_GRAVEDIGGER_INVITATION,
            NpcID.MACRO_MILES, NpcID.MACRO_MILES_UNDERWATER,
            NpcID.MACRO_MYSTERIOUS_OLD_MAN, NpcID.MACRO_MYSTERIOUS_OLD_MAN_UNDERWATER,
            NpcID.MACRO_MAZE_INVITATION, NpcID.MACRO_MIME_INVITATION,
            NpcID.MACRO_NILES, NpcID.MACRO_NILES_UNDERWATER,
            NpcID.MACRO_PILLORY_GUARD,
            NpcID.GRAB_POSTMAN,
            NpcID.MACRO_MAGNESON_INVITATION,
            NpcID.MACRO_HIGHWAYMAN, NpcID.MACRO_HIGHWAYMAN_UNDERWATER,
            NpcID.MACRO_SANDWICH_LADY_NPC,
            NpcID.MACRO_DRILLDEMON_INVITATION,
            NpcID.MACRO_COUNTCHECK_SURFACE, NpcID.MACRO_COUNTCHECK_UNDERWATER
    );

    private static final Set<Integer> GENIE_NPC_IDS = Set.of(NpcID.MACRO_GENI, NpcID.MACRO_GENI_UNDERWATER);

    private static final int RANDOM_EVENT_COOLDOWN_TICKS = 50;

    private final Client client;
    private final TaskExecutor taskExecutor;
    private final TaskContext taskContext;

    /**
     * Lamp skill selection (must be provided via env/system property).
     */
    @Getter
    private final Skill lampSkill;

    /**
     * Tracks last handled tick per NPC index to avoid duplicate queuing.
     */
    private final Map<Integer, Integer> lastHandledTickByIndex = new ConcurrentHashMap<>();

    @Inject
    public RandomEventHandler(Client client,
                              TaskExecutor taskExecutor,
                              TaskContext taskContext) {
        this.client = client;
        this.taskExecutor = taskExecutor;
        this.taskContext = taskContext;
        this.lampSkill = resolveLampSkill();
        log.info("RandomEventHandler initialized with lamp skill: {}", lampSkill);
    }

    // ========================================================================
    // Event Handling
    // ========================================================================

    @Subscribe
    public void onInteractingChanged(InteractingChanged event) {
        Actor source = event.getSource();
        Actor target = event.getTarget();

        if (!(source instanceof NPC) || target == null || target != client.getLocalPlayer()) {
            return;
        }

        NPC npc = (NPC) source;
        if (!RANDOM_EVENT_NPC_IDS.contains(npc.getId())) {
            return;
        }

        int tick = client.getTickCount();
        int npcIndex = npc.getIndex();

        Integer lastTick = lastHandledTickByIndex.get(npcIndex);
        if (lastTick != null && (tick - lastTick) < RANDOM_EVENT_COOLDOWN_TICKS) {
            log.debug("Random event already handled recently (npc idx={} id={}), skipping", npcIndex, npc.getId());
            return;
        }

        lastHandledTickByIndex.put(npcIndex, tick);

        if (GENIE_NPC_IDS.contains(npc.getId())) {
            handleGenie(npc);
        } else {
            handleDismiss(npc);
        }
    }

    // ========================================================================
    // Actions
    // ========================================================================

    private void handleDismiss(NPC npc) {
        InteractNpcTask dismiss = new InteractNpcTask(npc.getId(), "Dismiss")
                .withDescription("Dismiss random event")
                .withTrackMovement(false);

        taskExecutor.queueTask(dismiss, TaskPriority.URGENT);
        log.info("Queued dismiss for random event NPC {} ({})", npc.getName(), npc.getId());
    }

    private void handleGenie(NPC npc) {
        if (lampSkill == null) {
            log.error("LAMP_SKILL not configured; dismissing genie to avoid idle");
            handleDismiss(npc);
            return;
        }

        InteractNpcTask talk = new InteractNpcTask(npc.getId(), "Talk-to")
                .withDialogueExpected(true)
                .withDescription("Talk to Genie for lamp")
                .withTrackMovement(false);

        WaitForConditionTask waitForLamp = new WaitForConditionTask(ctx ->
                ctx.getInventoryState().hasItem(ItemID.MACRO_GENILAMP))
                .withDescription("Wait for Genie lamp")
                .withInactivityTimeout(33); // ~20 seconds

        Task rubLamp = new RubLampTask(taskContext);

        DialogueTask selectSkill = new DialogueTask()
                .withOptionText(toTitleCase(lampSkill.name()))
                .withDescription("Select lamp skill: " + toTitleCase(lampSkill.name()));
        selectSkill.setPriority(TaskPriority.URGENT);

        WaitForConditionTask waitConsumed = new WaitForConditionTask(ctx ->
                !ctx.getInventoryState().hasItem(ItemID.MACRO_GENILAMP))
                .withDescription("Wait for lamp to be consumed")
                .withInactivityTimeout(33); // ~20 seconds

        CompositeTask sequence = CompositeTask.sequential(
                talk,
                waitForLamp,
                rubLamp,
                selectSkill,
                waitConsumed
        );
        sequence.setPriority(TaskPriority.URGENT);
        sequence.setDescription("Genie lamp collection");

        taskExecutor.queueTask(sequence, TaskPriority.URGENT);
        log.info("Queued Genie handling sequence (lamp skill: {})", lampSkill);
    }

    // ========================================================================
    // Lamp Task
    // ========================================================================

    /**
     * Small task to rub lamp and select the configured skill.
     */
    private static class RubLampTask extends com.rocinante.tasks.AbstractTask {
        private final TaskContext ctx;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private CompletableFuture<Boolean> clickFuture;

        RubLampTask(TaskContext ctx) {
            this.ctx = ctx;
            this.priority = TaskPriority.URGENT;
        }

        @Override
        public String getDescription() {
            return "Rub Genie lamp";
        }

        @Override
        public boolean canExecute(TaskContext context) {
            return ctx.getInventoryClickHelper() != null;
        }

        @Override
        protected void executeImpl(TaskContext context) {
            if (started.compareAndSet(false, true)) {
                int slot = ctx.getInventoryState().getSlotOf(ItemID.MACRO_GENILAMP);
                if (slot < 0) {
                    fail("Genie lamp not found in inventory");
                    return;
                }

                clickFuture = ctx.getInventoryClickHelper()
                        .executeClick(slot, "Rub Genie lamp");
                log.debug("Clicked Genie lamp in slot {}", slot);
            }

            if (clickFuture != null && clickFuture.isDone()) {
                complete();
            }
        }
    }

    // ========================================================================
    // Config
    // ========================================================================

    private Skill resolveLampSkill() {
        String value = System.getProperty("rocinante.random.lampSkill");
        if (value == null || value.isBlank()) {
            value = System.getenv("LAMP_SKILL");
        }
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Skill.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.error("Invalid LAMP_SKILL '{}'. Expected Skill enum name (e.g., PRAYER).", value);
            return null;
        }
    }

    private static String toTitleCase(String value) {
        String lower = value.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}

