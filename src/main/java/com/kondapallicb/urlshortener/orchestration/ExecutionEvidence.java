package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record ExecutionEvidence(String runId, String requirement, String baselineHash,
        String outcomeHash, String workspace, List<String> changedFiles,
        List<ValidationAttempt> attempts, boolean rolledBack, String readiness,
        String buildArtifact, String buildArtifactHash, String failureReason, String policyReport, String isolation) {
    public ExecutionEvidence(String runId, String requirement, String baselineHash, String outcomeHash,
            String workspace, List<String> changedFiles, List<ValidationAttempt> attempts, boolean rolledBack,
            String readiness, String buildArtifact, String buildArtifactHash) {
        this(runId, requirement, baselineHash, outcomeHash, workspace, changedFiles, attempts, rolledBack,
                readiness, buildArtifact, buildArtifactHash, null, null, null);
    }
    public record ValidationAttempt(int exitCode, boolean timedOut, String log,
            List<String> testReports, String coverageReport, boolean cancelled) {
        public ValidationAttempt(int exitCode, boolean timedOut, String log, List<String> testReports, String coverageReport) {
            this(exitCode, timedOut, log, testReports, coverageReport, false);
        }
    }
}
