# AGENTS.md

Agent guide for the Rocinante project - an OSRS automation system.

## Project Structure

```
rocinante/
├── bot/                    # Java RuneLite plugin (Gradle, Java 17, Lombok)
│   └── src/main/java/com/rocinante/
│       ├── core/           # Plugin entry point, GameStateService
│       ├── tasks/          # Task system (THE HEART OF THE BOT)
│       ├── navigation/     # Pathfinding, collision, entity finding
│       ├── state/          # Immutable state snapshots
│       ├── input/          # Mouse/keyboard/camera controllers
│       ├── behavior/       # Humanization, breaks, profiles
│       ├── combat/         # Combat loop, prayer, gear switching
│       ├── quest/          # Quest automation, Quest Helper bridge
│       ├── progression/    # Training methods, unlock tracking
│       └── ...
│
└── web/                    # Management UI (Bun, SolidJS, TailwindCSS v4)
    └── src/
        ├── api/            # Bun HTTP server, Docker control
        └── client/         # SolidJS frontend components
```

## Core Philosophy: Building Blocks

**Everything is composed from reusable building blocks.** Tasks are the fundamental unit of work. You either:

1. **Build low-level** - Create atomic, reusable tasks/services that can be composed
2. **Build high-level** - Compose existing tasks/services to accomplish complex goals

### Example Composition Chain

```
SkillTask (train woodcutting to level 50)
  └── InteractObjectTask (click on tree)
        ├── NavigationService.findNearestReachableObject()
        │     ├── EntityFinder (scan scene for objects)
        │     ├── CollisionService (validate adjacency)
        │     └── PathCostEstimator (rank by path cost)
        ├── InteractionHelper.getClickPointForObject()
        └── SafeClickExecutor.clickObject()
```

### DRY Principles

- **Never duplicate logic** - If it exists, use it. If it should exist, create it at the lowest appropriate level.
- **Services centralize domain logic** - `NavigationService`, `GameStateService`, `QuestService`
- **Tasks are composable** - Use `CompositeTask.sequential()`, `.parallel()`, `.loop()`
- **Collections centralize IDs** - `ItemCollections`, `ObjectCollections`, `NpcCollections` for ID variants

---

## Task System

### Inheritance Hierarchy

```
Task (interface)
  └── AbstractTask
        ├── AbstractInteractionTask
        │     ├── InteractObjectTask
        │     └── InteractNpcTask
        ├── CompositeTask
        └── [All other tasks]
```

### Core Task Classes

| Class | Purpose |
|-------|---------|
| `Task` | Interface defining `execute()`, `canExecute()`, lifecycle hooks |
| `AbstractTask` | State machine, timeouts, retries, phase tracking |
| `AbstractInteractionTask` | Obstacle detection, camera rotation helpers |
| `CompositeTask` | Compose tasks: `sequential()`, `parallel()`, `loop()` |
| `TaskContext` | Access to all services and state during execution |
| `TaskExecutor` | Priority queue, retry policy, urgent interrupts |

### Task Lifecycle

```
PENDING → RUNNING → COMPLETED
              ↓
           FAILED (→ retry with backoff)
```

Tasks implement `executeImpl(TaskContext ctx)` - called once per game tick while RUNNING.

---

## Existing Tasks

### Atomic/Low-Level Tasks (`tasks/impl/`)

| Task | Description |
|------|-------------|
| `WalkToTask` | Navigate to destination using ShortestPath |
| `InteractObjectTask` | Click game objects (trees, rocks, banks) |
| `InteractNpcTask` | Click NPCs (fishing spots, shopkeepers) |
| `DialogueTask` | Handle NPC dialogue, select options |
| `BankTask` | Open bank, deposit/withdraw items |
| `DropInventoryTask` | Drop items (power training) |
| `PickupItemTask` | Pick up ground items |
| `EquipItemTask` / `UnequipItemTask` | Manage equipment |
| `WidgetInteractTask` | Click UI widgets (spells, prayers) |
| `UseItemOnObjectTask` | Use inventory item on object |
| `UseItemOnItemTask` | Combine inventory items |
| `UseItemOnNpcTask` | Use item on NPC |
| `WaitForConditionTask` | Wait until condition is true |
| `EmoteTask` | Perform emotes |
| `DigTask` | Use spade to dig |

