# Rocinante

RuneLite automation framework. See `REQUIREMENTS.md` for full spec.

## Requirements

- Docker

## Build

```bash
docker build -f build.Dockerfile -o build/libs .
```

Output: `build/libs/rocinante-<version>.jar`

## Run

```bash
# Build plugin first, then:
docker-compose up -d
```

## Project Structure

```
src/main/java/com/rocinante/
├── core/           # Plugin entry, task executor
├── config/         # RuneLite config interface
├── input/          # Mouse/keyboard controllers
├── timing/         # Delays, fatigue, breaks
├── tasks/          # Task system
├── state/          # Game state tracking
├── navigation/     # Pathfinding, web walker
├── integration/    # Quest Helper bridge, Claude API
├── combat/         # Combat loop, prayer, gear
├── slayer/         # Slayer task management
├── progression/    # Account goals, skill planning
├── ironman/        # Ironman restrictions, HCIM safety
├── data/           # Wiki service, teleport data
└── util/           # Helpers
```

## Local Gradle (optional)

If you want IDE support without Docker:

```bash
# Clone and build Quest Helper locally
git clone --depth 1 https://github.com/Zoinkwiz/quest-helper.git /tmp/qh
cd /tmp/qh && ./gradlew jar
mkdir -p libs && cp /tmp/qh/build/libs/quest-helper-*.jar libs/

# Then update build.gradle to use libs/quest-helper.jar
```

Or just use Docker for everything—it's simpler.

