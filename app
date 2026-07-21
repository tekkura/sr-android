#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
else
    echo "Unable to find Python. The app CLI requires Python 3.9 or newer." >&2
    echo "See docs/getting-started.md for setup instructions." >&2
    exit 1
fi

exec "$PYTHON_BIN" "$ROOT_DIR/scripts/app_cli.py" "$@"
