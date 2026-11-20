# Axon operations runbook

This is the operational policy for the CQRS/Axon implementation in
`procurement-service`. It closes the Axon items in
[MICROSERVICES-IMPLEMENTATION-STATUS.md](MICROSERVICES-IMPLEMENTATION-STATUS.md):
event-store ownership, backup/restore, replay, retention, schema evolution,
token/dead-letter handling, and the cross-service outbox/broker decision.

It applies to the **Compose** stack described in
[DOCKER-COMPOSE.md](DOCKER-COMPOSE.md). The host flow in
[RUN-LOCAL.md](RUN-LOCAL.md) deliberately uses the embedded in-memory Axon
infrastructure; see [Store ownership](#store-ownership) before mixing the two.

## Current topology

| Item | Value |
|---|---|
| Only Axon user | `procurement-service` (port `8086`) |
| Axon Server image | `axoniq/axonserver:2026.1.8`, single node, standalone mode |
| Endpoints | UI/REST `http://localhost:8024`, gRPC `axon-server:8124` inside Compose |
| Context | `default`, **non-DCB** (`dcbContext: false` in `/v2/contexts`) |
| Event processor | `invoiceProjection`, **subscribing** mode |
| Serializer | Jackson for events and general messages (Java records cannot be reflected by XStream) |
| Domain events | `InvoiceCreatedEvent`, `InvoiceArchivedEvent` |
| REST façade | `/cqrs/factures` (`/api/cqrs/factures/**` through the Gateway) |
| Read model | `procurement_db.invoice_projections`, `invoice_projection_lines` |

Useful read-only checks:

```bash
curl -fsS http://localhost:8024/v2/contexts
curl -fsS http://localhost:8024/v3/api-docs | python3 -m json.tool | head -40
curl -fsS http://localhost:8080/api/cqrs/factures | python3 -m json.tool | head -40
docker compose ps --format '{{.Name}}\t{{.Status}}'
```

## Store ownership

**Decision: Axon Server owns the event store in the deployed Compose stack;
Procurement owns the read model and the event contract. MySQL's Axon JPA tables
are compatibility storage for the host/tests, not a second production event
store.**

| State | Location (Compose) | Owner | Purpose |
|---|---|---|---|
| Event segments + snapshot segments | named volume `achat_axon-server-events` mounted at `/axonserver/events` | Platform / Axon Server | Append-only `InvoiceCreatedEvent` / `InvoiceArchivedEvent` history |
| Control DB (contexts, replication groups, clients) | named volume `achat_axon-server-data` mounted at `/axonserver/data` | Platform / Axon Server | Standalone `default` and `_admin` context configuration |
| Raft/replication log | named volume `achat_axon-server-log` mounted at `/axonserver/log` | Platform / Axon Server | Required to restart a standalone node consistently with its control DB |
| Control DB backups created by the REST API | `/axonserver/data/controldb*.zip` in the same named volume | Platform / Axon Server | Created by `POST /v1/backup/createControlDbBackup` |
| Invoice read model | `procurement_db.invoice_projections`, `invoice_projection_lines` | `procurement-service` | Answers `FindAllInvoicesQuery` and `FindInvoiceByIdQuery` |
| Axon JPA tables (`domain_event_entry`, `token_entry`, `dead_letter_entry`, …) | `procurement_db`, Flyway V4 | `procurement-service` | Required by Hibernate `ddl-auto=validate`; real store only when Axon Server is disabled |

Rules:

- **One effective event store per deployment.** Compose sets
  `AXON_AXONSERVER_ENABLED=true`; the host run and unit/integration tests leave
  it at `false`. Never flip the toggle on a populated deployment: events
  written to one store are not visible in the other.
- Do not let another service read the `default` context. Axon events are an
  in-service implementation detail of Procurement, not a shared database.
- Do not delete or rewrite stored events to "fix" a projection. Restore or
  replay instead.
- `scripts/compose-stack.sh down` keeps the named volumes;
  `scripts/compose-stack.sh reset` runs `docker compose down -v` and therefore
  **destroys the event store, control DB, raft log and all service databases**.
  Never use `reset` to recover from an Axon problem.
- The Compose file mounts the actual directories used by this image. The old
  `axon-server-data:/data` mount was not where this Axon Server version keeps
  its state; if you upgrade the image, re-check `/axonserver/axonserver.properties`
  before changing the volume map.

## Backup

### Preferred: offline, consistent snapshot

This is the simplest reliable option for a single-node demo stack. It captures
all three Axon state locations together with the Procurement read model at one
point in time.

```bash
cd "$(git rev-parse --show-toplevel)"
BACKUP_DIR="backups/axon/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$BACKUP_DIR"/{data,events,log}
DB_PASSWORD="${DB_PASSWORD:-root}"

# Quiesce writers, then stop Axon Server.
docker compose stop procurement-service axon-server

for volume in data events log; do
  docker run --rm \
    -v "achat_axon-server-${volume}:/src" \
    -v "$PWD/$BACKUP_DIR/${volume}:/dst" \
    alpine:latest sh -c 'cp -a /src/. /dst/'
done

docker exec -e MYSQL_PWD="$DB_PASSWORD" achat-mysql \
  mysqldump -uroot --single-transaction --routines --triggers procurement_db \
  > "$BACKUP_DIR/procurement_db.sql"

docker compose start axon-server
docker compose up -d
```

`backups/` is gitignored. Copy the timestamped directory to external storage;
a backup still inside the Compose project is not a backup.

### Online API alternative

Axon Server exposes a REST API at `http://localhost:8024/swagger-ui/index.html`.
For this non-DCB context the relevant endpoints are:

```bash
# 1. Config DB first; the response is a container path such as
#    /axonserver/data/controldb1795179540939.zip
CONTROL_DB_PATH="$(curl -fsS -X POST http://localhost:8024/v1/backup/createControlDbBackup)"
docker cp "achat-axon-server:$CONTROL_DB_PATH" "$BACKUP_DIR/"

# 2. Event and snapshot file lists for the non-DCB "default" context.
for kind in EVENT SNAPSHOT; do
  curl -fsS "http://localhost:8024/v1/backup/eventstore?targetContext=default&type=$kind"
done
```

Copy **every** path returned by the event/snapshot endpoints with `docker cp`;
the active segment is included and must be overwritten on restore. Also copy
`/axonserver/log/default` and `/axonserver/log/_admin` from the same node: the
control DB backup contains only `axonserver-configdb.mv.db`, and a control DB
restored without its matching raft log fails to start with
`Last applied index is higher than last log index`.

`POST /v2/backup` is for DCB contexts only. This stack's `default` context is
non-DCB and the endpoint returns `Backups can only be performed on DCB-contexts`.

Backup cadence: daily for 7 days and weekly for 4 weeks, off-host; take an
extra backup before and after an Axon Server/image upgrade and run a restore
drill at least quarterly. Discard all newer backups when rolling back to an
older one.

## Restore

Use the offline snapshot created above. Restoring only one of the Axon state
locations or only the MySQL read model produces an inconsistent system.

```bash
cd "$(git rev-parse --show-toplevel)"
BACKUP_DIR=backups/axon/<timestamp>       # choose one complete backup

scripts/compose-stack.sh down             # keep other volumes; replace Axon ones explicitly
docker volume rm \
  achat_axon-server-data \
  achat_axon-server-events \
  achat_axon-server-log

docker compose up -d axon-server
docker compose stop axon-server

for volume in data events log; do
  docker run --rm \
    -v "achat_axon-server-${volume}:/dst" \
    -v "$PWD/$BACKUP_DIR/${volume}:/src" \
    alpine:latest sh -c 'cp -a /src/. /dst/'
done

docker compose start axon-server
until curl -fsS http://localhost:8024/v2/contexts >/dev/null; do sleep 1; done

# Restore the read model before starting Procurement.
docker compose up -d mysql
until docker compose exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot \
  -p"${DB_PASSWORD:-root}" --silent >/dev/null 2>&1; do sleep 1; done
docker exec -i -e MYSQL_PWD="${DB_PASSWORD:-root}" achat-mysql \
  mysql -uroot procurement_db < "$BACKUP_DIR/procurement_db.sql"

docker compose up -d
```

Verification:

```bash
curl -fsS http://localhost:8024/v2/contexts          # default context present
curl -fsS http://localhost:8080/api/cqrs/factures    # expected invoice rows
docker compose logs --tail=100 axon-server | grep -i fatal && echo 'FAIL' || echo 'OK'
```

A restore rolls back everything written after the backup. Side effects already
sent to other services are **not** undone; coordinate with an operator if a
rollback affects settlement or stock state.

## Replay and projection rebuild

**Current decision: the `invoiceProjection` processor is subscribing, so it has
no tracking token and Axon Server cannot reset it to replay events.** This is
intentional for read-your-writes behaviour: a query immediately after a command
sees the projected event. Do not claim a replay is possible in this mode.

Supported recovery today:

1. Restore a matching Axon + `procurement_db` snapshot from
   [Restore](#restore), or
2. Delete `invoice_projection*` rows and re-issue the original create/archive
   commands from an authoritative source. This is a rebuild, not an Axon event
   replay, and requires callers to retain the commands.

To enable true replay (requires a reviewed code/config change):

1. Remove/override the `subscribing` setting in
   `services/procurement-service/src/main/resources/application.properties`
   so Axon uses a tracking processor. This makes projection updates
   asynchronous; adjust UI/tests that assume immediate consistency.
2. Make handlers replay-safe. `on(InvoiceCreatedEvent)` already upserts and
   replaces lines; `on(InvoiceArchivedEvent)` currently throws when the
   projection is missing. Make it tolerate a missing row before allowing
   partial replays, and add a `@ResetHandler` that clears the read model.
3. Optionally enable the dead-letter queue with
   `axon.eventhandling.processors.invoiceProjection.dlq.enabled=true`.
4. For a full replay: stop all Procurement replicas, pause
   `invoiceProjection` in the Axon Server UI (or
   `PATCH /v1/components/{component}/processors/{processor}/pause?context=default&tokenStoreIdentifier=...`),
   reset the token to HEAD/a point in time, clear the read-model tables, then
   start the processor and watch it catch up. The exact `component`,
   `processor` and `tokenStoreIdentifier` values must be read from the Axon
   Server processor page/API for the deployed client; do not guess them.
5. Resolve every dead letter before resetting: a replay keeps dead letters by
   default and treats those sequences specially. Clearing the DLQ drops events
   without reprocessing them, so only do that deliberately.

Never run a replay directly against production for the first time. Rehearse it
on a restored copy and compare `GET /api/cqrs/factures` counts and payloads.

## Retention

- **Events:** keep the full history. No Black Hole tier, ephemeral context,
  tiered storage or event deletion is configured, and none may be enabled
  while `InvoiceAggregate` is event-sourced. Axon Server's default
  `event.retention-time` applies only when secondary/tiered storage is in use;
  it does not expire events in this single-node standalone stack.
- **Snapshots:** none are configured. If introduced later, retain the latest
  snapshot per aggregate and delete older ones only after a successful restore
  test.
- **Backups:** follow the cadence in [Backup](#backup); never modify files in a
  backup and never back up into the live event-storage directory.
- **Disk:** alert before 80% usage on `achat_axon-server-events`,
  `achat_axon-server-data` and `mysql-data`. Growth is unbounded by design
  because events are retained.

## Schema evolution

Events are append-only and stored as Jackson JSON. Evolve the *reader*, not
the stored bytes:

- **Additive field:** add the field to the record and give the projector a
  default for older events. Verify aggregate reload and a projection rebuild
  against fixtures produced by the old version.
- **Rename/reshape:** use Jackson annotations where conversion at handling time
  is sufficient. For a true structural change, add a revision and a
  `SingleEventUpcaster` bean, register it in the `EventUpcasterChain` in the
  correct order, and test it with old payloads before deployment.
- **New business meaning:** prefer a new event type (for example
  `InvoiceUpdatedEvent`) over changing the meaning of `InvoiceCreatedEvent`.
- **Never** switch the event serializer away from Jackson on existing data,
  edit event files, or hand-edit Flyway V4 tables. Schema changes to the JPA
  compatibility tables require a new Flyway migration.
- Deploy projector changes only after a replay/rebuild rehearsal. Projectors
  must be idempotent because an event can be delivered more than once during
  recovery.

## Tokens and dead letters

With the current subscribing processor there are no persistent tracking tokens
and no dead-letter queue in use. `token_entry` and `dead_letter_entry` exist in
`procurement_db` only because JPA auto-configuration validates them; they are
not part of the Compose event-processing path.

If the processor is later switched to tracking mode with the DLQ enabled:

- monitor `dead_letter_entry` and the Axon Server processor page;
- fix the cause, then process whole sequences with `SequencedDeadLetterProcessor`
  so event order is preserved;
- treat retries as at-least-once and keep handlers idempotent;
- resolve or deliberately evict dead letters before a token reset.

## Cross-service durable publication

**Decision: no cross-service event publication, outbox table or message broker
is authorized.** Axon events and Axon Server remain entirely inside
Procurement. Cross-service calls stay synchronous REST through Feign and the
Gateway, with timeouts, Resilience4j retry/circuit-breaker/fallback and
idempotency keys where a retry can duplicate a write. This follows the
professor's synchronous REST requirement; the historical `docs/migration/`
documents that discuss event transports do not override it.

If a durable integration is approved later, the minimum pattern is:

1. the publisher writes the event to a local outbox table in the same database
   transaction as the business change;
2. a relay publishes to the broker with at-least-once delivery;
3. every consumer records the event id and is idempotent;
4. lag, retry and dead-letter queues are monitored, and the event contract is
   versioned and tested between services.

That change requires a new architecture decision and updates to
[MICROSERVICES-IMPLEMENTATION-STATUS.md](MICROSERVICES-IMPLEMENTATION-STATUS.md)
and `TEAM-DEVELOPMENT-GUIDE.md` before implementation. Do not start another
service against the `default` Axon context as a substitute.
