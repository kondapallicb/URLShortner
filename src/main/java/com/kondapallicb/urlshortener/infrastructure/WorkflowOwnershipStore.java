package com.kondapallicb.urlshortener.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.orchestration.*;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

public class WorkflowOwnershipStore {
    public record Claim(String runId, String workerId, long token, long version, long startedAt) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    public WorkflowOwnershipStore(DataSource dataSource, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.mapper = mapper;
        schema(jdbc);
    }
    public static DataSource local(String directory) {
        var ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:file:" + java.nio.file.Path.of(directory).toAbsolutePath().resolve("workflow-db") + ";DB_CLOSE_ON_EXIT=FALSE");
        ds.setUsername("sa"); ds.setPassword("");
        return ds;
    }
    public static void schema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_runs (run_id VARCHAR(100) PRIMARY KEY, version BIGINT NOT NULL, payload TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_tasks (run_id VARCHAR(100) PRIMARY KEY, token BIGINT NOT NULL, version BIGINT NOT NULL, worker_id VARCHAR(200), lease_until BIGINT NOT NULL, state VARCHAR(30) NOT NULL, result TEXT, requirement TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_attempts (run_id VARCHAR(100) NOT NULL, token BIGINT NOT NULL, worker_id VARCHAR(200) NOT NULL, state VARCHAR(30) NOT NULL, journal TEXT, result TEXT, PRIMARY KEY (run_id, token))");
        jdbc.execute("ALTER TABLE workflow_tasks ADD COLUMN IF NOT EXISTS started_at BIGINT NOT NULL DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_validations (run_id VARCHAR(100) NOT NULL, token BIGINT NOT NULL, attempt_number INT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,token,attempt_number))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_audit_key (key_id INT PRIMARY KEY, material TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_proposals (run_id VARCHAR(100) NOT NULL, token BIGINT NOT NULL, proposal_id VARCHAR(100) NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,token,proposal_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_journals (run_id VARCHAR(100) NOT NULL, token BIGINT NOT NULL, journal_id VARCHAR(64) NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,token,journal_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_revisions (child_id VARCHAR(100) PRIMARY KEY, parent_id VARCHAR(100) NOT NULL, requirement_hash VARCHAR(64) NOT NULL)");
    }
    private long now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant().toEpochMilli(); }

