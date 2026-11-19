# Docker Compose demo stack

This is an **additive** alternative to the host-based `scripts/run-stack.sh`
flow. Everything the old script starts now also runs in containers, plus
tracing, metrics, logs and dashboards. The existing script and its runbook are
still valid.

## 1. What you get with one command

`docker compose up -d --build` starts:

| Group | Containers |
|---|---|
| Data | MySQL 8.4 (six databases, Flyway migrations on service startup) |
| Platform | Config Server, Eureka, API Gateway |
| Business | product, inventory, supplier, user, procurement, payment |
| UI | Angular app served by Nginx on port 4200, proxying `/api` to the Gateway |
| Tracing | Zipkin (spans posted to `/api/v2/spans`, UI on 9411) |
| Event store | Axon Server (gRPC on 8124, admin UI on 8024) for procurement CQRS |
| Metrics | Prometheus scraping all nine `/actuator/prometheus` endpoints |
| Logs | Loki + Promtail tailing the Docker JSON log files |
| Dashboards | Grafana with a pre-provisioned "Achat - microservices overview" dashboard |

The optional `quality` profile adds SonarQube (with PostgreSQL) and Nexus.

**Image build:** both Dockerfiles are multistage. The Java image uses a Maven
builder and a small Alpine JRE runtime; the repackaged jar is exploded into
Spring Boot layers so a source change only invalidates the tiny application
layer, not the dependency layer. Only the selected module's POM and sources are
copied, Maven downloads go to a shared BuildKit cache, and `.dockerignore`
keeps `.git`, `.run` and `node_modules` out of the context. The Angular image
uses a Node builder and an Nginx runtime.

## 2. Before you start

1. The Compose stack publishes the same ports as the host script
   (`3307, 4200, 8080, 8082-8087, 8761, 8888`). Stop the old stack first:

   ```bash
   scripts/run-stack.sh stop
   docker stop stack-mysql 2>/dev/null || true
   ```

2. Linux only, for SonarQube's embedded Elasticsearch (skip if you do not use
   the `quality` profile):

   ```bash
   sudo sysctl -w vm.max_map_count=262144
   ```

3. Optionally copy `.env.example` to `.env` to change passwords. Compose reads
   `.env` automatically; the file is gitignored.

## 3. Start, seed, stop

```bash
scripts/compose-stack.sh up       # = docker compose up -d --build
scripts/compose-stack.sh status   # = docker compose ps
scripts/compose-stack.sh seed     # loads the canonical demo dataset via :8080/api
scripts/compose-stack.sh urls     # prints all demo URLs
scripts/compose-stack.sh down     # stop, keep named volumes
scripts/compose-stack.sh reset    # stop AND delete all data volumes
```

The first build downloads base images and Maven/npm dependencies; later builds
reuse the BuildKit caches. Wait until `docker compose ps` reports all services
`healthy` before seeding. If seeding reports empty collections, wait a few more
seconds for Eureka/load-balancer caches and rerun the seeder.

## 4. Demo URLs

| What | URL | Default credentials |
|---|---|---|
| Angular UI | <http://localhost:4200> | — |
| Gateway API | <http://localhost:8080/api/produits> | — |
| Eureka | <http://localhost:8761> | — |
| Product Swagger | <http://localhost:8082/swagger-ui.html> | — |
| Grafana | <http://localhost:3000> | `admin` / `admin` |
| Zipkin | <http://localhost:9411> | — |
| Axon Server | <http://localhost:8024> | — |
| Prometheus | <http://localhost:9090> | — |
| SonarQube (quality) | <http://localhost:9000> | `admin` / `admin` |
| Nexus (quality) | <http://localhost:8081> | `admin`, see below |
| MySQL | `localhost:3307` | `root` / `root` |

Set `GRAFANA_USER`, `GRAFANA_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD` in `.env`
to change the defaults. Do not expose these ports outside your machine: this
is a development stack with development credentials.

## 5. Tracing, metrics and logs demo

1. Generate traffic: open the UI at <http://localhost:4200>, click through the
   product/stock/invoice screens, or run a few API calls:

   ```bash
   for i in $(seq 1 20); do curl -s -o /dev/null http://localhost:8080/api/produits; done
   ```

2. **Grafana** — log in, open **Dashboards → Achat → Achat - microservices
   overview**. You get request rate by service, p95 latency, JVM heap, 5xx
   rate, status breakdown and the Loki log panel. The dashboard link in the
   header opens Zipkin.

3. **Zipkin** — choose a service (for example `api-gateway`) and click
   **Find Traces**. Spans propagate across the Gateway, Feign calls and the six
   services because `spring-boot-starter-zipkin` (Brave bridge + Zipkin
   reporter) is on every classpath and 100% sampling is enabled in Compose.

