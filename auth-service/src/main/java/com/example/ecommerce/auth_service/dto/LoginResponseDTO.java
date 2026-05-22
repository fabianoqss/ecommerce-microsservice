package com.example.ecommerce.auth_service.dto;

public record LoginResponseDTO(String token, String name, String email, String role) {
}
