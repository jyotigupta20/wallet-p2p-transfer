#!/usr/bin/env python3
"""
API coverage suite: every endpoint, every documented status code.

Where burst.sh proves the invariants hold under concurrency, this proves the
API surface behaves correctly one request at a time - including every way a
request can be rejected. A money API is judged as much by how cleanly it says
no as by how it says yes, so the rejection paths are tested as carefully as the
happy ones - every one must be a specific 4xx, never a 500, never a stack
trace.

  ./apitest.sh                         # local stack
  ./apitest.sh https://your.url        # deployed
  ./apitest.sh -v                      # show every request and response

Exit code is 0 only if every check passes.
"""

import argparse
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apiclient import (                                              # noqa: E402
    BOLD, CYAN, DIM, GREEN, RED, RESET, Report, STATS, request,
)

REPORT = Report()
VERBOSE = False
UNKNOWN_UUID = "00000000-0000-0000-0000-000000000000"


def section(title):
    print(f"\n{BOLD}{title}{RESET}", flush=True)


def show(method, path, resp, payload=None):
    if not VERBOSE:
        return
    print(f"    {CYAN}{method} {path}{RESET}")
    if payload is not None:
        print(f"    {DIM}request : {json.dumps(payload)}{RESET}")
    body = resp.text if len(resp.text) < 400 else resp.text[:400] + "..."
    print(f"    {DIM}response: {resp.status} {body}{RESET}")


def call(base, method, path, expect, label, token=None, payload=None, **kw):
    """One request, asserted against an expected status, with hygiene checks."""
    resp = request(base, method, path, token=token, payload=payload, **kw)
    show(method, path, resp, payload)
    REPORT.check(resp.status == expect, f"{method} {path} -> {expect}  ({label})",
                 f"got {resp.status}")
    if 400 <= resp.status < 600:
        REPORT.check("\tat " not in resp.text and "Exception" not in resp.text,
                     f"    ...and leaks no stack trace")
    return resp


# ---------------------------------------------------------------- fixtures

def make_account(base, prefix="api"):
    import uuid
    token = f"{prefix}-{uuid.uuid4()}"
    r = request(base, "POST", "/wallets", token=token)
    if r.status != 200:
        raise SystemExit(f"{RED}setup failed: POST /wallets -> {r.status} {r.text}{RESET}")
    return token, r.body["wallet_id"], r.body["balance_paise"]


# ---------------------------------------------------------------- suites

def wallets(base):
    section("POST /wallets  and  GET /wallets/{id}")
    import uuid
    token = f"cover-{uuid.uuid4()}"

    first = call(base, "POST", "/wallets", 200, "creates the caller's wallet", token=token)
    REPORT.check(first.header("X-Wallet-Created") == "true",
                 "    ...X-Wallet-Created: true on the creating call",
                 f"got {first.header('X-Wallet-Created')}")
    REPORT.check(isinstance(first.body.get("balance_paise"), int),
                 "    ...balance_paise is an integer, not a float or string",
                 f"got {type(first.body.get('balance_paise')).__name__}")

    second = call(base, "POST", "/wallets", 200, "is idempotent - returns the same wallet", token=token)
    REPORT.check(second.body.get("wallet_id") == first.body.get("wallet_id"),
                 "    ...same wallet_id on the second call")
    REPORT.check(second.header("X-Wallet-Created") == "false",
                 "    ...X-Wallet-Created: false the second time")
    REPORT.check(second.text == first.text,
                 "    ...response body byte-identical to the first")

    wallet_id = first.body["wallet_id"]
    call(base, "GET", f"/wallets/{wallet_id}", 200, "returns the balance", token=token)
    call(base, "GET", f"/wallets/{UNKNOWN_UUID}", 404, "unknown wallet", token=token)
    call(base, "GET", "/wallets/not-a-uuid", 400, "malformed wallet id", token=token)

    section("Authentication")
    call(base, "POST", "/wallets", 401, "no Authorization header")
    r = request(base, "POST", "/wallets", headers={"Authorization": "Basic abc123"})
    show("POST", "/wallets", r)
    REPORT.check(r.status == 401, "POST /wallets -> 401  (non-Bearer scheme)", f"got {r.status}")
    r = request(base, "POST", "/wallets", headers={"Authorization": "Bearer "})
    show("POST", "/wallets", r)
    REPORT.check(r.status == 401, "POST /wallets -> 401  (empty bearer token)", f"got {r.status}")


