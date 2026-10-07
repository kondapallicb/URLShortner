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
                        "URL JSON snapshots support one process; workflow ownership uses transactional SQL",
                        "Rate limiting is process-local and should move to Redis or API gateway for multiple instances",
                        "Click analytics intentionally stores limited request metadata and needs privacy review before production",
                        "Bounded agents execute offline Maven in a macOS OS sandbox; other hosts fail closed"
                ),
                List.of(
                        "Used versioned SQL workflow state, leased ownership, fenced journals, and isolated attempt workspaces",
                        "Used explicit ports and records so persistence and orchestration adapters can be replaced later",
                        "Authenticated operator and independent security approvals bind exact current execution evidence"
                ),
                List.of(
                        "Legacy workflow JSON history is not automatically migrated to SQL",
                        "Two configured bearer roles; no tenant ownership or identity-provider integration yet",
                        "No distributed tracing backend yet",
                        "Local test execution requires JDK 21 and Maven"
                ),
                List.of(
                        "Add transactional shared persistence for URLs, clicks, and idempotency keys",
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
                        "infrastructure: URL JSON storage, transactional workflow SQL, and base62 slug generation",
                        "orchestration: interpreted criteria, repository-specific tasks, governed operations, and executed evidence",
                        "observability: status and final engineering summary"
                ),
                List.of(
                        "POST /api/urls validates input and delegates to UrlShorteningService",
                        "GET /{slug} resolves the active mapping and records a click event",
                        "GET /api/urls/{slug}/analytics reads aggregated click metrics",
                        "POST /api/workflows interprets requirements and analyzes the repository before its plan approval gate",
                        "POST /api/workflows/{runId}/approvals authenticates ownership and binds the exact current evidence"
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
                        && decision.approval() != null && decision.approval().gate().equals("release-readiness")
                        && decision.approval().evidenceHash().equals(hash));
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
                        "Requirement-specific planning, governed implementation, and real build evidence implemented",
                        "Greenfield, brownfield, clarification, repair, and crash-recovery proof tests implemented",
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
