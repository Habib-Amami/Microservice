package org.example.achat.procurement.cqrs;

/**
 * Immutable snapshot of an invoice line carried by commands and events.
 *
 * <p>The write model stores quantities and prices; the read model projects
 * the same values into the query-side tables.
 */
public record InvoiceLineData(
        Long produitId,
        String codeProduit,
        String libelleProduit,
        Integer qteCommandee,
        Integer pourcentageRemise,
        float montantRemise,
        float prixTotalDetail) {
}
