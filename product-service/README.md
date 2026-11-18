# Product Service

Standalone Product and Product Category service. It is intentionally not a
module of `infrastructure/pom.xml`.

## Runtime requirements

- Java 21
- MySQL with an empty, provisioned `product_db` database
- Config Server on port 8888, with the committed `product-service.yml`
  available in its Git-backed repository
- Eureka Discovery Server on port 8761

The service defaults to port 8082 and registers as `product-service`. Flyway
creates and versions only Product-owned tables. Hibernate is set to validate
the Flyway schema rather than create or update it. Configure
`PRODUCT_DB_URL`, `PRODUCT_DB_USERNAME`, and `PRODUCT_DB_PASSWORD` outside
source control; no credentials are stored in the Config repository.

## Build and run (PowerShell)

```powershell
.\mvnw.cmd clean verify

$env:PRODUCT_DB_URL = "jdbc:mysql://localhost:3306/product_db?createDatabaseIfNotExist=true&serverTimezone=UTC"
$env:PRODUCT_DB_USERNAME = "<set locally; do not commit>"
$env:PRODUCT_DB_PASSWORD = "<set locally; do not commit>"
.\mvnw.cmd spring-boot:run
```

Start Config Server and Eureka before launching the service. Provision the
database and account separately; Flyway does not create databases or users.
Health is available at `/actuator/health`, and API documentation at
`/swagger-ui.html` and `/v3/api-docs`.

## API scope

The service provides the legacy Product CRUD routes under `/produits` and
Category CRUD routes under `/categories-produits`. Search is deferred because
the legacy API has no search contract. Product price remains `float` pending
Finance approval. Product codes are not unique-constrained.

`POST /produits` requires a `stockId`: a product cannot be created without a
stock. The service checks the stock with Inventory Service (`404` if unknown,
`503` if Inventory is unreachable), saves the catalog row, then asks Inventory
to register the assignment through its legacy
`PUT /produits/{productId}/stock/{stockId}` route. If that call fails, the new
catalog row is deleted so no stockless product is left behind (`503`). The
response already carries the assigned `stockId`.

Product responses expose the legacy `stockId` field as a read projection:
Inventory owns the assignment and pushes the id to
`PUT /internal/v1/products/{id}/stock` after saving it. That internal route is
not exposed by the Gateway; the legacy assignment path
`/produits/{productId}/stock/{stockId}` is routed to Inventory. The projection
is best-effort (assignment failures are logged by Inventory, never blocking
the assignment), so it is repaired by re-assigning or reseeding.

Category references are Product-owned and non-cascading. Database constraint
conflicts return HTTP 409. Product deletion cannot detect references in
Inventory or Procurement; define that cross-service policy before any
authoritative data cutover.

## Data migration

No source schema was supplied or inspected, and no data has been transferred.
See [`scripts/migration/README.md`](scripts/migration/README.md) for the
canonical export/import template and required preflight/reconciliation steps.
