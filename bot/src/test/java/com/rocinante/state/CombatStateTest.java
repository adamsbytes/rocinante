package com.rocinante.state;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Unit tests for CombatState class.
 */
public class CombatStateTest {

    // ========================================================================
    // Empty State
    // ========================================================================

    @Test
    public void testEmptyState() {
        CombatState empty = CombatState.EMPTY;

        assertNull(empty.getTargetNpc());
        assertFalse(empty.hasTarget());
        assertEquals(0, empty.getSpecialAttackEnergy());
        assertEquals(AttackStyle.UNKNOWN, empty.getCurrentAttackStyle());
        assertEquals(4, empty.getWeaponAttackSpeed());
        assertTrue(empty.getBoostedStats().isEmpty());
        assertEquals(PoisonState.NONE, empty.getPoisonState());
        assertTrue(empty.getAggressiveNpcs().isEmpty());
        assertFalse(empty.isInMultiCombat());
        assertEquals(-1, empty.getLastAttackTick());
        assertFalse(empty.isCanAttack());
    }

    // ========================================================================
    // Target Tests
    // ========================================================================

    @Test
    public void testHasTarget() {
        NpcSnapshot target = createNpcSnapshot(0, 100, "Goblin");

        CombatState stateWithTarget = CombatState.builder()
                .targetNpc(target)
                .targetPresent(true)
                .build();

        assertTrue(stateWithTarget.hasTarget());
        assertEquals(target, stateWithTarget.getTargetNpc());

        CombatState stateNoTarget = CombatState.builder()
                .targetPresent(false)
                .build();

        assertFalse(stateNoTarget.hasTarget());
    }

    // ========================================================================
    // Aggressor Tests
    // ========================================================================

    @Test
    public void testGetAggressorCount() {
        AggressorInfo aggressor1 = createAggressor(0, "Goblin", 5);
        AggressorInfo aggressor2 = createAggressor(1, "Rat", 1);

        CombatState state = CombatState.builder()
                .aggressiveNpcs(Arrays.asList(aggressor1, aggressor2))
                .build();

        assertEquals(2, state.getAggressorCount());
    }

    @Test
    public void testIsPiledUp() {
        AggressorInfo aggressor1 = createAggressor(0, "Goblin", 5);
        AggressorInfo aggressor2 = createAggressor(1, "Rat", 1);

        CombatState piledUp = CombatState.builder()
                .aggressiveNpcs(Arrays.asList(aggressor1, aggressor2))
                .build();

        assertTrue(piledUp.isPiledUp());

        CombatState notPiledUp = CombatState.builder()
                .aggressiveNpcs(Collections.singletonList(aggressor1))
                .build();

        assertFalse(notPiledUp.isPiledUp());

        CombatState noAggressors = CombatState.builder()
                .aggressiveNpcs(Collections.emptyList())
                .build();

        assertFalse(noAggressors.isPiledUp());
    }

    @Test
    public void testIsBeingAttacked() {
        AggressorInfo aggressor = createAggressor(0, "Goblin", 5);

        CombatState attacked = CombatState.builder()
                .aggressiveNpcs(Collections.singletonList(aggressor))
                .build();

        assertTrue(attacked.isBeingAttacked());

        CombatState notAttacked = CombatState.builder()
                .aggressiveNpcs(Collections.emptyList())
                .build();

        assertFalse(notAttacked.isBeingAttacked());
    }

    @Test
    public void testGetMostDangerousAggressor() {
        AggressorInfo weak = createAggressorWithMaxHit(0, "Rat", 1, 2);
        AggressorInfo strong = createAggressorWithMaxHit(1, "Goblin", 5, 10);

        CombatState state = CombatState.builder()
                .aggressiveNpcs(Arrays.asList(weak, strong))
                .build();

        Optional<AggressorInfo> most = state.getMostDangerousAggressor();
        assertTrue(most.isPresent());
        assertEquals("Goblin", most.get().getNpcName());
    }

    @Test
    public void testGetNextAttacker() {
        AggressorInfo later = createAggressorWithTiming(0, "Rat", 5);
        AggressorInfo sooner = createAggressorWithTiming(1, "Goblin", 2);

        CombatState state = CombatState.builder()
                .aggressiveNpcs(Arrays.asList(later, sooner))
                .build();

        Optional<AggressorInfo> next = state.getNextAttacker();
        assertTrue(next.isPresent());
        assertEquals("Goblin", next.get().getNpcName());
    }

