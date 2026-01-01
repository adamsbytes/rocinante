#!/usr/bin/env python3
"""
Bolt Launcher Login Automation
Uses image template matching for reliable UI navigation.

Built step-by-step - add templates as we discover each screen.
"""

import argparse
import json
import subprocess
import sys
import time
import random
from pathlib import Path

import cv2
import numpy as np

# Configuration
TEMPLATES_DIR = Path(__file__).parent / "templates"
SCREENSHOT_PATH = "/tmp/screen.png"
MATCH_THRESHOLD = 0.8
POLL_INTERVAL = 0.5  # seconds between checks

# Global config dict (loaded from JSON file)
CONFIG: dict = {}


class Colors:
    """ANSI color codes for logging."""
    GREEN = '\033[0;32m'
    YELLOW = '\033[1;33m'
    RED = '\033[0;31m'
    CYAN = '\033[0;36m'
    NC = '\033[0m'  # No Color


def log_info(msg: str):
    print(f"{Colors.GREEN}[INFO]{Colors.NC} {msg}", flush=True)


def log_warn(msg: str):
    print(f"{Colors.YELLOW}[WARN]{Colors.NC} {msg}", flush=True)


def log_error(msg: str):
    print(f"{Colors.RED}[ERROR]{Colors.NC} {msg}", flush=True)


def log_step(msg: str):
    print(f"{Colors.CYAN}[STEP]{Colors.NC} {msg}", flush=True)


def take_screenshot() -> np.ndarray | None:
    """Capture the current screen."""
    try:
        result = subprocess.run(
            ['import', '-window', 'root', SCREENSHOT_PATH],
            capture_output=True,
            text=True
        )
        if result.returncode != 0:
            log_error(f"Screenshot failed: {result.stderr}")
            return None
        img = cv2.imread(SCREENSHOT_PATH)
        if img is None:
            log_error(f"Failed to load screenshot from {SCREENSHOT_PATH}")
        return img
    except Exception as e:
        log_error(f"Failed to take screenshot: {e}")
        return None


def find_template(screen: np.ndarray, template_name: str, threshold: float = MATCH_THRESHOLD, return_bounds: bool = False) -> tuple[int, int] | tuple[int, int, int, int] | None:
    """
    Find a template image on screen.
    Returns (x, y) center coordinates if found, None otherwise.
    If return_bounds=True, returns (x, y, width, height) of top-left corner + size.
    """
    template_path = TEMPLATES_DIR / template_name
    if not template_path.exists():
        log_error(f"Template not found: {template_path}")
        return None
    
    template = cv2.imread(str(template_path))
    if template is None:
        log_error(f"Failed to load template image: {template_path}")
        return None
    
    # Convert to grayscale for robust matching
    screen_gray = cv2.cvtColor(screen, cv2.COLOR_BGR2GRAY)
    template_gray = cv2.cvtColor(template, cv2.COLOR_BGR2GRAY)
    
    result = cv2.matchTemplate(screen_gray, template_gray, cv2.TM_CCOEFF_NORMED)
    _, max_val, _, max_loc = cv2.minMaxLoc(result)
    
    log_info(f"Template '{template_name}': {max_val:.3f} (threshold: {threshold})")
    
    if max_val >= threshold:
        h, w = template.shape[:2]
        if return_bounds:
            return (max_loc[0], max_loc[1], w, h)
        center_x = max_loc[0] + w // 2
        center_y = max_loc[1] + h // 2
        return (center_x, center_y)
    
    return None


def get_mouse_position() -> tuple[int, int]:
    """Get current mouse position."""
    try:
        result = subprocess.run(
            ['xdotool', 'getmouselocation'],
            capture_output=True, text=True
        )
        # Parse "x:123 y:456 screen:0 window:789"
        parts = result.stdout.split()
        x = int(parts[0].split(':')[1])
        y = int(parts[1].split(':')[1])
        return (x, y)
    except Exception:
        return (960, 540)  # Default to center


