package org.example.achat.product.infrastructure.inventory;

import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.infrastructure.inventory.dto.StockResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Inventory-service owns stocks and product-to-stock assignments. Product
 * creation validates the stock and asks Inventory to register the assignment
 * through its legacy compatibility route.
 */
@FeignClient(name = "inventory-service", contextId = "productInventoryCreate")
public interface InventoryClient {

    @GetMapping("/stocks/{stockId}")
    StockResponse getStock(@PathVariable Long stockId);

    @PutMapping("/produits/{productId}/stock/{stockId}")
    ProductResponse assignStock(
            @PathVariable Long productId,
            @PathVariable Long stockId,
            @RequestHeader("Idempotency-Key") String idempotencyKey);
}
