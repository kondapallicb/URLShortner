package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

public class RequirementPlanner {
    private final ObjectMapper mapper;
    public RequirementPlanner(ObjectMapper mapper) { this.mapper = mapper; }

    public RequirementSpec parse(String requirement) { return parse(requirement, true); }
    public RequirementSpec parse(String requirement, boolean defaultsPermitted) {
        var interpretation = new RequirementInterpreter(mapper).interpret(requirement, defaultsPermitted);
        if (interpretation.status() != RequirementInterpretation.Status.READY) {
            throw new IllegalArgumentException("Clarification required: " + interpretation.unresolvedQuestions() + interpretation.unsupportedClauses());
        }
        validate(interpretation.normalized());
        return interpretation.normalized();
    }

    private void validate(RequirementSpec spec) {
        if (spec.capabilities() == null || spec.capabilities().isEmpty()
                || spec.acceptanceCriteria() == null || spec.acceptanceCriteria().isEmpty()) {
            throw new IllegalArgumentException("Capabilities and acceptance criteria are required");
        }
        if (spec.capabilities().stream().distinct().count() != spec.capabilities().size()
                || spec.capabilities().stream().anyMatch(java.util.Objects::isNull)
                || spec.acceptanceCriteria().stream().anyMatch(java.util.Objects::isNull)
                || spec.acceptanceCriteria().stream().distinct().count() != spec.acceptanceCriteria().size()) {
            throw new IllegalArgumentException("Duplicate capabilities or criteria");
        }
        if (spec.capabilities().contains(RequirementSpec.Capability.CUSTOM_ALIAS)) {
            var options = spec.alias();
            if (options == null || options.alphabet() == null || options.minLength() < 3 || options.maxLength() > 64
                    || options.maxLength() < options.minLength() || options.ttlSeconds() < 60 || options.ttlSeconds() > 31536000) {
                throw new IllegalArgumentException("Alias length must be within 3..64, TTL within 60..31536000 and alphabet explicit");
            }
        } else if (spec.alias() != null) throw new IllegalArgumentException("Alias options without alias capability");
        for (var criterion : spec.acceptanceCriteria()) {
            if (!spec.capabilities().contains(capability(criterion))) throw new IllegalArgumentException("Criterion has no implementing capability: " + criterion);
        }
        for (var capability : spec.capabilities()) {
            if (spec.acceptanceCriteria().stream().noneMatch(c -> capability(c) == capability)) {
                throw new IllegalArgumentException("Every capability requires acceptance criteria");
            }
        }
    }

    private RequirementSpec.Capability capability(RequirementSpec.Criterion criterion) {
        return switch (criterion) {
            case UTC_DAY_BOUNDARIES, UNKNOWN_SLUG_REJECTED -> RequirementSpec.Capability.UTC_DAILY_ANALYTICS;
            default -> RequirementSpec.Capability.CUSTOM_ALIAS;
        };
    }

