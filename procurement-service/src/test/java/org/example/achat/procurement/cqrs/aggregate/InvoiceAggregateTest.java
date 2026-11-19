package org.example.achat.procurement.cqrs.aggregate;

import org.axonframework.test.aggregate.AggregateTestFixture;
import org.axonframework.test.aggregate.FixtureConfiguration;
import org.example.achat.procurement.cqrs.InvoiceLineData;
import org.example.achat.procurement.cqrs.command.ArchiveInvoiceCommand;
import org.example.achat.procurement.cqrs.command.CreateInvoiceCommand;
import org.example.achat.procurement.cqrs.event.InvoiceArchivedEvent;
import org.example.achat.procurement.cqrs.event.InvoiceCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

class InvoiceAggregateTest {

    private FixtureConfiguration<InvoiceAggregate> fixture;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(InvoiceAggregate.class);
    }

    private static InvoiceLineData line() {
        return new InvoiceLineData(42L, "P-42", "Product", 3, 10, 1.5f, 13.5f);
    }

    private static CreateInvoiceCommand createCommand() {
        return new CreateInvoiceCommand(
                "invoice-1", 7L, 1.5f, 13.5f,
                LocalDate.of(2026, 4, 15), LocalDate.of(2026, 4, 16),
                false, List.of(line()));
    }

    private static InvoiceCreatedEvent createdEvent() {
        return new InvoiceCreatedEvent(
                "invoice-1", 7L, 1.5f, 13.5f,
                LocalDate.of(2026, 4, 15), LocalDate.of(2026, 4, 16),
                false, List.of(line()));
    }

    @Test
    void createCommandPublishesInvoiceCreatedEvent() {
        fixture.givenNoPriorActivity()
                .when(createCommand())
                .expectEvents(createdEvent());
    }

    @Test
    void archiveCommandPublishesInvoiceArchivedEvent() {
        fixture.given(createdEvent())
                .when(new ArchiveInvoiceCommand("invoice-1"))
                .expectEvents(new InvoiceArchivedEvent("invoice-1"));
    }

    @Test
    void archivingAnAlreadyArchivedInvoiceIsRejected() {
        fixture.given(createdEvent(), new InvoiceArchivedEvent("invoice-1"))
                .when(new ArchiveInvoiceCommand("invoice-1"))
                .expectException(IllegalStateException.class);
    }
}
