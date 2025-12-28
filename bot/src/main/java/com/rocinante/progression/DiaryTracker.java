package com.rocinante.progression;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Varbits;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks achievement diary completion status using RuneLite varbits.
 *
 * <p>Achievement diaries provide permanent rewards when completed:
 * <ul>
 *   <li>Unlimited teleports (Ardougne cloak, Fremennik boots, etc.)</li>
 *   <li>Resource access (noted bones, extra herb patches)</li>
 *   <li>Combat bonuses (Slayer helm recolors, better drop rates)</li>
 *   <li>Transportation unlocks (Fairy rings without staff, spirit tree access)</li>
 * </ul>
 *
 * <p>This class uses RuneLite's {@link Varbits} constants directly
 * to avoid hardcoding varbit IDs.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Check if Lumbridge Elite diary is complete (for fairy ring without staff)
 * if (diaryTracker.isComplete(DiaryRegion.LUMBRIDGE, DiaryTier.ELITE)) {
 *     // Can use fairy rings without dramen/lunar staff
 * }
 *
 * // Get highest completed tier for a region
 * DiaryTier best = diaryTracker.getHighestCompletedTier(DiaryRegion.VARROCK);
 * }</pre>
 */
@Slf4j
@Singleton
public class DiaryTracker {

    private final Client client;

    // Cache for diary completion status (refreshed on demand)
    private final Map<DiaryRegion, Map<DiaryTier, Boolean>> completionCache = new EnumMap<>(DiaryRegion.class);
    private int lastCacheRefreshTick = -1;
    private static final int CACHE_TTL_TICKS = 100; // Refresh every ~60 seconds

    // ========================================================================
    // Enums
    // ========================================================================

    /**
     * Achievement diary regions.
     */
    @Getter
    public enum DiaryRegion {
        ARDOUGNE("Ardougne"),
        DESERT("Desert"),
        FALADOR("Falador"),
        FREMENNIK("Fremennik"),
        KANDARIN("Kandarin"),
        KARAMJA("Karamja"),
        KOUREND("Kourend & Kebos"),
        LUMBRIDGE("Lumbridge & Draynor"),
        MORYTANIA("Morytania"),
        VARROCK("Varrock"),
        WESTERN("Western Provinces"),
        WILDERNESS("Wilderness");

        private final String displayName;

