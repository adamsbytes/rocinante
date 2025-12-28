package com.rocinante.slayer;

import net.runelite.api.NpcID;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for SlayerMaster enum.
 */
public class SlayerMasterTest {

    // ========================================================================
    // Lookup Tests
    // ========================================================================

    @Test
    public void testFromMasterId() {
        assertEquals(SlayerMaster.TURAEL, SlayerMaster.fromMasterId(1));
        assertEquals(SlayerMaster.MAZCHNA, SlayerMaster.fromMasterId(2));
        assertEquals(SlayerMaster.VANNAKA, SlayerMaster.fromMasterId(3));
        assertEquals(SlayerMaster.CHAELDAR, SlayerMaster.fromMasterId(4));
        assertEquals(SlayerMaster.NIEVE, SlayerMaster.fromMasterId(5));
        assertEquals(SlayerMaster.KONAR, SlayerMaster.fromMasterId(6));
        assertEquals(SlayerMaster.KRYSTILIA, SlayerMaster.fromMasterId(7));
        assertEquals(SlayerMaster.DURADEL, SlayerMaster.fromMasterId(8));
        assertNull(SlayerMaster.fromMasterId(99));
    }

    @Test
    public void testFromNpcId() {
        assertEquals(SlayerMaster.TURAEL, SlayerMaster.fromNpcId(NpcID.TURAEL));
        assertEquals(SlayerMaster.MAZCHNA, SlayerMaster.fromNpcId(NpcID.MAZCHNA));
        assertEquals(SlayerMaster.VANNAKA, SlayerMaster.fromNpcId(NpcID.VANNAKA));
        assertEquals(SlayerMaster.CHAELDAR, SlayerMaster.fromNpcId(NpcID.CHAELDAR));
        assertEquals(SlayerMaster.NIEVE, SlayerMaster.fromNpcId(NpcID.NIEVE));
        assertEquals(SlayerMaster.STEVE, SlayerMaster.fromNpcId(NpcID.STEVE));
        assertEquals(SlayerMaster.KONAR, SlayerMaster.fromNpcId(NpcID.KONAR_QUO_MATEN));
        assertEquals(SlayerMaster.KRYSTILIA, SlayerMaster.fromNpcId(NpcID.KRYSTILIA));
        assertEquals(SlayerMaster.DURADEL, SlayerMaster.fromNpcId(NpcID.DURADEL));
        assertNull(SlayerMaster.fromNpcId(99999));
    }

    // ========================================================================
    // Master Selection Tests
    // ========================================================================

    @Test
    public void testGetBestMaster_LowLevel() {
        // Combat 20, Slayer 1
        SlayerMaster best = SlayerMaster.getBestMaster(20, 1);
        assertEquals(SlayerMaster.MAZCHNA, best);
    }

    @Test
    public void testGetBestMaster_MidLevel() {
        // Combat 50, Slayer 1
        SlayerMaster best = SlayerMaster.getBestMaster(50, 1);
        assertEquals(SlayerMaster.VANNAKA, best);
    }

    @Test
    public void testGetBestMaster_HighLevel() {
        // Combat 100, Slayer 50
        SlayerMaster best = SlayerMaster.getBestMaster(100, 50);
        assertEquals(SlayerMaster.DURADEL, best);
    }

    @Test
    public void testGetBestMaster_HighCombatLowSlayer() {
        // Combat 100, Slayer 40 - can't use Duradel (requires 50 slayer)
        SlayerMaster best = SlayerMaster.getBestMaster(100, 40);
        assertEquals(SlayerMaster.NIEVE, best);
    }

    @Test
    public void testGetBestMaster_VeryLowLevel() {
        // Combat 5, Slayer 1
        SlayerMaster best = SlayerMaster.getBestMaster(5, 1);
        assertEquals(SlayerMaster.TURAEL, best);
    }

    @Test
    public void testGetBestMaster_WithKonar() {
        // Combat 100, Slayer 50, allow Konar
        SlayerMaster best = SlayerMaster.getBestMaster(100, 50, true, false);
        // Duradel (combat 100, slayer 50) beats Konar (combat 75, slayer 1)
        assertEquals(SlayerMaster.DURADEL, best);
    }

