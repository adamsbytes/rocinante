package com.rocinante.input;

import com.rocinante.behavior.FatigueModel;
import com.rocinante.behavior.PlayerProfile;
import com.rocinante.input.uinput.KeyCodeMapper;
import com.rocinante.input.uinput.UInputKeyboardDevice;
import com.rocinante.util.Randomization;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import static com.rocinante.input.uinput.LinuxInputConstants.*;

/**
 * Humanized keyboard controller using java.awt.Robot.
 * Implements all specifications from REQUIREMENTS.md Section 3.2:
 *
 * - Typing simulation with inter-key delays (50-150ms base)
 * - Bigram speed adjustment (common pairs 20% faster)
 * - Typo simulation (0.5-2% with backspace correction)
 * - Burst typing patterns (3-5 chars at 30-50ms)
 * - Hotkey reaction time (150-400ms delay)
 * - Key hold duration (40-100ms)
 * - F-key learning (subsequent uses 15-30% faster)
 *
 * Compatible with Linux headless environments (Xvfb).
 */
@Slf4j
@Singleton
public class RobotKeyboardController {

    // Inter-key delay constants (REQUIREMENTS 3.2.1)
    private static final long MIN_INTER_KEY_DELAY_MS = 50;
    private static final long MAX_INTER_KEY_DELAY_MS = 150;

    // Bigram speed multiplier (20% faster for common pairs)
    private static final double BIGRAM_SPEED_MULTIPLIER = 0.80;

    // Typo simulation constants
    private static final long MIN_TYPO_CORRECTION_DELAY_MS = 100;
    private static final long MAX_TYPO_CORRECTION_DELAY_MS = 300;

    // Burst typing constants
    private static final int MIN_BURST_LENGTH = 3;
    private static final int MAX_BURST_LENGTH = 5;
    private static final long MIN_BURST_DELAY_MS = 30;
    private static final long MAX_BURST_DELAY_MS = 50;
    private static final long MIN_POST_BURST_PAUSE_MS = 200;
    private static final long MAX_POST_BURST_PAUSE_MS = 400;
    private static final double BURST_PROBABILITY = 0.15; // 15% chance to enter burst mode

    // Hotkey constants (REQUIREMENTS 3.2.2)
    private static final long MIN_HOTKEY_REACTION_MS = 150;
    private static final long MAX_HOTKEY_REACTION_MS = 400;
    private static final long MIN_KEY_HOLD_MS = 40;
    private static final long MAX_KEY_HOLD_MS = 100;

    // F-key learning constants
    private static final double MIN_FKEY_SPEEDUP = 0.70; // 30% faster
    private static final double MAX_FKEY_SPEEDUP = 0.85; // 15% faster

    // Common English bigrams for speed adjustment
    private static final Map<String, Double> BIGRAM_SPEEDS = new HashMap<>();

