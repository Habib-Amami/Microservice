#!/usr/bin/env python3
"""Seed the microservice stack with its demo dataset.

Every service owns its demo data in ``src/main/resources/seed/``. This script
only orchestrates:

    supplier -> user -> inventory (stocks) -> product (with stockId)
    -> procurement -> payment

It resolves business keys (supplier/product/stock codes) to the IDs returned
by the owning service and links records exclusively through the API Gateway,
so all validation and business logic runs — including invoice amount
calculation and product snapshots in Procurement.

Usage:
    python3 scripts/seed-demo.py                 # seed an empty stack
    python3 scripts/seed-demo.py --dry-run       # print the plan only
    python3 scripts/seed-demo.py --force         # seed even if data exists
    python3 scripts/seed-demo.py --api-base http://localhost:8080/api

Exit codes: 0 success, 1 seed failure.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# Each service owns its seed files under src/main/resources/seed/.
SEED_DIRS = {
    "supplier": PROJECT_ROOT / "services/supplier-service/src/main/resources/seed",
    "user": PROJECT_ROOT / "services/user-service/src/main/resources/seed",
    "product": PROJECT_ROOT / "services/product-service/src/main/resources/seed",
    "inventory": PROJECT_ROOT / "services/inventory-service/src/main/resources/seed",
    "procurement": PROJECT_ROOT / "services/procurement-service/src/main/resources/seed",
    "payment": PROJECT_ROOT / "services/payment-service/src/main/resources/seed",
}

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


# 502/503/504 from the Gateway or a Feign client mean "no instance yet" while
# Eureka/load-balancer caches warm up after a restart, not that the request was
# rejected. They are safe to retry.
TRANSIENT_STATUS = {502, 503, 504}
RETRY_ATTEMPTS = 12
RETRY_DELAY_SECONDS = 3


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
        for attempt in range(1, RETRY_ATTEMPTS + 1):
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
                if error.code in TRANSIENT_STATUS and attempt < RETRY_ATTEMPTS:
                    print(f"    {method} {path} -> HTTP {error.code}, retry "
                          f"{attempt}/{RETRY_ATTEMPTS - 1} while services register ...")
                    time.sleep(RETRY_DELAY_SECONDS)
                    continue
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


def load(service, name):
    path = SEED_DIRS[service] / name
    if not path.exists():
        raise SeedError(f"missing seed file: {path}")
    return json.loads(path.read_text())


def ref(value, placeholder):
    return value if value is not None else f"<{placeholder}>"


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
    for sector in load("supplier", "sectors.json"):
        created = api.post("/secteurs-activite", {
            "codeSecteurActivite": sector["code"],
            "libelleSecteurActivite": sector["label"],
        })
        sectors[sector["code"]] = created.get("idSecteurActivite")

    suppliers = {}
    for supplier in load("supplier", "suppliers.json"):
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
            api.put(f"/fournisseurs/{ref(supplier_id, 'supplierId')}"
                    f"/secteurs-activite/{ref(sectors[sector_code], 'sectorId')}")
    return suppliers


def seed_operators(api):
    operators = {}
    for operator in load("user", "operators.json"):
        created = api.post("/operateurs", {
            "nom": operator["lastName"],
            "prenom": operator["firstName"],
            "password": operator["password"],
        })
        operators[operator["lastName"]] = created.get("idOperateur")
    return operators


def seed_products(api, stocks):
    categories = {}
    for category in load("product", "categories.json"):
        created = api.post("/categories-produits", {
            "codeCategorie": category["code"],
            "libelleCategorie": category["label"],
        })
        categories[category["code"]] = created.get("idCategorieProduit")

    assignments = {}
    for assignment in load("inventory", "assignments.json"):
        stock_id = stocks.get(assignment["stockLabel"])
        if stock_id is None and not api.dry_run:
            raise SeedError(f"No stock id for {assignment['stockLabel']}")
        assignments[assignment["productCode"]] = stock_id

    # A product must be created in a stock: product-service validates the stock
    # and asks inventory-service to register the assignment in the same call.
    products = {}
    for product in load("product", "products.json"):
        stock_id = assignments.get(product["code"])
        if stock_id is None and not api.dry_run:
            raise SeedError(f"No stock assignment for {product['code']}")
        created = api.post("/produits", {
            "codeProduit": product["code"],
            "libelleProduit": product["label"],
            "prix": product["price"],
            "dateCreation": None,
            "dateDerniereModification": None,
            "categorieProduitId": categories.get(product.get("categoryCode")),
            "stockId": stock_id,
        })
        products[product["code"]] = created.get("idProduit")
    return products


def seed_stocks(api):
    stocks = {}
    for stock in load("inventory", "stocks.json"):
        created = api.post("/stocks", {
            "libelleStock": stock["label"],
            "qte": stock["quantity"],
            "qteMin": stock["minimumQuantity"],
        })
        stocks[stock["label"]] = created.get("idStock")
    return stocks


def invoice_key(source):
    return (source["supplierCode"], source["date"])


def seed_invoices(api, suppliers, products):
    invoices = {}
    for invoice in load("procurement", "invoices.json"):
        lines = [{
            "qteCommandee": line["quantity"],
            "pourcentageRemise": line.get("discountPercentage", 0),
            "produit": {"idProduit": products[line["productCode"]]},
        } for line in invoice.get("details", [])]

        created = api.post("/facture/add-facture", {
            "montantRemise": 0,
            "montantFacture": 0,
            "dateCreationFacture": invoice.get("date"),
            "dateDerniereModificationFacture": invoice.get("date"),
            "archivee": False,
            "fournisseurId": suppliers[invoice["supplierCode"]],
            "detailsFacture": lines,
        })
        invoices[invoice_key(invoice)] = (created.get("idFacture"),
                                          created.get("montantFacture"))
    return invoices


def seed_settlements(api, invoices):
    count = 0
    for settlement in load("payment", "settlements.json"):
        invoice_id, _ = invoices[invoice_key(settlement["invoice"])]
        api.post("/reglements", {
            "montantPaye": settlement["paid"],
            "montantRestant": settlement["remaining"],
            "payee": settlement["paidStatus"],
            "dateReglement": settlement["date"],
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

        print("1/6 supplier: activity sectors and suppliers ...")
        suppliers = seed_suppliers(api)
        print("2/6 user: operators ...")
        seed_operators(api)
        print("3/6 inventory: stocks ...")
        stocks = seed_stocks(api)
        print("4/6 product: categories and products in their stocks ...")
        products = seed_products(api, stocks)
        print("5/6 procurement: invoices (amounts computed by the service) ...")
        invoices = seed_invoices(api, suppliers, products)
        print("6/6 payment: settlements ...")
        settlements = seed_settlements(api, invoices)

        print("\nSeed complete:")
        print(f"  suppliers: {len(suppliers)}  products: {len(products)}  "
              f"stocks: {len(stocks)}  invoices: {len(invoices)}  "
              f"settlements: {settlements}")
        for (supplier_code, date), (invoice_id, total) in invoices.items():
            print(f"  invoice {invoice_id}: {supplier_code} {date} -> "
                  f"computed total {total}")
        return 0
    except SeedError as error:
        print(f"\nSEED FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