### High-Level/Orchestrator Tasks

| Task | Description |
|------|-------------|
| `SkillTask` | Train any skill (delegates to appropriate sub-tasks) |
| `CombatTask` | Sustained combat with target selection, looting |
| `TravelTask` | All travel methods (teleports, transport) |
| `ResupplyTask` | Bank trip: deposit, withdraw, return |
| `ProcessItemTask` | Item-on-item processing (fletching, herblore) |
| `GrandExchangeTask` | Buy/sell on GE |
| `ShopPurchaseTask` | Buy from NPC shops |
| `TradeTask` | Player-to-player trading |
| `PuzzleTask` | Solve puzzles (sliding, light boxes) |

### Travel Sub-Tasks (`tasks/impl/travel/`)

| Task | Description |
|------|-------------|
| `FairyRingTask` | Navigate fairy ring network |
| `SpiritTreeTask` | Use spirit trees |
| `GnomeGliderTask` | Gnome glider transport |
| `CanoeTask` | River canoe system |
| `CharterShipTask` | Charter ship routes |
| `GroupingTeleportTask` | Minigame teleports |
| `QuetzalTask` | Varlamore quetzal transport |

### Skill-Specific Tasks (`tasks/impl/skills/`)

Organized by skill: `agility/`, `cooking/`, `firemaking/`, `fletching/`, `prayer/`, `slayer/`, `thieving/`

Examples:
- `AgilityCourseTask` - Run agility courses
- `CookingSkillTask` - Cook food on fires/ranges
- `FiremakingSkillTask` - Burn logs
- `GatherAndCookTask` - Fish then cook (combo training)
- `ThievingSkillTask` - Pickpocket NPCs or steal from stalls

---

## Key Services

### GameStateService (`core/`)
Single source of truth for all game state. Polls client once per tick, provides immutable snapshots.

```java
// Access via TaskContext
PlayerState player = ctx.getPlayerState();
InventoryState inv = ctx.getInventoryState();
WorldState world = ctx.getWorldState();
```

### NavigationService (`navigation/`)
Centralized navigation API. **Always use this instead of direct pathfinding.**

```java
NavigationService nav = ctx.getNavigationService();

// Find reachable objects (respects collision)
Optional<ObjectSearchResult> tree = nav.findNearestReachableObject(
    ctx, playerPos, Set.of(TREE_ID), searchRadius);

// Check if blocked by fence/wall
boolean blocked = nav.isBlocked(position);

// Request path (async)
nav.requestPath(ctx, start, destination);
```

### TaskContext
Injected into every task's `execute()` method. Provides access to everything:

- **State**: `getPlayerState()`, `getInventoryState()`, `getEquipmentState()`, `getWorldState()`, `getCombatState()`, `getBankState()`
- **Input**: `getMouseController()`, `getKeyboardController()`, `getCameraController()`
- **Services**: `getNavigationService()`, `getQuestService()`, `getInventoryPreparation()`
- **Helpers**: `getSafeClickExecutor()`, `getMenuHelper()`, `getWidgetClickHelper()`
- **Variables**: `setVariable()`, `getVariable()` - pass data between tasks

---

## State Management

All state classes are **immutable snapshots** (using Lombok `@Value`):

| State Class | Contents |
|-------------|----------|
| `PlayerState` | Position, animation, HP, prayer, run energy, skill levels |
| `InventoryState` | 28 slots, helper methods for item queries |
| `EquipmentState` | Equipped items by slot |
| `WorldState` | Nearby NPCs, objects, ground items, projectiles |
| `CombatState` | Target info, spec energy, poison status |
| `BankState` | Bank contents (persisted across sessions) |
| `SlayerState` | Current task, points, unlocks |

---

## Creating New Tasks

### 1. Extend AbstractTask

