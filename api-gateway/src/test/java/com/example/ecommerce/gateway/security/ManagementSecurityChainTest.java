package com.example.ecommerce.gateway.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "management.server.port=0",
        "local.management.port=8090",
        "spring.autoconfigure.exclude=org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
        "spring.data.redis.password=unused",
        "backend.url=http://localhost:1"
})
@ActiveProfiles("test")
class ManagementSecurityChainTest {

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/info", "/actuator/prometheus"})
    void onlyManagementPortAllowsAnonymousEndpointsWithoutSession(String path) throws Exception {
        assertSecurity(8090, path, false, 204);
        assertSecurity(8090, path, true, 204);
        assertSecurity(8080, path, false, 403);
        assertSecurity(8080, path, true, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/metrics", "/actuator/env", "/api/product", "/bff/me", "/oauth2/authorization/keycloak"})
    void managementPortDeniesOtherPathsWithoutCreatingSession(String path) throws Exception {
        assertSecurity(8090, path, false, 403);
        assertSecurity(8090, path, true, 403);
    }

    private void assertSecurity(int port, String path, boolean bearer, int expectedStatus) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setLocalPort(port);
        request.addHeader("X-Forwarded-Port", "8090");
        request.addHeader("X-Forwarded-Host", "localhost:8090");
        if (bearer) {
            request.addHeader("Authorization", "Bearer invalid-token");
        }
        var response = new MockHttpServletResponse();

        new ForwardedHeaderFilter().doFilter(request, response, (forwardedRequest, forwardedResponse) ->
                springSecurityFilterChain.doFilter(forwardedRequest, forwardedResponse,
                        (req, res) -> response.setStatus(204)));

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }
}
