package com.rocinante.agility;

import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AgilityCourseRepository}.
 */
public class AgilityCourseRepositoryTest {

    private AgilityCourseRepository repository;

    @Before
    public void setUp() {
        repository = new AgilityCourseRepository();
    }

    @Test
    public void testLoadCourses() {
        // Verify at least the Draynor course was loaded
        assertTrue("Should have at least one course", repository.getCourseCount() > 0);
        assertTrue("Should have Draynor rooftop course", repository.hasCourse("draynor_rooftop"));
    }

    @Test
    public void testGetCourseById() {
        Optional<AgilityCourse> course = repository.getCourseById("draynor_rooftop");

        assertTrue("Should find Draynor rooftop course", course.isPresent());
        assertEquals("Draynor Village Rooftop", course.get().getName());
        assertEquals(10, course.get().getRequiredLevel());
        assertEquals(120.0, course.get().getXpPerLap(), 0.1);
        assertEquals(12338, course.get().getRegionId());
    }

    @Test
    public void testGetCourseByRegion() {
        Optional<AgilityCourse> course = repository.getCourseByRegion(12338);

        assertTrue("Should find course by region 12338", course.isPresent());
        assertEquals("draynor_rooftop", course.get().getId());
    }

    @Test
    public void testGetCourseByRegionNotFound() {
        Optional<AgilityCourse> course = repository.getCourseByRegion(99999);
        assertFalse("Should not find course for invalid region", course.isPresent());
    }

    @Test
    public void testGetCoursesForLevel() {
        // Level 5 - should get nothing (Draynor requires 10)
        List<AgilityCourse> level5 = repository.getCoursesForLevel(5);
        assertTrue("No courses for level 5", level5.isEmpty() ||
                level5.stream().noneMatch(c -> c.getId().equals("draynor_rooftop")));

        // Level 15 - should get Draynor
        List<AgilityCourse> level15 = repository.getCoursesForLevel(15);
        assertTrue("Draynor available at level 15",
                level15.stream().anyMatch(c -> c.getId().equals("draynor_rooftop")));

        // Level 50 - should not get Draynor (maxLevel is 30)
        List<AgilityCourse> level50 = repository.getCoursesForLevel(50);
        assertFalse("Draynor not recommended at level 50",
                level50.stream().anyMatch(c -> c.getId().equals("draynor_rooftop")));
    }

    @Test
    public void testDraynorCourseObstacles() {
        Optional<AgilityCourse> courseOpt = repository.getCourseById("draynor_rooftop");
        assertTrue("Should find Draynor course", courseOpt.isPresent());

        AgilityCourse course = courseOpt.get();
        List<AgilityObstacle> obstacles = course.getObstacles();

        assertEquals("Draynor should have 7 obstacles", 7, obstacles.size());

        // Verify first obstacle
        AgilityObstacle first = obstacles.get(0);
        assertEquals("Rough wall", first.getName());
        assertEquals(11404, first.getObjectId());
        assertEquals("Climb", first.getAction());
        assertEquals(0, first.getIndex());

        // Verify last obstacle
        AgilityObstacle last = obstacles.get(6);
        assertEquals("Crate", last.getName());
        assertEquals(11632, last.getObjectId());
        assertEquals("Climb-down", last.getAction());
        assertEquals(6, last.getIndex());
    }

    @Test
    public void testCourseNavigation() {
        Optional<AgilityCourse> courseOpt = repository.getCourseById("draynor_rooftop");
        assertTrue("Should find Draynor course", courseOpt.isPresent());

        AgilityCourse course = courseOpt.get();

        // At start area - should get first obstacle
        WorldPoint startPos = new WorldPoint(3103, 3277, 0);
        Optional<AgilityObstacle> nextFromStart = course.determineNextObstacle(startPos);
        assertTrue("Should determine next obstacle from start", nextFromStart.isPresent());
        assertEquals("First obstacle should be rough wall", "Rough wall", nextFromStart.get().getName());

        // At course end - should get first obstacle (new lap)
        WorldPoint endPos = new WorldPoint(3103, 3261, 0);
        Optional<AgilityObstacle> nextFromEnd = course.determineNextObstacle(endPos);
        assertTrue("Should determine next obstacle from end", nextFromEnd.isPresent());
        assertEquals("Should restart with rough wall", "Rough wall", nextFromEnd.get().getName());
    }

