package com.rocinante.quest;

import com.questhelper.helpers.quests.cooksassistant.CooksAssistant;
import com.questhelper.helpers.quests.monkeymadnessi.MonkeyMadnessI;
import com.questhelper.questhelpers.QuestHelper;
import com.rocinante.quest.steps.QuestStep;
import com.rocinante.quest.bridge.QuestHelperBridge;
import com.rocinante.quest.steps.ConditionalQuestStep;
import com.rocinante.quest.steps.NpcQuestStep;
import com.rocinante.quest.steps.ObjectQuestStep;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Fixture tests for QuestHelperBridge using real QuestHelper quest classes.
 * These tests validate that translation succeeds for actual quests.
 */
public class QuestHelperBridgeFixtureTest {

    @Test
    public void translateCooksAssistant() {
        CooksAssistant quest = new CooksAssistant();
        injectMockDependencies(quest);
        
        QuestHelperBridge bridge = new QuestHelperBridge(quest);

        Map<Integer, QuestStep> steps = bridge.loadAllSteps();
        assertFalse("Steps should not be empty", steps.isEmpty());
        steps.values().forEach(step -> assertNotNull("Step should be translated", step));
        System.out.println("Cook's Assistant step classes: " + 
            steps.values().stream().map(s -> s.getClass().getSimpleName()).toList());

        // Cook's Assistant uses ConditionalSteps at the top level with NPC/Object steps nested inside
        assertTrue("Expected at least one conditional step", hasInstance(steps, ConditionalQuestStep.class));
        
        // Check for NPC steps inside conditionals (Cook's Assistant wraps everything in conditionals)
        assertTrue("Expected at least one NPC step (may be nested in conditional)", 
            hasInstanceDeep(steps, NpcQuestStep.class));

        Quest questWrapper = bridge.toQuest();
        assertNotNull("Quest wrapper should be created", questWrapper);
        assertNotNull("Quest steps should load", questWrapper.loadSteps());
    }

    @Test
    public void translateMonkeyMadnessI() {
        MonkeyMadnessI quest = new MonkeyMadnessI();
        injectMockDependencies(quest);
        
        QuestHelperBridge bridge = new QuestHelperBridge(quest);

        Map<Integer, QuestStep> steps = bridge.loadAllSteps();
        assertFalse("Steps should not be empty", steps.isEmpty());
        steps.values().forEach(step -> assertNotNull("Step should be translated", step));
        System.out.println("Monkey Madness I step classes: " + 
            steps.values().stream().map(s -> s.getClass().getSimpleName()).toList());

        // Monkey Madness is complex - verify we got meaningful steps
        assertTrue("Expected multiple steps for complex quest", steps.size() > 1);
        
        // Check for conditional steps (MM1 uses them heavily)
        assertTrue("Expected at least one conditional step", 
            hasInstance(steps, ConditionalQuestStep.class) || hasInstanceDeep(steps, ConditionalQuestStep.class));

        Quest questWrapper = bridge.toQuest();
        assertNotNull("Quest wrapper should be created", questWrapper);
        assertNotNull("Quest steps should load", questWrapper.loadSteps());
    }

    /**
     * Inject mock RuneLite dependencies into a QuestHelper instance.
     * QuestHelper quests need Client, ConfigManager, and EventBus to initialize properly.
     */
    private void injectMockDependencies(QuestHelper quest) {
        try {
            // Mock the required RuneLite services
            Client mockClient = mock(Client.class);
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            EventBus mockEventBus = mock(EventBus.class);

            // Inject into the quest via reflection (fields are protected/private in QuestHelper)
            setField(quest, "client", mockClient);
            setField(quest, "configManager", mockConfigManager);
            setField(quest, "eventBus", mockEventBus);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock dependencies", e);
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        if (field != null) {
            field.setAccessible(true);
            field.set(target, value);
        }
    }

    private Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static boolean hasInstance(Map<Integer, QuestStep> steps, Class<?> clazz) {
        return steps.values().stream().anyMatch(step -> clazz.isAssignableFrom(step.getClass()));
    }

    private static boolean hasInstanceDeep(Map<Integer, QuestStep> steps, Class<?> clazz) {
        for (QuestStep step : steps.values()) {
            if (clazz.isAssignableFrom(step.getClass())) {
                return true;
            }
            if (step instanceof ConditionalQuestStep) {
                ConditionalQuestStep conditional = (ConditionalQuestStep) step;
                for (ConditionalQuestStep.Branch branch : conditional.getBranches()) {
                    if (clazz.isAssignableFrom(branch.getStep().getClass())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
