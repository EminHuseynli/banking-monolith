package com.banking.accountservice.account.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

// Lightweight projection returned by the internal API consumed by transaction-service.
// Only includes fields transaction-service actually needs.
@Data
@Builder
public class AccountInternalResponse {
    private Long id;
    private BigDecimal balance;
    private String status;   // "ACTIVE" or "CLOSED" — String avoids shared enum dependency
    private Long userId;
}
