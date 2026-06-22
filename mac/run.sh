#!/usr/bin/env bash
# android-bridge Mac agent launcher. Creates venv, installs deps, runs the menu-bar agent.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d venv ]; then
  python3 -m venv venv
fi
# shellcheck disable=SC1091
source venv/bin/activate
pip install --quiet --upgrade pip
pip install --quiet -r requirements.txt

echo "android-bridge starting — look for 🌉 in the menu bar"
exec python bridge.py
