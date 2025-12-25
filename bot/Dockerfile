FROM eclipse-temurin:17-jdk-noble

# JDK 17 LTS for long-term support (Eclipse Temurin on Ubuntu 24.04 Noble)
# Ubuntu 24.04 required for Bolt launcher's glibc 2.38+ requirement
# Requires --add-opens for java.awt.Robot access (configured in entrypoint.sh)

# Install Xvfb, VNC, and dependencies for Bolt launcher
# Bolt is a native Linux launcher - no Wine needed!
RUN apt-get update && apt-get install -y \
    # Display and VNC
    xvfb \
    x11vnc \
    # Lightweight window manager and desktop
    openbox \
    feh \
    x11-xserver-utils \
    # GUI automation tools
    xdotool \
    # TOTP code generation for 2FA
    oathtool \
    # Screenshot/image tools for automation detection
    imagemagick \
    scrot \
    # Python for image-based automation
    python3 \
    python3-pip \
    python3-opencv \
    python3-numpy \
    # Network utilities
    wget \
    curl \
    ca-certificates \
    unzip \
    # Process management
    procps \
    # Font rendering
    fonts-liberation \
    # Bolt dependencies (CEF/Chromium based)
    libnss3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libasound2t64 \
    libpango-1.0-0 \
    libcairo2 \
    libatspi2.0-0 \
    libgtk-3-0 \
    # Additional Bolt dependencies
    libarchive13 \
    # Virtual audio (so apps don't complain about missing sound)
    pulseaudio \
    # XDG utilities (xdg-mime, xdg-open, etc)
    xdg-utils \
    && rm -rf /var/lib/apt/lists/*

# Create runelite user (non-root for security)
RUN useradd -m -s /bin/bash runelite
USER runelite
WORKDIR /home/runelite

# Set display environment
ENV DISPLAY=:99
ENV JAVA_HOME=/opt/java/openjdk

# Download and install Bolt launcher (native Linux launcher for OSRS/RuneLite)
# Bolt handles Jagex account authentication without needing Wine
ARG BOLT_VERSION=0.20.6
RUN wget -q -O /tmp/bolt.zip \
    "https://codeberg.org/Adamcake/Bolt/releases/download/${BOLT_VERSION}/Bolt-Linux.zip" && \
    unzip -q /tmp/bolt.zip -d /home/runelite && \
    rm /tmp/bolt.zip && \
    chmod +x /home/runelite/bolt-launcher/bolt && \
    ls -la /home/runelite/bolt-launcher/

# Download RuneLite.jar as fallback
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

# Create Bolt config and data directories
# These MUST exist so Docker volumes inherit correct ownership
RUN mkdir -p .config/bolt-launcher \
    && mkdir -p .local/share/bolt-launcher

# Copy Rocinante plugin (will be built separately)
# COPY --chown=runelite:runelite build/libs/rocinante-*.jar .runelite/plugins/

# Copy automation scripts and templates
COPY --chown=runelite:runelite entrypoint.sh /home/runelite/
COPY --chown=runelite:runelite bolt-login.sh /home/runelite/
COPY --chown=runelite:runelite bolt_login.py /home/runelite/
COPY --chown=runelite:runelite templates/ /home/runelite/templates/
RUN chmod +x /home/runelite/entrypoint.sh /home/runelite/bolt-login.sh /home/runelite/bolt_login.py

# Expose VNC port for debugging (optional)
EXPOSE 5900

ENTRYPOINT ["/home/runelite/entrypoint.sh"]
