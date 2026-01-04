package com.rocinante.tasks.impl;

import com.rocinante.behavior.InefficiencyInjector;
import com.rocinante.input.MenuHelper;
import com.rocinante.input.SafeClickExecutor;
import com.rocinante.input.WidgetClickHelper;
import com.rocinante.state.InventoryState;
import com.rocinante.state.PlayerState;
import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.TaskState;
import com.rocinante.timing.DelayProfile;
import com.rocinante.timing.HumanTimer.ReactionContext;
import com.rocinante.util.Randomization;
import com.rocinante.util.WidgetInteractionHelpers;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.rocinante.util.Randomization;

/**
 * Full-featured bank interaction task.
 *
 * <p>Per REQUIREMENTS.md Section 5.4.7:
 * <ul>
 *   <li>Open nearest bank (NPC or booth)</li>
 *   <li>Operations: deposit all, deposit specific items, withdraw specific items</li>
 *   <li>Quantity options: 1, 5, 10, X, All</li>
 *   <li>Navigate bank tabs with humanized patterns</li>
 *   <li>Use bank search when > 50 items visible</li>
 *   <li>Placeholder configuration support</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Withdraw specific items
 * BankTask withdraw = BankTask.withdraw(ItemID.LOBSTER, 10)
 *     .withDescription("Withdraw 10 lobsters");
 *
 * // Deposit all inventory
 * BankTask depositAll = BankTask.depositAll()
 *     .withDescription("Bank all items");
 *
 * // Withdraw all of an item
 * BankTask withdrawAll = BankTask.withdraw(ItemID.COINS, WithdrawQuantity.ALL);
 * }</pre>
 */
@Slf4j
public class BankTask extends AbstractTask {

    // ========================================================================
    // Widget Constants (from RuneLite InterfaceID)
    // ========================================================================

    /**
     * Bank main interface group ID.
     */
    public static final int WIDGET_BANK_GROUP = 12;

    /**
     * Bank side inventory group ID.
     */
    public static final int WIDGET_BANK_INVENTORY_GROUP = 15;

    /**
     * Bank container widget (main window).
     */
    public static final int WIDGET_BANK_CONTAINER = 1;

    /**
     * Bank items container.
     */
    public static final int WIDGET_BANK_ITEMS = 13;

    /**
     * Bank tabs container.
     */
    public static final int WIDGET_BANK_TABS = 11;

    /**
     * Bank search button.
     */
    public static final int WIDGET_BANK_SEARCH = 42; // 0x2a

    /**
     * Deposit inventory button.
     */
    public static final int WIDGET_DEPOSIT_INVENTORY = 44; // 0x2c

    /**
     * Deposit equipment button.
     */
    public static final int WIDGET_DEPOSIT_EQUIPMENT = 46; // 0x2e

    /**
     * Bank close button (X in corner).
     */
    public static final int WIDGET_BANK_CLOSE = 3;

    /**
     * Inventory items in bank side panel.
     */
    public static final int WIDGET_BANK_INV_ITEMS = 3;

    /**
     * Quantity selection buttons.
     */
    public static final int WIDGET_QUANTITY_1 = 30;
    public static final int WIDGET_QUANTITY_5 = 32;
    public static final int WIDGET_QUANTITY_10 = 34;
    public static final int WIDGET_QUANTITY_X = 36;
    public static final int WIDGET_QUANTITY_ALL = 38;

    // ========================================================================
    // Chatbox Widget Constants (for X quantity input)
    // ========================================================================


    /**
     * Chatbox widget group ID.
     */
    public static final int WIDGET_CHATBOX_GROUP = 162;

    /**
     * Chatbox input child widget.
     */
    public static final int WIDGET_CHATBOX_INPUT = 45;

    /**
     * Chatbox title text child (shows "Enter amount:" or similar).
     */
    public static final int WIDGET_CHATBOX_TITLE = 44;

    // ========================================================================
    // Menu Entry Options
    // ========================================================================

    /**
     * Withdraw menu option prefix for exact quantities.
     */
    private static final String MENU_WITHDRAW_X = "Withdraw-X";
    private static final String MENU_WITHDRAW_ALL = "Withdraw-All";
    private static final String MENU_WITHDRAW_ALL_BUT_1 = "Withdraw-All-but-1";

    /**
     * Deposit menu option prefix for exact quantities.
     */
    private static final String MENU_DEPOSIT_X = "Deposit-X";
    private static final String MENU_DEPOSIT_ALL = "Deposit-All";

    // ========================================================================
    // Common Bank Object/NPC IDs
    // ========================================================================

    /**
     * Common bank booth object IDs.
     */
    private static final Set<Integer> BANK_BOOTH_IDS = Set.of(
            10355, 10583, 18491, 20325, 20326, 20327, 20328, 24101, 25808, 26707,
            27254, 27260, 27263, 27265, 27267, 27292, 28429, 28430, 28431, 28861,
            29085, 30015, 32666, 34752, 34810, 35647, 36559, 37474
    );

    /**
     * Bank booth IDs that LOOK like bank booths but cannot be interacted with.
     * These are typically the staff-side/back of bank booths or decorative variants.
     */
    private static final Set<Integer> BANK_BOOTH_BLACKLIST = Set.of(
            10527,  // BANKPRIVATEBOOTH - back/staff side of Draynor bank booths
            10528,  // Another private booth variant
            10529   // Another private booth variant
    );

    /**
     * Common banker NPC IDs.
     */
    private static final Set<Integer> BANKER_NPC_IDS = Set.of(
            394, 395, 396, 397, 2897, 2898, 3194, 5488, 5901, 6200, 6362, 7049,
            7050, 8948
    );

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The operation to perform.
     */
    @Getter
    private final BankOperation operation;

    /**
     * Item ID for withdraw/deposit operations.
     */
    @Getter
    private final int itemId;

    /**
     * Quantity to withdraw/deposit.
     */
    @Getter
    private final int quantity;

    /**
     * Quantity mode for withdrawal.
     */
    @Getter
    private final WithdrawQuantity quantityMode;

    /**
     * Whether to close bank after operation.
     */
    @Getter
    @Setter
    private boolean closeAfter = true;

    /**
     * Search radius for finding bank.
     */
    @Getter
    @Setter
    private int searchRadius = 50;

    /**
     * Description for logging.
     */
    @Setter
    private String description;

    /**
     * Whether to withdraw as noted.
     */
    @Getter
    @Setter
    private boolean withdrawNoted = false;

    /**
     * Item IDs to exclude when depositing (for DEPOSIT_ALL operation).
     * When non-empty, deposits items individually instead of using "Deposit All" button.
     */
    @Getter
    private Set<Integer> exceptItemIds = Collections.emptySet();

    // ========================================================================
    // Execution State
    // ========================================================================

    /**
     * Current execution phase.
     */
    private BankPhase phase = BankPhase.FIND_BANK;

    /**
     * Target bank object (booth).
     */
    private GameObject targetBooth;

    /**
     * Target banker NPC.
     */
    private NPC targetBanker;

    /**
     * Sub-task for walking/traveling to bank when too far.
     */
    private WalkToTask walkSubTask;

    /**
     * Whether an async operation is pending.
     */
    private boolean operationPending = false;

    /**
     * Ticks waiting for response.
     */
    private int waitTicks = 0;

    /**
     * Retry counter for failed operations.
     */
    private int operationRetryCount = 0;

    /**
     * Phase to return to after dismissing tutorial.
     */
    private BankPhase returnPhaseAfterTutorial = BankPhase.SET_QUANTITY_MODE;

    /**
     * Maximum distance (tiles) to directly interact without walking closer.
     */
    private static final int INTERACTION_DISTANCE = 15;

    /**
     * Maximum ticks to wait for bank to open.
     */
    private static final int BANK_OPEN_TIMEOUT = 15;

    /**
     * Maximum ticks to wait for item operation.
     */
    private static final int OPERATION_TIMEOUT = 10;

    /**
     * Maximum ticks to wait for chatbox input to appear.
     */
    private static final int CHATBOX_TIMEOUT = 10;

    /**
     * Whether we need to enter a quantity in chatbox.
     */
    private boolean awaitingQuantityInput = false;


