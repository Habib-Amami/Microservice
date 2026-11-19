# Run and test the stack locally (Linux / macOS)

This is the practical runbook: start every component, seed the demo dataset,
and exercise each flow by hand. Windows users can use the same commands in
Git Bash or WSL, or start the components manually as shown in section 4.

## 1. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Java | 21+ | everything |
| Docker | any recent | MySQL 8.4 container |
| Python | 3.8+ | demo seeding |
| Node.js / npm | 22+ | Angular UI (optional) |
| curl | any | manual checks |

Ports that must be free: `3307` (MySQL), `8888` (Config), `8761` (Eureka),
`8080` (Gateway), `8082`–`8087` (services), and `4200` (UI, optional).

## 2. Quick start (one command)

```bash
# MySQL + Config Server + Eureka + Gateway + the six services
scripts/run-stack.sh start

# wipe the service databases and load the demo dataset
scripts/run-stack.sh reset

# per-component health
scripts/run-stack.sh status
```

Optional extra:

```bash
scripts/run-stack.sh start --frontend   # also runs the Angular UI on :4200
```

The script writes logs to `.run/logs/<component>.log`, PIDs to `.run/pids/`,
and leaves the MySQL container (`stack-mysql`, root/root, port 3307) running
between runs. Stop everything except MySQL with:

```bash
scripts/run-stack.sh stop
```

Override defaults with environment variables:

```bash
DB_PORT=3308 scripts/run-stack.sh start    # another MySQL port
SKIP_DB=1   scripts/run-stack.sh start     # use your own MySQL
scripts/run-stack.sh logs payment-service  # follow one component's log
```

## 3. What a healthy stack looks like

| URL | Expected |
|---|---|
| <http://localhost:8080/api/produits> | `200`, JSON array of products |
| <http://localhost:8761/> | Eureka dashboard listing 7 applications |
| <http://localhost:8888/product-service/default> | YAML/JSON config document |
| <http://localhost:8082/swagger-ui/index.html> | Product Service Swagger UI |
| <http://localhost:4200> | Angular UI (only with `--frontend` or `npm start`) |

All API traffic goes through the Gateway on **8080** and keeps the `/api`
prefix. Direct service ports exist but the UI and the seed script only use the
Gateway.

## 4. Manual start without the script

Start the databases once:

```bash
docker run -d --name stack-mysql -e MYSQL_ROOT_PASSWORD=root \
  -p 3307:3306 mysql:8.4
```

Use one terminal per component, **in this order**. The infrastructure modules
share the wrapper at `infrastructure/mvnw`; each service has its own `mvnw`.

```bash
# Terminal 1 — Config Server (must start first; it serves config-repo/ in native mode)
cd infrastructure/config-server && ../mvnw spring-boot:run
# ready when the log says: Started ConfigServerApplication
```

```bash
# Terminal 2 — Eureka
export CONFIG_SERVER_URL=http://localhost:8888
export EUREKA_ZONE=http://localhost:8761/eureka/
cd infrastructure/discovery-server && ../mvnw spring-boot:run
# ready when the log says: Started DiscoveryServerApplication
```

```bash
# Terminal 3 — Gateway
cd infrastructure/api-gateway && ../mvnw spring-boot:run
```

```bash
# Terminals 4–9 — the six services, one per terminal
# Shared for all six:
export CONFIG_SERVER_URL=http://localhost:8888
export EUREKA_CLIENT_SERVICE_URL_DEFAULT_ZONE=http://localhost:8761/eureka/
export DB="jdbc:mysql://localhost:3307"

# product-service — port 8082
cd services/product-service
PRODUCT_DB_URL="$DB/product_db?createDatabaseIfNotExist=true&serverTimezone=UTC" \
PRODUCT_DB_USERNAME=root PRODUCT_DB_PASSWORD=root ./mvnw spring-boot:run

# inventory-service — port 8083
cd services/inventory-service
INVENTORY_DB_URL="$DB/inventory_db?createDatabaseIfNotExist=true&serverTimezone=UTC" \
INVENTORY_DB_USERNAME=root INVENTORY_DB_PASSWORD=root ./mvnw spring-boot:run

# supplier-service — port 8084
cd services/supplier-service
SUPPLIER_DB_URL="$DB/supplier_db?createDatabaseIfNotExist=true&serverTimezone=UTC" \
SUPPLIER_DB_USERNAME=root SUPPLIER_DB_PASSWORD=root ./mvnw spring-boot:run

# user-service — port 8085
cd services/user-service
USER_DB_URL="$DB/user_db?createDatabaseIfNotExist=true&serverTimezone=UTC" \
USER_DB_USERNAME=root USER_DB_PASSWORD=root ./mvnw spring-boot:run

# procurement-service — port 8086
cd services/procurement-service
PROCUREMENT_DB_URL="$DB/procurement_db?createDatabaseIfNotExist=true&serverTimezone=UTC" \
PROCUREMENT_DB_USERNAME=root PROCUREMENT_DB_PASSWORD=root ./mvnw spring-boot:run

# payment-service — port 8087
cd services/payment-service
PAYMENT_DB_URL="$DB/payment_db?createDatabaseIfNotExist=true&serverTimezone=UTC" \
PAYMENT_DB_USERNAME=root PAYMENT_DB_PASSWORD=root ./mvnw spring-boot:run
```

