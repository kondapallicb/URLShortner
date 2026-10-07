package com.kondapallicb.urlshortener.observability;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EngineeringSummaryService {

    private final com.kondapallicb.urlshortener.orchestration.WorkflowRunRepository runs;
    private final com.kondapallicb.urlshortener.orchestration.WorkspaceExecutionService execution;

    public EngineeringSummaryService(com.kondapallicb.urlshortener.orchestration.WorkflowRunRepository runs,
            com.kondapallicb.urlshortener.orchestration.WorkspaceExecutionService execution) {
        this.runs = runs;
        this.execution = execution;
    }

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
                        "Atomic JSON snapshots support one process; shared multi-instance storage needs a database",
                        "Rate limiting is process-local and should move to Redis or API gateway for multiple instances",
                        "Click analytics intentionally stores limited request metadata and needs privacy review before production",
                        "The bounded alias agents execute local Maven; untrusted repository execution needs an OS sandbox"
                ),
                List.of(
                        "Used durable JSON snapshots for a single-instance worker; database transactions remain future work",
                        "Used explicit ports and records so persistence and orchestration adapters can be replaced later",
                        "Modeled approvals as API gates rather than background tasks to keep human ownership visible"
                ),
                List.of(
                        "No durable database migrations yet",
                        "One configured bearer operator; no tenant ownership or multiple roles yet",
                        "No distributed tracing backend yet",
                        "Local test execution requires JDK 21 and Maven"
                ),
                List.of(
                        "Add Postgres persistence for URLs, clicks, idempotency keys, and workflow runs",
                        "Add authentication and tenant-aware authorization",
                        "Wire Micrometer dashboards and distributed tracing",
                        "Expand bounded agents into a general planner and containerized worker"
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
                        "infrastructure: durable JSON repositories and base62 slug generation",
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
        String status = "NOT_RELEASE_READY";
        var latest = runs.all().stream().max(java.util.Comparator.comparing(run -> run.updatedAt()));
        if (latest.isPresent() && latest.get().state() == com.kondapallicb.urlshortener.orchestration.ExecutionState.COMPLETED) {
            try {
                String hash = execution.approvalHash(latest.get());
                boolean approved = latest.get().decisions().stream().anyMatch(decision ->
                        decision.decision().equals("APPROVED:release-readiness")
                        && decision.rationale().contains("evidence=" + hash + ";"));
                if (approved) status = "VALIDATED_AND_APPROVED_FOR_REVIEW";
            } catch (IllegalStateException stale) {
                status = "STALE_EVIDENCE";
            }
        }
        return new ReleaseReadiness(
                status,
                List.of(
                        "Core URL APIs implemented",
                        "Analytics and reliability controls implemented",
                        "Governed orchestration graph implemented",
                        "Three required scenario demonstrations implemented",
                        "Setup, testing, architecture, risks, and limitations documented"
                ),
                List.of(
                        "Inspect durable per-run Maven, test and coverage evidence",
                        "Require authenticated approvals bound to current source and artifact hashes",
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
