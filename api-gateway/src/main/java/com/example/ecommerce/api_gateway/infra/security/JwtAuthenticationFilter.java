package com.example.ecommerce.api_gateway.infra.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String secret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method) || isPublicRoute(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        if (token == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Token não fornecido");
            return;
        }

        String role = validateAndExtractRole(token);
        if (role == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Token inválido ou expirado");
            return;
        }

        if (requiresAdmin(path, method) && !"ROLE_ADMIN".equals(role)) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "Acesso negado: requer ROLE_ADMIN");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicRoute(String path, String method) {
        if (path.startsWith("/auth/")) return true;
        if (path.startsWith("/actuator")) return true;
        if ("GET".equals(method) && path.startsWith("/api/product")) return true;
        if ("GET".equals(method) && path.startsWith("/api/inventory")) return true;
        return false;
    }

    private boolean requiresAdmin(String path, String method) {
        if (path.startsWith("/api/product") && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) return true;
        if (path.startsWith("/api/inventory") && ("POST".equals(method) || "PUT".equals(method))) return true;
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        return header.substring(7);
    }

    private String validateAndExtractRole(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            return JWT.require(algorithm)
                    .withIssuer("auth-service")
                    .build()
                    .verify(token)
                    .getClaim("role")
                    .asString();
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
