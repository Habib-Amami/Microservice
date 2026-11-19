package org.example.achat.product.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

/**
 * Product creation always requires a stock. Inventory-service owns the
 * assignment, so {@code stockId} is validated and registered as part of the
 * create operation before the response is returned.
 */
public record ProductCreateRequest(
        @NotBlank String codeProduit,
        @NotBlank String libelleProduit,
        @PositiveOrZero float prix,
        LocalDate dateCreation,
        LocalDate dateDerniereModification,
        Long categorieProduitId,
        @NotNull Long stockId) {
}
