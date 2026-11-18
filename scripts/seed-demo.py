#!/usr/bin/env python3
"""Seed the microservice stack with the demo dataset.

The data in scripts/seed-data/ is the canonical copy of the demo dataset
previously seeded by the monolith (backend/src/main/resources/seed/). The
script drives the system end-to-end through the API Gateway so every record
is created by the owning service's own logic:

    supplier -> user -> product -> inventory -> procurement -> payment

Usage:
    python3 scripts/seed-demo.py                 # seed an empty stack
    python3 scripts/seed-demo.py --dry-run       # print the plan only
    python3 scripts/seed-demo.py --force         # seed even if data exists
    python3 scripts/seed-demo.py --api-base http://localhost:8080/api

Exit codes: 0 success, 1 seed failure, 2 precondition failure.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent / "seed-data"

# Collections checked to decide whether the target stack already has data.
EMPTY_GUARDS = [
    ("/fournisseurs", "suppliers"),
    ("/operateurs", "operators"),
    ("/produits", "products"),
    ("/stocks", "stocks"),
    ("/facture/retrieve-all-factures", "invoices"),
    ("/reglements", "settlements"),
]


class SeedError(RuntimeError):
    pass


class Api:
    def __init__(self, base, dry_run=False):
        self.base = base.rstrip("/")
        self.dry_run = dry_run

    def request(self, method, path, payload=None):
        if self.dry_run:
            print(f"    [dry-run] {method} {path}"
                  + (f" {json.dumps(payload)[:120]}" if payload else ""))
            return {} if method != "PUT" else None
        body = json.dumps(payload).encode() if payload is not None else None
        request = urllib.request.Request(
            self.base + path,
            data=body,
            method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")[:400]
            raise SeedError(f"{method} {path} -> HTTP {error.code}: {detail}") from None
        except urllib.error.URLError as error:
            raise SeedError(
                f"{method} {path} -> cannot reach {self.base}: {error.reason}. "
                "Start Config Server, Eureka, Gateway, and the services first."
            ) from None

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, payload):
        return self.request("POST", path, payload)

    def put(self, path, payload=None):
        return self.request("PUT", path, payload)


def load(name):
    return json.loads((DATA_DIR / name).read_text())


def require_empty(api, force):
    present = []
    for path, label in EMPTY_GUARDS:
        items = api.get(path) or []
        if items:
            present.append(f"{label}={len(items)}")
    if present and not force:
        raise SeedError(
            "Target stack already contains data (" + ", ".join(present) + "). "
            "Re-run with --force to seed anyway, or reset the service databases."
        )
    if present:
        print("  ! target stack is not empty, continuing because --force was given")


def seed_suppliers(api):
    sectors = {}
    for sector in load("secteurs.json"):
        created = api.post("/secteurs-activite", {
            "codeSecteurActivite": sector["code"],
            "libelleSecteurActivite": sector["label"],
        })
        sectors[sector["code"]] = created.get("idSecteurActivite")

    suppliers = {}
    for supplier in load("fournisseurs.json"):
        created = api.post("/fournisseurs", {
            "code": supplier["code"],
            "libelle": supplier["label"],
            "categorieFournisseur": supplier["category"],
            "detailFournisseur": {
                "email": supplier.get("email"),
                "dateDebutCollaboration": supplier.get("startDate"),
                "adresse": supplier.get("address"),
                "matricule": supplier.get("registrationNumber"),
            },
        })
        supplier_id = created.get("idFournisseur")
        suppliers[supplier["code"]] = supplier_id
        for sector_code in supplier.get("sectorCodes", []):
            supplier_ref = supplier_id if supplier_id is not None else "<supplierId>"
            sector_ref = sectors[sector_code]
            sector_ref = sector_ref if sector_ref is not None else "<sectorId>"
            api.put(f"/fournisseurs/{supplier_ref}/secteurs-activite/{sector_ref}")
    return suppliers


def seed_operators(api):
    operators = {}
    for operator in load("operateurs.json"):
        created = api.post("/operateurs", {
            "nom": operator["lastName"],
            "prenom": operator["firstName"],
            "password": operator["password"],
        })
        operators[operator["lastName"]] = created.get("idOperateur")
    return operators


def seed_products(api):
    categories = {}
    for category in load("categories.json"):
        created = api.post("/categories-produits", {
            "codeCategorie": category["code"],
            "libelleCategorie": category["label"],
        })
        categories[category["code"]] = created.get("idCategorieProduit")

    products = {}
    for product in load("products.json"):
        created = api.post("/produits", {
            "codeProduit": product["code"],
            "libelleProduit": product["label"],
            "prix": product["price"],
            "dateCreation": None,
            "dateDerniereModification": None,
            "categorieProduitId": categories.get(product.get("categoryCode")),
        })
        products[product["code"]] = created.get("idProduit")
    return products


def seed_stocks(api, products):
    stocks = {}
    for stock in load("stocks.json"):
        created = api.post("/stocks", {
            "libelleStock": stock["label"],
            "qte": stock["quantity"],
            "qteMin": stock["minimumQuantity"],
        })
        stocks[stock["label"]] = created.get("idStock")

    # Assign each product to the stock named in the seed data, through the
    # inventory service's legacy compatibility route.
    for product in load("products.json"):
        product_id = products.get(product["code"])
        stock_id = stocks.get(product.get("stockLabel"))
        if api.dry_run or (product_id and stock_id):
            product_ref = product_id if product_id is not None else "<productId>"
            stock_ref = stock_id if stock_id is not None else "<stockId>"
            api.put(f"/produits/{product_ref}/stock/{stock_ref}")
    return stocks


def seed_invoices(api, suppliers, products):
    invoices = []
    for invoice in load("factures.json"):
        lines = []
        for line in invoice.get("details", []):
            lines.append({
                "qteCommandee": line["quantity"],
                "pourcentageRemise": line.get("discountPercentage", 0),
                "produit": {"idProduit": products[line["productCode"]]},
            })
        created = api.post("/facture/add-facture", {
            "montantRemise": 0,
            "montantFacture": 0,
            "dateCreationFacture": invoice.get("date"),
            "dateDerniereModificationFacture": invoice.get("date"),
            "archivee": False,
            "fournisseurId": suppliers[invoice["supplierCode"]],
            "detailsFacture": lines,
        })
        invoice_id = created.get("idFacture")
        invoices.append((invoice, invoice_id, created.get("montantFacture")))
    return invoices


def seed_settlements(api, invoices):
    count = 0
    for invoice, invoice_id, _ in invoices:
        for payment in invoice.get("payments", []):
            api.post("/reglements", {
                "montantPaye": payment["paid"],
                "montantRestant": payment["remaining"],
                "payee": payment["paidStatus"],
                "dateReglement": payment["date"],
                "factureId": invoice_id,
            })
            count += 1
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--api-base", default="http://localhost:8080/api",
                        help="Gateway API base URL (default: %(default)s)")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the API calls without executing them")
    parser.add_argument("--force", action="store_true",
                        help="seed even when the target already contains data")
    args = parser.parse_args()

    api = Api(args.api_base, dry_run=args.dry_run)

    try:
        print(f"Seeding {args.api_base}"
              + (" (dry run)" if args.dry_run else ""))
        require_empty(api, args.force)

        print("1/6 suppliers and activity sectors ...")
        suppliers = seed_suppliers(api)
        print("2/6 operators ...")
        seed_operators(api)
        print("3/6 product categories and products ...")
        products = seed_products(api)
        print("4/6 stocks and product stock assignments ...")
        stocks = seed_stocks(api, products)
        print("5/6 invoices (amounts computed by Procurement) ...")
        invoices = seed_invoices(api, suppliers, products)
        print("6/6 settlements ...")
        settlements = seed_settlements(api, invoices)

        print("\nSeed complete:")
        print(f"  suppliers: {len(suppliers)}  products: {len(products)}  "
              f"stocks: {len(stocks)}  invoices: {len(invoices)}  "
              f"settlements: {settlements}")
        for invoice, invoice_id, computed_total in invoices:
            print(f"  invoice {invoice_id}: computed total {computed_total} "
                  f"(source file declares {invoice.get('amount')}; line amounts "
                  "are recalculated from catalog prices)")
        return 0
    except SeedError as error:
        print(f"\nSEED FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
