#!/usr/bin/env bash
# Self-contained Maven wrapper: uses the JDK 21 + Maven vendored under ../LLD/.tools
# so nothing needs to be installed system-wide. See docs/WRITEUP.md.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$HERE/../LLD/.tools"
export JAVA_HOME="$TOOLS/jdk-21.0.12+8/Contents/Home"
export PATH="$JAVA_HOME/bin:$TOOLS/apache-maven-3.9.9/bin:$PATH"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "ERROR: JDK not found at $JAVA_HOME" >&2; exit 1
fi
exec "$TOOLS/apache-maven-3.9.9/bin/mvn" "$@"
