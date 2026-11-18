import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CrudService } from '../api/crud.service';
import { Produit, ProduitCreatePayload, ProduitPayload } from '../models/produit.model';

@Injectable({ providedIn: 'root' })
export class ProduitService extends CrudService<Produit, ProduitPayload> {
  constructor() {
    super('produits');
  }

  /** A product cannot be created without a stock. */
  override create(payload: ProduitCreatePayload): Observable<Produit> {
    return this.http.post<Produit>(this.baseUrl, payload);
  }

  assignStock(produitId: number, stockId: number): Observable<Produit> {
    return this.http.put<Produit>(`${this.baseUrl}/${produitId}/stock/${stockId}`, null);
  }
}
