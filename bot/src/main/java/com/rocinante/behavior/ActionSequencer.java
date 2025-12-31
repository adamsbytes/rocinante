package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Action sequence randomization system for humanizing bot behavior.
 * 
 * Per REQUIREMENTS.md Section 3.5.5 (Action Sequence Randomization):
 * When multiple valid paths exist, randomize the order based on player profile weights.
 * 
 * Banking Sequences:
 * - TYPE_A: Deposit specific -> Deposit all -> Withdraw
 * - TYPE_B: Deposit all -> Withdraw
 * - TYPE_C: Withdraw -> Deposit all (items remain in inventory during transaction)
 * 
 * Combat Preparation Sequences:
 * - TYPE_A: Equip gear -> Eat food -> Activate prayers -> Engage
 * - TYPE_B: Activate prayers -> Equip gear -> Eat food -> Engage
 * - TYPE_C: Eat food -> Equip gear -> Activate prayers -> Engage
 * 
 * The system uses PlayerProfile weights for selection and reinforces successful sequences.
 */
@Slf4j
@Singleton
public class ActionSequencer {

    // === Banking Operations ===
    
    /**
     * Banking operation types.
     */
    public enum BankOperation {
        DEPOSIT_SPECIFIC,
        DEPOSIT_ALL,
        WITHDRAW
    }
    
    /**
     * Pre-defined banking sequences.
     */
    private static final Map<String, List<BankOperation>> BANKING_SEQUENCES = new LinkedHashMap<>();
    
    static {
        // TYPE_A: Most methodical - deposit specific first, then clear inventory, then withdraw
        BANKING_SEQUENCES.put("TYPE_A", Arrays.asList(
            BankOperation.DEPOSIT_SPECIFIC,
            BankOperation.DEPOSIT_ALL,
            BankOperation.WITHDRAW
        ));
        
        // TYPE_B: Quick clear - just deposit all and withdraw (most common)
        BANKING_SEQUENCES.put("TYPE_B", Arrays.asList(
            BankOperation.DEPOSIT_ALL,
            BankOperation.WITHDRAW
        ));
        
        // TYPE_C: Items in inventory during transaction - withdraw first, then deposit
        BANKING_SEQUENCES.put("TYPE_C", Arrays.asList(
            BankOperation.WITHDRAW,
            BankOperation.DEPOSIT_ALL
        ));
    }

    // === Combat Preparation Operations ===
    
    /**
     * Combat preparation operation types.
     */
    public enum CombatPrepOperation {
        EQUIP_GEAR,
        EAT_FOOD,
        ACTIVATE_PRAYERS,
        ENGAGE
    }
    
    /**
     * Pre-defined combat preparation sequences.
     */
    private static final Map<String, List<CombatPrepOperation>> COMBAT_PREP_SEQUENCES = new LinkedHashMap<>();
    
    static {
        // TYPE_A: Standard sequence - gear, food, prayers, engage
        COMBAT_PREP_SEQUENCES.put("TYPE_A", Arrays.asList(
            CombatPrepOperation.EQUIP_GEAR,
            CombatPrepOperation.EAT_FOOD,
            CombatPrepOperation.ACTIVATE_PRAYERS,
            CombatPrepOperation.ENGAGE
        ));
        
        // TYPE_B: Prayers first (anticipating combat)
        COMBAT_PREP_SEQUENCES.put("TYPE_B", Arrays.asList(
            CombatPrepOperation.ACTIVATE_PRAYERS,
            CombatPrepOperation.EQUIP_GEAR,
            CombatPrepOperation.EAT_FOOD,
            CombatPrepOperation.ENGAGE
        ));
        
        // TYPE_C: Food first (heal before combat)
        COMBAT_PREP_SEQUENCES.put("TYPE_C", Arrays.asList(
            CombatPrepOperation.EAT_FOOD,
            CombatPrepOperation.EQUIP_GEAR,
            CombatPrepOperation.ACTIVATE_PRAYERS,
            CombatPrepOperation.ENGAGE
        ));
    }