    // ========================================================================
    // Poison/Venom Tests
    // ========================================================================

    @Test
    public void testIsPoisoned() {
        PoisonState poisoned = PoisonState.builder()
                .type(PoisonType.POISON)
                .currentDamage(6)
                .build();

        CombatState state = CombatState.builder()
                .poisonState(poisoned)
                .build();

        assertTrue(state.isPoisoned());
        assertFalse(state.isVenomed());
    }

    @Test
    public void testIsVenomed() {
        PoisonState venomed = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(10)
                .build();

        CombatState state = CombatState.builder()
                .poisonState(venomed)
                .build();

        assertTrue(state.isVenomed());
        assertFalse(state.isPoisoned());
    }

    @Test
    public void testIsVenomCritical() {
        PoisonState criticalVenom = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(16)
                .build();

        CombatState critical = CombatState.builder()
                .poisonState(criticalVenom)
                .build();

        assertTrue(critical.isVenomCritical());

        PoisonState lowVenom = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(10)
                .build();

        CombatState notCritical = CombatState.builder()
                .poisonState(lowVenom)
                .build();

        assertFalse(notCritical.isVenomCritical());
    }

    @Test
    public void testPredictNextVenomDamage() {
        PoisonState venomed = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(10)
                .build();

        CombatState state = CombatState.builder()
                .poisonState(venomed)
                .build();

        assertEquals(12, state.predictNextVenomDamage());
    }

    @Test
    public void testGetCurrentVenomDamage() {
        PoisonState venomed = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(14)
                .build();

        CombatState state = CombatState.builder()
                .poisonState(venomed)
                .build();

        assertEquals(14, state.getCurrentVenomDamage());
    }

    @Test
    public void testGetCurrentVenomDamage_NotVenomed() {
        CombatState state = CombatState.builder()
                .poisonState(PoisonState.NONE)
                .build();

        assertEquals(0, state.getCurrentVenomDamage());
    }

    // ========================================================================
    // Special Attack Tests
    // ========================================================================

    @Test
    public void testHasSpecEnergy() {
        CombatState state = CombatState.builder()
                .specialAttackEnergy(75)
                .build();

        assertTrue(state.hasSpecEnergy(50));
        assertTrue(state.hasSpecEnergy(75));
        assertFalse(state.hasSpecEnergy(100));
    }

    @Test
    public void testCanUseSpec() {
        CombatState fullSpec = CombatState.builder()
                .specialAttackEnergy(100)
                .build();

        assertTrue(fullSpec.canUseSpec(25));
        assertTrue(fullSpec.canUseSpec(50));
        assertTrue(fullSpec.canUseSpec(100));

        CombatState halfSpec = CombatState.builder()
                .specialAttackEnergy(50)
                .build();

        assertTrue(halfSpec.canUseSpec(25));
        assertTrue(halfSpec.canUseSpec(50));
        assertFalse(halfSpec.canUseSpec(100));
    }

    // ========================================================================
    // Attack Timing Tests
    // ========================================================================

    @Test
    public void testIsAttackReady() {
        CombatState ready = CombatState.builder()
                .canAttack(true)
                .build();

        assertTrue(ready.isAttackReady());

        CombatState notReady = CombatState.builder()
                .canAttack(false)
                .build();

        assertFalse(notReady.isAttackReady());
    }

    @Test
    public void testGetTicksUntilCanAttack() {
        CombatState ready = CombatState.builder()
                .canAttack(true)
                .ticksSinceLastAttack(4)
                .weaponAttackSpeed(4)
                .build();

        assertEquals(0, ready.getTicksUntilCanAttack());

        CombatState notReady = CombatState.builder()
                .canAttack(false)
                .ticksSinceLastAttack(2)
                .weaponAttackSpeed(4)
                .build();

        assertEquals(2, notReady.getTicksUntilCanAttack());
    }

    // ========================================================================
    // Incoming Attack Tests
    // ========================================================================

