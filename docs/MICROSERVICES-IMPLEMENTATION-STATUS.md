# Microservices Implementation Status

**Evidence date:** 2026-11-17

**Scope:** All six business services are implemented and routed through the
API Gateway; the legacy monolith has been removed from the repository. Current
gap work adds Procurement invoice-line snapshots/Product lookup and Payment
invoice eligibility/recovery reporting. Inventory receiving is deferred pending
an approved receipt contract.

**Current validation:** Product creation requires a stock; see Product's row
below. The full stack (Config Server, Eureka, Gateway and six services) is
started by `scripts/run-stack.sh` and validated by the live Gateway smoke flow
in [docs/RUN-LOCAL.md](RUN-LOCAL.md).

**Migration/cutover:** No production data was copied. Local route and smoke
results are not evidence of source-data parity, production readiness, or an
accepted cutover.

The target architecture remains in
[`migration/02-revised-target-architecture.md`](migration/02-revised-target-architecture.md);
development rules are in
[`../TEAM-DEVELOPMENT-GUIDE.md`](../TEAM-DEVELOPMENT-GUIDE.md) and
[`migration/07-microservices-development-standards.md`](migration/07-microservices-development-standards.md).

## Service matrix

| Service | Local port / Eureka ID | Logical database / config database name | Implemented endpoints / scope | Dependencies | Build and test evidence |
|---|---|---|---|---|---|
| Product | `8082` / `product-service` | `product_db` (config default and README agree; the earlier `product_service` naming mismatch is resolved) | `GET/POST /produits`, `GET/PUT/DELETE /produits/{id}`, `GET/POST /categories-produits`, `GET/PUT/DELETE /categories-produits/{id}`. `POST /produits` requires `stockId`: Product validates the stock with Inventory, creates the catalog row, and asks Inventory to register the assignment before responding (`404` unknown stock, `503` unreachable Inventory, `400` missing stock). A failed assignment rolls the catalog row back. Stock assignment remains owned by Inventory; Product stores the `stockId` pushed to `PUT /internal/v1/products/{id}/stock` (not routed by the Gateway) as a read projection, so Product responses expose `stockId`. | MySQL + Flyway; Config Client; Eureka Client; Feign/LoadBalancer to Inventory (stock validation + create-time assignment). | `./mvnw -B test` — **25 run, 0 failed, 0 skipped** (unit + MockMvc contract + Testcontainers MySQL migration test). |
| Inventory | `8083` / `inventory-service` | `inventory_db` | `/stocks/**` CRUD, `PUT /produits/{productId}/stock/{stockId}` and `DELETE /produits/{productId}/stock` (unassign). After saving/clearing an assignment it pushes the `stockId` (or null) to Product Service's internal projection endpoint (best-effort; failures are logged). Stock deletion drops stale assignments for products deleted in Product Service and still returns `409` for live assignments. Advanced inventory positions, movements, reservations, and receiving are not implemented. | MySQL + Flyway; Config Client; Eureka Client; Feign/LoadBalancer to Product (catalog lookup + stock projection). | `./mvnw -B test` — **20 run, 0 failed, 0 skipped**. |
| Supplier | `8084` / `supplier-service` | `supplier_db` | `/fournisseurs/**`; `/secteurs-activite/**`; supplier/sector CRUD and sector assignment; `GET /internal/v1/suppliers/{supplierId}` reference lookup. | MySQL + Flyway; Config Client; Eureka Client; no outgoing Feign. | `Set-Location supplier-service; .\mvnw.cmd -B clean verify` — **6 run, 0 failed, 0 skipped**; CRUD/reference HTTP routes exercised over embedded H2, with Flyway disabled. |
| User | `8085` / `user-service` | `user_db` | `/operateurs/**` profile CRUD; `GET /internal/v1/operators/{operatorId}` reference lookup. Password input is stored as an encoded credential verifier and is never returned. `factureIds` remains in the legacy response but is empty (assignments belong to Procurement). | MySQL + Flyway; Config Client; Eureka Client; Spring Security Crypto; no outgoing Feign. No login/JWT/authorization implementation. | `Set-Location user-service; .\mvnw.cmd -B clean verify` — **3 run, 0 failed, 0 skipped**; CRUD/reference HTTP routes exercised over embedded H2, with Flyway disabled. |
| Procurement | `8086` / `procurement-service` | `procurement_db` | Legacy invoice paths, archive, query by supplier, operator assignment, and typed `detailsFacture` responses. Add accepts optional legacy-shaped lines, looks up Product, calculates line/header amounts, and stores product snapshots. Internal APIs: `GET /internal/v1/invoices/{id}/payment-eligibility`, `GET /internal/v1/invoices/total`. Recovery-rate path is routed to Payment at the Gateway. | MySQL + Flyway V1/V2; Config Client; Eureka Client; Feign/LoadBalancer to Product, Supplier, and User. No Procurement→Payment or Procurement→Inventory call. Remote calls precede local line/header persistence. | `Set-Location procurement-service; .\mvnw.cmd -B clean verify` — **5 run, 0 failed, 0 skipped**; invoice-line persistence/response and missing Product behavior exercised over H2; Feign mocked and Flyway disabled. |
| Payment | `8087` / `payment-service` | `payment_db` | `/reglements/**` settlement list/detail/create/update/delete, invoice lookup and revenue; writes validate invoice existence/archive state via Procurement. Legacy `/facture/pourcentageRecouvrement/{startDate}/{endDate}` is served by Payment. `GET /internal/v1/settlements/revenue` remains available. | MySQL + Flyway; Config Client; Eureka Client; OpenFeign/LoadBalancer to Procurement for eligibility and active invoice totals. One-way dependency; no Procurement Feign client. | `Set-Location payment-service; .\mvnw.cmd -B clean verify` — **6 run, 0 failed, 0 skipped**; create/update, archived/missing/unavailable invoice handling, and reporting exercised over H2 with Procurement Feign mocked and Flyway disabled. |

