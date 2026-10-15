package com.banking.transactionservice.messaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Published to RabbitMQ after every transaction.
// notification-service consumes this and persists the notification.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {
    private Long userId;
    private String message;
    private String type;   // TRANSACTION
}
