package com.kondapallicb.urlshortener.observability;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EngineeringSummaryService {

    public EngineeringSummary summary() {
        return new EngineeringSummary(
                "Build a production-shaped URL shortener prototype that also demonstrates governed agentic SDLC orchestration.",
                architecture(),
                releaseReadiness(),
                List.of(
                        "Controller tests cover URL creation, redirect, analytics, and workflow APIs",
                        "Service tests cover slug collisions, expiry, idempotency, analytics recording, and workflow gates",
                        "Structured error paths exist for validation, missing URLs, expired URLs, workflow misses, and rate limiting",
                        "Manual verification can use README curl examples for end-to-end API behavior"
                ),
                List.of(
                        "In-memory repositories lose data on restart",
                        "Rate limiting is process-local and should move to Redis or API gateway for multiple instances",
                        "Click analytics intentionally stores limited request metadata and needs privacy review before production",
                        "Workflow agents are modeled deterministically; real LLM/tool execution would need sandboxing and policy enforcement"
                ),
                List.of(
                        "Kept persistence in memory to focus assignment effort on design, orchestration, and reviewability",
                        "Used explicit ports and records so persistence and orchestration adapters can be replaced later",
                        "Modeled approvals as API gates rather than background tasks to keep human ownership visible"
                ),
                List.of(
                        "No durable database migrations yet",
                        "No authenticated user or tenant model yet",
                        "No distributed tracing backend yet",
                        "Local test execution requires JDK 21 and Maven"
                ),
                List.of(
                        "Add Postgres persistence for URLs, clicks, idempotency keys, and workflow runs",
                        "Add authentication and tenant-aware authorization",
                        "Wire Micrometer dashboards and distributed tracing",
                        "Replace deterministic orchestration actions with sandboxed agent/tool executors"
                )
        );
    }

    private ArchitectureOverview architecture() {
        return new ArchitectureOverview(
                "Layered Spring Boot service with ports-and-adapters boundaries",
                List.of(
                        "api: REST controllers, validation DTOs, and structured error handling",
                        "application: URL shortening use cases and clock abstraction",
                        "domain: URL mappings, analytics records, repository ports, and domain exceptions",
                        "infrastructure: in-memory repositories and base62 slug generation",
                        "orchestration: governed workflow graph, scenario catalog, approvals, metrics, and audit decisions",
                        "observability: status and final engineering summary"
                ),
                List.of(
                        "POST /api/urls validates input and delegates to UrlShorteningService",
                        "GET /{slug} resolves the active mapping and records a click event",
                        "GET /api/urls/{slug}/analytics reads aggregated click metrics",
                        "POST /api/workflows starts a scenario-backed workflow and advances to the next human gate",
                        "POST /api/workflows/{runId}/approvals records ownership and advances the graph"
                ),
                List.of(
                        "Use idempotency keys to avoid duplicate short URLs on create retries",
                        "Keep workflow dependency graph explicit and inspectable",
                        "Require human approval for architecture and release-readiness gates",
                        "Represent fallback, rollback, and safe-stop as first-class execution states"
                )
        );
    }

    private ReleaseReadiness releaseReadiness() {
        return new ReleaseReadiness(
                "POC_READY_FOR_REVIEW",
                List.of(
                        "Core URL APIs implemented",
                        "Analytics and reliability controls implemented",
                        "Governed orchestration graph implemented",
                        "Three required scenario demonstrations implemented",
                        "Setup, testing, architecture, risks, and limitations documented"
                ),
                List.of(
                        "Install JDK 21 and Maven, then run mvn test",
                        "Review approval-gate semantics with product/security stakeholders",
                        "Decide target persistence backend before production hardening"
                ),
                List.of(
                        "Revert the last deployment artifact",
                        "Disable workflow start endpoint if governance behavior misroutes approvals",
                        "Preserve existing shortened links when replacing in-memory storage with durable persistence"
                )
        );
    }
}
