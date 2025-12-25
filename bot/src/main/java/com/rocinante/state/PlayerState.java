package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of player state as specified in REQUIREMENTS.md Section 6.2.1.
 *
 * This class represents a point-in-time snapshot of the local player's state,
 * including position, animation, health, combat status, and status effects.
 * Instances are created by {@link com.rocinante.core.GameStateService} on each game tick.
 *
 * All fields are immutable to ensure thread-safety and prevent accidental modification.
 */
@Value
@Builder(toBuilder = true)
public class PlayerState {

    /**
     * Null/empty state for when player data is unavailable (not logged in, etc.).
     */
    public static final PlayerState EMPTY = PlayerState.builder()
            .worldPosition(null)
            .localPosition(null)
            .animationId(-1)
            .isMoving(false)
            .isInteracting(false)
            .currentHitpoints(0)
            .maxHitpoints(0)
            .currentPrayer(0)
            .maxPrayer(0)
            .runEnergy(0)
            .inCombat(false)
            .targetNpcIndex(-1)
            .skullIcon(-1)
            .isPoisoned(false)
            .isVenomed(false)
            .spellbook(0)
            .build();

    // ========================================================================
    // Position
    // ========================================================================

    /**
     * Current tile position in world coordinates.
     * May be null if player is not loaded.
     */
    WorldPoint worldPosition;

    /**
     * Current position in local (scene) coordinates.
     * May be null if player is not loaded.
     */
    LocalPoint localPosition;

    // ========================================================================
    // Animation & Movement
    // ========================================================================

    /**
     * Current animation ID being played.
     * -1 if no animation is playing.
     */
    int animationId;

    /**
     * Whether the player is currently moving (walking/running).
     * Determined by comparing pose animation to idle pose animation.
     */
    boolean isMoving;

    /**
     * Whether the player is currently interacting with an entity.
     * True for combat, NPC dialogue, object interaction, etc.
     */
    boolean isInteracting;

    // ========================================================================
    // Health & Resources
    // ========================================================================

    /**
     * Current hitpoints (boosted level).
     */
    int currentHitpoints;

    /**
     * Maximum hitpoints (real level).
     */
    int maxHitpoints;

    /**
     * Current prayer points (boosted level).
     */
    int currentPrayer;

    /**
     * Maximum prayer points (real level).
     */
    int maxPrayer;

    /**
     * Current run energy (0-100).
     */
    int runEnergy;

    // ========================================================================
    // Combat State
    // ========================================================================

    /**
     * Whether the player is currently in combat.
     * True if targeting an NPC that is also targeting the player.
     */
    boolean inCombat;

    /**
     * Index of the NPC the player is targeting.
     * -1 if not targeting any NPC.
     */
    int targetNpcIndex;

    // ========================================================================
    // Status Effects
    // ========================================================================

    /**
     * Current skull icon ID.
     * -1 if not skulled, otherwise the skull icon ordinal.
     * Common values: 0 = white skull, 1 = red skull (high risk)
     */
    int skullIcon;

    /**
     * Whether the player is currently poisoned.
     * Determined from VarPlayer.POISON value.
     */
    boolean isPoisoned;

    /**
     * Whether the player is currently venomed.
     * Determined from VarPlayer.POISON value (negative values indicate venom).
     */
    boolean isVenomed;

    // ========================================================================
    // Spellbook
    // ========================================================================

    /**
     * Current spellbook ID.
     * 0 = Standard, 1 = Ancient, 2 = Lunar, 3 = Arceuus.
     */
    int spellbook;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if player state is valid (player is logged in and loaded).
     *
     * @return true if the player data is valid
     */
    public boolean isValid() {
        return worldPosition != null;
    }

    /**
     * Check if player is idle (not moving, not animating, not interacting).
     *
     * @return true if the player is idle
     */
    public boolean isIdle() {
        return !isMoving && animationId == -1 && !isInteracting;
    }

