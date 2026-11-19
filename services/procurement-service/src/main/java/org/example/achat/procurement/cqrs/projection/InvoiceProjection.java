package org.example.achat.procurement.cqrs.projection;

import org.axonframework.config.ProcessingGroup;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.queryhandling.QueryHandler;
import org.example.achat.procurement.api.dto.CqrsInvoiceLineResponse;
import org.example.achat.procurement.api.dto.CqrsInvoiceResponse;
import org.example.achat.procurement.cqrs.CqrsInvoiceNotFoundException;
import org.example.achat.procurement.cqrs.InvoiceLineData;
import org.example.achat.procurement.cqrs.event.InvoiceArchivedEvent;
import org.example.achat.procurement.cqrs.event.InvoiceCreatedEvent;
import org.example.achat.procurement.cqrs.query.FindAllInvoicesQuery;
import org.example.achat.procurement.cqrs.query.FindInvoiceByIdQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read model of the invoice CQRS flow.
 *
 * <p>Events from the aggregate are projected into the {@code
 * invoice_projections} tables, and the {@code @QueryHandler} methods answer
 * REST queries from those tables only. The event processor is configured in
 * subscribe mode, so a command's effects are visible to the next query.
 */
@Component
@ProcessingGroup("invoiceProjection")
@Transactional(readOnly = true)
public class InvoiceProjection {

    private final InvoiceProjectionRepository repository;

    public InvoiceProjection(InvoiceProjectionRepository repository) {
        this.repository = repository;
    }

    @EventHandler
    @Transactional
    public void on(InvoiceCreatedEvent event) {
        InvoiceProjectionEntity invoice = repository.findById(event.id())
                .orElseGet(() -> new InvoiceProjectionEntity(event.id()));
        invoice.setFournisseurId(event.fournisseurId());
        invoice.setMontantRemise(event.montantRemise());
        invoice.setMontantFacture(event.montantFacture());
        invoice.setDateCreationFacture(event.dateCreationFacture());
        invoice.setDateDerniereModificationFacture(event.dateDerniereModificationFacture());
        invoice.setArchivee(event.archivee());

        invoice.getLignes().clear();
        if (event.lignes() != null) {
            for (InvoiceLineData line : event.lignes()) {
                InvoiceProjectionLineEntity projectedLine = new InvoiceProjectionLineEntity();
                projectedLine.setInvoice(invoice);
                projectedLine.setProduitId(line.produitId());
                projectedLine.setCodeProduit(line.codeProduit());
                projectedLine.setLibelleProduit(line.libelleProduit());
                projectedLine.setQteCommandee(line.qteCommandee());
                projectedLine.setPourcentageRemise(line.pourcentageRemise());
                projectedLine.setMontantRemise(line.montantRemise());
                projectedLine.setPrixTotalDetail(line.prixTotalDetail());
                invoice.getLignes().add(projectedLine);
            }
        }
        repository.save(invoice);
    }

    @EventHandler
    @Transactional
    public void on(InvoiceArchivedEvent event) {
        InvoiceProjectionEntity invoice = repository.findById(event.id())
                .orElseThrow(() -> new CqrsInvoiceNotFoundException(event.id()));
        invoice.setArchivee(true);
        repository.save(invoice);
    }

    @QueryHandler
    public CqrsInvoiceResponse handle(FindInvoiceByIdQuery query) {
        // Returning null (rather than throwing) keeps the "not found" signal
        // intact when the query travels through Axon Server, which otherwise
        // replaces the original exception with a generic QueryExecutionException.
        return repository.findById(query.id())
                .map(InvoiceProjection::toResponse)
                .orElse(null);
    }

    @QueryHandler
    public CqrsInvoiceResponse[] handle(FindAllInvoicesQuery query) {
        // An array keeps the element type in the serialized response. With a
        // List, Axon Server + Jackson deserializes the elements into Maps and
        // ResponseTypes.multipleInstancesOf(...) rejects them.
        return repository.findAll().stream()
                .map(InvoiceProjection::toResponse)
                .toArray(CqrsInvoiceResponse[]::new);
    }

    private static CqrsInvoiceResponse toResponse(InvoiceProjectionEntity invoice) {
        List<CqrsInvoiceLineResponse> lines = invoice.getLignes().stream()
                .map(line -> new CqrsInvoiceLineResponse(
                        line.getProduitId(),
                        line.getCodeProduit(),
                        line.getLibelleProduit(),
                        line.getQteCommandee(),
                        line.getPourcentageRemise(),
                        line.getMontantRemise(),
                        line.getPrixTotalDetail()))
                .toList();
        return new CqrsInvoiceResponse(
                invoice.getId(),
                invoice.getFournisseurId(),
                invoice.getMontantRemise(),
                invoice.getMontantFacture(),
                invoice.getDateCreationFacture(),
                invoice.getDateDerniereModificationFacture(),
                invoice.isArchivee(),
                lines);
    }
}
