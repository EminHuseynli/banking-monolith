package com.banking.transactionservice.account;

import com.banking.transactionservice.account.dto.AccountInternalResponse;
import com.banking.transactionservice.account.dto.BalanceUpdateRequest;
import com.banking.transactionservice.account.dto.InternalTransferRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

// Feign client for account-service internal API.
// URL from config: account-service.url (docker: http://account-service-blue:8082, local: http://localhost:8082)
@FeignClient(name = "account-service", url = "${account-service.url}")
public interface AccountClient {

    @GetMapping("/api/accounts/internal/{id}")
    AccountInternalResponse getAccount(@PathVariable Long id);

    @PostMapping("/api/accounts/internal/{id}/credit")
    AccountInternalResponse credit(@PathVariable Long id, @RequestBody BalanceUpdateRequest request);

    @PostMapping("/api/accounts/internal/{id}/debit")
    AccountInternalResponse debit(@PathVariable Long id, @RequestBody BalanceUpdateRequest request);

    @PostMapping("/api/accounts/internal/transfer")
    void transfer(@RequestBody InternalTransferRequest request);
}
