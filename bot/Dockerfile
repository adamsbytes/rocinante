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
# Stage 2: Build Shorest Path dependency
# =============================================================================
FROM eclipse-temurin:17-jdk-noble AS shortest-path-builder

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone --depth 1 https://github.com/Skretzo/shortest-path.git

WORKDIR /build/shortest-path
RUN chmod +x gradlew && ./gradlew jar --no-daemon

# =============================================================================
# Stage 3: Build Rocinante plugin
# =============================================================================
FROM eclipse-temurin:17-jdk-noble AS rocinante-builder

WORKDIR /build

# Copy Quest Helper JAR as compile dependency
COPY --from=quest-helper-builder /build/quest-helper/build/libs/quest-helper-*.jar deps/quest-helper.jar

# Copy Shortest Path JAR as compile dependency
COPY --from=shortest-path-builder /build/shortest-path/build/libs/shortest-path-*.jar deps/shortest-path.jar

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
# Stage 4: Runtime image
# =============================================================================
FROM eclipse-temurin:17-jdk-noble AS runtime

# JDK 17 LTS for long-term support (Eclipse Temurin on Ubuntu 24.04 Noble)
# Ubuntu 24.04 required for Bolt launcher's glibc 2.38+ requirement
# Requires --add-opens for java.awt.Robot access (configured in entrypoint.sh)

# Install Xvfb, VNC, and dependencies for Bolt launcher
# Bolt is a native Linux launcher - no Wine needed!
RUN apt-get update && apt-get install -y \
    # Build tools for spoofing library
    gcc \
    libc6-dev \
    # Display and VNC
    xvfb \
    x11vnc \
    # Lightweight window manager and desktop
    openbox \
    feh \
    x11-xserver-utils \
    x11-utils \
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
    # JSON processing for config file parsing
    jq \
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
    fonts-dejavu-extra \
    fonts-noto-mono \
    fonts-noto-ui-core \
    fonts-liberation-sans-narrow \
    fonts-font-awesome \
    fonts-opensymbol \
    fonts-liberation2 \
    fonts-croscore \
    fonts-crosextra-carlito \
    fonts-crosextra-caladea \
    fonts-noto-color-emoji \
    fonts-freefont-otf \
    fonts-urw-base35 \
    fonts-texgyre \
    # Bolt dependencies (CEF/Chromium based)
    libnss3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxkbcommon0 \
    libxcomposite1 \
    picom \
    upower \
    dbus-x11 \
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
# Build POSIX thread compatibility extensions
# =============================================================================
# Compatibility shims for /proc and /sys filesystem operations.
# Uses /etc/ld.so.preload instead of LD_PRELOAD env var to ensure the library
# is loaded by the dynamic linker BEFORE any application (including Java JVM)
# can cache environment variables - avoiding the LD_PRELOAD race condition.
COPY pthreads_ext.c /tmp/
RUN gcc -shared -fPIC -o /usr/lib/libpthreads_ext.so /tmp/pthreads_ext.c -ldl -lpthread -lm && \
    rm /tmp/pthreads_ext.c && \
    chmod 755 /usr/lib/libpthreads_ext.so && \
    # Use /etc/ld.so.preload instead of LD_PRELOAD env var to avoid race condition
    # where Java may cache env before hooks load. ld.so.preload is read by the
    # dynamic linker at process startup, before any application code runs.
    echo "/usr/lib/libpthreads_ext.so" > /etc/ld.so.preload

# =============================================================================
# Anti-fingerprint: Generate locale (must be done as root)
# =============================================================================
# Generate en_US.UTF-8 locale for consistent locale fingerprint
RUN sed -i '/en_US.UTF-8/s/^# //g' /etc/locale.gen && locale-gen

