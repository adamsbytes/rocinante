package com.rocinante.tasks.impl;

import com.rocinante.combat.AttackStyle;
import com.rocinante.combat.GearSet;
import com.rocinante.combat.GearSwitcher;
import com.rocinante.core.GameStateService;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import net.runelite.api.Item;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EquipItemTask.
 */
public class EquipItemTaskTest {

    @Mock
    private TaskContext ctx;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private GearSwitcher gearSwitcher;

    @Mock
    private InventoryState inventoryState;

    @Mock
    private EquipmentState equipmentState;

    // Test item IDs
    private static final int SHORTBOW = 841;
    private static final int BRONZE_ARROW = 882;
    private static final int AIR_STAFF = 1381;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        when(ctx.getGearSwitcher()).thenReturn(gearSwitcher);
        when(ctx.getGameStateService()).thenReturn(gameStateService);
        when(gameStateService.getInventoryState()).thenReturn(inventoryState);
        when(gameStateService.getEquipmentState()).thenReturn(equipmentState);

        // Default: empty equipment
        when(equipmentState.getEquippedItem(anyInt())).thenReturn(Optional.empty());
        when(equipmentState.hasEquipped(anyInt())).thenReturn(false);
        when(equipmentState.getWeaponId()).thenReturn(-1);
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    public void testConstructor_SingleItem() {
        EquipItemTask task = new EquipItemTask(SHORTBOW);

        assertEquals(SHORTBOW, task.getItemId());
        assertNull(task.getGearSet());
        assertNull(task.getAttackStyle());
        assertTrue(task.getDescription().contains(String.valueOf(SHORTBOW)));
    }

    @Test
    public void testConstructor_GearSet() {
        GearSet gearSet = GearSet.builder()
                .name("Ranged")
                .weapon(SHORTBOW)
                .build();

        EquipItemTask task = new EquipItemTask(gearSet);

        assertEquals(-1, task.getItemId());
        assertEquals(gearSet, task.getGearSet());
        assertNull(task.getAttackStyle());
        assertTrue(task.getDescription().contains("Ranged"));
    }

    @Test
    public void testConstructor_AttackStyle() {
        EquipItemTask task = new EquipItemTask(AttackStyle.RANGED);

        assertEquals(-1, task.getItemId());
        assertNull(task.getGearSet());
        assertEquals(AttackStyle.RANGED, task.getAttackStyle());
        assertTrue(task.getDescription().toLowerCase().contains("ranged"));
    }

    // ========================================================================
    // canExecute Tests
    // ========================================================================

    @Test
    public void testCanExecute_NoGearSwitcher() {
        when(ctx.getGearSwitcher()).thenReturn(null);

        EquipItemTask task = new EquipItemTask(SHORTBOW);

        assertFalse(task.canExecute(ctx));
    }

    @Test
    public void testCanExecute_SingleItem_InInventory() {
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(true);

        EquipItemTask task = new EquipItemTask(SHORTBOW);

        assertTrue(task.canExecute(ctx));
    }

    @Test
    public void testCanExecute_SingleItem_AlreadyEquipped() {
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(false);
        when(equipmentState.hasEquipped(SHORTBOW)).thenReturn(true);

        EquipItemTask task = new EquipItemTask(SHORTBOW);

        assertTrue(task.canExecute(ctx));
    }

    @Test
    public void testCanExecute_SingleItem_NotAvailable() {
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(false);
        when(equipmentState.hasEquipped(SHORTBOW)).thenReturn(false);

        EquipItemTask task = new EquipItemTask(SHORTBOW);

        assertFalse(task.canExecute(ctx));
    }

    @Test
    public void testCanExecute_GearSet_Available() {
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(true);
        when(inventoryState.hasItem(BRONZE_ARROW)).thenReturn(true);

        GearSet gearSet = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        // Mock the isAvailable check
        when(equipmentState.getEquippedItem(EquipmentState.SLOT_WEAPON)).thenReturn(Optional.empty());
        when(equipmentState.getEquippedItem(EquipmentState.SLOT_AMMO)).thenReturn(Optional.empty());

        EquipItemTask task = new EquipItemTask(gearSet);

        assertTrue(task.canExecute(ctx));
    }

    @Test
    public void testCanExecute_GearSet_NotAvailable() {
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(true);
        when(inventoryState.hasItem(BRONZE_ARROW)).thenReturn(false); // Missing arrows

        GearSet gearSet = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        when(equipmentState.getEquippedItem(anyInt())).thenReturn(Optional.empty());

        EquipItemTask task = new EquipItemTask(gearSet);

        assertFalse(task.canExecute(ctx));
    }

    @Test
    public void testCanExecute_AttackStyle_AlwaysTrue() {
        // Attack style auto-detection is done during execution
        EquipItemTask task = new EquipItemTask(AttackStyle.RANGED);

        assertTrue(task.canExecute(ctx));
    }

    // ========================================================================
    // execute Tests
    // ========================================================================

    @Test
    public void testExecute_SingleItem_AlreadyEquipped() {
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(true);
        when(equipmentState.hasEquipped(SHORTBOW)).thenReturn(true);

        EquipItemTask task = new EquipItemTask(SHORTBOW);
        task.execute(ctx);

        assertEquals(TaskState.COMPLETED, task.getState());
        verify(gearSwitcher, never()).equipItem(anyInt());
    }

