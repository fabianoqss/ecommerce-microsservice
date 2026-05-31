package com.example.ecommerce.auth_service.infra.init;

import com.example.ecommerce.auth_service.domain.user.User;
import com.example.ecommerce.auth_service.domain.user.enums.UserRole;
import com.example.ecommerce.auth_service.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.findByEmail("admin@ecommerce.com").isEmpty()) {
            User admin = new User();
            admin.setName("Administrador");
            admin.setEmail("admin@ecommerce.com");
            admin.setPassword(passwordEncoder.encode("Admin@123"));
            admin.setRole(UserRole.ROLE_ADMIN);
            userRepository.save(admin);
            System.out.println(">>> Admin criado: admin@ecommerce.com / Admin@123");
        }
    }
}
