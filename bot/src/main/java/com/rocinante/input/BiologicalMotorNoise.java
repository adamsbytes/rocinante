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
 * 1. **Tremor Resonance** - Human hands act as a RESONATOR, not just a low-pass filter.
 *    There's a SPIKE of energy at 8-12Hz (physiological tremor) caused by:
 *    - Mechanical resonance of the arm/hand system
 *    - Motor unit firing rate (~8-25Hz summing to ~10Hz)
 *    - Stretch reflex feedback loop
 *    Implemented with biologically-accurate modeling:
 *    - Elliptical phase relationships (X-Y offset 60-120° per person, not independent)
 *    - Ornstein-Uhlenbeck frequency drift (smooth mean-reverting, not uniform jumps)
 *    - Coherent amplitude envelope (same fatigue affects both axes)
 * 
 * 2. **Upper muscle bandwidth limit** - Muscles can't respond above ~25 Hz.
 *    Implemented as a first-order IIR low-pass filter at 25Hz.
 * 
 * 3. **Fatigue-scaled amplitude** - Tired muscles have more tremor.
 *    Amplitude scales 1.0x (fresh) to 1.8x (exhausted).
 * 
 * 4. **Movement phase adaptation** - Different phases have different noise characteristics:
 *    - BALLISTIC (0-25%): Fast initial movement, noise suppressed (0.3x)
 *    - CORRECTION (25-75%): Mid-adjustment, moderate noise (0.7x)  
 *    - PRECISION (75-100%): Fine targeting, maximum tremor (1.2x)
 * 
 * 5. **Speed-dependent suppression** - Fast movements mask noise (motion blur effect).
 *    High speed = less visible tremor.
 * 
 * 6. **Hand-dominance correlated axes** - X and Y noise are partially correlated
 *    based on dominant hand. Right-handed players have rightward-biased tremor.
 * 
 * 7. **Per-profile unique characteristics** - Uses PlayerProfile's physTremorFreq,
 *    physTremorAmp, and dominantHandBias for truly unique motor signatures.
 * 
 * <h3>Resulting Error Spectrum</h3>
 * The combination produces error residuals that are NOT pure 1/f:
 * - Low frequencies: ~1/f from underlying pink noise
 * - 8-12 Hz: SPIKE from physiological tremor resonance
 * - Mid frequencies: Enhanced by feedback oscillation
 * - High frequencies (>25Hz): Suppressed by muscle bandwidth limit
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
     * Visual feedback delay in samples (~100-250ms in real humans).
     * This value is per-profile, representing individual visual processing speed.
     * Creates a delay ring buffer for the feedback correction.
     * 
     * @see PlayerProfile#getFeedbackDelaySamples()
     */
    private final int feedbackDelaySamples;
    
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
    private final double[] errorHistoryX;
    
    /** Error history ring buffer for Y axis (delayed feedback) */
    private final double[] errorHistoryY;
    
    /** Current position in ring buffer */
    private int errorHistoryIndex = 0;
    
    /** Accumulated error from last sample (what we're trying to correct) */
    private double accumulatedErrorX = 0.0;
    private double accumulatedErrorY = 0.0;
    
    /** Per-profile feedback characteristics (generated from profile seed) */
    private final double feedbackGain;
    private final double feedbackOscillation;

    // ========================================================================
    // Tremor Resonance State (8-12Hz Physiological Tremor)
    // ========================================================================
    // Real human hands act as a BAND-PASS filter / resonator, creating a SPIKE
    // of energy at 8-12Hz (physiological tremor) due to:
    // - Mechanical resonance of the arm/hand system
    // - Firing rate of motor units (~8-25Hz, summing to ~10Hz)
    // - Stretch reflex feedback loop
    //
    // This is NOT just filtering - we must ADD energy at the tremor frequency.
    //
    // KEY INSIGHT: X and Y tremor share the same muscle groups and neural
    // pathways, so they're NOT independent. Real tremor exhibits:
    // 1. Elliptical phase relationships (60-120° offset, not random)
    // 2. Coherent amplitude envelope (same fatigue affects both axes)
    // 3. Mean-reverting frequency drift (Ornstein-Uhlenbeck, not uniform jumps)
    
    /** 
     * Current phase of tremor oscillation (radians).
     * Only one phase is needed - Y phase is derived using elliptical offset.
     * This creates characteristic "elliptical tremor orbits" seen in spectrograms.
     */
    private double tremorPhase = 0.0;
    
    /**
     * Per-profile phase offset between X and Y axes (radians, typically 60-120°).
     * Creates elliptical tremor orbits rather than linear or independent wobble.
     * Stored at construction since it's a stable anatomical characteristic.
     */
    private final double tremorPhaseOffsetXY;
    
    /** 
     * Current instantaneous tremor frequency (Hz).
     * Uses single frequency for both axes (same neural oscillator driving both).
     * Varies via Ornstein-Uhlenbeck process for smooth, mean-reverting drift.
     */
    private double tremorFreq;
    
    /**
     * Ornstein-Uhlenbeck drift state for frequency wandering.
     * 
     * OU process: dx = -theta * (x - mu) * dt + sigma * sqrt(dt) * dW
     * This creates the smooth "drift" seen in EMG tremor studies rather than
     * sudden jumps that would occur with uniform random retargeting.
     * The mean-reversion keeps frequency bounded without hard clamping.
     */
    private double ouFreqDrift = 0.0;
    
    /**
     * OU process mean-reversion rate (theta).
     * Higher = faster reversion to mean, tighter frequency bounds.
     * 0.1 gives ~10 second relaxation time, matching biological tremor drift.
     */
    private static final double OU_THETA = 0.1;
    
    /**
     * OU process volatility (sigma).
     * Controls magnitude of random frequency jumps.
     * 0.3 Hz/sqrt(s) gives ±1.5 Hz typical deviation from base frequency.
     */
    private static final double OU_SIGMA = 0.3;
    
    /**
     * Shared amplitude envelope for coherent X/Y modulation.
     * 
     * Real tremor amplitude fluctuates coherently for both axes because it's
     * the same muscle fatigue/arousal affecting both. The ratio between X and Y
     * can vary, but the overall modulation envelope should be shared.
     * 
     * Uses slow Perlin-like noise (accumulated from Gaussian) for smooth envelope.
     */
    private double sharedAmpEnvelope = 1.0;
    
    /**
     * Velocity of the shared amplitude envelope (for smooth transitions).
     * Acts as a low-pass filtered random walk for the envelope.
     */
    private double sharedAmpEnvelopeVelocity = 0.0;

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
     * @param playerProfile profile for tremor characteristics and feedback delay
     * @param fatigueModel fatigue model for amplitude scaling
     */
    public BiologicalMotorNoise(PlayerProfile playerProfile, FatigueModel fatigueModel) {
        this.playerProfile = playerProfile;
        this.fatigueModel = fatigueModel;
        this.random = new SecureRandom();
        this.pinkNoiseX = new PinkNoiseGenerator(random);
        this.pinkNoiseY = new PinkNoiseGenerator(random);
        this.lastSampleTimeNanos = System.nanoTime();
        
        // Per-profile feedback delay (10-25 samples = 100-250ms at 100Hz)
        // Represents visual processing latency - faster players detect errors sooner
        this.feedbackDelaySamples = playerProfile.getFeedbackDelaySamples();
        
        // Initialize error history ring buffers with profile-specific size
        this.errorHistoryX = new double[feedbackDelaySamples];
        this.errorHistoryY = new double[feedbackDelaySamples];
        
        // Derive per-profile feedback characteristics from motor correlation
        // Players with high motor correlation have more consistent (less oscillating) feedback
        double motorCorrelation = playerProfile.getMotorSpeedCorrelation();
        this.feedbackGain = FEEDBACK_GAIN_BASE * (0.7 + motorCorrelation * 0.6); // 0.24-0.56
        this.feedbackOscillation = (1.0 - motorCorrelation) * 0.3; // 0.03-0.15
        
        // Initialize tremor resonance with profile characteristics
        // Single frequency for both axes (same neural oscillator drives both)
        double baseTremorFreq = playerProfile.getPhysTremorFreq();
        this.tremorFreq = baseTremorFreq;
        this.ouFreqDrift = 0.0; // Start centered on base frequency
        
        // Elliptical phase offset from profile (60-120° typical)
        // This anatomical characteristic creates elliptical tremor orbits
        this.tremorPhaseOffsetXY = playerProfile.getPhysTremorPhaseOffset();
        
        // Randomize starting phase to avoid synchronized tremor across bots
        // Only one phase needed - Y is derived from X + offset
        this.tremorPhase = random.nextDouble() * 2.0 * Math.PI;
        
        // Initialize shared amplitude envelope at neutral
        this.sharedAmpEnvelope = 1.0;
        this.sharedAmpEnvelopeVelocity = 0.0;
        
        log.debug("BiologicalMotorNoise initialized: feedbackDelay={}samples, feedbackGain={}, " +
                  "tremorFreq={}Hz, phaseOffset={}°",
                feedbackDelaySamples, String.format("%.2f", feedbackGain), 
                String.format("%.1f", baseTremorFreq),
                String.format("%.0f", Math.toDegrees(tremorPhaseOffsetXY)));
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
        int delayedIndex = (errorHistoryIndex + 1) % feedbackDelaySamples;
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
        // Muscles can't respond above ~20-25 Hz (upper motor bandwidth limit)
        // Use higher cutoff than tremor - we'll ADD tremor resonance separately
        double muscleUpperLimitHz = 25.0;
        double alpha = calculateLpfAlpha(sampleRateHz, muscleUpperLimitHz);
        
        double filteredX = alpha * combinedX + (1.0 - alpha) * lpfStateX;
        double filteredY = alpha * combinedY + (1.0 - alpha) * lpfStateY;
        
        // Safety clamp to prevent numeric explosion in filter state
        lpfStateX = Math.max(-100.0, Math.min(100.0, filteredX));
        lpfStateY = Math.max(-100.0, Math.min(100.0, filteredY));
        
        // === Stage 4: Tremor Resonance (8-12Hz Physiological Tremor) ===
        // ADD energy at the tremor frequency - this is the key fix!
        // Human hands act as a resonator, creating a SPIKE at 8-12Hz, not just filtering.
        double[] tremorOffset = calculateTremorResonance(deltaSeconds);
        filteredX += tremorOffset[0];
        filteredY += tremorOffset[1];
        
        // === Stage 6: Update Error History ===
        // Store current output as "error" for future feedback correction
        // This creates the closed-loop behavior
        accumulatedErrorX = accumulatedErrorX * 0.9 + filteredX; // Leaky integrator
        accumulatedErrorY = accumulatedErrorY * 0.9 + filteredY;
        
        // Safety clamp to prevent numeric explosion (shouldn't happen with proper filtering)
        accumulatedErrorX = Math.max(-100.0, Math.min(100.0, accumulatedErrorX));
        accumulatedErrorY = Math.max(-100.0, Math.min(100.0, accumulatedErrorY));
        
        errorHistoryX[errorHistoryIndex] = accumulatedErrorX;
        errorHistoryY[errorHistoryIndex] = accumulatedErrorY;
        errorHistoryIndex = (errorHistoryIndex + 1) % feedbackDelaySamples;
        
        // === Stage 7: Amplitude Scaling ===
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
        
        // Reset tremor resonance state (but keep continuous phase for natural feel)
        // Don't reset phase - tremor continues across movements (biological continuity)
        // Just reset frequency wandering - OU drift state returns to center
        tremorFreq = playerProfile.getPhysTremorFreq();
        ouFreqDrift = 0.0;
        
        // Don't reset shared amplitude envelope - fatigue/arousal persists across movements
        // But dampen the velocity to avoid jarring transitions
        sharedAmpEnvelopeVelocity *= 0.5;
        
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
    
    /**
     * Calculate tremor resonance offset with biologically-accurate modeling.
     * 
     * This models the 8-12Hz physiological tremor as a resonant sine wave,
     * NOT just filtered noise. Real human hands act as a band-pass filter /
     * resonator due to:
     * - Mechanical resonance of the arm/hand system
     * - Motor unit firing rate (~8-25Hz, summing to ~10Hz tremor)
     * - Stretch reflex feedback loop
     * 
     * <h3>Key biological insights implemented:</h3>
     * 
     * <b>1. Elliptical Phase Relationships:</b>
     * X and Y aren't independent - they share muscle groups and neural pathways.
     * The phase offset (60-120° per person) creates characteristic "elliptical
     * tremor orbits" visible in spectrograms. This is a stable anatomical trait.
     * 
     * <b>2. Ornstein-Uhlenbeck Frequency Drift:</b>
     * Real tremor frequency wanders via mean-reverting random walk, not uniform
     * jumps. OU process: dx = -θ(x-μ)dt + σ√dt·dW. This creates the smooth
     * "drift" seen in EMG studies rather than sudden frequency changes.
     * 
     * <b>3. Coherent Amplitude Envelope:</b>
     * Amplitude fluctuates coherently for both axes because it's the same muscle
     * fatigue/arousal causing both. A shared slow envelope modulates both axes,
     * with small independent noise on top for biological variation.
     * 
     * @param deltaSeconds time since last sample
     * @return [tremorX, tremorY] offset in arbitrary units (scaled by calculateEffectiveAmplitude)
     */
    private double[] calculateTremorResonance(double deltaSeconds) {
        double baseTremorFreq = playerProfile.getPhysTremorFreq();
        
        // === Ornstein-Uhlenbeck Frequency Drift ===
        // OU process for mean-reverting frequency wandering:
        // dx = -theta * x * dt + sigma * sqrt(dt) * gaussian
        // This creates smooth biological drift rather than sudden jumps.
        // The mean-reversion (-theta * x) naturally bounds the frequency without hard clamps.
        double sqrtDt = Math.sqrt(deltaSeconds);
        double driftIncrement = -OU_THETA * ouFreqDrift * deltaSeconds 
                              + OU_SIGMA * sqrtDt * random.nextGaussian();
        ouFreqDrift += driftIncrement;
        
        // Soft clamp via saturation (avoids hard edges while keeping bounded)
        // tanh naturally limits to ±1, scaled to ±2Hz max deviation
        ouFreqDrift = Math.tanh(ouFreqDrift / 2.0) * 2.0;
        
        // Apply drift to get current frequency
        tremorFreq = baseTremorFreq + ouFreqDrift;
        
        // === Advance Single Phase (X axis) ===
        // Y phase is derived from X + offset to create elliptical orbits
        double phaseIncrement = 2.0 * Math.PI * tremorFreq * deltaSeconds;
        tremorPhase += phaseIncrement;
        
        // Keep phase in [0, 2π] to avoid floating point issues over time
        if (tremorPhase > 2.0 * Math.PI) {
            tremorPhase -= 2.0 * Math.PI;
        }
        
        // === Shared Amplitude Envelope ===
        // Real tremor amplitude fluctuates coherently for both axes because it's
        // the same muscle fatigue/arousal affecting both. We use a slow-varying
        // envelope (like Perlin noise) rather than per-sample random noise.
        // 
        // Implementation: damped random walk with mean-reversion to 1.0
        // This creates the smooth 0.5-2 second amplitude fluctuations seen in real tremor.
        double envelopeAccel = -0.5 * (sharedAmpEnvelope - 1.0)  // Mean reversion to 1.0
                             - 0.3 * sharedAmpEnvelopeVelocity   // Damping
                             + 0.2 * random.nextGaussian();       // Random forcing
        sharedAmpEnvelopeVelocity += envelopeAccel * deltaSeconds;
        sharedAmpEnvelope += sharedAmpEnvelopeVelocity * deltaSeconds;
        
        // Soft clamp envelope to [0.7, 1.4] (30% variation typical for real tremor)
        sharedAmpEnvelope = 0.7 + 0.7 / (1.0 + Math.exp(-(sharedAmpEnvelope - 1.05) * 5.0));
        
        // === Generate Elliptical Tremor ===
        // X and Y use the SAME underlying oscillation, but Y is phase-shifted.
        // This creates elliptical orbits (not linear, not independent circles).
        // Small independent noise (±10%) adds biological micro-variation on top.
        double independentNoiseX = 1.0 + (random.nextDouble() - 0.5) * 0.2;
        double independentNoiseY = 1.0 + (random.nextDouble() - 0.5) * 0.2;
        
        // Normalized amplitude (1.0) - final scaling applies profile amp via calculateEffectiveAmplitude()
        // which includes fatigue, phase, speed, and progress factors
        double tremorX = Math.sin(tremorPhase) * sharedAmpEnvelope * independentNoiseX;
        double tremorY = Math.sin(tremorPhase + tremorPhaseOffsetXY) * sharedAmpEnvelope * independentNoiseY;
        
        return new double[] { tremorX, tremorY };
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
