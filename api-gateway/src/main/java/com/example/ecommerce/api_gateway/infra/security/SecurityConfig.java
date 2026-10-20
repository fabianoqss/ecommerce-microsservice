package com.example.ecommerce.api_gateway.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final RequestMatcher BEARER_REQUEST = (HttpServletRequest request) -> {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    };

    @Bean
    @Order(0)
    public SecurityFilterChain managementChain(HttpSecurity http, Environment environment) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var managementPort = GatewayAuthorizationRules.managementPort(environment);
        var actuator = new OrRequestMatcher(paths.matcher("/actuator"), paths.matcher("/actuator/**"));
        var publicEndpoints = new OrRequestMatcher(paths.matcher("/actuator/health"),
                paths.matcher("/actuator/health/**"), paths.matcher("/actuator/info"),
                paths.matcher("/actuator/prometheus"));

        return http
                // Isola também caminhos da aplicação que chegarem pela porta de gerenciamento.
                .securityMatcher(new OrRequestMatcher(managementPort, actuator))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(new AndRequestMatcher(managementPort, publicEndpoints)).permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
                .build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain bearerChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(BEARER_REQUEST)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> GatewayAuthorizationRules.apply(authorize)
                        // /bff/** é só para o navegador (sessão).
                        .requestMatchers("/bff/**").denyAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter())))
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain sessionChain(HttpSecurity http,
                                            ClientRegistrationRepository clientRegistrations,
                                            BffProperties properties) throws Exception {
        var authorizationRequestResolver =
                new DefaultOAuth2AuthorizationRequestResolver(clientRegistrations, "/oauth2/authorization");
        // O client ecommerce-bff é confidencial, mas o realm exige PKCE S256 em todos os clients de usuário.
        authorizationRequestResolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        var logoutSuccessHandler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        if (StringUtils.hasText(properties.postLogoutRedirectUri())) {
            // Precisa estar em "Valid post logout redirect URIs" do client no IdP.
            logoutSuccessHandler.setPostLogoutRedirectUri(properties.postLogoutRedirectUri());
        }

        var unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        var paths = PathPatternRequestMatcher.withDefaults();

        return http
                .authorizeHttpRequests(authorize -> GatewayAuthorizationRules.apply(authorize)
                        .requestMatchers("/bff/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequestResolver))
                        .defaultSuccessUrl(properties.postLoginRedirectUri(), true))
                .logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler))
                // Cookie XSRF-TOKEN legível pelo JS + header X-XSRF-TOKEN (padrão do Spring Security para SPA).
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(unauthorized, paths.matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(unauthorized, paths.matcher("/bff/**")))
                .build();
    }

    @Bean
    public FilterRegistrationBean<SessionTokenRelayFilter> sessionTokenRelayFilter(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        var registration = new FilterRegistrationBean<>(new SessionTokenRelayFilter(authorizedClientManager));
        // Depois do springSecurityFilterChain (ordem -100), para enxergar a autenticação da sessão.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }
}
