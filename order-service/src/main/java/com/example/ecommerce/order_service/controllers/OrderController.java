package com.example.ecommerce.order_service.controllers;

import com.example.ecommerce.order_service.dtos.request.OrderRequestDTO;
import com.example.ecommerce.order_service.dtos.response.OrderResponseDTO;
import com.example.ecommerce.order_service.services.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/order")
@Tag(name = "Orders", description = "Controller for Order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Operation(
            description = "Get all orders (usuário comum vê só os próprios; admin vê todos)",
            summary = "List orders",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200")
            }
    )
    @GetMapping
    public ResponseEntity<List<OrderResponseDTO>> findAll(Authentication authentication) {
        return ResponseEntity.ok(orderService.findAll(authentication));
    }

    @Operation(
            description = "Get order by id (apenas o dono do pedido ou um admin)",
            summary = "Get order by id",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Forbidden", responseCode = "403"),
                    @ApiResponse(description = "Not Found", responseCode = "404")
            }
    )
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponseDTO> findById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(orderService.findById(id, authentication));
    }

    @Operation(
            description = "Create a new order for the authenticated user",
            summary = "Create a new order",
            responses = {
                    @ApiResponse(description = "Created", responseCode = "201"),
                    @ApiResponse(description = "Bad Request", responseCode = "400"),
                    @ApiResponse(description = "Unprocessable Entity", responseCode = "422")
            }
    )
    @PostMapping
    public ResponseEntity<OrderResponseDTO> createOrder(@Valid @RequestBody OrderRequestDTO request, Authentication authentication) {
        OrderResponseDTO response = orderService.createOrder(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
