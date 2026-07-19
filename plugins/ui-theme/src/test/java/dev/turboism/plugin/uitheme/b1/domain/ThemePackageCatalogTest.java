package dev.turboism.plugin.uitheme.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ThemePackageCatalogTest {

    @Test
    void validatesInheritanceDuplicatesAndDeterministicOrdering() {
        final ThemePackageMetadata child = metadata("vendor.child", "Child", "vendor.base");
        final ThemePackageMetadata base = metadata("vendor.base", "Base", null);
        final ThemePackageMetadata duplicateOne = metadata("vendor.duplicate", "One", null);
        final ThemePackageMetadata duplicateTwo = metadata("vendor.duplicate", "Two", null);
        final ThemePackageMetadata missing = metadata("vendor.missing", "Missing", "vendor.absent");

        final ThemePackageCatalog.Result result = ThemePackageCatalog.build(List.of(
            child,
            duplicateOne,
            missing,
            base,
            duplicateTwo
        ));

        assertEquals(List.of("vendor.base", "vendor.child"), result.accepted().stream()
            .map(ThemePackageCatalog.Candidate::id).toList());
        assertEquals(List.of("vendor.duplicate", "vendor.duplicate", "vendor.missing"),
            result.rejected().stream().map(ThemePackageCatalog.Candidate::id).toList());
        assertEquals(List.of(
            ThemePackageCatalog.IssueCode.DUPLICATE_ID,
            ThemePackageCatalog.IssueCode.DUPLICATE_ID,
            ThemePackageCatalog.IssueCode.MISSING_PARENT
        ), result.issues().stream().map(ThemePackageCatalog.Issue::code).toList());
        assertEquals(List.of(1, 4, 2), result.issues().stream()
            .map(ThemePackageCatalog.Issue::ordinal).toList());
    }

    @Test
    void rejectsCyclesInvalidDescendantsAndExcessiveDepth() {
        final var values = new java.util.ArrayList<ThemePackageMetadata>();
        values.add(metadata("cycle.alpha", "Alpha", "cycle.beta"));
        values.add(metadata("cycle.beta", "Beta", "cycle.alpha"));
        values.add(metadata("cycle.child", "Child", "cycle.alpha"));
        String parent = null;
        for (int index = 0; index < 17; index++) {
            final String id = "depth.n" + index;
            values.add(metadata(id, "Depth " + index, parent));
            parent = id;
        }

        final ThemePackageCatalog.Result result = ThemePackageCatalog.build(values);

        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.id().equals("cycle.alpha") && issue.code() == ThemePackageCatalog.IssueCode.INHERITANCE_CYCLE));
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.id().equals("cycle.child") && issue.code() == ThemePackageCatalog.IssueCode.INVALID_PARENT));
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.code() == ThemePackageCatalog.IssueCode.INHERITANCE_DEPTH));
    }

    @Test
    void appliesDefaultsAndRejectsBounds() {
        final ThemePackageMetadata defaults = new ThemePackageMetadata(
            "vendor.valid", "Valid", null, null, null, null, null, null, null, null
        );
        final ThemePackageCatalog.Result valid = ThemePackageCatalog.build(List.of(defaults));
        assertEquals(ThemeBase.ANY, valid.accepted().get(0).metadata().base());
        assertEquals(ThemeIcons.LIGHT, valid.accepted().get(0).metadata().icons());
        assertEquals("", valid.accepted().get(0).metadata().description());

        final ThemePackageCatalog.Result invalid = ThemePackageCatalog.build(List.of(
            metadata("Bad.Id", "", null),
            metadata("vendor.long", "x".repeat(129), null),
            metadata("vendor.bad-", "Bad trailing dash", null)
        ));
        assertEquals(3, invalid.rejected().size());
        assertTrue(invalid.issues().stream().anyMatch(issue -> issue.code() == ThemePackageCatalog.IssueCode.INVALID_ID));
        assertTrue(invalid.issues().stream().anyMatch(issue -> issue.code() == ThemePackageCatalog.IssueCode.INVALID_NAME));
    }

    private static ThemePackageMetadata metadata(String id, String name, String parentId) {
        return new ThemePackageMetadata(
            id,
            name,
            "",
            "",
            "",
            "",
            parentId,
            ThemeBase.ANY,
            ThemeIcons.LIGHT,
            false
        );
    }
}
