# Microservices Implementation Status

**Evidence date:** 2026-11-20

**Scope:** All six business services are implemented and routed through the
API Gateway; the legacy monolith has been removed from the repository. The
current stack also includes three cross-cutting capabilities:

- **Resilience4j** on the Product → Inventory calls (retry, rate limiter,
  circuit breaker and typed fallbacks), with an idempotency key on the retried
  write, plus an inbound Resilience4j rate limiter at the Gateway.
- **CQRS/Axon** inside Procurement (aggregate, commands, events, projector,
  REST command/query API, Axon Server in Compose).
- **Zipkin** distributed tracing, replacing Jaeger, plus Prometheus/Grafana
  and Loki/Promtail in the default Compose profile.

**Current validation:** 88 Maven tests across seven modules and 6 frontend
tests pass. The full Compose stack was built and exercised end to end: 27/27
gateway smoke checks, 10/10 Prometheus targets up, Zipkin receiving spans from
all nine instrumented services, and the CQRS command/query flow returning
`202/200/200/204/409/404` as designed. The gateway limiter allowed 20 requests
in a one-second window and rejected the next 10 with `429` and `X-RateLimit-*`
headers; the inventory idempotency flow replayed a repeated key and returned
`409` for a conflicting reuse. See
[docs/DOCKER-COMPOSE.md](DOCKER-COMPOSE.md) for the Compose runbook and
[docs/RUN-LOCAL.md](RUN-LOCAL.md) for the host-based flow.

**Migration/cutover:** No production data was copied. Local route, Compose and
smoke results are not evidence of source-data parity, production readiness, or
an accepted cutover.

The target architecture and standards that still govern unresolved decisions
are in
[`migration/02-revised-target-architecture.md`](migration/02-revised-target-architecture.md)
and [`migration/07-microservices-development-standards.md`](migration/07-microservices-development-standards.md).
Those documents are a **historical design record**; where they say CQRS/Axon,
Resilience4j or tracing is "pending" or "proposed", this status page is the
authoritative current state.

## Service matrix

| Service | Local port / Eureka ID | Logical database | Implemented endpoints / scope | Dependencies | Build and test evidence |
|---|---|---|---|---|---|
| Product | `8082` / `product-service` | `product_db` | `GET/POST /produits`, `GET/PUT/DELETE /produits/{id}`, `GET/POST /categories-produits`, `GET/PUT/DELETE /categories-produits/{id}`. `POST /produits` requires `stockId` (`400` when missing): Product validates the stock with Inventory, creates the catalog row, then registers the assignment before responding (`404` unknown stock, `503` unavailable Inventory). All Inventory calls go through `InventoryResilienceGateway` (3 attempts/500 ms retry, 5 req/s rate limit, circuit breaker, typed fallback); the assignment sends a freshly generated UUID `Idempotency-Key` so retries of a lost response replay safely, and a failed assignment rolls the catalog row back. Product stores the `stockId` pushed by Inventory as a read projection. | MySQL + Flyway; Config Client; Eureka Client; Feign/LoadBalancer to Inventory through the Resilience4j gateway. | `./mvnw -B -ntp verify` — **31 run, 0 failed**. |
| Inventory | `8083` / `inventory-service` | `inventory_db` | `/stocks/**` CRUD, `PUT /produits/{productId}/stock/{stockId}` and `DELETE /produits/{productId}/stock` (unassign). The assignment route accepts an optional `Idempotency-Key`: a repeated key for the same product/stock replays the result without re-writing or re-pushing the projection, and reuse for a different assignment returns `409`. After saving/clearing an assignment it pushes the `stockId` (or null) to Product's internal projection endpoint (best-effort; failures are logged). Stock deletion drops stale assignments and still returns `409` for live assignments. Advanced positions, movements, reservations and receiving are not implemented. | MySQL + Flyway V1–V2 (V2: `assignment_idempotency_keys` with a unique key); Config Client; Eureka Client; Feign/LoadBalancer to Product. | `./mvnw -B -ntp verify` — **26 run, 0 failed**. |
| Supplier | `8084` / `supplier-service` | `supplier_db` | `/fournisseurs/**`; `/secteurs-activite/**`; supplier/sector CRUD and sector assignment; `GET /internal/v1/suppliers/{supplierId}` reference lookup. | MySQL + Flyway; Config Client; Eureka Client; no outgoing Feign. | `./mvnw -B -ntp verify` — **6 run, 0 failed**. |
| User | `8085` / `user-service` | `user_db` | `/operateurs/**` profile CRUD; `GET /internal/v1/operators/{operatorId}` reference lookup. Passwords are stored as encoded verifiers and never returned. `factureIds` remains in the legacy response but is empty. No login/JWT/authorization implementation. | MySQL + Flyway; Config Client; Eureka Client; Spring Security Crypto; no outgoing Feign. | `./mvnw -B -ntp verify` — **3 run, 0 failed**. |
| Procurement | `8086` / `procurement-service` | `procurement_db` | Legacy invoice paths, archive, query by supplier, operator assignment, product snapshots and typed `detailsFacture` responses. **CQRS API under `/cqrs/factures`** (gateway path `/api/cqrs/factures/**`): `POST` dispatches `CreateInvoiceCommand` → `InvoiceCreatedEvent` → projector → `202 {id}`; `GET` and `GET /{id}` answer `FindAllInvoicesQuery` / `FindInvoiceByIdQuery` from the read model; `PUT /{id}/archive` dispatches `ArchiveInvoiceCommand` → `InvoiceArchivedEvent` (`204`, `409` if already archived, `404` if unknown). Internal APIs: `GET /internal/v1/invoices/{id}/payment-eligibility`, `GET /internal/v1/invoices/total`. Recovery-rate path is routed to Payment at the Gateway. | MySQL + Flyway V1–V4 (V3 projections, V4 Axon JPA tables); Axon Server in Compose (`axon.axonserver.enabled=true`), in-memory store for host/tests; Config Client; Eureka Client; Feign/LoadBalancer to Product, Supplier, User. No Procurement→Payment or Procurement→Inventory call. | `./mvnw -B -ntp verify` — **9 run, 0 failed** (aggregate test + end-to-end CQRS endpoint test + legacy contract/runtime tests). |
| Payment | `8087` / `payment-service` | `payment_db` | `/reglements/**` settlement list/detail/create/update/delete, invoice lookup and revenue; writes validate invoice existence/archive state via Procurement. Legacy `/facture/pourcentageRecouvrement/{startDate}/{endDate}` is served by Payment. `GET /internal/v1/settlements/revenue` remains available. | MySQL + Flyway; Config Client; Eureka Client; OpenFeign/LoadBalancer to Procurement for eligibility and active invoice totals. One-way dependency. | `./mvnw -B -ntp verify` — **6 run, 0 failed**. |

