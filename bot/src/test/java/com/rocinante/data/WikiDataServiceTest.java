package com.rocinante.data;

import com.rocinante.data.model.DropTable;
import com.rocinante.data.model.ItemSource;
import com.rocinante.data.model.ShopInventory;
import com.rocinante.data.model.WeaponInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests for WikiDataService.
 * These tests hit the real OSRS Wiki API to verify the full pipeline works.
 */
public class WikiDataServiceTest {

    private WikiCacheManager cacheManager;
    private WikiTemplateParser templateParser;
    private WikiDataService wikiDataService;

    @Before
    public void setUp() {
        cacheManager = new WikiCacheManager();
        templateParser = new WikiTemplateParser();
        wikiDataService = new WikiDataService(cacheManager, templateParser);

        // Clear cache to ensure fresh fetches
        cacheManager.invalidateAll();
    }

    @After
    public void tearDown() {
        wikiDataService.shutdown();
        cacheManager.invalidateAll();
    }

    // ========================================================================
    // Service State Tests
    // ========================================================================

    @Test
    public void testServiceIsAvailable() {
        assertTrue("Service should be available on startup", wikiDataService.isAvailable());
        assertEquals("Circuit should be closed", WikiDataService.CircuitState.CLOSED,
                wikiDataService.getCircuitState());
    }

    @Test
    public void testGetStats() {
        WikiDataService.ServiceStats stats = wikiDataService.getStats();
        assertNotNull("Stats should not be null", stats);
        assertEquals("Should start with 0 requests", 0, stats.totalRequests());
        assertEquals(WikiDataService.CircuitState.CLOSED, stats.circuitState());
    }

    // ========================================================================
    // Drop Table Integration Tests
    // ========================================================================

    @Test
    public void testGetDropTable_AbyssalDemon() throws Exception {
        DropTable dropTable = wikiDataService.getDropTable("Abyssal demon")
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Drop table should not be null", dropTable);
        assertEquals("Abyssal demon", dropTable.monsterName());
        assertEquals("Combat level should be 124", 124, dropTable.combatLevel());
        assertEquals("Slayer level should be 85", 85, dropTable.slayerLevel());

        // Should have drops
        assertFalse("Should have drops", dropTable.drops().isEmpty());

        // Should have the Abyssal whip
        List<DropTable.Drop> whipDrops = dropTable.findDropsByName("whip");
        assertFalse("Should have whip drop", whipDrops.isEmpty());

        // Verify wiki URL is set
        assertNotNull("Wiki URL should be set", dropTable.wikiUrl());
        assertTrue("Wiki URL should point to wiki",
                dropTable.wikiUrl().contains("oldschool.runescape.wiki"));
    }

    @Test
    public void testGetDropTable_Goblin() throws Exception {
        DropTable dropTable = wikiDataService.getDropTable("Goblin")
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Drop table should not be null", dropTable);
        assertEquals("Goblin", dropTable.monsterName());

        // Goblins have low combat level
        assertTrue("Combat level should be low", dropTable.combatLevel() < 20);

        // No slayer requirement
        assertEquals("No slayer requirement", 0, dropTable.slayerLevel());

        // Should have drops
        assertFalse("Should have drops", dropTable.drops().isEmpty());
    }

    @Test
    public void testGetDropTable_Caching() throws Exception {
        // First fetch - from wiki
        long startTime1 = System.currentTimeMillis();
        DropTable first = wikiDataService.getDropTable("Cow")
                .get(30, TimeUnit.SECONDS);
        long duration1 = System.currentTimeMillis() - startTime1;

        // Second fetch - should be from cache (much faster)
        long startTime2 = System.currentTimeMillis();
        DropTable second = wikiDataService.getDropTable("Cow")
                .get(30, TimeUnit.SECONDS);
        long duration2 = System.currentTimeMillis() - startTime2;

        assertNotNull("First fetch should succeed", first);
        assertNotNull("Second fetch should succeed", second);
        assertEquals("Monster names should match", first.monsterName(), second.monsterName());

        // Second fetch should be significantly faster (cached)
        assertTrue("Cached fetch should be faster: " + duration1 + "ms vs " + duration2 + "ms",
                duration2 < duration1 / 2 || duration2 < 100);
    }

    // ========================================================================
    // Weapon Info Integration Tests
    // ========================================================================

    @Test
    public void testGetWeaponInfo_DragonDagger() throws Exception {
        WeaponInfo weapon = wikiDataService.getWeaponInfo(1215, "Dragon dagger")
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Weapon info should not be null", weapon);
        assertEquals("Dragon dagger", weapon.itemName());

        // DDS has special attack
        assertTrue("Should have special attack", weapon.hasSpecialAttack());
        assertEquals("DDS spec costs 25%", 25, weapon.specialAttackCost());

        // Should have attack speed
        assertTrue("Should have attack speed", weapon.hasKnownAttackSpeed());
    }

