package com.example.ecommerce.notification_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OpenAPI notificationServiceAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Notification-Service API")
                        .description("Notification-Service Reference Project")
                        .version("v0.0.1")
                        .license(new License()
                                .name("https://github.com/fabianoqss/ecommerce-microsservice/tree/main/notification-service")));
    }
}
