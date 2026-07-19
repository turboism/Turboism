package dev.turboism.plugin.logfilter.b1.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record LogMatchPolicy(
    LogLevel minimumLevel,
    List<String> keywords,
    KeywordMode keywordMode,
    boolean caseSensitive
) {
    private static final int MAX_KEYWORDS = 16;
    private static final int MAX_KEYWORD_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 65_536;

    public LogMatchPolicy {
        minimumLevel = Objects.requireNonNull(minimumLevel, "minimumLevel");
        keywordMode = Objects.requireNonNull(keywordMode, "keywordMode");
        keywords = normalizeKeywords(keywords, caseSensitive);
    }

    public static LogMatchPolicy defaults() {
        return new LogMatchPolicy(LogLevel.INFO, List.of(), KeywordMode.ANY, false);
    }

    public LogMatchResult match(final LogLevel level, final String message) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
        final boolean truncated = message.length() > MAX_MESSAGE_LENGTH;
        final String observed = message.substring(0, Math.min(message.length(), MAX_MESSAGE_LENGTH));
        if (level.ordinal() < minimumLevel.ordinal()) {
            return new LogMatchResult(false, truncated, List.of());
        }
        if (keywords.isEmpty()) {
            return new LogMatchResult(true, truncated, List.of());
        }
        final String haystack = caseSensitive ? observed : observed.toLowerCase(Locale.ROOT);
        final List<String> matched = new ArrayList<>();
        for (String keyword : keywords) {
            final String needle = caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) {
                matched.add(keyword);
            }
        }
        final boolean matches = keywordMode == KeywordMode.ANY
            ? !matched.isEmpty()
            : matched.size() == keywords.size();
        return new LogMatchResult(matches, truncated, matched);
    }

    private static List<String> normalizeKeywords(final List<String> values, final boolean caseSensitive) {
        Objects.requireNonNull(values, "keywords");
        final Set<String> seen = new LinkedHashSet<>();
        final List<String> normalized = new ArrayList<>();
        for (String value : values) {
            Objects.requireNonNull(value, "keyword");
            final String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_KEYWORD_LENGTH || hasUnpairedSurrogate(trimmed)) {
                throw new IllegalArgumentException("keyword is outside the supported bounds");
            }
            final String identity = caseSensitive ? trimmed : trimmed.toLowerCase(Locale.ROOT);
            if (seen.add(identity)) {
                normalized.add(trimmed);
            }
        }
        if (normalized.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException("at most 16 keywords are supported");
        }
        return List.copyOf(normalized);
    }

    private static boolean hasUnpairedSurrogate(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
