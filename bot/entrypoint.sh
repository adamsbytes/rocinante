#!/bin/bash
set -e

echo "Starting Rocinante RuneLite Automation Container"
echo "================================================="
echo "Launcher: Bolt (native Linux launcher)"
echo "================================================="

# Display number from profile (required)
if [ -z "$DISPLAY_NUMBER" ]; then
    echo "ERROR: DISPLAY_NUMBER env var not set - profile configuration error"
    exit 1
fi
DISPLAY_NUM="$DISPLAY_NUMBER"
export DISPLAY=:${DISPLAY_NUM}

# =============================================================================
# Environment Fingerprint: Read from profile-specific env vars
# These MUST be set by docker.ts - no fallbacks, fail if missing
# =============================================================================

# Timezone from profile (should match proxy geolocation)
# Prefer TIMEZONE from profile, fall back to TZ if set by docker
if [ -n "$TIMEZONE" ]; then
    export TZ="$TIMEZONE"
fi
echo "Timezone: $TZ"

# Screen resolution from profile (required)
if [ -z "$SCREEN_RESOLUTION" ]; then
    echo "ERROR: SCREEN_RESOLUTION env var not set - profile configuration error"
    exit 1
fi
SCREEN_RES="$SCREEN_RESOLUTION"
echo "Screen resolution: $SCREEN_RES"

# Screen depth from profile (required)
if [ -z "$SCREEN_DEPTH" ]; then
    echo "ERROR: SCREEN_DEPTH env var not set - profile configuration error"
    exit 1
fi
echo "Screen depth: $SCREEN_DEPTH"

# Display DPI from profile (required)
if [ -z "$DISPLAY_DPI" ]; then
    echo "ERROR: DISPLAY_DPI env var not set - profile configuration error"
    exit 1
fi
echo "Display DPI: $DISPLAY_DPI"
echo "Display number: $DISPLAY_NUM"

# Additional fonts from profile (optional, can be empty)
echo "Additional fonts: ${ADDITIONAL_FONTS:-none}"

# Clean up stale Xvfb processes and lock files from previous runs
# This is necessary when container is restarted (not recreated)
echo "Cleaning up previous display state..."
pkill -9 Xvfb 2>/dev/null || true
rm -f /tmp/.X${DISPLAY_NUM}-lock 2>/dev/null || true
rm -f /tmp/.X11-unix/X${DISPLAY_NUM} 2>/dev/null || true

# Clean up stale Chromium/CEF lock files from previous container runs
# These persist in the mounted volume and block Bolt from starting
echo "Cleaning up Bolt/Chromium lock files..."
BOLT_DATA="$HOME/.local/share/bolt-launcher"
rm -f "$BOLT_DATA/SingletonLock" 2>/dev/null || true
rm -f "$BOLT_DATA/SingletonSocket" 2>/dev/null || true
rm -f "$BOLT_DATA/SingletonCookie" 2>/dev/null || true
rm -rf "$BOLT_DATA/CefCache/SingletonLock" 2>/dev/null || true
rm -rf "$BOLT_DATA/CefCache/SingletonSocket" 2>/dev/null || true
rm -rf "$BOLT_DATA/CefCache/SingletonCookie" 2>/dev/null || true
sleep 1

# Start Xvfb virtual display with profile-specific resolution
echo "Starting Xvfb on display :${DISPLAY_NUM} with resolution ${SCREEN_RES}x${SCREEN_DEPTH}..."
Xvfb :${DISPLAY_NUM} -screen 0 ${SCREEN_RES}x${SCREEN_DEPTH} -ac +extension GLX +render -noreset &
XVFB_PID=$!
sleep 2

# Verify Xvfb started successfully
if ! kill -0 $XVFB_PID 2>/dev/null; then
    echo "ERROR: Xvfb failed to start"
    exit 1
fi

# Start window manager for proper window handling
echo "Starting Openbox window manager..."
openbox &
sleep 1

# Set a nice desktop background (dark gradient)
xsetroot -solid "#1a1a2e"

# =============================================================================
# Anti-fingerprint: Configure display and fonts to look like real desktop
# =============================================================================

