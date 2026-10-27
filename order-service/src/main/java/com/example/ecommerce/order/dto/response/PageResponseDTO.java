package com.example.ecommerce.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(name = "PageResponseDTO", description = "Página de resultados")
public record PageResponseDTO<T>(
        @Schema(description = "Itens da página")
        List<T> content,
        @Schema(description = "Número da página (começa em 0)", example = "0")
        int page,
        @Schema(description = "Tamanho da página", example = "20")
        int size,
        @Schema(description = "Total de itens em todas as páginas", example = "42")
        long totalElements,
        @Schema(description = "Total de páginas", example = "3")
        int totalPages
) {
    public static <T> PageResponseDTO<T> from(Page<T> page) {
        return new PageResponseDTO<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
