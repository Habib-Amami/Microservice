package org.example.achat.facture.exceptions;

public class FactureNotFoundException extends RuntimeException {

	public FactureNotFoundException(Long factureId) {
		super("Facture not found with id: " + factureId);
	}
}
