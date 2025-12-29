package com.rocinante.util;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;

/**
 * Utility methods for NPC-related operations.
 * 
 * <p>Provides centralized logic for NPC classification (boss detection, etc.)
 * that can be used across the codebase without duplicating detection logic.
 * 
 * <p>Example usage:
 * <pre>{@code
 * if (NpcUtils.isBoss(npc.getName())) {
 *     // Use special attack, enable defensive prayer, etc.
 * }
 * }</pre>
 */
@Slf4j
public final class NpcUtils {

    private NpcUtils() {
        // Utility class
    }

    // ========================================================================
    // Boss Detection
    // ========================================================================

    /**
     * Boss NPC names for boss fight detection.
     * Names must match exactly what the game displays.
     * 
     * <p>Add additional bosses as new content is released or encountered.
     */
    private static final Set<String> BOSS_NAMES = Set.of(
            // GWD
            "General Graardor", "K'ril Tsutsaroth", "Commander Zilyana", "Kree'arra",
            // Wilderness bosses
            "Callisto", "Vet'ion", "Venenatis", "Scorpia", "Chaos Fanatic", "Crazy archaeologist",
            "King Black Dragon", "Chaos Elemental",
            // Solo bosses
            "Zulrah", "Vorkath", "The Nightmare", "Phosani's Nightmare",
            "Corporeal Beast", "Giant Mole", "Sarachnis", "Kalphite Queen",
            "Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme",
            "Cerberus", "Abyssal Sire", "Kraken", "Thermonuclear smoke devil",
            "Alchemical Hydra", "Grotesque Guardians",
            // Raids
            "Great Olm", "Verzik Vitur", "The Leviathan", "The Whisperer",
            "Vardorvis", "Duke Sucellus",
            // Slayer bosses
            "Skotizo",
            // Other
            "TzTok-Jad", "TzKal-Zuk", "Hespori", "Mimic", "The Gauntlet",
            "Crystalline Hunllef", "Corrupted Hunllef"
    );

    /**
     * Check if an NPC is a boss based on its name.
     * 
     * <p>This is the canonical boss detection method - use this instead of
     * combat level heuristics or other ad-hoc checks.
     * 
     * @param npcName the NPC name to check (null-safe)
     * @return true if the NPC is considered a boss, false otherwise
     */
    public static boolean isBoss(@Nullable String npcName) {
        if (npcName == null) {
            return false;
        }
        boolean result = BOSS_NAMES.contains(npcName);
        log.trace("isBoss check: name={}, result={}", npcName, result);
        return result;
    }

    /**
     * Get an unmodifiable view of all known boss names.
     * 
     * <p>Useful for debugging or displaying available bosses.
     * 
     * @return unmodifiable set of boss names
     */
    public static Set<String> getBossNames() {
        return Collections.unmodifiableSet(BOSS_NAMES);
    }
}

