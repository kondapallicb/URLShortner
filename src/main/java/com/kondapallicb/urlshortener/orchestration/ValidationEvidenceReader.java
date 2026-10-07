package com.kondapallicb.urlshortener.orchestration;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

public class ValidationEvidenceReader {
    public static final double LINE_THRESHOLD = 0.80;
    public static final double BRANCH_THRESHOLD = 0.70;
    public record Case(String className, String name, String status, String failure) { }
    public record Coverage(long lineCovered, long lineMissed, long branchCovered, long branchMissed) {
        public double lineRatio() { return ratio(lineCovered, lineMissed); }
        public double branchRatio() { return ratio(branchCovered, branchMissed); }
        private double ratio(long covered, long missed) { return covered + missed == 0 ? 1 : (double) covered / (covered + missed); }
    }
    public record Mapping(String requirementId, String criterionId, List<String> productionFiles, String test, boolean executed) { }
    public record Report(List<Case> discoveredCases, int passed, int failed, int errored, int skipped,
        Map<String, Coverage> coverage, List<String> compiledProductionFiles, List<String> compiledTests, List<Mapping> traceability,
        Map<String, String> sourceHashes, Map<String, String> reportHashes, String artifactHash) { }

    public Report read(EngineeringPlan plan, ExecutionEvidence.ValidationAttempt attempt, Path workspace, boolean enforce) throws IOException {
        var cases = new ArrayList<Case>();
        var reportHashes = new TreeMap<String, String>();
        var passedTests = new HashSet<String>();
        int passed = 0, failed = 0, errored = 0, skipped = 0;
        for (String report : attempt.testReports()) {
            Path path = Path.of(report);
            var suite = parse(path).getDocumentElement();
            if (!suite.getTagName().equals("testsuite")) throw new IOException("Expected testsuite");
            var nodes = suite.getElementsByTagName("testcase");
            int sf = 0, se = 0, ss = 0;
            var unique = new HashSet<String>();
            for (int i = 0; i < nodes.getLength(); i++) {
                var node = (Element) nodes.item(i);
                String className = node.getAttribute("classname"), name = node.getAttribute("name");
                if (className.isBlank() || name.isBlank() || !unique.add(className + "#" + name)) throw new IOException("Invalid or duplicate test case");
                String status = "PASSED", detail = null;
                if (node.getElementsByTagName("failure").getLength() > 0) { status = "FAILED"; detail = node.getElementsByTagName("failure").item(0).getTextContent(); failed++; sf++; }
                else if (node.getElementsByTagName("error").getLength() > 0) { status = "ERRORED"; detail = node.getElementsByTagName("error").item(0).getTextContent(); errored++; se++; }
                else if (node.getElementsByTagName("skipped").getLength() > 0) { status = "SKIPPED"; skipped++; ss++; }
                else { passed++; passedTests.add(className + "#" + name); }
                cases.add(new Case(className, name, status, detail == null ? null : detail.substring(0, Math.min(detail.length(), 16384))));
            }
            if (Integer.parseInt(suite.getAttribute("tests")) != nodes.getLength() || Integer.parseInt(suite.getAttribute("failures")) != sf
                    || Integer.parseInt(suite.getAttribute("errors")) != se || Integer.parseInt(suite.getAttribute("skipped")) != ss)
                throw new IOException("Test totals do not match discovered cases");
            reportHashes.put(report, binaryHash(path));
        }
        var coverage = new TreeMap<String, Coverage>();
        if (attempt.coverageReport() != null) {
            Path path = Path.of(attempt.coverageReport());
            var types = parse(path).getElementsByTagName("class");
            for (int i = 0; i < types.getLength(); i++) {
                var type = (Element) types.item(i);
                long lc = 0, lm = 0, bc = 0, bm = 0;
                var nodes = type.getChildNodes();
                for (int j = 0; j < nodes.getLength(); j++) if (nodes.item(j) instanceof Element counter && counter.getTagName().equals("counter")) {
                    long c = Long.parseLong(counter.getAttribute("covered")), m = Long.parseLong(counter.getAttribute("missed"));
                    if (c < 0 || m < 0) throw new IOException("Negative coverage");
                    if (counter.getAttribute("type").equals("LINE")) { lc = c; lm = m; }
                    if (counter.getAttribute("type").equals("BRANCH")) { bc = c; bm = m; }
                }
                coverage.put(type.getAttribute("name"), new Coverage(lc, lm, bc, bm));
            }
            reportHashes.put(path.toString(), binaryHash(path));
        }
        var sources = new TreeMap<String, String>();
        var compiled = new ArrayList<String>();
        var mappings = new ArrayList<Mapping>();
        var compiledTests = new ArrayList<String>();
        for (String testClass : plan.acceptanceTests().values().stream().map(value -> value.split("#")[0]).distinct().toList()) {
            Path testSource = workspace.resolve("src/test/java/" + testClass.replace('.', '/') + ".java");
            Path compiledTest = workspace.resolve("target/test-classes/" + testClass.replace('.', '/') + ".class");
            if (Files.isRegularFile(testSource)) { WorkspaceExecutionService.checkOutput(workspace, testSource); sources.put(workspace.relativize(testSource).toString(), binaryHash(testSource)); }
            if (Files.isRegularFile(compiledTest)) { WorkspaceExecutionService.checkOutput(workspace, compiledTest); compiledTests.add(testClass); }
            else if (enforce) throw new IOException("Required generated test did not compile: " + testClass);
        }
        for (String name : plan.generatedClasses()) {
            Path source = workspace.resolve("src/main/java/" + name + ".java");
            Path file = workspace.resolve("target/classes/" + name + ".class");
            WorkspaceExecutionService.checkOutput(workspace, source);
            sources.put(workspace.relativize(source).toString(), binaryHash(source));
            if (Files.isRegularFile(file)) { WorkspaceExecutionService.checkOutput(workspace, file); compiled.add(workspace.relativize(source).toString()); }
            if (enforce) {
                Coverage value = coverage.get(name);
                if (!Files.isRegularFile(file) || value == null || value.lineCovered() == 0
                        || (name.endsWith("Controller") && (value.lineRatio() < LINE_THRESHOLD || value.branchRatio() < BRANCH_THRESHOLD)))
                    throw new IOException("Generated production coverage or compilation gate failed: " + name);
            }
        }
        for (var criterion : plan.interpretation().criteria()) {
            String test = plan.acceptanceTests().get(criterion.behavior());
            var production = plan.tasks().stream().filter(t -> t.agentRole().equals("IMPLEMENTATION") && t.criteria().contains(criterion.behavior()))
                .flatMap(t -> t.files().stream()).filter(p -> p.startsWith("src/main/java/")).toList();
            boolean executed = passedTests.contains(test);
            mappings.add(new Mapping(plan.interpretation().requirementId(), criterion.id(), production, test, executed));
            if (enforce && (!executed || production.isEmpty() || !compiled.containsAll(production)))
                throw new IOException("Behavioral criterion lacks connected compiled production and passing executed test: " + criterion.id());
        }
        if (enforce && (cases.isEmpty() || failed != 0 || errored != 0 || attempt.coverageReport() == null))
            throw new IOException("Missing or failing validation evidence");
        Path artifact = workspace.resolve("target/url-shortener-agentic-0.0.1-SNAPSHOT.jar");
        String artifactHash = null;
        if (Files.isRegularFile(artifact)) { WorkspaceExecutionService.checkOutput(workspace, artifact); artifactHash = binaryHash(artifact); }
        return new Report(List.copyOf(cases), passed, failed, errored, skipped, coverage, compiled, compiledTests, mappings, sources, reportHashes, artifactHash);
    }
    public static org.w3c.dom.Document parse(Path path) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(path.toFile());
        } catch (Exception invalid) { throw new IOException("Malformed XML evidence: " + path, invalid); }
    }
    static String binaryHash(Path path) throws IOException {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
