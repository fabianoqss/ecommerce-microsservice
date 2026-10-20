package com.example.ecommerce.eureka_server;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
	"EUREKA_USERNAME=discovery-test",
	"EUREKA_PASSWORD=test-password",
	"management.tracing.export.zipkin.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
@AutoConfigureMetrics
class EurekaServerApplicationTests {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;
	private final List<Filter> initializedFilters = new ArrayList<>();

	@BeforeAll
	void configureHttpFilters() throws Exception {
		DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(context);
		builder.addFilters(context.getBean("springSecurityFilterChain", Filter.class));
		// O Eureka usa filtros Jersey; incluí-los mantém o registro real no teste sem sockets.
		for (String name : new String[] { "eurekaVersionFilterRegistration", "jerseyFilterRegistration" }) {
			Filter filter = context.getBean(name, FilterRegistrationBean.class).getFilter();
			// MockServletContext não registra os mapeamentos consultados pelo Jersey.
			MockServletContext servletContext = new MockServletContext() {
				@Override
				public FilterRegistration getFilterRegistration(String filterName) {
					return (FilterRegistration) Proxy.newProxyInstance(FilterRegistration.class.getClassLoader(),
						new Class<?>[] { FilterRegistration.class }, (proxy, method, args) -> {
							if (method.getName().equals("getUrlPatternMappings")) {
								return List.of("/eureka/*");
							}
							throw new UnsupportedOperationException(method.getName());
						});
				}
			};
			filter.init(new MockFilterConfig(servletContext, name));
			initializedFilters.add(filter);
			builder.addFilter(filter, "/eureka/*");
		}
		mvc = builder.build();
	}

	@AfterAll
	void destroyHttpFilters() {
		initializedFilters.forEach(Filter::destroy);
	}

	@Test
	void registryRequiresAuthentication() throws Exception {
		mvc.perform(get("/eureka/apps")).andExpect(status().isUnauthorized());
	}

	@Test
	void registryAcceptsConfiguredCredentials() throws Exception {
		mvc.perform(authenticated(get("/eureka/apps"))).andExpect(status().isOk());
	}

	@Test
	void registryRejectsIncorrectPassword() throws Exception {
		String credentials = Base64.getEncoder().encodeToString(
			"discovery-test:incorrect-password".getBytes(StandardCharsets.UTF_8));
		mvc.perform(get("/eureka/apps").header("Authorization", "Basic " + credentials))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void prometheusIsPublic() throws Exception {
		mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
	}

	@Test
	void otherActuatorEndpointsRequireAuthentication() throws Exception {
		mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
	}

	@Test
	void heartbeatReachesRegistryWithoutCsrfToken() throws Exception {
		// Uma instância ausente retorna 404; CSRF não deve bloquear o heartbeat com 403.
		mvc.perform(authenticated(put("/eureka/apps/MISSING/missing")))
			.andExpect(status().isNotFound());
	}

	@Test
	void csrfRemainsRequiredOutsideRegistry() throws Exception {
		mvc.perform(authenticated(post("/actuator/metrics"))).andExpect(status().isForbidden());
	}

	private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
		String credentials = Base64.getEncoder().encodeToString(
			"discovery-test:test-password".getBytes(StandardCharsets.UTF_8));
		return request.header("Authorization", "Basic " + credentials);
	}
}
