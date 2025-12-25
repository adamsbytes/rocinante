# RuneLite Automation Framework — Requirements Specification

## 1. Project Overview

### 1.1 Purpose
A modular, extensible RuneLite plugin framework for human-like game automation. The system must be indistinguishable from human input patterns and support arbitrarily complex task sequences including full quest completion, combat, slayer, and long-term account progression.

The framework supports multiple account types:
- **Normal accounts**: Full feature set including Grand Exchange integration.
- **Ironman accounts**: Self-sufficient gameplay with GE disabled, shop runs, and drop farming.
- **Hardcore Ironman (HCIM)**: Enhanced safety protocols to prevent permadeath, including Ring of Life requirements, elevated eat/flee thresholds, and risky content blocking.

### 1.2 Design Philosophy
- **Modularity over monoliths**: Every system (input, timing, tasks, state) must be independently swappable and testable.
- **Leverage existing plugins**: Integrate with RuneLite's Quest Helper and other plugins rather than reinventing definitions.
- **AI-driven decision making**: Use Claude API for complex interpretation and planning; deterministic code for execution.
- **Fail-safe defaults**: Any missing config or unexpected state results in safe idling, never erratic behavior.

### 1.3 Target Environment
- **Primary Platform**: Containerized (Docker) Linux (headless via Xvfb virtual display)
- RuneLite client (latest stable)
- Java 17 LTS (with AWT/Robot support)
- Quest Helper plugin (pre-installed via Docker configuration)
- Display requirements: Xvfb with minimum 1024x768 resolution (GPU optional but supported)

**Required JVM Arguments for JDK 16+**: For AWT Robot functionality on modern JDK:
```
--add-opens=java.desktop/sun.awt=ALL-UNNAMED
```
For macOS (also include):
```
--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED
```
These arguments must be added to both the Dockerfile and any local development run configurations.

---

## 2. Architecture

**Deployment Model**: Single RuneLite plugin running in one JVM process. All automation logic executes within the plugin using RuneLite's Guice dependency injection. Mouse and keyboard control via `java.awt.Robot` (compatible with Linux headless + Xvfb).

### 2.1 Core Modules

```
com.rocinante/
├── core/
│   ├── RocinantePlugin.java      # Main plugin entry, lifecycle management
│   │                              # Annotated with @PluginDependency(QuestHelperPlugin.class)
│   ├── TaskExecutor.java           # Task queue and execution engine
│   └── GameStateService.java       # Centralized game state polling
├── input/
│   ├── RobotMouseController.java   # Humanized mouse movement via java.awt.Robot
│   ├── RobotKeyboardController.java # Humanized keyboard input via java.awt.Robot
│   └── InputProfile.java           # Per-session input characteristic generation
├── timing/
│   ├── HumanTimer.java             # Gaussian/Poisson delay generation
│   ├── FatigueModel.java           # Session-aware fatigue curves
│   ├── BreakScheduler.java         # Micro/macro break scheduling
│   └── AttentionModel.java         # Simulated focus drift and recovery
├── tasks/
│   ├── Task.java                   # Base task interface
│   ├── TaskContext.java            # Shared state passed to tasks
│   ├── CompositeTask.java          # Sequential/parallel task grouping
│   ├── ConditionalTask.java        # Branching based on game state
│   └── impl/                       # Concrete task implementations
│       ├── InteractObjectTask.java
│       ├── InteractNpcTask.java
│       ├── WalkToTask.java
│       ├── WaitForConditionTask.java
│       ├── DialogueTask.java
│       ├── InventoryTask.java
│       ├── BankTask.java
│       ├── WidgetInteractTask.java
│       ├── CombatTask.java
│       ├── PrayerTask.java
│       ├── EquipmentTask.java
│       └── TeleportTask.java
├── state/
│   ├── PlayerState.java            # Position, animation, health, etc.
│   ├── InventoryState.java         # Item tracking, slot management
│   ├── EquipmentState.java         # Worn gear tracking
│   ├── WorldState.java             # Nearby objects, NPCs, ground items
│   ├── QuestState.java             # Quest varbit/varp tracking
│   ├── CombatState.java            # Combat-specific state tracking
│   ├── SlayerState.java            # Slayer task tracking
│   ├── IronmanState.java           # Account type and ironman-specific tracking
│   └── StateCondition.java         # Composable state predicates
├── navigation/
│   ├── PathFinder.java             # Tile-based pathfinding (A* or JPS)
│   ├── WebWalker.java              # Long-distance pathing via nav mesh/web
│   └── ObstacleHandler.java        # Doors, gates, agility shortcuts
├── integration/
│   ├── QuestHelperBridge.java      # Interface to Quest Helper plugin
│   ├── ClaudeAPIClient.java        # Claude API communication
│   └── AIDirector.java             # AI-driven decision layer
├── combat/
│   ├── CombatManager.java          # Combat loop orchestration
│   ├── PrayerFlicker.java          # Prayer switching logic
│   ├── GearSwitcher.java           # Equipment swapping
│   ├── FoodManager.java            # Eating logic and thresholds
│   ├── SpecialAttackManager.java   # Special attack usage
│   └── TargetSelector.java         # Enemy prioritization
├── slayer/
│   ├── SlayerManager.java          # Slayer task lifecycle
│   ├── SlayerMasterData.java       # Master locations and task weights
│   └── TaskLocationResolver.java   # Finding where to kill assignments
├── progression/
│   ├── AccountGoalPlanner.java     # Long-term goal management
│   ├── SkillPlanner.java           # Skill training path optimization
│   ├── QuestOrderPlanner.java      # Quest completion ordering
│   ├── GearProgressionPlanner.java # Equipment upgrade paths
│   └── UnlockTracker.java          # Track unlocks (teleports, areas, etc.)
├── ironman/
│   ├── IronmanRestrictions.java    # Validates actions against ironman restrictions
│   ├── IronmanState.java           # Tracks ironman account type and status
│   ├── SelfSufficiencyPlanner.java # Plans item acquisition without GE
│   ├── ResourceGatheringPlanner.java # Plans raw material collection chains
│   ├── DropTableAnalyzer.java      # Optimizes monster farming for drops
│   ├── ShopRunPlanner.java         # NPC shop stock management and runs
│   └── HCIMSafetyManager.java      # Enhanced safety for hardcore accounts
├── data/
│   ├── WikiDataService.java        # OSRS Wiki API client for dynamic data
│   ├── WikiCacheManager.java       # Cache layer for wiki API responses
│   ├── TeleportData.java           # Available teleport methods (static enum)
│   └── NavigationData.java         # Custom navigation web (locations.json, web.json)
├── config/
│   ├── RocinanteConfig.java        # RuneLite config interface
│   └── ProfileConfig.java          # JSON-loaded behavior profiles
└── util/
    ├── Randomization.java          # Statistical distribution utilities
    ├── Calculations.java           # Geometry, distance, tile math
    └── Logger.java                 # Debug/audit logging
```

