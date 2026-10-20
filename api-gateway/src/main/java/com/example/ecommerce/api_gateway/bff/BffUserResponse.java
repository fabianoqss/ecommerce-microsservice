package com.example.ecommerce.api_gateway.bff;

import java.util.Set;

public record BffUserResponse(String sub, String name, String email, Set<String> roles) {
}
