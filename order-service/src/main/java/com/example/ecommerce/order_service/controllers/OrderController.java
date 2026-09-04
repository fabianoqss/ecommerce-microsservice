package com.example.ecommerce.order_service.controllers;

import com.example.ecommerce.order_service.dtos.request.OrderRequestDTO;
import com.example.ecommerce.order_service.dtos.response.OrderResponseDTO;
import com.example.ecommerce.order_service.services.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/order")
@Tag(name = "Orders", description = "Controller for Order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Operation(
            description = "Get all orders",
            summary = "List all orders",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200")
            }
    )
    @GetMapping
    public ResponseEntity<List<OrderResponseDTO>> findAll() {
        return ResponseEntity.ok(orderService.findAll());
    }

    @Operation(
            description = "Get order by id",
            summary = "Get order by id",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Not Found", responseCode = "404")
            }
    )
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponseDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @Operation(
            description = "Create a new order",
            summary = "Create a new order",
            responses = {
                    @ApiResponse(description = "Created", responseCode = "201"),
                    @ApiResponse(description = "Bad Request", responseCode = "400"),
                    @ApiResponse(description = "Unprocessable Entity", responseCode = "422")
            }
    )
    @PostMapping
    public ResponseEntity<OrderResponseDTO> createOrder(@RequestBody OrderRequestDTO request) {
        OrderResponseDTO response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
