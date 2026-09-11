#!/usr/bin/env python3
"""
Self-asserting burst harness for the Paytm PML R2 wallet exercise.

Standard library only - no pip install, no network fetch. Run it against a
local stack or the deployed URL; pass several base URLs to spray a single
burst across multiple replicas at once.

Why Python rather than `xargs -P curl`: every scenario here releases its
requests from a threading.Barrier, so N requests leave the client within
microseconds of each other. Process-spawn staggering with xargs is not
concurrent enough to reliably expose the races this exercise is about.

Exit code is 0 only if every assertion passes.
"""

import argparse
import itertools
import os
import json
import random
import sys
import time
from collections import Counter

# ---------------------------------------------------------------- plumbing

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apiclient import (                                              # noqa: E402
    BOLD, DIM, GREEN, RED, RESET, Report, STATS, in_parallel, request,
)

REPORT = Report()


def scenario(title):
    print(f"\n{BOLD}{title}{RESET}", flush=True)


# ---------------------------------------------------------------- helpers

COUNTER = itertools.count()

def new_token(prefix="burst"):
    return f"{prefix}-{random.randrange(16**12):012x}-{next(COUNTER)}"


def make_wallet(urls, token=None):
    token = token or new_token()
    r = request(urls[0], "POST", "/wallets", token=token)
    if r.status != 200:
        raise SystemExit(f"{RED}setup failed: POST /wallets returned {r.status}: {r.text}{RESET}")
    return token, r.body["wallet_id"], r.body["balance_paise"]


def balance(urls, wallet_id, token):
    return request(urls[0], "GET", f"/wallets/{wallet_id}", token=token).body["balance_paise"]


def invariants(urls):
    return request(urls[0], "GET", "/admin/invariants").body


def wait_until_quiet(urls, timeout=90):
    """
    Block until the server stops producing terminal transfers.

    Necessary because a request that timed out CLIENT-side may still be
    committing server-side. Summing balances with several separate reads while
    that is happening observes a torn snapshot and reports a conservation
    failure that did not occur. Conservation is a property of the ledger at an
    instant, not of several reads taken across one.
    """
    deadline = time.time() + timeout
    previous, stable = None, 0
    while time.time() < deadline:
        inv = invariants(urls)
        current = (inv.get("transfers_completed"), inv.get("transfers_declined"))
        if current == previous:
            stable += 1
            if stable >= 2:
                return inv
        else:
            stable = 0
        previous = current
        time.sleep(0.5)
    return invariants(urls)


# ---------------------------------------------------------------- scenarios

def s1_concurrent_get_or_create(urls, n):
    """INVARIANT 4: N simultaneous POST /wallets for a brand-new user -> one wallet."""
    scenario(f"1. Concurrent get-or-create  ({n} simultaneous POST /wallets, brand-new user)")
    token = new_token("fresh")
    responses = in_parallel(n, lambda i: request(urls[i % len(urls)], "POST", "/wallets", token=token))

    ids = {r.body.get("wallet_id") for r in responses if r.status == 200}
    created = sum(1 for r in responses if r.headers.get("x-wallet-created") == "true")
    bodies = {r.text for r in responses if r.status == 200}
    statuses = Counter(r.status for r in responses)

    REPORT.check(statuses == Counter({200: n}), "all responses are 200", f"got {dict(statuses)}")
    REPORT.check(len(ids) == 1, "exactly ONE wallet exists for the user", f"distinct wallet_ids = {len(ids)}")
    REPORT.check(created == 1, "exactly one caller created it", f"X-Wallet-Created:true count = {created}")
    REPORT.check(len(bodies) == 1, "all response bodies byte-identical", f"distinct bodies = {len(bodies)}")
    return token, (ids.pop() if ids else None)


