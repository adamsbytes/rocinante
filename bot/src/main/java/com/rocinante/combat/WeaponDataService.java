package com.rocinante.combat;

import com.rocinante.data.WikiDataService;
import com.rocinante.data.model.WeaponInfo;
import com.rocinante.state.AttackStyle;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Centralized service for weapon data queries.
 *
 * Per REQUIREMENTS.md Section 10.6.1, weapon data should NOT be hardcoded.
 * This service:
 * - Queries WikiDataService for weapon attack speeds and combat info
 * - Caches weapon data with 24hr TTL
 * - Detects attack style from weapon ID
 * - Provides attack animation data for player attack detection
 * - Falls back to sensible defaults when data unavailable
 *
 * Used by:
 * - GameStateService - weapon speed and attack style for CombatState
 * - SpecialAttackManager - special attack costs (already has wiki integration)
 * - CombatTask - attack timing validation
 */
@Slf4j
@Singleton
public class WeaponDataService {

    private final WikiDataService wikiDataService;
    private final ItemManager itemManager;

    /** Cache for weapon info. Maps item ID -> CachedWeaponInfo. */
    private final Map<Integer, CachedWeaponInfo> weaponCache = new ConcurrentHashMap<>();
    /** In-flight wiki lookups by weapon ID to avoid duplicate fetches. */
    private final Map<Integer, java.util.concurrent.CompletableFuture<WeaponInfo>> inFlightRequests = new ConcurrentHashMap<>();
    /** Last fetch attempt timestamps to throttle retries on failure. */
    private final Map<Integer, Long> lastFetchAttemptMs = new ConcurrentHashMap<>();

