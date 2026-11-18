# Achat Purchasing and Stock Management

This repository contains an Angular application, the legacy Spring Boot
monolith, and six independently built services (product, inventory, supplier,
user, procurement, payment) behind a Config Server, Eureka and an API Gateway.
The system manages products, stock, suppliers, supplier invoices, operators,
and invoice settlements; it is not a customer e-commerce application.

> **Current integration status:** all six services are routed through the API
> Gateway (`/api/produits`, `/api/stocks`, `/api/fournisseurs`,
> `/api/operateurs`, `/api/facture`, `/api/reglements`), including the legacy
> product→stock assignment route. `/api/**` paths not claimed by a service
> still fall back to the monolith, which is kept as a rollback path until the
> migration is validated. Config Server serves `config-repo/` in native mode
> (no nested Git repository needed); restart config clients after changing it.
>
> **Linux/macOS quick start:** [docs/RUN-LOCAL.md](docs/RUN-LOCAL.md) — or:
>
> ```bash
> scripts/run-stack.sh start     # MySQL + config + Eureka + gateway + 6 services
> scripts/run-stack.sh reset     # wipe and seed the demo dataset
> scripts/run-stack.sh status    # per-component health
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
| Monolith (fallback) | `backend/` | 8089 | Not-yet-migrated routes, rollback |
| Angular UI | `frontend/` | 4200 | Browser UI (proxies `/api` to 8080) |

Services and infrastructure use Java 21, Spring Boot 4.0.9, and Spring Cloud
2025.1.4. The monolith remains on Spring Boot 4.1.1. Each Maven application
has an independent build.

## Prerequisites

- Java 21.
- Docker Desktop with Docker Compose **or** a local MySQL 8+ instance.
- Node.js 22.12+ (or 20.19+ / 24+) and npm for Angular.
- Git, network access to Maven Central, and (for first-time setup) access to
  download Maven Wrapper dependencies.

Do not commit passwords, tokens, private keys, `.env` files, production
connection strings, or real personal/financial data.

## Standard local run: Angular → Gateway → Product Service and monolith

Use this path to run the current local routing end-to-end. Product and
Category CRUD requests go to Product Service; all other API paths go to the
monolith. Open separate PowerShell terminals for the processes that must keep
running.

### 1. Start MySQL

From the repository root, use the backend's optional MySQL Compose service:

```powershell
Push-Location .\backend
docker compose up -d mysql
Pop-Location
```

Alternatively, start a local MySQL server and create a development database
and least-privilege account. The application database URL, username, and
password must match the database you provisioned.

The Compose file defaults to database `mydatabase` and initializes the MySQL
root password from `DB_PASSWORD` (or its development fallback). The monolith's
application defaults target a different database name/user. **Configure them
to match; do not assume the defaults agree.** To use Compose, create
`backend/.env` from the local example and set `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`, `MYSQL_DATABASE`, and `APP_SEED_ENABLED` consistently. Keep the
file local and uncommitted:

```powershell
Copy-Item .\backend\.env.example .\backend\.env
notepad .\backend\.env
```

Set `APP_SEED_ENABLED=true` only when you want the monolith's development seed
data in a disposable development database. Otherwise use `false`. Hibernate
schema `update` is a monolith development behavior; do not use this setup as
a production migration procedure.

### 2. Start the monolith

In a new PowerShell terminal:

```powershell
Push-Location .\backend
.\mvnw.cmd spring-boot:run
```

The monolith should listen on `http://localhost:8089`. Check its health at
`http://localhost:8089/actuator/health`.

### 3. Check the configuration repository

Config Server serves the YAML files in `config-repo/` directly (native mode);
no nested Git repository is required. Verify the folder contains the files
your applications need, including `api-gateway.yml` and `product-service.yml`;
**do not put secrets in these files.** Config clients load settings at
startup; after changing configuration, restart Config Server and the affected
clients, then verify the served configuration. The default search location is
relative to the Config Server working directory, so start it from
`infrastructure/config-server/`; see
[Infrastructure Foundation](infrastructure/README.md) for details.

### 4. Start Config Server

In a new PowerShell terminal:

```powershell
Push-Location .\infrastructure\config-server
..\mvnw.cmd spring-boot:run
```

Wait for the application to report that it has started. Verify a configuration
document is served:

```powershell
Invoke-RestMethod http://localhost:8888/api-gateway/default
Invoke-RestMethod http://localhost:8888/product-service/default
```

Config Server defaults to port `8888`. It must be available before Eureka and
Gateway because those applications import configuration from it.

### 5. Start Eureka

In a new PowerShell terminal:

```powershell
$env:CONFIG_SERVER_URL = "http://localhost:8888"
Push-Location .\infrastructure\discovery-server
..\mvnw.cmd spring-boot:run
```

Eureka defaults to port `8761`. Open `http://localhost:8761/` to see its
dashboard.

### 6. Start the API Gateway

In a new PowerShell terminal:

```powershell
$env:CONFIG_SERVER_URL = "http://localhost:8888"
$env:EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE = "http://localhost:8761/eureka/"
$env:MONOLITH_BASE_URL = "http://localhost:8089"
Push-Location .\infrastructure\api-gateway
..\mvnw.cmd spring-boot:run
```

