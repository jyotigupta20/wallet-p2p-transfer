# Wallet & P2P Transfer — write-up

## Data model

Four tables. Every graded invariant is a constraint, so a logic bug cannot
persist an illegal state.

```
users          (id, external_id UNIQUE, token_hash UNIQUE)
wallets        (id, user_id UNIQUE, balance_paise BIGINT CHECK (>= 0))
transfers      (id, user_id, idempotency_key, request_hash, from_wallet_id,
                to_wallet_id, amount_paise CHECK (> 0), status, decline_reason,
                from_balance_after, to_balance_after,
                UNIQUE (user_id, idempotency_key))
ledger_entries (id, transfer_id, wallet_id, kind, signed_amount_paise,
                balance_after, UNIQUE (transfer_id, wallet_id))
```

`UNIQUE (user_id)` **is** invariant 4. `CHECK (balance_paise >= 0)` **is**
invariant 2. `UNIQUE (user_id, idempotency_key)` **is** invariant 3.

`ledger_entries` makes invariant 1 a query rather than a claim: a completed
transfer writes exactly two rows summing to zero, so
`sum(signed_amount_paise)` over all `TRANSFER_*` rows must be `0` forever, and
every wallet balance must equal the sum of its own entries. Money enters the
system in exactly one place — the `OPENING` row written when a wallet is created
— so conservation is stated as *money in the system == money ever issued*, which
stays exact even when new wallets appear mid-burst. `GET /admin/invariants` runs all of it in one round trip - plus a count of
transfers stuck in `PENDING`, which should be structurally impossible (the
state exists only inside the uncommitted transaction) and is asserted precisely
because a broken assumption that stays silent is the dangerous kind. Anyone can
verify the claims rather than trust them.

`amount_paise CHECK (> 0)` is load-bearing. A negative amount makes the
conditional debit's `balance >= amount` guard vacuous and lets a caller pull
money *out* of the destination wallet. Jackson is configured with
`accept-float-as-int=false`, so `12.5` is rejected rather than truncated to `12`.
Money is `BIGINT`/`long` end to end.

## Mechanism

One transaction at **READ COMMITTED**:

1. Claim the key: `INSERT … ON CONFLICT (user_id, idempotency_key) DO NOTHING`.
2. Lock both wallet rows with `SELECT … FOR NO KEY UPDATE`, **in ascending
   wallet-id order**.
3. `UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id = :from AND
   balance_paise >= :amt RETURNING balance_paise`. Rows-affected is the
   decision — no read-modify-write in application code. Empty means declined.
4. Credit, write the two ledger rows, mark the transfer terminal.

It is the simplest correct thing because each invariant maps to one constraint
plus one statement, and because the decisions are made by the database rather
than by a Java `if` that races.

**Rejected:** `SERIALIZABLE` — correct, but this exact contention pattern is its
worst case: a storm of `40001`s plus a mandatory application retry loop, heavier
*and* slower here. **Optimistic version columns** — livelock on hot wallets under
exactly the graded burst. **Redis `SETNX` for idempotency** — cannot commit
atomically with the debit, so a crash between the two double-spends or poisons
the key permanently. **Per-wallet actor/queue** — the right answer once a single
primary stops absorbing the write rate, wrong here: durable queueing and a new
failure surface to replace a row lock.

## Deadlock

Sorted lock ordering is necessary but was **not sufficient**, and finding out
why was the most interesting part of this exercise. `transfers` has foreign
keys to `wallets`, so the key-claiming `INSERT` above takes a `FOR KEY SHARE`
lock on both wallet rows — in constraint-check order, which I don't control —
and holds it for the rest of the transaction. `FOR KEY SHARE` doesn't conflict with itself, so A→B and B→A both
end up holding shared locks on *both* rows and then both try to upgrade to `FOR
UPDATE`. Sorting the upgrades cannot help; the shared locks are already taken.
That produced 100 deadlocks in one 300-way burst. The fix is a lock strength,
not a retry loop: we only ever modify `balance_paise`, never a key column, so
`FOR NO KEY UPDATE` is the correct lock — exactly what the subsequent `UPDATE`
takes anyway — and it does not conflict with `FOR KEY SHARE`. The FK locks leave
the wait graph and sorted ordering becomes sufficient. Rejected alternative:
dropping the foreign keys, which trades referential integrity in a money schema
for something a weaker lock gives free.

## Idempotency

In `UNIQUE (user_id, idempotency_key)`, claimed **in the same transaction as the
debit and credit**. There is no window in which a key exists without its money or
money without its key. Scoped to the caller so one client's key cannot collide
with another's.

`ON CONFLICT DO NOTHING` rather than catching the unique violation, for three
reasons: a raw `23505` *aborts* the Postgres transaction (forcing a rollback and
a second connection just to read the winner — the usual source of 500s on the
replay path); `DO NOTHING` **blocks** on a concurrent uncommitted inserter of the
same key and resolves when it commits, which is what turns a K-way storm into one
winner and K−1 clean replays; and it composes inside the money transaction.

