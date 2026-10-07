package com.kondapallicb.urlshortener.orchestration;

import java.util.ArrayList;
import java.util.List;

public class PolicyEvaluator {
    public PolicyReport evaluate(EngineeringPlan plan, GovernancePolicy policy, List<FileOperation> operations) {
        var checks = new ArrayList<PolicyReport.Check>();
        checks.add(new PolicyReport.Check("BOUNDED_RETRY", policy.maxRetries() >= 0 && policy.maxRetries() <= 3,
                "Configured retries=" + policy.maxRetries()));
        checks.add(new PolicyReport.Check("SECURITY_REVIEW_REQUIRED", policy.requireSecurityReview(),
                "A separate authenticated security approval must precede outcome approval"));
        checks.add(new PolicyReport.Check("HIGH_IMPACT_APPROVAL_REQUIRED", policy.requireHumanApprovalForHighImpactChanges(),
                "API changes require plan approval before file application"));
        List<String> allowed = new ArrayList<>();
        plan.tasks().forEach(task -> allowed.addAll(task.files()));
        checks.add(new PolicyReport.Check("APPROVED_FILES_ONLY", operations.stream().allMatch(op -> allowed.contains(op.path()))
                && operations.stream().map(FileOperation::path).distinct().count() == operations.size(),
                "Operations must match the analyzed plan and have unique paths"));
        for (FileOperation operation : operations) {
            if (operation.path().equals("pom.xml") && operation.type() != FileOperation.Type.DELETE) {
                checks.add(new PolicyReport.Check("CONTROLLED_BUILD_CONFIGURATION", controlledPom(operation.content()),
                    "Only the approved Spring Boot parent and JaCoCo/Surefire/Boot plugins are permitted"));
            }
            String content = operation.content() == null ? "" : operation.content();
            boolean secret = content.contains("-----BEGIN") || java.util.regex.Pattern.compile(
                    "(?i)(password|api[_-]?key|secret|token)\\s*=\\s*\"[^\"]+\"").matcher(content).find();
            checks.add(new PolicyReport.Check("NO_EMBEDDED_SECRETS:" + operation.path(), !secret,
                    "Private keys and literal credential assignments are forbidden"));
            boolean escape = content.contains("Runtime.getRuntime") || content.contains("ProcessBuilder")
                    || content.contains("System.getenv") || content.contains("java.nio.file")
                    || content.contains("java.io.File") || content.contains("java.net.Socket");
            checks.add(new PolicyReport.Check("NO_EXTRA_TOOLS:" + operation.path(), !escape,
                    "Generated endpoint code cannot invoke processes, environment, host files or sockets"));
            if (operation.path().endsWith("CustomAliasController.java")) {
                checks.add(new PolicyReport.Check("INPUT_VALIDATION", content.contains("@Valid") && content.contains("@Pattern")
                        && content.contains("@NotBlank") && content.contains("SlugConflictException"),
                        "Alias input validation and conflict responses required"));
            }
        }
        for (String guardrail : policy.guardrails()) {
            boolean recognized = GovernancePolicy.defaultPolicy().guardrails().contains(guardrail);
            checks.add(new PolicyReport.Check("RECOGNIZED_GUARDRAIL", recognized, recognized ? guardrail : "Unsupported guardrail requires clarification"));
        }
        return new PolicyReport(checks.stream().allMatch(PolicyReport.Check::passed), List.copyOf(checks));
    }
    private boolean controlledPom(String content) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var parents = document.getElementsByTagName("parent");
            if (parents.getLength() != 1) return false;
            var parent = (org.w3c.dom.Element) parents.item(0);
            if (!parent.getElementsByTagName("artifactId").item(0).getTextContent().equals("spring-boot-starter-parent")
                    || !parent.getElementsByTagName("groupId").item(0).getTextContent().equals("org.springframework.boot")
                    || !parent.getElementsByTagName("version").item(0).getTextContent().equals("3.3.5")) return false;
            var plugins = document.getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                var plugin = (org.w3c.dom.Element) plugins.item(i);
                if (!java.util.Set.of("org.apache.maven.plugins:maven-surefire-plugin", "org.jacoco:jacoco-maven-plugin",
                        "org.springframework.boot:spring-boot-maven-plugin").contains(
                        plugin.getElementsByTagName("groupId").item(0).getTextContent() + ":" + plugin.getElementsByTagName("artifactId").item(0).getTextContent()))
                    return false;
            }
            return true;
        } catch (Exception invalid) { return false; }
    }
}
