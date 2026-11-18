import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { NotificationService } from '../../core/services/notification.service';
import { ProduitService } from '../../core/services/produit.service';
import { StockService } from '../../core/services/stock.service';
import ProduitForm from './produit-form';

@Component({ template: '' })
class StubRoute {}

describe('ProduitForm', () => {
  const stocks = [{ idStock: 1, libelleStock: 'Main warehouse', qte: 10, qteMin: 2 }];
  const created = {
    idProduit: 9,
    codeProduit: 'P-1',
    libelleProduit: 'Product',
    prix: 1,
    stockId: null,
    categorieProduitId: null,
  };

  function setup() {
    const produitService = {
      list: vi.fn(),
      get: vi.fn(),
      create: vi.fn().mockReturnValue(of(created)),
      update: vi.fn(),
      assignStock: vi.fn().mockReturnValue(of(created)),
    };
    const stockService = { list: vi.fn().mockReturnValue(of(stocks)) };

    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', component: StubRoute }]),
        { provide: ProduitService, useValue: produitService },
        { provide: StockService, useValue: stockService },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    });
    const fixture = TestBed.createComponent(ProduitForm);
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      form: any;
      submit: () => void;
    };
    return { fixture, form: component.form, submit: () => component.submit(), produitService };
  }

  it('never offers an empty stock choice', () => {
    const { fixture } = setup();

    fixture.nativeElement.querySelector('mat-select').click();
    fixture.detectChanges();

    const options = Array.from<HTMLElement>(document.querySelectorAll('mat-option'));
    expect(options.map((option) => option.textContent?.trim())).toEqual(['Main warehouse']);
    expect(document.body.textContent).not.toContain('None');
  });

  it('requires a stock and blocks submit until one is chosen', () => {
    const { form, submit, produitService } = setup();

    form.setValue({ codeProduit: 'P-1', libelleProduit: 'Product', prix: 1, stockId: null });
    expect(form.controls.stockId.hasError('required')).toBe(true);
    expect(form.invalid).toBe(true);

    submit();
    expect(produitService.create).not.toHaveBeenCalled();

    form.controls.stockId.setValue(1);
    expect(form.valid).toBe(true);
  });

  it('assigns the selected stock right after creating the product', () => {
    const { form, submit, produitService } = setup();

    form.setValue({ codeProduit: 'P-1', libelleProduit: 'Product', prix: 1, stockId: 1 });
    submit();

    expect(produitService.create).toHaveBeenCalledWith({
      codeProduit: 'P-1',
      libelleProduit: 'Product',
      prix: 1,
    });
    expect(produitService.assignStock).toHaveBeenCalledWith(9, 1);
  });
});
