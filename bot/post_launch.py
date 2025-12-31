#!/usr/bin/env python3
"""
Post-Launch Automation for RuneLite
Handles pre-game screens after RuneLite launches:
- License agreement acceptance
- Play button click
- Display name entry (for new accounts)

Uses OpenCV template matching (same approach as bolt_login.py).
"""

import subprocess
import sys
import time
import os
import random
from pathlib import Path

import cv2
import numpy as np

# Configuration
TEMPLATES_DIR = Path(__file__).parent / "templates"
SCREENSHOT_PATH = "/tmp/screen.png"
MATCH_THRESHOLD = 0.8
POLL_INTERVAL = 0.5  # seconds between checks


class Colors:
    """ANSI color codes for logging."""
    GREEN = '\033[0;32m'
    YELLOW = '\033[1;33m'
    RED = '\033[0;31m'
    CYAN = '\033[0;36m'
    MAGENTA = '\033[0;35m'
    NC = '\033[0m'  # No Color


def log_info(msg: str):
    print(f"{Colors.GREEN}[POST-LAUNCH]{Colors.NC} {msg}", flush=True)


def log_warn(msg: str):
    print(f"{Colors.YELLOW}[POST-LAUNCH]{Colors.NC} {msg}", flush=True)


def log_error(msg: str):
    print(f"{Colors.RED}[POST-LAUNCH]{Colors.NC} {msg}", flush=True)


def log_step(msg: str):
    print(f"{Colors.CYAN}[POST-LAUNCH]{Colors.NC} {msg}", flush=True)


def log_debug(msg: str):
    print(f"{Colors.MAGENTA}[POST-LAUNCH]{Colors.NC} {msg}", flush=True)


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
    template = cv2.imread(str(template_path))
    if template is None:
        return None
    
    # Convert to grayscale for robust matching
    screen_gray = cv2.cvtColor(screen, cv2.COLOR_BGR2GRAY)
    template_gray = cv2.cvtColor(template, cv2.COLOR_BGR2GRAY)
    
    result = cv2.matchTemplate(screen_gray, template_gray, cv2.TM_CCOEFF_NORMED)
    _, max_val, _, max_loc = cv2.minMaxLoc(result)
    
    log_debug(f"Template '{template_name}': {max_val:.3f} (threshold: {threshold})")
    
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
    cp1_x = start_x + (target_x - start_x) * 0.3 + random.randint(-50, 50)
    cp1_y = start_y + (target_y - start_y) * 0.1 + random.randint(-30, 30)
    cp2_x = start_x + (target_x - start_x) * 0.7 + random.randint(-50, 50)
    cp2_y = start_y + (target_y - start_y) * 0.9 + random.randint(-30, 30)
    
    # Number of steps based on distance
    distance = ((target_x - start_x)**2 + (target_y - start_y)**2)**0.5
    steps = max(10, int(distance / 15))
    
    for i in range(steps + 1):
        t = i / steps
        t = t * t * (3 - 2 * t)  # Smoothstep
        
        x = int(bezier_curve(t, start_x, cp1_x, cp2_x, target_x))
        y = int(bezier_curve(t, start_y, cp1_y, cp2_y, target_y))
        
        subprocess.run(['xdotool', 'mousemove', str(x), str(y)], check=False)
        time.sleep(random.uniform(0.005, 0.015))
    
    subprocess.run(['xdotool', 'mousemove', str(target_x), str(target_y)], check=False)


def click_at(x: int, y: int):
    """Click at screen coordinates with human-like movement."""
    human_move_mouse(x, y)
    time.sleep(random.uniform(0.05, 0.15))
    subprocess.run(['xdotool', 'click', '1'], check=False)
    time.sleep(random.uniform(0.1, 0.3))


def click_random_within(x: int, y: int, width: int, height: int):
    """Click at a random position within a bounding box."""
    margin = 5
    rand_x = x + random.randint(margin, max(margin + 1, width - margin))
    rand_y = y + random.randint(margin, max(margin + 1, height - margin))
    log_info(f"Clicking at randomized position ({rand_x}, {rand_y})")
    click_at(rand_x, rand_y)


def type_text(text: str):
    """Type text with human-like delays between keystrokes."""
    for char in text:
        subprocess.run(['xdotool', 'type', '--', char], check=False)
        time.sleep(random.uniform(0.05, 0.15))


