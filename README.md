# URL Shortener With Governed Engineering Execution

Java 21, Spring Boot 3 and Maven. URL shortening includes custom aliases, TTL,
deactivation, payload-bound idempotency and UTC daily click analytics. A bounded
engineering worker writes production code and acceptance tests in a copied repository,
runs Maven, and retains evidence for review. No workflow deploys changes automatically.

## Setup

Install JDK 21 and Maven, then run from this repository:

```bash
export WORKFLOW_OPERATOR_TOKEN='replace-with-a-long-random-secret'
export WORKFLOW_OPERATOR_NAME='engineering-lead'
mvn verify
mvn spring-boot:run
```

For Homebrew Java 21, set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
Workflow APIs and deactivation require the configured bearer token. An empty token
disables access. Use HTTPS for remote access. Audit identity comes from the configured
operator name, never an approval request body.

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

The worker supports **custom aliases on the existing domain**. It analyzes the existing
redirect integration and source hash, then plans an additional `/api/aliases` endpoint
and integration tests. Unsupported requests stop for clarification. Scenario catalog
entries describe examples; they are not three fully implemented agents.

```bash
curl http://localhost:8080/api/workflows \
  -H "Authorization: Bearer $WORKFLOW_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"GREENFIELD","requirement":"Add custom aliases"}'
```

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
create-only Java operations are applied in `workflow-evidence/RUN_ID/workspace`.
The worker runs `mvn --batch-mode --no-transfer-progress verify` with a five-minute
timeout. Validation requires successful exit, at least two passing and unskipped
generated acceptance tests, Surefire reports and JaCoCo coverage evidence.

Inspect the generated source and tests in the workspace before applying them to the
main repository. Evidence includes:

- `operations.json`: generated paths and file contents.
- `evidence.json`: requirement, baseline/outcome hashes, changed files and attempts.
- `attempt-N/maven.log`: full compiler and test output, including failures.
- `attempt-N/test-reports/TEST-*.xml`: discovered tests, failures and durations.
- `attempt-N/jacoco.xml`: executed coverage counters.
- `repair.json`: exact repair operations, when applicable.
- `validated-artifact.jar`: compiled Spring Boot artifact, bound to approval by SHA-256.

A diagnosed constructor-name compiler error permits one targeted repair and retry.
Unknown failures, unavailable Maven and timeout stop execution and remove agent-created
files, verifying the restored baseline source hash. This is a bounded repair policy.

After validation, fetch `approval-evidence` again and approve its new hash to complete
the workflow. The outcome hash binds source, compiled JAR, results, logs, tests and coverage. Changes
to source or generated output invalidate approval. Audit decisions store operator
identity and the exact hash. A completed review does not deploy the workspace.

## Clarification

Ambiguous requests stop before implementation. Submit a revised requirement:

```bash
curl http://localhost:8080/api/workflows/OLD_RUN_ID/clarifications \
  -H "Authorization: Bearer $WORKFLOW_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"requirement":"Add custom aliases on the existing domain"}'
```

This creates a new run and archives the previous run as superseded, removing its pending
approvals. Historical artifacts and decisions remain for audit and cannot authorize
the revised requirement.

## Persistence And Tests

URL mappings, payload-bound keys and clicks use atomic `data/urls.json` snapshots.
Workflow state uses `data/runs/RUN_ID.json`; evidence uses separate per-run directories.
Restart restores these records. Configure `URL_STORAGE_DIRECTORY` for a persistent
volume. Storage supports one process, not shared database transactions across instances.

`mvn verify` runs controller, service, concurrent reservation, restart, authentication,
rate limiting and orchestration tests. Execution tests launch real child Maven builds
and skip themselves inside children to prevent recursion. Repair evidence remains in
`target/execution-review/`; other temporary fixtures are removed. Coverage is in
`target/site/jacoco/index.html`.

The engineering summary derives review readiness from the latest run, its current
evidence hash and recorded outcome approval. No run means `NOT_RELEASE_READY`; changed
evidence means `STALE_EVIDENCE`. Valid completion means `VALIDATED_AND_APPROVED_FOR_REVIEW`,
which is not a production deployment certification.

## Remaining Assessment Work

This revision establishes one connected execution path. Arbitrary requirements remain
unsupported. The seven lifecycle phases are fixed, and planning supports one bounded
capability with repository checks. General requirement decomposition, brownfield refactor
agents, tenant ownership, multiple operator roles and broad repair policies remain open.
Unknown additional acceptance criteria need a new agent capability before implementation.

The worker executes trusted local Maven projects as the service user. A copied directory
is filesystem isolation, not an OS security sandbox. Untrusted repositories require a
containerized worker with network, filesystem and resource policies. Evidence hashes
detect changes but local files are not immutable audit storage. Snapshot persistence and
rate limiting remain process-local. Crash recovery restores recorded state but does not
automatically resume an interrupted Maven run.

The original six-commit history is in [IMPLEMENTATION_STEPS.md](IMPLEMENTATION_STEPS.md).
That narrative describes the earlier model; this README describes current behavior.
