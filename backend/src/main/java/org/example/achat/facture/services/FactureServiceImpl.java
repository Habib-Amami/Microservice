package org.example.achat.facture.services;

import jakarta.transaction.Transactional;
import org.example.achat.facture.entities.Facture;
import org.example.achat.facture.entities.DetailFacture;
import org.example.achat.facture.exceptions.FactureNotFoundException;
import org.example.achat.facture.repositories.FactureRepository;
import org.example.achat.facture.repositories.DetailFactureRepository;
import org.example.achat.produit.exceptions.ProduitNotFoundException;
import org.example.achat.produit.repositories.ProduitRepository;
import org.example.achat.produit.entities.Produit;
import org.example.achat.operateur.entities.Operateur;
import org.example.achat.operateur.exceptions.OperateurNotFoundException;
import org.example.achat.operateur.repositories.OperateurRepository;
import org.example.achat.fournisseurs.exceptions.FournisseurNotFoundException;
import org.example.achat.fournisseurs.repositories.FournisseurRepository;
import org.example.achat.reglements.services.ReglementServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class FactureServiceImpl implements IFactureService {

	@Autowired
    FactureRepository factureRepository;
	@Autowired
	OperateurRepository operateurRepository;
	@Autowired
    DetailFactureRepository detailFactureRepository;
	@Autowired
	FournisseurRepository fournisseurRepository;
	@Autowired
    ProduitRepository produitRepository;
    @Autowired
    ReglementServiceImpl reglementService;
	
	@Override
	public List<Facture> retrieveAllFactures() {
		return (List<Facture>) factureRepository.findAll();
	}

	
	public Facture addFacture(Facture f) {
		return factureRepository.save(f);
	}

	/*
	 * calculer les montants remise et le montant total d'un détail facture
	 * ainsi que les montants d'une facture
	 */
	private Facture addDetailsFacture(Facture f, Set<DetailFacture> detailsFacture) {
		float montantFacture = 0;
		float montantRemise = 0;
		for (DetailFacture detail : detailsFacture) {
			//Récuperer le produit 
			Produit produit = produitRepository.findById(detail.getProduit().getIdProduit())
					.orElseThrow(() -> new ProduitNotFoundException(detail.getProduit().getIdProduit()));
			//Calculer le montant total pour chaque détail Facture
			float prixTotalDetail = detail.getQteCommandee() * produit.getPrix();
			//Calculer le montant remise pour chaque détail Facture
			float montantRemiseDetail = (prixTotalDetail * detail.getPourcentageRemise()) / 100;
			float prixTotalDetailRemise = prixTotalDetail - montantRemiseDetail;
			detail.setMontantRemise(montantRemiseDetail);
			detail.setPrixTotalDetail(prixTotalDetailRemise);
			//Calculer le montant total pour la facture
			montantFacture = montantFacture + prixTotalDetailRemise;
			//Calculer le montant remise pour la facture
			montantRemise = montantRemise + montantRemiseDetail;
			detailFactureRepository.save(detail);
		}
		f.setMontantFacture(montantFacture);
		f.setMontantRemise(montantRemise);
		return f;
	}

	@Override
	public void cancelFacture(Long factureId) {
		Facture facture = factureRepository.findById(factureId)
				.orElseThrow(() -> new FactureNotFoundException(factureId));
		facture.setArchivee(true);
		factureRepository.save(facture);
	}

	@Override
	public Facture retrieveFacture(Long factureId) {
		return factureRepository.findById(factureId)
				.orElseThrow(() -> new FactureNotFoundException(factureId));
	}

	@Override
	public List<Facture> getFacturesByFournisseur(Long idFournisseur) {
		if (!fournisseurRepository.existsById(idFournisseur)) {
			throw new FournisseurNotFoundException(idFournisseur);
		}
		return factureRepository.findByFournisseurIdFournisseur(idFournisseur);
	}

	@Override
	public void assignOperateurToFacture(Long idOperateur, Long idFacture) {
		Facture facture = factureRepository.findById(idFacture)
				.orElseThrow(() -> new FactureNotFoundException(idFacture));
		Operateur operateur = operateurRepository.findById(idOperateur)
				.orElseThrow(() -> new OperateurNotFoundException(idOperateur));
		operateur.getFactures().add(facture);
		operateurRepository.save(operateur);
	}

	@Override
	public float pourcentageRecouvrement(Date startDate, Date endDate) {
		float totalFacturesEntreDeuxDates = factureRepository.getTotalFacturesEntreDeuxDates(startDate,endDate);
		if (totalFacturesEntreDeuxDates == 0) {
			return 0;
		}
		float totalRecouvrementEntreDeuxDates = reglementService.getRevenueBetweenDates(startDate, endDate);
		return (totalRecouvrementEntreDeuxDates/totalFacturesEntreDeuxDates)*100;
	}
	

}