# Demo data seeding

Each service owns its demo dataset in `src/main/resources/seed/`.
`seed-demo.py` is a thin orchestrator: it reads
those files, calls the owning service through the API Gateway in dependency
order, and resolves business codes to the IDs each service returns.

To start a local stack first, see [docs/RUN-LOCAL.md](../docs/RUN-LOCAL.md) or
use `scripts/run-stack.sh start` (then `scripts/run-stack.sh reset` to wipe and
reseed).

| Service | Seed files | Owns |
|---|---|---|
| supplier-service | `sectors.json`, `suppliers.json` | activity sectors, suppliers, sector assignments |
| user-service | `operators.json` | operator profiles |
| product-service | `categories.json`, `products.json` | product categories, products |
| inventory-service | `stocks.json`, `assignments.json` | stocks, product↔stock assignments |
| procurement-service | `invoices.json` | invoices and their lines |
| payment-service | `settlements.json` | settlements |

## Why the orchestrator is central but the data is per service

Invoices need supplier/product IDs and price snapshots owned by Supplier and
Product; settlements need invoice IDs owned by Procurement. Seeding each
service blindly at startup would force a hidden "IDs are 1, 2, 3…" contract
between services — exactly the coupling microservices avoid. Instead:

- **data ownership** follows the service boundary (each service's seed files
  describe only what that service owns), and
- **linking** happens once, in the orchestrator, using real API responses.

Cross-service references in the seed files are business codes, never IDs:

- `inventory/assignments.json` → `productCode` + `stockLabel`
- `procurement/invoices.json` → `supplierCode` + `productCode`
- `payment/settlements.json` → `{supplierCode, date}` of the invoice

## Prerequisites

Config Server, Eureka, API Gateway, and the six services must be running with
empty service databases. The script refuses to run when it detects existing
data unless `--force` is given.

## Usage

```bash
python3 scripts/seed-demo.py                    # seed an empty stack
python3 scripts/seed-demo.py --dry-run          # print planned calls only
python3 scripts/seed-demo.py --force            # seed even if data exists
python3 scripts/seed-demo.py --api-base http://localhost:8080/api
```

Exit code `0` on success; `1` on the first failed call (HTTP status and
response body are reported).

## Notes

- Amounts are not stored in the invoice seed files: the Procurement service
  computes line and header totals from the catalog prices it looks up via
  Feign, and stores product snapshots. The demo dataset produces 52.50 and
  49.50 (10% discounted line).
- Stocks are seeded before products because `POST /produits` requires a
  `stockId`: Product Service validates it with Inventory and registers the
  assignment in the same call.
- Product responses expose `stockId` from a read projection: Inventory owns
  the assignment and pushes the id to Product Service after saving it. The
  value is `null` only when an API-level unassignment happened or the
  projection call failed (re-assign or reseed to repair).
- Development/demo data only — never point this at a production database.
