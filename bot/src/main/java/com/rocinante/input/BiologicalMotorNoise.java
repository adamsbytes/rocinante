package com.rocinante.input;

import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.PlayerProfile;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;

/**
 * Biologically-constrained motor noise generator with feedback correction modeling.
 * 
 * Unlike pure pink noise (mathematically ideal 1/f spectrum), this generator
 * models realistic human motor noise including the **visual-motor feedback loop**
 * that makes real error residuals non-ideally "pink":
 * 
 * <h3>Core Model: Feedback Control Loop</h3>
 * Real human motor control is not open-loop additive noise. It's a closed-loop system:
 * <pre>
 *   Visual Cortex -> Motor Cortex -> Muscle -> Movement -> Visual Feedback -> ...
 * </pre>
 * 
 * The feedback loop means:
 * - Errors are detected and corrected
 * - But corrections themselves have noise
 * - Creating "error upon error" that breaks pure 1/f spectrum
 * 
 * This is modeled with:
 * 1. **Base pink noise** (1/f spectrum from muscle fiber recruitment)
 * 2. **Feedback correction noise** (delayed, filtered attempt to correct previous error)
 * 3. **Correction overshoot** (overcorrection creates oscillation)
 * 
 * <h3>Additional Biological Constraints</h3>
 * 
 * 1. **Biological frequency cutoff** - Real muscles can't respond above ~10-12 Hz.
 *    Implemented as a first-order IIR low-pass filter at the player's physTremorFreq.
 * 
 * 2. **Fatigue-scaled amplitude** - Tired muscles have more tremor.
 *    Amplitude scales 1.0x (fresh) to 1.8x (exhausted).
 * 
 * 3. **Movement phase adaptation** - Different phases have different noise characteristics:
 *    - BALLISTIC (0-25%): Fast initial movement, noise suppressed (0.3x)
 *    - CORRECTION (25-75%): Mid-adjustment, moderate noise (0.7x)  
 *    - PRECISION (75-100%): Fine targeting, maximum tremor (1.2x)
 * 
 * 4. **Speed-dependent suppression** - Fast movements mask noise (motion blur effect).
 *    High speed = less visible tremor.
 * 
 * 5. **Hand-dominance correlated axes** - X and Y noise are partially correlated
 *    based on dominant hand. Right-handed players have rightward-biased tremor.
 * 
 * 6. **Per-profile unique characteristics** - Uses PlayerProfile's physTremorFreq,
 *    physTremorAmp, and dominantHandBias for truly unique motor signatures.
 * 
 * <h3>Resulting Error Spectrum</h3>
 * The combination produces error residuals that are NOT pure 1/f:
 * - Low frequencies: ~1/f from underlying pink noise
 * - Mid frequencies: Enhanced by feedback oscillation
 * - High frequencies: Suppressed by muscle bandwidth limit
 * 
 * This passes spectral analysis tests that would catch pure pink noise.
 * 
 * References:
 * - Physiological tremor: 8-12 Hz (Deuschl et al., 1998)
 * - Motor unit firing: 8-25 Hz, summed to ~10 Hz tremor (Elble, 1996)
 * - Fatigue increases tremor amplitude by 50-100% (Vøllestad, 1997)
 * - Visual feedback latency: 100-200ms (Miall et al., 1993)
 */
@Slf4j
public class BiologicalMotorNoise {

    /**
     * Movement phases with different noise characteristics.
     */
    public enum MovementPhase {
        /** Initial fast movement - noise suppressed */
        BALLISTIC(0.3),
        /** Mid-movement adjustment - moderate noise */
        CORRECTION(0.7),
        /** Fine targeting - maximum tremor visible */
        PRECISION(1.2);
        
        private final double amplitudeScale;
        
        MovementPhase(double amplitudeScale) {
            this.amplitudeScale = amplitudeScale;
        }
        
        public double getAmplitudeScale() {
            return amplitudeScale;
        }
    }

    // ========================================================================
    // Dependencies
    // ========================================================================
    
    private final PlayerProfile playerProfile;
    private final FatigueModel fatigueModel;
    private final PinkNoiseGenerator pinkNoiseX;
    private final PinkNoiseGenerator pinkNoiseY;
    private final SecureRandom random;

    // ========================================================================
    // Filter State
    // ========================================================================
    
    /** IIR low-pass filter state for X axis */
    private double lpfStateX = 0.0;
    
    /** IIR low-pass filter state for Y axis */
    private double lpfStateY = 0.0;
    
    /** Last sample time in nanoseconds for accurate timing */
    private long lastSampleTimeNanos = 0;

