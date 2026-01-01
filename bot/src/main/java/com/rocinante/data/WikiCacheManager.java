package com.rocinante.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.rocinante.util.CacheSigningUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Two-tier cache manager for OSRS Wiki API responses.
 *
 * <p>Per REQUIREMENTS.md Section 8A.2.4, implements:
 * <ul>
 *   <li>Memory cache: Guava Cache with 1000 entry max, 24-hour TTL</li>
 *   <li>File cache: Persistent JSON files in ~/.runelite/rocinante/wiki-cache/</li>
 * </ul>
 *
 * <p>Lookup order: memory → file → (caller fetches from API) → update both caches.
 *
 * <p>Cache keys are hashed to create safe filenames. Each cache entry includes
 * metadata (timestamp, original key) for TTL validation.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Check cache first
 * Optional<String> cached = cacheManager.get("drop_table:Abyssal_demon");
 * if (cached.isPresent()) {
 *     return parseDropTable(cached.get());
 * }
 *
 * // Fetch from API and cache
 * String response = fetchFromWikiApi(pageName);
 * cacheManager.put("drop_table:Abyssal_demon", response);
 * }</pre>
 */
@Slf4j
@Singleton
public class WikiCacheManager {

    /**
     * Cache entry TTL in hours.
     */
    private static final int CACHE_TTL_HOURS = 24;

    /**
     * Maximum entries in memory cache.
     */
    private static final int MAX_MEMORY_ENTRIES = 1000;

    /**
     * File extension for cache files.
     */
    private static final String CACHE_FILE_EXTENSION = ".json";

    /**
     * Gson instance for JSON serialization.
     */
    private final Gson gson;

    /**
     * In-memory cache (first tier).
     */
    private final Cache<String, CacheEntry> memoryCache;

    /**
     * Base directory for file cache.
     */
    private final Path cacheDirectory;

    /**
     * Whether the cache manager is initialized.
     */
    @Getter
    private volatile boolean initialized = false;

    /**
     * Statistics tracking.
     */
    @Getter
    private volatile long fileHits = 0;
    @Getter
    private volatile long fileMisses = 0;

    /**
     * Expose cache directory for testing/monitoring.
     */
    Path getCacheDirectory() {
        return cacheDirectory;
    }

    /**
     * Cache entry wrapper with metadata.
     */
    public record CacheEntry(
            String key,
            String content,
            Instant timestamp,
            String contentType
    ) {
        /**
         * Check if this entry has expired based on TTL.
         *
         * @param ttlHours TTL in hours
         * @return true if expired
         */
        public boolean isExpired(int ttlHours) {
            return timestamp.plus(Duration.ofHours(ttlHours)).isBefore(Instant.now());
        }

        /**
         * Check if this entry is stale (past TTL but potentially usable).
         *
         * @return true if stale
         */
        public boolean isStale() {
            return isExpired(CACHE_TTL_HOURS);
        }
    }

    /**
     * File cache entry format for JSON serialization.
     * Note: Must be a class (not record) because GSON 2.8.5 can't deserialize records.
     */
    private static class FileCacheEntry {
        String key;
        String content;
        String timestamp;
        String contentType;
        String signature;  // HMAC-SHA256 signature of key+content+timestamp+contentType

        // Default constructor for GSON
        FileCacheEntry() {}

        FileCacheEntry(String key, String content, String timestamp, String contentType, String signature) {
            this.key = key;
            this.content = content;
            this.timestamp = timestamp;
            this.contentType = contentType;
            this.signature = signature;
        }

        static FileCacheEntry from(CacheEntry entry, String signature) {
            return new FileCacheEntry(
                    entry.key(),
                    entry.content(),
                    entry.timestamp().toString(),
                    entry.contentType(),
                    signature
            );
        }

        CacheEntry toCacheEntry() {
            return new CacheEntry(
                    key,
                    content,
                    Instant.parse(timestamp),
                    contentType
            );
        }

        /**
         * Get the data to be signed (key + content + timestamp + contentType).
         */
        String getSignableData() {
            return key + "\n" + content + "\n" + timestamp + "\n" + contentType;
        }
    }

    @Inject
    public WikiCacheManager() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        this.memoryCache = CacheBuilder.newBuilder()
                .maximumSize(MAX_MEMORY_ENTRIES)
                .expireAfterWrite(CACHE_TTL_HOURS, TimeUnit.HOURS)
                .recordStats()
                .build();

