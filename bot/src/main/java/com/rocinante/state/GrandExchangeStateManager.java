package com.rocinante.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Grand Exchange state with event subscription and persistence.
 *
 * <p>This singleton service:
 * <ul>
 *   <li>Subscribes to {@link GrandExchangeOfferChanged} events</li>
 *   <li>Builds immutable {@link GrandExchangeState} snapshots</li>
 *   <li>Tracks buy limits with 4-hour reset windows</li>
 *   <li>Persists buy limit timers across sessions</li>
 *   <li>Provides thread-safe access to GE state</li>
 * </ul>
 *
 * <p>Buy limit persistence location: {@code ~/.runelite/rocinante/ge/{account_hash}.json}
 */
@Slf4j
@Singleton
public class GrandExchangeStateManager {

    /**
     * Directory for GE state persistence.
     */
    private static final String GE_STATE_DIR = ".runelite/rocinante/ge";

    /**
     * Current schema version for persistence format.
     */
    private static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Periodic save interval in minutes.
     */
    private static final int SAVE_INTERVAL_MINUTES = 5;

    /**
     * GE interface widget group ID.
     */
    private static final int WIDGET_GE_GROUP = 465;

    private final Client client;
    private final ItemManager itemManager;
    private final Gson gson;
    private final ScheduledExecutorService saveExecutor;

    /**
     * Current GE state snapshot.
     */
    @Getter
    private volatile GrandExchangeState geState = GrandExchangeState.UNKNOWN;

    /**
     * Buy limit tracking data (item ID -> limit info).
     * Uses ConcurrentHashMap for thread-safety during event processing.
     */
    private final Map<Integer, GrandExchangeState.BuyLimitInfo> buyLimits = new ConcurrentHashMap<>();

    /**
     * Cached offers from RuneLite events.
     * Stores the latest offer for each slot.
     */
    private final GrandExchangeOffer[] cachedOffers = new GrandExchangeOffer[GrandExchangeState.TOTAL_SLOTS];

    /**
     * Previous quantities sold per slot (for tracking purchases).
     */
    private final int[] previousQuantitySold = new int[GrandExchangeState.TOTAL_SLOTS];

    /**
     * Hash of the current account name for persistence.
     */
    private volatile String accountHash;

    /**
     * Current game tick (updated externally).
     */
    private volatile int currentTick = 0;

    /**
     * Whether GE state has been modified since last save.
     */
    private volatile boolean dirty = false;

    /**
     * Whether the player is on a members world.
     */
    @Getter
    private volatile boolean isMember = true;

    @Inject
    public GrandExchangeStateManager(Client client, ItemManager itemManager) {
        this.client = client;
        this.itemManager = itemManager;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .create();
        this.saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GEState-Saver");
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

        log.info("GrandExchangeStateManager initialized");
    }

