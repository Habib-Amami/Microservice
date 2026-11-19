package org.example.achat.procurement.cqrs.aggregate;

import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.spring.stereotype.Aggregate;
import org.example.achat.procurement.cqrs.InvoiceLineData;
import org.example.achat.procurement.cqrs.command.ArchiveInvoiceCommand;
import org.example.achat.procurement.cqrs.command.CreateInvoiceCommand;
import org.example.achat.procurement.cqrs.event.InvoiceArchivedEvent;
import org.example.achat.procurement.cqrs.event.InvoiceCreatedEvent;

import java.util.ArrayList;
import java.util.List;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;

/**
 * Write model of an invoice.
 *
 * <p>This aggregate never touches the query tables. It only validates
 * commands and publishes events; the projector in {@code
 * org.example.achat.procurement.cqrs.projection} builds the read model from
 * those events. Instances are restored from the event store by {@link
 * EventSourcingRepository}, so no JPA mapping is needed on this side.
 */
@Aggregate
public class InvoiceAggregate {

    @AggregateIdentifier
    private String id;

    private boolean archivee;

    /** Required by Axon's event-sourcing repository. */
    protected InvoiceAggregate() {
    }

    @CommandHandler
    public InvoiceAggregate(CreateInvoiceCommand command) {
        apply(new InvoiceCreatedEvent(
                command.id(),
                command.fournisseurId(),
                command.montantRemise(),
                command.montantFacture(),
                command.dateCreationFacture(),
                command.dateDerniereModificationFacture(),
                command.archivee(),
                command.lignes() == null ? new ArrayList<>() : new ArrayList<>(command.lignes())));
    }

    @CommandHandler
    public void handle(ArchiveInvoiceCommand command) {
        if (archivee) {
            throw new IllegalStateException("Invoice is already archived: " + command.id());
        }
        apply(new InvoiceArchivedEvent(command.id()));
    }

    @EventHandler
    private void on(InvoiceCreatedEvent event) {
        this.id = event.id();
        this.archivee = event.archivee();
    }

    @EventHandler
    private void on(InvoiceArchivedEvent event) {
        this.archivee = true;
    }
}
