package com.rocinante.behavior.emergencies;

import com.rocinante.state.PlayerState;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeathEmergency.
 * Tests detection of death scenarios and triggering conditions.
 */
public class DeathEmergencyTest {

    private static final int DEATHS_OFFICE_REGION = 12106;
    private static final int DEATH_OFFICE_INTERFACE_ID = 669;
    private static final int DEATH_COFFER_INTERFACE_ID = 670;
    private static final int DIALOGUE_NPC_HEAD_GROUP = 231;

    @Mock
    private TaskContext taskContext;

    @Mock
    private PlayerState playerState;

    @Mock
    private Client client;

    @Mock
    private Task mockResponseTask;

    @Mock
    private Widget deathOfficeWidget;

    @Mock
    private Widget cofferWidget;

    @Mock
    private Widget dialogueWidget;

    @Mock
    private Widget npcNameWidget;

    private DeathEmergency.ResponseTaskFactory mockFactory;
    private DeathEmergency emergency;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        when(taskContext.getClient()).thenReturn(client);
        when(taskContext.getPlayerState()).thenReturn(playerState);

        // Default: not in Death's Office
        when(playerState.getWorldPosition()).thenReturn(new WorldPoint(3200, 3200, 0));

        // Default: no interfaces visible
        when(client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0)).thenReturn(null);
        when(client.getWidget(DEATH_COFFER_INTERFACE_ID, 0)).thenReturn(null);
        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 0)).thenReturn(null);
        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 4)).thenReturn(null);

        mockFactory = ctx -> mockResponseTask;
        emergency = new DeathEmergency(mockFactory);
    }

    // ========================================================================
    // Trigger Tests - Death's Office Region
    // ========================================================================

    @Test
    public void testIsTriggered_InDeathsOfficeRegion_ReturnsTrue() {
        // Player is in Death's Office region
        WorldPoint deathsOfficePos = mock(WorldPoint.class);
        when(deathsOfficePos.getRegionID()).thenReturn(DEATHS_OFFICE_REGION);
        when(playerState.getWorldPosition()).thenReturn(deathsOfficePos);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_NotInDeathsOfficeRegion_ReturnsFalse() {
        // Player at Lumbridge (different region)
        WorldPoint lumbridgePos = mock(WorldPoint.class);
        when(lumbridgePos.getRegionID()).thenReturn(12850); // Lumbridge region
        when(playerState.getWorldPosition()).thenReturn(lumbridgePos);

        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_NullPosition_ReturnsFalse() {
        when(playerState.getWorldPosition()).thenReturn(null);

        assertFalse(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Trigger Tests - Death's Office Interface
    // ========================================================================

    @Test
    public void testIsTriggered_DeathOfficeInterfaceOpen_ReturnsTrue() {
        when(client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0)).thenReturn(deathOfficeWidget);
        when(deathOfficeWidget.isHidden()).thenReturn(false);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_DeathOfficeInterfaceHidden_ReturnsFalse() {
        when(client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0)).thenReturn(deathOfficeWidget);
        when(deathOfficeWidget.isHidden()).thenReturn(true);

        assertFalse(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Trigger Tests - Death's Coffer Interface
    // ========================================================================

    @Test
    public void testIsTriggered_DeathCofferInterfaceOpen_ReturnsTrue() {
        when(client.getWidget(DEATH_COFFER_INTERFACE_ID, 0)).thenReturn(cofferWidget);
        when(cofferWidget.isHidden()).thenReturn(false);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_DeathCofferInterfaceHidden_ReturnsFalse() {
        when(client.getWidget(DEATH_COFFER_INTERFACE_ID, 0)).thenReturn(cofferWidget);
        when(cofferWidget.isHidden()).thenReturn(true);

        assertFalse(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Trigger Tests - Death NPC Dialogue
    // ========================================================================

    @Test
    public void testIsTriggered_DeathDialogueOpen_ReturnsTrue() {
        // NPC dialogue widget is visible
        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 0)).thenReturn(dialogueWidget);
        when(dialogueWidget.isHidden()).thenReturn(false);

        // NPC name is "Death"
        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 4)).thenReturn(npcNameWidget);
        when(npcNameWidget.getText()).thenReturn("Death");

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_OtherNpcDialogueOpen_ReturnsFalse() {
        // NPC dialogue widget is visible
        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 0)).thenReturn(dialogueWidget);
        when(dialogueWidget.isHidden()).thenReturn(false);

        // NPC name is NOT "Death"
        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 4)).thenReturn(npcNameWidget);
        when(npcNameWidget.getText()).thenReturn("Hans");

        assertFalse(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_DeathDialogueCaseInsensitive_ReturnsTrue() {
        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 0)).thenReturn(dialogueWidget);
        when(dialogueWidget.isHidden()).thenReturn(false);

        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 4)).thenReturn(npcNameWidget);
        when(npcNameWidget.getText()).thenReturn("DEATH");

        assertTrue(emergency.isTriggered(taskContext));
    }

    // ========================================================================
    // Response Task Tests
    // ========================================================================

    @Test
    public void testCreateResponseTask_ReturnsFactoryTask() {
        Task result = emergency.createResponseTask(taskContext);

        assertEquals(mockResponseTask, result);
    }

    @Test(expected = NullPointerException.class)
    public void testCreateResponseTask_NullFactory_ThrowsNPE() {
        // Factory is a required dependency - null factory should fail fast
        DeathEmergency noFactoryEmergency = new DeathEmergency(null);
        noFactoryEmergency.createResponseTask(taskContext);
    }

    // ========================================================================
    // Metadata Tests
    // ========================================================================

    @Test
    public void testGetId() {
        assertEquals("DEATH_EMERGENCY", emergency.getId());
    }

    @Test
    public void testGetDescription() {
        String description = emergency.getDescription();
        
        assertNotNull(description);
        assertTrue(description.toLowerCase().contains("died") || 
                   description.toLowerCase().contains("death") ||
                   description.toLowerCase().contains("recover"));
    }

    @Test
    public void testGetSeverity_HighPriority() {
        // Death recovery is high priority but below immediate health emergencies
        int severity = emergency.getSeverity();
        
        assertTrue(severity >= 70);
        assertTrue(severity < 100);
    }

    @Test
    public void testGetCooldownMs_ReasonableValue() {
        long cooldown = emergency.getCooldownMs();
        
        // Should have a reasonable cooldown (death handling takes time)
        assertTrue(cooldown >= 10000); // At least 10 seconds
        assertTrue(cooldown <= 60000); // No more than 1 minute
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    public void testIsTriggered_MultipleConditionsTrue_ReturnsTrue() {
        // Both in region AND interface open
        WorldPoint deathsOfficePos = mock(WorldPoint.class);
        when(deathsOfficePos.getRegionID()).thenReturn(DEATHS_OFFICE_REGION);
        when(playerState.getWorldPosition()).thenReturn(deathsOfficePos);

        when(client.getWidget(DEATH_OFFICE_INTERFACE_ID, 0)).thenReturn(deathOfficeWidget);
        when(deathOfficeWidget.isHidden()).thenReturn(false);

        assertTrue(emergency.isTriggered(taskContext));
    }

    @Test
    public void testIsTriggered_NpcNameNull_ReturnsFalse() {
        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 0)).thenReturn(dialogueWidget);
        when(dialogueWidget.isHidden()).thenReturn(false);

        when(client.getWidget(DIALOGUE_NPC_HEAD_GROUP, 4)).thenReturn(npcNameWidget);
        when(npcNameWidget.getText()).thenReturn(null);

        assertFalse(emergency.isTriggered(taskContext));
    }
}
