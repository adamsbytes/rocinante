package com.rocinante.behavior;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    private static final String PROFILE_DIR = ".runelite/rocinante/profiles";
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
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
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
        this.gson = new GsonBuilder().setPrettyPrinting().create();
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
     */
    public void initializeForAccount(String accountName) {
        this.accountHash = hashAccountName(accountName);
        this.sessionStartTime = Instant.now();

        Path profilePath = getProfilePath();
        if (Files.exists(profilePath)) {
            loadProfile(profilePath);
            applySessionDrift();
        } else {
            generateNewProfile(accountName);
        }
        
        // Update session tracking
        profileData.lastSessionStart = Instant.now();
        profileData.sessionCount++;
        
        // Save immediately and schedule periodic saves
        saveProfile(profilePath);
        schedulePersistence();
        
        loaded = true;
        log.info("PlayerProfile initialized for account hash: {} (session #{})", 
                accountHash, profileData.sessionCount);
    }

    /**
     * Initialize with default profile (for testing or when account name unavailable).
     */
    public void initializeDefault() {
        this.accountHash = "default";
        this.sessionStartTime = Instant.now();
        generateNewProfile("default");
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
     */
    private void generateNewProfile(String accountName) {
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
        
        // === Session patterns ===
        profileData.sessionRituals = selectSessionRituals(seededRandom);
        profileData.ritualExecutionProbability = 0.70 + seededRandom.nextDouble() * 0.20;  // 70-90%
        profileData.xpCheckFrequency = seededRandom.nextDouble() * 15.0;  // 0-15 per hour
        profileData.playerInspectionFrequency = seededRandom.nextDouble() * 5.0;  // 0-5 per hour
        
        // === Action sequencing ===
        profileData.bankingSequenceWeights = generateSequenceWeights(seededRandom, 
                Arrays.asList("TYPE_A", "TYPE_B", "TYPE_C"));
        profileData.combatPrepSequenceWeights = generateSequenceWeights(seededRandom,
                Arrays.asList("TYPE_A", "TYPE_B", "TYPE_C"));
        
        // === Teleport preferences ===
        profileData.teleportMethodWeights = generateTeleportWeights(seededRandom);
        profileData.lawRuneAversion = seededRandom.nextDouble() * 0.5;  // 0-50% aversion
        
        // === Metrics ===
        profileData.sessionCount = 0;
        profileData.totalPlaytimeHours = 0;

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
     */
    private void schedulePersistence() {
        saveExecutor.scheduleAtFixedRate(
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
                this.profileData = loaded;
                log.info("Loaded profile from: {} (session #{}, {} hours played)", 
                        path, loaded.sessionCount, String.format("%.1f", loaded.totalPlaytimeHours));
            } else {
                log.warn("Invalid profile data at {}, regenerating", path);
                generateNewProfile(accountHash);
            }
        } catch (IOException e) {
            log.warn("Failed to load profile, generating new: {}", e.getMessage());
            generateNewProfile(accountHash);
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
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, PROFILE_DIR, accountHash + ".json");
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

    private Map<String, Double> generateTeleportWeights(Random seededRandom) {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("SPELLBOOK", 0.15 + seededRandom.nextDouble() * 0.25);
        weights.put("JEWELRY", 0.15 + seededRandom.nextDouble() * 0.25);
        weights.put("FAIRY_RING", 0.10 + seededRandom.nextDouble() * 0.20);
        weights.put("SPIRIT_TREE", 0.05 + seededRandom.nextDouble() * 0.15);
        weights.put("HOUSE_PORTAL", 0.10 + seededRandom.nextDouble() * 0.20);
        weights.put("TABLETS", 0.10 + seededRandom.nextDouble() * 0.15);
        
        // Normalize
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        for (String key : weights.keySet()) {
            weights.put(key, weights.get(key) / total);
        }
        
        return weights;
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

    public Map<String, Double> getTeleportMethodWeights() {
        return Collections.unmodifiableMap(profileData.teleportMethodWeights);
    }

    public double getLawRuneAversion() {
        return profileData.lawRuneAversion;
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
    @Getter
    @Setter
    public static class ProfileData {
        // Schema versioning
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        String accountHash;
        Instant createdAt;
        Instant lastSessionStart;
        Instant lastLogout;

        // === Input characteristics ===
        double mouseSpeedMultiplier = 1.0;
        double clickVarianceModifier = 1.0;
        int typingSpeedWPM = 60;
        double dominantHandBias = 0.6;
        List<String> preferredIdlePositions = new ArrayList<>();
        double baseMisclickRate = 0.02;
        double baseTypoRate = 0.01;
        double overshootProbability = 0.12;
        double microCorrectionProbability = 0.20;

        // === Break behavior ===
        double breakFatigueThreshold = 0.80;
        Map<String, Double> breakActivityWeights = new LinkedHashMap<>();

        // === Camera preferences ===
        double preferredCompassAngle = 0.0;
        String pitchPreference = "MEDIUM";
        double cameraChangeFrequency = 10.0;

        // === Session patterns ===
        List<String> sessionRituals = new ArrayList<>();
        double ritualExecutionProbability = 0.80;
        double xpCheckFrequency = 5.0;
        double playerInspectionFrequency = 2.0;

        // === Action sequencing ===
        Map<String, Double> bankingSequenceWeights = new LinkedHashMap<>();
        Map<String, Double> combatPrepSequenceWeights = new LinkedHashMap<>();

        // === Teleport preferences ===
        Map<String, Double> teleportMethodWeights = new LinkedHashMap<>();
        double lawRuneAversion = 0.0;

        // === Metrics ===
        int sessionCount = 0;
        double totalPlaytimeHours = 0;

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

