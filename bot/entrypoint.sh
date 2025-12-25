#!/bin/bash
set -e

echo "Starting Rocinante RuneLite Automation Container"
echo "================================================="
echo "Launcher: Bolt (native Linux launcher)"
echo "================================================="

# Export display for GUI tools
export DISPLAY=:99

# Set a common timezone (appears more normal)
export TZ="America/New_York"

# Clean up stale Xvfb processes and lock files from previous runs
# This is necessary when container is restarted (not recreated)
echo "Cleaning up previous display state..."
pkill -9 Xvfb 2>/dev/null || true
rm -f /tmp/.X99-lock 2>/dev/null || true
rm -f /tmp/.X11-unix/X99 2>/dev/null || true

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

# Start Xvfb virtual display
echo "Starting Xvfb on display :99..."
Xvfb :99 -screen 0 1920x1080x24 -ac +extension GLX +render -noreset &
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

# Start PulseAudio for virtual sound (apps expect audio device)
echo "Starting PulseAudio..."
pulseaudio --start --exit-idle-time=-1 2>/dev/null || true

# Start VNC server for remote access (always enabled)
echo "Starting VNC server on port 5900..."
x11vnc -display :99 -bg -nopw -listen 0.0.0.0 -xkb -forever -shared
sleep 1

# Verify x11vnc started
if ! pgrep -x x11vnc > /dev/null; then
    echo "ERROR: x11vnc failed to start"
    exit 1
fi
echo "VNC server started - connect to port 5900"

# Ensure Quest Helper is configured for Plugin Hub install
SETTINGS_FILE="$HOME/.runelite/settings.properties"
if [ ! -f "$SETTINGS_FILE" ] || ! grep -q "quest-helper" "$SETTINGS_FILE" 2>/dev/null; then
    echo "Configuring Quest Helper in Plugin Hub settings..."
    mkdir -p "$HOME/.runelite"
    echo "runelite.externalPlugins=quest-helper" >> "$SETTINGS_FILE"
fi

# Build JVM arguments for RuneLite (passed via environment)
export RUNELITE_JVM_ARGS="-Djava.awt.headless=false"
RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS --add-opens=java.desktop/sun.awt=ALL-UNNAMED"
RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Xmx${JVM_HEAP_MAX:-2G}"
RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Xms${JVM_HEAP_MIN:-1G}"

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

if [ -n "$CLAUDE_API_KEY" ]; then
    RUNELITE_JVM_ARGS="$RUNELITE_JVM_ARGS -Drocinante.claude.apiKey=$CLAUDE_API_KEY"
fi

export RUNELITE_JVM_ARGS

# Display configuration
echo "================================================="
echo "Account Email: ${ACCOUNT_EMAIL:-<not set>}"
echo "2FA Enabled: $([ -n "$TOTP_SECRET" ] && echo 'Yes (TOTP)' || echo 'No')"
echo "Ironman Mode: ${IRONMAN_MODE:-false}"
echo "Ironman Type: ${IRONMAN_TYPE:-N/A}"
echo "Quest Helper: Pre-configured for auto-install"
echo "VNC: Enabled (port 5900)"
echo "================================================="

# Check for Bolt launcher
BOLT_PATH="/home/runelite/bolt-launcher/bolt"
if [ ! -f "$BOLT_PATH" ]; then
    echo "ERROR: Bolt launcher not found at $BOLT_PATH"
    exit 1
fi

echo "Found Bolt launcher at: $BOLT_PATH"

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

# Wait for RuneLite process
echo "Waiting for RuneLite process..."
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

# Keep container running and monitor RuneLite
echo "================================================="
echo "RuneLite should now be running"
echo "Container will stay alive to keep the session active"
echo "================================================="

# Monitor loop - keep container running
while true; do
    # Check if RuneLite is still running
    if ! xdotool search --name "RuneLite" > /dev/null 2>&1; then
        echo "WARNING: RuneLite window not detected"
        
        # Check if Bolt is still running
        if pgrep -f "bolt" > /dev/null 2>&1; then
            echo "Bolt is still running, waiting..."
        else
            echo "Neither RuneLite nor Bolt detected"
            # Could restart Bolt here if desired
        fi
    fi
    
    sleep 30
done