    @Test
    public void testMarkSpawnTiles() {
        Optional<AgilityCourse> courseOpt = repository.getCourseById("draynor_rooftop");
        assertTrue("Should find Draynor course", courseOpt.isPresent());

        AgilityCourse course = courseOpt.get();
        List<MarkSpawnTile> markTiles = course.getMarkSpawnTiles();

        assertFalse("Should have mark spawn tiles defined", markTiles.isEmpty());
        assertTrue("Should have multiple mark spawn locations", markTiles.size() >= 5);

        // Verify a known mark spawn tile
        assertTrue("Should have mark spawn at (3100, 3279, 3)",
                course.isMarkSpawnTile(new WorldPoint(3100, 3279, 3)));

        // Verify afterObstacle values
        MarkSpawnTile firstTile = markTiles.stream()
                .filter(t -> t.getPosition().equals(new WorldPoint(3100, 3279, 3)))
                .findFirst()
                .orElse(null);
        assertNotNull("Should find first mark spawn tile", firstTile);
        assertEquals("First mark spawn should be after obstacle 0", 0, firstTile.getAfterObstacle());
    }

    @Test
    public void testMarkReachability() {
        Optional<AgilityCourse> courseOpt = repository.getCourseById("draynor_rooftop");
        assertTrue("Should find Draynor course", courseOpt.isPresent());

        AgilityCourse course = courseOpt.get();

        // Mark on first rooftop (afterObstacle=0)
        WorldPoint markOnRoof1 = new WorldPoint(3100, 3279, 3);
        // Mark on second rooftop (afterObstacle=1)
        WorldPoint markOnRoof2 = new WorldPoint(3091, 3276, 3);

        // After completing no obstacles (-1), no marks should be reachable
        assertFalse("Mark on roof 1 should NOT be reachable before any obstacles",
                course.isMarkReachable(markOnRoof1, -1, 3));
        assertFalse("Mark on roof 2 should NOT be reachable before any obstacles",
                course.isMarkReachable(markOnRoof2, -1, 3));

        // After completing obstacle 0, mark on roof 1 should be reachable
        assertTrue("Mark on roof 1 SHOULD be reachable after obstacle 0",
                course.isMarkReachable(markOnRoof1, 0, 3));
        assertFalse("Mark on roof 2 should NOT be reachable after only obstacle 0",
                course.isMarkReachable(markOnRoof2, 0, 3));

        // After completing obstacle 1, marks on roof 1 and 2 should be reachable
        assertTrue("Mark on roof 1 SHOULD be reachable after obstacle 1",
                course.isMarkReachable(markOnRoof1, 1, 3));
        assertTrue("Mark on roof 2 SHOULD be reachable after obstacle 1",
                course.isMarkReachable(markOnRoof2, 1, 3));
    }

    @Test
    public void testGetReachableMarkSpawnTiles() {
        Optional<AgilityCourse> courseOpt = repository.getCourseById("draynor_rooftop");
        assertTrue("Should find Draynor course", courseOpt.isPresent());

        AgilityCourse course = courseOpt.get();

        // After completing 2 obstacles, should have 3 reachable tiles (0, 1, 2)
        List<MarkSpawnTile> reachable = course.getReachableMarkSpawnTiles(2);
        assertEquals("Should have 3 reachable tiles after 2 obstacles", 3, reachable.size());

        // All tiles should have afterObstacle <= 2
        for (MarkSpawnTile tile : reachable) {
            assertTrue("Tile afterObstacle should be <= 2",
                    tile.getAfterObstacle() <= 2);
        }
    }

    @Test
    public void testObstacleMatchingById() {
        Optional<AgilityCourse> courseOpt = repository.getCourseById("draynor_rooftop");
        assertTrue("Should find Draynor course", courseOpt.isPresent());

        AgilityObstacle roughWall = courseOpt.get().getFirstObstacle().orElse(null);
        assertNotNull("Should have first obstacle", roughWall);

        assertTrue("Should match primary object ID", roughWall.matchesObjectId(11404));
        assertFalse("Should not match wrong object ID", roughWall.matchesObjectId(12345));
    }

    @Test
    public void testCourseSummary() {
        Optional<AgilityCourse> courseOpt = repository.getCourseById("draynor_rooftop");
        assertTrue("Should find Draynor course", courseOpt.isPresent());

        String summary = courseOpt.get().getSummary();
        assertTrue("Summary should contain name", summary.contains("Draynor Village Rooftop"));
        assertTrue("Summary should contain level", summary.contains("10"));
        assertTrue("Summary should contain obstacle count", summary.contains("7"));
    }
}

