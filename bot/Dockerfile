# =============================================================================
# Stage 1: Build Quest Helper dependency
# =============================================================================
FROM eclipse-temurin:17-jdk-noble AS quest-helper-builder

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone --depth 1 https://github.com/Zoinkwiz/quest-helper.git

WORKDIR /build/quest-helper
RUN chmod +x gradlew && ./gradlew jar --no-daemon

# =============================================================================
# Stage 2: Build Rocinante plugin
# =============================================================================
FROM eclipse-temurin:17-jdk-noble AS rocinante-builder

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

# =============================================================================
# Stage 3: Runtime image
# =============================================================================
FROM eclipse-temurin:17-jdk-noble AS runtime

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
    # System utilities (standard on desktops, missing in minimal containers)
    lsb-release \
    pciutils \
    usbutils \
    lshw \
    net-tools \
    iproute2 \
    mesa-utils \
    # Themes and icons (makes Java/AWT look more native)
    gnome-themes-extra \
    adwaita-icon-theme \
    # Locale and timezone (anti-fingerprint: consistent locale data)
    locales \
    tzdata \
    # Font rendering (anti-fingerprint: diverse fonts like real desktop)
    # Base fonts (always enabled)
    fonts-liberation \
    fonts-dejavu-core \
    fonts-freefont-ttf \
    fonts-noto-core \
    fontconfig \
    # Optional fonts (selectively enabled per profile via fontconfig in entrypoint)
    fonts-firacode \
    fonts-roboto \
    fonts-ubuntu \
    fonts-inconsolata \
    fonts-lato \
    fonts-open-sans \
    fonts-cascadia-code \
    fonts-hack \
    fonts-jetbrains-mono \
    fonts-droid-fallback \
    fonts-wine \
    fonts-cantarell \
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
    # Machine ID generation (anti-fingerprint)
    dbus \
    && rm -rf /var/lib/apt/lists/*

# =============================================================================
# Anti-fingerprint: Generate locale (must be done as root)
# =============================================================================
# Generate en_US.UTF-8 locale for consistent locale fingerprint
RUN sed -i '/en_US.UTF-8/s/^# //g' /etc/locale.gen && locale-gen

# Disable optional fonts by default - entrypoint enables per profile
# This creates per-account font fingerprints
RUN for font in firacode roboto ubuntu inconsolata lato open-sans cascadia-code hack jetbrains-mono droid wine cantarell; do \
        rm -f /etc/fonts/conf.d/*${font}* 2>/dev/null || true; \
    done && fc-cache -f

# NOTE: machine-id is NOT generated here - it MUST be bind-mounted from host
# per-profile. Container will fail to start properly without it, which is
# the intended behavior to catch configuration errors early.

# Create runelite user (non-root for security)
RUN useradd -m -s /bin/bash runelite
USER runelite
WORKDIR /home/runelite

# Set display environment
ENV DISPLAY=:99
ENV JAVA_HOME=/opt/java/openjdk

# Anti-fingerprint: Consistent locale settings (matches generated locale)
ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8
ENV LANGUAGE=en_US:en

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
    && mkdir -p .local/share/bolt-launcher \
    && mkdir -p .local/share/bolt-launcher/.runelite/rocinante/profiles

# Anti-fingerprint: Create standard XDG user directories (looks like real desktop)
RUN mkdir -p ~/Desktop ~/Documents ~/Downloads ~/Pictures ~/Music ~/Videos \
    && mkdir -p ~/.config \
    && echo 'XDG_DESKTOP_DIR="$HOME/Desktop"' > ~/.config/user-dirs.dirs \
    && echo 'XDG_DOCUMENTS_DIR="$HOME/Documents"' >> ~/.config/user-dirs.dirs \
    && echo 'XDG_DOWNLOAD_DIR="$HOME/Downloads"' >> ~/.config/user-dirs.dirs \
    && echo 'XDG_PICTURES_DIR="$HOME/Pictures"' >> ~/.config/user-dirs.dirs \
    && echo 'XDG_MUSIC_DIR="$HOME/Music"' >> ~/.config/user-dirs.dirs \
    && echo 'XDG_VIDEOS_DIR="$HOME/Videos"' >> ~/.config/user-dirs.dirs

# Copy Rocinante plugin from build stage
COPY --from=rocinante-builder --chown=runelite:runelite /build/build/libs/rocinante-*.jar .runelite/plugins/

# Copy Quest Helper plugin (built in stage 1, required for quest data)
# Rename to consistent name for entrypoint.sh to find it
COPY --from=quest-helper-builder --chown=runelite:runelite /build/quest-helper/build/libs/quest-helper-*.jar .runelite/plugins/quest-helper.jar

# Copy automation scripts and templates
COPY --chown=runelite:runelite entrypoint.sh /home/runelite/
COPY --chown=runelite:runelite bolt-login.sh /home/runelite/
COPY --chown=runelite:runelite bolt_login.py /home/runelite/
COPY --chown=runelite:runelite post_launch.py /home/runelite/
COPY --chown=runelite:runelite templates/ /home/runelite/templates/
RUN chmod +x /home/runelite/entrypoint.sh /home/runelite/bolt-login.sh /home/runelite/bolt_login.py /home/runelite/post_launch.py

# Expose VNC port for debugging (optional)
EXPOSE 5900

ENTRYPOINT ["/home/runelite/entrypoint.sh"]
