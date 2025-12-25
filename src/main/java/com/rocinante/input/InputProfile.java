package com.rocinante.input;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Per-session input characteristics as specified in REQUIREMENTS.md Section 3.3.
 *
 * Generates randomized behavioral characteristics at session start:
 * - Base mouse speed multiplier (0.8-1.3)
 * - Click variance modifier (0.7-1.4)
 * - Preferred idle positions (2-4 screen regions)
 * - Typing speed WPM (40-80 range, fixed for session)
 * - Dominant hand simulation (slight bias toward right-side screen interactions)
 *
 * Profiles persist across sessions with ±10% drift per session to simulate natural skill variation.
 */
@Slf4j
@Singleton
public class InputProfile {

    private static final String PROFILE_DIR = ".runelite/rocinante/profiles";
    private static final double DRIFT_PERCENTAGE = 0.10; // ±10% drift per session

    // Profile constraints from REQUIREMENTS.md Section 3.3
    private static final double MIN_MOUSE_SPEED = 0.8;
    private static final double MAX_MOUSE_SPEED = 1.3;
    private static final double MIN_CLICK_VARIANCE = 0.7;
    private static final double MAX_CLICK_VARIANCE = 1.4;
    private static final int MIN_TYPING_WPM = 40;
    private static final int MAX_TYPING_WPM = 80;
    private static final int MIN_IDLE_POSITIONS = 2;
    private static final int MAX_IDLE_POSITIONS = 4;

    private final Randomization randomization;
    private final Gson gson;

    @Getter
    private ProfileData profileData;

    @Getter
    private String accountHash;

    @Getter
    private boolean loaded = false;

    @Inject
    public InputProfile(Randomization randomization) {
        this.randomization = randomization;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        this.profileData = new ProfileData();
    }

    /**
     * Initialize or load profile for a given account name.
     * Called when a user logs in to the game.
     *
     * @param accountName the account/username to load profile for
     */
    public void initializeForAccount(String accountName) {
        this.accountHash = hashAccountName(accountName);

        Path profilePath = getProfilePath();
        if (Files.exists(profilePath)) {
            loadProfile(profilePath);
            applySessionDrift();
            saveProfile(profilePath);
        } else {
            generateNewProfile(accountName);
            saveProfile(profilePath);
        }

        loaded = true;
        log.info("Input profile initialized for account hash: {}", accountHash);
    }

    /**
     * Initialize with default profile (for testing or when account name unavailable).
     */
    public void initializeDefault() {
        this.accountHash = "default";
        generateNewProfile("default");
        loaded = true;
        log.info("Default input profile initialized");
    }

    /**
     * Generate a new profile from account name as seed.
     * As per spec: "generate profile from SHA256(username + random_salt) as seed"
     */
    private void generateNewProfile(String accountName) {
        long seed = generateSeed(accountName);
        Random seededRandom = new Random(seed);
        Randomization seededRandomization = new Randomization(seed);

        profileData = new ProfileData();

        // Mouse speed multiplier: 0.8-1.3
        profileData.mouseSpeedMultiplier = MIN_MOUSE_SPEED +
                seededRandom.nextDouble() * (MAX_MOUSE_SPEED - MIN_MOUSE_SPEED);

        // Click variance modifier: 0.7-1.4
        profileData.clickVarianceModifier = MIN_CLICK_VARIANCE +
                seededRandom.nextDouble() * (MAX_CLICK_VARIANCE - MIN_CLICK_VARIANCE);

        // Typing speed WPM: 40-80 (fixed for session)
        profileData.typingSpeedWPM = seededRandomization.uniformRandomInt(MIN_TYPING_WPM, MAX_TYPING_WPM);

        // Dominant hand bias (0.5 = no bias, >0.5 = right bias, <0.5 = left bias)
        // Most people are right-handed, so bias toward 0.55-0.75
        profileData.dominantHandBias = 0.55 + seededRandom.nextDouble() * 0.20;

        // Preferred idle positions: 2-4 regions
        int numIdlePositions = seededRandomization.uniformRandomInt(MIN_IDLE_POSITIONS, MAX_IDLE_POSITIONS);
        profileData.preferredIdlePositions = selectRandomIdlePositions(seededRandom, numIdlePositions);

        // Misclick rate: 1-3% (from REQUIREMENTS 3.1.2)
        profileData.baseMisclickRate = 0.01 + seededRandom.nextDouble() * 0.02;

        // Typo rate: 0.5-2% (from REQUIREMENTS 3.2.1)
        profileData.baseTypoRate = 0.005 + seededRandom.nextDouble() * 0.015;

        // Overshoot probability: 8-15% (from REQUIREMENTS 3.1.1)
        profileData.overshootProbability = 0.08 + seededRandom.nextDouble() * 0.07;

        // Micro-correction probability: 20% baseline (from REQUIREMENTS 3.1.1)
        profileData.microCorrectionProbability = 0.15 + seededRandom.nextDouble() * 0.10;

        // Session count starts at 1
        profileData.sessionCount = 1;
        profileData.totalPlaytimeHours = 0;

        log.info("Generated new input profile: speed={}, clickVar={}, wpm={}, handBias={}",
                String.format("%.2f", profileData.mouseSpeedMultiplier),
                String.format("%.2f", profileData.clickVarianceModifier),
                profileData.typingSpeedWPM,
                String.format("%.2f", profileData.dominantHandBias));
    }