    // ========================================================================
    // Feedback Correction Loop State
    // ========================================================================
    // Models the visual-motor feedback loop that creates non-ideal error spectrum.
    // Real motor control: error detected -> correction attempted -> correction has noise
    
    /**
     * Visual feedback delay in samples (~100-200ms in real humans).
     * This creates a delay ring buffer for the feedback correction.
     */
    private static final int FEEDBACK_DELAY_SAMPLES = 15; // ~150ms at 100Hz sampling
    
    /**
     * Feedback correction gain (how aggressively errors are corrected).
     * 0.0 = no correction, 1.0 = full correction.
     * Too high causes oscillation (realistic for some players).
     */
    private static final double FEEDBACK_GAIN_BASE = 0.35;
    
    /**
     * Feedback overshoot probability (tendency to overcorrect).
     * Creates oscillation that breaks pure 1/f spectrum.
     */
    private static final double OVERSHOOT_PROBABILITY = 0.15;
    
    /**
     * Maximum overshoot multiplier when overcorrection occurs.
     */
    private static final double MAX_OVERSHOOT_MULTIPLIER = 1.4;
    
    /** Error history ring buffer for X axis (delayed feedback) */
    private final double[] errorHistoryX = new double[FEEDBACK_DELAY_SAMPLES];
    
    /** Error history ring buffer for Y axis (delayed feedback) */
    private final double[] errorHistoryY = new double[FEEDBACK_DELAY_SAMPLES];
    
    /** Current position in ring buffer */
    private int errorHistoryIndex = 0;
    
    /** Accumulated error from last sample (what we're trying to correct) */
    private double accumulatedErrorX = 0.0;
    private double accumulatedErrorY = 0.0;
    
    /** Per-profile feedback characteristics (generated from profile seed) */
    private final double feedbackGain;
    private final double feedbackOscillation;

    // ========================================================================
    // Movement Context
    // ========================================================================
    
    /** Current movement phase */
    private MovementPhase currentPhase = MovementPhase.BALLISTIC;
    
    /** Current movement speed in pixels per millisecond */
    private double currentSpeedPxPerMs = 0.0;
    
