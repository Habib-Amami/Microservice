package org.example.achat.facture.services;

import org.example.achat.facture.entities.Facture;
import org.example.achat.facture.exceptions.FactureNotFoundException;
import org.example.achat.facture.repositories.DetailFactureRepository;
import org.example.achat.facture.repositories.FactureRepository;
import org.example.achat.fournisseurs.exceptions.FournisseurNotFoundException;
import org.example.achat.fournisseurs.repositories.FournisseurRepository;
import org.example.achat.operateur.entities.Operateur;
import org.example.achat.operateur.exceptions.OperateurNotFoundException;
import org.example.achat.operateur.repositories.OperateurRepository;
import org.example.achat.produit.repositories.ProduitRepository;
import org.example.achat.reglements.services.ReglementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactureServiceImplTest {

    @Mock
    private FactureRepository factureRepository;

    @Mock
    private DetailFactureRepository detailFactureRepository;

    @Mock
    private OperateurRepository operateurRepository;

    @Mock
    private FournisseurRepository fournisseurRepository;

    @Mock
    private ProduitRepository produitRepository;

    @Mock
    private ReglementServiceImpl reglementService;

    @InjectMocks
    private FactureServiceImpl factureService;

    private Facture facture;

    @BeforeEach
    void setUp() {
        facture = new Facture();
        facture.setIdFacture(1L);
        facture.setArchivee(false);
    }

    @Test
    void retrieveFactureReturnsInvoiceWhenPresent() {
        when(factureRepository.findById(1L)).thenReturn(Optional.of(facture));

        assertThat(factureService.retrieveFacture(1L)).isSameAs(facture);
    }

    @Test
    void retrieveFactureThrowsWhenMissing() {
        when(factureRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factureService.retrieveFacture(99L))
                .isInstanceOf(FactureNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void cancelFactureArchivesExistingInvoice() {
        when(factureRepository.findById(1L)).thenReturn(Optional.of(facture));

        factureService.cancelFacture(1L);

        assertThat(facture.getArchivee()).isTrue();
        verify(factureRepository).save(facture);
    }

    @Test
    void cancelFactureThrowsAndDoesNotPersistWhenMissing() {
        when(factureRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factureService.cancelFacture(99L))
                .isInstanceOf(FactureNotFoundException.class);

        verify(factureRepository, never()).save(any(Facture.class));
    }

    @Test
    void assignOperateurThrowsWhenInvoiceMissing() {
        when(factureRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factureService.assignOperateurToFacture(1L, 99L))
                .isInstanceOf(FactureNotFoundException.class)
                .hasMessageContaining("99");

        verify(operateurRepository, never()).save(any(Operateur.class));
    }

    @Test
    void assignOperateurThrowsWhenOperatorMissing() {
        when(factureRepository.findById(1L)).thenReturn(Optional.of(facture));
        when(operateurRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factureService.assignOperateurToFacture(99L, 1L))
                .isInstanceOf(OperateurNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void assignOperateurLinksInvoiceToOperator() {
        Operateur operateur = new Operateur();
        operateur.setIdOperateur(2L);
        operateur.setFactures(new HashSet<>());
        when(factureRepository.findById(1L)).thenReturn(Optional.of(facture));
        when(operateurRepository.findById(2L)).thenReturn(Optional.of(operateur));

        factureService.assignOperateurToFacture(2L, 1L);

        assertThat(operateur.getFactures()).containsExactly(facture);
        verify(operateurRepository).save(operateur);
    }

    @Test
    void getFacturesByFournisseurThrowsWhenSupplierMissing() {
        when(fournisseurRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> factureService.getFacturesByFournisseur(99L))
                .isInstanceOf(FournisseurNotFoundException.class);
    }

    @Test
    void pourcentageRecouvrementReturnsZeroWhenNoInvoicedAmount() {
        when(factureRepository.getTotalFacturesEntreDeuxDates(any(Date.class), any(Date.class)))
                .thenReturn(0f);

        float rate = factureService.pourcentageRecouvrement(new Date(0), new Date());

        assertThat(rate).isZero();
        verify(reglementService, never()).getRevenueBetweenDates(any(Date.class), any(Date.class));
    }

    @Test
    void pourcentageRecouvrementComputesRate() {
        when(factureRepository.getTotalFacturesEntreDeuxDates(any(Date.class), any(Date.class)))
                .thenReturn(200f);
        when(reglementService.getRevenueBetweenDates(any(Date.class), any(Date.class)))
                .thenReturn(50f);

        float rate = factureService.pourcentageRecouvrement(new Date(0), new Date());

        assertThat(rate).isEqualTo(25f);
    }
}