All modules target Java **21**, Spring Boot **4.0.9** and Spring Cloud
**2025.1.4**. Each service has an independent Maven POM, wrapper, README,
`application.properties`, Config Server document, Flyway migration(s) and
tests. The infrastructure reactor (`infrastructure/`) contains Config Server,
Discovery Server and API Gateway.

All six services expose Springdoc OpenAPI/Swagger UI at `/swagger-ui.html` on
ports 8082–8087. A committed [Postman collection](../postman/README.md) covers
the Gateway routes, the `stockId` validation and the CQRS command/query flow.
Load balancing between replicas is verified with
`scripts/compose-stack.sh lb` and the `compose.lb.yml` overlay; see
[docs/DOCKER-COMPOSE.md](DOCKER-COMPOSE.md#41-load-balancing-test).

## Config Server and Gateway

Config documents are in `config-repo/`:

| Application | Config document | Notable settings |
|---|---|---|
| Product | `product-service.yml` | `product_db`; Feign connect/read timeouts; `resilience4j.retry` (3 attempts/500 ms), `resilience4j.ratelimiter` (5/1s), `resilience4j.circuitbreaker`. |
| Inventory | `inventory-service.yml` | `inventory_db`; Product Feign timeouts. |
| Supplier | `supplier-service.yml` | `supplier_db`; credential values are environment placeholders. |
| User | `user-service.yml` | `user_db`; credential values are environment placeholders. |
| Procurement | `procurement-service.yml` | `procurement_db`; Product/Supplier/User Feign timeouts. `application.properties` adds the Jackson Axon serializer, subscribing `invoiceProjection` processor and Axon Server toggle. |
| Payment | `payment-service.yml` | `payment_db`; Procurement Feign timeouts. |
| Shared | `application.yml` | Zipkin exporter (opt-in via `TRACING_EXPORT_ENABLED` + `ZIPKIN_ENDPOINT`) and common health/metrics exposure. |

`config-repo/api-gateway.yml` has explicit routes with `StripPrefix=1`:

| Gateway path / method | Eureka destination |
|---|---|
| `/api/stocks/**` | `INVENTORY-SERVICE` |
| `PUT /api/produits/*/stock/*`, `DELETE /api/produits/*/stock` | `INVENTORY-SERVICE` |
| `/api/produits/**`, `/api/categories-produits/**` | `PRODUCT-SERVICE` |
| `/api/cqrs/factures/**` | `PROCUREMENT-SERVICE` |
| `/api/fournisseurs/**`, `/api/secteurs-activite/**` | `SUPPLIER-SERVICE` |
| `/api/operateurs/**` | `USER-SERVICE` |
| `/api/facture/pourcentageRecouvrement/**` (more-specific route) | `PAYMENT-SERVICE` |
| `/api/facture/**` | `PROCUREMENT-SERVICE` |
| `/api/reglements/**` | `PAYMENT-SERVICE` |
| `/api/internal/**` and any other `/api/**` | rejected with `404` |

A `RequestRateLimiter` **default filter** applies to every route above:
`ClientIpKeyResolver` buckets requests by client address, `InboundRateLimiter`
uses a Resilience4j token bucket from `gateway.rate-limit` (`limit-for-period`,
default `20`, and `limit-refresh-period`, default `1s`, overridable with
`GATEWAY_RATE_LIMIT`), and rejected requests get `429` plus
`X-RateLimit-Limit`, `X-RateLimit-Remaining` and `Retry-After` headers. No Redis
or external proxy manager is needed. The buckets live in gateway memory, so
they are per-replica (not shared across gateway instances) and reset on
restart; this is edge protection for the demo, not a distributed quota. It
complements but does not replace authentication (see the gaps below).

Discovery locator remains disabled. Live smoke checks cover all routed path
families; see [docs/RUN-LOCAL.md](RUN-LOCAL.md). In particular, `/operateurs`
has no approved authentication policy and Payment APIs lack authorization and
settlement amount-limit/idempotency controls; their routes are not
production-safe.

## Observability and event store (Compose)

| Component | Endpoint | Purpose |
|---|---|---|
| Zipkin | `:9411` | Spans from Gateway, Config Server, Discovery Server and all six services. |
| Axon Server | `:8024` UI / `:8124` gRPC | Command/event/query routing for Procurement CQRS; standalone mode creates the `default` context on a fresh data volume. |
| Prometheus | `:9090` | 10/10 scrape targets up in the validated stack. |
| Grafana | `:3000` | Dashboards over Prometheus; datasource points at Zipkin for traces. |
| Loki + Promtail | — | Log aggregation. |

The host-based flow in `scripts/run-stack.sh` has no tracing server or Axon
Server: export is disabled and Procurement uses the in-memory buses.

## Test results

| Module | Command | Tests |
|---|---|---:|
| infrastructure | `./mvnw -B -ntp verify` | 7 |
| product-service | `./mvnw -B -ntp verify` | 31 |
| inventory-service | `./mvnw -B -ntp verify` | 26 |
| supplier-service | `./mvnw -B -ntp verify` | 6 |
| user-service | `./mvnw -B -ntp verify` | 3 |
| procurement-service | `./mvnw -B -ntp verify` | 9 |
| payment-service | `./mvnw -B -ntp verify` | 6 |
| **Maven total** | | **88 passed** |
| frontend | `npm ci && npm run build && npm test -- --watch=false` | 6 passed (2 files) |

Compose verification: `docker compose config --quiet` passed; 27/27 backend
smoke checks passed after `scripts/seed-demo.py --force`; all default-profile
containers healthy; Zipkin listed all nine instrumented services; Axon Server
contained the `default` context; Resilience4j metrics were exposed on Product's
`/actuator/metrics`. The load-balancing check scaled `product-service` to two
replicas, confirmed 2 Eureka registrations and a 10/10 split of 20 tagged GETs
across the replicas, then restored the single-instance stack.
`compose.lb.yml` validated with `docker compose config`.

Additional checks after the hardening work: 30 rapid gateway requests yielded
20 `200` + 10 `429` with `X-RateLimit-Limit: 20`; a product creation wrote a UUID
row to `inventory_db.assignment_idempotency_keys`; replaying the same key on
`PUT /produits/{id}/stock/{stockId}` returned `200` without a new row, and
reusing it for another stock returned `409`.

## Known gaps and cutover blockers

1. **No production data migration:** Source MySQL DDL, physical table/join
   names, data quality, ID backfill, reconciliation, source write-freeze,
   rollback and deployment secrets are not verified or performed.
2. **Authentication/authorization:** User login/token issuance, JWT/JWKS,
   roles/permissions, lockout, service identity and protected-resource
   enforcement do not exist. Do not expose User/Payment or other sensitive
   writes publicly before security approval.
3. **Procurement completeness:** CQRS/Axon is implemented for create/archive,
   but the draft/submit/approve/reject/cancel lifecycle, approval policy,
   audit trail, purchase-order receiving linkage and migration of legacy
   invoice-line data are not.
4. **Axon operations undocumented:** Backup, replay, retention, schema
   evolution and event-store ownership for Axon Server have not been decided
   or documented. Cross-service durable event publication (outbox/broker)
   remains an open architecture decision.
5. **Cross-service contract readiness:** Feign calls have caller-side H2 tests
   with providers mocked, but lack live provider-consumer contract tests,
   authentication and runtime Eureka verification. Procurement→Inventory
   remains deferred because the receipt/quantity-adjustment contract is not
   approved.
6. **Payment correctness:** Settlement writes fail closed for
   missing/archived invoices and when Procurement is unavailable. The
   eligibility rule does not validate outstanding balance; there is no
   idempotency key, reconciliation/reversal, durable invoice lifecycle
   projection or archived-settlement exclusion in revenue. Amounts remain
   `float`, with no currency policy.
7. **Supplier performance:** Metrics and projections are not implemented;
   source facts, definitions and freshness policies remain undecided.

Do not treat green standalone builds, Compose smoke results or local route
configuration as data parity, production readiness, service acceptance, or
authorization to switch the legacy application's authoritative writes to the
new services.
