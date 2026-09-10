package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void semanticChildDegradationDoesNotMarkGroupTruncated() {
        final WindowsHistoryManagerValidationProbe.NativeDetail child = simpleUnavailable();

        assertFalse(WindowsHistoryManagerValidationProbe.groupTruncated(
            1, 1, 1, List.of(child)
        ));
        assertEquals("history.detail.post-state-unavailable", child.degradationCode());
    }

    @Test
    void observedCountMismatchRemainsTruncated() {
        assertTrue(WindowsHistoryManagerValidationProbe.groupTruncated(
            2, 1, 1, List.of(simpleUnavailable())
        ));
    }

    @Test
    void projectionBudgetRemainsTruncated() {
        assertTrue(WindowsHistoryManagerValidationProbe.groupTruncated(
            2, 2, 1, List.of(simpleUnavailable(), simpleUnavailable())
        ));
    }

    @Test
    void depthOrNodeLimitRemainsTruncated() {
        final WindowsHistoryManagerValidationProbe.NativeDetail limited =
            WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                "com.live2d.undo.SimpleUndo",
                "LIMIT",
                "history.detail.node-or-depth-limit"
            );

        assertTrue(limited.truncated());
        assertTrue(WindowsHistoryManagerValidationProbe.groupTruncated(
            1, 1, 1, List.of(limited)
        ));
    }

    @Test
    void nestedTraversalLimitPropagates() {
        final WindowsHistoryManagerValidationProbe.NativeDetail limited =
            WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                "com.live2d.undo.SimpleUndo",
                "LIMIT",
                "history.detail.node-or-depth-limit"
            );
        final WindowsHistoryManagerValidationProbe.NativeDetail nested = group(
            1,
            List.of(limited),
            "history.detail.group-truncated"
        );

        assertTrue(nested.truncated());
        assertTrue(WindowsHistoryManagerValidationProbe.groupTruncated(
            1, 1, 1, List.of(nested)
        ));
    }

    @Test
    void decoderFailureIsNotTruncation() {
        final WindowsHistoryManagerValidationProbe.NativeDetail failed =
            WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                "com.live2d.undo.SimpleUndo",
                "FAILED",
                "history.detail.decoder-failed"
            );

        assertFalse(WindowsHistoryManagerValidationProbe.groupTruncated(
            1, 1, 1, List.of(failed)
        ));
        assertEquals("history.detail.decoder-failed", failed.degradationCode());
    }

    private static WindowsHistoryManagerValidationProbe.NativeDetail simpleUnavailable() {
        return new WindowsHistoryManagerValidationProbe.NativeDetail(
            "SIMPLE", "com.live2d.undo.SimpleUndo", "target", "", "", "", "", -1,
            0, List.of(), List.of(), true, false, "history.detail.post-state-unavailable"
        );
    }

    private static WindowsHistoryManagerValidationProbe.NativeDetail group(
        final int observedChildCount,
        final List<WindowsHistoryManagerValidationProbe.NativeDetail> children,
        final String degradationCode
    ) {
        return new WindowsHistoryManagerValidationProbe.NativeDetail(
            "GROUP", "com.live2d.undo.GroupUndo", "", "", "", "", "", -1,
            observedChildCount,
            children.stream().map(WindowsHistoryManagerValidationProbe.NativeDetail::entryClass).toList(),
            children,
            false,
            false,
            degradationCode
        );
    }
}
