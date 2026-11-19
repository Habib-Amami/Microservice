package org.example.achat.procurement.cqrs.event;

/** Emitted by {@code InvoiceAggregate} when an invoice is archived. */
public record InvoiceArchivedEvent(String id) {
}