# Disable optional fonts by default - entrypoint enables per profile
# This creates per-account font fingerprints
RUN for font in firacode roboto ubuntu inconsolata lato open-sans cascadia-code hack jetbrains-mono droid wine cantarell dejavu-extra noto-mono noto-ui-core liberation-sans-narrow font-awesome opensymbol liberation2 croscore crosextra-carlito crosextra-caladea noto-color-emoji freefont-otf urw-base35 texgyre; do \
        rm -f /etc/fonts/conf.d/*${font}* 2>/dev/null || true; \
    done && fc-cache -f

# NOTE: machine-id is NOT generated here - it MUST be bind-mounted from host
# per-profile. Container will fail to start properly without it, which is
# the intended behavior to catch configuration errors early.

# =============================================================================
# Steam Deck Identity: /etc/os-release and system config
# =============================================================================
# Make container identify as SteamOS 3.6 (Steam Deck's OS)
RUN echo 'NAME="SteamOS"' > /etc/os-release && \
    echo 'PRETTY_NAME="SteamOS 3.6.20"' >> /etc/os-release && \
    echo 'VERSION_ID="3.6.20"' >> /etc/os-release && \
    echo 'ID=steamos' >> /etc/os-release && \
    echo 'ID_LIKE=arch' >> /etc/os-release && \
    echo 'ANSI_COLOR="1;35"' >> /etc/os-release && \
    echo 'HOME_URL="https://www.steampowered.com/"' >> /etc/os-release && \
    echo 'BUG_REPORT_URL="https://github.com/ValveSoftware/SteamOS/issues"' >> /etc/os-release && \
    echo 'SUPPORT_URL="https://help.steampowered.com/"' >> /etc/os-release && \
    echo 'LOGO="steamos"' >> /etc/os-release && \
    echo 'BUILD_ID=20241216.1000' >> /etc/os-release && \
    echo 'VARIANT_ID=steamdeck' >> /etc/os-release

# Ensure dbus machine-id symlink exists (some apps check both locations)
RUN mkdir -p /var/lib/dbus && \
    ln -sf /etc/machine-id /var/lib/dbus/machine-id

# Create deck user (Steam Deck default username, UID 1000, GID 998 wheel group)
# Delete existing UID 1000 user if present (e.g., 'ubuntu' in base image)
RUN userdel -r $(getent passwd 1000 | cut -d: -f1) 2>/dev/null || true && \
    groupadd -g 998 wheel 2>/dev/null || true && \
    useradd -m -s /bin/bash -u 1000 -g 998 deck
USER deck
WORKDIR /home/deck

# Set display environment
ENV DISPLAY=:0
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
    unzip -q /tmp/bolt.zip -d /home/deck && \
    rm /tmp/bolt.zip && \
    chmod +x /home/deck/bolt-launcher/bolt && \
    ls -la /home/deck/bolt-launcher/

# Download RuneLite.jar as fallback
RUN wget -q https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar

# Create directories for plugin and profiles
RUN mkdir -p .runelite/plugins .runelite/rocinante/profiles .runelite/logs .runelite/plugin-hub

# Create screenshots directory (settings.properties is generated from template at runtime)
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
COPY --from=rocinante-builder --chown=deck:wheel /build/build/libs/qol-tweaks-*.jar .runelite/plugins/

# Copy Quest Helper plugin (built in stage 1, required for quest data)
# Rename to consistent name for entrypoint.sh to find it
COPY --from=quest-helper-builder --chown=deck:wheel /build/quest-helper/build/libs/quest-helper-*.jar .runelite/plugins/quest-helper.jar

# Copy Shortest Path plugin (built in stage 2, required for pathfinding)
COPY --from=shortest-path-builder --chown=deck:wheel /build/shortest-path/build/libs/shortest-path-*.jar .runelite/plugins/shortest-path.jar

# Copy automation scripts, templates, and settings
COPY --chown=deck:wheel entrypoint.sh /home/deck/
COPY --chown=deck:wheel bolt-login.sh /home/deck/
COPY --chown=deck:wheel bolt_login.py /home/deck/
COPY --chown=deck:wheel post_launch.py /home/deck/
COPY --chown=deck:wheel templates/ /home/deck/templates/
COPY --chown=deck:wheel settings.template.properties /home/deck/
RUN chmod +x /home/deck/entrypoint.sh /home/deck/bolt-login.sh /home/deck/bolt_login.py /home/deck/post_launch.py

# Expose VNC port for debugging (optional)
EXPOSE 5900

ENTRYPOINT ["/home/deck/entrypoint.sh"]
