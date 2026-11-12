package com.example.ecommerce.product.controller;


import com.example.ecommerce.product.dto.request.ProductRequestDTO;
import com.example.ecommerce.product.dto.response.PageResponseDTO;
import com.example.ecommerce.product.dto.response.ProductResponseDTO;
import com.example.ecommerce.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import com.example.ecommerce.product.infra.http.EntityTags;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(value = "/api/product")
@Tag(name = "Products", description = "Controller for Product")
public class ProductController {

    private static final String IF_MATCH_DESCRIPTION = "Product ETag obtained from GET /api/product/{id}";

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(
            description = "Get product by id",
            summary = "Get product by id",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Not Found", responseCode = "404"),
            }
    )
    @GetMapping(value = "/{id}")
    public ResponseEntity<ProductResponseDTO> findById(@PathVariable String id){
        ProductResponseDTO dto = productService.findByID(id);

        return ResponseEntity.ok().eTag(EntityTags.fromVersion(dto.version())).body(dto);
    }

    @Operation(
            description = "Get products paginated, optionally filtered by name and category",
            summary = "List products",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Bad Request", responseCode = "400"),
            }
    )
    @GetMapping
    public ResponseEntity<PageResponseDTO<ProductResponseDTO>> findAll(
            @Parameter(description = "Case-insensitive partial product name", example = "keyboard")
            @RequestParam(required = false) @Size(max = 100) String name,
            @Parameter(description = "Exact category", example = "Peripherals")
            @RequestParam(required = false) @Size(max = 100) String category,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(productService.findAll(name, category, page, size));
    }

    @Operation(
            description = "Get the distinct product categories, sorted",
            summary = "List categories",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
            }
    )
    @GetMapping("/categories")
    public ResponseEntity<List<String>> findCategories() {
        return ResponseEntity.ok(productService.findCategories());
    }

    @Operation(
            description = "Create a Product",
            summary = "Create a Product",
            responses = {
                    @ApiResponse(description = "Created", responseCode = "201"),
                    @ApiResponse(description = "Bad Request", responseCode = "400"),
                    @ApiResponse(description = "Unauthorized", responseCode = "401"),
                    @ApiResponse(description = "Forbidden", responseCode = "403"),
                    @ApiResponse(description = "Unprocessable Entity", responseCode = "422")
            }
    )

    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ProductResponseDTO> insert(@Valid @RequestBody ProductRequestDTO dto){
        ProductResponseDTO created = productService.insert(dto);

        URI uri = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();

        return ResponseEntity.created(uri).eTag(EntityTags.fromVersion(created.version())).body(created);

    }

    @Operation(
            description = "Update Product",
            summary = "Update a Product",
            responses = {
                    @ApiResponse(description = "Ok", responseCode = "200"),
                    @ApiResponse(description = "Not Found", responseCode = "404"),
                    @ApiResponse(description = "Bad Request", responseCode = "400"),
                    @ApiResponse(description = "Unauthorized", responseCode = "401"),
                    @ApiResponse(description = "Forbidden", responseCode = "403"),
                    @ApiResponse(description = "Precondition Failed: the product has changed since the GET request", responseCode = "412"),
                    @ApiResponse(description = "Unprocessable Entity", responseCode = "422"),
                    @ApiResponse(description = "Precondition Required: missing If-Match", responseCode = "428")
            }
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping(value = "/{id}")
    public ResponseEntity<ProductResponseDTO> update(
            @PathVariable String id,
            @Parameter(in = ParameterIn.HEADER, description = IF_MATCH_DESCRIPTION, example = "\"3\"")
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ProductRequestDTO dto) {
        ProductResponseDTO updated = productService.update(id, EntityTags.requireVersion(ifMatch), dto);

        return ResponseEntity.ok().eTag(EntityTags.fromVersion(updated.version())).body(updated);
    }


    @Operation(
            description = "Delete a Product",
            summary = "Delete a Product",
            responses = {
                    @ApiResponse(description = "Success", responseCode = "204"),
                    @ApiResponse(description = "Bad Request", responseCode = "400"),
                    @ApiResponse(description = "Not Found", responseCode = "404"),
                    @ApiResponse(description = "Unauthorized", responseCode = "401"),
                    @ApiResponse(description = "Forbidden", responseCode = "403"),
                    @ApiResponse(description = "Precondition Failed: the product has changed since the GET request", responseCode = "412"),
                    @ApiResponse(description = "Precondition Required: missing If-Match", responseCode = "428")
            }
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping(value = "/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @Parameter(in = ParameterIn.HEADER, description = IF_MATCH_DESCRIPTION, example = "\"3\"")
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch){
        productService.delete(id, EntityTags.requireVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

}