    static {
        // Most common English bigrams - 20% faster
        BIGRAM_SPEEDS.put("th", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("he", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("in", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("er", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("an", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("re", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("on", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("at", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("en", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("nd", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ti", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("es", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("or", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("te", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("of", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ed", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("is", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("it", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("al", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ar", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("st", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("to", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("nt", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ng", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("se", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ha", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("as", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ou", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("io", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("le", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ve", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("co", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("me", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("de", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("hi", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ri", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ro", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ic", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ne", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ea", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ra", BIGRAM_SPEED_MULTIPLIER);
        BIGRAM_SPEEDS.put("ce", BIGRAM_SPEED_MULTIPLIER);
    }

    // Keyboard neighbors for typo simulation (QWERTY layout)
    private static final Map<Character, char[]> KEYBOARD_NEIGHBORS = new HashMap<>();

    static {
        KEYBOARD_NEIGHBORS.put('q', new char[]{'w', 'a'});
        KEYBOARD_NEIGHBORS.put('w', new char[]{'q', 'e', 'a', 's'});
        KEYBOARD_NEIGHBORS.put('e', new char[]{'w', 'r', 's', 'd'});
        KEYBOARD_NEIGHBORS.put('r', new char[]{'e', 't', 'd', 'f'});
        KEYBOARD_NEIGHBORS.put('t', new char[]{'r', 'y', 'f', 'g'});
        KEYBOARD_NEIGHBORS.put('y', new char[]{'t', 'u', 'g', 'h'});
        KEYBOARD_NEIGHBORS.put('u', new char[]{'y', 'i', 'h', 'j'});
        KEYBOARD_NEIGHBORS.put('i', new char[]{'u', 'o', 'j', 'k'});
        KEYBOARD_NEIGHBORS.put('o', new char[]{'i', 'p', 'k', 'l'});
        KEYBOARD_NEIGHBORS.put('p', new char[]{'o', 'l'});
        KEYBOARD_NEIGHBORS.put('a', new char[]{'q', 'w', 's', 'z'});
        KEYBOARD_NEIGHBORS.put('s', new char[]{'w', 'e', 'a', 'd', 'z', 'x'});
        KEYBOARD_NEIGHBORS.put('d', new char[]{'e', 'r', 's', 'f', 'x', 'c'});
        KEYBOARD_NEIGHBORS.put('f', new char[]{'r', 't', 'd', 'g', 'c', 'v'});
        KEYBOARD_NEIGHBORS.put('g', new char[]{'t', 'y', 'f', 'h', 'v', 'b'});
        KEYBOARD_NEIGHBORS.put('h', new char[]{'y', 'u', 'g', 'j', 'b', 'n'});
        KEYBOARD_NEIGHBORS.put('j', new char[]{'u', 'i', 'h', 'k', 'n', 'm'});
        KEYBOARD_NEIGHBORS.put('k', new char[]{'i', 'o', 'j', 'l', 'm'});
        KEYBOARD_NEIGHBORS.put('l', new char[]{'o', 'p', 'k'});
        KEYBOARD_NEIGHBORS.put('z', new char[]{'a', 's', 'x'});
        KEYBOARD_NEIGHBORS.put('x', new char[]{'z', 's', 'd', 'c'});
        KEYBOARD_NEIGHBORS.put('c', new char[]{'x', 'd', 'f', 'v'});
        KEYBOARD_NEIGHBORS.put('v', new char[]{'c', 'f', 'g', 'b'});
        KEYBOARD_NEIGHBORS.put('b', new char[]{'v', 'g', 'h', 'n'});
        KEYBOARD_NEIGHBORS.put('n', new char[]{'b', 'h', 'j', 'm'});
        KEYBOARD_NEIGHBORS.put('m', new char[]{'n', 'j', 'k'});
    }

    private final UInputKeyboardDevice keyboardDevice;
    private final Randomization randomization;
    private final PlayerProfile playerProfile;
    private final ScheduledExecutorService executor;
    private final FatigueModel fatigueModel;

    // F-key learning tracking
    @Getter
    private final Map<Integer, Integer> fKeyUsageCount = new ConcurrentHashMap<>();

    @Getter
    private final Map<Integer, Long> fKeyLastUsedTime = new ConcurrentHashMap<>();

    @Inject
    public RobotKeyboardController(Randomization randomization, PlayerProfile playerProfile,
                                   FatigueModel fatigueModel) {
        // Create UInput virtual keyboard device using profile preset and polling rate
        // This injects input at the kernel level, bypassing java.awt.Robot's XTest flag
        // The machineId is used for deterministic physical path generation across restarts
        this.keyboardDevice = new UInputKeyboardDevice(
            playerProfile.getKeyboardDevicePreset(),
            playerProfile.getKeyboardPollingRate(),
            playerProfile.getMachineId()
        );
        this.randomization = randomization;
        this.playerProfile = playerProfile;
        this.fatigueModel = fatigueModel;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RobotKeyboardController");
            t.setDaemon(true);
            return t;
        });

        log.info("RobotKeyboardController initialized with UInput device: {}", 
                playerProfile.getKeyboardDevicePreset().getName());
    }

    // ========================================================================
    // Typing Methods
    // ========================================================================

    /**
     * Type text with humanized timing (no intentional typos).
     *
     * @param text the text to type
     * @return CompletableFuture that completes when typing is done
     */
    public CompletableFuture<Void> type(String text) {
        return executeTyping(text, false);
    }

    /**
     * Type text with humanized timing and occasional typos.
     * Typos are corrected with backspace as per REQUIREMENTS 3.2.1.
     *
     * @param text the text to type
     * @return CompletableFuture that completes when typing is done
     */
    public CompletableFuture<Void> typeWithTypos(String text) {
        return executeTyping(text, true);
    }

    /**
     * Execute typing with all humanization features.
     */
    private CompletableFuture<Void> executeTyping(String text, boolean allowTypos) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                char previousChar = '\0';
                int burstRemaining = 0;
                boolean inBurst = false;

                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);

                    // Check for burst mode
                    if (!inBurst && randomization.chance(BURST_PROBABILITY)) {
                        burstRemaining = randomization.uniformRandomInt(MIN_BURST_LENGTH, MAX_BURST_LENGTH);
                        inBurst = true;
                        log.debug("Entering burst mode for {} chars", burstRemaining);
                    }

                    // Simulate typo
                    if (allowTypos && shouldMakeTypo()) {
                        executeTypo(c);
                    } else {
                        typeCharacter(c);
                    }

                    // Inter-key delay
                    long delay;
                    if (inBurst && burstRemaining > 0) {
                        // Fast burst typing
                        delay = randomization.uniformRandomLong(MIN_BURST_DELAY_MS, MAX_BURST_DELAY_MS);
                        burstRemaining--;

                        if (burstRemaining == 0) {
                            inBurst = false;
                            // Post-burst pause
                            delay += randomization.uniformRandomLong(MIN_POST_BURST_PAUSE_MS, MAX_POST_BURST_PAUSE_MS);
                            log.debug("Burst complete, pausing");
                        }
                    } else {
                        // Normal typing with bigram adjustment
                        delay = calculateInterKeyDelay(previousChar, c);
                    }

                    if (i < text.length() - 1) {
                        Thread.sleep(delay);
                    }

                    previousChar = c;
                }

                future.complete(null);
            } catch (Exception e) {
                log.error("Typing failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Type a single character using UInput.
     */
    private void typeCharacter(char c) throws InterruptedException {
        long holdDuration = randomization.uniformRandomLong(MIN_KEY_HOLD_MS, MAX_KEY_HOLD_MS);
        keyboardDevice.typeChar(c, holdDuration);
    }

    /**
     * Calculate inter-key delay with bigram speed adjustment.
     */
    private long calculateInterKeyDelay(char previousChar, char currentChar) {
        // Get base delay from profile
        long baseDelay = getEffectiveBaseTypingDelay();

        // Add randomization around the base
        double mean = (MIN_INTER_KEY_DELAY_MS + MAX_INTER_KEY_DELAY_MS) / 2.0;
        double stdDev = (MAX_INTER_KEY_DELAY_MS - MIN_INTER_KEY_DELAY_MS) / 4.0;
        long delay = randomization.gaussianRandomLong(mean, stdDev, MIN_INTER_KEY_DELAY_MS, MAX_INTER_KEY_DELAY_MS);

        // Adjust for typing speed from profile
        delay = Math.round(delay * (60.0 / getEffectiveTypingSpeedWPM()));

        // Apply bigram speed adjustment
        if (previousChar != '\0') {
            String bigram = ("" + previousChar + currentChar).toLowerCase();
            Double speedMultiplier = BIGRAM_SPEEDS.get(bigram);
            if (speedMultiplier != null) {
                delay = Math.round(delay * speedMultiplier);
                log.debug("Bigram '{}' speedup applied", bigram);
            }
        }

        return delay;
    }

    /**
     * Get the effective base typing delay from PlayerProfile.
     */
    private long getEffectiveBaseTypingDelay() {
        if (playerProfile != null) {
            return playerProfile.getBaseTypingDelay();
        }
        return playerProfile.getBaseTypingDelay();
    }

    /**
     * Get the effective typing speed WPM from PlayerProfile.
     */
    private int getEffectiveTypingSpeedWPM() {
        if (playerProfile != null) {
            return playerProfile.getTypingSpeedWPM();
        }
        return playerProfile.getTypingSpeedWPM();
    }

    /**
     * Check if a typo should be made.
     */
    private boolean shouldMakeTypo() {
        double typoRate = getEffectiveTypoRate();
        return randomization.chance(typoRate);
    }

    /**
     * Get the effective typo rate from PlayerProfile.
     */
    private double getEffectiveTypoRate() {
        if (playerProfile != null) {
            return playerProfile.getBaseTypoRate();
        }
        return playerProfile.getBaseTypoRate();
    }

    /**
     * Execute a typo: type wrong character, pause, backspace, type correct character.
     */
    private void executeTypo(char correctChar) throws InterruptedException {
        // Get a neighboring key for realistic typo
        char typoChar = getTypoCharacter(correctChar);

        log.debug("Simulating typo: '{}' instead of '{}'", typoChar, correctChar);

        // Type the wrong character
        typeCharacter(typoChar);

        // Pause before noticing the mistake
        long correctionDelay = randomization.uniformRandomLong(
                MIN_TYPO_CORRECTION_DELAY_MS, MAX_TYPO_CORRECTION_DELAY_MS);
        Thread.sleep(correctionDelay);

        // Backspace to delete typo (don't record as separate action - part of typing)
        pressKeyInternalNoRecord(KeyEvent.VK_BACK_SPACE);

        // Small pause before typing correct character
        Thread.sleep(randomization.uniformRandomLong(30, 80));

        // Type the correct character
        typeCharacter(correctChar);
    }

    /**
     * Get a typo character (neighboring key on QWERTY keyboard).
     */
    private char getTypoCharacter(char c) {
        char lowerC = Character.toLowerCase(c);
        char[] neighbors = KEYBOARD_NEIGHBORS.get(lowerC);

        if (neighbors != null && neighbors.length > 0) {
            char typo = neighbors[randomization.uniformRandomInt(0, neighbors.length - 1)];
            // Preserve case
            return Character.isUpperCase(c) ? Character.toUpperCase(typo) : typo;
        }

        // Fallback: just use the character itself (rare case)
        return c;
    }

    // ========================================================================
    // Key Press Methods
    // ========================================================================

    /**
     * Press a single key with humanized timing.
     *
     * @param keyCode the AWT key code (e.g., KeyEvent.VK_A)
     * @return CompletableFuture that completes when key press is done
     */
    public CompletableFuture<Void> pressKey(int keyCode) {
        return pressKey(keyCode, randomization.uniformRandomLong(MIN_KEY_HOLD_MS, MAX_KEY_HOLD_MS));
    }

    /**
     * Press a single key with specified hold duration.
     *
     * @param keyCode      the AWT key code
     * @param holdDuration how long to hold the key in milliseconds
     * @return CompletableFuture that completes when key press is done
     */
    public CompletableFuture<Void> pressKey(int keyCode, long holdDuration) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                pressKeyInternal(keyCode, holdDuration, true);
                future.complete(null);
            } catch (Exception e) {
                log.error("Key press failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Core key press implementation using UInput.
     * 
     * @param keyCode the AWT key code
     * @param holdDuration how long to hold the key
     * @param recordAction whether to record this as an action for fatigue tracking
     */
    private void pressKeyInternal(int keyCode, long holdDuration, boolean recordAction) throws InterruptedException {
        keyboardDevice.tapKeyAwt(keyCode, holdDuration);
        
        if (recordAction) {
            fatigueModel.recordAction();
        }
    }

    /**
     * Internal key press with default duration, no action recording.
     * Used for typo correction backspace (part of typing action, not separate).
     */
    private void pressKeyInternalNoRecord(int keyCode) throws InterruptedException {
        long holdDuration = randomization.uniformRandomLong(MIN_KEY_HOLD_MS, MAX_KEY_HOLD_MS);
        pressKeyInternal(keyCode, holdDuration, false);
    }

    /**
     * Hold a key down without releasing it.
     * Must be paired with {@link #releaseKey(int)} to release.
     * Used for shift-click operations where shift needs to be held across multiple clicks.
     *
     * @param keyCode the AWT key code to hold
     * @return CompletableFuture that completes when key is pressed down
     */
    public CompletableFuture<Void> holdKey(int keyCode) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                keyboardDevice.pressKeyAwt(keyCode);
                log.debug("Key {} held down", keyCode);
                future.complete(null);
            } catch (Exception e) {
                log.error("Hold key failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Release a previously held key.
     * Should only be called after {@link #holdKey(int)}.
     *
     * @param keyCode the AWT key code to release
     * @return CompletableFuture that completes when key is released
     */
    public CompletableFuture<Void> releaseKey(int keyCode) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                keyboardDevice.releaseKeyAwt(keyCode);
                log.debug("Key {} released", keyCode);
                future.complete(null);
            } catch (Exception e) {
                log.error("Release key failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Press a hotkey combination (e.g., Ctrl+A).
     * Includes reaction time delay as per REQUIREMENTS 3.2.2.
     *
     * @param keyCodes the key codes to press (modifier keys first)
     * @return CompletableFuture that completes when hotkey is done
     */
    public CompletableFuture<Void> pressHotkey(int... keyCodes) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                // Reaction time delay
                long reactionDelay = randomization.uniformRandomLong(
                        MIN_HOTKEY_REACTION_MS, MAX_HOTKEY_REACTION_MS);
                Thread.sleep(reactionDelay);

                // Press all keys via UInput
                for (int keyCode : keyCodes) {
                    keyboardDevice.pressKeyAwt(keyCode);
                    Thread.sleep(randomization.uniformRandomLong(10, 30));
                }

                // Hold duration
                Thread.sleep(randomization.uniformRandomLong(MIN_KEY_HOLD_MS, MAX_KEY_HOLD_MS));

                // Release all keys in reverse order
                for (int i = keyCodes.length - 1; i >= 0; i--) {
                    keyboardDevice.releaseKeyAwt(keyCodes[i]);
                    Thread.sleep(randomization.uniformRandomLong(10, 30));
                }

                future.complete(null);
            } catch (Exception e) {
                log.error("Hotkey press failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    // ========================================================================
    // F-Key Methods with Learning
    // ========================================================================

    /**
     * Press an F-key with learning-based speed improvement.
     * As per REQUIREMENTS 3.2.2: subsequent uses are 15-30% faster.
     *
     * @param fKeyNumber the F-key number (1-12)
     * @return CompletableFuture that completes when key press is done
     */
    public CompletableFuture<Void> pressFKey(int fKeyNumber) {
        if (fKeyNumber < 1 || fKeyNumber > 12) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("F-key number must be 1-12"));
        }

        int keyCode = KeyEvent.VK_F1 + (fKeyNumber - 1);

        CompletableFuture<Void> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                // Calculate reaction delay with learning speedup
                long baseReaction = randomization.uniformRandomLong(
                        MIN_HOTKEY_REACTION_MS, MAX_HOTKEY_REACTION_MS);

                int usageCount = fKeyUsageCount.getOrDefault(fKeyNumber, 0);
                if (usageCount > 0) {
                    // Apply speedup for subsequent uses
                    double speedup = randomization.uniformRandom(MIN_FKEY_SPEEDUP, MAX_FKEY_SPEEDUP);
                    baseReaction = Math.round(baseReaction * speedup);
                    log.debug("F{} speedup applied (usage #{}): {}ms", fKeyNumber, usageCount + 1, baseReaction);
                }

                Thread.sleep(baseReaction);

                // Press the F-key via UInput
                long holdDuration = randomization.uniformRandomLong(MIN_KEY_HOLD_MS, MAX_KEY_HOLD_MS);
                keyboardDevice.tapKeyAwt(keyCode, holdDuration);

                // Update learning tracking
                fKeyUsageCount.put(fKeyNumber, usageCount + 1);
                fKeyLastUsedTime.put(fKeyNumber, System.currentTimeMillis());

                future.complete(null);
            } catch (Exception e) {
                log.error("F-key press failed", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Reset F-key learning for a new session.
     */
    public void resetFKeyLearning() {
        fKeyUsageCount.clear();
        fKeyLastUsedTime.clear();
        log.debug("F-key learning reset");
    }

    // ========================================================================
    // Special Key Methods
    // ========================================================================

    /**
     * Press Enter key.
     */
    public CompletableFuture<Void> pressEnter() {
        return pressKey(KeyEvent.VK_ENTER);
    }

    /**
     * Press Escape key.
     */
    public CompletableFuture<Void> pressEscape() {
        return pressKey(KeyEvent.VK_ESCAPE);
    }

    /**
     * Press Backspace key.
     */
    public CompletableFuture<Void> pressBackspace() {
        return pressKey(KeyEvent.VK_BACK_SPACE);
    }

    /**
     * Press Tab key.
     */
    public CompletableFuture<Void> pressTab() {
        return pressKey(KeyEvent.VK_TAB);
    }

    /**
     * Press Space key.
     */
    public CompletableFuture<Void> pressSpace() {
        return pressKey(KeyEvent.VK_SPACE);
    }

    /**
     * Press an arrow key.
     *
     * @param direction "up", "down", "left", or "right"
     */
    public CompletableFuture<Void> pressArrow(String direction) {
        int keyCode = switch (direction.toLowerCase()) {
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            default -> throw new IllegalArgumentException("Invalid direction: " + direction);
        };
        return pressKey(keyCode);
    }

    // ========================================================================
    // Raw Key Operations (for CameraController integration)
    // ========================================================================

    /**
     * Press a key down synchronously (blocking).
     * Used by CameraController for arrow key holds.
     * 
     * @param keyCode the AWT key code
     */
    public void pressKeySync(int keyCode) {
        keyboardDevice.pressKeyAwt(keyCode);
    }

    /**
     * Release a key synchronously (blocking).
     * Used by CameraController for arrow key releases.
     * 
     * @param keyCode the AWT key code
     */
    public void releaseKeySync(int keyCode) {
        keyboardDevice.releaseKeyAwt(keyCode);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Shutdown the controller and release resources.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close the UInput virtual device
        if (keyboardDevice != null) {
            keyboardDevice.close();
        }
    }
}

