package com.banking.accountservice.account;

import com.banking.accountservice.account.dto.AccountInternalResponse;
import com.banking.accountservice.account.dto.BalanceUpdateRequest;
import com.banking.accountservice.account.dto.InternalTransferRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Internal API consumed only by transaction-service via Feign.
// Not exposed through the api-gateway — service-to-service communication only.
@RestController
@RequestMapping("/api/accounts/internal")
@RequiredArgsConstructor
public class AccountInternalController {

    private final AccountService accountService;

    @GetMapping("/{id}")
    public ResponseEntity<AccountInternalResponse> getAccount(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountInternal(id));
    }

    @PostMapping("/{id}/credit")
    public ResponseEntity<AccountInternalResponse> credit(
            @PathVariable Long id,
            @Valid @RequestBody BalanceUpdateRequest request) {
        return ResponseEntity.ok(accountService.credit(id, request.getAmount()));
    }

    @PostMapping("/{id}/debit")
    public ResponseEntity<AccountInternalResponse> debit(
            @PathVariable Long id,
            @Valid @RequestBody BalanceUpdateRequest request) {
        return ResponseEntity.ok(accountService.debit(id, request.getAmount()));
    }

    // Atomic transfer: both balance changes happen in one DB transaction within account-service.
    // This is the key pattern that preserves consistency for transfers after DB separation.
    @PostMapping("/transfer")
    public ResponseEntity<Void> transfer(@Valid @RequestBody InternalTransferRequest request) {
        accountService.transfer(request.getSourceAccountId(), request.getTargetAccountId(), request.getAmount());
        return ResponseEntity.ok().build();
    }
}
