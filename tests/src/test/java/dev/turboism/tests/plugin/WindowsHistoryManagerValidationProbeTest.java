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
}
