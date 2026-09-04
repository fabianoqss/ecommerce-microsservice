package com.example.ecommerce.auth_service.service;

import com.example.ecommerce.auth_service.domain.user.User;
import com.example.ecommerce.auth_service.domain.user.enums.UserRole;
import com.example.ecommerce.auth_service.domain.user.User;
import com.example.ecommerce.auth_service.domain.user.enums.UserRole;
import com.example.ecommerce.auth_service.dto.request.LoginRequestDTO;
import com.example.ecommerce.auth_service.dto.response.LoginResponseDTO;
import com.example.ecommerce.auth_service.dto.request.RegisterRequestDTO;
import com.example.ecommerce.auth_service.service.exception.EmailAlreadyExistsException;
import com.example.ecommerce.auth_service.service.exception.InvalidCredentialsException;
import com.example.ecommerce.auth_service.infra.security.TokenService;
import com.example.ecommerce.auth_service.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    public LoginResponseDTO login(LoginRequestDTO dto) {
        User user = userRepository.findByEmail(dto.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(dto.password(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        String token = tokenService.generateToken(user);
        return new LoginResponseDTO(token, user.getName(), user.getEmail(), user.getRole().name());
    }

    public void register(RegisterRequestDTO dto) {
        if (userRepository.findByEmail(dto.email()).isPresent()) {
            throw new EmailAlreadyExistsException(dto.email());
        }

        User user = new User();
        user.setName(dto.name());
        user.setEmail(dto.email());
        user.setPassword(passwordEncoder.encode(dto.password()));
        user.setRole(UserRole.ROLE_USER);

        userRepository.save(user);
    }
}
