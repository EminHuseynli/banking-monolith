package com.banking.transactionservice.transaction.dto;

import com.banking.transactionservice.transaction.TransactionStatus;
import com.banking.transactionservice.transaction.TransactionType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TransactionResponse {
    private Long id;
    private TransactionType transactionType;
    private BigDecimal amount;
    private String description;
    private TransactionStatus status;
    private Long sourceAccountId;
    private Long targetAccountId;
    private LocalDateTime createdAt;
}
