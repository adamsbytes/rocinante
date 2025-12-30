package com.rocinante.tasks;

import com.rocinante.behavior.PlayerProfile;
import com.rocinante.core.GameStateService;
import com.rocinante.input.RobotKeyboardController;
import com.rocinante.input.WidgetClickHelper;
import com.rocinante.state.EquipmentState;
import com.rocinante.state.GroundItemSnapshot;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import org.mockito.stubbing.Answer;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reusable test utilities for state-machine-based tasks.
 * 
 * Provides:
 * - State machine advancement utilities
 * - Pre-built mock configurations for common scenarios
 * - Action tracking for verification
 */
public class TaskTestHelper {

    public static final int DEFAULT_MAX_ITERATIONS = 100;

    // ========================================================================
    // Mock Factory Methods - Common RuneLite Objects
    // ========================================================================

    /**
     * Create a LocalPoint for a given world position.
     * Use this instead of LocalPoint.fromWorld() which requires a real client.
     * 
     * Note: LocalPoint is a Lombok @Value class and cannot be mocked.
     * We create a real instance with computed coordinates.
     */
    public static LocalPoint createLocalPoint(WorldPoint worldPos) {
        // LocalPoint coordinates are in 1/128th tile units
        // Scene coordinates are world position modulo scene size
        return new LocalPoint(worldPos.getX() * 128, worldPos.getY() * 128);
    }

    /**
     * Create a LocalPoint for simple coordinates.
     */
    public static LocalPoint createLocalPoint(int x, int y) {
        return new LocalPoint(x * 128, y * 128);
    }

    /**
     * Create a mock NPC with standard setup.
     *
     * @param id       NPC ID
     * @param name     NPC name
     * @param worldPos World position
     * @return Configured mock NPC
     */
    public static NPC mockNpc(int id, String name, WorldPoint worldPos) {
        NPC npc = mock(NPC.class);
        NPCComposition comp = mock(NPCComposition.class);

        when(npc.getId()).thenReturn(id);
        when(npc.getName()).thenReturn(name);
        when(npc.getWorldLocation()).thenReturn(worldPos);
        when(npc.getLocalLocation()).thenReturn(createLocalPoint(worldPos));
        when(npc.getComposition()).thenReturn(comp);
        when(npc.isDead()).thenReturn(false);
        when(npc.getHealthRatio()).thenReturn(100);

        when(comp.getId()).thenReturn(id);
        when(comp.getName()).thenReturn(name);
        when(comp.getActions()).thenReturn(new String[]{"Attack", "Talk-to", "Examine", null, null});

        // Mock convex hull for click detection
        Polygon hull = new Polygon(
                new int[]{100, 150, 150, 100},
                new int[]{100, 100, 150, 150},
                4
        );
        when(npc.getConvexHull()).thenReturn(hull);

        return npc;
    }

    /**
     * Create a mock GameObject with standard setup.
     *
     * @param id       Object ID
     * @param worldPos World position
     * @return Configured mock GameObject
     */
    public static GameObject mockGameObject(int id, WorldPoint worldPos) {
        GameObject obj = mock(GameObject.class);
        ObjectComposition comp = mock(ObjectComposition.class);

        when(obj.getId()).thenReturn(id);
        when(obj.getWorldLocation()).thenReturn(worldPos);
        when(obj.getLocalLocation()).thenReturn(createLocalPoint(worldPos));

        when(comp.getId()).thenReturn(id);
        when(comp.getName()).thenReturn("Object_" + id);
        when(comp.getActions()).thenReturn(new String[]{"Use", "Examine", null, null, null});

        // Mock clickbox
        Shape clickbox = new Rectangle(100, 100, 50, 50);
        when(obj.getClickbox()).thenReturn(clickbox);

        return obj;
    }

    /**
     * Create a mock Tile containing a GameObject.
     */
    public static Tile mockTileWithObject(GameObject obj) {
        Tile tile = mock(Tile.class);
        when(tile.getGameObjects()).thenReturn(new GameObject[]{obj});
        when(tile.getWallObject()).thenReturn(null);
        when(tile.getDecorativeObject()).thenReturn(null);
        when(tile.getGroundObject()).thenReturn(null);
        return tile;
    }

