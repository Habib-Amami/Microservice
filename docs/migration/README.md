# Migration documents (historical archive)

The documents in this folder are the **historical design, baseline and
extraction records** of the strangler-fig migration. They describe the
purchasing/stock-management monolith as it existed during the migration and
are kept for traceability — they are **not** a description of the current
system.

The monolith lived in `backend/` (default port `8089`) and was removed after
all route families were covered by the six microservices and locally validated.
Consequently:

- Links into `backend/` from these documents point to files that no longer
  exist in the current tree. To inspect the historical source, use commit
  [`b662c0f`](https://github.com/Habib-Amami/Microservice/tree/b662c0f/backend),
  the last commit that contained it.
- Statements about a Gateway "monolith fallback", `MONOLITH_BASE_URL`, the
  `--monolith` run mode, or 8089 as a live endpoint are historical. The current
  Gateway has no fallback: unmatched `/api/**` and `/api/internal/**` return
  `404`.
- Legacy table names and API quirks recorded here were **inferences** from the
  old code. No production data was copied.
- These documents were written before implementation. Statements that describe
  CQRS/Axon, Resilience4j, distributed tracing or observability as "pending",
  "proposed", or "to be implemented" are **stale by design**: they record the
  original plan, not its current state. They are kept unchanged as a historical
  record.

For the current system, use:

- [Repository overview](../../README.md)
- [Local run and test runbook](../RUN-LOCAL.md)
- [Microservices implementation status](../MICROSERVICES-IMPLEMENTATION-STATUS.md)
- [Team development guide](../../TEAM-DEVELOPMENT-GUIDE.md)
- [Gateway configuration](../../config-repo/api-gateway.yml)
