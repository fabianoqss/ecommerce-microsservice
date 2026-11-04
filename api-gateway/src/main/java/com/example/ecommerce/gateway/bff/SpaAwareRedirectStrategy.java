package com.example.ecommerce.gateway.bff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;

import java.io.IOException;

public class SpaAwareRedirectStrategy implements RedirectStrategy {

    static final String REQUESTED_WITH_HEADER = "X-Requested-With";
    static final String XML_HTTP_REQUEST = "XMLHttpRequest";

    private final RedirectStrategy browserRedirect = new DefaultRedirectStrategy();

    @Override
    public void sendRedirect(HttpServletRequest request, HttpServletResponse response, String url) throws IOException {
        if (XML_HTTP_REQUEST.equalsIgnoreCase(request.getHeader(REQUESTED_WITH_HEADER))) {
            response.setStatus(HttpStatus.ACCEPTED.value());
            response.setHeader(HttpHeaders.LOCATION, url);
            return;
        }
        browserRedirect.sendRedirect(request, response, url);
    }
}
