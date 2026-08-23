package dev.turboism.plugin.psdimport.b1.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of interpreting the host-supplied parameter strings for a PSD-import action.
 *
 * <p>Always usable: every declared parameter has a value even when the input was malformed or
 * absent, and the issue list records what had to be substituted or ignored. An empty issue list
 * means the input was taken verbatim.
 *
 * @param values every declared parameter mapped to its effective boolean value, defensively copied
 *     and exposed unmodifiable in insertion order; must not be {@code null}
 * @param issues problems found while parsing, defensively copied via {@code List.copyOf} and
 *     therefore immutable and null-hostile; empty when the input was fully honoured
 */
public record PsdParameterParseResult(Map<String, Boolean> values, List<PsdParameterIssue> issues) {
    public PsdParameterParseResult {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        issues = List.copyOf(issues);
    }
}
