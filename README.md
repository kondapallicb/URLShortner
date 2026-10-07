# URL Shortener With Governed Engineering Execution

Java 21, Spring Boot 3 and Maven. URL shortening includes custom aliases, TTL,
deactivation, payload-bound idempotency and UTC daily click analytics. A bounded
engineering worker writes production code and acceptance tests in a copied repository,
runs controlled offline Maven clean verify, and retains evidence for review. No workflow deploys changes automatically.

## Setup

Install JDK 21 and Maven, then run from this repository:

```bash
export WORKFLOW_OPERATOR_TOKEN='replace-with-a-long-random-secret'
export WORKFLOW_OPERATOR_NAME='engineering-lead'
export WORKFLOW_SECURITY_TOKEN='a-separate-long-random-secret'
export WORKFLOW_SECURITY_NAME='security-reviewer'
mvn -DskipTests clean verify
mvn clean verify
mvn spring-boot:run
```

For Homebrew Java 21, set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
Workflow APIs and deactivation require the configured bearer token. An empty token
disables access. Use HTTPS for remote access. Audit identity comes from the configured
operator name, never an approval request body. Security reviewers have a separate token
and role; they cannot start workflows or approve operator gates. Identical operator and
reviewer credentials or names are rejected at startup. The first Maven command warms
the dependency cache before offline validation.

## URL APIs

```bash
curl -i http://localhost:8080/api/urls \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: campaign-1' \
  -d '{"longUrl":"https://example.org/campaign","ttlSeconds":86400,"customAlias":"campaign"}'
curl -i http://localhost:8080/campaign
curl http://localhost:8080/api/urls/campaign/analytics
curl -X POST http://localhost:8080/api/urls/campaign/deactivation \
  -H "Authorization: Bearer $WORKFLOW_OPERATOR_TOKEN"
```

Aliases use 3-64 ASCII letters, digits, underscores or hyphens. `api` and `actuator`
are reserved. Duplicate aliases and changed payloads under an existing idempotency
key return 409. Inactive and expired links return 410. Analytics remain accessible
after deactivation; `dailyClicksUtc` is indexed by UTC date.

Atomic reservation protects slug uniqueness. Repository transactions bind idempotency
keys to normalized URL, TTL and alias payloads. Rate limiting uses socket addresses,
ignores forwarded headers, returns `Retry-After`, expires inactive entries and caps
tracked clients at 10,000. This remains a process-local limiter.

## Execute A Requirement

The worker derives a task DAG from explicit capabilities, acceptance criteria and Java
AST analysis. Repository contents, not the scenario label, determine greenfield or brownfield mode.
For greenfield, set `app.execution.repository` to an empty source directory; the worker creates
an original minimal build/application template. `CUSTOM_ALIAS` creates `/api/aliases`; `UTC_DAILY_ANALYTICS` creates
`/api/analytics/{slug}/daily` using the existing analytics service. Capabilities can be
combined. Alias length, alphabet and TTL alter both production code and acceptance tests.
Each criterion is bound to a fully qualified executed test method. All generated production
files must compile and be exercised; generated endpoints additionally enforce line/branch thresholds.
The original `/api/urls` behavior is preserved; requirement-specific alias constraints apply to the generated `/api/aliases` contract. Existing integrations and output paths are analyzed before planning.
Unknown criteria, unknown JSON fields and unsupported text modifiers require clarification.

```bash
curl http://localhost:8080/api/workflows \
  -H "Authorization: Bearer $WORKFLOW_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"GREENFIELD","requirement":"Add custom aliases"}'
```

For requirement-specific work, provide a structured specification instead of text:

```json
{
  "scenario": "BROWNFIELD",
  "specification": {
    "capabilities": ["CUSTOM_ALIAS", "UTC_DAILY_ANALYTICS"],
    "alias": {"minLength": 5, "maxLength": 12, "ttlSeconds": 120, "alphabet": "ALPHANUMERIC",
      "caseSensitive": true, "reservedAliases": ["api", "actuator"], "duplicateStatus": 409, "redirectStatus": 302},
    "acceptanceCriteria": [
      "ALIAS_REDIRECT", "DUPLICATE_REJECTED", "INVALID_ALIAS_REJECTED",
      "RESERVED_ALIAS_REJECTED", "ALIAS_BOUNDARIES_AND_TTL",
      "UTC_DAY_BOUNDARIES", "UNKNOWN_SLUG_REJECTED"
    ]
  }
}
```

