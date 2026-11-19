package org.example.achat.procurement.cqrs.projection;

import org.springframework.data.jpa.repository.JpaRepository;

/** Read-model repository; only the projector writes to it. */
public interface InvoiceProjectionRepository extends JpaRepository<InvoiceProjectionEntity, String> {
}
