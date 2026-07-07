#!/usr/bin/env bash
set -euo pipefail

PORT="${PLAYERPROBE_PORT:-8765}"
ACTION="${1:-status}"

case "$ACTION" in
  status)
    curl "http://127.0.0.1:${PORT}/action/status"
    ;;
  stop)
    curl -X POST "http://127.0.0.1:${PORT}/action/stop"
    ;;
  forward)
    curl -X POST "http://127.0.0.1:${PORT}/action/move" \
      -H 'Content-Type: application/json' \
      -d '{"forward":true,"sprint":true,"durationMs":1200}'
    ;;
  stone)
    curl -X POST "http://127.0.0.1:${PORT}/action/gotoBlock" \
      -H 'Content-Type: application/json' \
      -d '{"id":"minecraft:stone","radius":20,"standRange":2}'
    ;;
  *)
    echo "Usage: $0 {status|stop|forward|stone}" >&2
    exit 2
    ;;
esac
