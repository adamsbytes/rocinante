package com.rocinante.behavior;

import com.google.gson.Gson;
import com.rocinante.data.GsonFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rocinante.input.ScreenRegion;
import com.rocinante.input.uinput.DevicePreset;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Consolidated player profile combining input characteristics and behavioral traits.
 * 
 * This class replaces InputProfile and adds extensive behavioral fingerprinting
 * to make each account behave uniquely and consistently across sessions.
 * 
 * Per REQUIREMENTS.md Sections 3.3, 3.4, 3.5, 3.6, and 4.2-4.4:
 * - Input characteristics (mouse speed, click variance, typing, etc.)
 * - Break behavior preferences
 * - Camera preferences
 * - Session patterns (rituals, XP checking, player inspection)
 * - Action sequence preferences
 * - Teleport preferences
 * - Long-term behavioral drift
 * 
 * Profile persists to ~/.runelite/rocinante/profiles/{account_hash}.json
 * with auto-save every 5 minutes and on logout.
 */
@Slf4j
@Singleton
public class PlayerProfile {

    private static final String DEFAULT_PROFILE_DIR = System.getProperty("user.home") 
        + "/.runelite/rocinante/profiles";
    private static final String PROFILE_SUBDIR = "profiles";
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final long SAVE_INTERVAL_MINUTES = 5;
    private static final Duration FRESH_SESSION_THRESHOLD = Duration.ofMinutes(15);
    private static final int MAX_DRIFT_HISTORY = 50;
    
    // === Drift percentages ===
    private static final double SESSION_DRIFT_PERCENT = 0.02;  // ±2% per session
    private static final double LONG_TERM_DRIFT_HOURS = 20.0;  // Drift every 20 hours
    
    // === Input characteristic bounds (from REQUIREMENTS.md 3.3) ===
    private static final double MIN_MOUSE_SPEED = 0.8;
    private static final double MAX_MOUSE_SPEED = 1.3;
    private static final double MIN_CLICK_VARIANCE = 0.7;
    private static final double MAX_CLICK_VARIANCE = 1.4;
    private static final int MIN_TYPING_WPM = 40;
    private static final int MAX_TYPING_WPM = 80;
    
    // === Break behavior bounds ===
    private static final double MIN_BREAK_THRESHOLD = 0.60;
    private static final double MAX_BREAK_THRESHOLD = 0.95;
    
    // === Preference weight bounds ===
    private static final double MIN_WEIGHT = 0.05;
    private static final double MAX_WEIGHT = 0.85;
    private static final double REINFORCEMENT_INCREMENT = 0.005;  // +0.5% per reinforcement
    
    // ========================================================================
    // Motor Trait Correlation Matrix (Multivariate Normal)
    // ========================================================================
    // 
    // Real humans show WEAK correlations (r=0.3-0.5) between motor traits,
    // NOT the near-perfect linear relationships (r≈0.9) that linear derivation produces.
    // This correlation matrix enables archetypes like:
    //   - "Fast but Sloppy": high speed, high overshoot, high wobble
    //   - "Slow but Snappy": low speed, short clicks, quick reactions
    //   - "FPS Precision": fast mouse, but deliberate slow clicks
    //   - "RTS Clicker": slow mouse, but rapid fire clicks
    //
    // Traits (indices):
    //   0: mouseSpeedMultiplier (0.8-1.3) - base movement speed
    //   1: clickDurationMu (65-95ms) - mean click hold time  
    //   2: cognitiveDelayBase (60-180ms) - reaction/processing time
    //   3: overshootProbability (0.08-0.20) - tendency to overshoot targets
    //   4: wobbleAmplitudeModifier (0.7-1.4) - hand tremor amplitude
    //   5: velocityFlow (0.2-0.65) - movement asymmetry (snappy vs lazy)
    //   6: fittsB (60-180ms/bit) - motor bandwidth for targeting
    //   7: minWalkClickIntervalBase (2.0-6.0) - walk click pacing
    //
    // Correlation strengths explained:
    //   ±0.35-0.45: Moderate correlation (expected in same-domain traits)
    //   ±0.20-0.30: Weak correlation (cross-domain traits)
    //   ±0.10-0.15: Very weak (nearly independent)
    //
    // Matrix is symmetric with 1.0 on diagonal.
    // ========================================================================
    private static final int MOTOR_TRAIT_COUNT = 8;
    private static final int MT_MOUSE_SPEED = 0;
    private static final int MT_CLICK_DURATION = 1;
    private static final int MT_COGNITIVE_DELAY = 2;
    private static final int MT_OVERSHOOT = 3;
    private static final int MT_WOBBLE = 4;
    private static final int MT_VELOCITY_FLOW = 5;
    private static final int MT_FITTS_B = 6;
    private static final int MT_WALK_INTERVAL = 7;
    
    // Motor correlation matrix is now generated randomly per-bot via
    // generateConstrainedRandomCorrelationMatrix() with constraints from
    // MOTOR_CORRELATION_CONSTRAINTS. No hardcoded template.

    // Motor trait distribution parameters: { mean, stdDev, min, max }
    private static final double[][] MOTOR_TRAIT_PARAMS = {
        { 1.05,  0.15,   0.80,   1.30 },  // mouseSpeedMultiplier
        { 80.0,  10.0,  65.0,  95.0 },    // clickDurationMu (ms)
        { 120.0, 40.0,  60.0, 180.0 },    // cognitiveDelayBase (ms)
        { 0.14,  0.04,  0.08,  0.20 },    // overshootProbability
        { 1.05,  0.22,  0.70,  1.40 },    // wobbleAmplitudeModifier
        { 0.425, 0.15,  0.20,  0.65 },    // velocityFlow
        { 120.0, 35.0,  60.0, 180.0 },    // fittsB (ms/bit)
        { 4.0,   1.2,   2.0,   6.0 },     // minWalkClickIntervalBase (ticks)
    };
    
    private final Randomization randomization;
    private final Gson gson;
    private final ScheduledExecutorService saveExecutor;
    
    /**
     * Handle to the scheduled save task.
     * Tracked to prevent creating multiple schedulers on re-login.
     */
    private ScheduledFuture<?> saveTask = null;
    
    @Getter
    private ProfileData profileData;
    
    @Getter
    private String accountHash;
    
    @Getter
    private boolean loaded = false;
    
    private Instant sessionStartTime;
    