def transfers(base):
    section("POST /transfers  - success and replay")
    alice_token, alice, opening = make_account(base, "alice")
    bob_token, bob, _ = make_account(base, "bob")
    key = "cover-key-1"
    body = {"from": alice, "to": bob, "amount_paise": 50_000, "idempotency_key": key}

    ok = call(base, "POST", "/transfers", 200, "moves money", token=alice_token, payload=body)
    REPORT.check(ok.body.get("status") == "COMPLETED", "    ...status COMPLETED")
    REPORT.check(ok.header("X-Idempotent-Replay") == "false", "    ...X-Idempotent-Replay: false")
    REPORT.check(ok.body.get("from_balance_after") == opening - 50_000,
                 "    ...from_balance_after is correct",
                 f"got {ok.body.get('from_balance_after')}")

    replay = call(base, "POST", "/transfers", 200, "same key, same body -> replay",
                  token=alice_token, payload=body)
    REPORT.check(replay.text == ok.text, "    ...replay body byte-identical to the original")
    REPORT.check(replay.header("X-Idempotent-Replay") == "true", "    ...X-Idempotent-Replay: true")

    conflict = call(base, "POST", "/transfers", 409, "same key, different body",
                    token=alice_token, payload={**body, "amount_paise": 7_777})
    REPORT.check(conflict.body.get("code") == "IDEMPOTENCY_KEY_REUSE",
                 "    ...code IDEMPOTENCY_KEY_REUSE", f"got {conflict.body.get('code')}")

    section("POST /transfers  - declined")
    broke_token, broke, broke_balance = make_account(base, "broke")
    declined = call(base, "POST", "/transfers", 422, "insufficient funds", token=broke_token,
                    payload={"from": broke, "to": bob, "amount_paise": broke_balance + 1,
                             "idempotency_key": "cover-decline-1"})
    REPORT.check(declined.body.get("status") == "DECLINED", "    ...status DECLINED")
    REPORT.check(declined.body.get("decline_reason") == "INSUFFICIENT_FUNDS",
                 "    ...decline_reason INSUFFICIENT_FUNDS")
    declined_replay = call(base, "POST", "/transfers", 422, "replaying a decline returns the decline",
                           token=broke_token,
                           payload={"from": broke, "to": bob, "amount_paise": broke_balance + 1,
                                    "idempotency_key": "cover-decline-1"})
    REPORT.check(declined_replay.text == declined.text,
                 "    ...declined replay body byte-identical")

    section("POST /transfers  - rejected input")
    bad = [
        ("negative amount",       {"from": alice, "to": bob, "amount_paise": -1, "idempotency_key": "b1"}, 400),
        ("zero amount",           {"from": alice, "to": bob, "amount_paise": 0, "idempotency_key": "b2"}, 400),
        ("self transfer",         {"from": alice, "to": alice, "amount_paise": 1, "idempotency_key": "b3"}, 400),
        ("missing idempotency_key", {"from": alice, "to": bob, "amount_paise": 1}, 400),
        ("blank idempotency_key", {"from": alice, "to": bob, "amount_paise": 1, "idempotency_key": "  "}, 400),
        ("missing 'to'",          {"from": alice, "amount_paise": 1, "idempotency_key": "b4"}, 400),
        ("unknown source wallet", {"from": UNKNOWN_UUID, "to": bob, "amount_paise": 1, "idempotency_key": "b5"}, 404),
        ("unknown destination",   {"from": alice, "to": UNKNOWN_UUID, "amount_paise": 1, "idempotency_key": "b6"}, 404),
    ]
    for label, payload, expect in bad:
        r = call(base, "POST", "/transfers", expect, label, token=alice_token, payload=payload)
        if r.status == expect and r.body:
            REPORT.check(bool(r.body.get("code")), f"    ...carries a machine-readable code",
                         f"code={r.body.get('code')}")

    # Raw-body cases that cannot be expressed as a Python dict
    for label, raw in [
        ("fractional paise", f'{{"from":"{alice}","to":"{bob}","amount_paise":12.5,"idempotency_key":"b7"}}'),
        ("amount as string", f'{{"from":"{alice}","to":"{bob}","amount_paise":"100","idempotency_key":"b8"}}'),
        ("unknown field",    f'{{"from":"{alice}","to":"{bob}","amount_paise":1,"idempotency_key":"b9","extra":1}}'),
        ("malformed JSON",   '{"from": '),
        ("empty body",       ''),
    ]:
        r = request(base, "POST", "/transfers", token=alice_token, raw_body=raw)
        show("POST", "/transfers", r)
        REPORT.check(r.status == 400, f"POST /transfers -> 400  ({label})", f"got {r.status}")
        REPORT.check("\tat " not in r.text, "    ...and leaks no stack trace")

    section("POST /transfers  - authorisation")
    call(base, "POST", "/transfers", 403, "debiting a wallet you do not own", token=bob_token,
         payload={"from": alice, "to": bob, "amount_paise": 1, "idempotency_key": "b10"})
    call(base, "POST", "/transfers", 401, "no bearer token",
         payload={"from": alice, "to": bob, "amount_paise": 1, "idempotency_key": "b11"})

    section("GET /transfers/{id}")
    call(base, "GET", f"/transfers/{ok.body['transfer_id']}", 200, "returns the transfer",
         token=alice_token)
    call(base, "GET", f"/transfers/{UNKNOWN_UUID}", 404, "unknown transfer", token=alice_token)
    call(base, "GET", f"/transfers/{ok.body['transfer_id']}", 401, "no bearer token")
    return alice_token


