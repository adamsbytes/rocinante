package com.rocinante.input;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InventoryClickHelper.
 *
 * Tests the inventory slot bounds calculation and humanized click offset generation
 * per REQUIREMENTS.md Section 3.1.2.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class InventoryClickHelperTest {

    private static final int SAMPLE_SIZE = 1000;
    private static final double TOLERANCE = 0.15; // 15% tolerance

    @Mock
    private Client client;

    @Mock
    private RobotMouseController mouseController;

    @Mock
    private Widget inventoryWidget;

    private InventoryClickHelper helper;

    @Before
    public void setUp() {
        helper = new InventoryClickHelper(client, mouseController);
    }

    // ========================================================================
    // Slot Validation Tests
    // ========================================================================

    @Test
    public void testExecuteClick_InvalidSlotNegative_ReturnsFalse() {
        CompletableFuture<Boolean> result = helper.executeClick(-1);
        assertFalse("Negative slot should return false", result.join());
    }

    @Test
    public void testExecuteClick_InvalidSlotTooHigh_ReturnsFalse() {
        CompletableFuture<Boolean> result = helper.executeClick(28);
        assertFalse("Slot >= 28 should return false", result.join());
    }

    @Test
    public void testExecuteClick_InvalidSlotWayTooHigh_ReturnsFalse() {
        CompletableFuture<Boolean> result = helper.executeClick(100);
        assertFalse("Slot >= 28 should return false", result.join());
    }

    @Test
    public void testExecuteClick_ValidSlot_WidgetNotVisible_ReturnsFalse() {
        when(client.getWidget(WidgetInfo.INVENTORY)).thenReturn(null);

        CompletableFuture<Boolean> result = helper.executeClick(0);
        assertFalse("Should return false when inventory widget not visible", result.join());
    }

    @Test
    public void testExecuteClick_ValidSlot_WidgetHidden_ReturnsFalse() {
        when(client.getWidget(WidgetInfo.INVENTORY)).thenReturn(inventoryWidget);
        when(inventoryWidget.isHidden()).thenReturn(true);

        CompletableFuture<Boolean> result = helper.executeClick(0);
        assertFalse("Should return false when inventory widget hidden", result.join());
    }

    // ========================================================================
    // Slot Bounds Calculation Tests
    // ========================================================================

    @Test
    public void testGetInventorySlotBounds_InvalidSlotNegative() {
        Rectangle bounds = helper.getInventorySlotBounds(inventoryWidget, -1);
        assertNull("Negative slot should return null bounds", bounds);
    }

    @Test
    public void testGetInventorySlotBounds_InvalidSlotTooHigh() {
        Rectangle bounds = helper.getInventorySlotBounds(inventoryWidget, 28);
        assertNull("Slot >= 28 should return null bounds", bounds);
    }

    @Test
    public void testGetInventorySlotBounds_ValidSlot_ManualCalculation() {
        // Set up widget with no children (forces manual calculation)
        when(inventoryWidget.getDynamicChildren()).thenReturn(null);
        when(inventoryWidget.getBounds()).thenReturn(new Rectangle(561, 213, 176, 259));

        // Test slot 0 (top-left)
        Rectangle bounds0 = helper.getInventorySlotBounds(inventoryWidget, 0);
        assertNotNull("Slot 0 should have bounds", bounds0);
        assertEquals("Slot 0 X should be widget X", 561, bounds0.x);
        assertEquals("Slot 0 Y should be widget Y", 213, bounds0.y);

        // Test slot 3 (end of first row)
        Rectangle bounds3 = helper.getInventorySlotBounds(inventoryWidget, 3);
        assertNotNull("Slot 3 should have bounds", bounds3);
        assertEquals("Slot 3 X should be in 4th column", 561 + 3 * (176 / 4), bounds3.x);
        assertEquals("Slot 3 Y should be in first row", 213, bounds3.y);

        // Test slot 4 (start of second row)
        Rectangle bounds4 = helper.getInventorySlotBounds(inventoryWidget, 4);
        assertNotNull("Slot 4 should have bounds", bounds4);
        assertEquals("Slot 4 X should be in first column", 561, bounds4.x);
        assertEquals("Slot 4 Y should be in second row", 213 + 1 * (259 / 7), bounds4.y);

        // Test slot 27 (last slot, bottom-right)
        Rectangle bounds27 = helper.getInventorySlotBounds(inventoryWidget, 27);
        assertNotNull("Slot 27 should have bounds", bounds27);
        assertEquals("Slot 27 X should be in 4th column", 561 + 3 * (176 / 4), bounds27.x);
        assertEquals("Slot 27 Y should be in 7th row", 213 + 6 * (259 / 7), bounds27.y);
    }

    @Test
    public void testGetInventorySlotBounds_ValidSlot_FromWidgetChildren() {
        // Set up widget with a child widget for slot 5
        Widget slotWidget = mock(Widget.class);
        Rectangle expectedBounds = new Rectangle(600, 250, 40, 36);
        when(slotWidget.getBounds()).thenReturn(expectedBounds);

        Widget[] children = new Widget[28];
        children[5] = slotWidget;
        when(inventoryWidget.getDynamicChildren()).thenReturn(children);

        Rectangle bounds = helper.getInventorySlotBounds(inventoryWidget, 5);
        assertNotNull("Slot 5 should have bounds from child widget", bounds);
        assertEquals("Bounds should match child widget", expectedBounds, bounds);
    }

    @Test
    public void testGetInventorySlotBounds_FallbackWhenChildNull() {
        // Set up widget with null child at slot 5 (falls back to manual calculation)
        Widget[] children = new Widget[28];
        children[5] = null;
        when(inventoryWidget.getDynamicChildren()).thenReturn(children);
        when(inventoryWidget.getBounds()).thenReturn(new Rectangle(561, 213, 176, 259));

        Rectangle bounds = helper.getInventorySlotBounds(inventoryWidget, 5);
        assertNotNull("Slot 5 should have calculated bounds when child is null", bounds);
    }

    // ========================================================================
    // Random Offset Tests (Section 3.1.2 Click Behavior)
    // ========================================================================

    @Test
    public void testRandomOffset_WithinValidRange() {
        int dimension = 40;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int offset = helper.randomOffset(dimension);
            assertTrue("Offset should be at least 2", offset >= 2);
            assertTrue("Offset should be at most dimension - 2", offset <= dimension - 2);
        }
    }

    @Test
    public void testRandomOffset_CenteredAround45To55Percent() {
        int dimension = 100;
        double sum = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int offset = helper.randomOffset(dimension);
            sum += offset;
        }

        double mean = sum / SAMPLE_SIZE;
        double expectedCenter = dimension * 0.5; // Mean of 45-55% is 50%

        // Mean should be close to 50% of dimension
        assertEquals("Mean offset should be near 50% of dimension",
                expectedCenter, mean, dimension * TOLERANCE);
    }

    @Test
    public void testRandomOffset_StandardDeviationApprox15Percent() {
        int dimension = 100;
        double sum = 0;
        int[] offsets = new int[SAMPLE_SIZE];

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            offsets[i] = helper.randomOffset(dimension);
            sum += offsets[i];
        }

        double mean = sum / SAMPLE_SIZE;

        // Calculate standard deviation
        double sumSquaredDiff = 0;
        for (int offset : offsets) {
            sumSquaredDiff += Math.pow(offset - mean, 2);
        }
        double stdDev = Math.sqrt(sumSquaredDiff / SAMPLE_SIZE);

        // Expected stdDev is 15% of dimension
        double expectedStdDev = dimension * 0.15;

        // Allow 50% tolerance for the standard deviation check
        assertTrue("Standard deviation should be approximately 15% of dimension",
                stdDev > expectedStdDev * 0.5 && stdDev < expectedStdDev * 1.5);
    }

    @Test
    public void testRandomOffset_SmallDimension() {
        int dimension = 10;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int offset = helper.randomOffset(dimension);
            assertTrue("Offset should be at least 2 for small dimension", offset >= 2);
            assertTrue("Offset should be at most 8 for small dimension", offset <= 8);
        }
    }

    @Test
    public void testRandomOffset_NeverExactCenter() {
        int dimension = 50;
        int exactCenter = dimension / 2;
        int exactCenterCount = 0;

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int offset = helper.randomOffset(dimension);
            if (offset == exactCenter) {
                exactCenterCount++;
            }
        }

        // Per Section 3.1.2: "Never click the geometric center"
        // With Gaussian distribution, exact center should be rare (< 10%)
        double exactCenterRatio = (double) exactCenterCount / SAMPLE_SIZE;
        assertTrue("Exact center hits should be rare (< 10%)", exactCenterRatio < 0.10);
    }

    // ========================================================================
    // Click Execution Tests
    // ========================================================================

    @Test
    public void testExecuteClick_Success() {
        // Set up mocks for successful click
        when(client.getWidget(WidgetInfo.INVENTORY)).thenReturn(inventoryWidget);
        when(inventoryWidget.isHidden()).thenReturn(false);
        when(inventoryWidget.getDynamicChildren()).thenReturn(null);
        when(inventoryWidget.getBounds()).thenReturn(new Rectangle(561, 213, 176, 259));

        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));

        CompletableFuture<Boolean> result = helper.executeClick(0, "Test click");
        assertTrue("Click should succeed", result.join());

        // Verify mouse controller was called
        verify(mouseController).moveToCanvas(anyInt(), anyInt());
        verify(mouseController).click();
    }

    @Test
    public void testExecuteClick_WithDescription_LogsCorrectly() {
        // Set up mocks
        when(client.getWidget(WidgetInfo.INVENTORY)).thenReturn(inventoryWidget);
        when(inventoryWidget.isHidden()).thenReturn(false);
        when(inventoryWidget.getDynamicChildren()).thenReturn(null);
        when(inventoryWidget.getBounds()).thenReturn(new Rectangle(561, 213, 176, 259));

        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));

        // Should not throw with description
        CompletableFuture<Boolean> result = helper.executeClick(5, "Eating shark");
        assertTrue("Click with description should succeed", result.join());
    }

    @Test
    public void testExecuteClick_WithoutDescription_Works() {
        // Set up mocks
        when(client.getWidget(WidgetInfo.INVENTORY)).thenReturn(inventoryWidget);
        when(inventoryWidget.isHidden()).thenReturn(false);
        when(inventoryWidget.getDynamicChildren()).thenReturn(null);
        when(inventoryWidget.getBounds()).thenReturn(new Rectangle(561, 213, 176, 259));

        when(mouseController.moveToCanvas(anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mouseController.click())
                .thenReturn(CompletableFuture.completedFuture(null));

        // Should work without description (null)
        CompletableFuture<Boolean> result = helper.executeClick(5);
        assertTrue("Click without description should succeed", result.join());
    }

    @Test
    public void testExecuteClick_MouseControllerFailure_ReturnsFalse() {
        // Set up mocks for failed click
        when(client.getWidget(WidgetInfo.INVENTORY)).thenReturn(inventoryWidget);
        when(inventoryWidget.isHidden()).thenReturn(false);
        when(inventoryWidget.getDynamicChildren()).thenReturn(null);
        when(inventoryWidget.getBounds()).thenReturn(new Rectangle(561, 213, 176, 259));

        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Mouse movement failed"));
        when(mouseController.moveToCanvas(anyInt(), anyInt())).thenReturn(failedFuture);

        CompletableFuture<Boolean> result = helper.executeClick(0);
        assertFalse("Click should fail when mouse controller fails", result.join());
    }
}

