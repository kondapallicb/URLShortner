# URL Shortener Agentic System

Spring Boot prototype for a URL shortener service with an agentic SDLC orchestration layer.

The assignment goal is not only to build URL shortening APIs, but to demonstrate governed engineering automation: requirement understanding, task decomposition, implementation, validation, documentation, release readiness, approval gates, retries, fallback, rollback, safe-stop controls, and audit-grade traceability.

For a full commit-by-commit implementation walkthrough, see [IMPLEMENTATION_STEPS.md](IMPLEMENTATION_STEPS.md).

## Current Commit Scope

The repository now contains the complete six-commit prototype:

- Spring Boot 3 project structure
- Java 21 Maven build
- Core URL shortening and redirect APIs
- Analytics, idempotency, and rate limiting controls
- Governed agentic SDLC orchestration graph
- Greenfield, brownfield, and ambiguous scenario demonstrations
- Architecture, observability, and release readiness summary

## Architecture Overview

The service uses a layered, ports-and-adapters style:

| Layer | Responsibility |
| --- | --- |
| `api` | REST controllers, request/response DTOs, validation, structured errors |
| `application` | URL shortening use cases, click recording, clock boundary |
| `domain` | URL mappings, analytics records, repository ports, domain exceptions |
| `infrastructure` | In-memory repositories and base62 slug generation |
| `orchestration` | Workflow graph, scenario catalog, approvals, decision lineage, metrics |
| `observability` | System status and final engineering summary |

Primary control flow:

1. `POST /api/urls` validates a long URL, applies idempotency if supplied, generates or reuses a slug, and returns the short link.
2. `GET /{slug}` verifies the mapping is active, records a click event, and redirects.
3. `GET /api/urls/{slug}/analytics` returns aggregate click metrics.
4. `POST /api/workflows` starts a scenario-backed SDLC workflow and advances to the next human gate.
5. `POST /api/workflows/{runId}/approvals` records the approval and resumes orchestration.

## Core URL APIs

Create a short URL:

```bash
curl -X POST http://localhost:8080/api/urls \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-request-1' \
  -d '{"longUrl":"https://example.com/articles/agentic-engineering","ttlSeconds":86400}'
```

Example response:

```json
{
  "slug": "AbC123x",
  "shortUrl": "http://localhost:8080/AbC123x",
  "longUrl": "https://example.com/articles/agentic-engineering",
  "createdAt": "2026-10-06T18:00:00Z",
  "expiresAt": "2026-10-07T18:00:00Z"
}
```

Redirect:

```bash
curl -i http://localhost:8080/AbC123x
```

Fetch analytics:

```bash
curl http://localhost:8080/api/urls/AbC123x/analytics
```

Reliability controls currently include:

- Idempotent URL creation using the optional `Idempotency-Key` header
- Redirect click recording with timestamp, client IP, user agent, and referrer metadata
- Per-client in-memory API rate limiting for `/api/**` endpoints
- Structured error responses for validation, missing URLs, expired URLs, and rate limit failures

## Agentic SDLC Orchestration

Start a governed workflow:

```bash
curl -X POST http://localhost:8080/api/workflows \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"GREENFIELD","requirement":"Add custom aliases for short URLs"}'
```

Approve the current human gate:

```bash
curl -X POST http://localhost:8080/api/workflows/{runId}/approvals \
  -H 'Content-Type: application/json' \
  -d '{"approver":"engineering-lead","comment":"Architecture gate approved"}'
```

The orchestration graph includes requirement understanding, decomposition, architecture/design, implementation, testing, documentation, and release readiness. It tracks dependency order, human gates, decision lineage, retry/rollback counters, success rate, and end-to-end latency.

List the built-in scenario demonstrations:

```bash
curl http://localhost:8080/api/workflows/scenarios
```

Included scenarios:

| Scenario | Demonstrates | Review focus |
| --- | --- | --- |
| `GREENFIELD` | Custom alias support from a new requirement | decomposition, API contract, validation, release approval |
| `BROWNFIELD` | Analytics storage refactor | impacted-module reasoning, regression safety, rollback planning |
| `AMBIGUOUS` | Branded short links | clarification gate, assumption tracking, safe-stop criteria |

Each scenario includes ambiguity notes, decomposition steps, orchestration path, validation plan, approval checkpoints, and expected engineering artifacts.

## Run

```bash
mvn spring-boot:run
```

Then call:

```bash
curl http://localhost:8080/api/system/status
```

Final engineering summary:

```bash
curl http://localhost:8080/api/system/engineering-summary
```

## Test

```bash
mvn test
```

Testing approach:

- Controller tests cover system status, URL APIs, analytics, workflow start/approval/status, and scenario listing.
- Application tests cover slug collision retries, expiry, idempotent create, and click analytics.
- Orchestration tests cover architecture approval, release approval, completion, scenario-specific ambiguity notes, and safe-stop criteria.
- Local execution requires JDK 21 and Maven.

## Release Readiness

Status: `POC_READY_FOR_REVIEW`

Completed:

- Core URL shortener functionality
- Analytics and reliability controls
- Governed SDLC orchestration model
- Required greenfield, brownfield, and ambiguous scenarios
- Setup, architecture, validation, risk, and limitation documentation

Required before production:

- Install durable persistence for URLs, clicks, idempotency keys, and workflow runs.
- Add authentication, authorization, and tenant ownership.
- Move rate limiting to Redis or an API gateway for multi-instance deployments.
- Add distributed tracing and production metrics dashboards.
- Run `mvn test` and CI in an environment with JDK 21 and Maven.

Rollback plan:

- Revert the latest deployment artifact.
- Disable workflow start/approval endpoints if governance routing fails.
- Preserve existing shortened links before migrating from in-memory storage to a database.

## Risks and Trade-offs

Risks:

- In-memory repositories lose data on restart.
- Process-local rate limiting does not protect a horizontally scaled deployment.
- Click analytics metadata needs privacy review before production use.
- Real LLM-backed agents would require sandboxing, tool allowlists, prompt-injection controls, and stronger audit storage.

Trade-offs:

- Persistence is intentionally in-memory to keep the assignment focused on design, reviewability, and orchestration.
- Workflow actions are deterministic to make behavior auditable and testable.
- Human approvals are explicit API gates so controlled autonomy remains visible.

## Final Engineering Summary

This prototype demonstrates a production-shaped URL shortener plus an agentic SDLC control plane. The URL service covers creation, redirection, TTL expiry, idempotency, click analytics, and reliability controls. The orchestration layer models requirement understanding, task decomposition, architecture/design, implementation, testing, documentation, and release readiness as an explicit dependency graph with approval gates and traceable decisions.

Known limitations are intentionally documented rather than hidden: no durable database, no auth model, no distributed tracing backend, and no live LLM/tool executor. Those are the next hardening steps after review.

## Commit Sequence

1. Initialize Spring Boot URL shortener service
2. Add core URL shortening and redirect APIs
3. Add analytics and reliability controls
4. Implement governed agentic SDLC orchestration graph
5. Add greenfield, brownfield, and ambiguous orchestration scenarios
6. Add architecture, observability, and release readiness summary
