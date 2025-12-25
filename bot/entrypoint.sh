#!/bin/bash
set -e

echo "Starting Rocinante RuneLite Automation Container"
echo "================================================="
echo "Authentication: Jagex Launcher (via Wine)"
echo "================================================="

# Export display for Wine and GUI tools
export DISPLAY=:99
export WINEARCH=win64
export WINEPREFIX=/home/runelite/.wine
export WINEDEBUG=-all

# Start Xvfb virtual display
echo "Starting Xvfb on display :99..."
Xvfb :99 -screen 0 1280x1024x24 -ac +extension GLX +render -noreset &
XVFB_PID=$!
sleep 2

# Verify Xvfb started successfully
if ! kill -0 $XVFB_PID 2>/dev/null; then
    echo "ERROR: Xvfb failed to start"
    exit 1
fi

# Start VNC server for remote access (always enabled)
echo "Starting VNC server on port 5900..."
x11vnc -display :99 -bg -nopw -listen 0.0.0.0 -xkb
echo "VNC server started - connect to port 5900"

# Initialize Wine prefix if it doesn't exist or is corrupted
if [ ! -d "$WINEPREFIX/drive_c" ]; then
    echo "Initializing Wine prefix..."
    wineboot --init
    # Wait for wineserver to finish
    while pgrep -x wineserver > /dev/null; do sleep 1; done
    echo "Wine prefix initialized"
fi

# Configure proxy for Wine if provided
if [ -n "$PROXY_HOST" ] && [ -n "$PROXY_PORT" ]; then
    echo "Configuring proxy: $PROXY_HOST:$PROXY_PORT"
    # Wine uses system proxy settings, configure via registry
    wine reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" \
        /v ProxyEnable /t REG_DWORD /d 1 /f 2>/dev/null || true
    wine reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" \
        /v ProxyServer /t REG_SZ /d "$PROXY_HOST:$PROXY_PORT" /f 2>/dev/null || true
fi

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

# Jagex Launcher executable path - check common installation locations
JAGEX_LAUNCHER=""
for path in \
    "$WINEPREFIX/drive_c/Program Files (x86)/Jagex Launcher/JagexLauncher.exe" \
    "$WINEPREFIX/drive_c/Program Files/Jagex Launcher/JagexLauncher.exe" \
    "$WINEPREFIX/drive_c/Program Files (x86)/Jagex/Launcher/JagexLauncher.exe" \
    "$WINEPREFIX/drive_c/Program Files/Jagex/Launcher/JagexLauncher.exe"; do
    if [ -f "$path" ]; then
        JAGEX_LAUNCHER="$path"
        break
    fi
done

# Check if Jagex Launcher is installed
if [ -z "$JAGEX_LAUNCHER" ] || [ ! -f "$JAGEX_LAUNCHER" ]; then
    echo "ERROR: Jagex Launcher not found in Wine prefix"
    echo "Searching for JagexLauncher.exe..."
    find "$WINEPREFIX/drive_c" -name "JagexLauncher.exe" 2>/dev/null || true
    echo "The launcher may need to be reinstalled or the path has changed"
    exit 1
fi

echo "Found Jagex Launcher at: $JAGEX_LAUNCHER"

# Launch Jagex Launcher via Wine
echo "Launching Jagex Launcher..."
wine "$JAGEX_LAUNCHER" &
LAUNCHER_PID=$!

# Give the launcher a moment to initialize
sleep 5

# Run login automation script
echo "Running login automation..."
if /home/runelite/jagex-login.sh; then
    echo "Login automation completed successfully"
else
    echo "WARNING: Login automation encountered issues"
    echo "Please check VNC connection for manual intervention"
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
    
    # Check if launcher is still running
    if ! kill -0 $LAUNCHER_PID 2>/dev/null; then
        # Launcher closed, check if RuneLite was spawned
        if ! xdotool search --name "RuneLite" > /dev/null 2>&1; then
            echo "WARNING: Launcher closed without spawning RuneLite"
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
        
        # Check if Jagex Launcher is still running
        if pgrep -f "JagexLauncher" > /dev/null 2>&1; then
            echo "Jagex Launcher is still running, waiting..."
        else
            echo "Neither RuneLite nor Jagex Launcher detected"
            # Could restart launcher here if desired
        fi
    fi
    
    sleep 30
done
