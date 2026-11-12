package com.example.ecommerce.notification.infra.mail;

import com.example.ecommerce.notification.dto.event.OrderEventDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SmtpNotificationSenderTest {

    private static final String FROM = "noreply@loja.example.com";
    private static final String CUSTOMER_EMAIL = "customer@example.com";
    private static final String ORDER_ID = "019a0b2c-3d4e-7f50-8a1b-2c3d4e5f6a7b";

    private JavaMailSender javaMailSender;
    private SimpleMeterRegistry meterRegistry;
    private SmtpNotificationSender sender;

    @BeforeEach
    void setUp() {
        javaMailSender = mock(JavaMailSender.class);
        meterRegistry = new SimpleMeterRegistry();
        sender = new SmtpNotificationSender(javaMailSender, meterRegistry, FROM);
    }

    @Test
    void sendsConfirmationEmailToCustomerFromApprovedSender() {
        sender.send(event(CUSTOMER_EMAIL, "CONFIRMED"), "Notificação de pedido criada");

        SimpleMailMessage message = sentMessage();
        assertThat(message.getFrom()).isEqualTo(FROM);
        assertThat(message.getTo()).containsExactly(CUSTOMER_EMAIL);
        assertThat(message.getSubject()).isEqualTo("Recebemos o seu pedido");
        assertThat(message.getText())
                .contains(ORDER_ID)
                .contains("R$")
                .contains("250,90")
                .contains("12/11/2026 10:30");
        assertThat(count("sent", "created")).isEqualTo(1.0);
    }

    @Test
    void sendsCancellationEmailWhenOrderIsCancelled() {
        sender.send(event(CUSTOMER_EMAIL, "CANCELLED"), "Notificação de pedido cancelado");

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Seu pedido foi cancelado");
        assertThat(message.getText()).contains("cancelado");
        assertThat(count("sent", "cancelled")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void skipsSendingWhenCustomerEmailIsMissing(String customerEmail) {
        sender.send(event(customerEmail, "CONFIRMED"), "Notificação de pedido criada");

        verifyNoInteractions(javaMailSender);
        assertThat(count("skipped", "created")).isEqualTo(1.0);
    }

    @Test
    void rethrowsMailFailureSoTheEventIsRedelivered() {
        MailSendException failure = new MailSendException("SMTP unavailable");
        doThrow(failure).when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send(event(CUSTOMER_EMAIL, "CONFIRMED"), "Notificação de pedido criada"))
                .isSameAs(failure);
        assertThat(count("failed", "created")).isEqualTo(1.0);
        assertThat(meterRegistry.find("ecommerce.notification.emails").tag("result", "sent").counter()).isNull();
    }

    private SimpleMailMessage sentMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }

    private double count(String result, String eventType) {
        return meterRegistry.get("ecommerce.notification.emails")
                .tag("result", result)
                .tag("event_type", eventType)
                .counter()
                .count();
    }

    private static OrderEventDTO event(String customerEmail, String status) {
        return new OrderEventDTO(UUID.randomUUID(), 2, ORDER_ID, "user-sub", customerEmail, status,
                new BigDecimal("250.90"), LocalDateTime.of(2026, 11, 12, 10, 30));
    }
}