    @Test
    public void testHasIncomingAttack() {
        CombatState incoming = CombatState.builder()
                .incomingAttackStyle(AttackStyle.MAGIC)
                .ticksUntilAttackLands(3)
                .build();

        assertTrue(incoming.hasIncomingAttack());

        CombatState noIncoming = CombatState.builder()
                .incomingAttackStyle(AttackStyle.UNKNOWN)
                .ticksUntilAttackLands(-1)
                .build();

        assertFalse(noIncoming.hasIncomingAttack());
    }

    @Test
    public void testIsAttackImminent() {
        CombatState imminent = CombatState.builder()
                .incomingAttackStyle(AttackStyle.RANGED)
                .ticksUntilAttackLands(1)
                .build();

        assertTrue(imminent.isAttackImminent());

        CombatState notImminent = CombatState.builder()
                .incomingAttackStyle(AttackStyle.RANGED)
                .ticksUntilAttackLands(5)
                .build();

        assertFalse(notImminent.isAttackImminent());
    }

    // ========================================================================
    // Boosted Stats Tests
    // ========================================================================

    @Test
    public void testGetBoostedLevel() {
        Map<Skill, Integer> stats = new EnumMap<>(Skill.class);
        stats.put(Skill.ATTACK, 99);
        stats.put(Skill.STRENGTH, 105); // Boosted

        CombatState state = CombatState.builder()
                .boostedStats(stats)
                .build();

        assertEquals(99, state.getBoostedLevel(Skill.ATTACK));
        assertEquals(105, state.getBoostedLevel(Skill.STRENGTH));
        assertEquals(-1, state.getBoostedLevel(Skill.DEFENCE)); // Not tracked
    }

    // ========================================================================
    // Summary Test
    // ========================================================================

    @Test
    public void testGetSummary() {
        NpcSnapshot target = createNpcSnapshot(0, 100, "Goblin");
        PoisonState venomed = PoisonState.builder()
                .type(PoisonType.VENOM)
                .currentDamage(10)
                .build();

        CombatState state = CombatState.builder()
                .targetNpc(target)
                .targetPresent(true)
                .specialAttackEnergy(75)
                .poisonState(venomed)
                .aggressiveNpcs(Collections.singletonList(createAggressor(0, "Rat", 1)))
                .inMultiCombat(true)
                .build();

        String summary = state.getSummary();
        assertTrue(summary.contains("target=Goblin"));
        assertTrue(summary.contains("spec=75%"));
        assertTrue(summary.contains("aggressors=1"));
        assertTrue(summary.contains("MULTI"));
        assertTrue(summary.contains("VENOM"));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private NpcSnapshot createNpcSnapshot(int index, int id, String name) {
        return NpcSnapshot.builder()
                .index(index)
                .id(id)
                .name(name)
                .combatLevel(5)
                .worldPosition(new WorldPoint(3200, 3200, 0))
                .healthRatio(30)
                .healthScale(30)
                .animationId(-1)
                .interactingIndex(-1)
                .targetingPlayer(true)
                .isDead(false)
                .size(1)
                .build();
    }

    private AggressorInfo createAggressor(int index, String name, int combatLevel) {
        return AggressorInfo.builder()
                .npcIndex(index)
                .npcId(100 + index)
                .npcName(name)
                .combatLevel(combatLevel)
                .ticksUntilNextAttack(-1)
                .expectedMaxHit(AggressorInfo.estimateMaxHit(combatLevel))
                .attackStyle(-1)
                .attackSpeed(-1)
                .lastAttackTick(-1)
                .isAttacking(false)
                .build();
    }

    private AggressorInfo createAggressorWithMaxHit(int index, String name, int combatLevel, int maxHit) {
        return AggressorInfo.builder()
                .npcIndex(index)
                .npcId(100 + index)
                .npcName(name)
                .combatLevel(combatLevel)
                .ticksUntilNextAttack(-1)
                .expectedMaxHit(maxHit)
                .attackStyle(-1)
                .attackSpeed(-1)
                .lastAttackTick(-1)
                .isAttacking(false)
                .build();
    }

    private AggressorInfo createAggressorWithTiming(int index, String name, int ticksUntilAttack) {
        return AggressorInfo.builder()
                .npcIndex(index)
                .npcId(100 + index)
                .npcName(name)
                .combatLevel(5)
                .ticksUntilNextAttack(ticksUntilAttack)
                .expectedMaxHit(5)
                .attackStyle(-1)
                .attackSpeed(4)
                .lastAttackTick(0)
                .isAttacking(false)
                .build();
    }
}

