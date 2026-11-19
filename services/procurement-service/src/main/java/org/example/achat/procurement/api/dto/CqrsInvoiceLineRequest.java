package org.example.achat.procurement.api.dto;

import jakarta.validation.constraints.NotNull;

/** Line payload accepted by the CQRS command endpoint. */
public record CqrsInvoiceLineRequest(
        Long produitId,
        String codeProduit,
        String libelleProduit,
        @NotNull Integer qteCommandee,
        @NotNull Integer pourcentageRemise,
        float montantRemise,
        float prixTotalDetail) {
}