Optional UI:

```bash
cd frontend && npm start     # http://localhost:4200, proxies /api to :8080
```

Each service reports readiness at `http://localhost:<port>/actuator/health`.
If a service starts before Config Server or Eureka is ready, it fails fast —
start it again once those are up.

## 5. Demo data

```bash
scripts/run-stack.sh seed          # only on an empty stack
scripts/run-stack.sh reset         # wipe + seed (idempotent demo state)
python3 scripts/seed-demo.py --dry-run   # preview API calls
```

`seed` refuses to run when data already exists; `reset` truncates the six
service databases (keeping Flyway history) and reseeds. Transient
`502/503/504` responses (the Gateway or a Feign client still warming its
Eureka cache right after a restart) are retried automatically for up to
~30 seconds. After `reset` the canonical dataset is:

| Domain | Rows |
|---|---|
| Suppliers / sectors | 2 suppliers (`SUP-TECH`, `SUP-OFFICE`), 3 sectors |
| Operators | 2 |
| Product categories / products | 2 / 2 (`CAT-OFFICE`, `CAT-IT`; paper 5.50, mouse 25.00) |
| Stocks / assignments | 2 / 2 |
| Invoices | 2 — 52.50 (SUP-TECH) and 49.50 (SUP-OFFICE, 10 % line discount) |
| Settlements | 2 — 52.50 paid / 0 left, 35.00 paid / 14.50 left |
| 2025 recovery rate | `85.78432` |

See [scripts/README.md](../scripts/README.md) for how the seed files are
owned per service.

## 6. Manual test checklist

These checks create a few test rows. Finish with
`scripts/run-stack.sh reset` to restore the canonical demo dataset.

Set the base URL once:

```bash
BASE=http://localhost:8080/api
id() { python3 -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }
```

### 6.1 Infrastructure and routing

```bash
# 7 applications registered: gateway + 6 services
curl -s http://localhost:8761/eureka/apps -H 'Accept: application/json' | head -c 400

# Config Server serves this service's file
curl -s http://localhost:8888/product-service/default | head -c 300

# Gateway routes to the right service
curl -s $BASE/produits | head -c 200
```

A bare `GET /api` returns `404` — that is expected; routes are per resource.

### 6.2 Product catalog (product-service)

```bash
curl -s $BASE/categories-produits
curl -s $BASE/produits

# create a category, then a product in it. A product MUST be created in a
# stock; use one of the seeded stocks (or create one in section 6.4 first).
CID=$(curl -s -XPOST $BASE/categories-produits -H 'Content-Type: application/json' \
  -d '{"codeCategorie":"CAT-TEST","libelleCategorie":"Test category"}' | id idCategorieProduit)

PID=$(curl -s -XPOST $BASE/produits -H 'Content-Type: application/json' \
  -d "{\"codeProduit\":\"PROD-TEST\",\"libelleProduit\":\"Test product\",\"prix\":12.5,\"categorieProduitId\":$CID,\"stockId\":1}" | id idProduit)

curl -s $BASE/produits/$PID          # categorieProduitId = $CID, stockId = 1

# creating without a stock, or with an unknown stock, is rejected
curl -s -o /dev/null -w 'no stock -> %{http_code}\n' -XPOST $BASE/produits \
  -H 'Content-Type: application/json' \
  -d '{"codeProduit":"PROD-BAD","libelleProduit":"No stock","prix":1}'
curl -s -o /dev/null -w 'unknown stock -> %{http_code}\n' -XPOST $BASE/produits \
  -H 'Content-Type: application/json' \
  -d '{"codeProduit":"PROD-BAD","libelleProduit":"Bad stock","prix":1,"stockId":999999}'
curl -s -XPUT $BASE/produits/$PID -H 'Content-Type: application/json' \
  -d "{\"codeProduit\":\"PROD-TEST\",\"libelleProduit\":\"Renamed\",\"prix\":13.0,\"categorieProduitId\":$CID}"
curl -s -o /dev/null -w 'delete product -> %{http_code}\n' -XDELETE $BASE/produits/$PID
curl -s -o /dev/null -w 'delete category -> %{http_code}\n' -XDELETE $BASE/categories-produits/$CID
```

