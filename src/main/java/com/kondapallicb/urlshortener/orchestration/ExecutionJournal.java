package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

public class ExecutionJournal {
    public enum Phase { PROPOSE, VALIDATE_PROPOSAL, APPLY, BUILD, PARSE_EVIDENCE, DIAGNOSE, REPAIR, FINALIZE }
    public enum Outcome { VALIDATED, REQUIREMENT_UNSUPPORTED, POLICY_REJECTED, STALE_INPUT, APPLICATION_FAILED,
        COMPILATION_FAILED, TEST_FAILED, BUILD_TIMEOUT, TOOL_UNAVAILABLE, EVIDENCE_INVALID, ROLLBACK_FAILED, CANCELLED, PROPOSAL_FAILED }
    public record Terminal(Outcome outcome, int attempt, Phase phase, String diagnosis, String recoveryDecision,
        boolean rollbackVerified, boolean evidencePersisted, Instant recordedAt) { }
    private final GovernedFileService persistence;
    public ExecutionJournal(ObjectMapper mapper) { persistence = new GovernedFileService(mapper); }
    public Lifecycle lifecycle(Path directory) { return new Lifecycle(directory); }
    public ExecutionEvidence unsupported(Path directory, String id, String requirement, RuntimeException cause) {
        try {
            Files.createDirectories(directory);
            var evidence = new ExecutionEvidence(id, requirement, null, null, null, List.of(), List.of(), false,
                "SAFE_STOPPED", null, null, cause.toString(), null, null);
            persistence.persist(directory.resolve("evidence.json"), evidence);
            persistence.persist(directory.resolve("terminal.json"), new Terminal(Outcome.REQUIREMENT_UNSUPPORTED, 0,
                Phase.PROPOSE, cause.toString(), "NO_MUTATION_CLARIFY", false, true, Instant.now()));
            return evidence;
        } catch (IOException failure) { throw new IllegalStateException("Evidence storage failed; no readiness", failure); }
    }
    public class Lifecycle {
        private final Path directory;
        private Phase phase = Phase.PROPOSE;
        private int attempt;
        private Outcome outcome;
        private String diagnosis;
        Lifecycle(Path directory) { this.directory = directory; }
        public void phase(Phase value) throws IOException {
            phase = value;
            persistence.persist(directory.resolve("coordinator.json"), new Terminal(outcome, attempt, phase, diagnosis,
                "IN_PROGRESS", false, false, Instant.now()));
        }
        public void attempt(int number) { attempt = number; }
        public void failure(Exception failure) {
            diagnosis = failure.toString();
            outcome = failure instanceof GovernedFileService.StaleInputException ? Outcome.STALE_INPUT
                : phase == Phase.PROPOSE ? Outcome.PROPOSAL_FAILED
                : phase == Phase.VALIDATE_PROPOSAL ? Outcome.POLICY_REJECTED
                : phase == Phase.APPLY || phase == Phase.REPAIR ? Outcome.APPLICATION_FAILED
                : phase == Phase.PARSE_EVIDENCE || phase == Phase.FINALIZE ? Outcome.EVIDENCE_INVALID : Outcome.TOOL_UNAVAILABLE;
        }
        public void rollbackFailure(Exception error) { outcome = Outcome.ROLLBACK_FAILED; diagnosis = error.toString(); }
        public void finish(boolean valid, boolean restored, String reason, List<ExecutionEvidence.ValidationAttempt> attempts) throws IOException {
            if (valid) outcome = Outcome.VALIDATED;
            else if (outcome == null && !attempts.isEmpty()) {
                var last = attempts.getLast();
                outcome = last.cancelled() ? Outcome.CANCELLED : last.timedOut() ? Outcome.BUILD_TIMEOUT : last.exitCode() < 0
                    ? Outcome.TOOL_UNAVAILABLE : Files.readString(Path.of(last.log())).contains("COMPILATION ERROR")
                    ? Outcome.COMPILATION_FAILED : Files.readString(Path.of(last.log())).contains("Coverage checks have not been met")
                    ? Outcome.EVIDENCE_INVALID : Outcome.TEST_FAILED;
            }
            if (outcome == null) outcome = Outcome.EVIDENCE_INVALID;
            persistence.persist(directory.resolve("terminal.json"), new Terminal(outcome, attempt, phase,
                diagnosis == null ? reason : diagnosis, valid ? "AWAIT_EXACT_APPROVAL" : restored ? "VERIFIED_ROLLBACK" : "SAFE_STOP_MANUAL_RECOVERY",
                restored, true, Instant.now()));
        }
    }
}
