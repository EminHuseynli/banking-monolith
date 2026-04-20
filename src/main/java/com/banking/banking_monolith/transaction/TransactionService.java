package com.banking.banking_monolith.transaction;

import com.banking.banking_monolith.account.Account;
import com.banking.banking_monolith.account.AccountRepository;
import com.banking.banking_monolith.account.AccountService;
import com.banking.banking_monolith.account.AccountStatus;
import com.banking.banking_monolith.exception.AccountNotActiveException;
import com.banking.banking_monolith.exception.InsufficientFundsException;
import com.banking.banking_monolith.exception.ResourceNotFoundException;
import com.banking.banking_monolith.notification.NotificationService;
import com.banking.banking_monolith.notification.NotificationType;
import com.banking.banking_monolith.transaction.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

// Handles all transaction business logic: deposit, withdraw, and transfer
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final NotificationService notificationService;

    // Adds money to the given account - anyone can deposit into any active account
    @Transactional
    public TransactionResponse deposit(DepositRequest request) {
        Account account = accountService.getActiveAccount(request.getAccountId());

        account.setBalance(account.getBalance().add(request.getAmount()));
        accountRepository.save(account);

        Transaction transaction = Transaction.builder()
                .transactionType(TransactionType.DEPOSIT)
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(TransactionStatus.SUCCESS)
                .sourceAccount(account)
                .build();

        Transaction saved = transactionRepository.save(transaction);
        notificationService.createNotification(account.getUser(),
                request.getAmount() + "$ has been deposited into your account.", NotificationType.TRANSACTION);

        return toResponse(saved);
    }

    // Removes money from the account - only the account owner can withdraw
    @Transactional
    public TransactionResponse withdraw(WithdrawRequest request, Long userId) {
        Account account = accountService.getActiveAccount(request.getAccountId());

        // Ensure the logged-in user owns this account
        if (!account.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Account not found for this user");
        }

        if (account.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds");
        }

        account.setBalance(account.getBalance().subtract(request.getAmount()));
        accountRepository.save(account);

        Transaction transaction = Transaction.builder()
                .transactionType(TransactionType.WITHDRAWAL)
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(TransactionStatus.SUCCESS)
                .sourceAccount(account)
                .build();

        Transaction saved = transactionRepository.save(transaction);
        notificationService.createNotification(account.getUser(),
                request.getAmount() + " $ has been withdrawn from your account.", NotificationType.TRANSACTION);

        return toResponse(saved);
    }

    // Moves money between two accounts - only the owner of the source account can initiate a transfer
    // Both balance changes happen in a single transaction so they either both succeed or both fail.
    // Accounts are locked in ascending ID order to prevent deadlock on concurrent A→B / B→A transfers.
    @Transactional
    public TransactionResponse transfer(TransferRequest request, Long userId) {
        Long srcId = request.getSourceAccountId();
        Long tgtId = request.getTargetAccountId();

        Long loId = Math.min(srcId, tgtId);
        Long hiId = Math.max(srcId, tgtId);

        Account loAccount = accountRepository.findByIdWithLock(loId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + loId));
        Account hiAccount = accountRepository.findByIdWithLock(hiId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + hiId));

        Account sourceAccount = srcId.equals(loId) ? loAccount : hiAccount;
        Account targetAccount = srcId.equals(loId) ? hiAccount : loAccount;

        if (!sourceAccount.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Account not found for this user");
        }
        if (sourceAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException("Account is not active: " + sourceAccount.getId());
        }
        if (targetAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException("Account is not active: " + targetAccount.getId());
        }
        if (sourceAccount.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds");
        }

        sourceAccount.setBalance(sourceAccount.getBalance().subtract(request.getAmount()));
        targetAccount.setBalance(targetAccount.getBalance().add(request.getAmount()));

        accountRepository.save(sourceAccount);
        accountRepository.save(targetAccount);

        Transaction transaction = Transaction.builder()
                .transactionType(TransactionType.TRANSFER)
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(TransactionStatus.SUCCESS)
                .sourceAccount(sourceAccount)
                .targetAccount(targetAccount)
                .build();

        Transaction saved = transactionRepository.save(transaction);

        // Notify both the sender and the receiver
        notificationService.createNotification(sourceAccount.getUser(),
                request.getAmount() + " $ has been transferred → " + targetAccount.getAccountNumber(),
                NotificationType.TRANSACTION);

        notificationService.createNotification(targetAccount.getUser(),
                request.getAmount() + " $ has been received ← " + sourceAccount.getAccountNumber(),
                NotificationType.TRANSACTION);

        return toResponse(saved);
    }

    // Returns all transactions for the given account (as source or target), newest first
    public List<TransactionResponse> getAccountHistory(Long accountId) {
        return transactionRepository.findByAccountId(accountId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Returns transactions filtered by a date range
    public List<TransactionResponse> getAccountHistoryByDateRange(Long accountId,
                                                                    LocalDateTime start,
                                                                    LocalDateTime end) {
        return transactionRepository.findByAccountIdAndDateRange(accountId, start, end)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Converts Transaction entity to TransactionResponse DTO
    private TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId())
                .transactionType(t.getTransactionType())
                .amount(t.getAmount())
                .description(t.getDescription())
                .status(t.getStatus())
                .sourceAccountId(t.getSourceAccount().getId())
                .targetAccountId(t.getTargetAccount() != null ? t.getTargetAccount().getId() : null)
                .createdAt(t.getCreatedAt())
                .build();
    }
}
