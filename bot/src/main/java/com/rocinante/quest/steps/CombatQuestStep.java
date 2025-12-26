package com.rocinante.quest.steps;

import com.rocinante.combat.AttackStyle;
import com.rocinante.combat.CombatConfig;
import com.rocinante.combat.CombatManager;
import com.rocinante.combat.GearSet;
import com.rocinante.combat.SelectionPriority;
import com.rocinante.combat.TargetSelector;
import com.rocinante.combat.TargetSelectorConfig;
import com.rocinante.combat.WeaponStyle;
import com.rocinante.combat.XpGoal;
import com.rocinante.combat.spell.CombatSpell;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.CombatTask;
import com.rocinante.tasks.impl.CombatTaskConfig;
import com.rocinante.tasks.impl.EquipItemTask;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Quest step for engaging in combat.
 *
 * Uses CombatTask internally, which provides:
 * <ul>
 *   <li>Intelligent target selection via TargetSelector</li>
 *   <li>Integration with CombatManager for eating, prayer, special attacks</li>
 *   <li>Safe-spotting support</li>
 *   <li>Loot collection</li>
 *   <li>Kill count tracking</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Kill one rat (Tutorial Island)
 * CombatQuestStep attackRat = new CombatQuestStep(NpcID.GIANT_RAT, "Kill the rat");
 *
 * // Kill 10 goblins with looting
 * CombatQuestStep killGoblins = new CombatQuestStep(NpcID.GOBLIN, "Kill 10 goblins")
 *     .withKillCount(10)
 *     .withLootEnabled(true);
 *
 * // Boss fight with eating enabled
 * CombatQuestStep demonSlayer = new CombatQuestStep(NpcID.DELRITH, "Defeat Delrith")
 *     .withEatEnabled(true)
 *     .withUseSpecialAttack(true);
 *
 * // Safe-spot combat with ranged
 * CombatQuestStep rangedKill = new CombatQuestStep(NpcID.MOSS_GIANT, "Kill Moss Giant")
 *     .withSafeSpot(new WorldPoint(2676, 9561, 0))
 *     .withAttackRange(7);
 * }</pre>
 */
@Slf4j
@Getter
@Setter
@Accessors(chain = true)
public class CombatQuestStep extends QuestStep {

    /**
     * The NPC ID to attack.
     */
    private final int npcId;

    /**
     * Optional NPC name filter (for NPCs with same ID but different names).
     */
    private String npcName;

    /**
     * Number of NPCs to kill (default: 1).
     */
    private int killCount = 1;

    /**
     * Search radius for finding the target (default: 15 tiles).
     */
    private int searchRadius = 15;

    /**
     * Whether to use special attack.
     */
    private boolean useSpecialAttack = false;

    /**
     * Whether eating is enabled during combat.
     */
    private boolean eatEnabled = false;

    /**
     * Whether protection prayers should be used.
     */
    private boolean usePrayer = false;

    /**
     * Whether to loot drops after kills.
     */
    private boolean lootEnabled = false;

    /**
     * Minimum loot value to pick up (if looting enabled).
     */
    private int lootMinValue = 1000;

    /**
     * Safe-spot position for ranged/magic combat.
     */
    private WorldPoint safeSpotPosition;

    /**
     * Attack range (1 = melee, 7+ = ranged/magic).
     */
    private int attackRange = 1;

    /**
     * Maximum combat duration before giving up.
     */
    private Duration maxDuration = Duration.ofMinutes(10);

    /**
     * Attack style to use (auto-equips appropriate gear from inventory).
     */
    private AttackStyle attackStyle;

    /**
     * Specific gear set to equip before combat (takes precedence over attackStyle).
     */
    private GearSet gearSet;

    /**
     * Spells to use for magic combat.
     * If specified, sets attackStyle to MAGIC automatically and configures CombatTask for spellcasting.
     */
    private List<CombatSpell> spells;

    /**
     * Whether to use autocast for the first spell (if autocastable).
     */
    private boolean useAutocast = true;

