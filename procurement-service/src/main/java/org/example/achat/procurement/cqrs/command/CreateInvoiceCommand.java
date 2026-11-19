package org.example.achat.procurement.cqrs.command;

import org.axonframework.modelling.command.TargetAggregateIdentifier;
import org.example.achat.procurement.cqrs.InvoiceLineData;

import java.time.LocalDate;
import java.util.List;

/** Command sent by the REST API to create an invoice in the write model. */
public record CreateInvoiceCommand(
        @TargetAggregateIdentifier String id,
        Long fournisseurId,
        float montantRemise,
        float montantFacture,
        LocalDate dateCreationFacture,
        LocalDate dateDerniereModificationFacture,
        boolean archivee,
        List<InvoiceLineData> lignes) {
}
