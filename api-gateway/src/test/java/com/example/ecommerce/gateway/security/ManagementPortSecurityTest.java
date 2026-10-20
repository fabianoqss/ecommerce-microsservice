package com.example.ecommerce.gateway.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP real nas duas portas; gerenciamento não depende de sessão nem de Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.health.redis.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
        "spring.data.redis.password=unused",
        "backend.url=http://localhost:1"
})
@ActiveProfiles("test")
class ManagementPortSecurityTest {

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    private final HttpClient client = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/info", "/actuator/prometheus", "/actuator/metrics"})
    void applicationPortNeverServesActuator(String path) throws Exception {
        var response = get(applicationPort, path, false);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/info", "/actuator/prometheus"})
    void managementEndpointsAreAnonymousAndNeverCreateSession(String path) throws Exception {
        assertThat(applicationPort).isNotEqualTo(managementPort);
        for (boolean bearer : new boolean[]{false, true}) {
            var response = get(managementPort, path, bearer);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            if (path.endsWith("prometheus")) {
                assertThat(response.body()).contains("# HELP", "# TYPE");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/metrics", "/actuator/env", "/api/product", "/bff/me", "/oauth2/authorization/keycloak"})
    void managementPortDeniesEveryOtherEndpointWithoutSession(String path) throws Exception {
        var response = get(managementPort, path, false);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    private HttpResponse<String> get(int port, String path, boolean bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                // Cabeçalhos encaminhados não alteram a porta local usada pela autorização.
                .header("X-Forwarded-Port", String.valueOf(managementPort));
        if (bearer) {
            request.header("Authorization", "Bearer invalid-token");
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
