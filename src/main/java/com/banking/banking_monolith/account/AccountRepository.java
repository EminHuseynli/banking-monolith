package com.banking.banking_monolith.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

// Database access for Account entity
public interface AccountRepository extends JpaRepository<Account, Long> {

    // Returns all accounts owned by the given user
    List<Account> findByUserId(Long userId);

    // Acquires a pessimistic write lock - used by transfer to prevent concurrent balance corruption
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") Long id);
}
