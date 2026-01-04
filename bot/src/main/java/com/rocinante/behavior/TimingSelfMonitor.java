package com.rocinante.behavior;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Self-monitoring system that detects if the bot's timing patterns are too consistent
 * (machine-like) or too erratic, and adjusts behavior to maintain human-like variance.
 * 
 * <h2>The Problem</h2>
 * <p>Even with proper distributions, timing can drift toward mechanical patterns due to:
 * <ul>
 *   <li>Implementation bugs that reduce variance</li>
 *   <li>Edge cases that bypass humanization</li>
 *   <li>System load affecting timing consistency</li>
 *   <li>Accumulated state that shifts distributions</li>
 * </ul>
 * 
 * <h2>Detection Approach</h2>
 * <p>Uses Coefficient of Variation (CV = σ/μ) as the primary metric:
 * <ul>
 *   <li><b>CV &lt; 0.15:</b> Timing too consistent (mechanical) - increase variance</li>
 *   <li><b>CV 0.15-0.80:</b> Normal human range - no action needed</li>
 *   <li><b>CV &gt; 1.5:</b> Timing too erratic - may indicate issues</li>
 * </ul>
 * 
 * <h2>Timing Categories</h2>
 * <p>Tracks different timing types separately since they have different expected CV ranges:
 * <ul>
 *   <li><b>Reaction times:</b> Ex-Gaussian, CV typically 0.25-0.50</li>
 *   <li><b>Action intervals:</b> Log-normal, CV typically 0.30-0.60</li>
 *   <li><b>Click durations:</b> Narrower range, CV typically 0.20-0.40</li>
 * </ul>
 * 
 * <h2>Response Actions</h2>
 * <p>When anomalies are detected:
 * <ul>
 *   <li>Log security warnings for monitoring</li>
 *   <li>Increase internal variance boost (exposed via {@link #getVarianceMultiplier()})</li>
 *   <li>Track anomaly frequency for trend analysis</li>
 * </ul>
 * 
 * <p>Consumers (HumanTimer, etc.) should call {@link #getVarianceMultiplier()} when generating
 * timing values and apply it to increase variance when timing is too consistent.
 */
@Slf4j
@Singleton
public class TimingSelfMonitor {

    // ========================================================================
    // Configuration Constants
    // ========================================================================
    
    /**
     * Number of samples to collect before running analysis.
     * Needs enough samples for statistical significance.
     */
    private static final int SAMPLE_SIZE = 100;
    
    /**
     * Minimum samples required for meaningful analysis.
     */
    private static final int MIN_SAMPLES_FOR_ANALYSIS = 50;
    
    /**
     * How often to run analysis (every N samples).
     */
    private static final int ANALYSIS_INTERVAL = 50;
    
    /**
     * Variance boost amount when timing is too consistent.
     * Applied multiplicatively to PerformanceState variance.
     */
    private static final double VARIANCE_BOOST_AMOUNT = 0.15;
    
    /**
     * Maximum accumulated variance boost to prevent runaway.
     */
    private static final double MAX_VARIANCE_BOOST = 0.50;
    
    /**
     * Number of consecutive normal checks before reducing boost.
     */
    private static final int NORMAL_CHECKS_TO_REDUCE_BOOST = 5;

    // ========================================================================
    // Timing Category Definitions
    // ========================================================================
    
    /**
     * Categories of timing to track separately.
     */
    public enum TimingCategory {
        /** Reaction times (responding to events) - expected CV: 0.25-0.50 */
        REACTION(0.18, 1.2),
        
        /** Inter-action intervals (time between clicks/actions) - expected CV: 0.30-0.60 */
        ACTION_INTERVAL(0.20, 1.3),
        
        /** Click hold durations - expected CV: 0.20-0.40 */
        CLICK_DURATION(0.15, 0.80),
        
        /** Tick jitter delays - expected CV: 0.35-0.70 */
        TICK_JITTER(0.25, 1.0);
        
        @Getter
        private final double minCV;
        @Getter 
        private final double maxCV;
        
        TimingCategory(double minCV, double maxCV) {
            this.minCV = minCV;
            this.maxCV = maxCV;
        }
    }

    // ========================================================================
    // Sample Buffers (Circular Arrays)
    // ========================================================================
    
    /**
     * Circular buffer for each timing category.
     * Using primitive arrays for efficiency.
     */
    private final long[][] sampleBuffers;
    private final int[] sampleIndices;
    private final int[] sampleCounts;
    private final AtomicInteger[] totalSampleCounts;
    
    // ========================================================================
    // State
    // ========================================================================
    
    /**
     * Current variance boost being applied (0.0 = no boost).
     * Consumers (HumanTimer, etc.) access this via getVarianceMultiplier().
     */
    @Getter
    private volatile double currentVarianceBoost = 0.0;
    
    /**
     * Consecutive normal checks (for reducing boost).
     */
    private final AtomicInteger consecutiveNormalChecks = new AtomicInteger(0);
    
    /**
     * Total anomalies detected (for trend monitoring).
     */
    @Getter
    private final AtomicLong totalAnomaliesDetected = new AtomicLong(0);
    
    /**
     * Whether monitoring is enabled.
     */
    @Getter
    private volatile boolean enabled = true;

    // ========================================================================
    // Construction
    // ========================================================================
    
    @Inject
    public TimingSelfMonitor() {
        int categoryCount = TimingCategory.values().length;
        this.sampleBuffers = new long[categoryCount][SAMPLE_SIZE];
        this.sampleIndices = new int[categoryCount];
        this.sampleCounts = new int[categoryCount];
        this.totalSampleCounts = new AtomicInteger[categoryCount];
        
        for (int i = 0; i < categoryCount; i++) {
            totalSampleCounts[i] = new AtomicInteger(0);
        }
        
        log.info("TimingSelfMonitor initialized with {} categories, {} sample buffer",
                categoryCount, SAMPLE_SIZE);
    }

    // ========================================================================
    // Sample Recording
    // ========================================================================
    
    /**
     * Record a timing sample for the specified category.
     * 
     * <p>Thread-safe via synchronized buffer access. Automatically triggers
     * analysis after ANALYSIS_INTERVAL samples.
     * 
     * @param category the timing category
     * @param timingMs the timing value in milliseconds
     */
    public void recordSample(TimingCategory category, long timingMs) {
        if (!enabled || timingMs <= 0) {
            return;
        }
        
        int catIndex = category.ordinal();
        
        synchronized (sampleBuffers[catIndex]) {
            // Add to circular buffer
            sampleBuffers[catIndex][sampleIndices[catIndex]] = timingMs;
            sampleIndices[catIndex] = (sampleIndices[catIndex] + 1) % SAMPLE_SIZE;
            
            if (sampleCounts[catIndex] < SAMPLE_SIZE) {
                sampleCounts[catIndex]++;
            }
        }
        
        // Check if we should run analysis
        int totalCount = totalSampleCounts[catIndex].incrementAndGet();
        if (totalCount % ANALYSIS_INTERVAL == 0) {
            analyzeCategory(category);
        }
    }
    
    /**
     * Convenience method for recording reaction time samples.
     */
    public void recordReactionTime(long timingMs) {
        recordSample(TimingCategory.REACTION, timingMs);
    }
    
    /**
     * Convenience method for recording action interval samples.
     */
    public void recordActionInterval(long timingMs) {
        recordSample(TimingCategory.ACTION_INTERVAL, timingMs);
    }
    
    /**
     * Convenience method for recording click duration samples.
     */
    public void recordClickDuration(long timingMs) {
        recordSample(TimingCategory.CLICK_DURATION, timingMs);
    }
    
    /**
     * Convenience method for recording tick jitter samples.
     */
    public void recordTickJitter(long timingMs) {
        recordSample(TimingCategory.TICK_JITTER, timingMs);
    }

    // ========================================================================
    // Analysis
    // ========================================================================
    
    /**
     * Analyze timing for a specific category.
     */
    private void analyzeCategory(TimingCategory category) {
        int catIndex = category.ordinal();
        int count;
        double mean, variance;
        
        synchronized (sampleBuffers[catIndex]) {
            count = sampleCounts[catIndex];
            if (count < MIN_SAMPLES_FOR_ANALYSIS) {
                return;
            }
            
            // Calculate mean
            long sum = 0;
            for (int i = 0; i < count; i++) {
                sum += sampleBuffers[catIndex][i];
            }
            mean = (double) sum / count;
            
            // Calculate variance
            double sumSquaredDiff = 0;
            for (int i = 0; i < count; i++) {
                double diff = sampleBuffers[catIndex][i] - mean;
                sumSquaredDiff += diff * diff;
            }
            variance = sumSquaredDiff / count;
        }
        
        if (mean <= 0) {
            return;
        }
        
        // Calculate Coefficient of Variation
        double stdDev = Math.sqrt(variance);
        double cv = stdDev / mean;
        
        // Check thresholds
        checkTimingAnomaly(category, cv, mean, stdDev, count);
    }
    
    /**
     * Check for timing anomalies and respond.
     */
    private void checkTimingAnomaly(TimingCategory category, double cv, 
                                     double mean, double stdDev, int sampleCount) {
        
        boolean tooConsistent = cv < category.getMinCV();
        boolean tooErratic = cv > category.getMaxCV();
        
        if (tooConsistent) {
            totalAnomaliesDetected.incrementAndGet();
            consecutiveNormalChecks.set(0);
            
            log.warn("[SECURITY] {} timing too consistent: CV={} (min expected: {}), " +
                     "mean={}ms, stdDev={}ms, samples={}",
                    category, String.format("%.3f", cv), 
                    String.format("%.2f", category.getMinCV()),
                    String.format("%.1f", mean), 
                    String.format("%.1f", stdDev), 
                    sampleCount);
            
            // Increase variance boost
            increaseVarianceBoost();
            
        } else if (tooErratic) {
            totalAnomaliesDetected.incrementAndGet();
            
            log.warn("[SECURITY] {} timing too erratic: CV={} (max expected: {}), " +
                     "mean={}ms, stdDev={}ms, samples={} - may indicate lag or issues",
                    category, String.format("%.3f", cv),
                    String.format("%.2f", category.getMaxCV()),
                    String.format("%.1f", mean),
                    String.format("%.1f", stdDev),
                    sampleCount);
            // Don't increase boost for erratic timing - that's a different problem
            
        } else {
            // Normal timing
            int normalCount = consecutiveNormalChecks.incrementAndGet();
            
            if (normalCount >= NORMAL_CHECKS_TO_REDUCE_BOOST && currentVarianceBoost > 0) {
                reduceVarianceBoost();
            }
            
            log.debug("{} timing normal: CV={}, mean={}ms, stdDev={}ms",
                    category, String.format("%.3f", cv),
                    String.format("%.1f", mean),
                    String.format("%.1f", stdDev));
        }
    }

    // ========================================================================
    // Variance Adjustment
    // ========================================================================
    
    /**
     * Increase variance boost when timing is too consistent.
     */
    private void increaseVarianceBoost() {
        double newBoost = Math.min(currentVarianceBoost + VARIANCE_BOOST_AMOUNT, MAX_VARIANCE_BOOST);
        
        if (newBoost != currentVarianceBoost) {
            currentVarianceBoost = newBoost;
            log.info("[SECURITY] Increased variance boost to {} to counter mechanical timing", 
                    String.format("%.2f", newBoost));
        }
    }
    
    /**
     * Reduce variance boost after sustained normal timing.
     */
    private void reduceVarianceBoost() {
        double newBoost = Math.max(currentVarianceBoost - (VARIANCE_BOOST_AMOUNT / 2), 0.0);
        
        if (newBoost != currentVarianceBoost) {
            currentVarianceBoost = newBoost;
            consecutiveNormalChecks.set(0);
            
            if (newBoost > 0) {
                log.info("[SECURITY] Reduced variance boost to {} after normal timing",
                        String.format("%.2f", newBoost));
            } else {
                log.info("[SECURITY] Removed variance boost - timing normalized");
            }
        }
    }
    
    /**
     * Get the variance multiplier to apply to timing distributions.
     * 
     * <p>Values greater than 1.0 increase variance. Use this when generating
     * timing values to add extra variance when needed.
     * 
     * @return multiplier for variance (1.0 = no change, 1.15 = 15% more variance)
     */
    public double getVarianceMultiplier() {
        return 1.0 + currentVarianceBoost;
    }

    // ========================================================================
    // Analysis Utilities
    // ========================================================================
    
    /**
     * Run analysis on all categories (for manual/periodic checks).
     */
    public void analyzeAll() {
        for (TimingCategory category : TimingCategory.values()) {
            analyzeCategory(category);
        }
    }
    
    /**
     * Get current statistics for a category.
     * 
     * @param category the timing category
     * @return stats object or null if insufficient samples
     */
    @Nullable
    public TimingStats getStats(TimingCategory category) {
        int catIndex = category.ordinal();
        
        synchronized (sampleBuffers[catIndex]) {
            int count = sampleCounts[catIndex];
            if (count < MIN_SAMPLES_FOR_ANALYSIS) {
                return null;
            }
            
            // Calculate stats
            long sum = 0;
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            
            for (int i = 0; i < count; i++) {
                long val = sampleBuffers[catIndex][i];
                sum += val;
                min = Math.min(min, val);
                max = Math.max(max, val);
            }
            
            double mean = (double) sum / count;
            
            double sumSquaredDiff = 0;
            for (int i = 0; i < count; i++) {
                double diff = sampleBuffers[catIndex][i] - mean;
                sumSquaredDiff += diff * diff;
            }
            double variance = sumSquaredDiff / count;
            double stdDev = Math.sqrt(variance);
            double cv = mean > 0 ? stdDev / mean : 0;
            
            return new TimingStats(category, count, mean, stdDev, cv, min, max);
        }
    }
    
    /**
     * Statistics for a timing category.
     */
    public static class TimingStats {
        @Getter private final TimingCategory category;
        @Getter private final int sampleCount;
        @Getter private final double mean;
        @Getter private final double stdDev;
        @Getter private final double coefficientOfVariation;
        @Getter private final long min;
        @Getter private final long max;
        
        public TimingStats(TimingCategory category, int sampleCount, double mean, 
                          double stdDev, double cv, long min, long max) {
            this.category = category;
            this.sampleCount = sampleCount;
            this.mean = mean;
            this.stdDev = stdDev;
            this.coefficientOfVariation = cv;
            this.min = min;
            this.max = max;
        }
        
        public boolean isTooConsistent() {
            return coefficientOfVariation < category.getMinCV();
        }
        
        public boolean isTooErratic() {
            return coefficientOfVariation > category.getMaxCV();
        }
        
        public boolean isNormal() {
            return !isTooConsistent() && !isTooErratic();
        }
        
        @Override
        public String toString() {
            String status = isTooConsistent() ? "TOO_CONSISTENT" : 
                           isTooErratic() ? "TOO_ERRATIC" : "NORMAL";
            return String.format("%s: CV=%.3f (%s), mean=%.1fms, stdDev=%.1fms, n=%d",
                    category, coefficientOfVariation, status, mean, stdDev, sampleCount);
        }
    }

    // ========================================================================
    // Control
    // ========================================================================
    
    /**
     * Enable or disable monitoring.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("TimingSelfMonitor {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Reset all sample buffers and state.
     */
    public void reset() {
        for (int i = 0; i < sampleBuffers.length; i++) {
            synchronized (sampleBuffers[i]) {
                sampleIndices[i] = 0;
                sampleCounts[i] = 0;
            }
            totalSampleCounts[i].set(0);
        }
        currentVarianceBoost = 0.0;
        consecutiveNormalChecks.set(0);
        log.info("TimingSelfMonitor reset");
    }
    
    /**
     * Get summary of all category stats for logging/debugging.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder("TimingSelfMonitor Summary:\n");
        sb.append(String.format("  Variance Boost: %.2f\n", currentVarianceBoost));
        sb.append(String.format("  Total Anomalies: %d\n", totalAnomaliesDetected.get()));
        
        for (TimingCategory category : TimingCategory.values()) {
            TimingStats stats = getStats(category);
            if (stats != null) {
                sb.append(String.format("  %s\n", stats));
            } else {
                sb.append(String.format("  %s: Insufficient samples\n", category));
            }
        }
        
        return sb.toString();
    }
}
