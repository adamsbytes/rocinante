package com.rocinante.behavior.tasks;

import com.rocinante.state.IronmanState;
import com.rocinante.tasks.TaskExecutor;
import com.rocinante.tasks.TaskPriority;
import com.rocinante.tasks.impl.TradeTask;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TradeHandler.
 * Tests trade detection, ironman blocking, and task queueing.
 */
public class TradeHandlerTest {

    @Mock
    private Client client;

    @Mock
    private IronmanState ironmanState;

    @Mock
    private TaskExecutor taskExecutor;

    private TradeHandler tradeHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        tradeHandler = new TradeHandler(client, ironmanState);
        tradeHandler.setTaskExecutor(taskExecutor);

        // Default state: logged in, normal account
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(ironmanState.isIronman()).thenReturn(false);
    }

    // ========================================================================
    // Trade Detection Tests
    // ========================================================================

    @Test
    public void testDetectsTradeRequest() {
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        
        tradeHandler.onChatMessage(chatMessage);
        
        assertEquals("TestPlayer", tradeHandler.getLastTradeRequestFrom());
    }

    @Test
    public void testIgnoresNonTradeMessages() {
        // Regular chat message
        ChatMessage regularChat = new ChatMessage();
        regularChat.setType(ChatMessageType.PUBLICCHAT);
        regularChat.setMessage("Hello world");
        
        tradeHandler.onChatMessage(regularChat);
        
        assertNull(tradeHandler.getLastTradeRequestFrom());
        verify(taskExecutor, never()).queueTask(any(), any());
    }

    @Test
    public void testIgnoresMalformedTradeMessage() {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setType(ChatMessageType.TRADEREQ);
        chatMessage.setMessage("Some random message without trade pattern");
        
        tradeHandler.onChatMessage(chatMessage);
        
        assertNull(tradeHandler.getLastTradeRequestFrom());
        verify(taskExecutor, never()).queueTask(any(), any());
    }

    // ========================================================================
    // Ironman Blocking Tests
    // ========================================================================

    @Test
    public void testBlocksTradeForIronman() {
        when(ironmanState.isIronman()).thenReturn(true);
        
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        
        // Should record the request but NOT queue a task
        assertEquals("TestPlayer", tradeHandler.getLastTradeRequestFrom());
        verify(taskExecutor, never()).queueTask(any(), any());
    }

    @Test
    public void testAllowsTradeForNormalAccount() {
        when(ironmanState.isIronman()).thenReturn(false);
        
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        
        verify(taskExecutor).queueTask(any(TradeTask.class), eq(TaskPriority.BEHAVIORAL));
    }

    // ========================================================================
    // Task Queueing Tests
    // ========================================================================

    @Test
    public void testQueuesTradeTaskWithBehavioralPriority() {
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        
        ArgumentCaptor<TradeTask> taskCaptor = ArgumentCaptor.forClass(TradeTask.class);
        verify(taskExecutor).queueTask(taskCaptor.capture(), eq(TaskPriority.BEHAVIORAL));
        
        TradeTask task = taskCaptor.getValue();
        assertNotNull(task);
        assertTrue(task.getConfig().isSendResponse());
    }

    @Test
    public void testSetsTradeInProgress() {
        assertFalse(tradeHandler.isTradeInProgress());
        
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        
        assertTrue(tradeHandler.isTradeInProgress());
    }

    @Test
    public void testBlocksNewTradeWhileInProgress() {
        // First trade
        ChatMessage firstTrade = createTradeRequest("Player1");
        tradeHandler.onChatMessage(firstTrade);
        
        // Second trade while first is in progress
        ChatMessage secondTrade = createTradeRequest("Player2");
        tradeHandler.onChatMessage(secondTrade);
        
        // Only one task should be queued
        verify(taskExecutor, times(1)).queueTask(any(), any());
        assertEquals("Player2", tradeHandler.getLastTradeRequestFrom());
    }

    @Test
    public void testMarkTradeComplete() {
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        assertTrue(tradeHandler.isTradeInProgress());
        
        tradeHandler.markTradeComplete();
        assertFalse(tradeHandler.isTradeInProgress());
    }

    // ========================================================================
    // Session Tracking Tests
    // ========================================================================

    @Test
    public void testTracksTradesPerSession() {
        assertEquals(0, tradeHandler.getTradesAcceptedThisSession());
        
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        
        assertEquals(1, tradeHandler.getTradesAcceptedThisSession());
    }

    @Test
    public void testResetSession() {
        // Accept some trades
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        tradeHandler.markTradeComplete();
        
        assertEquals(1, tradeHandler.getTradesAcceptedThisSession());
        
        // Reset session
        tradeHandler.resetSession();
        
        assertEquals(0, tradeHandler.getTradesAcceptedThisSession());
        assertNull(tradeHandler.getLastTradeRequestFrom());
        assertFalse(tradeHandler.isTradeInProgress());
    }

    // ========================================================================
    // Enable/Disable Tests
    // ========================================================================

    @Test
    public void testDisabledHandler() {
        tradeHandler.setEnabled(false);
        
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        
        verify(taskExecutor, never()).queueTask(any(), any());
    }

    @Test
    public void testReEnabledHandler() {
        tradeHandler.setEnabled(false);
        tradeHandler.setEnabled(true);
        
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        
        verify(taskExecutor).queueTask(any(), any());
    }

    // ========================================================================
    // State Tests
    // ========================================================================

    @Test
    public void testIgnoresWhenNotLoggedIn() {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        tradeHandler.onChatMessage(chatMessage);
        
        verify(taskExecutor, never()).queueTask(any(), any());
    }

    @Test
    public void testIgnoresWhenTaskExecutorNull() {
        TradeHandler handlerWithoutExecutor = new TradeHandler(client, ironmanState);
        // Don't set taskExecutor
        
        ChatMessage chatMessage = createTradeRequest("TestPlayer");
        handlerWithoutExecutor.onChatMessage(chatMessage);
        
        // Should log warning but not crash
        assertNotNull(handlerWithoutExecutor);
    }

    // ========================================================================
    // Rate Limiting Tests
    // ========================================================================

    @Test
    public void testRateLimitingBetweenTrades() {
        // First trade
        ChatMessage firstTrade = createTradeRequest("Player1");
        tradeHandler.onChatMessage(firstTrade);
        tradeHandler.markTradeComplete();
        
        // Second trade immediately (should be rate limited)
        ChatMessage secondTrade = createTradeRequest("Player2");
        tradeHandler.onChatMessage(secondTrade);
        
        // Only first trade should be queued (rate limited)
        verify(taskExecutor, times(1)).queueTask(any(), any());
    }

    // ========================================================================
    // Summary/Status Tests
    // ========================================================================

    @Test
    public void testGetSummary() {
        String summary = tradeHandler.getSummary();
        
        assertNotNull(summary);
        assertTrue(summary.contains("TradeHandler"));
        assertTrue(summary.contains("enabled=true"));
        assertTrue(summary.contains("inProgress=false"));
    }

    @Test
    public void testToString() {
        assertEquals(tradeHandler.getSummary(), tradeHandler.toString());
    }

    // ========================================================================
    // Player Name Extraction Tests
    // ========================================================================

    @Test
    public void testExtractsPlayerNameWithSpaces() {
        ChatMessage chatMessage = createTradeRequest("Test Player 123");
        tradeHandler.onChatMessage(chatMessage);
        
        assertEquals("Test Player 123", tradeHandler.getLastTradeRequestFrom());
    }

    @Test
    public void testExtractsPlayerNameWithSpecialChars() {
        ChatMessage chatMessage = createTradeRequest("L33t-H4x0r");
        tradeHandler.onChatMessage(chatMessage);
        
        assertEquals("L33t-H4x0r", tradeHandler.getLastTradeRequestFrom());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private ChatMessage createTradeRequest(String playerName) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setType(ChatMessageType.TRADEREQ);
        chatMessage.setMessage(playerName + " wishes to trade with you.");
        return chatMessage;
    }
}

