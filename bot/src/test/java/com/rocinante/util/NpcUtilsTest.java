package com.rocinante.util;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link NpcUtils} utility class.
 *
 * Validates boss detection logic used for special attack decisions,
 * defensive prayers, and other boss-specific behavior.
 */
public class NpcUtilsTest {

    // ========================================================================
    // isBoss() - God Wars Dungeon Bosses
    // ========================================================================

    @Test
    public void testIsBoss_GeneralGraardor() {
        assertTrue("General Graardor should be a boss", NpcUtils.isBoss("General Graardor"));
    }

    @Test
    public void testIsBoss_KrilTsutsaroth() {
        assertTrue("K'ril Tsutsaroth should be a boss", NpcUtils.isBoss("K'ril Tsutsaroth"));
    }

    @Test
    public void testIsBoss_CommanderZilyana() {
        assertTrue("Commander Zilyana should be a boss", NpcUtils.isBoss("Commander Zilyana"));
    }

    @Test
    public void testIsBoss_Kreearra() {
        assertTrue("Kree'arra should be a boss", NpcUtils.isBoss("Kree'arra"));
    }

    // ========================================================================
    // isBoss() - Wilderness Bosses
    // ========================================================================

    @Test
    public void testIsBoss_Callisto() {
        assertTrue("Callisto should be a boss", NpcUtils.isBoss("Callisto"));
    }

    @Test
    public void testIsBoss_Vetion() {
        assertTrue("Vet'ion should be a boss", NpcUtils.isBoss("Vet'ion"));
    }

    @Test
    public void testIsBoss_Venenatis() {
        assertTrue("Venenatis should be a boss", NpcUtils.isBoss("Venenatis"));
    }

    @Test
    public void testIsBoss_Scorpia() {
        assertTrue("Scorpia should be a boss", NpcUtils.isBoss("Scorpia"));
    }

    @Test
    public void testIsBoss_ChaosFanatic() {
        assertTrue("Chaos Fanatic should be a boss", NpcUtils.isBoss("Chaos Fanatic"));
    }

    @Test
    public void testIsBoss_CrazyArchaeologist() {
        assertTrue("Crazy archaeologist should be a boss", NpcUtils.isBoss("Crazy archaeologist"));
    }

    @Test
    public void testIsBoss_KingBlackDragon() {
        assertTrue("King Black Dragon should be a boss", NpcUtils.isBoss("King Black Dragon"));
    }

    @Test
    public void testIsBoss_ChaosElemental() {
        assertTrue("Chaos Elemental should be a boss", NpcUtils.isBoss("Chaos Elemental"));
    }

    // ========================================================================
    // isBoss() - Solo Bosses
    // ========================================================================

    @Test
    public void testIsBoss_Zulrah() {
        assertTrue("Zulrah should be a boss", NpcUtils.isBoss("Zulrah"));
    }

    @Test
    public void testIsBoss_Vorkath() {
        assertTrue("Vorkath should be a boss", NpcUtils.isBoss("Vorkath"));
    }

    @Test
    public void testIsBoss_TheNightmare() {
        assertTrue("The Nightmare should be a boss", NpcUtils.isBoss("The Nightmare"));
    }

    @Test
    public void testIsBoss_PhosanisNightmare() {
        assertTrue("Phosani's Nightmare should be a boss", NpcUtils.isBoss("Phosani's Nightmare"));
    }

    @Test
    public void testIsBoss_CorporealBeast() {
        assertTrue("Corporeal Beast should be a boss", NpcUtils.isBoss("Corporeal Beast"));
    }

    @Test
    public void testIsBoss_GiantMole() {
        assertTrue("Giant Mole should be a boss", NpcUtils.isBoss("Giant Mole"));
    }

    @Test
    public void testIsBoss_Sarachnis() {
        assertTrue("Sarachnis should be a boss", NpcUtils.isBoss("Sarachnis"));
    }

    @Test
    public void testIsBoss_KalphiteQueen() {
        assertTrue("Kalphite Queen should be a boss", NpcUtils.isBoss("Kalphite Queen"));
    }

    // ========================================================================
    // isBoss() - Dagannoth Kings
    // ========================================================================

    @Test
    public void testIsBoss_DagannothRex() {
        assertTrue("Dagannoth Rex should be a boss", NpcUtils.isBoss("Dagannoth Rex"));
    }