def press_key(key: str):
    """Press a keyboard key."""
    subprocess.run(['xdotool', 'key', key], check=False)
    time.sleep(random.uniform(0.1, 0.2))


def wait_and_click(template_name: str, timeout: float = 30, step_name: str = "", threshold: float = MATCH_THRESHOLD) -> bool:
    """
    Wait for template to appear and click it.
    Returns True if clicked, False if timeout.
    """
    log_step(f"{step_name}: Looking for '{template_name}' (threshold={threshold})...")
    
    elapsed = 0.0
    while elapsed < timeout:
        screen = take_screenshot()
        if screen is not None:
            coords = find_template(screen, template_name, threshold=threshold)
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


def main():
    log_info("=" * 50)
    log_info("Post-Launch Automation")
    log_info("Handles license, play button, and name entry")
    log_info("=" * 50)
    
    # Get character name from environment
    character_name = os.environ.get('CHARACTER_NAME', '')
    log_info(f"CHARACTER_NAME: {character_name if character_name else '<not set>'}")
    
    # Wait for RuneLite to fully render (GPU init, UI load, etc.)
    log_step("Waiting 20s for RuneLite to fully initialize...")
    time.sleep(20)
    
    # =========================================
    # Phase 1: Handle license agreement (if present) - ONE attempt only
    # =========================================
    log_step("Phase 1: Checking for license agreement screen...")
    
    screen = take_screenshot()
    if screen is not None:
        coords = find_template(screen, "license_accept_button.png")
        if coords:
            log_info(f"License button found at ({coords[0]}, {coords[1]}) - clicking")
            click_at(coords[0], coords[1])
            time.sleep(1)
        else:
            log_info("No license screen visible (not needed)")
    
    # =========================================
    # Phase 2: Click Play button
    # =========================================
    log_step("Phase 2: Looking for Play button...")
    
    # Lower threshold (0.6) because dynamic username affects matching
    if wait_and_click("runelite_play_button.png", timeout=30, step_name="Play", threshold=0.6):
        log_info("Play button clicked!")
        time.sleep(5)
    else:
        log_warn("Play button not found on screen")
    
    # =========================================
    # Phase 2.5: Handle in-game lobby screen ("Welcome to Gielinor")
    # This appears AFTER the RuneLite play button, BEFORE world loads
    # The "CLICK HERE TO PLAY" button enters the game world
    # =========================================
    log_step("Phase 2.5: Checking for in-game lobby screen...")
    
    time.sleep(3)  # Wait for lobby to potentially appear
    
    # Try a few times - the lobby screen may take a moment to appear
    for attempt in range(6):  # 6 attempts over ~15 seconds
        screen = take_screenshot()
        if screen is not None:
            coords = find_template(screen, "lobby_click_to_play.png", threshold=0.7)
            if coords:
                log_info(f"Lobby screen found at ({coords[0]}, {coords[1]}) - clicking")
                click_at(coords[0], coords[1])
                time.sleep(3)
                break
        time.sleep(2.5)
    else:
        log_info("No lobby screen detected (may not be needed for this account)")
    
    # =========================================
    # Phase 3: Handle name entry (for new accounts)
    # =========================================
    if character_name:
        log_step("Phase 3: Checking for name entry screen...")
        
        time.sleep(3)
        
        screen = take_screenshot()
        if screen is not None:
            coords = find_template(screen, "name_entry_field.png")
            if coords:
                log_info("Name entry screen detected")
                click_at(coords[0], coords[1])
                time.sleep(0.5)
                
                log_info(f"Typing character name: {character_name}")
                type_text(character_name)
                time.sleep(0.5)
                
                # Try confirm button, fall back to Enter
                if wait_and_click("name_confirm_button.png", timeout=5, step_name="Confirm Name"):
                    log_info("Name confirmed!")
                else:
                    press_key("Return")
                
                time.sleep(2)
            else:
                log_info("No name entry screen visible (may not be needed)")
    else:
        log_info("Phase 3: Skipping name entry (CHARACTER_NAME not set)")
    
    log_info("=" * 50)
    log_info("Post-launch automation complete")
    log_info("=" * 50)
    return 0


if __name__ == '__main__':
    sys.exit(main())

