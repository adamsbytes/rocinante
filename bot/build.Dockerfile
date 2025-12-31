# build.Dockerfile - Multi-stage build for Rocinante plugin
# Usage: docker build -f build.Dockerfile -o build/libs .

# Stage 1: Build Quest Helper dependency
FROM eclipse-temurin:17-jdk-noble AS quest-helper-builder

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone --depth 1 https://github.com/Zoinkwiz/quest-helper.git

WORKDIR /build/quest-helper
RUN chmod +x gradlew && ./gradlew jar --no-daemon

# Stage 2: Build Shortest Path dependency
FROM eclipse-temurin:17-jdk-noble AS shortest-path-builder

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone --depth 1 https://github.com/Skretzo/shortest-path.git

WORKDIR /build/shortest-path
RUN chmod +x gradlew && ./gradlew jar --no-daemon

# Stage 2: Build Rocinante plugin
FROM eclipse-temurin:17-jdk-noble AS rocinante-builder

WORKDIR /build

# Copy built dependencies
COPY --from=quest-helper-builder /build/quest-helper/build/libs/quest-helper-*.jar /deps/quest-helper.jar
COPY --from=shortest-path-builder /build/shortest-path/build/libs/shortest-path-*.jar /deps/shortest-path.jar

# Copy Gradle wrapper and build files first (better layer caching)
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/

# Download dependencies (cached unless build.gradle changes)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src/ src/
COPY runelite-plugin.properties ./

# Build the plugin
RUN ./gradlew jar --no-daemon

# Stage 3: Export artifact only
FROM scratch AS export
COPY --from=rocinante-builder /build/build/libs/rocinante-*.jar /