### 6.3 Suppliers and sectors (supplier-service)

```bash
curl -s $BASE/fournisseurs
curl -s $BASE/secteurs-activite

SID=$(curl -s -XPOST $BASE/fournisseurs -H 'Content-Type: application/json' -d '{
  "code":"SUP-TEST","libelle":"Test supplier","categorieFournisseur":"ORDINAIRE",
  "detailFournisseur":{"email":"test@example.test","dateDebutCollaboration":"2024-01-01",
  "adresse":"1 Test Street","matricule":"MF-TEST-001"}}' | id idFournisseur)

SEC=$(curl -s -XPOST $BASE/secteurs-activite -H 'Content-Type: application/json' \
  -d '{"codeSecteurActivite":"TEST","libelleSecteurActivite":"Test sector"}' | id idSecteurActivite)

curl -s -o /dev/null -w 'link sector -> %{http_code}\n' \
  -XPUT $BASE/fournisseurs/$SID/secteurs-activite/$SEC
curl -s $BASE/fournisseurs/$SID
```

### 6.4 Stock and product assignment (inventory-service)

```bash
curl -s $BASE/stocks

STID=$(curl -s -XPOST $BASE/stocks -H 'Content-Type: application/json' \
  -d '{"libelleStock":"Test warehouse","qte":10,"qteMin":2}' | id idStock)

# legacy compatibility route, owned by inventory-service
curl -s -XPUT $BASE/produits/1/stock/$STID
curl -s $BASE/stocks/$STID

# API-only unassignment — clears the projection too. The Angular product form
# never offers "None": a product must belong to a stock, on create and on edit.
curl -s -XDELETE $BASE/produits/1/stock
curl -s $BASE/produits/1        # "stockId": null
```

The assignment is owned by inventory-service (`stock_product_assignments`),
which pushes it to product-service's read projection after saving. Product
responses therefore expose `stockId`:

```bash
curl -s $BASE/produits/1        # {"idProduit":1,...,"stockId":<idStock>,...}
```

The projection is best-effort: if product-service is down during an
assignment, the assignment is still saved in inventory and a warning is
logged. Re-assign the product (or reseed) to repair it.

Assignments are owned by inventory. Product deletion happens in
product-service, so a deleted product can leave a stale assignment; inventory
detects that on stock deletion, removes the stale rows, and lets the stock be
deleted. A stock with a live assigned product still returns `409`.

### 6.5 Invoices (procurement-service)

Amounts are computed by the service from catalog prices — never send them.

```bash
curl -s $BASE/facture/retrieve-all-factures

INV=$(curl -s -XPOST $BASE/facture/add-facture -H 'Content-Type: application/json' -d '{
  "montantRemise":0,"montantFacture":0,
  "dateCreationFacture":"2025-03-01","dateDerniereModificationFacture":"2025-03-01",
  "archivee":false,"fournisseurId":1,
  "detailsFacture":[{"qteCommandee":3,"pourcentageRemise":10,"produit":{"idProduit":1}}]}')
echo "$INV" | head -c 300
IID=$(echo "$INV" | id idFacture)

curl -s $BASE/facture/retrieve-facture/$IID

# a separate invoice, to check cancellation (it flips "archivee" to true)
CID=$(curl -s -XPOST $BASE/facture/add-facture -H 'Content-Type: application/json' -d '{
  "montantRemise":0,"montantFacture":0,
  "dateCreationFacture":"2025-03-02","dateDerniereModificationFacture":"2025-03-02",
  "archivee":false,"fournisseurId":1,
  "detailsFacture":[{"qteCommandee":1,"pourcentageRemise":0,"produit":{"idProduit":2}}]}' \
  | id idFacture)
curl -s -o /dev/null -w 'cancel -> %{http_code}\n' -XPUT $BASE/facture/cancel-facture/$CID
curl -s $BASE/facture/retrieve-facture/$CID     # "archivee": true

# cancelling an unknown invoice is a 404, not a silent no-op
curl -s -o /dev/null -w 'cancel unknown -> %{http_code}\n' \
  -XPUT $BASE/facture/cancel-facture/999999
```

