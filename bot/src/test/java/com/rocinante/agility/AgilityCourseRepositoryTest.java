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
        // Verify all 11 courses were loaded
        assertEquals("Should have 11 courses", 11, repository.getCourseCount());
        
        // Verify each course exists
        assertTrue("Should have Draynor rooftop course", repository.hasCourse("draynor_rooftop"));
        assertTrue("Should have Al Kharid rooftop course", repository.hasCourse("al_kharid_rooftop"));
        assertTrue("Should have Varrock rooftop course", repository.hasCourse("varrock_rooftop"));
        assertTrue("Should have Canifis rooftop course", repository.hasCourse("canifis_rooftop"));
        assertTrue("Should have Falador rooftop course", repository.hasCourse("falador_rooftop"));
        assertTrue("Should have Seers rooftop course", repository.hasCourse("seers_rooftop"));
        assertTrue("Should have Pollnivneach rooftop course", repository.hasCourse("pollnivneach_rooftop"));
        assertTrue("Should have Rellekka rooftop course", repository.hasCourse("rellekka_rooftop"));
        assertTrue("Should have Ardougne rooftop course", repository.hasCourse("ardougne_rooftop"));
        assertTrue("Should have Prifddinas agility course", repository.hasCourse("prifddinas_agility"));
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
        // Test all course regions
        assertEquals("draynor_rooftop", repository.getCourseByRegion(12338).map(AgilityCourse::getId).orElse(null));
        assertEquals("al_kharid_rooftop", repository.getCourseByRegion(13105).map(AgilityCourse::getId).orElse(null));
        assertEquals("varrock_rooftop", repository.getCourseByRegion(12853).map(AgilityCourse::getId).orElse(null));
        assertEquals("canifis_rooftop", repository.getCourseByRegion(13878).map(AgilityCourse::getId).orElse(null));
        assertEquals("falador_rooftop", repository.getCourseByRegion(12084).map(AgilityCourse::getId).orElse(null));
        assertEquals("seers_rooftop", repository.getCourseByRegion(10806).map(AgilityCourse::getId).orElse(null));
        assertEquals("pollnivneach_rooftop", repository.getCourseByRegion(13358).map(AgilityCourse::getId).orElse(null));
        assertEquals("rellekka_rooftop", repository.getCourseByRegion(10553).map(AgilityCourse::getId).orElse(null));
        assertEquals("ardougne_rooftop", repository.getCourseByRegion(10547).map(AgilityCourse::getId).orElse(null));
        assertEquals("prifddinas_agility", repository.getCourseByRegion(12895).map(AgilityCourse::getId).orElse(null));
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

        // Level 50 - should not get Draynor (maxLevel is 20), should get Falador
        List<AgilityCourse> level50 = repository.getCoursesForLevel(50);
        assertFalse("Draynor not recommended at level 50",
                level50.stream().anyMatch(c -> c.getId().equals("draynor_rooftop")));
        assertTrue("Falador available at level 50",
                level50.stream().anyMatch(c -> c.getId().equals("falador_rooftop")));

        // Level 90 - should get Ardougne
        List<AgilityCourse> level90 = repository.getCoursesForLevel(90);
        assertTrue("Ardougne available at level 90",
                level90.stream().anyMatch(c -> c.getId().equals("ardougne_rooftop")));

        // Level 75 - should get Prifddinas  
        List<AgilityCourse> level75 = repository.getCoursesForLevel(75);
        assertTrue("Prifddinas available at level 75",
                level75.stream().anyMatch(c -> c.getId().equals("prifddinas_agility")));
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

    @Test
    public void testAllCoursesHaveObstacles() {
        // Verify each course has correct obstacle count
        verifyObstacleCount("draynor_rooftop", 7);
        verifyObstacleCount("al_kharid_rooftop", 8);
        verifyObstacleCount("varrock_rooftop", 9);
        verifyObstacleCount("canifis_rooftop", 8);
        verifyObstacleCount("falador_rooftop", 13);
        verifyObstacleCount("seers_rooftop", 6);
        verifyObstacleCount("pollnivneach_rooftop", 9);
        verifyObstacleCount("rellekka_rooftop", 7);
        verifyObstacleCount("ardougne_rooftop", 7);
        verifyObstacleCount("prifddinas_agility", 12);
    }

    private void verifyObstacleCount(String courseId, int expectedCount) {
        Optional<AgilityCourse> course = repository.getCourseById(courseId);
        assertTrue("Course " + courseId + " should exist", course.isPresent());
        assertEquals("Course " + courseId + " should have " + expectedCount + " obstacles",
                expectedCount, course.get().getObstacles().size());
    }

    @Test
    public void testAllCoursesHaveRequiredData() {
        for (String courseId : repository.getAllCourseIds()) {
            AgilityCourse course = repository.getCourseById(courseId).orElseThrow();
            
            // Basic course data
            assertNotNull("Course " + courseId + " should have name", course.getName());
            assertTrue("Course " + courseId + " should have positive required level", 
                    course.getRequiredLevel() > 0);
            assertTrue("Course " + courseId + " should have positive XP per lap", 
                    course.getXpPerLap() > 0);
            assertTrue("Course " + courseId + " should have positive region ID", 
                    course.getRegionId() > 0);
            
            // Obstacle data
            assertFalse("Course " + courseId + " should have obstacles", 
                    course.getObstacles().isEmpty());
            
            for (AgilityObstacle obstacle : course.getObstacles()) {
                assertNotNull("Obstacle should have name", obstacle.getName());
                assertTrue("Obstacle should have object ID", obstacle.getObjectId() > 0);
                assertNotNull("Obstacle should have action", obstacle.getAction());
            }
        }
    }

    @Test
    public void testCanifisCourseSpecificDetails() {
        // Canifis is known for high mark rate - verify it's configured correctly
        AgilityCourse canifis = repository.getCourseById("canifis_rooftop").orElseThrow();
        
        assertEquals("Canifis Rooftop", canifis.getName());
        assertEquals(40, canifis.getRequiredLevel());
        assertEquals(240.0, canifis.getXpPerLap(), 0.1);
        assertEquals(17.0, canifis.getMarksPerHour(), 0.1); // Double mark rate
        assertEquals(13878, canifis.getRegionId());
        assertFalse("Canifis should not require diary", canifis.isRequiresDiary());
    }

    @Test
    public void testArdougneCourseSpecificDetails() {
        // Ardougne is high-level course with diary bonus
        AgilityCourse ardougne = repository.getCourseById("ardougne_rooftop").orElseThrow();
        
        assertEquals("Ardougne Rooftop", ardougne.getName());
        assertEquals(90, ardougne.getRequiredLevel());
        assertEquals(889.0, ardougne.getXpPerLap(), 0.1);
        assertEquals(22.0, ardougne.getMarksPerHour(), 0.1);
        assertTrue("Ardougne should require diary", ardougne.isRequiresDiary());
        assertEquals("Ardougne Elite", ardougne.getDiaryName());
    }

    @Test
    public void testPrifddinasNoMarks() {
        // Prifddinas gives crystal shards, not marks
        AgilityCourse prif = repository.getCourseById("prifddinas_agility").orElseThrow();
        
        assertEquals("Prifddinas Agility Course", prif.getName());
        assertEquals(75, prif.getRequiredLevel());
        assertEquals(1337.0, prif.getXpPerLap(), 0.1);
        assertEquals(0.0, prif.getMarksPerHour(), 0.01); // No marks!
        assertTrue("Prifddinas should have empty mark spawn tiles", 
                prif.getMarkSpawnTiles().isEmpty());
    }

    @Test
    public void testRooftopCoursesFilter() {
        List<AgilityCourse> rooftopCourses = repository.getRooftopCourses();
        
        // Should include all rooftop courses (9 rooftop + prifddinas is NOT rooftop)
        assertEquals("Should have 9 rooftop courses", 9, rooftopCourses.size());
        
        // Verify prifddinas is not in the list (it's not a rooftop course)
        assertFalse("Prifddinas should not be in rooftop list",
                rooftopCourses.stream().anyMatch(c -> c.getId().equals("prifddinas_agility")));
    }
}

