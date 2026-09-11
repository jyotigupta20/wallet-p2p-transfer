#!/usr/bin/env bash
#
# One command to reproduce every graded invariant against a running service.
#
#   ./verify/burst.sh                                   # local stack on :8080
#   ./verify/burst.sh http://localhost:8000             # through the compose load balancer
#   ./verify/burst.sh https://your-app.onrender.com     # the deployed URL
#   ./verify/burst.sh https://a.example https://b.example   # spray one burst across two replicas
#
# Exits non-zero if any assertion fails.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

if ! command -v python3 >/dev/null 2>&1; then
  echo "burst.sh needs python3 (standard library only). On macOS: xcode-select --install" >&2
  exit 1
fi

exec python3 "$HERE/lib/burst.py" "$@"