Replay compares a SHA-256 `request_hash` of `from|to|amount`. Same key + same
body returns the stored result (`200`, `X-Idempotent-Replay: true`), byte
identical because `*_balance_after` is read from the row rather than recomputed.
Same key + different body is `409 IDEMPOTENCY_KEY_REUSE` and does not debit. A
*declined* transfer is persisted under its key too, so retrying a decline returns
the same decline instead of silently re-attempting later.

## Consistency vs availability

**CP, deliberately.** Correctness lives in one Postgres primary; if it is
unreachable the service returns `503` and readiness reports `DOWN` rather than
accepting writes it cannot make durable. For money, refusing a transfer is
recoverable and double-spending is not.

What I gave up: writes cannot survive a primary failure, and write throughput is
capped by one node. What I kept: the app tier holds no correctness state, so it
scales and fails over freely.

That claim is testable rather than asserted. `docker compose up` runs **two
replicas against one database** behind an nginx round-robin, and `burst.sh`
accepts several base URLs so a single burst sprays across both at once — if any
guarantee lived in process memory instead of in Postgres, that test would break
immediately. CI runs exactly that on every push.

To be precise about the deployment: the free Render tier gives **one** instance,
so the live URL is single-replica. The multi-replica property is demonstrated in
compose and in CI, not on the deployed URL. The same image and the same
configuration run in both; nothing about correctness changes with the instance
count, which is the point.

The pool is the concurrency bound (`DB_POOL_SIZE`: 20 locally, 10 on the free
instance): excess requests queue in
HikariCP, where queueing is cheap, instead of piling onto contended row locks.
`lock_timeout=10s` and `statement_timeout=20s` mean a pathological case degrades
into a `503 Retry-After` — explicitly retryable, nothing applied — rather than
hanging a thread. Every retryable SQLSTATE (`40001`, `40P01`, `55P03`, `57014`,
`53300`, `08xxx`) is classified as such rather than becoming an opaque 500.

## Capacity, and free-tier cost

**₹0.** Render free web service + Neon free Postgres + GitHub Actions cron; no
card. Render sleeps at 15 minutes idle and Neon autosuspends at 5, so a
10-minute Actions ping keeps both warm — an operating decision for this tier,
written down rather than hidden.

Measured against the live deployment (Render free, Singapore; Neon free,
ap-southeast-1):

| | |
|---|---|
| Network RTT, Delhi to Singapore | 243 ms |
| Uncontended transfer, end to end | 303 ms |
| **Server-side work per transfer** | **~60 ms** |
| 500 concurrent transfers over 3 wallets | all terminal, conservation exact, **zero 5xx** |

Under that 500-way burst the server-side p50 rose to ~2 s. That is the design
working: row locks serialise contended transfers, the pool bounds admission,
the excess queues. It degrades in latency, never in correctness. For money that
is the right trade. The binding constraint is the 0.1 vCPU free instance, not
the co-located database, which answers in single-digit milliseconds throughout.
Next steps in order: a real core, shard by wallet, then an actor per wallet —
only the last changes the design.

**Latency is correctness-adjacent here**, which is why the app is pinned to the
database's region. Every statement runs while row locks are held, so
app-to-database round-trip time multiplies into lock hold time: measured across
a WAN, one transfer held its locks ~2 s, contention blew through `lock_timeout`,
and the service shed load with 503s. Co-located, the same work is milliseconds.

## AI: directed vs decided

This round is an agentic exercise, so the honest disclosure is not how little
AI I used but how I directed it and how I know the result is correct.

**Directed.** Environment before business logic — Docker working end to end
before a line of domain code, so deployment was never the thing left until 2am.
Scoping UI out and demanding endpoint-level coverage instead, which produced the
125-check API suite and found two defects. Verifying the submission against the
brief clause by clause rather than trusting that it looked complete. And
exercising the deployed service by hand, which found three bugs every automated
suite had missed — they were all curl-shaped; none of them opened a browser.

**Accepted after review.** The locking strategy and the `FOR NO KEY UPDATE`
diagnosis; the double-entry ledger and `/admin/invariants`; the response shapes;
the container and deploy topology. I can defend each; I did not originate them.

**Accepted as typed.** Log field naming, Micrometer histogram bounds, nginx
directives, most prose.

**What it got wrong.** Ten defects reached working code. None were found by
reading it:

| Defect | Caught by |
|---|---|
| `@Transactional` on a self-invoked method — silently a no-op | review before it ran |
| `FOR UPDATE` deadlocking under foreign-key `KEY SHARE` locks | 100 deadlocks in one burst |
| `ON CONFLICT (token_hash)` missing the second unique constraint | 500 in the exact graded race, ~1 in 50 |
| Burst harness barrier/pool mismatch | deadlocked the test itself |
| Conservation measured across non-atomic reads | reported a failure that had not happened |
| `mvnw.sh` unusable on a clean checkout | fresh-clone test |
| Unknown path answering `WALLET_NOT_FOUND` | opening it in a browser |
| ndjson content-type downloading instead of displaying | opening it in a browser |
| Spring MVC client errors returning 500 | hostile probing of the live URL |
| Two overstated claims in this document | audit against deployed behaviour |

**What I own is the verification, not the typing.** 180 assertions across four
suites, all in CI; two regression tests for defects that actually shipped; and
the deadlock test's teeth proven by reverting the fix and confirming it fails.
