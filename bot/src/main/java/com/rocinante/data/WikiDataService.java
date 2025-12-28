package com.rocinante.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rocinante.data.model.DropTable;
import com.rocinante.data.model.ItemSource;
import com.rocinante.data.model.ShopInventory;
import com.rocinante.data.model.WeaponInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OSRS Wiki API client for fetching dynamic game data.
 *
 * <p>Per REQUIREMENTS.md Section 8A.2, implements:
 * <ul>
 *   <li>Proper User-Agent header (CRITICAL - 403 without this)</li>
 *   <li>Rate limiting: 30 req/min with priority queue</li>
 *   <li>Exponential backoff on 429 responses</li>
 *   <li>Circuit breaker pattern for resilience</li>
 * </ul>
 *
 * <p>Supported query types:
 * <ul>
 *   <li>Search (opensearch): Autocomplete item/monster names</li>
 *   <li>Page content (parse): Full page wikitext for parsing</li>
 *   <li>Categories (categorymembers): List items in a category</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Get drop table asynchronously
 * wikiDataService.getDropTable("Abyssal demon")
 *     .thenAccept(dropTable -> {
 *         for (DropTable.Drop drop : dropTable.drops()) {
 *             log.info("{} - {} ({})", drop.itemName(), drop.quantity(), drop.rarity());
 *         }
 *     });
 *
 * // Check if wiki is available
 * if (!wikiDataService.isAvailable()) {
 *     log.warn("Wiki unavailable, using cached data");
 * }
 * }</pre>
 */
@Slf4j
@Singleton
public class WikiDataService {

    // ========================================================================
    // Configuration Constants
    // ========================================================================

    /**
     * CRITICAL: Wiki returns HTTP 403 without proper User-Agent.
     * Format: ApplicationName/Version (contact info)
     */
    private static final String USER_AGENT = "RuneLite-Rocinante/1.0 (github.com/rocinante-bot)";

    /**
     * Wiki API base URL.
     */
    private static final String WIKI_API_BASE = "https://oldschool.runescape.wiki/api.php";

    /**
     * Wiki page base URL for reference links.
     */
    private static final String WIKI_PAGE_BASE = "https://oldschool.runescape.wiki/w/";

    /**
     * Rate limit: requests per minute (30 per spec).
     */
    private static final int RATE_LIMIT_PER_MINUTE = 30;

    /**
     * Minimum delay between requests in milliseconds.
     */
    private static final long MIN_REQUEST_DELAY_MS = 60_000 / RATE_LIMIT_PER_MINUTE; // 2000ms

    /**
     * Initial backoff delay for retries.
     */
    private static final long INITIAL_BACKOFF_MS = 1000;

    /**
     * Maximum backoff delay.
     */
    private static final long MAX_BACKOFF_MS = 8000;

    /**
     * Request timeout in seconds.
     */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    // ========================================================================
    // Circuit Breaker Constants
    // ========================================================================

    /**
     * Number of consecutive failures to open circuit.
     */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 5;

    /**
     * Time to wait before attempting recovery (HALF_OPEN state).
     */
    private static final Duration CIRCUIT_RECOVERY_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Maximum time to use stale cache before warning.
     */
    private static final Duration STALE_CACHE_WARNING_THRESHOLD = Duration.ofMinutes(5);

    // ========================================================================
    // Dependencies
    // ========================================================================

    private final OkHttpClient httpClient;
    private final WikiCacheManager cacheManager;
    private final WikiTemplateParser templateParser;
    private final Gson gson;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Circuit breaker state.
     */
    @Getter
    private volatile CircuitState circuitState = CircuitState.CLOSED;

    /**
     * Consecutive failure count for circuit breaker.
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * Time when circuit opened.
     */
    private volatile Instant circuitOpenedAt = null;

    /**
     * Time of last successful request.
     */
    private volatile Instant lastSuccessAt = Instant.now();

    /**
     * Time of last request (for rate limiting).
     */
    private final AtomicLong lastRequestTimeMs = new AtomicLong(0);

    /**
     * Total request count for statistics.
     */
    @Getter
    private final AtomicLong totalRequests = new AtomicLong(0);

