package com.example.ecommerce.order.infra.client;

import com.example.ecommerce.order.dto.request.InventoryItemDTO;
import com.example.ecommerce.order.dto.request.InventoryReserveRequestDTO;
import feign.Client;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeignClientAuthenticationTest {

    private static final InventoryReserveRequestDTO ITEMS = new InventoryReserveRequestDTO(
            List.of(new InventoryItemDTO("p1", 1)));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(Clients.class)
            .withPropertyValues("KC_ORDER_SERVICE_CLIENT_SECRET=test-secret", "DB_PASSWORD=unused",
                    "spring.cloud.openfeign.client.config.inventory-service.url=http://inventory.test",
                    "spring.cloud.openfeign.client.config.product-service.url=http://product.test");

    @Test
    void productClientNeverRequestsOrSendsServiceToken() {
        contextRunner.run(context -> {
            var product = context.getBean(ProductClient.class).getProductById("p1");

            assertThat(product.name()).isEqualTo("Teclado");
            assertThat(context.getBean(RecordingClient.class).requests).singleElement().satisfies(request ->
                    assertThat(request.headers()).doesNotContainKey("Authorization"));
            verifyNoInteractions(context.getBean(OAuth2AuthorizedClientManager.class));
        });
    }

    @Test
    void inventoryReserveAndReleaseSendServiceToken() {
        contextRunner.run(context -> {
            var manager = context.getBean(OAuth2AuthorizedClientManager.class);
            when(manager.authorize(any())).thenAnswer(invocation -> {
                var request = invocation.getArgument(0, org.springframework.security.oauth2.client.OAuth2AuthorizeRequest.class);
                assertThat(request.getClientRegistrationId()).isEqualTo("order-service");
                assertThat(request.getPrincipal().getName()).isEqualTo("order-service");
                return authorizedClient();
            });
            var inventory = context.getBean(InventoryClient.class);

            inventory.reserve(ITEMS);
            inventory.release(ITEMS);

            assertThat(context.getBean(RecordingClient.class).requests).hasSize(2).allSatisfy(request ->
                    assertThat(request.headers().get("Authorization")).containsExactly("Bearer svc-token"));
        });
    }

    @Test
    void missingAuthorizedClientStopsRequest() {
        contextRunner.run(context -> {
            var inventory = context.getBean(InventoryClient.class);

            assertThatThrownBy(() -> inventory.reserve(ITEMS))
                    .isInstanceOf(OAuth2AuthorizationException.class)
                    .hasMessageContaining("token");
            assertThat(context.getBean(RecordingClient.class).requests).isEmpty();
        });
    }

    @Test
    void authorizationFailureStopsRequest() {
        contextRunner.run(context -> {
            var failure = new OAuth2AuthorizationException(new OAuth2Error("temporarily_unavailable"));
            when(context.getBean(OAuth2AuthorizedClientManager.class).authorize(any())).thenThrow(failure);
            var inventory = context.getBean(InventoryClient.class);

            assertThatThrownBy(() -> inventory.reserve(ITEMS)).isSameAs(failure);
            assertThat(context.getBean(RecordingClient.class).requests).isEmpty();
        });
    }

    private static OAuth2AuthorizedClient authorizedClient() {
        var registration = ClientRegistration.withRegistrationId("order-service")
                .clientId("order-service").clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://identity.test/token").build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "svc-token",
                Instant.now(), Instant.now().plusSeconds(300));
        return new OAuth2AuthorizedClient(registration, "order-service", token);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableFeignClients(clients = {InventoryClient.class, ProductClient.class})
    static class Clients {
        @Bean
        OAuth2AuthorizedClientManager authorizedClientManager() {
            return mock(OAuth2AuthorizedClientManager.class);
        }

        @Bean
        RecordingClient recordingClient() {
            return new RecordingClient();
        }
    }

    static class RecordingClient implements Client {
        final List<Request> requests = new ArrayList<>();

        @Override
        public Response execute(Request request, Request.Options options) {
            requests.add(request);
            if (request.httpMethod() == Request.HttpMethod.GET) {
                return Response.builder().request(request).status(200)
                        .headers(Map.of("Content-Type", List.of("application/json")))
                        .body("{\"id\":\"p1\",\"name\":\"Teclado\",\"price\":10.00}", StandardCharsets.UTF_8).build();
            }
            return Response.builder().request(request).status(204).headers(Map.of()).build();
        }
    }
}
