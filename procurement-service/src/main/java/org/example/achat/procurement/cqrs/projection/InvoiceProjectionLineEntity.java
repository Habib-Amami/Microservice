package org.example.achat.procurement.cqrs.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Read-model row for one invoice line. */
@Entity
@Table(name = "invoice_projection_lines")
public class InvoiceProjectionLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private InvoiceProjectionEntity invoice;

    @Column(name = "produit_id")
    private Long produitId;

    @Column(name = "code_produit")
    private String codeProduit;

    @Column(name = "libelle_produit")
    private String libelleProduit;

    @Column(name = "qte_commandee")
    private Integer qteCommandee;

    @Column(name = "pourcentage_remise")
    private Integer pourcentageRemise;

    @Column(name = "montant_remise", nullable = false)
    private float montantRemise;

    @Column(name = "prix_total_detail", nullable = false)
    private float prixTotalDetail;

    protected InvoiceProjectionLineEntity() {
    }

    public Long getId() {
        return id;
    }

    public InvoiceProjectionEntity getInvoice() {
        return invoice;
    }

    public void setInvoice(InvoiceProjectionEntity invoice) {
        this.invoice = invoice;
    }

    public Long getProduitId() {
        return produitId;
    }

    public void setProduitId(Long produitId) {
        this.produitId = produitId;
    }

    public String getCodeProduit() {
        return codeProduit;
    }

    public void setCodeProduit(String codeProduit) {
        this.codeProduit = codeProduit;
    }

    public String getLibelleProduit() {
        return libelleProduit;
    }

    public void setLibelleProduit(String libelleProduit) {
        this.libelleProduit = libelleProduit;
    }

    public Integer getQteCommandee() {
        return qteCommandee;
    }

    public void setQteCommandee(Integer qteCommandee) {
        this.qteCommandee = qteCommandee;
    }

    public Integer getPourcentageRemise() {
        return pourcentageRemise;
    }

    public void setPourcentageRemise(Integer pourcentageRemise) {
        this.pourcentageRemise = pourcentageRemise;
    }

    public float getMontantRemise() {
        return montantRemise;
    }

    public void setMontantRemise(float montantRemise) {
        this.montantRemise = montantRemise;
    }

    public float getPrixTotalDetail() {
        return prixTotalDetail;
    }

    public void setPrixTotalDetail(float prixTotalDetail) {
        this.prixTotalDetail = prixTotalDetail;
    }
}