Checks worth doing:

- 3 × paper (5.50) with 10 % discount → `montantFacture` = `14.85`.
- Each line stores a product **snapshot** (`codeProduit`, `libelleProduit`,
  `prix`), so later catalog edits never rewrite history.
- Unknown product → `404`; missing quantity → `400`.

### 6.6 Settlements and reporting (payment-service)

```bash
# settle the invoice created above (amount = its computed total)
curl -s -XPOST $BASE/reglements -H 'Content-Type: application/json' \
  -d "{\"montantPaye\":14.85,\"montantRestant\":0,\"payee\":true,\"dateReglement\":\"2025-03-05\",\"factureId\":$IID}"

curl -s $BASE/reglements
curl -s $BASE/reglements/facture/$IID

# revenue over a period (sum of paid amounts)
curl -s "$BASE/reglements/revenue?startDate=2025-01-01&endDate=2025-12-31"

# recovery rate = paid / invoiced for the period
curl -s $BASE/facture/pourcentageRecouvrement/2025-01-01/2025-12-31
```

On the pristine dataset: revenue `87.5`, recovery rate `85.78432`.

### 6.7 Operators (user-service)

```bash
curl -s $BASE/operateurs
curl -s -XPOST $BASE/operateurs -H 'Content-Type: application/json' \
  -d '{"nom":"Test","prenom":"Operator","password":"change-me"}'
```

### 6.8 Cross-service behaviour

```bash
# Procurement resolves the supplier through Feign; an unknown id becomes a 404
# (Problem Details body from the gateway path).
curl -s -w '\nHTTP %{http_code}\n' -XPOST $BASE/facture/add-facture \
  -H 'Content-Type: application/json' -d '{
  "montantRemise":0,"montantFacture":0,
  "dateCreationFacture":"2025-03-01","dateDerniereModificationFacture":"2025-03-01",
  "archivee":false,"fournisseurId":999999,
  "detailsFacture":[{"qteCommandee":1,"pourcentageRemise":0,"produit":{"idProduit":1}}]}'
```

Restart one service (`scripts/run-stack.sh stop` stops everything; during
manual runs just Ctrl-C its terminal) and re-run a flow to confirm the Gateway
retries through Eureka instead of pinning a stale instance.

### 6.9 Angular UI

```bash
cd frontend && npm start
```

Open <http://localhost:4200>. The dev proxy forwards `/api` to the Gateway, so
every screen you test is exercising the same routes as the curl commands. The
product → stock assignment action is the legacy route implemented by
inventory-service.

## 7. Reset, logs and common problems

| Symptom | Cause / fix |
|---|---|
| `ERROR: ports already in use` | `scripts/run-stack.sh stop`, or free the port |
| `SEED FAILED: Target stack already contains data` | use `scripts/run-stack.sh reset` |
| Discovery fails with `Connection refused: localhost:8888` | Config Server was not ready; use the script (it waits) or start Config first |
| Service starts, then exits asking for config | same — Config Server must be up before any client |
| Config change has no effect | Config clients read at startup: restart Config Server **and** the affected services |
| `404` on `http://localhost:8080/api` | expected; use a resource path such as `/api/produits` |
| `404` on `/api/internal/**` | expected; service-to-service APIs are deliberately rejected at the Gateway |
| `400` on `POST /produits` with "stockId" validation error | creation requires a stock; add `"stockId": <existing idStock>` to the payload |
| `404` on `POST /produits` with "Stock not found" | the requested `stockId` does not exist; create the stock first (`POST /stocks`) |
| `503` on `POST /produits` | inventory-service was unavailable; the product row was rolled back, so retry once it is up |
| `stockId` is `null` in product responses | legacy/unassigned data, an API-level unassignment (`DELETE /produits/{id}/stock`), or a failed projection push. Check `inventory-service` log for `stock projection was not updated`, then re-assign (`PUT /api/produits/{id}/stock/{stockId}`) or reseed |
| MySQL port conflict with a system server | keep `DB_PORT=3307` (default) or set another free port |
| A component log is unreadable in the console | `scripts/run-stack.sh logs <component>` (`.run/logs/`) |

To stop everything and keep the database:

```bash
scripts/run-stack.sh stop
docker stop stack-mysql        # only if you also want MySQL down
```
