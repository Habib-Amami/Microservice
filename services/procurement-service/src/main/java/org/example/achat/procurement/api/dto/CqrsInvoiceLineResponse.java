package org.example.achat.procurement.api.dto;

/** A line as returned by the CQRS query side. */
public record CqrsInvoiceLineResponse(
        Long produitId,
        String codeProduit,
        String libelleProduit,
        Integer qteCommandee,
        Integer pourcentageRemise,
        float montantRemise,
        float prixTotalDetail) {
}
