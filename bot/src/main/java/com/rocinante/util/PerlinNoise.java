package com.rocinante.util;

import javax.inject.Singleton;

/**
 * Perlin noise implementation for humanized mouse movement path jitter.
 * As specified in REQUIREMENTS.md Section 3.1.1: Add Perlin noise to the path
 * with amplitude of 1-3 pixels, sampled every 5-10ms of movement time.
 *
 * This is a simplified 1D Perlin noise implementation suitable for adding
 * natural-looking variation to mouse movement paths.
 */
@Singleton
public class PerlinNoise {

    // Permutation table for noise generation
    private final int[] permutation;
    private final int[] p;

    // Default permutation table (Ken Perlin's original)
    private static final int[] DEFAULT_PERMUTATION = {
            151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
            140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
            247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
            57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
            74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
            60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
            65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
            200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
            52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
            207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
            119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
            129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
            218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
            81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
            184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
            222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180
    };

    /**
     * Create a PerlinNoise generator with default permutation.
     */
    public PerlinNoise() {
        this.permutation = DEFAULT_PERMUTATION.clone();
        this.p = new int[512];
        for (int i = 0; i < 256; i++) {
            p[i] = permutation[i];
            p[256 + i] = permutation[i];
        }
    }

    /**
     * Create a PerlinNoise generator with a custom seed.
     * The seed shuffles the permutation table for different noise patterns.
     *
     * @param seed the random seed
     */
    public PerlinNoise(long seed) {
        this.permutation = new int[256];
        this.p = new int[512];

        // Initialize with sequential values
        for (int i = 0; i < 256; i++) {
            permutation[i] = i;
        }

        // Fisher-Yates shuffle with seeded random
        java.util.Random random = new java.util.Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = permutation[i];
            permutation[i] = permutation[j];
            permutation[j] = temp;
        }