Alias options require lengths within 3..64 and TTL within 60..31536000 seconds.
The other alphabet is `URL_SAFE`, which permits underscores and hyphens.
Analytics-only specifications omit `alias`. The deterministic interpreter accounts for every
free-text clause. For example, "Add custom aliases with minimum length 8, maximum length 20,
and expiry after one hour." creates the corresponding constraints and a controlled-clock
expiry test. Unrecognized clauses are retained in an UNSUPPORTED interpretation, with
specific clarification questions. Assumptions are recorded, and policy can forbid defaults.
Case-insensitive lookup, non-302 redirects and non-409 duplicate behavior are explicitly
unsupported; they are never silently accepted. Structured specifications permit up to 16 distinct custom
reserved aliases of 1..64 URL-safe characters. `GET /api/workflows/RUN_ID/interpretation` exposes the complete interpretation.
`GET /api/workflows/RUN_ID/plan` exposes analyzed symbols, tasks, dependencies and criterion/test mappings.

Use the returned run ID:

```bash
curl http://localhost:8080/api/workflows/RUN_ID/approval-evidence \
  -H "Authorization: Bearer $WORKFLOW_OPERATOR_TOKEN"
curl http://localhost:8080/api/workflows/RUN_ID/approvals \
  -H "Authorization: Bearer $WORKFLOW_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"evidenceHash":"HASH_FROM_PREVIOUS_RESPONSE","comment":"Approve this plan"}'
curl http://localhost:8080/api/workflows/RUN_ID/evidence \
  -H "Authorization: Bearer $WORKFLOW_OPERATOR_TOKEN"
```

