package com.example.ecommerce.order.infra.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTraceContextTest {

    private static final String TRACE_ID = "463ac35c9f6413ad48485a3953bb6124";
    private static final String PARENT_ID = "a2fb4a1d1a96d312";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BraveAutoConfiguration.class))
            .withUserConfiguration(OutboxTraceContext.class);

    @Test
    void capturesActiveSpanAsW3cTraceparent() {
        contextRunner.run(context -> {
            Tracer tracer = context.getBean(Tracer.class);
            var propagation = context.getBean(OutboxTraceContext.class);
            Span parent = tracer.nextSpan().name("checkout").start();
            try (var scope = tracer.withSpan(parent)) {
                String traceparent = propagation.capture();

                assertThat(traceparent).matches("00-" + parent.context().traceId()
                        + "-" + parent.context().spanId() + "-0[01]");
            } finally {
                parent.end();
            }
        });
    }

    @Test
    void capturesNoTraceparentWithoutAnActiveSpan() {
        contextRunner.run(context -> {
            String traceparent = context.getBean(OutboxTraceContext.class).capture();

            assertThat(traceparent).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"00", "01"})
    void restoresStoredParentAndSamplingInsteadOfAmbientTrace(String flags) {
        contextRunner.run(context -> {
            Tracer tracer = context.getBean(Tracer.class);
            var propagation = context.getBean(OutboxTraceContext.class);
            Span ambient = tracer.nextSpan().name("poller").start();
            try (var ambientScope = tracer.withSpan(ambient)) {
                try (var restored = propagation.restore("00-" + TRACE_ID + "-" + PARENT_ID + "-" + flags)) {
                    Span dispatch = tracer.currentSpan();
                    assertThat(dispatch).isNotNull();
                    assertThat(dispatch.context().traceId()).isEqualTo(TRACE_ID);
                    assertThat(dispatch.context().parentId()).isEqualTo(PARENT_ID);
                    assertThat(dispatch.context().sampled()).isEqualTo("01".equals(flags));
                    Span child = tracer.nextSpan().name("publish").start();
                    assertThat(child.context().traceId()).isEqualTo(TRACE_ID);
                    assertThat(child.context().parentId()).isEqualTo(dispatch.context().spanId());
                    child.end();
                }
                assertThat(tracer.currentSpan().context()).isEqualTo(ambient.context());
            } finally {
                ambient.end();
            }
            assertThat(tracer.currentSpan()).isNull();
        });
    }

    @Test
    void preservesTraceparentAcrossCaptureAndRestore() {
        contextRunner.run(context -> {
            Tracer tracer = context.getBean(Tracer.class);
            Propagator propagator = context.getBean(Propagator.class);
            var propagation = context.getBean(OutboxTraceContext.class);
            Map<String, String> incoming = Map.of("traceparent", "00-" + TRACE_ID + "-" + PARENT_ID + "-01");
            Span request = propagator.extract(incoming, Map::get).start();
            String stored;
            try (var scope = tracer.withSpan(request)) {
                stored = propagation.capture();
            } finally {
                request.end();
            }

            try (var restored = propagation.restore(stored)) {
                Map<String, String> outgoing = new HashMap<>();
                propagator.inject(tracer.currentSpan().context(), outgoing, Map::put);
                assertThat(outgoing.get("traceparent")).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01");
            }
        });
    }

    @Test
    void missingContextDoesNotCreateASpan() {
        contextRunner.run(context -> {
            Tracer tracer = context.getBean(Tracer.class);
            var propagation = context.getBean(OutboxTraceContext.class);

            try (var scope = propagation.restore(null)) {
                assertThat(tracer.currentSpan()).isNull();
            }
            assertThat(tracer.currentSpan()).isNull();
        });
    }

    @Test
    void scopeIsClearedWhenDispatchFails() {
        contextRunner.run(context -> {
            Tracer tracer = context.getBean(Tracer.class);
            var propagation = context.getBean(OutboxTraceContext.class);

            assertThatThrownBy(() -> {
                try (var scope = propagation.restore("00-" + TRACE_ID + "-" + PARENT_ID + "-01")) {
                    throw new IllegalStateException("broker down");
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(tracer.currentSpan()).isNull();
        });
    }
}
