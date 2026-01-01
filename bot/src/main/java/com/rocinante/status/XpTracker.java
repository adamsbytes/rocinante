package com.rocinante.status;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks XP gains per skill during a session.
 * 
 * Subscribes to StatChanged events from RuneLite to detect XP changes
 * and maintains running totals for the current session.
 * 
 * Thread-safe for concurrent access from status publisher.
 */
@Slf4j
@Singleton
public class XpTracker {

    private final Client client;

    /**
     * Starting XP values when session began (or when tracking started).
     * Used to calculate session XP gains.
     */
    private final Map<Skill, Integer> startingXp = new ConcurrentHashMap<>();

    /**
     * Current XP values (updated on each StatChanged event).
     */
    private final Map<Skill, Integer> currentXp = new ConcurrentHashMap<>();

    /**
     * XP gained this session per skill.
     */
    private final Map<Skill, Integer> sessionXpGained = new ConcurrentHashMap<>();

    /**
     * Timestamp when session tracking started.
     */
    @Getter
    private volatile Instant sessionStartTime;

    /**
     * Total XP gained across all skills this session.
     */
    @Getter
    private volatile long totalSessionXp = 0;

    /**
     * Whether tracking is currently active.
     */
    @Getter
    private volatile boolean tracking = false;

    @Inject
    public XpTracker(Client client) {
        this.client = client;
        log.info("XpTracker initialized");
    }

    /**
     * Minimum Hitpoints XP for valid data. Level 10 HP = 1154 XP.
     * All accounts start with 10 HP, so any value below this indicates
     * the game hasn't finished loading skill data yet.
     */
    private static final int MIN_VALID_HITPOINTS_XP = 1154;

    /**
     * Start tracking XP for a new session.
     * Captures current XP values as the baseline.
     */
    public void startSession() {
        if (tracking) {
            log.warn("Session already started, ignoring startSession call");
            return;
        }

        // Validate that skill data has loaded by checking Hitpoints XP
        // All accounts start at level 10 HP (1154 XP minimum)
        int hitpointsXp;
        try {
            hitpointsXp = client.getSkillExperience(Skill.HITPOINTS);
        } catch (Exception e) {
            log.warn("Cannot start XP tracking: failed to read Hitpoints XP - {}", e.getMessage());
            return;
        }

        if (hitpointsXp < MIN_VALID_HITPOINTS_XP) {
            log.warn("Cannot start XP tracking: Hitpoints XP ({}) below minimum ({}). " +
                    "Game data not yet loaded.", hitpointsXp, MIN_VALID_HITPOINTS_XP);
            return;
        }

        sessionStartTime = Instant.now();
        startingXp.clear();
        currentXp.clear();
        sessionXpGained.clear();
        totalSessionXp = 0;

        // Capture starting XP for all skills
        for (Skill skill : Skill.values()) {
            if (skill == Skill.OVERALL) {
                continue; // Skip overall, we calculate it
            }
            try {
                int xp = client.getSkillExperience(skill);
                startingXp.put(skill, xp);
                currentXp.put(skill, xp);
                sessionXpGained.put(skill, 0);
            } catch (Exception e) {
                log.debug("Could not get XP for {}: {}", skill, e.getMessage());
            }
        }

        tracking = true;
        log.info("XP tracking started with {} skills captured (HP XP: {})", startingXp.size(), hitpointsXp);
    }

    /**
     * Stop tracking XP for the current session.
     * Preserves the final XP values for reporting.
     */
    public void endSession() {
        if (!tracking) {
            return;
        }

        tracking = false;
        log.info("XP tracking ended. Total XP gained: {}", totalSessionXp);
        
        // Log per-skill breakdown
        sessionXpGained.forEach((skill, xp) -> {
            if (xp > 0) {
                log.info("  {} XP gained: {}", skill.getName(), xp);
            }
        });
    }

