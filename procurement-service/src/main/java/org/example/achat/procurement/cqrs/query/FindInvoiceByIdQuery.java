package org.example.achat.procurement.cqrs.query;

/** Read-side query for one projected invoice. */
public record FindInvoiceByIdQuery(String id) {
}