    /**
     * Spell cycle mode when multiple spells are configured.
     */
    private CombatTaskConfig.SpellCycleMode spellCycleMode = CombatTaskConfig.SpellCycleMode.IN_ORDER;

    /**
     * Required weapon attack style (Slash, Stab, Crush).
     * Takes precedence over XP goal if they conflict.
     */
    private WeaponStyle weaponStyle = WeaponStyle.ANY;

    /**
     * Desired XP training goal.
     * Must be compatible with weaponStyle.
     */
    private XpGoal xpGoal = XpGoal.ANY;

    /**
     * Create a combat quest step for a single kill.
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
        TargetSelector targetSelector = ctx.getTargetSelector();
        CombatManager combatManager = ctx.getCombatManager();

        if (targetSelector == null || combatManager == null) {
            log.error("CombatQuestStep requires TargetSelector and CombatManager in TaskContext");
            throw new IllegalStateException("Combat system not available in TaskContext");
        }

        List<Task> tasks = new ArrayList<>();

        // Add equipment task if attack style or gear set specified
        if (gearSet != null) {
            // Use explicit gear set (takes precedence)
            log.debug("Adding EquipItemTask for gear set: {}", gearSet.getName());
            tasks.add(new EquipItemTask(gearSet)
                    .withDescription("Equip " + gearSet.getName() + " for combat"));
            
            // Auto-set attack range from gear set if not explicitly set
            if (attackRange == 1 && gearSet.getAttackStyle() != null) {
                attackRange = gearSet.getAttackStyle().getDefaultRange();
            }
        } else if (attackStyle != null) {
            // Use attack style auto-detection
            log.debug("Adding EquipItemTask for attack style: {}", attackStyle);
            tasks.add(new EquipItemTask(attackStyle)
                    .withDescription("Equip gear for " + attackStyle.name().toLowerCase() + " combat"));
            
            // Auto-set attack range from attack style if not explicitly set
            if (attackRange == 1) {
                attackRange = attackStyle.getDefaultRange();
            }
        }

        // Build target selector config
        TargetSelectorConfig.TargetSelectorConfigBuilder targetConfigBuilder = TargetSelectorConfig.builder()
                .priority(SelectionPriority.TARGETING_PLAYER)
                .priority(SelectionPriority.SPECIFIC_ID)
                .priority(SelectionPriority.NEAREST)
                .targetNpcId(npcId)
                .searchRadius(searchRadius)
                .skipInCombatWithOthers(true)
                .skipUnreachable(true)
                .skipDead(true);

        TargetSelectorConfig targetConfig = targetConfigBuilder.build();

        // Build combat task config
        CombatTaskConfig.CombatTaskConfigBuilder configBuilder = CombatTaskConfig.builder()
                .targetConfig(targetConfig)
                .killCount(killCount)
                .maxDuration(maxDuration)
                .lootEnabled(lootEnabled)
                .lootMinValue(lootMinValue)
                .stopWhenOutOfFood(eatEnabled) // Only stop for food if we care about eating
                .stopWhenLowResources(false);

        // Safe-spotting
        if (safeSpotPosition != null) {
            configBuilder
                    .useSafeSpot(true)
                    .safeSpotPosition(safeSpotPosition)
                    .attackRange(attackRange);
        } else if (attackRange > 1) {
            // Set attack range even without safe-spotting (for ranged/magic)
            configBuilder.attackRange(attackRange);
        }

        // Magic combat / spell configuration
        if (spells != null && !spells.isEmpty()) {
            log.debug("Configuring magic combat with {} spell(s)", spells.size());
            for (CombatSpell spell : spells) {
                configBuilder.spell(spell);
            }
            configBuilder.useAutocast(useAutocast);
            configBuilder.spellCycleMode(spellCycleMode);
            
            // Auto-set attack range for magic if not explicitly set
            if (attackRange == 1) {
                configBuilder.attackRange(10); // Default magic range
            }
        }

        // Weapon style and XP goal configuration
        if (weaponStyle != WeaponStyle.ANY) {
            configBuilder.weaponStyle(weaponStyle);
        }
        if (xpGoal != XpGoal.ANY) {
            configBuilder.xpGoal(xpGoal);
        }

        CombatTaskConfig config = configBuilder.build();

        // Configure CombatManager if eating/prayer is enabled
        if (eatEnabled || usePrayer || useSpecialAttack) {
            CombatConfig combatConfig = CombatConfig.builder()
                    .primaryEatThreshold(0.50)
                    .panicEatThreshold(0.25)
                    .useProtectionPrayers(usePrayer)
                    .useSpecialAttack(useSpecialAttack)
                    .specEnergyThreshold(useSpecialAttack ? 50 : 100)
                    .build();
            combatManager.setConfig(combatConfig);
        }

        // Create the CombatTask
        CombatTask combatTask = new CombatTask(config, targetSelector, combatManager)
                .withDescription(getText());

        tasks.add(combatTask);
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
     * Set number of kills required (builder-style).
     *
     * @param count the kill count
     * @return this step for chaining
     */
    public CombatQuestStep withKillCount(int count) {
        this.killCount = count;
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
     * Enable/disable special attack usage (builder-style).
     *
     * @param useSpec true to use special attack
     * @return this step for chaining
     */
    public CombatQuestStep withUseSpecialAttack(boolean useSpec) {
        this.useSpecialAttack = useSpec;
        return this;
    }

    /**
     * Enable/disable eating during combat (builder-style).
     *
     * @param enabled true to enable eating
     * @return this step for chaining
     */
    public CombatQuestStep withEatEnabled(boolean enabled) {
        this.eatEnabled = enabled;
        return this;
    }

    /**
     * Enable/disable prayer usage (builder-style).
     *
     * @param enabled true to enable prayer
     * @return this step for chaining
     */
    public CombatQuestStep withUsePrayer(boolean enabled) {
        this.usePrayer = enabled;
        return this;
    }

    /**
     * Enable/disable looting (builder-style).
     *
     * @param enabled true to enable looting
     * @return this step for chaining
     */
    public CombatQuestStep withLootEnabled(boolean enabled) {
        this.lootEnabled = enabled;
        return this;
    }

    /**
     * Set minimum loot value (builder-style).
     *
     * @param minValue the minimum GE value to loot
     * @return this step for chaining
     */
    public CombatQuestStep withLootMinValue(int minValue) {
        this.lootMinValue = minValue;
        return this;
    }

    /**
     * Set safe-spot position for ranged/magic combat (builder-style).
     *
     * @param position the safe-spot world position
     * @return this step for chaining
     */
    public CombatQuestStep withSafeSpot(WorldPoint position) {
        this.safeSpotPosition = position;
        return this;
    }

    /**
     * Set attack range (builder-style).
     * 1 = melee, 4-5 = shortbow/javelin, 7 = longbow/crossbow, 10 = magic
     *
     * @param range the attack range in tiles
     * @return this step for chaining
     */
    public CombatQuestStep withAttackRange(int range) {
        this.attackRange = range;
        return this;
    }

    /**
     * Set maximum combat duration (builder-style).
     *
     * @param duration the max duration
     * @return this step for chaining
     */
    public CombatQuestStep withMaxDuration(Duration duration) {
        this.maxDuration = duration;
        return this;
    }

    /**
     * Set attack style (builder-style).
     * Automatically equips appropriate gear from inventory before combat.
     * For example, RANGED will find and equip a bow/crossbow and ammo.
     *
     * @param style the attack style (MELEE, RANGED, or MAGIC)
     * @return this step for chaining
     */
    public CombatQuestStep withAttackStyle(AttackStyle style) {
        this.attackStyle = style;
        return this;
    }

    /**
     * Set specific gear set to equip before combat (builder-style).
     * Takes precedence over attackStyle if both are set.
     *
     * @param gearSet the gear set to equip
     * @return this step for chaining
     */
    public CombatQuestStep withGearSet(GearSet gearSet) {
        this.gearSet = gearSet;
        return this;
    }

    /**
     * Set spells to use for magic combat (builder-style).
     * Automatically configures the combat for magic style and sets attack range to 10.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Tutorial Island - cast Wind Strike on chicken
     * new CombatQuestStep(NpcID.CHICKEN, "Cast Wind Strike")
     *     .withSpells(StandardSpell.WIND_STRIKE);
     *
     * // Quest requiring multiple spells (Witch's House)
     * new CombatQuestStep(NpcID.EXPERIMENT, "Kill experiment with all 4 strike spells")
     *     .withSpells(
     *         StandardSpell.WIND_STRIKE,
     *         StandardSpell.WATER_STRIKE,
     *         StandardSpell.EARTH_STRIKE,
     *         StandardSpell.FIRE_STRIKE
     *     );
     *
     * // Ancient Magicks combat
     * new CombatQuestStep(NpcID.BOSS, "Kill boss with Ice Barrage")
     *     .withSpells(AncientSpell.ICE_BARRAGE);
     * }</pre>
     *
     * @param spells one or more spells to use
     * @return this step for chaining
     */
    public CombatQuestStep withSpells(CombatSpell... spells) {
        this.spells = Arrays.asList(spells);
        // Auto-set attack style to MAGIC when using spells
        if (this.attackStyle == null) {
            this.attackStyle = AttackStyle.MAGIC;
        }
        return this;
    }

    /**
     * Set spells to use for magic combat (builder-style, list variant).
     *
     * @param spells list of spells to use
     * @return this step for chaining
     */
    public CombatQuestStep withSpells(List<CombatSpell> spells) {
        this.spells = new ArrayList<>(spells);
        // Auto-set attack style to MAGIC when using spells
        if (this.attackStyle == null) {
            this.attackStyle = AttackStyle.MAGIC;
        }
        return this;
    }

    /**
     * Set whether to use autocast for magic combat (builder-style).
     * Only works if the spell supports autocasting.
     *
     * @param useAutocast true to enable autocast
     * @return this step for chaining
     */
    public CombatQuestStep withAutocast(boolean useAutocast) {
        this.useAutocast = useAutocast;
        return this;
    }

    /**
     * Set spell cycle mode for multiple spells (builder-style).
     *
     * @param mode the cycle mode
     * @return this step for chaining
     */
    public CombatQuestStep withSpellCycleMode(CombatTaskConfig.SpellCycleMode mode) {
        this.spellCycleMode = mode;
        return this;
    }

    /**
     * Set required weapon attack style (builder-style).
     * This takes precedence over XP goal if they conflict.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Enemy weak to slash
     * new CombatQuestStep(NpcID.KALPHITE, "Kill Kalphite")
     *     .withWeaponStyle(WeaponStyle.STAB);
     *
     * // Need slash style but want strength XP
     * new CombatQuestStep(NpcID.ZOMBIE, "Kill zombies")
     *     .withWeaponStyle(WeaponStyle.SLASH)
     *     .withXpGoal(XpGoal.STRENGTH);
     * }</pre>
     *
     * @param style the weapon style (SLASH, STAB, CRUSH, etc.)
     * @return this step for chaining
     */
    public CombatQuestStep withWeaponStyle(WeaponStyle style) {
        this.weaponStyle = style;
        return this;
    }

    /**
     * Set XP training goal (builder-style).
     * Must be compatible with weapon style - if not, weapon style takes precedence.
     *
     * <p>Compatibility:
     * <ul>
     *   <li>SLASH/STAB/CRUSH: ATTACK, STRENGTH, DEFENCE, SHARED</li>
     *   <li>MAGIC: MAGIC, MAGIC_DEFENCE</li>
     *   <li>RANGED: RANGED, RANGED_DEFENCE</li>
     * </ul>
     *
     * @param goal the XP goal
     * @return this step for chaining
     */
    public CombatQuestStep withXpGoal(XpGoal goal) {
        this.xpGoal = goal;
        return this;
    }
}
