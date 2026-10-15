package com.banking.transactionservice.transaction;

import com.banking.transactionservice.account.AccountClient;
import com.banking.transactionservice.account.dto.AccountInternalResponse;
import com.banking.transactionservice.account.dto.BalanceUpdateRequest;
import com.banking.transactionservice.account.dto.InternalTransferRequest;
import com.banking.transactionservice.exception.AccountNotActiveException;
import com.banking.transactionservice.exception.ResourceNotFoundException;
import com.banking.transactionservice.exception.ServiceUnavailableException;
import com.banking.transactionservice.messaging.NotificationPublisher;
import com.banking.transactionservice.transaction.dto.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final String ACCOUNT_SERVICE_CB = "accountService";

    private final TransactionRepository transactionRepository;
    private final AccountClient accountClient;
    private final NotificationPublisher notificationPublisher;

    @CircuitBreaker(name = ACCOUNT_SERVICE_CB, fallbackMethod = "depositFallback")
    public TransactionResponse deposit(DepositRequest request) {
        AccountInternalResponse account = getActiveAccount(request.getAccountId());

        accountClient.credit(account.getId(), new BalanceUpdateRequest(request.getAmount()));

        Transaction saved = transactionRepository.save(Transaction.builder()
                .transactionType(TransactionType.DEPOSIT)
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(TransactionStatus.SUCCESS)
                .sourceAccountId(account.getId())
                .build());

        notificationPublisher.publish(account.getUserId(),
                request.getAmount() + "$ has been deposited into your account.", "TRANSACTION");

        return toResponse(saved);
    }

    @CircuitBreaker(name = ACCOUNT_SERVICE_CB, fallbackMethod = "withdrawFallback")
    public TransactionResponse withdraw(WithdrawRequest request, Long userId) {
        AccountInternalResponse account = getActiveAccount(request.getAccountId());

        if (!account.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Account not found for this user");
        }

        accountClient.debit(account.getId(), new BalanceUpdateRequest(request.getAmount()));

        Transaction saved = transactionRepository.save(Transaction.builder()
                .transactionType(TransactionType.WITHDRAWAL)
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(TransactionStatus.SUCCESS)
                .sourceAccountId(account.getId())
                .build());

        notificationPublisher.publish(account.getUserId(),
                request.getAmount() + " $ has been withdrawn from your account.", "TRANSACTION");

        return toResponse(saved);
    }

    @CircuitBreaker(name = ACCOUNT_SERVICE_CB, fallbackMethod = "transferFallback")
    public TransactionResponse transfer(TransferRequest request, Long userId) {
        AccountInternalResponse source = getActiveAccount(request.getSourceAccountId());

        if (!source.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Account not found for this user");
        }

        AccountInternalResponse target = getActiveAccount(request.getTargetAccountId());

        accountClient.transfer(new InternalTransferRequest(
                source.getId(), target.getId(), request.getAmount()));

        Transaction saved = transactionRepository.save(Transaction.builder()
                .transactionType(TransactionType.TRANSFER)
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(TransactionStatus.SUCCESS)
                .sourceAccountId(source.getId())
                .targetAccountId(target.getId())
                .build());

        notificationPublisher.publish(source.getUserId(),
                request.getAmount() + " $ transferred → " + target.getId(), "TRANSACTION");
        notificationPublisher.publish(target.getUserId(),
                request.getAmount() + " $ received ← " + source.getId(), "TRANSACTION");

        return toResponse(saved);
    }

    public List<TransactionResponse> getAccountHistory(Long accountId) {
        return transactionRepository.findByAccountId(accountId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<TransactionResponse> getAccountHistoryByDateRange(Long accountId,
                                                                    LocalDateTime start,
                                                                    LocalDateTime end) {
        return transactionRepository.findByAccountIdAndDateRange(accountId, start, end)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ---- Fallback methods (circuit OPEN veya network/5xx hatası) ----
    // Not: 4xx hatalar (BadRequest, NotFound) ignore-exceptions ile circuit'e gitmez,
    // doğrudan GlobalExceptionHandler'a düşer. Fallback sadece gerçek servis kesintisinde çalışır.

    public TransactionResponse depositFallback(DepositRequest request, Throwable t) {
        rethrowIfBusinessError(t);
        throw new ServiceUnavailableException(
                "Account service is currently unavailable. Please try again later.");
    }

    public TransactionResponse withdrawFallback(WithdrawRequest request, Long userId, Throwable t) {
        rethrowIfBusinessError(t);
        throw new ServiceUnavailableException(
                "Account service is currently unavailable. Please try again later.");
    }

    public TransactionResponse transferFallback(TransferRequest request, Long userId, Throwable t) {
        rethrowIfBusinessError(t);
        throw new ServiceUnavailableException(
                "Account service is currently unavailable. Please try again later.");
    }

    // 4xx hatalar iş mantığı hatasıdır, servis kesintisi değil.
    // Fallback bunları yakalamamalı — GlobalExceptionHandler'a bırak.
    private void rethrowIfBusinessError(Throwable t) {
        if (t instanceof feign.FeignException.BadRequest ||
            t instanceof feign.FeignException.NotFound ||
            t instanceof ResourceNotFoundException ||
            t instanceof AccountNotActiveException) {
            throw (RuntimeException) t;
        }
    }

    // ---- Private helpers ----

    private AccountInternalResponse getActiveAccount(Long accountId) {
        AccountInternalResponse account = accountClient.getAccount(accountId);
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new AccountNotActiveException("Account is not active: " + accountId);
        }
        return account;
    }

    private TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId())
                .transactionType(t.getTransactionType())
                .amount(t.getAmount())
                .description(t.getDescription())
                .status(t.getStatus())
                .sourceAccountId(t.getSourceAccountId())
                .targetAccountId(t.getTargetAccountId())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
