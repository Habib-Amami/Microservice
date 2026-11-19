package org.example.achat.procurement.cqrs.event;

import org.example.achat.procurement.cqrs.InvoiceLineData;

import java.time.LocalDate;
import java.util.List;

/** Emitted by {@code InvoiceAggregate} when an invoice is created. */
public record InvoiceCreatedEvent(
        String id,
        Long fournisseurId,
        float montantRemise,
        float montantFacture,
        LocalDate dateCreationFacture,
        LocalDate dateDerniereModificationFacture,
        boolean archivee,
        List<InvoiceLineData> lignes) {
}
