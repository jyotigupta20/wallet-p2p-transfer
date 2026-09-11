package com.paytm.wallet.kernel.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which SQLSTATEs mean "nothing was applied, try again".
 *
 * This classification decides whether a failure is reported as a retryable 503
 * or an opaque 500, and getting it wrong is invisible: the service looks fine
 * until a database hiccup is blamed on the application. It earned a test when a
 * live 5xx turned out to be a waking, autosuspended database returning 57P03 -
 * a code the original hand-enumerated list did not include.
 */
class RetryableSqlStateTest {

    @ParameterizedTest(name = "{0} is retryable")
    @ValueSource(strings = {
            "40001",  // serialization_failure
            "40P01",  // deadlock_detected
            "55P03",  // lock_not_available - our lock_timeout
            "57014",  // query_canceled - our statement_timeout
            "53300",  // too_many_connections
            "08000", "08001", "08003", "08004", "08006", "08007", "08P01",
            "57P01",  // admin_shutdown
            "57P02",  // crash_shutdown
            "57P03",  // cannot_connect_now - the autosuspended database waking up
    })
    void retryable(String sqlState) {
        assertThat(ApiExceptionHandler.isRetryable(sqlState)).isTrue();
    }

    @ParameterizedTest(name = "{0} is NOT retryable")
    @ValueSource(strings = {
            "23505",  // unique_violation - a real conflict, retrying changes nothing
            "23503",  // foreign_key_violation
            "23514",  // check_violation - e.g. a negative balance was attempted
            "22003",  // numeric_value_out_of_range
            "42601",  // syntax_error - our bug, not a transient one
            "42P01",  // undefined_table
            "",
    })
    @DisplayName("permanent failures must not be dressed up as retryable")
    void notRetryable(String sqlState) {
        assertThat(ApiExceptionHandler.isRetryable(sqlState)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void nullIsNotRetryable() {
        assertThat(ApiExceptionHandler.isRetryable(null)).isFalse();
    }
}