    @Inject
    public PlayerProfile(Randomization randomization) {
        this.randomization = randomization;
        this.gson = GsonFactory.createPrettyPrinting();
        this.profileData = new ProfileData();
        this.saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlayerProfile-AutoSave");
            t.setDaemon(true);
            return t;
        });
        log.info("PlayerProfile initialized");
    }
    
    /**
     * Constructor for testing with custom randomization.
     */
    public PlayerProfile(Randomization randomization, ScheduledExecutorService executor) {
        this.randomization = randomization;
        this.gson = GsonFactory.createPrettyPrinting();
        this.profileData = new ProfileData();
        this.saveExecutor = executor;
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Initialize or load profile for a given account name.
     * Called when a user logs in to the game.
     *
     * @param accountName the account/username to load profile for
     * @param accountType the account type (for new profile generation), or null if unknown
     */
    public void initializeForAccount(String accountName, com.rocinante.behavior.AccountType accountType) {
        this.accountHash = hashAccountName(accountName);
        this.sessionStartTime = Instant.now();

        Path profilePath = getProfilePath();
        if (Files.exists(profilePath)) {
            loadProfile(profilePath);
            applySessionDrift();
        } else {
            generateNewProfile(accountName, accountType);
        }
        
        // Update session tracking
        profileData.lastSessionStart = Instant.now();
        profileData.sessionCount++;
        
        // Save immediately and schedule periodic saves
        saveProfile(profilePath);
        schedulePersistence();
        
        loaded = true;
        log.info("PlayerProfile initialized for account hash: {} (session #{}, type={})", 
                accountHash, profileData.sessionCount, accountType);
    }

    /**
     * Initialize with default profile (for testing or when account name unavailable).
     */
    public void initializeDefault() {
        this.accountHash = "default";
        this.sessionStartTime = Instant.now();
        generateNewProfile("default", null);
        profileData.lastSessionStart = Instant.now();
        loaded = true;
        log.info("Default PlayerProfile initialized");
    }

    // ========================================================================
    // Profile Generation
    // ========================================================================

    /**
     * Generate a new profile with a cryptographically random seed.
     * 
     * CRITICAL: Uses SecureRandom for the seed, NOT derived from account name.
     * This prevents prediction of profile characteristics from known usernames.
     * The random seed is stored in the profile and used for any future
     * regeneration to maintain consistency.
     * 
     * @param accountName the account name (used only for identification, not seeding)
     * @param accountType optional account type for type-specific defaults (null = unknown/normal)
     */
    private void generateNewProfile(String accountName, com.rocinante.behavior.AccountType accountType) {
        // CRITICAL: Use cryptographically random seed, NOT derived from account name
        // This prevents attackers from predicting profile characteristics
        long seed = generateCryptographicSeed();
        Random seededRandom = new Random(seed);
        Randomization seededRandomization = new Randomization(seed);

        profileData = new ProfileData();
        profileData.schemaVersion = CURRENT_SCHEMA_VERSION;
        profileData.accountHash = accountHash;
        profileData.profileSeed = seed;  // Store seed for future regeneration consistency
        profileData.createdAt = Instant.now();
        
        // ====================================================================
        // CORRELATED MOTOR TRAITS (Multivariate Normal with Per-Bot Noise)
        // ====================================================================
        // Instead of linearly deriving traits from mouseSpeedMultiplier (which
        // creates a rank-1 manifold where all bots are "Athletic Gamers" of
        // varying speeds), we use a multivariate normal with a covariance matrix.
        // 
        // This produces realistic human correlations (r=0.3-0.5 instead of ~0.9)
        // and enables diverse archetypes:
        //   - "Fast but Sloppy": high speed, high overshoot, high wobble
        //   - "Slow but Snappy": low speed, short clicks, quick reactions  
        //   - "FPS Precision": fast mouse, deliberate slow clicks
        //   - "RTS Clicker": slow mouse, rapid fire clicks
        // 
        // CRITICAL: Each bot gets a perturbed correlation matrix to avoid
        // population-level fingerprinting. Real human populations have varying
        // correlation structures between sub-groups (gamers vs casuals, age
        // groups, etc.). A fixed correlation matrix is statistically detectable.
        // ====================================================================
        
        // Extract means, stdDevs, mins, maxs from MOTOR_TRAIT_PARAMS
        double[] means = new double[MOTOR_TRAIT_COUNT];
        double[] stdDevs = new double[MOTOR_TRAIT_COUNT];
        double[] mins = new double[MOTOR_TRAIT_COUNT];
        double[] maxs = new double[MOTOR_TRAIT_COUNT];
        for (int i = 0; i < MOTOR_TRAIT_COUNT; i++) {
            means[i] = MOTOR_TRAIT_PARAMS[i][0];
            stdDevs[i] = MOTOR_TRAIT_PARAMS[i][1];
            mins[i] = MOTOR_TRAIT_PARAMS[i][2];
            maxs[i] = MOTOR_TRAIT_PARAMS[i][3];
        }
        
        // Generate per-bot unique constrained random correlation matrix
        // This ensures 100 bots have truly unique correlation structures (no clustering)
        // while maintaining realistic human motor control correlations
        double[][] uniqueCorrelationMatrix = generateConstrainedRandomCorrelationMatrix(seededRandom);

        // Generate 8 correlated motor traits together using the unique matrix
        double[] motorTraits = seededRandomization.multivariateNormalBounded(
                means, stdDevs, uniqueCorrelationMatrix, mins, maxs);

        // Store correlation matrix and pre-compute Cholesky decomposition for drift
        profileData.motorCorrelationMatrix = uniqueCorrelationMatrix;
        profileData.motorCorrelationCholesky = choleskyDecomposition(uniqueCorrelationMatrix);
        
        // Assign correlated traits to profile
        profileData.mouseSpeedMultiplier = motorTraits[MT_MOUSE_SPEED];
        profileData.clickDurationMu = motorTraits[MT_CLICK_DURATION];
        profileData.cognitiveDelayBase = motorTraits[MT_COGNITIVE_DELAY];
        profileData.overshootProbability = motorTraits[MT_OVERSHOOT];
        profileData.wobbleAmplitudeModifier = motorTraits[MT_WOBBLE];
        profileData.velocityFlow = motorTraits[MT_VELOCITY_FLOW];
        profileData.fittsB = motorTraits[MT_FITTS_B];
        profileData.minWalkClickIntervalBase = motorTraits[MT_WALK_INTERVAL];
        
        log.debug("Motor traits generated (MVN): speed={}, clickMu={}ms, cognitive={}ms, " +
                  "overshoot={}, wobble={}, velocity={}, fittsB={}, walkInt={}",
                  String.format("%.2f", profileData.mouseSpeedMultiplier),
                  String.format("%.1f", profileData.clickDurationMu),
                  String.format("%.1f", profileData.cognitiveDelayBase),
                  String.format("%.3f", profileData.overshootProbability),
                  String.format("%.2f", profileData.wobbleAmplitudeModifier),
                  String.format("%.2f", profileData.velocityFlow),
                  String.format("%.1f", profileData.fittsB),
                  String.format("%.2f", profileData.minWalkClickIntervalBase));
        
        // ====================================================================
        // INDEPENDENT/WEAKLY-DERIVED TRAITS
        // ====================================================================
        // These traits are NOT in the main correlation matrix because:
        // 1. They're in a different domain (e.g., typing vs mouse)
        // 2. They're derived from another correlated trait (same physiology)
        // 3. They're truly independent (physiological constants)
        // ====================================================================
        
        // === Independent input characteristics ===
        profileData.clickVarianceModifier = MIN_CLICK_VARIANCE +
                seededRandom.nextDouble() * (MAX_CLICK_VARIANCE - MIN_CLICK_VARIANCE);
        
        profileData.typingSpeedWPM = seededRandomization.uniformRandomInt(MIN_TYPING_WPM, MAX_TYPING_WPM);
        
        // Real handedness is BIMODAL, not uniform in the middle:
        // ~90% right-handed (bias 0.80-0.98), ~10% left-handed (bias 0.02-0.20)
        // Very few humans are truly ambidextrous (0.3-0.7 range)
        if (seededRandom.nextDouble() < 0.90) {
            // Right-handed: bias toward right side of screen (0.80-0.98)
            profileData.dominantHandBias = 0.80 + seededRandom.nextDouble() * 0.18;
        } else {
            // Left-handed: bias toward left side of screen (0.02-0.20)
            profileData.dominantHandBias = 0.02 + seededRandom.nextDouble() * 0.18;
        }
        
        // Preferred idle positions: 2-4 regions
        int numIdlePositions = seededRandomization.uniformRandomInt(2, 4);
        profileData.preferredIdlePositions = selectRandomIdlePositions(seededRandom, numIdlePositions);
        
        // Error rates - independent, personal characteristics
        profileData.baseMisclickRate = 0.01 + seededRandom.nextDouble() * 0.02;  // 1-3%
        profileData.baseTypoRate = 0.005 + seededRandom.nextDouble() * 0.015;    // 0.5-2%
        
        // === Micro-correction: derived from overshoot (same "sloppiness" domain) ===
        // Players who overshoot more also need more corrections
        // Weak derivation (adds noise, not deterministic)
        double correctionBase = 0.15 + seededRandom.nextDouble() * 0.08;
        double overshootCorrectionBonus = (profileData.overshootProbability - 0.14) * 0.4;
        profileData.microCorrectionProbability = Randomization.clamp(
                correctionBase + overshootCorrectionBonus, 0.10, 0.30);
        
        // === Drag wobble frequency: physiological constant (independent) ===
        profileData.wobbleFrequencyBase = 2.5 + seededRandom.nextDouble() * 1.5;  // 2.5-4.0 Hz
        profileData.wobbleFrequencyVariance = 0.3 + seededRandom.nextDouble() * 0.5;  // 0.3-0.8
        
        // === Click timing variance: derived from clickDurationMu (same domain) ===
        // These relate to the same physiological process as clickDurationMu
        // Sigma: base + small correlation with mu (longer clicks = slightly more variance)
        double clickSigmaBase = 10.0 + seededRandom.nextDouble() * 6.0;
        double muCorrelation = (profileData.clickDurationMu - 80.0) / 15.0 * 2.0; // ±2ms based on mu
        profileData.clickDurationSigma = Randomization.clamp(
                clickSigmaBase + muCorrelation, 8.0, 18.0);
        
        // Tau (exponential tail): independent - models occasional attention lapses
        profileData.clickDurationTau = 3.0 + seededRandom.nextDouble() * 10.0;  // 3-13ms
        
        // === Cognitive delay variance: independent ratio ===
        profileData.cognitiveDelayVariance = 0.3 + seededRandom.nextDouble() * 0.4;  // 0.3-0.7
        
        // === Motor speed correlation: now INDEPENDENT (not derived from speed) ===
        // This represents how consistently a person maintains their motor traits
        // across different contexts - varies by individual, not by speed
        profileData.motorSpeedCorrelation = 0.50 + seededRandom.nextDouble() * 0.40;  // 0.5-0.9
        
        // === Tick jitter: reaction time characteristics (independent domain) ===
        // Real human choice reaction times follow Ex-Gaussian distribution
        // μ: 150-400ms (central tendency), σ: 20-100ms (consistency), τ: 50-300ms (tail)
        profileData.jitterMu = 150.0 + seededRandom.nextDouble() * 250.0;
        profileData.jitterSigma = 20.0 + seededRandom.nextDouble() * 80.0;
        profileData.jitterTau = 50.0 + seededRandom.nextDouble() * 250.0;
        
        // === Attention modeling: independent characteristics ===
        profileData.tickSkipBaseProbability = 0.03 + seededRandom.nextDouble() * 0.05;  // 3-8%
        profileData.attentionLapseProbability = 0.005 + seededRandom.nextDouble() * 0.015;  // 0.5-2%
        
        // === Anticipation and Hesitation: inversely correlated (personality domain) ===
        double decisiveness = seededRandom.nextDouble();  // 0=cautious, 1=decisive
        profileData.anticipationProbability = 0.10 + decisiveness * 0.12;  // 10-22%
        profileData.hesitationProbability = 0.10 + (1.0 - decisiveness) * 0.18;  // 10-28%
        // Add noise to break perfect inverse correlation
        profileData.anticipationProbability += (seededRandom.nextDouble() - 0.5) * 0.04;
        profileData.hesitationProbability += (seededRandom.nextDouble() - 0.5) * 0.04;
        profileData.anticipationProbability = Randomization.clamp(profileData.anticipationProbability, 0.08, 0.25);
        profileData.hesitationProbability = Randomization.clamp(profileData.hesitationProbability, 0.08, 0.30);
        
        // === Mouse path complexity: weakly derived from hesitation (personality) ===
        double submovementBase = 0.15 + seededRandom.nextDouble() * 0.10;
        double hesitationSubmovementBonus = (profileData.hesitationProbability - 0.10) * 0.3;
        profileData.submovementProbability = Randomization.clamp(
                submovementBase + hesitationSubmovementBonus, 0.10, 0.35);
        profileData.usesPathSegmentation = seededRandom.nextDouble() > 0.3;  // 70% use segmentation
        
        // === Physiological constants: tremor frequency (independent) ===
        profileData.physTremorFreq = 8.0 + seededRandom.nextDouble() * 4.0;  // 8-12Hz
        
        // === Tremor amplitude: derived from wobble (same physiology) ===
        double tremorBase = 0.2 + seededRandom.nextDouble() * 0.8;
        double wobbleCorrelation = (profileData.wobbleAmplitudeModifier - 1.05) * 0.5;
        profileData.physTremorAmp = Randomization.clamp(tremorBase + wobbleCorrelation, 0.2, 1.5);
        
        // === Tremor phase offset: determines elliptical tremor orbit shape ===
        // Real human tremor has elliptical phase relationships between X and Y because
        // they share muscle groups. The offset varies by person (60-120° typical) based
        // on individual anatomy (muscle attachment points, neural pathway timing).
        // This creates the characteristic "elliptical orbits" seen in tremor spectrograms.
        profileData.physTremorPhaseOffset = Math.toRadians(60.0 + seededRandom.nextDouble() * 60.0);
        
        // === Motor unit quantization: derived from overshoot (same "jerky" domain) ===
        double motorUnitBase = seededRandom.nextDouble() * 0.8;
        double overshootMotorBonus = (profileData.overshootProbability - 0.14) * 2.5;
        profileData.motorUnitThreshold = Randomization.clamp(
                motorUnitBase + overshootMotorBonus, 0.0, 1.5);
        
        // === Feedback delay: visual-motor loop latency (10-25 samples at 100Hz = 100-250ms) ===
        // Weakly correlated with cognitive delay - faster thinkers also have faster visual processing
        double feedbackBase = 17.5; // Center of range
        double cognitiveCorrelation = (profileData.cognitiveDelayBase - 120.0) / 60.0 * 3.0; // ±3 samples
        double feedbackNoise = seededRandom.nextGaussian() * 3.0; // ±3 samples noise
        profileData.feedbackDelaySamples = (int) Randomization.clamp(
                feedbackBase + cognitiveCorrelation + feedbackNoise, 10, 25);
        
        // === Fitts' Law 'a' parameter: independent (neuromuscular initiation delay) ===
        // Not correlated with speed - some fast movers have slow starts, some don't
        double fittsACandidate = 80.0 + seededRandom.nextGaussian() * 35.0;
        profileData.fittsA = Randomization.clamp(fittsACandidate, 30.0, 200.0);

        // === Break behavior ===
        profileData.breakFatigueThreshold = MIN_BREAK_THRESHOLD +
                seededRandom.nextDouble() * (MAX_BREAK_THRESHOLD - MIN_BREAK_THRESHOLD);
        
        profileData.breakActivityWeights = generateBreakActivityWeights(seededRandom);
        
        // === Run energy thresholds ===
        // Generate with Gaussian distribution for natural per-player variation
        // Enable threshold: mean=40, stddev=12, clamped to [25, 65]
        double runEnableCandidate = 40.0 + seededRandom.nextGaussian() * 12.0;
        profileData.runEnableThreshold = (int) Math.max(25, Math.min(65, Math.round(runEnableCandidate)));
        
        // Disable threshold: mean=15, stddev=5, clamped to [5, 25]
        double runDisableCandidate = 15.0 + seededRandom.nextGaussian() * 5.0;
        profileData.runDisableThreshold = (int) Math.max(5, Math.min(25, Math.round(runDisableCandidate)));
        
        // Ensure hysteresis gap of at least 15 to prevent rapid toggling
        if (profileData.runEnableThreshold - profileData.runDisableThreshold < 15) {
            profileData.runDisableThreshold = profileData.runEnableThreshold - 15;
            // Re-clamp disable threshold after adjustment
            profileData.runDisableThreshold = Math.max(5, profileData.runDisableThreshold);
        }
        
        // === Camera preferences ===
        profileData.preferredCompassAngle = seededRandom.nextDouble() * 360.0;
        profileData.pitchPreference = selectPitchPreference(seededRandom);
        profileData.cameraChangeFrequency = 5.0 + seededRandom.nextDouble() * 15.0;  // 5-20 per hour
        
        // === Camera hold preferences (fidgeting behavior) ===
        profileData.cameraHoldFrequency = 0.05 + seededRandom.nextDouble() * 0.25;  // 5-30%
        profileData.cameraHoldPreferredDirection = selectCameraHoldDirection(seededRandom);
        profileData.cameraHoldSpeedPreference = selectCameraHoldSpeed(seededRandom);
        
        // === Camera snap-back preferences (returning to "home" angle) ===
        profileData.cameraSnapBackProbability = 0.3 + seededRandom.nextDouble() * 0.5;  // 30-80%
        profileData.cameraSnapBackTolerance = 20.0 + seededRandom.nextDouble() * 40.0;  // 20-60 degrees
        profileData.cameraSnapBackDelayMs = 2000 + (long)(seededRandom.nextDouble() * 6000);  // 2-8 seconds
        
        // === Session patterns ===
        profileData.sessionRituals = selectSessionRituals(seededRandom);
        profileData.ritualExecutionProbability = 0.70 + seededRandom.nextDouble() * 0.20;  // 70-90%
        profileData.xpCheckFrequency = seededRandom.nextDouble() * 15.0;  // 0-15 per hour
        profileData.playerInspectionFrequency = seededRandom.nextDouble() * 5.0;  // 0-5 per hour
        
        // === XP check method preferences ===
        // Generate random distribution that sums to 1.0
        // Some players exclusively use tab, others prefer orb, etc.
        double xpOrb = seededRandom.nextDouble();
        double xpTab = seededRandom.nextDouble();
        double xpTotal = xpOrb + xpTab + 0.01; // Add small amount for tracker
        profileData.xpCheckOrbProbability = xpOrb / xpTotal;
        profileData.xpCheckTabProbability = xpTab / xpTotal;
        // Tracker is implicit: 1.0 - orb - tab
        
        // === Player inspection target preferences ===
        // Nearby probability clamped to 30-80%
        profileData.inspectionNearbyProbability = 0.30 + seededRandom.nextDouble() * 0.50;
        // High/low split the remainder randomly
        double remainingProb = 1.0 - profileData.inspectionNearbyProbability;
        profileData.inspectionHighLevelProbability = seededRandom.nextDouble() * remainingProb;
        // Low level is implicit: 1.0 - nearby - high
        
        // === Action sequencing ===
        profileData.bankingSequenceWeights = generateSequenceWeights(seededRandom, 
                Arrays.asList("TYPE_A", "TYPE_B", "TYPE_C"));
        profileData.combatPrepSequenceWeights = generateSequenceWeights(seededRandom,
                Arrays.asList("TYPE_A", "TYPE_B", "TYPE_C"));
        
        // === Teleport preferences (account-type aware) ===
        profileData.teleportMethodWeights = generateTeleportWeights(seededRandom, accountType);
        profileData.lawRuneAversion = generateLawRuneAversion(seededRandom, accountType);

        // === Interface interaction preferences ===
        // ~60% of players prefer hotkeys, ~40% are habitual clickers
        profileData.prefersHotkeys = seededRandom.nextDouble() < 0.60;
        
        // === Predictive hovering preferences ===
        // Base prediction rate: 40-90% (how often player hovers over next target while acting)
        // Some players are very "ahead" and always predict, others are more reactive
        profileData.basePredictionRate = 0.40 + seededRandom.nextDouble() * 0.50;
        
        // Click speed bias: 0.0-1.0 (0=slow/hesitant, 1=fast/snappy)
        // Affects distribution of INSTANT vs DELAYED clicks when prediction succeeds
        
        // === Inventory slot preferences ===
        // Players develop habits about which inventory regions they favor
        profileData.inventorySlotPreference = selectInventoryPreference(seededRandom);
        profileData.inventorySlotBiasStrength = 0.3 + seededRandom.nextDouble() * 0.5;  // 30-80%
        
        // Click position within slots - personal micro-habits
        // Most people click slightly off-center in consistent ways
        profileData.inventoryClickRowBias = 0.35 + seededRandom.nextDouble() * 0.30;  // 0.35-0.65
        profileData.inventoryClickColBias = 0.35 + seededRandom.nextDouble() * 0.30;  // 0.35-0.65
        profileData.predictionClickSpeedBias = seededRandom.nextDouble();
        
        // === Metrics ===
        profileData.sessionCount = 0;
        profileData.totalPlaytimeHours = 0;

        // === Environment fingerprinting (anti-detection) ===
        // Machine ID: 32 hex characters, deterministic per account
        byte[] machineIdBytes = new byte[16];
        seededRandom.nextBytes(machineIdBytes);
        StringBuilder machineIdBuilder = new StringBuilder();
        for (byte b : machineIdBytes) {
            machineIdBuilder.append(String.format("%02x", b));
        }
        profileData.machineId = machineIdBuilder.toString();

        // Screen resolution: Fixed to 720p for now
        profileData.screenResolution = "1280x720";

        // Display DPI: Random from common values
        int[] COMMON_DPIS = {96, 110, 120, 144};
        profileData.displayDpi = COMMON_DPIS[seededRandom.nextInt(COMMON_DPIS.length)];

        // Additional fonts: 2-5 random from pool
        String[] OPTIONAL_FONTS = {
            "firacode", "roboto", "ubuntu", "inconsolata",
            "lato", "open-sans", "cascadia-code", "hack",
            "jetbrains-mono", "droid", "wine", "cantarell"
        };
        int numFonts = 2 + seededRandom.nextInt(4); // 2-5 fonts
        List<String> availableFonts = new ArrayList<>(Arrays.asList(OPTIONAL_FONTS));
        Collections.shuffle(availableFonts, seededRandom);
        profileData.additionalFonts = new CopyOnWriteArrayList<>(
            availableFonts.subList(0, Math.min(numFonts, availableFonts.size()))
        );

        // Timezone: Default, user sets per account to match proxy
        profileData.timezone = "America/New_York";
        
        // === Chronotype (circadian rhythm preferences) ===
        profileData.chronotype = selectChronotype(seededRandom);
        profileData.peakHourOffset = -2.0 + seededRandom.nextDouble() * 4.0;  // -2 to +2 hours
        profileData.circadianStrength = 0.1 + seededRandom.nextDouble() * 0.4;  // 0.1-0.5
        
        // === Motor Learning (individual learning characteristics) ===
        // Some people learn motor skills faster/more than others
        profileData.motorLearningCapacity = 0.12 + seededRandom.nextDouble() * 0.13;  // 12-25% max improvement
        profileData.motorLearningTau = 200.0 + seededRandom.nextDouble() * 400.0;  // 200-600 minutes to ~63%
        // taskExperience starts empty - will be populated during gameplay

        // === UInput Device Presets ===
        // All bots use Steam Deck devices - common hardware, blends in well, 
        // less statistical variance in device fingerprints across the botnet.
        // Device selection simplified to always use STEAMDECK_MOUSE and STEAMDECK_KEYBOARD.

        log.info("Generated new profile: mouseSpeed={}, clickVar={}, breakThreshold={}, rituals={}",
                String.format("%.2f", profileData.mouseSpeedMultiplier),
                String.format("%.2f", profileData.clickVarianceModifier),
                String.format("%.2f", profileData.breakFatigueThreshold),
                profileData.sessionRituals);
    }

    // ========================================================================
    // Drift Methods
    // ========================================================================

    /**
     * Apply ±2% drift to profile values at session start.
     * Simulates natural variation in player behavior between sessions.
     */
    void applySessionDrift() {
        List<DriftChange> changes = new ArrayList<>();

        // Input characteristics
        double beforeMouseSpeed = profileData.mouseSpeedMultiplier;
        profileData.mouseSpeedMultiplier = applyDrift(
                profileData.mouseSpeedMultiplier, MIN_MOUSE_SPEED, MAX_MOUSE_SPEED);
        recordChange(changes, "mouseSpeedMultiplier", beforeMouseSpeed, profileData.mouseSpeedMultiplier);

        double beforeClickVar = profileData.clickVarianceModifier;
        profileData.clickVarianceModifier = applyDrift(
                profileData.clickVarianceModifier, MIN_CLICK_VARIANCE, MAX_CLICK_VARIANCE);
        recordChange(changes, "clickVarianceModifier", beforeClickVar, profileData.clickVarianceModifier);
        
        // WPM has smaller drift
        int wpmDrift = (int) Math.round(profileData.typingSpeedWPM * 
                randomization.uniformRandom(-0.02, 0.02));
        int beforeWpm = profileData.typingSpeedWPM;
        profileData.typingSpeedWPM = Randomization.clamp(
                profileData.typingSpeedWPM + wpmDrift, MIN_TYPING_WPM, MAX_TYPING_WPM);
        recordChange(changes, "typingSpeedWPM", beforeWpm, profileData.typingSpeedWPM);
        
        // Error rates
        double beforeMisclick = profileData.baseMisclickRate;
        double beforeTypo = profileData.baseTypoRate;
        profileData.baseMisclickRate = applyDrift(profileData.baseMisclickRate, 0.01, 0.03);
        profileData.baseTypoRate = applyDrift(profileData.baseTypoRate, 0.005, 0.02);
        recordChange(changes, "baseMisclickRate", beforeMisclick, profileData.baseMisclickRate);
        recordChange(changes, "baseTypoRate", beforeTypo, profileData.baseTypoRate);
        
        // Break threshold
        double beforeBreak = profileData.breakFatigueThreshold;
        profileData.breakFatigueThreshold = applyDrift(
                profileData.breakFatigueThreshold, MIN_BREAK_THRESHOLD, MAX_BREAK_THRESHOLD);
        recordChange(changes, "breakFatigueThreshold", beforeBreak, profileData.breakFatigueThreshold);
        
        // Run energy thresholds drift (small - behavioral habits are fairly stable)
        int beforeRunEnable = profileData.runEnableThreshold;
        int beforeRunDisable = profileData.runDisableThreshold;
        int runEnableDrift = (int) Math.round(profileData.runEnableThreshold * 
                randomization.uniformRandom(-SESSION_DRIFT_PERCENT, SESSION_DRIFT_PERCENT));
        int runDisableDrift = (int) Math.round(profileData.runDisableThreshold * 
                randomization.uniformRandom(-SESSION_DRIFT_PERCENT, SESSION_DRIFT_PERCENT));
        profileData.runEnableThreshold = Randomization.clamp(
                profileData.runEnableThreshold + runEnableDrift, 25, 65);
        profileData.runDisableThreshold = Randomization.clamp(
                profileData.runDisableThreshold + runDisableDrift, 5, 25);
        // Maintain hysteresis gap
        if (profileData.runEnableThreshold - profileData.runDisableThreshold < 15) {
            profileData.runDisableThreshold = Math.max(5, profileData.runEnableThreshold - 15);
        }
        recordChange(changes, "runEnableThreshold", beforeRunEnable, profileData.runEnableThreshold);
        recordChange(changes, "runDisableThreshold", beforeRunDisable, profileData.runDisableThreshold);
        
        // Walk pacing drift (small - movement habits are stable)
        double beforeWalkInterval = profileData.minWalkClickIntervalBase;
        profileData.minWalkClickIntervalBase = applyDrift(
                profileData.minWalkClickIntervalBase, 2.0, 6.0, SESSION_DRIFT_PERCENT);
        recordChange(changes, "minWalkClickIntervalBase", beforeWalkInterval, profileData.minWalkClickIntervalBase);
        
        // Camera angle drift (larger, ±10°)
        double beforeAngle = profileData.preferredCompassAngle;
        double angleDrift = randomization.uniformRandom(-10, 10);
        profileData.preferredCompassAngle = (profileData.preferredCompassAngle + angleDrift + 360) % 360;
        recordChange(changes, "preferredCompassAngle", beforeAngle, profileData.preferredCompassAngle);
        
        // Frequency drift
        double beforeXpFreq = profileData.xpCheckFrequency;
        double beforeInspectFreq = profileData.playerInspectionFrequency;
        profileData.xpCheckFrequency = applyDrift(profileData.xpCheckFrequency, 0, 20, 0.10);
        profileData.playerInspectionFrequency = applyDrift(profileData.playerInspectionFrequency, 0, 8, 0.10);
        recordChange(changes, "xpCheckFrequency", beforeXpFreq, profileData.xpCheckFrequency);
        recordChange(changes, "playerInspectionFrequency", beforeInspectFreq, profileData.playerInspectionFrequency);
        
        // Prediction hover drift (smaller variance - these are stable personality traits)
        double beforePredictionRate = profileData.basePredictionRate;
        double beforePredictionBias = profileData.predictionClickSpeedBias;
        profileData.basePredictionRate = applyDrift(
                profileData.basePredictionRate, 0.30, 0.95, 0.03);
        profileData.predictionClickSpeedBias = applyDrift(
                profileData.predictionClickSpeedBias, 0.0, 1.0, 0.03);
        recordChange(changes, "basePredictionRate", beforePredictionRate, profileData.basePredictionRate);
        recordChange(changes, "predictionClickSpeedBias", beforePredictionBias, profileData.predictionClickSpeedBias);
        
        // Wobble characteristics drift (small - physical traits are stable)
        double beforeWobbleFreq = profileData.wobbleFrequencyBase;
        double beforeWobbleVar = profileData.wobbleFrequencyVariance;
        double beforeWobbleAmp = profileData.wobbleAmplitudeModifier;
        profileData.wobbleFrequencyBase = applyDrift(profileData.wobbleFrequencyBase, 2.5, 4.0, 0.02);
        profileData.wobbleFrequencyVariance = applyDrift(profileData.wobbleFrequencyVariance, 0.3, 0.8, 0.02);
        // Bounds match MOTOR_TRAIT_PARAMS[MT_WOBBLE] = {1.05, 0.22, 0.70, 1.40}
        profileData.wobbleAmplitudeModifier = applyDrift(profileData.wobbleAmplitudeModifier, 0.70, 1.40, 0.02);
        recordChange(changes, "wobbleFrequencyBase", beforeWobbleFreq, profileData.wobbleFrequencyBase);
        recordChange(changes, "wobbleFrequencyVariance", beforeWobbleVar, profileData.wobbleFrequencyVariance);
        recordChange(changes, "wobbleAmplitudeModifier", beforeWobbleAmp, profileData.wobbleAmplitudeModifier);
        
        // Click timing drift (small - motor patterns are stable)
        // Bounds match MOTOR_TRAIT_PARAMS[MT_CLICK_DURATION] = {80.0, 10.0, 65.0, 95.0}
        double beforeClickMu = profileData.clickDurationMu;
        double beforeClickSigma = profileData.clickDurationSigma;
        double beforeClickTau = profileData.clickDurationTau;
        profileData.clickDurationMu = applyDrift(profileData.clickDurationMu, 65.0, 95.0, 0.02);
        profileData.clickDurationSigma = applyDrift(profileData.clickDurationSigma, 8.0, 18.0, 0.02);
        profileData.clickDurationTau = applyDrift(profileData.clickDurationTau, 3.0, 13.0, 0.02);
        recordChange(changes, "clickDurationMu", beforeClickMu, profileData.clickDurationMu);
        recordChange(changes, "clickDurationSigma", beforeClickSigma, profileData.clickDurationSigma);
        recordChange(changes, "clickDurationTau", beforeClickTau, profileData.clickDurationTau);
        
        // Cognitive delay drift (small - thinking speed is stable)
        // Bounds match MOTOR_TRAIT_PARAMS[MT_COGNITIVE_DELAY] = {120.0, 40.0, 60.0, 180.0}
        double beforeCogBase = profileData.cognitiveDelayBase;
        double beforeCogVar = profileData.cognitiveDelayVariance;
        profileData.cognitiveDelayBase = applyDrift(profileData.cognitiveDelayBase, 60.0, 180.0, 0.02);
        profileData.cognitiveDelayVariance = applyDrift(profileData.cognitiveDelayVariance, 0.3, 0.7, 0.02);
        recordChange(changes, "cognitiveDelayBase", beforeCogBase, profileData.cognitiveDelayBase);
        recordChange(changes, "cognitiveDelayVariance", beforeCogVar, profileData.cognitiveDelayVariance);
        
        // Motor speed correlation drift (very small - motor patterns are highly stable)
        double beforeMotorCorr = profileData.motorSpeedCorrelation;
        profileData.motorSpeedCorrelation = applyDrift(profileData.motorSpeedCorrelation, 0.5, 0.9, 0.01);
        recordChange(changes, "motorSpeedCorrelation", beforeMotorCorr, profileData.motorSpeedCorrelation);
        
        // Tick jitter drift (very small - perception is stable)
        double beforeJitterMu = profileData.jitterMu;
        double beforeJitterSigma = profileData.jitterSigma;
        double beforeJitterTau = profileData.jitterTau;
        profileData.jitterMu = applyDrift(profileData.jitterMu, 150.0, 400.0, 0.01);
        profileData.jitterSigma = applyDrift(profileData.jitterSigma, 20.0, 100.0, 0.01);
        profileData.jitterTau = applyDrift(profileData.jitterTau, 50.0, 300.0, 0.01);
        recordChange(changes, "jitterMu", beforeJitterMu, profileData.jitterMu);
        recordChange(changes, "jitterSigma", beforeJitterSigma, profileData.jitterSigma);
        recordChange(changes, "jitterTau", beforeJitterTau, profileData.jitterTau);
        
        // Tick skip/attention drift (very small - attention patterns are stable)
        double beforeTickSkip = profileData.tickSkipBaseProbability;
        double beforeLapse = profileData.attentionLapseProbability;
        double beforeAnticipation = profileData.anticipationProbability;
        profileData.tickSkipBaseProbability = applyDrift(profileData.tickSkipBaseProbability, 0.03, 0.08, 0.005);
        profileData.attentionLapseProbability = applyDrift(profileData.attentionLapseProbability, 0.005, 0.02, 0.002);
        profileData.anticipationProbability = applyDrift(profileData.anticipationProbability, 0.10, 0.20, 0.01);
        recordChange(changes, "tickSkipBaseProbability", beforeTickSkip, profileData.tickSkipBaseProbability);
        recordChange(changes, "attentionLapseProbability", beforeLapse, profileData.attentionLapseProbability);
        recordChange(changes, "anticipationProbability", beforeAnticipation, profileData.anticipationProbability);
        
        // Path complexity drift (very small - motor patterns are stable)
        double beforeHesitation = profileData.hesitationProbability;
        double beforeSubmovement = profileData.submovementProbability;
        profileData.hesitationProbability = applyDrift(profileData.hesitationProbability, 0.10, 0.25, 0.005);
        profileData.submovementProbability = applyDrift(profileData.submovementProbability, 0.15, 0.30, 0.005);
        recordChange(changes, "hesitationProbability", beforeHesitation, profileData.hesitationProbability);
        recordChange(changes, "submovementProbability", beforeSubmovement, profileData.submovementProbability);

        // === Correlated Motor Traits Drift (Geometric Brownian Motion) ===
        // Apply correlated drift to all 8 motor traits using Cholesky decomposition.
        // This preserves the correlation structure across sessions.
        applyCorrelatedMotorTraitDrift(changes);

        // === Other Physiological Drift ===
        // Tremor amplitude drift (caffeine, fatigue, stress - highly variable)
        double beforeTremorAmp = profileData.physTremorAmp;
        profileData.physTremorAmp = applyDrift(profileData.physTremorAmp, 0.2, 1.5, 0.10);
        recordChange(changes, "physTremorAmp", beforeTremorAmp, profileData.physTremorAmp);
        
        // Feedback delay drift (very stable - visual processing speed doesn't change much)
        int beforeFeedbackDelay = profileData.feedbackDelaySamples;
        int feedbackDrift = (int) Math.round(profileData.feedbackDelaySamples * 
                randomization.uniformRandom(-0.01, 0.01));
        profileData.feedbackDelaySamples = Randomization.clamp(
                profileData.feedbackDelaySamples + feedbackDrift, 10, 25);
        recordChange(changes, "feedbackDelaySamples", beforeFeedbackDelay, profileData.feedbackDelaySamples);
        
        // Motor learning parameters drift (very small - learning rate is stable)
        double beforeLearningCapacity = profileData.motorLearningCapacity;
        double beforeLearningTau = profileData.motorLearningTau;
        profileData.motorLearningCapacity = applyDrift(
                profileData.motorLearningCapacity, 0.10, 0.30, 0.01);
        profileData.motorLearningTau = applyDrift(
                profileData.motorLearningTau, 150.0, 700.0, 0.01);
        recordChange(changes, "motorLearningCapacity", beforeLearningCapacity, profileData.motorLearningCapacity);
        recordChange(changes, "motorLearningTau", beforeLearningTau, profileData.motorLearningTau);

        // Tremor frequency drift (very stable biological constant)
        double beforeTremorFreq = profileData.physTremorFreq;
        profileData.physTremorFreq = applyDrift(profileData.physTremorFreq, 8.0, 12.0, 0.01);
        recordChange(changes, "physTremorFreq", beforeTremorFreq, profileData.physTremorFreq);

        recordDrift(DriftType.SESSION, changes);
        log.debug("Applied session drift (session #{})", profileData.sessionCount);
    }

    /**
     * Apply long-term skill improvement drift based on playtime.
     * Per REQUIREMENTS.md 3.4.6: gradual improvement over weeks/months.
     *
     * @param hoursPlayed hours played in this session
     */
    public void applyLongTermDrift(double hoursPlayed) {
        double oldPlaytime = profileData.totalPlaytimeHours;
        profileData.totalPlaytimeHours += hoursPlayed;
        List<DriftChange> changes = new ArrayList<>();
        recordChange(changes, "totalPlaytimeHours", oldPlaytime, profileData.totalPlaytimeHours);
        
        // Calculate how many drift periods we've crossed
        int oldPeriods = (int) (oldPlaytime / LONG_TERM_DRIFT_HOURS);
        int newPeriods = (int) (profileData.totalPlaytimeHours / LONG_TERM_DRIFT_HOURS);
        
        if (newPeriods > oldPeriods) {
            int periodsToApply = newPeriods - oldPeriods;
            
            for (int i = 0; i < periodsToApply; i++) {
                // Mouse speed improvement: +1-3% per 20 hours (cap at 1.3)
                double speedImprovement = randomization.uniformRandom(0.01, 0.03);
                double beforeSpeed = profileData.mouseSpeedMultiplier;
                profileData.mouseSpeedMultiplier = Math.min(MAX_MOUSE_SPEED,
                        profileData.mouseSpeedMultiplier + speedImprovement);
                recordChange(changes, "mouseSpeedMultiplier", beforeSpeed, profileData.mouseSpeedMultiplier);
                
                // Click precision improvement: reduce variance by 2-5% per 50 hours
                // (apply partial improvement every 20 hours)
                double varianceReduction = randomization.uniformRandom(0.008, 0.02);
                double beforeVariance = profileData.clickVarianceModifier;
                profileData.clickVarianceModifier = Math.max(MIN_CLICK_VARIANCE,
                        profileData.clickVarianceModifier - varianceReduction);
                recordChange(changes, "clickVarianceModifier", beforeVariance, profileData.clickVarianceModifier);
            }
            
            log.info("Applied long-term drift after {} hours total playtime", 
                    profileData.totalPlaytimeHours);
        }

        recordDrift(DriftType.LONG_TERM, changes);
    }

    /**
     * Apply ±drift% to a value within bounds.
     */
    private double applyDrift(double value, double min, double max) {
        return applyDrift(value, min, max, SESSION_DRIFT_PERCENT);
    }

    private double applyDrift(double value, double min, double max, double driftPct) {
        double drift = value * randomization.uniformRandom(-driftPct, driftPct);
        return Randomization.clamp(value + drift, min, max);
    }

    private void recordChange(List<DriftChange> changes, String field, double before, double after) {
        if (changes == null) {
            return;
        }
        if (Double.compare(before, after) != 0) {
            changes.add(new DriftChange(field, before, after));
        }
    }

    /**
     * Apply correlated ±2% drift to all 8 motor traits using Geometric Brownian Motion.
     * This preserves the correlation structure by transforming independent noise via Cholesky.
     *
     * Mathematical approach:
     * 1. Generate 8 independent N(0,1) samples: Z ~ N(0, I)
     * 2. Transform to correlated noise: ε = L·Z where L is Cholesky decomposition of R
     * 3. Scale by drift magnitude: ε *= σ (σ = 0.02 for ±2% drift)
     * 4. Apply geometric drift: trait_new = trait_old * exp(ε)
     * 5. Reflect into bounds if necessary
     *
     * This ensures correlation is preserved exactly across sessions, unlike independent drift
     * which causes correlation decay by factor 1/(1 + p²/3) per session.
     *
     * @param changes List to record changes for audit trail
     */
    private void applyCorrelatedMotorTraitDrift(List<DriftChange> changes) {
        // Validate Cholesky matrix exists
        if (profileData.motorCorrelationCholesky == null) {
            log.error("Cholesky decomposition missing, cannot apply correlated drift");
            return;
        }

        // Extract current motor trait values into array
        double[] currentTraits = new double[MOTOR_TRAIT_COUNT];
        currentTraits[MT_MOUSE_SPEED] = profileData.mouseSpeedMultiplier;
        currentTraits[MT_CLICK_DURATION] = profileData.clickDurationMu;
        currentTraits[MT_COGNITIVE_DELAY] = profileData.cognitiveDelayBase;
        currentTraits[MT_OVERSHOOT] = profileData.overshootProbability;
        currentTraits[MT_WOBBLE] = profileData.wobbleAmplitudeModifier;
        currentTraits[MT_VELOCITY_FLOW] = profileData.velocityFlow;
        currentTraits[MT_FITTS_B] = profileData.fittsB;
        currentTraits[MT_WALK_INTERVAL] = profileData.minWalkClickIntervalBase;

        // Generate 8 independent N(0,1) samples
        double[] independentNoise = new double[MOTOR_TRAIT_COUNT];
        for (int i = 0; i < MOTOR_TRAIT_COUNT; i++) {
            independentNoise[i] = randomization.gaussianRandom(0.0, 1.0);
        }

        // Transform to correlated noise: ε = L·Z
        double[] correlatedNoise = matrixVectorMultiply(
                profileData.motorCorrelationCholesky, independentNoise);

        // Scale by drift magnitude (σ = 0.02 for ±2% geometric drift)
        double sigma = SESSION_DRIFT_PERCENT;
        for (int i = 0; i < MOTOR_TRAIT_COUNT; i++) {
            correlatedNoise[i] *= sigma;
        }

        // Apply geometric drift: trait_new = trait_old * exp(ε)
        double[] newTraits = new double[MOTOR_TRAIT_COUNT];
        for (int i = 0; i < MOTOR_TRAIT_COUNT; i++) {
            newTraits[i] = currentTraits[i] * Math.exp(correlatedNoise[i]);
        }

        // Reflect into bounds if necessary
        double[][] bounds = new double[MOTOR_TRAIT_COUNT][2];
        for (int i = 0; i < MOTOR_TRAIT_COUNT; i++) {
            bounds[i][0] = MOTOR_TRAIT_PARAMS[i][2]; // min
            bounds[i][1] = MOTOR_TRAIT_PARAMS[i][3]; // max
        }

        for (int i = 0; i < MOTOR_TRAIT_COUNT; i++) {
            newTraits[i] = reflectIntoBounds(newTraits[i], bounds[i][0], bounds[i][1]);
        }

        // Write traits back to profileData and record changes
        recordChange(changes, "mouseSpeedMultiplier",
                profileData.mouseSpeedMultiplier, newTraits[MT_MOUSE_SPEED]);
        profileData.mouseSpeedMultiplier = newTraits[MT_MOUSE_SPEED];

        recordChange(changes, "clickDurationMu",
                profileData.clickDurationMu, newTraits[MT_CLICK_DURATION]);
        profileData.clickDurationMu = newTraits[MT_CLICK_DURATION];

        recordChange(changes, "cognitiveDelayBase",
                profileData.cognitiveDelayBase, newTraits[MT_COGNITIVE_DELAY]);
        profileData.cognitiveDelayBase = newTraits[MT_COGNITIVE_DELAY];

        recordChange(changes, "overshootProbability",
                profileData.overshootProbability, newTraits[MT_OVERSHOOT]);
        profileData.overshootProbability = newTraits[MT_OVERSHOOT];

        recordChange(changes, "wobbleAmplitudeModifier",
                profileData.wobbleAmplitudeModifier, newTraits[MT_WOBBLE]);
        profileData.wobbleAmplitudeModifier = newTraits[MT_WOBBLE];

        recordChange(changes, "velocityFlow",
                profileData.velocityFlow, newTraits[MT_VELOCITY_FLOW]);
        profileData.velocityFlow = newTraits[MT_VELOCITY_FLOW];

        recordChange(changes, "fittsB",
                profileData.fittsB, newTraits[MT_FITTS_B]);
        profileData.fittsB = newTraits[MT_FITTS_B];

        recordChange(changes, "minWalkClickIntervalBase",
                profileData.minWalkClickIntervalBase, newTraits[MT_WALK_INTERVAL]);
        profileData.minWalkClickIntervalBase = newTraits[MT_WALK_INTERVAL];
    }

    // ========================================================================
    // Matrix Utilities for Correlated Drift
    // ========================================================================

    /**
     * Generate constrained random correlation matrix satisfying human motor constraints.
     * Uses rejection sampling: generates random matrices until one satisfies all constraints.
     *
     * Expected acceptance rate: ~0.05-0.1% (requires ~1000-2000 attempts on average)
     * Computational cost: ~500-1000ms per bot (acceptable for one-time generation)
     *
     * @param rng Random number generator seeded per-bot
     * @return 8×8 correlation matrix satisfying all MOTOR_CORRELATION_CONSTRAINTS
     */
    private double[][] generateConstrainedRandomCorrelationMatrix(Random rng) {
        int maxAttempts = 10000;  // With relaxed constraints, should succeed much faster

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Generate random positive-definite correlation matrix
                double[][] R = generateRandomCorrelationMatrix(MOTOR_TRAIT_COUNT, rng);

                // Check against all 28 human motor constraints
                if (satisfiesAllConstraints(R, MOTOR_CORRELATION_CONSTRAINTS)) {
                    if (attempt > 1) {
                        log.info("Generated valid correlation matrix after {} attempts", attempt);
                    }
                    return R;
                }
            } catch (Exception e) {
                // Matrix generation can fail, continue trying
                continue;
            }
        }

        // FAIL LOUDLY - constraints are too strict
        throw new IllegalStateException(
            "Failed to generate valid correlation matrix after " + maxAttempts + " attempts. " +
            "Constraints are too restrictive - adjust MOTOR_CORRELATION_CONSTRAINTS.");
    }

    /**
     * Generate random positive-definite correlation matrix using eigenvalue method.
     * Matrix is symmetric with 1.0 on diagonal and eigenvalues > 0.
     *
     * @param size Matrix dimension (8 for motor traits)
     * @param rng Random number generator
     * @return Random correlation matrix
     */
    private double[][] generateRandomCorrelationMatrix(int size, Random rng) {
        // Generate random eigenvalues (must be positive for positive-definite)
        double[] eigenvalues = new double[size];
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            eigenvalues[i] = 0.1 + rng.nextDouble() * 0.9;  // Range [0.1, 1.0]
            sum += eigenvalues[i];
        }
        // Normalize eigenvalues to sum to 'size' (required for correlation matrix)
        for (int i = 0; i < size; i++) {
            eigenvalues[i] = eigenvalues[i] / sum * size;
        }

        // Generate random orthogonal matrix using QR decomposition of random matrix
        double[][] Q = randomOrthogonalMatrix(size, rng);

        // R = Q * diag(eigenvalues) * Q^T
        double[][] R = matrixMultiply(Q, diagonal(eigenvalues), transpose(Q));

        // Normalize to correlation matrix (diagonal = 1)
        return normalizeToCorrelation(R);
    }

    /**
     * Generate random orthogonal matrix using QR decomposition.
     */
    private double[][] randomOrthogonalMatrix(int size, Random rng) {
        // Generate random matrix
        double[][] A = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                A[i][j] = rng.nextGaussian();
            }
        }

        // QR decomposition via Gram-Schmidt
        double[][] Q = new double[size][size];
        for (int j = 0; j < size; j++) {
            // Copy column j
            double[] v = new double[size];
            for (int i = 0; i < size; i++) {
                v[i] = A[i][j];
            }

            // Orthogonalize against previous columns
            for (int k = 0; k < j; k++) {
                double dot = 0.0;
                for (int i = 0; i < size; i++) {
                    dot += Q[i][k] * v[i];
                }
                for (int i = 0; i < size; i++) {
                    v[i] -= dot * Q[i][k];
                }
            }

            // Normalize
            double norm = 0.0;
            for (int i = 0; i < size; i++) {
                norm += v[i] * v[i];
            }
            norm = Math.sqrt(norm);

            if (norm > 1e-10) {
                for (int i = 0; i < size; i++) {
                    Q[i][j] = v[i] / norm;
                }
            } else {
                // Degenerate case, use canonical basis vector
                Q[j][j] = 1.0;
            }
        }

        return Q;
    }

    /**
     * Create diagonal matrix from eigenvalues.
     */
    private double[][] diagonal(double[] eigenvalues) {
        int n = eigenvalues.length;
        double[][] D = new double[n][n];
        for (int i = 0; i < n; i++) {
            D[i][i] = eigenvalues[i];
        }
        return D;
    }

    /**
     * Matrix transpose.
     */
    private double[][] transpose(double[][] A) {
        int m = A.length;
        int n = A[0].length;
        double[][] T = new double[n][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                T[j][i] = A[i][j];
            }
        }
        return T;
    }

    /**
     * Matrix multiplication: C = A * B * B^T (for 3-matrix product).
     */
    private double[][] matrixMultiply(double[][] A, double[][] B, double[][] BT) {
        // First compute AB
        double[][] AB = matrixMultiply2(A, B);
        // Then compute (AB) * BT
        return matrixMultiply2(AB, BT);
    }

    /**
     * Matrix multiplication: C = A * B.
     */
    private double[][] matrixMultiply2(double[][] A, double[][] B) {
        int m = A.length;
        int n = B[0].length;
        int p = A[0].length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0.0;
                for (int k = 0; k < p; k++) {
                    sum += A[i][k] * B[k][j];
                }
                C[i][j] = sum;
            }
        }
        return C;
    }

    /**
     * Normalize matrix to correlation matrix (diagonal = 1).
     */
    private double[][] normalizeToCorrelation(double[][] R) {
        int n = R.length;
        double[][] C = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                C[i][j] = R[i][j] / Math.sqrt(R[i][i] * R[j][j]);
            }
        }
        return C;
    }

    /**
     * Check if matrix satisfies all motor correlation constraints.
     */
    private boolean satisfiesAllConstraints(double[][] R, CorrelationConstraint[] constraints) {
        for (CorrelationConstraint c : constraints) {
            double r = R[c.row][c.col];
            if (r < c.min || r > c.max) {
                return false;
            }
            // Check symmetry
            double r_sym = R[c.col][c.row];
            if (Math.abs(r - r_sym) > 1e-6) {
                return false;
            }
        }
        return true;
    }

    /**
     * Cholesky decomposition: Σ = L·L^T.
     * Returns lower triangular matrix L.
     *
     * @param A Symmetric positive-definite matrix
     * @return Lower triangular Cholesky factor
     * @throws IllegalArgumentException if matrix not positive definite
     */
    private double[][] choleskyDecomposition(double[][] A) {
        int n = A.length;
        double[][] L = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                for (int k = 0; k < j; k++) {
                    sum += L[i][k] * L[j][k];
                }

                if (i == j) {
                    double diag = A[i][i] - sum;
                    if (diag <= 0) {
                        throw new IllegalArgumentException(
                            String.format("Matrix not positive definite at diagonal %d (value: %.6f)", i, diag));
                    }
                    L[i][i] = Math.sqrt(diag);
                } else {
                    L[i][j] = (A[i][j] - sum) / L[j][j];
                }
            }
        }

        return L;
    }

    /**
     * Matrix-vector multiplication: y = L·z (for lower triangular L).
     */
    private double[] matrixVectorMultiply(double[][] L, double[] z) {
        int n = z.length;
        double[] result = new double[n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {  // L is lower triangular
                result[i] += L[i][j] * z[j];
            }
        }

        return result;
    }

    /**
     * Reflection boundary enforcement (better than clamping for preserving distribution).
     */
    private double reflectIntoBounds(double value, double min, double max) {
        if (value < min) {
            double excess = min - value;
            return Math.min(min + excess, max);
        } else if (value > max) {
            double excess = value - max;
            return Math.max(max - excess, min);
        }
        return value;
    }

    private void recordDrift(DriftType type, List<DriftChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        if (profileData.driftHistory == null) {
            profileData.driftHistory = new CopyOnWriteArrayList<>();
        }
        profileData.driftHistory.add(0, new DriftRecord(Instant.now(), type, new ArrayList<>(changes)));
        while (profileData.driftHistory.size() > MAX_DRIFT_HISTORY) {
            profileData.driftHistory.remove(profileData.driftHistory.size() - 1);
        }
    }

    // ========================================================================
    // Break Activity Selection
    // ========================================================================

    /**
     * Select a break activity based on weighted preferences.
     * 
     * @return the activity key to perform
     */
    public String selectBreakActivity() {
        return selectWeighted(profileData.breakActivityWeights);
    }

    /**
     * Reinforce a break activity preference after it was performed.
     * Increases weight by 0.5%, capped at 85%.
     * 
     * @param activity the activity that was performed
     */
    public void reinforceBreakActivity(String activity) {
        reinforceWeight(profileData.breakActivityWeights, activity);
    }

    /**
     * Get all available break activities with their weights.
     * 
     * @return unmodifiable map of activity weights
     */
    public Map<String, Double> getBreakActivityWeights() {
        return Collections.unmodifiableMap(profileData.breakActivityWeights);
    }

    // ========================================================================
    // Action Sequence Selection
    // ========================================================================

    /**
     * Select a banking sequence type based on profile preferences.
     * 
     * @return sequence type (TYPE_A, TYPE_B, TYPE_C)
     */
    public String selectBankingSequence() {
        return selectWeighted(profileData.bankingSequenceWeights);
    }

    /**
     * Reinforce a banking sequence preference.
     * 
     * @param sequence the sequence that was used
     */
    public void reinforceBankingSequence(String sequence) {
        reinforceWeight(profileData.bankingSequenceWeights, sequence);
    }

    /**
     * Select a combat preparation sequence type.
     * 
     * @return sequence type (TYPE_A, TYPE_B, TYPE_C)
     */
    public String selectCombatPrepSequence() {
        return selectWeighted(profileData.combatPrepSequenceWeights);
    }

    /**
     * Reinforce a combat prep sequence preference.
     * 
     * @param sequence the sequence that was used
     */
    public void reinforceCombatPrepSequence(String sequence) {
        reinforceWeight(profileData.combatPrepSequenceWeights, sequence);
    }

    // ========================================================================
    // Session State
    // ========================================================================

    /**
     * Check if this is a fresh session (>15 min since last logout).
     * Fresh sessions should execute session rituals.
     * 
     * @return true if fresh session
     */
    public boolean isFreshSession() {
        if (profileData.lastLogout == null) {
            return true;  // First session ever
        }
        Duration sinceLogout = Duration.between(profileData.lastLogout, Instant.now());
        return sinceLogout.compareTo(FRESH_SESSION_THRESHOLD) > 0;
    }

    /**
     * Record logout time for fresh session detection.
     */
    public void recordLogout() {
        profileData.lastLogout = Instant.now();
        
        // Record playtime for this session
        if (sessionStartTime != null) {
            Duration sessionDuration = Duration.between(sessionStartTime, Instant.now());
            applyLongTermDrift(sessionDuration.toHours() + sessionDuration.toMinutesPart() / 60.0);
        }
        
        save();
    }

    /**
     * Get time since session started.
     * 
     * @return duration since login
     */
    public Duration getSessionDuration() {
        if (sessionStartTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(sessionStartTime, Instant.now());
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    /**
     * Save profile to disk.
     */
    public void save() {
        if (accountHash == null || accountHash.equals("default")) {
            return;
        }
        saveProfile(getProfilePath());
    }

    /**
     * Schedule periodic saves every 5 minutes.
     * Cancels any previous scheduled save task to prevent memory leak.
     */
    private void schedulePersistence() {
        // Cancel previous save task if it exists
        if (saveTask != null && !saveTask.isCancelled()) {
            saveTask.cancel(false);
            log.debug("Cancelled previous save task");
        }
        
        // Schedule new save task
        saveTask = saveExecutor.scheduleAtFixedRate(
                this::save,
                SAVE_INTERVAL_MINUTES,
                SAVE_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
    }

    private void loadProfile(Path path) {
        try {
            String json = Files.readString(path);
            ProfileData loaded = gson.fromJson(json, ProfileData.class);
            if (loaded != null && loaded.isValid()) {
                // Handle schema migration if needed
                if (loaded.schemaVersion < CURRENT_SCHEMA_VERSION) {
                    migrateProfile(loaded);
                }
                // Convert to thread-safe collections (Gson deserializes to standard collections)
                ensureThreadSafeCollections(loaded);
                if (verifyChecksum(loaded)) {
                    this.profileData = loaded;
                    log.info("Loaded profile from: {} (session #{}, {} hours played)", 
                            path, loaded.sessionCount, String.format("%.1f", loaded.totalPlaytimeHours));
                } else if (!tryLoadBackup(path)) {
                    log.warn("Checksum validation failed for {}, regenerating profile", path);
                    generateNewProfile(accountHash, null);
                }
            } else {
                log.warn("Invalid profile data at {}, regenerating", path);
                generateNewProfile(accountHash, null);
            }
        } catch (IOException e) {
            log.warn("Failed to load profile, generating new: {}", e.getMessage());
            generateNewProfile(accountHash, null);
        }
    }

    /**
     * Ensure all collections in ProfileData are thread-safe.
     * Gson deserializes to standard collections, so we need to convert them.
     */
    private void ensureThreadSafeCollections(ProfileData data) {
        // Convert lists to CopyOnWriteArrayList
        if (data.preferredIdlePositions != null && !(data.preferredIdlePositions instanceof CopyOnWriteArrayList)) {
            data.preferredIdlePositions = new CopyOnWriteArrayList<>(data.preferredIdlePositions);
        }
        if (data.sessionRituals != null && !(data.sessionRituals instanceof CopyOnWriteArrayList)) {
            data.sessionRituals = new CopyOnWriteArrayList<>(data.sessionRituals);
        }
        
        // Convert maps to ConcurrentHashMap
        if (data.breakActivityWeights != null && !(data.breakActivityWeights instanceof ConcurrentHashMap)) {
            data.breakActivityWeights = new ConcurrentHashMap<>(data.breakActivityWeights);
        }
        if (data.bankingSequenceWeights != null && !(data.bankingSequenceWeights instanceof ConcurrentHashMap)) {
            data.bankingSequenceWeights = new ConcurrentHashMap<>(data.bankingSequenceWeights);
        }
        if (data.combatPrepSequenceWeights != null && !(data.combatPrepSequenceWeights instanceof ConcurrentHashMap)) {
            data.combatPrepSequenceWeights = new ConcurrentHashMap<>(data.combatPrepSequenceWeights);
        }
        if (data.teleportMethodWeights != null && !(data.teleportMethodWeights instanceof ConcurrentHashMap)) {
            data.teleportMethodWeights = new ConcurrentHashMap<>(data.teleportMethodWeights);
        }
        if (data.driftHistory == null) {
            data.driftHistory = new CopyOnWriteArrayList<>();
        } else if (!(data.driftHistory instanceof CopyOnWriteArrayList)) {
            data.driftHistory = new CopyOnWriteArrayList<>(data.driftHistory);
        }
    }

    private void saveProfile(Path path) {
        try {
            Files.createDirectories(path.getParent());

            // Backup existing file
            if (Files.exists(path)) {
                Path backup = Paths.get(path.toString() + ".bak");
                Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            profileData.checksum = computeChecksum(profileData);
            String json = gson.toJson(profileData);
            Files.writeString(path, json);
            log.debug("Saved profile to: {}", path);
        } catch (IOException e) {
            log.error("Failed to save profile: {}", e.getMessage());
        }
    }

    /**
    * Compute a stable checksum for the given profile data. The checksum field itself
    * is excluded from the hash to avoid self-reference.
    */
    private String computeChecksum(ProfileData data) {
        if (data == null) {
            return "";
        }
        String json = toCanonicalJson(data);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("Checksum algorithm unavailable, skipping checksum");
            return "";
        }
    }

    /**
    * Verify checksum and update it if missing. Returns false on mismatch.
    */
    private boolean verifyChecksum(ProfileData data) {
        if (data == null) {
            return false;
        }
        String computed = computeChecksum(data);
        if (data.checksum == null || data.checksum.isEmpty()) {
            data.checksum = computed;
            return true;
        }
        boolean matches = data.checksum.equals(computed);
        if (matches) {
            data.checksum = computed;
        }
        return matches;
    }

    private String toCanonicalJson(ProfileData data) {
        if (data == null) {
            return "";
        }
        String original = data.checksum;
        data.checksum = null;
        JsonElement tree = gson.toJsonTree(data);
        data.checksum = original;
        JsonElement canonical = canonicalize(tree);
        return gson.toJson(canonical);
    }

    private JsonElement canonicalize(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonPrimitive()) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            JsonArray copy = new JsonArray();
            for (JsonElement e : arr) {
                copy.add(canonicalize(e));
            }
            return copy;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject sorted = new JsonObject();
            List<String> keys = new ArrayList<>(obj.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                sorted.add(key, canonicalize(obj.get(key)));
            }
            return sorted;
        }
        return element;
    }

    /**
    * Attempt to load a backup profile when the primary file fails checksum or validation.
    *
    * @param path primary profile path
    * @return true if a valid backup was loaded
    */
    private boolean tryLoadBackup(Path path) {
        Path backup = Paths.get(path.toString() + ".bak");
        if (!Files.exists(backup)) {
            return false;
        }

        try {
            String backupJson = Files.readString(backup);
            ProfileData backupData = gson.fromJson(backupJson, ProfileData.class);
            if (backupData != null && backupData.isValid()) {
                ensureThreadSafeCollections(backupData);
                if (verifyChecksum(backupData)) {
                    this.profileData = backupData;
                    log.warn("Loaded profile from backup after primary validation failure");
                    return true;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load backup profile: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Save the profile to disk using the default path.
     * Called periodically and on logout.
     */
    public void saveProfile() {
        if (accountHash == null || accountHash.isEmpty()) {
            throw new IllegalStateException("Account hash not initialized for profile save");
        }
        saveProfile(getProfilePath());
    }

    private void migrateProfile(ProfileData data) {
        // Future: handle schema migrations
        log.info("Migrating profile from schema v{} to v{}", 
                data.schemaVersion, CURRENT_SCHEMA_VERSION);
        data.schemaVersion = CURRENT_SCHEMA_VERSION;
    }

    private Path getProfilePath() {
        // Hardcoded bolt-launcher RuneLite path (required environment)
        Path base = Paths.get("/home/runelite/.local/share/bolt-launcher/.runelite/rocinante/profiles");
        return base.resolve(accountHash + ".json");
    }

    // ========================================================================
    // Seed/Hash Generation
    // ========================================================================

    /**
     * Generate a cryptographically random seed for profile generation.
     * 
     * CRITICAL: This must be truly random to prevent prediction attacks.
     * An attacker who knows the username should NOT be able to predict
     * any profile characteristics.
     * 
     * @return 64-bit cryptographically random seed
     */
    private long generateCryptographicSeed() {
        java.security.SecureRandom secureRandom = new java.security.SecureRandom();
        return secureRandom.nextLong();
    }

    /**
     * @deprecated Use {@link #generateCryptographicSeed()} instead.
     * Kept for backward compatibility with profile migration.
     */
    @Deprecated
    private long generateSeedFromAccountName(String accountName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String salted = accountName + "_rocinante_behavioral_v1";
            byte[] hash = digest.digest(salted.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (hash[i] & 0xFF);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            return accountName.hashCode();
        }
    }

    private String hashAccountName(String accountName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(accountName.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hexString.append(String.format("%02x", hash[i]));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(accountName.hashCode());
        }
    }

    // ========================================================================
    // Profile Generation Helpers
    // ========================================================================

    private List<String> selectRandomIdlePositions(Random seededRandom, int count) {
        List<ScreenRegion> available = new ArrayList<>(Arrays.asList(ScreenRegion.getDefaultIdleRegions()));
        available.add(ScreenRegion.PRAYER);
        available.add(ScreenRegion.EQUIPMENT);
        available.add(ScreenRegion.XP_TRACKER);

        List<String> selected = new ArrayList<>();
        for (int i = 0; i < count && !available.isEmpty(); i++) {
            int index = seededRandom.nextInt(available.size());
            selected.add(available.remove(index).name());
        }
        return selected;
    }

    private String selectPitchPreference(Random seededRandom) {
        double roll = seededRandom.nextDouble();
        if (roll < 0.3) return "HIGH";
        if (roll < 0.7) return "MEDIUM";
        return "LOW";
    }

    private String selectCameraHoldDirection(Random seededRandom) {
        double roll = seededRandom.nextDouble();
        if (roll < 0.35) return "LEFT_BIAS";
        if (roll < 0.70) return "RIGHT_BIAS";
        return "NO_PREFERENCE";
    }

    private String selectCameraHoldSpeed(Random seededRandom) {
        double roll = seededRandom.nextDouble();
        if (roll < 0.25) return "SLOW";
        if (roll < 0.75) return "MEDIUM";
        return "FAST";
    }

    private String selectInventoryPreference(Random seededRandom) {
        // Distribution based on natural hand positioning and screen layout
        // Center and bottom-right are most common (right-handed mouse users)
        double roll = seededRandom.nextDouble();
        if (roll < 0.10) return "TOP_LEFT";
        if (roll < 0.20) return "TOP_RIGHT";
        if (roll < 0.50) return "CENTER";        // Most common - easy access
        if (roll < 0.65) return "BOTTOM_LEFT";
        if (roll < 0.90) return "BOTTOM_RIGHT";  // Second most common - near action bar
        return "RANDOM";  // Some players have no strong preference
    }

    private String selectChronotype(Random seededRandom) {
        // Distribution roughly matches real population chronotypes
        // Most people are "neutral" with slight variations
        double roll = seededRandom.nextDouble();
        if (roll < 0.25) return "EARLY_BIRD";   // ~25% are morning people
        if (roll < 0.55) return "NEUTRAL";      // ~30% are flexible
        return "NIGHT_OWL";                      // ~45% are evening people (especially gamers!)
    }

    private List<String> selectSessionRituals(Random seededRandom) {
        List<String> allRituals = Arrays.asList(
                "BANK_CHECK", "SKILL_TAB_CHECK", "FRIENDS_LIST_CHECK",
                "EQUIPMENT_REVIEW", "INVENTORY_ORGANIZE", "WORLD_CHECK"
        );
        
        int numRituals = 2 + seededRandom.nextInt(3);  // 2-4 rituals
        List<String> available = new ArrayList<>(allRituals);
        List<String> selected = new ArrayList<>();
        
        for (int i = 0; i < numRituals && !available.isEmpty(); i++) {
            int index = seededRandom.nextInt(available.size());
            selected.add(available.remove(index));
        }
        return selected;
    }

    private Map<String, Double> generateBreakActivityWeights(Random seededRandom) {
        Map<String, Double> weights = new LinkedHashMap<>();
        
        // Generate random weights that sum roughly to 1
        List<String> activities = Arrays.asList(
                "SKILLS_TAB_HOVER", "INVENTORY_HOVER", "EQUIPMENT_CHECK",
                "FRIENDS_LIST_CHECK", "CAMERA_DRIFT", "PURE_AFK",
                "XP_TRACKER_HOVER", "CHAT_SCROLL", "MINIMAP_DRAG"
        );
        
        double totalWeight = 0;
        for (String activity : activities) {
            double weight = 0.05 + seededRandom.nextDouble() * 0.25;  // 5-30% each
            weights.put(activity, weight);
            totalWeight += weight;
        }
        
        // Normalize to sum to 1.0
        for (String activity : activities) {
            weights.put(activity, weights.get(activity) / totalWeight);
        }
        
        return weights;
    }

    private Map<String, Double> generateSequenceWeights(Random seededRandom, List<String> options) {
        Map<String, Double> weights = new LinkedHashMap<>();
        
        // One dominant preference (40-60%), others split remainder
        int dominant = seededRandom.nextInt(options.size());
        double dominantWeight = 0.40 + seededRandom.nextDouble() * 0.20;
        
        double remaining = 1.0 - dominantWeight;
        int othersCount = options.size() - 1;
        
        for (int i = 0; i < options.size(); i++) {
            if (i == dominant) {
                weights.put(options.get(i), dominantWeight);
            } else {
                // Split remaining somewhat randomly
                double weight = remaining / othersCount * (0.5 + seededRandom.nextDouble());
                weights.put(options.get(i), weight);
            }
        }
        
        // Normalize
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        for (String opt : options) {
            weights.put(opt, weights.get(opt) / total);
        }
        
        return weights;
    }

    /**
     * Generate teleport method weights based on account type.
     * Per REQUIREMENTS.md 3.6:
     * - HCIM: fairy_ring: 0.70, house_portal: 0.20, teleport_tablet: 0.10
     * - Ironman: fairy_ring: 0.50, spellbook: 0.25, house_portal: 0.15, teleport_tablet: 0.10
     * - Normal: Random distribution
     */
    private Map<String, Double> generateTeleportWeights(Random seededRandom, com.rocinante.behavior.AccountType accountType) {
        Map<String, Double> weights = new LinkedHashMap<>();
        
        if (accountType != null && accountType.isHardcore()) {
            // HCIM: Strongly prefer law-rune-free methods
            weights.put("FAIRY_RING", 0.70);
            weights.put("HOUSE_PORTAL", 0.20);
            weights.put("TABLETS", 0.10);
            weights.put("SPELLBOOK", 0.0);
            weights.put("JEWELRY", 0.0);
            weights.put("SPIRIT_TREE", 0.0);
        } else if (accountType != null && accountType.isIronman()) {
            // Ironman: Bias toward law-rune-free but can use spellbook
            weights.put("FAIRY_RING", 0.50);
            weights.put("SPELLBOOK", 0.25);
            weights.put("HOUSE_PORTAL", 0.15);
            weights.put("TABLETS", 0.10);
            weights.put("JEWELRY", 0.0);
            weights.put("SPIRIT_TREE", 0.0);
        } else {
            // Normal account: Random distribution
        weights.put("SPELLBOOK", 0.15 + seededRandom.nextDouble() * 0.25);
        weights.put("JEWELRY", 0.15 + seededRandom.nextDouble() * 0.25);
        weights.put("FAIRY_RING", 0.10 + seededRandom.nextDouble() * 0.20);
        weights.put("SPIRIT_TREE", 0.05 + seededRandom.nextDouble() * 0.15);
        weights.put("HOUSE_PORTAL", 0.10 + seededRandom.nextDouble() * 0.20);
        weights.put("TABLETS", 0.10 + seededRandom.nextDouble() * 0.15);
        }
        
        // Normalize
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total > 0) {
        for (String key : weights.keySet()) {
            weights.put(key, weights.get(key) / total);
            }
        }
        
        return weights;
    }
    
    /**
     * Generate law rune aversion based on account type.
     * - HCIM: 1.0 (maximum aversion - law runes dangerous to obtain)
     * - Ironman: 0.6 (high aversion but can use when available)
     * - Normal: 0.0-0.3 (random low aversion)
     */
    private double generateLawRuneAversion(Random seededRandom, com.rocinante.behavior.AccountType accountType) {
        if (accountType != null && accountType.isHardcore()) {
            return 1.0;  // HCIM: Maximum aversion
        } else if (accountType != null && accountType.isIronman()) {
            return 0.6;  // Ironman: High aversion
        } else {
            return seededRandom.nextDouble() * 0.3;  // Normal: 0-30% aversion
        }
    }

    // ========================================================================
    // Weight Selection Utilities
    // ========================================================================

    private String selectWeighted(Map<String, Double> weights) {
        double roll = randomization.uniformRandom(0, 1);
        double cumulative = 0;
        
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }
        
        // Fallback to first option
        return weights.keySet().iterator().next();
    }

    private void reinforceWeight(Map<String, Double> weights, String key) {
        if (!weights.containsKey(key)) {
            return;
        }
        
        double currentWeight = weights.get(key);
        double newWeight = Math.min(MAX_WEIGHT, currentWeight + REINFORCEMENT_INCREMENT);
        
        // Reduce other weights proportionally
        double reduction = (newWeight - currentWeight) / (weights.size() - 1);
        
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            if (entry.getKey().equals(key)) {
                entry.setValue(newWeight);
            } else {
                entry.setValue(Math.max(MIN_WEIGHT, entry.getValue() - reduction));
            }
        }
    }

    // ========================================================================
    // Profile Metadata Accessors
    // ========================================================================

    /**
     * Get the cryptographically random seed used to generate this profile.
     * Used for deterministic but unique-per-profile behaviors like Perlin noise patterns.
     * @return the profile seed
     */
    public long getProfileSeed() {
        return profileData.profileSeed;
    }

    // ========================================================================
    // Input Characteristic Accessors (compatible with old InputProfile API)
    // ========================================================================

    public double getMouseSpeedMultiplier() {
        return profileData.mouseSpeedMultiplier;
    }

    public double getClickVarianceModifier() {
        return profileData.clickVarianceModifier;
    }

    public int getTypingSpeedWPM() {
        return profileData.typingSpeedWPM;
    }

    public double getDominantHandBias() {
        return profileData.dominantHandBias;
    }

    public List<ScreenRegion> getPreferredIdlePositions() {
        List<ScreenRegion> regions = new ArrayList<>();
        for (String name : profileData.preferredIdlePositions) {
            try {
                regions.add(ScreenRegion.valueOf(name));
            } catch (IllegalArgumentException e) {
                // Unknown region, skip
            }
        }
        if (regions.isEmpty()) {
            regions.addAll(Arrays.asList(ScreenRegion.getDefaultIdleRegions()));
        }
        return regions;
    }

    public double getBaseMisclickRate() {
        return profileData.baseMisclickRate;
    }

    public double getBaseTypoRate() {
        return profileData.baseTypoRate;
    }

    public double getOvershootProbability() {
        return profileData.overshootProbability;
    }

    public double getMicroCorrectionProbability() {
        return profileData.microCorrectionProbability;
    }
    
    // === Drag Wobble Getters ===
    
    /**
     * Get base frequency for drag wobble oscillation.
     * @return frequency in Hz (2.5-4.0)
     */
    public double getWobbleFrequencyBase() {
        return profileData.wobbleFrequencyBase;
    }
    
    /**
     * Get variance in wobble frequency per drag.
     * @return variance factor (0.3-0.8)
     */
    public double getWobbleFrequencyVariance() {
        return profileData.wobbleFrequencyVariance;
    }
    
    /**
     * Get wobble amplitude modifier (hand steadiness).
     * @return amplitude modifier (0.7-1.3)
     */
    public double getWobbleAmplitudeModifier() {
        return profileData.wobbleAmplitudeModifier;
    }
    
    // === Click Timing Getters (Ex-Gaussian Parameters) ===
    
    /**
     * Get the mean (mu) of the click duration Gaussian component.
     * @return mean click duration in ms (75-95)
     */
    public double getClickDurationMu() {
        return profileData.clickDurationMu;
    }
    
    /**
     * Get the standard deviation (sigma) of click duration.
     * @return std dev in ms (10-20)
     */
    public double getClickDurationSigma() {
        return profileData.clickDurationSigma;
    }
    
    /**
     * Get the exponential tail parameter (tau) for click duration.
     * Higher values mean more occasional slow clicks.
     * @return tail parameter in ms (5-15)
     */
    public double getClickDurationTau() {
        return profileData.clickDurationTau;
    }
    
    // === Cognitive Delay Getters ===
    
    /**
     * Get base cognitive delay between action transitions.
     * @return base delay in ms (80-200)
     */
    public double getCognitiveDelayBase() {
        return profileData.cognitiveDelayBase;
    }
    
    /**
     * Get variance factor for cognitive delay.
     * @return variance factor (0.3-0.7)
     */
    public double getCognitiveDelayVariance() {
        return profileData.cognitiveDelayVariance;
    }
    
    /**
     * Get the motor speed correlation factor.
     * This determines how tightly mouse speed correlates with click speed.
     * Higher values mean "fast players" are fast at both mouse movement and clicking.
     * 
     * @return motor speed correlation (0.5-0.9)
     */
    public double getMotorSpeedCorrelation() {
        return profileData.motorSpeedCorrelation;
    }
    
    /**
     * Get the Ex-Gaussian μ (mean) for tick jitter.
     * @return jitter mean in milliseconds (150-400)
     */
    public double getJitterMu() {
        return profileData.jitterMu;
    }
    
    /**
     * Get the Ex-Gaussian σ (std dev) for tick jitter.
     * @return jitter std dev in milliseconds (20-100)
     */
    public double getJitterSigma() {
        return profileData.jitterSigma;
    }
    
    /**
     * Get the Ex-Gaussian τ (tail) for tick jitter.
     * @return jitter tail in milliseconds (50-300)
     */
    public double getJitterTau() {
        return profileData.jitterTau;
    }
    
    /**
     * Get the base probability of skipping a game tick.
     * Modified by activity type and fatigue at runtime.
     * @return tick skip probability (0.03-0.08)
     */
    public double getTickSkipBaseProbability() {
        return profileData.tickSkipBaseProbability;
    }
    
    /**
     * Get the base probability of an attention lapse.
     * Rare but significant delays where player zones out.
     * @return attention lapse probability (0.005-0.02)
     */
    public double getAttentionLapseProbability() {
        return profileData.attentionLapseProbability;
    }
    
    /**
     * Get the probability of anticipation (faster-than-normal reaction).
     * Used when waiting for predictable events.
     * @return anticipation probability (0.10-0.20)
     */
    public double getAnticipationProbability() {
        return profileData.anticipationProbability;
    }
    
    // === Mouse Path Complexity Getters ===
    
    /**
     * Get the probability of hesitation during mouse movement.
     * @return hesitation probability (0.10-0.25)
     */
    public double getHesitationProbability() {
        return profileData.hesitationProbability;
    }
    
    /**
     * Get the probability of sub-movements on long paths.
     * @return sub-movement probability (0.15-0.30)
     */
    public double getSubmovementProbability() {
        return profileData.submovementProbability;
    }
    
    /**
     * Check if this player uses segmented approach phases for long movements.
     * @return true if segmentation is used
     */
    public boolean usesPathSegmentation() {
        return profileData.usesPathSegmentation;
    }

    public int getSessionCount() {
        return profileData.sessionCount;
    }

    /**
     * Get the timestamp of the last session start.
     * Used by PerformanceState to determine if daily performance should regenerate.
     * 
     * @return last session start timestamp, or null if never played
     */
    public java.time.Instant getLastSessionStart() {
        return profileData.lastSessionStart;
    }

    public double getTotalPlaytimeHours() {
        return profileData.totalPlaytimeHours;
    }

    /**
     * Get the recorded drift history (most recent first).
     */
    public List<DriftRecord> getDriftHistory() {
        return Collections.unmodifiableList(profileData.driftHistory);
    }

    /**
     * Calculate inter-character delay for typing based on WPM.
     */
    public long getBaseTypingDelay() {
        return Math.round(12000.0 / profileData.typingSpeedWPM);
    }

    /**
     * Select a random idle position weighted by dominant hand bias.
     */
    public ScreenRegion selectIdlePosition() {
        List<ScreenRegion> positions = getPreferredIdlePositions();
        if (positions.isEmpty()) {
            return ScreenRegion.INVENTORY;
        }

        double[] weights = new double[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            ScreenRegion region = positions.get(i);
            if (region.isRightSide()) {
                weights[i] = profileData.dominantHandBias;
            } else {
                weights[i] = 1.0 - profileData.dominantHandBias;
            }
        }

        int index = randomization.weightedChoice(weights);
        return positions.get(index);
    }

    // ========================================================================
    // Physiological Physics Engine Accessors
    // ========================================================================

    /**
     * Get the velocity flow (skew) parameter.
     * Determines asymmetry of movement acceleration.
     * 0.2 = fast start (snappy), 0.8 = slow start (lazy).
     * @return flow parameter (0.2-0.8)
     */
    public double getVelocityFlow() {
        return profileData.velocityFlow;
    }

    /**
     * Get physiological tremor frequency.
     * Base alpha rhythm of the nervous system.
     * @return frequency in Hz (8.0-12.0)
     */
    public double getPhysTremorFreq() {
        return profileData.physTremorFreq;
    }

    /**
     * Get physiological tremor amplitude.
     * Base noise scale for hand stability.
     * @return amplitude in pixels (0.2-1.5)
     */
    public double getPhysTremorAmp() {
        return profileData.physTremorAmp;
    }

    /**
     * Get physiological tremor phase offset between X and Y axes.
     * 
     * Real human tremor exhibits elliptical phase relationships - X and Y aren't
     * fully independent because they share muscle groups and neural pathways.
     * This offset creates characteristic "elliptical tremor orbits" visible in
     * spectrograms. The offset is typically 60-120° per person.
     * 
     * @return phase offset in radians (1.05-2.09, i.e., 60-120 degrees)
     */
    public double getPhysTremorPhaseOffset() {
        return profileData.physTremorPhaseOffset;
    }

    /**
     * Get motor unit recruitment threshold.
     * Simulates "steppiness" of movement due to muscle quantization.
     * @return threshold in pixels (0.0-1.5)
     */
    public double getMotorUnitThreshold() {
        return profileData.motorUnitThreshold;
    }
    
    /**
     * Feedback delay for the visual-motor feedback loop in BiologicalMotorNoise.
     * 
     * Represents the visual processing latency - how long it takes for the brain
     * to detect a positioning error and begin correcting it. Real humans have
     * latencies of 100-250ms (10-25 samples at 100Hz).
     * 
     * Lower values = more coordinated player (faster error detection)
     * Higher values = more sluggish corrections
     * 
     * @return feedback delay in samples (10-25)
     */
    public int getFeedbackDelaySamples() {
        return profileData.feedbackDelaySamples;
    }

    /**
     * Fitts' Law 'a' parameter: base reaction/initiation time.
     * This is the neuromuscular delay before movement begins - the time to
     * perceive the target and initiate the motor response.
     * 
     * Used in: Movement Time = a + b * log2(1 + Distance/Width)
     * 
     * @return base time in milliseconds (30-200ms)
     */
    public double getFittsA() {
        return profileData.fittsA;
    }

    /**
     * Fitts' Law 'b' parameter: index of difficulty scaler.
     * This represents motor bandwidth - how quickly the person can process
     * spatial information and execute precise movements.
     * 
     * Higher values = slower, more deliberate targeting
     * Lower values = faster, more aggressive targeting
     * 
     * Used in: Movement Time = a + b * log2(1 + Distance/Width)
     * 
     * @return difficulty scaler in ms/bit (60-180ms/bit)
     */
    public double getFittsB() {
        return profileData.fittsB;
    }

    // ========================================================================
    // Behavioral Accessors
    // ========================================================================

    public double getBreakFatigueThreshold() {
        return profileData.breakFatigueThreshold;
    }

    // === Run Energy Thresholds ===
    
    /**
     * Get the energy level at which to enable running.
     * Per-profile value with Gaussian distribution, range 25-65.
     * 
     * @return run enable threshold (energy percentage)
     */
    public int getRunEnableThreshold() {
        return profileData.runEnableThreshold;
    }
    
    /**
     * Get the energy level at which to disable running.
     * Per-profile value with Gaussian distribution, range 5-25.
     * Always at least 15 below runEnableThreshold for hysteresis.
     * 
     * @return run disable threshold (energy percentage)
     */
    public int getRunDisableThreshold() {
        return profileData.runDisableThreshold;
    }
    
    // === Walk Pacing ===
    
    /**
     * Get the minimum ticks between walk clicks with per-call jitter.
     * Base value is generated per-profile (correlated with mouse speed),
     * then small Gaussian jitter is added each call for natural variation.
     * 
     * Fast movers (high mouseSpeedMultiplier) tend to have lower intervals
     * (more frequent clicking), while slow movers have higher intervals.
     * 
     * @return minimum ticks between walk clicks (2-6)
     */
    public int getMinWalkClickInterval() {
        // Add small per-call jitter around the base for natural variation
        double jitter = randomization.gaussianRandom(0, 0.3);
        double interval = profileData.minWalkClickIntervalBase + jitter;
        return (int) Math.round(Randomization.clamp(interval, 2.0, 6.0));
    }
    
    /**
     * Get the raw base walk click interval without jitter.
     * Useful for debugging/testing.
     * 
     * @return base walk click interval
     */
    public double getMinWalkClickIntervalBase() {
        return profileData.minWalkClickIntervalBase;
    }

    public double getPreferredCompassAngle() {
        return profileData.preferredCompassAngle;
    }

    public String getPitchPreference() {
        return profileData.pitchPreference;
    }

    public double getCameraChangeFrequency() {
        return profileData.cameraChangeFrequency;
    }
    
    /**
     * Get probability of snapping back to preferred camera angle.
     * @return snap-back probability (0.3-0.8)
     */
    public double getCameraSnapBackProbability() {
        return profileData.cameraSnapBackProbability;
    }
    
    /**
     * Get minimum angle deviation before snap-back triggers.
     * @return tolerance in degrees (20-60)
     */
    public double getCameraSnapBackTolerance() {
        return profileData.cameraSnapBackTolerance;
    }
    
    /**
     * Get delay after camera movement before snap-back can occur.
     * @return delay in milliseconds (2000-8000)
     */
    public long getCameraSnapBackDelayMs() {
        return profileData.cameraSnapBackDelayMs;
    }

    public List<String> getSessionRituals() {
        return Collections.unmodifiableList(profileData.sessionRituals);
    }

    public double getRitualExecutionProbability() {
        return profileData.ritualExecutionProbability;
    }

    public double getXpCheckFrequency() {
        return profileData.xpCheckFrequency;
    }

    public double getPlayerInspectionFrequency() {
        return profileData.playerInspectionFrequency;
    }
    
    // === XP check method preferences ===
    
    public double getXpCheckOrbProbability() {
        return profileData.xpCheckOrbProbability;
    }
    
    public double getXpCheckTabProbability() {
        return profileData.xpCheckTabProbability;
    }
    
    /**
     * Get the probability of using XP tracker overlay.
     * This is the remainder after orb and tab probabilities.
     */
    public double getXpCheckTrackerProbability() {
        return Math.max(0, 1.0 - profileData.xpCheckOrbProbability - profileData.xpCheckTabProbability);
    }
    
    // === Player inspection target preferences ===
    
    public double getInspectionNearbyProbability() {
        return profileData.inspectionNearbyProbability;
    }
    
    public double getInspectionHighLevelProbability() {
        return profileData.inspectionHighLevelProbability;
    }
    
    /**
     * Get the probability of inspecting low-level players.
     * This is the remainder after nearby and high-level probabilities.
     */
    public double getInspectionLowLevelProbability() {
        return Math.max(0, 1.0 - profileData.inspectionNearbyProbability - profileData.inspectionHighLevelProbability);
    }

    public Map<String, Double> getTeleportMethodWeights() {
        return Collections.unmodifiableMap(profileData.teleportMethodWeights);
    }

    public double getLawRuneAversion() {
        return profileData.lawRuneAversion;
    }

    // ========================================================================
    // Predictive Hover Accessors
    // ========================================================================

    /**
     * Get the base rate at which this player hovers over the next target while acting.
     * 
     * @return prediction rate from 0.30-0.95
     */
    public double getBasePredictionRate() {
        return profileData.basePredictionRate;
    }

    /**
     * Get the bias toward instant vs delayed clicks when prediction succeeds.
     * 
     * @return speed bias from 0.0 (hesitant) to 1.0 (snappy)
     */
    public double getPredictionClickSpeedBias() {
        return profileData.predictionClickSpeedBias;
    }
    
    /**
     * Get the preferred inventory slot region.
     * @return preference: "TOP_LEFT", "TOP_RIGHT", "CENTER", "BOTTOM_LEFT", "BOTTOM_RIGHT", or "RANDOM"
     */
    public String getInventorySlotPreference() {
        return profileData.inventorySlotPreference;
    }
    
    /**
     * Get the strength of inventory slot preference.
     * @return bias strength (0.3-0.8)
     */
    public double getInventorySlotBiasStrength() {
        return profileData.inventorySlotBiasStrength;
    }
    
    /**
     * Get the row bias for click position within inventory slots.
     * @return row bias (0.35-0.65, where 0.5 is center)
     */
    public double getInventoryClickRowBias() {
        return profileData.inventoryClickRowBias;
    }
    
    /**
     * Get the column bias for click position within inventory slots.
     * @return column bias (0.35-0.65, where 0.5 is center)
     */
    public double getInventoryClickColBias() {
        return profileData.inventoryClickColBias;
    }

    // ========================================================================
    // Environment Fingerprint Accessors
    // ========================================================================

    /**
     * Get the machine ID for this profile (32 hex chars).
     * Used to report consistent hardware identity across sessions.
     */
    public String getMachineId() {
        return profileData.machineId;
    }

    /**
     * Get the screen resolution for this profile (e.g., "1280x720").
     */
    public String getScreenResolution() {
        return profileData.screenResolution;
    }

    /**
     * Get the display DPI for this profile (e.g., 96, 110, 120, 144).
     */
    public int getDisplayDpi() {
        return profileData.displayDpi;
    }

    /**
     * Get the additional fonts enabled for this profile.
     * Returns unmodifiable list of font names (without "fonts-" prefix).
     */
    public List<String> getAdditionalFonts() {
        return Collections.unmodifiableList(profileData.additionalFonts);
    }

    /**
     * Get the timezone for this profile (e.g., "America/New_York").
     * Should match proxy geolocation.
     */
    public String getTimezone() {
        return profileData.timezone;
    }

    // ========================================================================
    // UInput Device Preset Accessors
    // ========================================================================

    /**
     * Get the mouse device preset for uinput hardware spoofing.
     * All bots use Steam Deck mouse for uniformity and blending.
     * @return STEAMDECK_MOUSE preset
     */
    public DevicePreset getMouseDevicePreset() {
        return DevicePreset.STEAMDECK_MOUSE;
    }

    /**
     * Get the keyboard device preset for uinput hardware spoofing.
     * All bots use Steam Deck keyboard for uniformity and blending.
     * @return STEAMDECK_KEYBOARD preset
     */
    public DevicePreset getKeyboardDevicePreset() {
        return DevicePreset.STEAMDECK_KEYBOARD;
    }
    
    /**
     * Get the mouse polling rate in Hz.
     * Steam Deck controller uses 125Hz polling (typical for integrated controllers).
     */
    public int getMousePollingRate() {
        return DevicePreset.STEAMDECK_MOUSE.getDefaultPollingRate();
    }
    
    /**
     * Get the keyboard polling rate in Hz.
     * Steam Deck controller uses 125Hz polling (typical for integrated controllers).
     */
    public int getKeyboardPollingRate() {
        return DevicePreset.STEAMDECK_KEYBOARD.getDefaultPollingRate();
    }
    
    /**
     * Get the player's chronotype.
     * @return "EARLY_BIRD", "NIGHT_OWL", or "NEUTRAL"
     */
    public String getChronotype() {
        return profileData.chronotype;
    }
    
    /**
     * Get the peak hour offset from chronotype default.
     * @return offset in hours (-2 to +2)
     */
    public double getPeakHourOffset() {
        return profileData.peakHourOffset;
    }
    
    /**
     * Get the strength of circadian rhythm effects.
     * Higher values = more pronounced time-of-day performance variation.
     * @return circadian strength (0.1-0.5)
     */
    public double getCircadianStrength() {
        return profileData.circadianStrength;
    }
    
    /**
     * Calculate the circadian performance multiplier for the current time.
     * Returns a value that can be used to adjust action speed, error rate, etc.
     * 
     * <p>Models a biologically realistic circadian rhythm with:
     * <ul>
     *   <li><b>Sinusoidal primary rhythm:</b> Smooth cosine-based transitions rather than
     *       linear decline, matching the natural oscillation of alertness hormones</li>
     *   <li><b>Post-lunch dip:</b> Universal performance dip ~2-4pm caused by postprandial
     *       somnolence (digestion) and natural body temperature drop, independent of chronotype</li>
     *   <li><b>Night trough amplification:</b> The 3-5am window is a circadian nadir where
     *       performance craters beyond what distance-from-peak would predict (microsleeps,
     *       severely impaired reaction time)</li>
     *   <li><b>Asymmetric rise/decline:</b> Morning alertness rises faster than evening
     *       decline due to cortisol awakening response vs gradual melatonin onset</li>
     * </ul>
     * 
     * @param hourOfDay the current hour (0-23) in the player's timezone
     * @return performance multiplier (0.5-1.0, lower when tired, higher when alert)
     */
    public double getCircadianPerformanceMultiplier(int hourOfDay) {
        // Base peak hours by chronotype
        int basePeakHour;
        switch (profileData.chronotype) {
            case "EARLY_BIRD":
                basePeakHour = 9;   // Peak at 9am
                break;
            case "NIGHT_OWL":
                basePeakHour = 21;  // Peak at 9pm
                break;
            default: // NEUTRAL
                basePeakHour = 15;  // Peak at 3pm
        }
        
        // Apply personal offset
        double peakHour = basePeakHour + profileData.peakHourOffset;
        
        // ========================================================================
        // Primary Rhythm: Sinusoidal (Cosine Wave)
        // ========================================================================
        // Cosine gives 1.0 at peak, smoothly declining to -1.0 at nadir (12h from peak).
        // This matches the natural oscillation of circadian alertness hormones.
        
        double hoursFromPeak = hourOfDay - peakHour;
        // Normalize to [0, 24) range
        while (hoursFromPeak < 0) hoursFromPeak += 24;
        while (hoursFromPeak >= 24) hoursFromPeak -= 24;
        
        // Cosine wave: 1.0 at peak (0h), -1.0 at nadir (12h from peak)
        double primaryRhythm = Math.cos(hoursFromPeak * Math.PI / 12.0);
        
        // ========================================================================
        // Post-Lunch Dip (Universal, ~2-4pm)
        // ========================================================================
        // Separate biological process: postprandial somnolence (digestion redirects blood
        // flow), natural body temperature dip, and homeostatic sleep pressure.
        // This dip occurs regardless of chronotype.
        
        double lunchDipCenter = 14.5; // 2:30pm - peak of the afternoon dip
        double lunchDipWidth = 2.0;   // ~2 hours wide (Gaussian spread)
        double distanceFromLunchDip = Math.abs(hourOfDay - lunchDipCenter);
        if (distanceFromLunchDip > 12) distanceFromLunchDip = 24 - distanceFromLunchDip;
        
        // Gaussian curve centered on 2:30pm
        double lunchDipEffect = Math.exp(-distanceFromLunchDip * distanceFromLunchDip 
                / (2 * lunchDipWidth * lunchDipWidth));
        double lunchDipMagnitude = 0.08; // 8% dip at peak (2:30pm)
        
        // ========================================================================
        // Night Trough Amplification (3-5am)
        // ========================================================================
        // The circadian nadir is MUCH worse than "just far from peak":
        // - Core body temperature minimum
        // - Cortisol at lowest point
        // - Microsleep intrusions even in motivated subjects
        // - Reaction time can be 2-3x worse than daytime
        // This creates the characteristic "cratering" of night shift performance.
        
        double nightTroughCenter = 4.0; // 4am - circadian nadir
        double distanceFromTrough = Math.abs(hourOfDay - nightTroughCenter);
        if (distanceFromTrough > 12) distanceFromTrough = 24 - distanceFromTrough;
        
        // Wider Gaussian for the night trough (affects 2-6am range significantly)
        double nightTroughEffect = Math.exp(-distanceFromTrough * distanceFromTrough / 8.0);
        double nightTroughMagnitude = 0.15; // Additional 15% penalty at 4am
        
        // ========================================================================
        // Combine All Effects
        // ========================================================================
        
        // Primary rhythm: scale by circadianStrength, map from [-1,1] to [1-strength, 1+strength/2]
        // At peak (primaryRhythm=1): performance = 1.0
        // At nadir (primaryRhythm=-1): performance = 1.0 - circadianStrength
        double basePerformance = 1.0 - (1.0 - primaryRhythm) * profileData.circadianStrength * 0.5;
        
        // Apply lunch dip and night trough as additive penalties scaled by circadianStrength
        // Players with lower circadianStrength are also less affected by these dips
        double lunchPenalty = lunchDipEffect * lunchDipMagnitude * (profileData.circadianStrength / 0.25);
        double nightPenalty = nightTroughEffect * nightTroughMagnitude * (profileData.circadianStrength / 0.25);
        
        double finalPerformance = basePerformance - lunchPenalty - nightPenalty;
        
        // Clamp to [0.5, 1.0] - even at worst, humans maintain some baseline function
        return Math.max(0.5, Math.min(1.0, finalPerformance));
    }

    // ========================================================================
    // Motor Learning with Skill Transfer
    // ========================================================================
    // 
    // Real humans show partial transfer between related tasks. If you master
    // woodcutting, you're slightly better at mining on day 1 (similar click
    // timing patterns). This prevents the suspicious scenario where a bot with
    // 500 hours total playtime is "expert" at woodcutting but "novice" at mining.
    //
    // Transfer model:
    // - Within-category transfer: 50% proficiency transfers (e.g., mining <-> woodcutting)
    // - Cross-category transfer: 15% of total playtime as "general gaming skill"
    // ========================================================================
    
    /**
     * Skill categories for motor learning transfer.
     * Tasks within the same category share similar motor patterns and transfer proficiency.
     */
    public enum SkillCategory {
        /** Resource gathering: clicking on game objects (trees, rocks, fishing spots) */
        GATHERING,
        /** Combat: target acquisition, prayer switching, gear management */
        COMBAT,
        /** Processing: interface interactions (cooking, smithing, crafting, fletching) */
        PROCESSING,
        /** Agility/Thieving: timing-based, quick reactions */
        REACTION,
        /** Banking: inventory management, deposit/withdraw patterns */
        BANKING,
        /** Navigation: minimap clicking, camera control */
        NAVIGATION,
        /** General: doesn't fit other categories, no special transfer */
        GENERAL
    }
    
    /**
     * Within-category skill transfer coefficient.
     * 50% of proficiency from related tasks transfers.
     */
    private static final double WITHIN_CATEGORY_TRANSFER = 0.50;
    
    /**
     * Cross-category (general gaming skill) transfer coefficient.
     * 15% of total playtime proficiency transfers to all tasks.
     */
    private static final double GENERAL_SKILL_TRANSFER = 0.15;
    
    /**
     * Map task types to their skill categories.
     * Used for calculating skill transfer between related tasks.
     */
    private static final Map<String, SkillCategory> TASK_CATEGORIES = Map.ofEntries(
        // Gathering skills (clicking on game objects)
        Map.entry("WOODCUTTING", SkillCategory.GATHERING),
        Map.entry("MINING", SkillCategory.GATHERING),
        Map.entry("FISHING", SkillCategory.GATHERING),
        Map.entry("HUNTER", SkillCategory.GATHERING),
        Map.entry("FARMING", SkillCategory.GATHERING),
        
        // Combat skills (target acquisition, switching)
        Map.entry("COMBAT", SkillCategory.COMBAT),
        Map.entry("MELEE", SkillCategory.COMBAT),
        Map.entry("RANGED", SkillCategory.COMBAT),
        Map.entry("MAGIC", SkillCategory.COMBAT),
        Map.entry("SLAYER", SkillCategory.COMBAT),
        
        // Processing skills (interface interactions)
        Map.entry("COOKING", SkillCategory.PROCESSING),
        Map.entry("SMITHING", SkillCategory.PROCESSING),
        Map.entry("CRAFTING", SkillCategory.PROCESSING),
        Map.entry("FLETCHING", SkillCategory.PROCESSING),
        Map.entry("HERBLORE", SkillCategory.PROCESSING),
        Map.entry("RUNECRAFT", SkillCategory.PROCESSING),
        Map.entry("CONSTRUCTION", SkillCategory.PROCESSING),
        
        // Reaction skills (timing-based)
        Map.entry("AGILITY", SkillCategory.REACTION),
        Map.entry("THIEVING", SkillCategory.REACTION),
        
        // Banking (universal skill)
        Map.entry("BANKING", SkillCategory.BANKING),
        Map.entry("GRAND_EXCHANGE", SkillCategory.BANKING),
        
        // Navigation
        Map.entry("NAVIGATION", SkillCategory.NAVIGATION),
        Map.entry("WALKING", SkillCategory.NAVIGATION),
        Map.entry("TRAVEL", SkillCategory.NAVIGATION),
        
        // General
        Map.entry("FIREMAKING", SkillCategory.GENERAL),
        Map.entry("PRAYER", SkillCategory.GENERAL)
    );
    
    /**
     * Record task experience for motor learning.
     * 
     * Call this periodically (e.g., every minute) while performing a task
     * to accumulate experience and improve proficiency.
     * 
     * @param taskType the task type identifier (e.g., "WOODCUTTING", "COMBAT", "BANKING")
     * @param minutesSpent time spent on this task in this session (can be fractional)
     */
    public void recordTaskExperience(String taskType, double minutesSpent) {
        if (taskType == null || taskType.isEmpty() || minutesSpent <= 0) {
            return;
        }
        
        String key = taskType.toUpperCase();
        profileData.taskExperience.merge(key, minutesSpent, Double::sum);
        
        log.trace("Motor learning: {} experience += {} min (total: {} min)", 
                key, String.format("%.1f", minutesSpent),
                String.format("%.1f", profileData.taskExperience.get(key)));
    }
    
    /**
     * Get the motor learning proficiency multiplier for a task, including skill transfer.
     * 
     * <p>Returns a value < 1.0 indicating improved performance (faster) for familiar tasks.
     * Uses exponential learning curve with skill transfer from related tasks.
     * 
     * <h3>Skill Transfer Model</h3>
     * Real humans show partial transfer between related tasks:
     * <ul>
     *   <li><b>Direct experience:</b> Full proficiency from this specific task</li>
     *   <li><b>Within-category transfer:</b> 50% proficiency from related tasks
     *       (e.g., mining skill partially transfers to woodcutting)</li>
     *   <li><b>General skill transfer:</b> 15% proficiency from total playtime
     *       ("gaming intuition" applies to all tasks)</li>
     * </ul>
     * 
     * <p>This prevents the suspicious case where a 500-hour player is "expert"
     * at woodcutting but "novice" at mining.
     * 
     * <h3>Examples</h3>
     * With default capacity=0.18, tau=400:
     * <ul>
     *   <li>0 min direct, 0 min total: 1.00 (no improvement)</li>
     *   <li>400 min direct, 400 min total: 0.88 (~12% faster)</li>
     *   <li>0 min direct, 400 min in same category: 0.94 (~6% faster from transfer)</li>
     *   <li>0 min direct, 2000 min total across all skills: 0.97 (~3% faster from general)</li>
     * </ul>
     * 
     * @param taskType the task type identifier
     * @return performance multiplier (0.75-1.0, lower = faster/better)
     */
    public double getTaskProficiencyMultiplier(String taskType) {
        if (taskType == null || taskType.isEmpty()) {
            return 1.0;
        }
        
        String key = taskType.toUpperCase();
        
        // 1. Direct experience for this specific task
        double directExperience = profileData.taskExperience.getOrDefault(key, 0.0);
        
        // 2. Calculate within-category transfer (50% from related tasks)
        double categoryTransferExperience = calculateCategoryTransfer(key);
        
        // 3. Calculate general skill transfer (15% from total playtime)
        double generalTransferExperience = calculateGeneralTransfer(key);
        
        // Combine: direct + max(categoryTransfer, generalTransfer)
        // Using max prevents double-counting - you get the better of category or general transfer
        double effectiveExperience = directExperience + 
                Math.max(categoryTransferExperience, generalTransferExperience);
        
        if (effectiveExperience <= 0) {
            return 1.0; // No experience, no improvement
        }
        
        // Exponential learning: improvement = capacity * (1 - e^(-experience/tau))
        double learningFraction = 1.0 - Math.exp(-effectiveExperience / profileData.motorLearningTau);
        double improvement = profileData.motorLearningCapacity * learningFraction;
        
        // Return multiplier (lower = better/faster)
        return 1.0 - improvement;
    }
    
    /**
     * Calculate within-category skill transfer for a task.
     * 
     * @param taskType the target task type
     * @return effective experience from category transfer (in minutes)
     */
    private double calculateCategoryTransfer(String taskType) {
        SkillCategory targetCategory = TASK_CATEGORIES.getOrDefault(taskType, SkillCategory.GENERAL);
        
        // Sum experience from all tasks in the same category (excluding the target task itself)
        double categoryExperience = 0.0;
        for (Map.Entry<String, Double> entry : profileData.taskExperience.entrySet()) {
            String otherTask = entry.getKey();
            if (otherTask.equals(taskType)) {
                continue; // Don't count direct experience
            }
            
            SkillCategory otherCategory = TASK_CATEGORIES.getOrDefault(otherTask, SkillCategory.GENERAL);
            if (otherCategory == targetCategory && targetCategory != SkillCategory.GENERAL) {
                // Same category - 50% transfer
                categoryExperience += entry.getValue() * WITHIN_CATEGORY_TRANSFER;
            }
        }
        
        return categoryExperience;
    }
    
    /**
     * Calculate general skill transfer from total playtime.
     * 
     * @param taskType the target task type (for exclusion from calculation)
     * @return effective experience from general transfer (in minutes)
     */
    private double calculateGeneralTransfer(String taskType) {
        // Sum all experience across all tasks (excluding the target task)
        double totalExperience = 0.0;
        for (Map.Entry<String, Double> entry : profileData.taskExperience.entrySet()) {
            if (!entry.getKey().equals(taskType)) {
                totalExperience += entry.getValue();
            }
        }
        
        // 15% of total playtime transfers as "general gaming skill"
        return totalExperience * GENERAL_SKILL_TRANSFER;
    }
    
    /**
     * Get the skill category for a task type.
     * 
     * @param taskType the task type identifier
     * @return the skill category (GENERAL if not found)
     */
    public SkillCategory getTaskCategory(String taskType) {
        if (taskType == null || taskType.isEmpty()) {
            return SkillCategory.GENERAL;
        }
        return TASK_CATEGORIES.getOrDefault(taskType.toUpperCase(), SkillCategory.GENERAL);
    }
    
    /**
     * Get total experience across all tasks.
     * Useful for determining overall playtime and general skill level.
     * 
     * @return total experience in minutes
     */
    public double getTotalTaskExperience() {
        return profileData.taskExperience.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
    }
    
    /**
     * Get the total experience accumulated for a task.
     * 
     * @param taskType the task type identifier
     * @return accumulated experience in minutes
     */
    public double getTaskExperience(String taskType) {
        if (taskType == null || taskType.isEmpty()) {
            return 0.0;
        }
        return profileData.taskExperience.getOrDefault(taskType.toUpperCase(), 0.0);
    }
    
    /**
     * Get all task experiences.
     * 
     * @return unmodifiable view of task experience map
     */
    public Map<String, Double> getAllTaskExperience() {
        return Collections.unmodifiableMap(profileData.taskExperience);
    }
    
    /**
     * Get the player's motor learning capacity (max improvement fraction).
     * 
     * @return max improvement (0.12-0.25, meaning 12-25% faster at mastered tasks)
     */
    public double getMotorLearningCapacity() {
        return profileData.motorLearningCapacity;
    }
    
    /**
     * Get the player's learning rate time constant.
     * 
     * @return tau in minutes (time to reach ~63% of max improvement)
     */
    public double getMotorLearningTau() {
        return profileData.motorLearningTau;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Shutdown the profile (save and stop auto-save).
     */
    public void shutdown() {
        recordLogout();
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========================================================================
    // Drift History
    // ========================================================================

    @Getter
    public static class DriftChange {
        private final String field;
        private final double before;
        private final double after;

        public DriftChange(String field, double before, double after) {
            this.field = field;
            this.before = before;
            this.after = after;
        }
    }

    public enum DriftType {
        SESSION,
        LONG_TERM
    }

    // ========================================================================
    // Motor Trait Correlation Constraint System
    // ========================================================================

    /**
     * Correlation constraint for motor trait pairs.
     * Ensures generated random correlation matrices match realistic human psychology.
     */
    private static class CorrelationConstraint {
        final int row;
        final int col;
        final double min;
        final double max;

        CorrelationConstraint(int row, int col, double min, double max) {
            this.row = row;
            this.col = col;
            this.min = min;
            this.max = max;
        }
    }

    /**
     * Human motor control correlation constraints based on research.
     * These constraints ensure randomly generated correlation matrices are realistic.
     *
     * Research sources:
     * - Fitts' Law speed-accuracy tradeoff
     * - Motor control reaction time studies
     * - Physiological tremor research
     * - Individual differences in motor control
     */
    private static final CorrelationConstraint[] MOTOR_CORRELATION_CONSTRAINTS = {
        // === CRITICAL CONSTRAINTS: Exclude obviously inhuman patterns ===
        // These are widened significantly to create a larger feasible region.
        // The goal is to reject statistically impossible patterns, not to match
        // exact human correlations (which vary widely anyway).

        // Speed-Timing inverse relationships (faster movers = shorter delays)
        new CorrelationConstraint(MT_MOUSE_SPEED, MT_COGNITIVE_DELAY, -0.70, 0.10),
        new CorrelationConstraint(MT_MOUSE_SPEED, MT_FITTS_B, -0.70, 0.10),
        new CorrelationConstraint(MT_MOUSE_SPEED, MT_CLICK_DURATION, -0.60, 0.20),

        // Timing parameters should correlate positively (slow responders are slow everywhere)
        new CorrelationConstraint(MT_COGNITIVE_DELAY, MT_FITTS_B, -0.20, 0.80),
        new CorrelationConstraint(MT_CLICK_DURATION, MT_COGNITIVE_DELAY, -0.30, 0.70),

        // Speed-accuracy tradeoff (faster movers overshoot more, but not always)
        new CorrelationConstraint(MT_MOUSE_SPEED, MT_OVERSHOOT, -0.20, 0.70),
        new CorrelationConstraint(MT_MOUSE_SPEED, MT_WOBBLE, -0.30, 0.60),

        // Exclude perfect correlations (r > 0.85 suggests duplicate measurements)
        // This is enforced by matrix generation, but we'll add soft bounds
        new CorrelationConstraint(MT_OVERSHOOT, MT_WOBBLE, -0.30, 0.75),
        new CorrelationConstraint(MT_CLICK_DURATION, MT_FITTS_B, -0.30, 0.75),
    };

    @Getter
    public static class DriftRecord {
        private final Instant timestamp;
        private final DriftType type;
        private final List<DriftChange> changes;

        public DriftRecord(Instant timestamp, DriftType type, List<DriftChange> changes) {
            this.timestamp = timestamp;
            this.type = type;
            this.changes = changes;
        }
    }

    // ========================================================================
    // Profile Data Class
    // ========================================================================

    /**
     * Data class for profile persistence.
     * All fields are serialized to JSON.
     */
    /**
     * Inner data class holding all profile fields.
     * 
     * Thread safety notes:
     * - Maps use ConcurrentHashMap for safe concurrent access during reinforcement
     * - Lists use CopyOnWriteArrayList for safe iteration during reads
     * - Volatile primitives ensure visibility across threads (game thread, save thread)
     * - Gson will deserialize into standard collections, but we convert on load
     */
    @Getter
    @Setter
    public static class ProfileData {
        // Schema versioning
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        String accountHash;
        
        /**
         * Cryptographically random seed used to generate this profile.
         * CRITICAL: This must be truly random, NOT derived from account name.
         * Stored so that future regeneration (e.g., adding new fields) maintains consistency.
         */
        long profileSeed;
        
        volatile Instant createdAt;
        volatile Instant lastSessionStart;
        volatile Instant lastLogout;
        String checksum;
        List<DriftRecord> driftHistory = new CopyOnWriteArrayList<>();

        // === Input characteristics ===
        volatile double mouseSpeedMultiplier = 1.0;
        volatile double clickVarianceModifier = 1.0;
        volatile int typingSpeedWPM = 60;
        volatile double dominantHandBias = 0.6;
        List<String> preferredIdlePositions = new CopyOnWriteArrayList<>();
        volatile double baseMisclickRate = 0.02;
        volatile double baseTypoRate = 0.01;
        volatile double overshootProbability = 0.12;
        volatile double microCorrectionProbability = 0.20;
        
        // === Drag wobble characteristics (hand tremor during drag) ===
        /**
         * Base frequency of wobble oscillation during drags (2.5-4.0 Hz).
         * Higher = faster tremor, lower = slower tremor.
         */
        volatile double wobbleFrequencyBase = 3.25;
        
        /**
         * How much the wobble frequency varies per drag (0.3-0.8).
         * Higher = more variable, lower = more consistent.
         */
        volatile double wobbleFrequencyVariance = 0.5;
        
        /**
         * Modifier for wobble amplitude (0.7-1.3).
         * Higher = shakier hands, lower = steadier hands.
         */
        volatile double wobbleAmplitudeModifier = 1.0;
        
        // === Click timing (ex-Gaussian distribution for human-like reaction times) ===
        /**
         * Mean of Gaussian component for click hold duration (75-95ms).
         * This is the "typical" click speed for this player.
         */
        volatile double clickDurationMu = 85.0;
        
        /**
         * Standard deviation of Gaussian component (10-20ms).
         * Lower = more consistent clicks, higher = more variable.
         */
        volatile double clickDurationSigma = 15.0;
        
        /**
         * Exponential tail parameter (5-15ms).
         * Higher = more occasional slow clicks (distraction, fatigue).
         * Creates the realistic right-skewed reaction time distribution.
         */
        volatile double clickDurationTau = 10.0;
        
        // === Cognitive delay (thinking time between different actions) ===
        /**
         * Base cognitive delay between action transitions (80-200ms).
         * Represents personal "thinking speed" - time to switch mental context.
         */
        volatile double cognitiveDelayBase = 140.0;
        
        /**
         * Variance in cognitive delay (0.3-0.7).
         * How much the delay varies: delay * uniform(1-var, 1+var).
         */
        volatile double cognitiveDelayVariance = 0.5;
        
        // === Motor speed correlation (unified "tempo") ===
        /**
         * Correlation between mouse speed and click speed (0.5-0.9).
         * Higher = faster movers are faster clickers (realistic).
         * Lower = mouse/click speeds are more independent.
         * 
         * This creates coherent "fast players" vs "slow players" rather than
         * having fast mouse but slow clicks (which would be detectably odd).
         */
        volatile double motorSpeedCorrelation = 0.7;
        
        // === Tick jitter (perception delay before actions) ===
        /**
         * Ex-Gaussian μ (Gaussian mean) for tick jitter (150-400ms).
         * Base perception delay - how quickly this player notices game events.
         * Real human choice reaction times center around 200-350ms.
         */
        volatile double jitterMu = 250.0;
        
        /**
         * Ex-Gaussian σ (Gaussian std dev) for tick jitter (20-100ms).
         * Consistency of perception - lower = more consistent, higher = more variable.
         * Real humans show σ of 20-100ms depending on attention and task complexity.
         */
        volatile double jitterSigma = 50.0;
        
        /**
         * Ex-Gaussian τ (exponential tail) for tick jitter (50-300ms).
         * Occasional longer delays - higher = more frequent "slow" reactions.
         * Real humans have τ of 50-300ms capturing distraction/fatigue effects.
         */
        volatile double jitterTau = 150.0;
        
        /**
         * Base probability of skipping a game tick (3-8%).
         * Creates realistic delays where player doesn't react within the same tick.
         * Modified by activity type and fatigue.
         */
        volatile double tickSkipBaseProbability = 0.05;
        
        /**
         * Base probability of attention lapse (0.5-2%).
         * Rare but significant delays (1.5-3 seconds) where player zones out.
         * More likely during repetitive tasks and high fatigue.
         */
        volatile double attentionLapseProbability = 0.01;
        
        /**
         * Probability of anticipation - reacting faster than normal (10-20%).
         * When waiting for predictable events (inventory full, ore depleted),
         * player may be "ready" and react in 25-50ms instead of normal delay.
         */
        volatile double anticipationProbability = 0.15;

        // === Mouse path complexity (humanized movement patterns) ===
        /**
         * Probability of hesitation during mouse movement (0.10-0.25).
         * Hesitations are brief pauses (5-15ms) mid-movement.
         */
        volatile double hesitationProbability = 0.15;
        
        /**
         * Probability of sub-movements on long paths (0.15-0.30).
         * Sub-movements are direction corrections via intermediate waypoints.
         */
        volatile double submovementProbability = 0.20;

        /**
         * Per-bot unique correlation matrix for 8 motor traits.
         * Generated via constrained random sampling to ensure realistic human correlations.
         * This matrix is used to preserve correlation structure during session drift.
         */
        volatile double[][] motorCorrelationMatrix;

        /**
         * Cholesky decomposition (lower triangular) of motorCorrelationMatrix.
         * Pre-computed and cached to avoid repeated decomposition during drift application.
         * Used to transform independent noise into correlated noise: ε = L·Z
         */
        volatile double[][] motorCorrelationCholesky;

        /**
         * Whether this player uses segmented approach phases for long movements.
         * Segmented = ballistic -> approach -> fine-tune phases.
         */
        volatile boolean usesPathSegmentation = true;
        
        // === Physiological Physics Engine Parameters ===
        /**
         * Velocity flow/skew (Asymmetry).
         * Determines peak acceleration point.
         * 0.3 = fast start (snappy), 0.6 = slow start (lazy).
         * Range: 0.2 - 0.8
         */
        volatile double velocityFlow = 0.4;

        /**
         * Physiological tremor frequency (Hz).
         * Base alpha rhythm of the nervous system.
         * Range: 8.0 - 12.0
         */
        volatile double physTremorFreq = 10.0;

        /**
         * Physiological tremor amplitude (pixels).
         * Base noise scale.
         * Range: 0.2 - 1.5
         */
        volatile double physTremorAmp = 0.5;

        /**
         * Physiological tremor phase offset between X and Y axes (radians).
         * 
         * Real human tremor exhibits elliptical phase relationships because X and Y
         * hand movements aren't fully independent - they share the same muscle groups
         * and neural pathways. This creates characteristic "elliptical tremor orbits"
         * visible in spectrograms. The phase offset varies by person (muscle attachment
         * geometry, neural timing) but is typically 60-120° (not random).
         * 
         * Range: 1.05 - 2.09 radians (60-120 degrees)
         */
        volatile double physTremorPhaseOffset = 1.57; // ~90° default

        /**
         * Motor unit quantization threshold (pixels).
         * Simulates "jerk" or stepping in movement.
         * Range: 0.0 - 1.5
         */
        volatile double motorUnitThreshold = 0.5;

        /**
         * Feedback delay in samples for visual-motor loop in BiologicalMotorNoise.
         * Represents visual processing latency (~100-250ms in real humans at 100Hz = 10-25 samples).
         * Lower = faster visual-motor feedback (more coordinated player).
         * Higher = slower feedback (more delayed/sluggish corrections).
         * Range: 10 - 25 samples
         */
        volatile int feedbackDelaySamples = 15;

        // === Fitts' Law Parameters (Movement Time = a + b * log2(1 + D/W)) ===
        /**
         * Fitts' Law 'a' parameter: base reaction/initiation time (ms).
         * Represents neuromuscular delay before movement begins.
         * Varies significantly between individuals (40-150ms typical).
         * Range: 30 - 200ms
         */
        volatile double fittsA = 50.0;

        /**
         * Fitts' Law 'b' parameter: index of difficulty scaler (ms/bit).
         * Represents motor bandwidth - how quickly a person can process
         * spatial information and execute precise movements.
         * Higher = slower, more deliberate targeting.
         * Range: 60 - 180 ms/bit
         */
        volatile double fittsB = 100.0;

        // === Break behavior ===
        volatile double breakFatigueThreshold = 0.80;
        Map<String, Double> breakActivityWeights = new ConcurrentHashMap<>();

        // === Run energy thresholds (hysteresis to prevent toggling) ===
        /**
         * Energy level at which to enable running (25-65%).
         * Generated per-profile with Gaussian distribution.
         */
        volatile int runEnableThreshold = 40;
        
        /**
         * Energy level at which to disable running (5-25%).
         * Generated per-profile with Gaussian distribution.
         * Always at least 15 below runEnableThreshold for hysteresis.
         */
        volatile int runDisableThreshold = 15;
        
        // === Walk pacing ===
        /**
         * Base ticks between walk clicks during pathfinding (2.0-6.0).
         * Lower = more frequent clicking (aggressive/impatient player).
         * Higher = less frequent clicking (patient/relaxed player).
         * Correlated with mouseSpeedMultiplier: fast movers click more often.
         */
        volatile double minWalkClickIntervalBase = 4.0;

        // === Camera preferences ===
        volatile double preferredCompassAngle = 0.0;
        volatile String pitchPreference = "MEDIUM";
        volatile double cameraChangeFrequency = 10.0;
        
        // === Camera hold preferences (fidgeting behavior) ===
        /**
         * Frequency of camera hold during idle periods (0.05-0.30 = 5-30%).
         * Camera hold is when player holds arrow key to spin camera out of boredom.
         */
        volatile double cameraHoldFrequency = 0.15;
        
        /**
         * Preferred direction for camera hold: "LEFT_BIAS", "RIGHT_BIAS", or "NO_PREFERENCE".
         */
        volatile String cameraHoldPreferredDirection = "NO_PREFERENCE";
        
        /**
         * Speed preference for camera hold: "SLOW", "MEDIUM", or "FAST".
         */
        volatile String cameraHoldSpeedPreference = "MEDIUM";
        
        // === Camera snap-back preferences (returning to "home" angle) ===
        /**
         * Probability of snapping back to preferred angle after deviation (0.3-0.8).
         * Higher = player has strong angle preference, lower = more flexible.
         */
        volatile double cameraSnapBackProbability = 0.5;
        
        /**
         * Minimum deviation from preferred angle before snap-back triggers (20-60 degrees).
         * Player won't snap back if they're "close enough" to their preferred angle.
         */
        volatile double cameraSnapBackTolerance = 40.0;
        
        /**
         * Delay after camera movement before snap-back can occur (2000-8000ms).
         * Represents "settling time" before the player unconsciously returns to comfort.
         */
        volatile long cameraSnapBackDelayMs = 4000;

        // === Session patterns ===
        List<String> sessionRituals = new CopyOnWriteArrayList<>();
        volatile double ritualExecutionProbability = 0.80;
        volatile double xpCheckFrequency = 5.0;
        volatile double playerInspectionFrequency = 2.0;
        
        // === XP Check method preferences (must sum to 1.0) ===
        /**
         * Probability of checking XP via skill orb hover.
         */
        volatile double xpCheckOrbProbability = 0.70;
        
        /**
         * Probability of checking XP via skills tab.
         */
        volatile double xpCheckTabProbability = 0.25;
        
        /**
         * Probability of checking XP via XP tracker overlay.
         * Calculated as 1.0 - orb - tab, not stored.
         */
        // Note: tracker probability is implicit (1 - orb - tab)
        
        // === Player inspection target preferences ===
        /**
         * Probability of inspecting random nearby players (30-80%).
         */
        volatile double inspectionNearbyProbability = 0.60;
        
        /**
         * Probability of inspecting high-level players.
         */
        volatile double inspectionHighLevelProbability = 0.30;
        
        /**
         * Probability of inspecting low-level players.
         * Calculated as 1.0 - nearby - high, not stored.
         */
        // Note: lowLevel probability is implicit (1 - nearby - high)

        // === Action sequencing ===
        Map<String, Double> bankingSequenceWeights = new ConcurrentHashMap<>();
        Map<String, Double> combatPrepSequenceWeights = new ConcurrentHashMap<>();

        // === Teleport preferences ===
        Map<String, Double> teleportMethodWeights = new ConcurrentHashMap<>();
        volatile double lawRuneAversion = 0.0;

        // === Interface interaction preferences ===
        /**
         * Whether this player prefers using hotkeys (F-keys) for tab switching.
         * Some players are habitual clickers, others prefer keyboard shortcuts.
         * This affects WidgetInteractTask behavior when opening tabs.
         */
        volatile boolean prefersHotkeys = true;

        // === Predictive hovering preferences ===
        /**
         * Base rate at which player hovers over next target while current action is in progress.
         * Range: 0.30-0.95 (30-95%).
         * 
         * High values (0.8+) represent very engaged "ahead" players who always anticipate.
         * Low values (0.4-) represent more reactive players who wait for actions to complete.
         * 
         * This base rate is modified by fatigue (reduces prediction) and attention state
         * (DISTRACTED significantly reduces, AFK disables).
         */
        volatile double basePredictionRate = 0.60;
        
        /**
         * Bias toward instant vs delayed clicks when prediction succeeds.
         * Range: 0.0-1.0 (0=hesitant/slow, 1=snappy/fast).
         * 
         * High values (0.8+) mean the player typically clicks instantly when their
         * current action completes.
         * Low values (0.3-) mean the player often hesitates briefly before clicking
         * even when they've been hovering.
         * 
         * This affects the distribution of ClickBehavior outcomes:
         * - High bias: More INSTANT, fewer DELAYED
         * - Low bias: More DELAYED, fewer INSTANT
         */
        volatile double predictionClickSpeedBias = 0.50;
        
        // === Inventory slot preferences ===
        /**
         * Preferred slot region for inventory operations.
         * Players develop habits about which slots they gravitate toward.
         * Options: "TOP_LEFT", "TOP_RIGHT", "CENTER", "BOTTOM_LEFT", "BOTTOM_RIGHT", "RANDOM"
         */
        volatile String inventorySlotPreference = "CENTER";
        
        /**
         * How strongly the player prefers their preferred region (0.3-0.8).
         * Higher = stronger preference (clicks cluster in preferred region).
         * Lower = more random distribution across inventory.
         */
        volatile double inventorySlotBiasStrength = 0.5;
        
        /**
         * Row offset for click point within inventory slots (normalized 0.0-1.0).
         * Some players consistently click slightly higher or lower within each slot.
         * 0.5 = center, 0.3 = upper third, 0.7 = lower third.
         */
        volatile double inventoryClickRowBias = 0.5;
        
        /**
         * Column offset for click point within inventory slots (normalized 0.0-1.0).
         * Some players consistently click slightly left or right within each slot.
         * 0.5 = center, 0.3 = left third, 0.7 = right third.
         */
        volatile double inventoryClickColBias = 0.5;

        // === Metrics ===
        volatile int sessionCount = 0;
        volatile double totalPlaytimeHours = 0;

        // === Environment fingerprinting (anti-detection) ===
        /**
         * Machine ID - 32 hex chars, stable per account.
         * Used to report consistent hardware identity to the game client.
         */
        String machineId;

        /**
         * Screen resolution - e.g., "1280x720".
         * Fixed to 720p for now but stored for future flexibility.
         */
        String screenResolution = "1280x720";

        /**
         * Display DPI - one of 96, 110, 120, 144.
         * Randomized per profile to vary display fingerprint.
         */
        int displayDpi = 96;

        /**
         * Additional fonts enabled for this profile.
         * Subset of optional fonts installed in container, selectively enabled per profile.
         */
        List<String> additionalFonts = new CopyOnWriteArrayList<>();

        /**
         * Timezone matching proxy location - e.g., "America/New_York".
         * Should be set per-account to match proxy geolocation.
         */
        String timezone = "America/New_York";
        
        // === UInput Device Presets ===
        // Simplified: All bots use Steam Deck devices (STEAMDECK_MOUSE, STEAMDECK_KEYBOARD).
        // This ensures uniform hardware fingerprint across the botnet, blending in well
        // with legitimate Steam Deck users. Device selection is no longer per-profile.
        // See PlayerProfile.getMouseDevicePreset() and getKeyboardDevicePreset().
        
        // === Chronotype (circadian rhythm preferences) ===
        /**
         * The player's chronotype: "EARLY_BIRD", "NIGHT_OWL", or "NEUTRAL".
         * Affects preferred play times and activity level variations by time of day.
         * 
         * EARLY_BIRD: Peak activity 6am-12pm, winds down after 8pm, avoids late night.
         * NIGHT_OWL: Slow morning, peak activity 6pm-2am, often plays late.
         * NEUTRAL: More flexible schedule, moderate variation across day.
         */
        volatile String chronotype = "NEUTRAL";
        
        /**
         * Peak performance hour offset from chronotype default (in hours, -2 to +2).
         * Allows individual variation within chronotype.
         * E.g., EARLY_BIRD with peakHourOffset=1.5 peaks later than typical early bird.
         */
        volatile double peakHourOffset = 0.0;
        
        /**
         * How strongly the player's performance varies with time of day (0.1-0.5).
         * Higher values = more pronounced circadian effects (slower when tired).
         * Lower values = more consistent performance regardless of time.
         */
        volatile double circadianStrength = 0.25;
        
        // === Motor Learning (task-specific skill improvement) ===
        /**
         * Per-task proficiency map tracking motor learning over time.
         * 
         * Key: Task type identifier (e.g., "WOODCUTTING", "FISHING", "COMBAT", "BANKING")
         * Value: Accumulated experience (in minutes of practice)
         * 
         * Proficiency modifier is calculated from experience using:
         *   modifier = 1.0 - (capacity * (1 - exp(-experience / tau)))
         * 
         * This creates logarithmic learning: fast improvement initially,
         * plateauing at max improvement (~15-20% faster at familiar tasks).
         */
        Map<String, Double> taskExperience = new ConcurrentHashMap<>();
        
        /**
         * Maximum motor learning improvement as a fraction (0.15-0.25).
         * Generated per-profile to create individual learning capacity.
         * Some players learn faster/more than others.
         */
        volatile double motorLearningCapacity = 0.18;
        
        /**
         * Learning rate time constant (in minutes of practice).
         * Lower = faster initial learning, higher = slower to plateau.
         * Typical range: 200-600 minutes (3-10 hours to reach ~63% of max improvement).
         */
        volatile double motorLearningTau = 400.0;

        /**
         * Validate profile data is within expected bounds.
         */
        public boolean isValid() {
            return mouseSpeedMultiplier >= 0.5 && mouseSpeedMultiplier <= 2.0
                    && clickVarianceModifier >= 0.5 && clickVarianceModifier <= 2.0
                    && typingSpeedWPM >= 20 && typingSpeedWPM <= 120
                    && dominantHandBias >= 0.0 && dominantHandBias <= 1.0
                    && breakFatigueThreshold >= 0.3 && breakFatigueThreshold <= 1.0
                    && runEnableThreshold >= 25 && runEnableThreshold <= 65
                    && runDisableThreshold >= 5 && runDisableThreshold <= 25
                    && runEnableThreshold - runDisableThreshold >= 15
                    && minWalkClickIntervalBase >= 2.0 && minWalkClickIntervalBase <= 6.0
                    && feedbackDelaySamples >= 10 && feedbackDelaySamples <= 25
                    && motorLearningCapacity >= 0.10 && motorLearningCapacity <= 0.30
                    && motorLearningTau >= 150.0 && motorLearningTau <= 700.0;
        }
    }
}
