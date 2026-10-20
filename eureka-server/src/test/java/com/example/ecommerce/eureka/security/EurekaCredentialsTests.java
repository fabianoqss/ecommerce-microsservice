package com.example.ecommerce.eureka.security;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

class EurekaCredentialsTests {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withUserConfiguration(SecurityConfiguration.class, WebSecurity.class)
		.withInitializer(context -> {
			// Credenciais da máquina não devem mascarar os cenários de configuração ausente.
			context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
			context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		});

	@Test
	void startsWithExplicitCredentials() {
		runner.withPropertyValues("EUREKA_USERNAME=discovery-test", "EUREKA_PASSWORD=test-password")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(UserDetailsService.class).loadUserByUsername("discovery-test").getUsername())
					.isEqualTo("discovery-test");
			});
	}

	@ParameterizedTest
	@MethodSource("invalidCredentials")
	void refusesToStartWithoutNonblankCredentials(String username, String password, String missingProperty) {
		WebApplicationContextRunner configured = runner;
		if (username != null) {
			configured = configured.withPropertyValues("EUREKA_USERNAME=" + username);
		}
		if (password != null) {
			configured = configured.withPropertyValues("EUREKA_PASSWORD=" + password);
		}
		configured.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining(missingProperty);
		});
	}

	static Stream<Arguments> invalidCredentials() {
		return Stream.of(
			Arguments.of(null, "test-password", "EUREKA_USERNAME"),
			Arguments.of("", "test-password", "EUREKA_USERNAME"),
			Arguments.of("   ", "test-password", "EUREKA_USERNAME"),
			Arguments.of("discovery-test", null, "EUREKA_PASSWORD"),
			Arguments.of("discovery-test", "", "EUREKA_PASSWORD"),
			Arguments.of("discovery-test", "   ", "EUREKA_PASSWORD")
		);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebSecurity
	static class WebSecurity {
	}
}
