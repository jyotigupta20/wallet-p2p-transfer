package com.paytm.wallet.wallet;

import com.paytm.wallet.auth.Caller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /**
     * Get-or-create the wallet belonging to the bearer-identified caller.
     *
     * Always 200, never 201: N concurrent calls for a brand-new user must come
     * back byte-identical, and a status code that differs between the winner
     * and the losers would defeat that. Whether this call did the creating is
     * in the X-Wallet-Created header.
     */
    @PostMapping("/wallets")
    public ResponseEntity<WalletResponse> getOrCreate(Caller caller) {
        WalletService.GetOrCreate result = wallets.getOrCreate(caller);
        return ResponseEntity.ok()
                .header("X-Wallet-Created", Boolean.toString(result.created()))
                .body(WalletResponse.from(result.wallet()));
    }

    @GetMapping("/wallets/{id}")
    public WalletResponse balance(@PathVariable UUID id, Caller caller) {
        // Balances are readable by any authenticated caller: the burst script
        // asserts conservation across wallets it does not own. Auth
        // sophistication is explicitly out of scope; what is enforced is that
        // only the owner may DEBIT a wallet (see TransferService).
        return WalletResponse.from(wallets.byId(id));
    }
}
