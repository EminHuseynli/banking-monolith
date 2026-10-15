package com.banking.transactionservice.account.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AccountInternalResponse {
    private Long id;
    private BigDecimal balance;
    private String status;
    private Long userId;
}
