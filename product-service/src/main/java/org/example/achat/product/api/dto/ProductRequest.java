package org.example.achat.product.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

public record ProductRequest(
        @NotBlank String codeProduit,
        @NotBlank String libelleProduit,
        @PositiveOrZero float prix,
        LocalDate dateCreation,
        LocalDate dateDerniereModification,
        // Optional: legacy clients omit it; when present the product is
        // assigned to that category (or moved to it on update).
        Long categorieProduitId) {
}
