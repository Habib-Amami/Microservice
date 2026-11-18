package org.example.achat.inventory.infrastructure.product.dto;

/**
 * Payload for product-service's internal stock projection endpoint.
 * The null case clears an assignment.
 */
public record StockAssignmentRequest(Long stockId) {
}
