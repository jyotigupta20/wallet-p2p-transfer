# Wallet & P2P Transfer — Paytm PML R2

A wallet service with peer-to-peer transfers, where **every graded invariant is
enforced by a database constraint rather than by application code**. The app can
have bugs; Postgres still cannot represent a negative balance, a duplicate
idempotency key, or a second wallet for the same user.

- **Live URL:** _(filled in at deploy)_
- **Public logs:** `GET /debug/logs/stream` on the live URL — see [Observability](#observability)
- **One-command burst:** `./burst.sh <url>` — asserts all four invariants, exits non-zero on any failure
- **Write-up:** [`docs/WRITEUP.md`](docs/WRITEUP.md)

---

## Quick start

```bash
docker compose up --build          # Postgres + TWO app replicas + nginx round-robin
./burst.sh http://localhost:8000   # through the load balancer
```

Two replicas is the point, not decoration: correctness has to survive requests
landing on different JVMs. To prove it, spray one burst directly across both:

```bash
./burst.sh http://localhost:8080 http://localhost:8081
```

| Service | URL | Notes |
|---|---|---|
| nginx load balancer | http://localhost:8000 | round-robins both replicas |
| app-1 | http://localhost:8080 | `X-Instance-Id: app-1` |
| app-2 | http://localhost:8081 | `X-Instance-Id: app-2` |
| Postgres | localhost:5432 | `wallet` / `wallet` / `wallet` |

## Tests

```bash
./mvnw.sh test        # 9 concurrency tests against a real Postgres (Testcontainers)
```

Requires Docker. Every test releases its threads from a `CyclicBarrier`, so the
requests are genuinely simultaneous rather than merely overlapping. Two of them
are regression tests for bugs that actually reached a running instance — see
[What the burst script caught](#what-the-burst-script-caught).

No JDK or Maven is needed on the host: `./mvnw.sh` uses the vendored toolchain
under `../LLD/.tools`.

---

## API

Auth is a bearer token per user: `Authorization: Bearer <token>`. Any
previously-unseen token provisions a user (`app.auth.auto-provision`, on by
default), which keeps the burst script zero-setup and exercises race-free
get-or-create on the user row too. Only the SHA-256 hash of a token is ever
stored, and tokens are never logged.

Money is **integer paise everywhere** — `BIGINT` in the database, `long` on the
wire. `12.5` is rejected, never truncated.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/wallets` | Get-or-create the caller's wallet. Always `200`. |
| `GET` | `/wallets/{id}` | Current balance. |
| `POST` | `/transfers` | `{from, to, amount_paise, idempotency_key}` |
| `GET` | `/transfers/{id}` | Transfer status. |
| `GET` | `/health` | Liveness/readiness; reports `DOWN` if Postgres is unreachable. |
| `GET` | `/metrics` | Prometheus exposition. |
| `GET` | `/admin/invariants` | **Verify the graded invariants yourself, in one curl.** |
| `GET` | `/admin/status` | Request counts, error rate, p50/p95/p99. |
| `GET` | `/debug/logs` | Recent structured logs (ndjson). |
| `GET` | `/debug/logs/stream` | Live log stream (SSE). |

### Two response-shape decisions

Both exist so that a K-way retry storm returns **byte-identical** bodies:

- **`POST /transfers` always returns `200` on success, never `201`.** Under K
  concurrent calls with one key, exactly one request creates and K−1 replay. A
  creator answering `201` and replays answering `200` would not be identical.
  The distinction lives in the `X-Idempotent-Replay` header instead.
- **A declined transfer is `422` carrying the same transfer body**, not a
  problem+json. Its body must also be identical across replays, and problem+json
  carries a per-request correlation id that would differ. `status` and
  `decline_reason` keep the rejection specific and machine-readable.

Everything else that fails returns RFC 9457 `application/problem+json` with a
machine-readable `code` — `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSE`,
`INVALID_AMOUNT`, `FORBIDDEN`, `SERVICE_BUSY`, … — and never a stack trace.

### Example

```bash
H=http://localhost:8000
A=$(curl -s -XPOST $H/wallets -H "Authorization: Bearer alice" | jq -r .wallet_id)
B=$(curl -s -XPOST $H/wallets -H "Authorization: Bearer bob"   | jq -r .wallet_id)

curl -s -XPOST $H/transfers -H "Authorization: Bearer alice" \
     -H 'Content-Type: application/json' \
     -d "{\"from\":\"$A\",\"to\":\"$B\",\"amount_paise\":50000,\"idempotency_key\":\"k-1\"}"

# send it again - same body, same key
curl -si -XPOST $H/transfers -H "Authorization: Bearer alice" \
     -H 'Content-Type: application/json' \
     -d "{\"from\":\"$A\",\"to\":\"$B\",\"amount_paise\":50000,\"idempotency_key\":\"k-1\"}" \
  | grep -i x-idempotent-replay        # -> true, and an identical body

curl -s $H/admin/invariants | jq       # "holds": true
```

---

## The invariants, and where each one lives

| # | Invariant | Enforced by |
|---|---|---|
| 1 | **Conservation** | Debit and credit in one transaction, plus a double-entry `ledger_entries` table whose `TRANSFER_*` rows must sum to exactly zero |
| 2 | **No overdraft** | `UPDATE … WHERE balance_paise >= :amt` (rows-affected is the decision), backed by `CHECK (balance_paise >= 0)` |
| 3 | **Exactly-once** | `UNIQUE (user_id, idempotency_key)` claimed with `ON CONFLICT DO NOTHING` **in the same transaction as the money** |
| 4 | **Race-free get-or-create** | `UNIQUE (user_id)` on `wallets`, same `ON CONFLICT` pattern |

Conservation is a *query*, not a claim:

```sql
SELECT sum(signed_amount_paise) FROM ledger_entries
 WHERE kind IN ('TRANSFER_DEBIT','TRANSFER_CREDIT');   -- must be 0, forever
```

`GET /admin/invariants` runs that, plus a check that recomputes every wallet
balance from its ledger and counts disagreements. Money enters the system in
exactly one place — the `OPENING` row written when a wallet is created — so
conservation is stated as *money in the system == money ever issued*, which
stays exact even when the grader creates new wallets mid-burst.

Full reasoning, including the alternatives rejected: [`docs/WRITEUP.md`](docs/WRITEUP.md).

---

## Observability

**Logs.** Structured JSON (ECS) with a `correlation_id` and an `instance_id` on
every line. Domain events: `wallet.created`, `wallet.get_or_create.existing`,
`transfer.completed`, `transfer.declined`, `transfer.idempotent_replay`,
`transfer.idempotency_key_conflict`, `deadlock_detected`, `invariant_violation`.

Every free host puts its log viewer behind a login, so the service serves its
own. Run this in one pane and `./burst.sh` in another:

```bash
curl -N https://<live-url>/debug/logs/stream
```

**Metrics.** `GET /metrics`. Request rate, latency and error rate come from
`http_server_requests`; domain counters are
`wallet_transfers_total{result=completed|declined_insufficient_funds|idempotent_replay|idempotency_key_conflict}`.

The invariants are also exported as gauges — `wallet_total_balance_paise`,
`wallet_negative_balance_count`, `wallet_ledger_mismatch_count`,
`wallet_invariants_hold`. During a conservation burst the first is a **flat
line** and the rest are pinned at zero, which is a more convincing argument
than any paragraph.

---

## What the burst script caught

Neither of these is reachable without real concurrency, and **both would have
fired during live grading**.

**1. A deadlock that sorted lock ordering could not prevent.** `transfers` has
foreign keys to `wallets`, so inserting a transfer takes a `FOR KEY SHARE` lock
on *both* wallet rows — in constraint-check order, which the application does
not control — and holds it for the transaction. `FOR KEY SHARE` does not
conflict with itself, so A→B and B→A both end up holding shared locks on both
rows, then both try to upgrade to `FOR UPDATE`. Sorting the upgrades cannot
help: the shared locks were already taken on both rows. 100 deadlocks in one
300-way burst.

The fix is a lock strength, not a retry loop: we only ever modify
`balance_paise`, never a key column, so **`FOR NO KEY UPDATE`** is the correct
lock — precisely what the subsequent `UPDATE` takes anyway — and it does *not*
conflict with `FOR KEY SHARE`. The foreign-key locks drop out of the wait graph
and sorted ordering becomes sufficient on its own.

**2. A 500 on the exact scenario the brief grades.** `users` has two unique
constraints, `external_id` and `token_hash`, both derived from the same bearer
token, so a duplicate violates both. `ON CONFLICT (token_hash) DO NOTHING`
suppresses only the named one — when Postgres checked the `external_id` index
first it raised a raw `23505`, aborting the transaction and returning a 500.
This fires on *"fire N simultaneous `POST /wallets` for a brand-new user"*.
Untargeted `ON CONFLICT DO NOTHING` is the correct intent. It appeared once in
50 requests; manual testing would never have found it.

---

## Container

Four-stage `Dockerfile`: dependency resolution (cached separately from source),
build, jar extraction, and a **CDS training run** that halves JVM startup — it
matters on a free tier that cold-starts. Runtime is an Alpine JRE, runs as
**non-root** (uid 10001), defines a **`HEALTHCHECK`** against the readiness
probe (so an instance that cannot reach Postgres reports unhealthy rather than
merely alive), and uses an exec-form entrypoint so `SIGTERM` reaches the JVM and
`server.shutdown=graceful` actually drains in-flight transfers.

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/wallet` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `wallet` / `wallet` | |
| `DB_POOL_SIZE` | `20` | The pool is the concurrency bound |
| `OPENING_BALANCE_PAISE` | `0` | New wallets open with this; `1000000` in compose |
| `INSTANCE_ID` | `local` | Stamped on every log line and response |
| `PORT` | `8080` | |
| `AUTH_AUTO_PROVISION` | `true` | Unknown bearer token provisions a user |

## Layout

```
src/main/java/com/paytm/wallet/
  kernel/web/     correlation ids, problem+json, error taxonomy   <- reusable
  kernel/obs/     domain metrics, log ring buffer, SSE stream     <- reusable
  auth/           bearer token -> user, cached
  wallet/         get-or-create, balance
  transfer/       the whole exercise; read TransferService first
  admin/          invariant checks, status
src/main/resources/db/migration/V1__init.sql   the invariants, as constraints
src/test/java/    9 concurrency tests
scripts/burst.py  the burst harness
```

`kernel/` is deliberately domain-free. The graded properties here — an
idempotency key committed with its side effect, an atomic conditional write,
clean 4xx, correlation ids — are the same properties every exercise in this
round needs, so they are separated from the wallet itself.
