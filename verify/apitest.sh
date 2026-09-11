#!/usr/bin/env bash
#
# Exercise every endpoint and every documented status code.
#
#   ./verify/apitest.sh                              # local stack on :8080
#   ./verify/apitest.sh http://localhost:8000        # through the compose load balancer
#   ./verify/apitest.sh https://your-app.onrender.com
#   ./verify/apitest.sh -v                           # show every request and response
#
# Exits non-zero if any check fails.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "apitest.sh needs python3" >&2; exit 1; }
exec python3 "$HERE/lib/api_test.py" "$@"
