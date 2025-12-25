#!/bin/bash
# Jagex Launcher Login Automation Script
# Automates the login flow for Jagex accounts with TOTP support

set -e

# Configuration
LAUNCHER_WINDOW_NAME="Jagex Launcher"
RUNELITE_WINDOW_NAME="RuneLite"
LOGIN_TIMEOUT=120  # seconds to wait for login elements
POLL_INTERVAL=1    # seconds between checks

# Colors for logging
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Wait for a window to appear
wait_for_window() {
    local window_name="$1"
    local timeout="${2:-$LOGIN_TIMEOUT}"
    local elapsed=0
    
    log_info "Waiting for window: $window_name (timeout: ${timeout}s)"
    
    while [ $elapsed -lt $timeout ]; do
        if xdotool search --name "$window_name" > /dev/null 2>&1; then
            log_info "Window found: $window_name"
            sleep 1  # Give window time to fully render
            return 0
        fi
        sleep $POLL_INTERVAL
        elapsed=$((elapsed + POLL_INTERVAL))
    done
    
    log_error "Timeout waiting for window: $window_name"
    return 1
}

# Get window ID by name
get_window_id() {
    local window_name="$1"
    xdotool search --name "$window_name" 2>/dev/null | head -1
}

# Focus a window by ID
focus_window() {
    local window_id="$1"
    xdotool windowactivate --sync "$window_id" 2>/dev/null
    sleep 0.5
}

# Type text with human-like delays
type_text() {
    local text="$1"
    local delay="${2:-50}"  # milliseconds between keystrokes
    xdotool type --delay "$delay" "$text"
}

# Press a key
press_key() {
    local key="$1"
    xdotool key "$key"
    sleep 0.3
}

# Generate TOTP code from secret
generate_totp() {
    if [ -z "$TOTP_SECRET" ]; then
        log_error "TOTP_SECRET environment variable not set"
        return 1
    fi
    oathtool --totp -b "$TOTP_SECRET"
}

# Check if RuneLite is already running (session valid)
check_runelite_running() {
    if xdotool search --name "$RUNELITE_WINDOW_NAME" > /dev/null 2>&1; then
        return 0
    fi
    return 1
}

# Check if we're on the login screen (need to enter credentials)
check_login_screen() {
    local window_id="$1"
    # The Jagex Launcher shows "Log in" or similar when credentials needed
    # We'll use window title or look for specific elements
    # This is a heuristic - may need adjustment based on actual launcher behavior
    if xdotool search --name "Log in" > /dev/null 2>&1; then
        return 0
    fi
    return 1
}

# Perform login with credentials
do_login() {
    local window_id="$1"
    
    log_info "Performing login..."
    
    # Validate credentials
    if [ -z "$ACCOUNT_EMAIL" ]; then
        log_error "ACCOUNT_EMAIL environment variable not set"
        return 1
    fi
    
    if [ -z "$ACCOUNT_PASSWORD" ]; then
        log_error "ACCOUNT_PASSWORD environment variable not set"
        return 1
    fi
    
    focus_window "$window_id"
    
    # Wait for the login form to be ready
    sleep 2
    
    # Enter email
    log_info "Entering email..."
    type_text "$ACCOUNT_EMAIL"
    press_key "Tab"
    sleep 0.5
    
    # Enter password
    log_info "Entering password..."
    type_text "$ACCOUNT_PASSWORD"
    press_key "Return"
    
    log_info "Credentials submitted, waiting for response..."
    sleep 3
    
    return 0
}

# Handle 2FA if prompted
handle_2fa() {
    log_info "Checking for 2FA prompt..."
    
    # Wait a moment for 2FA prompt to appear
    sleep 2
    
    # Check if 2FA is required (look for authenticator/code input)
    # This detection may need refinement based on actual launcher UI
    if xdotool search --name "Authenticator" > /dev/null 2>&1 || \
       xdotool search --name "verification" > /dev/null 2>&1 || \
       xdotool search --name "code" > /dev/null 2>&1; then
        
        log_info "2FA prompt detected"
        
        if [ -z "$TOTP_SECRET" ]; then
            log_error "2FA required but TOTP_SECRET not provided"
            log_info "Please enter 2FA code manually via VNC or provide TOTP_SECRET"
            return 1
        fi
        
        # Generate TOTP code
        local totp_code
        totp_code=$(generate_totp)
        
        if [ -z "$totp_code" ]; then
            log_error "Failed to generate TOTP code"
            return 1
        fi
        
        log_info "Generated TOTP code, entering..."
        
        # Find and focus the 2FA input window
        local auth_window
        auth_window=$(xdotool search --name "Authenticator" 2>/dev/null | head -1)
        if [ -n "$auth_window" ]; then
            focus_window "$auth_window"
        fi
        
        # Enter the TOTP code
        type_text "$totp_code" 100
        press_key "Return"
        
        log_info "2FA code submitted"
        sleep 3
    else
        log_info "No 2FA prompt detected"
    fi
    
    return 0
}

# Select RuneLite from game selection (if needed)
select_runelite() {
    log_info "Looking for game selection screen..."
    sleep 2
    
    # The Jagex Launcher may show a game selection screen
    # Look for RuneLite option and click it
    local launcher_window
    launcher_window=$(get_window_id "$LAUNCHER_WINDOW_NAME")
    
    if [ -n "$launcher_window" ]; then
        focus_window "$launcher_window"
        
        # Try to find and click RuneLite
        # This may need adjustment based on actual launcher layout
        # Using keyboard navigation as fallback
        
        # Press Tab to navigate to game list, then arrow keys to find RuneLite
        log_info "Navigating to RuneLite..."
        press_key "Tab"
        sleep 0.5
        
        # Try clicking on "RuneLite" text if visible
        # Or use keyboard to select and launch
        press_key "Return"
        sleep 1
    fi
}

# Main login flow
main() {
    log_info "=========================================="
    log_info "Jagex Launcher Login Automation"
    log_info "=========================================="
    
    # Check if RuneLite is already running
    if check_runelite_running; then
        log_info "RuneLite is already running - session appears valid"
        return 0
    fi
    
    # Wait for Jagex Launcher to appear
    if ! wait_for_window "$LAUNCHER_WINDOW_NAME" 60; then
        log_error "Jagex Launcher did not appear"
        return 1
    fi
    
    local launcher_window
    launcher_window=$(get_window_id "$LAUNCHER_WINDOW_NAME")
    
    if [ -z "$launcher_window" ]; then
        log_error "Could not get Jagex Launcher window ID"
        return 1
    fi
    
    log_info "Jagex Launcher window ID: $launcher_window"
    
    # Check if login is needed
    sleep 3  # Wait for launcher to fully load
    
    # Try to detect if we're already logged in (game selection visible)
    # or if we need to enter credentials
    
    # Attempt login if credentials are provided
    if [ -n "$ACCOUNT_EMAIL" ] && [ -n "$ACCOUNT_PASSWORD" ]; then
        # Check if login screen is showing
        if ! check_runelite_running; then
            do_login "$launcher_window"
            handle_2fa
        fi
    else
        log_warn "No credentials provided - manual login required via VNC"
    fi
    
    # Select RuneLite from game list
    select_runelite
    
    # Wait for RuneLite to launch
    log_info "Waiting for RuneLite to launch..."
    if wait_for_window "$RUNELITE_WINDOW_NAME" 120; then
        log_info "RuneLite launched successfully!"
        return 0
    else
        log_error "RuneLite did not launch within timeout"
        return 1
    fi
}

# Run main function
main "$@"

