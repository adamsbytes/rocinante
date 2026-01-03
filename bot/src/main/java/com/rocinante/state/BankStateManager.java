package com.rocinante.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the player's bank state with persistence across sessions.
 *
 * <p>This singleton service:
 * <ul>
 *   <li>Captures bank contents when the bank interface is open</li>
 *   <li>Persists bank state to disk for cross-session availability</li>
 *   <li>Loads persisted bank state on login</li>
 *   <li>Provides thread-safe access to bank state</li>
 * </ul>
 *
 * <p>Bank state is saved:
 * <ul>
 *   <li>When the bank interface closes</li>
 *   <li>Periodically every 5 minutes while bank is open</li>
 *   <li>On logout</li>
 * </ul>
 *
 * <p>Persistence location: {@code ~/.runelite/rocinante/bank/{account_hash}.json}
 */
@Slf4j
@Singleton
public class BankStateManager {

    /**
     * Directory for bank state persistence.
     */
    private static final String BANK_STATE_DIR = ".runelite/rocinante/bank";

    /**
     * Current schema version for persistence format.
     */
    private static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Bank widget group ID.
     */
    private static final int WIDGET_BANK_GROUP = 12;

    /**
     * Periodic save interval in minutes.
     */
    private static final int SAVE_INTERVAL_MINUTES = 5;

    private final Client client;
    private final Gson gson;
    private final ScheduledExecutorService saveExecutor;

    /**
     * Current bank state snapshot.
     */
    @Getter
    private volatile BankState bankState = BankState.UNKNOWN;

    /**
     * Hash of the current account name for persistence.
     */
    private volatile String accountHash;

    /**
     * Current game tick (updated externally).
     */
    private volatile int currentTick = 0;

    /**
     * Whether the bank interface is currently open.
     */
    @Getter
    private volatile boolean bankOpen = false;

    /**
     * Whether bank state has been modified since last save.
     */
    private volatile boolean dirty = false;