    @Test
    public void testGetBestMaster_WithWilderness() {
        // Combat 30, Slayer 1, allow wilderness
        SlayerMaster best = SlayerMaster.getBestMaster(30, 1, false, true);
        // Krystilia has no combat requirement, but Mazchna (combat 20) should still be selected
        // as higher combat requirement = better tasks
        assertEquals(SlayerMaster.MAZCHNA, best);
    }

    // ========================================================================
    // Requirement Tests
    // ========================================================================

    @Test
    public void testMeetsRequirements() {
        assertTrue(SlayerMaster.TURAEL.meetsRequirements(3, 1));
        assertTrue(SlayerMaster.MAZCHNA.meetsRequirements(20, 1));
        assertFalse(SlayerMaster.MAZCHNA.meetsRequirements(15, 1));

        assertTrue(SlayerMaster.DURADEL.meetsRequirements(100, 50));
        assertFalse(SlayerMaster.DURADEL.meetsRequirements(100, 40)); // Slayer too low
        assertFalse(SlayerMaster.DURADEL.meetsRequirements(90, 50)); // Combat too low
    }

    // ========================================================================
    // Property Tests
    // ========================================================================

    @Test
    public void testWildernessOnly() {
        assertTrue(SlayerMaster.KRYSTILIA.isWildernessOnly());
        assertFalse(SlayerMaster.DURADEL.isWildernessOnly());
        assertFalse(SlayerMaster.TURAEL.isWildernessOnly());
    }

    @Test
    public void testCanResetStreak() {
        assertTrue(SlayerMaster.TURAEL.canResetStreak());
        assertTrue(SlayerMaster.SPRIA.canResetStreak());
        assertFalse(SlayerMaster.DURADEL.canResetStreak());
        assertFalse(SlayerMaster.KONAR.canResetStreak());
    }

    @Test
    public void testHasLocationRestrictions() {
        assertTrue(SlayerMaster.KONAR.hasLocationRestrictions());
        assertFalse(SlayerMaster.DURADEL.hasLocationRestrictions());
        assertFalse(SlayerMaster.NIEVE.hasLocationRestrictions());
    }

    // ========================================================================
    // Standard Masters Tests
    // ========================================================================

    @Test
    public void testGetStandardMasters() {
        SlayerMaster[] standard = SlayerMaster.getStandardMasters();

        // Should be sorted by combat requirement (lowest to highest)
        assertTrue(standard.length >= 6);
        assertEquals(SlayerMaster.TURAEL, standard[0]);

        // Krystilia (wilderness) should not be in standard list
        for (SlayerMaster master : standard) {
            assertFalse(master.isWildernessOnly());
        }

        // Spria and Steve should be excluded as duplicates
        for (SlayerMaster master : standard) {
            assertNotEquals(SlayerMaster.SPRIA, master);
            assertNotEquals(SlayerMaster.STEVE, master);
        }
    }

    // ========================================================================
    // Dialogue Tests
    // ========================================================================

    @Test
    public void testGetNewTaskDialogueOptions() {
        String[] options = SlayerMaster.DURADEL.getNewTaskDialogueOptions();
        assertNotNull(options);
        assertTrue(options.length > 0);
        // Should contain something about getting an assignment
        boolean hasAssignment = false;
        for (String option : options) {
            if (option.toLowerCase().contains("assignment") || option.toLowerCase().contains("task")) {
                hasAssignment = true;
                break;
            }
        }
        assertTrue(hasAssignment);
    }

    @Test
    public void testGetCheckTaskDialogueOptions() {
        String[] options = SlayerMaster.NIEVE.getCheckTaskDialogueOptions();
        assertNotNull(options);
        assertTrue(options.length > 0);
    }

    // ========================================================================
    // Web Node Test
    // ========================================================================

    @Test
    public void testGetWebNodeId() {
        String nodeId = SlayerMaster.DURADEL.getWebNodeId();
        assertEquals("slayer_master_duradel", nodeId);

        String konarNode = SlayerMaster.KONAR.getWebNodeId();
        assertEquals("slayer_master_konar", konarNode);
    }
}