    /**
     * Constructor for testing with custom executor.
     */
    public GrandExchangeStateManager(Client client, ItemManager itemManager, ScheduledExecutorService executor) {
        this.client = client;
        this.itemManager = itemManager;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .create();
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
            // Detect membership status from world type
            updateMembershipStatus();
            
            // Load persisted buy limit data for this account
            String accountName = client.getLocalPlayer() != null ?
                    client.getLocalPlayer().getName() : null;
            if (accountName != null && !accountName.isEmpty()) {
                initializeForAccount(accountName);
            }
            
            // Initialize from current GE offers
            refreshFromClient();
        } else if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST) {
            // Save and reset on logout
            saveIfDirty();
            geState = GrandExchangeState.UNKNOWN;
            accountHash = null;
            dirty = false;
            
            // Clear cached offers
            for (int i = 0; i < GrandExchangeState.TOTAL_SLOTS; i++) {
                cachedOffers[i] = null;
                previousQuantitySold[i] = 0;
            }
        }
    }

    /**
     * Handle GE offer changes.
     * This is the primary way we track GE state changes.
     */
    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        int slot = event.getSlot();
        GrandExchangeOffer offer = event.getOffer();

        if (slot < 0 || slot >= GrandExchangeState.TOTAL_SLOTS) {
            log.warn("Invalid GE slot index: {}", slot);
            return;
        }

        // Track buy limit updates for buy offers
        if (offer != null && offer.getState() != GrandExchangeOfferState.EMPTY) {
            trackBuyLimit(slot, offer);
        }

        // Update cached offer
        cachedOffers[slot] = offer;

        // Rebuild state snapshot
        rebuildState();
        dirty = true;

        log.debug("GE offer changed - slot {}: {}", slot, 
                offer != null ? offer.getState() : "null");
    }

    // ========================================================================
    // Buy Limit Tracking
    // ========================================================================

    /**
     * Track buy limit usage when an offer progresses.
     */
    private void trackBuyLimit(int slot, GrandExchangeOffer offer) {
        // Only track buy offers
        GrandExchangeOfferState state = offer.getState();
        if (state != GrandExchangeOfferState.BUYING && 
            state != GrandExchangeOfferState.BOUGHT &&
            state != GrandExchangeOfferState.CANCELLED_BUY) {
            return;
        }

        int itemId = offer.getItemId();
        int currentQuantitySold = offer.getQuantitySold();
        int previousSold = previousQuantitySold[slot];

        // Calculate how many items were just bought
        int newlyBought = currentQuantitySold - previousSold;
        if (newlyBought > 0) {
            updateBuyLimit(itemId, newlyBought);
            log.debug("Buy limit updated for item {}: +{} bought", itemId, newlyBought);
        }

        // Update previous quantity for next comparison
        previousQuantitySold[slot] = currentQuantitySold;

        // Reset previous quantity when offer completes or is cancelled
        if (state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.CANCELLED_BUY) {
            // Keep the tracking until slot is cleared (EMPTY state)
        }
    }

    /**
     * Update buy limit tracking for an item.
     */
    private void updateBuyLimit(int itemId, int quantityBought) {
        GrandExchangeState.BuyLimitInfo existing = buyLimits.get(itemId);
        
        if (existing == null || existing.hasReset()) {
            // Start a new tracking window
            buyLimits.put(itemId, GrandExchangeState.BuyLimitInfo.create(itemId, quantityBought));
        } else {
            // Add to existing window
            buyLimits.put(itemId, existing.withAdditionalPurchase(quantityBought));
        }
        
        dirty = true;
    }

    /**
     * Get the buy limit for an item from ItemManager.
     *
     * @param itemId the item ID
     * @return the buy limit, or -1 if unknown
     */
    public int getItemBuyLimit(int itemId) {
        if (itemManager == null) {
            return -1;
        }
        
        try {
            // Use the non-deprecated getItemStats(int) method
            var itemStats = itemManager.getItemStats(itemId);
            if (itemStats != null) {
                return itemStats.getGeLimit();
            }
        } catch (Exception e) {
            log.trace("Failed to get item stats for {}: {}", itemId, e.getMessage());
        }
        
        return -1;
    }

    /**
     * Get remaining buy limit for an item.
     *
     * @param itemId the item ID
     * @return remaining quantity that can be bought, or the full limit if not tracked
     */
    public int getRemainingBuyLimit(int itemId) {
        int maxLimit = getItemBuyLimit(itemId);
        if (maxLimit <= 0) {
            return Integer.MAX_VALUE; // Unknown limit, allow any quantity
        }
        return geState.getRemainingBuyLimit(itemId, maxLimit);
    }

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Rebuild the GE state snapshot from cached offers.
     */
    private void rebuildState() {
        // Clean up expired buy limits before building state
        cleanupExpiredBuyLimits();
        
        geState = GrandExchangeState.fromOffers(
                cachedOffers,
                new HashMap<>(buyLimits),
                currentTick,
                isMember
        );
    }

    /**
     * Remove expired buy limit entries.
     */
    private void cleanupExpiredBuyLimits() {
        buyLimits.entrySet().removeIf(entry -> entry.getValue().hasReset());
    }

    /**
     * Update membership status from current world type.
     */
    private void updateMembershipStatus() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        
        var worldTypes = client.getWorldType();
        isMember = worldTypes != null && worldTypes.contains(WorldType.MEMBERS);
        log.debug("Membership status: {}", isMember ? "MEMBERS" : "F2P");
    }

    /**
     * Refresh GE state from client.
     * Call this after login to get initial state.
     */
    public void refreshFromClient() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers != null) {
            for (int i = 0; i < Math.min(offers.length, GrandExchangeState.TOTAL_SLOTS); i++) {
                cachedOffers[i] = offers[i];
                // Initialize previous quantity tracking
                if (offers[i] != null) {
                    previousQuantitySold[i] = offers[i].getQuantitySold();
                }
            }
        }

        rebuildState();
        log.debug("GE state refreshed from client: {}", geState.getSummary());
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Initialize GE state for a specific account.
     * Called on login to load persisted buy limit data.
     *
     * @param accountName the account name
     */
    public void initializeForAccount(String accountName) {
        this.accountHash = hashAccountName(accountName);

        Path path = getGEStatePath();
        if (Files.exists(path)) {
            loadBuyLimits(path);
        } else {
            buyLimits.clear();
            log.info("No persisted GE state found for account hash: {}", accountHash);
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
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(accountName.toLowerCase().hashCode());
        }
    }

    /**
     * Get the path to the GE state file.
     */
    private Path getGEStatePath() {
        String homeDir = System.getProperty("user.home");
        return Paths.get(homeDir, GE_STATE_DIR, accountHash + ".json");
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    /**
     * Load buy limit data from disk.
     */
    private void loadBuyLimits(Path path) {
        try {
            String json = Files.readString(path);
            PersistedGEState persisted = gson.fromJson(json, PersistedGEState.class);

            if (persisted != null && persisted.isValid()) {
                buyLimits.clear();
                for (PersistedBuyLimit limit : persisted.buyLimits) {
                    GrandExchangeState.BuyLimitInfo info = GrandExchangeState.BuyLimitInfo.builder()
                            .itemId(limit.itemId)
                            .quantityBought(limit.quantityBought)
                            .windowStartTime(limit.windowStartTime)
                            .build();
                    
                    // Only load if not expired
                    if (!info.hasReset()) {
                        buyLimits.put(limit.itemId, info);
                    }
                }
                
                log.info("Loaded GE buy limits from {}: {} active limits", 
                        path, buyLimits.size());
            } else {
                log.warn("Invalid persisted GE state at {}, starting fresh", path);
                buyLimits.clear();
            }
        } catch (IOException | JsonSyntaxException e) {
            log.warn("Failed to load GE state from {}: {}", path, e.getMessage());
            buyLimits.clear();
        }
    }

    /**
     * Save buy limit data to disk.
     */
    public void save() {
        if (accountHash == null) {
            return;
        }

        Path path = getGEStatePath();
        try {
            Files.createDirectories(path.getParent());

            // Backup existing file
            if (Files.exists(path)) {
                Path backup = path.resolveSibling(accountHash + ".json.bak");
                Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            PersistedGEState persisted = new PersistedGEState();
            persisted.schemaVersion = CURRENT_SCHEMA_VERSION;
            persisted.lastSavedEpoch = Instant.now().getEpochSecond();
            persisted.buyLimits = new PersistedBuyLimit[buyLimits.size()];

            int i = 0;
            for (var entry : buyLimits.entrySet()) {
                PersistedBuyLimit limit = new PersistedBuyLimit();
                limit.itemId = entry.getValue().getItemId();
                limit.quantityBought = entry.getValue().getQuantityBought();
                limit.windowStartTime = entry.getValue().getWindowStartTime();
                persisted.buyLimits[i++] = limit;
            }

            String json = gson.toJson(persisted);
            Files.writeString(path, json);

            dirty = false;
            log.debug("Saved GE state to {}: {} buy limits", path, buyLimits.size());
        } catch (IOException e) {
            log.error("Failed to save GE state to {}: {}", path, e.getMessage());
        }
    }

    /**
     * Save only if there are unsaved changes.
     */
    private void saveIfDirty() {
        if (dirty && accountHash != null) {
            save();
        }
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
     * Check if GE state is known (has been observed at least once).
     *
     * @return true if GE has been observed
     */
    public boolean isGEKnown() {
        return !geState.isUnknown();
    }

    /**
     * Check if GE interface is currently open.
     * 
     * @return true if GE interface is visible
     */
    public boolean isGEInterfaceOpen() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return false;
        }
        
        var widget = client.getWidget(WIDGET_GE_GROUP, 0);
        return widget != null && !widget.isHidden();
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
        log.info("GrandExchangeStateManager shutdown complete");
    }

    // ========================================================================
    // Persistence Data Classes
    // ========================================================================

    /**
     * Data class for JSON persistence.
     */
    private static class PersistedGEState {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        long lastSavedEpoch;
        PersistedBuyLimit[] buyLimits;

        boolean isValid() {
            return buyLimits != null;
        }
    }

    /**
     * Individual buy limit for persistence.
     */
    private static class PersistedBuyLimit {
        int itemId;
        int quantityBought;
        Instant windowStartTime;
    }

    /**
     * Gson TypeAdapter for Instant serialization.
     */
    private static class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toEpochMilli());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.ofEpochMilli(in.nextLong());
        }
    }
}

