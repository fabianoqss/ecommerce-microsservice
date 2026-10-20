package com.example.ecommerce.eureka_server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			// Clientes Eureka não enviam tokens CSRF nas operações de registro e heartbeat.
			.csrf(csrf -> csrf.ignoringRequestMatchers("/eureka/**"))
			.authorizeHttpRequests(authorize -> authorize
				// A porta 8761 deve permanecer interna em produção, inclusive as métricas.
				.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
				.anyRequest().authenticated())
			.httpBasic(Customizer.withDefaults())
			.build();
	}

	@Bean
	UserDetailsService eurekaUsers(Environment environment) {
		String username = environment.getRequiredProperty("EUREKA_USERNAME");
		String password = environment.getRequiredProperty("EUREKA_PASSWORD");
		Assert.hasText(username, "EUREKA_USERNAME deve ser informado");
		Assert.hasText(password, "EUREKA_PASSWORD deve ser informado");
		return new InMemoryUserDetailsManager(User.withUsername(username)
			.password(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password))
			.roles("EUREKA")
			.build());
	}
}