    /**
     * Create a mock GroundItemSnapshot.
     *
     * @param id       Item ID
     * @param name     Item name
     * @param quantity Quantity
     * @param worldPos World position
     * @return Configured GroundItemSnapshot (real object, not mock)
     */
    public static GroundItemSnapshot mockGroundItem(int id, String name, int quantity, WorldPoint worldPos) {
        return GroundItemSnapshot.builder()
                .id(id)
                .name(name)
                .quantity(quantity)
                .worldPosition(worldPos)
                .gePrice(100)
                .haPrice(50)
                .tradeable(true)
                .stackable(false)
                .despawnTick(-1)
                .privateItem(false)
                .build();
    }

    /**
     * Create a mock InventoryState with specified items.
     *
     * @param itemCounts Map of itemId to count (slot doesn't matter for basic tests)
     * @return Configured mock InventoryState
     */
    public static InventoryState mockInventoryState(int... itemIds) {
        InventoryState inv = mock(InventoryState.class);
        when(inv.isFull()).thenReturn(false);
        when(inv.getFreeSlots()).thenReturn(Math.max(0, 28 - itemIds.length));

        // Default: no items
        when(inv.hasItem(anyInt())).thenReturn(false);
        when(inv.countItem(anyInt())).thenReturn(0);
        when(inv.getSlotOf(anyInt())).thenReturn(-1);

        // Configure specific items
        for (int slot = 0; slot < itemIds.length; slot++) {
            int itemId = itemIds[slot];
            if (itemId > 0) {
                when(inv.hasItem(itemId)).thenReturn(true);
                when(inv.countItem(itemId)).thenReturn(1);
                when(inv.getSlotOf(itemId)).thenReturn(slot);
            }
        }

        return inv;
    }

    /**
     * Create a PlayerState with specified animation.
     * Use this instead of PlayerState.builder() when you need animation checks.
     *
     * @param animationId Animation ID (-1 for idle)
     * @param worldPos    World position
     * @return Configured PlayerState (real object via builder)
     */
    public static PlayerState playerStateWithAnimation(int animationId, WorldPoint worldPos) {
        return PlayerState.builder()
                .animationId(animationId)
                .worldPosition(worldPos)
                .isMoving(false)
                .isInteracting(false)
                .build();
    }

    /**
     * Create an idle PlayerState at a position.
     */
    public static PlayerState idlePlayerState(WorldPoint worldPos) {
        return playerStateWithAnimation(-1, worldPos);
    }

    // ========================================================================
    // State Machine Utilities
    // ========================================================================

    /**
     * Advance task execution until a condition becomes true.
     */
    public static int advanceUntil(Task task, TaskContext ctx, int maxIterations, BooleanSupplier condition) {
        for (int i = 0; i < maxIterations; i++) {
            if (condition.getAsBoolean()) {
                return i;
            }
            task.execute(ctx);
            if (task.getState() == TaskState.COMPLETED || task.getState() == TaskState.FAILED) {
                if (condition.getAsBoolean()) {
                    return i + 1;
                }
                fail("Task ended in state " + task.getState() + " before condition was met");
            }
        }
        fail("Condition not met after " + maxIterations + " iterations");
        return maxIterations;
    }