    /**
     * Whether we've already set the X quantity for this session.
     * RuneLite remembers the last X value, so we only need to enter it once.
     */
    @Getter
    @Setter
    private static int lastXQuantity = 0;

    /**
     * Counter for redundant bank open/close actions.
     * Per REQUIREMENTS.md 3.4.4: 3% of bank trips open/close bank twice before transaction.
     */
    private int redundantActionsRemaining = 0;

    /**
     * Whether we've checked for redundant action injection.
     */
    private boolean redundantActionChecked = false;

    /**
     * Index tracking progress through individual item deposits (for except mode).
     */
    private int depositIndex = 0;

    /**
     * List of item IDs to deposit individually (populated when exceptItemIds is used).
     */
    private List<Integer> itemsToDepositIndividually = null;

    /**
     * Cached current quantity mode (tracked from widget state).
     */
    private WithdrawQuantity currentQuantityMode = null;

    /**
     * Whether we've determined the deposit strategy for this task.
     */
    private boolean depositStrategyDetermined = false;

    /**
     * Chosen deposit strategy for this task.
     */
    private DepositStrategy depositStrategy = DepositStrategy.BUTTON;

    /**
     * Override quantity mode to set before performing operation.
     * Used when deposit strategy requires ALL mode but it's not currently set.
     */
    private WithdrawQuantity quantityModeOverride = null;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create a bank task with specified operation.
     */
    private BankTask(BankOperation operation, int itemId, int quantity, WithdrawQuantity quantityMode) {
        this.operation = operation;
        this.itemId = itemId;
        this.quantity = quantity;
        this.quantityMode = quantityMode;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a task to withdraw items.
     *
     * @param itemId   the item to withdraw
     * @param quantity the quantity to withdraw
     * @return bank task configured for withdrawal
     */
    public static BankTask withdraw(int itemId, int quantity) {
        return new BankTask(BankOperation.WITHDRAW, itemId, quantity, WithdrawQuantity.EXACT);
    }

    /**
     * Create a task to withdraw items with a quantity mode.
     *
     * @param itemId       the item to withdraw
     * @param quantityMode the quantity mode (ALL, X, etc.)
     * @return bank task configured for withdrawal
     */
    public static BankTask withdraw(int itemId, WithdrawQuantity quantityMode) {
        return new BankTask(BankOperation.WITHDRAW, itemId, 0, quantityMode);
    }

    /**
     * Create a task to deposit specific items.
     *
     * @param itemId   the item to deposit
     * @param quantity the quantity to deposit (-1 for all)
     * @return bank task configured for deposit
     */
    public static BankTask deposit(int itemId, int quantity) {
        return new BankTask(BankOperation.DEPOSIT, itemId, quantity, WithdrawQuantity.EXACT);
    }

    /**
     * Create a task to deposit all inventory items.
     *
     * @return bank task configured for deposit all
     */
    public static BankTask depositAll() {
        return new BankTask(BankOperation.DEPOSIT_ALL, -1, -1, WithdrawQuantity.ALL);
    }

    /**
     * Create a task to deposit all equipment.
     *
     * @return bank task configured for deposit equipment
     */
    public static BankTask depositEquipment() {
        return new BankTask(BankOperation.DEPOSIT_EQUIPMENT, -1, -1, WithdrawQuantity.ALL);
    }

    /**
     * Create a task to just open the bank.
     *
     * @return bank task that only opens bank
     */
    public static BankTask open() {
        BankTask task = new BankTask(BankOperation.OPEN_ONLY, -1, -1, WithdrawQuantity.ALL);
        task.setCloseAfter(false);
        return task;
    }

    /**
     * Create a task to withdraw items using X-mode.
     * This sets the default quantity mode to X, allowing fast left-click withdrawals.
     * Ideal for bulk operations like withdrawing 14 herbs and 14 vials.
     *
     * <p>Usage:
     * <pre>{@code
     * // Set X mode to 14 and withdraw herbs
     * BankTask withdrawHerbs = BankTask.withdrawX(ItemID.GUAM_LEAF, 14);
     * // Subsequent withdraws with same quantity can use simple left-clicks
     * BankTask withdrawVials = BankTask.withdraw(ItemID.VIAL_OF_WATER, WithdrawQuantity.X);
     * }</pre>
     *
     * @param itemId   the item to withdraw
     * @param quantity the X quantity to set
     * @return bank task configured for X-mode withdrawal
     */
    public static BankTask withdrawX(int itemId, int quantity) {
        BankTask task = new BankTask(BankOperation.WITHDRAW, itemId, quantity, WithdrawQuantity.X);
        return task;
    }

    /**
     * Create a task to deposit items using X-mode.
     *
     * @param itemId   the item to deposit
     * @param quantity the X quantity to set
     * @return bank task configured for X-mode deposit
     */
    public static BankTask depositX(int itemId, int quantity) {
        return new BankTask(BankOperation.DEPOSIT, itemId, quantity, WithdrawQuantity.X);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set whether to close bank after operation.
     */
    public BankTask withCloseAfter(boolean close) {
        this.closeAfter = close;
        return this;
    }

    /**
     * Set search radius for finding bank.
     */
    public BankTask withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    /**
     * Set description for logging.
     */
    public BankTask withDescription(String desc) {
        this.description = desc;
        return this;
    }

    /**
     * Set whether to withdraw as noted items.
     */
    public BankTask withWithdrawNoted(boolean noted) {
        this.withdrawNoted = noted;
        return this;
    }

    /**
     * Set items to exclude when depositing.
     * When used with {@link #depositAll()}, deposits items individually,
     * skipping the specified item IDs.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Deposit all except axe and knife (keep tools)
     * BankTask task = BankTask.depositAll()
     *     .exceptItems(Set.of(ItemID.RUNE_AXE, ItemID.KNIFE));
     * }</pre>
     *
     * @param itemIds item IDs to keep in inventory
     * @return this task for chaining
     */
    public BankTask exceptItems(Set<Integer> itemIds) {
        if (itemIds != null && !itemIds.isEmpty()) {
            this.exceptItemIds = new HashSet<>(itemIds);
        }
        return this;
    }

    /**
     * Set a single item to exclude when depositing.
     * Convenience method for {@link #exceptItems(Set)}.
     *
     * @param itemId item ID to keep in inventory
     * @return this task for chaining
     */
    public BankTask exceptItem(int itemId) {
        this.exceptItemIds = Set.of(itemId);
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        // Handle sub-task execution
        if (walkSubTask != null) {
            executeWalkSubTask(ctx);
            return;
        }

        switch (phase) {
            case FIND_BANK:
                executeFindBank(ctx);
                break;
            case WALK_TO_BANK:
                executeWalkToBank(ctx);
                break;
            case OPEN_BANK:
                executeOpenBank(ctx);
                break;
            case WAIT_BANK_OPEN:
                executeWaitBankOpen(ctx);
                break;
            case DISMISS_TUTORIAL:
                executeDismissTutorial(ctx);
                break;
            case SET_QUANTITY_MODE:
                executeSetQuantityMode(ctx);
                break;
            case PERFORM_OPERATION:
                executePerformOperation(ctx);
                break;
            case WAIT_CHATBOX_INPUT:
                executeWaitChatboxInput(ctx);
                break;
            case ENTER_QUANTITY:
                executeEnterQuantity(ctx);
                break;
            case WAIT_OPERATION:
                executeWaitOperation(ctx);
                break;
            case CLOSE_BANK:
                executeCloseBank(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Find Bank
    // ========================================================================

    private void executeFindBank(TaskContext ctx) {
        Client client = ctx.getClient();
        PlayerState player = ctx.getPlayerState();
        WorldPoint playerPos = player.getWorldPosition();

        if (playerPos == null) {
            fail("Cannot determine player position");
            return;
        }

        // Check if bank is already open
        if (isBankOpen(client)) {
            log.debug("Bank is already open");
            phase = (operation == BankOperation.OPEN_ONLY) ? BankPhase.CLOSE_BANK : BankPhase.SET_QUANTITY_MODE;
            if (operation == BankOperation.OPEN_ONLY) {
                complete();
            }
            return;
        }

        // Find both booth and banker, then randomly pick if both available
        targetBooth = findNearestBankBooth(ctx, playerPos);
        targetBanker = findNearestBanker(ctx, playerPos);

        if (targetBooth != null && targetBanker != null) {
            // Both available - randomly pick for human-like behavior
            if (Randomization.secureBoolean()) {
                log.debug("Found both bank booth and banker - choosing booth");
                targetBanker = null;
            } else {
                log.debug("Found both bank booth and banker - choosing banker");
                targetBooth = null;
            }
        }

        if (targetBooth != null) {
            log.debug("Found bank booth {} at {}", targetBooth.getId(), targetBooth.getWorldLocation());
            int distance = playerPos.distanceTo(targetBooth.getWorldLocation());
            if (distance > INTERACTION_DISTANCE) {
                log.debug("Bank booth is {} tiles away, walking closer first", distance);
                phase = BankPhase.WALK_TO_BANK;
            } else {
                phase = BankPhase.OPEN_BANK;
            }
            return;
        }

        if (targetBanker != null) {
            log.debug("Found banker {} at {}", targetBanker.getId(), targetBanker.getWorldLocation());
            int distance = playerPos.distanceTo(targetBanker.getWorldLocation());
            if (distance > INTERACTION_DISTANCE) {
                log.debug("Banker is {} tiles away, walking closer first", distance);
                phase = BankPhase.WALK_TO_BANK;
            } else {
                phase = BankPhase.OPEN_BANK;
            }
            return;
        }

        fail("No bank found within " + searchRadius + " tiles");
    }

    private GameObject findNearestBankBooth(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int playerPlane = playerPos.getPlane();

        GameObject nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int x = 0; x < net.runelite.api.Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < net.runelite.api.Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) continue;

                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects == null) continue;

                for (GameObject obj : gameObjects) {
                    if (obj == null) continue;

                    int id = obj.getId();
                    // Check for bank booths
                    if (BANK_BOOTH_IDS.contains(id) || isBankBoothByName(client, id)) {
                        WorldPoint objPos = obj.getWorldLocation();
                        if (objPos != null) {
                            int dist = playerPos.distanceTo(objPos);
                            if (dist <= searchRadius && dist < nearestDist) {
                                nearest = obj;
                                nearestDist = dist;
                            }
                        }
                    }
                }
            }
        }
        return nearest;
    }

    private boolean isBankBoothByName(Client client, int objectId) {
        // Check blacklist first - some objects are named "bank booth" but can't be used
        if (BANK_BOOTH_BLACKLIST.contains(objectId)) {
            return false;
        }
        
        ObjectComposition def = client.getObjectDefinition(objectId);
        if (def == null) return false;
        String name = def.getName();
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        return lowerName.contains("bank booth") || lowerName.contains("bank chest");
    }

    private NPC findNearestBanker(TaskContext ctx, WorldPoint playerPos) {
        Client client = ctx.getClient();
        NPC nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;

            if (BANKER_NPC_IDS.contains(npc.getId()) || isBankerByName(npc)) {
                WorldPoint npcPos = npc.getWorldLocation();
                if (npcPos != null) {
                    int dist = playerPos.distanceTo(npcPos);
                    if (dist <= searchRadius && dist < nearestDist) {
                        nearest = npc;
                        nearestDist = dist;
                    }
                }
            }
        }
        return nearest;
    }

