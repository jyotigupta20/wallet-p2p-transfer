#!/usr/bin/env bash
# SIGKILL a replica mid-burst, restart it, replay every idempotency key.
# Local only - it needs docker compose to do the killing.
#   ./crashtest.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
exec python3 "$HERE/scripts/crash_test.py" "$@"
