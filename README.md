# URL Shortener Agentic System

Spring Boot prototype for a URL shortener service with an agentic SDLC orchestration layer.

The assignment goal is not only to build URL shortening APIs, but to demonstrate governed engineering automation: requirement understanding, task decomposition, implementation, validation, documentation, release readiness, approval gates, retries, fallback, rollback, safe-stop controls, and audit-grade traceability.

## Current Commit Scope

This foundation commit establishes:

- Spring Boot 3 project structure
- Java 21 Maven build
- Health/status endpoint
- Package boundaries for API, domain, application, infrastructure, orchestration, and observability
- Initial orchestration stage model
- Smoke tests

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

## Run

```bash
mvn spring-boot:run
```

Then call:

```bash
curl http://localhost:8080/api/system/status
```

## Test

```bash
mvn test
```

## Planned Commit Sequence

1. Initialize Spring Boot URL shortener service
2. Add core URL shortening and redirect APIs
3. Add analytics and reliability controls
4. Implement governed agentic SDLC orchestration graph
5. Add greenfield, brownfield, and ambiguous orchestration scenarios
6. Add architecture, observability, and release readiness summary
