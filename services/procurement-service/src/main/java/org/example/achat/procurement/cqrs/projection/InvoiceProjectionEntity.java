package org.example.achat.procurement.cqrs.projection;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Read-model row for one invoice, maintained only by the Axon projector. */
@Entity
@Table(name = "invoice_projections")
public class InvoiceProjectionEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "fournisseur_id")
    private Long fournisseurId;

    @Column(name = "montant_remise", nullable = false)
    private float montantRemise;

    @Column(name = "montant_facture", nullable = false)
    private float montantFacture;

    @Column(name = "date_creation_facture")
    private LocalDate dateCreationFacture;

    @Column(name = "date_derniere_modification_facture")
    private LocalDate dateDerniereModificationFacture;

    @Column(name = "archivee", nullable = false)
    private boolean archivee;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<InvoiceProjectionLineEntity> lignes = new ArrayList<>();

    protected InvoiceProjectionEntity() {
    }

    public InvoiceProjectionEntity(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public Long getFournisseurId() {
        return fournisseurId;
    }

    public void setFournisseurId(Long fournisseurId) {
        this.fournisseurId = fournisseurId;
    }

    public float getMontantRemise() {
        return montantRemise;
    }

    public void setMontantRemise(float montantRemise) {
        this.montantRemise = montantRemise;
    }

    public float getMontantFacture() {
        return montantFacture;
    }

    public void setMontantFacture(float montantFacture) {
        this.montantFacture = montantFacture;
    }

    public LocalDate getDateCreationFacture() {
        return dateCreationFacture;
    }

    public void setDateCreationFacture(LocalDate dateCreationFacture) {
        this.dateCreationFacture = dateCreationFacture;
    }

    public LocalDate getDateDerniereModificationFacture() {
        return dateDerniereModificationFacture;
    }

    public void setDateDerniereModificationFacture(LocalDate dateDerniereModificationFacture) {
        this.dateDerniereModificationFacture = dateDerniereModificationFacture;
    }

    public boolean isArchivee() {
        return archivee;
    }

    public void setArchivee(boolean archivee) {
        this.archivee = archivee;
    }

    public List<InvoiceProjectionLineEntity> getLignes() {
        return lignes;
    }

    public void setLignes(List<InvoiceProjectionLineEntity> lignes) {
        this.lignes = lignes;
    }
}