    // === Dependencies ===
    
    private final Randomization randomization;
    
    @Setter
    @Nullable
    private PlayerProfile playerProfile;

    @Inject
    public ActionSequencer(Randomization randomization) {
        this.randomization = randomization;
        log.info("ActionSequencer initialized");
    }

    // ========================================================================
    // Banking Sequences
    // ========================================================================

    /**
     * Get the banking sequence to use for this bank interaction.
     * 
     * @return ordered list of BankOperation
     */
    public List<BankOperation> getBankingSequence() {
        String sequenceType = selectBankingSequenceType();
        List<BankOperation> sequence = BANKING_SEQUENCES.get(sequenceType);
        
        log.debug("Selected banking sequence: {} -> {}", sequenceType, sequence);
        return new ArrayList<>(sequence);
    }

    /**
     * Get the banking sequence to use, filtering to only include specified operations.
     * 
     * @param neededOperations set of operations that are actually needed
     * @return ordered list of BankOperation (only those needed)
     */
    public List<BankOperation> getBankingSequence(Set<BankOperation> neededOperations) {
        return getBankingSequenceResult(neededOperations).getSequence();
    }

    /**
     * Get the banking sequence along with the selected sequence type so callers can
     * reinforce the profile weights after successful execution.
     *
     * @param neededOperations set of operations that are actually needed
     * @return SequenceResult containing type and ordered operations
     */
    public SequenceResult<BankOperation> getBankingSequenceResult(Set<BankOperation> neededOperations) {
        String sequenceType = selectBankingSequenceType();
        List<BankOperation> fullSequence = BANKING_SEQUENCES.get(sequenceType);

        // Filter to only needed operations, maintaining sequence order
        List<BankOperation> filtered = new ArrayList<>();
        for (BankOperation op : fullSequence) {
            if (neededOperations.contains(op)) {
                filtered.add(op);
            }
        }

        log.debug("Selected banking sequence: {} -> {} (filtered from {})",
                sequenceType, filtered, fullSequence);
        return new SequenceResult<>(sequenceType, filtered);
    }

    /**
     * Select banking sequence type based on profile weights.
     */
    private String selectBankingSequenceType() {
        if (playerProfile != null) {
            return playerProfile.selectBankingSequence();
        }
        
        // Default weights if no profile
        return selectWeightedDefault(BANKING_SEQUENCES.keySet());
    }

    /**
     * Reinforce a banking sequence that worked well.
     * 
     * @param sequenceType the sequence type to reinforce
     */
    public void reinforceBankingSequence(String sequenceType) {
        if (playerProfile != null) {
            playerProfile.reinforceBankingSequence(sequenceType);
            log.debug("Reinforced banking sequence: {}", sequenceType);
        }
    }

    // ========================================================================
    // Combat Preparation Sequences
    // ========================================================================

    /**
     * Get the combat preparation sequence to use.
     * 
     * @return ordered list of CombatPrepOperation
     */
    public List<CombatPrepOperation> getCombatPrepSequence() {
        String sequenceType = selectCombatPrepSequenceType();
        List<CombatPrepOperation> sequence = COMBAT_PREP_SEQUENCES.get(sequenceType);
        
        log.debug("Selected combat prep sequence: {} -> {}", sequenceType, sequence);
        return new ArrayList<>(sequence);
    }

    /**
     * Get the combat preparation sequence, filtering to only include specified operations.
     * 
     * @param neededOperations set of operations that are actually needed
     * @return ordered list of CombatPrepOperation (only those needed)
     */
    public List<CombatPrepOperation> getCombatPrepSequence(Set<CombatPrepOperation> neededOperations) {
        return getCombatPrepSequenceResult(neededOperations).getSequence();
    }

