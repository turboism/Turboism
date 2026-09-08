package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsHistoryManagerValidationProbeTest {

    @Test
    void escapesEvidenceAsJsonLines() {
        assertEquals(
            "quote\\\" slash\\\\ line\\n tab\\t",
            WindowsHistoryManagerValidationProbe.json("quote\" slash\\ line\n tab\t")
        );
    }

    @Test
    void boundsLabelsByUnicodeCodePoint() {
        final String value = "😀".repeat(200);
        final String bounded = WindowsHistoryManagerValidationProbe.boundedLabel(value);
        assertEquals(160, bounded.codePointCount(0, bounded.length()));
        assertEquals("", WindowsHistoryManagerValidationProbe.boundedLabel(null));
    }

    @Test
    void semanticScalarProjectionRejectsUnknownObjectStringConversion() {
        final Object explosive = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("must not stringify unknown host objects");
            }
        };
        assertEquals("", WindowsHistoryManagerValidationProbe.safeScalar(explosive));
        assertEquals("", WindowsHistoryManagerValidationProbe.boundedLabel(explosive));
        assertEquals("7", WindowsHistoryManagerValidationProbe.safeScalar(7));
    }
}
