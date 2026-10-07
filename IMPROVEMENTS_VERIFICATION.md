# Six Improvements: Implementation And Verification

This record covers the implementation requirements supplied for the execution plane. Existing URL endpoints remain connected to their original service and repository. Deterministic agents generate real files; explanatory stage output is not evidence of a successful build or release.

## Verification Environment

- Java 21: `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- Maven with the warmed offline repository `/private/tmp/urlshortener-m2`.
- macOS Seatbelt isolates every generated Maven execution. Network access is denied; writes are confined to the attempt workspace; credentials and the workflow database are not exposed to the build.
- PostgreSQL 16 test server: `jdbc:postgresql://127.0.0.1:55432/postgres`, user `workflow_test`, temporary local test cluster. This is not a production database credential or deployment configuration.
- Durable H2 is the local fallback; PostgreSQL is the supported shared workflow store. Multiple workers also need the same approved repository and shared evidence volume.

## Stage 1: Explicit Requirement Interpretation

Implemented in `RequirementInterpretation`, `RequirementInterpreter`, `RequirementSpec`, `RequirementPlanner`, and the alias implementation/testing agents.

Interpretation retains the original text, requirement ID, normalized capabilities, stable acceptance IDs, parameter values and their sources, recorded assumptions, questions, unsupported clauses, and readiness status. Strict JSON or the documented bounded natural-language grammar is accepted. Unrecognized clauses are never silently discarded. Recorded defaults are policy-controlled. Production and tests consume the same normalized options.

Proof tests: `RequirementInterpreterTest`, `RequirementPlannerTest`, and `WorkspaceExecutionServiceTest.interpretsNaturalLanguageAndExecutesControlledExpiryProof`. The latter creates real Spring code and tests for minimum 8, maximum 20, and one-hour expiry: 7 and 21 reject; 8 and 20 accept; an injected mutable clock proves 302 before expiry and 410 at expiry. Case, alphabet, reserved aliases, and duplicate behavior are also tested. The undefined ownership clause stops before application mutation and retains specific questions.

Persisted evidence: `interpretation.json`, `plan.json`, generated acceptance source, Surefire XML, JaCoCo XML, and structured `validation.json` under `target/assessment-proof/hour-expiry/` and the stopped-outcome proof directories.

Limitation: this is an explicit bounded interpreter, not general natural-language understanding. Unsupported ownership protocols, case-insensitive aliases, and alternative duplicate/redirect statuses require clarification or another implementation capability.

## Stage 2: Repository-Specific Plans And Revisions

Implemented in `RequirementPlanner`, `EngineeringPlan`, `PlanValidator`, `WorkflowGraph`, `GreenfieldAgent`, and `DefaultWorkflowEngine`.

Java compiler AST analysis identifies actual entry points, service/repository boundaries, methods, tests, and build files. The planner combines normalized criteria, repository capabilities, agent capabilities, and policies. Tasks declare IDs, roles, traceability, dependencies, inputs, outputs, scopes, gates, and recovery limits. Invalid dependencies, cycles, conflicting writes, and missing acceptance coverage reject before execution.

Brownfield snapshots the approved source/build/documentation and runs its regression suite. Greenfield starts with empty application source and generates its own minimal build, application, repository, redirect path, endpoint, tests, and documentation. A scenario label does not determine repository mode or force a predefined ambiguity response. Clarification supersedes the old revision, removes its current approval eligibility, creates explicit parent lineage, and replans from current inputs.

Proof tests: `RequirementPlannerTest`, `PlanContractTest`, `DefaultWorkflowEngineTest`, `WorkspaceExecutionServiceTest.greenfieldGeneratesAndValidatesApplicationFromEmptySource`, and its real clarification/approval invalidation test.

Persisted evidence: plans, approved baseline snapshots, `GENERATED_README.md`, revision rows in `workflow_revisions`, and `target/assessment-proof/greenfield/`.

Limitation: available implementation capabilities are custom aliases and brownfield UTC daily analytics. Greenfield daily analytics requires a click-history capability and stops explicitly. No stale artifact reuse is attempted; conservative regeneration is used.

## Stage 3: Governed File Operations

Implemented in `FileOperation`, `GovernedFileService`, and `WorkflowOwnershipStore` proposal/journal persistence. CREATE, UPDATE, and DELETE include the full contract and current revision/input hashes. Agent intents cannot mutate the workspace themselves.

