#!/usr/bin/env python3
"""
Crash safety: SIGKILL a replica mid-burst, restart it, replay every key.

The property under test is the one that matters for money: a transfer that was
in flight when its process was killed either applied completely or not at all,
and replaying its idempotency_key afterwards returns that same outcome rather
than applying it a second time.

SIGKILL, not SIGTERM, deliberately - graceful shutdown would drain in-flight
work and prove nothing. Postgres rolls back the uncommitted transaction of a
connection that dies, so each transfer resolves to committed-or-nothing; the
idempotency key then makes the replay agree with whatever happened.

Local only: it needs docker compose to do the killing.
"""

import os
import subprocess
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apiclient import BOLD, DIM, GREEN, RED, RESET, Report, request   # noqa: E402

REPORT = Report()
AMOUNT = 1_000


def compose(*args):
    env = dict(os.environ)
    env["PATH"] = env.get("PATH", "") + ":" + os.path.expanduser("~/.docker/bin")
    return subprocess.run(["docker", "compose", *args], capture_output=True, text=True, env=env)


def wait_healthy(url, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if request(url, "GET", "/health", timeout=3).status == 200:
            return True
        time.sleep(1)
    return False


def main():
    urls = sys.argv[1:] or ["http://localhost:8080", "http://localhost:8081"]
    victim = os.environ.get("VICTIM", "app-1")
    count = int(os.environ.get("TRANSFERS", "400"))

    print(f"{BOLD}Crash safety - SIGKILL {victim} mid-burst, restart, replay every key{RESET}")
    for u in urls:
        if request(u, "GET", "/health").status != 200:
            raise SystemExit(f"{RED}{u} is not healthy; bring the stack up first.{RESET}")

    # Two wallets, maximum contention, so the kill lands inside a real transaction.
    alice = f"crash-a-{uuid.uuid4()}"
    bob = f"crash-b-{uuid.uuid4()}"
    aw = request(urls[0], "POST", "/wallets", token=alice).body["wallet_id"]
    bw = request(urls[0], "POST", "/wallets", token=bob).body["wallet_id"]
    start_a = request(urls[0], "GET", f"/wallets/{aw}", token=alice).body["balance_paise"]
    start_b = request(urls[0], "GET", f"/wallets/{bw}", token=bob).body["balance_paise"]
    inv_before = request(urls[0], "GET", "/admin/invariants").body

    keys = [f"crash-{uuid.uuid4()}" for _ in range(count)]
    payloads = [{"from": aw, "to": bw, "amount_paise": AMOUNT, "idempotency_key": k} for k in keys]

    # --- fire, and kill the victim partway through -------------------------
    print(f"\n{BOLD}1. firing {count} transfers; killing {victim} partway{RESET}")
    import threading
    first_pass = [None] * count
    killed = threading.Event()
    answered = threading.Semaphore(0)

    def send(i):
        first_pass[i] = request(urls[i % len(urls)], "POST", "/transfers",
                                token=alice, payload=payloads[i], timeout=20)
        answered.release()

    # Fire the kill on PROGRESS, not on a guessed delay. A fixed sleep is a race
    # against the service: these transfers take single-digit milliseconds each,
    # so a 350ms delay simply landed after the burst had finished and the test
    # proved nothing while reporting success.
    def kill():
        for _ in range(max(count // 10, 1)):
            answered.acquire()
        compose("kill", "-s", "SIGKILL", victim)
        killed.set()

    threads = [threading.Thread(target=send, args=(i,)) for i in range(count)]
    killer = threading.Thread(target=kill)
    killer.start()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    killer.join()

    survived = sum(1 for r in first_pass if r and r.status in (200, 422))
    lost = count - survived
    print(f"{DIM}   {survived} answered, {lost} lost to the kill{RESET}")
    REPORT.check(killed.is_set(), "the victim really was SIGKILLed")
    REPORT.check(lost > 0, "the kill actually interrupted in-flight work",
                 f"{lost} requests never got an answer")

    # --- bring it back ------------------------------------------------------
    print(f"\n{BOLD}2. restarting {victim}{RESET}")
    compose("up", "-d", victim)
    REPORT.check(all(wait_healthy(u) for u in urls), "every replica is healthy again")

    # --- replay every key ---------------------------------------------------
    print(f"\n{BOLD}3. replaying all {count} idempotency keys{RESET}")
    replays = [request(urls[i % len(urls)], "POST", "/transfers", token=alice,
                       payload=payloads[i], timeout=30) for i in range(count)]

    statuses = {r.status for r in replays}
    REPORT.check(statuses <= {200, 422}, "every replay resolved cleanly",
                 f"statuses seen: {sorted(statuses)}")

    completed_keys = [i for i, r in enumerate(replays) if r.status == 200]
    ids = {r.body.get("transfer_id") for r in replays if r.body.get("transfer_id")}
    REPORT.check(len(ids) == count, "each key maps to exactly ONE transfer",
                 f"{len(ids)} distinct transfer ids for {count} keys")

    # --- the money -----------------------------------------------------------
    end_a = request(urls[0], "GET", f"/wallets/{aw}", token=alice).body["balance_paise"]
    end_b = request(urls[0], "GET", f"/wallets/{bw}", token=bob).body["balance_paise"]
    expected_moved = len(completed_keys) * AMOUNT

    print(f"\n{BOLD}4. the money{RESET}")
    REPORT.check(start_a - end_a == expected_moved,
                 "sender debited exactly once per completed key",
                 f"debited {start_a - end_a}, expected {expected_moved}")
    REPORT.check(end_b - start_b == expected_moved,
                 "recipient credited exactly once per completed key",
                 f"credited {end_b - start_b}, expected {expected_moved}")
    REPORT.check(start_a + start_b == end_a + end_b,
                 "CONSERVATION across the crash", f"{start_a + start_b} -> {end_a + end_b}")

    inv = request(urls[0], "GET", "/admin/invariants").body
    REPORT.check(inv["negative_balance_count"] == 0, "no negative balance")
    REPORT.check(inv["transfer_ledger_sum_paise"] == 0, "ledger still sums to zero")
    REPORT.check(inv["wallets_disagreeing_with_ledger"] == 0,
                 "every wallet reconciles against its ledger")
    REPORT.check(inv["holds"], "all invariants hold after the crash")
    REPORT.check(inv["total_wallet_balance_paise"] - inv_before["total_wallet_balance_paise"]
                 == inv["total_issued_paise"] - inv_before["total_issued_paise"],
                 "no money created or destroyed by the crash")

    return REPORT.summary()


if __name__ == "__main__":
    sys.exit(main())
