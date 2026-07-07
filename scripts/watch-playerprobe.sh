#!/usr/bin/env bash
set -euo pipefail

RADIUS="${1:-2}"
INTERVAL_MS="${2:-1000}"
PORT="${PLAYERPROBE_PORT:-8765}"

curl -N "http://127.0.0.1:${PORT}/watch?radius=${RADIUS}&intervalMs=${INTERVAL_MS}"
