package com.banking.accountservice.account;

import com.banking.accountservice.account.dto.AccountInternalResponse;
import com.banking.accountservice.account.dto.AccountResponse;
import com.banking.accountservice.account.dto.CreateAccountRequest;
import com.banking.accountservice.exception.AccountNotActiveException;
import com.banking.accountservice.exception.InsufficientFundsException;
import com.banking.accountservice.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    // Creates a new account for the given userId (from X-User-Id header via gateway)
    public AccountResponse createAccount(Long userId, CreateAccountRequest request) {
        Account account = Account.builder()
                .accountNumber("PENDING")
                .accountType(request.getAccountType())
                .balance(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .userId(userId)
                .build();

        Account saved = accountRepository.save(account);
        saved.setAccountNumber(String.format("ACC-%06d", saved.getId()));
        saved = accountRepository.save(saved);

        return toResponse(saved);
    }

    public AccountResponse getBalance(Long accountId, Long userId) {
        return toResponse(findByIdAndUser(accountId, userId));
    }

    public List<AccountResponse> getUserAccounts(Long userId) {
        return accountRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public AccountResponse closeAccount(Long accountId, Long userId) {
        Account account = findByIdAndUser(accountId, userId);
        account.setStatus(AccountStatus.CLOSED);
        return toResponse(accountRepository.save(account));
    }

    // Used by transaction-service (internal call) to get an active account
    public Account getActiveAccount(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException("Account is not active: " + accountId);
        }
        return account;
    }

    // ---- Internal API (called by transaction-service via Feign) ----

    public AccountInternalResponse getAccountInternal(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        return toInternalResponse(account);
    }

    @Transactional
    public AccountInternalResponse credit(Long accountId, BigDecimal amount) {
        Account account = getActiveAccount(accountId);
        account.setBalance(account.getBalance().add(amount));
        return toInternalResponse(accountRepository.save(account));
    }

    @Transactional
    public AccountInternalResponse debit(Long accountId, BigDecimal amount) {
        Account account = getActiveAccount(accountId);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient funds in account: " + accountId);
        }
        account.setBalance(account.getBalance().subtract(amount));
        return toInternalResponse(accountRepository.save(account));
    }

    // Atomic transfer: both balance changes happen in one DB transaction.
    // This preserves atomicity even after schema separation — the accounts table
    // lives in account_schema and is owned exclusively by account-service.
    @Transactional
    public void transfer(Long sourceId, Long targetId, BigDecimal amount) {
        Account source = getActiveAccount(sourceId);
        Account target = getActiveAccount(targetId);
        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient funds in account: " + sourceId);
        }
        source.setBalance(source.getBalance().subtract(amount));
        target.setBalance(target.getBalance().add(amount));
        accountRepository.save(source);
        accountRepository.save(target);
    }

    private Account findByIdAndUser(Long accountId, Long userId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        if (!account.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Account not found for this user");
        }
        return account;
    }

    private AccountInternalResponse toInternalResponse(Account account) {
        return AccountInternalResponse.builder()
                .id(account.getId())
                .balance(account.getBalance())
                .status(account.getStatus().name())
                .userId(account.getUserId())
                .build();
    }

    private AccountResponse toResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .accountType(account.getAccountType())
                .balance(account.getBalance())
                .status(account.getStatus())
                .userId(account.getUserId())
                .createdAt(account.getCreatedAt())
                .build();
    }
}
