package com.rocinante.navigation;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class UnifiedNavigationGraphDynamicTransitionTest {

    @Test
    public void addsDynamicTransitionEdgesWhenCandidatesPresent() {
        // Empty navigation web
        NavigationWeb web = NavigationWeb.parse("{\"version\":\"1\",\"nodes\":[],\"edges\":[],\"regions\":[]}");

        // Plane transition handler with a dynamic candidate at (3200,3200,0)
        PlaneTransitionHandler handler = Mockito.mock(PlaneTransitionHandler.class);
        PlaneTransitionHandler.DynamicTransitionCandidate candidate =
                new PlaneTransitionHandler.DynamicTransitionCandidate(1234, new WorldPoint(3200, 3200, 0), "Climb-up");
        Mockito.when(handler.getAllTransitions()).thenReturn(Collections.emptyList());
        Mockito.when(handler.getDynamicCandidates()).thenReturn(Collections.singletonList(candidate));

        UnifiedNavigationGraph graph = new UnifiedNavigationGraph(web, handler);

        // Expect edges for going up and down between plane 0 and 1
        String baseNodeId = "dyn_3200_3200_0";
        String upNodeId = "dyn_3200_3200_1";

        assertNotNull(graph.getNode(baseNodeId));
        assertNotNull(graph.getNode(upNodeId));

        NavigationEdge upEdge = graph.getEdge(baseNodeId, upNodeId);
        NavigationEdge downEdge = graph.getEdge(upNodeId, baseNodeId);

        assertNotNull(upEdge);
        assertNotNull(downEdge);
        assertEquals(WebEdgeType.STAIRS, upEdge.getType());
        assertEquals(WebEdgeType.STAIRS, downEdge.getType());
        assertEquals(5, upEdge.getCostTicks());
        assertEquals(5, downEdge.getCostTicks());
    }
}

