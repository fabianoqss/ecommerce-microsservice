package com.example.ecommerce.notification.infra.log;

import com.example.ecommerce.notification.dto.event.OrderEventDTO;
import com.example.ecommerce.notification.service.port.NotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public void send(OrderEventDTO event, String notification) {
        log.info(
                "{}: eventId={}, version={}, orderId={}, userId={}, status={}, total={}, orderTime={}",
                notification, event.eventId(), event.version(), event.orderId(), event.userId(), event.status(), event.totalValue(), event.orderTime()
        );
    }
}