4. **Axon Server** — open <http://localhost:8024> to see the
   `procurement-service` client, its command handlers and the events produced
   by the CQRS endpoints (`POST /api/cqrs/factures`).

5. **Prometheus** — <http://localhost:9090/graph> and query
   `sum by (application) (rate(http_server_requests_seconds_count[1m]))` or
   `up{job="spring-boot"}`. All nine targets should be `UP`.

6. **Loki logs** — in the Grafana dashboard's log panel, filter by container ID
   or search for a request. Promtail reads
   `/var/lib/docker/containers/*/*-json.log`, so this works on a Linux Docker
   host; on Docker Desktop those files live inside the VM and a different log
   client (for example Grafana Alloy with the Docker service discovery) is
   needed.

Configuration lives in:

- `config-repo/application.yml` — shared actuator exposure, `application` metric
  tag, sampling and Zipkin endpoint (export is off by default for the host run).
- `services/procurement-service/src/main/resources/application.properties` — Axon Server
  connection settings (the host run uses the in-memory event store).
- `docker/prometheus.yml` — scrape targets.
- `docker/promtail-config.yml` — log discovery/parsing.
- `docker/grafana/` — datasources, dashboard provider and dashboard JSON.

## 6. SonarQube and Nexus

```bash
scripts/compose-stack.sh quality   # = docker compose --profile quality up -d
```

- **SonarQube**: first login `admin`/`admin`, then change the password and
  generate a token under *My Account → Security*. Run the analysis:

  ```bash
  SONAR_TOKEN=<your-token> scripts/sonar-scan.sh
  ```

  The script analyses the nine leaf modules as projects `achat-<module>`
  (slashes in infrastructure module paths become dashes, e.g.
  `achat-infrastructure-config-server`).

- **Nexus**: on the first start the random admin password is in
  `/nexus-data/admin.password`:

  ```bash
  docker exec achat-nexus cat /nexus-data/admin.password
  ```

  Log in as `admin`, complete the wizard, then publish snapshots:

  ```bash
  NEXUS_USER=admin NEXUS_PASSWORD='<your-password>' scripts/publish-nexus.sh
  ```

  This packages each module and deploys it to the `maven-snapshots` hosted
  repository, including the `infrastructure` parent POM.

- **CI**: both steps are optional. Set the repository variable
  `ENABLE_QUALITY=true`, plus variables `SONAR_URL` / `NEXUS_URL` and secrets
  `SONAR_TOKEN` / `NEXUS_USER` / `NEXUS_PASSWORD`. Steps with missing settings
  are skipped, so the ordinary CI run is unaffected. GitHub-hosted runners
  need URLs reachable from the internet (or a self-hosted runner on your
  network).

## 7. Troubleshooting

| Symptom | Check |
|---|---|
| Port already allocated | Stop the host stack (section 2) or change the published port in `compose.yaml` / `.env`. |
| A Java container is `unhealthy` | `docker compose logs -f <service>`; confirm Config Server and Eureka are healthy first. |
| Gateway returns 503 for a route | Wait for Eureka registration; `docker compose ps` and the Eureka dashboard show registration state. |
| No metrics in Grafana | `curl -s localhost:8080/actuator/prometheus | head` and check Prometheus → Status → Targets. |
| No logs in Loki | Confirm Promtail is running and can read `/var/lib/docker/containers` (rootless Docker and Docker Desktop need a different client). |
| No traces in Zipkin | `docker compose logs api-gateway | grep -i zipkin`; Compose sets `TRACING_EXPORT_ENABLED=true` and `ZIPKIN_ENDPOINT=http://zipkin:9411/api/v2/spans`. |
| SonarQube exits during startup | Set `vm.max_map_count=262144` and allow ~2 GB RAM. |
| Nexus deploy returns 401 | Use the password from `/nexus-data/admin.password` after completing the first-login wizard. |

## 8. Difference from `scripts/run-stack.sh`

| | `scripts/run-stack.sh` | Docker Compose |
|---|---|---|
| MySQL | `stack-mysql` container on 3307 | `achat-mysql` container on 3307, named volume |
| Platform/services | Maven `spring-boot:run` on the host | Multi-stage images (`docker/Dockerfile.java`) |
| Frontend | `ng serve` on 4200 | Nginx-served production build on 4200 |
| Config | `config-repo/` relative path | read-only bind mount at `/config-repo` |
| Service URLs | `localhost` | container DNS names (`mysql`, `config-server`, …) |
| Observability | Prometheus endpoint only | Zipkin + Axon Server + Prometheus + Loki + Grafana |
