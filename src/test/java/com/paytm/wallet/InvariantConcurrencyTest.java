package com.paytm.wallet;

import com.paytm.wallet.admin.Invariants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four graded invariants, proven against a real Postgres in CI rather than
 * only by a live burst against a deployed URL.
 *
 * Every test here releases its threads from a CyclicBarrier so requests are
 * genuinely simultaneous. Two of these are regression tests for bugs that
 * actually shipped into a running instance and were caught by burst.sh:
 * {@link #bidirectionalTransfers_doNotDeadlock()} and
 * {@link #concurrentGetOrCreate_forBrandNewUser_yieldsExactlyOneWallet()}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.wallet.opening-balance-paise=1000000",
                "logging.level.root=WARN"
        })
@Testcontainers
class InvariantConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final long OPENING = 1_000_000L;

    @Autowired
    TestRestTemplate http;

    // ------------------------------------------------------------------
    // INVARIANT 4 - race-free get-or-create
    // ------------------------------------------------------------------

    @Test
    void concurrentGetOrCreate_forBrandNewUser_yieldsExactlyOneWallet() throws Exception {
        String token = "brand-new-" + UUID.randomUUID();
        int n = 64;

        List<ResponseEntity<Map>> responses = allAtOnce(n,
                i -> () -> post("/wallets", token, null));

        assertThat(responses).allSatisfy(r ->
                assertThat(r.getStatusCode().value()).isEqualTo(200));

        Set<Object> walletIds = responses.stream()
                .map(r -> r.getBody().get("wallet_id")).collect(Collectors.toSet());
        assertThat(walletIds)
                .as("64 simultaneous POST /wallets for one brand-new user must yield ONE wallet")
                .hasSize(1);

        long created = responses.stream()
                .filter(r -> "true".equals(r.getHeaders().getFirst("X-Wallet-Created")))
                .count();
        assertThat(created).as("exactly one caller may create it").isEqualTo(1);

        Set<Long> balances = responses.stream()
                .map(r -> ((Number) r.getBody().get("balance_paise")).longValue())
                .collect(Collectors.toSet());
        assertThat(balances).as("all responses agree on the balance").containsExactly(OPENING);
    }

    // ------------------------------------------------------------------
    // INVARIANT 3 - exactly-once
    // ------------------------------------------------------------------

    @Test
    void idempotentRetryStorm_appliesTheTransferExactlyOnce() throws Exception {
        var alice = newAccount();
        var bob = newAccount();
        long amount = 25_000;
        String key = "storm-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "from", alice.wallet, "to", bob.wallet,
                "amount_paise", amount, "idempotency_key", key);

        int k = 64;
        List<ResponseEntity<Map>> responses = allAtOnce(k,
                i -> () -> post("/transfers", alice.token, body));

        assertThat(responses).allSatisfy(r ->
                assertThat(r.getStatusCode().value())
                        .as("zero 5xx on the replay path").isEqualTo(200));

        Set<Object> transferIds = responses.stream()
                .map(r -> r.getBody().get("transfer_id")).collect(Collectors.toSet());
        assertThat(transferIds).as("one key means one transfer").hasSize(1);

        Set<Map> bodies = responses.stream().map(ResponseEntity::getBody).collect(Collectors.toSet());
        assertThat(bodies).as("every response body identical").hasSize(1);

        long originals = responses.stream()
                .filter(r -> "false".equals(r.getHeaders().getFirst("X-Idempotent-Replay")))
                .count();
        assertThat(originals).as("exactly one original, the rest replays").isEqualTo(1);

        assertThat(balanceOf(alice)).isEqualTo(OPENING - amount);
        assertThat(balanceOf(bob)).isEqualTo(OPENING + amount);
    }

    @Test
    void sameKeyWithDifferentBody_conflictsAndDoesNotDebitTwice() {
        var alice = newAccount();
        var bob = newAccount();
        String key = "reuse-" + UUID.randomUUID();

        ResponseEntity<Map> first = post("/transfers", alice.token, Map.of(
                "from", alice.wallet, "to", bob.wallet,
                "amount_paise", 1_000, "idempotency_key", key));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        long afterFirst = balanceOf(alice);

        ResponseEntity<Map> conflict = post("/transfers", alice.token, Map.of(
                "from", alice.wallet, "to", bob.wallet,
                "amount_paise", 7_777, "idempotency_key", key));

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSE");
        assertThat(balanceOf(alice)).as("a conflicting replay must not debit").isEqualTo(afterFirst);
    }

    /**
     * The idempotency key is scoped to the caller, not global.
     *
     * If it were global, one client's choice of key would silently suppress a
     * different client's transfer - the second caller would get back someone
     * else's transfer as a "replay", having moved no money of their own. That
     * is a correctness bug that looks like idempotency working.
     */
    @Test
    void theSameKeyFromTwoDifferentUsersIsTwoDifferentTransfers() {
        var alice = newAccount();
        var bob = newAccount();
        var carol = newAccount();
        String sharedKey = "same-key-different-callers";

        ResponseEntity<Map> first = post("/transfers", alice.token, Map.of(
                "from", alice.wallet, "to", carol.wallet,
                "amount_paise", 1_000, "idempotency_key", sharedKey));
        ResponseEntity<Map> second = post("/transfers", bob.token, Map.of(
                "from", bob.wallet, "to", carol.wallet,
                "amount_paise", 2_000, "idempotency_key", sharedKey));

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value())
                .as("the second caller must not be blocked by the first caller's key")
                .isEqualTo(200);
        assertThat(second.getBody().get("transfer_id"))
                .as("two callers, two transfers")
                .isNotEqualTo(first.getBody().get("transfer_id"));

        assertThat(balanceOf(alice)).isEqualTo(OPENING - 1_000);
        assertThat(balanceOf(bob)).isEqualTo(OPENING - 2_000);
        assertThat(balanceOf(carol))
                .as("both transfers landed")
                .isEqualTo(OPENING + 3_000);
        assertInvariantsHold();
    }

    // ------------------------------------------------------------------
    // INVARIANTS 1 and 2 - conservation and no overdraft
    // ------------------------------------------------------------------

    /**
     * Regression test for a real deadlock.
     *
     * Every thread transfers between the SAME two wallets, half in each
     * direction, all released together. Before lockWallet() was changed from
     * FOR UPDATE to FOR NO KEY UPDATE this produced a storm of 40P01s (the
     * foreign keys on transfers take FOR KEY SHARE on both wallet rows, so both
     * directions ended up upgrading a shared lock they both held).
     *
     * The assertion that matters is not just conservation - it is that not one
     * request returned a 5xx.
     */
    @Test
    void bidirectionalTransfers_doNotDeadlock() throws Exception {
        var alice = newAccount();
        var bob = newAccount();
        int n = 120;

        List<ResponseEntity<Map>> responses = allAtOnce(n, i -> () -> {
            boolean forward = (i % 2 == 0);
            var from = forward ? alice : bob;
            var to = forward ? bob : alice;
            return post("/transfers", from.token, Map.of(
                    "from", from.wallet, "to", to.wallet,
                    "amount_paise", 100, "idempotency_key", "bidi-" + UUID.randomUUID()));
        });

        List<Integer> codes = responses.stream().map(r -> r.getStatusCode().value()).toList();
        assertThat(codes)
                .as("A->B and B->A concurrently must never deadlock into a 5xx")
                .allMatch(c -> c == 200 || c == 422);

        assertThat(balanceOf(alice) + balanceOf(bob))
                .as("conservation across both wallets").isEqualTo(2 * OPENING);
        assertInvariantsHold();
    }

    @Test
    void manyConcurrentTransfersOverFewWallets_conserveMoney() throws Exception {
        int walletCount = 4;
        List<Account> accounts = IntStream.range(0, walletCount)
                .mapToObj(i -> newAccount()).toList();
        long before = accounts.stream().mapToLong(this::balanceOf).sum();

        int n = 200;
        var random = new java.util.Random(42);
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int a = random.nextInt(walletCount);
            int b = (a + 1 + random.nextInt(walletCount - 1)) % walletCount;
            pairs.add(new int[]{a, b});
        }

        List<ResponseEntity<Map>> responses = allAtOnce(n, i -> () -> {
            var from = accounts.get(pairs.get(i)[0]);
            var to = accounts.get(pairs.get(i)[1]);
            return post("/transfers", from.token, Map.of(
                    "from", from.wallet, "to", to.wallet,
                    "amount_paise", 1_000, "idempotency_key", "mix-" + UUID.randomUUID()));
        });

        assertThat(responses).allSatisfy(r ->
                assertThat(r.getStatusCode().value()).isIn(200, 422));
        assertThat(accounts.stream().mapToLong(this::balanceOf).sum())
                .as("CONSERVATION: no money created or destroyed").isEqualTo(before);
        assertInvariantsHold();
    }

    @Test
    void overdraftStorm_neverProducesANegativeBalance() throws Exception {
        var alice = newAccount();
        var bob = newAccount();
        long amount = OPENING / 2 + 1;          // at most one can succeed
        int n = 64;

        List<ResponseEntity<Map>> responses = allAtOnce(n, i -> () ->
                post("/transfers", alice.token, Map.of(
                        "from", alice.wallet, "to", bob.wallet,
                        "amount_paise", amount, "idempotency_key", "over-" + UUID.randomUUID())));

        long succeeded = responses.stream().filter(r -> r.getStatusCode().value() == 200).count();
        assertThat(succeeded).as("at most one debit of more than half the balance").isLessThanOrEqualTo(1);
        assertThat(responses).allSatisfy(r ->
                assertThat(r.getStatusCode().value()).isIn(200, 422));
        assertThat(balanceOf(alice)).isGreaterThanOrEqualTo(0);
        assertInvariantsHold();
    }

    // ------------------------------------------------------------------
    // Money hygiene
    // ------------------------------------------------------------------

    @Test
    void fractionalAmountIsRejectedRatherThanTruncated() {
        var alice = newAccount();
        var bob = newAccount();
        HttpHeaders headers = jsonHeaders(alice.token);
        String raw = """
                {"from":"%s","to":"%s","amount_paise":12.5,"idempotency_key":"frac-1"}
                """.formatted(alice.wallet, bob.wallet);

        ResponseEntity<Map> r = http.exchange("/transfers", HttpMethod.POST,
                new HttpEntity<>(raw, headers), Map.class);

        assertThat(r.getStatusCode().value())
                .as("12.5 paise must be refused, never silently truncated to 12").isEqualTo(400);
        assertThat(balanceOf(alice)).isEqualTo(OPENING);
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        var alice = newAccount();
        var bob = newAccount();
        for (long bad : new long[]{0L, -1L, -100_000L}) {
            ResponseEntity<Map> r = post("/transfers", alice.token, Map.of(
                    "from", alice.wallet, "to", bob.wallet,
                    "amount_paise", bad, "idempotency_key", "bad-" + bad));
            assertThat(r.getStatusCode().value())
                    .as("amount_paise=%d must be a 400", bad).isEqualTo(400);
        }
        assertThat(balanceOf(bob)).as("a negative amount must not drain the destination")
                .isEqualTo(OPENING);
    }

    @Test
    void debitingAWalletYouDoNotOwnIsForbidden() {
        var alice = newAccount();
        var bob = newAccount();
        ResponseEntity<Map> r = post("/transfers", bob.token, Map.of(
                "from", alice.wallet, "to", bob.wallet,
                "amount_paise", 100, "idempotency_key", "theft-" + UUID.randomUUID()));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(balanceOf(alice)).isEqualTo(OPENING);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private record Account(String token, String wallet) { }

    private Account newAccount() {
        String token = "acct-" + UUID.randomUUID();
        ResponseEntity<Map> r = post("/wallets", token, null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return new Account(token, (String) r.getBody().get("wallet_id"));
    }

    private long balanceOf(Account a) {
        ResponseEntity<Map> r = http.exchange("/wallets/" + a.wallet, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(a.token)), Map.class);
        return ((Number) r.getBody().get("balance_paise")).longValue();
    }

    private void assertInvariantsHold() {
        ResponseEntity<Invariants> r =
                http.getForEntity("/admin/invariants", Invariants.class);
        Invariants inv = r.getBody();
        assertThat(inv.negativeBalanceCount()).as("no negative balances").isZero();
        assertThat(inv.transferLedgerSumPaise()).as("double-entry ledger sums to zero").isZero();
        assertThat(inv.walletsDisagreeingWithLedger())
                .as("every balance reconciles against its ledger").isZero();
        assertThat(inv.transfersPending())
                .as("no transfer left claimed but unfinalised").isZero();
        assertThat(inv.holds()).as("server-side invariant check").isTrue();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path, String token, Object body) {
        return http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(token)), Map.class);
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    /** Runs n tasks with all threads released from a common barrier. */
    private <T> List<T> allAtOnce(int n, java.util.function.IntFunction<Callable<T>> factory)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(n);
        AtomicInteger failures = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = IntStream.range(0, n)
                    .mapToObj(i -> pool.submit(() -> {
                        barrier.await();
                        return factory.apply(i).call();
                    }))
                    .toList();
            List<T> out = new ArrayList<>(n);
            for (Future<T> f : futures) {
                try {
                    out.add(f.get());
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }
            assertThat(failures.get()).as("no request failed at the transport level").isZero();
            return out;
        }
    }
}
