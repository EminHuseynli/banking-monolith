package com.banking.accountservice.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BalanceUpdateRequest {

    @NotNull
    @DecimalMin(value = "0.0001", message = "Amount must be positive")
    private BigDecimal amount;
}
