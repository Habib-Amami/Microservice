package org.example.achat.product.api;

import jakarta.validation.Valid;
import org.example.achat.product.api.dto.ProductCreateRequest;
import org.example.achat.product.api.dto.ProductRequest;
import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.application.ProductApplicationService;
import org.example.achat.product.application.ProductCreationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/produits")
public class ProductController {

    private final ProductApplicationService productService;
    private final ProductCreationService creationService;

    public ProductController(
            ProductApplicationService productService,
            ProductCreationService creationService) {
        this.productService = productService;
        this.creationService = creationService;
    }

    @GetMapping
    public List<ProductResponse> getProducts() {
        return productService.findAll();
    }

    @GetMapping("/{productId}")
    public ProductResponse getProduct(@PathVariable Long productId) {
        return productService.findById(productId);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creationService.create(request));
    }

    @PutMapping("/{productId}")
    public ProductResponse updateProduct(
            @PathVariable Long productId,
            @Valid @RequestBody ProductRequest request) {
        return productService.update(productId, request);
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long productId) {
        productService.delete(productId);
        return ResponseEntity.noContent().build();
    }
}