def s2_idempotent_retry_storm(urls, k):
    """INVARIANT 3: the same idempotency_key fired K times -> one debit, identical replies."""
    scenario(f"2. Idempotent retry storm  ({k} concurrent POSTs, one idempotency_key)")
    tok_a, wa, _ = make_wallet(urls)
    tok_b, wb, _ = make_wallet(urls)
    before_a, before_b = balance(urls, wa, tok_a), balance(urls, wb, tok_b)
    amount = 25_000
    payload = {"from": wa, "to": wb, "amount_paise": amount,
               "idempotency_key": "storm-" + new_token()}

    responses = in_parallel(k, lambda i: request(
        urls[i % len(urls)], "POST", "/transfers", token=tok_a, payload=payload))

    statuses = Counter(r.status for r in responses)
    ids = {r.body.get("transfer_id") for r in responses if r.status == 200}
    bodies = {r.text for r in responses if r.status == 200}
    originals = sum(1 for r in responses if r.headers.get("x-idempotent-replay") == "false")
    after_a, after_b = balance(urls, wa, tok_a), balance(urls, wb, tok_b)

    REPORT.check(statuses == Counter({200: k}), "all responses are 200 (zero 5xx, zero errors)",
                 f"got {dict(statuses)}")
    REPORT.check(len(ids) == 1, "exactly ONE transfer was created", f"distinct transfer_ids = {len(ids)}")
    REPORT.check(len(bodies) == 1, "all K response bodies byte-identical", f"distinct bodies = {len(bodies)}")
    REPORT.check(originals == 1, "exactly one response was the original, rest replays",
                 f"X-Idempotent-Replay:false count = {originals}")
    REPORT.check(before_a - after_a == amount, "sender debited EXACTLY once",
                 f"delta = {before_a - after_a}, expected {amount}")
    REPORT.check(after_b - before_b == amount, "recipient credited EXACTLY once",
                 f"delta = {after_b - before_b}, expected {amount}")


def s3_same_key_different_body(urls):
    """INVARIANT 3, second half: key reuse with a different body is a 409, not a second debit."""
    scenario("3. Idempotency key reuse with a different body")
    tok_a, wa, _ = make_wallet(urls)
    _, wb, _ = make_wallet(urls)
    key = "reuse-" + new_token()
    base = {"from": wa, "to": wb, "amount_paise": 1_000, "idempotency_key": key}

    first = request(urls[0], "POST", "/transfers", token=tok_a, payload=base)
    after_first = balance(urls, wa, tok_a)
    conflict = request(urls[0], "POST", "/transfers", token=tok_a,
                       payload={**base, "amount_paise": 7_777})
    after_conflict = balance(urls, wa, tok_a)

    REPORT.check(first.status == 200, "original transfer succeeded", f"status {first.status}")
    REPORT.check(conflict.status == 409, "same key + different body -> 409", f"status {conflict.status}")
    REPORT.check(conflict.body.get("code") == "IDEMPOTENCY_KEY_REUSE",
                 "409 carries a machine-readable code", f"code={conflict.body.get('code')}")
    REPORT.check(after_first == after_conflict, "the conflicting replay did NOT debit again",
                 f"balance {after_first} -> {after_conflict}")


