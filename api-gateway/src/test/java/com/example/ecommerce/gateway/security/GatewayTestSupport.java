package com.example.ecommerce.gateway.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** Gateway completo com Redis real (sessões) e WireMock no lugar dos microsserviços. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class GatewayTestSupport {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-alpine3.21")
            .withCommand("redis-server", "--requirepass", "redis-test")
            .withExposedPorts(6379);

    static final WireMockServer BACKEND = new WireMockServer(wireMockConfig().dynamicPort());

    // Compartilhados por todas as classes de teste do gateway (mesmo contexto Spring em cache);
    // o Ryuk do Testcontainers encerra o Redis e a JVM de teste encerra o WireMock.
    static {
        REDIS.start();
        BACKEND.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "redis-test");
        registry.add("backend.url", BACKEND::baseUrl);
    }

    @Autowired
    protected MockMvc mvc;

    @BeforeEach
    void resetBackend() {
        BACKEND.resetAll();
        BACKEND.stubFor(any(urlMatching("/api/.*")).willReturn(okJson("{}")));
    }
}
