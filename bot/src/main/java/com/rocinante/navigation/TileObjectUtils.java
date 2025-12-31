package com.rocinante.navigation;

import lombok.experimental.UtilityClass;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;

/**
 * Shared helpers for working with RuneLite TileObject types.
 */
@UtilityClass
public class TileObjectUtils {

    /**
     * Get the world location for any TileObject variant.
     *
     * @param target tile object
     * @return world point, or null if unavailable
     */
    public static WorldPoint getWorldPoint(TileObject target) {
        if (target instanceof GameObject) {
            return ((GameObject) target).getWorldLocation();
        }
        if (target instanceof WallObject) {
            return ((WallObject) target).getWorldLocation();
        }
        if (target instanceof GroundObject) {
            return ((GroundObject) target).getWorldLocation();
        }
        if (target instanceof DecorativeObject) {
            return ((DecorativeObject) target).getWorldLocation();
        }
        return null;
    }
}

