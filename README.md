# Rocinante

Full automation system for Old School RuneScape via RuneLite plugin. Includes a web management UI with live VNC streaming, bot status monitoring, and task queuing.

## Requirements

- Docker & Docker Compose
- Bun (for web UI development)

## Quick Start

```bash
# Build and run everything
docker compose up -d

# Web UI available at http://localhost:3000
```

## Project Structure

```
.
├── bot/                      # RuneLite plugin + container runtime
│   ├── src/main/java/com/rocinante/
│   │   ├── core/             # Plugin entry, task executor
│   │   ├── behavior/         # Player profiles, humanization
│   │   ├── input/            # Mouse/keyboard simulation
│   │   ├── navigation/       # Pathfinding, web walker
│   │   ├── tasks/            # Task system and implementations
│   │   ├── combat/           # Combat loop, prayer, gear
│   │   ├── quest/            # Quest automation
│   │   ├── slayer/           # Slayer task management
│   │   ├── progression/      # Account goals, skill planning
│   │   ├── ironman/          # Ironman restrictions, HCIM safety
│   │   ├── data/             # Wiki API, teleport data
│   │   ├── status/           # Bot-to-web status reporting
│   │   └── ...
│   ├── Dockerfile            # Bot container (Xvfb, VNC, Bolt launcher)
│   └── entrypoint.sh         # Container startup script
│
├── web/                      # Management web UI
│   ├── src/
│   │   ├── api/              # Bun server (Docker control, VNC proxy)
│   │   ├── client/           # SolidJS frontend
│   │   └── shared/           # Shared types
│   └── data/                 # Bot configs, runtime status
│
├── docker-compose.yml        # Service orchestration
├── REQUIREMENTS.md           # Full specification
└── PHASES.md                 # Implementation roadmap
```

## Features

### Web Management UI
- **Bot Dashboard**: Create/configure accounts, start/stop containers, live status monitoring
- **VNC Streaming**: Watch bot gameplay in real-time via a high-performance VNC connection
- **Screenshot Gallery**: Auto-captured level ups, pet drops, deaths, valuable drops, boss kills

### Anti-Detection
- **Behavioral Profiles**: Per-account fingerprints (mouse speed, click variance, typing WPM, break patterns) that persist across sessions with gradual drift over time
- **Environment Fingerprinting**: Each container gets unique machine-id, display config (resolution/DPI/depth), timezone, fonts, hostname, and JVM GC algorithm
- **Humanization**: Fatigue modeling, attention state, inefficiency injection, predictive hovering, idle behaviors

### Navigation
- **Cost-Based Pathfinding**: ShortestPath plugin integration with transport cost penalties, such as avoiding law rune use for HCIM bots
- **Transport Methods**: Fairy rings, spirit trees, gnome gliders, canoes, charter ships, minigame teleports, quetzal

### Skills & Combat  
- **Training Tasks**: Agility courses, cooking, firemaking, fletching, prayer (bone burying), thieving, slayer
- **Combat System**: Prayer flicking (tick-perfect/lazy/always-on), gear switching, special attacks, target prioritization

### Account Modes
- **Ironman Support**: Resource-aware pathing, wilderness avoidance, death prevention safety checks
- **Death Recovery**: Gravestone retrieval, Death's Office handling

### Automation
- **Quest System**: Quest Helper plugin bridge with step translation for support of all quests. Includes a custom quest for Tutorial Island handling.
- **Grand Exchange**: Automatic gravestone retrieval and Death's Office handling
- **Breaks & Logout**: Scheduled breaks, random event handling, session limits
