package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.tests.plugin.WindowsPsdClipMaskValidationProbe.MaskState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verdict-seam tests for {@link WindowsPsdClipMaskValidationProbe}'s expected-state derivation. */
final class WindowsPsdClipMaskValidationProbeTest {

    private static MaskState state(final String... masks) {
        return new MaskState(List.of(masks).stream().map(ArtMeshId::new).toList(), false);
    }

    @Test
    void syntheticExpectedCrossMapsFirstTwoTargets() {
        final ArtMeshId a = new ArtMeshId("a");
        final ArtMeshId b = new ArtMeshId("b");
        final ArtMeshId c = new ArtMeshId("c");
        final Map<ArtMeshId, MaskState> before = new LinkedHashMap<>();
        before.put(a, state("x"));
        before.put(b, state("y"));
        before.put(c, state("z"));

        final Map<ArtMeshId, MaskState> expected = WindowsPsdClipMaskValidationProbe.syntheticExpected(before);
        assertEquals(List.of(b), expected.get(a).masks());
        assertEquals(List.of(a), expected.get(b).masks());
        assertEquals(before.get(c), expected.get(c));
    }

    @Test
    void wrongExpectedBatchTruncatesFirstNonEmptyExpectation() {
        final ClipMaskReplacement replacement = new ClipMaskReplacement(
                new ArtMeshId("target"),
                List.of(new ArtMeshId("m1"), new ArtMeshId("m2")),
                false,
                List.of(new ArtMeshId("r1")),
                false);
        final List<ClipMaskReplacement> wrong =
                WindowsPsdClipMaskValidationProbe.wrongExpectedBatch(List.of(replacement));
        assertEquals(List.of(new ArtMeshId("m1")), wrong.get(0).expectedMaskArtMeshIds());
        assertFalse(wrong.get(0).expectedInverted());
    }

    @Test
    void wrongExpectedBatchFlipsInversionWhenAllExpectationsEmpty() {
        final ClipMaskReplacement replacement =
                new ClipMaskReplacement(new ArtMeshId("target"), List.of(), false, List.of(new ArtMeshId("r1")), false);
        final List<ClipMaskReplacement> wrong =
                WindowsPsdClipMaskValidationProbe.wrongExpectedBatch(List.of(replacement));
        assertTrue(wrong.get(0).expectedInverted());
        assertEquals(replacement.expectedMaskArtMeshIds(), wrong.get(0).expectedMaskArtMeshIds());
    }

    @Test
    void appendAssertionSerializesVerdictLine() {
        final StringBuilder report = new StringBuilder();
        WindowsPsdClipMaskValidationProbe.appendAssertion(report, "commit", "2", "3", false);
        final String text = report.toString();
        assertTrue(text.contains("assertion=commit\n"));
        assertTrue(text.contains("expected=2\n"));
        assertTrue(text.contains("actual=3\n"));
        assertTrue(text.contains("status=FAIL\n"));
    }
}
