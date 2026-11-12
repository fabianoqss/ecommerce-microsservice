package com.example.ecommerce.notification.infra.mail;

import com.example.ecommerce.notification.dto.event.OrderEventDTO;
import com.example.ecommerce.notification.service.port.NotificationSender;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "ecommerce.notification", name = "channel", havingValue = "email")
public class SmtpNotificationSender implements NotificationSender {

    private static final String CANCELLED_STATUS = "CANCELLED";
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter ORDER_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", PT_BR);
    private static final String EMAILS_METRIC = "ecommerce.notification.emails";

    private final JavaMailSender javaMailSender;
    private final MeterRegistry meterRegistry;
    private final String from;

    public SmtpNotificationSender(JavaMailSender javaMailSender,
                                  MeterRegistry meterRegistry,
                                  @Value("${ecommerce.notification.mail.from}") String from) {
        this.javaMailSender = javaMailSender;
        this.meterRegistry = meterRegistry;
        this.from = from;
    }

    @Override
    public void send(OrderEventDTO event, String notification) {
        String eventType = isCancellation(event) ? "cancelled" : "created";

        if (event.customerEmail() == null || event.customerEmail().isBlank()) {
            log.warn("E-mail não enviado: pedido sem e-mail do cliente: orderId={}, event_type={}", event.orderId(), eventType);
            count("skipped", eventType);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(event.customerEmail());
        message.setSubject(subjectFor(event));
        message.setText(bodyFor(event));

        try {
            javaMailSender.send(message);
        } catch (MailException ex) {
            count("failed", eventType);
            throw ex;
        }

        count("sent", eventType);
        log.info("E-mail de pedido enviado: orderId={}, event_type={}", event.orderId(), eventType);
    }

    private boolean isCancellation(OrderEventDTO event) {
        return CANCELLED_STATUS.equals(event.status());
    }

    private String subjectFor(OrderEventDTO event) {
        return isCancellation(event)
                ? "Seu pedido foi cancelado"
                : "Recebemos o seu pedido";
    }

    private String bodyFor(OrderEventDTO event) {
        String headline = isCancellation(event)
                ? "O seu pedido foi cancelado. Se o pagamento já tiver sido feito, o valor será estornado."
                : "Obrigado pela compra! O seu pedido foi confirmado.";

        return """
                Olá!

                %s

                Pedido: %s
                Total: %s
                Data: %s

                Equipe da Loja
                """.formatted(
                headline,
                event.orderId(),
                event.totalValue() == null ? "-" : NumberFormat.getCurrencyInstance(PT_BR).format(event.totalValue()),
                event.orderTime() == null ? "-" : ORDER_TIME_FORMAT.format(event.orderTime()));
    }

    private void count(String result, String eventType) {
        meterRegistry.counter(EMAILS_METRIC, "result", result, "event_type", eventType).increment();
    }
}
