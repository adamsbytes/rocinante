package com.rocinante.tasks.impl;

import com.rocinante.tasks.AbstractTask;
import com.rocinante.tasks.TaskContext;
import com.rocinante.timing.DelayProfile;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Reusable task for performing emotes.
 *
 * <p>This task can be used for:
 * <ul>
 *   <li>Quest requirements (e.g., emote steps in various quests)</li>
 *   <li>Treasure Trails / Clue Scrolls (emote clues)</li>
 *   <li>Random events that require emotes</li>
 *   <li>General emote interactions</li>
 * </ul>
 *
 * <p>The task handles:
 * <ul>
 *   <li>Opening the emote tab if not already open</li>
 *   <li>Scrolling to find the emote if needed</li>
 *   <li>Clicking the emote with humanized timing</li>
 *   <li>Waiting for the emote animation to complete</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Perform a bow emote
 * EmoteTask bowTask = new EmoteTask(Emote.BOW);
 *
 * // Perform wave emote with waiting for animation
 * EmoteTask waveTask = new EmoteTask(Emote.WAVE)
 *     .withWaitForAnimation(true);
 *
 * // Perform emote by name (for dynamic resolution)
 * EmoteTask emoteTask = EmoteTask.byName("Dance");
 * }</pre>
 */
@Slf4j
public class EmoteTask extends AbstractTask {

    // ========================================================================
    // Widget IDs for Emote Tab
    // ========================================================================

    /**
     * Emote tab widget group ID.
     * This is the main emote interface (216 = 0x00d8).
     */
    private static final int EMOTE_GROUP_ID = 216;

    /**
     * Emote contents (scrollable container holding all emote icons).
     */
    private static final int EMOTE_CONTENTS_CHILD = 2;

    /**
     * Emote scrollbar for scrolling through emotes.
     */
    private static final int EMOTE_SCROLLBAR_CHILD = 4;

    /**
     * Tab index for the emote tab.
     */
    private static final int TAB_EMOTES = 12;

    // ========================================================================
    // Emote Definitions
    // ========================================================================

    /**
     * Enum of all available emotes with their sprite IDs.
     * Sprite IDs are used to locate emotes in the emote tab.
     */
    @Getter
    public enum Emote {
        YES("Yes", 0),
        NO("No", 1),
        BOW("Bow", 2),
        ANGRY("Angry", 3),
        THINK("Think", 4),
        WAVE("Wave", 5),
        SHRUG("Shrug", 6),
        CHEER("Cheer", 7),
        BECKON("Beckon", 8),
        LAUGH("Laugh", 9),
        JUMP_FOR_JOY("Jump for Joy", 10),
        YAWN("Yawn", 11),
        DANCE("Dance", 12),
        JIG("Jig", 13),
        SPIN("Spin", 14),
        HEADBANG("Headbang", 15),
        CRY("Cry", 16),
        BLOW_KISS("Blow Kiss", 17),
        PANIC("Panic", 18),
        RASPBERRY("Raspberry", 19),
        CLAP("Clap", 20),
        SALUTE("Salute", 21),
        GOBLIN_BOW("Goblin Bow", 22),
        GOBLIN_SALUTE("Goblin Salute", 23),
        GLASS_BOX("Glass Box", 24),
        CLIMB_ROPE("Climb Rope", 25),
        LEAN("Lean", 26),
        GLASS_WALL("Glass Wall", 27),
        IDEA("Idea", 28),
        STAMP("Stamp", 29),
        FLAP("Flap", 30),
        SLAP_HEAD("Slap Head", 31),
        ZOMBIE_WALK("Zombie Walk", 32),
        ZOMBIE_DANCE("Zombie Dance", 33),
        SCARED("Scared", 34),
        RABBIT_HOP("Rabbit Hop", 35),
        SIT_UP("Sit Up", 36),
        PUSH_UP("Push Up", 37),
        STAR_JUMP("Star Jump", 38),
        JOG("Jog", 39),
        FLEX("Flex", 40),
        SKILL_CAPE("Skill Cape", 41),
        AIR_GUITAR("Air Guitar", 42),
        URI_TRANSFORM("Uri Transform", 43),
        SMOOTH_DANCE("Smooth Dance", 44),
        CRAZY_DANCE("Crazy Dance", 45),
        PREMIER_SHIELD("Premier Shield", 46),
        EXPLORE("Explore", 47),
        RELIC_UNLOCK("Relic Unlock", 48),
        PARTY("Party", 49);

