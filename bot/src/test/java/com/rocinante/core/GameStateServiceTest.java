package com.rocinante.core;

import com.rocinante.behavior.AttentionModel;
import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.combat.WeaponDataService;
import com.rocinante.data.NpcCombatDataLoader;
import com.rocinante.data.ProjectileDataLoader;
import com.rocinante.state.BankStateManager;
import com.rocinante.state.GrandExchangeStateManager;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GameStateService class.
 */
public class GameStateServiceTest {

    @Mock
    private Client client;

    @Mock
    private Player localPlayer;

    @Mock
    private ItemContainer inventoryContainer;

    @Mock
    private ItemContainer equipmentContainer;

    @Mock
    private WorldPoint mockWorldPoint;

    @Mock
    private LocalPoint mockLocalPoint;

    @Mock
    private ItemManager itemManager;

    @Mock
    private BankStateManager bankStateManager;
    
    @Mock
    private GrandExchangeStateManager grandExchangeStateManager;
    
    @Mock
    private PlayerProfile playerProfile;
    
    @Mock
    private FatigueModel fatigueModel;
    
    @Mock
    private BreakScheduler breakScheduler;
    
    @Mock
    private AttentionModel attentionModel;

    @Mock
    private WeaponDataService weaponDataService;

    @Mock
    private NpcCombatDataLoader npcCombatDataLoader;

    @Mock
    private ProjectileDataLoader projectileDataLoader;

    @Mock
    private SlayerPluginService slayerPluginService;

    @Mock
    private com.rocinante.state.IronmanState ironmanState;

    @Mock
    private com.rocinante.status.XpTracker xpTracker;

    @Mock
    private com.rocinante.navigation.PathCostCache pathCostCache;

