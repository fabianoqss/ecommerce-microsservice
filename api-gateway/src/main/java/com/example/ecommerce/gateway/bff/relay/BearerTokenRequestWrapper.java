package com.example.ecommerce.gateway.bff.relay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.http.HttpHeaders;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

class BearerTokenRequestWrapper extends HttpServletRequestWrapper {

    private final String authorization;

    BearerTokenRequestWrapper(HttpServletRequest request, String accessToken) {
        super(request);
        this.authorization = "Bearer " + accessToken;
    }

    @Override
    public String getHeader(String name) {
        return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? authorization : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                ? Collections.enumeration(Set.of(authorization))
                : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
        names.add(HttpHeaders.AUTHORIZATION);
        return Collections.enumeration(names);
    }
}
