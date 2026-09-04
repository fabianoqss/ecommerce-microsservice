package com.example.ecommerce.auth_service.dto.response;

public record LoginResponseDTO(String token, String name, String email, String role) {
}