    /**
     * Advance task a fixed number of ticks.
     */
    public static void advanceTicks(Task task, TaskContext ctx, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (task.getState() == TaskState.COMPLETED || task.getState() == TaskState.FAILED) {
                return;
            }
            task.execute(ctx);
        }
    }

    /**
     * Advance task to completion or failure.
     */
    public static TaskState advanceToCompletion(Task task, TaskContext ctx, int maxIterations) {
        for (int i = 0; i < maxIterations; i++) {
            task.execute(ctx);
            if (task.getState() == TaskState.COMPLETED || task.getState() == TaskState.FAILED) {
                return task.getState();
            }
        }
        return task.getState();
    }

    // ========================================================================
    // Mock Builder - Fluent API for building test contexts
    // ========================================================================

    /**
     * Create a new MockBuilder for fluent test context setup.
     */
    public static MockBuilder mockBuilder() {
        return new MockBuilder();
    }

    /**
     * Fluent builder for creating realistic mock TaskContexts.
     */
    public static class MockBuilder {
        // Core mocks
        private TaskContext ctx;
        private Client client;
        private GameStateService gameStateService;
        private PlayerProfile playerProfile;
        private PlayerProfile.ProfileData profileData;
        private PlayerState playerState;
        private EquipmentState equipmentState;
        private InventoryState inventoryState;
        private RobotKeyboardController keyboardController;
        private WidgetClickHelper widgetClickHelper;
        
        // Action tracking
        private ActionTracker tracker;
        
        // Configuration
        private boolean prefersHotkeys = true;
        private boolean profileLoaded = true;
        private GameStateService.InterfaceMode interfaceMode = GameStateService.InterfaceMode.FIXED;
        
        public MockBuilder() {
            this.tracker = new ActionTracker();
            createBaseMocks();
        }

        private void createBaseMocks() {
            ctx = mock(TaskContext.class);
            client = mock(Client.class);
            gameStateService = mock(GameStateService.class);
            playerProfile = mock(PlayerProfile.class);
            profileData = mock(PlayerProfile.ProfileData.class);
            playerState = mock(PlayerState.class);
            equipmentState = mock(EquipmentState.class);
            inventoryState = mock(InventoryState.class);
            keyboardController = mock(RobotKeyboardController.class);
            widgetClickHelper = mock(WidgetClickHelper.class);
        }

        /**
         * Player prefers hotkeys (F-keys) for tab switching.
         */
        public MockBuilder withHotkeyPreference() {
            this.prefersHotkeys = true;
            this.profileLoaded = true;
            return this;
        }

        /**
         * Player prefers clicking for tab switching.
         */
        public MockBuilder withClickPreference() {
            this.prefersHotkeys = false;
            this.profileLoaded = true;
            return this;
        }

        /**
         * No player profile loaded (defaults to hotkeys).
         */
        public MockBuilder withNoProfile() {
            this.profileLoaded = false;
            return this;
        }

        /**
         * Set interface mode (FIXED, RESIZABLE_CLASSIC, RESIZABLE_MODERN).
         */
        public MockBuilder withInterfaceMode(GameStateService.InterfaceMode mode) {
            this.interfaceMode = mode;
            return this;
        }

        /**
         * Spellbook tab is closed (needs to be opened).
         */
        public MockBuilder withSpellbookClosed() {
            when(client.getWidget(218, 0)).thenReturn(null);
            return this;
        }

        /**
         * Spellbook tab is open.
         */
        public MockBuilder withSpellbookOpen() {
            Widget spellbook = mock(Widget.class);
            when(spellbook.isHidden()).thenReturn(false);
            when(client.getWidget(218, 0)).thenReturn(spellbook);
            // Also mock home teleport spell widget
            Widget homeTeleport = mock(Widget.class);
            when(homeTeleport.isHidden()).thenReturn(false);
            when(homeTeleport.getBounds()).thenReturn(new java.awt.Rectangle(100, 100, 30, 30));
            when(client.getWidget(218, 7)).thenReturn(homeTeleport);
            return this;
        }

        /**
         * Equipment tab is closed.
         */
        public MockBuilder withEquipmentClosed() {
            when(client.getWidget(387, 0)).thenReturn(null);
            return this;
        }

        /**
         * Equipment tab is open.
         */
        public MockBuilder withEquipmentOpen() {
            Widget equipment = mock(Widget.class);
            when(equipment.isHidden()).thenReturn(false);
            when(client.getWidget(387, 0)).thenReturn(equipment);
            return this;
        }

        /**
         * Inventory tab is closed (widget not visible).
         * Use withInventoryContaining() to add items that still exist.
         */
        public MockBuilder withInventoryClosed() {
            when(client.getWidget(149, 0)).thenReturn(null);
            return this;
        }

        /**
         * Inventory tab is open and visible.
         */
        public MockBuilder withInventoryOpen() {
            Widget inventory = mock(Widget.class);
            when(inventory.isHidden()).thenReturn(false);
            when(inventory.getDynamicChildren()).thenReturn(new Widget[28]);
            when(client.getWidget(149, 0)).thenReturn(inventory);
            return this;
        }

        /**
         * Inventory contains specified items (for canExecute checks).
         * This sets up the ItemContainer which findItemSlot uses.
         * Does NOT affect whether the inventory tab widget is visible.
         */
        public MockBuilder withInventoryContaining(int... itemIds) {
            ItemContainer container = mock(ItemContainer.class);
            Item[] items = new Item[28];
            for (int i = 0; i < 28; i++) {
                Item item = mock(Item.class);
                if (i < itemIds.length && itemIds[i] > 0) {
                    when(item.getId()).thenReturn(itemIds[i]);
                    when(item.getQuantity()).thenReturn(1);
                } else {
                    when(item.getId()).thenReturn(-1);
                    when(item.getQuantity()).thenReturn(0);
                }
                items[i] = item;
            }
            when(container.getItems()).thenReturn(items);
            when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(container);
            return this;
        }

        /**
         * Player has specific jewelry equipped.
         */
        public MockBuilder withEquippedJewelry(int itemId) {
            when(equipmentState.hasEquipped(itemId)).thenReturn(true);
            return this;
        }

        /**
         * Player has no jewelry equipped.
         */
        public MockBuilder withNoEquippedJewelry() {
            when(equipmentState.hasEquipped(anyInt())).thenReturn(false);
            return this;
        }

        /**
         * Build and return the configured context with all wiring.
         */
        public MockResult build() {
            // Wire up context
            when(ctx.getClient()).thenReturn(client);
            when(ctx.getGameStateService()).thenReturn(gameStateService);
            when(ctx.getPlayerProfile()).thenReturn(profileLoaded ? playerProfile : null);
            when(ctx.getPlayerState()).thenReturn(playerState);
            when(ctx.getKeyboardController()).thenReturn(keyboardController);
            when(ctx.getWidgetClickHelper()).thenReturn(widgetClickHelper);
            when(ctx.isLoggedIn()).thenReturn(true);
            
            // Wire up GameStateService
            when(gameStateService.getInterfaceMode()).thenReturn(interfaceMode);
            when(gameStateService.getEquipmentState()).thenReturn(equipmentState);
            when(gameStateService.getInventoryState()).thenReturn(inventoryState);
            
            // Wire up PlayerProfile
            if (profileLoaded) {
                when(playerProfile.isLoaded()).thenReturn(true);
                when(playerProfile.getProfileData()).thenReturn(profileData);
                when(profileData.isPrefersHotkeys()).thenReturn(prefersHotkeys);
            }
            
            // Wire up PlayerState
            when(playerState.getWorldPosition()).thenReturn(new WorldPoint(3000, 3000, 0));
            
            // Wire up action tracking
            when(keyboardController.pressKey(anyInt())).thenAnswer(invocation -> {
                tracker.keyPressed = true;
                tracker.lastKeyCode = invocation.getArgument(0);
                return CompletableFuture.completedFuture(null);
            });
            
            when(widgetClickHelper.clickWidget(anyInt(), anyInt(), anyString())).thenAnswer(invocation -> {
                tracker.widgetClicked = true;
                tracker.lastClickGroupId = invocation.getArgument(0);
                tracker.lastClickChildId = invocation.getArgument(1);
                tracker.lastClickDescription = invocation.getArgument(2);
                return CompletableFuture.completedFuture(true);
            });
            
            return new MockResult(ctx, client, tracker);
        }
    }

    /**
     * Result of mock building - provides context and tracking.
     */
    public static class MockResult {
        public final TaskContext ctx;
        public final Client client;
        public final ActionTracker tracker;

        MockResult(TaskContext ctx, Client client, ActionTracker tracker) {
            this.ctx = ctx;
            this.client = client;
            this.tracker = tracker;
        }
    }

    /**
     * Tracks actions taken during test execution.
     */
    public static class ActionTracker {
        public boolean keyPressed = false;
        public int lastKeyCode = -1;
        
        public boolean widgetClicked = false;
        public int lastClickGroupId = -1;
        public int lastClickChildId = -1;
        public String lastClickDescription = null;

        public void reset() {
            keyPressed = false;
            lastKeyCode = -1;
            widgetClicked = false;
            lastClickGroupId = -1;
            lastClickChildId = -1;
            lastClickDescription = null;
        }
        
        public boolean usedHotkey() {
            return keyPressed && !widgetClicked;
        }
        
        public boolean usedClick() {
            return widgetClicked && !keyPressed;
        }
    }
}