def bezier_curve(t: float, p0: float, p1: float, p2: float, p3: float) -> float:
    """Calculate point on cubic bezier curve."""
    return (1-t)**3 * p0 + 3*(1-t)**2 * t * p1 + 3*(1-t) * t**2 * p2 + t**3 * p3


def human_move_mouse(target_x: int, target_y: int):
    """Move mouse to target with human-like bezier curve motion."""
    start_x, start_y = get_mouse_position()
    
    # Generate control points for bezier curve (add some randomness)
    # Control points create a slight arc rather than straight line
    cp1_x = start_x + (target_x - start_x) * 0.3 + random.randint(-50, 50)
    cp1_y = start_y + (target_y - start_y) * 0.1 + random.randint(-30, 30)
    cp2_x = start_x + (target_x - start_x) * 0.7 + random.randint(-50, 50)
    cp2_y = start_y + (target_y - start_y) * 0.9 + random.randint(-30, 30)
    
    # Number of steps based on distance
    distance = ((target_x - start_x)**2 + (target_y - start_y)**2)**0.5
    steps = max(10, int(distance / 15))
    
    for i in range(steps + 1):
        t = i / steps
        # Add slight speed variation (slower at start and end)
        t = t * t * (3 - 2 * t)  # Smoothstep
        
        x = int(bezier_curve(t, start_x, cp1_x, cp2_x, target_x))
        y = int(bezier_curve(t, start_y, cp1_y, cp2_y, target_y))
        
        subprocess.run(['xdotool', 'mousemove', str(x), str(y)], check=False)
        time.sleep(random.uniform(0.005, 0.015))
    
    # Final position (ensure we hit the target)
    subprocess.run(['xdotool', 'mousemove', str(target_x), str(target_y)], check=False)


def click_at(x: int, y: int):
    """Click at screen coordinates with human-like movement."""
    human_move_mouse(x, y)
    time.sleep(random.uniform(0.05, 0.15))  # Small pause before click
    subprocess.run(['xdotool', 'click', '1'], check=False)
    time.sleep(random.uniform(0.1, 0.3))


def click_random_within(x: int, y: int, width: int, height: int):
    """Click at a random position within a bounding box."""
    # Add some randomness but stay within bounds (with margin)
    margin = 5
    rand_x = x + random.randint(margin, max(margin + 1, width - margin))
    rand_y = y + random.randint(margin, max(margin + 1, height - margin))
    log_info(f"Clicking at randomized position ({rand_x}, {rand_y})")
    click_at(rand_x, rand_y)


def type_text(text: str):
    """Type text with human-like delays between keystrokes."""
    for char in text:
        subprocess.run(['xdotool', 'type', '--', char], check=False)
        time.sleep(random.uniform(0.05, 0.15))  # Random delay per keystroke


def press_key(key: str):
    """Press a keyboard key."""
    subprocess.run(['xdotool', 'key', key], check=False)
    time.sleep(random.uniform(0.1, 0.2))


def wait_and_click(template_name: str, timeout: float = 30, step_name: str = "") -> bool:
    """
    Wait for template to appear and click it.
    Returns True if clicked, False if timeout.
    """
    log_step(f"{step_name}: Looking for '{template_name}'...")
    
    elapsed = 0.0
    while elapsed < timeout:
        screen = take_screenshot()
        if screen is not None:
            coords = find_template(screen, template_name)
            if coords:
                log_info(f"Found at ({coords[0]}, {coords[1]}) - clicking")
                click_at(coords[0], coords[1])
                return True
        time.sleep(POLL_INTERVAL)
        elapsed += POLL_INTERVAL
    
    log_warn(f"Timeout waiting for '{template_name}'")
    return False


def check_template_present(template_name: str) -> bool:
    """Check if a template is currently visible (no waiting)."""
    screen = take_screenshot()
    if screen is None:
        return False
    return find_template(screen, template_name) is not None


