package dev.turboism.plugin.logfilter.b1.domain;

import java.util.List;
import java.util.Objects;

public record LogFilterSettings(
    LogLevel minimumLevel,
    KeywordMode keywordMode,
    boolean caseSensitive,
    List<String> keywords
) {
    public LogFilterSettings {
        minimumLevel = Objects.requireNonNull(minimumLevel, "minimumLevel");
        keywordMode = Objects.requireNonNull(keywordMode, "keywordMode");
        keywords = new LogMatchPolicy(minimumLevel, keywords, keywordMode, caseSensitive).keywords();
    }

    public static LogFilterSettings defaults() {
        return new LogFilterSettings(LogLevel.INFO, KeywordMode.ANY, false, List.of());
    }

    public LogMatchPolicy toPolicy() {
        return new LogMatchPolicy(minimumLevel, keywords, keywordMode, caseSensitive);
    }

    public static LogFilterSettings fromPolicy(final LogMatchPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return new LogFilterSettings(
            policy.minimumLevel(), policy.keywordMode(), policy.caseSensitive(), policy.keywords()
        );
    }
}
