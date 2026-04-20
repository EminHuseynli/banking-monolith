package com.banking.banking_monolith.account;

import com.banking.banking_monolith.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Account entity - mapped to the "accounts" table in the database
// Each account belongs to one user
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Unique account number generated in the format ACC-000001
    @Column(name = "account_number", unique = true, nullable = false)
    private String accountNumber;

    // CHECKING or SAVINGS
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    // Stored with high precision to avoid rounding errors in financial calculations
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    // ACTIVE or CLOSED - closed accounts cannot be used for transactions
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    // The user who owns this account
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Automatically set creation time before saving to DB
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    //
}
