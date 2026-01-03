package com.rocinante.input;

import java.security.SecureRandom;

/**
 * Generates 1/f (pink) noise using the Voss-McCartney algorithm.
 * 
 * Pink noise has a power spectral density inversely proportional to frequency,
 * meaning lower frequencies have more power than higher frequencies. This matches
 * real human motor noise characteristics where movement errors are dominated by
 * low-frequency drift rather than high-frequency jitter.
 * 
 * The Voss-McCartney algorithm works by:
 * 1. Maintaining N random values (octaves) at different update rates
 * 2. Each octave i is updated every 2^i steps (lower octaves update more frequently)
 * 3. The sum of all octaves gives pink noise
 * 
 * This is in contrast to white noise (from Math.round() or ThreadLocalRandom)
 * which has equal power at all frequencies - a detectable non-human pattern.
 * 
 * References:
 * - Voss & Clarke, "1/f noise in music and speech" (1975)
 * - Gardner, "Efficient generation of colored noise" (1978)
 * 
 * @see <a href="https://www.firstpr.com.au/dsp/pink-noise/">Pink Noise Generation</a>
 */
public class PinkNoiseGenerator {

    /**
     * Number of octaves (rows) in the generator.
     * 16 octaves gives good quality pink noise with proper 1/f spectrum.
     */
    private static final int NUM_OCTAVES = 16;

    /**
     * Random values for each octave. Lower indices update more frequently.
     */
    private final double[] octaves;

    /**
     * Running sum of all octave values (cached for efficiency).
     */
    private double runningSum;

    /**
     * Step counter for determining which octave to update.
     */
    private long stepCounter;

    /**
     * Random number generator.
     */
    private final SecureRandom random;

    /**
     * Normalization factor to keep output roughly in [-1, 1] range.
     * Since we sum NUM_OCTAVES values each in [-1, 1], divide by sqrt(NUM_OCTAVES)
     * to normalize variance while preserving the 1/f spectrum shape.
     */
    private static final double NORMALIZATION = 1.0 / Math.sqrt(NUM_OCTAVES);

    /**
     * Create a new pink noise generator.
     */
    public PinkNoiseGenerator() {
        this(new SecureRandom());
    }

    /**
     * Create a new pink noise generator with a specific random source.
     * 
     * @param random the random number generator to use
     */
    public PinkNoiseGenerator(SecureRandom random) {
        this.random = random;
        this.octaves = new double[NUM_OCTAVES];
        this.runningSum = 0;
        this.stepCounter = 0;

        // Initialize all octaves with random values
        for (int i = 0; i < NUM_OCTAVES; i++) {
            octaves[i] = random.nextDouble() * 2.0 - 1.0;  // [-1, 1]
            runningSum += octaves[i];
        }
    }

    /**
     * Generate the next pink noise sample.
     * 
     * @return a value roughly in [-1, 1] range with 1/f spectral characteristics
     */
    public double next() {
        // Determine which octave to update based on trailing zeros in counter
        // This ensures lower octaves update more frequently (higher frequency)
        // and higher octaves update less frequently (lower frequency)
        int octaveToUpdate = Long.numberOfTrailingZeros(stepCounter);
        
        // Clamp to valid octave range (handles counter = 0 case)
        if (octaveToUpdate >= NUM_OCTAVES) {
            octaveToUpdate = NUM_OCTAVES - 1;
        }

        // Update the selected octave
        double oldValue = octaves[octaveToUpdate];
        double newValue = random.nextDouble() * 2.0 - 1.0;  // [-1, 1]
        octaves[octaveToUpdate] = newValue;

        // Update running sum efficiently (subtract old, add new)
        runningSum = runningSum - oldValue + newValue;

        // Increment step counter
        stepCounter++;

        // Return normalized sum
        return runningSum * NORMALIZATION;
    }

    /**
     * Generate pink noise scaled to a specific amplitude.
     * 
     * @param amplitude maximum deviation from zero
     * @return a value roughly in [-amplitude, amplitude] with 1/f characteristics
     */
    public double next(double amplitude) {
        return next() * amplitude;
    }

    /**
     * Generate a 2D pink noise vector for motor movement.
     * Uses two independent pink noise sequences for X and Y axes.
     * 
     * @param amplitude maximum deviation per axis
     * @return array of [dx, dy] pink noise offsets
     */
    public double[] next2D(double amplitude) {
        return new double[] {
            next() * amplitude,
            next() * amplitude
        };
    }

    /**
     * Reset the generator to its initial state.
     * Useful for reproducible testing.
     */
    public void reset() {
        stepCounter = 0;
        runningSum = 0;
        for (int i = 0; i < NUM_OCTAVES; i++) {
            octaves[i] = random.nextDouble() * 2.0 - 1.0;
            runningSum += octaves[i];
        }
    }

    /**
     * Get the current step count.
     * Useful for debugging/testing.
     * 
     * @return number of samples generated since creation/reset
     */
    public long getStepCount() {
        return stepCounter;
    }
}