The four new services all inherit Spring Boot **4.0.9**, target Java **21**,
and import Spring Cloud **2025.1.4**. Each has an independent Maven POM,
wrapper, README, `application.properties`, Config Server document, Flyway
migration(s), and tests; Procurement now includes V2 for invoice lines.
Spring Cloud 2025.1.4's official reference states its supported Spring Boot
version is 4.0.9:
[Spring Cloud 2025.1 reference](https://docs.spring.io/spring-cloud-release/reference/2025.1/index.html).

## Config Server and Gateway

Config documents are in `config-repo/`:

| Application | Config document | Database/configuration notes |
|---|---|---|
| Product | `product-service.yml` | Database default is `product_db`, matching the revised target architecture. Credentials use environment placeholders. |
| Inventory | `inventory-service.yml` | `inventory_db`; Product Feign timeouts configured. |
| Supplier | `supplier-service.yml` | `supplier_db`; credential values are environment placeholders. |
| User | `user-service.yml` | `user_db`; credential values are environment placeholders. |
| Procurement | `procurement-service.yml` | `procurement_db`; Product/Supplier/User Feign connect/read timeouts configured. |
| Payment | `payment-service.yml` | `payment_db`; Procurement Feign connect/read timeouts configured. |

`config-repo/api-gateway.yml` has explicit routes with `StripPrefix=1` for every
public legacy path family:

| Gateway path / method | Eureka destination |
|---|---|
| `/api/stocks/**` | `INVENTORY-SERVICE` |
| `PUT /api/produits/*/stock/*` | `INVENTORY-SERVICE` |
| `/api/produits/**` | `PRODUCT-SERVICE` |
| `/api/categories-produits/**` | `PRODUCT-SERVICE` |
| `/api/fournisseurs/**` | `SUPPLIER-SERVICE` |
| `/api/secteurs-activite/**` | `SUPPLIER-SERVICE` |
| `/api/operateurs/**` | `USER-SERVICE` |
| `/api/facture/pourcentageRecouvrement/**` (more-specific route) | `PAYMENT-SERVICE` |
| `/api/facture/**` | `PROCUREMENT-SERVICE` |
| `/api/reglements/**` | `PAYMENT-SERVICE` |
| `/api/internal/**` | rejected with `404` (never routed) |
| any other `/api/**` | `404` (no fallback route) |

Discovery locator remains disabled. Live smoke checks cover all routed path
families; see [docs/RUN-LOCAL.md](RUN-LOCAL.md). In particular, `/operateurs` has
no approved authentication policy and Payment APIs lack authorization and
settlement amount-limit/idempotency controls; their routes are not
production-safe.

## Test Results

| Service | Command executed | Passed | Failed | Skipped | Notes |
|---|---|---:|---:|---:|---|
| Product | `.\product-service\mvnw.cmd -f .\product-service\pom.xml clean verify` | 11 | 0 | 1 | Skipped Testcontainers/MySQL test; Docker environment unavailable. |
| Inventory | `.\inventory-service\mvnw.cmd -f .\inventory-service\pom.xml clean verify` | 12 | 0 | 0 | Controller/service suite. |
| Supplier | `Set-Location supplier-service; .\mvnw.cmd -B clean verify` | 6 | 0 | 0 | Includes CRUD/reference HTTP probes over embedded H2; Flyway disabled in that test. |
| User | `Set-Location user-service; .\mvnw.cmd -B clean verify` | 3 | 0 | 0 | Includes CRUD/reference HTTP probes over embedded H2; Flyway disabled in that test. |
| Procurement | `Set-Location procurement-service; .\mvnw.cmd -B clean verify` | 5 | 0 | 0 | Includes invoice-line persistence/response, Product 404 handling, and internal invoice queries over H2; Feign mocked, Flyway disabled. |
| Payment | `Set-Location payment-service; .\mvnw.cmd -B clean verify` | 6 | 0 | 0 | Includes settlement eligibility failures and legacy recovery-rate route over H2; Procurement Feign mocked, Flyway disabled. |
| API Gateway | `.\infrastructure\mvnw.cmd -f .\infrastructure\api-gateway\pom.xml -B verify` | 0 | 0 | 0 | Module build passed; no test sources. YAML parses, but no live Gateway/Eureka route probe was run. |

Aggregate: **43 passed, 0 failed, 1 skipped** (44 tests reported). All six
services have successful `clean verify` evidence; Procurement and Payment were
rerun for this gap implementation. The changed Gateway, Payment, and
Procurement YAML files parsed successfully, and `git diff --check` passed.

## Verification boundary

All six standalone projects passed `clean verify` on Java 21. New-service
embedded endpoint tests start each Spring Boot app on a random local port,
create/use an isolated H2 database, and probe all implemented business and
reference HTTP routes. Feign references are mocked, including Product and
Procurement for the new cross-service calls.
Those tests disable Flyway and Eureka and tolerate an unavailable Config
Server; they establish HTTP/JPA wiring, **not** MySQL SQL compatibility,
Flyway correctness, registry integration, or Config Server startup behavior.

Product's existing Testcontainers schema test was skipped because no valid
Docker environment was available. Inventory's suite is controller/service
focused. No MySQL server was available for executing Procurement's new V2
migration or validating the Product/Payment Feign clients against live
providers. The Gateway module compiled, but the Gateway configuration was not
exercised against a running Eureka/service set. Build output included a
Flyway community-metadata HTTP 401 warning from Maven Central metadata
resolution; it did not fail dependency resolution or the builds.

## Known gaps and cutover blockers

1. **No production data migration:** Source MySQL DDL, physical table/join
   names, data quality, ID backfill, row-count/relationship/financial
   reconciliation, source write-freeze, rollback, and deployment secrets are
   not verified or performed.
2. **No MySQL schema verification:** Run every service migration, including
   Procurement V2, on a disposable MySQL database; validate Hibernate
   mappings and CRUD and test upgrades before any cutover.
3. **Product database name mismatch — RESOLVED:** Product config and README now both use `product_db`.
4. **Authentication/authorization:** User login/token issuance, JWT/JWKS,
   roles/permissions, lockout, service identity, and protected-resource
   enforcement do not exist. Do not expose User/Payment or other sensitive
   writes publicly before security approval.
5. **Procurement completeness:** Invoice-line persistence, typed response
   mapping, Product lookup, and Product snapshots are implemented for line
   details submitted to Procurement. Existing legacy invoice-line data has
   not been migrated. Inventory receiving, approval lifecycle, durable
   CQRS/Axon persistence, and durable integration facts remain unimplemented.
6. **Cross-service contract readiness:** Supplier/User/Product and
   Payment/Procurement calls have caller-side H2 tests with Feign mocked, but
   lack live provider-consumer integration tests, authentication, and runtime
   Eureka verification. Procurement→Inventory remains deferred because the
   Inventory API has no approved receipt/quantity-adjustment contract.
7. **Payment correctness:** Settlement writes fail closed for missing or
   archived invoices and when Procurement is unavailable. The eligibility
   rule does not validate outstanding balance; there is no idempotency key,
   reconciliation/reversal, durable invoice lifecycle projection, or archived
   settlement exclusion in revenue. Amounts remain `float`, with no currency
   policy.
8. **Supplier performance:** Metrics and projections are not implemented;
   source facts, definitions, and freshness policies remain undecided.
9. **Operations/integration:** No deployment manifests, production health
   checks, service-authenticated Config Server/Eureka, observability, or
   Gateway runtime contract suite were added.

Do not treat green standalone builds or local route configuration as data
parity, production readiness, service acceptance, or authorization to switch
the legacy application's authoritative writes to the new services.
