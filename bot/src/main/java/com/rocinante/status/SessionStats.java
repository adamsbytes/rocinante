package com.rocinante.status;

import com.rocinante.behavior.BreakScheduler;
import com.rocinante.behavior.FatigueModel;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.Skill;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of session statistics.
 * 
 * Aggregates data from various tracking systems:
 * - Runtime from session start time
 * - Break data from BreakScheduler
 * - Action count from FatigueModel
 * - XP data from XpTracker
 */
@Value
@Builder
public class SessionStats {

    /**
     * Empty session stats for when no session is active.
     */
    public static final SessionStats EMPTY = SessionStats.builder()
            .sessionStartTime(null)
            .runtimeMs(0)
            .breakCount(0)
            .totalBreakDurationMs(0)
            .actionCount(0)
            .fatigueLevel(0.0)
            .totalXpGained(0)
            .xpPerHour(0.0)
            .xpGainedBySkill(Map.of())
            .build();

    /**
     * When this session started.
     */
    Instant sessionStartTime;

    /**
     * Total runtime in milliseconds (excluding break time).
     */
    long runtimeMs;

    /**
     * Number of breaks taken this session.
     */
    int breakCount;

    /**
     * Total time spent on breaks in milliseconds.
     */
    long totalBreakDurationMs;

    /**
     * Total actions performed this session.
     */
    long actionCount;

    /**
     * Current fatigue level (0.0 - 1.0).
     */
    double fatigueLevel;

    /**
     * Total XP gained across all skills.
     */
    long totalXpGained;

    /**
     * XP gained per hour rate.
     */
    double xpPerHour;

    /**
     * XP gained per skill this session.
     */
    Map<Skill, Integer> xpGainedBySkill;

    /**
     * Create a SessionStats snapshot from the current tracking state.
     * 
     * @param xpTracker the XP tracker
     * @param fatigueModel the fatigue model (nullable)
     * @param breakScheduler the break scheduler (nullable)
     * @return current session stats
     */
    public static SessionStats capture(
            XpTracker xpTracker,
            FatigueModel fatigueModel,
            BreakScheduler breakScheduler) {
        
        if (xpTracker == null || !xpTracker.isTracking()) {
            return EMPTY;
        }

        Instant startTime = xpTracker.getSessionStartTime();
        long runtimeMs = xpTracker.getSessionDurationMs();
        
        // Get break data
        int breakCount = 0;
        long breakDurationMs = 0;
        if (breakScheduler != null) {
            breakCount = breakScheduler.getBreaksTaken();
            breakDurationMs = breakScheduler.getTotalBreakDuration().toMillis();
        }

        // Get fatigue data
        double fatigue = 0.0;
        long actionCount = 0;
        if (fatigueModel != null) {
            fatigue = fatigueModel.getFatigueLevel();
            actionCount = fatigueModel.getSessionActionCount();
        }

        // Get XP data
        long totalXp = xpTracker.getTotalSessionXp();
        double xpPerHour = xpTracker.getTotalXpPerHour();
        Map<Skill, Integer> xpBySkill = xpTracker.getAllXpGained();

        return SessionStats.builder()
                .sessionStartTime(startTime)
                .runtimeMs(runtimeMs)
                .breakCount(breakCount)
                .totalBreakDurationMs(breakDurationMs)
                .actionCount(actionCount)
                .fatigueLevel(fatigue)
                .totalXpGained(totalXp)
                .xpPerHour(xpPerHour)
                .xpGainedBySkill(xpBySkill)
                .build();
    }

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Get runtime as a Duration.
     * 
     * @return runtime duration
     */
    public Duration getRuntime() {
        return Duration.ofMillis(runtimeMs);
    }

    /**
     * Get total break duration as a Duration.
     * 
     * @return break duration
     */
    public Duration getTotalBreakDuration() {
        return Duration.ofMillis(totalBreakDurationMs);
    }

    /**
     * Get active time (runtime minus breaks).
     * 
     * @return active duration
     */
    public Duration getActiveTime() {
        return Duration.ofMillis(Math.max(0, runtimeMs - totalBreakDurationMs));
    }

    /**
     * Get runtime formatted as HH:MM:SS.
     * 
     * @return formatted runtime string
     */
    public String getFormattedRuntime() {
        return formatDuration(runtimeMs);
    }

    /**
     * Get break duration formatted as HH:MM:SS.
     * 
     * @return formatted break duration string
     */
    public String getFormattedBreakDuration() {
        return formatDuration(totalBreakDurationMs);
    }

    /**
     * Get XP per hour formatted with K/M suffix.
     * 
     * @return formatted XP rate string
     */
    public String getFormattedXpPerHour() {
        return formatNumber((long) xpPerHour);
    }

    /**
     * Get total XP formatted with K/M suffix.
     * 
     * @return formatted XP string
     */
    public String getFormattedTotalXp() {
        return formatNumber(totalXpGained);
    }

    /**
     * Check if the session has meaningful data.
     * 
     * @return true if session is active with data
     */
    public boolean hasData() {
        return sessionStartTime != null && runtimeMs > 0;
    }

    /**
     * Get actions per hour rate.
     * 
     * @return actions per hour
     */
    public double getActionsPerHour() {
        if (runtimeMs < 1000) {
            return 0;
        }
        double hours = runtimeMs / 3600000.0;
        return actionCount / hours;
    }

    /**
     * Get fatigue as a percentage string.
     * 
     * @return fatigue percentage (e.g., "35%")
     */
    public String getFormattedFatigue() {
        return String.format("%.0f%%", fatigueLevel * 100);
    }

    // ========================================================================
    // Formatting Utilities
    // ========================================================================

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        return String.format("%02d:%02d:%02d",
                hours,
                minutes % 60,
                seconds % 60);
    }

    private static String formatNumber(long value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        } else if (value >= 1_000) {
            return String.format("%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    /**
     * Get a summary for logging.
     * 
     * @return summary string
     */
    public String getSummary() {
        if (!hasData()) {
            return "SessionStats[no data]";
        }
        
        return String.format(
                "SessionStats[runtime=%s, breaks=%d, actions=%d, fatigue=%s, xp=%s, xp/hr=%s]",
                getFormattedRuntime(),
                breakCount,
                actionCount,
                getFormattedFatigue(),
                getFormattedTotalXp(),
                getFormattedXpPerHour()
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

