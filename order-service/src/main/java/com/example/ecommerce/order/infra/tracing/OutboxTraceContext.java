package com.example.ecommerce.order.infra.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OutboxTraceContext {

    private final Tracer tracer;
    private final Propagator propagator;

    public String capture() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        propagator.inject(span.context(), headers, Map::put);
        return headers.get("traceparent");
    }

    public TraceScope restore(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return new TraceScope(Span.NOOP, () -> { });
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", traceparent);
        Span span = propagator.extract(headers, Map::get).name("outbox.dispatch").start();
        return new TraceScope(span, tracer.withSpan(span));
    }

    public record TraceScope(Span span, Tracer.SpanInScope scope) implements AutoCloseable {

        public void error(Throwable error) {
            span.error(error);
        }

        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}
