/** `ProduitResponse` */
export interface Produit {
  idProduit: number;
  codeProduit: string;
  libelleProduit: string;
  prix: number;
  stockId: number | null;
  categorieProduitId: number | null;
}

/**
 * Writable fields of the `Produit` entity: the stock link is not writable here.
 * Stock assignment stays owned by inventory-service and goes through
 * `PUT /produits/{id}/stock/{stockId}`; the resulting `stockId` comes back as a
 * read projection in `Produit`.
 */
export interface ProduitPayload {
  codeProduit: string;
  libelleProduit: string;
  prix: number;
}

/** `Produit` entity as embedded in a `DetailFacture`. */
export interface ProduitEntity {
  idProduit: number;
  codeProduit: string;
  libelleProduit: string;
  prix: number;
  dateCreation: string | null;
  dateDerniereModification: string | null;
}
