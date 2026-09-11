#!/usr/bin/env bash
#
# Convenience wrapper. Prefers a vendored toolchain if one happens to be
# present, otherwise falls back to the standard Maven Wrapper (./mvnw), which
# downloads Maven itself and needs nothing but a JDK on PATH.
#
# A clean checkout has no vendored toolchain, so ./mvnw is the path that must
# always work - use it directly if you prefer:  ./mvnw test
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
VENDORED="$HERE/../LLD/.tools"

if [[ -x "$VENDORED/jdk-21.0.12+8/Contents/Home/bin/java" ]]; then
  export JAVA_HOME="$VENDORED/jdk-21.0.12+8/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v java >/dev/null 2>&1 && [[ -z "${JAVA_HOME:-}" ]]; then
  cat >&2 <<'MSG'
No JDK found. Install JDK 21 (e.g. https://adoptium.net) and set JAVA_HOME,
or skip the host build entirely and use the container:

    docker compose up --build
MSG
  exit 1
fi

exec "$HERE/mvnw" "$@"