    /**
     * Apply ±10% drift to profile values to simulate natural skill variation.
     * Called at the start of each session.
     */
    private void applySessionDrift() {
        profileData.sessionCount++;

        // Apply drift to mouse speed (bounded)
        profileData.mouseSpeedMultiplier = applyDrift(
                profileData.mouseSpeedMultiplier, MIN_MOUSE_SPEED, MAX_MOUSE_SPEED);

        // Apply drift to click variance (bounded)
        profileData.clickVarianceModifier = applyDrift(
                profileData.clickVarianceModifier, MIN_CLICK_VARIANCE, MAX_CLICK_VARIANCE);

        // Typing WPM stays fixed per session but can drift slightly between sessions
        // Smaller drift for WPM (±5%)
        int wpmDrift = (int) Math.round(profileData.typingSpeedWPM * randomization.uniformRandom(-0.05, 0.05));
        profileData.typingSpeedWPM = Randomization.clamp(
                profileData.typingSpeedWPM + wpmDrift, MIN_TYPING_WPM, MAX_TYPING_WPM);

        // Slight drift to hand bias (very small)
        profileData.dominantHandBias = applyDrift(profileData.dominantHandBias, 0.45, 0.80, 0.02);

        // Drift error rates
        profileData.baseMisclickRate = applyDrift(profileData.baseMisclickRate, 0.01, 0.03);
        profileData.baseTypoRate = applyDrift(profileData.baseTypoRate, 0.005, 0.02);

        log.debug("Applied session drift (session #{}): speed={}, clickVar={}, wpm={}",
                profileData.sessionCount,
                String.format("%.2f", profileData.mouseSpeedMultiplier),
                String.format("%.2f", profileData.clickVarianceModifier),
                profileData.typingSpeedWPM);
    }

    /**
     * Apply ±10% drift to a value within bounds.
     */
    private double applyDrift(double value, double min, double max) {
        return applyDrift(value, min, max, DRIFT_PERCENTAGE);
    }

    /**
     * Apply drift to a value within bounds with custom drift percentage.
     */
    private double applyDrift(double value, double min, double max, double driftPct) {
        double drift = value * randomization.uniformRandom(-driftPct, driftPct);
        return Randomization.clamp(value + drift, min, max);
    }

    /**
     * Select random idle positions from available screen regions.
     */
    private List<String> selectRandomIdlePositions(Random seededRandom, int count) {
        List<ScreenRegion> available = new ArrayList<>(Arrays.asList(ScreenRegion.getDefaultIdleRegions()));
        // Add some additional regions
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

    /**
     * Generate deterministic seed from account name.
     */
    private long generateSeed(String accountName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Add a salt for uniqueness
            String salted = accountName + "_rocinante_salt_v1";
            byte[] hash = digest.digest(salted.getBytes(StandardCharsets.UTF_8));
            // Use first 8 bytes as long seed
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (hash[i] & 0xFF);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return accountName.hashCode();
        }
    }

    /**
     * Hash account name for file storage (privacy).
     */
    private String hashAccountName(String accountName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(accountName.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 8; i++) { // Use first 8 bytes for shorter filename
                hexString.append(String.format("%02x", hash[i]));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(accountName.hashCode());
        }
    }

