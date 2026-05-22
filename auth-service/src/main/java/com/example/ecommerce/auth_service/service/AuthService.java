package com.example.ecommerce.auth_service.service;

import com.example.ecommerce.auth_service.domain.user.User;
import com.example.ecommerce.auth_service.domain.user.enums.UserRole;
import com.example.ecommerce.auth_service.dto.LoginRequestDTO;
import com.example.ecommerce.auth_service.dto.LoginResponseDTO;
import com.example.ecommerce.auth_service.dto.RegisterRequestDTO;
import com.example.ecommerce.auth_service.infra.security.TokenService;
import com.example.ecommerce.auth_service.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    public ResponseEntity<LoginResponseDTO> login(LoginRequestDTO dto) {
        User user = userRepository.findByEmail(dto.email())
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        if (!passwordEncoder.matches(dto.password(), user.getPassword())) {
            return ResponseEntity.status(401).build();
        }

        String token = tokenService.generateToken(user);
        return ResponseEntity.ok(new LoginResponseDTO(token, user.getName(), user.getEmail(), user.getRole().name()));
    }

    public ResponseEntity<LoginResponseDTO> register(RegisterRequestDTO dto) {
        if (userRepository.findByEmail(dto.email()).isPresent()) {
            return ResponseEntity.status(409).build();
        }

        User user = new User();
        user.setName(dto.name());
        user.setEmail(dto.email());
        user.setPassword(passwordEncoder.encode(dto.password()));
        user.setRole(UserRole.ROLE_USER);

        userRepository.save(user);

        String token = tokenService.generateToken(user);
        return ResponseEntity.ok(new LoginResponseDTO(token, user.getName(), user.getEmail(), user.getRole().name()));
    }
}
