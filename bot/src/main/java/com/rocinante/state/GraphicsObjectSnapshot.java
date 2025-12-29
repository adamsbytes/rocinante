package com.rocinante.state;

import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.SpotanimID;

import java.util.Set;

/**
 * Immutable snapshot of a graphics object's state at a point in time.
 *
 * Per REQUIREMENTS.md Section 6.2.4, graphics objects are tracked for prayer flicking.
 * Graphics objects are visual effects (spell impacts, AOE indicators, etc.).
 */
@Value
@Builder
public class GraphicsObjectSnapshot {

    /**
     * Known AOE (area of effect) graphics IDs that indicate dangerous ground tiles.
     * These are graphics objects placed on the ground that players should avoid.
     * 
     * Uses RuneLite's SpotanimID constants for maintainability.
     */
    private static final Set<Integer> KNOWN_AOE_GRAPHICS = Set.of(
            // Chambers of Xeric (CoX) - Olm
            SpotanimID.OLM_CRYSTAL_BOMB_EXPLODE,
            SpotanimID.OLM_CRYSTAL_BOMB_TRAVEL,
            SpotanimID.OLM_ACID_SPIT,
            SpotanimID.OLM_CRYSTALROCK_FALLING,
            SpotanimID.OLM_CRYSTAL_EXPLODE,
            SpotanimID.OLM_FIREWALL_TRAVEL,
            SpotanimID.OLM_FIREWALL_TRAVEL_2,
            SpotanimID.OLM_BURNWITHME_TRAVEL,
            SpotanimID.OLM_BURNWITHME_TRAVEL_PL,
            SpotanimID.OLM_BURNWITHME_PL_SPOT,
            SpotanimID.OLM_SHOCKWAVE,
            
            // Theatre of Blood (ToB) - Xarpus acid pools
            SpotanimID.TOB_XARPUS_ACIDPOOL_END_0,
            SpotanimID.TOB_XARPUS_ACIDPOOL_END_1,
            SpotanimID.TOB_XARPUS_ACIDPOOL_END_2,
            SpotanimID.TOB_XARPUS_ACIDPOOL_END_3,
            SpotanimID.TOB_XARPUS_ACIDSPIT,
            SpotanimID.TOB_XARPUS_ACIDSPLASH,
            
            // Theatre of Blood (ToB) - Verzik
            SpotanimID.VERZIK_ACIDBOMB_PROJANIM,
            SpotanimID.VERZIK_ACIDBOMB_SMALL_IMPACT,
            SpotanimID.VERZIK_ACIDBOMB_IMPACT,
            
            // Vorkath
            SpotanimID.VORKATH_ACID_TRAVEL,
            SpotanimID.VORKATH_AREA_TRAVEL,
            SpotanimID.VORKATH_AREA_SMALL_TRAVEL,
            SpotanimID.VORKATH_AREA_TRAVEL_SMALL,
            
            // Alchemical Hydra pools
            SpotanimID.HYDRABOSS_POOLS_PROJ,
            SpotanimID.HYDRABOSS_POOLS_SPLASH,
            SpotanimID.HYDRABOSS_POOLS_LANDING_0,
            SpotanimID.HYDRABOSS_POOLS_LANDING_45,
            SpotanimID.HYDRABOSS_POOLS_LANDING_90,
            SpotanimID.HYDRABOSS_POOLS_LANDING_135,
            SpotanimID.HYDRABOSS_POOLS_LANDING_180,
            SpotanimID.HYDRABOSS_POOLS_LANDING_225,
            SpotanimID.HYDRABOSS_POOLS_LANDING_270,
            SpotanimID.HYDRABOSS_POOLS_LANDING_315,
            SpotanimID.HYDRABOSS_POOLS_LANDED_0,
            SpotanimID.HYDRABOSS_POOLS_LANDED_45,
            SpotanimID.HYDRABOSS_POOLS_LANDED_90,
            SpotanimID.HYDRABOSS_POOLS_LANDED_135,
            SpotanimID.HYDRABOSS_POOLS_LANDED_180,
            SpotanimID.HYDRABOSS_POOLS_LANDED_225,
            SpotanimID.HYDRABOSS_POOLS_LANDED_270,
            SpotanimID.HYDRABOSS_POOLS_LANDED_315,
            
            // Lizardman Shaman acid
            SpotanimID.LIZARDSHAMAN_SPIT_ACID,
            SpotanimID.LIZARDSHAMAN_ACID_SPLASH,
            
            // Wintertodt area attack
            SpotanimID.WINT_AREA_ATTACK_SPOT,
            
            // Desert Treasure 2 - Leviathan
            SpotanimID.DT2_LEVIATHAN_BOMB01,
            
            // Tombs of Amascut - Wardens
            SpotanimID.FX_WARDENS_BOMB01,
            SpotanimID.FX_WARDENS_BOMB02,
            SpotanimID.FX_WARDENS_BOMB03,
            
            // Araxxor acid
            SpotanimID.ARAXYTE_ACID_SPIDER_EXPLOSION,
            
            // Fire giant boss AOE
            SpotanimID.VFX_FIRE_GIANT_BRANDA_QUEEN_ATTACK_AOE_PROJ01
    );

    /**
     * The graphics object's ID (determines visual appearance).
     */
    int id;

    /**
     * The local position where this graphics object is displayed.
     */
    LocalPoint localPosition;

    /**
     * The world position (converted from local).
     */
    WorldPoint worldPosition;

    /**
     * The animation frame currently being displayed.
     */
    int frame;

    /**
     * The game cycle when this graphics object was created.
     */
    int startCycle;

    /**
     * The height offset for display.
     */
    int height;

    /**
     * Whether this graphics object is finished playing.
     */
    boolean finished;

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Calculate distance from this graphics object to a world point.
     *
     * @param target the target point
     * @return distance in tiles, or -1 if position invalid
     */
    public int distanceTo(WorldPoint target) {
        if (worldPosition == null || target == null) {
            return -1;
        }
        return worldPosition.distanceTo(target);
    }

    /**
     * Check if this graphics object is within a certain distance of a point.
     *
     * @param target   the target point
     * @param distance maximum distance in tiles
     * @return true if within distance
     */
    public boolean isWithinDistance(WorldPoint target, int distance) {
        int dist = distanceTo(target);
        return dist >= 0 && dist <= distance;
    }

    /**
     * Check if this graphics object is a known AOE (area of effect) indicator.
     * 
     * AOE indicators are dangerous ground effects that players should avoid,
     * such as acid pools, fire tiles, bomb explosions, etc.
     *
     * @return true if this is a known AOE indicator graphics object
     */
    public boolean isAoeIndicator() {
        return KNOWN_AOE_GRAPHICS.contains(id);
    }

    /**
     * Get a summary string for logging.
     *
     * @return summary of this graphics object
     */
    public String getSummary() {
        return String.format("GraphicsObject[id=%d at %s, frame=%d, finished=%s]",
                id,
                worldPosition,
                frame,
                finished);
    }
}

