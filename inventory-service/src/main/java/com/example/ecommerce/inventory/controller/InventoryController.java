package com.example.ecommerce.inventory.controller;

import com.example.ecommerce.inventory.dto.request.InventoryRequestDTO;
import com.example.ecommerce.inventory.dto.request.InventoryReserveRequestDTO;
import com.example.ecommerce.inventory.dto.request.InventoryUpdateDTO;
import com.example.ecommerce.inventory.dto.response.InventoryDTO;
import com.example.ecommerce.inventory.dto.response.ReservationResponseDTO;
import com.example.ecommerce.inventory.dto.response.ReservationResult;
import com.example.ecommerce.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequestMapping("/api/inventory")
@RestController
@Tag(name = "Inventory", description = "Controller for Inventory")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    @Operation(
            description = "Check stock for one or more SKU codes",
            summary = "Check if items are in stock",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200")
            }
    )
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<InventoryDTO> isInStock(@RequestParam("skuCode") List<String> skuCode) {
        return inventoryService.isInStock(skuCode);
    }

    @Operation(
            description = "Insert a new inventory item",
            summary = "Insert inventory item",
            responses = {
                    @ApiResponse(description = "Created", responseCode = "201"),
                    @ApiResponse(description = "Bad Request", responseCode = "400")
            }
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryDTO insert(@Valid @RequestBody InventoryRequestDTO dto) {
        return inventoryService.insert(dto);
    }

    @Operation(
            description = "Update quantity of an inventory item",
            summary = "Update inventory item",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Not Found", responseCode = "404"),
                    @ApiResponse(description = "Bad Request", responseCode = "400")
            }
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public InventoryDTO update(@PathVariable Long id, @Valid @RequestBody InventoryUpdateDTO dto) {
        return inventoryService.update(id, dto);
    }

    @Operation(
            description = "Idempotently reserve stock for an order identified by reservationId (orderId).",
            summary = "Idempotent stock reservation by reservationId",
            responses = {
                    @ApiResponse(description = "Created - reservation created and stock decremented", responseCode = "201"),
                    @ApiResponse(description = "Ok - idempotent replay with identical items", responseCode = "200"),
                    @ApiResponse(description = "Bad Request - invalid payload", responseCode = "400"),
                    @ApiResponse(description = "Conflict - insufficient stock or nonexistent SKU", responseCode = "409"),
                    @ApiResponse(description = "Gone - reservation already released/tombstoned", responseCode = "410"),
                    @ApiResponse(description = "Unprocessable Content - reservation exists with different items", responseCode = "422")
            }
    )
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationResponseDTO> reserveById(
            @PathVariable UUID reservationId,
            @Valid @RequestBody InventoryReserveRequestDTO dto) {
        ReservationResult result = inventoryService.reserve(reservationId, dto.items());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.reservation());
    }

    @Operation(
            description = "Idempotently release a stock reservation (or record a tombstone if not yet reserved).",
            summary = "Idempotent stock release by reservationId",
            responses = {
                    @ApiResponse(description = "No Content - released, tombstone created, or noop", responseCode = "204")
            }
    )
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/reservations/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseById(@PathVariable UUID reservationId) {
        inventoryService.release(reservationId);
    }

    @Operation(
            description = "Get current state of a stock reservation for reconciliation or diagnostics.",
            summary = "Get stock reservation by reservationId",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Not Found", responseCode = "404")
            }
    )
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/reservations/{reservationId}")
    @ResponseStatus(HttpStatus.OK)
    public ReservationResponseDTO getReservation(@PathVariable UUID reservationId) {
        return inventoryService.getReservation(reservationId);
    }
}
