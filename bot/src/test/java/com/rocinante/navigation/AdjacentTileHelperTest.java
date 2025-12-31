package com.rocinante.navigation;

import net.runelite.api.GameObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

public class AdjacentTileHelperTest {

    @Test
    public void findsReachableAdjacentWithShortestPath() {
        PathFinder pathFinder = Mockito.mock(PathFinder.class);
        Reachability reachability = Mockito.mock(Reachability.class);
        GameObject targetObj = Mockito.mock(GameObject.class);
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint target = new WorldPoint(3202, 3202, 0);
        WorldPoint reachable = new WorldPoint(3201, 3202, 0);

        Mockito.when(pathFinder.findPath(eq(start), eq(reachable), eq(true)))
                .thenReturn(List.of(start, reachable));

        Mockito.when(pathFinder.findPath(eq(start), any(WorldPoint.class), eq(true)))
                .thenAnswer(invocation -> {
                    WorldPoint dest = invocation.getArgument(1);
                    if (dest.equals(reachable)) {
                        return List.of(start, reachable);
                    }
                    return Collections.emptyList();
                });

        Mockito.when(reachability.getObjectFootprint(targetObj)).thenReturn(Set.of(target));
        Mockito.when(reachability.canInteract(eq(reachable), eq(targetObj))).thenReturn(true);
        Mockito.when(targetObj.getWorldLocation()).thenReturn(target);

        Optional<AdjacentTileHelper.AdjacentPath> adjacent =
                AdjacentTileHelper.findReachableAdjacent(pathFinder, reachability, start, targetObj);

        assertTrue("Expected reachable adjacent tile", adjacent.isPresent());
        assertEquals(reachable, adjacent.get().getDestination());
        assertEquals(List.of(start, reachable), adjacent.get().getPath());
    }

    @Test
    public void returnsEmptyWhenNoAdjacentReachable() {
        PathFinder pathFinder = Mockito.mock(PathFinder.class);
        Reachability reachability = Mockito.mock(Reachability.class);
        GameObject targetObj = Mockito.mock(GameObject.class);
        WorldPoint start = new WorldPoint(3200, 3200, 0);

        Mockito.when(pathFinder.findPath(eq(start), any(WorldPoint.class), eq(true)))
                .thenReturn(Collections.emptyList());

        Mockito.when(reachability.getObjectFootprint(targetObj)).thenReturn(Set.of());
        Mockito.when(targetObj.getWorldLocation()).thenReturn(start);

        Optional<AdjacentTileHelper.AdjacentPath> adjacent =
                AdjacentTileHelper.findReachableAdjacent(pathFinder, reachability, start, targetObj);

        assertTrue("Expected no reachable adjacent tile", adjacent.isEmpty());
    }
}