# Set DPI from profile (anti-fingerprint: varies per account)
echo "Setting display DPI to ${DISPLAY_DPI}..."
xrandr --dpi $DISPLAY_DPI 2>/dev/null || true

# Add fake monitor name for more realistic display fingerprint
# Extract width and height from SCREEN_RES (format: WIDTHxHEIGHT)
SCREEN_WIDTH=$(echo $SCREEN_RES | cut -d'x' -f1)
SCREEN_HEIGHT=$(echo $SCREEN_RES | cut -d'x' -f2)
echo "Configuring virtual monitor ${SCREEN_RES}..."
# Create a mode for the resolution (modeline values are approximate but functional)
xrandr --newmode "${SCREEN_RES}_60.00" 74.50 $SCREEN_WIDTH $((SCREEN_WIDTH+64)) $((SCREEN_WIDTH+128)) $((SCREEN_WIDTH+384)) $SCREEN_HEIGHT $((SCREEN_HEIGHT+3)) $((SCREEN_HEIGHT+8)) $((SCREEN_HEIGHT+28)) -hsync +vsync 2>/dev/null || true
xrandr --addmode screen "${SCREEN_RES}_60.00" 2>/dev/null || true

# Enable profile-specific fonts via fontconfig
if [ -n "$ADDITIONAL_FONTS" ]; then
    echo "Enabling profile-specific fonts: $ADDITIONAL_FONTS"
    mkdir -p ~/.config/fontconfig/conf.d
    for font in $ADDITIONAL_FONTS; do
        # Find and symlink the font config (font names don't have fonts- prefix)
        font_conf=$(ls /usr/share/fontconfig/conf.avail/*${font}* 2>/dev/null | head -1)
        if [ -n "$font_conf" ]; then
            ln -sf "$font_conf" ~/.config/fontconfig/conf.d/ 2>/dev/null || true
            echo "  Enabled font: $font"
        fi
    done
fi

# Rebuild font cache with profile-specific fonts
fc-cache -f 2>/dev/null || true

# =============================================================================
# Anti-fingerprint: Generate deterministic junk files
# =============================================================================
generate_junk_files() {
    local seed_str="${HOSTNAME:-unknown}"
    local seed=0
    
    for (( i=0; i<${#seed_str}; i++ )); do
        local char="${seed_str:$i:1}"
        local val=$(printf '%d' "'$char")
        seed=$(( (seed * 31 + val) % 1000000 ))
    done
    
    local num_files=$(( (seed % 6) + 3 ))
    
    local files=(
        "Downloads/ubuntu-22.04.3-desktop-amd64.iso"
        "Documents/notes.txt"
        "Desktop/.directory"
        "Downloads/install.sh"
        "Documents/todo.md"
        "Pictures/screenshot-2024.png"
        "Downloads/package.deb"
        "Documents/readme.txt"
        "Desktop/bookmarks.html"
        "Music/.nomedia"
        "Videos/.gitkeep"
        "Downloads/archive.zip"
    )
    
    echo "Generating $num_files deterministic junk files..."
    for (( i=0; i<num_files; i++ )); do
        local idx=$(( (seed + i * 17) % ${#files[@]} ))
        local file="${files[$idx]}"
        local path="$HOME/$file"
        
        mkdir -p "$(dirname "$path")"
        
        if [[ "$file" == *.iso ]] || [[ "$file" == *.zip ]] || [[ "$file" == *.deb ]]; then
            touch "$path"
        else
            echo "# Placeholder" > "$path"
        fi
        
        local days_ago=$(( (seed + i * 7) % 90 + 1 ))
        touch -d "$days_ago days ago" "$path" 2>/dev/null || true
        
        echo "  Created: $file"
    done
}
generate_junk_files

# Hide container indicators from environment
unset container 2>/dev/null || true

# Start PulseAudio for virtual sound (apps expect audio device)
echo "Starting PulseAudio..."
pulseaudio --start --exit-idle-time=-1 2>/dev/null || true

# Start VNC server on Unix socket (not TCP - reduces fingerprint)
# Socket path is in the bind-mounted status directory for web server access
VNC_SOCKET_PATH="$HOME/.local/share/bolt-launcher/.runelite/rocinante/vnc.sock"
echo "Starting VNC server on Unix socket: $VNC_SOCKET_PATH"
rm -f "$VNC_SOCKET_PATH" 2>/dev/null || true
x11vnc -display :${DISPLAY_NUM} -bg -nopw -unixsock "$VNC_SOCKET_PATH" -xkb -forever -shared
sleep 1

# Verify x11vnc started
if ! pgrep -x x11vnc > /dev/null; then
    echo "ERROR: x11vnc failed to start"
    exit 1
fi

# Make socket accessible from host (web server runs as different user)
chmod 777 "$VNC_SOCKET_PATH"
echo "VNC server started on socket: $VNC_SOCKET_PATH"

# Ensure Quest Helper is configured for Plugin Hub install
# Write to BOTH settings locations (HOME and BOLT) to cover all launch modes
SETTINGS_FILE="$HOME/.runelite/settings.properties"
BOLT_SETTINGS="$HOME/.local/share/bolt-launcher/.runelite/settings.properties"

for settings in "$SETTINGS_FILE" "$BOLT_SETTINGS"; do
    mkdir -p "$(dirname "$settings")"
    echo "Configuring Quest Helper in: $settings"
    
    # Ensure externalPlugins includes quest-helper
    if [ ! -f "$settings" ] || ! grep -q "runelite.externalPlugins=.*quest-helper" "$settings" 2>/dev/null; then
        if [ -f "$settings" ]; then
            sed -i '/^runelite\.externalPlugins=/d' "$settings" 2>/dev/null || true
        fi
        echo "runelite.externalPlugins=quest-helper" >> "$settings"
    fi
    
    # Enable Quest Helper plugin
    if ! grep -q "^runelite\.questhelperplugin=true" "$settings" 2>/dev/null; then
        sed -i '/^runelite\.questhelperplugin=/d' "$settings" 2>/dev/null || true
        sed -i '/^questhelpervars\./d' "$settings" 2>/dev/null || true
        cat >> "$settings" << EOF
runelite.questhelperplugin=true
questhelpervars.selected-assist-level=true
EOF
    fi
done

# Build JVM arguments for RuneLite (passed via environment)
export RUNELITE_JVM_ARGS="-Djava.awt.headless=false"
RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS --add-opens=java.desktop/sun.awt=ALL-UNNAMED"
RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Xmx${JVM_HEAP_MAX:-2G}"
RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Xms${JVM_HEAP_MIN:-1G}"

# GC algorithm from profile (required)
if [ -z "$GC_ALGORITHM" ]; then
    echo "ERROR: GC_ALGORITHM env var not set - profile configuration error"
    exit 1
fi
case "$GC_ALGORITHM" in
    G1GC)
        RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -XX:+UseG1GC"
        ;;
    ParallelGC)
        RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -XX:+UseParallelGC"
        ;;
    ZGC)
        RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -XX:+UseZGC"
        ;;
    *)
        echo "ERROR: Unknown GC_ALGORITHM '$GC_ALGORITHM'"
        exit 1
        ;;
esac
echo "GC Algorithm: $GC_ALGORITHM"

# Configure Rocinante via environment variables
if [ -n "$IRONMAN_MODE" ]; then
    RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Drocinante.ironman.enabled=$IRONMAN_MODE"
fi

if [ -n "$IRONMAN_TYPE" ]; then
    RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Drocinante.ironman.type=$IRONMAN_TYPE"
fi

if [ -n "$HCIM_SAFETY_LEVEL" ]; then
    RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Drocinante.hcim.safetyLevel=$HCIM_SAFETY_LEVEL"
fi

if [ -n "$LAMP_SKILL" ]; then
    RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Drocinante.random.lampSkill=$LAMP_SKILL"
fi

if [ -n "$CLAUDE_API_KEY" ]; then
    RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Drocinante.claude.apiKey=$CLAUDE_API_KEY"
fi

export RUNELITE_JVM_ARGS

# Display configuration
echo "================================================="
echo "Account Email: ${ACCOUNT_EMAIL:-<not set>}"
echo "Character Name: ${CHARACTER_NAME:-<not set>}"
echo "2FA Enabled: $([ -n "$TOTP_SECRET" ] && echo 'Yes (TOTP)' || echo 'No')"
echo "Ironman Mode: ${IRONMAN_MODE:-false}"
echo "Ironman Type: ${IRONMAN_TYPE:-N/A}"
echo "Game Size: ${GAME_SIZE:-960x540}"
echo "Quest Helper: Pre-configured for auto-install"
echo "VNC: Enabled (port 5900)"
echo "Fast Track: $([ -f "$JAGEX_SESSION_FILE" ] && [ -s "$JAGEX_SESSION_FILE" ] && echo 'Available (saved session)' || echo 'Not available (first run)')"
echo "================================================="

# Check for Bolt launcher
BOLT_PATH="/home/runelite/bolt-launcher/bolt"
if [ ! -f "$BOLT_PATH" ]; then
    echo "ERROR: Bolt launcher not found at $BOLT_PATH"
    exit 1
fi

echo "Found Bolt launcher at: $BOLT_PATH"

# =========================================================================
# PATHS AND CREDENTIAL CHECK
# =========================================================================
BOLT_HOME="$HOME/.local/share/bolt-launcher"
REPO_DIR="$BOLT_HOME/.runelite/repository2"
PLUGIN_JAR="$BOLT_HOME/.runelite/plugins/rocinante-0.1.0-SNAPSHOT.jar"
QUEST_HELPER_JAR="$BOLT_HOME/.runelite/plugins/quest-helper.jar"
CREDENTIALS_FILE="$BOLT_HOME/.runelite/credentials.properties"
BOLT_RUNELITE_PLUGINS="$BOLT_HOME/.runelite/plugins"
# Persistent Jagex session env vars - enables fast track on subsequent launches
JAGEX_SESSION_FILE="$BOLT_HOME/jagex_session.env"

# Copy plugins to Bolt's RuneLite plugins directory
mkdir -p "$BOLT_RUNELITE_PLUGINS"
if [ -f "$HOME/.runelite/plugins/rocinante-"*.jar ]; then
    echo "Copying Rocinante plugin to Bolt's plugin directory..."
    cp "$HOME/.runelite/plugins/rocinante-"*.jar "$BOLT_RUNELITE_PLUGINS/"
else
    echo "WARNING: Rocinante plugin JAR not found in ~/.runelite/plugins/"
fi

if [ -f "$HOME/.runelite/plugins/quest-helper.jar" ]; then
    echo "Copying Quest Helper plugin to Bolt's plugin directory..."
    cp "$HOME/.runelite/plugins/quest-helper.jar" "$BOLT_RUNELITE_PLUGINS/"
else
    echo "WARNING: Quest Helper JAR not found in ~/.runelite/plugins/"
fi
echo "Plugins in Bolt directory:"
ls -la "$BOLT_RUNELITE_PLUGINS/"

# JVM module opens required for RuneLite's reflection-heavy code on JDK 17+
# These prevent InaccessibleObjectException and NoSuchFieldException errors
JAVA_OPENS=""
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/java.lang=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/java.util=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/java.io=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/java.net=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/java.security=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/sun.security.ssl=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/sun.net.www.protocol.https=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.desktop/sun.awt=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.desktop/java.awt=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.desktop/java.awt.event=ALL-UNNAMED"
JAVA_OPENS="$JAVA_OPENS --add-opens=java.desktop/sun.java2d=ALL-UNNAMED"

# Function to build classpath from repository JARs + our plugin
build_classpath() {
    RUNELITE_CP=""
    for jar in "$REPO_DIR"/*.jar; do
        if [ -n "$RUNELITE_CP" ]; then
            RUNELITE_CP="$RUNELITE_CP:$jar"
        else
            RUNELITE_CP="$jar"
        fi
    done
    # Add our plugin and Quest Helper to the classpath
    RUNELITE_CP="$RUNELITE_CP:$PLUGIN_JAR"
    if [ -f "$QUEST_HELPER_JAR" ]; then
        RUNELITE_CP="$RUNELITE_CP:$QUEST_HELPER_JAR"
    fi
    echo "$RUNELITE_CP"
}

# Function to configure RuneLite settings
configure_runelite_settings() {
    RUNELITE_SETTINGS="$BOLT_HOME/.runelite/settings.properties"
    PROFILES_DIR="$BOLT_HOME/.runelite/profiles2"
    mkdir -p "$(dirname "$RUNELITE_SETTINGS")"
    
    # Set game/client size from environment (default to Xvfb resolution)
    GAME_SIZE="${GAME_SIZE:-960x540}"
    echo "Configuring RuneLite for resolution: $GAME_SIZE"
    
    # Set default world from environment (default to 418 - safe F2P world)
    DEFAULT_WORLD="${PREFERRED_WORLD:-418}"
    echo "Configuring default world: $DEFAULT_WORLD"
    
    # Update global settings.properties (preserve externalPlugins)
    sed -i '/^runelite\.gameSize=/d' "$RUNELITE_SETTINGS" 2>/dev/null || true
    sed -i '/^runelite\.clientSize=/d' "$RUNELITE_SETTINGS" 2>/dev/null || true
    sed -i '/^defaultworld\./d' "$RUNELITE_SETTINGS" 2>/dev/null || true
    
    cat >> "$RUNELITE_SETTINGS" << EOF
runelite.gameSize=$GAME_SIZE
defaultworld.defaultWorld=$DEFAULT_WORLD
defaultworld.useLastWorld=false
EOF
    
    # Ensure Quest Helper is in externalPlugins
    if ! grep -q "runelite.externalPlugins=.*quest-helper" "$RUNELITE_SETTINGS" 2>/dev/null; then
        sed -i '/^runelite\.externalPlugins=/d' "$RUNELITE_SETTINGS" 2>/dev/null || true
        echo "runelite.externalPlugins=quest-helper" >> "$RUNELITE_SETTINGS"
        echo "Added Quest Helper to externalPlugins"
    fi
    
    # Enable Quest Helper plugin in settings.properties (fallback when no profile exists)
    if ! grep -q "^runelite\.questhelperplugin=true" "$RUNELITE_SETTINGS" 2>/dev/null; then
        sed -i '/^runelite\.questhelperplugin=/d' "$RUNELITE_SETTINGS" 2>/dev/null || true
        sed -i '/^questhelpervars\./d' "$RUNELITE_SETTINGS" 2>/dev/null || true
        cat >> "$RUNELITE_SETTINGS" << EOF
runelite.questhelperplugin=true
questhelpervars.selected-assist-level=true
EOF
        echo "Enabled Quest Helper plugin in settings"
    fi
    
    # IMPORTANT: RuneLite uses profile-specific config files that override settings.properties
    # Update ALL existing profile files to ensure settings take effect
    if [ -d "$PROFILES_DIR" ]; then
        echo "Updating profile configs in $PROFILES_DIR..."
        for profile_file in "$PROFILES_DIR"/*.properties; do
            if [ -f "$profile_file" ]; then
                echo "  Updating: $(basename "$profile_file")"
                # Remove existing settings we're about to set
                sed -i '/^defaultworld\./d' "$profile_file" 2>/dev/null || true
                sed -i '/^runelite\.questhelperplugin=/d' "$profile_file" 2>/dev/null || true
                sed -i '/^runelite\.externalPlugins=/d' "$profile_file" 2>/dev/null || true
                sed -i '/^questhelpervars\./d' "$profile_file" 2>/dev/null || true
                # Add our settings - enable Quest Helper plugin
                # Note: RuneLite uses profile configs, NOT settings.properties after first launch
                # externalPlugins tells RuneLite to install from Plugin Hub
                # questhelperplugin=true enables the plugin once installed
                cat >> "$profile_file" << EOF
defaultworld.defaultWorld=$DEFAULT_WORLD
defaultworld.useLastWorld=false
runelite.externalPlugins=quest-helper
runelite.questhelperplugin=true
questhelpervars.selected-assist-level=true
EOF
            fi
        done
    else
        # Create default profile if none exists
        mkdir -p "$PROFILES_DIR"
        DEFAULT_PROFILE="$PROFILES_DIR/default.properties"
        echo "Creating default profile: $DEFAULT_PROFILE"
        cat > "$DEFAULT_PROFILE" << EOF
defaultworld.defaultWorld=$DEFAULT_WORLD
defaultworld.useLastWorld=false
runelite.externalPlugins=quest-helper
runelite.questhelperplugin=true
questhelpervars.selected-assist-level=true
EOF
    fi
}

# Function to launch RuneLite with our plugin
launch_runelite_with_plugin() {
    local RUNELITE_CP="$1"
    
    echo "Launching RuneLite with Rocinante plugin..."
    echo "Classpath includes $(echo "$RUNELITE_CP" | tr ':' '\n' | wc -l) JARs"
    echo "Plugin JAR: $PLUGIN_JAR"
    
    java \
        -ea \
        -cp "$RUNELITE_CP" \
        -Xmx${JVM_HEAP_MAX:-2G} \
        -Xms${JVM_HEAP_MIN:-1G} \
        -Duser.home="$BOLT_HOME" \
        -Djava.awt.headless=false \
        -Drunelite.insecure-skip-tls-verification=true \
        $JAVA_OPENS \
        com.rocinante.RocinanteLauncher --debug --insecure-write-credentials &
    
    RUNELITE_PID=$!
    echo "RuneLite launched with PID: $RUNELITE_PID"
}

# =========================================================================
# FAST TRACK: If we have saved Jagex session vars, skip Bolt entirely
# =========================================================================
if [ -f "$JAGEX_SESSION_FILE" ] && [ -s "$JAGEX_SESSION_FILE" ] && [ -f "$PLUGIN_JAR" ] && [ -d "$REPO_DIR" ]; then
    echo "================================================="
    echo "FAST TRACK: Found saved Jagex session"
    echo "================================================="
    
    # Source the saved session variables
    echo "Loading Jagex session from: $JAGEX_SESSION_FILE"
    source "$JAGEX_SESSION_FILE"
    
    # Verify we got the vars
    if [ -n "$JX_SESSION_ID" ] && [ -n "$JX_CHARACTER_ID" ]; then
        echo "Jagex session loaded:"
        echo "  JX_SESSION_ID: ${JX_SESSION_ID:0:20}... (truncated)"
        echo "  JX_CHARACTER_ID: $JX_CHARACTER_ID"
        echo "  JX_DISPLAY_NAME: $JX_DISPLAY_NAME"
        
        # Export them for RuneLite
        export JX_SESSION_ID
        export JX_CHARACTER_ID
        export JX_DISPLAY_NAME
        
        configure_runelite_settings
        RUNELITE_CP=$(build_classpath)
        launch_runelite_with_plugin "$RUNELITE_CP"
        
        # Wait for RuneLite window
        sleep 10
        if xdotool search --name "RuneLite" > /dev/null 2>&1; then
            echo "RuneLite started via fast track!"
            
            # Run post-launch automation
            echo "Running post-launch automation..."
            python3 /home/runelite/post_launch.py || {
                echo "WARNING: Post-launch automation encountered issues"
            }
            
            # Skip to monitoring loop
            echo "================================================="
            echo "RuneLite running with Rocinante plugin (fast track)"
            echo "================================================="
            
            # Monitor loop
            while true; do
                if ! xdotool search --name "RuneLite" > /dev/null 2>&1; then
                    echo "WARNING: RuneLite window not detected"
                    if [ -n "$RUNELITE_PID" ] && kill -0 $RUNELITE_PID 2>/dev/null; then
                        echo "RuneLite process still running, waiting for window..."
                    else
                        echo "RuneLite process died - container will restart and retry fast track"
                        # Don't delete session file - crash could be unrelated to session validity
                        # If session is truly invalid, we'll add detection for that later
                        exit 1
                    fi
                fi
                sleep 30
            done
        else
            echo "WARNING: Fast track failed - RuneLite window not detected"
            echo "Removing stale session file and falling back to Bolt..."
            rm -f "$JAGEX_SESSION_FILE"
            # Fall through to Bolt authentication below
        fi
    else
        echo "WARNING: Session file exists but variables are empty"
        echo "Removing invalid session file..."
        rm -f "$JAGEX_SESSION_FILE"
        # Fall through to Bolt authentication
    fi
fi

# =========================================================================
# BOLT AUTHENTICATION (first run or if fast track failed)
# =========================================================================
echo "================================================="
echo "Launching Bolt for Jagex account authentication"
echo "================================================="

# Launch Bolt
    # Chromium flags for containerized environment:
    # --no-sandbox: Required for containers
    # --disable-gpu: Use CPU rendering (SwiftShader handles WebGL)
    # --use-gl=swiftshader: Software WebGL (still reports as supported - normal fingerprint)
    # --disable-dev-shm-usage: Use /tmp instead of /dev/shm (prevents OOM crashes)
    # --disable-features=VizDisplayCompositor: Fixes compositing in virtual displays
    # --lang=en-US: Match our timezone region
    echo "Launching Bolt..."
    cd /home/runelite/bolt-launcher
    ./bolt \
        --no-sandbox \
        --disable-gpu \
        --use-gl=swiftshader \
        --disable-dev-shm-usage \
        --disable-features=VizDisplayCompositor \
        --lang=en-US \
        &
    BOLT_PID=$!
    
    # Give Bolt a moment to initialize
    sleep 5
    
    # Run login automation script if credentials are provided
    if [ -n "$ACCOUNT_EMAIL" ] && [ -n "$ACCOUNT_PASSWORD" ]; then
        echo "Running login automation..."
        if /home/runelite/bolt-login.sh; then
            echo "Login automation completed successfully"
        else
            echo "WARNING: Login automation encountered issues"
            echo "Please check VNC connection for manual intervention"
        fi
    else
        echo "No credentials provided - manual login required via VNC"
    fi
    
    # Wait for RuneLite process (launched by Bolt)
    echo "Waiting for RuneLite process from Bolt..."
    RUNELITE_WAIT_TIMEOUT=300
    WAIT_ELAPSED=0
    
    while [ $WAIT_ELAPSED -lt $RUNELITE_WAIT_TIMEOUT ]; do
        # Check if RuneLite window exists
        if xdotool search --name "RuneLite" > /dev/null 2>&1; then
            echo "RuneLite detected!"
            break
        fi
        
        # Check if Bolt is still running
        if ! kill -0 $BOLT_PID 2>/dev/null; then
            # Bolt closed, check if RuneLite was spawned
            if ! xdotool search --name "RuneLite" > /dev/null 2>&1; then
                echo "WARNING: Bolt closed without spawning RuneLite"
            fi
            break
        fi
        
        sleep 5
        WAIT_ELAPSED=$((WAIT_ELAPSED + 5))
        echo "Waiting for RuneLite... (${WAIT_ELAPSED}s / ${RUNELITE_WAIT_TIMEOUT}s)"
    done
    
    # =========================================================================
    # PLUGIN SIDELOADING: Kill Bolt's RuneLite and relaunch with our plugin
    # =========================================================================
    if [ -f "$PLUGIN_JAR" ] && [ -d "$REPO_DIR" ]; then
        echo "================================================="
        echo "Sideloading Rocinante plugin..."
        echo "================================================="
        
        # Give RuneLite a moment to fully initialize (ensures session is written)
        sleep 5
        
        # CRITICAL: Capture Jagex session env vars from Bolt's RuneLite before killing it
        # Bolt passes JX_SESSION_ID, JX_CHARACTER_ID, JX_DISPLAY_NAME to authenticate
        echo "Capturing Jagex session from Bolt's RuneLite..."
        BOLT_RUNELITE_PID=$(pgrep -f "net.runelite.client.RuneLite" | head -1)
        if [ -n "$BOLT_RUNELITE_PID" ] && [ -f "/proc/$BOLT_RUNELITE_PID/environ" ]; then
            # Read environment variables from the running process
            JX_SESSION_ID=$(tr '\0' '\n' < /proc/$BOLT_RUNELITE_PID/environ | grep "^JX_SESSION_ID=" | cut -d= -f2-)
            JX_CHARACTER_ID=$(tr '\0' '\n' < /proc/$BOLT_RUNELITE_PID/environ | grep "^JX_CHARACTER_ID=" | cut -d= -f2-)
            JX_DISPLAY_NAME=$(tr '\0' '\n' < /proc/$BOLT_RUNELITE_PID/environ | grep "^JX_DISPLAY_NAME=" | cut -d= -f2-)
            
            echo "Captured Jagex session:"
            echo "  JX_SESSION_ID: ${JX_SESSION_ID:0:20}... (truncated)"
            echo "  JX_CHARACTER_ID: $JX_CHARACTER_ID"
            echo "  JX_DISPLAY_NAME: $JX_DISPLAY_NAME"
            
            # Export for our relaunched RuneLite
            export JX_SESSION_ID
            export JX_CHARACTER_ID
            export JX_DISPLAY_NAME
            
            # SAVE session to persistent file for fast track on future launches
            if [ -n "$JX_SESSION_ID" ] && [ -n "$JX_CHARACTER_ID" ]; then
                echo "Saving Jagex session for fast track..."
                cat > "$JAGEX_SESSION_FILE" << EOF
# Jagex session captured from Bolt - enables fast track on restart
# Generated: $(date)
export JX_SESSION_ID="$JX_SESSION_ID"
export JX_CHARACTER_ID="$JX_CHARACTER_ID"
export JX_DISPLAY_NAME="$JX_DISPLAY_NAME"
EOF
                echo "Session saved to: $JAGEX_SESSION_FILE"
            fi
        else
            echo "WARNING: Could not capture Jagex session - PID: $BOLT_RUNELITE_PID"
        fi
        
        # Kill the RuneLite process spawned by Bolt (but keep Bolt alive for session)
        echo "Killing Bolt-spawned RuneLite..."
        pkill -f "net.runelite.client.RuneLite" || true
        sleep 2
        
        configure_runelite_settings
        RUNELITE_CP=$(build_classpath)
        launch_runelite_with_plugin "$RUNELITE_CP"
        
        # Wait for new RuneLite window
        sleep 10
        if xdotool search --name "RuneLite" > /dev/null 2>&1; then
            echo "RuneLite with plugin started successfully!"
            echo "Credentials will be saved to: $CREDENTIALS_FILE"
            
            # Run post-launch automation (license, play button, name entry)
            echo "Running post-launch automation..."
            python3 /home/runelite/post_launch.py || {
                echo "WARNING: Post-launch automation encountered issues"
                echo "You may need to manually accept license or click Play via VNC"
            }
        else
            echo "WARNING: RuneLite window not detected after relaunch"
        fi
    else
        echo "WARNING: Plugin JAR or repository not found, skipping sideload"
        echo "  Plugin JAR: $PLUGIN_JAR (exists: $([ -f "$PLUGIN_JAR" ] && echo yes || echo no))"
        echo "  Repo dir: $REPO_DIR (exists: $([ -d "$REPO_DIR" ] && echo yes || echo no))"
    fi

# Keep container running and monitor RuneLite
echo "================================================="
echo "RuneLite should now be running with Rocinante plugin"
echo "Container will stay alive to keep the session active"
echo "================================================="

# Monitor loop - keep container running
while true; do
    # Check if RuneLite is still running
    if ! xdotool search --name "RuneLite" > /dev/null 2>&1; then
        echo "WARNING: RuneLite window not detected"
        
        # Check if our RuneLite process is still alive
        if [ -n "$RUNELITE_PID" ] && kill -0 $RUNELITE_PID 2>/dev/null; then
            echo "RuneLite process still running, waiting for window..."
        else
            echo "RuneLite process died, may need restart"
        fi
    fi
    
    sleep 30
done
