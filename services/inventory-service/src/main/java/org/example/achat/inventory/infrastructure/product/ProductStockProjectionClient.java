package org.example.achat.inventory.infrastructure.product;

import org.example.achat.inventory.infrastructure.product.dto.StockAssignmentRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Pushes the product's stock projection after an assignment is saved here.
 * The relation is owned by inventory-service; product-service only mirrors it.
 */
@FeignClient(
        name = "product-service",
        contextId = "productStockProjection",
        path = "/internal/v1/products")
public interface ProductStockProjectionClient {

    @PutMapping("/{productId}/stock")
    void assignStock(
            @PathVariable Long productId,
            @RequestBody StockAssignmentRequest request);
}