### 2.2 Dependency Injection
Use Guice (RuneLite's DI framework) for all module wiring. No static singletons. Every service must be injectable and mockable.

**Scoping Requirements**:
- **Services and managers**: Annotate with `@Singleton` (GameStateService, RobotMouseController, CombatManager, etc.)
- **Task implementations**: Do NOT use `@Singleton` (created fresh per execution)
- **Configuration**: Provided via `@Provides` method, not singleton
- **Stateful components**: Use `@Singleton` only if shared state is required
- Reference RuneLite plugin development documentation for implementation patterns

### 2.3 Event Architecture
- Subscribe to RuneLite events via `@Subscribe` annotations.
- Internal events use a lightweight pub/sub bus for decoupled module communication.
- All state changes flow through GameStateService; tasks never poll the client directly.

**Event Subscription Priorities**:
RuneLite processes events in priority order. Use explicit priorities when execution order matters:

```java
// State tracking - run AFTER game state updates (low priority)
@Subscribe(priority = -10)
public void onGameTick(GameTick event) {
    gameStateService.updateState();
}

// Combat reactions - run EARLY for responsive prayer switching (high priority)
@Subscribe(priority = 10)
public void onProjectileMoved(ProjectileMoved event) {
    prayerFlicker.reactToProjectile(event);
}

// Default priority (0) - most event handlers
@Subscribe
public void onNpcSpawned(NpcSpawned event) {
    worldState.trackNpc(event.getNpc());
}
```

**Priority Guidelines**:
- Combat-critical reactions: `priority = 10` to `20`
- State updates and caching: `priority = -10` to `-20` (run after other plugins)
- Most automation logic: default priority `0`

---

## 3. Input System

### 3.1 RobotMouseController

**Implementation**: Uses `java.awt.Robot` for cross-platform mouse control. Compatible with Linux headless environments (Xvfb).

#### 3.1.1 Movement Algorithm
Implement bezier curve-based mouse movement with the following characteristics:

- **Curve generation**: Control point count varies by distance:
  - Short movements (< 200px): 3 control points
  - Medium movements (200-500px): 4 control points  
  - Long movements (> 500px): 5 control points
  - Control points are offset perpendicular to the direct path by a random distance (5-15% of total distance, reduced from 25% to avoid erratic movement).
- **Speed profile**: Movement follows a sigmoid velocity curve—slow start (0-15% of path), fast middle (15-85%), slow end (85-100%).
- **Noise injection**: Add Perlin noise to the path with amplitude of 1-3 pixels, sampled every 5-10ms of movement time.
- **Overshoot simulation**: 8-15% of movements overshoot the target by 3-12 pixels, followed by a correction movement after 50-150ms.
- **Micro-correction**: 20% of movements end with a small 1-3 pixel adjustment after 100-200ms to simulate final positioning.
- **Duration calculation**: Base duration = `sqrt(distance) * 10 + gaussianRandom(50, 150)` milliseconds. Minimum 80ms, maximum 1500ms.

#### 3.1.2 Click Behavior

- **Position variance**: Never click the geometric center. Use 2D gaussian distribution centered at 45-55% of the hitbox width/height, with σ = 15% of dimension.
- **Click timing**: 
  - Mouse-down to mouse-up duration: 60-120ms (gaussian, μ=85ms, σ=15ms).
  - Double-click interval (when applicable): 80-180ms.
- **Misclick simulation**: 
  - 1-3% of clicks miss the target hitbox by 5-20 pixels.
  - Misclicks are followed by a correction click after 200-500ms.
- **Click fatigue**: After 200+ clicks in a session, increase click position variance by 10-30%.

#### 3.1.3 Idle Behavior
When awaiting the next action:
- 70% of the time: Mouse remains stationary.
- 20% of the time: Small drift (5-30 pixels) in a random direction over 500-2000ms.
- 10% of the time: Mouse moves to a "rest position" (inventory area, minimap, or chat) via natural path.

### 3.2 RobotKeyboardController

**Implementation**: Uses `java.awt.Robot` for keyboard input simulation.

#### 3.2.1 Typing Simulation
- **Inter-key delay**: 50-150ms base, adjusted per character pair (common bigrams like "th", "er" are 20% faster).
- **Typo simulation**: 0.5-2% of characters are mistyped, followed by backspace correction after 100-300ms.
- **Burst patterns**: Occasional fast bursts (3-5 chars at 30-50ms) followed by longer pauses (200-400ms).

#### 3.2.2 Hotkey Usage
- **Reaction time**: Hotkey presses have 150-400ms delay from triggering condition.
- **Key hold duration**: 40-100ms.
- **F-key patterns**: Humans develop muscle memory; once an F-key is used, subsequent uses in the same session are 15-30% faster.

### 3.3 Input Profile Generation
At session start, generate an `InputProfile` with randomized characteristics:
- Base mouse speed multiplier (0.8-1.3)
- Click variance modifier (0.7-1.4)
- Preferred idle positions (2-4 screen regions)
- Typing speed WPM (40-80 range, fixed for session)
- Dominant hand simulation (slight bias toward right-side screen interactions)

Persist profile across sessions with ±10% drift per session to simulate natural skill variation.

### 3.4 Behavioral Fingerprinting Avoidance

Beyond input humanization, implement behavioral randomization to prevent detection through statistical analysis of action patterns.

#### 3.4.1 Session-Based Behavioral Profiles
Generate a unique behavioral "personality" per account that persists across sessions with gradual drift:

**Behavioral Traits**:
- **Camera preference**: Preferred camera angles (e.g., compass angle 0-360°, pitch high/medium/low).
- **Action ordering preference**: When multiple valid action sequences exist, weight toward specific patterns (e.g., bank before inventory, or vice versa).
- **Rest position favorites**: 2-3 "comfort zones" where mouse tends to idle (e.g., always near minimap vs. always near inventory).
- **Break timing personality**: Early breaker (breaks at 70% fatigue) vs. late breaker (breaks at 90% fatigue).
- **Risk tolerance**: Conservative (flee early) vs. aggressive (maximize DPS before fleeing).

**Implementation**: `BehavioralProfile` class tracks all traits above with per-account seeded RNG for consistency. Key methods:
- `applySessionDrift()` - Adjusts all traits by ±5-10% each session (simulates habit evolution)
- `getSequenceWeight(ActionSequence)` - Returns weighted preference for action ordering
- `getIdleAction()` - Selects idle behavior based on weighted preferences

#### 3.4.2 Action Sequence Randomization
When multiple valid execution paths exist, randomize selection to avoid scripted patterns:

**Examples**:
- **Banking**: Randomly choose between (deposit all → withdraw) vs. (deposit specific → deposit all → withdraw).
- **Quest steps**: If two objectives can be completed in any order, randomize which comes first.
- **Combat preparation**: Randomize order of (equip gear → eat food → activate prayer) vs. (activate prayer → equip gear → eat food).

**Weighting**:
- Use behavioral profile weights (60% weight to preferred sequence, 40% to alternatives).
- Re-weight after each execution (slight preference reinforcement, max 80/20 split).

#### 3.4.3 Camera Behavior Coupling
Humans correlate camera movement with mouse movement. Current spec only humanizes mouse; add camera coupling:

**Camera Movement Triggers**:
- **During long mouse movements** (>500px): 30% chance to adjust camera angle by 5-20° in same general direction.
- **When clicking off-screen objects**: Rotate camera toward object before clicking (70% of the time).
- **Idle camera drift**: During breaks or waiting, slowly rotate camera 1-3° per 2-5 seconds (20% chance per interval).
- **Manual camera checks**: Periodically (every 2-5 minutes), rotate 360° to "look around" (5% chance per minute).

#### 3.4.4 Intentional Inefficiency Injection
Perfect efficiency is inhuman. Inject 5-10% suboptimal actions:

**Inefficiency Types**:
- **Misclicks** (already in 3.1.2): 1-3% miss target, correct after delay.
- **Backtracking**: 2% of walks, walk 1-2 tiles past destination, then return.
- **Redundant actions**: 3% of bank trips, open/close bank twice before actual transaction.
- **Hesitation**: 5% of actions, hover over target for 500-1500ms before clicking (simulating decision-making).
- **Action cancellation**: 1% of queued actions, cancel and re-queue after 1-3 second pause.

**XP Rate Throttling** (optional config):
- Calculate theoretical maximum XP/hour for current activity.
- Inject delays to cap at 80-95% of maximum (configurable).
- Vary percentage per session (simulate "good days" and "bad days").

#### 3.4.5 Logout Pattern Humanization
Instant logout after dangerous events = bot flag. Add human-like logout behavior:

**Logout Timing**:
- **After combat escape**: Wait 2-15 seconds (catching breath, checking stats) before logout.
- **After level-up**: Wait 5-20 seconds (reading level-up text, checking new unlocks).
- **At break time**: Don't logout immediately at fatigue threshold; 20% chance to continue 1-5 more actions.
- **Random logout probability**: 0.1% chance per minute during safe activities (simulating IRL interruptions).

**Logout Actions**:
- 60%: Direct logout button click.
- 30%: Press ESC → click logout.
- 10%: Click logout, wait 1-2 sec, close game window (simulating alt-tab away).

#### 3.4.6 Long-Term Behavioral Drift
Simulate skill improvement and habit changes over weeks/months:

**Drift Mechanisms**:
- **Mouse speed improvement**: Every 20 hours of playtime, increase base speed multiplier by 1-3% (cap at 1.3).
- **Click precision improvement**: Every 50 hours, reduce click variance by 2-5% (cap at minimum 0.7).
- **Break pattern evolution**: Every 10 sessions, adjust break frequency ±5 minutes, break duration ±20 seconds.
- **Sequence preference evolution**: Gradually shift action sequence weights (max 5% per 10 hours toward new preferences).

**Persistence**:
- Store behavioral profile in `~/.runelite/rocinante/profiles/{username}.profile`.
- Update after each session with drift calculations.
- On new account, generate fresh profile from account name hash seed (deterministic but unique).

### 3.5 Play Session Behavioral Patterns

Create per-account "play fingerprints" that simulate realistic human gaming habits. These patterns persist across sessions with gradual drift to simulate evolving player behavior.

#### 3.5.1 Session Start Rituals
Generate 2-4 account-specific actions performed at session start (80% probability to execute):

**Common Rituals**:
- **Bank check**: Open bank, scan tabs (2-6 second pause), close bank.
- **Skill tab inspection**: Open skills tab, check specific skills (combat, slayer, or training goal), close after 1-3 seconds.
- **Friend list check**: Open friends list, scan for online friends, close.
- **Equipment review**: Open equipment tab, hover over worn items, close.
- **Inventory organization**: Rearrange inventory items into preferred pattern.

**Implementation**: Each account randomly selects 2-4 rituals weighted by account "personality" (e.g., efficiency-focused accounts skip rituals 30% of time).

#### 3.5.2 Mid-Session Habits
Periodic actions during gameplay to simulate human curiosity and boredom:

**Right-Click Player Inspection**:
- Frequency: 0-5 times per hour (per-account constant ±20% session variance).
- Target selection: 60% nearby players, 30% high-level players, 10% low-level players.
- Inspection duration: 0.5-2 seconds (hover on examine).

**XP Checking Patterns**:
- Frequency: 0-15 times per hour (skill-dependent: training skill checked 3x more often).
- Method: 70% hover skill orb, 25% open skills tab, 5% use XP tracker overlay.
- Duration: 0.3-1.5 seconds per check.

**World Hopping Behavior**:
- Trigger: 5% chance per 30 minutes during non-combat activities.
- Target world: Prefer low-population worlds (weight: 60%), random worlds (40%).
- Pre-hop actions: 30% chance to check current world population via world selector.

#### 3.5.3 Camera Angle Fingerprinting
Each account develops persistent camera preferences:

**Camera Profile**:
- **Preferred compass angle**: Base angle (0-360°) with ±45° tolerance zone.
- **Preferred pitch**: High (90-70°), Medium (70-40°), Low (40-10°) - weighted choice per account.
- **Angle change frequency**: Conservative (changes <5/hour) vs. Active (changes >15/hour).
- **Zoom preference**: Always zoomed out (90%), occasionally zoom in for precision (10%).

**Session Variance**: 
- Apply ±10° drift to preferred angle each session.
- 20% chance to temporarily adopt different pitch for specific activities (e.g., low pitch for PvM).

#### 3.5.4 Idle Behavior Taxonomy
When no task is queued, select idle action from weighted distribution:

**Idle Action Types**:
- **Stationary**: Mouse remains still (weight: 40%).
- **Skill tab cycling**: Open skills tab, scan through tabs, close (weight: 15%).
- **Equipment inspection**: Hover over worn equipment (weight: 10%).
- **Chat scrolling**: Scroll through game/public chat (weight: 10%).
- **Minimap dragging**: Drag minimap view around current area (weight: 10%).
- **Mouse drift**: Small random movements 5-30px (weight: 10%).
- **Camera rotation**: Slow 360° pan (weight: 5%).

**Weighting**: Per-account idle action weights set at profile creation, drift ±5% per 20 hours played.

#### 3.5.5 Action Sequencing Fingerprints
When multiple valid action orders exist, each account has persistent preferences:

**Banking Sequences**:
- **Type A** (60% of accounts): Check supplies → Deposit all → Withdraw specific items → Close bank.
- **Type B** (30% of accounts): Deposit specific → Deposit all → Withdraw items → Close bank.
- **Type C** (10% of accounts): Withdraw first → Deposit all → Re-withdraw → Close bank.

**Combat Preparation Sequences**:
- **Type A**: Equip gear → Eat food → Activate prayers → Engage.
- **Type B**: Activate prayers → Equip gear → Eat food → Engage.
- **Type C**: Eat food → Equip gear → Activate prayers → Engage.

**Preference Reinforcement**: Each execution of preferred sequence increases its weight by 0.5% (max 85% for any single sequence).

### 3.6 Behavioral Profile Persistence

Store and maintain per-account behavioral profiles to ensure consistent, unique "play fingerprints" across all sessions.

**Storage Location**: `~/.runelite/rocinante/profiles/{account_hash}.json`

**Profile File Contents** (full schema in `data/profile.schema.json`):
- `schema_version`, `account_hash`, timestamps, playtime metrics
- `behavioral_weights`: Action sequence preferences (banking A/B/C), idle action weights
- `camera_preferences`: Compass angle, pitch preference, zoom level
- `session_patterns`: Start rituals, player inspection frequency, XP check frequency
- `teleport_preferences`: Method weights (fairy_ring, spellbook, jewelry, house_portal, tablets), law_rune_aversion
- `skill_metrics`: Mouse speed multiplier, click precision, break patterns
- `world_preferences`: Primary/alternate worlds, type preference, population preference
- `session_history`: Recent session summaries for drift calculation

**Teleport Preferences**:
- `preferred_method_weights`: Weighted preferences for teleport method selection
  - `fairy_ring`: Preference for fairy ring network (law rune-free)
  - `spellbook`: Standard/ancient/lunar spellbook teleports (consumes law runes)
  - `jewelry`: Ring of dueling, games necklace, etc. (consumable charges)
  - `house_portal`: Player-owned house teleports (law rune-free if tabs)
  - `teleport_tablet`: One-use teleport tablets (law rune-free)
- `law_rune_aversion`: Multiplier reducing law rune teleport selection (0.0 = no aversion, 1.0 = max aversion)
- `allow_consumable_teleports`: Whether to consume jewelry charges/tablets
- **Account-specific defaults**:
  - **HCIM**: `fairy_ring: 0.70, house_portal: 0.20, teleport_tablet: 0.10` (law_rune_aversion: 1.0)
    - ALWAYS prefer law rune-free methods - law runes require runecrafting or dangerous shop runs
  - **Ironman**: `fairy_ring: 0.50, spellbook: 0.25, house_portal: 0.15, teleport_tablet: 0.10` (law_rune_aversion: 0.6)
    - Bias toward free methods but can use spellbook teleports when law runes available
  - **Normal**: Random weighted distribution (law_rune_aversion: 0.0-0.3, randomized per account)
    - Full freedom - some accounts prefer jewelry, others prefer spellbook, others prefer fairy rings

**World Preferences**:
- `primary_world`: Preferred world number (e.g., 302 for US skill total world)
- `alternate_worlds`: List of fallback worlds if primary is full or unavailable
- `world_type_preference`: Preferred world type (`SKILL_TOTAL`, `F2P`, `MEMBERS`, `PVP`, etc.)
- `population_preference`: Target population range (`LOW` 0-100, `MEDIUM` 100-300, `HIGH` 300+)
- Used during login and world hopping to maintain consistent world selection patterns
- Respects HCIM safety restrictions (no PvP/Deadman worlds when `IronmanState.isHardcore()` returns true)

**Update Frequency**: After each session (on logout or plugin shutdown)

**Schema Versioning**: Include `schema_version` field for migration compatibility when profile format evolves

**Encryption** (Optional): 
- AES-256 encryption with config-provided key
- Protects behavioral fingerprint data from external analysis
- Config option: `encryptBehavioralProfiles` (default: false)

**Profile Generation**:
- On first use for account, generate profile from `SHA256(username + random_salt)` as seed
- Ensures deterministic but unique starting point per account
- Apply initial randomization within defined ranges
- Begin tracking metrics from session 1

**Drift Application**:
- After every session: apply drift calculations to all metrics (see Section 3.4.6)
- Store drift history for last 100 sessions (enables long-term trend analysis)
- Periodically reset outlier values to prevent extreme drift (every 50 hours)

**Corruption Recovery** (robustness for persistence layer):
- **Schema validation**: On profile load, validate JSON against `profile.schema.json`
- **Automatic backup**: Before each profile update, copy current file to `{account_hash}.json.bak`
- **Recovery order**:
  1. Load primary profile → validate schema
  2. If validation fails → load backup → validate schema
  3. If backup validation fails → regenerate fresh profile from account seed
  4. Log all recovery actions with severity WARNING
- **Corruption causes**: Disk full during write, process kill during save, manual file tampering
- **Integrity check**: Include SHA256 checksum of profile contents; verify on load

---

## 4. Timing System

### 4.1 HumanTimer

#### 4.1.1 Distribution Types
Implement and support:
- **Gaussian**: For most delays. Configurable μ and σ.
- **Poisson**: For event-triggered actions (reaction time).
- **Uniform**: For bounded randomization.
- **Exponential**: For break duration and rare events.

#### 4.1.2 Standard Delays
Define named delay profiles:

| Profile | Distribution | Parameters | Use Case |
|---------|--------------|------------|----------|
| `REACTION` | Poisson | λ=250ms, min=150ms, max=600ms | Responding to game events |
| `ACTION_GAP` | Gaussian | μ=800ms, σ=200ms, min=400ms, max=2000ms | Between routine actions |
| `MENU_SELECT` | Gaussian | μ=180ms, σ=50ms | Choosing menu option after right-click |
| `DIALOGUE_READ` | Gaussian | μ=1200ms + 50ms/word, σ=300ms | Reading NPC dialogue |
| `INVENTORY_SCAN` | Gaussian | μ=400ms, σ=100ms | Finding item in inventory |
| `BANK_SEARCH` | Gaussian | μ=600ms, σ=150ms | Locating item in bank |
| `PRAYER_SWITCH` | Gaussian | μ=80ms, σ=20ms, min=50ms | Prayer flicking |
| `GEAR_SWITCH` | Gaussian | μ=120ms, σ=30ms | Equipment swaps |

### 4.2 Fatigue Model

#### 4.2.1 Fatigue Accumulation
Track a `fatigueLevel` float (0.0 = fresh, 1.0 = exhausted):
- Increment by 0.0005 per action performed.
- Increment by 0.00002 per second of active session time.
- Decrement by 0.1 per minute of break time.
- Decrement by 0.3 at session start (simulating return from AFK).

#### 4.2.2 Fatigue Effects
Apply fatigue as multipliers:
- Delay multiplier: `1.0 + (fatigueLevel * 0.5)` — up to 50% slower when exhausted.
- Click variance multiplier: `1.0 + (fatigueLevel * 0.4)` — less precise when tired.
- Misclick probability multiplier: `1.0 + (fatigueLevel * 2.0)` — up to 3x more misclicks.

### 4.3 Break Scheduler

#### 4.3.1 Break Types

| Type | Duration | Trigger | During Break |
|------|----------|---------|--------------|
| Micro-pause | 2-8 seconds | Every 30-90 actions (30% chance) | Mouse stationary or small drift |
| Short break | 30-180 seconds | Every 15-40 minutes (60% chance) | Mouse moves to idle region, may tab out |
| Long break | 5-20 minutes | Every 60-120 minutes (80% chance) | Simulate AFK; optional logout |
| Session end | N/A | After 2-6 hours | Logout and stop plugin |

#### 4.3.2 Break Behavior
- Short and long breaks may include: opening random tabs (skills, quest log), moving camera idly, typing partial messages then deleting.
- Break timing inherits exponential distribution to avoid predictable patterns.

### 4.4 Attention Model

#### 4.4.1 Focus States
Model player attention as a state machine:
- **Focused** (70% of active time): Normal delays, high precision.
- **Distracted** (25% of active time): 1.3x delays, occasionally miss game events (process with 200-800ms extra lag).
- **AFK** (5% of active time, excluding breaks): 3-15 second unresponsive periods.

Transitions between states occur every 30-180 seconds with weighted probabilities.

#### 4.4.2 External Distraction Simulation
Simulate random attention lapses: 2-15 second pauses, 2-6 times per hour. Triggered by: game chat messages (30% chance) or random intervals (exponential distribution).

---

## 5. Task System

### 5.1 Task Interface

```
interface Task {
    TaskState getState();                    // PENDING, RUNNING, COMPLETED, FAILED
    boolean canExecute(TaskContext ctx);     // Pre-conditions check
    void execute(TaskContext ctx);           // Perform one tick of work
    void onComplete(TaskContext ctx);        // Cleanup/transition logic
    void onFail(TaskContext ctx, Exception e); // Error handling
    String getDescription();                 // For logging/debugging
    Duration getTimeout();                   // Max execution time before stuck detection
}
```

**Global Task Timeout** (robustness for stuck detection):
- **Default timeout**: 60 seconds for all task types (configurable per-task)
- **Timeout behavior**: On timeout expiry, task transitions to FAILED state
- **Stuck detection callback**: `TaskExecutor.onTaskStuck(Task, Duration)` invoked before failure
- **Recovery strategy**: Automatic retry with exponential backoff (1s → 2s → 4s, max 3 retries)
- **Override for special cases**: Some tasks (e.g., long walks, combat) may specify longer timeouts
- **Monitoring**: Track timeout frequency per task type for debugging chronic stuck conditions

### 5.2 Task Context
`TaskContext` provides:
- Read-only access to `PlayerState`, `InventoryState`, `WorldState`, `QuestState`, `CombatState`, `SlayerState`.
- `RobotMouseController` and `RobotKeyboardController` references.
- `HumanTimer` for delay scheduling.
- Mutable `taskVariables` map for passing data between subtasks.
- `abortTask()` method for immediate task termination.

### 5.3 Composite Tasks
`CompositeTask` supports:
- **Sequential execution**: Child tasks run in order; failure aborts sequence.
- **Parallel execution**: Child tasks run concurrently; configurable failure policy (fail-fast, fail-silent, require-all).
- **Loop execution**: Repeat child sequence N times or until condition.
- **Conditional branching**: Execute different subtasks based on `StateCondition`.

### 5.4 Standard Task Implementations

#### 5.4.1 InteractObjectTask
- Locate object by ID within configurable radius (default: 15 tiles).
- Pathfind and walk to object if not in interaction range.
- Rotate camera toward object (optional, configurable probability).
- Hover object briefly (100-400ms) before clicking.
- Support for specific menu actions (first option, "Chop", "Mine", etc.).
- Wait for player animation or position change as success confirmation.

#### 5.4.2 InteractNpcTask
- All of `InteractObjectTask` behaviors.
- Additional NPC tracking during movement (update click target if NPC moves).
- Support dialogue initiation vs. direct action (e.g., "Talk-to" vs. "Pickpocket").

#### 5.4.3 WalkToTask
- Accept destination as: tile coordinate, object ID (walk to nearest), NPC ID, or named location.
- Use `WebWalker` for distances > 30 tiles, direct pathfinding otherwise.
- Incorporate humanized path deviations: occasional 1-2 tile detours (10% of walks).
- Click ahead on minimap for long walks; click in viewport for short walks.
- Handle run energy: enable run above 40% energy, disable below 15%.

#### 5.4.4 WaitForConditionTask
- Block task queue until `StateCondition` evaluates true.
- Configurable timeout (default: 30 seconds).
- Configurable poll interval (default: 1 game tick).
- Idle mouse behavior while waiting (per `RobotMouseController` idle spec).

#### 5.4.5 DialogueTask
- Detect dialogue widget visibility.
- Read dialogue text; select response based on keyword matching or option index.
- Humanized reading delays (see `DIALOGUE_READ` timing).
- Support for multi-step dialogues as sequential clicks with reading pauses.
- Handle "Click to continue" vs. numbered options differently.

#### 5.4.6 InventoryTask
- Operations: use item, drop item, equip item, combine items.
- Find item by ID or by name pattern.
- Humanized inventory scanning: don't jump instantly to correct slot.
- For dropping: support shift-click dropping with humanized multi-drop patterns (occasional pause between drops).

#### 5.4.7 BankTask
- Open nearest bank (NPC or booth).
- Operations: deposit all, deposit specific items, withdraw specific items (quantity: 1, 5, 10, X, All).
- Navigate bank tabs with humanized tab-click patterns.
- Use bank search when > 50 items visible.
- Placeholder configuration support.

#### 5.4.8 WidgetInteractTask
- Interact with arbitrary widgets by ID.
- Support click, hover, drag operations.
- Required for: prayer switching, spellbook, equipment screen, quest interfaces.

#### 5.4.9 CombatTask
- Attack specified NPC or nearest attackable NPC matching criteria.
- Integrate with `CombatManager` for ongoing combat loop.
- Support for safe-spotting: maintain distance while attacking.
- Loot drops based on configurable value threshold or item whitelist.

#### 5.4.10 PrayerTask
- Enable/disable specific prayers.
- Support quick-prayer toggle.
- Integrate with `PrayerFlicker` for tick-perfect switching.

#### 5.4.11 EquipmentTask
- Equip item from inventory.
- Unequip item to inventory.
- Support gear set switching (define named sets, swap all at once).

#### 5.4.12 TeleportTask
- Teleport via: spellbook, jewelry, tablets, POH portals, fairy rings, spirit trees.
- Select appropriate method based on availability, destination, AND behavioral profile `teleport_preferences`.
- Apply account-specific law rune aversion (HCIM strongly avoids, ironman moderately avoids, normal accounts randomized).
- Weight selection by `preferred_method_weights` from behavioral profile for account uniqueness.
- Handle interfaces (fairy ring code entry, spirit tree selection).

### 5.5 Task Queue Management
- FIFO queue with priority support (URGENT, NORMAL, LOW).
- URGENT interrupts current task (for: combat reactions, death handling).
- Failed tasks trigger configurable retry policy: immediate retry, exponential backoff, or abort.
- Idle task injected when queue empty (camera movement, skill tab checks, etc.).

---

## 6. State Management

### 6.1 GameStateService
Single source of truth for all game state queries. Polls client once per game tick and caches values. Modules never call `Client` directly.

#### 6.1.1 Caching Strategy
To minimize expensive client API calls and improve performance, implement intelligent state caching:

**Cache Requirements**:

Implement intelligent state caching to minimize expensive client API calls:
- Cache state values per game tick with policy-based invalidation
- Use `@Subscribe(priority = -10)` for state updates (run after other plugins)
- Invalidate caches on relevant events (ItemContainerChanged, NpcSpawned, etc.)

**Cache Policies**:
| Policy | Invalidation | Use Case |
|--------|--------------|----------|
| `TICK_CACHED` | Every game tick | Player position, animation, combat state |
| `EVENT_INVALIDATED` | On specific event only | Inventory (invalidate on ItemContainerChanged) |
| `EXPENSIVE_COMPUTED` | 5 ticks or on event | Nearby NPCs/objects scan |
| `SESSION_CACHED` | Never (until logout) | Unlocked teleports, completed quests |
| `LAZY_COMPUTED` | On first access | Pathfinding results, wiki API data |

**Performance Targets**: State queries <1ms when cached, full tick update <5ms, cache footprint <10MB, cache hit rate >90%.

### 6.2 State Components

#### 6.2.1 PlayerState
- Current tile position (world and local coordinates).
- Current animation ID.
- Is moving (boolean).
- Is interacting (boolean, includes combat).
- Current hitpoints, prayer, run energy.
- Combat state (in combat, target NPC ID).
- Skull status, poison status, venom status.
- Current spellbook.

#### 6.2.2 InventoryState
- Full 28-slot inventory representation.
- Item ID and quantity per slot.
- Helper methods: `hasItem(id)`, `countItem(id)`, `getFreeSlots()`, `isFull()`.
- Food detection: `getFoodSlots()`, `getBestFood()`.

#### 6.2.3 EquipmentState
- All 11 equipment slots tracked.
- Helper methods: `getWeapon()`, `getAmmoCount()`, `hasEquipped(id)`.
- Gear set comparison for switching.

#### 6.2.4 WorldState
- Nearby game objects (within 20 tiles).
- Nearby NPCs (within 20 tiles), including health bars.
- Nearby players (within 20 tiles).
- Ground items (within 15 tiles).
- Currently visible widgets.
- Projectiles and graphics objects (for prayer flicking).

#### 6.2.5 QuestState
- Varp and Varbit reading utilities.
- Quest completion status for all quests.
- Current quest step (derived from varbits).
- Helper: `getQuestState(questId)` returns enum (NOT_STARTED, IN_PROGRESS, COMPLETED).

#### 6.2.6 CombatState
- Currently targeted NPC (if any).
- Incoming attack detection (animation/projectile).
- Attack style and speed of current weapon.
- Special attack energy percentage.
- Current boosted/drained combat stats.
- **Poison/Venom tracking**:
  - `isPoisoned()` / `isVenomed()` status
  - `currentVenomDamage`: Current venom damage level (6, 8, 10... up to 20)
  - `lastVenomTick`: Game tick of last venom hit (for predicting next)
  - `predictNextVenomDamage()`: Returns expected next damage value
- **Multi-combat NPC aggro tracking**:
  - `getAggressiveNPCs()`: List of NPCs currently targeting player (not just in multi area)
  - `getAggressorCount()`: Count of NPCs currently in combat with player
  - `getNPCAttackCooldowns()`: Map of NPC → ticks until next attack
  - HCIM auto-flee threshold: `aggressorCount >= 2` in any area

#### 6.2.7 SlayerState
- Current slayer task creature.
- Remaining kill count.
- Current slayer master.
- Slayer points.
- Unlocked slayer rewards/extensions.

#### 6.2.8 IronmanState
- Account type: `NORMAL`, `IRONMAN`, `HARDCORE_IRONMAN`, `GROUP_IRONMAN`.
- HCIM death count (0 = still hardcore, 1+ = downgraded to regular ironman).
- Group ironman prestige level (if applicable).
- Helper methods: `isIronman()`, `isHardcore()`, `canUseGE()`, `canTradeWith(player)`.
- Safety equipment status: Ring of Life equipped, charged status.
- Emergency teleport availability (one-click escape items).

**Account Type Detection**:
- Use `client.getAccountType()` which returns `AccountType` enum (`NORMAL`, `IRONMAN`, `HARDCORE_IRONMAN`, `ULTIMATE_IRONMAN`, `GROUP_IRONMAN`)
- Check hardcore status via death count tracking (HCIM with 1+ deaths = downgraded to regular ironman)
- Initialize on plugin startup and cache for session duration

### 6.3 State Conditions
Composable predicates for task gating:

- `PlayerAtTile(x, y)`, `PlayerInArea(minX, minY, maxX, maxY)`
- `PlayerIsIdle()`, `PlayerIsAnimating(animationId)`
- `HasItem(itemId, quantity)`, `InventoryFull()`, `InventoryContains(itemId)`
- `HasEquipped(itemId)`, `WearingGearSet(setName)`
- `ObjectExists(objectId, radius)`, `NpcExists(npcId, radius)`
- `NpcHealthBelow(percent)`, `NpcTargetingPlayer()`
- `QuestStepEquals(questId, step)`, `QuestCompleted(questId)`
- `WidgetVisible(widgetId)`, `DialogueOpen()`
- `HealthBelow(percent)`, `PrayerBelow(percent)`, `RunEnergyAbove(percent)`
- `SpecialAttackAbove(percent)`, `PoisonActive()`, `VenomActive()`
- `SlayerTaskActive()`, `SlayerCountBelow(n)`
- `SkillLevelAbove(skill, level)`, `HasRequirements(requirementSet)`
- `IsIronman()`, `IsHardcoreIronman()`, `CanUseGrandExchange()`
- `HasRingOfLife()`, `HasEmergencyTeleport()`, `SafetyEquipmentReady()`

Compound conditions via `and()`, `or()`, `not()` methods.

---

## 7. Navigation System

### 7.1 PathFinder
- A* implementation on tile grid.
- Collision data sourced from client's collision maps.
- Support for: blocked tiles, water, obstacles requiring interaction.
- Path caching: reuse computed paths if start/end unchanged.
- Maximum path length: 100 tiles (longer paths delegate to WebWalker).

### 7.2 WebWalker
- Pre-defined navigation graph (web) covering major OSRS areas.
- Nodes represent key locations (banks, teleport spots, quest areas, slayer locations).
- Edges represent walkable paths or transport methods (fairy rings, spirit trees, teleports, charter ships).
- Integrate with `TeleportData` and behavioral profile `teleport_preferences` to select travel methods.
- Weight teleport selection by account type and individual preferences (HCIM avoids law runes, etc.).

#### 7.2.1 Navigation Web JSON Format
The navigation web is stored in `data/web.json` with the following structure:

**Node Definition**:
```json
{
  "id": "unique_node_identifier",
  "name": "Human-readable name",
  "x": 3253,
  "y": 3420,
  "plane": 0,
  "type": "bank|teleport|quest|slayer|generic",
  "tags": ["members", "safe", "bank_nearby"],
  "metadata": {
    "bank_type": "booth|chest|npc",
    "slayer_master": "name_if_applicable"
  }
}
```

**Edge Definition**:
```json
{
  "from": "source_node_id",
  "to": "destination_node_id",
  "type": "walk|teleport|transport|agility|quest_locked",
  "cost_ticks": 45,
  "bidirectional": true,
  "requirements": [],
  "metadata": {}
}
```

**Example Web Schema** (abbreviated - full schema in `data/web.schema.json`):
```json
{
  "version": "1.0",
  "nodes": [
    {"id": "varrock_west_bank", "name": "Varrock West Bank", "x": 3253, "y": 3420, "plane": 0, "type": "bank", "tags": ["f2p", "safe"]}
  ],
  "edges": [
    {"from": "varrock_west_bank", "to": "varrock_ge_center", "type": "walk", "cost_ticks": 52, "bidirectional": true},
    {"from": "lumbridge_castle", "to": "varrock_west_bank", "type": "teleport", "cost_ticks": 3, "requirements": [{"type": "magic_level", "value": 25}]},
    {"from": "edgeville_bank", "to": "varrock_ge_center", "type": "transport", "cost_ticks": 15, "requirements": [{"type": "quest", "quest_id": "GRAND_TREE"}]}
  ],
  "regions": [
    {"id": "misthalin", "name": "Misthalin Region", "nodes": ["varrock_west_bank", "lumbridge_castle"], "members_only": false}
  ]
}
```

**Requirement Types**:
| Type | Fields | Description |
|------|--------|-------------|
| `magic_level` | `value` | Minimum magic level |
| `agility_level` | `value` | Minimum agility level |
| `quest` | `quest_id`, `state` | Quest completion requirement |
| `item` | `item_id`, `quantity`, `consumed` | Required item (consumed if true) |
| `runes` | `items: [{id, quantity}]` | Rune cost for spell |
| `skill` | `skill`, `level` | Any skill requirement |
| `combat_level` | `value` | Minimum combat level |
| `ironman_restriction` | `type` | Ironman-specific (e.g., no GE access) |

**Edge Types**:
- `walk`: Standard walking path (no interaction required).
- `teleport`: Magical teleport (spell, jewelry, tablet).
- `transport`: NPC-based transport (ship, balloon, spirit tree, fairy ring).
- `agility`: Agility shortcut (may have failure chance).
- `quest_locked`: Path unlocked by quest completion.
- `door`: Requires opening door/gate.

**Path Finding Algorithm**:
- Use Dijkstra's algorithm with edge cost_ticks as weight.
- Check all requirements before considering an edge.
- For ironman accounts, filter out edges with ironman restrictions.
- For HCIM, apply additional safety filters (avoid wilderness regions unless explicitly enabled).

### 7.3 ObstacleHandler
- Register handlers for common obstacles: doors (open if closed), gates, agility shortcuts.
- Each obstacle has: object ID, required action, success condition, optional requirements (agility level).
- WebWalker incorporates obstacle traversal cost into pathfinding.

---

## 8. Quest Helper Integration

### 8.1 Design Rationale
Instead of defining our own quest data, leverage RuneLite's Quest Helper plugin which already contains:
- All quest step definitions.
- Required items and stats.
- NPC and object IDs for each step.
- Varbit conditions for progress tracking.
- Overlay hints for current objectives.
- **Wiki URLs for all quests** (`questinfo/ExternalQuestResources.java`).
- **Item collections** (`ItemCollections.java` — grouped item categories).

The framework reads Quest Helper's state and translates it into executable tasks.

### 8.1.1 Quest Helper Data Extraction
Quest Helper's internal structure provides rich data that eliminates the need for our own definitions. Quest Helper uses RuneLite's built-in ID enums (`ItemID`, `NpcID`, `ObjectID`, `VarbitID`) and provides comprehensive quest step data through its internal classes.

**Leverageable Components:**
| Component | Quest Helper Source | Our Usage |
|-----------|--------------------|-----------| 
| Item IDs | `net.runelite.api.ItemID` | Direct import |
| NPC IDs | `net.runelite.api.NpcID` | Direct import |
| Object IDs | `net.runelite.api.ObjectID` | Direct import |
| Varbits | `QuestVarbits.java`, `VarbitID` | Direct import |
| Item collections | `ItemCollections.java` (~66KB, comprehensive) | Import or reference |
| NPC collections | `NpcCollections.java` | Grouped NPC IDs |
| Teleport collections | `TeleportCollections.java` | Teleport item groups |
| Zone definitions | `com.questhelper.requirements.zone.Zone`, `ZoneRequirement`, `PolyZone` | Extract for area checks |
| Wiki URLs | `questinfo/ExternalQuestResources.java` | Enum lookup |
| Dialogue options | Per-step `addDialogStep()` | Extract via bridge |
| Dialogue choices | `com.questhelper.steps.choice.DialogChoiceSteps` (container class) | Extract via bridge |

**Note on ID enums**: `ItemID`, `NpcID`, `ObjectID`, and `VarbitID` are **RuneLite API enums** (`net.runelite.api.*`), NOT Quest Helper classes. Quest Helper simply uses these standard RuneLite enums. Import them directly from RuneLite's API.

### 8.2 QuestHelperBridge

#### 8.2.1 Plugin Detection and Dependency
- Use `@PluginDependency(QuestHelperPlugin.class)` to ensure Quest Helper loads first.
- Inject `QuestHelperPlugin` instance via Guice - this is the entry point to Quest Helper's API.
- Access `QuestManager` through the plugin instance, NOT via direct injection.
- Quest Helper is pre-installed and enabled via Docker configuration (see `Dockerfile` and `entrypoint.sh`).

#### 8.2.2 State Extraction
The QuestHelperBridge service extracts quest state via Quest Helper's plugin and manager hierarchy.

**Access Pattern** (correct injection hierarchy):
1. Inject `QuestHelperPlugin` via `@Inject private QuestHelperPlugin questHelperPlugin;`
2. Access `QuestManager` via plugin: `questHelperPlugin.getQuestManager()`
3. Get active quest: `questManager.getSelectedQuest()` → Returns `QuestHelper` instance
4. Get current step: `questHelper.getCurrentStep()` → Returns active `QuestStep`

**Accessible Data from QuestHelper instance:**
- Current step object and substeps via `getCurrentStep()`
- Highlighted objects/NPCs from step's `getWorldPoint()`, `getNpcs()`, `getObjects()`
- Panel text from step's `getText()`
- Item requirements from `getItemRequirements()` (returns `List<ItemRequirement>`)
- General requirements from `getGeneralRequirements()` (returns `List<Requirement>`)

**Important**: Do NOT inject `QuestManager` directly - it must be accessed through `QuestHelperPlugin` to ensure proper initialization order.

#### 8.2.3 Step Type Mapping
Quest Helper uses polymorphic step types. Map each to our task primitives:

| Quest Helper Step | Our Task | Extraction |
|-------------------|----------|------------|
| `NpcStep` | `InteractNpcTask` + `DialogueTask` | `step.getNpcIds()`, `step.getWorldPoint()` |
| `MultiNpcStep` | `CompositeTask` (multiple InteractNpc) | `step.getNpcIds()` array iteration |
| `NpcEmoteStep` | `EmoteStep` + near NPC | `step.getNpcIds()`, `step.getEmote()` |
| `NpcFollowerStep` | `WaitForConditionTask` (NPC proximity) | `step.getNpcId()`, check follower distance |
| `ObjectStep` | `InteractObjectTask` | `step.getObjectIds()`, `step.getWorldPoint()` |
| `ItemStep` | `InventoryTask` (pickup) | `step.getItemId()`, `step.getWorldPoint()` |
| `ConditionalStep` | `ConditionalTask` | `step.getCondition()`, `step.getSteps()` |
| `ReorderableConditionalStep` | `ConditionalTask` (dynamic ordering) | `step.getSteps()`, reorder based on conditions |
| `DigStep` | `WalkToTask` + `InventoryTask` (use spade) | `step.getWorldPoint()` |
| `EmoteStep` | `WidgetInteractTask` (emote tab) | `step.getEmote()`, `step.getWorldPoint()` |
| `PuzzleWrapperStep` | Recursive conversion | `step.getSteps()` (unwrap and convert children) |
| `PuzzleStep` | Composite or AI-directed | `step.getText()` for puzzle-specific logic |
| `WidgetStep` | `WidgetInteractTask` | `step.getWidgetId()`, `step.getChildId()` |
| `TileStep` | `WalkToTask` | `step.getWorldPoint()` |
| `BoardShipStep` | `InteractNpcTask` (charter ship) | `step.getNpcId()`, destination from step data |
| `SailStep` | `InteractObjectTask` (gangplank) | `step.getObjectId()`, `step.getWorldPoint()` |
| `PortTaskStep` | Quest-specific handling | Player-Owned Port mechanics (rarely used) |
| `QuestSyncStep` | `WaitForConditionTask` (quest var) | `step.getQuestHelperQuest()`, `step.getVar()` |
| `OwnerStep` | Interface for step containers | `step.getSteps()` for child steps |
| `DetailedOwnerStep` | `CompositeTask` (parent container) | `step.getSteps()` recursive conversion |
| `DetailedQuestStep` | Various | Parse `step.getText()` with AI Director |
| `choice/DialogChoiceStep` | `DialogueTask` (single choice) | `step.getChoices()` for choice text |
| `choice/DialogChoiceSteps` | Dialogue choice container | Holds multiple `DialogChoiceStep` instances |
| `choice/WidgetChoiceStep` | `WidgetInteractTask` (selection) | `step.getWidgetId()`, `step.getChoiceId()` |
| `choice/WidgetChoiceSteps` | Widget choice container | Holds multiple `WidgetChoiceStep` instances |
| `playermadesteps/*` | Various (player-created guides) | Parse via AI Director |

**Note on choice/ subdirectory**: Contains both step classes (`DialogChoiceStep`, `WidgetChoiceStep`) and container classes (`DialogChoiceSteps`, `WidgetChoiceSteps`). Also includes `DialogChoiceChange` and `WidgetTextChange` which are RuneLite events (`net.runelite.api.events.*`) used for tracking dialog state changes via `@Subscribe` - these are NOT step types.

**Quest Helper Data Extraction Details:**

Beyond step types, Quest Helper provides critical supporting data:

- **Zone definitions**: Extract `Zone`, `ZoneRequirement`, and `PolyZone` classes from `com.questhelper.requirements.zone` for area-based logic. **Important distinction**: `Zone` defines a 3D rectangular geographic region (min/max WorldPoint + planes), while `ZoneRequirement` is a conditional check that evaluates whether the player is currently within one or more `Zone` instances. Use Quest Helper's zone system instead of hardcoding tile regions - zones handle multi-region areas, plane changes, and irregular boundaries automatically.
- **Item collections**: Import `ItemCollections.java` enums for grouped item handling (e.g., `FOOD`, `PICKAXES`, `AXES`, `HATCHETS`). Quest Helper maintains these collections for all quest requirements - leverage them for inventory validation and item selection.
- **Dialogue choices**: Extract from `DialogChoiceSteps` for multi-option dialogue handling. Quest Helper stores the exact dialogue text and option indices - use this for reliable dialogue navigation.
- **World point accuracy**: All Quest Helper steps include precise `WorldPoint` coordinates validated against live game data. Use these coordinates directly rather than approximating or hardcoding - they account for quest variations and plane changes.

**Leveraging Quest Helper's Built-in Data:**
- `QuestHelper.getItemRequirements()` → Returns all required items with quantities and alternate options.
- `QuestHelper.getGeneralRequirements()` → Returns skill, quest, and combat level requirements.
- `QuestStep.getWorldPoint()` → Returns exact tile for step objectives.
- `Requirement.check(Client)` → Built-in validation method for all requirement types.

**Conversion Implementation**: Use pattern matching to map Quest Helper step types to framework task primitives. Refer to step type mapping table above for complete conversions. Recursively convert child steps for composite/conditional steps. Fallback to AI Director for ambiguous or unhandled step types.

#### 8.2.4 Requirement Resolution

**Use Quest Helper's Built-in Validation**: Quest Helper provides `Requirement.check(Client)` method for ALL requirement types (skills, quests, items, combat levels, etc.). Do NOT reimplement requirement validation logic.

**Validation Process**:
Before executing quest steps:
1. Call `Requirement.check(Client)` for ALL requirements from `getGeneralRequirements()` and `getItemRequirements()`
2. If ANY requirement fails:
   - **Quest requirements** → Queue prerequisite quest via `QuestOrderPlanner`
   - **Skill requirements** → Queue training via `SkillPlanner` to reach required level
   - **Item requirements** → Check bank first; if not present, query `WikiDataService` for acquisition sources
   - **Combat level requirements** → Queue combat training if below requirement
3. If ALL requirements pass → Proceed with quest step execution

**Why Use Quest Helper Validation**: Quest Helper's requirement system is battle-tested by thousands of users and handles edge cases (varbit changes, partial completion states, item alternates) that would be complex to reimplement.

### 8.3 AI Director Integration for Quests
For steps that Quest Helper describes ambiguously or that require interpretation:
- Send step description + current game state to Claude API
- Claude returns structured task sequence in defined JSON format
- Validate response against available task types before execution

### 8.4 Quest Helper Event Subscription

Subscribe to Quest Helper's internal events to detect step changes and quest progression in real-time.

**Event Subscription Pattern**:
- Use RuneLite's event bus (`@Subscribe`) to listen for Quest Helper events
- Quest Helper posts events when steps change, requirements update, or quest completes

**Key Events to Monitor**:
- `QuestStateChanged`: Fired when quest varbit changes indicate progression
- Step transition detection: Poll `questHelper.getCurrentStep()` on each game tick; compare to previous step
- Panel text changes: Monitor `step.getText()` for instruction updates within a single step

**Implementation**:
- On step change detection: Convert new step to task via step type mapping (Section 8.2.3)
- On quest completion: Update `QuestState`, trigger next quest in queue
- On requirement failure (mid-quest): Pause quest, queue requirement resolution task

**Fallback (if events unavailable)**: Poll Quest Helper state every 3-5 game ticks for changes.

---

## 8A. OSRS Wiki Data Integration

### 8A.1 Design Rationale
Rather than maintaining proprietary data files for game content (drop tables, shop inventories, item sources, slayer creatures), leverage the OSRS Wiki as the authoritative data source. The wiki is:
- **Community-maintained** — Updated within hours of game changes.
- **Comprehensive** — Contains all game data in structured infoboxes.
- **Accessible** — MediaWiki API provides programmatic access.

**Key Principle**: Use the wiki for **dynamic game data** (drop rates, shop stock, item sources) while keeping **custom/curated data** local (navigation web, safety ratings, training recommendations).

### 8A.2 WikiDataService

#### 8A.2.1 API Configuration

**CRITICAL User-Agent Requirement**: The OSRS Wiki **WILL RETURN HTTP 403** without proper User-Agent header. This is not optional.

**Mandatory User-Agent Format**: `ApplicationName/Version (contact@email.com)` or `ApplicationName/Version (github.com/repo)`

**Recommended OkHttp Version**: 4.x (tested with 4.12.0)

**DO NOT use**:
- Generic Java user agents (`Java/1.8.0` etc.) - **BLOCKED**
- Missing User-Agent header - **BLOCKED**  
- Vague descriptions without contact info - **MAY BE BLOCKED**

**Mandatory Headers Example**:
```java
// CRITICAL: Wiki returns HTTP 403 without this exact format
static final String USER_AGENT = "RuneLite-Rocinante/1.0 (github.com/yourrepo)";

Request request = new Request.Builder()
    .url(url)
    .header("User-Agent", USER_AGENT) // REQUIRED - 403 without this
    .header("Accept", "application/json")
    .build();
```

**Rate Limiting Requirements**:
- Base rate: 30 requests/minute (0.5 RPS) - be respectful to wiki servers
- Implement exponential backoff on 429 responses: 1s → 2s → 4s → 8s
- Request queue with priority levels: quest data (HIGH), drop tables (MEDIUM), general info (LOW)

**Circuit Breaker Pattern** (resilience for wiki unavailability):
- **Failure threshold**: 5 consecutive failures triggers OPEN state
- **States**: CLOSED (normal) → OPEN (failing) → HALF_OPEN (testing recovery)
- **OPEN behavior**: Return stale cache immediately, skip API calls, log warning
- **Recovery probe**: After 60 seconds in OPEN, send single test request (HALF_OPEN)
- **Recovery criteria**: Single successful response → CLOSED state, resume normal operation
- **Health check endpoint**: `WikiDataService.isAvailable()` returns circuit breaker state
- **Graceful degradation**: If wiki unavailable for >5 minutes, use stale cache + log warning
- **Monitoring**: Track total failures, circuit opens, and recovery times for debugging

#### 8A.2.2 Supported Query Types

| Query Type | Wiki API Call | Use Case |
|------------|---------------|----------|
| **Search** | `action=opensearch&search=<term>` | Autocomplete item/monster names |
| **Page Content** | `action=parse&page=<name>&prop=wikitext` | Full page wikitext for parsing |
| **Structured Data** | `action=query&titles=<name>&prop=revisions&rvprop=content` | Raw revision content |
| **Categories** | `action=query&list=categorymembers&cmtitle=<category>` | List items in category |

#### 8A.2.3 Data Parsing

Parse wiki page wikitext to extract structured data from infoboxes with robust error handling.

**High-Level Requirements**:
- **Multiple fallback patterns**: Implement primary → fallback1 → fallback2 pattern chain for each data type (drop tables, shop inventories, item sources).
- **Data range validation**: Validate parsed data against sensible ranges (item IDs: 1-30000, drop rates: valid rarity strings, prices: > 0).
- **Malformed template handling**: Gracefully handle missing fields, nested templates, and wiki markup variations. Skip malformed entries with debug logging.
- **Parse failure logging**: Log failures with full wiki page URL for manual review (format: `https://oldschool.runescape.wiki/w/{PageName}`).
- **Validation before return**: Sanity-check all parsed data structures (non-empty collections, valid IDs, reasonable quantities).

**Data Types to Parse**:
- **Drop tables**: Multiple template formats (see fallback order below)
- **Item sources**: `{{ItemSources}}` template OR infobox "source" field parsing
- **Shop inventories**: `{{Store}}` template OR table-based shop listings
- **Slayer info**: `{{Infobox Monster}}` template with slayer-specific fields (slaylvl, category, weakness, locations)

**Drop Table Template Fallback Order** (try in sequence):
1. `{{DropsTableHead}}` + `{{DropsLine}}` — Most common modern format
2. `{{DropTableNew}}` — Newer unified format (single template with params)
3. Simple wikitable with headers: Item, Quantity, Rarity — Legacy pages
4. `{{Drops}}` — Older deprecated format (some pages still use)

**Fallback Pattern**: Try primary parser → fallback parsers in sequence → validate result → log failures with wiki URL for manual review.

#### 8A.2.4 Caching Strategy
`WikiCacheManager` implements two-tier caching:
- **Memory cache**: Guava `Cache` with 1000 entry max, 24-hour TTL
- **File cache**: Persistent JSON files in `~/.runelite/rocinante/wiki-cache/`

Lookup order: memory → file → wiki API → update both caches. On plugin startup, `refreshStaleEntries()` updates entries older than TTL if network available.

### 8A.3 Data Types Retrieved from Wiki

#### 8A.3.1 Drop Tables
Query monster pages for drop tables. Used for:
- Ironman item acquisition planning
- GP/hour calculations
- Slayer loot expectations

**Example Query:**
```
GET /api.php?action=parse&page=Abyssal_demon&prop=wikitext&format=json
```

**Parsed Result:**
```java
public record DropTable(
    String monsterName,
    int combatLevel,
    List<Drop> drops
) {
    public record Drop(int itemId, String itemName, String quantity, String rarity, boolean membersOnly) {}
}
```

#### 8A.3.2 Item Sources
Query item pages to find where items come from. Used for:
- Ironman acquisition planning
- Gear progression planning
- Quest item preparation

**Wiki Infobox Fields:**
- `source` — How to obtain the item
- `examine` — Item description
- `quest` — Quest requirements
- `tradeable` — Can be traded (affects ironman methods)

#### 8A.3.3 Shop Inventories
Query shop pages for stock information. Used for:
- Shop run planning
- Ironman supply acquisition
- World hopping optimization

**Data Extracted:**
- Shop name and location
- Items sold (ID, price, stock)
- Restock mechanics (timed vs. player-sold)

#### 8A.3.4 Slayer Monster Information
Query monster pages for slayer-specific data. Used for:
- Task location selection
- Gear recommendations
- Weakness exploitation

**Data Extracted:**
- Slayer level requirement
- Slayer category (for helm bonuses)
- Combat stats and max hit
- Locations with coordinates
- Weakness (stab/slash/crush/magic/ranged)

### 8A.4 Fallback Behavior
If wiki API is unavailable:
1. **Use cached data** — File cache persists between sessions.
2. **Use bundled defaults** — Ship minimal static data for critical items.
3. **Log warning** — Alert user that data may be stale.
4. **Continue operation** — Don't block automation; degrade gracefully.

### 8A.5 Integration with Other Modules

| Module | Wiki Data Used |
|--------|----------------|
| `SelfSufficiencyPlanner` | Item sources for ironman acquisition |
| `ShopRunPlanner` | Shop inventories and locations |
| `DropTableAnalyzer` | Monster drop tables for farming |
| `SlayerManager` | Monster locations and weaknesses |
| `GearProgressionPlanner` | Item sources for upgrade planning |
| `AIDirector` | Context data for planning queries |

### 8A.6 Data Source Priority Hierarchy

**CRITICAL PRINCIPLE: NO REDUNDANT DATA FILES**

NEVER maintain static data when an authoritative source exists. Every redundant file is:
- A maintenance burden (must be updated when game changes)
- A source of bugs (stale data diverges from reality)
- Unnecessary work (duplicates existing reliable sources)

Follow this strict priority hierarchy for ALL game data:

**Priority 1: RuneLite Client API** (ALWAYS USE FIRST)
- Item definitions: `itemManager.getItemComposition(itemId)` — NOT `items.json`
- NPC definitions: `client.getNpcDefinition(npcId)` — NOT `npcs.json`
- Object definitions: `client.getObjectDefinition(objectId)` — NOT `objects.json`
- Game constants: `net.runelite.api.ItemID`, `NpcID`, `ObjectID`, `VarbitID` — NOT hardcoded IDs
- **Rationale**: Direct game client data is always accurate and requires ZERO maintenance

**Priority 2: Quest Helper Plugin Data** (USE FOR ALL QUEST CONTENT)
- Quest steps: `QuestHelper.getCurrentStep()` — NOT custom quest definitions
- Quest requirements: `QuestHelper.getItemRequirements()`, `getGeneralRequirements()` — NOT `quest_requirements.json`
- Item collections: `ItemCollections` enums — NOT custom item groups
- Zone definitions: `Zone`, `ZoneRequirement`, `PolyZone` classes — NOT hardcoded regions
- WorldPoint coordinates: All step locations validated against game — NOT approximations
- **Rationale**: Battle-tested by thousands of users, automatically updated, handles edge cases

**Priority 3: OSRS Wiki API** (DYNAMIC DATA ONLY)
- Drop tables: Query monster pages, cache for 24 hours — NOT `drop_tables.json`
- Shop inventories: Query shop pages, cache for 24 hours — NOT `shop_data.json`
- Item sources: Parse item pages for acquisition methods — NOT `item_sources.json`
- Slayer monster info: Extract from infoboxes — NOT `slayer_data.json`
- **Rationale**: Community-maintained, updated within hours of game changes, comprehensive

**Priority 4: Local Fallback Files** (ONLY WHEN NO ALTERNATIVE EXISTS)
- Stale wiki cache (>24 hours old, network unavailable)
- Bundled emergency defaults for critical items
- **Rationale**: Offline operation support, emergency fallback ONLY

**NEVER CREATE THESE FILES** (Authoritative sources exist):
- ❌ `items.json` → Use `itemManager.getItemComposition(itemId)`
- ❌ `npcs.json` → Use `client.getNpcDefinition(npcId)`
- ❌ `objects.json` → Use `client.getObjectDefinition(objectId)`
- ❌ `quest_requirements.json` → Use Quest Helper's `getGeneralRequirements()`
- ❌ `drop_tables.json` → Query OSRS Wiki API, cache result
- ❌ `shop_data.json` → Query OSRS Wiki API, cache result
- ❌ `item_sources.json` → Query OSRS Wiki API, cache result

**Examples of Necessary Local Data** (Custom/Curated):
- ✅ `web.json` - Custom navigation graph (pathing logic)
- ✅ `gear_tiers.json` - Opinionated upgrade paths
- ✅ `training_methods.json` - Curated XP rate recommendations
- ✅ `hcim_risk_ratings.json` - Custom safety assessments
- ✅ `supply_chains.json` - Crafting dependency graphs for ironman planning

---

## 9. AI Director System

### 9.1 Purpose
The AI Director handles decisions that are too complex or variable for hardcoded logic:
- Interpreting Quest Helper instructions.
- Resolving ambiguous game situations.
- Planning efficient sequences for multi-part objectives.
- Adapting to unexpected states.
- Long-term account progression planning.

### 9.2 ClaudeAPIClient

#### 9.2.1 API Configuration
- API endpoint: `https://api.anthropic.com/v1/messages`
- API key stored securely (RuneLite's credential storage or environment variable).
- Model selection: configurable, default to `claude-sonnet-4-5` for speed, `claude-opus-4-5` for complex planning.
- **Note**: Use model aliases (e.g., `claude-sonnet-4-5`, `claude-opus-4-5`) to automatically receive the latest version.
- **Rate limiting**: Adaptive based on API tier and model, with exponential backoff on 429 errors.

**Required HTTP Headers**:
- `X-Api-Key`: Your Anthropic API key (required)
- `anthropic-version`: API version date string, e.g., `2023-06-01` (required)
- `Content-Type`: `application/json` (required)

**Rate Limit Configuration**:
- Support configurable API tiers: FREE (5 RPM), BUILD (50 RPM), SCALE (1000 RPM), ENTERPRISE (2000 RPM)
- Implement adaptive rate limiting with exponential backoff on 429 errors (1s → 2s → 4s → 8s)
- Queue requests when rate limit reached, process with priority
- Config: `claudeApiTier` enum and optional `claudeCustomRateLimit` override

#### 9.2.2 Request Format
All requests include:
- System prompt defining available task types and response format.
- Current game state snapshot (relevant subset).
- Specific query or decision needed.
- Constraints (available items, unlocked teleports, skill levels).

#### 9.2.3 Response Parsing
Responses must be structured JSON:
```
{
  "reasoning": "Brief explanation of approach",
  "tasks": [
    { "type": "walkTo", "params": { "destination": "lumbridge_cow_field" } },
    { "type": "interactNpc", "params": { "npcName": "Cow", "action": "Attack" } }
  ],
  "fallback": "Description of what to do if tasks fail"
}
```
Validate all task types and parameters before execution.

#### 9.2.4 Caching
- Cache responses for identical game state + query combinations.
- Cache TTL: 5 minutes for dynamic situations, 24 hours for static planning.
- Reduce API costs and latency for repeated scenarios.

### 9.3 AIDirector

#### 9.3.1 Decision Points
Invoke AI Director when:
- Quest Helper step requires interpretation.
- Player is stuck (no progress for 60+ seconds).
- Multiple valid approaches exist (choose optimal).
- Unexpected dialogue or event occurs.
- Progression planning requested.

#### 9.3.2 Context Building
Build minimal but sufficient context:
- Player stats, equipment, inventory.
- Current location and nearby entities.
- Active quest/task state.
- Recent action history (last 10 actions).
- Current objective description.

#### 9.3.3 Fallback Behavior
If API unavailable or response invalid:
- Log error.
- Attempt simple heuristic (click highlighted object, talk to nearest NPC).
- If still stuck after 3 attempts, pause and alert user.

---

## 10. Combat System

### 10.1 CombatManager

#### 10.1.1 Combat Loop
Main combat loop executed every game tick during combat:
1. Check health → eat if below threshold.
2. Check prayer → restore or toggle prayers.
3. Check special attack → use if conditions met.
4. Check target → retarget if current dead or out of range.
5. Check loot → pick up valuable drops.
6. Execute queued combat actions (gear switches, prayer flicks).

#### 10.1.2 Eat Logic
- Primary food threshold: configurable (default: 50% HP, **65% HP for HCIM**).
- Panic threshold: 25% HP, eat immediately even if mid-action (**40% HP for HCIM**).
- Combo eating: support food + karambwan tick eating when low.
- Brew sipping: if using Saradomin brews, pair with restore.
- **HCIM Mode**: Always maintain minimum 2 food items; abort task if food runs low.
- **HCIM Mode**: Pre-eat before engaging new targets if HP < 70%.

#### 10.1.3 Prayer Management
- Protection prayers: auto-detect incoming attack style (melee/range/mage) from NPC data or animation.
- Offensive prayers: maintain configured offensive prayer during combat.
- Lazy flicking: option to flick prayers on NPC attack tick only.
- Drain management: disable prayers if points critically low and no restore available.

#### 10.1.4 HCIM Safety Protocol
When `IronmanState.isHardcore()` is true, apply enhanced combat safety:
- **Pre-combat checks**:
  - Verify minimum food count (configurable, default: 4 pieces).
  - Verify emergency teleport available (configurable item list).
  - **CRITICAL**: Verify Ring of Life equipped AND charged (or Phoenix necklace as backup).
  - Calculate enemy max hit; refuse combat if max_hit >= current_hp * 0.6.
- **During combat**:
  - Monitor for multi-combat pile-ups; flee if 2+ enemies attacking.
  - Flee immediately if HP drops below 50% with no food remaining.
  - Prioritize prayer points for protection prayers over offensive.
- **Risk assessment scoring**:
  - Score each potential combat encounter (enemy level, type, location).
  - Block high-risk content without explicit user override:
    - Wilderness (all areas).
    - Multi-combat boss encounters without appropriate gear.
    - Monsters with instant-kill mechanics (e.g., Galvek, Jad).
- **Emergency escape priority order**:
  1. Manual teleport (one-click: ROD, house tab, ectophial, royal seed pod).
  2. Standard teleport spell (if not in combat/wilderness).
  3. Run to safe tile + logout (if teleport blocked).
  4. **Ring of Life as last resort insurance** (see mechanics below).
- **Ring of Life mechanics (CRITICAL FOR HCIM)**:
  - **Purpose**: Last-resort failsafe, NOT a primary escape mechanism.
  - **Trigger**: Automatically activates at **10% of player's maximum hitpoints** (e.g., 9 HP at 90 max HP).
  - **CRITICAL LIMITATION - RING IS DESTROYED ON USE**: 
    - Teleports to last respawn point (may NOT be safe location).
    - **Ring is CONSUMED and DESTROYED on trigger** - disappears from equipment slot.
    - **Ironman MUST re-obtain** via:
      - Crafting (requires 75 Crafting + dragonstone + gold bar)
      - Limited NPC shop stock (Grum's Gold Exchange - player-sold only)
    - Cannot be manually triggered - purely automatic.
  - **System Requirements**:
    - System MUST NOT rely on RoL trigger as primary escape (flee at 30-40% HP minimum).
    - **IMMEDIATELY queue RoL replacement after any trigger event** (HCIM priority).
    - Verify equipped ring is charged via item composition tracking before ANY combat.
    - **REFUSE combat** if `hcimRequireRingOfLife=true` and RoL missing/uncharged.
  - **Ring of Life Charge Verification** (CRITICAL):
    - Verify item ID matches Ring of Life (`ItemID.RING_OF_LIFE` = 2570)
    - Note: RoL itself has no "charges" - it either exists or is consumed (destroyed on trigger)
    - After each RoL trigger event, ring disappears from equipment → must be replaced
    - Pre-combat check: `isRingOfLifeEquipped()` returns `equipment.contains(ItemID.RING_OF_LIFE)` (ID: 2570)
  - **Ironman Replacement Protocol**:
    - Track RoL consumption count in behavioral profile.
    - If triggered: pause automation → craft/buy replacement → verify equipped → resume.
    - **HCIM Bank Requirement**: Maintain minimum 2 spare RoL in bank at all times.
    - Queue replacement crafting when spare count drops below 2.
    - Acquisition priority: Craft (75 Crafting) > Shop run (limited stock) > Drop farm (cockatrice, 1/128)
- **Death prevention**: If HP < 30% and no escape available, spam-click food and emergency teleport simultaneously.

### 10.2 PrayerFlicker

#### 10.2.1 Attack Detection
- Read NPC animation ID to determine attack style.
- For multi-style NPCs: switch protection prayer based on detected animation.
- Lead time: switch 1 tick before attack lands.

#### 10.2.2 Flick Timing
- One-tick flicking: activate prayer, deactivate after 1 game tick.
- Humanization: ±50ms variance on flick timing. Occasional missed flicks (2-5%).
- Intensity setting: perfect flicking, lazy flicking, or no flicking.

### 10.3 GearSwitcher

#### 10.3.1 Switch Sets
Define gear sets by name with item IDs:
- Each set: up to 11 equipment slots.
- Partial sets: only specified slots switch.
- Pre-validation: verify all items present before switching.

#### 10.3.2 Switch Execution
- Click sequence: humanized delays between each equipment click (80-150ms).
- Optimal order: weapon last to avoid losing attack tick.
- Failure handling: if switch incomplete, continue with current gear.

### 10.4 SpecialAttackManager

#### 10.4.1 Special Weapon Detection
- Identify equipped weapon's special attack.
- Know energy cost per weapon.

#### 10.4.2 Usage Strategy
- Threshold-based: use at X% energy.
- Target-based: use on specific enemy types (e.g., boss only).
- Stacking: for specs that stack (DDS), use multiple times.
- Weapon switching: switch to spec weapon, use spec, switch back.

### 10.5 TargetSelector

#### 10.5.1 Selection Priority
Configurable priority order:
1. NPCs already targeting player (reactive).
2. Lowest HP NPCs (fast kills).
3. Highest HP NPCs (sustained combat).
4. Nearest NPC.
5. Specific NPC by ID or name.

#### 10.5.2 Avoidance
- Skip NPCs in combat with other players.
- Skip NPCs that are unreachable.
- Optional: skip NPCs above certain combat level.

### 10.6 Combat Mechanics Validation

Ensure combat system accurately reflects OSRS game mechanics for reliable automation.

#### 10.6.1 Attack Speed and Tick Timing
**Weapon Attack Speed**:
OSRS combat operates on a tick system (1 tick = 0.6 seconds = 600ms). Weapon attack speeds vary:

| Weapon Speed Category | Ticks Between Attacks | Examples |
|----------------------|----------------------|----------|
| Fastest | 2 ticks (1.2s) | Darts, knives, shortbows |
| Fast | 3 ticks (1.8s) | Scimitars, whips, crossbows |
| Average | 4 ticks (2.4s) | Longswords, battleaxes |
| Slow | 5 ticks (3.0s) | 2h swords, godswords |
| Slowest | 6+ ticks (3.6s+) | Mauls, heavy weapons |

**Implementation Requirements**: 
- **DO NOT hardcode weapon attack speeds** - query OSRS Wiki for weapon data and cache results via `WikiDataService`.
- Parse weapon infobox from wiki pages to extract attack speed (field: `aspeed`).
- Cache weapon speeds with 24-hour TTL; fallback to cached data if wiki unavailable.
- Track weapon attack speed dynamically per equipped weapon.
- Never attempt attacks faster than weapon speed to avoid queued click patterns.
- Weapon speed changes on gear switch - re-query cache after equipment changes.

**Player Attack Cycle**: Click → Face target (tick 1) → Attack lands (tick 1 + weapon_speed) → Repeat. Never attack faster than weapon speed to avoid queued clicks.

#### 10.6.2 Protection Prayer Mechanics
**Timing Constraints**:
- Protection prayers have a **1-tick activation delay** (not instant).
- Must activate prayer **1 tick before** attack lands.
- Prayer flicking requires tick-perfect timing (600ms intervals).

**NPC Attack Patterns**: Map NPC animation IDs to attack styles. Track NPC-specific attack speeds. Schedule prayer switches 1 tick before attack lands.

**Protection Prayer Order** (when switching):
1. Deactivate current protection prayer
2. Wait 1 game tick (600ms)  
3. Activate new protection prayer
4. Attack lands on next tick (protected if switch was early enough)

**Lazy Flicking** (prayer conservation):
- Activate protection prayer on NPC attack tick
- Deactivate 1 tick later
- Saves prayer points but requires perfect timing
- Add humanization: 2-5% missed flicks (too late or too early)

#### 10.6.3 Special Attack Mechanics
**Special Attack Energy**:
- Maximum: 100%
- Regenerates at 10% per minute (passive)
- Special attacks consume 25%, 50%, or 100% depending on weapon

**Weapon Switching Rules**: Weapon switches take 1 tick. Add human delay variance (100±30ms). Special attacks require sufficient energy (25%, 50%, or 100% depending on weapon).

**Special Attack Preservation**:
- If target dies before special attack lands, energy is NOT consumed
- If special attack misses/splashes, energy IS consumed
- Some specs (like DDS) can be stacked by switching rapidly (tick manipulation)

#### 10.6.4 Poison and Venom Mechanics
**Poison Damage**:
- Initial damage: 6 HP (weapon-dependent)
- Decreases by 1 HP each hit until reaches 1 HP
- Hits every 18 seconds (30 ticks)
- Can be cured with antipoison

**Venom Damage**:
- Initial damage: 6 HP
- **Increases** by 2 HP each hit (6 → 8 → 10 → ... → max 20 HP)
- Hits every 18 seconds (30 ticks)
- **Venom cure options**:
  - **Antivenom**: Fully cures venom.
  - **Antivenom+**: Fully cures AND provides immunity for 3 minutes (prevents reapplication).
  - **Antipoison (any tier)**: Downgrades venom to poison (resets damage to 6, stops escalation, but still takes poison damage).
  - **Serpentine helm**: Provides passive venom immunity while worn.

**Implementation**: Track poison/venom status, damage values, and timing (18-second intervals). For venom, increment damage by 2 each hit (max 20). Flee if predicted next damage would breach flee threshold.

#### 10.6.5 Multi-Combat Area Detection
**Combat Type Detection**:
- **Single combat**: Only one entity can attack you at a time
- **Multi-combat**: Multiple entities can attack simultaneously

**Detection Method**: Check `client.getVarbitValue(Varbits.MULTICOMBAT_AREA)` — returns 1 if player is in multi-combat zone, 0 otherwise. Note: `Varbits` is from `net.runelite.api.Varbits` (RuneLite API), not Quest Helper.

**NPC Aggro Counting** (critical for pile-up detection):
- Poll all NPCs in render distance for `npc.getInteracting() == localPlayer`
- Track NPCs currently animating attack against player (not just targeting)
- Maintain `Map<NPC, Integer>` of attackers → ticks until next attack
- Calculate combined incoming damage per tick from all aggressors

**HCIM Safety**:
- Always check multi-combat status before engaging
- If multi-combat + 2+ aggressive NPCs nearby → refuse combat
- **HCIM flee threshold**: 2+ NPCs attacking simultaneously (even in single-combat area if somehow possible)
- Flee immediately if multi-combat pile-up detected during combat
- Pre-combat check: Count aggressive NPCs in area; refuse if pile-up risk > 0.1

#### 10.6.6 PvP World Restrictions
**PvP World Detection**: Check `client.getWorldType()` for `WorldType.PVP` or `WorldType.DEADMAN`.

**HCIM Restrictions**:
- Block all PvP worlds by default (config override required)
- Warn on login if PvP world detected
- Auto-logout if accidentally on PvP world and HCIM mode enabled

**Skull Detection**: Check `client.getLocalPlayer().getSkullIcon()` - HCIM should NEVER be skulled. If detected, immediately teleport + logout.

#### 10.6.7 Poison/Venom Interaction with Damage Calculation

**Poison/Venom Tracking Requirements**:
When poisoned or venomed, the combat system must track damage accumulation separately from enemy attacks:

- **Damage prediction**: Calculate expected poison/venom damage over next 30 seconds (accounting for damage progression).
- **Flee threshold adjustment**: Modify flee calculation to account for poison damage: `(current_HP - expected_poison_damage_next_30s) < flee_threshold`.
- **Venom escalation detection**: When venom damage reaches 16+ HP per hit, treat as critical threat.
- **HCIM venom protocol**: If venomed and `venom_damage >= 16`, flee immediately regardless of current HP percentage.
- **Antipoison availability**: Before engaging poisonous enemies, verify antipoison/antivenom in inventory (HCIM requirement).

**Edge Cases**:
- If poisoned during low-food combat, prioritize escape over continuing combat.
- If venomed with no antivenom available, flee after 2nd venom hit (before damage escalates to dangerous levels).
- Track time since last poison damage to predict next hit (18-second intervals).

#### 10.6.8 Food Delay Mechanics

**OSRS Food Consumption Rules**:
OSRS enforces strict delay mechanics for food consumption that must be respected:

- **Standard food delay**: 3 ticks (1.8 seconds) between eating most food items.
- **Karambwan exception**: Can be eaten on same tick as other food (combo eating mechanic).
- **Saradomin brew delay**: **3 ticks (1.8 seconds)** - SAME as standard food, NOT 4 ticks.
  - **CRITICAL**: Brews lower Attack/Strength/Defence/Ranged/Magic by 2+10% of level.
  - Brews restore prayer points - must pair with super restore to counter stat drain.
  - Use case: Emergency healing + prayer restoration, not regular food.
- **Purple sweets**: 0.6 seconds (1 tick) delay, can be spam-eaten.

**Implementation Requirements**:
- Never attempt to eat food items faster than game allows (will queue actions, creating detectable pattern).
- Track last food consumption timestamp per food type.
- Add realistic variance to delays: `base_delay + gaussian(100ms, 30ms)`.
- Combo eating timing: When HP critical, eat regular food, wait 1 tick (600ms), eat karambwan.
- Brew sipping: When using Sara brews, pair with super restore. Allow 3 ticks after brew before drinking restore to avoid consumption conflicts.

**Humanization**:
- Don't always combo eat at optimal timing - 60% of time eat separately with 2-5 tick gap.
- Occasionally eat food when not strictly necessary (panic eating at 55-60% HP, 10% probability).

### 10.7 Loot Management

#### 10.7.1 Loot Detection
- Scan ground items after each kill.
- Filter by whitelist (always loot) and value threshold (loot if worth > X gp).

#### 10.7.2 Loot Execution
- Prioritize high-value items if limited inventory space.
- Humanized delay between loot clicks.
- Area looting: after multi-kill, collect all loot before resuming combat.

---

## 11. Slayer System

### 11.1 SlayerManager

#### 11.1.1 Task Lifecycle
1. Detect current slayer task from SlayerState.
2. If no task: travel to slayer master, get new task.
3. Resolve task location via `TaskLocationResolver`.
4. Travel to location.
5. Execute combat loop until task complete.
6. Handle inventory/bank as needed between kills.
7. Repeat.

#### 11.1.2 Master Selection
- Configure preferred slayer master.
- Auto-select highest-level master based on combat level if not configured.
- Support special masters (Konar, Wilderness).

### 11.2 TaskLocationResolver

#### 11.2.1 Location Database
For each slayer creature:
- List of valid locations (tile regions).
- Recommended location based on efficiency/safety.
- Required items for location (rope for kalphites, etc.).
- Cannon placement spots (if using).
- **HCIM safety rating** for each location.

#### 11.2.2 Location Selection
- Prefer closest location if multiple valid.
- Prefer locations with bank nearby for long tasks.
- Avoid high-traffic areas (optional anti-competition).
- **HCIM**: Avoid multi-combat locations unless explicitly configured.
- **HCIM**: Prefer locations with easy escape routes (near teleport points).

### 11.3 Task-Specific Handling

#### 11.3.1 Special Requirements
Handle creatures with special needs:
- Bring specific items (ice coolers, rock hammer).
- Use specific attacks (leaf-bladed weapons).
- Wear specific gear (earmuffs, nose peg).
- Use specific mechanics (mirrors for basilisks).

#### 11.3.2 Cannon Integration
- Place cannon at optimal spot.
- Reload when low.
- Pick up after task complete.
- **HCIM**: Disable cannon by default (attracts multiple NPCs, risky).
- **HCIM**: If cannon enabled, require elevated food/safety thresholds.
- **Ironman**: Plan cannonball acquisition (smithing or shop purchase).

### 11.4 Block and Skip

#### 11.4.1 Task Preferences
Configure:
- Blocked tasks (never assign).
- Skipped tasks (skip with points if assigned).
- Extended tasks (enable for point-per-hour efficiency).
- Preferred tasks (never skip).

#### 11.4.2 Point Management
- Track available slayer points.
- Skip tasks only if points above threshold.
- Plan blocks based on master's task weight.

---

## 12. Account Progression System

### 12.1 AccountGoalPlanner

#### 12.1.1 Goal Types
Support goals:
- **Skill goal**: Reach level X in skill Y.
- **Quest goal**: Complete specific quest.
- **Item goal**: Acquire specific item(s).
- **Unlock goal**: Unlock specific content (fairy rings, spirit trees, etc.).
- **Diary goal**: Complete achievement diary tier.
- **Combat goal**: Reach combat stats for specific content.

#### 12.1.2 Goal Dependencies
Automatically resolve dependencies:
- Quest A requires Quest B → queue B before A.
- Item A requires 70 Smithing → add skill goal.
- Location requires completion of quest → add quest goal.

#### 12.1.3 Goal Prioritization
When multiple goals queued:
- Priority value (user-configurable).
- Efficiency scoring (XP/hour, GP/hour impact).
- Proximity bonus (prefer goals achievable with minimal travel).

### 12.2 SkillPlanner

#### 12.2.1 Training Methods
For each skill, database of training methods:
- Level requirements.
- XP per action.
- Actions per hour (theoretical).
- GP cost or profit.
- Required items and quantities.
- Location.
- **Ironman viability**: whether method is self-sufficient.
- **Supply chain**: what resources must be gathered first.

#### 12.2.2 Method Selection
Given target level, current level, and constraints:
- Calculate most efficient path.
- Consider available GP for buyable methods.
- Factor in current inventory/bank.
- Prefer methods that also progress other goals.

##### 12.2.2.1 Ironman Method Selection
When `IronmanState.isIronman()` is true:
- **EXCLUDE** methods requiring GE-bought supplies.
- **PRIORITIZE** methods that produce useful byproducts:
  - Fishing → Cooking food supplies.
  - Mining → Smithing materials → gear upgrades.
  - Woodcutting → Fletching ranged weapons.
  - Farming → Herblore secondaries.
  - Slayer → Combat XP + valuable drops.
- **CONSIDER** full supply chains:
  - Don't train Herblore until Farming can supply herbs.
  - Don't train Fletching until Woodcutting supplies logs.
  - Don't train Smithing until Mining supplies ore.
- **FACTOR IN** alternative supply sources:
  - Kingdom of Miscellania (passive resources).
  - Wintertodt (supplies crates).
  - Tempoross (fishing rewards).
  - Giant's Foundry (smithing without ores).

##### 12.2.2.2 Ironman Integrated Training Loops
Prefer closed-loop training that doesn't require external supplies:

| Loop | Skills Trained | Output |
|------|---------------|--------|
| Combat → Slayer | Attack, Strength, Defence, Slayer | GP, rare drops, herbs |
| Fishing → Cooking | Fishing, Cooking | Food for combat |
| Mining → Smithing | Mining, Smithing | Gear, darts, cannonballs |
| Woodcutting → Fletching | Woodcutting, Fletching | Arrows, bows |
| Farming → Herblore | Farming, Herblore | Potions for combat |
| Thieving → GP → Shop runs | Thieving | Runes, supplies |

#### 12.2.3 AI-Assisted Planning
For complex training decisions:
- Send skill goals + account state to AI Director.
- Request optimal training plan.
- Validate feasibility of returned plan.
- **Ironman**: Include supply chain constraints in context.

### 12.3 QuestOrderPlanner

#### 12.3.1 Quest Graph
Build directed graph of all quests:
- Nodes: quests.
- Edges: prerequisites (quest, skill, item).

#### 12.3.2 Optimal Ordering
Given target quests:
- Compute required prerequisite chain.
- Order by: training efficiency (quests that give XP in needed skills first), reward utility (early unlocks worth more), total time.
- Account for current progress.

#### 12.3.3 Quest Reward Integration
Track valuable quest rewards:
- XP rewards → factor into skill planning.
- Unlocks → factor into navigation options.
- Items → factor into gear progression.

### 12.4 GearProgressionPlanner

#### 12.4.1 Upgrade Paths
For each combat style, define upgrade tiers:
- Melee: bronze → iron → steel → ... → dragon → barrows → bandos → ...
- Each tier: items required, stats granted, cost, requirements.
- **Ironman paths** may differ: prioritize obtainable items over BiS.

#### 12.4.2 Acquisition Methods
For each item, determine valid acquisition methods based on account type:

##### 12.4.2.1 Normal Account Methods
- Buy from GE (cost).
- Buy from NPC shop (cost, location).
- Obtain from quest.
- Obtain from drop (monster, rate).
- Craft (requirements, materials).

##### 12.4.2.2 Ironman Account Methods (GE Disabled)
When `IronmanState.isIronman()` is true, **exclude GE entirely**:
- ~~Buy from GE~~ — **DISABLED**
- Buy from NPC shop (cost, location, stock limits, restock timers).
- Obtain from quest (**PRIORITIZE** — guaranteed items).
- Obtain from drop (monster, rate, drop table analysis).
- Craft/Smith/Fletch (requirements, self-gathered materials).
- Collect from world spawns (item spawn locations, respawn timers).
- Thieve from NPCs (pickpocket loot tables).
- Grow via Farming (seeds, allotments, herb patches).
- Minigame rewards (Barb Assault, Pest Control, etc.).

##### 12.4.2.3 Ironman Acquisition Priority
When planning item acquisition for ironman:
1. **Quest rewards** — guaranteed, no RNG.
2. **Shop purchases** — reliable but may need world hopping for stock.
3. **Crafting** — if materials are obtainable.
4. **Minigame rewards** — time-gated but reliable.
5. **Monster drops** — RNG-dependent, plan for grind time.
6. **World spawns** — slow but zero requirements.

##### 12.4.2.4 Supply Chain Planning
For craftable items, plan the full resource chain:
```
Rune platebody acquisition:
├── Option A: Buy from Oziach (84,500gp, requires Dragon Slayer I)
├── Option B: Smith (99 Smithing required)
│   └── 5x Runite bars
│       └── 5x Runite ore (Mining 85, or buy from Ore seller)
│       └── 40x Coal (Mining, or Miscellania, or MLM)
├── Option C: Monster drop
│   └── Steel dragons (1/128), Iron dragons (1/128)
```

#### 12.4.3 Upgrade Recommendations
Given current gear and goals:
- Recommend next upgrade per slot.
- Factor in cost-efficiency (DPS gain per GP).
- Factor in acquisition difficulty.
- **Ironman**: Factor in acquisition time, not just GP cost.
- **Ironman**: Consider "stepping stone" upgrades (items easier to get than BiS that enable farming BiS).

#### 12.4.4 Ironman Gear Priority Adjustments
Some items are higher priority for ironman due to self-sufficiency:
- **Fighter torso** — free, powerful, no RNG (Barbarian Assault).
- **Barrows gloves** — quest-locked but guaranteed.
- **Fire cape** — skill-gated not RNG-gated.
- **Void equipment** — time investment but guaranteed.
- **Dragon defender** — farmable, relatively common drop.
- **Berserker ring (i)** — NMZ imbue makes it best-in-slot for effort.

De-prioritize:
- Bandos armor — significant grind, marginal upgrade over Barrows.
- Dragon boots — 1/128 from Spiritual mages, stuck on rune boots longer.

### 12.5 UnlockTracker

#### 12.5.1 Tracked Unlocks
- Teleport methods: spells, jewelry, tablets, POH portals.
- Transportation: fairy rings, spirit trees, gnome gliders, charter ships.
- Areas: quest-locked regions, skill-requirement areas.
- Features: NPC contact, house tabs, etc.

#### 12.5.2 Unlock Utilization
Navigation and planning systems query UnlockTracker:
- WebWalker checks available teleports for routing.
- TaskPlanner considers unlocks for method selection.
- ProgressionPlanner values unlocks in goal ordering.

---

## 12A. Ironman Mode Support

### 12A.1 IronmanRestrictions Service
Central singleton service that enforces ironman restrictions across all modules. Key methods to implement:

**Trading and Economy Restrictions**:
- `canUseGrandExchange()` - Returns false for all ironman types
- `canTradeWithPlayers()` - False except for Group Ironman with group members
- `canPickupPlayerDrops()` - False for all ironman types
- `canUseLootshare()` / `canUseCoinshare()` - False for all ironman types

**Acquisition Methods**:
- `getValidMethods(itemId)` - Returns list of valid acquisition methods (excludes GRAND_EXCHANGE for ironman)
- Valid ironman methods: MONSTER_DROP, QUEST_REWARD, SKILLING, NPC_SHOP, WORLD_SPAWN, MINIGAME

**Safety Configuration**:
- `isHardcore()` - Returns true if account type is HARDCORE_IRONMAN
- `getCombatSafetyConfig()` - Returns enhanced safety thresholds for HCIM (higher eat/flee thresholds, required Ring of Life)

**Group Ironman**:
- `canAccessGroupStorage()` - True only for GROUP_IRONMAN type
- `canTradeWithGroupMembers(player)` - Validates group membership (requires roster tracking)

**Content Restrictions**:
- `canUseMinigame(minigame)` - Blocks team-based minigames with tradeable rewards; allows NMZ, Pest Control, BA, etc.
- `canEnterInstance(bossId, isGroupInstance)` - Solo instances only for standard/HCIM; group instances for GIM with group members
- `canReceiveDrop(drop, killer)` - Can only loot own kills or group members' kills (GIM only)

**PvP and World Restrictions**:
- `canAttackPlayer(target)` - Blocks PvP for HCIM (unless explicitly enabled), allows for regular ironman
- `canUseWorld(worldType)` - Blocks PVP/DEADMAN/HIGH_RISK worlds for HCIM

### 12A.2 SelfSufficiencyPlanner
Plans item acquisition chains for ironman accounts:

#### 12A.2.1 Acquisition Planning
Given a target item:
1. Check if item is in bank/inventory.
2. If not, query `IronmanAcquisitionData` for sources.
3. For each source, calculate:
   - Time to acquire (farming, grinding, etc.).
   - Prerequisite items/levels needed.
   - Recursive dependencies (materials for crafting).
4. Return optimal acquisition plan.

#### 12A.2.2 Example: Abyssal Whip Acquisition
```
Target: Abyssal whip (4151)
Account: Ironman

Available methods:
1. Monster drop: Abyssal demon (1/512)
   - Requirements: 85 Slayer
   - Time estimate: ~2.5 hours at 250 kills/hr
   - Location: Slayer Tower (safest), Catacombs (prayer restore)
   
Plan:
├── IF Slayer < 85: Train Slayer to 85 first
│   └── Estimated time: varies based on current level
├── ELSE: Farm Abyssal demons
│   ├── Recommended gear: [check GearProgressionPlanner]
│   ├── Food needed: ~50 sharks estimated
│   ├── Prayer pots: ~10 estimated
│   └── Expected drops: ~205k gp in alchables during grind
```

### 12A.3 HCIMSafetyManager
Dedicated manager for hardcore ironman death prevention:

#### 12A.3.1 Pre-Activity Checks
Before any dangerous activity, `checkSafety(Activity)` validates:
- **Equipment**: Ring of Life equipped, emergency teleport available
- **Supplies**: Minimum food count (configurable, default 4)
- **Content**: Activity risk rating vs. config (block wilderness if disabled)
- **Combat**: Enemy max hit vs. current HP (block if enemy can combo for 60%+ HP)

Returns `SafetyCheckResult` with list of `SafetyIssue` enums. Block activity if any issues present.

#### 12A.3.2 Runtime Monitoring
During dangerous activities:
- Poll health every game tick.
- Track incoming damage patterns.
- Monitor food/prayer supplies.
- Detect multi-combat situations.
- Auto-trigger escape if thresholds breached.

#### 12A.3.3 Emergency Procedures
When escape is triggered:
1. **Immediate**: Stop all attack actions.
2. **Tick 1**: Click emergency teleport OR Ring of Life threshold reached.
3. **Tick 2**: If teleport failed, spam-click food.
4. **Tick 3**: If still in danger, click logout.
5. **Post-escape**: Log incident, notify user, pause automation.

#### 12A.3.4 Multi-Layered Failsafes

Implement defense-in-depth approach to absolute death prevention:

**Layer 1: Pre-Check (Before Engaging)**
- Verify all safety equipment present (Ring of Life, emergency teleport)
- Validate minimum food count (4+ pieces, 8+ for bosses)
- Calculate death probability score (see 12A.3.5)
- Block engagement if P(death) > 1%

**Layer 2: Runtime Monitoring (Every Tick During Combat)**
- Poll health, food count, prayer points
- Track incoming damage patterns
- Monitor multi-combat aggro
- Detect poison/venom status and escalation

**Layer 3: Panic Protocol (HP < Flee Threshold)**
- Stop all offensive actions immediately
- Execute emergency teleport (1-click items prioritized)
- Spam-click food while teleport animating
- If teleport fails, attempt logout

**Layer 4: Emergency Shutdown (All Failsafes Breached)**
If HP < 20% AND no food remaining AND emergency teleport unavailable:
- **Priority 1**: Call `client.requestLogout()` for graceful logout (may not work if in combat)
- **Priority 2**: Rapid teleport spam — cycle through all available teleport methods (jewelry, tabs, spells)
- **Priority 3**: Rapid food spam — eat any remaining food items including combo eats
- **Priority 4**: World hop attempt (30-second cooldown may prevent this)
- Log full incident context for post-mortem analysis (game state, combat log, item availability)
- Alert user via system notification if available

**Important**: `System.exit(0)` is NOT viable from plugin context (RuneLite's security restrictions prevent plugins from terminating the JVM). The client must exit through normal channels (logout, disconnect, or user intervention).

**Failsafe Testing**: Simulate worst-case scenarios (rapid damage, teleport failures, multi-combat) to verify no deaths occur.

#### 12A.3.5 Death Probability Scoring

Before every combat encounter, calculate probabilistic death risk:

**Scoring Function**:
```
P(death) = base_risk * enemy_factor * supply_factor * escape_factor * multi_factor * status_factor

Where:
- base_risk = 0.001 (0.1% baseline)
- enemy_factor = (enemy_max_hit / player_max_hp)^2
- supply_factor = 1.0 / (food_count + 1)
- escape_factor = has_emergency_teleport ? 0.5 : 2.0
- multi_factor = 1.0 + (0.3 * num_aggressors)  // Escalates with each additional attacker
- status_factor = venom_escalation_factor OR poison_factor (see below)
```

**Risk Thresholds**:
- **P(death) < 0.001 (0.1%)**: Safe - proceed with combat
- **P(death) 0.001-0.01 (0.1-1%)**: Caution - warn user, proceed if configured
- **P(death) > 0.01 (1%)**: BLOCK - refuse engagement unless explicit override

**Venom Escalation Tracking** (CRITICAL for HCIM):
Venom damage escalates each hit: 6 → 8 → 10 → 12 → ... → 20 (max)
- Track current venom damage level from previous hitsplats
- `venom_escalation_factor = 1.0 + (current_venom_damage / 10.0)`
- Example: At 20 venom damage, factor = 3.0 (tripled risk)
- Cure venom immediately when detected; antivenom+ prevents reapplication

**Poison Status Factor**:
- `poison_factor = 1.2` (constant damage, less dangerous than venom)
- Cure poison when convenient; not emergency-critical like venom

**Multi-Combat NPC Count**:
- Track actual number of NPCs currently attacking player (not just boolean)
- Each additional attacker increases risk by 30%
- Example: 1 NPC = 1.0x, 2 NPCs = 1.3x, 3 NPCs = 1.6x, 4 NPCs = 1.9x

**Combo Food Detection**:
When health critical (< flee threshold):
- Prioritize karambwan (0-tick combo food) for instant heal
- Use purple sweets if available (also 0-tick)
- Standard food has 3-tick delay (may be too slow in emergencies)

**Additional Factors**:
- Player skill levels (Defence, Hitpoints affect survival)
- Prayer points available (protection prayers reduce risk by 60-100% depending on type)
- Gear quality (higher defence → lower enemy_factor)
- Ring of Life equipped (reduces risk by 50% as last-resort failsafe at 10% HP)

**Override Configuration**: `hcimMaxDeathProbability` (double, 0.01-5.0, default: 1.0) — Maximum acceptable death probability percentage.

### 12A.4 Shop Run Integration
For ironman accounts, integrate NPC shop runs into resource planning:

#### 12A.4.1 Shop Run Scheduler
`ShopRunPlanner` tracks shop stock timers and schedules runs based on resource needs. Key shops: Ore Seller (Blast Furnace), Lundail (Mage Arena), Zaff's Staffs (Varrock), Charter Ships, Rogue's Den, Culinaromancer's Chest. `getRecommendedRuns(ResourceNeeds)` matches needed resources to shop availability, considering world hopping for stock refresh.

#### 12A.4.2 World Hopping for Stock
- Track which worlds have been visited for each shop.
- Implement world hop cooldown (30 seconds between hops).
- Prefer low-population worlds for shop stock.
- **HCIM**: Only hop to non-PvP, non-high-risk worlds.

---

## 13. Safety and Recovery

### 13.1 Death Handling
- Detect death via player state.
- URGENT task: respawn click.
- Post-death: navigate to death location or grave (configurable).
- Optional: abort current high-level task on death.
- **HCIM**: Death = status downgrade to regular ironman. Log event prominently. Pause all automation for user acknowledgment.

### 13.2 Combat Escape
- Monitor health during combat tasks.
- Below configurable threshold (default: 30%): flee to safe tile, eat food if available.
- Support for teleport escape (configurable: home teleport, teleport tabs, ring of life auto-detect).

#### 13.2.1 Standard Account Thresholds
| Threshold | Default | Action |
|-----------|---------|--------|
| Warning | 40% HP | Eat food if available |
| Flee | 30% HP | Move to safe tile, eat |
| Panic | 20% HP | Emergency teleport |

#### 13.2.2 HCIM Enhanced Thresholds
| Threshold | Default | Action |
|-----------|---------|--------|
| Caution | 70% HP | Pre-eat before next attack |
| Warning | 50% HP | Eat immediately, consider fleeing |
| Flee | 40% HP | Disengage combat, move to safe tile |
| Panic | 30% HP | Emergency teleport, spam food |
| Critical | 20% HP | Logout + teleport simultaneously |

#### 13.2.3 HCIM Required Safety Equipment
Automation will warn or refuse to start dangerous content without:
- **Ring of Life** (preferred) OR **Phoenix Necklace** — equipped at all times during combat.
- **Emergency Teleport** — one of: Royal seed pod, Ring of dueling, House teleport tab, Ectophial.
- **Sufficient Food** — minimum 4 pieces for any combat, 8+ for bosses/slayer.
- **Prayer Points** — minimum 20 points before engaging (for protection prayers).

#### 13.2.4 HCIM Content Restrictions
The following content requires explicit user override (`hcimAllowRiskyContent` config):
- **Wilderness** — all areas, any purpose.
- **Multi-combat zones** — without safeguard conditions.
- **Bosses with OHKO mechanics** — Jad, Zuk, Galvek, Vorkath (acid phase).
- **Poison/venom heavy content** — without antipoison/antivenom.
- **PvM minigames** — ToB, CoX (without experience).

Without override, these activities will be skipped and logged.

### 13.3 Disconnection Recovery

**Login State Machine** (comprehensive connection state handling):
- **States**: `LOGGED_OUT`, `LOGGING_IN`, `LOGGED_IN`, `HOPPING`
- **Detection**: Poll `client.getGameState()` → map to login states
- **Transitions**:
  - `LOGGED_OUT` → `LOGGING_IN` (after random 5-30s delay to avoid instant reconnects)
  - `LOGGING_IN` → `LOGGED_IN` (on successful login) or `LOGGED_OUT` (on failure)
  - `LOGGED_IN` → `HOPPING` (on world hop initiation) or `LOGGED_OUT` (on disconnect)
  - `HOPPING` → `LOGGED_IN` (on hop success) or `LOGGED_OUT` (on hop failure)

**Login Attempt Rate Limiting** (prevent account locks):
- Maximum 3 login attempts per 5 minutes
- If 3 failures: pause for 15 minutes before retrying
- Track consecutive failures; if 5 consecutive → alert user + pause automation

**World Hop Cooldown** (minimum 30 seconds between hops):
- Enforce 30-second minimum between world hops
- Queue hop requests if cooldown not elapsed
- Track hop history for pattern analysis

**Recovery Behavior**:
- On disconnect detection: Save current task state checkpoint
- After successful login: Resume task queue from last checkpoint
- Handle "too many login attempts" error gracefully (extended wait)

### 13.4 Stuck Detection
- If no meaningful state change for 60 seconds during active task: flag stuck.
- Stuck recovery: attempt camera rotation, small random walk, then abort task if still stuck.
- Invoke AI Director if basic recovery fails.
- Log stuck events for debugging.

### 13.5 Emergency Stop
- Global hotkey (configurable, default: END key) immediately halts all automation.
- Mouse and keyboard return to user control.
- State persisted for potential resume.

### 13.6 Random Event Handling
- Detect random event NPCs targeting player.
- Dismiss: click "dismiss" option.
- Optionally engage simple randoms (genie, classroom) for rewards.

### 13.7 World Type Management

Detect and enforce world type restrictions to prevent HCIM deaths and ensure compliance with game mechanics.

**World Type Detection**: Use `client.getWorldType()` which returns `EnumSet<WorldType>` containing: `PVP`, `DEADMAN`, `SKILL_TOTAL`, `HIGH_RISK`, etc.

**HCIM World Restrictions** (when `IronmanState.isHardcore()` is true):
- **Block PvP worlds**: `WorldType.PVP` or `WorldType.DEADMAN` are forbidden
- **Block high-risk worlds**: `WorldType.HIGH_RISK` forbidden unless `hcimAllowRiskyContent` enabled
- **Preferred worlds**: Low-population skill total requirement worlds (fewer players = less competition, safer)

**Login/World Hop Validation**:
- Subscribe to `GameStateChanged` event, check world type on `LOGGED_IN`
- If HCIM on unsafe world: call `client.requestLogout()`, pause automation, notify user
- Check `isWorldSafeForHCIM(worldTypes)` → returns false if contains PVP, DEADMAN, or HIGH_RISK (without override)

**World Selection Strategy** (per-account from behavioral profile):
1. **Primary world**: Always attempt `world_preferences.primary_world` first
2. **Alternate worlds**: Cycle through `alternate_worlds` if primary unavailable
3. **World type preference**: Filter by `world_type_preference` (SKILL_TOTAL, F2P, MEMBERS)
4. **Population preference**: Match `population_preference` range (LOW/MEDIUM/HIGH)
5. **HCIM overrides**: Safety restrictions override preferences (block PvP/Deadman/HIGH_RISK)

**World Hopping Safety**:
- Validate target world against profile preferences AND safety restrictions before hopping
- Maintain whitelist of known-safe worlds for HCIM
- Never hop to PvP/Deadman worlds regardless of config
- Prefer skill total worlds for bot detection avoidance

**Configuration**: `hcimSafeWorldsOnly` (boolean, default: true) restricts world hopping to HCIM-safe worlds

---

## 14. Configuration

### 14.1 RuneLite Config Panel
Expose via standard RuneLite config interface:
- Master enable/disable toggle.
- Current task display (read-only).
- Global delay multiplier (0.5x - 2.0x).
- Break frequency (low/medium/high).
- Auto-login toggle.
- Emergency stop hotkey binding.
- AI Director enable/disable.
- Claude API key entry.
- Combat settings (eat threshold, prayer usage, loot threshold).

#### 14.1.1 Ironman Configuration
Ironman-specific settings in dedicated `@ConfigSection`:

**Core Ironman Settings**:
| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `ironmanMode` | boolean | false | Enable ironman-specific restrictions |
| `ironmanType` | enum | STANDARD_IRONMAN | Account type (STANDARD/HARDCORE/GROUP) |

**HCIM Safety Settings** (safety-critical, defaults conservative):
| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `hcimSafetyLevel` | enum | PARANOID | Safety protocol level (RELAXED/CAUTIOUS/PARANOID) |
| `hcimRequireRingOfLife` | boolean | true | Refuse combat without RoL equipped |
| `hcimMinFoodCount` | int (1-28) | 4 | Min food to start combat |
| `hcimEatThreshold` | int (30-90) | 65 | HP% to trigger eating |
| `hcimFleeThreshold` | int (20-70) | 40 | HP% to trigger escape |
| `hcimEmergencyTeleport` | enum | ROYAL_SEED_POD | Preferred escape method |
| `hcimAllowWilderness` | boolean | false | Enable wilderness (DANGEROUS) |
| `hcimAllowRiskyContent` | boolean | false | Enable OHKO bosses (DANGEROUS) |

### 14.2 Profile Configuration
JSON file (`profiles/default.json`) for detailed behavior tuning:
- All timing parameters from Section 4.
- All input parameters from Section 3.
- Task-specific overrides.
- Multiple profiles selectable at runtime.

### 14.3 Data Files
Separate data directory (`data/`) containing:

#### 14.3.0 Wiki-Sourced Data (Dynamic via WikiDataService)
The following data is fetched dynamically from the OSRS Wiki API (`https://oldschool.runescape.wiki/api.php`) rather than maintained in static files:
- **Object/NPC/Item definitions** — Use RuneLite's `client.getObjectDefinition()`, `client.getNpcDefinition()`, `itemManager.getItemComposition()`.
- **Slayer creature data** — Query wiki monster pages for locations, weaknesses, slayer requirements.
- **Drop tables** — Parse monster pages for drop table infoboxes.
- **Shop inventories** — Query shop pages for stock, prices, and locations.
- **Ironman acquisition sources** — Query item pages for "Item sources" data.

**Wiki API Integration**: Query `https://oldschool.runescape.wiki/api.php` with parameters `action=parse&page={PageName}&prop=wikitext&format=json`. See Section 8A.2 for full details.

**Caching Strategy:**
- Cache wiki responses for 24 hours (game data rarely changes).
- Use local fallback files for offline operation.
- Refresh cache on plugin startup if network available.

#### 14.3.1 Static Data Files (Custom/Curated)
- `locations.json` — Named location coordinates (custom navigation nodes).
- `web.json` — Navigation web graph (custom pathing structure).
- `teleports.json` — Teleport method data (static enum with requirements).
- `gear_tiers.json` — Equipment progression data (opinionated/curated).
- `training_methods.json` — Skill training method data (XP rates, curated recommendations).
- `supply_chains.json` — Resource dependency graphs for crafting (curated for ironman).
- `hcim_risk_ratings.json` — Content risk assessments for HCIM safety (custom ratings).

#### 14.3.2 Ironman Acquisition Data (Wiki-Sourced)
Instead of maintaining a static `ironman_acquisition.json` file, item sources are fetched from the OSRS Wiki API. The WikiDataService parses item pages to extract the "Item sources" infobox.

**Example Wiki API Query**: `GET /api.php?action=parse&page=Abyssal_whip&prop=wikitext&format=json`

**Parsed Data Structure** (cached locally, abbreviated):
```json
{
  "items": {
    "4151": {
      "name": "Abyssal whip",
      "ironman_sources": [
        {"method": "monster_drop", "source": "Abyssal demon", "drop_rate": "1/512", "requirements": {"slayer": 85}}
      ]
    },
    "2550": {
      "name": "Ring of life",
      "hcim_priority": "CRITICAL",
      "ironman_sources": [
        {"method": "crafting", "level": 45, "materials": [{"item_id": 1615, "quantity": 1}, {"item_id": 1635, "quantity": 1}]},
        {"method": "npc_shop", "source": "Grum's Gold Exchange", "restock_time": "player_sells_only"}
      ]
    }
  }
}
```

**Source Methods**: `monster_drop`, `npc_shop`, `crafting`, `smithing`, `quest_reward`, `minigame`. Each method includes relevant metadata (drop rates, requirements, materials, prices).

#### 14.3.3 HCIM Risk Ratings Data Format
Content risk assessments in `hcim_risk_ratings.json`:

**Risk Levels**: `EXTREME` (block by default), `HIGH`, `MEDIUM`, `LOW`

**Example entries** (abbreviated):
| Content | Risk Level | Block Default | Notes |
|---------|------------|---------------|-------|
| Wilderness | EXTREME | Yes | PKers, multi-combat, skull tricks |
| Fight Caves | HIGH | No | Jad hits 97; requires reliable prayer switches |
| Barrows | LOW | No | Max hit 25; bring prayer potions |
| Multi-combat Slayer | MEDIUM | No | Monitor NPC aggro |

Each entry includes: `name`, `risk_level`, `block_by_default`, optional `requirements`, `max_hit`, `dangerous_waves`, and `notes`.

---

## 15. Logging and Debugging

### 15.1 Log Levels
- `ERROR`: Exceptions, failed tasks, stuck states, API failures.
- `WARN`: Unexpected but recoverable situations.
- `INFO`: Task start/complete, breaks, session events, AI requests.
- `DEBUG`: Individual actions, timing values, state changes.
- `TRACE`: Per-tick state dumps, mouse coordinates.

### 15.2 Audit Log
Optional file-based log capturing:
- Timestamp of every action.
- Action type and target.
- Delay applied.
- Success/failure status.
- AI Director requests and responses.

Purpose: Post-session analysis of behavior patterns.

### 15.3 Debug Overlay
In-game overlay (toggleable) showing:
- Current task and subtask.
- Fatigue level.
- Next scheduled break.
- Target object/NPC highlight.
- Planned path visualization.
- Combat state (incoming attacks, prayer status).
- Slayer task progress.
- AI Director status (last query, response time).
- **Ironman**: Current acquisition plan progress.
- **HCIM**: Safety status panel:
  - Ring of Life: ✓/✗
  - Emergency teleport: ✓/✗
  - Food count: X/minimum
  - Current HP vs flee threshold
  - Risk rating of current activity

### 15.4 Detection Vector Mitigation

Comprehensive analysis of known bot detection methods and systematic countermeasures.

#### 15.4.1 Known Detection Vectors

OSRS employs multiple detection methods to identify automated gameplay. These vectors are well-documented in bot detection literature and must be systematically countered via mitigations in Section 15.4.2:

1. **Tick-Perfect Action Timing** - Actions occurring at exact tick intervals
2. **Input Pattern Repetition** - Identical action sequences repeated >100 times
3. **Statistical Distribution Anomalies** - Unnatural timing distribution patterns
4. **Camera-Mouse Correlation** - Lack of natural camera movement with mouse
5. **XP Gain Rate Consistency** - Sustained maximum theoretical XP/hour
6. **Break Pattern Regularity** - Breaks at predictable intervals
7. **Instant Reaction Times** - Actions <100ms after game events
8. **Zero Input Errors** - Perfect execution without human mistakes
9. **Logout Pattern Analysis** - Instant logout after specific events
10. **Action Sequencing Invariance** - Multi-step tasks always in identical order

#### 15.4.2 Mitigation Strategies

Systematic countermeasures implemented across the framework:

**Mitigation 1: Timing Variance Layering**
- Apply ≥3 independent randomization layers to all delays: base distribution sample, fatigue multiplier, attention state multiplier, session variance, micro-variance.
- Never allow two consecutive delays to be identical.

**Mitigation 2: Action Sequence Randomization** (See Section 3.4.2)
- Never execute multi-step sequences in same order twice in a row
- Weighted randomization based on behavioral profile
- Track recent sequences, penalize exact repetition

**Mitigation 3: Camera-Mouse Coupling** (See Section 3.4.3)
- 30% of long mouse movements trigger camera adjustment
- Idle camera drift during waits
- Periodic "look around" rotations

**Mitigation 4: Intentional Inefficiency Injection**
- Base inefficiency rate: 5-10% of actions
- Types: hesitation (500-1500ms hover), misclick correction, backtracking, redundant actions, action cancellation
- Fatigue multiplier: increased rate when fatigued

**Mitigation 5: XP Rate Throttling**
- Track XP/hour for current activity
- Optional throttling to 80-95% of theoretical maximum
- Inject 2-10 second AFK moments when exceeding configured rate

**Mitigation 6: Reaction Time Realism**
- Never react instantly to game events (<100ms)
- Baseline: 150-600ms (Poisson λ=250ms) + attention state modifier + surprise factor
- Unexpected events add 200±100ms delay

**Mitigation 7: Error Injection**
- Configurable base rates: typos (1.5%), misclicks (2.5%), action cancellations (1%)
- Fatigue increases error rates proportionally

**Mitigation 8: Logout Behavior Humanization** (See Section 3.4.5)
- Never instant logout after events
- 2-15 second "recovery" delay after danger
- Varied logout methods (button, ESC menu, window close)

**Mitigation 9: Long-Term Behavioral Evolution** (See Section 3.4.6)
- Gradual skill improvement over weeks
- Break pattern drift
- Action preference evolution
- Prevents same behavior fingerprint across months

#### 15.4.3 Detection Mitigation Checklist

Before each release, verify all mitigations are active:

- [ ] **Timing variance**: At least 3 layers of randomization on all delays
- [ ] **Action sequences**: Never identical sequence 3+ times consecutively  
- [ ] **Camera coupling**: Implemented for 30%+ of mouse movements
- [ ] **Inefficiency rate**: 5-10% of actions are suboptimal
- [ ] **Reaction times**: All event responses have 150-600ms delay minimum
- [ ] **Error injection**: Misclicks, typos, action cancellations present
- [ ] **Logout delays**: No instant logouts, 2-15s delay after events
- [ ] **XP rate capping**: Optional throttling to realistic human rates
- [ ] **Behavioral profiles**: Per-account uniqueness with gradual drift
- [ ] **Break randomization**: No predictable patterns across sessions

#### 15.4.4 Statistical Self-Validation

Implement automated detection of bot-like patterns in own behavior (hourly validation of last 100+ actions):

**Tests to Perform**:
- **Chi-squared test** on action delays (p-value < 0.05 indicates too uniform)
- **Sequence repetition detection** (flag if same sequence repeated >10 times)
- **Error rate validation** (flag if <1% error rate - too perfect)
- **Reaction time distribution** (flag if average <180ms - too fast)

**Auto-Adjustment on Detection**: Increase variance, inject more inefficiency, force extended break, or regenerate behavioral profile.

#### 15.4.5 Adaptive Behavior Adjustment

If self-validation detects bot-like patterns, automatically adjust behavior:

**Adjustment Actions**:
1. **Increase variance**: Multiply all σ values by 1.2-1.5x for next hour
2. **Force inefficiency**: Increase inefficiency injection rate to 15-20% temporarily
3. **Extended break**: Take immediate 5-15 minute break to reset patterns
4. **Behavioral reset**: Regenerate behavioral profile with new seed
5. **Alert user**: Log warning that detection risk was identified and mitigated

**Configuration**:
| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `enableBehaviorValidation` | boolean | true | Automatically detect and mitigate bot-like patterns |
| `detectionRiskResponse` | enum | AUTO_ADJUST | Response action: `LOG_ONLY`, `AUTO_ADJUST`, `PAUSE_AND_ALERT` |

---

## 16. Testing Requirements

### 16.1 Unit Tests
- All timing distributions verified against expected statistical properties.
- State condition logic 100% branch covered.
- Task pre-condition and completion logic covered.
- Combat calculations (DPS, switch timing) verified.

### 16.2 Integration Tests
- Mock client for task execution verification.
- Mock Quest Helper bridge for quest execution tests.
- Mock Claude API for AI Director tests.
- Full slayer task dry-runs.

### 16.3 Statistical Validation
- Record 1000+ action delay samples; verify distribution matches specification.
- Record 1000+ click positions; verify 2D gaussian properties.
- Compare action patterns against recorded human gameplay (if available).

### 16.4 Combat Tests
- Verify eat timing prevents deaths.
- Verify prayer switching accuracy.
- Verify gear switch completion rates.

### 16.5 Ironman Tests
- Verify GE is never used when ironman mode enabled.
- Verify item acquisition plans only use valid ironman sources.
- Verify supply chain planning correctly sequences resource gathering.
- Verify shop run planner handles stock limits and world hopping.
- Verify drop table planning produces reasonable time estimates.

### 16.6 HCIM Safety Tests
- Verify Ring of Life requirement blocks combat when missing.
- Verify flee threshold triggers escape at configured HP percentage.
- Verify emergency teleport executes within 3 game ticks.
- Verify multi-combat detection triggers escape correctly.
- Verify food minimum enforcement triggers bank trip.
- Verify risky content blocking prevents wilderness entry.
- Simulate damage scenarios and verify no deaths occur above flee threshold.
- Stress test escape protocol with rapid damage intake.

---

## 17. Implementation Phases

Implementation follows dependency order: Foundation → Core Automation → Navigation → Quest/Wiki Integration → Combat → Slayer → Progression → Ironman → HCIM Safety → Polish

All phases must pass acceptance criteria in Section 19 before proceeding to next phase. Critical path items:
- Phase 1: RuneLite plugin setup, Guice DI, Robot controllers, Linux/Xvfb compatibility
- Phase 4: Quest Helper bridge with `@PluginDependency`, Wiki API integration, Claude API client
- Phase 7A: Ironman restrictions enforcement, self-sufficiency planning
- Phase 7B: HCIM multi-layer failsafes, death probability scoring

---

## 18. References

Consult the following documentation via MCP:

### 18.1 RuneLite Development
- RuneLite Plugin Development: https://github.com/runelite/runelite/wiki
- RuneLite API Javadocs: https://static.runelite.net/api/runelite-api/
- RuneLite Client Javadocs: https://static.runelite.net/api/runelite-client/
- RuneLite Wiki Plugin Source: https://github.com/runelite/runelite/tree/master/runelite-client/src/main/java/net/runelite/client/plugins/wiki

### 18.2 Quest Helper Integration
- Quest Helper Plugin Source: https://github.com/Zoinkwiz/quest-helper
- Key packages to understand:
  - `com.questhelper.questhelpers` — Base quest helper classes
  - `com.questhelper.steps` — Quest step types (NpcStep, ObjectStep, etc.)
  - `com.questhelper.requirements` — Requirement system
  - `com.questhelper.questinfo` — Quest metadata and wiki URLs
  - `com.questhelper.collections` — ItemCollections for grouped items

### 18.3 OSRS Wiki API
- OSRS Wiki: https://oldschool.runescape.wiki/
- **MediaWiki API Endpoint**: `https://oldschool.runescape.wiki/api.php`
- API Documentation: https://oldschool.runescape.wiki/api.php (add `?action=help` for docs)
- Key API actions:
  - `action=opensearch` — Search/autocomplete
  - `action=parse` — Parse page content
  - `action=query` — Query page metadata
- Example queries:
  ```
  # Search for items
  GET /api.php?action=opensearch&search=abyssal+whip&format=json
  
  # Get monster drop table
  GET /api.php?action=parse&page=Abyssal_demon&prop=wikitext&format=json
  
  # Get item sources
  GET /api.php?action=parse&page=Abyssal_whip&prop=wikitext&format=json
  ```
- Be respectful: Rate limit requests to ~30/minute
- UTM parameters: Add `utm_source=runelite` for attribution

### 18.4 Other Libraries
- Gaussian/statistical distribution implementations: standard Java libraries or Apache Commons Math
- OkHttp for HTTP requests: https://square.github.io/okhttp/
- Gson for JSON parsing: https://github.com/google/gson

---

## 19. Acceptance Criteria

The implementation is complete when:

1. A naive observer cannot distinguish bot actions from human actions in a 30-minute gameplay recording.
2. Any quest supported by Quest Helper completes successfully when selected (test minimum 10 quests across difficulty tiers).
3. Continuous skilling sessions run for 4+ hours without stuck states.
4. Slayer tasks complete successfully for 10 consecutive assignments across 3 different masters.
5. Combat encounters maintain >95% survival rate with appropriate gear and food.
6. Account progression planner generates valid, executable plans for common goals (e.g., "complete Recipe for Disaster").
7. Statistical analysis of timing/click data shows no detectable patterns distinguishable from reference human data.
8. All Phase 1-8 features implemented and passing test suites.
9. AI Director successfully interprets and executes 90%+ of Quest Helper steps without manual intervention.

### 19.1 Ironman-Specific Acceptance Criteria

10. **GE Restriction Enforcement**: Ironman mode never attempts to use Grand Exchange; all item acquisition uses valid ironman methods.
11. **Self-Sufficiency Planning**: Given a goal item, the system generates a valid acquisition plan using only ironman-available methods (drops, shops, crafting, quests).
12. **Supply Chain Completion**: Ironman skill training correctly identifies and executes prerequisite resource gathering (e.g., mine ore before smithing training).
13. **Shop Run Efficiency**: Shop run planner correctly identifies optimal shops and handles world hopping for stock refresh.

### 19.2 HCIM Safety Acceptance Criteria

14. **Zero Preventable Deaths**: In 100 hours of HCIM testing, zero deaths occur due to automation failure when safety protocols are enabled.
15. **Ring of Life Enforcement**: System refuses to enter combat without Ring of Life when `hcimRequireRingOfLife` is enabled.
16. **Emergency Escape Success**: When flee threshold is reached, successful escape occurs within 3 game ticks in 99%+ of cases.
17. **Food Minimum Enforcement**: System banks for food before food count drops below configured minimum during extended combat.
18. **Risky Content Blocking**: Wilderness and OHKO-mechanic content is blocked unless explicitly overridden via config.
19. **Multi-Combat Detection**: System detects when multiple NPCs are attacking and triggers escape protocol within 2 ticks.
20. **Health Monitoring Accuracy**: Eat threshold triggers reliably; no deaths occur above the configured flee threshold with food available.

### 19.3 Behavioral Fingerprinting Acceptance Criteria

21. **Behavioral Uniqueness**: Statistical analysis of 100 bot sessions shows <95% correlation between any two accounts' behavioral profiles (mouse patterns, break timing, action sequences). Each account must exhibit distinct "personality" in timing variance, camera preferences, and idle behaviors.
22. **Long-term Consistency**: Account behavioral profiles drift <10% over 50 hours of gameplay, demonstrating stable "personality" without detectable pattern repetition. Drift mechanisms must simulate natural skill improvement while maintaining recognizable behavioral fingerprint.

---

## 20. Docker Deployment

**Deployment Model**: One container per account. Each container runs isolated with dedicated proxy, resources, and behavioral profile.

**Benefits**: Proxy isolation (one proxy per container), crash isolation, simple scaling (`docker-compose up -d accountN`), resource limits per account.

**Quick Start**:
```bash
cp .env.example .env        # Configure accounts and proxies
docker-compose build
docker-compose up -d        # Start all accounts
docker logs -f rocinante_account1
```

**Files**: `Dockerfile`, `docker-compose.yml`, `entrypoint.sh`, `.env.example` (see inline comments for configuration).

**Critical**: Backup `./profiles/` directory - contains unique behavioral fingerprints that cannot be regenerated.
