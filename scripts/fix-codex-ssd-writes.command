#!/bin/zsh
set -euo pipefail

CODEX_HOME="${HOME}/.codex"
DB="${CODEX_HOME}/logs_2.sqlite"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${CODEX_HOME}/backups/ssd-write-fix-${STAMP}"

echo "Codex SSD write mitigation"
echo "Database: ${DB}"
echo

if pgrep -afil "Codex.app/Contents|codex app-server" >/dev/null 2>&1; then
  echo "Codex still appears to be running."
  echo "Please quit Codex completely first, then run this script again."
  echo
  pgrep -afil "Codex.app/Contents|codex app-server" || true
  echo
  read "reply?Press Return to close this window..."
  exit 1
fi

if [[ ! -f "${DB}" ]]; then
  echo "Cannot find ${DB}"
  read "reply?Press Return to close this window..."
  exit 1
fi

echo "Current log database files:"
ls -lh "${DB}" "${DB}-wal" "${DB}-shm" 2>/dev/null || true
echo

mkdir -p "${BACKUP_DIR}"
echo "Backing up before any changes:"
echo "  ${BACKUP_DIR}"

if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "${DB}" ".backup '${BACKUP_DIR}/logs_2.sqlite.backup'"
else
  /usr/bin/python3 - "${DB}" "${BACKUP_DIR}/logs_2.sqlite.backup" <<'PY'
import sqlite3
import sys

src, dst = sys.argv[1], sys.argv[2]
source = sqlite3.connect(src)
target = sqlite3.connect(dst)
with target:
    source.backup(target)
target.close()
source.close()
PY
fi

cp -p "${DB}" "${BACKUP_DIR}/logs_2.sqlite.raw-copy" 2>/dev/null || true
cp -p "${DB}-wal" "${BACKUP_DIR}/logs_2.sqlite-wal.raw-copy" 2>/dev/null || true
cp -p "${DB}-shm" "${BACKUP_DIR}/logs_2.sqlite-shm.raw-copy" 2>/dev/null || true

echo
echo "Creating trigger to block future diagnostic log inserts..."
SQL_TRIGGER='CREATE TRIGGER IF NOT EXISTS block_log_inserts
BEFORE INSERT ON logs
BEGIN
  SELECT RAISE(IGNORE);
END;'

if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "${DB}" "${SQL_TRIGGER}"
  echo "Running WAL checkpoint..."
  sqlite3 "${DB}" "PRAGMA wal_checkpoint(TRUNCATE);"
  echo "Running VACUUM..."
  sqlite3 "${DB}" "VACUUM;"
  sqlite3 "${DB}" "PRAGMA optimize;"
  echo
  echo "Trigger verification:"
  sqlite3 "${DB}" "SELECT name, tbl_name, sql FROM sqlite_master WHERE type='trigger' AND name='block_log_inserts';"
else
  /usr/bin/python3 - "${DB}" <<'PY'
import sqlite3
import sys

db = sys.argv[1]
conn = sqlite3.connect(db)
conn.executescript("""
CREATE TRIGGER IF NOT EXISTS block_log_inserts
BEFORE INSERT ON logs
BEGIN
  SELECT RAISE(IGNORE);
END;
""")
conn.commit()
print("Running WAL checkpoint...")
print(conn.execute("PRAGMA wal_checkpoint(TRUNCATE);").fetchall())
conn.close()

conn = sqlite3.connect(db)
print("Running VACUUM...")
conn.execute("VACUUM;")
conn.execute("PRAGMA optimize;")
conn.commit()
print()
print("Trigger verification:")
for row in conn.execute("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='trigger' AND name='block_log_inserts';"):
    print(row)
conn.close()
PY
fi

echo
echo "Final log database files:"
ls -lh "${DB}" "${DB}-wal" "${DB}-shm" 2>/dev/null || true
echo
echo "Done. Backup kept at:"
echo "  ${BACKUP_DIR}"
echo
read "reply?Press Return to close this window..."
