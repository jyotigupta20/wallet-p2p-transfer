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
stays exact even when new wallets appear mid-burst. `GET /admin/invariants` runs
all of it in one round trip: anyone can verify the claims rather than trust them.

`amount_paise CHECK (> 0)` is load-bearing. A negative amount makes the
conditional debit's `balance >= amount` guard vacuous and lets a caller pull
money *out* of the destination wallet. Jackson is configured with
`accept-float-as-int=false`, so `12.5` is rejected rather than truncated to `12`.
Money is `BIGINT`/`long` end to end.

## Simplest-correct mechanism, and what I rejected

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

**Deadlock.** Sorted lock ordering is necessary but was *not sufficient*, and
finding out why was the most interesting part of this exercise. `transfers` has
foreign keys to `wallets`, so step 1 takes a `FOR KEY SHARE` lock on both wallet
rows — in constraint-check order, which I don't control — and holds it for the
transaction. `FOR KEY SHARE` doesn't conflict with itself, so A→B and B→A both
end up holding shared locks on *both* rows and then both try to upgrade to `FOR
UPDATE`. Sorting the upgrades cannot help; the shared locks are already taken.
That produced 100 deadlocks in one 300-way burst. The fix is a lock strength,
not a retry loop: we only ever modify `balance_paise`, never a key column, so
`FOR NO KEY UPDATE` is the correct lock — exactly what the subsequent `UPDATE`
takes anyway — and it does not conflict with `FOR KEY SHARE`. The FK locks leave
the wait graph and sorted ordering becomes sufficient. Rejected alternative:
dropping the foreign keys, which trades referential integrity in a money schema
for something a weaker lock gives free.

**Rejected:** `SERIALIZABLE` — correct, but this exact contention pattern is its
worst case: a storm of `40001`s plus a mandatory application retry loop, heavier
*and* slower here. **Optimistic version columns** — livelock on hot wallets under
exactly the graded burst. **Redis `SETNX` for idempotency** — cannot commit
atomically with the debit, so a crash between the two double-spends or poisons
the key permanently. **Per-wallet actor/queue** — the right answer once a single
primary stops absorbing the write rate, wrong here: durable queueing and a new
failure surface to replace a row lock.

## Where idempotency lives

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
capped by one node. What I kept: the app tier is stateless, so it scales and
fails over freely — which is why this deploys as **two replicas against one
database**, and why `burst.sh` sprays a single burst across both. If the
guarantees lived in process memory that test would break immediately.

The pool is the concurrency bound (`DB_POOL_SIZE=20`): excess requests queue in
HikariCP, where queueing is cheap, instead of piling onto contended row locks.
`lock_timeout=10s` and `statement_timeout=20s` mean a pathological case degrades
into a `503 Retry-After` — explicitly retryable, nothing applied — rather than
hanging a thread. Every retryable SQLSTATE (`40001`, `40P01`, `55P03`, `57014`,
`53300`, `08xxx`) is classified as such rather than becoming an opaque 500.

## Capacity, and free-tier cost

**₹0.** Render free web service + Neon free Postgres + GitHub Actions cron; no
card. Render sleeps after 15 minutes idle and Neon autosuspends after 5, so a
10-minute Actions ping keeps both warm — a deliberate operating decision for this
tier, documented rather than hidden.

Measured locally: **1000 concurrent transfers over 3 wallets, across two
containerised replicas, all 1000 terminal, zero 5xx, ~3.5 s** (≈ 285 transfers/s
under maximum contention; p99 ≈ 194 ms at 200-way concurrency). It falls over
when the single primary saturates or connections exhaust — the next steps would
be shard-by-wallet and an actor/queue per wallet, in that order. Beyond free
tier, a Render starter instance plus a small managed Postgres is roughly
₹1,500–2,500/month.

## AI: directed vs decided

> **Draft — replace with your own honest account before submitting.**

- **Directed (I chose, AI implemented):** the exercise framing and priorities;
  Java/Spring/raw SQL over an ORM so the locking is visible; deploying two
  replicas to prove correctness is not process-local; making the burst script
  self-asserting rather than output-printing.
- **AI decided, I reviewed and can defend:** the `FOR NO KEY UPDATE` diagnosis
  and fix; `ON CONFLICT DO NOTHING` over exception-catching; the double-entry
  ledger and `/admin/invariants`; the always-`200` and `422`-with-body response
  shapes; the error taxonomy; the Dockerfile's CDS stage.
- **AI decided, accepted as-is:** log field naming, the exact Micrometer
  histogram bounds, nginx config details, README prose.

Both bugs above were found by running the burst script, not by reading the code.
