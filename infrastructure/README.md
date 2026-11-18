# Infrastructure Foundation

The independent Maven reactor contains Config Server, Eureka Discovery
Server, and API Gateway. The six business services are separate Maven projects
that register here and are reached through the Gateway.

## Project structure

```text
infrastructure/
  pom.xml
  config-server/
  discovery-server/
  api-gateway/
config-repo/
  application.yml
  discovery-server.yml
  api-gateway.yml
```

## Selected versions and dependencies

- Java 21
- Spring Boot 4.0.9
- Spring Cloud BOM 2025.1.4
- Maven Wrapper 3.10.0
- Config Server: `spring-cloud-config-server` 5.0.6
- Eureka Server: `spring-cloud-starter-netflix-eureka-server` 5.0.3
- Config Client: `spring-cloud-starter-config` 5.0.6
- API Gateway: `spring-cloud-starter-gateway-server-webflux` 5.0.4,
  `spring-cloud-starter-netflix-eureka-client` 5.0.3, and
  `spring-cloud-starter-loadbalancer` 5.0.4

The Cloud BOM manages Spring Cloud dependency versions centrally. The official
2025.1.4 release documentation lists Boot 4.0.9 as supported. The six business
services use the same baseline and are not included in this reactor.

## Configuration repository

Config Server runs in **native mode**: it serves the YAML files in
`config-repo/` directly from the local filesystem — no nested Git repository
is required. The YAML files contain local-development defaults; never store
passwords, tokens, private keys, or environment secrets in this repository.

The default search location is `file:../../config-repo/`, relative to the
Config Server working directory, so start it from
`infrastructure/config-server/`. To point it at a different local folder, set
`CONFIG_REPO_DIR` to that folder's `file:` URI, for example:

```powershell
$env:CONFIG_REPO_DIR = "file:C:/path/to/config-repo/"
```

## Build and test

The workspace's default build task uses the Maven Wrapper, so a global Maven
installation is not required. In VS Code, run **Build > Build Task** or press
`Ctrl+Shift+B`.

Run from the repository root:

```powershell
Push-Location .\infrastructure
try {
    .\mvnw.cmd -version
    .\mvnw.cmd clean verify
} finally {
    Pop-Location
}
```

The reactor includes only `config-server`, `discovery-server`, and
`api-gateway`. Verification compiles all modules and runs the Config Server
endpoint test. Discovery Server and Gateway startup/routing checks are separate
runtime validation steps.

## Startup order and PowerShell commands

Start Config Server first. It reads `config-repo/`; Discovery Server must
retrieve `discovery-server.yml` from Config Server and fails startup if that
configuration server is not available. Config Server has no Config Client
dependency and does not fetch configuration from itself.

In the first PowerShell terminal, from the repository root:

```powershell
Push-Location .\infrastructure\config-server
try {
    ..\mvnw.cmd spring-boot:run
} finally {
    Pop-Location
}
```

Config Server defaults to port 8888. To override its port in that terminal,
set `$env:CONFIG_SERVER_PORT` before the command.

In a second terminal, set a reachable Config Server URL if the default is not
suitable, then start Eureka:

```powershell
$env:CONFIG_SERVER_URL = "http://localhost:8888"
Push-Location .\infrastructure\discovery-server
try {
    ..\mvnw.cmd spring-boot:run
} finally {
    Pop-Location
}
```

Eureka defaults to port 8761. For a different port, set
`$env:EUREKA_SERVER_PORT` in this terminal. Host-local `localhost` defaults
are for local processes; when running in containers or other networks, provide
reachable URLs through environment variables. Container networking is not
configured by this project.

In a third PowerShell terminal, configure and start the Gateway:

```powershell
$env:CONFIG_SERVER_URL = "http://localhost:8888"
$env:EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE = "http://localhost:8761/eureka/"
Push-Location .\infrastructure\api-gateway
try {
    ..\mvnw.cmd spring-boot:run
} finally {
    Pop-Location
}
```

The Gateway defaults to port 8080. `API_GATEWAY_PORT` overrides the port;
`CONFIG_SERVER_URL` and `EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE` override the
Config Server and Eureka registry addresses respectively. Services are resolved
through Eureka by service ID.

Inside a container, `localhost` refers to that container, not the host or
another application. A future container deployment must supply resolvable
Config Server and Eureka addresses; no Docker Compose or container networking
is defined here.

## Runtime verification

Dashboard URL:

```text
http://localhost:8761/
```

Registry REST endpoint:

```text
http://localhost:8761/eureka/apps
```

Before the API Gateway starts, there should be no registered applications.
The Discovery Server has `register-with-eureka=false` and
`fetch-registry=false`, so it does not register itself or fetch its own
registry. Config Server is not an Eureka client. After Gateway starts, expect
the `API-GATEWAY` client registration in the registry. Eureka's REST response
may represent an empty registry with an empty application list before then.

