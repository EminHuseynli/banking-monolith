package com.banking.accountservice.account.dto;

import com.banking.accountservice.account.AccountStatus;
import com.banking.accountservice.account.AccountType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AccountResponse {
    private Long id;
    private String accountNumber;
    private AccountType accountType;
    private BigDecimal balance;
    private AccountStatus status;
    private Long userId;
    private LocalDateTime createdAt;
}
