package org.example.achat.procurement.cqrs.command;

import org.axonframework.modelling.command.TargetAggregateIdentifier;

/** Command that archives an existing invoice. */
public record ArchiveInvoiceCommand(@TargetAggregateIdentifier String id) {
}
