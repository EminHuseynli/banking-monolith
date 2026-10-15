package com.banking.transactionservice.account.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InternalTransferRequest {
    private Long sourceAccountId;
    private Long targetAccountId;
    private BigDecimal amount;
}
