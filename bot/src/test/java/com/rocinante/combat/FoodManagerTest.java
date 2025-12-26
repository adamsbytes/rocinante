package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FoodManager class.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FoodManagerTest {

    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    private FoodManager foodManager;

    @Before
    public void setUp() {
        foodManager = new FoodManager(client, gameStateService);
        foodManager.setConfig(FoodConfig.DEFAULT);
        foodManager.setHcimMode(false);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private PlayerState createPlayerState(int currentHp, int maxHp) {
        return PlayerState.builder()
                .worldPosition(null) // Will be set to non-null for valid state
                .currentHitpoints(currentHp)
                .maxHitpoints(maxHp)
                .build();
    }

    private PlayerState createValidPlayerState(int currentHp, int maxHp) {
        return PlayerState.builder()
                .worldPosition(new net.runelite.api.coords.WorldPoint(3200, 3200, 0))
                .currentHitpoints(currentHp)
                .maxHitpoints(maxHp)
                .build();
    }

    private InventoryState createInventoryWithFood(int... foodIds) {
        Item[] items = new Item[28];
        for (int i = 0; i < foodIds.length && i < 28; i++) {
            items[i] = createItem(foodIds[i], 1);
        }
        return new InventoryState(items);
    }

    private InventoryState createEmptyInventory() {
        return new InventoryState(new Item[28]);
    }

    private Item createItem(int id, int quantity) {
        Item item = mock(Item.class);
        when(item.getId()).thenReturn(id);
        when(item.getQuantity()).thenReturn(quantity);
        return item;
    }

    // ========================================================================
    // Basic Functionality Tests
    // ========================================================================

    @Test
    public void testCanEat_NoDelayInitially() {
        assertTrue(foodManager.canEat(0));
        assertTrue(foodManager.canEat(100));
    }

    @Test
    public void testCanEat_DelayAfterEating() {
        foodManager.onFoodEaten(10);

        // Should not be able to eat for 3 ticks
        assertFalse(foodManager.canEat(10));
        assertFalse(foodManager.canEat(11));
        assertFalse(foodManager.canEat(12));

        // Should be able to eat after 3 ticks
        assertTrue(foodManager.canEat(13));
        assertTrue(foodManager.canEat(14));
    }

    @Test
    public void testReset_ClearsState() {
        foodManager.onFoodEaten(10);
        foodManager.onBrewUsed();
        foodManager.onBrewUsed();

        assertEquals(2, foodManager.getBrewSipsSinceRestore());

        foodManager.reset();

        assertEquals(-1, foodManager.getLastEatTick());
        assertEquals(0, foodManager.getBrewSipsSinceRestore());
        assertTrue(foodManager.canEat(0));
    }

    // ========================================================================
    // Check and Eat Tests
    // ========================================================================

    @Test
    public void testCheckAndEat_NullWhenPlayerInvalid() {
        PlayerState invalidState = PlayerState.EMPTY;
        when(gameStateService.getPlayerState()).thenReturn(invalidState);

        CombatAction action = foodManager.checkAndEat(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndEat_NullWhenHealthAboveThreshold() {
        PlayerState player = createValidPlayerState(80, 99); // ~80% health
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkAndEat(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndEat_EatsAtPrimaryThreshold() {
        // 50% threshold by default, 45/99 = ~45%
        PlayerState player = createValidPlayerState(45, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkAndEat(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.EAT, action.getType());
        assertEquals(CombatAction.Priority.HIGH, action.getPriority());
    }

    @Test
    public void testCheckAndEat_PanicEatAtPanicThreshold() {
        // 25% threshold by default, 20/99 = ~20%
        PlayerState player = createValidPlayerState(20, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkAndEat(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.EAT, action.getType());
        assertEquals(CombatAction.Priority.URGENT, action.getPriority());
    }

    @Test
    public void testCheckAndEat_NullWhenNoFood() {
        PlayerState player = createValidPlayerState(20, 99);
        InventoryState inventory = createEmptyInventory();

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkAndEat(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndEat_NullWhenOnCooldown() {
        PlayerState player = createValidPlayerState(20, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        // Record an eat
        foodManager.onFoodEaten(10);

        // Try to eat again too soon
        CombatAction action = foodManager.checkAndEat(11);

        assertNull(action);
    }

    // ========================================================================
    // HCIM Mode Tests
    // ========================================================================

    @Test
    public void testCheckAndEat_HcimHigherThresholds() {
        foodManager.setHcimMode(true);
        foodManager.setConfig(FoodConfig.HCIM_SAFE);

        // 60% health - would not eat normally, but HCIM has 65% threshold
        PlayerState player = createValidPlayerState(60, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkAndEat(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.EAT, action.getType());
    }

    @Test
    public void testCheckPreEat_OnlyInHcimMode() {
        // Pre-eat disabled in normal mode
        PlayerState player = createValidPlayerState(60, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkPreEat(0);

        assertNull(action);
    }

    @Test
    public void testCheckPreEat_HcimPreEatsBeforeEngaging() {
        foodManager.setHcimMode(true);
        foodManager.setConfig(FoodConfig.HCIM_SAFE);

        // 65% health - below HCIM pre-eat threshold of 70%
        PlayerState player = createValidPlayerState(65, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkPreEat(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.EAT, action.getType());
    }

    // ========================================================================
    // Combo Eating Tests
    // ========================================================================

    @Test
    public void testComboEat_WithKarambwan() {
        // Panic situation - should always try combo eat
        PlayerState player = createValidPlayerState(20, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK, ItemID.COOKED_KARAMBWAN);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkAndEat(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.EAT, action.getType());
        assertTrue(action.isComboEat());
        assertTrue(action.getPrimarySlot() >= 0);
        assertTrue(action.getSecondarySlot() >= 0);
    }

    @Test
    public void testComboEat_NoKarambwanAvailable() {
        // Panic situation but no karambwan
        PlayerState player = createValidPlayerState(20, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK, ItemID.LOBSTER);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkAndEat(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.EAT, action.getType());
        assertFalse(action.isComboEat());
        assertEquals(-1, action.getSecondarySlot());
    }

    @Test
    public void testComboEat_DisabledInConfig() {
        foodManager.setConfig(FoodConfig.builder()
                .primaryEatThreshold(0.50)
                .panicEatThreshold(0.25)
                .useComboEating(false)
                .build());

        PlayerState player = createValidPlayerState(20, 99);
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK, ItemID.COOKED_KARAMBWAN);

        when(gameStateService.getPlayerState()).thenReturn(player);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        CombatAction action = foodManager.checkAndEat(0);

        assertNotNull(action);
        assertFalse(action.isComboEat());
    }

    // ========================================================================
    // Saradomin Brew Tests
    // ========================================================================

    @Test
    public void testBrewTracking() {
        assertEquals(0, foodManager.getBrewSipsSinceRestore());

        foodManager.onBrewUsed();
        assertEquals(1, foodManager.getBrewSipsSinceRestore());

        foodManager.onBrewUsed();
        assertEquals(2, foodManager.getBrewSipsSinceRestore());

        foodManager.onRestoreUsed();
        assertEquals(0, foodManager.getBrewSipsSinceRestore());
    }

    @Test
    public void testHasSaradominBrew() {
        InventoryState withBrew = createInventoryWithFood(ItemID.SARADOMIN_BREW4);
        InventoryState withoutBrew = createInventoryWithFood(ItemID.SHARK);

        assertTrue(foodManager.hasSaradominBrew(withBrew));
        assertFalse(foodManager.hasSaradominBrew(withoutBrew));
    }

    // ========================================================================
    // Food Availability Tests
    // ========================================================================

    @Test
    public void testHasEnoughFood_Normal() {
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK, ItemID.SHARK, ItemID.SHARK);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        // Default minimum is 2
        assertTrue(foodManager.hasEnoughFood());
    }

    @Test
    public void testHasEnoughFood_InsufficientForHcim() {
        foodManager.setHcimMode(true);
        foodManager.setConfig(FoodConfig.HCIM_SAFE);

        // Only 2 food, but HCIM needs 4
        InventoryState inventory = createInventoryWithFood(ItemID.SHARK, ItemID.SHARK);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        assertFalse(foodManager.hasEnoughFood());
    }

    @Test
    public void testGetFoodCount() {
        // Use food IDs that are in InventoryState.FOOD_IDS
        // 385 = Shark (in FOOD_IDS), 379 = Lobster (in FOOD_IDS), 391 = Manta ray (in FOOD_IDS)
        InventoryState inventory = createInventoryWithFood(385, 379, 391);
        when(gameStateService.getInventoryState()).thenReturn(inventory);

        assertEquals(3, foodManager.getFoodCount());
    }

    // ========================================================================
    // FoodConfig Tests
    // ========================================================================

    @Test
    public void testFoodConfig_DefaultValues() {
        FoodConfig config = FoodConfig.DEFAULT;

        assertEquals(0.50, config.getPrimaryEatThreshold(), 0.001);
        assertEquals(0.25, config.getPanicEatThreshold(), 0.001);
        assertEquals(2, config.getMinimumFoodCount());
        assertTrue(config.isUseComboEating());
        assertFalse(config.isPreEatBeforeEngaging());
    }

    @Test
    public void testFoodConfig_HcimValues() {
        FoodConfig config = FoodConfig.HCIM_SAFE;

        assertEquals(0.65, config.getPrimaryEatThreshold(), 0.001);
        assertEquals(0.40, config.getPanicEatThreshold(), 0.001);
        assertEquals(4, config.getMinimumFoodCount());
        assertTrue(config.isPreEatBeforeEngaging());
        assertEquals(0.70, config.getPreEatThreshold(), 0.001);
    }

    @Test
    public void testFoodConfig_EffectiveThresholds() {
        FoodConfig config = FoodConfig.builder()
                .primaryEatThreshold(0.50)
                .panicEatThreshold(0.25)
                .minimumFoodCount(2)
                .build();

        // Normal mode
        assertEquals(0.50, config.getEffectiveEatThreshold(false), 0.001);
        assertEquals(0.25, config.getEffectivePanicThreshold(false), 0.001);
        assertEquals(2, config.getEffectiveMinFoodCount(false));

        // HCIM mode - enforces minimums
        assertEquals(0.65, config.getEffectiveEatThreshold(true), 0.001);
        assertEquals(0.40, config.getEffectivePanicThreshold(true), 0.001);
        assertEquals(4, config.getEffectiveMinFoodCount(true));
    }

    @Test
    public void testFoodConfig_PanicEatRange() {
        FoodConfig config = FoodConfig.builder()
                .panicEatHealthMin(0.55)
                .panicEatHealthMax(0.60)
                .build();

        assertTrue(config.isInPanicEatRange(0.55));
        assertTrue(config.isInPanicEatRange(0.57));
        assertTrue(config.isInPanicEatRange(0.60));
        assertFalse(config.isInPanicEatRange(0.54));
        assertFalse(config.isInPanicEatRange(0.61));
    }
}

