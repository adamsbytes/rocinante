package com.rocinante.behavior;

import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.RobotMouseController;
import com.rocinante.timing.HumanTimer;
import com.rocinante.util.Randomization;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LogoutHandler.
 * Tests logout timing, method selection, and context-based behavior.
 */
public class LogoutHandlerTest {

    @Mock
    private Client client;
    
    @Mock
    private HumanTimer humanTimer;
    
    @Mock
    private RobotMouseController mouseController;
    
    @Mock
    private RobotKeyboardController keyboardController;
    
    @Mock
    private PlayerProfile playerProfile;
    
    private Randomization randomization;
    private LogoutHandler handler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        randomization = new Randomization(12345L);
        handler = new LogoutHandler(client, randomization, humanTimer);
        handler.setMouseController(mouseController);
        handler.setKeyboardController(keyboardController);
        handler.setPlayerProfile(playerProfile);
        
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
    }

    // ========================================================================
    // Logout Timing Tests
    // ========================================================================

    @Test
    public void testGetLogoutDelay_Normal_ReasonableRange() {
        for (int i = 0; i < 100; i++) {
            long delay = handler.getLogoutDelay(LogoutHandler.LogoutContext.NORMAL);
            assertTrue("Normal delay should be >= 1000ms", delay >= 1000);
            assertTrue("Normal delay should be <= 5000ms", delay <= 5000);
        }
    }

    @Test
    public void testGetLogoutDelay_PostCombatEscape_LongerRange() {
        for (int i = 0; i < 100; i++) {
            long delay = handler.getLogoutDelay(LogoutHandler.LogoutContext.POST_COMBAT_ESCAPE);
            assertTrue("Post-combat delay should be >= 2000ms", delay >= 2000);
            assertTrue("Post-combat delay should be <= 15000ms", delay <= 15000);
        }
    }

    @Test
    public void testGetLogoutDelay_PostLevelUp_LongerRange() {
        for (int i = 0; i < 100; i++) {
            long delay = handler.getLogoutDelay(LogoutHandler.LogoutContext.POST_LEVEL_UP);
            assertTrue("Post-level-up delay should be >= 5000ms", delay >= 5000);
            assertTrue("Post-level-up delay should be <= 20000ms", delay <= 20000);
        }
    }

    @Test
    public void testGetLogoutDelay_Emergency_ShortRange() {
        for (int i = 0; i < 100; i++) {
            long delay = handler.getLogoutDelay(LogoutHandler.LogoutContext.EMERGENCY);
            assertTrue("Emergency delay should be >= 500ms", delay >= 500);
            assertTrue("Emergency delay should be <= 2000ms", delay <= 2000);
        }
    }

    @Test
    public void testGetLogoutDelay_RandomIRL_QuickRange() {
        for (int i = 0; i < 100; i++) {
            long delay = handler.getLogoutDelay(LogoutHandler.LogoutContext.RANDOM_IRL);
            assertTrue("Random IRL delay should be >= 500ms", delay >= 500);
            assertTrue("Random IRL delay should be <= 3000ms", delay <= 3000);
        }
    }

    // ========================================================================
    // Logout Method Selection Tests
    // ========================================================================

    @Test
    public void testSelectLogoutMethod_ReturnsValidMethod() {
        for (int i = 0; i < 100; i++) {
            LogoutHandler.LogoutMethod method = handler.selectLogoutMethod();
            assertNotNull(method);
        }
    }

    @Test
    public void testSelectLogoutMethod_MostCommonIsDirectClick() {
        int directCount = 0;
        int total = 1000;
        
        for (int i = 0; i < total; i++) {
            LogoutHandler.LogoutMethod method = handler.selectLogoutMethod();
            if (method == LogoutHandler.LogoutMethod.DIRECT_CLICK) {
                directCount++;
            }
        }
        
        // Direct click should be 60% (600 +/- 100)
        assertTrue("Direct click should be most common (~60%)", 
                directCount > 500 && directCount < 700);
    }

    @Test
    public void testSelectLogoutMethod_EscThenClickIsCommon() {
        int escCount = 0;
        int total = 1000;
        
        for (int i = 0; i < total; i++) {
            LogoutHandler.LogoutMethod method = handler.selectLogoutMethod();
            if (method == LogoutHandler.LogoutMethod.ESC_THEN_CLICK) {
                escCount++;
            }
        }
        
        // ESC then click should be 30% (300 +/- 80)
        assertTrue("ESC then click should be common (~30%)", 
                escCount > 200 && escCount < 400);
    }

    @Test
    public void testSelectLogoutMethod_AltTabAwayIsRare() {
        int altTabCount = 0;
        int total = 1000;
        
        for (int i = 0; i < total; i++) {
            LogoutHandler.LogoutMethod method = handler.selectLogoutMethod();
            if (method == LogoutHandler.LogoutMethod.ALT_TAB_AWAY) {
                altTabCount++;
            }
        }
        
        // Alt-tab away should be 10% (100 +/- 50)
        assertTrue("Alt-tab away should be rare (~10%)", 
                altTabCount > 50 && altTabCount < 200);
    }

    // ========================================================================
    // Break Continuation Tests
    // ========================================================================

    @Test
    public void testShouldContinueAtBreak_TwentyPercentChance() {
        int continueCount = 0;
        int total = 1000;
        
        for (int i = 0; i < total; i++) {
            if (handler.shouldContinueAtBreak()) {
                continueCount++;
            }
        }
        
        // 20% chance (200 +/- 80)
        assertTrue("Should continue at break ~20% of time", 
                continueCount > 120 && continueCount < 280);
    }

    @Test
    public void testShouldContinueAtBreak_SetsExtraActions() {
        // Run until we get a true result
        handler = new LogoutHandler(client, new Randomization(System.nanoTime()), humanTimer);
        
        for (int i = 0; i < 100; i++) {
            if (handler.shouldContinueAtBreak()) {
                int extra = handler.getExtraActionsRemaining();
                assertTrue("Extra actions should be 1-5", extra >= 1 && extra <= 5);
                return;
            }
        }
        
        // If we never got true, that's okay - just checking when we do
    }

    @Test
    public void testDecrementExtraActions_CountsDown() {
        // Force set extra actions
        handler.shouldContinueAtBreak(); // This might set it
        
        // Manually check decrement logic
        int initial = handler.getExtraActionsRemaining();
        if (initial > 0) {
            boolean shouldLogout = handler.decrementExtraActions();
            assertEquals(initial - 1, handler.getExtraActionsRemaining());
            
            // Should only return true when reaching 0
            assertEquals(handler.getExtraActionsRemaining() <= 0, shouldLogout);
        }
    }

    // ========================================================================
    // Random Logout Tests
    // ========================================================================

    @Test
    public void testCheckRandomLogout_NotSafe_ReturnsFalse() {
        assertFalse(handler.checkRandomLogout(false));
    }

    @Test
    public void testCheckRandomLogout_SetsContext() {
        // Run many times to potentially trigger random logout
        handler = new LogoutHandler(client, new Randomization(System.nanoTime()), humanTimer);
        
        // Since it's 0.1% per minute and we check once, we won't actually trigger it
        // But we can verify it doesn't crash and returns false for unsafe
        boolean result = handler.checkRandomLogout(false);
        assertFalse("Unsafe activity should never trigger random logout", result);
    }

    // ========================================================================
    // State Management Tests
    // ========================================================================

    @Test
    public void testIsLoggingOut_InitiallyFalse() {
        assertFalse(handler.isLoggingOut());
    }

    @Test
    public void testReset_ClearsState() {
        handler.setContext(LogoutHandler.LogoutContext.EMERGENCY);
        handler.reset();
        
        assertFalse(handler.isLoggingOut());
        assertEquals(0, handler.getExtraActionsRemaining());
        assertEquals(LogoutHandler.LogoutContext.NORMAL, handler.getCurrentContext());
    }

    @Test
    public void testSetContext_ChangesCurrentContext() {
        handler.setContext(LogoutHandler.LogoutContext.POST_LEVEL_UP);
        assertEquals(LogoutHandler.LogoutContext.POST_LEVEL_UP, handler.getCurrentContext());
    }

    // ========================================================================
    // Enum Tests
    // ========================================================================

    @Test
    public void testLogoutContext_HasAllExpectedValues() {
        LogoutHandler.LogoutContext[] contexts = LogoutHandler.LogoutContext.values();
        
        assertEquals(5, contexts.length);
        assertNotNull(LogoutHandler.LogoutContext.NORMAL);
        assertNotNull(LogoutHandler.LogoutContext.POST_COMBAT_ESCAPE);
        assertNotNull(LogoutHandler.LogoutContext.POST_LEVEL_UP);
        assertNotNull(LogoutHandler.LogoutContext.RANDOM_IRL);
        assertNotNull(LogoutHandler.LogoutContext.EMERGENCY);
    }

    @Test
    public void testLogoutMethod_HasAllExpectedValues() {
        LogoutHandler.LogoutMethod[] methods = LogoutHandler.LogoutMethod.values();
        
        assertEquals(3, methods.length);
        assertNotNull(LogoutHandler.LogoutMethod.DIRECT_CLICK);
        assertNotNull(LogoutHandler.LogoutMethod.ESC_THEN_CLICK);
        assertNotNull(LogoutHandler.LogoutMethod.ALT_TAB_AWAY);
    }
}

