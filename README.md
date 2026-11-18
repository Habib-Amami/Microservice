# Achat Purchasing and Stock Management

This repository contains an Angular application and six independently built
services (product, inventory, supplier, user, procurement, payment) behind a
Config Server, Eureka and an API Gateway. The system manages products, stock,
suppliers, supplier invoices, operators, and invoice settlements; it is not a
customer e-commerce application.

> **Current integration status:** all six services are routed through the API
> Gateway (`/api/produits`, `/api/stocks`, `/api/fournisseurs`,
> `/api/operateurs`, `/api/facture`, `/api/reglements`), including the legacy
> product→stock assignment route. Unmatched `/api/**` paths and `/api/internal/**`
> are rejected with `404`; there is no fallback application. Config Server serves
> `config-repo/` in native mode (no nested Git repository needed); restart config
> clients after changing it.
>
> **Linux/macOS quick start:** [docs/RUN-LOCAL.md](docs/RUN-LOCAL.md) — or:
>
> ```bash
> scripts/run-stack.sh start     # MySQL + config + Eureka + gateway + 6 services
> scripts/run-stack.sh reset     # wipe and seed the demo dataset
> scripts/run-stack.sh status    # per-component health
> ```
>
> **One-command demo with GUI, tracing, metrics and logs:** [docs/DOCKER-COMPOSE.md](docs/DOCKER-COMPOSE.md)
>
> ```bash
> scripts/compose-stack.sh up      # everything, including Grafana + Jaeger + Loki
> scripts/compose-stack.sh seed    # canonical demo dataset
> scripts/compose-stack.sh urls    # all UI URLs
> ```

## Applications and local ports

| Component | Folder | Default port | Purpose |
|---|---|---:|---|
| MySQL | Docker (see runbook) or local install | 3307 / 3306 | Service databases |
| Config Server | `infrastructure/config-server/` | 8888 | Native config from `config-repo/` |
| Eureka Discovery Server | `infrastructure/discovery-server/` | 8761 | Service registry |
| API Gateway | `infrastructure/api-gateway/` | 8080 | Single entry point for `/api/**` |
| Product Service | `product-service/` | 8082 | Products, product categories |
| Inventory Service | `inventory-service/` | 8083 | Stocks, product→stock links |
| Supplier Service | `supplier-service/` | 8084 | Suppliers, activity sectors |
| User Service | `user-service/` | 8085 | Operators |
| Procurement Service | `procurement-service/` | 8086 | Invoices and lines |
| Payment Service | `payment-service/` | 8087 | Settlements, recovery rate |
| Angular UI | `frontend/` | 4200 | Browser UI (proxies `/api` to 8080) |

All applications use Java 21, Spring Boot 4.0.9, and Spring Cloud 2025.1.4.
Each Maven application has an independent build.

## Prerequisites

- Java 21.
- Docker Desktop with Docker Compose **or** a local MySQL 8+ instance.
- Node.js 22.12+ (or 20.19+ / 24+) and npm for Angular.
- Git, network access to Maven Central, and (for first-time setup) access to
  download Maven Wrapper dependencies.

Do not commit passwords, tokens, private keys, `.env` files, production
connection strings, or real personal/financial data.

## Standard local run: Angular → Gateway → six services

The supported path is the script, which starts MySQL (Docker), Config Server,
Eureka, the Gateway and all six services, waits for each to become ready, and
writes logs/PIDs under `.run/`:

```bash
scripts/run-stack.sh start          # add --frontend to also run Angular on :4200
scripts/run-stack.sh reset          # wipe the service databases and reseed
scripts/run-stack.sh status         # per-component health
scripts/run-stack.sh stop           # stop everything except MySQL
```

