package com.example.ecommerce.api_gateway.bff;

import com.example.ecommerce.api_gateway.infra.security.KeycloakRoles;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BffUserController {

    /**
     * Usuário da sessão para um cliente de navegador montar a interface. Acessar o CsrfToken força
     * a gravação do cookie XSRF-TOKEN, que o cliente devolve no header X-XSRF-TOKEN nas escritas.
     */
    @GetMapping("/bff/me")
    public BffUserResponse me(@AuthenticationPrincipal OidcUser user, CsrfToken csrfToken) {
        csrfToken.getToken();
        return new BffUserResponse(user.getSubject(), user.getFullName(), user.getEmail(),
                KeycloakRoles.fromClaims(user.getClaims()));
    }
}
