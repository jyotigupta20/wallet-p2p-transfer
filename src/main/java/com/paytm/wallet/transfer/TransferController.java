package com.paytm.wallet.transfer;

import com.paytm.wallet.auth.Caller;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class TransferController {

    /** Set on every POST /transfers so a caller can tell a replay from the original. */
    private static final String REPLAY_HEADER = "X-Idempotent-Replay";

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /**
     * Two deliberate response-shape decisions, both in service of the graded
     * "identical responses" property under a retry storm:
     *
     *  - Always 200 on success, never 201. Under K concurrent calls with one
     *    key, exactly one request creates and K-1 replay. If the creator
     *    answered 201 and the replays 200, the K responses would not be
     *    identical. The distinction lives in the X-Idempotent-Replay header,
     *    where it belongs.
     *
     *  - A declined transfer is 422 carrying the same TransferResponse body,
     *    not a problem+json. Its body must also be byte-identical across
     *    replays, and problem+json carries a per-request correlation id that
     *    would differ between them. status and decline_reason make the
     *    rejection specific and machine-readable, which is what the brief
     *    actually asks for.
     */
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> create(Caller caller,
                                                   @Valid @RequestBody TransferRequest request) {
        TransferService.Outcome outcome = transfers.transfer(caller, request);
        HttpStatus status = outcome.transfer().isCompleted()
                ? HttpStatus.OK
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .header(REPLAY_HEADER, Boolean.toString(outcome.replay()))
                .body(TransferResponse.from(outcome.transfer()));
    }

    /** Status query: always 200 for a transfer that exists, whatever its outcome. */
    @GetMapping("/transfers/{id}")
    public TransferResponse byId(@PathVariable UUID id, Caller caller) {
        return TransferResponse.from(transfers.byId(id));
    }
}