The complete proposal is validated before application: approved roots, traversal and symlink rejection, duplicate paths and IDs, permitted types, count/size limits, task scope, expected existence/hashes, traceability, and current inputs. Files are staged; replacements use atomic rename and fsync. Backups and durable per-batch journals support recovery. Multi-file application is not described as atomic: a failure restores and verifies the original touched-file hashes. The coordinator additionally restores the full approved baseline on a terminal execution failure.

Repairs use the same proposal validation, policy evaluation, staging, backup, diff, and journal pipeline. Original proposals are archived with authenticated encryption so rejected secret-bearing content is not stored as plaintext. Accepted normalized operations remain reviewable.

Proof tests: `GovernedFileServiceTest` covers traversal, absolute paths, symlinks, duplicates, stale revisions/hashes, oversized content, scope violations, CREATE conflicts, mid-batch failure, UPDATE restoration, and DELETE restoration. `WorkspaceExecutionServiceTest` exercises complete baseline restoration and real governed compiler/test repairs.

Persisted evidence: encrypted original proposal, `operations.json`, `application/proposal.json`, `validation.json`, `changes.diff`, `journal.json`, staged files, and backups. Repair batches have separate audit directories. Journals/proposals are also transactionally stored.

Limitations: 100 files, 1 MiB per file, 5 MiB per proposal; approved textual file types only. Windows reparse points are not supported because execution is macOS-only. Audit encryption keys are protected by the workflow database boundary, not an external KMS.

## Stage 4: Centralized Execution And Honest Failure Outcomes

`ExecutionCoordinator` owns interpretation, snapshot, proposal, validation, journaling, application, fixed build execution, diagnosis, repair, finalization, ownership, and rollback. `WorkspaceExecutionService` is its Spring facade; `ExecutionJournal` records lifecycle progress and classified outcomes.

Proposal/application/startup/test/parsing/finalization failures terminate honestly. Retry count and total elapsed time are bounded; repairs depend on discovered diagnostics and affected generated files. Unknown failures stop. Failed attempts retain separate logs/reports. Storage failure blocks readiness, leaves recovery information, and does not publish an uncommitted evidence file as authoritative. Rollback failure is distinctly recorded rather than reported as success.

Proof tests: `ExecutionFailureTest` injects build startup, report parsing, finalization, evidence storage, and rollback failures. `WorkspaceExecutionServiceTest` covers mid-application failure, real compiler repair, failed acceptance repair without weakening tests, zero-retry policy, timeouts, cancellation, forbidden changes, and missing Maven.

Persisted evidence: `coordinator.json`, `terminal.json`, attempt logs, validation records, repair proposals/journals, and database attempts. The retained end-to-end demonstration includes a nonzero real compilation exit followed by a successful diagnosis-driven repaired build.

Limitations: deterministic repairs address the documented generated alias/analytics constructor and response errors. Arbitrary repository failures are not repaired automatically. Each build is limited to five minutes, total execution to ten minutes, and output to 2 MiB.

## Stage 5: Executed Evidence And Exact Approvals

Implemented in `SandboxRunner`, `ValidationEvidenceReader`, `PolicyEvaluator`, `ExecutionEvidence`, `EngineeringSummaryService`, authentication, and approval handling. Builds always use the configured trusted Maven capability with offline `clean verify`; agents cannot supply commands.

JaCoCo enforcement requires generated endpoint classes to meet 80% line and 70% branch coverage. Generated bootstrap production classes must compile and have positive exercised-line evidence. Secure XML parsing records exact fully qualified classes, method names, statuses, failures, skipped totals, coverage, source/compiled artifacts, and criterion-to-production-to-test mappings. Missing, malformed, deleted, renamed, skipped, or excluded required-test evidence blocks acceptance. The coordinator also rejects source changes made by the build outside the governed operation pipeline.

Build metadata includes capability/version, timestamps, duration, exit/timeout, bounded merged output, and truncation state. Approval hashes bind current inputs, source, plan, policies, operations, journals, structured validation, reports, logs, and build artifacts. Authenticated operator and independent security identities approve exact evidence. Final readiness is derived from current connected criteria, executed tests, passing policies, and current approvals, never a static POC label.

Proof tests: four required-test sabotage cases and an actual JaCoCo-threshold build failure in `ExecutionFailureTest`; source-tamper and clarification checks in `WorkspaceExecutionServiceTest`; the fully approved `AssessmentDemonstrationTest`.

Persisted evidence: `attempt-N/build.json`, `maven.log`, structured validation, Surefire/JaCoCo reports, JAR hashes, policy report, final evidence, and authenticated approval audit records.

