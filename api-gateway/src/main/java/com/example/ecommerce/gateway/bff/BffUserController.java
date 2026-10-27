package com.example.ecommerce.gateway.bff;

import com.example.ecommerce.gateway.security.jwt.KeycloakRoles;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BffUserController {
    @GetMapping("/bff/me")
    public BffUserResponse me(@AuthenticationPrincipal OidcUser user, CsrfToken csrfToken) {
        csrfToken.getToken();
        return new BffUserResponse(user.getSubject(), user.getFullName(), user.getEmail(),
                KeycloakRoles.fromClaims(user.getClaims()));
    }
}