def s4_conservation_under_contention(urls, wallets_n, transfers_n):
    """INVARIANT 1 + 2: many concurrent transfers over a few wallets, both directions at once."""
    scenario(f"4. Conservation under contention  ({transfers_n} concurrent transfers "
             f"over {wallets_n} wallets, A->B and B->A simultaneously)")
    accounts = [make_wallet(urls) for _ in range(wallets_n)]
    before_total = sum(balance(urls, w, t) for t, w, _ in accounts)
    inv_before = invariants(urls)

    def one(i):
        src, dst = random.sample(range(wallets_n), 2)
        tok, wallet, _ = accounts[src]
        _, dst_wallet, _ = accounts[dst]
        return request(urls[i % len(urls)], "POST", "/transfers", token=tok, payload={
            "from": wallet, "to": dst_wallet,
            "amount_paise": random.randrange(100, 5_000),
            "idempotency_key": f"contend-{new_token()}-{i}",
        })

    responses = in_parallel(transfers_n, one)

    statuses = Counter(r.status for r in responses)
    completed = sum(1 for r in responses if r.status == 200)
    declined = sum(1 for r in responses if r.status == 422)

    # Let anything still in flight land before measuring; see wait_until_quiet.
    inv_after = wait_until_quiet(urls)
    after_total = sum(balance(urls, w, t) for t, w, _ in accounts)

    REPORT.check(STATS.server_errors == 0, "zero 5xx responses across the entire run so far",
                 f"5xx count = {STATS.server_errors}")
    REPORT.check(set(statuses) <= {200, 422}, "every response was 200 or a clean 422",
                 f"got {dict(statuses)}")
    REPORT.check(completed + declined == transfers_n, "every request reached a terminal outcome",
                 f"{completed} completed + {declined} declined = {completed + declined}")
    REPORT.check(inv_after["total_wallet_balance_paise"] - inv_before["total_wallet_balance_paise"]
                 == inv_after["total_issued_paise"] - inv_before["total_issued_paise"],
                 "CONSERVATION (server snapshot): balance moved only by what was issued",
                 f"balance +{inv_after['total_wallet_balance_paise'] - inv_before['total_wallet_balance_paise']}, "
                 f"issued +{inv_after['total_issued_paise'] - inv_before['total_issued_paise']}")
    REPORT.check(before_total == after_total, "CONSERVATION: total balance unchanged",
                 f"{before_total} -> {after_total}")
    REPORT.check(inv_after["negative_balance_count"] == 0, "NO OVERDRAFT: no negative balance",
                 f"negatives = {inv_after['negative_balance_count']}")
    REPORT.check(inv_after["transfer_ledger_sum_paise"] == 0,
                 "double-entry ledger sums to exactly zero",
                 f"sum = {inv_after['transfer_ledger_sum_paise']}")
    REPORT.check(inv_after["wallets_disagreeing_with_ledger"] == 0,
                 "every wallet balance reconciles against its ledger",
                 f"mismatches = {inv_after['wallets_disagreeing_with_ledger']}")
    REPORT.check(inv_after["holds"], "server-side invariant check reports HOLDS")
    REPORT.check(inv_after["total_wallet_balance_paise"] == inv_after["total_issued_paise"],
                 "money in the system == money ever issued",
                 f"{inv_after['total_wallet_balance_paise']} vs {inv_after['total_issued_paise']}")