    private GameStateService gameStateService;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        gameStateService = new GameStateService(client, itemManager, bankStateManager, 
                grandExchangeStateManager,
                ironmanState,
                playerProfile, fatigueModel, breakScheduler, attentionModel,
                xpTracker,
                weaponDataService, npcCombatDataLoader, projectileDataLoader,
                pathCostCache);
        // Wire SlayerPluginService via setter (optional)
        gameStateService.setSlayerPluginService(slayerPluginService);
    }

    // ========================================================================
    // Initial State
    // ========================================================================

    @Test
    public void testInitialState() {
        assertFalse(gameStateService.isLoggedIn());
        assertEquals(0, gameStateService.getCurrentTick());
    }

    @Test
    public void testGetPlayerState_NotLoggedIn() {
        PlayerState state = gameStateService.getPlayerState();

        assertSame(PlayerState.EMPTY, state);
    }

    @Test
    public void testGetInventoryState_NotLoggedIn() {
        InventoryState state = gameStateService.getInventoryState();

        assertSame(InventoryState.EMPTY, state);
    }

    @Test
    public void testGetEquipmentState_NotLoggedIn() {
        EquipmentState state = gameStateService.getEquipmentState();

        assertSame(EquipmentState.EMPTY, state);
    }

    // ========================================================================
    // Login/Logout Events
    // ========================================================================

    @Test
    public void testOnGameStateChanged_Login() {
        GameStateChanged event = new GameStateChanged();
        event.setGameState(GameState.LOGGED_IN);

        gameStateService.onGameStateChanged(event);

        assertTrue(gameStateService.isLoggedIn());
    }
    
    @Test
    public void testOnGameStateChanged_Login_InitializesPlayerProfile() {
        setupMockPlayer();
        when(localPlayer.getName()).thenReturn("TestPlayer");
        
        GameStateChanged event = new GameStateChanged();
        event.setGameState(GameState.LOGGED_IN);
        
        gameStateService.onGameStateChanged(event);
        
        // Note: PlayerProfile.initializeForAccount is called on client thread,
        // so we can't directly verify in unit test without RuneLite test harness
        // This test verifies the handler executes without error
        assertTrue(gameStateService.isLoggedIn());
    }

    @Test
    public void testOnGameStateChanged_Logout() {
        // First login
        GameStateChanged loginEvent = new GameStateChanged();
        loginEvent.setGameState(GameState.LOGGED_IN);
        gameStateService.onGameStateChanged(loginEvent);

        // Then logout
        GameStateChanged logoutEvent = new GameStateChanged();
        logoutEvent.setGameState(GameState.LOGIN_SCREEN);
        gameStateService.onGameStateChanged(logoutEvent);

        assertFalse(gameStateService.isLoggedIn());
        assertEquals(0, gameStateService.getCurrentTick());
    }
    
    @Test
    public void testOnGameStateChanged_Logout_CallsSessionEndMethods() {
        // First login
        setupMockPlayer();
        when(localPlayer.getName()).thenReturn("TestPlayer");
        GameStateChanged loginEvent = new GameStateChanged();
        loginEvent.setGameState(GameState.LOGGED_IN);
        gameStateService.onGameStateChanged(loginEvent);
        
        // Then logout
        GameStateChanged logoutEvent = new GameStateChanged();
        logoutEvent.setGameState(GameState.LOGIN_SCREEN);
        gameStateService.onGameStateChanged(logoutEvent);
        
        // Verify session end methods called
        verify(fatigueModel, times(1)).onSessionEnd();
        verify(breakScheduler, times(1)).onSessionEnd();
        verify(playerProfile, times(1)).recordLogout();
    }

    @Test
    public void testOnGameStateChanged_ConnectionLost() {
        // First login
        GameStateChanged loginEvent = new GameStateChanged();
        loginEvent.setGameState(GameState.LOGGED_IN);
        gameStateService.onGameStateChanged(loginEvent);

        // Connection lost
        GameStateChanged dcEvent = new GameStateChanged();
        dcEvent.setGameState(GameState.CONNECTION_LOST);
        gameStateService.onGameStateChanged(dcEvent);

        assertFalse(gameStateService.isLoggedIn());
    }

    // ========================================================================
    // Game Tick Handling
    // ========================================================================

    @Test
    public void testOnGameTick_IncrementsTickCount() {
        setupLoggedInState();
        setupMockPlayer();

        GameTick tick = new GameTick();
        gameStateService.onGameTick(tick);

        assertEquals(1, gameStateService.getCurrentTick());

        gameStateService.onGameTick(tick);
        assertEquals(2, gameStateService.getCurrentTick());
    }

    @Test
    public void testOnGameTick_NotLoggedIn_DoesNothing() {
        // Don't set logged in state
        GameTick tick = new GameTick();
        gameStateService.onGameTick(tick);

        assertEquals(0, gameStateService.getCurrentTick());
    }

    // ========================================================================
    // Player State Building
    // ========================================================================

    @Test
    public void testGetPlayerState_BuildsFromClient() {
        setupLoggedInState();
        setupMockPlayer();

        // Simulate a game tick to populate state
        gameStateService.onGameTick(new GameTick());

        PlayerState state = gameStateService.getPlayerState();

        assertTrue(state.isValid());
        assertEquals(50, state.getCurrentHitpoints());
        assertEquals(99, state.getMaxHitpoints());
        assertEquals(43, state.getCurrentPrayer());
        assertEquals(70, state.getMaxPrayer());
        assertFalse(state.isMoving());
        assertFalse(state.isInCombat());
    }

    @Test
    public void testGetPlayerState_PoisonStatus() {
        setupLoggedInState();
        setupMockPlayer();

        // Set poison value positive (poisoned)
        when(client.getVarpValue(102)).thenReturn(5);

        gameStateService.onGameTick(new GameTick());
        PlayerState poisonedState = gameStateService.getPlayerState();

        assertTrue(poisonedState.isPoisoned());
        assertFalse(poisonedState.isVenomed());
    }

    @Test
    public void testGetPlayerState_VenomStatus() {
        setupLoggedInState();
        setupMockPlayer();

        // Set poison value negative (venomed)
        when(client.getVarpValue(102)).thenReturn(-8);

        gameStateService.onGameTick(new GameTick());
        PlayerState venomedState = gameStateService.getPlayerState();

        assertFalse(venomedState.isPoisoned());
        assertTrue(venomedState.isVenomed());
    }

    @Test
    public void testGetPlayerState_NullPlayer() {
        setupLoggedInState();
        when(client.getLocalPlayer()).thenReturn(null);

        gameStateService.onGameTick(new GameTick());
        PlayerState state = gameStateService.getPlayerState();

        assertSame(PlayerState.EMPTY, state);
    }

    // ========================================================================
    // Inventory State Building
    // ========================================================================

    @Test
    public void testGetInventoryState_BuildsFromClient() {
        setupLoggedInState();
        setupMockPlayer();
        setupMockInventory();

        gameStateService.onGameTick(new GameTick());
        InventoryState state = gameStateService.getInventoryState();

        assertTrue(state.hasItem(379)); // Shark
        assertEquals(1000, state.countItem(995)); // Coins
    }

    @Test
    public void testGetInventoryState_NullContainer() {
        setupLoggedInState();
        setupMockPlayer();
        when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(null);

        gameStateService.onGameTick(new GameTick());
        InventoryState state = gameStateService.getInventoryState();

        assertSame(InventoryState.EMPTY, state);
    }

    // ========================================================================
    // Equipment State Building
    // ========================================================================

    @Test
    public void testGetEquipmentState_BuildsFromClient() {
        setupLoggedInState();
        setupMockPlayer();
        setupMockEquipment();

        gameStateService.onGameTick(new GameTick());
        EquipmentState state = gameStateService.getEquipmentState();

        assertTrue(state.hasEquipped(4151)); // Abyssal whip
    }

    @Test
    public void testGetEquipmentState_NullContainer() {
        setupLoggedInState();
        setupMockPlayer();
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(null);

        gameStateService.onGameTick(new GameTick());
        EquipmentState state = gameStateService.getEquipmentState();

        assertSame(EquipmentState.EMPTY, state);
    }

    // ========================================================================
    // Item Container Changed Events
    // ========================================================================

    @Test
    public void testOnItemContainerChanged_Inventory() {
        setupLoggedInState();
        setupMockPlayer();
        setupMockInventory();

        // First tick to populate cache
        gameStateService.onGameTick(new GameTick());

        // Change inventory
        Item mockLobster = mock(Item.class);
        when(mockLobster.getId()).thenReturn(347);
        when(mockLobster.getQuantity()).thenReturn(1);

        Item[] newItems = new Item[28];
        newItems[0] = mockLobster; // Lobster instead of shark
        when(inventoryContainer.getItems()).thenReturn(newItems);

        // Fire container changed event
        ItemContainerChanged event = new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer);
        gameStateService.onItemContainerChanged(event);

        // Next tick should pick up changes
        gameStateService.onGameTick(new GameTick());
        InventoryState state = gameStateService.getInventoryState();

        assertTrue(state.hasItem(347)); // Lobster
        assertFalse(state.hasItem(379)); // No shark
    }

    @Test
    public void testOnItemContainerChanged_Equipment() {
        setupLoggedInState();
        setupMockPlayer();
        setupMockEquipment();

        // First tick to populate cache
        gameStateService.onGameTick(new GameTick());

        // Change equipment
        Item mockSword = mock(Item.class);
        when(mockSword.getId()).thenReturn(1289);
        when(mockSword.getQuantity()).thenReturn(1);

        Item[] newEquip = new Item[14];
        newEquip[EquipmentState.SLOT_WEAPON] = mockSword; // Rune sword
        when(equipmentContainer.getItems()).thenReturn(newEquip);

        // Fire container changed event
        ItemContainerChanged event = new ItemContainerChanged(InventoryID.EQUIPMENT.getId(), equipmentContainer);
        gameStateService.onItemContainerChanged(event);

        // Next tick should pick up changes
        gameStateService.onGameTick(new GameTick());
        EquipmentState state = gameStateService.getEquipmentState();

        assertTrue(state.hasEquipped(1289)); // Rune sword
        assertFalse(state.hasEquipped(4151)); // No whip
    }

    // ========================================================================
    // Cache Management
    // ========================================================================

    @Test
    public void testInvalidateAllCaches() {
        setupLoggedInState();
        setupMockPlayer();
        setupMockInventory();
        setupMockEquipment();

        // Populate caches
        gameStateService.onGameTick(new GameTick());

        // Invalidate
        gameStateService.invalidateAllCaches();

        // Cache should be rebuilt on next access
        // (We can't easily verify this without exposing more internals,
        //  but we can verify the method doesn't throw)
        assertNotNull(gameStateService.getPlayerState());
    }

    @Test
    public void testGetCacheStats() {
        String stats = gameStateService.getCacheStats();

        assertTrue(stats.contains("PlayerState"));
        assertTrue(stats.contains("InventoryState"));
        assertTrue(stats.contains("EquipmentState"));
    }

    @Test
    public void testAreCacheTargetsMet_InitiallyTrue() {
        // With no accesses yet, should return true (skip check)
        assertTrue(gameStateService.areCacheTargetsMet());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupLoggedInState() {
        GameStateChanged event = new GameStateChanged();
        event.setGameState(GameState.LOGGED_IN);
        gameStateService.onGameStateChanged(event);
    }

    private void setupMockPlayer() {
        when(client.getLocalPlayer()).thenReturn(localPlayer);

        // Mock WorldPoint
        when(mockWorldPoint.getX()).thenReturn(3200);
        when(mockWorldPoint.getY()).thenReturn(3200);
        when(mockWorldPoint.getPlane()).thenReturn(0);
        when(localPlayer.getWorldLocation()).thenReturn(mockWorldPoint);
        when(localPlayer.getLocalLocation()).thenReturn(mockLocalPoint);
        when(localPlayer.getAnimation()).thenReturn(-1);
        when(localPlayer.getPoseAnimation()).thenReturn(808);
        when(localPlayer.getIdlePoseAnimation()).thenReturn(808);
        when(localPlayer.getInteracting()).thenReturn(null);
        when(localPlayer.getSkullIcon()).thenReturn(-1);

        when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(50);
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(99);
        when(client.getBoostedSkillLevel(Skill.PRAYER)).thenReturn(43);
        when(client.getRealSkillLevel(Skill.PRAYER)).thenReturn(70);
        when(client.getEnergy()).thenReturn(800000); // 80% (value is 0-10000 * 100)
        when(client.getVarpValue(102)).thenReturn(0); // Not poisoned
        when(client.getVarbitValue(4070)).thenReturn(0); // Standard spellbook
    }

    private void setupMockInventory() {
        Item mockShark = mock(Item.class);
        when(mockShark.getId()).thenReturn(379);
        when(mockShark.getQuantity()).thenReturn(1);

        Item mockCoins = mock(Item.class);
        when(mockCoins.getId()).thenReturn(995);
        when(mockCoins.getQuantity()).thenReturn(1000);

        Item[] items = new Item[28];
        items[0] = mockShark;    // Shark
        items[1] = mockCoins;    // Coins

        when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
        when(inventoryContainer.getItems()).thenReturn(items);
    }

    private void setupMockEquipment() {
        Item mockWhip = mock(Item.class);
        when(mockWhip.getId()).thenReturn(4151);
        when(mockWhip.getQuantity()).thenReturn(1);

        Item[] equipment = new Item[14];
        equipment[EquipmentState.SLOT_WEAPON] = mockWhip; // Abyssal whip

        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(equipmentContainer);
        when(equipmentContainer.getItems()).thenReturn(equipment);
    }
}