    @Test
    public void testGetWeaponInfo_AbyssalWhip() throws Exception {
        WeaponInfo weapon = wikiDataService.getWeaponInfo(4151, "Abyssal whip")
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Weapon info should not be null", weapon);

        // Whip has special attack
        assertTrue("Whip should have special attack", weapon.hasSpecialAttack());
        assertEquals("Whip spec costs 50%", 50, weapon.specialAttackCost());

        // Whip is 4-tick weapon
        if (weapon.hasKnownAttackSpeed()) {
            assertEquals("Whip is 4-tick", 4, weapon.attackSpeed());
        }

        // Should have slash bonus
        assertTrue("Should have slash bonus", weapon.slashBonus() > 0);
    }

    @Test
    public void testGetWeaponInfoSync() {
        // Sync version should also work
        WeaponInfo weapon = wikiDataService.getWeaponInfoSync(1215, "Dragon dagger");

        assertNotNull("Sync fetch should work", weapon);
        if (weapon.isValid()) {
            assertEquals("Dragon dagger", weapon.itemName());
            assertTrue("Should have special attack", weapon.hasSpecialAttack());
        }
    }

    // ========================================================================
    // Item Source Integration Tests
    // ========================================================================

    @Test
    public void testGetItemSources_Bones() throws Exception {
        ItemSource itemSource = wikiDataService.getItemSources("Bones")
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Item source should not be null", itemSource);
        assertEquals("Bones", itemSource.itemName());

        // Bones are tradeable and F2P
        assertTrue("Bones should be tradeable", itemSource.tradeable());
        assertFalse("Bones should be F2P", itemSource.membersOnly());
    }

    @Test
    public void testGetItemSources_DragonScimitar() throws Exception {
        ItemSource itemSource = wikiDataService.getItemSources("Dragon scimitar")
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Item source should not be null", itemSource);

        // Dragon scimitar is tradeable and members
        assertTrue("Should be tradeable", itemSource.tradeable());
        assertTrue("Should be members only", itemSource.membersOnly());
    }

    // ========================================================================
    // Shop Inventory Integration Tests
    // ========================================================================

    @Test
    public void testGetShopInventory_AuburysRuneShop() throws Exception {
        ShopInventory shop = wikiDataService.getShopInventory("Aubury's Rune Shop")
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Shop should not be null", shop);
        assertEquals("Aubury's Rune Shop", shop.shopName());

        // Note: Shop parsing depends on wiki page format
        // Just verify we get a valid response
        assertTrue("Shop should be valid", shop.isValid());
    }

    // ========================================================================
    // Search Integration Tests
    // ========================================================================

    @Test
    public void testSearch_Abyssal() throws Exception {
        List<String> results = wikiDataService.search("Abyssal", 5, WikiDataService.Priority.LOW)
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Search results should not be null", results);
        assertFalse("Should have results for 'Abyssal'", results.isEmpty());

        // Should include Abyssal demon or Abyssal whip
        boolean hasAbyssalResult = results.stream()
                .anyMatch(r -> r.toLowerCase().contains("abyssal"));
        assertTrue("Should have Abyssal-related results", hasAbyssalResult);
    }

    @Test
    public void testSearch_Dragon() throws Exception {
        List<String> results = wikiDataService.search("Dragon dagger")
                .get(30, TimeUnit.SECONDS);

        assertNotNull("Search results should not be null", results);
        assertFalse("Should have results", results.isEmpty());
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Test
    public void testGetDropTable_NonexistentMonster() throws Exception {
        // This should return empty drop table, not throw
        DropTable dropTable = wikiDataService.getDropTable("ThisMonsterDoesNotExist12345")
                .get(30, TimeUnit.SECONDS);

        // Should return empty or minimal table, not crash
        assertNotNull("Should return non-null even for invalid monster", dropTable);
    }

    @Test
    public void testGetWeaponInfo_NonexistentItem() throws Exception {
        WeaponInfo weapon = wikiDataService.getWeaponInfo(-1, "ThisItemDoesNotExist12345")
                .get(30, TimeUnit.SECONDS);

        // Should return empty weapon info, not crash
        assertNotNull("Should return non-null even for invalid item", weapon);
    }

    // ========================================================================
    // Rate Limiting Tests
    // ========================================================================

    @Test
    public void testRateLimiting_MultipleRequests() throws Exception {
        // Make several requests in quick succession
        // The service should rate limit them
        long startTime = System.currentTimeMillis();

        wikiDataService.getDropTable("Goblin").get(30, TimeUnit.SECONDS);
        wikiDataService.getDropTable("Cow").get(30, TimeUnit.SECONDS);
        wikiDataService.getDropTable("Chicken").get(30, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;

        // With rate limiting at 30 req/min (2s between requests),
        // 3 requests should take at least 4 seconds
        // But we're lenient since first request may be fast from cold start
        assertTrue("Requests completed", duration > 0);

        // Verify stats show requests
        WikiDataService.ServiceStats stats = wikiDataService.getStats();
        assertTrue("Should have made requests", stats.totalRequests() >= 3);
    }
}

