#!/bin/bash
set -e

echo "Starting Rocinante RuneLite Automation Container"
echo "================================================="

# Start Xvfb virtual display
echo "Starting Xvfb on display :99..."
Xvfb :99 -screen 0 1024x768x24 -ac +extension GLX +render -noreset &
XVFB_PID=$!
sleep 2

# Verify Xvfb started successfully
if ! kill -0 $XVFB_PID 2>/dev/null; then
    echo "ERROR: Xvfb failed to start"
    exit 1
fi

# Optional: Start VNC server for remote debugging
if [ "$ENABLE_VNC" = "true" ]; then
    echo "Starting VNC server on port 5900..."
    x11vnc -display :99 -bg -nopw -listen 0.0.0.0 -xkb
fi

# Build JVM arguments
JVM_ARGS="-Djava.awt.headless=false"
JVM_ARGS="$JVM_ARGS --add-opens=java.desktop/sun.awt=ALL-UNNAMED"
JVM_ARGS="$JVM_ARGS -Xmx${JVM_HEAP_MAX:-2G}"
JVM_ARGS="$JVM_ARGS -Xms${JVM_HEAP_MIN:-1G}"

# Configure proxy if provided
if [ -n "$PROXY_HOST" ] && [ -n "$PROXY_PORT" ]; then
    echo "Configuring proxy: $PROXY_HOST:$PROXY_PORT"
    JVM_ARGS="$JVM_ARGS -Dhttp.proxyHost=$PROXY_HOST"
    JVM_ARGS="$JVM_ARGS -Dhttp.proxyPort=$PROXY_PORT"
    JVM_ARGS="$JVM_ARGS -Dhttps.proxyHost=$PROXY_HOST"
    JVM_ARGS="$JVM_ARGS -Dhttps.proxyPort=$PROXY_PORT"
    
    # Proxy authentication if provided
    if [ -n "$PROXY_USER" ] && [ -n "$PROXY_PASS" ]; then
        echo "Configuring proxy authentication for user: $PROXY_USER"
        JVM_ARGS="$JVM_ARGS -Dhttp.proxyUser=$PROXY_USER"
        JVM_ARGS="$JVM_ARGS -Dhttp.proxyPassword=$PROXY_PASS"
        JVM_ARGS="$JVM_ARGS -Dhttps.proxyUser=$PROXY_USER"
        JVM_ARGS="$JVM_ARGS -Dhttps.proxyPassword=$PROXY_PASS"
    fi
fi

# Configure Rocinante via environment variables
if [ -n "$IRONMAN_MODE" ]; then
    JVM_ARGS="$JVM_ARGS -Drocinante.ironman.enabled=$IRONMAN_MODE"
fi

if [ -n "$IRONMAN_TYPE" ]; then
    JVM_ARGS="$JVM_ARGS -Drocinante.ironman.type=$IRONMAN_TYPE"
fi

if [ -n "$HCIM_SAFETY_LEVEL" ]; then
    JVM_ARGS="$JVM_ARGS -Drocinante.hcim.safetyLevel=$HCIM_SAFETY_LEVEL"
fi

if [ -n "$CLAUDE_API_KEY" ]; then
    JVM_ARGS="$JVM_ARGS -Drocinante.claude.apiKey=$CLAUDE_API_KEY"
fi

# Ensure Quest Helper is configured for Plugin Hub install
SETTINGS_FILE="$HOME/.runelite/settings.properties"
if [ ! -f "$SETTINGS_FILE" ] || ! grep -q "quest-helper" "$SETTINGS_FILE" 2>/dev/null; then
    echo "Configuring Quest Helper in Plugin Hub settings..."
    mkdir -p "$HOME/.runelite"
    echo "runelite.externalPlugins=quest-helper" >> "$SETTINGS_FILE"
fi

# Launch RuneLite
echo "Launching RuneLite with Rocinante plugin..."
echo "Account: ${ACCOUNT_USERNAME:-<not set>}"
echo "Ironman Mode: ${IRONMAN_MODE:-false}"
echo "Ironman Type: ${IRONMAN_TYPE:-N/A}"
echo "Quest Helper: Pre-configured for auto-install"
echo "================================================="

# Execute RuneLite (replaces shell process for proper signal handling)
exec java $JVM_ARGS \
    -jar RuneLite.jar \
    --developer-mode

# Note: exec replaces the shell with java process, so this line never executes
# but we handle cleanup via Docker's signal handling to Xvfb