    /** Current progress through movement (0.0 to 1.0) */
    private double currentProgress = 0.0;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a biological motor noise generator.
     * 
     * @param playerProfile profile for tremor characteristics
     * @param fatigueModel fatigue model for amplitude scaling
     */
    public BiologicalMotorNoise(PlayerProfile playerProfile, FatigueModel fatigueModel) {
        this.playerProfile = playerProfile;
        this.fatigueModel = fatigueModel;
        this.random = new SecureRandom();
        this.pinkNoiseX = new PinkNoiseGenerator(random);
        this.pinkNoiseY = new PinkNoiseGenerator(random);
        this.lastSampleTimeNanos = System.nanoTime();
        
        // Derive per-profile feedback characteristics from motor correlation
        // Players with high motor correlation have more consistent (less oscillating) feedback
        double motorCorrelation = playerProfile.getMotorSpeedCorrelation();
        this.feedbackGain = FEEDBACK_GAIN_BASE * (0.7 + motorCorrelation * 0.6); // 0.24-0.56
        this.feedbackOscillation = (1.0 - motorCorrelation) * 0.3; // 0.03-0.15
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Set the movement context for adaptive noise generation.
     * Call this before generating noise for a movement.
     * 
     * @param progress movement progress (0.0 = start, 1.0 = end)
     * @param speedPxPerMs current speed in pixels per millisecond
     */
    public void setMovementContext(double progress, double speedPxPerMs) {
        this.currentProgress = Math.max(0.0, Math.min(1.0, progress));
        this.currentSpeedPxPerMs = Math.max(0.0, speedPxPerMs);
        
        // Determine phase from progress
        if (progress < 0.25) {
            this.currentPhase = MovementPhase.BALLISTIC;
        } else if (progress < 0.75) {
            this.currentPhase = MovementPhase.CORRECTION;
        } else {
            this.currentPhase = MovementPhase.PRECISION;
        }
    }

    /**
     * Generate the next noise sample for the X axis.
     * Applies biological filtering and context-aware scaling.
     * 
     * @return noise value in pixels
     */
    public double nextX() {
        double[] xy = next2D();
        return xy[0];
    }

    /**
     * Generate the next noise sample for the Y axis.
     * Applies biological filtering and context-aware scaling.
     * 
     * @return noise value in pixels
     */
    public double nextY() {
        double[] xy = next2D();
        return xy[1];
    }

    /**
     * Generate correlated 2D noise samples with feedback correction modeling.
     * X and Y are partially correlated based on dominant hand bias.
     * 
     * The output is NOT pure pink noise because it includes:
     * 1. Base pink noise (1/f from muscle recruitment)
     * 2. Delayed feedback correction attempt (visual-motor loop)
     * 3. Correction noise (the correction itself is imperfect)
     * 4. Occasional overcorrection (creates mid-frequency oscillation)
     * 
     * @return array of [noiseX, noiseY] in pixels
     */
    public double[] next2D() {
        // Calculate time delta for accurate filtering
        long currentTimeNanos = System.nanoTime();
        double deltaSeconds = (currentTimeNanos - lastSampleTimeNanos) / 1_000_000_000.0;
        lastSampleTimeNanos = currentTimeNanos;
        
        // Clamp delta to reasonable range (avoid huge jumps after pauses)
        deltaSeconds = Math.max(0.001, Math.min(0.1, deltaSeconds));
        
        // Get effective sample rate
        double sampleRateHz = 1.0 / deltaSeconds;
        
        // === Stage 1: Base Pink Noise (muscle fiber recruitment) ===
        double rawX = pinkNoiseX.next();
        double rawY = pinkNoiseY.next();
        
        // Apply hand-dominance correlation
        double[] correlated = applyHandCorrelation(rawX, rawY);
        rawX = correlated[0];
        rawY = correlated[1];
        
        // === Stage 2: Feedback Correction Loop ===
        // The visual-motor system detects accumulated error and attempts to correct it.
        // But the correction has a delay (visual processing) and is itself noisy.
        
        // Get delayed error from ring buffer (simulates visual processing delay)
        int delayedIndex = (errorHistoryIndex + 1) % FEEDBACK_DELAY_SAMPLES;
        double delayedErrorX = errorHistoryX[delayedIndex];
        double delayedErrorY = errorHistoryY[delayedIndex];
        
        // Calculate correction attempt (with noise)
        // The motor system tries to counteract the detected error
        double correctionX = -delayedErrorX * feedbackGain;
        double correctionY = -delayedErrorY * feedbackGain;
        
        // Add noise to the correction (the correction itself is imperfect)
        double correctionNoiseX = pinkNoiseX.next() * 0.3;
        double correctionNoiseY = pinkNoiseY.next() * 0.3;
        correctionX += correctionNoiseX;
        correctionY += correctionNoiseY;
        
        // Occasional overcorrection (creates oscillation that breaks pure 1/f)
        if (random.nextDouble() < OVERSHOOT_PROBABILITY) {
            double overshoot = 1.0 + random.nextDouble() * (MAX_OVERSHOOT_MULTIPLIER - 1.0);
            overshoot += feedbackOscillation * random.nextGaussian();
            correctionX *= overshoot;
            correctionY *= overshoot;
        }
        
        // Combine base noise with correction
        double combinedX = rawX + correctionX;
        double combinedY = rawY + correctionY;
        
        // === Stage 3: Biological Low-Pass Filter ===
        // Muscles can't respond above ~10-12 Hz
        double cutoffHz = playerProfile.getPhysTremorFreq();
        double alpha = calculateLpfAlpha(sampleRateHz, cutoffHz);
        
        double filteredX = alpha * combinedX + (1.0 - alpha) * lpfStateX;
        double filteredY = alpha * combinedY + (1.0 - alpha) * lpfStateY;
        lpfStateX = filteredX;
        lpfStateY = filteredY;
        
        // === Stage 4: Update Error History ===
        // Store current output as "error" for future feedback correction
        // This creates the closed-loop behavior
        accumulatedErrorX = accumulatedErrorX * 0.9 + filteredX; // Leaky integrator
        accumulatedErrorY = accumulatedErrorY * 0.9 + filteredY;
        
        errorHistoryX[errorHistoryIndex] = accumulatedErrorX;
        errorHistoryY[errorHistoryIndex] = accumulatedErrorY;
        errorHistoryIndex = (errorHistoryIndex + 1) % FEEDBACK_DELAY_SAMPLES;
        
        // === Stage 5: Amplitude Scaling ===
        double amplitude = calculateEffectiveAmplitude();
        
        return new double[] {
            filteredX * amplitude,
            filteredY * amplitude
        };
    }

    /**
     * Reset the generator state.
     * Call when starting a new, unrelated movement.
     */
    public void reset() {
        // Reset filter state
        lpfStateX = 0.0;
        lpfStateY = 0.0;
        lastSampleTimeNanos = System.nanoTime();
        
        // Reset movement context
        currentPhase = MovementPhase.BALLISTIC;
        currentSpeedPxPerMs = 0.0;
        currentProgress = 0.0;
        
        // Reset feedback correction state
        java.util.Arrays.fill(errorHistoryX, 0.0);
        java.util.Arrays.fill(errorHistoryY, 0.0);
        errorHistoryIndex = 0;
        accumulatedErrorX = 0.0;
        accumulatedErrorY = 0.0;
        
        // Reset underlying pink noise generators
        pinkNoiseX.reset();
        pinkNoiseY.reset();
    }

    // ========================================================================
    // Internal Methods
    // ========================================================================

    /**
     * Apply hand-dominance based correlation between X and Y axes.
     * 
     * Right-handed players (dominantHandBias > 0.5):
     *   - Positive correlation: X and Y tend to move together
     *   - Slight rightward bias in combined tremor
     * 
     * Left-handed players (dominantHandBias < 0.5):
     *   - Negative correlation component
     *   - Slight leftward bias in combined tremor
     * 
     * @param rawX independent X noise
     * @param rawY independent Y noise
     * @return correlated [X, Y] noise
     */
    private double[] applyHandCorrelation(double rawX, double rawY) {
        double dominantBias = playerProfile.getDominantHandBias();
        
        // Correlation coefficient based on hand dominance
        // 0.5 = neutral (no correlation)
        // 0.75 = strong right-hand (positive correlation ~0.5)
        // 0.25 = strong left-hand (negative correlation ~-0.5)
        double correlationStrength = (dominantBias - 0.5) * 2.0; // Range: -1.0 to 1.0
        
        // Clamp to reasonable correlation range (0.3-0.5 is typical for hand tremor)
        correlationStrength = correlationStrength * 0.4; // Scale to ±0.4 max
        
        // Apply correlation using Cholesky-like transformation
        // X stays independent, Y gets a correlated component from X
        double correlatedY = rawY * Math.sqrt(1.0 - correlationStrength * correlationStrength) 
                           + rawX * correlationStrength;
        
        // Add directional bias based on dominant hand
        // Right-handed: slight positive X bias, Left-handed: slight negative X bias
        double directionalBias = (dominantBias - 0.5) * 0.1; // Small bias ±0.05
        double biasedX = rawX + directionalBias;
        
        return new double[] { biasedX, correlatedY };
    }

    /**
     * Calculate effective amplitude combining all scaling factors.
     */
    private double calculateEffectiveAmplitude() {
        // Base amplitude from profile (0.2-1.5 pixels)
        double baseAmp = playerProfile.getPhysTremorAmp();
        
        // Fatigue scaling: 1.0x (fresh) to 1.8x (exhausted)
        double fatigueLevel = fatigueModel.getFatigueLevel();
        double fatigueScale = 1.0 + fatigueLevel * 0.8;
        
        // Movement phase scaling
        double phaseScale = currentPhase.getAmplitudeScale();
        
        // Speed suppression: fast movements mask noise
        // At 1 px/ms: 0.67x, at 2 px/ms: 0.5x, at 0 px/ms: 1.0x
        double speedScale = 1.0 / (1.0 + currentSpeedPxPerMs * 0.5);
        
        // Progress-based fine adjustment
        // Smooth transition as we approach target
        double progressScale = 1.0;
        if (currentProgress > 0.9) {
            // Final approach: extra tremor visibility (can't hide it anymore)
            progressScale = 1.0 + (currentProgress - 0.9) * 2.0; // Up to 1.2x at progress=1.0
        }
        
        return baseAmp * fatigueScale * phaseScale * speedScale * progressScale;
    }

    /**
     * Calculate IIR low-pass filter alpha coefficient.
     * 
     * First-order IIR: y[n] = alpha * x[n] + (1-alpha) * y[n-1]
     * 
     * @param sampleRateHz current sample rate
     * @param cutoffHz filter cutoff frequency (muscle bandwidth)
     * @return alpha coefficient (0-1)
     */
    private double calculateLpfAlpha(double sampleRateHz, double cutoffHz) {
        // Prevent division by zero and ensure stable filter
        if (sampleRateHz <= 0 || cutoffHz <= 0) {
            return 1.0; // Pass-through if invalid
        }
        
        // First-order IIR approximation: alpha = 2*pi*fc / (2*pi*fc + fs)
        double omega = 2.0 * Math.PI * cutoffHz;
        double alpha = omega / (omega + sampleRateHz);
        
        // Clamp to valid range
        return Math.max(0.0, Math.min(1.0, alpha));
    }

    // ========================================================================
    // Getters for Testing/Debugging
    // ========================================================================

    public MovementPhase getCurrentPhase() {
        return currentPhase;
    }

    public double getCurrentProgress() {
        return currentProgress;
    }

    public double getCurrentSpeedPxPerMs() {
        return currentSpeedPxPerMs;
    }
}
