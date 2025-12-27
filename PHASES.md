# Implementation Phases

## Phase 0: Webserver and Docker Runtime
**Goal**: A management pane should exist that successfully runs the bot container via Docker.

## Phase 1: Core Foundation
**Goal**: Bot can perform basic humanized clicks and read game state.

- [X] `RobotMouseController` - Bezier curves, click variance, idle behavior
- [X] `RobotKeyboardController` - Typing simulation, hotkeys
- [X] `HumanTimer` - Gaussian/Poisson delays, named profiles
- [X] `GameStateService` - Tick-based state polling and caching
- [X] `PlayerState`, `InventoryState`, `EquipmentState` - Basic state tracking

**Test**: Click objects in-game with humanized movement. Verify timing distributions match spec.

---

## Phase 2: Task System & Navigation
**Goal**: Bot can execute task sequences and walk anywhere.

- [X] `Task` interface, `TaskContext`, `TaskExecutor`
- [X] `CompositeTask`, `ConditionalTask` - Task composition
- [X] Basic tasks: `WalkToTask`, `InteractObjectTask`, `InteractNpcTask`, `WaitForConditionTask`
- [X] `PathFinder` - A* on collision maps
- [X] `WebWalker` - Long-distance navigation via web graph
- [X] `ObstacleHandler` - Doors, gates
- [X] `data/web.json` - Initial navigation graph (major cities/banks)

**Test**: Queue "walk to Varrock bank, open bank, close bank" - executes with humanized behavior.

---

## Phase 3: Combat System
**Goal**: Bot can fight, eat, pray, and survive.

- [X] `WorldState` - NPCs, objects, ground items, projectiles
- [X] `CombatState` - Target, incoming attacks, poison/venom tracking
- [X] `CombatManager` - Main combat loop
- [X] `TargetSelector` - Enemy prioritization (selection priority, avoidance rules)
- [X] `CombatTask` - Full combat automation with phases (find target, position, attack, monitor, loot)
- [X] `GearSwitcher` - Equipment swaps with humanized delays
- [X] `EquipItemTask` - Equip gear sets by style or explicit items
- [X] `GearSet` - Immutable equipment configuration with auto-detection
- [X] `AttackStyle` - Melee/Ranged/Magic style configuration
- [X] `WeaponStyle` - Damage type (Slash/Stab/Crush/etc.) with XP goal compatibility
- [X] `XpGoal` - Training goal selection (Attack/Strength/Defence/etc.)
- [X] Spell system - `CombatSpell`, `StandardSpell`, `AncientSpell`, `Spellbook`
- [X] `CombatQuestStep` - Quest step integration with all combat features
- [X] `FoodManager` - Eat thresholds, combo eating
- [X] `PrayerFlicker` - Protection prayer switching
- [X] `SpecialAttackManager` - Spec weapon usage

**Test**: Kill cows/goblins for 30 minutes without dying. Verify eat timing, prayer switching.

---

## Phase 4: Integrations (Quest Helper + Wiki + AI)
**Goal**: Bot can read Quest Helper state and query external data.

- [X] `QuestHelperBridge` - Plugin detection, state extraction
- [X] Quest step → Task mapping (NpcStep, ObjectStep, etc.)
- [X] `QuestState` - Varbit tracking, quest progress (`Quest`, `QuestProgress`, `QuestExecutor`)
- [X] `VarbitCondition`, `ZoneCondition` - Quest requirement conditions
- [X] `TutorialIsland` - Completed as custom quest implementation
- [X] `BankState` - Player's bank state for quest items/planning/etc
- [ ] `WikiDataService` - OSRS Wiki API client
- [ ] `WikiCacheManager` - Response caching
- [ ] Drop table, item source, shop inventory parsing
- [ ] `ClaudeAPIClient` - Anthropic API integration
- [ ] `AIDirector` - Decision layer for ambiguous situations

**Test**: Select a simple quest in Quest Helper, bot executes first 5 steps automatically.

---

## Phase 5: Behavioral Anti-Detection
**Goal**: Bot behavior is statistically indistinguishable from human.

- [X] `FatigueModel` - Session fatigue accumulation/effects
- [X] `BreakScheduler` - Micro/short/long breaks
- [X] `AttentionModel` - Focus states, distraction simulation
- [X] `PlayerProfile` - Per-account persistent fingerprints
- [X] `BotActivityTracker` - Activity type awareness, account type detection, HCIM safety checks
- [X] `EmergencyHandler` - Poison/health emergency conditions with task interruption
- [X] Session rituals, idle behaviors, XP checking patterns
- [X] Long-term behavioral drift (via PlayerProfile session drift)
- [ ] Action sequence randomization
- [ ] Camera-mouse coupling
- [ ] Intentional inefficiency injection
- [ ] Logout behavior humanization

**Test**: Record 2-hour session, run statistical analysis. No detectable patterns vs human baseline.

---

## Phase 6: Slayer System
**Goal**: Bot can complete slayer tasks autonomously.

- [ ] `SlayerState` - Task tracking, points, unlocks
- [ ] `SlayerManager` - Task lifecycle
- [ ] `TaskLocationResolver` - Find where to kill assignments
- [ ] `SlayerMasterData` - Master locations and task weights
- [ ] Special requirements handling (items, gear, mechanics)
- [ ] Cannon integration (optional)
- [ ] Block/skip/extend preferences

**Test**: Complete 10 consecutive slayer tasks across 3 different masters.

---

## Phase 7: Progression Planning
**Goal**: Bot can plan and execute long-term account goals.

- [ ] `AccountGoalPlanner` - Goal types and dependencies
- [ ] `SkillPlanner` - Training method selection
- [ ] `QuestOrderPlanner` - Optimal quest ordering
- [ ] `GearProgressionPlanner` - Equipment upgrade paths
- [X] `UnlockTracker` - Teleports, areas, features (integrated with WebWalker)
- [ ] `TeleportTask` with behavioral profile preferences
- [ ] `InventoryTask`
- [X] `BankTask`, `DialogueTask`, `WidgetInteractTask`

**Test**: Give goal "complete Recipe for Disaster" - bot generates valid plan and begins execution.

---

## Phase 8: Ironman & HCIM Support
**Goal**: Full ironman support with HCIM death prevention.

- [ ] `IronmanState` - Account type detection
- [ ] `IronmanRestrictions` - GE/trade blocking
- [ ] `SelfSufficiencyPlanner` - Item acquisition without GE
- [ ] `ResourceGatheringPlanner` - Supply chain planning
- [ ] `DropTableAnalyzer` - Monster farming optimization
- [ ] `ShopRunPlanner` - NPC shop management
- [ ] `HCIMSafetyManager` - Pre-activity checks, runtime monitoring
- [ ] Ring of Life enforcement
- [ ] Emergency escape protocols
- [ ] Death probability scoring
- [ ] Multi-combat detection
- [ ] World type restrictions (no PvP/Deadman)

**Test**: 100 hours HCIM operation with zero preventable deaths.

---

## Definition of Done

Each phase complete when:
1. All listed components implemented
2. Unit tests pass (>80% coverage on new code)
3. Integration test for phase goal passes
4. No regressions in previous phases

