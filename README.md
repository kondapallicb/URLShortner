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