    /**
     * Total failure count for statistics.
     */
    @Getter
    private final AtomicLong totalFailures = new AtomicLong(0);

    /**
     * Circuit open count for statistics.
     */
    @Getter
    private final AtomicInteger circuitOpenCount = new AtomicInteger(0);

    /**
     * Executor for async operations.
     */
    private final ExecutorService executor;

    /**
     * Priority queue for requests.
     */
    private final PriorityBlockingQueue<PrioritizedRequest> requestQueue;

    /**
     * Whether the service is shutting down.
     */
    private volatile boolean shuttingDown = false;

    // ========================================================================
    // Circuit Breaker State
    // ========================================================================

    /**
     * Circuit breaker states per REQUIREMENTS.md 8A.2.1.
     */
    public enum CircuitState {
        /**
         * Normal operation - requests proceed.
         */
        CLOSED,

        /**
         * Failing - skip requests, return cached data.
         */
        OPEN,

        /**
         * Testing recovery - allow single probe request.
         */
        HALF_OPEN
    }

    /**
     * Request priority levels.
     */
    public enum Priority {
        HIGH(0),    // Quest data
        MEDIUM(1),  // Drop tables
        LOW(2);     // General info

        final int value;

        Priority(int value) {
            this.value = value;
        }
    }

    /**
     * Wrapper for prioritized requests.
     */
    private record PrioritizedRequest(
            Priority priority,
            long timestamp,
            String url,
            CompletableFuture<String> future
    ) implements Comparable<PrioritizedRequest> {
        @Override
        public int compareTo(PrioritizedRequest other) {
            int priorityCompare = Integer.compare(this.priority.value, other.priority.value);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public WikiDataService(WikiCacheManager cacheManager, WikiTemplateParser templateParser) {
        this.cacheManager = cacheManager;
        this.templateParser = templateParser;
        this.gson = new Gson();

        // Configure OkHttpClient with proper headers
        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json")
                            .build();
                    return chain.proceed(request);
                })
                .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        // Request queue with priority ordering
        this.requestQueue = new PriorityBlockingQueue<>();

        // Single-threaded executor for sequential request processing
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WikiDataService-Worker");
            t.setDaemon(true);
            return t;
        });

        // Start request processor
        startRequestProcessor();

        log.info("WikiDataService initialized with User-Agent: {}", USER_AGENT);
    }

    /**
     * Start the background request processor.
     */
    private void startRequestProcessor() {
        executor.submit(() -> {
            while (!shuttingDown) {
                try {
                    PrioritizedRequest request = requestQueue.poll(1, TimeUnit.SECONDS);
                    if (request != null) {
                        processRequest(request);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error processing wiki request", e);
                }
            }
        });
    }

    /**
     * Process a single request with rate limiting.
     */
    private void processRequest(PrioritizedRequest request) {
        // Rate limiting
        long now = System.currentTimeMillis();
        long lastRequest = lastRequestTimeMs.get();
        long timeSinceLast = now - lastRequest;

        if (timeSinceLast < MIN_REQUEST_DELAY_MS) {
            try {
                Thread.sleep(MIN_REQUEST_DELAY_MS - timeSinceLast);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                request.future.completeExceptionally(e);
                return;
            }
        }

        lastRequestTimeMs.set(System.currentTimeMillis());
        totalRequests.incrementAndGet();

        // Check circuit breaker
        if (!canMakeRequest()) {
            log.debug("Circuit breaker preventing request: {}", circuitState);
            request.future.completeExceptionally(
                    new IOException("Circuit breaker is " + circuitState));
            return;
        }

        // Execute request
        try {
            String response = executeRequest(request.url);
            request.future.complete(response);
            onSuccess();
        } catch (Exception e) {
            request.future.completeExceptionally(e);
            onFailure(e);
        }
    }

    /**
     * Execute HTTP request with retry logic.
     */
    private String executeRequest(String url) throws IOException {
        int attempt = 0;
        long backoff = INITIAL_BACKOFF_MS;
        IOException lastException = null;

        while (attempt < 4) { // Max 4 attempts (initial + 3 retries)
            attempt++;

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    ResponseBody body = response.body();
                    return body != null ? body.string() : "";
                }

                if (response.code() == 429) {
                    // Rate limited - exponential backoff
                    log.warn("Wiki API rate limited (429), backing off {}ms", backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during backoff", e);
                    }
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                    continue;
                }

                if (response.code() == 403) {
                    throw new IOException("Wiki API returned 403 Forbidden - check User-Agent header");
                }

                throw new IOException("Wiki API error: " + response.code() + " " + response.message());
            } catch (IOException e) {
                lastException = e;
                if (attempt < 4) {
                    log.debug("Request failed (attempt {}), retrying: {}", attempt, e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                }
            }
        }

        throw lastException != null ? lastException : new IOException("Request failed after retries");
    }

    // ========================================================================
    // Circuit Breaker Logic
    // ========================================================================

    /**
     * Check if a request can be made based on circuit breaker state.
     */
    private boolean canMakeRequest() {
        switch (circuitState) {
            case CLOSED:
                return true;

            case OPEN:
                // Check if recovery timeout has passed
                if (circuitOpenedAt != null &&
                        Duration.between(circuitOpenedAt, Instant.now()).compareTo(CIRCUIT_RECOVERY_TIMEOUT) > 0) {
                    circuitState = CircuitState.HALF_OPEN;
                    log.info("Circuit breaker entering HALF_OPEN state for recovery probe");
                    return true;
                }
                return false;

            case HALF_OPEN:
                // Allow single probe request
                return true;

            default:
                return false;
        }
    }

    /**
     * Record successful request.
     */
    private void onSuccess() {
        consecutiveFailures.set(0);
        lastSuccessAt = Instant.now();

        if (circuitState == CircuitState.HALF_OPEN) {
            circuitState = CircuitState.CLOSED;
            log.info("Circuit breaker recovered, returning to CLOSED state");
        }
    }

    /**
     * Record failed request.
     */
    private void onFailure(Exception e) {
        totalFailures.incrementAndGet();
        int failures = consecutiveFailures.incrementAndGet();

        log.debug("Wiki request failed ({} consecutive): {}", failures, e.getMessage());

        if (circuitState == CircuitState.HALF_OPEN) {
            // Recovery failed - return to OPEN
            circuitState = CircuitState.OPEN;
            circuitOpenedAt = Instant.now();
            log.warn("Circuit breaker recovery failed, returning to OPEN state");
        } else if (failures >= CIRCUIT_FAILURE_THRESHOLD && circuitState == CircuitState.CLOSED) {
            // Open circuit
            circuitState = CircuitState.OPEN;
            circuitOpenedAt = Instant.now();
            circuitOpenCount.incrementAndGet();
            log.warn("Circuit breaker opened after {} consecutive failures", failures);
        }
    }

    /**
     * Check if the wiki service is available.
     *
     * @return true if circuit breaker is closed or half-open
     */
    public boolean isAvailable() {
        return circuitState != CircuitState.OPEN;
    }

    // ========================================================================
    // High-Level API Methods
    // ========================================================================

    /**
     * Get drop table for a monster.
     *
     * @param monsterName the monster name
     * @return future containing the drop table
     */
    public CompletableFuture<DropTable> getDropTable(String monsterName) {
        return getDropTable(monsterName, Priority.MEDIUM);
    }

    /**
     * Get drop table for a monster with priority.
     *
     * @param monsterName the monster name
     * @param priority    request priority
     * @return future containing the drop table
     */
    public CompletableFuture<DropTable> getDropTable(String monsterName, Priority priority) {
        String cacheKey = WikiCacheManager.dropTableKey(monsterName);
        String pageName = toPageName(monsterName);
        String wikiUrl = WIKI_PAGE_BASE + pageName;

        // Check cache first
        Optional<String> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            log.trace("Drop table cache hit for {}", monsterName);
            return CompletableFuture.completedFuture(
                    templateParser.parseDropTable(cached.get(), monsterName, wikiUrl));
        }

        // Fetch from API
        return fetchPageContent(pageName, priority)
                .thenApply(wikitext -> {
                    cacheManager.put(cacheKey, wikitext, "drop_table");
                    return templateParser.parseDropTable(wikitext, monsterName, wikiUrl);
                })
                .exceptionally(e -> {
                    log.warn("Failed to fetch drop table for {}: {}", monsterName, e.getMessage());
                    // Try stale cache
                    Optional<String> stale = cacheManager.getIncludingStale(cacheKey);
                    if (stale.isPresent()) {
                        log.info("Using stale cache for {}", monsterName);
                        return templateParser.parseDropTable(stale.get(), monsterName, wikiUrl);
                    }
                    return DropTable.EMPTY;
                });
    }

    /**
     * Get item sources for an item.
     *
     * @param itemName the item name
     * @return future containing the item sources
     */
    public CompletableFuture<ItemSource> getItemSources(String itemName) {
        return getItemSources(itemName, Priority.MEDIUM);
    }

    /**
     * Get item sources for an item with priority.
     *
     * @param itemName the item name
     * @param priority request priority
     * @return future containing the item sources
     */
    public CompletableFuture<ItemSource> getItemSources(String itemName, Priority priority) {
        String cacheKey = WikiCacheManager.itemSourceKey(itemName);
        String pageName = toPageName(itemName);
        String wikiUrl = WIKI_PAGE_BASE + pageName;

        // Check cache first
        Optional<String> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            log.trace("Item source cache hit for {}", itemName);
            return CompletableFuture.completedFuture(
                    templateParser.parseItemSources(cached.get(), itemName, wikiUrl));
        }

        // Fetch from API
        return fetchPageContent(pageName, priority)
                .thenApply(wikitext -> {
                    cacheManager.put(cacheKey, wikitext, "item_source");
                    return templateParser.parseItemSources(wikitext, itemName, wikiUrl);
                })
                .exceptionally(e -> {
                    log.warn("Failed to fetch item sources for {}: {}", itemName, e.getMessage());
                    Optional<String> stale = cacheManager.getIncludingStale(cacheKey);
                    if (stale.isPresent()) {
                        log.info("Using stale cache for {}", itemName);
                        return templateParser.parseItemSources(stale.get(), itemName, wikiUrl);
                    }
                    return ItemSource.EMPTY;
                });
    }

    /**
     * Get shop inventory for a shop.
     *
     * @param shopName the shop name
     * @return future containing the shop inventory
     */
    public CompletableFuture<ShopInventory> getShopInventory(String shopName) {
        return getShopInventory(shopName, Priority.MEDIUM);
    }

    /**
     * Get shop inventory for a shop with priority.
     *
     * @param shopName the shop name
     * @param priority request priority
     * @return future containing the shop inventory
     */
    public CompletableFuture<ShopInventory> getShopInventory(String shopName, Priority priority) {
        String cacheKey = WikiCacheManager.shopInventoryKey(shopName);
        String pageName = toPageName(shopName);
        String wikiUrl = WIKI_PAGE_BASE + pageName;

        // Check cache first
        Optional<String> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            log.trace("Shop inventory cache hit for {}", shopName);
            return CompletableFuture.completedFuture(
                    templateParser.parseShopInventory(cached.get(), shopName, wikiUrl));
        }

        // Fetch from API
        return fetchPageContent(pageName, priority)
                .thenApply(wikitext -> {
                    cacheManager.put(cacheKey, wikitext, "shop_inventory");
                    return templateParser.parseShopInventory(wikitext, shopName, wikiUrl);
                })
                .exceptionally(e -> {
                    log.warn("Failed to fetch shop inventory for {}: {}", shopName, e.getMessage());
                    Optional<String> stale = cacheManager.getIncludingStale(cacheKey);
                    if (stale.isPresent()) {
                        log.info("Using stale cache for {}", shopName);
                        return templateParser.parseShopInventory(stale.get(), shopName, wikiUrl);
                    }
                    return ShopInventory.EMPTY;
                });
    }

    /**
     * Get weapon information.
     *
     * @param itemId   the item ID
     * @param itemName the item name
     * @return future containing the weapon info
     */
    public CompletableFuture<WeaponInfo> getWeaponInfo(int itemId, String itemName) {
        return getWeaponInfo(itemId, itemName, Priority.MEDIUM);
    }

    /**
     * Get weapon information with priority.
     *
     * @param itemId   the item ID
     * @param itemName the item name
     * @param priority request priority
     * @return future containing the weapon info
     */
    public CompletableFuture<WeaponInfo> getWeaponInfo(int itemId, String itemName, Priority priority) {
        String cacheKey = WikiCacheManager.weaponInfoKey(itemName);
        String pageName = toPageName(itemName);
        String wikiUrl = WIKI_PAGE_BASE + pageName;

        // Check cache first
        Optional<String> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            log.trace("Weapon info cache hit for {}", itemName);
            return CompletableFuture.completedFuture(
                    templateParser.parseWeaponInfo(cached.get(), itemName, itemId, wikiUrl));
        }

        // Fetch from API
        return fetchPageContent(pageName, priority)
                .thenApply(wikitext -> {
                    cacheManager.put(cacheKey, wikitext, "weapon_info");
                    return templateParser.parseWeaponInfo(wikitext, itemName, itemId, wikiUrl);
                })
                .exceptionally(e -> {
                    log.warn("Failed to fetch weapon info for {}: {}", itemName, e.getMessage());
                    Optional<String> stale = cacheManager.getIncludingStale(cacheKey);
                    if (stale.isPresent()) {
                        log.info("Using stale cache for {}", itemName);
                        return templateParser.parseWeaponInfo(stale.get(), itemName, itemId, wikiUrl);
                    }
                    return WeaponInfo.EMPTY;
                });
    }

    /**
     * Get weapon information synchronously (blocking).
     * Falls back to cached/default data if unavailable.
     *
     * @param itemId   the item ID
     * @param itemName the item name
     * @return weapon info (never null)
     */
    public WeaponInfo getWeaponInfoSync(int itemId, String itemName) {
        try {
            return getWeaponInfo(itemId, itemName, Priority.HIGH)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Sync weapon info fetch failed for {}: {}", itemName, e.getMessage());
            return WeaponInfo.EMPTY;
        }
    }

    /**
     * Search for items/pages matching a query.
     *
     * @param query the search query
     * @return future containing list of matching page names
     */
    public CompletableFuture<List<String>> search(String query) {
        return search(query, 10, Priority.LOW);
    }

    /**
     * Search for items/pages matching a query with limit and priority.
     *
     * @param query    the search query
     * @param limit    max results
     * @param priority request priority
     * @return future containing list of matching page names
     */
    public CompletableFuture<List<String>> search(String query, int limit, Priority priority) {
        String url = buildUrl("opensearch", query, null)
                + "&limit=" + limit;

        return queueRequest(url, priority)
                .thenApply(this::parseSearchResults)
                .exceptionally(e -> {
                    log.warn("Search failed for '{}': {}", query, e.getMessage());
                    return List.of();
                });
    }

    // ========================================================================
    // Low-Level API Methods
    // ========================================================================

    /**
     * Fetch raw page content (wikitext).
     *
     * @param pageName the wiki page name
     * @param priority request priority
     * @return future containing the wikitext
     */
    public CompletableFuture<String> fetchPageContent(String pageName, Priority priority) {
        String cacheKey = WikiCacheManager.pageContentKey(pageName);

        // Check cache
        Optional<String> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached.get());
        }

        String url = buildUrl("parse", pageName, "wikitext");
        return queueRequest(url, priority)
                .thenApply(response -> {
                    String wikitext = parseWikitextFromResponse(response);
                    cacheManager.put(cacheKey, wikitext, "page_content");
                    return wikitext;
                });
    }

    /**
     * Queue a request for processing.
     */
    private CompletableFuture<String> queueRequest(String url, Priority priority) {
        CompletableFuture<String> future = new CompletableFuture<>();
        PrioritizedRequest request = new PrioritizedRequest(
                priority, System.nanoTime(), url, future);
        requestQueue.offer(request);
        return future;
    }

    // ========================================================================
    // URL Building
    // ========================================================================

    /**
     * Build a wiki API URL.
     *
     * @param action   API action (opensearch, parse, query)
     * @param target   search term or page name
     * @param prop     property to fetch (for parse action)
     * @return the URL
     */
    private String buildUrl(String action, String target, @Nullable String prop) {
        StringBuilder url = new StringBuilder(WIKI_API_BASE);
        url.append("?format=json");
        url.append("&action=").append(action);

        switch (action) {
            case "opensearch":
                url.append("&search=").append(urlEncode(target));
                url.append("&redirects=resolve");
                break;
            case "parse":
                url.append("&page=").append(urlEncode(target));
                if (prop != null) {
                    url.append("&prop=").append(prop);
                }
                break;
            case "query":
                url.append("&titles=").append(urlEncode(target));
                if (prop != null) {
                    url.append("&prop=").append(prop);
                }
                break;
        }

        // Add UTM source per wiki guidelines
        url.append("&utm_source=runelite");

        return url.toString();
    }

    /**
     * URL encode a string.
     */
    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value.replace(" ", "_");
        }
    }

    // ========================================================================
    // Response Parsing
    // ========================================================================

    /**
     * Parse wikitext from API response.
     */
    private String parseWikitextFromResponse(String response) {
        try {
            // Use older Gson API for compatibility with RuneLite's bundled Gson version
            JsonObject json = new JsonParser().parse(response).getAsJsonObject();
            if (json.has("parse")) {
                JsonObject parse = json.getAsJsonObject("parse");
                if (parse.has("wikitext")) {
                    JsonObject wikitext = parse.getAsJsonObject("wikitext");
                    if (wikitext.has("*")) {
                        return wikitext.get("*").getAsString();
                    }
                }
            }
            // Alternative structure
            if (json.has("wikitext")) {
                return json.get("wikitext").getAsString();
            }
        } catch (Exception e) {
            log.warn("Failed to parse wikitext from response: {}", e.getMessage());
        }
        return "";
    }

    /**
     * Parse search results from API response.
     */
    private List<String> parseSearchResults(String response) {
        List<String> results = new ArrayList<>();
        try {
            // Use older Gson API for compatibility with RuneLite's bundled Gson version
            JsonArray json = new JsonParser().parse(response).getAsJsonArray();
            if (json.size() > 1) {
                JsonArray names = json.get(1).getAsJsonArray();
                for (JsonElement elem : names) {
                    results.add(elem.getAsString());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse search results: {}", e.getMessage());
        }
        return results;
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Convert a name to wiki page format.
     */
    private String toPageName(String name) {
        if (name == null) {
            return "";
        }
        // Replace spaces with underscores, capitalize first letter
        String pageName = name.trim().replace(' ', '_');
        if (!pageName.isEmpty()) {
            pageName = Character.toUpperCase(pageName.charAt(0)) + pageName.substring(1);
        }
        return pageName;
    }

    /**
     * Refresh stale cache entries in the background.
     * Called on startup if network is available.
     */
    public void refreshStaleEntries() {
        if (!isAvailable()) {
            log.debug("Wiki not available, skipping stale entry refresh");
            return;
        }

        cacheManager.getStaleKeys().forEach(key -> {
            // Extract page name from cache key
            String[] parts = key.split(":", 2);
            if (parts.length == 2) {
                String pageName = parts[1].replace('_', ' ');
                log.debug("Refreshing stale cache entry: {}", pageName);
                fetchPageContent(pageName, Priority.LOW)
                        .thenAccept(content -> log.trace("Refreshed: {}", pageName))
                        .exceptionally(e -> {
                            log.debug("Failed to refresh {}: {}", pageName, e.getMessage());
                            return null;
                        });
            }
        });
    }

    /**
     * Get service statistics.
     */
    public ServiceStats getStats() {
        return new ServiceStats(
                totalRequests.get(),
                totalFailures.get(),
                circuitOpenCount.get(),
                circuitState,
                lastSuccessAt,
                cacheManager.getStats()
        );
    }

    /**
     * Service statistics record.
     */
    public record ServiceStats(
            long totalRequests,
            long totalFailures,
            int circuitOpenCount,
            CircuitState circuitState,
            Instant lastSuccessAt,
            WikiCacheManager.WikiCacheStats cacheStats
    ) {
        public double successRate() {
            if (totalRequests == 0) return 1.0;
            return (double) (totalRequests - totalFailures) / totalRequests;
        }
    }

    /**
     * Shutdown the service.
     */
    public void shutdown() {
        shuttingDown = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("WikiDataService shut down");
    }
}

