#!/bin/zsh
set -euo pipefail

DB="${HOME}/.codex/logs_2.sqlite"
WAL="${DB}-wal"
INTERVAL="${1:-5}"

echo "Monitoring ${WAL}"
echo "Interval: ${INTERVAL}s"
echo "Press Ctrl-C to stop."
echo

previous_size=0
previous_time="$(date +%s)"
if [[ -f "${WAL}" ]]; then
  previous_size="$(stat -f%z "${WAL}")"
fi

while true; do
  sleep "${INTERVAL}"
  now="$(date +%s)"
  size=0
  if [[ -f "${WAL}" ]]; then
    size="$(stat -f%z "${WAL}")"
  fi
  delta=$(( size - previous_size ))
  elapsed=$(( now - previous_time ))
  if (( elapsed <= 0 )); then
    elapsed=1
  fi
  rate=$(( delta / elapsed ))
  printf "%s  wal=%s bytes  delta=%+d bytes  approx_rate=%+d B/s\n" "$(date '+%Y-%m-%d %H:%M:%S')" "${size}" "${delta}" "${rate}"
  previous_size="${size}"
  previous_time="${now}"
done
