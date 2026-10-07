package com.kondapallicb.urlshortener.orchestration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public final class PlanValidator {
    public void validate(List<EngineeringPlan.Task> tasks, RequirementSpec spec) {
        var byId = new HashMap<String, EngineeringPlan.Task>();
        var writes = new HashSet<String>();
        var covered = new HashSet<RequirementSpec.Criterion>();
        for (var task : tasks) {
            if (byId.put(task.id(), task) != null) throw new IllegalArgumentException("Duplicate task ID");
            for (String path : task.writeScope()) if (!writes.add(path)) throw new IllegalArgumentException("Conflicting task writes: " + path);
            if (task.agentRole().equals("TESTING")) covered.addAll(task.criteria());
            if (task.maxAttempts() < 1 || task.requirementIds().isEmpty()) throw new IllegalArgumentException("Incomplete task contract");
        }
        var visiting = new HashSet<String>();
        var visited = new HashSet<String>();
        for (String id : byId.keySet()) visit(id, byId, visiting, visited);
        if (!covered.containsAll(spec.acceptanceCriteria())) throw new IllegalArgumentException("Missing behavioral criterion test coverage");
    }
    private void visit(String id, java.util.Map<String, EngineeringPlan.Task> tasks, java.util.Set<String> visiting, java.util.Set<String> visited) {
        if (!tasks.containsKey(id)) throw new IllegalArgumentException("Unknown dependency: " + id);
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Task dependency cycle");
        for (String parent : tasks.get(id).dependsOn()) visit(parent, tasks, visiting, visited);
        visiting.remove(id); visited.add(id);
    }
}
