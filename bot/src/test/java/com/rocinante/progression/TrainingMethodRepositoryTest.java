package com.rocinante.progression;

import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Unit tests for TrainingMethodRepository.
 */
public class TrainingMethodRepositoryTest {

    private TrainingMethodRepository repository;

    @Before
    public void setUp() {
        repository = new TrainingMethodRepository();
    }

    // ========================================================================
    // Loading Tests
    // ========================================================================

    @Test
    public void testRepositoryLoadsMethodsFromJson() {
        assertTrue("Repository should load at least one method", repository.getMethodCount() > 0);
    }

    @Test
    public void testAllMethodIds() {
        assertFalse("Should have method IDs", repository.getAllMethodIds().isEmpty());
        assertTrue("Should contain iron_ore_powermine", 
                repository.hasMethod("iron_ore_powermine"));
    }

    // ========================================================================
    // Query Tests
    // ========================================================================

    @Test
    public void testGetMethodById() {
        Optional<TrainingMethod> method = repository.getMethodById("iron_ore_powermine");
        
        assertTrue("iron_ore_powermine should exist", method.isPresent());
        assertEquals("Iron Ore - Power Mining", method.get().getName());
        assertEquals(Skill.MINING, method.get().getSkill());
        assertEquals(MethodType.GATHER, method.get().getMethodType());
        assertEquals(15, method.get().getMinLevel());
        assertEquals(35.0, method.get().getXpPerAction(), 0.01);
    }

    @Test
    public void testGetMethodById_NotFound() {
        Optional<TrainingMethod> method = repository.getMethodById("nonexistent_method");
        assertFalse("Nonexistent method should not be found", method.isPresent());
    }

    @Test
    public void testGetMethodsForSkill() {
        List<TrainingMethod> miningMethods = repository.getMethodsForSkill(Skill.MINING);
        
        assertFalse("Should have mining methods", miningMethods.isEmpty());
        assertTrue("All methods should be for Mining",
                miningMethods.stream().allMatch(m -> m.getSkill() == Skill.MINING));
    }

    @Test
    public void testGetMethodsForSkillAtLevel() {
        // Level 1 should include copper/tin but not iron
        List<TrainingMethod> level1Methods = repository.getMethodsForSkill(Skill.MINING, 1);
        assertTrue("Level 1 should have at least one method", level1Methods.size() >= 1);
        assertTrue("Level 1 methods should all have minLevel <= 1",
                level1Methods.stream().allMatch(m -> m.getMinLevel() <= 1));
        
        // Level 15 should include iron
        List<TrainingMethod> level15Methods = repository.getMethodsForSkill(Skill.MINING, 15);
        assertTrue("Level 15 should have more methods", level15Methods.size() >= level1Methods.size());
    }

    @Test
    public void testGetBestMethod() {
        Optional<TrainingMethod> best = repository.getBestMethod(Skill.MINING, 15);
        
        assertTrue("Should find a best method for Mining at 15", best.isPresent());
        assertTrue("Best method should be valid for level 15", best.get().isValidForLevel(15));
    }

    @Test
    public void testGetGatheringMethods() {
        List<TrainingMethod> gatheringMethods = repository.getGatheringMethods();
        
        assertFalse("Should have gathering methods", gatheringMethods.isEmpty());
        assertTrue("All should be GATHER type",
                gatheringMethods.stream().allMatch(m -> m.getMethodType() == MethodType.GATHER));
    }

    @Test
    public void testGetProcessingMethods() {
        List<TrainingMethod> processingMethods = repository.getProcessingMethods();
        
        assertFalse("Should have processing methods", processingMethods.isEmpty());
        assertTrue("All should be PROCESS type",
                processingMethods.stream().allMatch(m -> m.getMethodType() == MethodType.PROCESS));
    }

    @Test
    public void testGetPowerMethods() {
        List<TrainingMethod> powerMethods = repository.getPowerMethods();
        
        assertFalse("Should have power training methods", powerMethods.isEmpty());
        assertTrue("All should have dropWhenFull=true",
                powerMethods.stream().allMatch(TrainingMethod::isDropWhenFull));
    }

    @Test
    public void testGetBankingMethods() {
        List<TrainingMethod> bankingMethods = repository.getBankingMethods();
        
        assertFalse("Should have banking methods", bankingMethods.isEmpty());
        assertTrue("All should require banking",
                bankingMethods.stream().allMatch(TrainingMethod::requiresBanking));
    }

    // ========================================================================
    // Ironman Filter Tests
    // ========================================================================

    @Test
    public void testIronmanFilter() {
        List<TrainingMethod> ironmanMethods = repository.getMethodsForSkill(Skill.MINING, 1, true);
        
        assertFalse("Should have ironman-viable methods", ironmanMethods.isEmpty());
        assertTrue("All should be ironman-viable",
                ironmanMethods.stream().allMatch(TrainingMethod::isIronmanViable));
    }