    private boolean isBankerByName(NPC npc) {
        String name = npc.getName();
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        return lowerName.contains("banker") || lowerName.equals("bank tutor");
    }

    // ========================================================================
    // Phase: Walk To Bank
    // ========================================================================

    private void executeWalkToBank(TaskContext ctx) {
        WorldPoint targetPos = null;
        
        if (targetBooth != null) {
            targetPos = targetBooth.getWorldLocation();
        } else if (targetBanker != null) {
            targetPos = targetBanker.getWorldLocation();
        }

        if (targetPos == null) {
            fail("Lost bank target while traveling");
            return;
        }

        // Create walk sub-task (uses WebWalker for optimal path including teleports)
        walkSubTask = new WalkToTask(targetPos)
                .withDescription("Travel to bank");
        log.debug("Starting travel to bank at {}", targetPos);
    }

    private void executeWalkSubTask(TaskContext ctx) {
        // Execute the walk task (handles state transitions automatically)
        walkSubTask.execute(ctx);

        // Check if walk is done
        if (walkSubTask.getState().isTerminal()) {
            if (walkSubTask.getState() == TaskState.COMPLETED) {
                log.debug("Travel to bank completed, proceeding to open");
                phase = BankPhase.OPEN_BANK;
            } else {
                log.warn("Travel to bank failed: {}", walkSubTask.getFailureReason());
                fail("Failed to travel to bank");
            }
            walkSubTask = null;
        }
    }

    // ========================================================================
    // Phase: Open Bank (uses SafeClickExecutor)
    // ========================================================================

    private void executeOpenBank(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        // Check for redundant action injection (per REQUIREMENTS.md 3.4.4)
        if (!redundantActionChecked) {
            redundantActionChecked = true;
            var inefficiency = ctx.getInefficiencyInjector();
            if (inefficiency != null) {
                InefficiencyInjector.InefficiencyResult result = inefficiency.checkBankInefficiency();
                if (result != null && result.isPresent()
                        && result.getType() == InefficiencyInjector.InefficiencyType.REDUNDANT_ACTION) {
                    redundantActionsRemaining = result.getAmount();
                } else if (inefficiency.shouldPerformRedundantAction()) {
                    redundantActionsRemaining = inefficiency.getRedundantRepetitions();
                }
                if (redundantActionsRemaining > 0) {
                    log.debug("Injecting {} redundant bank open/close actions", redundantActionsRemaining);
                }
            }
        }

        SafeClickExecutor safeClick = ctx.getSafeClickExecutor();

        if (targetBooth != null) {
            // Click bank booth
            Rectangle bounds = targetBooth.getClickbox() != null ?
                    targetBooth.getClickbox().getBounds() : null;
            if (bounds == null || bounds.width == 0) {
                log.debug("Bank booth not visible, walking closer...");
                waitTicks++;
                if (waitTicks > 5) {
                    // Target not visible, need to walk closer
                    phase = BankPhase.WALK_TO_BANK;
                    waitTicks = 0;
                }
                return;
            }

            log.debug("Clicking bank booth");
            operationPending = true;
            safeClick.clickObject(targetBooth, "Bank")
                    .thenAccept(success -> {
                        operationPending = false;
                        if (success) {
                            waitTicks = 0;
                            phase = BankPhase.WAIT_BANK_OPEN;
                        } else {
                            fail("Failed to click bank booth");
                        }
                    })
                    .exceptionally(e -> {
                        operationPending = false;
                        log.error("Bank booth click failed", e);
                        fail("Click failed: " + e.getMessage());
                        return null;
                    });
        } else if (targetBanker != null) {
            // Click banker NPC
            Rectangle bounds = targetBanker.getConvexHull() != null ?
                    targetBanker.getConvexHull().getBounds() : null;
            if (bounds == null || bounds.width == 0) {
                log.debug("Banker not visible, walking closer...");
                waitTicks++;
                if (waitTicks > 5) {
                    // Target not visible, need to walk closer
                    phase = BankPhase.WALK_TO_BANK;
                    waitTicks = 0;
                }
                return;
            }

            log.debug("Clicking banker");
            operationPending = true;
            safeClick.clickNpc(targetBanker, "Bank")
                    .thenAccept(success -> {
                        operationPending = false;
                        if (success) {
                            waitTicks = 0;
                            phase = BankPhase.WAIT_BANK_OPEN;
                        } else {
                            fail("Failed to click banker");
                        }
                    })
                    .exceptionally(e -> {
                        operationPending = false;
                        log.error("Banker click failed", e);
                        fail("Click failed: " + e.getMessage());
                        return null;
                    });
        } else {
            fail("Lost bank target");
        }
    }

    // ========================================================================
    // Phase: Wait Bank Open
    // ========================================================================

