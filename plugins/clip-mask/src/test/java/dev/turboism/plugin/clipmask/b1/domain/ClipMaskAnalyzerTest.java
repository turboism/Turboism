package dev.turboism.plugin.clipmask.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ClipMaskAnalyzerTest {

    @Test
    void groupsExactInversionAndOrderConflictsWithDuplicateSourceCounts() {
        final ClipMaskAnalysis result = ClipMaskAnalyzer.analyze(List.of(
            target("a", false, "m1", "m2"),
            target("b", false, "m1", "m2"),
            target("c", true, "m1", "m2"),
            target("d", false, "m2", "m1"),
            target("e", false, "m1", "m1", "m2"),
            target("f", false, "m1", "m2", "m1")
        ));
        assertEquals(1, result.exactDuplicateGroups().size());
        assertEquals(List.of("a", "b"), result.exactDuplicateGroups().get(0).targetIds());
        assertEquals(1, result.inversionConflictGroups().size());
        assertEquals(List.of("a", "b", "c", "d"), result.inversionConflictGroups().get(0).targetIds());
        assertEquals(2, result.orderConflictGroups().size());
        assertTrue(result.orderConflictGroups().stream().anyMatch(group -> group.targetIds().equals(List.of("e", "f"))));
        assertEquals(2, result.counts().uniqueSourceCount());
        assertEquals(14, result.counts().totalSourceReferenceCount());
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), result.tableRows().stream().map(ClipMaskTableRow::targetId).toList());
    }

    @Test
    void rejectsDuplicateTargetsAndMalformedRowsDeterministically() {
        final ClipMaskAnalysis result = ClipMaskAnalyzer.analyze(List.of(
            target("dup", false, "m1"),
            target("dup", false, "m2"),
            target("empty", false),
            target("bad", false, "")
        ));
        assertEquals(0, result.acceptedTargets().size());
        assertEquals(List.of("bad", "dup", "dup", "empty"), result.rejectedTargets().stream().map(ClipMaskTarget::targetId).toList());
        assertEquals(2, result.issues().stream()
            .filter(issue -> issue.code() == ClipMaskIssueCode.DUPLICATE_TARGET_ID).count());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code() == ClipMaskIssueCode.EMPTY_MASK_SET));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code() == ClipMaskIssueCode.INVALID_SOURCE_ID));
    }

    @Test
    void targetPermutationDoesNotChangeAnalysis() {
        final List<ClipMaskTarget> first = List.of(target("b", false, "m2", "m1"), target("a", false, "m1", "m2"));
        final List<ClipMaskTarget> second = List.of(first.get(1), first.get(0));
        assertEquals(ClipMaskAnalyzer.analyze(first), ClipMaskAnalyzer.analyze(second));
    }

    private static ClipMaskTarget target(String id, boolean inverted, String... sources) {
        return new ClipMaskTarget(id, List.of(sources), inverted);
    }
}