        private final String name;
        private final int index;

        Emote(String name, int index) {
            this.name = name;
            this.index = index;
        }

        /**
         * Get emote by name (case-insensitive).
         */
        public static Emote fromName(String name) {
            if (name == null) return null;
            String normalizedName = name.toLowerCase().replace("_", " ").replace("-", " ");
            for (Emote emote : values()) {
                if (emote.name.toLowerCase().equals(normalizedName) ||
                    emote.name().toLowerCase().replace("_", " ").equals(normalizedName)) {
                    return emote;
                }
            }
            return null;
        }

        /**
         * Get emote by sprite ID (for Quest Helper integration).
         */
        public static Emote fromSpriteId(int spriteId) {
            // Sprite IDs from Quest Helper's QuestEmote
            return SPRITE_ID_MAP.get(spriteId);
        }
    }

    /**
     * Map of sprite IDs to emotes (for Quest Helper integration).
     */
    private static final Map<Integer, Emote> SPRITE_ID_MAP = new HashMap<>();

    static {
        // These sprite IDs are from SpriteID.Emotes in RuneLite
        // They may need adjustment based on actual values
        SPRITE_ID_MAP.put(0, Emote.YES);
        SPRITE_ID_MAP.put(1, Emote.NO);
        SPRITE_ID_MAP.put(2, Emote.BOW);
        SPRITE_ID_MAP.put(3, Emote.ANGRY);
        SPRITE_ID_MAP.put(4, Emote.THINK);
        SPRITE_ID_MAP.put(5, Emote.WAVE);
        SPRITE_ID_MAP.put(6, Emote.SHRUG);
        SPRITE_ID_MAP.put(7, Emote.CHEER);
        SPRITE_ID_MAP.put(8, Emote.BECKON);
        SPRITE_ID_MAP.put(9, Emote.LAUGH);
        SPRITE_ID_MAP.put(10, Emote.JUMP_FOR_JOY);
        SPRITE_ID_MAP.put(11, Emote.YAWN);
        SPRITE_ID_MAP.put(12, Emote.DANCE);
        SPRITE_ID_MAP.put(13, Emote.JIG);
        SPRITE_ID_MAP.put(14, Emote.SPIN);
        SPRITE_ID_MAP.put(15, Emote.HEADBANG);
        SPRITE_ID_MAP.put(16, Emote.CRY);
        SPRITE_ID_MAP.put(17, Emote.BLOW_KISS);
        SPRITE_ID_MAP.put(18, Emote.PANIC);
        SPRITE_ID_MAP.put(19, Emote.RASPBERRY);
        SPRITE_ID_MAP.put(20, Emote.CLAP);
        SPRITE_ID_MAP.put(21, Emote.SALUTE);
        SPRITE_ID_MAP.put(22, Emote.GOBLIN_BOW);
        SPRITE_ID_MAP.put(23, Emote.GOBLIN_SALUTE);
        // Add more mappings as needed for unlockable emotes
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * The emote to perform.
     */
    @Getter
    private final Emote emote;

    /**
     * Emote name (for dynamic resolution when emote enum isn't known).
     */
    @Getter
    private final String emoteName;

    /**
     * Sprite ID (for Quest Helper integration).
     */
    @Getter
    @Setter
    private int spriteId = -1;

    /**
     * Whether to wait for the emote animation to complete.
     */
    @Getter
    @Setter
    private boolean waitForAnimation = true;

    /**
     * Custom description.
     */
    @Setter
    private String description;

    // ========================================================================
    // Execution State
    // ========================================================================

    private EmotePhase phase = EmotePhase.OPEN_TAB;
    private boolean clickPending = false;
    private int waitTicks = 0;
    private Widget targetEmoteWidget = null;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Create an emote task with a known emote.
     *
     * @param emote the emote to perform
     */
    public EmoteTask(Emote emote) {
        this.emote = emote;
        this.emoteName = emote != null ? emote.getName() : null;
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create an emote task by name (for dynamic resolution).
     *
     * @param emoteName the name of the emote
     */
    public EmoteTask(String emoteName) {
        this.emoteName = emoteName;
        this.emote = Emote.fromName(emoteName);
        this.timeout = Duration.ofSeconds(30);
    }

    /**
     * Create an emote task by sprite ID (for Quest Helper integration).
     *
     * @param spriteId the sprite ID of the emote
     */
    public static EmoteTask fromSpriteId(int spriteId) {
        Emote emote = Emote.fromSpriteId(spriteId);
        if (emote != null) {
            return new EmoteTask(emote);
        }
        // Create with sprite ID for fallback matching
        EmoteTask task = new EmoteTask((Emote) null);
        task.spriteId = spriteId;
        return task;
    }

    /**
     * Factory method to create by name.
     *
     * @param name the emote name
     * @return new EmoteTask
     */
    public static EmoteTask byName(String name) {
        return new EmoteTask(name);
    }

    // ========================================================================
    // Builder Methods
    // ========================================================================

    /**
     * Set whether to wait for animation (builder-style).
     *
     * @param wait true to wait for animation to complete
     * @return this task for chaining
     */
    public EmoteTask withWaitForAnimation(boolean wait) {
        this.waitForAnimation = wait;
        return this;
    }

    /**
     * Set custom description (builder-style).
     *
     * @param desc the description
     * @return this task for chaining
     */
    public EmoteTask withDescription(String desc) {
        this.description = desc;
        return this;
    }

    /**
     * Set sprite ID for Quest Helper integration (builder-style).
     *
     * @param spriteId the sprite ID
     * @return this task for chaining
     */
    public EmoteTask withSpriteId(int spriteId) {
        this.spriteId = spriteId;
        return this;
    }

    // ========================================================================
    // Task Implementation
    // ========================================================================

    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }

    @Override
    protected void executeImpl(TaskContext ctx) {
        if (clickPending) {
            return;
        }

        switch (phase) {
            case OPEN_TAB:
                executeOpenTab(ctx);
                break;
            case FIND_EMOTE:
                executeFindEmote(ctx);
                break;
            case SCROLL_TO_EMOTE:
                executeScrollToEmote(ctx);
                break;
            case CLICK_EMOTE:
                executeClickEmote(ctx);
                break;
            case WAIT_ANIMATION:
                executeWaitAnimation(ctx);
                break;
        }
    }

    // ========================================================================
    // Phase: Open Emote Tab
    // ========================================================================

    private void executeOpenTab(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if emote tab is already open
        Widget emoteContainer = client.getWidget(EMOTE_GROUP_ID, EMOTE_CONTENTS_CHILD);
        if (emoteContainer != null && !emoteContainer.isHidden()) {
            log.debug("Emote tab already open");
            phase = EmotePhase.FIND_EMOTE;
            return;
        }

        // Open emote tab using F12 or clicking
        log.debug("Opening emote tab");
        clickPending = true;

        // Use keyboard shortcut (F12 for emotes)
        ctx.getKeyboardController().pressKey(java.awt.event.KeyEvent.VK_F12)
                .thenRun(() -> {
                    clickPending = false;
                    waitTicks = 0;
                    phase = EmotePhase.FIND_EMOTE;
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to open emote tab", e);
                    fail("Failed to open emote tab: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Find Emote
    // ========================================================================

    private void executeFindEmote(TaskContext ctx) {
        Client client = ctx.getClient();

        Widget emoteContainer = client.getWidget(EMOTE_GROUP_ID, EMOTE_CONTENTS_CHILD);
        if (emoteContainer == null || emoteContainer.isHidden()) {
            // Tab not open yet, wait
            waitTicks++;
            if (waitTicks > 10) {
                // Try opening again
                phase = EmotePhase.OPEN_TAB;
                waitTicks = 0;
            }
            return;
        }

        Widget[] children = emoteContainer.getDynamicChildren();
        if (children == null || children.length == 0) {
            waitTicks++;
            if (waitTicks > 5) {
                log.warn("No emote widgets found in container");
                fail("Emote container is empty");
            }
            return;
        }

        // Find the target emote widget
        targetEmoteWidget = findEmoteWidget(children);

        if (targetEmoteWidget == null) {
            log.warn("Could not find emote: {} (spriteId={})", emoteName, spriteId);
            fail("Emote not found: " + (emoteName != null ? emoteName : "spriteId=" + spriteId));
            return;
        }

        log.debug("Found emote widget: {}", emoteName);

        // Check if emote is visible in the scroll area
        if (isEmoteVisible(client, targetEmoteWidget)) {
            phase = EmotePhase.CLICK_EMOTE;
        } else {
            phase = EmotePhase.SCROLL_TO_EMOTE;
        }
    }

    /**
     * Find the emote widget by matching name, index, or sprite ID.
     */
    private Widget findEmoteWidget(Widget[] children) {
        // Try matching by emote index first (most reliable)
        if (emote != null && emote.getIndex() >= 0 && emote.getIndex() < children.length) {
            Widget indexed = children[emote.getIndex()];
            if (indexed != null) {
                return indexed;
            }
        }

        // Try matching by sprite ID
        if (spriteId > 0) {
            for (Widget child : children) {
                if (child != null && child.getSpriteId() == spriteId) {
                    return child;
                }
            }
        }

        // Try matching by name (tooltip or action)
        if (emoteName != null) {
            String normalizedName = emoteName.toLowerCase();
            for (Widget child : children) {
                if (child == null) continue;

                // Check tooltip
                String tooltip = child.getName();
                if (tooltip != null && tooltip.toLowerCase().contains(normalizedName)) {
                    return child;
                }

                // Check actions
                String[] actions = child.getActions();
                if (actions != null) {
                    for (String action : actions) {
                        if (action != null && action.toLowerCase().contains(normalizedName)) {
                            return child;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Check if the emote widget is visible in the scroll area.
     */
    private boolean isEmoteVisible(Client client, Widget emoteWidget) {
        Widget emoteWindow = client.getWidget(EMOTE_GROUP_ID, 1); // Scrollable parent
        if (emoteWindow == null) {
            return true; // Assume visible if can't check
        }

        Rectangle windowBounds = emoteWindow.getBounds();
        Rectangle emoteBounds = emoteWidget.getBounds();

        if (windowBounds == null || emoteBounds == null) {
            return true;
        }

        // Check if emote is within visible scroll area
        return emoteBounds.y >= windowBounds.y &&
               emoteBounds.y + emoteBounds.height <= windowBounds.y + windowBounds.height;
    }

    // ========================================================================
    // Phase: Scroll to Emote
    // ========================================================================

    private void executeScrollToEmote(TaskContext ctx) {
        Client client = ctx.getClient();

        // Calculate scroll amount needed
        Widget emoteContainer = client.getWidget(EMOTE_GROUP_ID, EMOTE_CONTENTS_CHILD);
        if (emoteContainer == null || targetEmoteWidget == null) {
            phase = EmotePhase.FIND_EMOTE;
            return;
        }

        // Use RuneLite's scroll script to scroll to the widget
        int targetY = targetEmoteWidget.getRelativeY();
        int containerHeight = emoteContainer.getHeight();
        int targetScroll = Math.max(0, targetY - containerHeight / 2 + targetEmoteWidget.getHeight() / 2);

        log.debug("Scrolling to emote at Y={}, scroll={}", targetY, targetScroll);

        // Set scroll position
        try {
            // Use client script to update scrollbar
            client.runScript(
                    72, // UPDATE_SCROLLBAR script ID
                    EMOTE_GROUP_ID << 16 | EMOTE_SCROLLBAR_CHILD,
                    EMOTE_GROUP_ID << 16 | EMOTE_CONTENTS_CHILD,
                    targetScroll
            );
        } catch (Exception e) {
            log.debug("Could not use scroll script, proceeding anyway", e);
        }

        // Give a tick for scroll to apply, then click
        waitTicks++;
        if (waitTicks > 2) {
            waitTicks = 0;
            phase = EmotePhase.CLICK_EMOTE;
        }
    }

    // ========================================================================
    // Phase: Click Emote
    // ========================================================================

    private void executeClickEmote(TaskContext ctx) {
        if (targetEmoteWidget == null || targetEmoteWidget.isHidden()) {
            phase = EmotePhase.FIND_EMOTE;
            return;
        }

        Rectangle bounds = targetEmoteWidget.getBounds();
        if (bounds == null || bounds.width == 0 || bounds.height == 0) {
            log.warn("Emote widget has invalid bounds");
            fail("Emote widget not clickable");
            return;
        }

        // Calculate humanized click point
        Point clickPoint = calculateClickPoint(bounds);
        log.debug("Clicking emote '{}' at ({}, {})", emoteName, clickPoint.x, clickPoint.y);

        clickPending = true;

        // Add small hover delay before click
        long hoverDelay = ctx.getHumanTimer().getDelay(DelayProfile.REACTION);

        ctx.getHumanTimer().sleep(hoverDelay)
                .thenCompose(v -> ctx.getMouseController().moveToCanvas(clickPoint.x, clickPoint.y))
                .thenCompose(v -> ctx.getMouseController().click())
                .thenRun(() -> {
                    clickPending = false;
                    waitTicks = 0;
                    if (waitForAnimation) {
                        phase = EmotePhase.WAIT_ANIMATION;
                    } else {
                        log.info("Emote '{}' performed", emoteName);
                        complete();
                    }
                })
                .exceptionally(e -> {
                    clickPending = false;
                    log.error("Failed to click emote", e);
                    fail("Failed to click emote: " + e.getMessage());
                    return null;
                });
    }

    // ========================================================================
    // Phase: Wait for Animation
    // ========================================================================

    private void executeWaitAnimation(TaskContext ctx) {
        Client client = ctx.getClient();

        // Check if player is animating
        int animationId = client.getLocalPlayer().getAnimation();
        if (animationId != -1) {
            // Player is animating - wait for it to finish
            waitTicks = 0;
            return;
        }

        // Animation finished or wasn't detected
        waitTicks++;
        if (waitTicks > 5) {
            // Give enough time for animation, then complete
            log.info("Emote '{}' completed", emoteName);
            complete();
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Point calculateClickPoint(Rectangle bounds) {
        // Gaussian-distributed click within bounds
        double offsetX = (Math.random() - 0.5) * bounds.width * 0.6;
        double offsetY = (Math.random() - 0.5) * bounds.height * 0.6;

        int x = bounds.x + bounds.width / 2 + (int) offsetX;
        int y = bounds.y + bounds.height / 2 + (int) offsetY;

        return new Point(x, y);
    }

    // ========================================================================
    // Description
    // ========================================================================

    @Override
    public String getDescription() {
        if (description != null) {
            return description;
        }
        if (emoteName != null) {
            return "EmoteTask[" + emoteName + "]";
        }
        if (spriteId > 0) {
            return "EmoteTask[spriteId=" + spriteId + "]";
        }
        return "EmoteTask[unknown]";
    }

    // ========================================================================
    // Phase Enum
    // ========================================================================

    private enum EmotePhase {
        OPEN_TAB,
        FIND_EMOTE,
        SCROLL_TO_EMOTE,
        CLICK_EMOTE,
        WAIT_ANIMATION
    }
}