        // Double the permutation table
        for (int i = 0; i < 256; i++) {
            p[i] = permutation[i];
            p[256 + i] = permutation[i];
        }
    }

    /**
     * Generate 1D Perlin noise at a given position.
     * Returns a value in the range [-1, 1].
     *
     * @param x the input coordinate
     * @return noise value in [-1, 1]
     */
    public double noise1D(double x) {
        // Find unit interval containing point
        int X = fastFloor(x) & 255;

        // Find relative position in interval
        x -= fastFloor(x);

        // Compute fade curve
        double u = fade(x);

        // Hash coordinates of the interval corners
        int a = p[X];
        int b = p[X + 1];

        // Interpolate between gradients
        return lerp(u, grad1D(a, x), grad1D(b, x - 1));
    }

    /**
     * Generate 1D Perlin noise with specified frequency and amplitude.
     * This is the primary method for mouse path jitter as per spec.
     *
     * @param x         the input coordinate (typically time or distance along path)
     * @param frequency how rapidly the noise changes (higher = more jagged)
     * @param amplitude the maximum deviation from zero
     * @return noise value in [-amplitude, amplitude]
     */
    public double noise1D(double x, double frequency, double amplitude) {
        return noise1D(x * frequency) * amplitude;
    }

    /**
     * Generate multi-octave (fractal) 1D Perlin noise for more natural variation.
     * Each octave adds finer detail to the noise.
     *
     * @param x           the input coordinate
     * @param octaves     number of noise layers to combine
     * @param persistence amplitude multiplier for each successive octave
     * @return combined noise value
     */
    public double fractalNoise1D(double x, int octaves, double persistence) {
        double total = 0;
        double frequency = 1;
        double amplitude = 1;
        double maxValue = 0;

        for (int i = 0; i < octaves; i++) {
            total += noise1D(x * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= 2;
        }

        return total / maxValue;
    }

    /**
     * Generate 2D Perlin noise at a given position.
     * Useful for 2D position jitter if needed.
     *
     * @param x the X coordinate
     * @param y the Y coordinate
     * @return noise value in [-1, 1]
     */
    public double noise2D(double x, double y) {
        // Find unit square containing point
        int X = fastFloor(x) & 255;
        int Y = fastFloor(y) & 255;

        // Find relative position in square
        x -= fastFloor(x);
        y -= fastFloor(y);

        // Compute fade curves
        double u = fade(x);
        double v = fade(y);

        // Hash coordinates of square corners
        int aa = p[p[X] + Y];
        int ab = p[p[X] + Y + 1];
        int ba = p[p[X + 1] + Y];
        int bb = p[p[X + 1] + Y + 1];

        // Interpolate
        double x1 = lerp(u, grad2D(aa, x, y), grad2D(ba, x - 1, y));
        double x2 = lerp(u, grad2D(ab, x, y - 1), grad2D(bb, x - 1, y - 1));

        return lerp(v, x1, x2);
    }

    /**
     * Generate 2D Perlin noise with specified frequency and amplitude.
     *
     * @param x         the X coordinate
     * @param y         the Y coordinate
     * @param frequency how rapidly the noise changes
     * @param amplitude the maximum deviation from zero
     * @return noise value in [-amplitude, amplitude]
     */
    public double noise2D(double x, double y, double frequency, double amplitude) {
        return noise2D(x * frequency, y * frequency) * amplitude;
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Fade function for smooth interpolation (6t^5 - 15t^4 + 10t^3).
     * Ken Perlin's improved noise function uses this smoother curve.
     */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /**
     * Linear interpolation.
     */
    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /**
     * 1D gradient function.
     */
    private static double grad1D(int hash, double x) {
        return (hash & 1) == 0 ? x : -x;
    }

    /**
     * 2D gradient function using hash to select gradient direction.
     */
    private static double grad2D(int hash, double x, double y) {
        int h = hash & 3;
        double u = h < 2 ? x : y;
        double v = h < 2 ? y : x;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /**
     * Fast floor function.
     */
    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    // ========================================================================
    // Convenience Methods for Mouse Path Generation
    // ========================================================================

    /**
     * Generate noise-based offset for a point along a mouse path.
     * As specified: 1-3 pixel amplitude, sample every 5-10ms.
     *
     * @param pathProgress progress along the path (0.0 to 1.0)
     * @param amplitude    noise amplitude in pixels (spec: 1-3)
     * @param seed         unique seed for this movement
     * @return X and Y offsets as a double array [offsetX, offsetY]
     */
    public double[] getPathOffset(double pathProgress, double amplitude, long seed) {
        // Use different frequencies for X and Y to avoid diagonal bias
        // Hash the seed to get continuous pseudo-random values instead of discrete uniform
        // (seed % 5) was detectable via statistical tests - produces only 5 values
        double hashX = hashToUnitInterval(seed);
        double hashY = hashToUnitInterval(seed ^ 0xDEADBEEFL);
        
        double frequencyX = 3.0 + hashX * 2.5;  // Range: 3.0 - 5.5
        double frequencyY = 3.5 + hashY * 2.5;  // Range: 3.5 - 6.0

        double offsetX = noise1D(pathProgress * 10 + seed * 0.001, frequencyX, amplitude);
        double offsetY = noise1D(pathProgress * 10 + seed * 0.002 + 100, frequencyY, amplitude);

        return new double[]{offsetX, offsetY};
    }
    
    /**
     * Hash a long seed to a value in [0, 1).
     * Uses a simple but effective hash function to convert discrete seeds
     * to continuous values, avoiding detectable discrete uniform patterns.
     */
    private double hashToUnitInterval(long seed) {
        // MurmurHash3-like mixing
        seed ^= seed >>> 33;
        seed *= 0xff51afd7ed558ccdL;
        seed ^= seed >>> 33;
        seed *= 0xc4ceb9fe1a85ec53L;
        seed ^= seed >>> 33;
        
        // Convert to [0, 1)
        return (seed & 0x7FFFFFFFFFFFFFFFL) / (double) Long.MAX_VALUE;
    }

    /**
     * Generate a perpendicular offset for a point along a mouse path.
     * This creates jitter perpendicular to the movement direction.
     *
     * @param pathProgress progress along the path (0.0 to 1.0)
     * @param amplitude    noise amplitude in pixels
     * @return perpendicular offset magnitude
     */
    public double getPerpendicularOffset(double pathProgress, double amplitude) {
        return noise1D(pathProgress * 8, 1.0, amplitude);
    }
}