    /**
     * Handle stat change events from RuneLite.
     * Updates XP tracking when skills change.
     */
    @Subscribe
    public void onStatChanged(StatChanged event) {
        if (!tracking) {
            return;
        }

        Skill skill = event.getSkill();
        if (skill == Skill.OVERALL) {
            return;
        }

        int newXp = event.getXp();
        Integer oldXp = currentXp.get(skill);

        if (oldXp == null) {
            // First time seeing this skill, set baseline
            startingXp.put(skill, newXp);
            currentXp.put(skill, newXp);
            sessionXpGained.put(skill, 0);
            return;
        }

        if (newXp > oldXp) {
            int xpGain = newXp - oldXp;
            currentXp.put(skill, newXp);

            // Update session XP gained
            Integer starting = startingXp.get(skill);
            if (starting != null) {
                int totalGained = newXp - starting;
                sessionXpGained.put(skill, totalGained);
            }

            // Update total
            totalSessionXp += xpGain;

            log.debug("{} XP: +{} (session total: {})", 
                    skill.getName(), xpGain, sessionXpGained.get(skill));
        }
    }

    /**
     * Get XP gained for a specific skill this session.
     * 
     * @param skill the skill to query
     * @return XP gained, or 0 if not tracked
     */
    public int getXpGained(Skill skill) {
        return sessionXpGained.getOrDefault(skill, 0);
    }

    /**
     * Get current XP for a specific skill.
     * 
     * @param skill the skill to query
     * @return current XP, or -1 if not tracked
     */
    public int getCurrentXp(Skill skill) {
        return currentXp.getOrDefault(skill, -1);
    }

    /**
     * Get current level for a specific skill.
     * 
     * @param skill the skill to query
     * @return current level
     */
    public int getCurrentLevel(Skill skill) {
        try {
            return client.getRealSkillLevel(skill);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Get boosted level for a specific skill.
     * 
     * @param skill the skill to query
     * @return boosted level
     */
    public int getBoostedLevel(Skill skill) {
        try {
            return client.getBoostedSkillLevel(skill);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Get all XP gained this session as an immutable map.
     * 
     * @return map of skill to XP gained
     */
    public Map<Skill, Integer> getAllXpGained() {
        return Collections.unmodifiableMap(new EnumMap<>(sessionXpGained));
    }

    /**
     * Get all current XP values as an immutable map.
     * 
     * @return map of skill to current XP
     */
    public Map<Skill, Integer> getAllCurrentXp() {
        return Collections.unmodifiableMap(new EnumMap<>(currentXp));
    }

    /**
     * Get XP gained per hour for a specific skill.
     * 
     * @param skill the skill to query
     * @return XP per hour, or 0 if session just started
     */
    public double getXpPerHour(Skill skill) {
        if (sessionStartTime == null) {
            return 0;
        }

        long elapsedMs = Instant.now().toEpochMilli() - sessionStartTime.toEpochMilli();
        if (elapsedMs < 1000) {
            return 0; // Avoid division by very small numbers
        }

        int xpGained = getXpGained(skill);
        double hours = elapsedMs / 3600000.0;
        return xpGained / hours;
    }

    /**
     * Get total XP per hour across all skills.
     * 
     * @return total XP per hour
     */
    public double getTotalXpPerHour() {
        if (sessionStartTime == null) {
            return 0;
        }

        long elapsedMs = Instant.now().toEpochMilli() - sessionStartTime.toEpochMilli();
        if (elapsedMs < 1000) {
            return 0;
        }

        double hours = elapsedMs / 3600000.0;
        return totalSessionXp / hours;
    }

    /**
     * Get the session duration in milliseconds.
     * 
     * @return session duration, or 0 if not started
     */
    public long getSessionDurationMs() {
        if (sessionStartTime == null) {
            return 0;
        }
        return Instant.now().toEpochMilli() - sessionStartTime.toEpochMilli();
    }

    /**
     * Reset tracking state without ending the session.
     * Used for testing or when client state becomes invalid.
     */
    public void reset() {
        tracking = false;
        sessionStartTime = null;
        startingXp.clear();
        currentXp.clear();
        sessionXpGained.clear();
        totalSessionXp = 0;
        log.debug("XpTracker reset");
    }

    /**
     * Get a summary of tracking state.
     * 
     * @return summary string
     */
    public String getSummary() {
        if (!tracking) {
            return "XpTracker[inactive]";
        }
        
        long skillsWithGains = sessionXpGained.values().stream()
                .filter(xp -> xp > 0)
                .count();
        
        return String.format(
                "XpTracker[tracking=%s, totalXp=%d, skills=%d, xp/hr=%.0f]",
                tracking, totalSessionXp, skillsWithGains, getTotalXpPerHour()
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

