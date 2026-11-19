-- CQRS read model: tables populated by the Axon invoice projector.
-- The write model (invoices, invoice_lines) stays owned by the legacy REST API.

CREATE TABLE invoice_projections (
    id VARCHAR(64) NOT NULL,
    fournisseur_id BIGINT,
    montant_remise FLOAT NOT NULL,
    montant_facture FLOAT NOT NULL,
    date_creation_facture DATE,
    date_derniere_modification_facture DATE,
    archivee BOOLEAN NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE invoice_projection_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    invoice_id VARCHAR(64) NOT NULL,
    produit_id BIGINT,
    code_produit VARCHAR(255),
    libelle_produit VARCHAR(255),
    qte_commandee INT,
    pourcentage_remise INT,
    montant_remise FLOAT NOT NULL,
    prix_total_detail FLOAT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_invoice_projection_line_invoice
        FOREIGN KEY (invoice_id) REFERENCES invoice_projections (id),
    INDEX ix_invoice_projection_line_invoice (invoice_id)
);
