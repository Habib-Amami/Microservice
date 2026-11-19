package org.example.achat.procurement.api.dto;

import java.time.LocalDate;
import java.util.List;

/** Projection returned by the CQRS query endpoints. */
public record CqrsInvoiceResponse(
        String id,
        Long fournisseurId,
        float montantRemise,
        float montantFacture,
        LocalDate dateCreationFacture,
        LocalDate dateDerniereModificationFacture,
        boolean archivee,
        List<CqrsInvoiceLineResponse> lignes) {
}