def wait_for_window(window_name: str, timeout: float = 60) -> str | None:
    """Wait for a window to appear and return its ID."""
    log_info(f"Waiting for window: {window_name} (timeout: {timeout}s)")
    elapsed = 0.0
    while elapsed < timeout:
        try:
            result = subprocess.run(
                ['xdotool', 'search', '--name', window_name],
                capture_output=True,
                text=True
            )
            if result.returncode == 0 and result.stdout.strip():
                window_id = result.stdout.strip().split('\n')[0]
                log_info(f"Window found: {window_name} (ID: {window_id})")
                return window_id
        except Exception:
            pass
        time.sleep(POLL_INTERVAL)
        elapsed += POLL_INTERVAL
    
    log_error(f"Timeout waiting for window: {window_name}")
    return None


def load_config(config_path: str) -> dict:
    """Load and validate config from JSON file."""
    with open(config_path, 'r') as f:
        config = json.load(f)
    
    # Required fields - fail loud if missing (KeyError)
    _ = config['username']
    _ = config['password']
    _ = config['totpSecret']
    _ = config['characterName']
    
    return config


def main():
    global CONFIG
    
    parser = argparse.ArgumentParser(description='Bolt Launcher Login Automation')
    parser.add_argument('--config', required=True, help='Path to config.json')
    args = parser.parse_args()
    
    log_info("=" * 50)
    log_info("Bolt Launcher Login Automation")
    log_info("=" * 50)
    
    # Load config from JSON file (fail loud if missing required fields)
    log_info(f"Loading config from: {args.config}")
    CONFIG = load_config(args.config)
    log_info(f"Account: {CONFIG['username']}")
    log_info(f"Character: {CONFIG['characterName']}")
    
    # Check if RuneLite is already running
    result = subprocess.run(
        ['xdotool', 'search', '--name', 'RuneLite'],
        capture_output=True, text=True
    )
    if result.returncode == 0 and result.stdout.strip():
        log_info("RuneLite is already running - nothing to do")
        return 0
    
    # Wait for Bolt window
    bolt_window = wait_for_window("Bolt", timeout=60)
    if not bolt_window:
        log_error("Bolt launcher did not appear")
        return 1
    
    # Give it a moment to fully render
    time.sleep(2)
    
    # =========================================
    # STEP 1: Check for disclaimer (determines if we need full login)
    # If "I Understand" is present → need full login flow
    # If NOT present → session persisted, skip to character select
    # =========================================
    log_step("Step 1: Checking for disclaimer...")
    
    screen = take_screenshot()
    need_login = screen is not None and find_template(screen, "i_understand_button.png") is not None
    
    if need_login:
        log_info("Disclaimer found - need full login flow")
        if wait_and_click("i_understand_button.png", timeout=5, step_name="Step 1"):
            log_info("Dismissed disclaimer dialog")
            time.sleep(2)  # Wait for next screen
        
        # =========================================
        # STEP 2: Click login button
        # =========================================
        log_step("Step 2: Clicking login button...")
        
        if wait_and_click("login_button.png", timeout=10, step_name="Step 2"):
            log_info("Clicked login button")
            time.sleep(3)  # Wait for login screen
        else:
            log_warn("Could not find login button")
        
        # =========================================
        # STEP 3: Handle Cloudflare verification
        # =========================================
        log_step("Step 3: Checking for Cloudflare verification...")
        
        # Wait for Cloudflare to appear (randomized for human-like behavior)
        wait_time = random.uniform(3.2, 3.6)
        log_info(f"Waiting {wait_time:.1f}s for Cloudflare...")
        time.sleep(wait_time)
        
        screen = take_screenshot()
        if screen is not None:
            # Lower threshold for Cloudflare (0.75x normal)
            bounds = find_template(screen, "cloudflare_human_checkbox.png", threshold=MATCH_THRESHOLD * 0.75, return_bounds=True)
            if bounds is not None:
                x, y, w, h = bounds
                log_info("Cloudflare checkbox detected - clicking with randomization")
                click_random_within(x, y, w, h)
                time.sleep(3)  # Wait for verification to complete
            else:
                log_info("No Cloudflare checkbox detected")
        
        # =========================================
        # STEP 4: Enter email
        # =========================================
        log_step("Step 4: Entering email...")
        
        # Credentials loaded from config.json (fail loud if missing)
        email = CONFIG['username']
        password = CONFIG['password']
        
        # Wait a bit longer before typing (let page fully load)
        time.sleep(random.uniform(1.3, 1.6))
        
        # Type email with human-like delays
        type_text(email)
        time.sleep(random.uniform(0.2, 0.4))
        
        # Press Enter
        press_key("Return")
        log_info("Email submitted")
        time.sleep(2)  # Wait for next screen
        
        # Check for "can't open page" error
        screen = take_screenshot()
        if screen is not None and find_template(screen, "error_login_cant_open_page.png") is not None:
            log_error("Login error: 'Can't open page' - restarting to retry")
            return 1
        
        # =========================================
        # STEP 5: Enter password
        # =========================================
        log_step("Step 5: Entering password...")
        
        # Small delay before typing
        time.sleep(random.uniform(0.3, 0.6))
        
        # Type password
        type_text(password)
        time.sleep(random.uniform(0.2, 0.4))
        
        # Press Enter
        press_key("Return")
        log_info("Password submitted")
        time.sleep(3)  # Wait for login to process
        
        # =========================================
        # STEP 6: Handle 2FA (required - fail loud if missing)
        # =========================================
        totp_secret = CONFIG['totpSecret']
        if totp_secret:
            log_step("Step 6: Handling 2FA...")
            
            # Wait for authenticator app button to appear
            time.sleep(random.uniform(2.0, 2.3))
            
            # Click the "Authenticator App" button
            if wait_and_click("login_authenticator_app_button.png", timeout=10, step_name="Step 6a"):
                log_info("Clicked Authenticator App button")
            else:
                log_warn("Could not find Authenticator App button")
            
            # Wait for code entry screen
            time.sleep(random.uniform(2.0, 2.3))
            
            # Generate TOTP code using oathtool
            try:
                result = subprocess.run(
                    ['oathtool', '--totp', '-b', totp_secret],
                    capture_output=True, text=True, check=True
                )
                totp_code = result.stdout.strip()
                log_info(f"Generated TOTP code: {totp_code[:2]}****")
                
                # Type the code
                type_text(totp_code)
                time.sleep(random.uniform(0.2, 0.4))
                press_key("Return")
                log_info("TOTP code submitted")
                time.sleep(3)
            except subprocess.CalledProcessError as e:
                log_error(f"Failed to generate TOTP code: {e}")
                return 1
        else:
            log_step("Step 6: No TOTP configured, skipping 2FA")
    else:
        log_info("No disclaimer - session persisted")
        
        # =========================================
        # STEP 2 (persisted): Click select account button
        # =========================================
        log_step("Step 2 (persisted): Clicking select account button...")
        
        time.sleep(random.uniform(0.5, 0.8))
        
        # Save coords so we can click again to minimize menu in Step 5
        select_account_coords = None
        screen = take_screenshot()
        if screen is not None:
            select_account_coords = find_template(screen, "select_account_button.png")
            if select_account_coords:
                log_info(f"Found select account button at {select_account_coords}")
                click_at(select_account_coords[0], select_account_coords[1])
            else:
                log_warn("Could not find select account button")
        
        # =========================================
        # STEP 3 (persisted): Click user select dropdown
        # =========================================
        log_step("Step 3 (persisted): Clicking user select dropdown...")
        
        time.sleep(random.uniform(0.5, 0.8))
        
        if wait_and_click("user_select_label.png", timeout=10, step_name="Step 3 (persisted)"):
            log_info("Clicked user select dropdown")
        else:
            log_warn("Could not find user select label")
        
        # =========================================
        # STEP 4 (persisted): Select user from dropdown
        # =========================================
        log_step("Step 4 (persisted): Selecting user from dropdown...")
        
        time.sleep(random.uniform(0.8, 1.2))
        
        screen = take_screenshot()
        if screen is not None:
            coords = find_template(screen, "user_select_header.png")
            if coords:
                # Click 20 pixels below the header to select user
                click_x = coords[0]
                click_y = coords[1] + 20
                log_info(f"Found user header at ({coords[0]}, {coords[1]}), clicking user at ({click_x}, {click_y})")
                click_at(click_x, click_y)
                time.sleep(1)  # Wait for selection
            else:
                log_warn("Could not find user select header")
        
        # =========================================
        # STEP 5 (persisted): Click select account button to minimize menu
        # =========================================
        log_step("Step 5 (persisted): Minimizing account menu...")
        
        time.sleep(random.uniform(0.5, 0.8))
        
        if select_account_coords:
            log_info(f"Clicking select account button again at {select_account_coords} to minimize")
            click_at(select_account_coords[0], select_account_coords[1])
            time.sleep(0.5)
        else:
            log_warn("No saved coords for select account button")
    
    # =========================================
    # STEP 7: Open character select dropdown
    # =========================================
    log_step("Step 7: Opening character select dropdown...")
    
    time.sleep(random.uniform(2.0, 2.3))
    
    screen = take_screenshot()
    if screen is not None:
        coords = find_template(screen, "character_select_label.png")
        if coords:
            # Click about 30 pixels below the label to open dropdown
            click_x = coords[0]
            click_y = coords[1] + 30
            log_info(f"Found character select label at ({coords[0]}, {coords[1]}), clicking dropdown at ({click_x}, {click_y})")
            click_at(click_x, click_y)
        else:
            log_warn("Could not find character select label")
    
    # =========================================
    # STEP 7a: Select character from dropdown
    # =========================================
    log_step("Step 7a: Selecting character from dropdown...")
    
    time.sleep(random.uniform(1.5, 1.8))
    
    screen = take_screenshot()
    if screen is not None:
        coords = find_template(screen, "character_select_header.png")
        if coords:
            # Click about 30 pixels below the header to select character
            click_x = coords[0]
            click_y = coords[1] + 30
            log_info(f"Found character header at ({coords[0]}, {coords[1]}), clicking character at ({click_x}, {click_y})")
            click_at(click_x, click_y)
            time.sleep(1)  # Wait for selection
        else:
            log_warn("Could not find character select header")
    
    # =========================================
    # STEP 8: Click Play button
    # =========================================
    log_step("Step 8: Clicking Play button...")
    
    time.sleep(random.uniform(0.5, 0.8))
    
    if wait_and_click("play_button.png", timeout=10, step_name="Step 8"):
        log_info("Clicked Play button - launching game!")
        time.sleep(3)  # Wait for game to start loading
    else:
        log_warn("Could not find Play button")
    
    # =========================================
    # Wait for RuneLite to launch
    # =========================================
    log_step("Waiting for RuneLite to launch...")
    runelite = wait_for_window("RuneLite", timeout=300)
    
    if runelite:
        log_info("RuneLite launched successfully!")
        
        # Maximize the RuneLite window
        # Wait for RuneLite to fully initialize (not just window creation)
        time.sleep(20)
        log_info("Maximizing RuneLite window...")
        try:
            # Get the window ID
            result = subprocess.run(
                ['xdotool', 'search', '--name', 'RuneLite'],
                capture_output=True, text=True
            )
            window_ids = result.stdout.strip().split('\n')
            if window_ids and window_ids[0]:
                window_id = window_ids[0]
                # Move to origin and resize to full screen (1920x1080)
                subprocess.run(['xdotool', 'windowmove', window_id, '0', '0'], check=False)
                subprocess.run(['xdotool', 'windowsize', window_id, '1920', '1080'], check=False)
                log_info("RuneLite window resized to 1920x1080")
        except Exception as e:
            log_warn(f"Could not maximize window: {e}")
        
        return 0
    else:
        log_warn("RuneLite did not launch - check VNC")
        return 1


if __name__ == '__main__':
    sys.exit(main())
