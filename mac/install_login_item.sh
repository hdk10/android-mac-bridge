#!/usr/bin/env bash
# Install the Mac Bridge agent as a per-user LaunchAgent: autostart at login, restart on crash.
# Idempotent. Run: ./install_login_item.sh   (uninstall: ./install_login_item.sh uninstall)
set -euo pipefail

LABEL="com.androidbridge"
MACDIR="$(cd "$(dirname "$0")" && pwd)"
PYTHON="$MACDIR/venv/bin/python"
BRIDGE="$MACDIR/bridge.py"
DEST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"

uninstall() {
  launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  rm -f "$DEST"
  echo "uninstalled $LABEL"
}

if [ "${1:-}" = "uninstall" ]; then uninstall; exit 0; fi

# ensure venv exists
if [ ! -x "$PYTHON" ]; then
  echo "venv missing — running run.sh once to create it..."
  ( cd "$MACDIR" && python3 -m venv venv && ./venv/bin/pip install -q -r requirements.txt )
fi

# stop any hand-launched agent so launchd owns the port
pgrep -f "bridge.py" | xargs kill -9 2>/dev/null || true

# render plist with real paths
mkdir -p "$HOME/Library/LaunchAgents"
sed -e "s#__PYTHON__#$PYTHON#" \
    -e "s#__BRIDGE__#$BRIDGE#" \
    -e "s#__MACDIR__#$MACDIR#" \
    "$MACDIR/$LABEL.plist" > "$DEST"

# (re)load
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
launchctl bootstrap "$DOMAIN" "$DEST"
launchctl enable "$DOMAIN/$LABEL"
launchctl kickstart -k "$DOMAIN/$LABEL"

echo "installed + started: $LABEL"
echo "plist: $DEST"
launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -E "state =|pid =" || true