    @Inject
    public BankStateManager(Client client) {
        this.client = client;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        this.saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Thread-12");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule periodic saves
        saveExecutor.scheduleAtFixedRate(
                this::saveIfDirty,
                SAVE_INTERVAL_MINUTES,
                SAVE_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        
        log.info("BankStateManager initialized");
    }

    /**
     * Constructor for testing with custom executor.
     */
    public BankStateManager(Client client, ScheduledExecutorService executor) {
        this.client = client;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.saveExecutor = executor;
    }

    // ========================================================================
    // Event Handlers
    // ========================================================================

    /**
     * Handle game state changes (login/logout).
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();

        if (state == GameState.LOGGED_IN) {
            // Load persisted bank state for this account
            String accountName = client.getLocalPlayer() != null ? 
                    client.getLocalPlayer().getName() : null;
            if (accountName != null && !accountName.isEmpty()) {
                initializeForAccount(accountName);
            }
        } else if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST) {
            // Save and reset on logout
            saveIfDirty();
            bankState = BankState.UNKNOWN;
            accountHash = null;
            bankOpen = false;
            dirty = false;
        }
    }

    /**
     * Handle bank item container changes.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.BANK.getId()) {
            return;
        }

        // Bank container was updated - capture new state
        ItemContainer container = event.getItemContainer();
        if (container == null) {
            return;
        }

        Item[] items = container.getItems();
        bankState = BankState.fromItemContainer(items, currentTick);
        dirty = true;
        bankOpen = true;

        log.debug("Bank state updated: {} items at tick {}", 
                bankState.getUsedSlots(), currentTick);
    }

    /**
     * Handle widget close events to detect bank closure.
     */
    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        if (event.getGroupId() == WIDGET_BANK_GROUP) {
            bankOpen = false;
            log.debug("Bank interface closed");
            
            // Save bank state when bank closes
            if (dirty) {
                saveExecutor.submit(this::save);
            }
        }
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Initialize bank state for a specific account.
     * Called on login to load persisted bank state.
     *
     * @param accountName the account name
     */
    public void initializeForAccount(String accountName) {
        this.accountHash = hashAccountName(accountName);
        
        Path path = getBankStatePath();
        if (Files.exists(path)) {
            loadBankState(path);
        } else {
            bankState = BankState.UNKNOWN;
            log.info("No persisted bank state found for account hash: {}", accountHash);
        }
    }

    /**
     * Hash the account name for file naming.
     */
    private String hashAccountName(String accountName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(accountName.toLowerCase().getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) { // First 8 bytes = 16 hex chars
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return Integer.toHexString(accountName.toLowerCase().hashCode());
        }
    }

    /**
     * Get the path to the bank state file.
     */
    private Path getBankStatePath() {
        String homeDir = System.getProperty("user.home");
        return Paths.get(homeDir, BANK_STATE_DIR, accountHash + ".json");
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    /**
     * Load bank state from disk.
     */
    private void loadBankState(Path path) {
        try {
            String json = Files.readString(path);
            PersistedBankState persisted = gson.fromJson(json, PersistedBankState.class);
            
            if (persisted != null && persisted.isValid()) {
                // Handle schema migration if needed
                if (persisted.schemaVersion < CURRENT_SCHEMA_VERSION) {
                    migrateSchema(persisted);
                }
                
                bankState = persisted.toBankState();
                log.info("Loaded bank state from {}: {} items, last updated {}",
                        path, bankState.getUsedSlots(), 
                        Instant.ofEpochSecond(persisted.lastUpdatedEpoch));
            } else {
                log.warn("Invalid persisted bank state at {}, starting fresh", path);
                bankState = BankState.UNKNOWN;
            }
        } catch (IOException | JsonSyntaxException e) {
            log.warn("Failed to load bank state from {}: {}", path, e.getMessage());
            bankState = BankState.UNKNOWN;
        }
    }

    /**
     * Save bank state to disk.
     */
    public void save() {
        if (accountHash == null || bankState.isUnknown()) {
            return;
        }

        Path path = getBankStatePath();
        try {
            Files.createDirectories(path.getParent());

            // Backup existing file
            if (Files.exists(path)) {
                Path backup = path.resolveSibling(accountHash + ".json.bak");
                Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            PersistedBankState persisted = PersistedBankState.fromBankState(bankState);
            String json = gson.toJson(persisted);
            Files.writeString(path, json);
            
            dirty = false;
            log.debug("Saved bank state to {}: {} items", path, bankState.getUsedSlots());
        } catch (IOException e) {
            log.error("Failed to save bank state to {}: {}", path, e.getMessage());
        }
    }

    /**
     * Save only if there are unsaved changes.
     */
    private void saveIfDirty() {
        if (dirty && accountHash != null && !bankState.isUnknown()) {
            save();
        }
    }

    /**
     * Migrate old schema versions to current.
     */
    private void migrateSchema(PersistedBankState persisted) {
        // Future: handle schema migrations
        log.info("Migrating bank state from schema v{} to v{}", 
                persisted.schemaVersion, CURRENT_SCHEMA_VERSION);
        persisted.schemaVersion = CURRENT_SCHEMA_VERSION;
    }

    // ========================================================================
    // State Access
    // ========================================================================

    /**
     * Update the current tick (called by GameStateService).
     *
     * @param tick the current game tick
     */
    public void setCurrentTick(int tick) {
        this.currentTick = tick;
    }

    /**
     * Check if bank state is known (has been observed at least once).
     *
     * @return true if bank has been observed
     */
    public boolean isBankKnown() {
        return !bankState.isUnknown();
    }

    /**
     * Check if bank interface is currently open.
     * Uses widget visibility check for accuracy.
     *
     * @return true if bank is open
     */
    public boolean isBankInterfaceOpen() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return false;
        }
        
        Widget bankWidget = client.getWidget(WIDGET_BANK_GROUP, 0);
        return bankWidget != null && !bankWidget.isHidden();
    }

    /**
     * Force refresh bank state from client.
     * Only works when bank interface is open.
     *
     * @return true if refresh was successful
     */
    public boolean refreshFromClient() {
        if (!isBankInterfaceOpen()) {
            return false;
        }

        ItemContainer container = client.getItemContainer(InventoryID.BANK);
        if (container == null) {
            return false;
        }

        Item[] items = container.getItems();
        bankState = BankState.fromItemContainer(items, currentTick);
        dirty = true;
        return true;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Shutdown the manager (save and cleanup).
     */
    public void shutdown() {
        saveIfDirty();
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("BankStateManager shutdown complete");
    }

    // ========================================================================
    // Persistence Data Class
    // ========================================================================

    /**
     * Data class for JSON persistence.
     */
    private static class PersistedBankState {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        long lastUpdatedEpoch;
        int lastUpdatedTick;
        List<PersistedItem> items;

        boolean isValid() {
            return items != null;
        }

        BankState toBankState() {
            List<BankState.BankItem> bankItems = new ArrayList<>();
            for (PersistedItem item : items) {
                bankItems.add(BankState.BankItem.builder()
                        .itemId(item.itemId)
                        .quantity(item.quantity)
                        .slot(item.slot)
                        .tab(item.tab)
                        .build());
            }
            return new BankState(bankItems, lastUpdatedTick, false);
        }

        static PersistedBankState fromBankState(BankState state) {
            PersistedBankState persisted = new PersistedBankState();
            persisted.schemaVersion = CURRENT_SCHEMA_VERSION;
            persisted.lastUpdatedEpoch = Instant.now().getEpochSecond();
            persisted.lastUpdatedTick = state.getLastUpdatedTick();
            persisted.items = new ArrayList<>();

            for (BankState.BankItem item : state.getAllItems()) {
                PersistedItem pi = new PersistedItem();
                pi.itemId = item.getItemId();
                pi.quantity = item.getQuantity();
                pi.slot = item.getSlot();
                pi.tab = item.getTab();
                persisted.items.add(pi);
            }

            return persisted;
        }
    }

    /**
     * Individual item for persistence.
     */
    private static class PersistedItem {
        int itemId;
        int quantity;
        int slot;
        int tab;
    }
}