    @Test
    public void testExecute_SingleItem_Success() {
        // Item in inventory, not equipped
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(true);
        when(equipmentState.hasEquipped(SHORTBOW)).thenReturn(false);

        // GearSwitcher returns success
        when(gearSwitcher.equipItem(SHORTBOW)).thenReturn(CompletableFuture.completedFuture(true));

        EquipItemTask task = new EquipItemTask(SHORTBOW);
        task.execute(ctx);

        // Task should be running (waiting for completion check)
        assertEquals(TaskState.RUNNING, task.getState());
        verify(gearSwitcher).equipItem(SHORTBOW);

        // Simulate item now equipped
        when(equipmentState.hasEquipped(SHORTBOW)).thenReturn(true);
        task.execute(ctx);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testExecute_GearSet_AlreadyEquipped() {
        // Create gear set
        GearSet gearSet = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        // Both items already equipped
        Item equippedBow = mock(Item.class);
        when(equippedBow.getId()).thenReturn(SHORTBOW);
        when(equipmentState.getEquippedItem(EquipmentState.SLOT_WEAPON)).thenReturn(Optional.of(equippedBow));

        Item equippedArrows = mock(Item.class);
        when(equippedArrows.getId()).thenReturn(BRONZE_ARROW);
        when(equipmentState.getEquippedItem(EquipmentState.SLOT_AMMO)).thenReturn(Optional.of(equippedArrows));

        // Enable canExecute
        when(inventoryState.hasItem(anyInt())).thenReturn(true);

        EquipItemTask task = new EquipItemTask(gearSet);
        task.execute(ctx);

        assertEquals(TaskState.COMPLETED, task.getState());
        verify(gearSwitcher, never()).switchTo(any(GearSet.class));
    }

    @Test
    public void testExecute_GearSet_Success() {
        GearSet gearSet = GearSet.builder()
                .weapon(SHORTBOW)
                .ammo(BRONZE_ARROW)
                .build();

        // Items in inventory
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(true);
        when(inventoryState.hasItem(BRONZE_ARROW)).thenReturn(true);

        // Not yet equipped
        when(equipmentState.getEquippedItem(anyInt())).thenReturn(Optional.empty());

        // GearSwitcher returns success
        when(gearSwitcher.switchTo(gearSet)).thenReturn(CompletableFuture.completedFuture(true));

        EquipItemTask task = new EquipItemTask(gearSet);
        task.execute(ctx);

        assertEquals(TaskState.RUNNING, task.getState());
        verify(gearSwitcher).switchTo(gearSet);

        // Simulate items now equipped
        Item equippedBow = mock(Item.class);
        when(equippedBow.getId()).thenReturn(SHORTBOW);
        when(equipmentState.getEquippedItem(EquipmentState.SLOT_WEAPON)).thenReturn(Optional.of(equippedBow));

        Item equippedArrows = mock(Item.class);
        when(equippedArrows.getId()).thenReturn(BRONZE_ARROW);
        when(equipmentState.getEquippedItem(EquipmentState.SLOT_AMMO)).thenReturn(Optional.of(equippedArrows));

        task.execute(ctx);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    @Test
    public void testExecute_AttackStyle_Success() {
        // Set up ranged gear in inventory - mock hasItem for all ranged weapons
        // Since GearSet.autoDetect iterates through RANGED_WEAPONS set (unordered),
        // we need to make sure at least one ranged weapon returns true
        when(inventoryState.hasItem(SHORTBOW)).thenReturn(true);
        when(inventoryState.hasItem(BRONZE_ARROW)).thenReturn(true);

        // The implementation auto-detects gear set from inventory, then calls switchTo
        when(gearSwitcher.switchTo(any(GearSet.class))).thenReturn(CompletableFuture.completedFuture(true));

        EquipItemTask task = new EquipItemTask(AttackStyle.RANGED);
        task.execute(ctx);

        assertEquals(TaskState.RUNNING, task.getState());
        verify(gearSwitcher).switchTo(any(GearSet.class));
    }

    @Test
    public void testExecute_AttackStyle_NoGearFound() {
        // No ranged gear available
        when(inventoryState.hasItem(anyInt())).thenReturn(false);
        when(equipmentState.getEquippedItem(anyInt())).thenReturn(Optional.empty());
        when(equipmentState.getWeaponId()).thenReturn(-1);

        EquipItemTask task = new EquipItemTask(AttackStyle.RANGED);
        task.execute(ctx);

        // Should fail since no gear detected and not already equipped for style
        assertEquals(TaskState.FAILED, task.getState());
    }

    @Test
    public void testExecute_AttackStyle_AlreadyEquippedForStyle() {
        // Already have ranged weapon equipped
        when(equipmentState.getWeaponId()).thenReturn(SHORTBOW);

        EquipItemTask task = new EquipItemTask(AttackStyle.RANGED);
        task.execute(ctx);

        assertEquals(TaskState.COMPLETED, task.getState());
        verify(gearSwitcher, never()).equipForStyle(any());
    }

    @Test
    public void testExecute_MeleeStyle_AlwaysComplete() {
        // Melee is default - unarmed or non-ranged/magic weapon
        when(equipmentState.getWeaponId()).thenReturn(-1); // Unarmed

        EquipItemTask task = new EquipItemTask(AttackStyle.MELEE);
        task.execute(ctx);

        assertEquals(TaskState.COMPLETED, task.getState());
    }

    // ========================================================================
    // Builder Method Tests
    // ========================================================================

    @Test
    public void testWithDescription() {
        EquipItemTask task = new EquipItemTask(SHORTBOW)
                .withDescription("Custom description");

        assertEquals("Custom description", task.getDescription());
    }
}

