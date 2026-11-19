package org.example.achat.procurement.api;

import jakarta.validation.Valid;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.queryhandling.QueryGateway;
import org.axonframework.messaging.responsetypes.ResponseTypes;
import org.example.achat.procurement.api.dto.CqrsInvoiceLineRequest;
import org.example.achat.procurement.api.dto.CqrsInvoiceRequest;
import org.example.achat.procurement.api.dto.CqrsInvoiceResponse;
import org.example.achat.procurement.cqrs.InvoiceLineData;
import org.example.achat.procurement.cqrs.command.ArchiveInvoiceCommand;
import org.example.achat.procurement.cqrs.command.CreateInvoiceCommand;
import org.example.achat.procurement.cqrs.query.FindAllInvoicesQuery;
import org.example.achat.procurement.cqrs.query.FindInvoiceByIdQuery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * REST façade over the Axon command and query buses.
 *
 * <p>Commands go through {@link CommandGateway} to the {@code InvoiceAggregate};
 * queries go through {@link QueryGateway} to the projector's {@code
 * @QueryHandler} methods. The legacy {@code /facture/**} endpoints are left
 * untouched so existing clients keep working.
 */
@RestController
@RequestMapping("/cqrs/factures")
public class CqrsInvoiceController {

    private final CommandGateway commands;
    private final QueryGateway queries;

    public CqrsInvoiceController(CommandGateway commands, QueryGateway queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@Valid @RequestBody CqrsInvoiceRequest request) {
        String id = UUID.randomUUID().toString();
        List<InvoiceLineData> lines = request.lignes() == null
                ? List.of()
                : request.lignes().stream().map(CqrsInvoiceController::toLineData).toList();

        commands.sendAndWait(new CreateInvoiceCommand(
                id,
                request.fournisseurId(),
                request.montantRemise(),
                request.montantFacture(),
                request.dateCreationFacture(),
                request.dateDerniereModificationFacture(),
                Boolean.TRUE.equals(request.archivee()),
                lines));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", id));
    }

    @PutMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable String id) {
        // Validate against the read model first. Axon Server replaces a thrown
        // domain exception with a generic CommandExecutionException, so the
        // REST layer owns the HTTP status mapping instead.
        CqrsInvoiceResponse existing = await(queries.query(
                new FindInvoiceByIdQuery(id),
                ResponseTypes.instanceOf(CqrsInvoiceResponse.class)));
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.archivee()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        commands.sendAndWait(new ArchiveInvoiceCommand(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<CqrsInvoiceResponse> findAll() {
        // A typed array survives Jackson serialization through Axon Server;
        // multipleInstancesOf(...) would receive a List of Maps instead.
        CqrsInvoiceResponse[] invoices = await(queries.query(
                new FindAllInvoicesQuery(),
                ResponseTypes.instanceOf(CqrsInvoiceResponse[].class)));
        return invoices == null ? List.of() : List.of(invoices);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CqrsInvoiceResponse> findById(@PathVariable String id) {
        CqrsInvoiceResponse invoice = await(queries.query(
                new FindInvoiceByIdQuery(id),
                ResponseTypes.instanceOf(CqrsInvoiceResponse.class)));
        return invoice == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(invoice);
    }

    private static InvoiceLineData toLineData(CqrsInvoiceLineRequest line) {
        return new InvoiceLineData(
                line.produitId(),
                line.codeProduit(),
                line.libelleProduit(),
                line.qteCommandee(),
                line.pourcentageRemise(),
                line.montantRemise(),
                line.prixTotalDetail());
    }

    /** Surface the original Axon exception so the @RestControllerAdvice can map it. */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
