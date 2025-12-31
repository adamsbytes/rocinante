package com.rocinante.data;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Unit tests for WikiCacheManager.
 */
public class WikiCacheManagerTest {

    private WikiCacheManager cacheManager;
    private Path testCacheDir;

    @Before
    public void setUp() throws IOException {
        // Isolate cache dir per test run
        testCacheDir = Files.createTempDirectory("wiki-cache-test");
        System.setProperty("WIKI_CACHE_DIR", testCacheDir.toString());

        cacheManager = new WikiCacheManager();
        testCacheDir = cacheManager.getCacheDirectory();
    }

    @After
    public void tearDown() throws IOException {
        System.clearProperty("WIKI_CACHE_DIR");
        Path cleanupDir = cacheManager.getCacheDirectory();
        if (Files.exists(cleanupDir)) {
            try (Stream<Path> files = Files.walk(cleanupDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    // ========================================================================
    // Initialization Tests
    // ========================================================================

    @Test
    public void testInitialization() {
        assertTrue("Cache manager should be initialized", cacheManager.isInitialized());
        assertTrue("Cache directory should exist", Files.exists(testCacheDir));
    }

    // ========================================================================
    // Basic Cache Operations
    // ========================================================================

    @Test
    public void testPutAndGet() {
        String key = "test_key";
        String content = "test content";

        cacheManager.put(key, content);
        Optional<String> retrieved = cacheManager.get(key);

        assertTrue("Should retrieve cached content", retrieved.isPresent());
        assertEquals("Content should match", content, retrieved.get());
    }

    @Test
    public void testGetNonExistent() {
        Optional<String> result = cacheManager.get("nonexistent_key");
        assertFalse("Should return empty for non-existent key", result.isPresent());
    }

    @Test
    public void testGetNullKey() {
        Optional<String> result = cacheManager.get(null);
        assertFalse("Should return empty for null key", result.isPresent());
    }

    @Test
    public void testGetEmptyKey() {
        Optional<String> result = cacheManager.get("");
        assertFalse("Should return empty for empty key", result.isPresent());
    }

    @Test
    public void testPutNullKey() {
        cacheManager.put(null, "content");
        // Should not throw, just be a no-op
    }

    @Test
    public void testPutNullContent() {
        cacheManager.put("key", null);
        Optional<String> result = cacheManager.get("key");
        assertFalse("Should return empty for null content", result.isPresent());
    }

    @Test
    public void testPutWithContentType() {
        String key = "typed_key";
        String content = "typed content";
        String contentType = "drop_table";

        cacheManager.put(key, content, contentType);
        Optional<String> retrieved = cacheManager.get(key);

        assertTrue("Should retrieve content", retrieved.isPresent());
        assertEquals("Content should match", content, retrieved.get());
    }

    // ========================================================================
    // Invalidation Tests
    // ========================================================================

    @Test
    public void testInvalidate() {
        String key = "invalidate_test";
        cacheManager.put(key, "content");

        assertTrue("Should have content before invalidation", cacheManager.get(key).isPresent());

        cacheManager.invalidate(key);

        assertFalse("Should not have content after invalidation", cacheManager.get(key).isPresent());
    }

    @Test
    public void testInvalidateNonExistent() {
        cacheManager.invalidate("nonexistent");
        // Should not throw
    }

    @Test
    public void testInvalidateAll() {
        cacheManager.put("key1", "content1");
        cacheManager.put("key2", "content2");
        cacheManager.put("key3", "content3");

        cacheManager.invalidateAll();

        assertFalse("Key1 should be invalidated", cacheManager.get("key1").isPresent());
        assertFalse("Key2 should be invalidated", cacheManager.get("key2").isPresent());
        assertFalse("Key3 should be invalidated", cacheManager.get("key3").isPresent());
    }

    // ========================================================================
    // File Cache Tests
    // ========================================================================

    @Test
    public void testFileCachePersistence() throws IOException {
        String key = "persistence_test";
        String content = "persistent content";

        cacheManager.put(key, content);

        // Verify file was created
        long fileCount;
        try (Stream<Path> files = Files.list(testCacheDir)) {
            fileCount = files.filter(p -> p.toString().endsWith(".json")).count();
        }
        assertTrue("Should have at least one cache file", fileCount >= 1);
    }

    @Test
    public void testFileCacheRecovery() {
        String key = "recovery_test";
        String content = "recovery content";

        cacheManager.put(key, content);

        // Create a new cache manager instance to simulate restart
        WikiCacheManager newCacheManager = new WikiCacheManager();

        Optional<String> recovered = newCacheManager.get(key);
        assertTrue("Should recover content from file cache", recovered.isPresent());
        assertEquals("Recovered content should match", content, recovered.get());
    }

    // ========================================================================
    // Stale Cache Tests
    // ========================================================================

    @Test
    public void testGetIncludingStale() {
        String key = "stale_test";
        String content = "stale content";

        cacheManager.put(key, content);

        Optional<String> result = cacheManager.getIncludingStale(key);
        assertTrue("Should retrieve fresh content", result.isPresent());
        assertEquals("Content should match", content, result.get());
    }

    @Test
    public void testGetIncludingStaleNonExistent() {
        Optional<String> result = cacheManager.getIncludingStale("nonexistent");
        assertFalse("Should return empty for non-existent key", result.isPresent());
    }

    @Test
    public void testIsStale() {
        String key = "stale_check";
        cacheManager.put(key, "content");

        // Fresh entry should not be stale
        assertFalse("Fresh entry should not be stale", cacheManager.isStale(key));
    }

    @Test
    public void testGetStaleKeys() {
        // With fresh entries, should have no stale keys
        cacheManager.put("fresh1", "content1");
        cacheManager.put("fresh2", "content2");

        long staleCount = cacheManager.getStaleKeys().count();
        assertEquals("Fresh entries should not be stale", 0, staleCount);
    }

    // ========================================================================
    // Statistics Tests
    // ========================================================================

    @Test
    public void testGetStats() {
        cacheManager.put("stats_test", "content");
        cacheManager.get("stats_test"); // Hit
        cacheManager.get("nonexistent"); // Miss

        WikiCacheManager.WikiCacheStats stats = cacheManager.getStats();

        assertNotNull("Stats should not be null", stats);
        assertTrue("Memory size should be >= 1", stats.memorySize() >= 1);
    }

    @Test
    public void testStatsHitRates() {
        WikiCacheManager.WikiCacheStats stats = new WikiCacheManager.WikiCacheStats(10, 5, 3, 2, 10, 5);

        // Memory hit rate: 10 / (10 + 5) = 0.667
        assertEquals(0.667, stats.memoryHitRate(), 0.01);

        // File hit rate: 3 / (3 + 2) = 0.6
        assertEquals(0.6, stats.fileHitRate(), 0.01);

        // Overall: (10 + 3) / (10 + 5 + 3 + 2) = 13/20 = 0.65
        assertEquals(0.65, stats.overallHitRate(), 0.01);
    }

    @Test
    public void testStatsHitRatesWithZero() {
        WikiCacheManager.WikiCacheStats stats = new WikiCacheManager.WikiCacheStats(0, 0, 0, 0, 0, 0);

        assertEquals(0.0, stats.memoryHitRate(), 0.001);
        assertEquals(0.0, stats.fileHitRate(), 0.001);
        assertEquals(0.0, stats.overallHitRate(), 0.001);
    }

    // ========================================================================
    // Cache Key Generation Tests
    // ========================================================================

    @Test
    public void testDropTableKey() {
        String key = WikiCacheManager.dropTableKey("Abyssal demon");
        assertEquals("drop_table:abyssal_demon", key);
    }

    @Test
    public void testItemSourceKey() {
        String key = WikiCacheManager.itemSourceKey("Dragon dagger");
        assertEquals("item_source:dragon_dagger", key);
    }

    @Test
    public void testShopInventoryKey() {
        String key = WikiCacheManager.shopInventoryKey("Aubury's Rune Shop");
        assertEquals("shop:aubury's_rune_shop", key);
    }

    @Test
    public void testWeaponInfoKey() {
        String key = WikiCacheManager.weaponInfoKey("Abyssal whip");
        assertEquals("weapon:abyssal_whip", key);
    }

    @Test
    public void testPageContentKey() {
        String key = WikiCacheManager.pageContentKey("Abyssal demon");
        assertEquals("page:abyssal_demon", key);
    }

    @Test
    public void testNormalizePageName() {
        assertEquals("abyssal_demon", WikiCacheManager.normalizePageName("Abyssal demon"));
        assertEquals("dragon_dagger_(p++)", WikiCacheManager.normalizePageName("Dragon dagger (p++)"));
        assertEquals("", WikiCacheManager.normalizePageName(null));
        assertEquals("", WikiCacheManager.normalizePageName("  "));
    }

    // ========================================================================
    // Large Content Tests
    // ========================================================================

    @Test
    public void testLargeContent() {
        String key = "large_content_test";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("Line ").append(i).append(": This is some test content.\n");
        }
        String largeContent = sb.toString();

        cacheManager.put(key, largeContent);
        Optional<String> retrieved = cacheManager.get(key);

        assertTrue("Should retrieve large content", retrieved.isPresent());
        assertEquals("Large content should match", largeContent, retrieved.get());
    }

    // ========================================================================
    // Special Characters Tests
    // ========================================================================

    @Test
    public void testSpecialCharactersInKey() {
        String key = "special:chars/in\\key<with>quotes\"and'apostrophes";
        String content = "content for special key";

        cacheManager.put(key, content);
        Optional<String> retrieved = cacheManager.get(key);

        assertTrue("Should handle special characters in key", retrieved.isPresent());
        assertEquals("Content should match", content, retrieved.get());
    }

    @Test
    public void testUnicodeContent() {
        String key = "unicode_test";
        String content = "Unicode content: 日本語 中文 العربية 한국어 🎮⚔️🛡️";

        cacheManager.put(key, content);
        Optional<String> retrieved = cacheManager.get(key);

        assertTrue("Should handle unicode content", retrieved.isPresent());
        assertEquals("Unicode content should match", content, retrieved.get());
    }
}

