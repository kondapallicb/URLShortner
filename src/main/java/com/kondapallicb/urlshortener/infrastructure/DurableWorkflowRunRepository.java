package com.kondapallicb.urlshortener.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kondapallicb.urlshortener.orchestration.WorkflowRun;
import com.kondapallicb.urlshortener.orchestration.WorkflowRunRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class DurableWorkflowRunRepository implements WorkflowRunRepository {
    private final Path directory;
    private final ObjectMapper mapper;
    public DurableWorkflowRunRepository(@Value("${app.storage.directory:./data}") String directory, ObjectMapper mapper) {
        this.directory = Path.of(directory).resolve("runs").toAbsolutePath();
        this.mapper = mapper;
    }
    private Path path(String id) {
        if (!id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid run ID");
        return directory.resolve(id + ".json");
    }
    @Override public synchronized WorkflowRun save(WorkflowRun run) {
        Path target = path(run.runId());
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "run-", ".tmp");
            mapper.writeValue(temporary.toFile(), run);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return run;
        } catch (IOException exception) { throw new IllegalStateException("Cannot persist workflow", exception); }
    }
    @Override public synchronized Optional<WorkflowRun> findById(String id) {
        Path target = path(id);
        if (!Files.exists(target)) return Optional.empty();
        try { return Optional.of(mapper.readValue(target.toFile(), WorkflowRun.class)); }
        catch (IOException exception) { throw new IllegalStateException("Cannot read workflow", exception); }
    }
    @Override public synchronized java.util.List<WorkflowRun> all() {
        if (!Files.isDirectory(directory)) return java.util.List.of();
        try (var paths = Files.list(directory)) {
            var runs = new java.util.ArrayList<WorkflowRun>();
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                runs.add(mapper.readValue(path.toFile(), WorkflowRun.class));
            }
            return java.util.List.copyOf(runs);
        } catch (IOException exception) { throw new IllegalStateException("Cannot read workflow history", exception); }
    }
}
