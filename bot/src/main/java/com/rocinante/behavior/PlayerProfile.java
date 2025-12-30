package com.rocinante.behavior;

import com.google.gson.Gson;
import com.rocinante.data.GsonFactory;
import com.rocinante.input.ScreenRegion;
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
     * Generate a new profile from account name as seed.
     * Creates a deterministic but unique behavioral fingerprint.
     * 
     * @param accountName the account name to generate profile for
     * @param accountType optional account type for type-specific defaults (null = unknown/normal)
     */
    private void generateNewProfile(String accountName, com.rocinante.behavior.AccountType accountType) {
        long seed = generateSeed(accountName);
        Random seededRandom = new Random(seed);
        Randomization seededRandomization = new Randomization(seed);

        profileData = new ProfileData();
        profileData.schemaVersion = CURRENT_SCHEMA_VERSION;
        profileData.accountHash = accountHash;
        profileData.createdAt = Instant.now();
        
        // === Input characteristics (from InputProfile) ===
        profileData.mouseSpeedMultiplier = MIN_MOUSE_SPEED +
                seededRandom.nextDouble() * (MAX_MOUSE_SPEED - MIN_MOUSE_SPEED);
        
        profileData.clickVarianceModifier = MIN_CLICK_VARIANCE +
                seededRandom.nextDouble() * (MAX_CLICK_VARIANCE - MIN_CLICK_VARIANCE);
        
        profileData.typingSpeedWPM = seededRandomization.uniformRandomInt(MIN_TYPING_WPM, MAX_TYPING_WPM);
        
        // Most people are right-handed, bias toward 0.55-0.75
        profileData.dominantHandBias = 0.55 + seededRandom.nextDouble() * 0.20;
        
        // Preferred idle positions: 2-4 regions
        int numIdlePositions = seededRandomization.uniformRandomInt(2, 4);
        profileData.preferredIdlePositions = selectRandomIdlePositions(seededRandom, numIdlePositions);
        
        // Error rates
        profileData.baseMisclickRate = 0.01 + seededRandom.nextDouble() * 0.02;  // 1-3%
        profileData.baseTypoRate = 0.005 + seededRandom.nextDouble() * 0.015;    // 0.5-2%
        profileData.overshootProbability = 0.08 + seededRandom.nextDouble() * 0.07;  // 8-15%
        profileData.microCorrectionProbability = 0.15 + seededRandom.nextDouble() * 0.10;  // 15-25%
        
        // === Break behavior ===
        profileData.breakFatigueThreshold = MIN_BREAK_THRESHOLD +
                seededRandom.nextDouble() * (MAX_BREAK_THRESHOLD - MIN_BREAK_THRESHOLD);
        
        profileData.breakActivityWeights = generateBreakActivityWeights(seededRandom);
        
        // === Camera preferences ===
        profileData.preferredCompassAngle = seededRandom.nextDouble() * 360.0;
        profileData.pitchPreference = selectPitchPreference(seededRandom);
        profileData.cameraChangeFrequency = 5.0 + seededRandom.nextDouble() * 15.0;  // 5-20 per hour
        
        // === Camera hold preferences (fidgeting behavior) ===
        profileData.cameraHoldFrequency = 0.05 + seededRandom.nextDouble() * 0.25;  // 5-30%
        profileData.cameraHoldPreferredDirection = selectCameraHoldDirection(seededRandom);
        profileData.cameraHoldSpeedPreference = selectCameraHoldSpeed(seededRandom);
        
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
    private void applySessionDrift() {
        // Input characteristics
        profileData.mouseSpeedMultiplier = applyDrift(
                profileData.mouseSpeedMultiplier, MIN_MOUSE_SPEED, MAX_MOUSE_SPEED);
        profileData.clickVarianceModifier = applyDrift(
                profileData.clickVarianceModifier, MIN_CLICK_VARIANCE, MAX_CLICK_VARIANCE);
        
        // WPM has smaller drift
        int wpmDrift = (int) Math.round(profileData.typingSpeedWPM * 
                randomization.uniformRandom(-0.02, 0.02));
        profileData.typingSpeedWPM = Randomization.clamp(
                profileData.typingSpeedWPM + wpmDrift, MIN_TYPING_WPM, MAX_TYPING_WPM);
        
        // Error rates
        profileData.baseMisclickRate = applyDrift(profileData.baseMisclickRate, 0.01, 0.03);
        profileData.baseTypoRate = applyDrift(profileData.baseTypoRate, 0.005, 0.02);
        
        // Break threshold
        profileData.breakFatigueThreshold = applyDrift(
                profileData.breakFatigueThreshold, MIN_BREAK_THRESHOLD, MAX_BREAK_THRESHOLD);
        
        // Camera angle drift (larger, ±10°)
        double angleDrift = randomization.uniformRandom(-10, 10);
        profileData.preferredCompassAngle = (profileData.preferredCompassAngle + angleDrift + 360) % 360;
        
        // Frequency drift
        profileData.xpCheckFrequency = applyDrift(profileData.xpCheckFrequency, 0, 20, 0.10);
        profileData.playerInspectionFrequency = applyDrift(profileData.playerInspectionFrequency, 0, 8, 0.10);

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
        
        // Calculate how many drift periods we've crossed
        int oldPeriods = (int) (oldPlaytime / LONG_TERM_DRIFT_HOURS);
        int newPeriods = (int) (profileData.totalPlaytimeHours / LONG_TERM_DRIFT_HOURS);
        
        if (newPeriods > oldPeriods) {
            int periodsToApply = newPeriods - oldPeriods;
            
            for (int i = 0; i < periodsToApply; i++) {
                // Mouse speed improvement: +1-3% per 20 hours (cap at 1.3)
                double speedImprovement = randomization.uniformRandom(0.01, 0.03);
                profileData.mouseSpeedMultiplier = Math.min(MAX_MOUSE_SPEED,
                        profileData.mouseSpeedMultiplier + speedImprovement);
                
                // Click precision improvement: reduce variance by 2-5% per 50 hours
                // (apply partial improvement every 20 hours)
                double varianceReduction = randomization.uniformRandom(0.008, 0.02);
                profileData.clickVarianceModifier = Math.max(MIN_CLICK_VARIANCE,
                        profileData.clickVarianceModifier - varianceReduction);
            }
            
            log.info("Applied long-term drift after {} hours total playtime", 
                    profileData.totalPlaytimeHours);
        }
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
            log.trace("Cancelled previous save task");
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
                this.profileData = loaded;
                log.info("Loaded profile from: {} (session #{}, {} hours played)", 
                        path, loaded.sessionCount, String.format("%.1f", loaded.totalPlaytimeHours));
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
    }

    private void saveProfile(Path path) {
        try {
            Files.createDirectories(path.getParent());

            // Backup existing file
            if (Files.exists(path)) {
                Path backup = Paths.get(path.toString() + ".bak");
                Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String json = gson.toJson(profileData);
            Files.writeString(path, json);
            log.trace("Saved profile to: {}", path);
        } catch (IOException e) {
            log.error("Failed to save profile: {}", e.getMessage());
        }
    }

    /**
     * Save the profile to disk using the default path.
     * Called periodically and on logout.
     */
    public void saveProfile() {
        if (accountHash != null && !accountHash.isEmpty()) {
            saveProfile(getProfilePath());
        }
    }

    private void migrateProfile(ProfileData data) {
        // Future: handle schema migrations
        log.info("Migrating profile from schema v{} to v{}", 
                data.schemaVersion, CURRENT_SCHEMA_VERSION);
        data.schemaVersion = CURRENT_SCHEMA_VERSION;
    }

    private Path getProfilePath() {
        // Use ROCINANTE_STATUS_DIR if available (same as status files), else default
        String statusDir = System.getenv("ROCINANTE_STATUS_DIR");
        if (statusDir != null && !statusDir.isEmpty()) {
            // Status dir is like /home/runelite/.runelite/rocinante/<botId>
            // Profile dir should be sibling: /home/runelite/.runelite/rocinante/profiles
            Path statusPath = Paths.get(statusDir);
            return statusPath.getParent().resolve(PROFILE_SUBDIR).resolve(accountHash + ".json");
        }
        return Paths.get(DEFAULT_PROFILE_DIR, accountHash + ".json");
    }

    // ========================================================================
    // Seed/Hash Generation
    // ========================================================================

    private long generateSeed(String accountName) {
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

    public int getSessionCount() {
        return profileData.sessionCount;
    }

    public double getTotalPlaytimeHours() {
        return profileData.totalPlaytimeHours;
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
    // Behavioral Accessors
    // ========================================================================

    public double getBreakFatigueThreshold() {
        return profileData.breakFatigueThreshold;
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
        volatile Instant createdAt;
        volatile Instant lastSessionStart;
        volatile Instant lastLogout;

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

        // === Break behavior ===
        volatile double breakFatigueThreshold = 0.80;
        Map<String, Double> breakActivityWeights = new ConcurrentHashMap<>();

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

        /**
         * Validate profile data is within expected bounds.
         */
        public boolean isValid() {
            return mouseSpeedMultiplier >= 0.5 && mouseSpeedMultiplier <= 2.0
                    && clickVarianceModifier >= 0.5 && clickVarianceModifier <= 2.0
                    && typingSpeedWPM >= 20 && typingSpeedWPM <= 120
                    && dominantHandBias >= 0.0 && dominantHandBias <= 1.0
                    && breakFatigueThreshold >= 0.3 && breakFatigueThreshold <= 1.0;
        }
    }
}