    /** Cache TTL in milliseconds (24 hours). */
    private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24);
    /** Minimum interval between fetch retries for the same weapon. */
    private static final long FETCH_RETRY_MS = TimeUnit.SECONDS.toMillis(5);

    /** Common attack animations for different weapon categories (fallback). */
    private static final Map<String, int[]> COMMON_ATTACK_ANIMATIONS = Map.of(
            "sword", new int[]{390, 386},
            "dagger", new int[]{376, 377},
            "staff", new int[]{1162, 1167},
            "bow", new int[]{426},
            "crossbow", new int[]{427},
            "unarmed", new int[]{422, 423}
    );

    @Inject
    public WeaponDataService(WikiDataService wikiDataService, ItemManager itemManager) {
        this.wikiDataService = wikiDataService;
        this.itemManager = itemManager;
        log.info("WeaponDataService initialized");
    }

    // ========================================================================
    // Weapon Speed Queries
    // ========================================================================

    /**
     * Get the attack speed for a weapon in game ticks.
     * Queries wiki with cache, falls back to defaults.
     *
     * @param weaponId the weapon item ID
     * @return attack speed in ticks (2-6), or 4 if unknown
     */
    public int getWeaponSpeed(int weaponId) {
        if (weaponId <= 0) {
            return WeaponInfo.SPEED_AVERAGE; // Unarmed = 4 ticks
        }

        // Check cache
        WeaponInfo cached = getCachedWeaponInfo(weaponId);
        if (cached != null && cached.hasKnownAttackSpeed()) {
            return cached.attackSpeed();
        }

        // Trigger async fetch; use heuristic until data arrives
        ensureWeaponInfoFetch(weaponId);

        // Fallback: Use weapon category heuristics
        return estimateSpeedFromCategory(weaponId);
    }

    /**
     * Estimate weapon speed from category.
     */
    private int estimateSpeedFromCategory(int weaponId) {
        // Scimitars are 3-tick
        if (weaponId == ItemID.RUNE_SCIMITAR || weaponId == ItemID.DRAGON_SCIMITAR ||
            weaponId == ItemID.ADAMANT_SCIMITAR || weaponId == ItemID.MITHRIL_SCIMITAR) {
            return 3;
        }
        // Godswords are 5-tick
        if (weaponId == ItemID.ARMADYL_GODSWORD || weaponId == ItemID.BANDOS_GODSWORD ||
            weaponId == ItemID.SARADOMIN_GODSWORD || weaponId == ItemID.ZAMORAK_GODSWORD) {
            return 5;
        }
        // Whip is 4-tick
        if (weaponId == ItemID.ABYSSAL_WHIP) {
            return 4;
        }
        // Default to 4-tick (average)
        return WeaponInfo.SPEED_AVERAGE;
    }

    // ========================================================================
    // Attack Style Detection
    // ========================================================================

    /**
     * Get the attack style for a weapon.
     * Determines if weapon is melee/ranged/magic.
     *
     * @param weaponId the weapon item ID
     * @return the attack style (MELEE/RANGED/MAGIC)
     */
    public AttackStyle getAttackStyle(int weaponId) {
        if (weaponId <= 0) {
            return AttackStyle.MELEE; // Unarmed = melee
        }

        // Fast path: check WeaponCategories
        if (WeaponCategories.isRangedWeapon(weaponId)) {
            return AttackStyle.RANGED;
        }
        if (WeaponCategories.isMagicWeapon(weaponId)) {
            return AttackStyle.MAGIC;
        }

        // Check cache
        WeaponInfo cached = getCachedWeaponInfo(weaponId);
        if (cached != null) {
            if (cached.isRanged()) return AttackStyle.RANGED;
            if (cached.isMagic()) return AttackStyle.MAGIC;
            if (cached.isMelee()) return AttackStyle.MELEE;
        }

        // Kick off async fetch so we get improved data soon
        ensureWeaponInfoFetch(weaponId);

        // Default to melee
        return AttackStyle.MELEE;
    }

    // ========================================================================
    // Attack Animation Detection
    // ========================================================================

    /**
     * Check if an animation ID is a player attack animation for the given weapon.
     *
     * @param weaponId    the equipped weapon ID (or -1 for unarmed)
     * @param animationId the animation ID to check
     * @return true if this is an attack animation
     */
    public boolean isPlayerAttackAnimation(int weaponId, int animationId) {
        if (animationId < 0) {
            return false;
        }

        // Unarmed
        if (weaponId <= 0) {
            return animationId == 422 || animationId == 423; // Punch/kick
        }

        // Magic
        if (WeaponCategories.isMagicWeapon(weaponId)) {
            return animationId == 1162 || animationId == 1167 || animationId == 7855;
        }

        // Ranged
        if (WeaponCategories.isRangedWeapon(weaponId)) {
            return animationId >= 426 && animationId <= 427;
        }

        // Melee (most common range)
        return animationId >= 390 && animationId <= 450;
    }

    // ========================================================================
    // Cache Management
    // ========================================================================

    /**
     * Get cached weapon info if valid (not expired).
     */
    @Nullable
    private WeaponInfo getCachedWeaponInfo(int weaponId) {
        CachedWeaponInfo cached = weaponCache.get(weaponId);
        if (cached == null) {
            return null;
        }

        // Check if expired (24hr TTL)
        long age = System.currentTimeMillis() - cached.timestamp;
        if (age > CACHE_TTL_MS) {
            weaponCache.remove(weaponId);
            return null;
        }

        return cached.info;
    }

    /**
     * Kick off an async wiki fetch for weapon data if not already cached or in-flight.
     */
    private void ensureWeaponInfoFetch(int weaponId) {
        long now = System.currentTimeMillis();
        Long lastAttempt = lastFetchAttemptMs.get(weaponId);
        if (lastAttempt != null && (now - lastAttempt) < FETCH_RETRY_MS) {
            return;
        }
        lastFetchAttemptMs.put(weaponId, now);

        inFlightRequests.computeIfAbsent(weaponId, id -> {
            String itemName = getItemName(id);
            if (itemName == null || itemName.isEmpty()) {
                return CompletableFuture.completedFuture(WeaponInfo.EMPTY);
            }

            log.debug("Fetching weapon info async for {}", itemName);
            return wikiDataService.getWeaponInfo(id, itemName, WikiDataService.Priority.HIGH)
                    .handle((info, ex) -> {
                        if (ex != null) {
                            log.debug("Weapon info fetch failed for {}: {}", itemName, ex.getMessage());
                            return WeaponInfo.EMPTY;
                        }
                        if (info != null && info.isValid()) {
                            weaponCache.put(id, new CachedWeaponInfo(info, System.currentTimeMillis()));
                            return info;
                        }
                        return WeaponInfo.EMPTY;
                    })
                    .whenComplete((info, ex) -> inFlightRequests.remove(id));
        });
    }

    /**
     * Get item name from ItemManager.
     */
    @Nullable
    private String getItemName(int itemId) {
        if (itemId <= 0) {
            return null;
        }
        try {
            var composition = itemManager.getItemComposition(itemId);
            return composition != null ? composition.getName() : null;
        } catch (Exception e) {
            log.debug("Failed to get item name for ID {}: {}", itemId, e.getMessage());
            return null;
        }
    }

    /**
     * Clear the weapon cache.
     */
    public void clearCache() {
        weaponCache.clear();
        log.debug("Weapon cache cleared");
    }

    /**
     * Get cache statistics.
     */
    public String getCacheStats() {
        return String.format("WeaponDataService[cached=%d weapons]", weaponCache.size());
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /** Cached weapon info with timestamp for TTL validation. */
    private static class CachedWeaponInfo {
        final WeaponInfo info;
        final long timestamp;

        CachedWeaponInfo(WeaponInfo info, long timestamp) {
            this.info = info;
            this.timestamp = timestamp;
        }
    }
}
