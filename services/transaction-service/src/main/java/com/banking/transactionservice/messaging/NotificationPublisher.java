package com.banking.transactionservice.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(Long userId, String message, String type) {
        NotificationEvent event = new NotificationEvent(userId, message, type);
        rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE, event);
        log.debug("Published notification event for userId={}", userId);
    }
}
