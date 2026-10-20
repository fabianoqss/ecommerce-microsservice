package com.example.ecommerce.gateway.security;

import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayAuthorizationRulesTest {

    @Test
    void managementMatcherUsesLocalConnectionPort() {
        var matcher = GatewayAuthorizationRules.managementPort(new MockEnvironment());
        var request = new MockHttpServletRequest();
        request.setLocalPort(8080);
        request.addHeader("X-Forwarded-Port", "8090");

        assertThat(matcher.matches(request)).isFalse();
        request.setLocalPort(8090);
        assertThat(matcher.matches(request)).isTrue();
    }

    @Test
    void forwardedWrapperCannotImpersonateManagementConnection() {
        var request = new MockHttpServletRequest();
        request.setLocalPort(8080);
        var forwarded = new HttpServletRequestWrapper(request) {
            @Override
            public int getLocalPort() {
                return 8090;
            }
        };

        assertThat(GatewayAuthorizationRules.managementPort(new MockEnvironment()).matches(forwarded)).isFalse();
    }

    @Test
    void actualRandomPortOverridesConfiguredPort() {
        var environment = new MockEnvironment().withProperty("management.server.port", "0")
                .withProperty("local.management.port", "49152");
        var request = new MockHttpServletRequest();
        request.setLocalPort(49152);

        assertThat(GatewayAuthorizationRules.managementPort(environment).matches(request)).isTrue();
    }

    @Test
    void samePortConfigurationDoesNotMakeActuatorPublic() {
        var environment = new MockEnvironment().withProperty("management.server.port", "8080")
                .withProperty("server.port", "8080");
        var request = new MockHttpServletRequest();
        request.setLocalPort(8080);

        assertThat(GatewayAuthorizationRules.managementPort(environment).matches(request)).isFalse();
    }
}