def s5_overdraft_storm(urls, n):
    """INVARIANT 2 under load: concurrent debits that cannot all succeed."""
    scenario(f"5. Overdraft storm  ({n} concurrent transfers, each nearly the whole balance)")
    tok_a, wa, opening = make_wallet(urls)
    _, wb, _ = make_wallet(urls)
    amount = max(opening // 2, 1) + 1          # at most one of these can ever succeed

    responses = in_parallel(n, lambda i: request(
        urls[i % len(urls)], "POST", "/transfers", token=tok_a, payload={
            "from": wa, "to": wb, "amount_paise": amount,
            "idempotency_key": f"overdraft-{new_token()}-{i}"}))

    statuses = Counter(r.status for r in responses)
    ok = sum(1 for r in responses if r.status == 200)
    final = balance(urls, wa, tok_a)

    REPORT.check(set(statuses) <= {200, 422}, "no 5xx under an impossible-demand burst",
                 f"got {dict(statuses)}")
    REPORT.check(ok <= 1, "at most ONE debit succeeded", f"{ok} succeeded of {n}")
    REPORT.check(final >= 0, "balance never went negative", f"final balance = {final}")
    REPORT.check(all(r.body.get("decline_reason") == "INSUFFICIENT_FUNDS"
                     for r in responses if r.status == 422),
                 "every decline states INSUFFICIENT_FUNDS")


def s6_clean_rejections(urls):
    """Malformed input must be a specific 4xx, never a 500 or a stack trace."""
    scenario("6. Clean rejection of bad input  (specific 4xx, never 5xx, never a stack trace)")
    tok_a, wa, _ = make_wallet(urls)
    _, wb, _ = make_wallet(urls)

    cases = [
        ("negative amount",   {"from": wa, "to": wb, "amount_paise": -1, "idempotency_key": new_token()}, 400),
        ("zero amount",       {"from": wa, "to": wb, "amount_paise": 0, "idempotency_key": new_token()}, 400),
        ("fractional paise",  {"from": wa, "to": wb, "amount_paise": 12.5, "idempotency_key": new_token()}, 400),
        ("self transfer",     {"from": wa, "to": wa, "amount_paise": 100, "idempotency_key": new_token()}, 400),
        ("missing key",       {"from": wa, "to": wb, "amount_paise": 100}, 400),
        ("unknown wallet",    {"from": wa, "to": "00000000-0000-0000-0000-000000000000",
                               "amount_paise": 100, "idempotency_key": new_token()}, 404),
    ]
    for label, payload, expected in cases:
        r = request(urls[0], "POST", "/transfers", token=tok_a, payload=payload)
        REPORT.check(r.status == expected, f"{label} -> {expected}", f"got {r.status}")
        REPORT.check("Exception" not in r.text and "\tat " not in r.text,
                     f"{label} response leaks no stack trace")

    r = request(urls[0], "POST", "/transfers", token=None,
                payload={"from": wa, "to": wb, "amount_paise": 1, "idempotency_key": new_token()})
    REPORT.check(r.status == 401, "missing bearer token -> 401", f"got {r.status}")


# ---------------------------------------------------------------- main

def main():
    p = argparse.ArgumentParser(
        description="Reproduce and assert every graded invariant against a running wallet service.")
    p.add_argument("urls", nargs="*", default=["http://localhost:8080"],
                   help="one or more base URLs; a burst is sprayed across all of them")
    p.add_argument("--wallets-burst", type=int, default=50,
                   help="N for the concurrent get-or-create scenario")
    p.add_argument("--retry-storm", type=int, default=50,
                   help="K for the idempotent retry storm")
    p.add_argument("--transfers", type=int, default=300,
                   help="concurrent transfers for the contention scenario")
    p.add_argument("--contended-wallets", type=int, default=5,
                   help="how few wallets those transfers fight over")
    p.add_argument("--overdraft", type=int, default=50,
                   help="concurrent transfers in the overdraft storm")
    p.add_argument("--seed", type=int, default=None, help="seed the RNG for a reproducible run")
    args = p.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    urls = args.urls or ["http://localhost:8080"]
    print(f"{BOLD}Paytm PML R2 - wallet invariant burst{RESET}")
    print(f"  target(s): {', '.join(urls)}")

    for u in urls:
        h = request(u, "GET", "/health")
        if h.status != 200:
            raise SystemExit(f"{RED}{u} is not healthy (status {h.status}); aborting.{RESET}")
    print(f"  {GREEN}all targets healthy{RESET}")

    s1_concurrent_get_or_create(urls, args.wallets_burst)
    s2_idempotent_retry_storm(urls, args.retry_storm)
    s3_same_key_different_body(urls)
    s4_conservation_under_contention(urls, args.contended_wallets, args.transfers)
    s5_overdraft_storm(urls, args.overdraft)
    s6_clean_rejections(urls)

    # ---- final global assertions ----
    scenario("Final state")
    final = invariants(urls)
    REPORT.check(STATS.server_errors == 0, "ZERO 5xx responses for the whole run",
                 f"5xx count = {STATS.server_errors}")
    REPORT.check(final["holds"], "all invariants hold on the server after every burst")
    REPORT.check(final["negative_balance_count"] == 0, "no wallet is negative")
    REPORT.check(final["transfer_ledger_sum_paise"] == 0, "ledger still sums to zero")
    REPORT.check(final.get("transfers_pending") == 0,
                 "no transfer left claimed but unfinalised",
                 f"pending = {final.get('transfers_pending')}")

    print(f"\n{DIM}  HTTP status distribution: "
          f"{dict(sorted(STATS.status_counts.items()))}{RESET}")
    print(f"{DIM}  wallets={final['wallet_count']} "
          f"total_balance_paise={final['total_wallet_balance_paise']} "
          f"completed={final['transfers_completed']} declined={final['transfers_declined']}{RESET}")

    return REPORT.summary()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
