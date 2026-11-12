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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            description = "Get orders paginated, newest first (regular users see only their own orders; admins see all orders)",
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
            description = "Get order by id (order owner or admin only; another user's order returns 404)",
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
            description = "Cancel an order (owner or admin) and queue an asynchronous stock return. "
                    + "Repeating the request for an already cancelled order returns 200 with no effect.",
            summary = "Cancel an order",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Bad Request", responseCode = "400"),
                    @ApiResponse(description = "Not Found", responseCode = "404"),
                    @ApiResponse(description = "Conflict - status does not allow cancellation or concurrent modification", responseCode = "409")
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
            description = "Create a new order for the authenticated user. Repeating the request with the same "
                    + "Idempotency-Key returns the existing order without reserving stock again.",
            summary = "Create a new order",
            responses = {
                    @ApiResponse(description = "Created (or replay of an order already created with the same key)", responseCode = "201"),
                    @ApiResponse(description = "Bad Request - invalid payload or missing/invalid Idempotency-Key", responseCode = "400"),
                    @ApiResponse(description = "Conflict - order with this key is still being processed or stock is insufficient", responseCode = "409"),
                    @ApiResponse(description = "Unprocessable Content - key reused with different contents", responseCode = "422")
            }
    )
    @PostMapping
    public ResponseEntity<OrderResponseDTO> createOrder(
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
                    description = "Client-generated UUID for each checkout attempt, resent on retries")
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody OrderRequestDTO request,
            Authentication authentication,
            @AuthenticationPrincipal Jwt jwt) {
        CreateOrderResult result = orderService.createOrder(request, authentication.getName(), idempotencyKey, jwt.getClaimAsString("email"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.orderResponseDTO());
    }
}
