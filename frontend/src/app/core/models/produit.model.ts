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
 * Writable fields of the `Produit` entity. On update, the stock is changed
 * separately through inventory-service's `PUT /produits/{id}/stock/{stockId}`.
 */
export interface ProduitPayload {
  codeProduit: string;
  libelleProduit: string;
  prix: number;
}

/**
 * Creation requires the stock the product belongs to. Product-service
 * validates it and registers the assignment with inventory-service before it
 * responds, so the returned `Produit` already carries the `stockId`.
 */
export interface ProduitCreatePayload extends ProduitPayload {
  stockId: number;
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