To confirm Eureka's external configuration, query Config Server:

```powershell
$config = Invoke-RestMethod http://localhost:8888/discovery-server/default
$config.propertySources[0].source["server.port"]
$config.propertySources[0].source["eureka.client.register-with-eureka"]
$config.propertySources[0].source["eureka.client.fetch-registry"]
```

Expected output:

```text
8761
false
false
```

The Eureka dashboard and registry must not be exposed publicly without
appropriate security and network controls. Authentication/authorization is
intentionally out of scope here.

## API Gateway routing

The Gateway external configuration is `config-repo/api-gateway.yml`. It
registers `api-gateway` with Eureka, imports configuration through Config
Data, disables the discovery route locator, and declares explicit routes for
every public legacy path family. Each route uses `lb://<SERVICE-ID>` and one
`StripPrefix=1`:

| Route ID | Match | Destination |
|---|---|---|
| `inventory-stock-service` | `/api/stocks/**` | `INVENTORY-SERVICE` |
| `inventory-product-stock-assignment` | `PUT /api/produits/*/stock/*` | `INVENTORY-SERVICE` |
| `inventory-product-stock-unassignment` | `DELETE /api/produits/*/stock` | `INVENTORY-SERVICE` |
| `product-service` | `/api/produits/**` | `PRODUCT-SERVICE` |
| `category-service` | `/api/categories-produits/**` | `PRODUCT-SERVICE` |
| `supplier-service` | `/api/fournisseurs/**` | `SUPPLIER-SERVICE` |
| `activity-sector-service` | `/api/secteurs-activite/**` | `SUPPLIER-SERVICE` |
| `user-service` | `/api/operateurs/**` | `USER-SERVICE` |
| `payment-recovery-rate-compatibility` | `/api/facture/pourcentageRecouvrement/**` | `PAYMENT-SERVICE` |
| `procurement-service` | `/api/facture/**` | `PROCUREMENT-SERVICE` |
| `payment-service` | `/api/reglements/**` | `PAYMENT-SERVICE` |
| `reject-internal-api` | `/api/internal/**` | `404` (never forwarded) |

Any other `/api/**` path returns `404`; there is no fallback application.
Gateway's proxy forwards the incoming method, body and query string and relays
the upstream response. No routes are created automatically from registry
entries. The Angular development proxy forwards `/api` unchanged to port 8080.

Direct smoke test (assuming a seeded stack):

```powershell
curl.exe -i "http://localhost:8080/api/produits"
curl.exe -i "http://localhost:8080/api/stocks"
```

Product creation now requires a stock, so a create probe must include an
existing `stockId`. Use a disposable development database and remove any test
record through the API:

```powershell
$body = '{"codeProduit":"GW-SMOKE","libelleProduit":"Gateway smoke test","prix":1,"stockId":1}'
curl.exe -i -X POST "http://localhost:8080/api/produits" -H "Content-Type: application/json" -d $body
```

Query preservation can be checked with:

```powershell
curl.exe -i "http://localhost:8080/api/produits?smoke=true"
```

Gateway is a local-development foundation and must not be exposed publicly.
Eureka registration is service discovery, not authentication or authorization.
Authentication, authorization, rate limiting, and production edge protection
must be designed before public deployment.

## Troubleshooting

- **Config Server cannot find configuration:** Start it from
  `infrastructure/config-server/` (the default search location
  `file:../../config-repo/` is relative to the working directory) and confirm
  the YAML files exist in `config-repo/`. To use another folder, set
  `CONFIG_REPO_DIR`.
- **Config endpoint is missing properties:** Check
  `http://localhost:8888/discovery-server/default` and confirm
  `discovery-server.yml` is present in `config-repo/`.
- **Eureka cannot retrieve configuration:** Start Config Server first and
  verify `CONFIG_SERVER_URL` points to an address reachable from the Eureka
  process.
- **Eureka does not start on 8761:** Check whether the port is occupied or set
  `EUREKA_SERVER_PORT`; confirm Eureka's configuration was committed.
  - **Gateway cannot retrieve configuration:** Start Config Server first and
    confirm `http://localhost:8888/api-gateway/default` serves the explicit
    routes and the expected Eureka URL.
  - **Gateway cannot connect to Eureka or a service:** Check the
    `EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE` value from the Gateway process
    environment and that the target service is registered. Do not use host
    `localhost` for another container.
  - **Gateway route appears missing:** Confirm `api-gateway.yml` is committed to
    the Config Server's `main` branch and
    `spring.cloud.gateway.server.webflux.discovery.locator.enabled` is `false`.
  - **Maven cannot resolve dependencies:** Check Maven Central access and run
    `.\mvnw.cmd -U clean verify` from `infrastructure/`.
