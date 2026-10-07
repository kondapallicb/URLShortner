package com.kondapallicb.urlshortener.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class RequirementInterpreter {
    private final ObjectMapper mapper;
    public RequirementInterpreter(ObjectMapper mapper) { this.mapper = mapper; }
    public RequirementInterpretation interpret(String original, boolean defaultsPermitted) {
        String text = original == null ? "" : original.strip();
        String id = "req-" + hash(text).substring(0, 16);
        var parameters = new LinkedHashMap<String, RequirementInterpretation.Parameter>();
        var assumptions = new ArrayList<String>();
        var questions = new ArrayList<String>();
        var unsupported = new ArrayList<String>();
        RequirementSpec spec = null;
        try {
            if (text.startsWith("{")) {
                spec = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(text, RequirementSpec.class);
                if (spec.alias() != null) {
                    var a = spec.alias();
                    parameters.put("minLength", new RequirementInterpretation.Parameter(a.minLength(), text));
                    parameters.put("maxLength", new RequirementInterpretation.Parameter(a.maxLength(), text));
                    parameters.put("ttlSeconds", new RequirementInterpretation.Parameter(a.ttlSeconds(), text));
                }
            } else if (text.toLowerCase(Locale.ROOT).startsWith("add custom aliases")) {
                String remainder = text.substring("add custom aliases".length()).strip().toLowerCase(Locale.ROOT);
                if (remainder.equals("for short urls")) remainder = "";
                int min = 3, max = 64;
                long ttl = 2592000;
                var matches = Pattern.compile("minimum length (\\d+)|maximum length (\\d+)|expiry after (one|\\d+) (hour|hours|minute|minutes|second|seconds)|allowed characters (alphanumeric|url-safe)|case (sensitive|insensitive)|duplicate behavior (reject)|redirect status (\\d+)|reserved aliases \\[([A-Za-z0-9_, -]*)\\]").matcher(remainder);
                StringBuffer residual = new StringBuffer();
                var alphabet = RequirementSpec.AliasOptions.Alphabet.URL_SAFE;
                boolean sensitive = true;
                int redirect = 302;
                List<String> reserved = List.of("api", "actuator");
                while (matches.find()) {
                    String key;
                    Object value;
                    if (matches.group(1) != null) { key = "minLength"; value = min = Integer.parseInt(matches.group(1)); }
                    else if (matches.group(2) != null) { key = "maxLength"; value = max = Integer.parseInt(matches.group(2)); }
                    else if (matches.group(3) != null) {
                        key = "ttlSeconds";
                        long amount = matches.group(3).equals("one") ? 1 : Long.parseLong(matches.group(3));
                        value = ttl = Math.multiplyExact(amount, matches.group(4).startsWith("hour") ? 3600 : matches.group(4).startsWith("minute") ? 60 : 1);
                    } else if (matches.group(5) != null) {
                        key = "alphabet"; value = alphabet = matches.group(5).equals("alphanumeric")
                            ? RequirementSpec.AliasOptions.Alphabet.ALPHANUMERIC : RequirementSpec.AliasOptions.Alphabet.URL_SAFE;
                    } else if (matches.group(6) != null) { key = "caseSensitive"; value = sensitive = matches.group(6).equals("sensitive"); }
                    else if (matches.group(7) != null) { key = "duplicateStatus"; value = 409; }
                    else if (matches.group(8) != null) { key = "redirectStatus"; value = redirect = Integer.parseInt(matches.group(8)); }
                    else { key = "reservedAliases"; value = reserved = matches.group(9).isBlank() ? List.of() : java.util.Arrays.stream(matches.group(9).split(",")).map(String::strip).toList(); }
                    if (parameters.putIfAbsent(key, new RequirementInterpretation.Parameter(value, matches.group())) != null)
                        questions.add("Specify one unambiguous value for " + key);
                    matches.appendReplacement(residual, "");
                }
                matches.appendTail(residual);
                String clause = residual.toString().replaceAll("\\b(with|and)\\b|[,;.]", " ").strip();
                if (!clause.isBlank()) {
                    unsupported.add(clause);
                    questions.add("Define the behavior and acceptance tests for unsupported clause: " + clause);
                }
                spec = new RequirementSpec(RequirementSpec.aliases().capabilities(),
                    new RequirementSpec.AliasOptions(min, max, ttl, alphabet, sensitive, reserved, 409, redirect),
                    RequirementSpec.aliases().acceptanceCriteria());
            } else {
                unsupported.add(text);
                questions.add("Provide supported custom-alias constraints or a structured CUSTOM_ALIAS / UTC_DAILY_ANALYTICS specification.");
            }
            if (spec != null && spec.alias() != null) {
                var a = spec.alias();
                var values = new LinkedHashMap<String, Object>();
                values.put("minLength", a.minLength()); values.put("maxLength", a.maxLength()); values.put("ttlSeconds", a.ttlSeconds());
                values.put("alphabet", a.alphabet()); values.put("caseSensitive", a.caseSensitive());
                values.put("reservedAliases", a.reservedAliases()); values.put("duplicateStatus", a.duplicateStatus());
                values.put("redirectStatus", a.redirectStatus());
                for (var entry : values.entrySet()) if (!parameters.containsKey(entry.getKey())) {
                    if (text.startsWith("{")) parameters.put(entry.getKey(), new RequirementInterpretation.Parameter(entry.getValue(), text));
                    else {
                        assumptions.add(entry.getKey() + "=" + entry.getValue() + " (compatibility policy default)");
                        parameters.put(entry.getKey(), new RequirementInterpretation.Parameter(entry.getValue(), "recorded policy assumption"));
                    }
                }
                if (!a.caseSensitive()) unsupported.add("Case-insensitive alias lookup is not supported by the current runtime");
                if (a.redirectStatus() != 302 || a.duplicateStatus() != 409) unsupported.add("Runtime supports redirect 302 and duplicate rejection 409 only");
                if (a.reservedAliases() == null || a.reservedAliases().size() > 16 || a.reservedAliases().stream().distinct().count() != a.reservedAliases().size() || a.reservedAliases().stream().anyMatch(v -> v == null || v.length() > 64 || !v.matches("[A-Za-z0-9_-]+")))
                    questions.add("Specify up to 16 distinct reserved aliases, each 1..64 URL-safe characters");
                if (a.minLength() < 3 || a.maxLength() > 64 || a.maxLength() < a.minLength() || a.ttlSeconds() < 60 || a.ttlSeconds() > 31536000 || a.alphabet() == null)
                    questions.add("Specify lengths within 3..64, TTL within 60..31536000 seconds, and an allowed alphabet");
            }
            if (spec != null && (spec.capabilities() == null || spec.capabilities().isEmpty() || spec.acceptanceCriteria() == null || spec.acceptanceCriteria().isEmpty()))
                questions.add("Supply capabilities and acceptance criteria");
        } catch (Exception invalid) { questions.add("Invalid specification: " + invalid.getMessage()); }
        if (spec != null && spec.capabilities() != null && spec.acceptanceCriteria() != null) {
            var required = new java.util.LinkedHashSet<>(spec.acceptanceCriteria());
            if (spec.capabilities().contains(RequirementSpec.Capability.CUSTOM_ALIAS)) required.addAll(RequirementSpec.aliases().acceptanceCriteria());
            if (spec.capabilities().contains(RequirementSpec.Capability.UTC_DAILY_ANALYTICS)) required.addAll(List.of(RequirementSpec.Criterion.UTC_DAY_BOUNDARIES, RequirementSpec.Criterion.UNKNOWN_SLUG_REJECTED));
            spec = new RequirementSpec(spec.capabilities(), spec.alias(), List.copyOf(required));
        }
        if (!defaultsPermitted && !assumptions.isEmpty()) questions.add("Policy forbids defaults; explicitly supply all alias constraints.");
        var status = !unsupported.isEmpty() ? RequirementInterpretation.Status.UNSUPPORTED
            : !questions.isEmpty() ? RequirementInterpretation.Status.NEEDS_CLARIFICATION : RequirementInterpretation.Status.READY;
        List<RequirementInterpretation.AcceptanceCriterion> criteria = spec == null || spec.acceptanceCriteria() == null ? List.of()
            : spec.acceptanceCriteria().stream().map(c -> new RequirementInterpretation.AcceptanceCriterion(id + ":" + c, c)).toList();
        return new RequirementInterpretation(id, text, spec, parameters, criteria, List.copyOf(assumptions),
            List.copyOf(questions), List.copyOf(unsupported), status);
    }
    static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
