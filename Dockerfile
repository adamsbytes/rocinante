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

# Pre-configure RuneLite settings
# - Enable Quest Helper from Plugin Hub
# - Enable Screenshot plugin with all important event captures
RUN echo 'runelite.externalPlugins=quest-helper' > .runelite/settings.properties && \
    echo '' >> .runelite/settings.properties && \
    echo '# Screenshot plugin configuration' >> .runelite/settings.properties && \
    echo 'screenshot=true' >> .runelite/settings.properties && \
    echo 'screenshot:rewards=true' >> .runelite/settings.properties && \
    echo 'screenshot:levels=true' >> .runelite/settings.properties && \
    echo 'screenshot:kingdom=true' >> .runelite/settings.properties && \
    echo 'screenshot:pets=true' >> .runelite/settings.properties && \
    echo 'screenshot:deaths=true' >> .runelite/settings.properties && \
    echo 'screenshot:valuableDropThreshold=100000' >> .runelite/settings.properties && \
    echo 'screenshot:valuabledrops=true' >> .runelite/settings.properties && \
    echo 'screenshot:untradedvaluabledrops=true' >> .runelite/settings.properties && \
    echo 'screenshot:bossKills=true' >> .runelite/settings.properties && \
    echo 'screenshot:pvpKills=false' >> .runelite/settings.properties && \
    echo 'screenshot:friendChatKicks=false' >> .runelite/settings.properties && \
    echo 'screenshot:duels=false' >> .runelite/settings.properties && \
    echo 'screenshot:collectionLogEntries=true' >> .runelite/settings.properties && \
    echo 'screenshot:combatAchievements=true' >> .runelite/settings.properties && \
    echo 'screenshot:displayDate=true' >> .runelite/settings.properties && \
    echo 'screenshot:notifyWhenTaken=false' >> .runelite/settings.properties

# Create screenshots directory
RUN mkdir -p .runelite/screenshots

# Copy Rocinante plugin (will be built separately)
# COPY --chown=runelite:runelite build/libs/rocinante-*.jar .runelite/plugins/

# Copy entrypoint script
COPY --chown=runelite:runelite entrypoint.sh /home/runelite/
RUN chmod +x /home/runelite/entrypoint.sh

# Expose VNC port for debugging (optional)
EXPOSE 5900

ENTRYPOINT ["/home/runelite/entrypoint.sh"]

