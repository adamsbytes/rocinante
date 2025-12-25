FROM openjdk:17-jdk-slim

# JDK 17 LTS for long-term support
# Requires --add-opens for java.awt.Robot access (configured in entrypoint.sh)

# Install Xvfb and dependencies
RUN apt-get update && apt-get install -y \
    xvfb \
    x11vnc \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create runelite user (non-root for security)
RUN useradd -m -s /bin/bash runelite
USER runelite
WORKDIR /home/runelite

# Download RuneLite launcher
RUN wget -q https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar

# Create directories for plugin and profiles
RUN mkdir -p .runelite/plugins .runelite/rocinante/profiles .runelite/logs .runelite/plugin-hub

# Pre-configure RuneLite to enable Quest Helper from Plugin Hub
# This seeds the settings so Quest Helper is installed on first launch
RUN echo 'runelite.externalPlugins=quest-helper' > .runelite/settings.properties

# Copy Rocinante plugin (will be built separately)
# COPY --chown=runelite:runelite build/libs/rocinante-*.jar .runelite/plugins/

# Copy entrypoint script
COPY --chown=runelite:runelite entrypoint.sh /home/runelite/
RUN chmod +x /home/runelite/entrypoint.sh

# Expose VNC port for debugging (optional)
EXPOSE 5900

ENTRYPOINT ["/home/runelite/entrypoint.sh"]