def operational(base):
    section("Operational endpoints")
    for path in ["/health", "/health/liveness", "/health/readiness"]:
        r = call(base, "GET", path, 200, "probe")
        if path == "/health":
            REPORT.check(r.body.get("status") == "UP", "    ...reports UP")
            REPORT.check(r.body.get("components", {}).get("db", {}).get("status") == "UP",
                         "    ...database component UP")

    metrics = call(base, "GET", "/metrics", 200, "Prometheus exposition")
    for series, why in [
        ("http_server_requests_seconds", "request rate and latency"),
        ("wallet_transfers_total", "domain counters"),
        ("wallet_total_balance_paise", "conservation as a gauge"),
        ("wallet_negative_balance_count", "overdraft as a gauge"),
        ("wallet_invariants_hold", "all invariants as a gauge"),
    ]:
        REPORT.check(series in metrics.text, f"    ...exposes {series}  ({why})")

    inv = call(base, "GET", "/admin/invariants", 200, "invariant snapshot")
    for field in ["conservation_holds", "no_overdraft", "ledger_reconciles", "holds"]:
        REPORT.check(inv.body.get(field) is True, f"    ...{field} is true",
                     f"got {inv.body.get(field)}")

    status = call(base, "GET", "/admin/status", 200, "latency and error rate")
    REPORT.check("p99_ms" in status.body.get("latency", {}), "    ...reports p99 latency")
    REPORT.check(status.body.get("server_errors_5xx") == 0, "    ...zero 5xx so far",
                 f"got {status.body.get('server_errors_5xx')}")

    nd = call(base, "GET", "/debug/logs?n=5", 200, "recent structured logs")
    REPORT.check("ndjson" in (nd.header("Content-Type") or ""),
                 "    ...tools get newline-delimited JSON",
                 f"got {nd.header('Content-Type')}")

    # A browser must get something it will DISPLAY. Serving ndjson to a browser
    # makes it download a file, which defeats "publicly viewable logs".
    page = request(base, "GET", "/debug/logs", accept="text/html")
    show("GET", "/debug/logs", page)
    REPORT.check(page.status == 200 and "text/html" in (page.header("Content-Type") or ""),
                 "GET /debug/logs -> 200 text/html  (a browser gets a viewable page)",
                 f"got {page.status} {page.header('Content-Type')}")
    REPORT.check("EventSource" in page.text,
                 "    ...and that page tails the live stream")

    call(base, "GET", "/debug/logs/info", 200, "log buffer state")

    # SSE: assert it opens with the right content type, then close immediately.
    try:
        req = urllib.request.Request(base.rstrip("/") + "/debug/logs/stream")
        with urllib.request.urlopen(req, timeout=10) as r:
            ctype = r.headers.get("Content-Type", "")
            REPORT.check(r.status == 200 and "text/event-stream" in ctype,
                         "GET /debug/logs/stream -> 200 text/event-stream  (live log stream)",
                         f"got {r.status} {ctype}")
    except Exception as e:                                    # noqa: BLE001
        REPORT.check(False, "GET /debug/logs/stream opens", str(e))

    section("Service root and unknown routes")
    root = call(base, "GET", "/", 200, "API index at the root")
    REPORT.check("api" in root.body and "verify_it_yourself" in root.body,
                 "    ...the index points at the endpoints that verify the service")

    missing = call(base, "GET", "/nope", 404, "unknown path")
    REPORT.check(missing.body.get("code") == "ENDPOINT_NOT_FOUND",
                 "    ...an unknown path is ENDPOINT_NOT_FOUND, not WALLET_NOT_FOUND",
                 f"got {missing.body.get('code')}")


