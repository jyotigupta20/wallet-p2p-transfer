#!/usr/bin/env bash
#
# Parses the local Neon connection string into the env vars the app expects.
# The connection file is gitignored and never committed - deployments get these
# values as environment variables set in the host's dashboard.
#
#   source deploy/neon-env.sh && ./mvnw.sh spring-boot:run
#
# Note: channel_binding is a libpq option that PgJDBC does not understand, so
# it is dropped. sslmode=require is kept - Neon refuses plaintext connections.
# Resolve from the repo root rather than from $BASH_SOURCE: this file is meant
# to be sourced, and BASH_SOURCE is unset under zsh (the default macOS shell),
# which silently resolves to the wrong directory.
if [[ -z "${CONN_FILE:-}" ]]; then
  REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
  CONN_FILE="$REPO_ROOT/deploy/connection/db_connection.yml"
fi

if [[ ! -f "$CONN_FILE" ]]; then
  echo "No connection file at $CONN_FILE" >&2
  return 1 2>/dev/null || exit 1
fi

eval "$(python3 - "$CONN_FILE" <<'PY'
import sys, re, urllib.parse as up
raw = open(sys.argv[1]).read().strip()
m = re.search(r'postgresql://\S+', raw)
if not m:
    sys.exit("could not find a postgresql:// URL in the connection file")
u = up.urlparse(m.group(0))
db = u.path.lstrip('/')
port = f":{u.port}" if u.port else ""
print(f'export DATABASE_URL="jdbc:postgresql://{u.hostname}{port}/{db}?sslmode=require"')
print(f'export DB_USER={up.quote(up.unquote(u.username))}')
print(f"export DB_PASSWORD='{up.unquote(u.password)}'")
PY
)"

echo "DATABASE_URL=$DATABASE_URL"
echo "DB_USER=$DB_USER"
echo "DB_PASSWORD=<${#DB_PASSWORD} chars, hidden>"
