package org.example.achat.product.api.dto;

/**
 * Stock assignment pushed by inventory-service, which owns the relation.
 * A null {@code stockId} clears the projection (product no longer in a stock).
 */
public record StockAssignmentRequest(Long stockId) {
}
