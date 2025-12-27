package com.rocinante.combat;

import com.rocinante.core.GameStateService;
import com.rocinante.data.WikiDataService;
import com.rocinante.data.model.WeaponInfo;
import com.rocinante.state.*;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SpecialAttackManager class.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SpecialAttackManagerTest {

    @Mock
    private Client client;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private GearSwitcher gearSwitcher;

    @Mock
    private WikiDataService wikiDataService;

    @Mock
    private ItemManager itemManager;

    @Mock
    private CombatState combatState;

    @Mock
    private EquipmentState equipmentState;

    @Mock
    private InventoryState inventoryState;

    @Mock
    private ItemComposition itemComposition;

    private SpecialAttackManager specManager;

    @Before
    public void setUp() {
        specManager = new SpecialAttackManager(client, gameStateService, gearSwitcher, wikiDataService, itemManager);
        specManager.setConfig(SpecialAttackConfig.DEFAULT);

        when(gameStateService.getCombatState()).thenReturn(combatState);
        when(gameStateService.getEquipmentState()).thenReturn(equipmentState);
        when(gameStateService.getInventoryState()).thenReturn(inventoryState);

        // Default mock for item composition
        when(itemManager.getItemComposition(anyInt())).thenReturn(itemComposition);
        when(itemComposition.getName()).thenReturn("Test Item");

        // Default mock for wiki service
        when(wikiDataService.getWeaponInfo(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(WeaponInfo.EMPTY));
        when(wikiDataService.getWeaponInfoSync(anyInt(), anyString()))
                .thenReturn(WeaponInfo.EMPTY);
    }

    // ========================================================================
    // SpecialWeapon Tests
    // ========================================================================

    @Test
    public void testSpecialWeapon_EnergyCosts() {
        assertEquals(25, SpecialWeapon.DRAGON_DAGGER.getEnergyCost());
        assertEquals(50, SpecialWeapon.DRAGON_WARHAMMER.getEnergyCost());
        assertEquals(50, SpecialWeapon.ARMADYL_GODSWORD.getEnergyCost());
        assertEquals(100, SpecialWeapon.DRAGON_BATTLEAXE.getEnergyCost());
    }

    @Test
    public void testSpecialWeapon_ForItemId() {
        assertEquals(SpecialWeapon.DRAGON_DAGGER, SpecialWeapon.forItemId(ItemID.DRAGON_DAGGER));
        assertEquals(SpecialWeapon.DRAGON_DAGGER, SpecialWeapon.forItemId(ItemID.DRAGON_DAGGERP));
        assertEquals(SpecialWeapon.DRAGON_WARHAMMER, SpecialWeapon.forItemId(ItemID.DRAGON_WARHAMMER));
        assertNull(SpecialWeapon.forItemId(ItemID.BRONZE_SWORD));
    }

    @Test
    public void testSpecialWeapon_HasSpecialAttack() {
        assertTrue(SpecialWeapon.hasSpecialAttack(ItemID.DRAGON_DAGGER));
        assertTrue(SpecialWeapon.hasSpecialAttack(ItemID.DRAGON_WARHAMMER));
        assertTrue(SpecialWeapon.hasSpecialAttack(ItemID.ABYSSAL_WHIP)); // Whip has spec
        assertFalse(SpecialWeapon.hasSpecialAttack(ItemID.BRONZE_SWORD));
    }

    @Test
    public void testSpecialWeapon_CanStack() {
        assertTrue(SpecialWeapon.DRAGON_DAGGER.canStack());
        assertTrue(SpecialWeapon.GRANITE_MAUL.canStack());
        assertFalse(SpecialWeapon.DRAGON_WARHAMMER.canStack());
        assertFalse(SpecialWeapon.ARMADYL_GODSWORD.canStack());
    }

    @Test
    public void testSpecialWeapon_MaxStackCount() {
        assertEquals(4, SpecialWeapon.DRAGON_DAGGER.getMaxStackCount()); // 100/25 = 4
        assertEquals(2, SpecialWeapon.DRAGON_WARHAMMER.getMaxStackCount()); // 100/50 = 2
        assertEquals(1, SpecialWeapon.DRAGON_BATTLEAXE.getMaxStackCount()); // 100/100 = 1
    }

    @Test
    public void testSpecialWeapon_IsDpsWeapon() {
        assertTrue(SpecialWeapon.DRAGON_DAGGER.isDpsWeapon()); // DOUBLE_HIT
        assertTrue(SpecialWeapon.DRAGON_CLAWS.isDpsWeapon()); // QUAD_HIT
        assertTrue(SpecialWeapon.ARMADYL_GODSWORD.isDpsWeapon()); // HIGH_DAMAGE
        assertFalse(SpecialWeapon.DRAGON_WARHAMMER.isDpsWeapon()); // DEFENCE_DRAIN
    }

    @Test
    public void testSpecialWeapon_IsUtilityWeapon() {
        assertTrue(SpecialWeapon.DRAGON_WARHAMMER.isUtilityWeapon()); // DEFENCE_DRAIN
        assertTrue(SpecialWeapon.BANDOS_GODSWORD.isUtilityWeapon()); // STAT_DRAIN
        assertTrue(SpecialWeapon.SARADOMIN_GODSWORD.isUtilityWeapon()); // HEAL
        assertFalse(SpecialWeapon.DRAGON_DAGGER.isUtilityWeapon()); // DOUBLE_HIT
    }

    // ========================================================================
    // SpecialAttackConfig Tests
    // ========================================================================

    @Test
    public void testSpecialAttackConfig_Default() {
        SpecialAttackConfig config = SpecialAttackConfig.DEFAULT;

        assertTrue(config.isEnabled());
        assertEquals(100, config.getEnergyThreshold());
        assertFalse(config.isUseOnlyOnBosses());
        assertFalse(config.isStackSpecs());
    }

    @Test
    public void testSpecialAttackConfig_DpsMode() {
        SpecialAttackConfig config = SpecialAttackConfig.DPS_MODE;

        assertTrue(config.isEnabled());
        assertEquals(25, config.getEnergyThreshold());
        assertTrue(config.isStackSpecs());
        assertEquals(4, config.getMaxStackCount());
    }

    @Test
    public void testSpecialAttackConfig_BossUtility() {
        SpecialAttackConfig config = SpecialAttackConfig.BOSS_UTILITY;

        assertTrue(config.isEnabled());
        assertEquals(50, config.getEnergyThreshold());
        assertTrue(config.isUseOnlyOnBosses());
        assertFalse(config.isStackSpecs());
    }

    @Test
    public void testSpecialAttackConfig_ShouldSpecTarget() {
        SpecialAttackConfig bossOnly = SpecialAttackConfig.builder()
                .enabled(true)
                .useOnlyOnBosses(true)
                .build();

        // Should only spec bosses
        assertTrue(bossOnly.shouldSpecTarget(100, 200, true));
        assertFalse(bossOnly.shouldSpecTarget(100, 200, false));
    }

    @Test
    public void testSpecialAttackConfig_ShouldSpecTarget_MinLevel() {
        SpecialAttackConfig minLevel = SpecialAttackConfig.builder()
                .enabled(true)
                .minTargetCombatLevel(100)
                .build();

        assertTrue(minLevel.shouldSpecTarget(100, 150, false));
        assertFalse(minLevel.shouldSpecTarget(100, 50, false));
    }

    @Test
    public void testSpecialAttackConfig_ShouldSpecTarget_SpecificNpcs() {
        SpecialAttackConfig specific = SpecialAttackConfig.builder()
                .enabled(true)
                .targetNpcIds(Set.of(1234, 5678))
                .build();

        assertTrue(specific.shouldSpecTarget(1234, 100, false));
        assertTrue(specific.shouldSpecTarget(5678, 100, false));
        assertFalse(specific.shouldSpecTarget(9999, 100, false));
    }

    @Test
    public void testSpecialAttackConfig_CalculateSpecCount_NoStack() {
        SpecialAttackConfig config = SpecialAttackConfig.builder()
                .enabled(true)
                .energyThreshold(50)
                .stackSpecs(false)
                .build();

        assertEquals(1, config.calculateSpecCount(100, 25));
        assertEquals(1, config.calculateSpecCount(50, 50));
        assertEquals(0, config.calculateSpecCount(49, 50)); // Below threshold
    }

    @Test
    public void testSpecialAttackConfig_CalculateSpecCount_WithStack() {
        SpecialAttackConfig config = SpecialAttackConfig.builder()
                .enabled(true)
                .energyThreshold(25)
                .stackSpecs(true)
                .maxStackCount(4)
                .build();

        assertEquals(4, config.calculateSpecCount(100, 25)); // 100/25 = 4
        assertEquals(2, config.calculateSpecCount(50, 25));  // 50/25 = 2
        assertEquals(2, config.calculateSpecCount(100, 50)); // Limited by energy
    }

    @Test
    public void testSpecialAttackConfig_CalculateSpecCount_LimitedByMaxStack() {
        SpecialAttackConfig config = SpecialAttackConfig.builder()
                .enabled(true)
                .energyThreshold(25)
                .stackSpecs(true)
                .maxStackCount(2)
                .build();

        assertEquals(2, config.calculateSpecCount(100, 25)); // Max is 2
    }

    // ========================================================================
    // SpecialAttackManager Tests
    // ========================================================================

    @Test
    public void testCheckAndUseSpec_DisabledWhenConfigDisabled() {
        specManager.setConfig(SpecialAttackConfig.builder()
                .enabled(false)
                .build());

        CombatAction action = specManager.checkAndUseSpec(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndUseSpec_NullWhenNoTarget() {
        when(combatState.hasTarget()).thenReturn(false);

        CombatAction action = specManager.checkAndUseSpec(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndUseSpec_NullWhenEnergyBelowThreshold() {
        when(combatState.hasTarget()).thenReturn(true);
        when(combatState.getSpecialAttackEnergy()).thenReturn(50); // Below 100% threshold

        CombatAction action = specManager.checkAndUseSpec(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndUseSpec_NullWhenNoSpecWeapon() {
        when(combatState.hasTarget()).thenReturn(true);
        when(combatState.getSpecialAttackEnergy()).thenReturn(100);
        when(equipmentState.getWeaponId()).thenReturn(ItemID.BRONZE_SWORD); // No spec

        specManager.setConfig(SpecialAttackConfig.builder()
                .enabled(true)
                .energyThreshold(100)
                .useSpecWeapon(false)
                .build());

        CombatAction action = specManager.checkAndUseSpec(0);

        assertNull(action);
    }

    @Test
    public void testCheckAndUseSpec_UsesEquippedSpecWeapon() {
        when(combatState.hasTarget()).thenReturn(true);
        when(combatState.getSpecialAttackEnergy()).thenReturn(100);
        when(combatState.getTargetNpc()).thenReturn(null);
        when(equipmentState.getWeaponId()).thenReturn(ItemID.DRAGON_DAGGER);

        specManager.setConfig(SpecialAttackConfig.builder()
                .enabled(true)
                .energyThreshold(25)
                .useSpecWeapon(false)
                .stackSpecs(false)
                .build());

        CombatAction action = specManager.checkAndUseSpec(0);

        assertNotNull(action);
        assertEquals(CombatAction.Type.SPECIAL_ATTACK, action.getType());
        assertEquals(CombatAction.Priority.HIGH, action.getPriority());
    }

    @Test
    public void testCheckAndUseSpec_SwitchesToSpecWeapon() {
        when(combatState.hasTarget()).thenReturn(true);
        when(combatState.getSpecialAttackEnergy()).thenReturn(100);
        when(combatState.getTargetNpc()).thenReturn(null);
        when(equipmentState.getWeaponId()).thenReturn(ItemID.ABYSSAL_WHIP);
        when(inventoryState.hasItem(ItemID.DRAGON_DAGGER)).thenReturn(true);
        when(inventoryState.getNonEmptyItems()).thenReturn(Collections.emptyList());

        specManager.setConfig(SpecialAttackConfig.builder()
                .enabled(true)
                .energyThreshold(25)
                .useSpecWeapon(true)
                .specWeaponItemId(ItemID.DRAGON_DAGGER)
                .build());

        CombatAction action = specManager.checkAndUseSpec(0);

        // Should be a gear switch first
        assertNotNull(action);
        assertEquals(CombatAction.Type.GEAR_SWITCH, action.getType());
    }

    @Test
    public void testGetSpecEnergyCost() {
        assertEquals(25, specManager.getSpecEnergyCost(ItemID.DRAGON_DAGGER));
        assertEquals(50, specManager.getSpecEnergyCost(ItemID.DRAGON_WARHAMMER));
        assertEquals(-1, specManager.getSpecEnergyCost(ItemID.BRONZE_SWORD));
    }

    @Test
    public void testHasSpecialAttack() {
        assertTrue(specManager.hasSpecialAttack(ItemID.DRAGON_DAGGER));
        assertTrue(specManager.hasSpecialAttack(ItemID.DRAGON_WARHAMMER));
        assertFalse(specManager.hasSpecialAttack(ItemID.BRONZE_SWORD));
    }

    @Test
    public void testGetCurrentEnergy() {
        when(combatState.getSpecialAttackEnergy()).thenReturn(75);

        assertEquals(75, specManager.getCurrentEnergy());
    }

    @Test
    public void testGetTicksUntilThreshold() {
        // 75% energy, 100% threshold = need 25% more
        // 1% per 10 ticks = 250 ticks
        when(combatState.getSpecialAttackEnergy()).thenReturn(75);

        assertEquals(250, specManager.getTicksUntilThreshold());
    }

    @Test
    public void testGetTicksUntilThreshold_AlreadyMet() {
        when(combatState.getSpecialAttackEnergy()).thenReturn(100);

        assertEquals(0, specManager.getTicksUntilThreshold());
    }

    @Test
    public void testIsSpecReady() {
        when(combatState.getSpecialAttackEnergy()).thenReturn(100);
        assertTrue(specManager.isSpecReady());

        when(combatState.getSpecialAttackEnergy()).thenReturn(50);
        assertFalse(specManager.isSpecReady());
    }

    @Test
    public void testIsSpecReady_CustomThreshold() {
        specManager.setConfig(SpecialAttackConfig.builder()
                .enabled(true)
                .energyThreshold(50)
                .build());

        when(combatState.getSpecialAttackEnergy()).thenReturn(50);
        assertTrue(specManager.isSpecReady());

        when(combatState.getSpecialAttackEnergy()).thenReturn(49);
        assertFalse(specManager.isSpecReady());
    }

    // ========================================================================
    // Reset Tests
    // ========================================================================

    @Test
    public void testReset() {
        specManager.onSpecUsed(100);
        
        specManager.reset();

        assertNull(specManager.getCurrentSpecWeapon());
        assertEquals(-1, specManager.getMainWeaponItemId());
        assertEquals(-1, specManager.getLastSpecTick());
        assertEquals(0, specManager.getSpecsUsedInSequence());
        assertFalse(specManager.isInSpecSequence());
        assertFalse(specManager.isPendingSwitchBack());
    }

    @Test
    public void testOnCombatEnd() {
        specManager.onSpecUsed(100);

        specManager.onCombatEnd();

        assertEquals(0, specManager.getSpecsUsedInSequence());
        assertFalse(specManager.isInSpecSequence());
        assertFalse(specManager.isPendingSwitchBack());
    }

    @Test
    public void testOnSpecUsed() {
        specManager.onSpecUsed(50);

        assertEquals(50, specManager.getLastSpecTick());
        assertEquals(1, specManager.getSpecsUsedInSequence());

        specManager.onSpecUsed(51);

        assertEquals(51, specManager.getLastSpecTick());
        assertEquals(2, specManager.getSpecsUsedInSequence());
    }

    // ========================================================================
    // WikiDataService Integration Tests
    // ========================================================================

    @Test
    public void testGetWeaponAttackSpeed_DefaultValue() {
        // When no wiki data available, should return default (4)
        assertEquals(WeaponInfo.SPEED_AVERAGE, specManager.getWeaponAttackSpeed(ItemID.BRONZE_SWORD));
    }

    @Test
    public void testGetWeaponAttackSpeed_FromWiki() {
        // Mock wiki service to return weapon info
        WeaponInfo whipInfo = WeaponInfo.builder()
                .itemId(ItemID.ABYSSAL_WHIP)
                .itemName("Abyssal whip")
                .attackSpeed(4)
                .hasSpecialAttack(true)
                .specialAttackCost(50)
                .build();
        when(wikiDataService.getWeaponInfoSync(eq(ItemID.ABYSSAL_WHIP), anyString()))
                .thenReturn(whipInfo);

        int speed = specManager.getWeaponAttackSpeed(ItemID.ABYSSAL_WHIP);

        assertEquals(4, speed);
    }

    @Test
    public void testGetWeaponInfo_CachesResult() {
        WeaponInfo ddsInfo = WeaponInfo.builder()
                .itemId(ItemID.DRAGON_DAGGER)
                .itemName("Dragon dagger")
                .attackSpeed(4)
                .hasSpecialAttack(true)
                .specialAttackCost(25)
                .build();
        when(wikiDataService.getWeaponInfoSync(eq(ItemID.DRAGON_DAGGER), anyString()))
                .thenReturn(ddsInfo);

        // First call should query wiki
        WeaponInfo first = specManager.getWeaponInfo(ItemID.DRAGON_DAGGER);
        // Second call should use cache
        WeaponInfo second = specManager.getWeaponInfo(ItemID.DRAGON_DAGGER);

        assertEquals(ddsInfo.itemId(), first.itemId());
        assertEquals(ddsInfo.itemId(), second.itemId());
        // Should only call wiki service once
        verify(wikiDataService, times(1)).getWeaponInfoSync(eq(ItemID.DRAGON_DAGGER), anyString());
    }

    @Test
    public void testClearWeaponInfoCache() {
        WeaponInfo ddsInfo = WeaponInfo.builder()
                .itemId(ItemID.DRAGON_DAGGER)
                .itemName("Dragon dagger")
                .attackSpeed(4)
                .hasSpecialAttack(true)
                .specialAttackCost(25)
                .build();
        when(wikiDataService.getWeaponInfoSync(eq(ItemID.DRAGON_DAGGER), anyString()))
                .thenReturn(ddsInfo);

        // Populate cache
        specManager.getWeaponInfo(ItemID.DRAGON_DAGGER);

        // Clear cache
        specManager.clearWeaponInfoCache();

        // Should query wiki again
        specManager.getWeaponInfo(ItemID.DRAGON_DAGGER);

        verify(wikiDataService, times(2)).getWeaponInfoSync(eq(ItemID.DRAGON_DAGGER), anyString());
    }

    @Test
    public void testPrefetchWeaponInfo() {
        when(wikiDataService.getWeaponInfo(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(WeaponInfo.EMPTY));

        specManager.prefetchWeaponInfo(ItemID.DRAGON_DAGGER, ItemID.ABYSSAL_WHIP);

        // Should trigger async fetch for both items
        verify(wikiDataService, times(1)).getWeaponInfo(eq(ItemID.DRAGON_DAGGER), anyString());
        verify(wikiDataService, times(1)).getWeaponInfo(eq(ItemID.ABYSSAL_WHIP), anyString());
    }
}