Gateway defaults to port `8080`. The current local Gateway configuration
routes Product and Category paths through Eureka to Product Service, while
other API paths fall back to the monolith. Start Product Service before
requesting those Product paths. Smoke-test Product routing and the monolith
fallback separately:

```powershell
curl.exe -i http://localhost:8080/api/produits
curl.exe -i http://localhost:8080/api/stocks
```

`/api/produits` requires Product Service to be registered in Eureka;
`/api/stocks` continues to use the monolith. Both routes remove `/api` exactly
once.

### 7. Start Angular

In a new PowerShell terminal:

```powershell
Push-Location .\frontend
npm install
npm start
```

Open `http://localhost:4200`. `angular.json` loads `proxy.conf.json`, which
forwards `/api/**` unchanged to `http://localhost:8080`. Start only one
Angular dev server for this folder at a time.

## Start Product Service

Product Service is an independent application with its own MySQL database.
Because the current local Gateway config routes Product and Category requests
to it, start this service before using those parts of the UI. **Do not point
it at the monolith database.**

1. Provision an empty MySQL database named for Product Service and a dedicated
   database account. No source data migration is performed by startup.
2. Verify that `product-service.yml` in `config-repo` contains appropriate
   local connection settings and contains no committed credentials. Supply
   database secrets through local environment variables.
3. In a new PowerShell terminal, configure and start the service:

```powershell
$env:CONFIG_SERVER_URL = "http://localhost:8888"
$env:EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE = "http://localhost:8761/eureka/"
$env:PRODUCT_SERVICE_PORT = "8082"
$env:PRODUCT_DB_URL = "jdbc:mysql://localhost:3306/product_db?createDatabaseIfNotExist=true&serverTimezone=UTC"
$env:PRODUCT_DB_USERNAME = "<your-local-product-db-user>"
$env:PRODUCT_DB_PASSWORD = "<your-local-product-db-password>"
Push-Location .\product-service
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

Flyway creates Product-owned tables in the Product database; it does not
create the database/account or migrate monolith data. Check
`http://localhost:8082/actuator/health` and the Eureka dashboard. API docs are
available at `http://localhost:8082/swagger-ui.html`.

Current Product Service limitations: search is not implemented; MySQL migration
integration validation and source data transfer are still pending. Stock
assignment is owned by Inventory Service (`PUT /api/produits/{id}/stock/{stockId}`
is routed there); Inventory pushes the resulting `stockId` into Product
Service's read projection, so Product responses expose it. The internal
projection endpoint (`PUT /internal/v1/products/{id}/stock`) is deliberately
not routed by the Gateway.

## Seed demo data

With the full stack running and empty service databases, populate the demo
dataset through the Gateway (suppliers → operators → products → stocks →
invoices → settlements):

```bash
python3 scripts/seed-demo.py            # add --dry-run to preview
```

See [scripts/README.md](scripts/README.md) for details.

## Build and test

Run each project's independent verification from its own folder:

```powershell
Push-Location .\backend
.\mvnw.cmd clean verify
Pop-Location

Push-Location .\infrastructure
.\mvnw.cmd clean verify
Pop-Location

Push-Location .\product-service
.\mvnw.cmd clean verify
Pop-Location

Push-Location .\frontend
npm run build
npm test
Pop-Location
```

Tests that use Testcontainers require a working Docker Engine. If Docker is
unavailable, report the skipped/failed container tests; a successful compile
alone does not verify database migrations or service startup.

## Common troubleshooting

| Symptom | Check |
|---|---|
| Monolith cannot connect to MySQL | Confirm MySQL is ready, port 3306 is free, and `backend/.env` database name/user/password match the provisioned database. |
| Config Server cannot find configuration or missing YAML | Confirm the YAML files exist in `config-repo/` and the Config Server is started from `infrastructure/config-server/` (the search location is relative to the working directory). |
| Eureka or Gateway cannot start | Start Config Server first; verify `CONFIG_SERVER_URL`, and confirm ports 8761/8080 are available. |
| Gateway returns 404/503 | Check route configuration served by Config Server, monolith health on 8089, `MONOLITH_BASE_URL`, and whether a Product route requires Product Service registration. |
| Frontend cannot retrieve API data | Confirm monolith, Config Server, Eureka, Gateway, and Angular are running in that order; check proxy target 8080 and the browser network request under `/api`. |
| Maven cannot resolve dependencies | Check Java 21, network access to Maven Central, then rerun the wrapper command from the project's folder. |
| Angular port is occupied | Stop the other dev server or use `npm start -- --port 4300`. |

Host-local `localhost` defaults are not valid between containers. No Docker
network or production deployment is provided by this repository. Do not
expose Config Server, Eureka, Gateway, actuator endpoints, or development
databases publicly; production security and deployment configuration require
separate approval.

## More detailed documentation

- [Team development guide](TEAM-DEVELOPMENT-GUIDE.md)
- [Infrastructure startup and Gateway details](infrastructure/README.md)
- [Monolith setup and API limitations](backend/README.md)
- [Angular setup and troubleshooting](frontend/README.md)
- [Product Service setup and migration status](product-service/README.md)
- [Migration architecture and validation documents](docs/migration/)
