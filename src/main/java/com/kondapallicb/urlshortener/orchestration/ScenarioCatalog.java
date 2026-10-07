package com.kondapallicb.urlshortener.orchestration;

import java.util.List;
import java.util.Optional;

public interface ScenarioCatalog {

    List<ScenarioDemonstration> all();

    Optional<ScenarioDemonstration> findByScenario(WorkflowScenario scenario);
}
