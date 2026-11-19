package org.example.achat.procurement.cqrs;

/** Raised by the query side when no projection exists for an invoice id. */
public class CqrsInvoiceNotFoundException extends RuntimeException {

    public CqrsInvoiceNotFoundException(String id) {
        super("Invoice not found with id: " + id);
    }
}
