package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ActionSequencer.
 * Tests sequence selection, randomization, and reinforcement.
 */
public class ActionSequencerTest {

    private Randomization randomization;
    private ActionSequencer sequencer;

    @Mock
    private PlayerProfile playerProfile;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        randomization = new Randomization(12345L);
        sequencer = new ActionSequencer(randomization);
    }

    // ========================================================================
    // Banking Sequences Tests
    // ========================================================================

    @Test
    public void testGetBankingSequence_ReturnsNonEmptyList() {
        List<ActionSequencer.BankOperation> sequence = sequencer.getBankingSequence();
        
        assertNotNull(sequence);
        assertFalse(sequence.isEmpty());
    }

    @Test
    public void testGetBankingSequence_ContainsValidOperations() {
        List<ActionSequencer.BankOperation> sequence = sequencer.getBankingSequence();
        
        for (ActionSequencer.BankOperation op : sequence) {
            assertNotNull(op);
            assertTrue(op == ActionSequencer.BankOperation.DEPOSIT_SPECIFIC ||
                      op == ActionSequencer.BankOperation.DEPOSIT_ALL ||
                      op == ActionSequencer.BankOperation.WITHDRAW);
        }
    }

    @Test
    public void testGetBankingSequence_WithFilter_OnlyIncludesNeeded() {
        Set<ActionSequencer.BankOperation> needed = new HashSet<>();
        needed.add(ActionSequencer.BankOperation.DEPOSIT_ALL);
        needed.add(ActionSequencer.BankOperation.WITHDRAW);
        
        List<ActionSequencer.BankOperation> sequence = sequencer.getBankingSequence(needed);
        
        assertTrue(sequence.size() <= needed.size());
        for (ActionSequencer.BankOperation op : sequence) {
            assertTrue("Should only include needed operations", needed.contains(op));
        }
    }

    @Test
    public void testGetBankingSequenceTypes_ReturnsAllTypes() {
        Set<String> types = sequencer.getBankingSequenceTypes();
        
        assertTrue(types.contains("TYPE_A"));
        assertTrue(types.contains("TYPE_B"));
        assertTrue(types.contains("TYPE_C"));
    }

    @Test
    public void testGetBankingSequenceByType_TypeA_HasDepositSpecific() {
        List<ActionSequencer.BankOperation> sequence = sequencer.getBankingSequenceByType("TYPE_A");
        
        assertTrue(sequence.contains(ActionSequencer.BankOperation.DEPOSIT_SPECIFIC));
    }

    @Test
    public void testGetBankingSequenceByType_TypeB_NoDepositSpecific() {
        List<ActionSequencer.BankOperation> sequence = sequencer.getBankingSequenceByType("TYPE_B");
        
        assertFalse(sequence.contains(ActionSequencer.BankOperation.DEPOSIT_SPECIFIC));
    }

    @Test
    public void testGetBankingSequenceByType_TypeC_WithdrawFirst() {
        List<ActionSequencer.BankOperation> sequence = sequencer.getBankingSequenceByType("TYPE_C");
        
        assertEquals(ActionSequencer.BankOperation.WITHDRAW, sequence.get(0));
    }

    @Test
    public void testGetBankingSequenceByType_InvalidType_ReturnsEmpty() {
        List<ActionSequencer.BankOperation> sequence = sequencer.getBankingSequenceByType("INVALID");
        
        assertTrue(sequence.isEmpty());
    }

    // ========================================================================
    // Combat Prep Sequences Tests
    // ========================================================================

    @Test
    public void testGetCombatPrepSequence_ReturnsNonEmptyList() {
        List<ActionSequencer.CombatPrepOperation> sequence = sequencer.getCombatPrepSequence();
        
        assertNotNull(sequence);
        assertFalse(sequence.isEmpty());
    }

    @Test
    public void testGetCombatPrepSequence_EndsWithEngage() {
        List<ActionSequencer.CombatPrepOperation> sequence = sequencer.getCombatPrepSequence();
        
        assertEquals(ActionSequencer.CombatPrepOperation.ENGAGE, 
                    sequence.get(sequence.size() - 1));
    }

    @Test
    public void testGetCombatPrepSequence_WithFilter_OnlyIncludesNeeded() {
        Set<ActionSequencer.CombatPrepOperation> needed = new HashSet<>();
        needed.add(ActionSequencer.CombatPrepOperation.EQUIP_GEAR);
        needed.add(ActionSequencer.CombatPrepOperation.ENGAGE);
        
        List<ActionSequencer.CombatPrepOperation> sequence = sequencer.getCombatPrepSequence(needed);
        
        assertTrue(sequence.size() <= needed.size());
        for (ActionSequencer.CombatPrepOperation op : sequence) {
            assertTrue("Should only include needed operations", needed.contains(op));
        }
    }

    @Test
    public void testGetCombatPrepSequenceTypes_ReturnsAllTypes() {
        Set<String> types = sequencer.getCombatPrepSequenceTypes();
        
        assertTrue(types.contains("TYPE_A"));
        assertTrue(types.contains("TYPE_B"));
        assertTrue(types.contains("TYPE_C"));
    }

    @Test
    public void testGetCombatPrepSequenceByType_TypeA_GearFirst() {
        List<ActionSequencer.CombatPrepOperation> sequence = 
            sequencer.getCombatPrepSequenceByType("TYPE_A");
        
        assertEquals(ActionSequencer.CombatPrepOperation.EQUIP_GEAR, sequence.get(0));
    }

    @Test
    public void testGetCombatPrepSequenceByType_TypeB_PrayersFirst() {
        List<ActionSequencer.CombatPrepOperation> sequence = 
            sequencer.getCombatPrepSequenceByType("TYPE_B");
        
        assertEquals(ActionSequencer.CombatPrepOperation.ACTIVATE_PRAYERS, sequence.get(0));
    }

    @Test
    public void testGetCombatPrepSequenceByType_TypeC_FoodFirst() {
        List<ActionSequencer.CombatPrepOperation> sequence = 
            sequencer.getCombatPrepSequenceByType("TYPE_C");
        
        assertEquals(ActionSequencer.CombatPrepOperation.EAT_FOOD, sequence.get(0));
    }

    // ========================================================================
    // Randomization Tests
    // ========================================================================

    @Test
    public void testRandomizeOrder_PreservesElements() {
        List<String> original = Arrays.asList("A", "B", "C", "D", "E");
        
        List<String> shuffled = sequencer.randomizeOrder(original);
        
        assertEquals(original.size(), shuffled.size());
        assertTrue(shuffled.containsAll(original));
    }

    @Test
    public void testRandomizeOrder_ChangesOrder() {
        List<String> original = Arrays.asList("A", "B", "C", "D", "E");
        
        // Run multiple times to ensure at least one is different
        boolean foundDifferent = false;
        for (int i = 0; i < 100; i++) {
            List<String> shuffled = sequencer.randomizeOrder(original);
            if (!shuffled.equals(original)) {
                foundDifferent = true;
                break;
            }
        }
        
        assertTrue("Randomization should produce different orders", foundDifferent);
    }

    @Test
    public void testRandomizeOrderPartially_KeepsFixedIndices() {
        List<String> original = Arrays.asList("A", "B", "C", "D", "E");
        Set<Integer> fixed = new HashSet<>(Arrays.asList(0, 4)); // Keep first and last
        
        for (int i = 0; i < 100; i++) {
            List<String> result = sequencer.randomizeOrderPartially(original, fixed);
            
            assertEquals("First element should stay fixed", "A", result.get(0));
            assertEquals("Last element should stay fixed", "E", result.get(4));
        }
    }

    // ========================================================================
    // Item Order Strategy Tests
    // ========================================================================

    @Test
    public void testGetItemOrderStrategy_ReturnsValidStrategy() {
        for (int i = 0; i < 100; i++) {
            ActionSequencer.ItemOrderStrategy strategy = sequencer.getItemOrderStrategy();
            assertNotNull(strategy);
        }
    }

    @Test
    public void testApplyItemOrderStrategy_InOrder_PreservesOrder() {
        List<String> items = Arrays.asList("A", "B", "C");
        
        List<String> result = sequencer.applyItemOrderStrategy(items, 
            ActionSequencer.ItemOrderStrategy.IN_ORDER);
        
        assertEquals(items, result);
    }

    @Test
    public void testApplyItemOrderStrategy_Reverse_ReversesOrder() {
        List<String> items = Arrays.asList("A", "B", "C");
        
        List<String> result = sequencer.applyItemOrderStrategy(items, 
            ActionSequencer.ItemOrderStrategy.REVERSE);
        
        assertEquals(Arrays.asList("C", "B", "A"), result);
    }

    @Test
    public void testApplyItemOrderStrategy_Random_PreservesElements() {
        List<String> items = Arrays.asList("A", "B", "C");
        
        List<String> result = sequencer.applyItemOrderStrategy(items, 
            ActionSequencer.ItemOrderStrategy.RANDOM);
        
        assertEquals(items.size(), result.size());
        assertTrue(result.containsAll(items));
    }

    // ========================================================================
    // Generic Sequence Tests
    // ========================================================================

    @Test
    public void testGetGenericSequence_ReturnsCorrectType() {
        Map<String, List<String>> sequences = new HashMap<>();
        sequences.put("SEQ_A", Arrays.asList("1", "2", "3"));
        sequences.put("SEQ_B", Arrays.asList("A", "B"));
        
        Map<String, Double> weights = new HashMap<>();
        weights.put("SEQ_A", 1.0);
        weights.put("SEQ_B", 0.0); // Zero weight should never be selected
        
        ActionSequencer.SequenceResult<String> result = sequencer.getGenericSequence(sequences, weights);
        
        assertEquals("SEQ_A", result.getSequenceType());
        assertEquals(Arrays.asList("1", "2", "3"), result.getSequence());
    }

    @Test
    public void testGetGenericSequence_NullWeights_UsesEqualWeights() {
        Map<String, List<String>> sequences = new HashMap<>();
        sequences.put("SEQ_A", Arrays.asList("1"));
        sequences.put("SEQ_B", Arrays.asList("2"));
        
        // Both should be selectable with null weights
        Set<String> selectedTypes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ActionSequencer.SequenceResult<String> result = sequencer.getGenericSequence(sequences, null);
            selectedTypes.add(result.getSequenceType());
        }
        
        assertTrue("Both types should be selectable with equal weights", 
                  selectedTypes.contains("SEQ_A") && selectedTypes.contains("SEQ_B"));
    }

    // ========================================================================
    // Profile Integration Tests
    // ========================================================================

    @Test
    public void testWithPlayerProfile_UseProfileWeights() {
        when(playerProfile.selectBankingSequence()).thenReturn("TYPE_C");
        sequencer.setPlayerProfile(playerProfile);
        
        List<ActionSequencer.BankOperation> sequence = sequencer.getBankingSequence();
        
        // Should have used profile's sequence type
        assertEquals(ActionSequencer.BankOperation.WITHDRAW, sequence.get(0));
        verify(playerProfile).selectBankingSequence();
    }

    @Test
    public void testReinforceBankingSequence_CallsProfile() {
        sequencer.setPlayerProfile(playerProfile);
        
        sequencer.reinforceBankingSequence("TYPE_A");
        
        verify(playerProfile).reinforceBankingSequence("TYPE_A");
    }

    @Test
    public void testReinforceCombatPrepSequence_CallsProfile() {
        sequencer.setPlayerProfile(playerProfile);
        
        sequencer.reinforceCombatPrepSequence("TYPE_B");
        
        verify(playerProfile).reinforceCombatPrepSequence("TYPE_B");
    }
}

