package dev.turboism.plugin.logfilter.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

final class LogMatchPolicyTest {

    @Test
    void appliesSeverityLiteralKeywordsAndAnyAllComposition() {
        final LogMatchPolicy any = new LogMatchPolicy(LogLevel.INFO, List.of("alpha", "beta"), KeywordMode.ANY, true);
        assertFalse(any.match(LogLevel.DEBUG, "alpha beta").matches());
        assertEquals(List.of("alpha"), any.match(LogLevel.INFO, "contains alpha only").matchedKeywords());
        assertTrue(any.match(LogLevel.ERROR, "contains beta").matches());

        final LogMatchPolicy all = new LogMatchPolicy(LogLevel.TRACE, List.of("a.b", "[x]"), KeywordMode.ALL, true);
        assertTrue(all.match(LogLevel.INFO, "literal a.b and [x]").matches());
        assertFalse(all.match(LogLevel.INFO, "regex-like axb x").matches());
    }

    @Test
    void normalizesKeywordsWithRootLocaleAndStableDeduplication() {
        final Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            final LogMatchPolicy policy = new LogMatchPolicy(
                LogLevel.INFO,
                List.of(" Info ", "INFO", "", "İ"),
                KeywordMode.ANY,
                false
            );
            assertEquals(List.of("Info", "İ"), policy.keywords());
            assertEquals(List.of("Info"), policy.match(LogLevel.INFO, "INFO record").matchedKeywords());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void emptyKeywordsUseSeverityOnlyAndMessagesAreBounded() {
        final LogMatchPolicy policy = LogMatchPolicy.defaults();
        assertTrue(policy.match(LogLevel.INFO, "message").matches());
        assertFalse(policy.match(LogLevel.DEBUG, "message").matches());
        final String message = "x".repeat(65_536) + "needle";
        final LogMatchResult result = new LogMatchPolicy(
            LogLevel.TRACE, List.of("needle"), KeywordMode.ANY, true
        ).match(LogLevel.INFO, message);
        assertFalse(result.matches());
        assertTrue(result.truncatedInput());
    }

    @Test
    void rejectsKeywordBoundsAndUnpairedSurrogates() {
        assertThrows(IllegalArgumentException.class, () -> new LogMatchPolicy(
            LogLevel.INFO,
            java.util.stream.IntStream.range(0, 17).mapToObj(index -> "x" + index).toList(),
            KeywordMode.ANY,
            false
        ));
        assertThrows(IllegalArgumentException.class, () -> new LogMatchPolicy(
            LogLevel.INFO, List.of("x".repeat(129)), KeywordMode.ANY, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new LogMatchPolicy(
            LogLevel.INFO, List.of("\uD800"), KeywordMode.ANY, false
        ));
    }
}
