package com.rocinante.navigation;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

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
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Two-tier cache manager for training spot rankings.
 *
 * <p>Caches ranked training object positions per location, enabling "smart training"
 * where objects with optimal roundtrip efficiency to banks are preferred.
 *
 * <p>Implements the same pattern as WikiCacheManager:
 * <ul>
 *   <li>Memory cache: Guava Cache with 500 entry max, 7-day TTL</li>
 *   <li>File cache: Persistent JSON files in ~/.runelite/rocinante/training-spot-cache/</li>
 * </ul>
 *
 * <p>Cache is shared across all bot instances via Docker volume mount.
 *
 * <p>Cache key format: {@code training:{regionId}:{objectIdHash}:{banking}}
 *
 * <p>Example usage:
 * <pre>{@code
 * // Check cache first
 * Optional<List<RankedCandidate>> cached = cache.get(cacheKey);
 * if (cached.isPresent()) {
 *     return cached.get();
 * }
 *
 * // Compute rankings and cache
 * List<RankedCandidate> rankings = computeRankings(...);
 * cache.put(cacheKey, rankings);
 * }</pre>
 */
@Slf4j
@Singleton
public class TrainingSpotCache {

    /**
     * Cache entry TTL in days.
     */
    private static final int CACHE_TTL_DAYS = 7;