    /**
     * Get the combat preparation sequence along with the selected type so tasks can
     * reinforce preferences after success.
     *
     * @param neededOperations set of operations that are actually needed
     * @return SequenceResult containing type and ordered operations
     */
    public SequenceResult<CombatPrepOperation> getCombatPrepSequenceResult(Set<CombatPrepOperation> neededOperations) {
        String sequenceType = selectCombatPrepSequenceType();
        List<CombatPrepOperation> fullSequence = COMBAT_PREP_SEQUENCES.get(sequenceType);

        // Filter to only needed operations, maintaining sequence order
        List<CombatPrepOperation> filtered = new ArrayList<>();
        for (CombatPrepOperation op : fullSequence) {
            if (neededOperations.contains(op)) {
                filtered.add(op);
            }
        }

        log.debug("Selected combat prep sequence: {} -> {} (filtered from {})",
                sequenceType, filtered, fullSequence);
        return new SequenceResult<>(sequenceType, filtered);
    }

    /**
     * Select combat prep sequence type based on profile weights.
     */
    private String selectCombatPrepSequenceType() {
        if (playerProfile != null) {
            return playerProfile.selectCombatPrepSequence();
        }
        
        // Default weights if no profile
        return selectWeightedDefault(COMBAT_PREP_SEQUENCES.keySet());
    }

    /**
     * Reinforce a combat prep sequence that worked well.
     * 
     * @param sequenceType the sequence type to reinforce
     */
    public void reinforceCombatPrepSequence(String sequenceType) {
        if (playerProfile != null) {
            playerProfile.reinforceCombatPrepSequence(sequenceType);
            log.debug("Reinforced combat prep sequence: {}", sequenceType);
        }
    }

    // ========================================================================
    // Generic Sequence Support
    // ========================================================================

    /**
     * Result of sequence selection containing the type and elements.
     */
    public static class SequenceResult<T> {
        @Getter
        private final String sequenceType;
        @Getter
        private final List<T> sequence;
        
        public SequenceResult(String sequenceType, List<T> sequence) {
            this.sequenceType = sequenceType;
            this.sequence = sequence;
        }
    }

    /**
     * Get a generic sequence from custom sequence definitions.
     * Allows tasks to define their own sequence variations.
     * 
     * @param sequences map of sequence type to sequence elements
     * @param weights map of sequence type to weight (null for equal weights)
     * @param <T> element type
     * @return SequenceResult with selected type and elements
     */
    public <T> SequenceResult<T> getGenericSequence(Map<String, List<T>> sequences, 
                                                     @Nullable Map<String, Double> weights) {
        String selectedType = selectWeightedType(sequences.keySet(), weights);
        List<T> sequence = sequences.get(selectedType);
        
        return new SequenceResult<>(selectedType, new ArrayList<>(sequence));
    }