    public Optional<Claim> claim(String id, String worker, String requirement, Duration lease) {
        try { jdbc.update("INSERT INTO workflow_tasks(run_id,token,version,lease_until,state,requirement) VALUES (?,0,0,0,'PENDING',?)", id, requirement); }
        catch (DuplicateKeyException existing) { }
        return transaction.execute(status -> {
            long now = now();
            int changed = jdbc.update("UPDATE workflow_tasks SET token=token+1, version=version+1, worker_id=?, lease_until=?, started_at=CASE WHEN started_at=0 THEN ? ELSE started_at END, state='RUNNING' WHERE run_id=? AND state <> 'COMPLETED' AND lease_until<=? AND requirement=?",
                worker, now + lease.toMillis(), now, id, now, requirement);
            if (changed != 1) return Optional.empty();
            Claim claim = jdbc.queryForObject("SELECT token,version,started_at FROM workflow_tasks WHERE run_id=?",
                (rs, index) -> new Claim(id, worker, rs.getLong(1), rs.getLong(2), rs.getLong(3)), id);
            jdbc.update("UPDATE workflow_attempts SET state='SUPERSEDED' WHERE run_id=? AND state='RUNNING'", id);
            jdbc.update("INSERT INTO workflow_attempts(run_id,token,worker_id,state) VALUES (?,?,?,'RUNNING')", id, claim.token(), worker);
            return Optional.of(claim);
        });
    }
    public void heartbeat(Claim claim, Duration lease) {
        long now = now();
        if (jdbc.update("UPDATE workflow_tasks SET lease_until=? WHERE run_id=? AND token=? AND worker_id=? AND lease_until>? AND state='RUNNING'",
            now + lease.toMillis(), claim.runId(), claim.token(), claim.workerId(), now) != 1) throw new StaleWorkerException();
    }
    public void requireOwner(Claim claim) {
        long now = now();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_tasks WHERE run_id=? AND token=? AND worker_id=? AND lease_until>? AND state='RUNNING'",
            Long.class, claim.runId(), claim.token(), claim.workerId(), now);
        if (count == null || count != 1) throw new StaleWorkerException();
    }
    public record EncryptedProposal(String algorithm, String iv, String ciphertext, String originalHash) { }
    public EncryptedProposal archiveProposal(Claim claim, String proposalId, Object proposal) {
        try {
            String original = json(proposal);
            byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, auditKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
            var archive = new EncryptedProposal("AES-256-GCM", Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(cipher.doFinal(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                RequirementHash.of(original));
            transaction.executeWithoutResult(status -> {
                lock(claim);
                jdbc.update("INSERT INTO workflow_proposals(run_id,token,proposal_id,payload) VALUES (?,?,?,?)", claim.runId(), claim.token(), proposalId, json(archive));
            });
            return archive;
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Proposal archival failed", failure); }
    }
    public String decryptProposal(EncryptedProposal archive) {
        try {
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, auditKey(), new javax.crypto.spec.GCMParameterSpec(128, Base64.getDecoder().decode(archive.iv())));
            String original = new String(cipher.doFinal(Base64.getDecoder().decode(archive.ciphertext())), java.nio.charset.StandardCharsets.UTF_8);
            if (!RequirementHash.of(original).equals(archive.originalHash())) throw new IllegalStateException("Proposal hash mismatch");
            return original;
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Invalid proposal archive", failure); }
    }
    private javax.crypto.spec.SecretKeySpec auditKey() {
        var values = jdbc.queryForList("SELECT material FROM workflow_audit_key WHERE key_id=1", String.class);
        if (values.isEmpty()) {
            byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
            try { jdbc.update("INSERT INTO workflow_audit_key(key_id,material) VALUES (1,?)", Base64.getEncoder().encodeToString(key)); }
            catch (DuplicateKeyException concurrent) { }
            values = jdbc.queryForList("SELECT material FROM workflow_audit_key WHERE key_id=1", String.class);
        }
        return new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(values.getFirst()), "AES");
    }

    public void validation(Claim claim, int number, Object evidence) {
        transaction.executeWithoutResult(status -> {
            lock(claim);
            jdbc.update("INSERT INTO workflow_validations(run_id,token,attempt_number,payload) VALUES (?,?,?,?)", claim.runId(), claim.token(), number, json(evidence));
        });
    }
    public void journal(Claim claim, GovernedFileService.Journal journal) {
        transaction.executeWithoutResult(status -> {
            // Row lock couples fencing validation and journal persistence in the same transaction.
            lock(claim);
            String payload = json(journal);
            int changed = jdbc.update("UPDATE workflow_journals SET payload=? WHERE run_id=? AND token=? AND journal_id=?", payload, claim.runId(), claim.token(), journal.journalId());
            if (changed == 0) jdbc.update("INSERT INTO workflow_journals(run_id,token,journal_id,payload) VALUES (?,?,?,?)", claim.runId(), claim.token(), journal.journalId(), payload);
            jdbc.update("UPDATE workflow_attempts SET journal=? WHERE run_id=? AND token=?", json(journal), claim.runId(), claim.token());
        });
    }
    private void lock(Claim claim) {
        jdbc.queryForObject("SELECT token FROM workflow_tasks WHERE run_id=? FOR UPDATE", Long.class, claim.runId());
        requireOwner(claim);
    }
    public void finish(Claim claim, ExecutionEvidence evidence) {
        String payload = json(evidence);
        transaction.executeWithoutResult(status -> {
            var current = jdbc.queryForMap("SELECT token,worker_id,state,result,lease_until FROM workflow_tasks WHERE run_id=? FOR UPDATE", claim.runId());
            if (current.get("state").equals("COMPLETED") && ((Number) current.get("token")).longValue() == claim.token()
                    && Objects.equals(current.get("worker_id"), claim.workerId()) && payload.equals(current.get("result"))) return;
            requireOwner(claim);
            if (jdbc.update("UPDATE workflow_tasks SET state='COMPLETED',result=?,version=version+1 WHERE run_id=? AND token=? AND version=?",
                payload, claim.runId(), claim.token(), claim.version()) != 1) throw new StaleWorkerException();
            jdbc.update("UPDATE workflow_attempts SET state='COMPLETED',result=? WHERE run_id=? AND token=?", payload, claim.runId(), claim.token());
        });
    }
    public Optional<ExecutionEvidence> completed(String id) {
        var rows = jdbc.queryForList("SELECT result FROM workflow_tasks WHERE run_id=? AND state='COMPLETED'", String.class, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(read(rows.getFirst(), ExecutionEvidence.class));
    }
    public List<String> expired() {
        return jdbc.queryForList("SELECT run_id FROM workflow_tasks WHERE state='RUNNING' AND lease_until<=?", String.class, now());
    }
    public List<GovernedFileService.Journal> journals(String id) {
        return jdbc.queryForList("SELECT payload FROM workflow_journals WHERE run_id=? ORDER BY token", String.class, id).stream()
            .map(value -> read(value, GovernedFileService.Journal.class)).toList();
    }
    public List<Map<String, Object>> attempts(String id) {
        return jdbc.queryForList("SELECT token,worker_id,state,journal,result FROM workflow_attempts WHERE run_id=? ORDER BY token", id);
    }
    public void lineage(String parent, String child, String requirement) {
        jdbc.update("INSERT INTO workflow_revisions(child_id,parent_id,requirement_hash) VALUES (?,?,?)", child, parent, RequirementHash.of(requirement));
    }
    public Optional<String> parent(String child) {
        var rows = jdbc.queryForList("SELECT parent_id FROM workflow_revisions WHERE child_id=?", String.class, child);
        return rows.stream().findFirst();
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); } catch (Exception error) { throw new IllegalStateException("Invalid durable outcome", error); }
    }
    public static class StaleWorkerException extends IllegalStateException {
        public StaleWorkerException() { super("Ownership lease or fencing token is stale"); }
    }
    private static class RequirementHash {
        static String of(String value) {
            try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }
}