    @Test
    public void testIsBoss_DagannothPrime() {
        assertTrue("Dagannoth Prime should be a boss", NpcUtils.isBoss("Dagannoth Prime"));
    }

    @Test
    public void testIsBoss_DagannothSupreme() {
        assertTrue("Dagannoth Supreme should be a boss", NpcUtils.isBoss("Dagannoth Supreme"));
    }

    // ========================================================================
    // isBoss() - Slayer Bosses
    // ========================================================================

    @Test
    public void testIsBoss_Cerberus() {
        assertTrue("Cerberus should be a boss", NpcUtils.isBoss("Cerberus"));
    }

    @Test
    public void testIsBoss_AbyssalSire() {
        assertTrue("Abyssal Sire should be a boss", NpcUtils.isBoss("Abyssal Sire"));
    }

    @Test
    public void testIsBoss_Kraken() {
        assertTrue("Kraken should be a boss", NpcUtils.isBoss("Kraken"));
    }

    @Test
    public void testIsBoss_ThermonuclearSmokDevil() {
        assertTrue("Thermonuclear smoke devil should be a boss", 
                NpcUtils.isBoss("Thermonuclear smoke devil"));
    }

    @Test
    public void testIsBoss_AlchemicalHydra() {
        assertTrue("Alchemical Hydra should be a boss", NpcUtils.isBoss("Alchemical Hydra"));
    }

    @Test
    public void testIsBoss_GrotesqueGuardians() {
        assertTrue("Grotesque Guardians should be a boss", NpcUtils.isBoss("Grotesque Guardians"));
    }

    @Test
    public void testIsBoss_Skotizo() {
        assertTrue("Skotizo should be a boss", NpcUtils.isBoss("Skotizo"));
    }

    // ========================================================================
    // isBoss() - Raids Bosses
    // ========================================================================

    @Test
    public void testIsBoss_GreatOlm() {
        assertTrue("Great Olm should be a boss", NpcUtils.isBoss("Great Olm"));
    }

    @Test
    public void testIsBoss_VerzikVitur() {
        assertTrue("Verzik Vitur should be a boss", NpcUtils.isBoss("Verzik Vitur"));
    }

    @Test
    public void testIsBoss_TheLeviathan() {
        assertTrue("The Leviathan should be a boss", NpcUtils.isBoss("The Leviathan"));
    }

    @Test
    public void testIsBoss_TheWhisperer() {
        assertTrue("The Whisperer should be a boss", NpcUtils.isBoss("The Whisperer"));
    }

    @Test
    public void testIsBoss_Vardorvis() {
        assertTrue("Vardorvis should be a boss", NpcUtils.isBoss("Vardorvis"));
    }

    @Test
    public void testIsBoss_DukeSucellus() {
        assertTrue("Duke Sucellus should be a boss", NpcUtils.isBoss("Duke Sucellus"));
    }

    // ========================================================================
    // isBoss() - Other Bosses
    // ========================================================================

    @Test
    public void testIsBoss_TzTokJad() {
        assertTrue("TzTok-Jad should be a boss", NpcUtils.isBoss("TzTok-Jad"));
    }

    @Test
    public void testIsBoss_TzKalZuk() {
        assertTrue("TzKal-Zuk should be a boss", NpcUtils.isBoss("TzKal-Zuk"));
    }

    @Test
    public void testIsBoss_Hespori() {
        assertTrue("Hespori should be a boss", NpcUtils.isBoss("Hespori"));
    }

    @Test
    public void testIsBoss_Mimic() {
        assertTrue("Mimic should be a boss", NpcUtils.isBoss("Mimic"));
    }

    @Test
    public void testIsBoss_CrystallineHunllef() {
        assertTrue("Crystalline Hunllef should be a boss", NpcUtils.isBoss("Crystalline Hunllef"));
    }

    @Test
    public void testIsBoss_CorruptedHunllef() {
        assertTrue("Corrupted Hunllef should be a boss", NpcUtils.isBoss("Corrupted Hunllef"));
    }

    // ========================================================================
    // isBoss() - Regular NPCs (Should NOT be bosses)
    // ========================================================================

    @Test
    public void testIsBoss_Man_NotBoss() {
        assertFalse("Man should not be a boss", NpcUtils.isBoss("Man"));
    }

