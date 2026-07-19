package dev.turboism.plugin.psdimport.b1.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PsdParameterParseResult(Map<String, Boolean> values, List<PsdParameterIssue> issues) {
    public PsdParameterParseResult {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        issues = List.copyOf(issues);
    }
}