    /**
     * Randomize order of elements while respecting dependencies.
     * Useful for operations that can be done in any order.
     * 
     * @param elements list of elements to randomize
     * @param <T> element type
     * @return new list with randomized order
     */
    public <T> List<T> randomizeOrder(List<T> elements) {
        List<T> shuffled = new ArrayList<>(elements);
        
        // Fisher-Yates shuffle using our Randomization
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = randomization.uniformRandomInt(0, i);
            T temp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, temp);
        }
        
        return shuffled;
    }

    /**
     * Randomize order while keeping specified elements at fixed positions.
     * 
     * @param elements list of elements
     * @param fixedIndices indices that should not be moved
     * @param <T> element type
     * @return new list with partially randomized order
     */
    public <T> List<T> randomizeOrderPartially(List<T> elements, Set<Integer> fixedIndices) {
        List<T> result = new ArrayList<>(elements);
        List<Integer> mutableIndices = new ArrayList<>();
        
        for (int i = 0; i < elements.size(); i++) {
            if (!fixedIndices.contains(i)) {
                mutableIndices.add(i);
            }
        }
        
        // Collect elements at mutable positions
        List<T> mutableElements = new ArrayList<>();
        for (int idx : mutableIndices) {
            mutableElements.add(elements.get(idx));
        }
        
        // Shuffle mutable elements
        mutableElements = randomizeOrder(mutableElements);
        
        // Place back
        for (int i = 0; i < mutableIndices.size(); i++) {
            result.set(mutableIndices.get(i), mutableElements.get(i));
        }
        
        return result;
    }

    // ========================================================================
    // Item Order Randomization
    // ========================================================================

    /**
     * Result of item order selection for bank withdrawals/deposits.
     */
    public enum ItemOrderStrategy {
        /** Process items in the order they appear in the list */
        IN_ORDER,
        /** Process items in reverse order */
        REVERSE,
        /** Process items in random order */
        RANDOM,
        /** Process by slot position (left-to-right, top-to-bottom) */
        BY_SLOT
    }

    /**
     * Get the item processing order strategy.
     * Used for bank withdrawals, deposits, etc.
     * 
     * @return ItemOrderStrategy to use
     */
    public ItemOrderStrategy getItemOrderStrategy() {
        double roll = randomization.uniformRandom(0, 1);
        
        // Most common is in-order (list order or slot order)
        if (roll < 0.50) {
            return ItemOrderStrategy.IN_ORDER;
        } else if (roll < 0.70) {
            return ItemOrderStrategy.BY_SLOT;
        } else if (roll < 0.85) {
            return ItemOrderStrategy.RANDOM;
        } else {
            return ItemOrderStrategy.REVERSE;
        }
    }

    /**
     * Apply item order strategy to a list.
     * 
     * @param items items to order
     * @param strategy the ordering strategy
     * @param <T> item type
     * @return ordered list
     */
    public <T> List<T> applyItemOrderStrategy(List<T> items, ItemOrderStrategy strategy) {
        switch (strategy) {
            case REVERSE:
                List<T> reversed = new ArrayList<>(items);
                Collections.reverse(reversed);
                return reversed;
            case RANDOM:
                return randomizeOrder(items);
            case IN_ORDER:
            case BY_SLOT:
            default:
                return new ArrayList<>(items);
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Select a type based on weights.
     * 
     * @param types available types
     * @param weights weight map (null for equal weights)
     * @return selected type
     */
    private String selectWeightedType(Set<String> types, @Nullable Map<String, Double> weights) {
        if (weights == null || weights.isEmpty()) {
            return selectWeightedDefault(types);
        }
        
        double totalWeight = 0;
        for (String type : types) {
            totalWeight += weights.getOrDefault(type, 1.0);
        }
        
        double roll = randomization.uniformRandom(0, totalWeight);
        double cumulative = 0;
        
        for (String type : types) {
            cumulative += weights.getOrDefault(type, 1.0);
            if (roll <= cumulative) {
                return type;
            }
        }
        
        // Fallback
        return types.iterator().next();
    }

    /**
     * Select with equal weights.
     */
    private String selectWeightedDefault(Set<String> types) {
        int index = randomization.uniformRandomInt(0, types.size() - 1);
        int i = 0;
        for (String type : types) {
            if (i == index) {
                return type;
            }
            i++;
        }
        return types.iterator().next();
    }

    // ========================================================================
    // Sequence Type Queries
    // ========================================================================

    /**
     * Get all available banking sequence types.
     */
    public Set<String> getBankingSequenceTypes() {
        return Collections.unmodifiableSet(BANKING_SEQUENCES.keySet());
    }

    /**
     * Get all available combat prep sequence types.
     */
    public Set<String> getCombatPrepSequenceTypes() {
        return Collections.unmodifiableSet(COMBAT_PREP_SEQUENCES.keySet());
    }

    /**
     * Get the operations for a specific banking sequence type.
     */
    public List<BankOperation> getBankingSequenceByType(String type) {
        List<BankOperation> sequence = BANKING_SEQUENCES.get(type);
        return sequence != null ? new ArrayList<>(sequence) : Collections.emptyList();
    }

    /**
     * Get the operations for a specific combat prep sequence type.
     */
    public List<CombatPrepOperation> getCombatPrepSequenceByType(String type) {
        List<CombatPrepOperation> sequence = COMBAT_PREP_SEQUENCES.get(type);
        return sequence != null ? new ArrayList<>(sequence) : Collections.emptyList();
    }
}

