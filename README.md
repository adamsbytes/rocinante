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

- **Web UI**: Create/manage bots, view live VNC, monitor status, queue tasks
- **Humanization**: Per-account behavioral profiles (mouse patterns, timing, breaks)
- **Anti-fingerprint**: Unique machine IDs, display configs, fonts per bot
- **Quest Helper Integration**: Automated quest completion via reflection bridge
- **Navigation**: Full web walker with realistic pathing
- **Combat**: Prayer flicking, gear switching, safe-spotting
- **Ironman Support**: Resource tracking, HCIM death prevention
