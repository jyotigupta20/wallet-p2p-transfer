#!/usr/bin/env bash
# SIGKILL a replica mid-burst, restart it, replay every idempotency key.
# Local only - it needs docker compose to do the killing.
#   ./verify/crashtest.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# docker compose must run from the repo root, where the compose file lives.
cd "$HERE/.." && exec python3 "$HERE/lib/crash_test.py" "$@"
