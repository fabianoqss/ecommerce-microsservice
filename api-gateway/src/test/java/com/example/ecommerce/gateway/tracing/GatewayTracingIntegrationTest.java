package com.example.ecommerce.gateway.tracing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.health.redis.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "spring.test.tracing.export=true",
        "spring.autoconfigure.exclude=org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
        "spring.data.redis.password=unused",
        "KC_BFF_CLIENT_SECRET=unused"
})
class GatewayTracingIntegrationTest {

    private static final String TRACE_ID = "463ac35c9f6413ad48485a3953bb6124";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-a2fb4a1d1a96d312-01";
    private static final BlockingQueue<Map<String, List<String>>> REQUESTS = new LinkedBlockingQueue<>();
    private static final HttpServer BACKEND = startBackend();

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("PRODUCT_SERVICE_URL", () -> "http://localhost:" + BACKEND.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @Test
    void proxyContinuesIncomingTraceWithObservedClientSpan() throws Exception {
        REQUESTS.clear();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/product"))
                .header("traceparent", TRACEPARENT)
                .header("tracestate", "vendor=value")
                .GET().build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, List<String>> headers = REQUESTS.poll(5, TimeUnit.SECONDS);
        assertThat(headers).isNotNull();
        assertThat(header(headers, "traceparent"))
                .matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01")
                .isNotEqualTo(TRACEPARENT);
        assertThat(header(headers, "tracestate")).isNull();
    }

    @Test
    void proxyCreatesTraceHeadersForRequestsWithoutContext() throws Exception {
        REQUESTS.clear();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/product"))
                .GET().build();

        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        Map<String, List<String>> headers = REQUESTS.poll(5, TimeUnit.SECONDS);
        assertThat(headers).isNotNull();
        assertThat(header(headers, "traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse(null);
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/product", exchange -> {
                REQUESTS.add(Map.copyOf(exchange.getRequestHeaders()));
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
