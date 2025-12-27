package com.rocinante.input;

import com.rocinante.behavior.PlayerProfile;
import com.rocinante.util.PerlinNoise;
import com.rocinante.util.Randomization;
import net.runelite.api.Client;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CameraController.
 * Tests camera rotation, camera hold, and idle drift behaviors.
 */
public class CameraControllerTest {

    @Mock
    private Client client;
    
    @Mock
    private Robot robot;
    
    @Mock
    private Canvas canvas;
    
    private Randomization randomization;
    private PerlinNoise perlinNoise;
    private ScheduledExecutorService executor;
    private CameraController cameraController;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        randomization = new Randomization(12345L);
        perlinNoise = new PerlinNoise();
        executor = Executors.newSingleThreadScheduledExecutor();
        
        // Mock client
        when(client.getCameraYaw()).thenReturn(0);
        when(client.getCameraPitch()).thenReturn(256);
        when(client.getCanvas()).thenReturn(canvas);
        when(canvas.getWidth()).thenReturn(800);
        when(canvas.getHeight()).thenReturn(600);
        when(canvas.getLocationOnScreen()).thenReturn(new Point(100, 100));
        
        cameraController = new CameraController(robot, client, randomization, perlinNoise, executor);
    }

    @Test
    public void testDegreesToJau_ConversionsCorrect() {
        assertEquals(2048, CameraController.degreesToJau(360));
        assertEquals(1024, CameraController.degreesToJau(180));
        assertEquals(512, CameraController.degreesToJau(90));
        assertEquals(0, CameraController.degreesToJau(0));
    }

    @Test
    public void testJauToDegrees_ConversionsCorrect() {
        assertEquals(360.0, CameraController.jauToDegrees(2048), 0.01);
        assertEquals(180.0, CameraController.jauToDegrees(1024), 0.01);
        assertEquals(90.0, CameraController.jauToDegrees(512), 0.01);
        assertEquals(0.0, CameraController.jauToDegrees(0), 0.01);
    }

    @Test
    public void testGetCurrentYawDegrees_ReturnsCurrent() {
        when(client.getCameraYaw()).thenReturn(1024); // 180 degrees
        
        double yaw = cameraController.getCurrentYawDegrees();
        
        assertEquals(180.0, yaw, 0.5);
    }

    @Test
    public void testGetCurrentPitchDegrees_ReturnsCurrent() {
        when(client.getCameraPitch()).thenReturn(256); // ~45 degrees
        
        double pitch = cameraController.getCurrentPitchDegrees();
        
        assertTrue(pitch > 40 && pitch < 50);
    }

    @Test
    public void testIsRotating_InitiallyFalse() {
        assertFalse(cameraController.isRotating());
    }

    @Test
    public void testShouldPerformCameraHold_WithoutProfile_DefaultChance() {
        // Without profile, uses 15% default
        int holdCount = 0;
        for (int i = 0; i < 1000; i++) {
            if (cameraController.shouldPerformCameraHold()) {
                holdCount++;
            }
        }
        
        // Should be roughly 15% (150 +/- 50)
        assertTrue("Camera hold frequency should be around 15%", 
                holdCount > 100 && holdCount < 250);
    }

    @Test
    public void testShouldPerformCameraHold_WithProfile_UsesProfileFrequency() {
        PlayerProfile profile = mock(PlayerProfile.class);
        PlayerProfile.ProfileData profileData = mock(PlayerProfile.ProfileData.class);
        when(profile.getProfileData()).thenReturn(profileData);
        when(profileData.getCameraHoldFrequency()).thenReturn(0.50); // 50%
        
        cameraController.setPlayerProfile(profile);
        
        int holdCount = 0;
        for (int i = 0; i < 1000; i++) {
            if (cameraController.shouldPerformCameraHold()) {
                holdCount++;
            }
        }
        
        // Should be roughly 50% (500 +/- 100)
        assertTrue("Camera hold frequency should be around 50% when profile says so", 
                holdCount > 400 && holdCount < 600);
    }

    @Test
    public void testDirectionEnum_HasCorrectKeyCodes() {
        assertEquals(java.awt.event.KeyEvent.VK_LEFT, CameraController.Direction.LEFT.getKeyCode());
        assertEquals(java.awt.event.KeyEvent.VK_RIGHT, CameraController.Direction.RIGHT.getKeyCode());
    }

    @Test
    public void testDirectionEnum_HasCorrectSigns() {
        assertEquals(-1, CameraController.Direction.LEFT.getSign());
        assertEquals(1, CameraController.Direction.RIGHT.getSign());
    }

    @Test
    public void testHoldSpeedEnum_HasReasonableValues() {
        assertTrue(CameraController.HoldSpeed.SLOW.getDegreesPerSecond() < 
                   CameraController.HoldSpeed.MEDIUM.getDegreesPerSecond());
        assertTrue(CameraController.HoldSpeed.MEDIUM.getDegreesPerSecond() < 
                   CameraController.HoldSpeed.FAST.getDegreesPerSecond());
    }

    @Test
    public void testFullRotationJau_Is2048() {
        assertEquals(2048, CameraController.FULL_ROTATION_JAU);
    }

    @Test
    public void testShutdown_CompletesWithoutError() {
        assertDoesNotThrow(() -> cameraController.shutdown());
    }
    
    private void assertDoesNotThrow(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            fail("Should not throw: " + e.getMessage());
        }
    }
}