        DiaryRegion(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * Achievement diary tiers.
     */
    @Getter
    public enum DiaryTier {
        EASY(1),
        MEDIUM(2),
        HARD(3),
        ELITE(4);

        private final int level;

        DiaryTier(int level) {
            this.level = level;
        }
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    @Inject
    public DiaryTracker(Client client) {
        this.client = client;
        log.info("DiaryTracker initialized");
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Check if a diary tier is complete.
     *
     * @param region the diary region
     * @param tier   the diary tier
     * @return true if the diary tier is completed
     */
    public boolean isComplete(DiaryRegion region, DiaryTier tier) {
        int varbitId = getVarbitId(region, tier);
        if (varbitId == -1) {
            log.warn("No varbit found for diary {}/{}", region, tier);
            return false;
        }

        try {
            int value = client.getVarbitValue(varbitId);
            boolean complete = value == 1;
            log.trace("Diary {}/{} = {} (complete={})", region, tier, value, complete);
            return complete;
        } catch (Exception e) {
            log.warn("Error checking diary varbit {}: {}", varbitId, e.getMessage());
            return false;
        }
    }

    /**
     * Check if a diary tier is complete by string identifier.
     * Format: "REGION_TIER" e.g., "LUMBRIDGE_ELITE", "VARROCK_HARD"
     *
     * @param diaryTier the diary tier string
     * @return true if completed
     */
    public boolean isComplete(String diaryTier) {
        if (diaryTier == null || diaryTier.isEmpty()) {
            return false;
        }

        String upper = diaryTier.toUpperCase();

        // Parse region and tier
        DiaryRegion region = null;
        DiaryTier tier = null;

        for (DiaryRegion r : DiaryRegion.values()) {
            if (upper.startsWith(r.name())) {
                region = r;
                break;
            }
        }

        for (DiaryTier t : DiaryTier.values()) {
            if (upper.endsWith(t.name())) {
                tier = t;
                break;
            }
        }

        if (region == null || tier == null) {
            log.warn("Could not parse diary tier string: {}", diaryTier);
            return false;
        }

        return isComplete(region, tier);
    }

    /**
     * Check if any tier of a diary is complete.
     *
     * @param region the diary region
     * @return true if at least Easy tier is complete
     */
    public boolean hasAnyComplete(DiaryRegion region) {
        for (DiaryTier tier : DiaryTier.values()) {
            if (isComplete(region, tier)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the highest completed diary tier for a region.
     *
     * @param region the diary region
     * @return highest completed tier, or null if none complete
     */
    @Nullable
    public DiaryTier getHighestCompletedTier(DiaryRegion region) {
        // Check from highest to lowest
        if (isComplete(region, DiaryTier.ELITE)) return DiaryTier.ELITE;
        if (isComplete(region, DiaryTier.HARD)) return DiaryTier.HARD;
        if (isComplete(region, DiaryTier.MEDIUM)) return DiaryTier.MEDIUM;
        if (isComplete(region, DiaryTier.EASY)) return DiaryTier.EASY;
        return null;
    }

    /**
     * Get all completed diary tiers for a region.
     *
     * @param region the diary region
     * @return set of completed tiers
     */
    public Set<DiaryTier> getCompletedTiers(DiaryRegion region) {
        Set<DiaryTier> completed = EnumSet.noneOf(DiaryTier.class);
        for (DiaryTier tier : DiaryTier.values()) {
            if (isComplete(region, tier)) {
                completed.add(tier);
            }
        }
        return completed;
    }

    /**
     * Get all regions where at least one tier is complete.
     *
     * @return set of regions with any completion
     */
    public Set<DiaryRegion> getRegionsWithCompletion() {
        Set<DiaryRegion> regions = EnumSet.noneOf(DiaryRegion.class);
        for (DiaryRegion region : DiaryRegion.values()) {
            if (hasAnyComplete(region)) {
                regions.add(region);
            }
        }
        return regions;
    }

    /**
     * Count total completed diary tiers across all regions.
     *
     * @return total count (0-48, 12 regions * 4 tiers)
     */
    public int getTotalCompletedCount() {
        int count = 0;
        for (DiaryRegion region : DiaryRegion.values()) {
            count += getCompletedTiers(region).size();
        }
        return count;
    }

    // ========================================================================
    // Common Diary Reward Checks
    // ========================================================================

    /**
     * Check if player can use fairy rings without a dramen/lunar staff.
     * Requires Lumbridge & Draynor Elite diary.
     *
     * @return true if fairy ring access is staffless
     */
    public boolean hasFairyRingWithoutStaff() {
        return isComplete(DiaryRegion.LUMBRIDGE, DiaryTier.ELITE);
    }

    /**
     * Check if player has unlimited Ardougne teleports via cloak.
     * Requires Ardougne Medium diary.
     *
     * @return true if unlimited Ardougne cloak teleports available
     */
    public boolean hasUnlimitedArdougneTeleport() {
        return isComplete(DiaryRegion.ARDOUGNE, DiaryTier.MEDIUM);
    }

    /**
     * Check if player has access to the Prifddinas city.
     * This is quest-based (Song of the Elves), not diary.
     * Included here for convenience but delegates to quest checks.
     *
     * @return false (needs quest check from UnlockTracker)
     */
    public boolean hasPrifddinas() {
        // This is actually a quest requirement (Song of the Elves)
        // Return false here - UnlockTracker handles quest checks
        return false;
    }

    /**
     * Check if player has noted bones from Morytania.
     * Requires Morytania Hard diary.
     *
     * @return true if bones are automatically noted in Morytania
     */
    public boolean hasNotedBones() {
        return isComplete(DiaryRegion.MORYTANIA, DiaryTier.HARD);
    }

    /**
     * Check if player has extra herb patches (Falador Hard).
     * Requires Falador Hard diary.
     *
     * @return true if extra herb patch is available
     */
    public boolean hasExtraHerbPatch() {
        return isComplete(DiaryRegion.FALADOR, DiaryTier.HARD);
    }

    /**
     * Check if player has Karamja gloves 4 benefits.
     * Requires Karamja Elite diary.
     *
     * @return true if Karamja Elite complete
     */
    public boolean hasKaramjaElite() {
        return isComplete(DiaryRegion.KARAMJA, DiaryTier.ELITE);
    }

    /**
     * Check if player has Wilderness sword 4 benefits.
     * Requires Wilderness Elite diary.
     *
     * @return true if Wilderness Elite complete
     */
    public boolean hasWildernessElite() {
        return isComplete(DiaryRegion.WILDERNESS, DiaryTier.ELITE);
    }

    // ========================================================================
    // Varbit Mapping
    // ========================================================================

    /**
     * Get the varbit ID for a diary region and tier.
     * Uses RuneLite's Varbits constants directly.
     */
    private int getVarbitId(DiaryRegion region, DiaryTier tier) {
        switch (region) {
            case ARDOUGNE:
                switch (tier) {
                    case EASY: return Varbits.DIARY_ARDOUGNE_EASY;
                    case MEDIUM: return Varbits.DIARY_ARDOUGNE_MEDIUM;
                    case HARD: return Varbits.DIARY_ARDOUGNE_HARD;
                    case ELITE: return Varbits.DIARY_ARDOUGNE_ELITE;
                }
                break;
            case DESERT:
                switch (tier) {
                    case EASY: return Varbits.DIARY_DESERT_EASY;
                    case MEDIUM: return Varbits.DIARY_DESERT_MEDIUM;
                    case HARD: return Varbits.DIARY_DESERT_HARD;
                    case ELITE: return Varbits.DIARY_DESERT_ELITE;
                }
                break;
            case FALADOR:
                switch (tier) {
                    case EASY: return Varbits.DIARY_FALADOR_EASY;
                    case MEDIUM: return Varbits.DIARY_FALADOR_MEDIUM;
                    case HARD: return Varbits.DIARY_FALADOR_HARD;
                    case ELITE: return Varbits.DIARY_FALADOR_ELITE;
                }
                break;
            case FREMENNIK:
                switch (tier) {
                    case EASY: return Varbits.DIARY_FREMENNIK_EASY;
                    case MEDIUM: return Varbits.DIARY_FREMENNIK_MEDIUM;
                    case HARD: return Varbits.DIARY_FREMENNIK_HARD;
                    case ELITE: return Varbits.DIARY_FREMENNIK_ELITE;
                }
                break;
            case KANDARIN:
                switch (tier) {
                    case EASY: return Varbits.DIARY_KANDARIN_EASY;
                    case MEDIUM: return Varbits.DIARY_KANDARIN_MEDIUM;
                    case HARD: return Varbits.DIARY_KANDARIN_HARD;
                    case ELITE: return Varbits.DIARY_KANDARIN_ELITE;
                }
                break;
            case KARAMJA:
                switch (tier) {
                    case EASY: return Varbits.DIARY_KARAMJA_EASY;
                    case MEDIUM: return Varbits.DIARY_KARAMJA_MEDIUM;
                    case HARD: return Varbits.DIARY_KARAMJA_HARD;
                    case ELITE: return Varbits.DIARY_KARAMJA_ELITE;
                }
                break;
            case KOUREND:
                switch (tier) {
                    case EASY: return Varbits.DIARY_KOUREND_EASY;
                    case MEDIUM: return Varbits.DIARY_KOUREND_MEDIUM;
                    case HARD: return Varbits.DIARY_KOUREND_HARD;
                    case ELITE: return Varbits.DIARY_KOUREND_ELITE;
                }
                break;
            case LUMBRIDGE:
                switch (tier) {
                    case EASY: return Varbits.DIARY_LUMBRIDGE_EASY;
                    case MEDIUM: return Varbits.DIARY_LUMBRIDGE_MEDIUM;
                    case HARD: return Varbits.DIARY_LUMBRIDGE_HARD;
                    case ELITE: return Varbits.DIARY_LUMBRIDGE_ELITE;
                }
                break;
            case MORYTANIA:
                switch (tier) {
                    case EASY: return Varbits.DIARY_MORYTANIA_EASY;
                    case MEDIUM: return Varbits.DIARY_MORYTANIA_MEDIUM;
                    case HARD: return Varbits.DIARY_MORYTANIA_HARD;
                    case ELITE: return Varbits.DIARY_MORYTANIA_ELITE;
                }
                break;
            case VARROCK:
                switch (tier) {
                    case EASY: return Varbits.DIARY_VARROCK_EASY;
                    case MEDIUM: return Varbits.DIARY_VARROCK_MEDIUM;
                    case HARD: return Varbits.DIARY_VARROCK_HARD;
                    case ELITE: return Varbits.DIARY_VARROCK_ELITE;
                }
                break;
            case WESTERN:
                switch (tier) {
                    case EASY: return Varbits.DIARY_WESTERN_EASY;
                    case MEDIUM: return Varbits.DIARY_WESTERN_MEDIUM;
                    case HARD: return Varbits.DIARY_WESTERN_HARD;
                    case ELITE: return Varbits.DIARY_WESTERN_ELITE;
                }
                break;
            case WILDERNESS:
                switch (tier) {
                    case EASY: return Varbits.DIARY_WILDERNESS_EASY;
                    case MEDIUM: return Varbits.DIARY_WILDERNESS_MEDIUM;
                    case HARD: return Varbits.DIARY_WILDERNESS_HARD;
                    case ELITE: return Varbits.DIARY_WILDERNESS_ELITE;
                }
                break;
        }
        return -1;
    }

    // ========================================================================
    // Debug/Logging
    // ========================================================================

    /**
     * Log diary completion status for debugging.
     */
    public void logDiaryStatus() {
        log.info("=== Diary Completion Status ===");
        for (DiaryRegion region : DiaryRegion.values()) {
            DiaryTier highest = getHighestCompletedTier(region);
            String status = highest != null ? highest.name() : "NONE";
            log.info("{}: {}", region.getDisplayName(), status);
        }
        log.info("Total completed tiers: {}/48", getTotalCompletedCount());
        log.info("================================");
    }
}