    /**
     * Maximum entries in memory cache.
     */
    private static final int MAX_MEMORY_ENTRIES = 500;

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
            List<CachedCandidate> candidates,
            WorldPoint bankPosition,
            Instant timestamp
    ) {
        /**
         * Check if this entry has expired based on TTL.
         *
         * @param ttlDays TTL in days
         * @return true if expired
         */
        public boolean isExpired(int ttlDays) {
            return timestamp.plus(Duration.ofDays(ttlDays)).isBefore(Instant.now());
        }

        /**
         * Check if this entry is stale (past TTL but potentially usable).
         *
         * @return true if stale
         */
        public boolean isStale() {
            return isExpired(CACHE_TTL_DAYS);
        }
    }

    /**
     * Cached candidate position with cost data.
     * Serializable version of RankedCandidate for file storage.
     */
    public record CachedCandidate(
            int x,
            int y,
            int plane,
            int objectId,
            int cost,
            int bankDistance
    ) {
        public WorldPoint toWorldPoint() {
            return new WorldPoint(x, y, plane);
        }

        public static CachedCandidate from(RankedCandidate candidate) {
            return new CachedCandidate(
                    candidate.position().getX(),
                    candidate.position().getY(),
                    candidate.position().getPlane(),
                    candidate.objectId(),
                    candidate.cost(),
                    candidate.bankDistance()
            );
        }

        public RankedCandidate toRankedCandidate() {
            return new RankedCandidate(toWorldPoint(), objectId, cost, bankDistance);
        }
    }

    /**
     * File cache entry format for JSON serialization.
     * Note: Must be a class (not record) because GSON 2.8.5 can't deserialize records.
     */
    private static class FileCacheEntry {
        String key;
        List<FileCachedCandidate> candidates;
        int bankX;
        int bankY;
        int bankPlane;
        String timestamp;

        // Default constructor for GSON
        FileCacheEntry() {}

        FileCacheEntry(String key, List<FileCachedCandidate> candidates, 
                       int bankX, int bankY, int bankPlane, String timestamp) {
            this.key = key;
            this.candidates = candidates;
            this.bankX = bankX;
            this.bankY = bankY;
            this.bankPlane = bankPlane;
            this.timestamp = timestamp;
        }

        static FileCacheEntry from(CacheEntry entry) {
            List<FileCachedCandidate> fileCandidates = entry.candidates().stream()
                    .map(FileCachedCandidate::from)
                    .collect(Collectors.toList());
            
            WorldPoint bank = entry.bankPosition();
            return new FileCacheEntry(
                    entry.key(),
                    fileCandidates,
                    bank != null ? bank.getX() : 0,
                    bank != null ? bank.getY() : 0,
                    bank != null ? bank.getPlane() : 0,
                    entry.timestamp().toString()
            );
        }

        CacheEntry toCacheEntry() {
            List<CachedCandidate> cachedCandidates = candidates.stream()
                    .map(FileCachedCandidate::toCachedCandidate)
                    .collect(Collectors.toList());
            
            WorldPoint bank = (bankX != 0 || bankY != 0) 
                    ? new WorldPoint(bankX, bankY, bankPlane) 
                    : null;
            
            return new CacheEntry(
                    key,
                    cachedCandidates,
                    bank,
                    Instant.parse(timestamp)
            );
        }
    }

    /**
     * File-serializable candidate (class for GSON compatibility).
     */
    private static class FileCachedCandidate {
        int x;
        int y;
        int plane;
        int objectId;
        int cost;
        int bankDistance;

        FileCachedCandidate() {}

        FileCachedCandidate(int x, int y, int plane, int objectId, int cost, int bankDistance) {
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.objectId = objectId;
            this.cost = cost;
            this.bankDistance = bankDistance;
        }

        static FileCachedCandidate from(CachedCandidate c) {
            return new FileCachedCandidate(c.x(), c.y(), c.plane(), c.objectId(), c.cost(), c.bankDistance());
        }

        CachedCandidate toCachedCandidate() {
            return new CachedCandidate(x, y, plane, objectId, cost, bankDistance);
        }
    }

    @Inject
    public TrainingSpotCache() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        this.memoryCache = CacheBuilder.newBuilder()
                .maximumSize(MAX_MEMORY_ENTRIES)
                .expireAfterWrite(CACHE_TTL_DAYS, TimeUnit.DAYS)
                .recordStats()
                .build();

        this.cacheDirectory = Paths.get(System.getProperty("user.home"), resolveCacheDir());

        initialize();
    }

    private static String resolveCacheDir() {
        String prop = System.getProperty("TRAINING_SPOT_CACHE_DIR");
        if (prop != null && !prop.isEmpty()) {
            return prop;
        }
        String env = System.getenv("TRAINING_SPOT_CACHE_DIR");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return "/home/runelite/.local/share/bolt-launcher/.runelite/rocinante/training-spot-cache";
    }

    /**
     * Initialize the cache manager.
     * Creates cache directory if needed.
     */
    private void initialize() {
        try {
            Files.createDirectories(cacheDirectory);
            initialized = true;
            log.info("TrainingSpotCache initialized at: {}", cacheDirectory);
        } catch (IOException e) {
            log.error("Failed to create training spot cache directory: {}", cacheDirectory, e);
            initialized = false;
        }
    }

    // ========================================================================
    // Cache Operations
    // ========================================================================

    /**
     * Get cached rankings by key.
     *
     * <p>Lookup order: memory cache → file cache.
     * Returns empty if not found or expired.
     *
     * @param cacheKey the cache key
     * @return the cached rankings, or empty if not found/expired
     */
    public Optional<List<RankedCandidate>> get(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return Optional.empty();
        }

        // Check memory cache first
        CacheEntry memoryEntry = memoryCache.getIfPresent(cacheKey);
        if (memoryEntry != null && !memoryEntry.isStale()) {
            log.debug("Memory cache hit for key: {}", cacheKey);
            return Optional.of(toRankedCandidates(memoryEntry.candidates()));
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
                return Optional.of(toRankedCandidates(entry.candidates()));
            } else {
                log.debug("File cache entry is stale for key: {}", cacheKey);
            }
        }

        fileMisses++;
        return Optional.empty();
    }

    /**
     * Get cached rankings including stale entries if valid ones aren't available.
     *
     * @param cacheKey the cache key
     * @return the cached rankings (possibly stale), or empty if not found
     */
    public Optional<List<RankedCandidate>> getIncludingStale(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return Optional.empty();
        }

        // Check memory cache first
        CacheEntry memoryEntry = memoryCache.getIfPresent(cacheKey);
        if (memoryEntry != null) {
            if (memoryEntry.isStale()) {
                log.debug("Returning stale memory cache entry for key: {}", cacheKey);
            }
            return Optional.of(toRankedCandidates(memoryEntry.candidates()));
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
            return Optional.of(toRankedCandidates(entry.candidates()));
        }

        return Optional.empty();
    }

    /**
     * Store rankings in both caches.
     *
     * @param cacheKey     the cache key
     * @param candidates   the ranked candidates to cache
     * @param bankPosition the bank position used for ranking (null if no banking)
     */
    public void put(String cacheKey, List<RankedCandidate> candidates, WorldPoint bankPosition) {
        if (cacheKey == null || cacheKey.isEmpty() || candidates == null || candidates.isEmpty()) {
            return;
        }

        List<CachedCandidate> cachedCandidates = candidates.stream()
                .map(CachedCandidate::from)
                .collect(Collectors.toList());

        CacheEntry entry = new CacheEntry(cacheKey, cachedCandidates, bankPosition, Instant.now());

        // Update memory cache
        memoryCache.put(cacheKey, entry);

        // Update file cache
        putToFileCache(cacheKey, entry);

        log.debug("Cached {} training spot rankings for key: {}", candidates.size(), cacheKey);
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

        log.info("Invalidated all training spot cache entries");
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
     * @return the cache entry, or empty if not found/invalid
     */
    private Optional<CacheEntry> readCacheFile(Path filePath) {
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }

        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            FileCacheEntry fileEntry = gson.fromJson(json, FileCacheEntry.class);
            if (fileEntry == null || fileEntry.candidates == null) {
                log.warn("Invalid cache file (null content): {}", filePath);
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
            FileCacheEntry fileEntry = FileCacheEntry.from(entry);
            String json = gson.toJson(fileEntry);
            Files.writeString(filePath, json, StandardCharsets.UTF_8);
            log.debug("Wrote cache file: {}", filePath);
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
    // Conversion Helpers
    // ========================================================================

    private List<RankedCandidate> toRankedCandidates(List<CachedCandidate> cached) {
        return cached.stream()
                .map(CachedCandidate::toRankedCandidate)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    /**
     * Get combined cache statistics.
     *
     * @return cache statistics
     */
    public TrainingSpotCacheStats getStats() {
        CacheStats guavaStats = memoryCache.stats();
        return new TrainingSpotCacheStats(
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
    public record TrainingSpotCacheStats(
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
    // Cache Key Generation
    // ========================================================================

    /**
     * Generate a cache key for training spot rankings.
     *
     * @param regionId   the map region ID
     * @param objectIds  the object IDs being searched for
     * @param bankRequired whether banking is required (affects roundtrip calculation)
     * @return the cache key
     */
    public static String trainingSpotKey(int regionId, Collection<Integer> objectIds, boolean bankRequired) {
        // Sort object IDs for consistent hashing
        String objectIdHash = hashObjectIds(objectIds);
        return String.format("training:%d:%s:%s", regionId, objectIdHash, bankRequired);
    }

    /**
     * Hash a collection of object IDs to a short string.
     */
    private static String hashObjectIds(Collection<Integer> objectIds) {
        if (objectIds == null || objectIds.isEmpty()) {
            return "empty";
        }
        
        // Sort and concatenate for consistent hashing
        String joined = objectIds.stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            // Use first 4 bytes (8 hex chars) for brevity
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
