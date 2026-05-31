package com.example.ecommerce.auth_service.controller;

import com.example.ecommerce.auth_service.dto.LoginRequestDTO;
import com.example.ecommerce.auth_service.dto.LoginResponseDTO;
import com.example.ecommerce.auth_service.dto.RegisterRequestDTO;

import com.example.ecommerce.auth_service.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO dto) {
        return ResponseEntity.ok(authService.login(dto));
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequestDTO dto) {
        authService.register(dto);
        return ResponseEntity.status(201).build();
    }
}
