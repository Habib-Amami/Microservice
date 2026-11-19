# Postman collection

[`achat-microservices.postman_collection.json`](achat-microservices.postman_collection.json)
is a Postman v2.1 collection covering the main Gateway routes, the Product
`stockId` validation, the CQRS command/query flow and the legacy recovery-rate
route.

## Use it

1. Start and seed the stack:

   ```bash
   scripts/compose-stack.sh up
   scripts/compose-stack.sh seed
   ```

2. Import the JSON file into Postman (**Import → File**).

3. Check the collection variables: `baseUrl` defaults to `http://localhost:8080/api`.
   Import a separate Postman *environment* if you need another host.

4. Suggested order: run **00 - Infrastructure** first, then **01 Catalog** →
   **02 Stock** → **03 Suppliers** → **04 Operators** → **05 Invoices** →
   **06 CQRS** → **07 Settlements**.

The **Create invoice command** request returns `202 { "id": "..." }`. Copy that
id into the `cqrsInvoiceId` variable before running the get/archive requests.
`PUT .../archive` returns `204` the first time and `409` on a repeat, as
documented in [docs/MICROSERVICES-IMPLEMENTATION-STATUS.md](../docs/MICROSERVICES-IMPLEMENTATION-STATUS.md).

Write requests mutate the demo databases. Use `scripts/compose-stack.sh reset`
to start from an empty seeded state.
