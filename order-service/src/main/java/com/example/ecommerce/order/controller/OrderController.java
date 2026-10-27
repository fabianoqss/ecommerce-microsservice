package com.example.ecommerce.order.controller;

import com.example.ecommerce.order.dto.request.CancelOrderRequestDTO;
import com.example.ecommerce.order.dto.request.OrderRequestDTO;
import com.example.ecommerce.order.dto.response.CreateOrderResult;
import com.example.ecommerce.order.dto.response.OrderResponseDTO;
import com.example.ecommerce.order.dto.response.PageResponseDTO;
import com.example.ecommerce.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/order")
@Tag(name = "Orders", description = "Controller for Order")
public class OrderController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private OrderService orderService;

    @Operation(
            description = "Get orders paginated, newest first (usuário comum vê só os próprios; admin vê todos)",
            summary = "List orders",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Bad Request", responseCode = "400")
            }
    )
    @GetMapping
    public ResponseEntity<PageResponseDTO<OrderResponseDTO>> findAll(
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size,
            Authentication authentication) {
        return ResponseEntity.ok(orderService.findAll(page, size, authentication));
    }

    @Operation(
            description = "Get order by id (apenas o dono do pedido ou um admin; pedido de outro usuário responde 404)",
            summary = "Get order by id",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Not Found", responseCode = "404")
            }
    )
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponseDTO> findById(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(orderService.findById(id, authentication));
    }

    @Operation(
            description = "Cancel an order (dono ou admin) e enfileira a devolução assíncrona do estoque. "
                    + "Repetir em um pedido já cancelado responde 200 sem efeito.",
            summary = "Cancel an order",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Bad Request", responseCode = "400"),
                    @ApiResponse(description = "Not Found", responseCode = "404"),
                    @ApiResponse(description = "Conflict - status não permite cancelar ou alteração concorrente", responseCode = "409")
            }
    )
    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponseDTO> cancel(@PathVariable UUID id,
                                                   @Valid @RequestBody(required = false) CancelOrderRequestDTO request,
                                                   Authentication authentication) {
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(orderService.cancel(id, reason, authentication));
    }

    @Operation(
            description = "Create a new order for the authenticated user. Repetir a requisição com o mesmo "
                    + "Idempotency-Key devolve o pedido já criado, sem nova reserva de estoque.",
            summary = "Create a new order",
            responses = {
                    @ApiResponse(description = "Created (ou replay de um pedido já criado com a mesma chave)", responseCode = "201"),
                    @ApiResponse(description = "Bad Request - payload inválido ou Idempotency-Key ausente/inválido", responseCode = "400"),
                    @ApiResponse(description = "Conflict - pedido com esta chave ainda em processamento ou sem estoque", responseCode = "409"),
                    @ApiResponse(description = "Unprocessable Content - chave reutilizada com outro conteúdo", responseCode = "422")
            }
    )
    @PostMapping
    public ResponseEntity<OrderResponseDTO> createOrder(
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
                    description = "UUID gerado pelo cliente por tentativa de checkout e reenviado nas retentativas")
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody OrderRequestDTO request,
            Authentication authentication) {
        CreateOrderResult result = orderService.createOrder(request, authentication.getName(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.orderResponseDTO());
    }
}