    // ========================================================================
    // TrainingMethod Tests
    // ========================================================================

    @Test
    public void testTrainingMethodValidForLevel() {
        TrainingMethod ironMethod = repository.getMethodById("iron_ore_powermine").get();
        
        assertFalse("Level 10 should not be valid for iron", ironMethod.isValidForLevel(10));
        assertTrue("Level 15 should be valid for iron", ironMethod.isValidForLevel(15));
        assertTrue("Level 50 should be valid for iron", ironMethod.isValidForLevel(50));
    }

    @Test
    public void testTrainingMethodXpPerHour() {
        TrainingMethod ironMethod = repository.getMethodById("iron_ore_powermine").get();
        
        // xpPerHour now depends on the location's actionsPerHour
        // iron_ore_powermine at al_kharid has 1200 actions/hr
        double expectedXpPerHour = 35.0 * 1200; // xpPerAction * actionsPerHour at default location
        assertEquals(expectedXpPerHour, ironMethod.getXpPerHour(15), 0.01);
    }

    @Test
    public void testTrainingMethodTypeChecks() {
        TrainingMethod gatherMethod = repository.getMethodById("iron_ore_powermine").get();
        assertTrue("Mining should be gathering", gatherMethod.isGatheringMethod());
        assertFalse("Mining should not be processing", gatherMethod.isProcessingMethod());
        
        // Find a processing method
        Optional<TrainingMethod> processMethod = repository.getMethodById("arrow_shafts_logs");
        if (processMethod.isPresent()) {
            assertFalse("Fletching should not be gathering", processMethod.get().isGatheringMethod());
            assertTrue("Fletching should be processing", processMethod.get().isProcessingMethod());
        }
    }

    @Test
    public void testTrainingMethodHasTargetObjects() {
        TrainingMethod ironMethod = repository.getMethodById("iron_ore_powermine").get();
        
        assertTrue("Iron mining should have target objects", ironMethod.hasTargetObjects());
        assertFalse("Iron mining should not have target NPCs", ironMethod.hasTargetNpcs());
    }

    @Test
    public void testTrainingMethodSummary() {
        TrainingMethod method = repository.getMethodById("iron_ore_powermine").get();
        String summary = method.getSummary();
        
        assertNotNull("Summary should not be null", summary);
        assertTrue("Summary should contain method name", summary.contains("Iron Ore"));
        assertTrue("Summary should contain skill name", summary.contains("Mining"));
    }

    // ========================================================================
    // Agility Method Tests
    // ========================================================================

    @Test
    public void testAgilityMethodLoaded() {
        Optional<TrainingMethod> method = repository.getMethodById("draynor_rooftop_course");
        
        assertTrue("Draynor rooftop course should exist", method.isPresent());
        assertEquals(Skill.AGILITY, method.get().getSkill());
        assertEquals(MethodType.AGILITY, method.get().getMethodType());
        assertEquals("draynor_rooftop", method.get().getCourseId());
        assertTrue("Agility method should be a course", method.get().isAgilityCourse());
    }

    @Test
    public void testAgilityMethodsForSkill() {
        List<TrainingMethod> agilityMethods = repository.getMethodsForSkill(Skill.AGILITY);
        
        assertFalse("Should have agility methods", agilityMethods.isEmpty());
        assertTrue("All should be for Agility",
                agilityMethods.stream().allMatch(m -> m.getSkill() == Skill.AGILITY));
    }

    @Test
    public void testAgilityMethodByType() {
        List<TrainingMethod> agilityMethods = repository.getMethodsByType(MethodType.AGILITY);
        
        assertFalse("Should have AGILITY type methods", agilityMethods.isEmpty());
        assertTrue("All should have courseId set",
                agilityMethods.stream().allMatch(m -> m.getCourseId() != null));
    }

    // ========================================================================
    // Ground Item Watch Tests
    // ========================================================================

    @Test
    public void testGroundItemWatchLoaded() {
        Optional<TrainingMethod> method = repository.getMethodById("draynor_rooftop_course");
        assertTrue("Draynor rooftop course should exist", method.isPresent());

        TrainingMethod agility = method.get();
        assertTrue("Agility should have watched ground items", agility.hasWatchedGroundItems());

        List<GroundItemWatch> watches = agility.getWatchedGroundItems();
        assertFalse("Should have at least one watched item", watches.isEmpty());

        // Check mark of grace is watched
        assertTrue("Should watch for Mark of grace",
                watches.stream().anyMatch(w -> w.getItemId() == 11849));
    }

