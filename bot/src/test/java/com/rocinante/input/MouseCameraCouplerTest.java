package com.rocinante.input;

import com.rocinante.util.Randomization;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MouseCameraCoupler.
 * Tests camera-mouse coupling, idle drift, and off-screen target handling.
 */
public class MouseCameraCouplerTest {

    @Mock
    private Client client;
    
    @Mock
    private CameraController cameraController;
    
    @Mock
    private Canvas canvas;
    
    @Mock
    private Player localPlayer;
    
    private Randomization randomization;
    private MouseCameraCoupler coupler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        randomization = new Randomization(12345L);
        
        // Mock client
        when(client.getCanvas()).thenReturn(canvas);
        when(canvas.getWidth()).thenReturn(800);
        when(canvas.getHeight()).thenReturn(600);
        when(client.getLocalPlayer()).thenReturn(localPlayer);
        when(localPlayer.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
        
        // Mock camera controller
        when(cameraController.isRotating()).thenReturn(false);
        
        coupler = new MouseCameraCoupler(client, cameraController, randomization);
    }

    @Test
    public void testIsOnScreen_CenterIsOnScreen() {
        assertTrue(coupler.isOnScreen(400, 300)); // Center
    }

    @Test
    public void testIsOnScreen_EdgeIsOffScreen() {
        assertFalse(coupler.isOnScreen(10, 300)); // Left edge
        assertFalse(coupler.isOnScreen(790, 300)); // Right edge
        assertFalse(coupler.isOnScreen(400, 10)); // Top edge
        assertFalse(coupler.isOnScreen(400, 590)); // Bottom edge
    }

    @Test
    public void testIsOnScreen_MarginRespected() {
        // Points just inside margin should be off-screen
        assertFalse(coupler.isOnScreen(49, 300)); // Just inside left margin
        assertFalse(coupler.isOnScreen(751, 300)); // Just inside right margin
    }

    @Test
    public void testIsEnabled_DefaultTrue() {
        assertTrue(coupler.isEnabled());
    }

    @Test
    public void testSetEnabled_ChangesState() {
        coupler.setEnabled(false);
        assertFalse(coupler.isEnabled());
        
        coupler.setEnabled(true);
        assertTrue(coupler.isEnabled());
    }

    @Test
    public void testOnBeforeMouseMove_ShortMovement_NoCameraAction() throws Exception {
        // Short movement (< 500px) should not trigger camera
        coupler.onBeforeMouseMove(100, 100, 200, 200).get();
        
        // Should not have called any camera rotation methods
        verify(cameraController, never()).rotateBy(anyDouble(), anyDouble());
    }

    @Test
    public void testOnBeforeMouseMove_WhenDisabled_NoCameraAction() throws Exception {
        coupler.setEnabled(false);
        
        // Even long movement should not trigger camera when disabled
        coupler.onBeforeMouseMove(0, 0, 1000, 1000).get();
        
        verify(cameraController, never()).rotateBy(anyDouble(), anyDouble());
    }

    @Test
    public void testOnBeforeMouseMove_WhenAlreadyRotating_NoCameraAction() throws Exception {
        when(cameraController.isRotating()).thenReturn(true);
        
        coupler.onBeforeMouseMove(0, 0, 1000, 1000).get();
        
        verify(cameraController, never()).rotateBy(anyDouble(), anyDouble());
    }

    @Test
    public void testGetTimeSinceLastCameraMovement_DelegatestoController() {
        when(cameraController.getTimeSinceLastMovement()).thenReturn(5000L);
        
        assertEquals(5000L, coupler.getTimeSinceLastCameraMovement());
        verify(cameraController).getTimeSinceLastMovement();
    }

    @Test
    public void testReset_ResetsTimingState() {
        // Call reset
        coupler.reset();
        
        // Verify timing is reset (getTimeSinceLastDrift should be near 0)
        assertTrue(coupler.getTimeSinceLastDrift().toMillis() < 1000);
    }

    @Test
    public void testTick_WhenDisabled_DoesNothing() {
        coupler.setEnabled(false);
        coupler.tick();
        
        // Should not trigger any camera operations
        verify(cameraController, never()).performIdleDrift();
        verify(cameraController, never()).perform360LookAround();
    }

    @Test
    public void testTick_WhenAlreadyRotating_DoesNothing() {
        when(cameraController.isRotating()).thenReturn(true);
        coupler.tick();
        
        // Should not trigger any camera operations
        verify(cameraController, never()).performIdleDrift();
        verify(cameraController, never()).perform360LookAround();
    }

    @Test
    public void testCheckCameraHold_WhenDisabled_ReturnsImmediately() throws Exception {
        coupler.setEnabled(false);
        
        coupler.checkCameraHold().get();
        
        verify(cameraController, never()).performCameraHold();
    }

    @Test
    public void testCheckCameraHold_WhenRotating_ReturnsImmediately() throws Exception {
        when(cameraController.isRotating()).thenReturn(true);
        
        coupler.checkCameraHold().get();
        
        verify(cameraController, never()).performCameraHold();
    }

    @Test
    public void testCoupledMovementPlan_HasCorrectFields() {
        MouseCameraCoupler.CoupledMovementPlan plan = 
            new MouseCameraCoupler.CoupledMovementPlan(45.0, 100, 100, 400, 300);
        
        assertEquals(45.0, plan.getCameraRotationDegrees(), 0.01);
        assertEquals(100, plan.getMouseStartX());
        assertEquals(100, plan.getMouseStartY());
        assertEquals(400, plan.getEstimatedTargetX());
        assertEquals(300, plan.getEstimatedTargetY());
    }
}

