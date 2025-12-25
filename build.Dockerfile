# build.Dockerfile - Multi-stage build for Rocinante plugin
# Usage: docker build -f build.Dockerfile -o build/libs .

# Stage 1: Build Quest Helper dependency
FROM openjdk:17-jdk-slim AS quest-helper-builder

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone --depth 1 https://github.com/Zoinkwiz/quest-helper.git

WORKDIR /build/quest-helper
RUN chmod +x gradlew && ./gradlew jar --no-daemon

# Stage 2: Build Rocinante plugin
FROM openjdk:17-jdk-slim AS rocinante-builder

WORKDIR /build

# Copy Quest Helper JAR as compile dependency
COPY --from=quest-helper-builder /build/quest-helper/build/libs/quest-helper-*.jar /deps/quest-helper.jar

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