    public EngineeringPlan analyze(RequirementSpec spec, Path repository, String baselineHash) throws IOException {
        return analyze(spec, repository, baselineHash, GovernancePolicy.defaultPolicy(),
            new RequirementInterpreter(mapper).interpret(mapper.writeValueAsString(spec), true));
    }
    public EngineeringPlan analyze(RequirementSpec spec, Path repository, String baselineHash,
            GovernancePolicy policy, RequirementInterpretation interpretation) throws IOException {
        spec = interpretation.normalized();
        validate(spec);
        boolean greenfield;
        Path main = repository.resolve("src/main/java");
        if (!Files.exists(main)) greenfield = true;
        else try (var paths = Files.walk(main)) { greenfield = paths.noneMatch(p -> p.toString().endsWith(".java")); }
        if (greenfield && spec.capabilities().contains(RequirementSpec.Capability.UTC_DAILY_ANALYTICS))
            throw new IllegalArgumentException("Greenfield daily analytics requires a declared click-history capability; unsupported");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Repository analysis requires a JDK, not a JRE");
        MapBuilder symbols = new MapBuilder();
        try (var manager = compiler.getStandardFileManager(null, null, null);
                var paths = greenfield ? java.util.stream.Stream.<Path>empty() : Files.walk(repository.resolve("src/main/java"))) {
            var files = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            JavacTask task = (JavacTask) compiler.getTask(new StringWriter(), manager, diagnostics,
                    List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(files));
            for (var unit : files.isEmpty() ? List.<com.sun.source.tree.CompilationUnitTree>of() : task.parse()) {
                String path = repository.relativize(Path.of(unit.getSourceFile().toUri())).toString();
                List<String> names = new ArrayList<>();
                new TreeScanner<Void, Void>() {
                    @Override public Void visitAnnotation(com.sun.source.tree.AnnotationTree tree, Void unused) {
                        names.add("@" + tree.getAnnotationType());
                        return super.visitAnnotation(tree, unused);
                    }
                    @Override public Void visitClass(ClassTree tree, Void unused) {
                        names.add(tree.getSimpleName().toString());
                        return super.visitClass(tree, unused);
                    }
                    @Override public Void visitMethod(MethodTree tree, Void unused) {
                        names.add(tree.getName().toString());
                        return super.visitMethod(tree, unused);
                    }
                    @Override public Void visitMethodInvocation(com.sun.source.tree.MethodInvocationTree tree, Void unused) {
                        if (tree.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree member) {
                            names.add(member.getIdentifier().toString());
                        } else if (tree.getMethodSelect() instanceof com.sun.source.tree.IdentifierTree identifier) {
                            names.add(identifier.getName().toString());
                        }
                        return super.visitMethodInvocation(tree, unused);
                    }
                    @Override public Void visitVariable(VariableTree tree, Void unused) {
                        names.add(tree.getName().toString());
                        return super.visitVariable(tree, unused);
                    }
                }.scan(unit, null);
                symbols.values.put(path, List.copyOf(names));
            }
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
                throw new IllegalStateException("Repository has syntax errors: " + diagnostics.getDiagnostics());
            }
        }
        List<EngineeringPlan.Task> tasks = new ArrayList<>();
        var tests = new EnumMap<RequirementSpec.Criterion, String>(RequirementSpec.Criterion.class);
        List<String> classes = new ArrayList<>();
        for (var capability : spec.capabilities()) {
            var criteria = spec.acceptanceCriteria().stream().filter(c -> capability(c) == capability).toList();
            String id = capability.name().toLowerCase(Locale.ROOT);
            String className;
            if (capability == RequirementSpec.Capability.CUSTOM_ALIAS) {
                if (!greenfield) symbols.require("UrlController.java", "resolveAndRecordClick");
                if (!greenfield) symbols.require("UrlMappingRepository.java", "save");
                className = "CustomAliasController";
                for (var criterion : criteria) tests.put(criterion, "com.kondapallicb.urlshortener.api." + switch (criterion) {
                    case ALIAS_REDIRECT, DUPLICATE_REJECTED -> "CustomAliasAcceptanceTest#aliasRedirectsAndRejectsReplacement";
                    case INVALID_ALIAS_REJECTED, RESERVED_ALIAS_REJECTED -> "CustomAliasAcceptanceTest#invalidAndReservedAliasesAreRejected";
                    default -> "CustomAliasAcceptanceTest#aliasBoundariesAndTtl";
                });
            } else {
                symbols.require("UrlShorteningService.java", "analytics");
                symbols.require("UrlAnalytics.java", "dailyClicksUtc");
                className = "DailyAnalyticsController";
                for (var criterion : criteria) tests.put(criterion, "com.kondapallicb.urlshortener.api." + (criterion == RequirementSpec.Criterion.UTC_DAY_BOUNDARIES
                        ? "DailyAnalyticsAcceptanceTest#groupsByUtcDate" : "DailyAnalyticsAcceptanceTest#unknownSlugReturns404"));
            }
            String production = "src/main/java/com/kondapallicb/urlshortener/api/" + className + ".java";
            if (Files.exists(repository.resolve(production))) throw new IllegalArgumentException("Capability already exists; request a supported change instead of overwriting it");
            classes.add("com/kondapallicb/urlshortener/api/" + className);
            String objective = capability == RequirementSpec.Capability.CUSTOM_ALIAS
                    ? "Implement aliases with options " + spec.alias() : "Expose UTC daily analytics through the existing analytics service";
            tasks.add(new EngineeringPlan.Task(id, objective, List.of(), List.of(production), criteria));
            var testFiles = criteria.stream().map(tests::get).map(name -> "src/test/java/com/kondapallicb/urlshortener/api/"
                    + name.split("#")[0].substring(name.lastIndexOf('.') + 1) + ".java").distinct().toList();
            tasks.add(new EngineeringPlan.Task(id + "-acceptance", "Execute acceptance criteria: " + criteria,
                    List.of(id), testFiles, criteria));
        }
        if (greenfield) {
            var scaffold = new GreenfieldAgent().scaffold().stream().map(FileOperation::path).toList();
            classes.addAll(scaffold.stream().filter(p -> p.startsWith("src/main/java/") && p.endsWith(".java"))
                .map(p -> p.substring("src/main/java/".length(), p.length() - 5)).toList());
            tasks.addFirst(new EngineeringPlan.Task("bootstrap", "Generate application from empty source", List.of(), scaffold, List.of()));
            for (int index = 1; index < tasks.size(); index++) {
                var t = tasks.get(index);
                if (t.dependsOn().isEmpty()) tasks.set(index, new EngineeringPlan.Task(t.id(), t.objective(), List.of("bootstrap"), t.files(), t.criteria()));
            }
        }
        tasks.add(new EngineeringPlan.Task("documentation", "Generate requirement-specific build and API runbook",
            tasks.stream().filter(t -> t.id().endsWith("-acceptance")).map(EngineeringPlan.Task::id).toList(),
            List.of("GENERATED_README.md"), List.of()));
        var bound = tasks.stream().map(t -> t.bind(interpretation, policy)).toList();
        new PlanValidator().validate(bound, spec);
        var files = new ArrayList<String>();
        if (Files.isDirectory(repository.resolve("src"))) try (var paths = Files.walk(repository.resolve("src"))) {
            files.addAll(paths.filter(Files::isRegularFile).map(p -> repository.relativize(p).toString()).sorted().toList());
        }
        for (String file : List.of("pom.xml", "mvnw", "README.md")) if (Files.isRegularFile(repository.resolve(file))) files.add(file);
        if (Files.isDirectory(repository.resolve(".mvn"))) try (var paths = Files.walk(repository.resolve(".mvn"))) {
            files.addAll(paths.filter(Files::isRegularFile).map(p -> repository.relativize(p).toString()).sorted().toList());
        }
        var analysis = new EngineeringPlan.RepositoryAnalysis(files,
            symbols.values.entrySet().stream().filter(e -> e.getValue().contains("main") || e.getValue().contains("@RestController")).map(java.util.Map.Entry::getKey).toList(),
            symbols.values.keySet().stream().filter(p -> p.contains("/application/")).toList(),
            symbols.values.keySet().stream().filter(p -> p.contains("Repository")).toList(),
            files.stream().filter(p -> p.equals("pom.xml") || p.startsWith(".mvn/") || p.equals("mvnw")).toList(),
            files.stream().filter(p -> p.startsWith("src/test/")).toList(),
            greenfield ? List.of() : symbols.values.values().stream().flatMap(List::stream)
                .filter(name -> List.of("resolveAndRecordClick", "isExpired", "deactivate", "customAlias", "idempotencyPayload", "dailyClicksUtc", "reserve").contains(name)).distinct().sorted().toList());
        return new EngineeringPlan(spec, baselineHash, symbols.values, bound, tests, classes,
            greenfield ? EngineeringPlan.Mode.GREENFIELD : EngineeringPlan.Mode.BROWNFIELD, analysis, interpretation,
            List.of("CUSTOM_ALIAS", "UTC_DAILY_ANALYTICS_BROWNFIELD", "SPRING_BOOT_BOOTSTRAP", "JAVAC_AST", "MAVEN_CLEAN_VERIFY", "GOVERNED_FILES"), policy);
    }

    private static class MapBuilder {
        final LinkedHashMap<String, List<String>> values = new LinkedHashMap<>();
        void require(String filename, String member) {
            if (values.entrySet().stream().noneMatch(entry -> entry.getKey().endsWith("/" + filename) && entry.getValue().contains(member))) {
                throw new IllegalArgumentException("Repository lacks required integration: " + filename + "#" + member);
            }
        }
    }
}
