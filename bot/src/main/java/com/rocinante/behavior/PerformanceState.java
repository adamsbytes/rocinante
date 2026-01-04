package com.rocinante.behavior;

import com.rocinante.util.Randomization;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Real-time performance state combining static identity with dynamic modifiers.
 * 
 * <p>This class solves the "static fingerprint" detection problem by adding realistic
 * day-to-day and hour-to-hour variation to motor characteristics. Without this,
 * a bot's behavioral fingerprint (reaction time distribution, mouse path characteristics)
 * remains too consistent across sessions, making it detectable via statistical analysis.
 * 
 * <h2>Design Philosophy: Correlated Variation</h2>
 * <p>Real human variation is CORRELATED - when you're having a bad day, you're worse at
 * everything, not randomly good at some things and bad at others. This class applies a
 * single performance modifier chain to ALL motor traits, creating realistic "good day / bad day"
 * effects without looking intentionally random.
 * 
 * <h2>Performance Modifier Chain</h2>
 * <pre>
 *   effectiveTiming = baseTiming / performanceModifier
 *   performanceModifier = dailyPerformance × circadianModifier
 * </pre>
 * 
 * <h2>Daily Performance (Asymmetric AR(1) Autocorrelation)</h2>
 * <p>Real human performance has temporal autocorrelation with ASYMMETRIC dynamics:
 * <ul>
 *   <li><b>Degradation is fast:</b> One bad night = immediate impact</li>
 *   <li><b>Recovery is slow:</b> Takes 2-3 good nights to fully bounce back</li>
 * </ul>
 * 
 * <p>This class uses an asymmetric AR(1) process:
 * <pre>
 *   if degrading: today = 0.4 × yesterday + 0.6 × innovation  (fast crash)
 *   if recovering: today = 0.7 × yesterday + 0.3 × innovation (slow recovery)
 * </pre>
 * 
 * <p>This models real physiology where fatigue accumulates rapidly but dissipates slowly.
 * A player performing well can crash quickly from one bad night, but recovering from
 * a slump requires sustained good conditions over several days.
 * 
 * <h2>Circadian Modulation</h2>
 * <p>Uses {@link PlayerProfile#getCircadianPerformanceMultiplier(int)} which accounts for
 * chronotype (EARLY_BIRD, NIGHT_OWL, NEUTRAL) and personal peak hour offset.
 * 
 * <h2>Usage</h2>
 * <p>Replace direct profile access with effective values from this class:
 * <pre>
 *   // OLD (static fingerprint):
 *   double fittsB = playerProfile.getFittsB();
 *   
 *   // NEW (dynamic fingerprint):
 *   double fittsB = performanceState.getEffectiveFittsB();
 * </pre>
 * 
 * @see PlayerProfile for base trait values
 */
@Slf4j
@Singleton
public class PerformanceState {
    
    // ========================================================================
    // Daily Performance Configuration
    // ========================================================================
    
    /**
     * Minimum time gap (hours) that triggers daily state regeneration.
     * If the player hasn't played for this long, treat it as a "new day" even
     * if it's the same calendar date.
     */
    private static final double SESSION_GAP_HOURS_THRESHOLD = 6.0;
    
    /**
     * Standard deviation for daily performance Gaussian innovation.
     * Results in ~68% of NEW random component within ±0.08 of mean (1.0).
     * Higher than before since AR(1) dampens the effect.
     */
    private static final double DAILY_PERFORMANCE_INNOVATION_STDDEV = 0.08;
    
    /**
     * Minimum daily performance (really bad day).
     */
    private static final double DAILY_PERFORMANCE_MIN = 0.85;
    
    /**
     * Maximum daily performance (really good day).
     */
    private static final double DAILY_PERFORMANCE_MAX = 1.15;
    
    /**
     * AR(1) coefficient when performance is DEGRADING (innovation pulls downward).
     * 
     * <p>Lower coefficient = less carry-over = faster change toward the innovation.
     * One bad night has immediate impact - degradation is rapid.
     * 
     * <p>With coefficient 0.4:
     * <ul>
     *   <li>Only 40% of yesterday's good performance carries forward</li>
     *   <li>Bad influences take effect quickly (1-2 days to hit bottom)</li>
     * </ul>
     */
    private static final double AR_COEFFICIENT_DEGRADING = 0.4;
    
    /**
     * AR(1) coefficient when performance is RECOVERING (innovation pulls upward).
     * 
     * <p>Higher coefficient = more carry-over = slower change toward the innovation.
     * Recovery is slower - takes 2-3 good nights to fully bounce back.
     * 
     * <p>With coefficient 0.7:
     * <ul>
     *   <li>70% of yesterday's bad performance carries forward</li>
     *   <li>Recovery is gradual (3-5 days to fully recover)</li>
     *   <li>Models real physiology: fatigue accumulates fast, dissipates slowly</li>
     * </ul>
     */
    private static final double AR_COEFFICIENT_RECOVERING = 0.7;
    
    // ========================================================================
    // Dependencies (all required - no fallbacks)
    // ========================================================================
    
    private final PlayerProfile playerProfile;
    
    // ========================================================================
    // State
    // ========================================================================
    
    /**
     * Current daily performance factor (0.85-1.15).
     * Higher = better performance (faster, more precise).
     */
    private volatile double dailyPerformance = 1.0;
    
    /**
     * Date when daily performance was last regenerated.
     */
    private volatile LocalDate dailyStateDate;
    
    /**
     * Whether the performance state has been initialized.
     */
    private volatile boolean initialized = false;
    
    /**
     * Current task type for motor learning context.
     * Set via setCurrentTaskType() when a task begins executing.
     * Null when no task is active or task type is unknown.
     */
    private volatile String currentTaskType = null;
    
    // ========================================================================
    // Constructor
    // ========================================================================
    
    @Inject
    public PerformanceState(PlayerProfile playerProfile) {
        this.playerProfile = Objects.requireNonNull(playerProfile, "PlayerProfile is required");
        log.info("PerformanceState created (awaiting initialization)");
    }
    
    // ========================================================================
    // Initialization
    // ========================================================================
    
    /**
     * Initialize performance state for a new session.
     * Must be called after PlayerProfile is loaded.
     * 
     * @throws IllegalStateException if PlayerProfile is not loaded
     */
    public void initializeSession() {
        if (!playerProfile.isLoaded()) {
            throw new IllegalStateException("Cannot initialize PerformanceState: PlayerProfile not loaded");
        }
        
        // Check if we need to regenerate daily state
        LocalDate today = LocalDate.now();
        boolean needsRegeneration = false;
        
        if (dailyStateDate == null || !dailyStateDate.equals(today)) {
            // New day or first initialization
            needsRegeneration = true;
            log.info("Daily state regeneration: new day (was: {}, now: {})", dailyStateDate, today);
        } else if (shouldRegenerateFromSessionGap()) {
            // Same day but long gap - treat as new "mental day"
            needsRegeneration = true;
            log.info("Daily state regeneration: session gap > {}h", SESSION_GAP_HOURS_THRESHOLD);
        }
        
        if (needsRegeneration) {
            regenerateDailyState();
        }
        
        initialized = true;
        log.info("PerformanceState initialized: dailyPerformance={}, chronotype={}, circadianStrength={}",
                String.format("%.3f", dailyPerformance),
                playerProfile.getChronotype(),
                String.format("%.2f", playerProfile.getCircadianStrength()));
    }
    
    /**
     * Check if we should regenerate daily state based on session gap.
     */
    private boolean shouldRegenerateFromSessionGap() {
        // Get last session start time from profile
        java.time.Instant lastSessionStart = playerProfile.getLastSessionStart();
        if (lastSessionStart == null) {
            return true; // No previous session, regenerate
        }
        
        java.time.Duration gap = java.time.Duration.between(lastSessionStart, java.time.Instant.now());
        double gapHours = gap.toMinutes() / 60.0;
        return gapHours >= SESSION_GAP_HOURS_THRESHOLD;
    }
    
    /**
     * Generate new daily performance state using asymmetric AR(1) autocorrelation.
     * 
     * <p>Real humans don't switch instantly from "good day" to "bad day" at midnight.
     * Performance has temporal autocorrelation with ASYMMETRIC dynamics:
     * <ul>
     *   <li><b>Degradation is fast:</b> One bad night = immediate impact (α = 0.4)</li>
     *   <li><b>Recovery is slow:</b> Takes 2-3 good nights to bounce back (α = 0.7)</li>
     * </ul>
     * 
     * <p>This models real physiology where fatigue accumulates rapidly but dissipates
     * slowly. A player who's been performing well can crash quickly from one bad night,
     * but recovering from a slump takes sustained good conditions.
     * 
     * <p>Asymmetric AR(1): today = α × yesterday + (1-α) × innovation
     * where α depends on whether innovation pulls up (recovering) or down (degrading).
     */
    private void regenerateDailyState() {
        LocalDate today = LocalDate.now();
        double previousPerformance = dailyPerformance; // Store before update
        
        // Generate new random "innovation" (the random component)
        // Uses SecureRandom for unpredictable daily variation
        double innovation = 1.0 + Randomization.getSecureRandom().nextGaussian() * DAILY_PERFORMANCE_INNOVATION_STDDEV;
        
        // Asymmetric AR(1): use different coefficients for degradation vs recovery
        // Degradation (innovation < previous): fast change (low α = 0.4)
        // Recovery (innovation > previous): slow change (high α = 0.7)
        double blended;
        if (dailyStateDate == null) {
            // First ever session - no previous day to blend with
            blended = innovation;
        } else {
            // Choose coefficient based on direction of change
            double arCoefficient;
            if (innovation < previousPerformance) {
                // Degrading: bad influences take effect quickly
                arCoefficient = AR_COEFFICIENT_DEGRADING;
            } else {
                // Recovering: good influences take effect slowly
                arCoefficient = AR_COEFFICIENT_RECOVERING;
            }
            
            // Blend previous day's performance with new innovation
            blended = arCoefficient * previousPerformance + (1.0 - arCoefficient) * innovation;
        }
        
        // Clamp to valid range
        dailyPerformance = Math.max(DAILY_PERFORMANCE_MIN, Math.min(DAILY_PERFORMANCE_MAX, blended));
        
        // Determine direction based on actual change
        String direction = (dailyStateDate == null) ? "init" : 
                          (dailyPerformance < previousPerformance ? "degrading" : "recovering");
        dailyStateDate = today;
        
        log.info("Daily performance: {} → {} ({}, innovation={}, date={})", 
                String.format("%.3f", previousPerformance),
                String.format("%.3f", dailyPerformance),
                direction,
                String.format("%.3f", innovation),
                dailyStateDate);
    }
    
    // ========================================================================
    // Performance Modifier Calculation
    // ========================================================================
    
    /**
     * Get the combined performance modifier for the current moment.
     * 
     * <p>This is the KEY to realistic variation:
     * <ul>
     *   <li>All traits share the SAME modifier chain</li>
     *   <li>Creates correlated "good day" / "bad day" effects</li>
     *   <li>Doesn't look intentionally random because it follows real patterns</li>
     * </ul>
     * 
     * @return performance modifier (typically 0.75-1.20)
     * @throws IllegalStateException if not initialized
     */
    public double getPerformanceModifier() {
        requireInitialized();
        
        // 1. Daily state (0.85-1.15) - "some days you're just off"
        double daily = dailyPerformance;
        
        // 2. Circadian rhythm (0.5-1.0 depending on circadianStrength)
        // Time-of-day effects based on chronotype
        int hour = LocalTime.now().getHour();
        double circadian = playerProfile.getCircadianPerformanceMultiplier(hour);
        
        // Combined: multiply all factors
        // Range: ~0.43 (worst: 0.85 * 0.5) to ~1.15 (best: 1.15 * 1.0)
        return daily * circadian;
    }
    
    /**
     * Get the current daily performance factor only (without circadian).
     * Useful for debugging and logging.
     * 
     * @return daily performance (0.85-1.15)
     */
    public double getDailyPerformance() {
        requireInitialized();
        return dailyPerformance;
    }
    
    /**
     * Get the current circadian modifier only (without daily).
     * Useful for debugging and logging.
     * 
     * @return circadian modifier (0.5-1.0 typically)
     */
    public double getCircadianModifier() {
        requireInitialized();
        int hour = LocalTime.now().getHour();
        return playerProfile.getCircadianPerformanceMultiplier(hour);
    }
    
    // ========================================================================
    // Motor Learning Integration
    // ========================================================================
    
    /**
     * Set the current task type for motor learning context.
     * 
     * <p>Call this when a task begins execution. The motor learning proficiency
     * for this task type will then be included in performance calculations.
     * 
     * @param taskType the task type identifier (e.g., "WOODCUTTING", "COMBAT", "BANKING")
     *                 or null to clear
     */
    public void setCurrentTaskType(String taskType) {
        this.currentTaskType = taskType != null ? taskType.toUpperCase() : null;
    }
    
    /**
     * Get the current task type.
     * 
     * @return current task type or null if not set
     */
    public String getCurrentTaskType() {
        return currentTaskType;
    }
    
    /**
     * Get the motor learning multiplier for the current task.
     * 
     * <p>This is a value < 1.0 indicating improved performance from practice.
     * Players who have practiced a task extensively will be faster at it.
     * 
     * <p>Includes skill transfer: related tasks and general gaming skill
     * also contribute to proficiency (see PlayerProfile.getTaskProficiencyMultiplier).
     * 
     * @return motor learning multiplier (0.75-1.0, lower = faster/more proficient)
     */
    public double getMotorLearningMultiplier() {
        requireInitialized();
        if (currentTaskType == null) {
            return 1.0; // No task context, no motor learning bonus
        }
        return playerProfile.getTaskProficiencyMultiplier(currentTaskType);
    }
    
    // NOTE: Motor learning is NOT combined with performance modifier because they
    // work in opposite directions:
    //   - Performance modifier: lower = worse day (divide to get slower)
    //   - Motor learning: lower = better skill (multiply to get faster)
    //
    // The formula for "bad" traits (where lower is faster) is:
    //   effective = base * motorLearning / performance
    //
    // This is applied directly in getEffectiveFitts* methods.
    
    // ========================================================================
    // Effective Motor Trait Getters (Fitts' Law)
    // ========================================================================
    
    /**
     * Get effective Fitts' Law 'a' parameter (base time).
     * 
     * <p>Higher 'a' = more minimum time before movement starts.
     * Bad performance (lower modifier) = higher effective 'a' (slower start).
     * 
     * <p>Includes motor learning: experienced players have faster movement initiation.
     * Motor learning multiplier (0.75-1.0, lower = better) reduces movement time.
     * 
     * <p>Formula: effective = base * motorLearning / performance
     * <ul>
     *   <li>Expert (0.85) on bad day (0.68): 40 * 0.85 / 0.68 = 50ms (learned skill compensates)</li>
     *   <li>Novice (1.0) on good day (1.0): 40 * 1.0 / 1.0 = 40ms (baseline)</li>
     * </ul>
     * 
     * @return effective fittsA with performance and motor learning modulation
     */
    public double getEffectiveFittsA() {
        requireInitialized();
        double base = playerProfile.getFittsA();
        // Motor learning (lower = better) multiplies to reduce time
        // Performance (lower = worse day) divides to increase time
        return base * getMotorLearningMultiplier() / getPerformanceModifier();
    }
    
    /**
     * Get effective Fitts' Law 'b' parameter (difficulty scaling).
     * 
     * <p>Higher 'b' = more time added per unit of difficulty.
     * Bad performance (lower modifier) = higher effective 'b' (slower targeting).
     * 
     * <p>Includes motor learning: experienced players have better target acquisition.
     * This is where motor learning has the biggest impact - practiced players
     * can acquire targets significantly faster regardless of daily variation.
     * 
     * @return effective fittsB with performance and motor learning modulation
     */
    public double getEffectiveFittsB() {
        requireInitialized();
        double base = playerProfile.getFittsB();
        // Same formula as fittsA: motor learning improves, bad day worsens
        return base * getMotorLearningMultiplier() / getPerformanceModifier();
    }
    
    // ========================================================================
    // Effective Motor Trait Getters (Reaction Time / Jitter)
    // ========================================================================
    
    /**
     * Get effective Ex-Gaussian μ (mu) parameter for reaction time.
     * 
     * <p>Higher μ = slower base reaction time.
     * Bad performance = higher effective μ (slower reactions).
     * 
     * @return effective jitterMu with performance modulation (ms)
     */
    public double getEffectiveJitterMu() {
        requireInitialized();
        double base = playerProfile.getJitterMu();
        return base / getPerformanceModifier();
    }
    
    /**
     * Get effective Ex-Gaussian σ (sigma) parameter for reaction time variance.
     * 
     * <p>Higher σ = more inconsistent reactions.
     * Bad performance = higher effective σ (less consistent).
     * 
     * @return effective jitterSigma with performance modulation (ms)
     */
    public double getEffectiveJitterSigma() {
        requireInitialized();
        double base = playerProfile.getJitterSigma();
        return base / getPerformanceModifier();
    }
    
    /**
     * Get effective Ex-Gaussian τ (tau) parameter for reaction time tail.
     * 
     * <p>Higher τ = more occasional long delays (zoning out).
     * Bad performance = higher effective τ (more attention lapses).
     * 
     * @return effective jitterTau with performance modulation (ms)
     */
    public double getEffectiveJitterTau() {
        requireInitialized();
        double base = playerProfile.getJitterTau();
        return base / getPerformanceModifier();
    }
    
    // ========================================================================
    // Effective Motor Trait Getters (Click Timing)
    // ========================================================================
    
    /**
     * Get effective click duration μ (mean click hold time).
     * 
     * <p>Higher = longer clicks.
     * Bad performance = slightly longer click holds.
     * 
     * @return effective clickDurationMu with performance modulation (ms)
     */
    public double getEffectiveClickDurationMu() {
        requireInitialized();
        double base = playerProfile.getClickDurationMu();
        return base / getPerformanceModifier();
    }
    
    /**
     * Get effective click duration σ (variance in click hold time).
     * 
     * <p>Higher = more inconsistent click timing.
     * Bad performance = less consistent clicks.
     * 
     * @return effective clickDurationSigma with performance modulation (ms)
     */
    public double getEffectiveClickDurationSigma() {
        requireInitialized();
        double base = playerProfile.getClickDurationSigma();
        return base / getPerformanceModifier();
    }
    
    /**
     * Get effective click duration τ (tail for occasional long clicks).
     * 
     * @return effective clickDurationTau with performance modulation (ms)
     */
    public double getEffectiveClickDurationTau() {
        requireInitialized();
        double base = playerProfile.getClickDurationTau();
        return base / getPerformanceModifier();
    }
    
    // ========================================================================
    // Effective Motor Trait Getters (Cognitive Delay)
    // ========================================================================
    
    /**
     * Get effective cognitive delay base (thinking time before actions).
     * 
     * <p>Higher = more thinking time.
     * Bad performance = slower cognitive processing.
     * 
     * @return effective cognitiveDelayBase with performance modulation (ms)
     */
    public double getEffectiveCognitiveDelayBase() {
        requireInitialized();
        double base = playerProfile.getCognitiveDelayBase();
        return base / getPerformanceModifier();
    }
    
    /**
     * Get effective cognitive delay variance.
     * This represents how inconsistent the thinking time is.
     * 
     * <p>Note: Variance is inversely related to performance (worse = more variance).
     * 
     * @return effective cognitiveDelayVariance with performance modulation
     */
    public double getEffectiveCognitiveDelayVariance() {
        requireInitialized();
        double base = playerProfile.getCognitiveDelayVariance();
        // Variance increases when performance is bad
        return base / getPerformanceModifier();
    }
    
    // ========================================================================
    // Effective Motor Trait Getters (Movement Quality)
    // ========================================================================
    
    /**
     * Get effective overshoot probability.
     * 
     * <p>Higher = more likely to overshoot target.
     * Bad performance = more overshoots.
     * 
     * <p>Clamped to max 0.40 to prevent unrealistic overshoot rates.
     * 
     * @return effective overshootProbability with performance modulation (0.02-0.40)
     */
    public double getEffectiveOvershootProbability() {
        requireInitialized();
        double base = playerProfile.getOvershootProbability();
        // Overshoots increase when performance is bad
        double effective = base / getPerformanceModifier();
        // Clamp to max 40% - no human overshoots more than this consistently
        return Math.min(effective, 0.40);
    }
    
    /**
     * Get effective wobble amplitude modifier.
     * 
     * <p>Higher = more path wobble/tremor.
     * Bad performance = more wobble.
     * 
     * <p>Clamped to max 2.0 to prevent unrealistic tremor levels.
     * 
     * @return effective wobbleAmplitudeModifier with performance modulation (0.35-2.0)
     */
    public double getEffectiveWobbleAmplitudeModifier() {
        requireInitialized();
        double base = playerProfile.getWobbleAmplitudeModifier();
        double effective = base / getPerformanceModifier();
        // Clamp to max 2.0 - beyond this looks like Parkinson's
        return Math.min(effective, 2.0);
    }
    
    /**
     * Get effective velocity flow.
     * 
     * <p>Higher = lazier, more fluid movements (0.2 = snappy, 0.8 = lazy).
     * Bad performance = more sluggish movements.
     * 
     * <p>Clamped to max 1.0 (valid range is 0.0-1.0).
     * 
     * @return effective velocityFlow with performance modulation (0.2-1.0)
     */
    public double getEffectiveVelocityFlow() {
        requireInitialized();
        double base = playerProfile.getVelocityFlow();
        // Bad performance = more sluggish (higher flow value)
        double effective = base / getPerformanceModifier();
        // Clamp to valid range max
        return Math.min(effective, 1.0);
    }
    
    /**
     * Get effective mouse speed multiplier.
     * 
     * <p>Higher = faster mouse movements.
     * Bad performance = slower movements.
     * 
     * @return effective mouseSpeedMultiplier with performance modulation
     */
    public double getEffectiveMouseSpeedMultiplier() {
        requireInitialized();
        double base = playerProfile.getMouseSpeedMultiplier();
        // Good performance (higher modifier) = faster mouse (keep base or boost)
        // Bad performance (lower modifier) = slower mouse
        return base * getPerformanceModifier();
    }
    
    // ========================================================================
    // Utility
    // ========================================================================
    
    /**
     * Check if performance state is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Throw if not initialized.
     */
    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("PerformanceState not initialized - call initializeSession() first");
        }
    }
    
    /**
     * Get a summary of current performance state.
     */
    public String getSummary() {
        if (!initialized) {
            return "PerformanceState[not initialized]";
        }
        
        int hour = LocalTime.now().getHour();
        return String.format(
                "PerformanceState[daily=%.3f, circadian=%.3f (hour=%d), combined=%.3f, date=%s]",
                dailyPerformance,
                getCircadianModifier(),
                hour,
                getPerformanceModifier(),
                dailyStateDate
        );
    }
    
    @Override
    public String toString() {
        return getSummary();
    }
}