```java
@Slf4j
public class MyTask extends AbstractTask {
    
    @Override
    public boolean canExecute(TaskContext ctx) {
        return ctx.isLoggedIn();
    }
    
    @Override
    protected void executeImpl(TaskContext ctx) {
        // Called once per game tick while RUNNING
        // Use phase-based execution for multi-step tasks
        
        if (isDone) {
            complete();  // Transitions to COMPLETED
        }
        
        if (somethingWentWrong) {
            fail("Reason");  // Transitions to FAILED
        }
    }
    
    @Override
    public String getDescription() {
        return "My custom task";
    }
}
```

### 2. Use Phase-Based Execution

```java
private enum Phase { INIT, WALK, INTERACT, WAIT, DONE }
private Phase phase = Phase.INIT;

@Override
protected void executeImpl(TaskContext ctx) {
    switch (phase) {
        case INIT:
            // Setup
            phase = Phase.WALK;
            break;
        case WALK:
            if (activeSubTask == null) {
                activeSubTask = new WalkToTask(destination);
            }
            executeSubTask(ctx);
            break;
        // ...
    }
}

private void executeSubTask(TaskContext ctx) {
    activeSubTask.execute(ctx);
    if (activeSubTask.getState().isTerminal()) {
        if (activeSubTask.getState() == TaskState.COMPLETED) {
            phase = nextPhase;
        } else {
            fail("Sub-task failed");
        }
        activeSubTask = null;
    }
}
```

### 3. Compose Existing Tasks

```java
// Sequential execution
CompositeTask sequence = CompositeTask.sequential(
    new WalkToTask(bankLocation),
    BankTask.depositAll(),
    new WalkToTask(trainingSpot)
);

// Loop until condition
CompositeTask trainingLoop = CompositeTask.loop(
    new InteractObjectTask(TREE_ID, "Chop down"),
    new WaitForConditionTask(ctx -> !ctx.getPlayerState().isAnimating())
).untilCondition(ctx -> ctx.getInventoryState().isFull());
```

---

## Important Patterns

### Delegate to Services, Not Client

```java
// BAD: Direct client access
Tile[][][] tiles = client.getScene().getTiles();
// scan for objects manually...

// GOOD: Use NavigationService
Optional<ObjectSearchResult> result = 
    ctx.getNavigationService().findNearestReachableObject(ctx, pos, objectIds, radius);
```

### Check Terminal State

```java
if (activeSubTask.getState().isTerminal()) {
    // COMPLETED or FAILED - handle accordingly
}
```

### Use Builder Pattern

```java
InteractObjectTask task = new InteractObjectTask(TREE_ID, "Chop down")
    .withSearchRadius(15)
    .withSuccessAnimation(WOODCUTTING_ANIM)
    .withWaitForIdle(true);
```

### Handle Async Operations

```java
if (clickPending) return;  // Wait for async click to complete

clickPending = true;
ctx.getMouseController().click()
    .thenRun(() -> {
        clickPending = false;
        phase = Phase.WAIT_RESPONSE;
    })
    .exceptionally(e -> {
        clickPending = false;
        fail("Click failed");
        return null;
    });
```

---

## ID Constants

**Always use RuneLite constants** instead of hardcoded IDs:

```java
// GOOD
import net.runelite.api.ItemID;
import net.runelite.api.ObjectID;
import net.runelite.api.NpcID;

int axe = ItemID.BRONZE_AXE;
int booth = ObjectID.BANK_BOOTH_10355;

// BAD - hardcoded magic numbers
int axe = 1351;
```

For IDs not in RuneLite constants, check `/tmp/runelite/runelite-api/src/main/java/net/runelite/api/gameval/`.

---

## Web UI (Brief)

- **Framework**: SolidJS + TanStack Router + TanStack Query
- **Styling**: TailwindCSS v4
- **Server**: Bun with native Bun HTTP server and websocket for VNC/status updates
- **Purpose**: Create/manage bot containers, view VNC streams, monitor status, queue tasks

Key files:
- `web/src/api/server.ts` - HTTP endpoints
- `web/src/client/router.tsx` - SolidJS routes
- `web/src/client/components/` - UI components