    private void executeWaitBankOpen(TaskContext ctx) {
        Client client = ctx.getClient();

        if (isBankOpen(client)) {
            log.debug("Bank opened successfully");
            
            // Debug: dump ALL visible bank widgets on first open
            if (waitTicks == 0) {
                logAllBankWidgets(client);
            }
            
            // Check for first-time bank tutorial dialog blocking interaction
            if (isBankTutorialVisible(client)) {
                log.info("Bank tutorial dialog detected (first-time use) - will dismiss");
                returnPhaseAfterTutorial = BankPhase.SET_QUANTITY_MODE;
                phase = BankPhase.DISMISS_TUTORIAL;
                waitTicks = 0;
                return;
            }
            
            // Handle redundant action: close and reopen bank
            if (redundantActionsRemaining > 0) {
                redundantActionsRemaining--;
                log.debug("Performing redundant bank close/reopen ({} remaining)", redundantActionsRemaining);
                
                // Close the bank, wait, then go back to OPEN_BANK phase
                // Chain the operations properly to avoid race conditions
                operationPending = true;
                closeBank(ctx)
                    .thenCompose(v -> ctx.getHumanTimer().sleep(
                        ctx.getRandomization().uniformRandomLong(300, 800)))
                    .thenRun(() -> {
                        operationPending = false;
                        phase = BankPhase.OPEN_BANK;
                    })
                    .exceptionally(e -> {
                        operationPending = false;
                        log.error("Redundant bank close failed", e);
                        phase = BankPhase.OPEN_BANK;
                        return null;
                    });
                return;
            }
            
            if (operation == BankOperation.OPEN_ONLY) {
                complete();
                return;
            }
            phase = BankPhase.SET_QUANTITY_MODE;
            return;
        }

        waitTicks++;
        if (waitTicks > BANK_OPEN_TIMEOUT) {
            // Go back to FIND_BANK to re-evaluate (target may have moved, we may be too far)
            log.debug("Bank didn't open, re-evaluating...");
            phase = BankPhase.FIND_BANK;
            targetBooth = null;
            targetBanker = null;
            waitTicks = 0;
        }
    }

    // ========================================================================
    // Phase: Dismiss Tutorial (first-time bank use)
    // ========================================================================

