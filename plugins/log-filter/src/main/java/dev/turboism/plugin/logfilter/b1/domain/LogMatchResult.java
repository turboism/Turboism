package dev.turboism.plugin.logfilter.b1.domain;

import java.util.List;

public record LogMatchResult(boolean matches, boolean truncatedInput, List<String> matchedKeywords) {
    public LogMatchResult {
        matchedKeywords = List.copyOf(matchedKeywords);
    }
}
