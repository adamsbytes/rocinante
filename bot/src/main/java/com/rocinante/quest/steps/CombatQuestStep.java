package com.rocinante.quest.steps;

import com.rocinante.state.StateCondition;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.InteractNpcTask;
import com.rocinante.tasks.impl.WaitForConditionTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * Quest step for engaging in combat.
 *
 * This step handles simple combat encounters like attacking rats on Tutorial Island.
 * For more complex combat (boss fights, prayer switching), use the full combat system.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Attack a rat for Tutorial Island
 * CombatQuestStep attackRat = new CombatQuestStep(NpcID.GIANT_RAT, "Kill the rat")
 *     .withWaitForDeath(true);
 *
 * // Attack with ranged
 * CombatQuestStep rangedAttack = new CombatQuestStep(NpcID.CHICKEN, "Kill the chicken with magic")
 *     .withAttackStyle(AttackStyle.MAGIC);
 * }</pre>
 */
@Getter
@Setter
@Accessors(chain = true)
public class CombatQuestStep extends QuestStep {

    /**
     * Attack styles for combat.
     */
    public enum AttackStyle {
        MELEE,
        RANGED,
        MAGIC
    }

    /**
     * The NPC ID to attack.
     */
    private final int npcId;

    /**
     * Optional NPC name filter.
     */
    private String npcName;

    /**
     * Attack style to use.
     */
    private AttackStyle attackStyle = AttackStyle.MELEE;

    /**
     * Whether to wait for the NPC to die.
     */
    private boolean waitForDeath = true;

    /**
     * Search radius for finding the target.
     */
    private int searchRadius = 15;

    /**
     * Whether to use special attack.
     */
    private boolean useSpecialAttack = false;

    /**
     * Maximum ticks to wait for death.
     */
    private int deathTimeoutTicks = 100;

    /**
     * Create a combat quest step.
     *
     * @param npcId the NPC ID to attack
     * @param text  instruction text
     */
    public CombatQuestStep(int npcId, String text) {
        super(text);
        this.npcId = npcId;
    }

    /**
     * Create a combat quest step with default text.
     *
     * @param npcId the NPC ID to attack
     */
    public CombatQuestStep(int npcId) {
        super("Attack NPC");
        this.npcId = npcId;
    }

    @Override
    public StepType getType() {
        return StepType.COMBAT;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        // Create attack task
        InteractNpcTask attackTask = new InteractNpcTask(npcId, "Attack")
                .withSearchRadius(searchRadius)
                .withDialogueExpected(false)
                .withWaitForIdle(false)
                .withDescription(getText());

        if (npcName != null) {
            attackTask.withNpcName(npcName);
        }

        tasks.add(attackTask);

        // Optionally wait for NPC death
        if (waitForDeath) {
            // Wait until player is no longer in combat (NPC died)
            WaitForConditionTask waitTask = new WaitForConditionTask(
                    new NpcDeathCondition(npcId)
            ).withDescription("Wait for NPC to die");

            tasks.add(waitTask);
        }

        return tasks;
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set NPC name filter (builder-style).
     *
     * @param name the NPC name
     * @return this step for chaining
     */
    public CombatQuestStep withNpcName(String name) {
        this.npcName = name;
        return this;
    }

    /**
     * Set attack style (builder-style).
     *
     * @param style the attack style
     * @return this step for chaining
     */
    public CombatQuestStep withAttackStyle(AttackStyle style) {
        this.attackStyle = style;
        return this;
    }

    /**
     * Set whether to wait for death (builder-style).
     *
     * @param wait true to wait for NPC death
     * @return this step for chaining
     */
    public CombatQuestStep withWaitForDeath(boolean wait) {
        this.waitForDeath = wait;
        return this;
    }

    /**
     * Set search radius (builder-style).
     *
     * @param radius the radius in tiles
     * @return this step for chaining
     */
    public CombatQuestStep withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set whether to use special attack (builder-style).
     *
     * @param useSpec true to use special attack
     * @return this step for chaining
     */
    public CombatQuestStep withSpecialAttack(boolean useSpec) {
        this.useSpecialAttack = useSpec;
        return this;
    }

    // ========================================================================
    // NPC Death Condition
    // ========================================================================

    /**
     * Condition that checks if combat is complete (player idle or NPC dead).
     */
    private static class NpcDeathCondition implements StateCondition {
        private final int npcId;
        private int ticksInCombat = 0;
        private int ticksIdle = 0;

        NpcDeathCondition(int npcId) {
            this.npcId = npcId;
        }

        @Override
        public boolean test(TaskContext ctx) {
            // Check if player is idle (not in combat, not animating)
            boolean playerIdle = !ctx.getPlayerState().isInCombat() 
                    && !ctx.getPlayerState().isAnimating()
                    && !ctx.getPlayerState().isInteracting();

            if (playerIdle) {
                ticksIdle++;
                // Wait a few ticks of being idle to confirm combat is over
                return ticksIdle >= 3;
            }

            ticksIdle = 0;
            ticksInCombat++;

            // Safety timeout - if we've been in combat for too long, assume it's done
            return ticksInCombat > 100;
        }

        @Override
        public String describe() {
            return "NpcDeath(" + npcId + ")";
        }
    }
}