Limitations: generated engineering outcomes are review-ready, not automatically deployed. Fixed Maven is used instead of a downloaded wrapper; dependencies must already be in the approved cache. macOS Seatbelt is the only tested OS isolation backend; unsupported platforms fail closed.

## Stage 6: Durable Ownership And Crash Recovery

Implemented in `WorkflowOwnershipStore`, `DurableWorkflowRunRepository`, workflow version fields, coordinator ownership/reconciliation, and startup reconciliation in `DefaultWorkflowEngine`.

SQL transactions persist workflow state and approval audits, attempts, validation, journals, proposal archives, and revision lineage. Atomic claims identify workers, expire leases, renew heartbeats, increment fencing tokens, enforce versioned state transitions, and finalize identical results idempotently. A stale worker cannot journal or finalize a replacement attempt. Each ownership token gets an isolated workspace, preventing stale writes from changing the replacement workspace.

An interrupted directory triggers persisted plan/journal/baseline inspection and source-hash verification. Incomplete evidence is invalidated. Recovery conservatively restores the approved baseline in a fresh isolated workspace and rebuilds within the original recovery budget. Startup finds durable RUNNING workflows and advances them through recovery; it never manufactures a release approval.

Proof tests: `WorkflowOwnershipStoreTest` uses two contending connections, expiry/takeover, stale finalization/journal rejection, heartbeats, idempotence, version conflicts, encrypted proposal recovery, durable approvals, and lineage. `CrashRecoveryTest` actually kills a separate JVM after partial application, recovers with token 2, checks journals, writes into the old workspace to prove isolation, and verifies automatic startup reconciliation requires a fresh security approval. PostgreSQL is exercised by the final command below.

Limitations: legacy workflow JSON history is not automatically migrated to SQL; retain historical backups. Shared evidence storage is required for cross-host recovery. Existing URL mapping storage/rate limiting remains distinct from workflow ownership and is not presented as a multi-node transactional URL service. Recovery restarts verified work rather than resuming an arbitrary JVM instruction.

## Commands And Results

Use the Java path above and a warmed cache. The exact final regression command is:

```sh
caffeinate -i -s env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn --offline --batch-mode --no-transfer-progress -Dmaven.repo.local=/private/tmp/urlshortener-m2 '-Dworkflow.test.jdbc=jdbc:postgresql://127.0.0.1:55432/postgres' clean verify -l /private/tmp/url-checkin-final-verify.log
```

Focused runs used the same environment/options, `test`, and the following selectors:

| Stage | Selector | Observed Result |
| --- | --- | --- |
| 1 | `RequirementInterpreterTest,RequirementPlannerTest` | 6 tests passed in the initial focused run |
| 1-2 | Controlled-expiry and empty-source greenfield methods of `WorkspaceExecutionServiceTest` | Both real Maven proofs passed |
| 2-3,6 | `PlanContractTest,GovernedFileServiceTest,WorkflowOwnershipStoreTest,DefaultWorkflowEngineTest` | 8 tests passed before later additional assertions |
| 4-6 | `ExecutionFailureTest,CrashRecoveryTest` | 11 tests passed before additional rollback/startup proofs |
| 6 | `CrashRecoveryTest#startupReconcilesPersistedRunningWorkflowAndRequiresNewSecurityApproval,WorkflowOwnershipStoreTest` | 3 passed, zero failures/errors/skips after correcting heartbeat-test startup timing |
| Final demonstration | `AssessmentDemonstrationTest` | Passed; retained repaired build and exact approved durable outcome |

Focused logs are retained in `/private/tmp/url-stage1.log`, `/private/tmp/url-proof12.log`, `/private/tmp/url-stage3.log`, `/private/tmp/url-failures.log`, `/private/tmp/url-startup-fixed.log`, and `/private/tmp/url-demo.log`. The last demo log also honestly retains the earlier failed recovery check that was corrected; it is not a clean full-suite certificate.

Final `clean verify` result: **BUILD SUCCESS**, completed October 7, 2026 at 08:14:18 CDT in **11 minutes 26 seconds**. Proper XML aggregation of the 19 top-level Surefire reports confirmed **71 tests, zero failures, zero errors, zero skips**. Maven reported that all coverage checks were met. The 11 failure tests, 12 workspace execution tests, two killed-worker recovery tests, and the approved end-to-end demonstration all passed in this run. `git diff --check` also passed.

