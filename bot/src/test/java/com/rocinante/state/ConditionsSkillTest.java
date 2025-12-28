package com.rocinante.state;

import com.rocinante.tasks.TaskContext;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for skill-related conditions in Conditions class.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ConditionsSkillTest {

    @Mock
    private TaskContext ctx;

    @Mock
    private Client client;

    @Before
    public void setUp() {
        when(ctx.getClient()).thenReturn(client);
    }

    // ========================================================================
    // skillLevelReached Tests
    // ========================================================================

    @Test
    public void testSkillLevelReached_AtTarget() {
        when(client.getRealSkillLevel(Skill.MINING)).thenReturn(50);
        
        StateCondition condition = Conditions.skillLevelReached(Skill.MINING, 50);
        assertTrue("Should return true when level equals target", condition.test(ctx));
    }

    @Test
    public void testSkillLevelReached_AboveTarget() {
        when(client.getRealSkillLevel(Skill.MINING)).thenReturn(60);
        
        StateCondition condition = Conditions.skillLevelReached(Skill.MINING, 50);
        assertTrue("Should return true when level exceeds target", condition.test(ctx));
    }

    @Test
    public void testSkillLevelReached_BelowTarget() {
        when(client.getRealSkillLevel(Skill.MINING)).thenReturn(40);
        
        StateCondition condition = Conditions.skillLevelReached(Skill.MINING, 50);
        assertFalse("Should return false when level below target", condition.test(ctx));
    }

    // ========================================================================
    // skillLevelEquals Tests
    // ========================================================================

    @Test
    public void testSkillLevelEquals_Match() {
        when(client.getRealSkillLevel(Skill.WOODCUTTING)).thenReturn(30);
        
        StateCondition condition = Conditions.skillLevelEquals(Skill.WOODCUTTING, 30);
        assertTrue("Should return true when level equals exactly", condition.test(ctx));
    }

    @Test
    public void testSkillLevelEquals_NoMatch() {
        when(client.getRealSkillLevel(Skill.WOODCUTTING)).thenReturn(31);
        
        StateCondition condition = Conditions.skillLevelEquals(Skill.WOODCUTTING, 30);
        assertFalse("Should return false when level differs", condition.test(ctx));
    }

    // ========================================================================
    // skillLevelBelow Tests
    // ========================================================================

    @Test
    public void testSkillLevelBelow_Below() {
        when(client.getRealSkillLevel(Skill.FISHING)).thenReturn(20);
        
        StateCondition condition = Conditions.skillLevelBelow(Skill.FISHING, 30);
        assertTrue("Should return true when level is below threshold", condition.test(ctx));
    }

    @Test
    public void testSkillLevelBelow_Equal() {
        when(client.getRealSkillLevel(Skill.FISHING)).thenReturn(30);
        
        StateCondition condition = Conditions.skillLevelBelow(Skill.FISHING, 30);
        assertFalse("Should return false when level equals threshold", condition.test(ctx));
    }

    @Test
    public void testSkillLevelBelow_Above() {
        when(client.getRealSkillLevel(Skill.FISHING)).thenReturn(40);
        
        StateCondition condition = Conditions.skillLevelBelow(Skill.FISHING, 30);
        assertFalse("Should return false when level exceeds threshold", condition.test(ctx));
    }

    // ========================================================================
    // skillXpReached Tests
    // ========================================================================

    @Test
    public void testSkillXpReached_AtTarget() {
        when(client.getSkillExperience(Skill.FLETCHING)).thenReturn(100000);
        
        StateCondition condition = Conditions.skillXpReached(Skill.FLETCHING, 100000);
        assertTrue("Should return true when XP equals target", condition.test(ctx));
    }

    @Test
    public void testSkillXpReached_AboveTarget() {
        when(client.getSkillExperience(Skill.FLETCHING)).thenReturn(150000);
        
        StateCondition condition = Conditions.skillXpReached(Skill.FLETCHING, 100000);
        assertTrue("Should return true when XP exceeds target", condition.test(ctx));
    }

    @Test
    public void testSkillXpReached_BelowTarget() {
        when(client.getSkillExperience(Skill.FLETCHING)).thenReturn(50000);
        
        StateCondition condition = Conditions.skillXpReached(Skill.FLETCHING, 100000);
        assertFalse("Should return false when XP below target", condition.test(ctx));
    }

    // ========================================================================
    // totalLevelReached Tests
    // ========================================================================

    @Test
    public void testTotalLevelReached_AtTarget() {
        when(client.getTotalLevel()).thenReturn(500);
        
        StateCondition condition = Conditions.totalLevelReached(500);
        assertTrue("Should return true when total level equals target", condition.test(ctx));
    }

    @Test
    public void testTotalLevelReached_AboveTarget() {
        when(client.getTotalLevel()).thenReturn(750);
        
        StateCondition condition = Conditions.totalLevelReached(500);
        assertTrue("Should return true when total level exceeds target", condition.test(ctx));
    }

    // ========================================================================
    // skillIsBoosted Tests
    // ========================================================================

    @Test
    public void testSkillIsBoosted_Boosted() {
        when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(70);
        when(client.getBoostedSkillLevel(Skill.ATTACK)).thenReturn(75);
        
        StateCondition condition = Conditions.skillIsBoosted(Skill.ATTACK);
        assertTrue("Should return true when boosted above real level", condition.test(ctx));
    }

    @Test
    public void testSkillIsBoosted_NotBoosted() {
        when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(70);
        when(client.getBoostedSkillLevel(Skill.ATTACK)).thenReturn(70);
        
        StateCondition condition = Conditions.skillIsBoosted(Skill.ATTACK);
        assertFalse("Should return false when boosted equals real", condition.test(ctx));
    }

    @Test
    public void testSkillIsBoosted_Drained() {
        when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(70);
        when(client.getBoostedSkillLevel(Skill.ATTACK)).thenReturn(65);
        
        StateCondition condition = Conditions.skillIsBoosted(Skill.ATTACK);
        assertFalse("Should return false when drained below real", condition.test(ctx));
    }

    // ========================================================================
    // skillIsDrained Tests
    // ========================================================================

    @Test
    public void testSkillIsDrained_Drained() {
        when(client.getRealSkillLevel(Skill.DEFENCE)).thenReturn(50);
        when(client.getBoostedSkillLevel(Skill.DEFENCE)).thenReturn(45);
        
        StateCondition condition = Conditions.skillIsDrained(Skill.DEFENCE);
        assertTrue("Should return true when drained below real", condition.test(ctx));
    }

    @Test
    public void testSkillIsDrained_NotDrained() {
        when(client.getRealSkillLevel(Skill.DEFENCE)).thenReturn(50);
        when(client.getBoostedSkillLevel(Skill.DEFENCE)).thenReturn(50);
        
        StateCondition condition = Conditions.skillIsDrained(Skill.DEFENCE);
        assertFalse("Should return false when not drained", condition.test(ctx));
    }

    // ========================================================================
    // boostedLevelAtLeast Tests
    // ========================================================================

    @Test
    public void testBoostedLevelAtLeast_Meets() {
        when(client.getBoostedSkillLevel(Skill.STRENGTH)).thenReturn(85);
        
        StateCondition condition = Conditions.boostedLevelAtLeast(Skill.STRENGTH, 85);
        assertTrue("Should return true when boosted meets requirement", condition.test(ctx));
    }

    @Test
    public void testBoostedLevelAtLeast_Exceeds() {
        when(client.getBoostedSkillLevel(Skill.STRENGTH)).thenReturn(90);
        
        StateCondition condition = Conditions.boostedLevelAtLeast(Skill.STRENGTH, 85);
        assertTrue("Should return true when boosted exceeds requirement", condition.test(ctx));
    }

    @Test
    public void testBoostedLevelAtLeast_Below() {
        when(client.getBoostedSkillLevel(Skill.STRENGTH)).thenReturn(80);
        
        StateCondition condition = Conditions.boostedLevelAtLeast(Skill.STRENGTH, 85);
        assertFalse("Should return false when boosted below requirement", condition.test(ctx));
    }

    // ========================================================================
    // Composability Tests
    // ========================================================================

    @Test
    public void testSkillConditionsComposable() {
        when(client.getRealSkillLevel(Skill.MINING)).thenReturn(50);
        when(client.getRealSkillLevel(Skill.WOODCUTTING)).thenReturn(40);
        
        StateCondition miningReached = Conditions.skillLevelReached(Skill.MINING, 50);
        StateCondition wcReached = Conditions.skillLevelReached(Skill.WOODCUTTING, 50);
        
        // Test AND composition
        StateCondition both = miningReached.and(wcReached);
        assertFalse("Both conditions should fail (WC not 50)", both.test(ctx));
        
        // Test OR composition
        StateCondition either = miningReached.or(wcReached);
        assertTrue("Either condition should pass (Mining is 50)", either.test(ctx));
        
        // Test NOT composition
        StateCondition notWc = wcReached.not();
        assertTrue("NOT WC reached should pass (WC is 40)", notWc.test(ctx));
    }
}

