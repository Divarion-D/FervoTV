#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point; generation lives in the Python script.
exec python3 "$(dirname "$0")/media-fixtures.py" "$@"
