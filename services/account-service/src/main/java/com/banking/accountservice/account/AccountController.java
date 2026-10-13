package com.banking.accountservice.account;

import com.banking.accountservice.account.dto.AccountResponse;
import com.banking.accountservice.account.dto.CreateAccountRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// All endpoints receive the authenticated user's ID from the X-User-Id header,
// which is set by the api-gateway after JWT validation (Token Relay pattern).
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getUserAccounts(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(accountService.getUserAccounts(userId));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<AccountResponse> getBalance(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(accountService.getBalance(id, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AccountResponse> closeAccount(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(accountService.closeAccount(id, userId));
    }
}
