package com.rocinante.progression;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * A specific location where a training method can be performed.
 *
 * <p>Each training method can have multiple locations with different
 * efficiency rates and requirements. The UI allows users to select
 * which location to use.
 *
 * <p>The {@code trainingArea} field specifies the center of the training area.
 * The bot will travel to this location before starting training if the player
 * is more than 15 tiles away.
 *
 * <p>Examples:
 * <ul>
 *   <li>Willows at Draynor (80 actions/hr) vs Prifddinas (95 actions/hr, requires SotE)</li>
 *   <li>Cake stall in Ardougne safespot vs Hosidius</li>
 *   <li>Iron ore at Al Kharid vs Mining Guild (with invisible +7 boost)</li>
 * </ul>
 */
@Value
@Builder
public class MethodLocation {

    /**
     * Unique identifier for this location within the method.
     * Used for selection and configuration (e.g., "draynor", "ardy_safespot").
     */
    String id;

    /**
     * Human-readable name displayed in UI.
     * Should include area and any notable features (e.g., "Ardougne (safespot)").
     */
    String name;

    /**
     * Center/optimal spot for training at this location.
     * The bot will travel here before starting training if not nearby.
     * Required for all locations.
     */
    WorldPoint trainingArea;

    /**
     * Exact position to stand at for optimal training.
     * May be null if general area is sufficient.
     * When set, used instead of trainingArea for positioning.
     * 
     * @deprecated Prefer using {@link #trainingArea} for location enforcement.
     *             This field is kept for backwards compatibility with methods
     *             that require precise positioning (e.g., agility courses).
     */
    @Deprecated
    WorldPoint exactPosition;

    /**
     * Actions per hour achievable at this location.
     * Combined with method's xpPerAction to calculate XP/hr.
     * Varies by location due to spawn density, competition, travel time, etc.
     */
    int actionsPerHour;

    /**
     * Location-specific requirements.
     * These are in addition to any method-level requirements.
     * May be null if no extra requirements.
     */
    @Builder.Default
    MethodRequirements requirements = MethodRequirements.none();

    /**
     * Optional notes or tips for this location.
     * Displayed in UI to help user choose.
     */
    String notes;

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Calculate XP per hour at this location.
     *
     * @param xpPerAction the method's XP per action
     * @return estimated XP per hour
     */
    public double getXpPerHour(double xpPerAction) {
        return xpPerAction * actionsPerHour;
    }

    /**
     * Calculate XP per hour for level-based XP methods.
     *
     * @param level the player's current level
     * @param xpMultiplier the method's XP multiplier
     * @return estimated XP per hour
     */
    public double getXpPerHour(int level, double xpMultiplier) {
        return (level * xpMultiplier) * actionsPerHour;
    }

    /**
     * Check if this location has additional requirements beyond the method.
     *
     * @return true if location has its own requirements
     */
    public boolean hasRequirements() {
        return requirements != null && requirements.hasRequirements();
    }

    /**
     * Check if this location requires membership.
     *
     * @return true if members-only
     */
    public boolean requiresMembership() {
        return requirements != null && requirements.isMembers();
    }

    /**
     * Get a display string for UI selection.
     *
     * @param xpPerAction the method's XP per action
     * @return formatted string like "Draynor Village - 5,400 xp/hr"
     */
    public String getDisplayString(double xpPerAction) {
        return String.format("%s - %,.0f xp/hr", name, getXpPerHour(xpPerAction));
    }

    /**
     * Get a display string for level-based XP methods.
     *
     * @param level the player's current level
     * @param xpMultiplier the method's XP multiplier
     * @return formatted string like "Draynor Village - 5,400 xp/hr @ lvl 50"
     */
    public String getDisplayString(int level, double xpMultiplier) {
        return String.format("%s - %,.0f xp/hr @ lvl %d", 
                name, getXpPerHour(level, xpMultiplier), level);
    }

    /**
     * Get summary including requirements.
     *
     * @return human-readable summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        sb.append(" (").append(actionsPerHour).append(" actions/hr)");
        
        if (hasRequirements()) {
            sb.append(" [").append(requirements.getSummary()).append("]");
        }
        
        if (notes != null && !notes.isEmpty()) {
            sb.append(" - ").append(notes);
        }
        
        return sb.toString();
    }
}