The first full run was interrupted by real laptop sleep: the low-coverage build failed its 80% threshold as intended, but the suspended worker also lost its lease. The preserved `/private/tmp/url-sleep-interrupted-verify.log` is a failed/interrupted run, not a success certificate. A subsequent run was deliberately stopped to correct outdated engineering-summary descriptions before final compilation. The successful final run used `caffeinate` to keep the host awake without changing execution leases or fencing rules. The final log and top-level `target/surefire-reports` are authoritative. Recursive execution-plane tests intentionally do not recurse inside sandbox child builds; all generated behavioral acceptance methods executed successfully and no top-level test was skipped.

Independent post-build verification rehashed the approved demonstration's two generated Java sources, 21 report artifacts, and retained JAR with Ruby's standard SHA-256 implementation. All matched the persisted hashes. All five criterion mappings recorded executed tests. The demonstration retained actual attempt exit codes `[1, 0]`, workflow state `COMPLETED`, readiness `VALIDATED_AND_APPROVED_FOR_REVIEW`, and architecture, independent security, and release approvals.

The exact additional durable-state check was:

```sh
/opt/homebrew/opt/postgresql@16/bin/psql -h 127.0.0.1 -p 55432 -U workflow_test -d postgres -c "SELECT run_id, version, payload::jsonb->>'state' AS workflow_state FROM workflow_runs WHERE run_id='9d764520-ecca-4605-9202-2d2006ca14a0'; SELECT token, state FROM workflow_attempts WHERE run_id='9d764520-ecca-4605-9202-2d2006ca14a0' ORDER BY token; SELECT state, token FROM workflow_tasks WHERE run_id='9d764520-ecca-4605-9202-2d2006ca14a0';"
```

It confirmed workflow version 10, `WAITING_FOR_APPROVAL`, attempt token 1 `SUPERSEDED`, attempt token 2 `COMPLETED`, and finalized task ownership at token 2. Recovery therefore persisted validated execution without manufacturing release approval. The temporary PostgreSQL server is stopped after verification; its cluster data remains at `/private/tmp/url-workflow-pg` for review. To inspect it again locally:

```sh
/opt/homebrew/opt/postgresql@16/bin/pg_ctl -D /private/tmp/url-workflow-pg -l /private/tmp/url-workflow-pg.log -o '-p 55432 -h 127.0.0.1 -k /private/tmp' start
```

## Inspect The Complete Demonstration

After `clean verify`, inspect `target/assessment-proof/approved-outcome/<id>/demonstration.json`. It links the requirement, explicit interpretation, repository-specific plan, governed operations, real failed/successful build attempts, exact approvals, and restarted durable summary. Its sibling `evidence/<run-id>/` contains baseline/workspace snapshots and the reviewable audit artifacts. Other retained proof directories hold hour expiry, greenfield, failure, and killed-worker recovery evidence. `target/` is intentionally ignored by Git; paths are preserved because evidence/approval hashes bind the actual artifacts.

The successful final demonstration is [demonstration.json](target/assessment-proof/approved-outcome/121e19c8-25c2-4113-8964-3a9ef9aacb2a/demonstration.json). The proof folders will be replaced by another `clean` build, so review the retained evidence before rerunning that command.

## Approved Pre-Commit Checks

After user approval, four trailing blank lines in new files were removed and the full suite was rerun. The current source fingerprint was independently recomputed across all 104 source/build/documentation input files. It still matched the successful 71-test build: `75e991e1bdddabb5f121c0faa1ff4923dfd255db4405c0e53b7fe341cd555adc`.

The packaged Spring Boot JAR was started on a temporary localhost port with isolated data in `/private/tmp/url-checkin-final-smoke-1791378895909`. It reported successful startup and actuator health `UP`. Twelve live HTTP checks passed: health, idempotent URL creation, changed-payload conflict, alias collision, redirect, UTC analytics, unauthenticated workflow rejection, security role separation, the parameterized dynamic plan, interpreted criteria, unsupported-clause safe stop, and authenticated deactivation with HTTP 410.

The JAR was stopped and restarted against the same data. Five additional checks passed: health, persisted workflow state, current approval hash, persisted URL deactivation, and persisted analytics. Workflow `c4a54d4c-ccb7-4820-9543-829fce507aa0` retained version 4 and its architecture approval gate. Both temporary server processes were then stopped. These smoke checks supplement the full Maven suite; they do not replace it.

Final whitespace validation and a credential-pattern scan passed. GitHub fetch succeeded using the existing configured GitHub identity explicitly; local `main` and fetched `origin/main` matched before the commit. Generated runtime evidence, temporary data, and build output remain excluded from Git.

No threshold was lowered and no failing behavioral test was disabled to obtain acceptance. The user approved check-in after reviewing the six implementation stages.
