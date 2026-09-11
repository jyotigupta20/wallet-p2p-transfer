"""
Shared HTTP plumbing for the burst harness and the API coverage suite.

Standard library only - no pip install, so it runs anywhere python3 exists.
"""

import json
import threading
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

GREEN, RED, YELLOW, CYAN, DIM, BOLD, RESET = (
    "\033[32m", "\033[31m", "\033[33m", "\033[36m", "\033[2m", "\033[1m", "\033[0m"
)

# Generous by default: when the app and its database are in different regions a
# contended transfer can legitimately take seconds, and a client-side timeout
# would be scored as a service failure it is not.
TIMEOUT_SECONDS = 60


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.status_counts = Counter()
        self.server_errors = 0

    def record(self, status):
        with self.lock:
            self.status_counts[status] += 1
            if status >= 500:
                self.server_errors += 1


STATS = Stats()


class Response:
    __slots__ = ("status", "body", "headers", "text")

    def __init__(self, status, text, headers):
        self.status = status
        self.text = text
        self.headers = {k.lower(): v for k, v in headers}
        try:
            self.body = json.loads(text) if text else {}
        except json.JSONDecodeError:
            self.body = {}

    def header(self, name):
        return self.headers.get(name.lower())

    def __repr__(self):
        return f"<{self.status} {self.text[:120]}>"


def request(base, method, path, token=None, payload=None, timeout=None,
            raw_body=None, headers=None, accept=None):
    """
    One HTTP call. Never raises for an HTTP status - a 4xx is data here, not an
    error - so a caller can assert on rejection behaviour as easily as success.
    """
    timeout = timeout or TIMEOUT_SECONDS
    if raw_body is not None:
        data = raw_body.encode()
    elif payload is not None:
        data = json.dumps(payload).encode()
    else:
        data = None

    req = urllib.request.Request(base.rstrip("/") + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if accept:
        req.add_header("Accept", accept)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            resp = Response(r.status, r.read().decode(errors="replace"), r.getheaders())
    except urllib.error.HTTPError as e:
        resp = Response(e.code, e.read().decode(errors="replace"), e.headers.items())
    except Exception as e:                                   # noqa: BLE001
        # A transport failure is a failure of the service under test and must
        # not be silently swallowed into a passing run.
        resp = Response(0, json.dumps({"transport_error": str(e)}), [])
    STATS.record(resp.status)
    return resp


def in_parallel(count, fn):
    """
    Run fn(i) `count` times, all released from a common barrier.

    The pool must have one thread per barrier party. Sizing the pool smaller
    than the barrier deadlocks instantly: the first `workers` threads block
    waiting for parties that can never be scheduled. So the pool is always
    `count` wide, and callers bound concurrency by choosing `count`.
    """
    barrier = threading.Barrier(count)
    results = [None] * count

    def run(i):
        barrier.wait()
        results[i] = fn(i)

    with ThreadPoolExecutor(max_workers=count) as pool:
        list(pool.map(run, range(count)))
    return results


class Report:
    """Accumulates checks, prints them as they happen, exits non-zero on any failure."""

    def __init__(self):
        self.checks = []

    def check(self, ok, label, detail=""):
        self.checks.append((bool(ok), label, detail))
        mark = f"{GREEN}PASS{RESET}" if ok else f"{RED}FAIL{RESET}"
        line = f"  [{mark}] {label}"
        if detail:
            line += f"  {DIM}{detail}{RESET}"
        print(line, flush=True)
        return bool(ok)

    @property
    def failed(self):
        return [c for c in self.checks if not c[0]]

    def summary(self):
        failed = self.failed
        total = len(self.checks)
        print()
        if failed:
            print(f"{RED}{BOLD}FAILED{RESET}  {len(failed)} of {total} checks failed:")
            for _, label, detail in failed:
                print(f"  {RED}x{RESET} {label}  {DIM}{detail}{RESET}")
            return 1
        print(f"{GREEN}{BOLD}ALL {total} CHECKS PASSED{RESET}")
        return 0
