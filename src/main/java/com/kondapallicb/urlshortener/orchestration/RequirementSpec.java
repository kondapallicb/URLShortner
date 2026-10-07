package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record RequirementSpec(List<Capability> capabilities, AliasOptions alias,
        List<Criterion> acceptanceCriteria) {
    public enum Capability { CUSTOM_ALIAS, UTC_DAILY_ANALYTICS }
    public enum Criterion {
        ALIAS_REDIRECT, DUPLICATE_REJECTED, INVALID_ALIAS_REJECTED, RESERVED_ALIAS_REJECTED,
        ALIAS_BOUNDARIES_AND_TTL, UTC_DAY_BOUNDARIES, UNKNOWN_SLUG_REJECTED
    }
    public record AliasOptions(int minLength, int maxLength, long ttlSeconds, Alphabet alphabet,
            boolean caseSensitive, List<String> reservedAliases, int duplicateStatus, int redirectStatus) {
        public AliasOptions(int minLength, int maxLength, long ttlSeconds, Alphabet alphabet) {
            this(minLength, maxLength, ttlSeconds, alphabet, true, List.of("api", "actuator"), 409, 302);
        }
        public enum Alphabet { URL_SAFE, ALPHANUMERIC }
        public static AliasOptions defaults() { return new AliasOptions(3, 64, 2592000, Alphabet.URL_SAFE); }
    }
    public static RequirementSpec aliases() {
        return new RequirementSpec(List.of(Capability.CUSTOM_ALIAS), AliasOptions.defaults(), List.of(
                Criterion.ALIAS_REDIRECT, Criterion.DUPLICATE_REJECTED, Criterion.INVALID_ALIAS_REJECTED,
                Criterion.RESERVED_ALIAS_REJECTED, Criterion.ALIAS_BOUNDARIES_AND_TTL));
    }
}