    /**
     * Dismiss the tutorial dialog that appears on first use.
     * Uses the generic tutorial system in WidgetInteractionHelpers.
     */
    private void executeDismissTutorial(TaskContext ctx) {
        if (operationPending) {
            return;
        }

        // Check if tutorial is still visible
        if (!isBankTutorialVisible(ctx.getClient())) {
            log.debug("Tutorial dismissed, returning to {}", returnPhaseAfterTutorial);
            phase = returnPhaseAfterTutorial;
            waitTicks = 0;
            return;
        }

        // Use the shared utility to dismiss
        operationPending = true;
        WidgetInteractionHelpers.dismissGenericTutorial(ctx)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        log.info("Dismissed tutorial successfully");
                        recordProgress();
                    } else {
                        log.warn("Failed to dismiss tutorial, will retry");
                        waitTicks++;
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Tutorial dismiss failed", e);
                    waitTicks++;
                    return null;
                });

        // Safety timeout
        if (waitTicks > 10) {
            log.error("Could not dismiss tutorial after {} attempts!", waitTicks);
            fail("Cannot dismiss tutorial");
        }
    }

    /**
     * Debug: brute force scan ALL widget groups to find the tutorial.
     */
    private void logAllBankWidgets(Client client) {
        log.info("=== BRUTE FORCE SCAN: ALL WIDGET GROUPS ===");
        
        // Scan all groups 0-1000
        for (int groupId = 0; groupId < 1000; groupId++) {
            for (int childId = 0; childId < 30; childId++) {
                Widget w = client.getWidget(groupId, childId);
                if (w != null && !w.isHidden()) {
                    String text = w.getText();
                    if (text != null && !text.isEmpty()) {
                        // Log ANY visible widget with text
                        log.info("Widget {}:{} text='{}'", groupId, childId, 
                                text.substring(0, Math.min(80, text.length())));
                    }
                }
            }
        }
        log.info("=== END BRUTE FORCE SCAN ===");
    }

    /**
     * Check if a screen highlight tutorial overlay is visible.
     * Delegates to WidgetInteractionHelpers for the generic tutorial system.
     */
    private static boolean isBankTutorialVisible(Client client) {
        return WidgetInteractionHelpers.isGenericTutorialVisible(client);
    }

    // ========================================================================
    // Phase: Set Quantity Mode
    // ========================================================================

    private void executeSetQuantityMode(TaskContext ctx) {
        // Handle override from deposit strategy (e.g., need ALL mode for left-click deposits)
        if (quantityModeOverride != null) {
            int overrideWidget = getQuantityWidget(quantityModeOverride);
            if (overrideWidget >= 0) {
                log.debug("Setting quantity mode to {} for deposit strategy", quantityModeOverride);
                currentQuantityMode = quantityModeOverride;
                quantityModeOverride = null;  // Clear override
                clickQuantityButton(ctx, overrideWidget, BankPhase.PERFORM_OPERATION);
                return;
            }
            quantityModeOverride = null;
        }
        
        // For deposit all/equipment, skip quantity selection (unless strategy requires it)
        if (operation == BankOperation.DEPOSIT_ALL || operation == BankOperation.DEPOSIT_EQUIPMENT) {
            phase = BankPhase.PERFORM_OPERATION;
            return;
        }

        // For exact quantities (one-off right-click), skip quantity button
        if (quantityMode == WithdrawQuantity.EXACT) {
            phase = BankPhase.PERFORM_OPERATION;
            return;
        }

        // For X mode with specific quantity, check if we need to set it
        if (quantityMode == WithdrawQuantity.X && quantity > 0) {
            // If the last X quantity matches, we can skip the input dialog
            if (lastXQuantity == quantity) {
                log.debug("X quantity already set to {}, using left-click mode", quantity);
                // Just click the X button to ensure mode is active, then left-click items
                clickQuantityButton(ctx, WIDGET_QUANTITY_X, BankPhase.PERFORM_OPERATION);
                return;
            }
            // Need to set a new X value - click X button and wait for input dialog
            log.debug("Setting X quantity to {} (was {})", quantity, lastXQuantity);
            awaitingQuantityInput = true;
            clickQuantityButton(ctx, WIDGET_QUANTITY_X, BankPhase.WAIT_CHATBOX_INPUT);
            return;
        }

        // Click the appropriate quantity button for other modes
        int quantityWidget = getQuantityWidget(quantityMode);
        if (quantityWidget < 0) {
            phase = BankPhase.PERFORM_OPERATION;
            return;
        }

        clickQuantityButton(ctx, quantityWidget, BankPhase.PERFORM_OPERATION);
    }

    /**
     * Click a quantity button and transition to the next phase.
     * Uses WidgetClickHelper for humanized interaction.
     */
    private void clickQuantityButton(TaskContext ctx, int widgetChildId, BankPhase nextPhase) {
        Client client = ctx.getClient();
        Widget qtyWidget = client.getWidget(WIDGET_BANK_GROUP, widgetChildId);

        if (qtyWidget == null || qtyWidget.isHidden()) {
            log.debug("Quantity widget {} not found, proceeding", widgetChildId);
            phase = nextPhase;
            return;
        }

        operationPending = true;
        ctx.getWidgetClickHelper().clickWidget(qtyWidget, "Quantity button")
                .thenAccept(success -> {
                    operationPending = false;
                    waitTicks = 0;
                    phase = nextPhase;
                    if (!success) {
                        log.debug("Quantity button click returned false, proceeding anyway");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.debug("Failed to click quantity button, proceeding anyway");
                    phase = nextPhase;
                    return null;
                });
    }

    private int getQuantityWidget(WithdrawQuantity mode) {
        switch (mode) {
            case ONE: return WIDGET_QUANTITY_1;
            case FIVE: return WIDGET_QUANTITY_5;
            case TEN: return WIDGET_QUANTITY_10;
            case X: return WIDGET_QUANTITY_X;
            case ALL: return WIDGET_QUANTITY_ALL;
            default: return -1;
        }
    }

    // ========================================================================
    // Phase: Perform Operation
    // ========================================================================

    private void executePerformOperation(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if tutorial is blocking (can appear on first bank use)
        if (isBankTutorialVisible(client)) {
            log.warn("Bank tutorial blocking operation - dismissing first");
            returnPhaseAfterTutorial = BankPhase.PERFORM_OPERATION;
            phase = BankPhase.DISMISS_TUTORIAL;
            waitTicks = 0;
            return;
        }

        switch (operation) {
            case DEPOSIT_ALL:
                clickDepositAll(ctx, client);
                break;
            case DEPOSIT_EQUIPMENT:
                clickDepositEquipment(ctx, client);
                break;
            case DEPOSIT:
                depositItem(ctx, client);
                break;
            case WITHDRAW:
                withdrawItem(ctx, client);
                break;
            case OPEN_ONLY:
                complete();
                break;
        }
    }

    private void clickDepositAll(TaskContext ctx, Client client) {
        // Determine deposit strategy on first call (humanized behavior)
        if (!depositStrategyDetermined) {
            depositStrategyDetermined = true;
            currentQuantityMode = detectCurrentQuantityMode(client);
            
            if (exceptItemIds.isEmpty()) {
                // True "deposit all" - no exceptions
                depositStrategy = determineDepositAllStrategy(ctx);
            } else {
                // "Deposit all except" - need to deposit items individually
                buildItemsToDepositList(ctx);
                depositStrategy = determineDepositExceptStrategy(ctx, currentQuantityMode);
            }
            
            log.debug("Deposit strategy: {} (quantity mode: {}, {} items to deposit)",
                    depositStrategy, currentQuantityMode, 
                    itemsToDepositIndividually != null ? itemsToDepositIndividually.size() : "all");
        }

        // Execute based on strategy
        switch (depositStrategy) {
            case BUTTON:
                executeDepositButton(ctx, client);
                break;
            case LEFT_CLICK_ALL_MODE:
                executeDepositLeftClick(ctx, client);
                break;
            case RIGHT_CLICK_MENU:
                executeDepositRightClick(ctx, client);
                break;
        }
    }

    /**
     * Determine strategy for true "deposit all" (no exceptions).
     * 95% use button, 5% use All mode + left-click items.
     */
    private DepositStrategy determineDepositAllStrategy(TaskContext ctx) {
        double roll = Randomization.secureDouble();
        
        // Get profile preference if available (some players never use button)
        double buttonPreference = 0.95;
        if (ctx.getPlayerProfile() != null) {
            // Use click variance as a proxy for "efficiency" - higher variance = more likely to click items
            buttonPreference = 0.90 + (1.0 - ctx.getPlayerProfile().getClickVarianceModifier()) * 0.08;
        }
        
        if (roll < buttonPreference) {
            return DepositStrategy.BUTTON;
        } else {
            // Build item list for left-click approach
            buildItemsToDepositList(ctx);
            return DepositStrategy.LEFT_CLICK_ALL_MODE;
        }
    }

    /**
     * Determine strategy for "deposit all except" cases.
     * Behavior depends on current quantity mode and number of items.
     */
    private DepositStrategy determineDepositExceptStrategy(TaskContext ctx, WithdrawQuantity currentMode) {
        int numUniqueItems = itemsToDepositIndividually != null ? itemsToDepositIndividually.size() : 0;
        double roll = Randomization.secureDouble();
        
        // Profile-based modifier
        double leftClickBias = 0.90;  // Base 90% left-click preference
        if (ctx.getPlayerProfile() != null) {
            // Players with faster mouse speed tend to prefer left-click
            leftClickBias = 0.85 + ctx.getPlayerProfile().getMouseSpeedMultiplier() * 0.08;
            leftClickBias = Math.min(0.98, leftClickBias);
        }
        
        if (currentMode == WithdrawQuantity.ALL) {
            // "All" mode is already selected - can left-click efficiently
            if (numUniqueItems > 2) {
                // >2 unique items: 100% left-click (no one right-clicks 3+ items)
                return DepositStrategy.LEFT_CLICK_ALL_MODE;
            } else {
                // ≤2 unique items: 90% left-click, 10% right-click
                return roll < leftClickBias 
                        ? DepositStrategy.LEFT_CLICK_ALL_MODE 
                        : DepositStrategy.RIGHT_CLICK_MENU;
            }
        } else {
            // "All" mode NOT selected
            if (numUniqueItems > 2) {
                // >2 unique items: 95% select "All" mode first (via SET_QUANTITY_MODE phase)
                if (roll < 0.95) {
                    // Need to set quantity mode to ALL first
                    phase = BankPhase.SET_QUANTITY_MODE;
                    quantityModeOverride = WithdrawQuantity.ALL;
                    return DepositStrategy.LEFT_CLICK_ALL_MODE;
                } else {
                    return DepositStrategy.RIGHT_CLICK_MENU;
                }
            } else {
                // ≤2 unique items: either right-click (common) or spam left-click
                return roll < 0.70 
                        ? DepositStrategy.RIGHT_CLICK_MENU 
                        : DepositStrategy.LEFT_CLICK_ALL_MODE;
            }
        }
    }

    /**
     * Build list of unique item IDs to deposit (excluding excepted items).
     */
    private void buildItemsToDepositList(TaskContext ctx) {
        itemsToDepositIndividually = new ArrayList<>();
        InventoryState inventory = ctx.getInventoryState();
        
        if (inventory != null) {
            Set<Integer> seen = new HashSet<>();
            for (net.runelite.api.Item item : inventory.getAllItems()) {
                if (item != null && item.getId() > 0) {
                    int itemId = item.getId();
                    if (!exceptItemIds.contains(itemId) && !seen.contains(itemId)) {
                        itemsToDepositIndividually.add(itemId);
                        seen.add(itemId);
                    }
                }
            }
        }
        
        depositIndex = 0;
    }

    /**
     * Detect the currently selected quantity mode from bank widget state.
     */
    private WithdrawQuantity detectCurrentQuantityMode(Client client) {
        // Check which quantity button appears "pressed" (sprite state)
        // The pressed button typically has a different sprite ID or state
        
        // Check ALL button first (most relevant for deposit operations)
        Widget allBtn = client.getWidget(WIDGET_BANK_GROUP, WIDGET_QUANTITY_ALL);
        if (allBtn != null && !allBtn.isHidden()) {
            // Sprite ID for "pressed" state varies, but we can check common patterns
            // For now, assume default is not ALL unless we explicitly set it
        }
        
        // If we previously set a quantity mode this session, use that
        // Otherwise default to unknown (assume not ALL)
        return null;  // Unknown/not ALL
    }

    /**
     * Execute deposit using the "Deposit Inventory" button.
     */
    private void executeDepositButton(TaskContext ctx, Client client) {
        Widget depositBtn = client.getWidget(WIDGET_BANK_GROUP, WIDGET_DEPOSIT_INVENTORY);
        if (depositBtn == null || depositBtn.isHidden()) {
            fail("Deposit inventory button not found");
            return;
        }
        clickWidget(ctx, depositBtn, "Deposit all");
    }

    /**
     * Execute deposit by left-clicking items (requires ALL mode).
     */
    private void executeDepositLeftClick(TaskContext ctx, Client client) {
        // Ensure we have items list
        if (itemsToDepositIndividually == null) {
            buildItemsToDepositList(ctx);
        }
        
        // Check if done
        if (depositIndex >= itemsToDepositIndividually.size()) {
            log.debug("Left-click deposits complete");
            waitTicks = 0;
            phase = BankPhase.WAIT_OPERATION;
            return;
        }
        
        int itemIdToDeposit = itemsToDepositIndividually.get(depositIndex);
        
        Widget invContainer = client.getWidget(WIDGET_BANK_INVENTORY_GROUP, WIDGET_BANK_INV_ITEMS);
        if (invContainer == null) {
            fail("Bank inventory widget not found");
            return;
        }

        Widget itemWidget = findItemInWidget(invContainer, itemIdToDeposit);
        if (itemWidget == null) {
            log.debug("Item {} not found in inventory, skipping", itemIdToDeposit);
            depositIndex++;
            return;
        }

        log.debug("Left-click depositing item {} ({}/{})", 
                itemIdToDeposit, depositIndex + 1, itemsToDepositIndividually.size());
        
        // Simple left-click (uses current quantity mode)
        operationPending = true;
        ctx.getWidgetClickHelper().clickWidget(itemWidget, "Deposit item")
                .thenAccept(success -> {
                    operationPending = false;
                    depositIndex++;
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.debug("Left-click deposit failed for item {}, skipping", itemIdToDeposit);
                    depositIndex++;
                    return null;
                });
    }

    /**
     * Execute deposit by right-clicking and selecting "Deposit-All".
     */
    private void executeDepositRightClick(TaskContext ctx, Client client) {
        // Ensure we have items list
        if (itemsToDepositIndividually == null) {
            buildItemsToDepositList(ctx);
        }
        
        // Check if done
        if (depositIndex >= itemsToDepositIndividually.size()) {
            log.debug("Right-click deposits complete");
            waitTicks = 0;
            phase = BankPhase.WAIT_OPERATION;
            return;
        }
        
        int itemIdToDeposit = itemsToDepositIndividually.get(depositIndex);
        
        Widget invContainer = client.getWidget(WIDGET_BANK_INVENTORY_GROUP, WIDGET_BANK_INV_ITEMS);
        if (invContainer == null) {
            fail("Bank inventory widget not found");
            return;
        }

        Widget itemWidget = findItemInWidget(invContainer, itemIdToDeposit);
        if (itemWidget == null) {
            log.debug("Item {} not found in inventory, skipping", itemIdToDeposit);
            depositIndex++;
            return;
        }

        log.debug("Right-click depositing all of item {} ({}/{})", 
                itemIdToDeposit, depositIndex + 1, itemsToDepositIndividually.size());
        
        Rectangle bounds = itemWidget.getBounds();
        if (bounds == null || bounds.width == 0) {
            depositIndex++;
            return;
        }

        operationPending = true;
        ctx.getMenuHelper().selectMenuEntry(bounds, MENU_DEPOSIT_ALL)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        depositIndex++;
                    } else {
                        log.debug("Deposit-All menu failed, skipping item");
                        depositIndex++;
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.debug("Deposit failed for item {}, skipping", itemIdToDeposit);
                    depositIndex++;
                    return null;
                });
    }

    private void clickDepositEquipment(TaskContext ctx, Client client) {
        Widget depositBtn = client.getWidget(WIDGET_BANK_GROUP, WIDGET_DEPOSIT_EQUIPMENT);
        if (depositBtn == null || depositBtn.isHidden()) {
            fail("Deposit equipment button not found");
            return;
        }

        clickWidget(ctx, depositBtn, "Deposit equipment");
    }

    private void depositItem(TaskContext ctx, Client client) {
        // Find item in bank inventory (side panel)
        Widget invContainer = client.getWidget(WIDGET_BANK_INVENTORY_GROUP, WIDGET_BANK_INV_ITEMS);
        if (invContainer == null) {
            fail("Bank inventory not found");
            return;
        }

        Widget itemWidget = findItemInWidget(invContainer, itemId);
        if (itemWidget == null) {
            fail("Item " + itemId + " not found in inventory");
            return;
        }

        // Right-click to deposit specific quantity or left-click for current quantity mode
        if (quantity > 0 && quantityMode == WithdrawQuantity.EXACT) {
            // Need to right-click and select deposit option
            rightClickDeposit(ctx, itemWidget);
        } else {
            clickWidget(ctx, itemWidget, "Deposit item");
        }
    }

    private void withdrawItem(TaskContext ctx, Client client) {
        // Find item in bank
        Widget bankItems = client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_ITEMS);
        if (bankItems == null) {
            fail("Bank items widget not found");
            return;
        }

        Widget itemWidget = findItemInWidget(bankItems, itemId);
        if (itemWidget == null) {
            fail("Item " + itemId + " not found in bank");
            return;
        }

        // For exact quantity, use right-click menu
        if (quantity > 0 && quantityMode == WithdrawQuantity.EXACT) {
            rightClickWithdraw(ctx, itemWidget);
        } else {
            clickWidget(ctx, itemWidget, "Withdraw item");
        }
    }

    private Widget findItemInWidget(Widget container, int targetItemId) {
        Widget[] children = container.getDynamicChildren();
        if (children == null) {
            children = container.getStaticChildren();
        }
        if (children == null) return null;

        for (Widget child : children) {
            if (child != null && child.getItemId() == targetItemId) {
                return child;
            }
        }
        return null;
    }

    /**
     * Click a widget using WidgetClickHelper.
     */
    private void clickWidget(TaskContext ctx, Widget widget, String actionDesc) {
        operationPending = true;
        ctx.getWidgetClickHelper().clickWidget(widget, actionDesc)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                    log.debug("{} clicked", actionDesc);
                    waitTicks = 0;
                    phase = BankPhase.WAIT_OPERATION;
                    } else {
                        fail("Click failed: " + actionDesc);
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to {}", actionDesc, e);
                    fail("Click failed");
                    return null;
                });
    }

    /**
     * Perform a right-click deposit with exact quantity.
     * Uses MenuHelper to open context menu and select "Deposit-X" entry.
     */
    private void rightClickDeposit(TaskContext ctx, Widget widget) {
        awaitingQuantityInput = true;
        
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            fail("Widget has no bounds for right-click");
            return;
        }

        log.debug("Right-clicking to deposit {} of item {}", quantity, itemId);
        operationPending = true;

        ctx.getMenuHelper().selectMenuEntry(bounds, MENU_DEPOSIT_X)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        log.debug("Deposit menu entry selected");
                    waitTicks = 0;
                        phase = BankPhase.WAIT_CHATBOX_INPUT;
                    } else {
                        fail("Menu entry selection failed for Deposit-X");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to select deposit menu entry", e);
                    fail("Menu selection failed");
                    return null;
                });
    }

    /**
     * Perform a right-click withdrawal with exact quantity.
     * Uses MenuHelper to open context menu and select "Withdraw-X" entry.
     */
    private void rightClickWithdraw(TaskContext ctx, Widget widget) {
        awaitingQuantityInput = true;
        
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) {
            fail("Widget has no bounds for right-click");
            return;
        }

        log.debug("Right-clicking to withdraw {} of item {}", quantity, itemId);
            operationPending = true;

        ctx.getMenuHelper().selectMenuEntry(bounds, MENU_WITHDRAW_X)
                .thenAccept(success -> {
                    operationPending = false;
                    if (success) {
                        log.debug("Withdraw menu entry selected");
                    waitTicks = 0;
                    phase = BankPhase.WAIT_CHATBOX_INPUT;
                    } else {
                        fail("Menu entry selection failed for Withdraw-X");
                    }
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to select withdraw menu entry", e);
                    fail("Menu selection failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait for Chatbox Input
    // ========================================================================

    private void executeWaitChatboxInput(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if chatbox input is visible
        Widget chatboxInput = client.getWidget(WIDGET_CHATBOX_GROUP, WIDGET_CHATBOX_INPUT);
        if (chatboxInput != null && !chatboxInput.isHidden()) {
            log.debug("Chatbox input appeared");
            phase = BankPhase.ENTER_QUANTITY;
            return;
        }

        waitTicks++;
        if (waitTicks > CHATBOX_TIMEOUT) {
            log.warn("Chatbox input didn't appear, assuming operation proceeded");
            // Maybe the operation didn't need X input (like if clicking Withdraw-All)
            phase = BankPhase.WAIT_OPERATION;
        }
    }

    // ========================================================================
    // Phase: Enter Quantity in Chatbox
    // ========================================================================

    private void executeEnterQuantity(TaskContext ctx) {
        Client client = ctx.getClient();

        // Verify chatbox is still open
        Widget chatboxInput = client.getWidget(WIDGET_CHATBOX_GROUP, WIDGET_CHATBOX_INPUT);
        if (chatboxInput == null || chatboxInput.isHidden()) {
            log.warn("Chatbox closed unexpectedly");
            phase = BankPhase.WAIT_OPERATION;
            return;
        }

        String quantityStr = String.valueOf(quantity);
        log.debug("Entering quantity: {}", quantityStr);

        operationPending = true;
        
        // Type the quantity and press Enter (EXPECTED - typing familiar value)
        ctx.getKeyboardController().type(quantityStr)
                .thenCompose(v -> ctx.getHumanTimer().sleepContextual(ReactionContext.EXPECTED))
                .thenCompose(v -> ctx.getKeyboardController().pressEnter())
                .thenRun(() -> {
                    operationPending = false;
                    // Remember this X value for future operations
                    lastXQuantity = quantity;
                    waitTicks = 0;
                    phase = BankPhase.WAIT_OPERATION;
                    log.debug("Quantity entered, waiting for operation to complete");
                })
                .exceptionally(e -> {
                    operationPending = false;
                    log.error("Failed to enter quantity", e);
                    fail("Quantity entry failed");
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait Operation
    // ========================================================================

    private void executeWaitOperation(TaskContext ctx) {
        Client client = ctx.getClient();
        waitTicks++;

        // Check if tutorial appeared and is blocking (can appear mid-operation)
        if (isBankTutorialVisible(client)) {
            log.warn("Bank tutorial appeared during operation - dismissing");
            returnPhaseAfterTutorial = BankPhase.PERFORM_OPERATION;
            phase = BankPhase.DISMISS_TUTORIAL;
            waitTicks = 0;
            return;
        }

        // Check if operation completed based on operation type
        boolean operationComplete = false;

        switch (operation) {
            case DEPOSIT_ALL:
                if (exceptItemIds.isEmpty()) {
                    operationComplete = ctx.getInventoryState().isEmpty();
                } else {
                    // Check that inventory only contains excepted items
                    operationComplete = inventoryOnlyContainsExceptedItems(ctx.getInventoryState());
                }
                break;
            case DEPOSIT:
                operationComplete = !ctx.getInventoryState().hasItem(itemId);
                break;
            case WITHDRAW:
                operationComplete = ctx.getInventoryState().hasItem(itemId);
                break;
            case DEPOSIT_EQUIPMENT:
                // Check if equipment is empty - would need EquipmentState
                operationComplete = waitTicks > 2;
                break;
            default:
                operationComplete = true;
        }

        if (operationComplete) {
            log.debug("Bank operation completed successfully");
            if (closeAfter) {
                phase = BankPhase.CLOSE_BANK;
            } else {
                complete();
            }
            return;
        }

        if (waitTicks > OPERATION_TIMEOUT) {
            // Operation did NOT complete - this is a failure, not success!
            log.error("Bank operation timed out - inventory state did not change as expected");
            
            // Check what might have gone wrong
            if (!isBankOpen(client)) {
                fail("Bank closed unexpectedly during operation");
            } else {
                // Bank is still open but operation didn't work - retry
                operationRetryCount++;
                if (operationRetryCount >= 3) {
                    fail("Bank operation failed after 3 retries - items not deposited/withdrawn");
                } else {
                    log.warn("Retrying bank operation (attempt {}/3)", operationRetryCount + 1);
                    waitTicks = 0;
                    phase = BankPhase.PERFORM_OPERATION;
                }
            }
        }
    }

    // ========================================================================
    // Phase: Close Bank
    // ========================================================================

    private void executeCloseBank(TaskContext ctx) {
        Client client = ctx.getClient();

        if (!isBankOpen(client)) {
            complete();
            return;
        }

        // Press Escape to close bank (more human-like)
        operationPending = true;
        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_ESCAPE)
                .thenRun(() -> {
                    operationPending = false;
                    complete();
                })
                .exceptionally(e -> {
                    operationPending = false;
                    // Try clicking close button
                    Widget closeBtn = client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_CLOSE);
                    if (closeBtn != null && !closeBtn.isHidden()) {
                        ctx.getWidgetClickHelper().clickWidget(closeBtn, "Close bank button")
                                    .thenRun(this::complete);
                    } else {
                        fail("Failed to close bank");
                    }
                    return null;
                });
    }
    
    /**
     * Close the bank interface without completing the task.
     * Used for redundant action injection (close and reopen).
     * 
     * @return CompletableFuture that completes when the close key is pressed
     */
    private CompletableFuture<Void> closeBank(TaskContext ctx) {
        // Press Escape to close bank (more human-like than clicking X)
        return ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_ESCAPE)
                .exceptionally(e -> {
                    log.debug("Failed to close bank via ESC: {}", e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if bank interface is currently open.
     */
    public static boolean isBankOpen(Client client) {
        Widget bankContainer = client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_CONTAINER);
        return bankContainer != null && !bankContainer.isHidden();
    }

    /**
     * Check if inventory only contains items from the exceptItemIds set.
     * Used to determine if "deposit all except" operation is complete.
     */
    private boolean inventoryOnlyContainsExceptedItems(InventoryState inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return true;
        }
        
        for (net.runelite.api.Item item : inventory.getAllItems()) {
            if (item != null && item.getId() > 0) {
                if (!exceptItemIds.contains(item.getId())) {
                    return false; // Found an item that should have been deposited
                }
            }
        }
        return true;
    }

    // ========================================================================
    // Idle Bank Check (Reusable for Break/Ritual Tasks)
    // ========================================================================

    /**
     * Maximum distance (in tiles) to consider a bank "nearby" for idle checks.
     * ~30 game ticks of walking time, roughly 20 tiles.
     */
    private static final int IDLE_BANK_CHECK_MAX_DISTANCE = 20;

    /**
     * Perform an idle bank check ritual - find nearby bank, open it briefly, 
     * hover over a few tabs/items, then close.
     * 
     * <p>This is a reusable method for break tasks and session rituals that want
     * to simulate a player casually checking their bank without performing any
     * actual banking operations.
     * 
     * <p>If no bank is within {@link #IDLE_BANK_CHECK_MAX_DISTANCE} tiles, the
     * future completes with {@code false} (skipped). Otherwise completes with
     * {@code true} after the bank check is performed.
     *
     * @param ctx the task context
     * @param randomization randomization for timing variance
     * @param humanTimer timer for delays
     * @return CompletableFuture that completes with true if bank check was performed,
     *         false if skipped due to distance
     */
    public static CompletableFuture<Boolean> performIdleBankCheck(
            TaskContext ctx, 
            Randomization randomization, 
            com.rocinante.timing.HumanTimer humanTimer) {
        
        Client client = ctx.getClient();
        WorldPoint playerPos = ctx.getPlayerState().getWorldPosition();
        
        if (playerPos == null) {
            log.debug("Cannot perform idle bank check - player position unknown");
            return CompletableFuture.completedFuture(false);
        }

        // Find nearest bank booth or banker within distance limit
        GameObject booth = findNearestBankBoothStatic(client, playerPos, IDLE_BANK_CHECK_MAX_DISTANCE);
        NPC banker = findNearestBankerStatic(client, playerPos, IDLE_BANK_CHECK_MAX_DISTANCE);

        if (booth == null && banker == null) {
            log.debug("No bank within {} tiles for idle check, skipping", IDLE_BANK_CHECK_MAX_DISTANCE);
            return CompletableFuture.completedFuture(false);
        }

        // Pick one randomly if both available
        final boolean useBooth;
        if (booth != null && banker != null) {
            useBooth = Randomization.secureBoolean();
        } else {
            useBooth = booth != null;
        }

        log.debug("Performing idle bank check at {}", useBooth ? "booth" : "banker");

        // Open bank, hover briefly, close
        return openBankForIdleCheck(ctx, useBooth ? booth : null, useBooth ? null : banker)
                .thenCompose(opened -> {
                    if (!opened) {
                        return CompletableFuture.completedFuture(false);
                    }
                    // Brief pause after opening
                    return humanTimer.sleep(randomization.uniformRandomLong(300, 600))
                            .thenApply(v -> true);
                })
                .thenCompose(opened -> {
                    if (!opened) {
                        return CompletableFuture.completedFuture(false);
                    }
                    // Hover over 2-4 bank tabs or items
                    return performIdleBankHovers(ctx, randomization, humanTimer)
                            .thenApply(v -> true);
                })
                .thenCompose(success -> {
                    if (!success) {
                        return CompletableFuture.completedFuture(false);
                    }
                    // Close bank with ESC
                    return ctx.getKeyboardController().pressKey(KeyEvent.VK_ESCAPE)
                            .thenCompose(v -> humanTimer.sleep(randomization.uniformRandomLong(100, 300)))
                            .thenApply(v -> true);
                })
                .exceptionally(e -> {
                    log.debug("Idle bank check failed: {}", e.getMessage());
                    // Try to close bank if it's open
                    if (isBankOpen(client)) {
                        ctx.getKeyboardController().pressKey(KeyEvent.VK_ESCAPE);
                    }
                    return false;
                });
    }

    /**
     * Find nearest bank booth within radius (static version for idle checks).
     */
    private static GameObject findNearestBankBoothStatic(Client client, WorldPoint playerPos, int maxDistance) {
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int playerPlane = playerPos.getPlane();

        GameObject nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int x = 0; x < net.runelite.api.Constants.SCENE_SIZE; x++) {
            for (int y = 0; y < net.runelite.api.Constants.SCENE_SIZE; y++) {
                Tile tile = tiles[playerPlane][x][y];
                if (tile == null) continue;

                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects == null) continue;

                for (GameObject obj : gameObjects) {
                    if (obj == null) continue;

                    int id = obj.getId();
                    if (BANK_BOOTH_IDS.contains(id) || isBankBoothByNameStatic(client, id)) {
                        WorldPoint objPos = obj.getWorldLocation();
                        if (objPos != null) {
                            int dist = playerPos.distanceTo(objPos);
                            if (dist <= maxDistance && dist < nearestDist) {
                                nearest = obj;
                                nearestDist = dist;
                            }
                        }
                    }
                }
            }
        }
        return nearest;
    }

    private static boolean isBankBoothByNameStatic(Client client, int objectId) {
        // Check blacklist first - some objects are named "bank booth" but can't be used
        if (BANK_BOOTH_BLACKLIST.contains(objectId)) {
            return false;
        }
        
        ObjectComposition def = client.getObjectDefinition(objectId);
        if (def == null) return false;
        String name = def.getName();
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        return lowerName.contains("bank booth") || lowerName.contains("bank chest");
    }

    /**
     * Find nearest banker NPC within radius (static version for idle checks).
     */
    private static NPC findNearestBankerStatic(Client client, WorldPoint playerPos, int maxDistance) {
        NPC nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;

            if (BANKER_NPC_IDS.contains(npc.getId()) || isBankerByNameStatic(npc)) {
                WorldPoint npcPos = npc.getWorldLocation();
                if (npcPos != null) {
                    int dist = playerPos.distanceTo(npcPos);
                    if (dist <= maxDistance && dist < nearestDist) {
                        nearest = npc;
                        nearestDist = dist;
                    }
                }
            }
        }
        return nearest;
    }

    private static boolean isBankerByNameStatic(NPC npc) {
        String name = npc.getName();
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        return lowerName.contains("banker") || lowerName.equals("bank tutor");
    }

    /**
     * Open bank for idle check (no walking, direct click only).
     */
    private static CompletableFuture<Boolean> openBankForIdleCheck(
            TaskContext ctx, GameObject booth, NPC banker) {
        
        Client client = ctx.getClient();
        
        // Already open?
        if (isBankOpen(client)) {
            return CompletableFuture.completedFuture(true);
        }

        SafeClickExecutor safeClick = ctx.getSafeClickExecutor();

        if (booth != null) {
            Rectangle bounds = booth.getClickbox() != null ? booth.getClickbox().getBounds() : null;
            if (bounds == null || bounds.width == 0) {
                log.debug("Bank booth not visible for idle check");
                return CompletableFuture.completedFuture(false);
            }
            return safeClick.clickObject(booth, "Bank")
                    .thenCompose(success -> {
                        if (!success) {
                            return CompletableFuture.completedFuture(false);
                        }
                        // Wait for bank to open (up to ~3 seconds)
                        return waitForBankOpen(client, 5);
                    });
        } else if (banker != null) {
            Rectangle bounds = banker.getConvexHull() != null ? banker.getConvexHull().getBounds() : null;
            if (bounds == null || bounds.width == 0) {
                log.debug("Banker not visible for idle check");
                return CompletableFuture.completedFuture(false);
            }
            return safeClick.clickNpc(banker, "Bank")
                    .thenCompose(success -> {
                        if (!success) {
                            return CompletableFuture.completedFuture(false);
                        }
                        return waitForBankOpen(client, 5);
                    });
        }

        return CompletableFuture.completedFuture(false);
    }

    /**
     * Wait for bank to open with polling.
     */
    private static CompletableFuture<Boolean> waitForBankOpen(Client client, int maxAttempts) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // Simple polling with CompletableFuture chain
        checkBankOpenRecursive(client, maxAttempts, 0, future);
        
        return future;
    }

    private static void checkBankOpenRecursive(Client client, int maxAttempts, int attempt, 
                                                CompletableFuture<Boolean> future) {
        if (isBankOpen(client)) {
            future.complete(true);
            return;
        }
        if (attempt >= maxAttempts) {
            future.complete(false);
            return;
        }
        
        // Schedule next check after 600ms
        CompletableFuture.delayedExecutor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> checkBankOpenRecursive(client, maxAttempts, attempt + 1, future));
    }

    /**
     * Perform idle hovers over bank content.
     */
    private static CompletableFuture<Void> performIdleBankHovers(
            TaskContext ctx, Randomization randomization, 
            com.rocinante.timing.HumanTimer humanTimer) {
        
        Client client = ctx.getClient();
        int hoverCount = randomization.uniformRandomInt(2, 4);
        
        // Chain hovers sequentially
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        
        for (int i = 0; i < hoverCount; i++) {
            chain = chain.thenCompose(v -> {
                // Try to hover over a bank tab or item
                Widget bankTabs = client.getWidget(WIDGET_BANK_GROUP, WIDGET_BANK_TABS);
                Widget target = null;
                
                if (bankTabs != null && !bankTabs.isHidden()) {
                    Widget[] children = bankTabs.getDynamicChildren();
                    if (children != null && children.length > 0) {
                        int idx = randomization.uniformRandomInt(0, children.length - 1);
                        target = children[idx];
                    }
                }
                
                if (target != null && !target.isHidden()) {
                    Rectangle bounds = target.getBounds();
                    if (bounds != null && bounds.width > 0) {
                        int x = bounds.x + randomization.uniformRandomInt(5, Math.max(6, bounds.width - 5));
                        int y = bounds.y + randomization.uniformRandomInt(5, Math.max(6, bounds.height - 5));
                        return ctx.getMouseController().moveToCanvas(x, y)
                                .thenCompose(v2 -> humanTimer.sleep(
                                        randomization.uniformRandomLong(400, 1000)));
                    }
                }
                
                // Fallback: idle behavior
                return ctx.getMouseController().performIdleBehavior()
                        .thenCompose(v2 -> humanTimer.sleep(randomization.uniformRandomLong(300, 700)));
            });
        }
        
        return chain;
    }

    @Override
    public String getTaskType() {
        return "BANKING";
    }
    
    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        switch (operation) {
            case WITHDRAW:
                return String.format("BankTask[withdraw %d x%d]", itemId, quantity);
            case DEPOSIT:
                return String.format("BankTask[deposit %d x%d]", itemId, quantity);
            case DEPOSIT_ALL:
                return "BankTask[deposit all]";
            case DEPOSIT_EQUIPMENT:
                return "BankTask[deposit equipment]";
            case OPEN_ONLY:
                return "BankTask[open]";
            default:
                return "BankTask[" + operation + "]";
        }
    }

    // ========================================================================
    // Enums
    // ========================================================================

    /**
     * Bank operation types.
     */
    public enum BankOperation {
        OPEN_ONLY,
        WITHDRAW,
        DEPOSIT,
        DEPOSIT_ALL,
        DEPOSIT_EQUIPMENT
    }

    /**
     * Withdrawal quantity modes.
     */
    public enum WithdrawQuantity {
        ONE,
        FIVE,
        TEN,
        X,
        ALL,
        EXACT
    }

    /**
     * Execution phases.
     */
    private enum BankPhase {
        FIND_BANK,
        WALK_TO_BANK,
        OPEN_BANK,
        WAIT_BANK_OPEN,
        DISMISS_TUTORIAL,
        SET_QUANTITY_MODE,
        PERFORM_OPERATION,
        WAIT_CHATBOX_INPUT,
        ENTER_QUANTITY,
        WAIT_OPERATION,
        CLOSE_BANK
    }

    /**
     * Strategy for depositing items (humanized behavior).
     */
    private enum DepositStrategy {
        /** Use the "Deposit Inventory" button (most common for true deposit-all) */
        BUTTON,
        /** Left-click items with "All" quantity mode selected */
        LEFT_CLICK_ALL_MODE,
        /** Right-click and select "Deposit-All" from menu */
        RIGHT_CLICK_MENU
    }
}

