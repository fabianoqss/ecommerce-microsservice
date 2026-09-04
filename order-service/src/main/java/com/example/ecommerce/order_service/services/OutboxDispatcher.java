package com.example.ecommerce.order_service.services;

import com.example.ecommerce.order_service.config.RabbitMQConfig;
import com.example.ecommerce.order_service.dtos.OrderEventDTO;
import com.example.ecommerce.order_service.entities.OutboxEvent;
import com.example.ecommerce.order_service.entities.OutboxStatus;
import com.example.ecommerce.order_service.repositories.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxDispatcher {

    private static final int MAX_ATTEMPTS = 10;
    private static final int CONFIRM_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void dispatch(Long outboxId) {
        OutboxEvent outbox = outboxEventRepository.findById(outboxId).orElse(null);
        if (outbox == null || outbox.getStatus() != OutboxStatus.PENDING) {
            return;
        }

        try {
            OrderEventDTO event = objectMapper.readValue(outbox.getPayload(), OrderEventDTO.class);
            CorrelationData correlationData = new CorrelationData(outbox.getEventId().toString());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.ROUTING_KEY,
                    event,
                    correlationData
            );

            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ rejeitou o evento: " + confirm.reason());
            }
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException("Evento não roteado: " + correlationData.getReturned().getReplyText());
            }

            outbox.setStatus(OutboxStatus.SENT);
            outbox.setSentAt(LocalDateTime.now());
            outbox.setLastError(null);
            log.info("Evento de pedido publicado: eventId={}, orderId={}", outbox.getEventId(), outbox.getAggregateId());
        } catch (Exception ex) {
            int attempts = outbox.getAttempts() + 1;
            outbox.setAttempts(attempts);
            outbox.setLastError(limit(ex.getMessage()));
            if (attempts >= MAX_ATTEMPTS) {
                outbox.setStatus(OutboxStatus.FAILED);
                log.error("Evento movido para FAILED após {} tentativas: eventId={}", attempts, outbox.getEventId(), ex);
            } else {
                log.warn("Falha ao publicar evento (tentativa {}/{}): eventId={}", attempts, MAX_ATTEMPTS, outbox.getEventId(), ex);
            }
        }
    }

    private String limit(String message) {
        if (message == null) {
            return "Erro sem mensagem";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