For a manual start (one terminal per component, in order), see
[docs/RUN-LOCAL.md, section 4](docs/RUN-LOCAL.md#4-manual-start-without-the-script).
The essential order is:

1. **MySQL** — the script starts `stack-mysql` on port 3307; each service gets
   its own database (`product_db`, `inventory_db`, `supplier_db`, `user_db`,
   `procurement_db`, `payment_db`).
2. **Config Server** — start from `infrastructure/config-server/` so the
   relative `config-repo/` search location resolves. It must be up before any
   client. Verify with `curl http://localhost:8888/api-gateway/default`.
3. **Eureka** — set `CONFIG_SERVER_URL=http://localhost:8888` and start from
   `infrastructure/discovery-server/`; dashboard at `http://localhost:8761/`.
4. **Gateway** — set `CONFIG_SERVER_URL` and
   `EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE=http://localhost:8761/eureka/`, then
   start from `infrastructure/api-gateway/`. It listens on 8080.
5. **Six services** — start each from its own folder with its DB URL/user/
   password plus the shared Config Server and Eureka variables (exact commands
   in the runbook). Registration in Eureka completes shortly after startup.
6. **Angular** — `cd frontend && npm start`. `proxy.conf.json` forwards
   `/api/**` unchanged to `http://localhost:8080`. Run only one `ng serve` for
   this folder at a time.

Check that `curl http://localhost:8080/api/produits` returns `200` before using
the UI. Both the Gateway and the Angular proxy preserve `/api`; the Gateway
removes it exactly once when forwarding to a service.

## Docker Compose: full stack + observability

For a complete demo, [docs/DOCKER-COMPOSE.md](docs/DOCKER-COMPOSE.md) describes
the additive Compose stack: MySQL, Config Server, Eureka, Gateway, six services,
the Angular UI, **Jaeger** (distributed tracing), **Prometheus + Grafana**
(metrics dashboard) and **Loki + Promtail** (logs). SonarQube and Nexus are
available behind the `quality` profile, with `scripts/sonar-scan.sh` and
`scripts/publish-nexus.sh` for analysis and snapshot publishing.

```bash
scripts/run-stack.sh stop                 # free the ports used by the host flow
docker stop stack-mysql 2>/dev/null || true
scripts/compose-stack.sh up               # build and start all default services
scripts/compose-stack.sh seed             # load the demo dataset
scripts/compose-stack.sh quality          # optional: SonarQube + Nexus
scripts/compose-stack.sh status
scripts/compose-stack.sh urls
```

Demo UIs: Angular `http://localhost:4200`, Eureka `:8761`, Grafana `:3000`,
Jaeger `:16686`, Prometheus `:9090`, SonarQube `:9000` and Nexus `:8081`.
Grafana and SonarQube default to `admin`/`admin`; see the runbook for Nexus
first-login instructions and for generating a SonarQube token.

## Start Product Service

Product Service is an independent application with its own MySQL database.
Start it before using product/category parts of the UI. **Do not point it at
another service's database.**

1. Provision an empty MySQL database named for Product Service and a dedicated
   database account. No source data migration is performed by startup.
2. Verify that `product-service.yml` in `config-repo` contains appropriate
   local connection settings and contains no committed credentials. Supply
   database secrets through local environment variables.
3. In a new terminal, configure and start the service (see the runbook for the
   equivalent bash commands):

```powershell
$env:CONFIG_SERVER_URL = "http://localhost:8888"
$env:EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE = "http://localhost:8761/eureka/"
$env:PRODUCT_SERVICE_PORT = "8082"
$env:PRODUCT_DB_URL = "jdbc:mysql://localhost:3307/product_db?createDatabaseIfNotExist=true&serverTimezone=UTC"
$env:PRODUCT_DB_USERNAME = "<your-local-product-db-user>"
$env:PRODUCT_DB_PASSWORD = "<your-local-product-db-password>"
Push-Location .\product-service
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

Flyway creates Product-owned tables in the Product database; it does not
create the database/account or migrate legacy data. Check
`http://localhost:8082/actuator/health` and the Eureka dashboard. API docs are
available at `http://localhost:8082/swagger-ui.html`.

Current Product Service limitations: search is not implemented. Creating a
product requires a `stockId`: Product Service validates the stock with
Inventory and asks Inventory to register the assignment before returning `201`,
so no stockless product can be created through the API. Stock assignment itself
is still owned by Inventory Service
(`PUT /api/produits/{id}/stock/{stockId}` is routed there); Inventory pushes the
resulting `stockId` into Product Service's read projection, so Product
responses expose it. The internal projection endpoint
(`PUT /internal/v1/products/{id}/stock`) is deliberately not routed by the
Gateway.

## Seed demo data

With the full stack running and empty service databases, populate the demo
dataset through the Gateway (suppliers → operators → stocks → products →
invoices → settlements):

```bash
python3 scripts/seed-demo.py            # add --dry-run to preview
```

See [scripts/README.md](scripts/README.md) for details.

## Build and test

Run each project's independent verification from its own folder:

```powershell
foreach ($m in @('infrastructure','product-service','inventory-service',
                 'supplier-service','user-service','procurement-service',
                 'payment-service')) {
  Push-Location $m; .\mvnw.cmd clean verify; Pop-Location
}
Push-Location .\frontend
npm run build
npm test
Pop-Location
```

On Linux/macOS use `./mvnw -B verify` in each Maven folder. CI runs the same
seven Maven modules plus the frontend on every push. Tests that use
Testcontainers require a working Docker Engine; a successful compile alone
does not verify database migrations or service startup.

## Common troubleshooting

| Symptom | Check |
|---|---|
| Config Server cannot find configuration or missing YAML | Confirm the YAML files exist in `config-repo/` and the Config Server is started from `infrastructure/config-server/` (the search location is relative to the working directory). |
| Eureka or Gateway cannot start | Start Config Server first; verify `CONFIG_SERVER_URL`, and confirm ports 8761/8080 are available. |
| Gateway returns 404 | Check route configuration served by Config Server and whether the target service is registered in Eureka. Unmatched `/api/**` paths are intentionally rejected with `404`. |
| Gateway returns 503 | Check that the owning service is up and registered; Feign/LoadBalancer caches may need a few seconds after a restart. |
| `400` on `POST /api/produits` | Creation requires `stockId`; add an existing stock id to the payload. |
| Frontend cannot retrieve API data | Confirm Config Server, Eureka, Gateway and the six services are up; check the proxy target 8080 and the browser network request under `/api`. |
| Maven cannot resolve dependencies | Check Java 21, network access to Maven Central, then rerun the wrapper command from the project's folder. |
| Angular port is occupied | Stop the other dev server or use `npm start -- --port 4300`. |

Host-local `localhost` defaults are not valid between containers: the Compose
stack overrides them with container DNS names (`mysql`, `config-server`, `api-gateway`,
…). All of the Docker material here is for **local demos only**. Do not expose
Config Server, Eureka, Gateway, actuator endpoints, or development databases
publicly; production security and deployment configuration require separate
approval.

## More detailed documentation

- [Team development guide](TEAM-DEVELOPMENT-GUIDE.md)
- [Local run and test runbook](docs/RUN-LOCAL.md)
- [Docker Compose demo stack, observability, SonarQube and Nexus](docs/DOCKER-COMPOSE.md)
- [Microservices implementation status](docs/MICROSERVICES-IMPLEMENTATION-STATUS.md)
- [Infrastructure startup and Gateway details](infrastructure/README.md)
- [Angular setup and troubleshooting](frontend/README.md)
- [Per-service setup notes](product-service/README.md)
- [Migration architecture and validation documents](docs/migration/README.md) —
  historical design/baseline records; the legacy monolith they describe was
  removed after migration.