        this.cacheDirectory = Paths.get(System.getProperty("user.home"), resolveCacheDir());

        initialize();
    }

    private static String resolveCacheDir() {
        String prop = System.getProperty("WIKI_CACHE_DIR");
        if (prop != null && !prop.isEmpty()) {
            return prop;
        }
        String env = System.getenv("WIKI_CACHE_DIR");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return "/home/runelite/.local/share/bolt-launcher/.runelite/rocinante/wiki-cache";
    }

    /**
     * Initialize the cache manager.
     * Creates cache directory if needed.
     */
    private void initialize() {
        try {
            Files.createDirectories(cacheDirectory);
            initialized = true;
            log.info("WikiCacheManager initialized at: {}", cacheDirectory);
        } catch (IOException e) {
            log.error("Failed to create wiki cache directory: {}", cacheDirectory, e);
            initialized = false;
        }
    }

    // ========================================================================
    // Cache Operations
    // ========================================================================

    /**
     * Get a cached value by key.
     *
     * <p>Lookup order: memory cache → file cache.
     * Returns empty if not found or expired.
     *
     * @param cacheKey the cache key
     * @return the cached content, or empty if not found/expired
     */
    public Optional<String> get(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return Optional.empty();
        }

        // Check memory cache first
        CacheEntry memoryEntry = memoryCache.getIfPresent(cacheKey);
        if (memoryEntry != null && !memoryEntry.isStale()) {
            log.debug("Memory cache hit for key: {}", cacheKey);
            return Optional.of(memoryEntry.content());
        }

        // Check file cache
        Optional<CacheEntry> fileEntry = getFromFileCache(cacheKey);
        if (fileEntry.isPresent()) {
            CacheEntry entry = fileEntry.get();
            if (!entry.isStale()) {
                // Populate memory cache from file
                memoryCache.put(cacheKey, entry);
                fileHits++;
                log.debug("File cache hit for key: {}", cacheKey);
                return Optional.of(entry.content());
            } else {
                log.debug("File cache entry is stale for key: {}", cacheKey);
            }
        }

        fileMisses++;
        return Optional.empty();
    }

    /**
     * Get a cached value, including stale entries if valid ones aren't available.
     *
     * <p>Used by circuit breaker when wiki is unavailable.
     *
     * @param cacheKey the cache key
     * @return the cached content (possibly stale), or empty if not found
     */
    public Optional<String> getIncludingStale(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return Optional.empty();
        }

        // Check memory cache first
        CacheEntry memoryEntry = memoryCache.getIfPresent(cacheKey);
        if (memoryEntry != null) {
            if (memoryEntry.isStale()) {
                log.debug("Returning stale memory cache entry for key: {}", cacheKey);
            }
            return Optional.of(memoryEntry.content());
        }

        // Check file cache
        Optional<CacheEntry> fileEntry = getFromFileCache(cacheKey);
        if (fileEntry.isPresent()) {
            CacheEntry entry = fileEntry.get();
            if (entry.isStale()) {
                log.debug("Returning stale file cache entry for key: {}", cacheKey);
            }
            // Populate memory cache
            memoryCache.put(cacheKey, entry);
            return Optional.of(entry.content());
        }

        return Optional.empty();
    }

    /**
     * Store a value in both caches.
     *
     * @param cacheKey    the cache key
     * @param content     the content to cache
     * @param contentType optional content type descriptor (e.g., "drop_table", "item_source")
     */
    public void put(String cacheKey, String content, String contentType) {
        if (cacheKey == null || cacheKey.isEmpty() || content == null) {
            return;
        }

        CacheEntry entry = new CacheEntry(cacheKey, content, Instant.now(), contentType);

        // Update memory cache
        memoryCache.put(cacheKey, entry);

        // Update file cache
        putToFileCache(cacheKey, entry);

        log.debug("Cached content for key: {} (type: {})", cacheKey, contentType);
    }

    /**
     * Store a value in both caches with default content type.
     *
     * @param cacheKey the cache key
     * @param content  the content to cache
     */
    public void put(String cacheKey, String content) {
        put(cacheKey, content, "wiki_page");
    }

    /**
     * Invalidate a cache entry from both caches.
     *
     * @param cacheKey the cache key to invalidate
     */
    public void invalidate(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return;
        }

        // Remove from memory cache
        memoryCache.invalidate(cacheKey);

        // Remove from file cache
        Path filePath = getCacheFilePath(cacheKey);
        try {
            Files.deleteIfExists(filePath);
            log.debug("Invalidated cache entry for key: {}", cacheKey);
        } catch (IOException e) {
            log.warn("Failed to delete cache file: {}", filePath, e);
        }
    }

    /**
     * Invalidate all cache entries.
     */
    public void invalidateAll() {
        memoryCache.invalidateAll();

        try (Stream<Path> files = Files.list(cacheDirectory)) {
            files.filter(p -> p.toString().endsWith(CACHE_FILE_EXTENSION))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete cache file: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list cache directory: {}", cacheDirectory, e);
        }

        log.info("Invalidated all wiki cache entries");
    }

    // ========================================================================
    // Stale Entry Refresh
    // ========================================================================

    /**
     * Get all stale cache keys that need refreshing.
     *
     * <p>Called on startup to identify entries that should be refreshed
     * if network is available.
     *
     * @return stream of stale cache keys
     */
    public Stream<String> getStaleKeys() {
        if (!initialized) {
            return Stream.empty();
        }

        try (Stream<Path> files = Files.list(cacheDirectory)) {
            return files
                    .filter(p -> p.toString().endsWith(CACHE_FILE_EXTENSION))
                    .map(this::readCacheFile)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(CacheEntry::isStale)
                    .map(CacheEntry::key)
                    .toList()  // Collect to list to avoid stream closing issues
                    .stream();
        } catch (IOException e) {
            log.warn("Failed to list cache directory for stale entries: {}", e.getMessage());
            return Stream.empty();
        }
    }

    /**
     * Count the number of stale entries in the file cache.
     *
     * @return count of stale entries
     */
    public long countStaleEntries() {
        return getStaleKeys().count();
    }

    /**
     * Check if a specific key has a stale entry.
     *
     * @param cacheKey the cache key
     * @return true if entry exists and is stale
     */
    public boolean isStale(String cacheKey) {
        Optional<CacheEntry> entry = getFromFileCache(cacheKey);
        return entry.map(CacheEntry::isStale).orElse(false);
    }

    // ========================================================================
    // File Cache Operations
    // ========================================================================

    /**
     * Get an entry from the file cache.
     *
     * @param cacheKey the cache key
     * @return the cache entry, or empty if not found
     */
    private Optional<CacheEntry> getFromFileCache(String cacheKey) {
        if (!initialized) {
            return Optional.empty();
        }

        Path filePath = getCacheFilePath(cacheKey);
        return readCacheFile(filePath);
    }

    /**
     * Read a cache entry from a file.
     *
     * @param filePath the file path
     * @return the cache entry, or empty if not found/invalid/tampered
     */
    private Optional<CacheEntry> readCacheFile(Path filePath) {
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }

        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            FileCacheEntry fileEntry = gson.fromJson(json, FileCacheEntry.class);
            if (fileEntry == null || fileEntry.content == null) {
                log.warn("Invalid cache file (null content): {}", filePath);
                return Optional.empty();
            }

            // Verify signature
            if (!CacheSigningUtil.verify(fileEntry.getSignableData(), fileEntry.signature)) {
                log.warn("Cache file rejected (invalid signature): {}", filePath);
                // Delete tampered/unsigned file
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException deleteEx) {
                    log.debug("Failed to delete tampered cache file {}: {}", filePath, deleteEx.getMessage());
                }
                return Optional.empty();
            }

            return Optional.of(fileEntry.toCacheEntry());
        } catch (IOException e) {
            log.warn("Failed to read cache file: {}", filePath, e);
            return Optional.empty();
        } catch (JsonSyntaxException e) {
            log.warn("Invalid JSON in cache file: {}", filePath, e);
            // Delete corrupted file
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException deleteEx) {
                log.debug("Failed to delete corrupted cache file {}: {}", filePath, deleteEx.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * Write a cache entry to the file cache.
     *
     * @param cacheKey the cache key
     * @param entry    the cache entry to write
     */
    private void putToFileCache(String cacheKey, CacheEntry entry) {
        if (!initialized) {
            return;
        }

        Path filePath = getCacheFilePath(cacheKey);

        try {
            // Create file entry with signature
            String signature = CacheSigningUtil.sign(entry.key() + "\n" + entry.content() + "\n" + entry.timestamp() + "\n" + entry.contentType());
            FileCacheEntry fileEntry = FileCacheEntry.from(entry, signature);

            String json = gson.toJson(fileEntry);
            Files.writeString(filePath, json, StandardCharsets.UTF_8);
            log.debug("Wrote signed cache file: {}", filePath);
        } catch (IOException e) {
            log.warn("Failed to write cache file: {}", filePath, e);
        }
    }

    /**
     * Get the file path for a cache key.
     *
     * @param cacheKey the cache key
     * @return the file path
     */
    private Path getCacheFilePath(String cacheKey) {
        String hash = hashKey(cacheKey);
        return cacheDirectory.resolve(hash + CACHE_FILE_EXTENSION);
    }

    /**
     * Hash a cache key to create a safe filename.
     *
     * @param key the cache key
     * @return the hashed key (hex string)
     */
    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            // Use first 16 bytes (32 hex chars) for reasonable filename length
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    /**
     * Get combined cache statistics.
     *
     * @return cache statistics
     */
    public WikiCacheStats getStats() {
        CacheStats guavaStats = memoryCache.stats();
        return new WikiCacheStats(
                guavaStats.hitCount(),
                guavaStats.missCount(),
                fileHits,
                fileMisses,
                memoryCache.size(),
                countFileCacheEntries()
        );
    }

    /**
     * Count entries in the file cache.
     *
     * @return number of cache files
     */
    private long countFileCacheEntries() {
        if (!initialized) {
            return 0;
        }

        try (Stream<Path> files = Files.list(cacheDirectory)) {
            return files.filter(p -> p.toString().endsWith(CACHE_FILE_EXTENSION)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Combined cache statistics record.
     */
    public record WikiCacheStats(
            long memoryHits,
            long memoryMisses,
            long fileHits,
            long fileMisses,
            long memorySize,
            long fileSize
    ) {
        public double memoryHitRate() {
            long total = memoryHits + memoryMisses;
            return total > 0 ? (double) memoryHits / total : 0.0;
        }

        public double fileHitRate() {
            long total = fileHits + fileMisses;
            return total > 0 ? (double) fileHits / total : 0.0;
        }

        public double overallHitRate() {
            long totalHits = memoryHits + fileHits;
            long totalMisses = memoryMisses + fileMisses;
            long total = totalHits + totalMisses;
            return total > 0 ? (double) totalHits / total : 0.0;
        }
    }

    // ========================================================================
    // Cache Key Generation Helpers
    // ========================================================================

    /**
     * Generate a cache key for a drop table query.
     *
     * @param monsterName the monster name
     * @return the cache key
     */
    public static String dropTableKey(String monsterName) {
        return "drop_table:" + normalizePageName(monsterName);
    }

    /**
     * Generate a cache key for an item source query.
     *
     * @param itemName the item name
     * @return the cache key
     */
    public static String itemSourceKey(String itemName) {
        return "item_source:" + normalizePageName(itemName);
    }

    /**
     * Generate a cache key for a shop inventory query.
     *
     * @param shopName the shop name
     * @return the cache key
     */
    public static String shopInventoryKey(String shopName) {
        return "shop:" + normalizePageName(shopName);
    }

    /**
     * Generate a cache key for weapon info query.
     *
     * @param itemName the item name
     * @return the cache key
     */
    public static String weaponInfoKey(String itemName) {
        return "weapon:" + normalizePageName(itemName);
    }

    /**
     * Generate a cache key for raw page content.
     *
     * @param pageName the wiki page name
     * @return the cache key
     */
    public static String pageContentKey(String pageName) {
        return "page:" + normalizePageName(pageName);
    }

    /**
     * Normalize a page name for use in cache keys.
     * Replaces spaces with underscores and lowercases.
     *
     * @param pageName the page name
     * @return normalized name
     */
    public static String normalizePageName(String pageName) {
        if (pageName == null) {
            return "";
        }
        return pageName.trim()
                .replace(' ', '_')
                .toLowerCase();
    }
}

