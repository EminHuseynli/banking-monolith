package com.banking.accountservice.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

// Used by transaction-service to perform an atomic balance transfer.
// Both the debit and credit happen in a single @Transactional call within account-service,
// preserving atomicity across the two balance updates even after DB schema separation.
@Data
public class InternalTransferRequest {

    @NotNull
    private Long sourceAccountId;

    @NotNull
    private Long targetAccountId;

    @NotNull
    @DecimalMin(value = "0.0001", message = "Amount must be positive")
    private BigDecimal amount;
}
