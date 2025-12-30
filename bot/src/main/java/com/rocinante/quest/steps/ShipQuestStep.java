package com.rocinante.quest.steps;

import com.rocinante.tasks.Task;
import com.rocinante.tasks.TaskContext;
import com.rocinante.tasks.impl.InteractObjectTask;
import com.rocinante.tasks.impl.WalkToTask;
import com.rocinante.quest.steps.WaitQuestStep;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Quest step for boarding/sailing via gangplanks or similar ship objects.
 *
 * <p>This keeps the travel logic explicit (walk → board) and avoids treating ship
 * travel as a generic teleport. Sailing skill content remains handled elsewhere.</p>
 */
@Getter
@Accessors(chain = true)
public class ShipQuestStep extends QuestStep {

    private final List<Integer> objectIds = new ArrayList<>();
    private WorldPoint targetLocation;
    private String menuAction = "Board";
    private int searchRadius = 15;

    public ShipQuestStep(List<Integer> objectIds, WorldPoint targetLocation, String text) {
        super(text);
        if (objectIds != null) {
            this.objectIds.addAll(objectIds);
        }
        this.targetLocation = targetLocation;
    }

    public ShipQuestStep withMenuAction(String action) {
        if (action != null && !action.isEmpty()) {
            this.menuAction = action;
        }
        return this;
    }

    public ShipQuestStep withSearchRadius(int radius) {
        this.searchRadius = radius;
        return this;
    }

    @Override
    public StepType getType() {
        return StepType.CUSTOM;
    }

    @Override
    public WorldPoint getTargetLocation() {
        return targetLocation;
    }

    @Override
    public List<Task> toTasks(TaskContext ctx) {
        List<Task> tasks = new ArrayList<>();

        if (targetLocation != null) {
            tasks.add(new WalkToTask(targetLocation)
                    .withDescription("Walk to ship: " + getText()));
        }

        if (!objectIds.isEmpty()) {
            InteractObjectTask board = new InteractObjectTask(objectIds.get(0), menuAction)
                    .withAlternateIds(objectIds.subList(1, objectIds.size()))
                    .withSearchRadius(searchRadius)
                    .withDescription(getText())
                    .withWaitForIdle(true);
            tasks.add(board);
        } else {
            // No object id available; rely on location and wait for the cutscene/transition.
            tasks.addAll(new WaitQuestStep(getText()).toTasks(ctx));
        }

        return tasks;
    }

    public List<Integer> getObjectIds() {
        return Collections.unmodifiableList(objectIds);
    }
}

