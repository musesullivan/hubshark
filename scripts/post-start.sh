#!/usr/bin/env bash

set -u

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${HUBSHARK_LOG_DIR:-$BASE_DIR/logs}"
mkdir -p "$LOG_DIR" "$BASE_DIR/data" 2>/dev/null || true

ts() {
    date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z'
}

printf '%s [INFO] post_start begin\n' "$(ts)" >> "$LOG_DIR/hubshark.log" 2>/dev/null || true

if bash "$BASE_DIR/hubshark.sh" --silent-start >> "$LOG_DIR/post-start.log" 2>&1; then
    printf '%s [INFO] post_start complete\n' "$(ts)" >> "$LOG_DIR/hubshark.log" 2>/dev/null || true
    exit 0
else
    rc=$?
fi

printf '%s [WARN] post_start failed rc=%s\n' "$(ts)" "$rc" >> "$LOG_DIR/hubshark.log" 2>/dev/null || true
exit "$rc"
