package com.example.ecommerce.auth_service.infra.init;

import com.example.ecommerce.auth_service.domain.user.User;
import com.example.ecommerce.auth_service.domain.user.enums.UserRole;
import com.example.ecommerce.auth_service.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.warn("ADMIN_EMAIL/ADMIN_PASSWORD não configurados — nenhum admin será criado automaticamente.");
            return;
        }

        userRepository.findByEmail(adminEmail).ifPresentOrElse(
                existing -> log.info("Admin '{}' já existe, nada a fazer.", adminEmail),
                () -> {
                    User admin = new User();
                    admin.setName("Administrador");
                    admin.setEmail(adminEmail);
                    admin.setPassword(passwordEncoder.encode(adminPassword));
                    admin.setRole(UserRole.ROLE_ADMIN);
                    userRepository.save(admin);
                    log.info("Admin criado: {}", adminEmail);
                }
        );
    }
}