    @Test
    public void testIsBoss_Goblin_NotBoss() {
        assertFalse("Goblin should not be a boss", NpcUtils.isBoss("Goblin"));
    }

    @Test
    public void testIsBoss_Guard_NotBoss() {
        assertFalse("Guard should not be a boss", NpcUtils.isBoss("Guard"));
    }

    @Test
    public void testIsBoss_Cow_NotBoss() {
        assertFalse("Cow should not be a boss", NpcUtils.isBoss("Cow"));
    }

    @Test
    public void testIsBoss_Chicken_NotBoss() {
        assertFalse("Chicken should not be a boss", NpcUtils.isBoss("Chicken"));
    }

    @Test
    public void testIsBoss_AbyssalDemon_NotBoss() {
        // Abyssal demon (regular slayer creature) should not be a boss
        // Only Abyssal Sire is a boss
        assertFalse("Abyssal demon should not be a boss", NpcUtils.isBoss("Abyssal demon"));
    }

    @Test
    public void testIsBoss_BlueDragon_NotBoss() {
        assertFalse("Blue dragon should not be a boss", NpcUtils.isBoss("Blue dragon"));
    }

    @Test
    public void testIsBoss_BlackDemon_NotBoss() {
        assertFalse("Black demon should not be a boss", NpcUtils.isBoss("Black demon"));
    }

    @Test
    public void testIsBoss_Dagannoth_NotBoss() {
        // Regular dagannoth should not be a boss (only Kings are)
        assertFalse("Dagannoth should not be a boss", NpcUtils.isBoss("Dagannoth"));
    }

    // ========================================================================
    // isBoss() - Null Safety
    // ========================================================================

    @Test
    public void testIsBoss_Null_ReturnsFalse() {
        assertFalse("Null should not be a boss", NpcUtils.isBoss(null));
    }

    @Test
    public void testIsBoss_EmptyString_ReturnsFalse() {
        assertFalse("Empty string should not be a boss", NpcUtils.isBoss(""));
    }

    // ========================================================================
    // isBoss() - Case Sensitivity
    // ========================================================================

    @Test
    public void testIsBoss_CaseSensitive() {
        // Boss names must match exactly
        assertFalse("Lowercase 'zulrah' should not match", NpcUtils.isBoss("zulrah"));
        assertFalse("Uppercase 'ZULRAH' should not match", NpcUtils.isBoss("ZULRAH"));
        assertFalse("Mixed case 'ZuLrAh' should not match", NpcUtils.isBoss("ZuLrAh"));
    }

    // ========================================================================
    // getBossNames() Tests
    // ========================================================================

    @Test
    public void testGetBossNames_NotNull() {
        assertNotNull("getBossNames() should not return null", NpcUtils.getBossNames());
    }

    @Test
    public void testGetBossNames_NotEmpty() {
        assertFalse("getBossNames() should not be empty", NpcUtils.getBossNames().isEmpty());
    }

    @Test
    public void testGetBossNames_ContainsKnownBosses() {
        Set<String> bossNames = NpcUtils.getBossNames();
        assertTrue("Should contain Zulrah", bossNames.contains("Zulrah"));
        assertTrue("Should contain Vorkath", bossNames.contains("Vorkath"));
        assertTrue("Should contain General Graardor", bossNames.contains("General Graardor"));
        assertTrue("Should contain Cerberus", bossNames.contains("Cerberus"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testGetBossNames_Immutable() {
        Set<String> bossNames = NpcUtils.getBossNames();
        bossNames.add("Fake Boss");
    }

    // ========================================================================
    // Consistency Tests
    // ========================================================================

    @Test
    public void testIsBoss_ConsistentWithGetBossNames() {
        // Every name in getBossNames() should return true for isBoss()
        for (String bossName : NpcUtils.getBossNames()) {
            assertTrue("isBoss() should return true for '" + bossName + "'", 
                    NpcUtils.isBoss(bossName));
        }
    }

    @Test
    public void testBossCount_ReasonableNumber() {
        // Sanity check: there should be a reasonable number of bosses
        int bossCount = NpcUtils.getBossNames().size();
        assertTrue("Should have at least 30 bosses", bossCount >= 30);
        assertTrue("Should have fewer than 100 bosses", bossCount < 100);
    }
}