def cross_cutting(base, token):
    section("Correlation id and instance id")
    supplied = "my-trace-id-12345"
    r = request(base, "GET", "/health", headers={"X-Request-Id": supplied})
    show("GET", "/health", r)
    REPORT.check(r.header("X-Correlation-Id") == supplied,
                 "a caller-supplied X-Request-Id is echoed back as X-Correlation-Id",
                 f"got {r.header('X-Correlation-Id')}")

    r2 = request(base, "GET", "/health")
    REPORT.check(bool(r2.header("X-Correlation-Id")),
                 "one is generated when the caller supplies none",
                 f"got {r2.header('X-Correlation-Id')}")
    REPORT.check(r2.header("X-Correlation-Id") != supplied,
                 "    ...and it is a fresh id, not a stale one")
    REPORT.check(bool(r2.header("X-Instance-Id")),
                 "every response names the serving instance",
                 f"X-Instance-Id={r2.header('X-Instance-Id')}")

    # End-to-end proof that the MDC wiring works: make a request with a known
    # id that produces a domain event, then find that id in the log stream.
    import uuid
    trace = "trace-" + uuid.uuid4().hex[:12]
    t, wallet, _ = make_account(base, "trace")
    traced = request(base, "POST", "/transfers", token=t,
                     payload={"from": wallet, "to": UNKNOWN_UUID, "amount_paise": 1,
                              "idempotency_key": "trace-" + trace},
                     headers={"X-Request-Id": trace})
    served_by = traced.header("X-Instance-Id")

    # The log ring buffer is per-instance, so behind a load balancer the log
    # read can land on a different replica than the one that served the traced
    # request. Retry until we are reading the right instance's buffer rather
    # than reporting a failure that is really just a routing coincidence.
    found = False
    for _ in range(12):
        logs = request(base, "GET", "/debug/logs?n=500")
        if logs.header("X-Instance-Id") == served_by:
            found = trace in logs.text
            break
    else:
        logs = request(base, "GET", "/debug/logs?n=500")
        found = trace in logs.text
    REPORT.check(found,
                 "the correlation id actually reaches the structured logs",
                 f"searched the last 500 lines on instance {served_by}")

    section("Error format")
    err = request(base, "GET", f"/wallets/{UNKNOWN_UUID}", token=token)
    REPORT.check("problem+json" in (err.header("Content-Type") or ""),
                 "errors use RFC 9457 application/problem+json",
                 f"got {err.header('Content-Type')}")
    for field in ["type", "title", "status", "detail", "code"]:
        REPORT.check(field in err.body, f"    ...problem detail carries '{field}'")
    REPORT.check(bool(err.body.get("correlation_id")),
                 "    ...and the correlation id, so a caller can quote one id")


def main():
    global VERBOSE
    p = argparse.ArgumentParser(description="Exercise every endpoint and every status code.")
    p.add_argument("url", nargs="?", default="http://localhost:8080")
    p.add_argument("-v", "--verbose", action="store_true",
                   help="print every request and response")
    args = p.parse_args()
    VERBOSE = args.verbose
    base = args.url

    print(f"{BOLD}API coverage - wallet & P2P transfer{RESET}")
    print(f"  target: {base}")
    health = request(base, "GET", "/health")
    if health.status != 200:
        raise SystemExit(f"{RED}{base} is not healthy (status {health.status}); aborting.{RESET}")
    print(f"  {GREEN}target healthy{RESET}")

    wallets(base)
    token = transfers(base)
    operational(base)
    cross_cutting(base, token)

    print(f"\n{DIM}  HTTP status distribution: "
          f"{dict(sorted(STATS.status_counts.items()))}{RESET}")
    REPORT.check(STATS.server_errors == 0, "ZERO 5xx responses across the whole suite",
                 f"5xx count = {STATS.server_errors}")
    return REPORT.summary()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
