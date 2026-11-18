# Demo data seeding

`seed-demo.py` populates a running microservice stack with the demo dataset
that the monolith previously loaded from `backend/src/main/resources/seed/`.
The canonical copy of that dataset now lives in [`seed-data/`](seed-data/), so
it survives the monolith's retirement.

Every record is created through the API Gateway, so it passes through the
owning service's validation and business logic (including invoice amount
calculation and product snapshots in Procurement).

## Prerequisites

The full stack must be running: Config Server, Eureka, API Gateway, and the
six services, with empty service databases. The script refuses to run when it
detects existing data unless `--force` is given.

## Usage

```bash
python3 scripts/seed-demo.py                    # seed an empty stack
python3 scripts/seed-demo.py --dry-run          # print planned calls only
python3 scripts/seed-demo.py --force            # seed even if data exists
python3 scripts/seed-demo.py --api-base http://localhost:8080/api
```

Exit codes: `0` success, `1` seeding failed (first error is reported with the
HTTP status and response body), `2` reserved for precondition failures raised
before seeding starts.

## Seed order

The order follows the service dependency graph; later steps reference IDs
returned by earlier ones:

1. **supplier** — activity sectors, suppliers, supplier→sector assignments
2. **user** — operators
3. **product** — product categories, products (with category assignment)
4. **inventory** — stocks, then product→stock assignments via the legacy
   `PUT /produits/{productId}/stock/{stockId}` compatibility route
5. **procurement** — invoices with lines; amounts and product snapshots are
   computed by the service, not copied from the seed file
6. **payment** — settlements per invoice

## Notes

- The `amount`/`total` fields in `factures.json` are informational only. The
  Procurement service recalculates line and header amounts from the stored
  product prices, so values shown in the H2/Flyway-era docs may differ from
  the computed totals (the script prints both).
- Product responses intentionally report `stockId: null`; stock membership is
  owned by the Inventory service and is visible through its assignment table.
- Do not use this script against production data. It is a development/demo
  seeding tool.