    /**
     * Get the path to the profile file.
     */
    private Path getProfilePath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, PROFILE_DIR, accountHash + ".json");
    }

    /**
     * Load profile from file.
     */
    private void loadProfile(Path path) {
        try {
            String json = Files.readString(path);
            ProfileData loaded = gson.fromJson(json, ProfileData.class);
            if (loaded != null && loaded.isValid()) {
                this.profileData = loaded;
                log.info("Loaded input profile from: {}", path);
            } else {
                log.warn("Invalid profile data, regenerating");
                generateNewProfile(accountHash);
            }
        } catch (IOException e) {
            log.warn("Failed to load profile, generating new: {}", e.getMessage());
            generateNewProfile(accountHash);
        }
    }

    /**
     * Save profile to file.
     */
    private void saveProfile(Path path) {
        try {
            Files.createDirectories(path.getParent());

            // Backup existing file first (corruption recovery as per spec)
            if (Files.exists(path)) {
                Path backup = Paths.get(path.toString() + ".bak");
                Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String json = gson.toJson(profileData);
            Files.writeString(path, json);
            log.debug("Saved input profile to: {}", path);
        } catch (IOException e) {
            log.error("Failed to save profile: {}", e.getMessage());
        }
    }

    /**
     * Record playtime for long-term drift calculations.
     *
     * @param hours hours played in this session
     */
    public void recordPlaytime(double hours) {
        profileData.totalPlaytimeHours += hours;
        saveProfile(getProfilePath());
    }

    // ========================================================================
    // Accessor Methods
    // ========================================================================

    /**
     * Get the mouse speed multiplier for this session.
     */
    public double getMouseSpeedMultiplier() {
        return profileData.mouseSpeedMultiplier;
    }

    /**
     * Get the click variance modifier for this session.
     */
    public double getClickVarianceModifier() {
        return profileData.clickVarianceModifier;
    }

    /**
     * Get the typing speed in words per minute.
     */
    public int getTypingSpeedWPM() {
        return profileData.typingSpeedWPM;
    }

    /**
     * Get the dominant hand bias (>0.5 = right bias).
     */
    public double getDominantHandBias() {
        return profileData.dominantHandBias;
    }

    /**
     * Get the preferred idle screen regions.
     */
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
            // Fallback to defaults
            regions.addAll(Arrays.asList(ScreenRegion.getDefaultIdleRegions()));
        }
        return regions;
    }

    /**
     * Get the base misclick rate (1-3%).
     */
    public double getBaseMisclickRate() {
        return profileData.baseMisclickRate;
    }

    /**
     * Get the base typo rate (0.5-2%).
     */
    public double getBaseTypoRate() {
        return profileData.baseTypoRate;
    }

    /**
     * Get the overshoot probability (8-15%).
     */
    public double getOvershootProbability() {
        return profileData.overshootProbability;
    }

    /**
     * Get the micro-correction probability (~20%).
     */
    public double getMicroCorrectionProbability() {
        return profileData.microCorrectionProbability;
    }

    /**
     * Get the total session count for this account.
     */
    public int getSessionCount() {
        return profileData.sessionCount;
    }

    /**
     * Get total recorded playtime in hours.
     */
    public double getTotalPlaytimeHours() {
        return profileData.totalPlaytimeHours;
    }

    /**
     * Calculate the inter-character delay for typing based on WPM.
     * Average word length is 5 characters.
     *
     * @return base inter-character delay in milliseconds
     */
    public long getBaseTypingDelay() {
        // WPM = words per minute
        // 1 word = 5 characters
        // Characters per minute = WPM * 5
        // Characters per second = (WPM * 5) / 60
        // Milliseconds per character = 60000 / (WPM * 5) = 12000 / WPM
        return Math.round(12000.0 / profileData.typingSpeedWPM);
    }

    /**
     * Select a random idle position weighted by dominant hand bias.
     *
     * @return a screen region for idle behavior
     */
    public ScreenRegion selectIdlePosition() {
        List<ScreenRegion> positions = getPreferredIdlePositions();
        if (positions.isEmpty()) {
            return ScreenRegion.INVENTORY;
        }

        // Apply dominant hand bias
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
    // Profile Data Class
    // ========================================================================

    /**
     * Data class for profile persistence.
     */
    @Getter
    @Setter
    public static class ProfileData {
        private double mouseSpeedMultiplier = 1.0;
        private double clickVarianceModifier = 1.0;
        private int typingSpeedWPM = 60;
        private double dominantHandBias = 0.6;
        private List<String> preferredIdlePositions = new ArrayList<>();
        private double baseMisclickRate = 0.02;
        private double baseTypoRate = 0.01;
        private double overshootProbability = 0.12;
        private double microCorrectionProbability = 0.20;
        private int sessionCount = 0;
        private double totalPlaytimeHours = 0;

        /**
         * Validate profile data is within expected bounds.
         */
        public boolean isValid() {
            return mouseSpeedMultiplier >= 0.5 && mouseSpeedMultiplier <= 2.0
                    && clickVarianceModifier >= 0.5 && clickVarianceModifier <= 2.0
                    && typingSpeedWPM >= 20 && typingSpeedWPM <= 120
                    && dominantHandBias >= 0.0 && dominantHandBias <= 1.0;
        }
    }
}

