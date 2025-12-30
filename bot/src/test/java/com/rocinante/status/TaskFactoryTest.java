package com.rocinante.status;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rocinante.combat.CombatManager;
import com.rocinante.combat.TargetSelector;
import com.rocinante.progression.TrainingMethodRepository;
import com.rocinante.quest.Quest;
import com.rocinante.quest.QuestExecutor;
import com.rocinante.quest.QuestService;
import com.rocinante.tasks.Task;
import com.rocinante.tasks.impl.CombatTask;
import com.rocinante.tasks.impl.SkillTask;
import com.rocinante.tasks.impl.WalkToTask;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class TaskFactoryTest {

    private TrainingMethodRepository repository;
    private TaskFactory factory;

    @Mock
    private TargetSelector targetSelector;

    @Mock
    private CombatManager combatManager;

    @Mock
    private QuestExecutor questExecutor;

    @Mock
    private QuestService questService;

    @Mock
    private Quest quest;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        repository = new TrainingMethodRepository();
        factory = new TaskFactory(repository);
        factory.setTargetSelector(targetSelector);
        factory.setCombatManager(combatManager);
        factory.setQuestExecutor(questExecutor);
        factory.setQuestService(questService);

        when(quest.getName()).thenReturn("Test Quest");
        when(quest.getId()).thenReturn("test_quest");
        when(questService.getQuestById(anyString())).thenReturn(quest);
    }

    @Test
    public void createsSkillTaskFromJson() {
        JsonObject spec = new JsonObject();
        spec.addProperty("taskType", "SKILL");
        spec.addProperty("methodId", "iron_ore_powermine");
        spec.addProperty("targetType", "LEVEL");
        spec.addProperty("targetValue", 20);
        spec.addProperty("bankInsteadOfDrop", true);

        Optional<Task> task = factory.createTask(spec);

        assertTrue(task.isPresent());
        assertTrue(task.get() instanceof SkillTask);
        assertEquals("iron_ore_powermine", ((SkillTask) task.get()).getConfig().getMethod().getId());
    }

    @Test
    public void createsCombatTaskFromJson() {
        JsonObject spec = new JsonObject();
        spec.addProperty("taskType", "COMBAT");
        JsonArray names = new JsonArray();
        names.add("Chicken");
        spec.add("targetNpcs", names);
        spec.addProperty("completionType", "KILL_COUNT");
        spec.addProperty("completionValue", 2);
        spec.addProperty("useSafeSpot", false);
        spec.addProperty("lootEnabled", false);

        Optional<Task> task = factory.createTask(spec);

        assertTrue(task.isPresent());
        assertTrue(task.get() instanceof CombatTask);
    }

    @Test
    public void createsNavigationTaskFromJson() {
        JsonObject spec = new JsonObject();
        spec.addProperty("taskType", "NAVIGATION");
        spec.addProperty("locationId", "lumbridge_castle");
        spec.addProperty("description", "Walk to Lumbridge");

        Optional<Task> task = factory.createTask(spec);

        assertTrue(task.isPresent());
        assertTrue(task.get() instanceof WalkToTask);
        assertEquals("Walk to Lumbridge", ((WalkToTask) task.get()).getDescription());
    }

    @Test
    public void createsQuestTaskFromJson() {
        JsonObject spec = new JsonObject();
        spec.addProperty("taskType", "QUEST");
        spec.addProperty("questId", "TEST_QUEST");

        Optional<Task> task = factory.createTask(spec);

        assertTrue(task.isPresent());
        assertEquals("Start quest: Test Quest", task.get().getDescription());
    }

    @Test
    public void missingQuestIdReturnsEmpty() {
        JsonObject spec = new JsonObject();
        spec.addProperty("taskType", "QUEST");

        Optional<Task> task = factory.createTask(spec);

        assertFalse(task.isPresent());
    }

    @Test
    public void invalidMethodReturnsEmpty() {
        JsonObject spec = new JsonObject();
        spec.addProperty("taskType", "SKILL");
        spec.addProperty("methodId", "does_not_exist");
        spec.addProperty("targetType", "LEVEL");
        spec.addProperty("targetValue", 10);

        Optional<Task> task = factory.createTask(spec);

        assertFalse(task.isPresent());
    }
}