    @Test
    public void testGroundItemWatchParsing() {
        Optional<TrainingMethod> method = repository.getMethodById("draynor_rooftop_course");
        assertTrue("Draynor rooftop course should exist", method.isPresent());

        GroundItemWatch markWatch = method.get().getWatchedGroundItems().get(0);
        assertEquals(11849, markWatch.getItemId());
        assertEquals("Mark of grace", markWatch.getItemName());
        assertEquals(100, markWatch.getPriority());
        assertEquals(20, markWatch.getMaxPickupDistance());
        assertFalse("Marks should not interrupt", markWatch.isInterruptAction());
    }

    @Test
    public void testWoodcuttingBirdNestWatching() {
        // In new format, bird nest watching is on willow_trees_powerchop
        Optional<TrainingMethod> method = repository.getMethodById("willow_trees_powerchop");
        assertTrue("Willow trees powerchop should exist", method.isPresent());

        TrainingMethod wc = method.get();
        assertTrue("Woodcutting should have watched ground items", wc.hasWatchedGroundItems());

        List<GroundItemWatch> watches = wc.getWatchedGroundItems();
        assertTrue("Should watch for multiple nest types", watches.size() >= 3);

        // Check bird nest is watched
        assertTrue("Should watch for Bird nest (empty)",
                watches.stream().anyMatch(w -> w.getItemId() == 5070));

        // Bird nests should interrupt action (they despawn quickly)
        assertTrue("Nest watches should interrupt action",
                watches.stream()
                        .filter(w -> w.getItemName().contains("Bird nest"))
                        .allMatch(GroundItemWatch::isInterruptAction));
    }

    @Test
    public void testGroundItemWatchMatching() {
        Optional<TrainingMethod> method = repository.getMethodById("draynor_rooftop_course");
        assertTrue("Draynor rooftop course should exist", method.isPresent());

        GroundItemWatch markWatch = method.get().getWatchedGroundItems().get(0);

        assertTrue("Should match mark ID", markWatch.matches(11849));
        assertFalse("Should not match random ID", markWatch.matches(12345));
    }

    // ========================================================================
    // Multi-Location Tests
    // ========================================================================

    @Test
    public void testMethodHasLocations() {
        TrainingMethod ironMethod = repository.getMethodById("iron_ore_powermine").get();
        
        assertTrue("Iron mining should have locations", ironMethod.getLocationCount() > 0);
        assertTrue("Iron mining should have multiple locations", ironMethod.hasMultipleLocations());
    }

    @Test
    public void testMethodLocationDetails() {
        TrainingMethod ironMethod = repository.getMethodById("iron_ore_powermine").get();
        MethodLocation defaultLoc = ironMethod.getDefaultLocation();
        
        assertNotNull("Should have a default location", defaultLoc);
        assertNotNull("Location should have an ID", defaultLoc.getId());
        assertNotNull("Location should have a name", defaultLoc.getName());
        assertTrue("Location should have actions per hour", defaultLoc.getActionsPerHour() > 0);
    }

    @Test
    public void testMethodLocationLookup() {
        TrainingMethod ironMethod = repository.getMethodById("iron_ore_powermine").get();
        
        MethodLocation alKharid = ironMethod.getLocation("al_kharid");
        assertNotNull("Should find al_kharid location", alKharid);
        assertEquals("al_kharid", alKharid.getId());
        
        MethodLocation nonexistent = ironMethod.getLocation("nonexistent");
        assertNull("Should return null for nonexistent location", nonexistent);
    }

    @Test
    public void testMethodXpPerHourRange() {
        TrainingMethod ironMethod = repository.getMethodById("iron_ore_powermine").get();
        
        double[] range = ironMethod.getXpPerHourRange(15);
        assertTrue("Min XP/hr should be > 0", range[0] > 0);
        assertTrue("Max XP/hr should be >= min", range[1] >= range[0]);
    }

    // ========================================================================
    // Requirements Tests
    // ========================================================================

    @Test
    public void testMethodRequirements() {
        // Prifddinas agility should have quest requirement
        Optional<TrainingMethod> prifMethod = repository.getMethodById("prifddinas_agility_course");
        assertTrue("Prifddinas course should exist", prifMethod.isPresent());
        
        assertTrue("Prifddinas should have requirements", prifMethod.get().hasRequirements());
        assertTrue("Prifddinas should require membership", prifMethod.get().requiresMembership());
    }

    @Test
    public void testLocationRequirements() {
        TrainingMethod ironMethod = repository.getMethodById("iron_ore_powermine").get();
        
        // Mining guild location should have level requirement
        MethodLocation guildLoc = ironMethod.getLocation("mining_guild");
        if (guildLoc != null) {
            assertTrue("Mining guild should have requirements", guildLoc.hasRequirements());
            assertTrue("Mining guild should require membership", guildLoc.requiresMembership());
        }
    }
}
