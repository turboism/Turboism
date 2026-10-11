package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorArtMeshAuthoringReadAccessTest.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorWarpAuthoringSelectorContract;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.WarpKeyformPositions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorWarpKeyformAccessTest {
    static final DeformerId ID = new DeformerId("warp");
    static final List<Point2> POINTS = List.of(new Point2(2, 3), new Point2(5, 3), new Point2(2, 7), new Point2(5, 7));

    static WarpKeyformPositions edit(float x, float y, String guid) {
        return new WarpKeyformPositions(List.of(new Coordinate("y", y), new Coordinate("x", x)), guid, POINTS);
    }

    @Test
    void readsStoredFormsWithoutCurrentFormOrSliderSelectorsAndWritesOnlyNamedCells() {
        var f = new Fixture();
        var before = f.read();
        assertEquals(9, before.forms().size());
        assertEquals(9, before.cells().size());
        assertEquals(before.axes().get(0).parameterId(), before.axes().get(1).parameterId());
        f.write(before, List.of(edit(1, 1, "f8"), edit(-1, -1, "f0")));
        var after = f.read();
        assertEquals(1, f.writes);
        assertEquals(before.axes(), after.axes());
        assertEquals(before.cells(), after.cells());
        for (int i = 0; i < before.forms().size(); i++) {
            var old = before.forms().get(i);
            var now = after.forms().get(i);
            if (List.of("f8", "f0").contains(old.formGuid())) assertEquals(POINTS, now.controlPoints());
            else assertEquals(old, now);
        }
        f.write(after, List.of(edit(1, 1, "f8")));
        assertEquals(1, f.writes);
        f.source.base.forms.get(0).positions[0] = 999;
        assertNotEquals(999, before.forms().get(0).controlPoints().get(0).x());
        assertThrows(UnsupportedOperationException.class, () -> before.forms().clear());
    }

    @Test
    void rejectsDefaultSharedWrongGuidIncompleteDuplicateAndOffGridTargetsBeforeWriter() {
        var invalid = List.of(
                List.of(edit(0, 0, "f4")),
                List.of(edit(1, 1, "f7")),
                List.of(edit(.5f, 1, "f8")),
                List.of(edit(1, 1, "f8"), edit(1, 1, "f8")),
                List.of(new WarpKeyformPositions(List.of(new Coordinate("x", 1)), "f8", POINTS)),
                List.of(new WarpKeyformPositions(
                        List.of(new Coordinate("x", 1), new Coordinate("x", 1)), "f8", POINTS)),
                List.of(new WarpKeyformPositions(edit(1, 1, "f8").coordinates(), "f8", List.of(new Point2(0, 0)))));
        for (var input : invalid) {
            var f = new Fixture();
            assertThrows(IllegalArgumentException.class, () -> f.write(f.read(), input));
            assertEquals(0, f.writes);
        }
        var shared = new Fixture();
        shared.source
                .base
                .grid
                .rows
                .get("f8")
                .add(shared.source.base.grid.rows.remove("f7").get(0));
        assertThrows(IllegalArgumentException.class, () -> shared.write(shared.read(), List.of(edit(1, 1, "f8"))));
        assertEquals(0, shared.writes);
        var missing = new Fixture();
        missing.source.base.grid.bindings.get(0).parameter.defaultValue = .25f;
        assertThrows(IllegalArgumentException.class, () -> missing.write(missing.read(), List.of(edit(1, 1, "f8"))));
    }

    @Test
    void rejectsStaleSnapshotAndReusedNativeGuidDuringWriteWithoutTouchingReplacement() {
        var f = new Fixture();
        var before = f.read();
        f.source.rows = 2;
        assertThrows(RuntimeException.class, () -> f.write(before, List.of(edit(1, 1, "f8"))));
        assertEquals(0, f.writes);
        var replaced = new Fixture();
        var old = replaced.read();
        replaced.beforeMutation = () -> replaced.source.base.forms.set(8, new StoredForm("f8", 8));
        assertThrows(IllegalStateException.class, () -> replaced.write(old, List.of(edit(1, 1, "f8"))));
        assertNotEquals(POINTS, replaced.read().forms().get(8).controlPoints());
    }

    @Test
    void midBatchSetterFailureAndPostconditionFailureRestoreCompleteBeforeImage() {
        var f = new Fixture();
        var before = f.read();
        f.source.base.forms.get(8).afterSetPositions = () -> {
            throw new IllegalStateException("injected setter failure");
        };
        assertThrows(RuntimeException.class, () -> f.write(before, List.of(edit(-1, -1, "f0"), edit(1, 1, "f8"))));
        assertEquals(before, f.read());
        var post = new Fixture();
        var original = post.read();
        post.afterMutation = () -> post.source.base.forms.get(7).positions[0] = 888;
        assertThrows(IllegalStateException.class, () -> post.write(original, List.of(edit(1, 1, "f8"))));
        assertEquals(original, post.read());
    }

    @Test
    void capabilitiesVersionTransactionAndMorphRefuseBeforeWriter() {
        for (String capability : EditorWarpAuthoringSelectorContract.CAPABILITY_IDS) {
            var f = new Fixture();
            f.resolver = resolver("5.3.02", capability, "", false);
            assertThrows(UnsupportedOperationException.class, f::read);
        }
        for (String alias : EditorWarpAuthoringSelectorContract.READ_ALIASES) {
            var f = new Fixture();
            f.resolver = resolver("5.3.02", "", alias, false);
            assertThrows(UnsupportedOperationException.class, f::read);
        }
        for (String alias : EditorWarpAuthoringSelectorContract.WRITE_ALIASES) {
            var f = new Fixture();
            var before = f.read();
            f.resolver = resolver("5.3.02", "", alias, false);
            assertThrows(UnsupportedOperationException.class, () -> f.write(before, List.of(edit(1, 1, "f8"))));
            assertEquals(0, f.writes);
        }
        var deny = new Fixture();
        var before = deny.read();
        deny.resolver = resolver("5.3.02", "", "", true);
        assertThrows(UnsupportedOperationException.class, () -> deny.write(before, List.of(edit(1, 1, "f8"))));
        var transaction = new Fixture();
        transaction.requireTransaction = () -> {
            throw new IllegalStateException("no scope");
        };
        assertThrows(
                IllegalStateException.class, () -> transaction.write(transaction.read(), List.of(edit(1, 1, "f8"))));
        assertEquals(0, transaction.writes);
        for (String version : List.of("5.4.00")) {
            var f = new Fixture();
            f.resolver = resolver(version, "", "", false);
            assertThrows(UnsupportedOperationException.class, f::read);
        }
        var morph = new Fixture();
        morph.source.base.targets.targets.add(new Target(new Guid("f8"), null));
        assertThrows(UnsupportedOperationException.class, morph::read);
    }

    @Test
    void pathologicalDimensionsRejectBeforeControlCopiesAndOffThreadReadsFail() {
        var f = new Fixture();
        f.source.rows = Integer.MAX_VALUE;
        f.source.columns = Integer.MAX_VALUE;
        assertThrows(IllegalArgumentException.class, f::read);
        assertThrows(
                IllegalStateException.class, () -> new EditorWarpAuthoringReadAccess(f.resolver).read(ID, f.source));
    }

    @Test
    void protectedDefaultCollateralChangeIsNeverSilentlyRepaired() {
        var f = new Fixture();
        var before = f.read();
        f.afterMutation = () -> f.source.base.forms.get(4).positions[0] = 888;
        assertThrows(IllegalStateException.class, () -> f.write(before, List.of(edit(1, 1, "f8"))));
        assertEquals(888, f.read().forms().get(4).controlPoints().get(0).x());
    }

    public static final class WarpSource {
        final Source base = new Source();
        int rows = 1, columns = 1;

        public Guid guid() {
            return base.guid();
        }

        public Grid grid() {
            return base.grid();
        }

        public List<StoredForm> forms() {
            return base.forms();
        }

        public Targets targets() {
            return base.targets();
        }

        public int rows() {
            return rows;
        }

        public int columns() {
            return columns;
        }

        public boolean quad() {
            return true;
        }
    }

    private static VerifiedMemberResolver resolver(
            String version, String missingCapability, String missingAlias, boolean denyWrite) {
        var capabilities = new HashSet<>(EditorWarpAuthoringSelectorContract.CAPABILITY_IDS);
        if (!denyWrite) capabilities.add(EditorWarpAuthoringSelectorContract.WRITE_CAPABILITY_ID);
        capabilities.remove(missingCapability);
        var selectors = new ArrayList<StaticSelector>();
        for (var selector : EditorArtMeshAuthoringReadAccessTest.selectors())
            if (!selector.alias().contains("art-mesh")
                    && !selector.alias().contains("drawable-form")
                    && !selector.alias().equals("cubism.editor-model.parameter-controllable.keyform-grid")
                    && !selector.alias().equals("cubism.editor-model.parameter-controllable.morph-target-set"))
                selectors.add(selector);
        selectors.add(method("cubism.editor-model.deformer-source.guid", WarpSource.class, "guid"));
        selectors.add(method("cubism.editor-model.parameter-controllable.keyform-grid", WarpSource.class, "grid"));
        selectors.add(
                method("cubism.editor-model.parameter-controllable.morph-target-set", WarpSource.class, "targets"));
        selectors.add(method("cubism.editor-model.warp-source.keyforms", WarpSource.class, "forms"));
        selectors.add(method("cubism.editor-model.warp-source.row", WarpSource.class, "rows"));
        selectors.add(method("cubism.editor-model.warp-source.col", WarpSource.class, "columns"));
        selectors.add(method("cubism.editor-model.warp-source.quad-transform", WarpSource.class, "quad"));
        selectors.add(method("cubism.editor-model.warp-form.positions", StoredForm.class, "positions"));
        selectors.add(
                method("cubism.editor-model.warp-form.set-positions", StoredForm.class, "setPositions", float[].class));
        for (String alias : EditorWarpAuthoringSelectorContract.WRITE_ALIASES)
            if (selectors.stream().noneMatch(s -> s.alias().equals(alias)))
                selectors.add(method(alias, WarpSource.class, "guid"));
        return TestVerifiedResolvers.create(
                version,
                EditorWarpAuthoringSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors.stream().filter(s -> !s.alias().equals(missingAlias)).toList(),
                WarpSource.class.getClassLoader());
    }

    private static final class Fixture {
        final WarpSource source = new WarpSource();
        VerifiedMemberResolver resolver = resolver("5.3.02", "", "", false);
        int writes;
        Runnable beforeMutation = () -> {}, afterMutation = () -> {}, requireTransaction = () -> {};

        Fixture() {
            source.base.targets.targets.clear();
            source.base.forms.removeIf(f -> f.guid.text().startsWith("m"));
            var last = source.base.grid.rows.get("f0").remove(1);
            source.base.grid.rows.put("f8", new ArrayList<>(List.of(last)));
            source.base.forms.add(new StoredForm("f8", 8));
        }

        WarpAuthoringSnapshot read() {
            return EditorHostThread.dispatch(
                    "test Warp read", () -> new EditorWarpAuthoringReadAccess(resolver).read(ID, source));
        }

        void write(WarpAuthoringSnapshot before, List<WarpKeyformPositions> edits) {
            EditorWarpKeyformAccess.writeBatch(
                    resolver,
                    ID,
                    () -> source,
                    before,
                    edits,
                    requireTransaction,
                    (form, property, label, mutation, applied, compensation, restored) -> {
                        writes++;
                        beforeMutation.run();
                        try {
                            mutation.run();
                            afterMutation.run();
                            if (!applied.getAsBoolean()) throw new IllegalStateException("postcondition");
                        } catch (RuntimeException failure) {
                            if (!restored.getAsBoolean()) compensation.run();
                            assertTrue(restored.getAsBoolean());
                            throw failure;
                        }
                    });
        }
    }
}
