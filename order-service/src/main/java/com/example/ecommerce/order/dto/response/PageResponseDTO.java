package com.example.ecommerce.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(name = "PageResponseDTO", description = "Page of results")
public record PageResponseDTO<T>(
        @Schema(description = "Page items")
        List<T> content,
        @Schema(description = "Page number (starts at 0)", example = "0")
        int page,
        @Schema(description = "Page size", example = "20")
        int size,
        @Schema(description = "Total items across all pages", example = "42")
        long totalElements,
        @Schema(description = "Total pages", example = "3")
        int totalPages
) {
    public static <T> PageResponseDTO<T> from(Page<T> page) {
        return new PageResponseDTO<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
