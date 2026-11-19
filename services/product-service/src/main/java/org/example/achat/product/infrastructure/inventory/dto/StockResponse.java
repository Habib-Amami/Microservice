package org.example.achat.product.infrastructure.inventory.dto;

/** Minimal view of inventory-service's stock response, used for create validation. */
public record StockResponse(
        Long idStock,
        String libelleStock,
        Integer qte,
        Integer qteMin) {
}