Architecture approval invokes implementation and testing agents. Their structured,
CREATE/UPDATE/DELETE operations pass a single controlled application pipeline. It validates
traceability, current input and before hashes, task write scope, unique normalized paths,
symlink confinement, allowed file types, at most 100 files, 1 MiB per file and 5 MiB per batch.
It stages writes, backs up existing contents, and records a durable journal before mutation.
Individual replacements are atomic; the batch is not claimed to be atomic. Verified rollback
restores the pre-application hashes after a partial failure. Repairs use this same pipeline.
The macOS worker runs offline Maven under a Seatbelt OS sandbox, with no network,
no host credential inheritance, a read-only dependency cache, confined writes and
bounded JVM memory. Each build has a five-minute timeout; total execution is bounded by
10 minutes and the configured maximum attempts and terminates the process tree before
restoration. Worker-produced report and artifact paths reject symlink escapes before
the parent process reads them. Local-only dependency locks follow the
[Apache Maven Resolver configuration](https://maven.apache.org/resolver/configuration.html).
Unsupported OS hosts or missing sandbox tools stop; unsandboxed fallback is forbidden.
Validation requires successful exit, every declared acceptance test passing and unskipped,
Surefire reports and JaCoCo enforcement for every generated endpoint class: at least
80% line coverage and 70% branch coverage. Both Maven JaCoCo check and the parent evidence
validator enforce these thresholds. Missing required methods, skipped tests, renamed tests,
excluded tests and malformed reports block readiness, regardless of overall test counts.

Inspect the generated source and tests in the workspace before applying them to the
main repository. Evidence includes:

- `operations.json`: generated paths and file contents.
- `evidence.json`: requirement, baseline/outcome hashes, changed files and attempts.
- `interpretation.json`: stable requirement/criterion IDs, parameter sources, assumptions and questions.
- `original-proposal.encrypted.json`: full rejected/accepted agent intent, encrypted with AES-256-GCM; the key is stored in the trusted database, never passed to Maven.
- `application/`: normalized proposal, validation, before/after manifest, unified diff, staged writes and backups.
- `coordinator.json` and `terminal.json`: phase, classified failure, recovery decision and persistence status.
- `attempt-N/maven.log`: bounded merged stdout/stderr (2 MiB); metadata records truncation.
- `attempt-N/build.json`: fixed capability, Maven version, start/end, duration, exit code and timeout.
- `attempt-N/validation.json`: discovered cases, totals, failures, line/branch coverage, compiled files, criterion/production/test mappings and hashes.
- `attempt-N/test-reports/TEST-*.xml`: discovered tests, failures and durations.
- `attempt-N/jacoco.xml`: executed coverage counters.
- `attempt-N/diagnosis.json` and `attempt-N/repair.json`: failure diagnosis and exact repair operations, when applicable.
- `plan.json`: analyzed repository and requirement-specific task DAG.
- `governance-policy.json` and `policy-report.json`: policy and independently evaluated checks.
- `validated-artifact.jar`: compiled Spring Boot artifact, bound to approval by SHA-256.

A compiler error identifying generated files, or a generated acceptance-test failure,
can produce a repair to noncanonical generated production artifacts. Repairs never relax
acceptance assertions. The policy's `maxRetries` controls the retry budget, including zero.
Each failed attempt retains a diagnosis and exact repair operations. Unknown failures,
partial file-write errors, policy violations, cancellation, unavailable Maven and timeout
stop execution and restore the complete source snapshot, verifying its SHA-256. Failures are classified and retained when storage is available. If evidence persistence fails,
readiness is blocked and the recoverable journal is retained; the API does not claim a
finalized durable outcome. A failed restoration is recorded as ROLLBACK_FAILED and forbids approval.

After validation, an independent security gate checks generated files, input validation,
credential literals, forbidden tool APIs, declared guardrails and retry limits.
Inspect `/api/workflows/RUN_ID/security-evidence`, fetch `approval-evidence`, then approve
that hash using the security reviewer's bearer token. The operator must subsequently
fetch and approve the distinct release hash. The policy, plan, source, compiled JAR,
results, logs, tests and coverage are bound to approvals. Changes
to source or generated output invalidate approval. Audit decisions store operator
identity and the exact hash. A completed review does not deploy the workspace.

## Clarification

Ambiguous requests stop before implementation. Submit a revised requirement:

```bash
curl http://localhost:8080/api/workflows/OLD_RUN_ID/clarifications \
  -H "Authorization: Bearer $WORKFLOW_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"requirement":"Add custom aliases"}'
```

This creates a new revision with explicit parent lineage (`GET /api/workflows/RUN_ID/lineage`) and archives the previous run as superseded, removing its pending
approvals. Historical artifacts and decisions remain for audit and cannot authorize
the revised requirement. Clarification requests also accept the same `specification`
object as workflow starts, so revised acceptance criteria remain structured and traceable.

## Persistence And Tests

URL mappings, payload-bound keys and clicks use atomic `data/urls.json` snapshots.
Workflow state, approvals, claims, attempt evidence and recovery journals use JDBC
transactions with version checks and monotonic fencing tokens. PostgreSQL is the
multi-worker backend:

```bash
export WORKFLOW_JDBC_URL='jdbc:postgresql://localhost:5432/url_workflows'
export WORKFLOW_DB_USER='url_worker'
export WORKFLOW_DB_PASSWORD='use-a-secret-manager'
```

The database/user must already exist and the user needs table creation privileges.
Without these variables, an embedded file-backed H2 database is used for local
single-process development, not cross-host coordination. Both backends are tested.
Legacy JSON workflow history is not auto-migrated; retain it separately before upgrading.

Claims have worker identities, 30-second leases and heartbeats every 10 seconds.
Database time determines lease ownership. Finalization is idempotent and fenced.
A killed worker leaves a journal and an expiring lease; startup reconciliation of
RUNNING workflows retries from a hash-verified baseline within the original budget.
Existing workspaces trigger reconciliation rather than unconditional rejection.
A replacement gets `RUN_ID/recovery-TOKEN/workspace`, never the stale worker's mutable
workspace. Recovery inspects transactional journals and workspace hashes, invalidates
incomplete evidence, and conservatively rebuilds instead of resuming unvalidated output.
Completed outcomes and exact approvals survive restart.

Workers for one deployment must share a durable evidence volume and the approved
repository snapshot. Evidence files are outside the Maven sandbox; transactional
rows fence publication but are not a distributed filesystem. A database or evidence
volume outage blocks readiness. Restrict database access and enable encryption/backups;
proposal archive keys are database-protected, not an external KMS. URL snapshot storage and rate limiting remain
single-process; PostgreSQL coordination applies to engineering workflows.

`mvn clean verify` runs controller, service, concurrent reservation, restart, authentication,
rate limiting and orchestration tests. Execution tests launch real child Maven builds
and skip themselves inside children to prevent recursion. Repair evidence remains in
`target/execution-review/`; other temporary fixtures are removed. Coverage is in
`target/site/jacoco/index.html`.

The engineering summary derives review readiness from the latest run, current
evidence hashes, passing security policy and exact security/outcome approvals. No run means `NOT_RELEASE_READY`; changed
evidence means `STALE_EVIDENCE`. Valid completion means `VALIDATED_AND_APPROVED_FOR_REVIEW`,
which is not a production deployment certification.

## Remaining Assessment Work

The capability registry is bounded: alias creation and existing-service UTC analytics
are implemented; arbitrary domain ownership, tenant models and general refactors require
new capabilities. Unsupported work is rejected instead of receiving an implementation claim.
Lifecycle phases schedule the generated task DAG; the independent security stage is policy-driven.
Repair is limited to diagnosed generated artifacts. Baseline repository failures stop.

The tested execution backend is macOS Seatbelt. Linux and Windows execution fail closed
until an OS sandbox backend is provided. Static policy checks are explicit guardrail
checks, not a comprehensive security audit; independent human review remains required.
Evidence hashes detect changes but local files are not immutable audit storage.
URL snapshot persistence and rate limiting remain process-local. Recovery rebuilds from
baseline; it does not resume a partially executed JVM. Greenfield generation currently
supports alias applications with a minimal in-memory mapping repository; greenfield
analytics requires an additional declared click-history capability and is rejected.
Brownfield preserves the existing durable URL repository and regression tests.

The new six-point delivery and verification record is in [IMPROVEMENTS_VERIFICATION.md](IMPROVEMENTS_VERIFICATION.md).

The original six-commit history is in [IMPLEMENTATION_STEPS.md](IMPLEMENTATION_STEPS.md).
That narrative describes the earlier model; this README describes current behavior.
