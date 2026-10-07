package com.kondapallicb.urlshortener.orchestration;

import java.util.List;

public record PolicyReport(boolean passed, List<Check> checks) {
    public record Check(String name, boolean passed, String evidence) { }
}
