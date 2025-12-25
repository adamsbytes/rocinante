FROM eclipse-temurin:17-jdk-jammy

# JDK 17 LTS for long-term support (Eclipse Temurin - successor to OpenJDK images)
# Requires --add-opens for java.awt.Robot access (configured in entrypoint.sh)

# Install Xvfb, Wine, and automation dependencies
# Wine is needed to run the Jagex Launcher for Jagex account authentication
RUN dpkg --add-architecture i386 && \
    apt-get update && apt-get install -y \
    # Display and VNC
    xvfb \
    x11vnc \
    # Wine for Jagex Launcher
    wine \
    wine32 \
    wine64 \
    # GUI automation tools
    xdotool \
    # TOTP code generation for 2FA
    oathtool \
    # Screenshot/image tools for automation detection
    imagemagick \
    # Network utilities
    wget \
    curl \
    ca-certificates \
    # Process management
    procps \
    # For Wine font rendering
    fonts-liberation \
    fonts-wine \
    && rm -rf /var/lib/apt/lists/*

# Create runelite user (non-root for security)
RUN useradd -m -s /bin/bash runelite
USER runelite
WORKDIR /home/runelite

# Set up Wine environment
ENV WINEARCH=win64
ENV WINEPREFIX=/home/runelite/.wine
ENV WINEDEBUG=-all
ENV DISPLAY=:99

# Initialize Wine prefix (creates .wine directory structure)
RUN wineboot --init && \
    # Wait for wine to finish initialization
    while pgrep -x wineserver > /dev/null; do sleep 1; done

# Download Jagex Launcher installer from official CDN
# Source: https://cdn.jagex.com/Jagex%20Launcher%20Installer.exe
RUN mkdir -p /home/runelite/jagex && \
    wget -q -O "/home/runelite/jagex/Jagex Launcher Installer.exe" \
    "https://cdn.jagex.com/Jagex%20Launcher%20Installer.exe"

# Install Jagex Launcher via Wine (silent install)
RUN wine "/home/runelite/jagex/Jagex Launcher Installer.exe" /S && \
    while pgrep -x wineserver > /dev/null; do sleep 1; done && \
    rm "/home/runelite/jagex/Jagex Launcher Installer.exe"

# Download RuneLite launcher (fallback and for plugin installation)
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

# Copy automation scripts
COPY --chown=runelite:runelite entrypoint.sh /home/runelite/
COPY --chown=runelite:runelite jagex-login.sh /home/runelite/
RUN chmod +x /home/runelite/entrypoint.sh /home/runelite/jagex-login.sh

# Expose VNC port for debugging (optional)
EXPOSE 5900

ENTRYPOINT ["/home/runelite/entrypoint.sh"]
