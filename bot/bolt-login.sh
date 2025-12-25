#!/bin/bash
# Bolt Launcher Login Automation Script
# Delegates to Python-based image detection for reliable UI navigation

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Run the Python-based login automation
exec python3 "$SCRIPT_DIR/bolt_login.py"