    /**
     * Check if player is animating (has an active animation).
     *
     * @return true if an animation is playing
     */
    public boolean isAnimating() {
        return animationId != -1;
    }

    /**
     * Check if player is animating with a specific animation ID.
     *
     * @param expectedAnimationId the animation ID to check for
     * @return true if the player is playing the specified animation
     */
    public boolean isAnimating(int expectedAnimationId) {
        return animationId == expectedAnimationId;
    }

    /**
     * Get health as a percentage (0.0 to 1.0).
     *
     * @return health percentage, or 0.0 if max is 0
     */
    public double getHealthPercent() {
        if (maxHitpoints == 0) {
            return 0.0;
        }
        return (double) currentHitpoints / maxHitpoints;
    }

    /**
     * Get prayer as a percentage (0.0 to 1.0).
     *
     * @return prayer percentage, or 0.0 if max is 0
     */
    public double getPrayerPercent() {
        if (maxPrayer == 0) {
            return 0.0;
        }
        return (double) currentPrayer / maxPrayer;
    }

    /**
     * Check if health is below a threshold percentage.
     *
     * @param percent the threshold (0.0 to 1.0)
     * @return true if current health is below the threshold
     */
    public boolean isHealthBelow(double percent) {
        return getHealthPercent() < percent;
    }

    /**
     * Check if prayer is below a threshold percentage.
     *
     * @param percent the threshold (0.0 to 1.0)
     * @return true if current prayer is below the threshold
     */
    public boolean isPrayerBelow(double percent) {
        return getPrayerPercent() < percent;
    }

    /**
     * Check if run energy is above a threshold.
     *
     * @param threshold the threshold (0-100)
     * @return true if run energy is above the threshold
     */
    public boolean isRunEnergyAbove(int threshold) {
        return runEnergy > threshold;
    }

    /**
     * Check if the player is skulled.
     *
     * @return true if the player has any skull icon
     */
    public boolean isSkulled() {
        return skullIcon >= 0;
    }

    /**
     * Check if the player has any poison or venom effect.
     *
     * @return true if poisoned or venomed
     */
    public boolean hasPoisonEffect() {
        return isPoisoned || isVenomed;
    }

    /**
     * Get the spellbook name.
     *
     * @return human-readable spellbook name
     */
    public String getSpellbookName() {
        switch (spellbook) {
            case 0:
                return "Standard";
            case 1:
                return "Ancient";
            case 2:
                return "Lunar";
            case 3:
                return "Arceuus";
            default:
                return "Unknown";
        }
    }

    /**
     * Check if player is at a specific tile.
     *
     * @param x world X coordinate
     * @param y world Y coordinate
     * @return true if player is at the specified tile
     */
    public boolean isAtTile(int x, int y) {
        return worldPosition != null
                && worldPosition.getX() == x
                && worldPosition.getY() == y;
    }

    /**
     * Check if player is at a specific world point.
     *
     * @param point the world point to check
     * @return true if player is at the specified point
     */
    public boolean isAtTile(WorldPoint point) {
        return worldPosition != null && worldPosition.equals(point);
    }

    /**
     * Check if player is within an area defined by corner coordinates.
     *
     * @param minX minimum X coordinate
     * @param minY minimum Y coordinate
     * @param maxX maximum X coordinate
     * @param maxY maximum Y coordinate
     * @return true if player is within the area
     */
    public boolean isInArea(int minX, int minY, int maxX, int maxY) {
        if (worldPosition == null) {
            return false;
        }
        int x = worldPosition.getX();
        int y = worldPosition.getY();
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    /**
     * Calculate distance to a world point.
     *
     * @param target the target point
     * @return distance in tiles, or -1 if position is invalid
     */
    public int distanceTo(WorldPoint target) {
        if (worldPosition == null || target == null) {
            return -1;
        }
        return worldPosition.distanceTo(target);
    }
}

