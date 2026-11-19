package org.example.achat.product.api;

import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.api.dto.StockAssignmentRequest;
import org.example.achat.product.application.ProductApplicationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoint: inventory-service owns stock assignments and
 * pushes them here so product reads can expose {@code stockId} without a
 * remote call. Not exposed through the API Gateway.
 */
@RestController
@RequestMapping("/internal/v1/products")
public class InternalProductController {

    private final ProductApplicationService productService;

    public InternalProductController(ProductApplicationService productService) {
        this.productService = productService;
    }

    @PutMapping("/{productId}/stock")
    public ProductResponse assignStock(
            @PathVariable Long productId,
            @RequestBody StockAssignmentRequest request) {
        return productService.updateStockAssignment(productId, request.stockId());
    }
}
