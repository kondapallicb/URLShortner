package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record ExecutionEvidence(String runId, String requirement, String baselineHash,
        String outcomeHash, String workspace, List<String> changedFiles,
        List<ValidationAttempt> attempts, boolean rolledBack, String readiness,
        String buildArtifact, String buildArtifactHash) {
    public record ValidationAttempt(int exitCode, boolean timedOut, String log,
            List<String> testReports, String coverageReport) { }
}
