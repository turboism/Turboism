package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorObjectReadTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorClipMaskReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;
import dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpGrid;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorObjectReadAccessTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void warpMirrorIgnoresGlueOutsideSelectedHierarchy(final boolean preserveDescendants) {
        final Fixture fixture = new Fixture();
        fixture.source.artMeshSources.get(0).targetDeformer = null;
        final var result = mirrorWithGlue(fixture, preserveDescendants);
        assertEquals(WarpMirrorOutcome.NO_CHANGE, result.outcome());
        assertTrue(result.blockers().isEmpty());
        assertEquals(0, fixture.source.updateCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a-direct", "b-direct", "a-nested", "b-nested"})
    void warpMirrorAllowsGlueTouchingEitherDescendantEndpoint(final String relationship) {
        final Fixture fixture = new Fixture();
        final var root = fixture.source.deformerSources.get(0);
        final var rotation = fixture.source.deformerSources.get(1);
        root.targetDeformer = null;
        rotation.targetDeformer = relationship.endsWith("nested") ? root : null;
        final var mesh = fixture.source.artMeshSources.get(0);
        final var mask = fixture.source.artMeshSources.get(1);
        mesh.targetDeformer = null;
        mask.targetDeformer = null;
        final var endpoint = relationship.startsWith("a") ? mesh : mask;
        endpoint.targetDeformer = relationship.endsWith("nested") ? rotation : root;
        if (relationship.endsWith("nested")) rotation.locked = true;
        final var result = mirrorWithGlue(fixture, true);
        if (relationship.endsWith("nested")) {
            assertEquals(
                    List.of(WarpMirrorBlockerCode.DESCENDANT_LOCKED),
                    result.blockers().stream().map(b -> b.code()).toList());
        } else {
            assertEquals(WarpMirrorOutcome.NO_CHANGE, result.outcome());
            assertTrue(result.blockers().isEmpty());
        }
        assertEquals(0, fixture.source.updateCount);
    }

    @Test
    void unrelatedGlueDoesNotMaskDescendantLockCheck() {
        final Fixture fixture = new Fixture();
        fixture.source.artMeshSources.get(0).locked = true;
        final var mask = fixture.source.artMeshSources.get(1);
        final var other = new ArtMeshSource("ArtMeshOther", "Other Mesh", false, fixture.failures);
        fixture.source.artMeshSources.add(other);
        fixture.source.model.artMeshes.add(new ArtMesh(other));
        fixture.source.glueSources.clear();
        fixture.source.glueSources.add(new GlueSource(mask, other, fixture.failures));
        final var result = mirrorWithGlue(fixture, true);
        assertEquals(
                List.of(WarpMirrorBlockerCode.DESCENDANT_LOCKED),
                result.blockers().stream().map(b -> b.code()).toList());
    }

    private static WarpMirrorResult mirrorWithGlue(final Fixture fixture, final boolean preserveDescendants) {
        final var aliases = new java.util.ArrayList<>(selectors());
        aliases.add(
                method("cubism.editor-model.warp-source.keyforms", WarpSource.class, "keyforms", "()Ljava/util/List;"));
        aliases.add(method(
                "cubism.editor-model.model-source.default-keyform-locked",
                ModelSource.class,
                "defaultKeyformLocked",
                "()Z"));
        aliases.add(method(
                "cubism.editor-model.parameter-controllable.calculated-form",
                ArtMesh.class,
                "calculatedForm",
                desc(ArtMeshForm.class)));
        aliases.add(method(
                "cubism.editor-model.art-mesh-source.keyforms", ArtMeshSource.class, "keyforms", "()Ljava/util/List;"));
        final var resolver = TestVerifiedResolvers.create(
                "5.3.02",
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(EditorObjectReadSelectorContract.CAPABILITY_ID),
                aliases,
                Host.class.getClassLoader());
        final var objects = new EditorObjectReadAccess(
                resolver,
                (identity, model) -> {},
                new EditorMorphTargetAccess(resolver, (identity, model) -> {}),
                null);
        final var coordinator = new EditorAuthoringTransactionCoordinator(new GridRootHost(fixture));
        final var mirror = new EditorWarpMirrorAccess(
                resolver,
                objects,
                () -> new EditorWarpMirrorAccess.NativeBinding("session", 1, fixture.source, fixture.source.model),
                coordinator,
                () -> {
                    throw new AssertionError("Preflight must not open a transaction");
                });
        final var root = (Warp) fixture.source.model.deformers.get(0);
        final var source = (WarpSource) root.source;
        source.row = 1;
        source.col = 1;
        ((WarpForm) root.form).positions = new float[] {0, 0, 1, 0, 0, 1, 1, 1};
        return mirror.apply(new WarpMirrorRequest(
                new DeformerId("WarpFace"), WarpMirrorDirection.LEFT_TO_RIGHT, preserveDescendants));
    }

    @ParameterizedTest
    @ValueSource(strings = {"artmesh", "deformer"})
    void objectMembershipRetainsSourceInstanceBijectionAcrossFreshReads(final String kind) {
        final Fixture fixture = new Fixture();
        var core = new EditorObjectReadCore(resolver("5.3.02"), (identity, model) -> {}, null);
        var source = fixture.source;
        var model = source.model;
        final boolean isArtMesh = "artmesh".equals(kind);
        final java.util.function.Supplier<List<?>> reader = () ->
                isArtMesh ? core.artMeshes("session", source, model) : core.deformerRefs("session", source, model);

        assertEquals(2, reader.get().size());
        if (isArtMesh) {
            var original = model.artMeshes.get(0);
            model.artMeshes.add(new ArtMesh(original.source));
            assertThrows(IllegalStateException.class, reader::get);
            model.artMeshes.remove(model.artMeshes.size() - 1);
            model.artMeshes.set(
                    0, new ArtMesh(new ArtMeshSource("ArtMeshFace", "Replacement", true, fixture.failures)));
            assertThrows(IllegalStateException.class, reader::get);
            model.artMeshes.set(0, original);
            var replaced = new ArtMesh(original.source);
            model.artMeshes.set(0, replaced);
            org.junit.jupiter.api.Assertions.assertSame(
                    replaced, core.artMeshes("session", source, model).get(0).instance());
            source.artMeshSources.add(original.source);
            assertThrows(IllegalStateException.class, reader::get);
            source.artMeshSources.remove(source.artMeshSources.size() - 1);
            model.artMeshes.clear();
            assertThrows(IllegalStateException.class, reader::get);
        } else {
            var original = model.deformers.get(0);
            model.deformers.add(new Warp((WarpSource) source.deformerSources.get(0)));
            assertThrows(IllegalStateException.class, reader::get);
            model.deformers.remove(model.deformers.size() - 1);
            model.deformers.set(0, new Warp(new WarpSource(fixture.failures)));
            assertThrows(IllegalStateException.class, reader::get);
            model.deformers.set(0, original);
            var replaced = new Warp((WarpSource) source.deformerSources.get(0));
            model.deformers.set(0, replaced);
            org.junit.jupiter.api.Assertions.assertSame(
                    replaced, core.deformerRefs("session", source, model).get(0).instance());
            source.deformerSources.add(source.deformerSources.get(0));
            assertThrows(IllegalStateException.class, reader::get);
            source.deformerSources.remove(source.deformerSources.size() - 1);
            model.deformers.clear();
            assertThrows(IllegalStateException.class, reader::get);
        }
    }

    @Test
    void replaySourceSurvivesRebuiltInstanceButRejectsReplacedSourceAndDocument() {
        final Fixture fixture = new Fixture();
        boolean[] active = {true};
        var core = new EditorObjectReadCore(
                resolver("5.3.02"),
                (identity, model) -> {
                    if (!active[0]) throw new IllegalStateException("stale document");
                },
                null);
        var ref = core.deformerRefs("session", fixture.source, fixture.source.model)
                .get(0);
        fixture.source.model.deformers.set(0, new Warp((WarpSource) ref.source()));
        assertThrows(
                IllegalStateException.class,
                () -> core.currentDeformer("session", fixture.source, fixture.source.model, ref));
        org.junit.jupiter.api.Assertions.assertSame(
                ref.source(), core.replayDeformerSource("session", fixture.source, fixture.source.model, ref));
        active[0] = false;
        assertThrows(
                IllegalStateException.class,
                () -> core.replayDeformerSource("session", fixture.source, fixture.source.model, ref));
        active[0] = true;
        fixture.source.deformerSources.set(0, new WarpSource(fixture.failures));
        assertThrows(
                IllegalStateException.class,
                () -> core.replayDeformerSource("session", fixture.source, fixture.source.model, ref));
        fixture.source.deformerSources.clear();
        assertThrows(
                IllegalStateException.class,
                () -> core.replayDeformerSource("session", fixture.source, fixture.source.model, ref));
    }

    @org.junit.jupiter.api.Test
    void warpGridChangesJoinOneRootAndCompensatePartialSetterFailure() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var aliases = new java.util.ArrayList<>(selectors());
        aliases.add(method(
                "cubism.editor-history.document.undo-manager",
                Document.class,
                "undoManager",
                desc(GridHistoryManager.class)));
        aliases.add(StaticSelector.classSelector(
                "cubism.editor-history.manager.class", internal(GridHistoryManager.class)));
        aliases.add(method(
                "cubism.editor-history.manager.entries", GridHistoryManager.class, "entries", "()Ljava/util/List;"));
        aliases.add(method("cubism.editor-history.manager.position", GridHistoryManager.class, "position", "()I"));
        aliases.add(method("cubism.editor-history.manager.can-undo", GridHistoryManager.class, "canUndo", "()Z"));
        aliases.add(method("cubism.editor-history.manager.can-redo", GridHistoryManager.class, "canRedo", "()Z"));
        aliases.add(
                StaticSelector.classSelector("cubism.editor-history.entry.class", internal(GridHistoryEntry.class)));
        aliases.add(method(
                "cubism.editor-history.entry.presentation-name",
                GridHistoryEntry.class,
                "label",
                "()Ljava/lang/String;"));
        aliases.add(method("cubism.editor-history.entry.significant", GridHistoryEntry.class, "significant", "()Z"));
        final var resolver = TestVerifiedResolvers.create(
                "5.3.02",
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(
                        EditorObjectReadSelectorContract.CAPABILITY_ID,
                        EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID,
                        "cubism.editor-history.read"),
                aliases,
                Host.class.getClassLoader());
        final var binding = edtCall(
                () -> new dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding(
                        "probe", "doc", 1, "model", 1, Thread.currentThread()));
        final var host = new GridRootHost(fixture);
        final var coordinator =
                new dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator(host);
        final var access = new EditorObjectReadAccess(
                resolver,
                (identity, model) -> {},
                new EditorMorphTargetAccess(resolver, (identity, model) -> {}),
                null,
                null,
                null,
                coordinator,
                () -> binding);
        final var warp = access.warpDeformers("session-a", fixture.source, fixture.source.model)
                .find(new DeformerId("WarpFace"));
        final WarpGrid first = new WarpGrid(
                1, 1, true, List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2), new Point2(2, 2)));
        final WarpGrid second = new WarpGrid(
                1, 1, false, List.of(new Point2(3, 4), new Point2(5, 4), new Point2(3, 6), new Point2(5, 6)));
        final var committed = edtCall(() -> coordinator.execute(
                binding, dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("Import root"), () -> {
                    warp.replaceGrid(first);
                    warp.replaceGrid(second);
                    return null;
                }));
        assertEquals(
                dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome.COMMITTED,
                committed.outcome(),
                () -> String.valueOf(host.failure));
        assertEquals(second, warp.grid());
        assertEquals(1, host.begins);
        assertEquals(1, fixture.document.editMode.edits.size());
        assertEquals(2, fixture.document.editMode.edits.get(0).undoAddCount);
        final var before = host.history(binding);
        fixture.failures.failOn(4);
        final var failed = edtCall(() -> coordinator.execute(
                binding, dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("Failed root"), () -> {
                    warp.replaceGrid(first);
                    return null;
                }));
        assertEquals(dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome.ROLLED_BACK, failed.outcome());
        assertEquals(second, warp.grid());
        assertEquals(before, host.history(binding));
        assertEquals(1, fixture.document.editMode.edits.size());
    }

    @org.junit.jupiter.api.Test
    void rotationFormsJoinOneRootAndCompensatePartialSetterFailure() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var aliases = new java.util.ArrayList<>(selectors());
        aliases.add(method(
                "cubism.editor-history.document.undo-manager",
                Document.class,
                "undoManager",
                desc(GridHistoryManager.class)));
        aliases.add(StaticSelector.classSelector(
                "cubism.editor-history.manager.class", internal(GridHistoryManager.class)));
        aliases.add(method(
                "cubism.editor-history.manager.entries", GridHistoryManager.class, "entries", "()Ljava/util/List;"));
        aliases.add(method("cubism.editor-history.manager.position", GridHistoryManager.class, "position", "()I"));
        aliases.add(method("cubism.editor-history.manager.can-undo", GridHistoryManager.class, "canUndo", "()Z"));
        aliases.add(method("cubism.editor-history.manager.can-redo", GridHistoryManager.class, "canRedo", "()Z"));
        aliases.add(
                StaticSelector.classSelector("cubism.editor-history.entry.class", internal(GridHistoryEntry.class)));
        aliases.add(method(
                "cubism.editor-history.entry.presentation-name",
                GridHistoryEntry.class,
                "label",
                "()Ljava/lang/String;"));
        aliases.add(method("cubism.editor-history.entry.significant", GridHistoryEntry.class, "significant", "()Z"));
        final var resolver = TestVerifiedResolvers.create(
                "5.3.02",
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(
                        EditorObjectReadSelectorContract.CAPABILITY_ID,
                        EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID,
                        "cubism.editor-history.read"),
                aliases,
                Host.class.getClassLoader());
        final var binding = edtCall(
                () -> new dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding(
                        "probe", "doc", 1, "model", 1, Thread.currentThread()));
        final var host = new GridRootHost(fixture);
        final var coordinator =
                new dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator(host);
        final var access = new EditorObjectReadAccess(
                resolver,
                (identity, model) -> {},
                new EditorMorphTargetAccess(resolver, (identity, model) -> {}),
                null,
                null,
                null,
                coordinator,
                () -> binding);
        final var rotation = access.rotationDeformers("session-a", fixture.source, fixture.source.model)
                .find(new DeformerId("RotationHead"));
        final var first = new RotationDeformerForm(-180, 20, 30, 1, false, false);
        final var second = new RotationDeformerForm(180, 20, 30, 1, false, false);
        final var committed = edtCall(() -> coordinator.execute(
                binding, dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("Import root"), () -> {
                    rotation.replaceForm(first);
                    rotation.replaceForm(second);
                    return null;
                }));
        assertEquals(
                dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome.COMMITTED,
                committed.outcome(),
                () -> String.valueOf(host.failure));
        assertEquals(second, rotation.form());
        assertEquals(1, host.begins);
        assertEquals(1, fixture.document.editMode.edits.size());
        assertEquals(2, fixture.document.editMode.edits.get(0).undoAddCount);
        final var before = host.history(binding);
        fixture.failures.failOn(4);
        final var failed = edtCall(() -> coordinator.execute(
                binding, dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("Failed root"), () -> {
                    rotation.replaceForm(first);
                    return null;
                }));
        assertEquals(dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome.ROLLED_BACK, failed.outcome());
        assertEquals(second, rotation.form());
        assertEquals(before, host.history(binding));
        assertEquals(1, fixture.document.editMode.edits.size());
    }

    private static final class GridRootHost
            implements dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Host {
        @Override
        public void undoEditGroup(
                final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding
                        expected,
                final Object edit) {}

        final Fixture fixture;
        int begins;
        Throwable failure;
        final java.util.ArrayList<dev.turboism.sdk.cubism.history.HistoryEntry> entries = new java.util.ArrayList<>();

        GridRootHost(Fixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public boolean isCurrent(
                dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding b) {
            return b.hostThread() == Thread.currentThread();
        }

        @Override
        public dev.turboism.sdk.cubism.history.HistorySnapshot history(
                dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding b) {
            return new dev.turboism.sdk.cubism.history.HistorySnapshot(
                    dev.turboism.sdk.cubism.history.HistorySnapshot.Availability.AVAILABLE,
                    1,
                    entries.size(),
                    entries.size(),
                    List.copyOf(entries),
                    !entries.isEmpty(),
                    false,
                    "doc",
                    "manager");
        }

        @Override
        public Object beginEdit(
                dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding b,
                String label) {
            begins++;
            return fixture.document.editMode.begin(label);
        }

        @Override
        public void endEdit(
                dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding b,
                Object edit,
                boolean abort) {
            fixture.document.editMode.end(abort, null);
            if (!abort) entries.add(new dev.turboism.sdk.cubism.history.HistoryEntry(entries.size(), "root", true));
        }

        @Override
        public void refresh(
                dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding b,
                java.util.Set<dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement> requirements) {}

        @Override
        public java.util.Optional<String> committedHistoryEntryId(
                dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding b,
                dev.turboism.sdk.cubism.history.HistorySnapshot before,
                dev.turboism.sdk.cubism.history.HistorySnapshot after,
                String id,
                String label) {
            return after.position() == before.position() + 1
                    ? java.util.Optional.of("entry-" + after.position())
                    : java.util.Optional.empty();
        }

        @Override
        public String diagnosticId(String code, Throwable failure) {
            this.failure = failure;
            return code;
        }
    }

    @org.junit.jupiter.api.Test
    void storedSnapshotDispatchesCurrentCheckAndRejectsReplacedSourceBeforeDecoding() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var resolver = resolver("5.3.02"); // No N1 capability: decoder must fail closed.
        final boolean[] checkingSnapshot = {false};
        final var access = new EditorObjectReadAccess(
                resolver,
                (identity, model) -> {
                    if (checkingSnapshot[0]) assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
                },
                new EditorMorphTargetAccess(resolver, (identity, model) -> {}),
                null);
        final var mesh = access.drawables("session-a", fixture.source, fixture.source.model)
                .find(new ArtMeshId("ArtMeshFace"));
        checkingSnapshot[0] = true;
        assertThrows(UnsupportedOperationException.class, mesh::authoringSnapshot);
        assertThrows(UnsupportedOperationException.class, mesh::evaluationSnapshot);
        fixture.replaceAllWithSameIds();
        assertThrows(IllegalStateException.class, mesh::authoringSnapshot);
        assertThrows(IllegalStateException.class, mesh::evaluationSnapshot);
        assertTrue(fixture.document.editMode.edits.isEmpty());
        assertFalse(fixture.document.dirty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsArtMeshWarpAndRotationAuthoringState(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();

        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        assertEquals(0, mesh.index());
        assertEquals("Face Mesh", mesh.name());
        assertTrue(mesh.visible());
        assertEquals(false, mesh.locked());
        assertEquals(0.75F, mesh.getOpacity());
        assertEquals(7, mesh.drawOrder());
        assertEquals(new Point2(1.0F, 0.0F), mesh.geometry().positions().get(1));
        assertEquals(List.of(0, 1, 2), mesh.geometry().triangleIndices());
        assertEquals("face", mesh.userData());
        assertTrue(mesh.culling());
        assertFalse(mesh.doubleSided());
        assertTrue(mesh.invertedMask());
        assertEquals("PartFace", mesh.parentPartId().orElseThrow().value());
        assertEquals("WarpFace", mesh.parentDeformerId().orElseThrow().value());
        assertEquals(
                List.of("ParamAngleX"),
                mesh.parameterIds().stream().map(value -> value.value()).toList());
        assertEquals(
                List.of("ArtMeshMask"),
                mesh.maskIds().stream().map(value -> value.value()).toList());
        assertEquals("guid:ArtMeshFace", mesh.guid());
        assertEquals(0, mesh.parentPartIndex());
        assertEquals(0, mesh.parentDeformerIndex());
        assertEquals(0, mesh.parameters().get(0));
        assertEquals(1, mesh.masks().get(0));
        assertEquals(
                List.of("ArtMeshFace", "ArtMeshMask"),
                model.drawables().all().stream()
                        .map(value -> value.id().value())
                        .toList());
        assertTrue(model.drawables().find(new ArtMeshId("ArtMeshMask")).doubleSided());

        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        assertEquals(0, warp.index());
        assertEquals("Face Warp", warp.name());
        assertEquals(0.8F, warp.getOpacity());
        assertEquals(2, warp.grid().rows());
        assertEquals(3, warp.grid().columns());
        assertEquals(new Point2(4.0F, 4.5F), warp.grid().controlPoints().get(4));
        assertEquals("PartFace", warp.parentPartId().orElseThrow().value());
        assertEquals("RotationHead", warp.parentDeformerId().orElseThrow().value());
        assertEquals(
                List.of("ParamAngleX"),
                warp.parameterIds().stream().map(value -> value.value()).toList());
        assertEquals(0, warp.parentPartIndex());
        assertEquals(1, warp.parentDeformerIndex());
        assertEquals(0, warp.parameters().get(0));

        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));
        assertEquals(1, rotation.index());
        assertEquals("Head Rotation", rotation.name());
        assertEquals(0.9F, rotation.getOpacity());
        assertEquals(30.0F, rotation.baseAngle());
        assertEquals(15.0F, rotation.form().angle());
        assertEquals(new Point2(2.0F, 3.0F), rotation.form().origin());
        assertEquals(1.25F, rotation.form().scale());
        assertTrue(rotation.form().reflectedX());

        final var glue = model.glues().find(new GlueId("GlueFace"));
        assertEquals(0, glue.index());
        assertEquals("ArtMeshFace", glue.drawableAId().value());
        assertEquals("ArtMeshMask", glue.drawableBId().value());
        assertEquals(0, glue.drawableA());
        assertEquals(1, glue.drawableB());
        assertEquals(
                List.of("ParamAngleX"),
                glue.parameterIds().stream().map(value -> value.value()).toList());
        assertEquals(0, glue.parameters().get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void replacesClipMasksAfterCompletePreflightInOneTransaction(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final List<ClipMaskReplacement> replacements = List.of(
                new ClipMaskReplacement(
                        new ArtMeshId("ArtMeshFace"),
                        List.of(new ArtMeshId("ArtMeshMask")),
                        true,
                        List.of(new ArtMeshId("ArtMeshMask")),
                        false),
                new ClipMaskReplacement(
                        new ArtMeshId("ArtMeshMask"), List.of(), false, List.of(new ArtMeshId("ArtMeshFace")), true));

        model.replaceArtMeshClipMasks(replacements);

        assertEquals(List.of(fixture.maskSource().guid), fixture.meshSource().clipGuids());
        assertFalse(fixture.meshSource().invertedMask());
        assertEquals(List.of(fixture.meshSource().guid), fixture.maskSource().clipGuids());
        assertTrue(fixture.maskSource().invertedMask());
        assertEquals(
                List.of(
                        "clip:ArtMeshFace", "invert:ArtMeshFace",
                        "clip:ArtMeshMask", "invert:ArtMeshMask"),
                fixture.failures.setterEvents);
        assertEquals(1, fixture.document.editMode.edits.size());
        // One edit session admits one target-handler Undo snapshot per planned target in
        // plan order on both exact routes (host evidence: the handler snapshot is
        // target-scoped on 5.2 and 5.3.02); the single edit session still merges into
        // one Undo step.
        assertEquals(2, fixture.document.editMode.edits.get(0).undoAddCount);
        assertEquals(List.of("ArtMeshFace", "ArtMeshMask"), fixture.document.editMode.edits.get(0).undoTargets);
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.document.pack.partRefreshCount);
        assertEquals(1, fixture.document.pack.repaintCount);
        assertTrue(fixture.document.dirty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void clipMaskExpectedMismatchDoesNotOpenAnEdit(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();

        assertThrows(
                IllegalStateException.class,
                () -> model.replaceArtMeshClipMasks(List.of(new ClipMaskReplacement(
                        new ArtMeshId("ArtMeshFace"),
                        List.of(new ArtMeshId("ArtMeshMask")),
                        false,
                        List.of(new ArtMeshId("ArtMeshMask")),
                        false))));

        assertAbortedWithoutPublishedEffects(fixture);
        assertEquals(List.of(), fixture.failures.setterEvents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void clipMaskSetterFailureRestoresTheWholeBatch(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        fixture.failures.failOn(4);

        assertThrows(
                RuntimeException.class,
                () -> model.replaceArtMeshClipMasks(List.of(
                        new ClipMaskReplacement(
                                new ArtMeshId("ArtMeshFace"),
                                List.of(new ArtMeshId("ArtMeshMask")),
                                true,
                                List.of(new ArtMeshId("ArtMeshMask")),
                                false),
                        new ClipMaskReplacement(
                                new ArtMeshId("ArtMeshMask"),
                                List.of(),
                                false,
                                List.of(new ArtMeshId("ArtMeshFace")),
                                true))));

        assertEquals(List.of(fixture.maskSource().guid), fixture.meshSource().clipGuids());
        assertTrue(fixture.meshSource().invertedMask());
        assertEquals(List.of(), fixture.maskSource().clipGuids());
        assertFalse(fixture.maskSource().invertedMask());
        assertAbortedWithoutPublishedEffects(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void clipMaskRejectedUndoAdmissionAbortsBeforeMutation(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        // Both routes admit one snapshot per target; rejection of any admission must
        // fail before any mutation and abort the single edit session.
        fixture.failures.rejectUndoAdmission(2);
        assertThrows(
                IllegalStateException.class,
                () -> model.replaceArtMeshClipMasks(List.of(
                        new ClipMaskReplacement(
                                new ArtMeshId("ArtMeshFace"),
                                List.of(new ArtMeshId("ArtMeshMask")),
                                true,
                                List.of(new ArtMeshId("ArtMeshMask")),
                                false),
                        new ClipMaskReplacement(
                                new ArtMeshId("ArtMeshMask"),
                                List.of(),
                                false,
                                List.of(new ArtMeshId("ArtMeshFace")),
                                true))));
        assertAbortedWithoutPublishedEffects(fixture);
        assertEquals(List.of(), fixture.failures.setterEvents);
    }

    @Test
    void clipMaskWriteFailsClosedWithoutDedicatedVerifiedCapability() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();

        assertThrows(
                UnsupportedOperationException.class,
                () -> model.replaceArtMeshClipMasks(List.of(new ClipMaskReplacement(
                        new ArtMeshId("ArtMeshFace"),
                        List.of(new ArtMeshId("ArtMeshMask")),
                        true,
                        List.of(new ArtMeshId("ArtMeshMask")),
                        false))));

        assertAbortedWithoutPublishedEffects(fixture);
        assertEquals(List.of(), fixture.failures.setterEvents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsOrderedKeyformBindingsForArtMeshWarpAndRotation(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();

        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));

        assertBinding(mesh.getParameterBindings(), dev.turboism.sdk.cubism.model.ParameterBindingTargetType.ART_MESH);
        assertBinding(
                warp.getParameterBindings(), dev.turboism.sdk.cubism.model.ParameterBindingTargetType.WARP_DEFORMER);
        assertBinding(
                rotation.getParameterBindings(),
                dev.turboism.sdk.cubism.model.ParameterBindingTargetType.ROTATION_DEFORMER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void mergesMorphTargetBindingsAfterKeyformForDrawableAndDeformer(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        fixture.addParameter("EyeParam");
        fixture.meshSource().morphTargetSet.targets.add(new HostMorphTarget("EyeParam", 1.0F));
        fixture.source.deformerSources.get(0).morphTargetSet.targets.add(new HostMorphTarget("EyeParam", 0.25F));
        final var model = new EditorBackedCubismModelAccess(resolver(version, false, true), "session-a").active();

        final var meshBindings =
                model.drawables().find(new ArtMeshId("ArtMeshFace")).getParameterBindings();
        assertEquals(2, meshBindings.size());
        final var keyform = meshBindings.get(0);
        assertEquals(dev.turboism.sdk.cubism.model.ParameterBindingFamily.KEYFORM_GRID, keyform.family());
        assertEquals("ParamAngleX", keyform.parameterId().value());
        final var morph = meshBindings.get(1);
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingTargetType.ART_MESH,
                morph.target().type());
        assertEquals(dev.turboism.sdk.cubism.model.ParameterBindingFamily.BLEND_SHAPE, morph.family());
        assertEquals("EyeParam", morph.parameterId().value());
        assertEquals(1, morph.points().size());
        assertEquals("EyeParam:morph:0", morph.points().get(0).id().value());
        assertEquals(1.0F, morph.points().get(0).value());

        final var warpBindings =
                model.warpDeformers().find(new DeformerId("WarpFace")).getParameterBindings();
        assertEquals(2, warpBindings.size());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingFamily.BLEND_SHAPE,
                warpBindings.get(1).family());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingTargetType.WARP_DEFORMER,
                warpBindings.get(1).target().type());

        // SDK derived projections filter the merged list by family
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        assertEquals(
                List.of("EyeParam"),
                mesh.getMorphParameterBindings().stream()
                        .map(value -> value.parameterId().value())
                        .toList());
        assertEquals(
                List.of("ParamAngleX"),
                mesh.getNormalParameterBindings().stream()
                        .map(value -> value.parameterId().value())
                        .toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void deduplicatesKeyformAndMorphBindingsByParameterPreferringMorph(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        // ParamAngleX appears TWICE in the same keyform grid: only the first row survives
        final KeyformBinding duplicateAngle = new KeyformBinding(List.of(10.0F, 20.0F));
        duplicateAngle.parameterId = new Id("ParamAngleX");
        fixture.meshSource().keyformGrid.bindings.add(duplicateAngle);
        fixture.addParameter("EyeParam");
        // EyeParam sits in BOTH containers: the keyform grid and the morph-target set
        final KeyformBinding eyeKeyform = new KeyformBinding();
        eyeKeyform.parameterId = new Id("EyeParam");
        fixture.meshSource().keyformGrid.bindings.add(eyeKeyform);
        fixture.meshSource().morphTargetSet.targets.add(new HostMorphTarget("EyeParam", 1.0F));
        fixture.meshSource().morphTargetSet.targets.add(new HostMorphTarget("EyeParam", 2.0F));
        final var model = new EditorBackedCubismModelAccess(resolver(version, false, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));

        final var bindings = mesh.getParameterBindings();
        // one row per parameter: first keyform ParamAngleX kept (duplicate dropped),
        // duplicated EyeParam keyform row replaced by the morph row
        assertEquals(2, bindings.size());
        assertEquals(
                List.of(-30.0F, 0.0F, 30.0F),
                bindings.get(0).points().stream()
                        .map(dev.turboism.sdk.cubism.model.ParameterBindingPoint::value)
                        .toList());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingFamily.KEYFORM_GRID,
                bindings.get(0).family());
        assertEquals("ParamAngleX", bindings.get(0).parameterId().value());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingFamily.BLEND_SHAPE,
                bindings.get(1).family());
        assertEquals("EyeParam", bindings.get(1).parameterId().value());
        assertEquals(
                List.of(1.0F, 2.0F),
                bindings.get(1).points().stream()
                        .map(dev.turboism.sdk.cubism.model.ParameterBindingPoint::value)
                        .toList());

        // the parameter-side reverse scan is deduplicated the same way
        final var reverse = model.parameters()
                .find(new dev.turboism.sdk.cubism.id.ParameterId("EyeParam"))
                .getParameterBindings();
        assertEquals(1, reverse.size());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingFamily.BLEND_SHAPE,
                reverse.get(0).family());

        // other parameters are unaffected
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        assertEquals(1, warp.getParameterBindings().size());
        assertEquals(
                List.of("ParamAngleX"),
                warp.getNormalParameterBindings().stream()
                        .map(value -> value.parameterId().value())
                        .toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void morphMergeFailsSoftWhenTheMorphCapabilityIsAbsent(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        fixture.meshSource().morphTargetSet.targets.add(new HostMorphTarget("EyeParam", 1.0F));
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));

        // keyform bindings keep their behavior; the morph portion is dropped
        assertEquals(1, mesh.getParameterBindings().size());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingFamily.KEYFORM_GRID,
                mesh.getParameterBindings().get(0).family());
        assertTrue(mesh.getMorphParameterBindings().isEmpty());
        // combined derivation fails soft (no combined evidence -> non-combined)
        assertTrue(mesh.getCombinedParameterBindings().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void parameterSideReverseScanIncludesMorphBindings(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        fixture.addParameter("EyeParam");
        fixture.meshSource().morphTargetSet.targets.add(new HostMorphTarget("EyeParam", 1.0F));
        final var model = new EditorBackedCubismModelAccess(resolver(version, false, true), "session-a").active();

        final var reverse = model.parameters()
                .find(new dev.turboism.sdk.cubism.id.ParameterId("EyeParam"))
                .getParameterBindings();
        assertEquals(1, reverse.size());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingTargetType.ART_MESH,
                reverse.get(0).target().type());
        assertEquals("ArtMeshFace", reverse.get(0).target().id());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingFamily.BLEND_SHAPE,
                reverse.get(0).family());
        assertEquals(
                List.of(1.0F),
                reverse.get(0).points().stream()
                        .map(dev.turboism.sdk.cubism.model.ParameterBindingPoint::value)
                        .toList());

        // keyform bindings still flow through the reverse scan for ArtMesh and Deformers
        final var keyformReverse = model.parameters()
                .find(new dev.turboism.sdk.cubism.id.ParameterId("ParamAngleX"))
                .getParameterBindings();
        assertTrue(keyformReverse.stream()
                .allMatch(
                        value -> value.family() == dev.turboism.sdk.cubism.model.ParameterBindingFamily.KEYFORM_GRID));
        assertTrue(keyformReverse.stream()
                .anyMatch(value ->
                        value.target().type() == dev.turboism.sdk.cubism.model.ParameterBindingTargetType.ART_MESH));
        assertTrue(keyformReverse.stream()
                .anyMatch(value -> value.target().type()
                        == dev.turboism.sdk.cubism.model.ParameterBindingTargetType.WARP_DEFORMER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void combinedParameterBindingsDeriveFromTheParameterCombinedFlag(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        fixture.source.model.parameterSet.parameters.get(0).source.combined = true; // ParamAngleX
        final var model = new EditorBackedCubismModelAccess(resolver(version, false, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));

        assertEquals(
                List.of("ParamAngleX"),
                mesh.getCombinedParameterBindings().stream()
                        .map(value -> value.parameterId().value())
                        .toList());
        assertEquals(
                dev.turboism.sdk.cubism.model.ParameterBindingFamily.KEYFORM_GRID,
                mesh.getCombinedParameterBindings().get(0).family());
        assertEquals(
                List.of("ParamAngleX"),
                model.warpDeformers().find(new DeformerId("WarpFace")).getCombinedParameterBindings().stream()
                        .map(value -> value.parameterId().value())
                        .toList());

        // normal = keyform-grid bindings whose parameter is neither morph nor combined
        assertTrue(mesh.getNormalParameterBindings().isEmpty(), "combined parameters are not normal");
        assertTrue(
                model.warpDeformers()
                        .find(new DeformerId("WarpFace"))
                        .getNormalParameterBindings()
                        .isEmpty(),
                "combined parameters are not normal on deformers");

        // morph bindings are never combined and never normal
        fixture.addParameter("EyeParam");
        fixture.meshSource().morphTargetSet.targets.add(new HostMorphTarget("EyeParam", 1.0F));
        assertEquals(
                List.of("ParamAngleX"),
                mesh.getCombinedParameterBindings().stream()
                        .map(value -> value.parameterId().value())
                        .toList());
        assertTrue(mesh.getNormalParameterBindings().isEmpty(), "morph bindings are not normal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void editsParameterBindingsWithStrictConflictsAndExplicitUnbind(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var operations = model.parameterBindings(new dev.turboism.sdk.cubism.id.ParameterId("ParamAngleX"));
        final var target = dev.turboism.sdk.cubism.model.ParameterBindingTarget.artMesh(new ArtMeshId("ArtMeshFace"));

        operations.unbind(target);
        operations.bind(target, List.of(point("new:0", -30.0F), point("new:1", 30.0F)));
        operations.createPoint(target, point("ignored", 0.0F));
        var binding = model.drawables()
                .find(new ArtMeshId("ArtMeshFace"))
                .getParameterBindings()
                .get(0);
        assertThrows(IllegalStateException.class, () -> operations.createPoint(target, point("conflict", 0.0F)));
        operations.movePoint(target, binding.points().get(1).id(), 5.0F);
        binding = model.drawables()
                .find(new ArtMeshId("ArtMeshFace"))
                .getParameterBindings()
                .get(0);
        assertEquals(
                List.of(-30.0F, 5.0F, 30.0F),
                binding.points().stream().map(point -> point.value()).toList());
        operations.deletePoint(target, binding.points().get(0).id());
        binding = model.drawables()
                .find(new ArtMeshId("ArtMeshFace"))
                .getParameterBindings()
                .get(0);
        operations.deletePoint(target, binding.points().get(0).id());
        final var last = model.drawables()
                .find(new ArtMeshId("ArtMeshFace"))
                .getParameterBindings()
                .get(0)
                .points()
                .get(0);
        assertThrows(IllegalStateException.class, () -> operations.deletePoint(target, last.id()));
        operations.unbind(target);
        operations.bind(target, List.of(point("batch:0", -30.0F), point("batch:1", 0.0F), point("batch:2", 30.0F)));

        assertTrue(fixture.document.dirty);
        assertEquals(8, fixture.document.editMode.edits.size());
        assertEquals(8, fixture.source.updateCount);
        assertEquals(8, fixture.document.pack.parameterRefreshCount);
        assertEquals(8, fixture.document.pack.partRefreshCount);
        assertEquals(0, fixture.document.pack.deformerRefreshCount);
        assertEquals(8, fixture.document.pack.repaintCount);

        final var warpTarget =
                dev.turboism.sdk.cubism.model.ParameterBindingTarget.warpDeformer(new DeformerId("WarpFace"));
        final var rotationTarget =
                dev.turboism.sdk.cubism.model.ParameterBindingTarget.rotationDeformer(new DeformerId("RotationHead"));
        operations.unbind(warpTarget);
        operations.bind(warpTarget, List.of(point("warp:0", -30.0F), point("warp:1", 0.0F), point("warp:2", 30.0F)));
        operations.unbind(rotationTarget);
        operations.bind(
                rotationTarget,
                List.of(point("rotation:0", -30.0F), point("rotation:1", 0.0F), point("rotation:2", 30.0F)));
        assertBinding(
                model.warpDeformers().find(new DeformerId("WarpFace")).getParameterBindings(),
                dev.turboism.sdk.cubism.model.ParameterBindingTargetType.WARP_DEFORMER);
        assertBinding(
                model.rotationDeformers().find(new DeformerId("RotationHead")).getParameterBindings(),
                dev.turboism.sdk.cubism.model.ParameterBindingTargetType.ROTATION_DEFORMER);
        assertEquals(4, fixture.document.pack.deformerRefreshCount);

        final var batch = model.parameterBindingBatch();
        batch.invert(List.of(target));
        assertEquals(
                List.of(30.0F, 0.0F, -30.0F),
                model.drawables().find(new ArtMeshId("ArtMeshFace")).getParameterBindings().get(0).points().stream()
                        .map(point -> point.value())
                        .toList());
        batch.transfer(new dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan(
                new dev.turboism.sdk.cubism.id.ParameterId("ParamAngleX"),
                new dev.turboism.sdk.cubism.id.ParameterId("ParamAngleY"),
                List.of(target),
                true));
        final var transferred =
                model.drawables().find(new ArtMeshId("ArtMeshFace")).getParameterBindings();
        assertEquals("ParamAngleY", transferred.get(0).parameterId().value());
        assertEquals(
                List.of(-30.0F, 0.0F, 30.0F),
                transferred.get(0).points().stream().map(point -> point.value()).toList());
        assertEquals(14, fixture.document.editMode.edits.size());

        operations.unbind(rotationTarget);
        final int editsBeforeEmptyInversion = fixture.document.editMode.edits.size();
        batch.invert(List.of(rotationTarget));
        assertEquals(editsBeforeEmptyInversion, fixture.document.editMode.edits.size());
    }

    private static dev.turboism.sdk.cubism.model.ParameterBindingPoint point(final String id, final float value) {
        return new dev.turboism.sdk.cubism.model.ParameterBindingPoint(
                new dev.turboism.sdk.cubism.id.ParameterBindingPointId(id), value);
    }

    private static <T> T edtCall(final java.util.concurrent.Callable<T> task) {
        final java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try {
                    ref.set(task.call());
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        } catch (java.lang.reflect.InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return ref.get();
    }

    private static void assertBinding(
            final List<dev.turboism.sdk.cubism.model.ParameterBinding> bindings,
            final dev.turboism.sdk.cubism.model.ParameterBindingTargetType targetType) {
        assertEquals(1, bindings.size());
        final var binding = bindings.get(0);
        assertEquals(targetType, binding.target().type());
        assertEquals("ParamAngleX", binding.parameterId().value());
        assertEquals(dev.turboism.sdk.cubism.model.ParameterBindingFamily.KEYFORM_GRID, binding.family());
        assertEquals(
                List.of(-30.0F, 0.0F, 30.0F),
                binding.points().stream().map(point -> point.value()).toList());
        assertEquals(
                List.of("ParamAngleX:0", "ParamAngleX:1", "ParamAngleX:2"),
                binding.points().stream().map(point -> point.id().value()).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void sameIdSourceReplacementMakesReferencesStale(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));
        final var glue = model.glues().find(new GlueId("GlueFace"));

        fixture.replaceAllWithSameIds();

        assertThrows(IllegalStateException.class, mesh::name);
        assertThrows(IllegalStateException.class, warp::grid);
        assertThrows(IllegalStateException.class, rotation::form);
        assertThrows(IllegalStateException.class, glue::id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void staleParameterBindingBatchOperationsFailBeforeOpeningAnEdit(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var batch = model.parameterBindingBatch();
        final var target = dev.turboism.sdk.cubism.model.ParameterBindingTarget.artMesh(new ArtMeshId("ArtMeshFace"));

        fixture.replaceAllWithSameIds();

        assertThrows(IllegalStateException.class, () -> batch.invert(List.of(target)));
        assertEquals(0, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void writesScalarAuthoringStateWithOneUndoDirtyAndRefresh(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));

        mesh.setOpacity(0.5F);
        mesh.setVisible(false);
        mesh.setLocked(true);
        warp.setOpacity(0.6F);
        rotation.setBaseAngle(45.0F);

        assertEquals(0.5F, fixture.mesh().form.opacity);
        assertFalse(fixture.meshSource().visible);
        assertTrue(fixture.meshSource().locked);
        assertEquals(0.6F, fixture.warp().form.opacity);
        assertEquals(45.0F, fixture.rotationSource().baseAngle);
        assertEquals(5, fixture.document.editMode.edits.size());
        assertEquals(5, fixture.source.updateCount);
        assertEquals(3, fixture.document.pack.partRefreshCount);
        assertEquals(2, fixture.document.pack.deformerRefreshCount);
        assertEquals(5, fixture.document.pack.repaintCount);
        assertTrue(fixture.document.dirty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void writeCapabilityIsIndependentFromReadAndNoChangeSkipsMutation(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var readonlyModel = new EditorBackedCubismModelAccess(resolver(version, false), "session-a").active();
        final var readonlyMesh = readonlyModel.drawables().find(new ArtMeshId("ArtMeshFace"));
        assertThrows(UnsupportedOperationException.class, () -> readonlyMesh.setOpacity(0.5F));
        assertEquals(0, fixture.document.editMode.edits.size());

        final var writableModel = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = writableModel.drawables().find(new ArtMeshId("ArtMeshFace"));
        mesh.setOpacity(0.75F);
        assertEquals(0, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void replacesCompleteAuthoringSnapshotsAtomically(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));

        final ArtMeshGeometry meshGeometry = new ArtMeshGeometry(
                List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2)),
                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                List.of(0, 2, 1));
        final WarpGrid warpGrid = new WarpGrid(
                1, 1, true, List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2), new Point2(2, 2)));
        final RotationDeformerForm rotationForm = new RotationDeformerForm(25.0F, 4.0F, 5.0F, 1.5F, false, true);

        mesh.replaceGeometry(meshGeometry);
        warp.replaceGrid(warpGrid);
        rotation.replaceForm(rotationForm);

        assertEquals(
                List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2)),
                mesh.geometry().positions());
        assertEquals(List.of(0, 2, 1), mesh.geometry().triangleIndices());
        assertEquals(warpGrid, warp.grid());
        assertEquals(rotationForm, rotation.form());
        assertEquals(3, fixture.document.editMode.edits.size());
        assertEquals(3, fixture.source.updateCount);

        mesh.replaceGeometry(meshGeometry);
        warp.replaceGrid(warpGrid);
        rotation.replaceForm(rotationForm);
        assertEquals(3, fixture.document.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void rollsBackCompleteSnapshotsWhenALaterHostSetterFails(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final var warp = model.warpDeformers().find(new DeformerId("WarpFace"));
        final var rotation = model.rotationDeformers().find(new DeformerId("RotationHead"));
        final ArtMeshGeometry originalMesh = mesh.geometry();
        final WarpGrid originalWarp = warp.grid();
        final RotationDeformerForm originalRotation = rotation.form();

        fixture.failures.failOn(4);
        final ArtMeshGeometry changedMesh = new ArtMeshGeometry(
                List.of(new Point2(4, 5), new Point2(2, 0), new Point2(0, 2)),
                List.of(new Point2(0.2F, 0.3F), new Point2(0.8F, 0.3F), new Point2(0.2F, 0.9F)),
                List.of(0, 2, 1));
        assertThrows(RuntimeException.class, () -> mesh.replaceGeometry(changedMesh));
        assertEquals(originalMesh, mesh.geometry());
        assertAbortedWithoutPublishedEffects(fixture);

        fixture.resetPublishedEffects();
        fixture.failures.failOn(4);
        final WarpGrid changedWarp = new WarpGrid(
                1,
                1,
                !originalWarp.quadTransform(),
                List.of(new Point2(0, 0), new Point2(2, 0), new Point2(0, 2), new Point2(2, 2)));
        assertThrows(RuntimeException.class, () -> warp.replaceGrid(changedWarp));
        assertEquals(originalWarp, warp.grid());
        assertAbortedWithoutPublishedEffects(fixture);

        fixture.resetPublishedEffects();
        fixture.failures.failOn(4);
        assertThrows(
                RuntimeException.class,
                () -> rotation.replaceForm(new RotationDeformerForm(
                        22.0F, 8.0F, 9.0F, 1.5F, !originalRotation.reflectedX(), !originalRotation.reflectedY())));
        assertEquals(originalRotation, rotation.form());
        assertAbortedWithoutPublishedEffects(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void pointInfoVertexMoveProjectsOntoGeometryReplacement(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        final var mesh = model.drawables().find(new ArtMeshId("ArtMeshFace"));
        final ArtMeshSource source = fixture.meshSource();
        final ArtMeshForm form = fixture.mesh().form;

        // Absolute single-vertex move (PointInfo slider sets X/Y of the selected vertex).
        mesh.replaceGeometry(mesh.geometry().withVertexPosition(1, 2.0F, 3.0F));
        assertEquals(new Point2(2.0F, 3.0F), mesh.geometry().positions().get(1));
        assertArrayEquals(new float[] {0, 0, 2, 3, 0, 1}, form.positions());
        assertArrayEquals(new float[] {0, 0, 2, 3, 0, 1}, source.sourcePositions);
        assertArrayEquals(new float[] {0, 0, 1, 0, 0, 1}, source.sourceUvs);
        assertArrayEquals(new int[] {0, 1, 2}, source.sourceIndices);
        assertEquals(1, fixture.document.editMode.edits.size());
        assertTrue(fixture.document.dirty);

        // Relative move (PointInfo +/-delta buttons) — delta applied against the current position.
        final Point2 current = mesh.geometry().positions().get(1);
        mesh.replaceGeometry(mesh.geometry().withVertexPosition(1, current.x() + 0.5F, current.y() - 1.0F));
        assertEquals(new Point2(2.5F, 2.0F), mesh.geometry().positions().get(1));
        assertEquals(2, fixture.document.editMode.edits.size());

        // Multi-selection move (several selected vertices committed as one edit).
        mesh.replaceGeometry(mesh.geometry().withVertexPosition(0, 4.0F, 4.0F).withVertexPosition(2, 5.0F, 5.0F));
        assertEquals(new Point2(4.0F, 4.0F), mesh.geometry().positions().get(0));
        assertEquals(new Point2(5.0F, 5.0F), mesh.geometry().positions().get(2));
        assertEquals(3, fixture.document.editMode.edits.size());
        assertEquals(3, fixture.source.updateCount);

        // No-op when the geometry is unchanged (no edit, no instance update).
        mesh.replaceGeometry(mesh.geometry());
        assertEquals(3, fixture.document.editMode.edits.size());
        assertEquals(3, fixture.source.updateCount);
    }

    private static void assertAbortedWithoutPublishedEffects(final Fixture fixture) {
        assertEquals(0, fixture.document.editMode.edits.size());
        assertFalse(fixture.document.dirty);
        assertEquals(0, fixture.source.updateCount);
        assertEquals(0, fixture.document.pack.partRefreshCount);
        assertEquals(0, fixture.document.pack.deformerRefreshCount);
        assertEquals(0, fixture.document.pack.repaintCount);
    }

    private static VerifiedMemberResolver resolver(final String version) {
        return resolver(version, false);
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean includeWrite) {
        return resolver(version, includeWrite, false);
    }

    private static VerifiedMemberResolver resolver(
            final String version, final boolean includeWrite, final boolean includeMorph) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorClipMaskReadSelectorContract.CAPABILITY_ID);
        capabilities.add(
                dev.turboism.mapping.verification.selector.EditorParameterBindingReadSelectorContract.CAPABILITY_ID);
        if (includeWrite) {
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract
                            .ART_MESH_CAPABILITY_ID);
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID);
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract
                            .ROTATION_CAPABILITY_ID);
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorParameterBindingWriteSelectorContract
                            .ART_MESH_CAPABILITY_ID);
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorParameterBindingWriteSelectorContract
                            .WARP_CAPABILITY_ID);
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorParameterBindingWriteSelectorContract
                            .ROTATION_CAPABILITY_ID);
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorParameterBindingBatchWriteSelectorContract
                            .INVERT_CAPABILITY_ID);
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorParameterBindingBatchWriteSelectorContract
                            .TRANSFER_CAPABILITY_ID);
            capabilities.add(EditorObjectWriteSelectorContract.CLIP_MASK_CAPABILITY_ID);
        }
        if (includeMorph) {
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorMorphTargetSelectorContract.READ_CAPABILITY_ID);
        }
        return TestVerifiedResolvers.create(
                version,
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors(),
                Host.class.getClassLoader());
    }

    @Test
    void calculatedSnapshotUsesLiveInstanceAndDispatchesWithoutEditingOrRefreshing() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var all = new java.util.ArrayList<>(selectors());
        all.addAll(List.of(
                method(
                        "cubism.editor-model.art-mesh.calculated-form",
                        ArtMesh.class,
                        "calculatedForm",
                        desc(ArtMeshForm.class)),
                method(
                        "cubism.editor-model.art-mesh.interpolated-form",
                        ArtMesh.class,
                        "calculatedForm",
                        desc(ArtMeshForm.class)),
                method("cubism.editor-model.drawable-form.coord-type", ArtMeshForm.class, "coordType", desc(Id.class)),
                method("cubism.editor-model.coord-type.label", Id.class, "value", "()Ljava/lang/String;"),
                method(
                        "cubism.editor-model.model.last-updated-parameter-set",
                        Model.class,
                        "consumed",
                        desc(ParameterSet.class)),
                method("cubism.editor-model.model.last-updated-time", Model.class, "passTime", "()J"),
                method("cubism.editor-model.parameter-set.update-version", ParameterSet.class, "version", "()I"),
                method("cubism.editor-model.parameter.value", Parameter.class, "value", "()F")));
        final var resolver = TestVerifiedResolvers.create(
                "5.3.02",
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(
                        EditorObjectReadSelectorContract.CAPABILITY_ID,
                        dev.turboism.mapping.verification.selector.EditorArtMeshEvaluationReadSelectorContract
                                .CAPABILITY_ID),
                all,
                Host.class.getClassLoader());
        final boolean[] sampling = {false};
        final var access = new EditorObjectReadAccess(
                resolver,
                (identity, model) -> {
                    if (sampling[0]) assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
                },
                new EditorMorphTargetAccess(resolver, (identity, model) -> {}),
                null);
        final var mesh = access.drawables("session-a", fixture.source, fixture.source.model)
                .find(new ArtMeshId("ArtMeshFace"));
        fixture.mesh().calculated.positions = new float[] {12, 13, 14, 15, 16, 17};
        fixture.resetPublishedEffects();
        sampling[0] = true;
        final var result = mesh.evaluationSnapshot();
        assertEquals(
                12F, result.calculatedForm().orElseThrow().positions().get(0).x());
        assertEquals("Canvas", result.calculatedForm().orElseThrow().coordinateType());
        assertEquals(0F, fixture.mesh().form.positions[0], "stored current form unchanged");
        assertEquals(result, mesh.evaluationSnapshot());
        assertTrue(fixture.document.editMode.edits.isEmpty());
        assertFalse(fixture.document.dirty);
        assertEquals(0, fixture.source.updateCount);
        assertEquals(0, fixture.document.pack.repaintCount);
        fixture.source.model.artMeshes.clear();
        assertThrows(IllegalStateException.class, mesh::evaluationSnapshot);
        assertEquals(
                12F, result.calculatedForm().orElseThrow().positions().get(0).x());
    }

    @Test
    void textureIndexStaysOnTheEvaluatedJoinRatherThanReadingTheEditorTextureChain() {
        // The model-image table position is not the Core render texture index — verified
        // 5.3.02 classfiles expose a second (atlas-region) input kind, and no evidence
        // equates the authoring list order with render or export page order. The getter
        // must stay on the evaluated join and report unavailability instead of
        // silently substituting the editor binding chain.
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final TexImage image = new TexImage("image-a");
        fixture.source.textureManager.all.add(image);
        final TexExt extension = new TexExt();
        final TexInput input = new TexInput(image);
        extension.inputs.add(input);
        extension.current = input;
        fixture.meshSource().textureInputExtension = extension;
        final var resolver = textureResolver(true);
        final var access = new EditorObjectReadAccess(
                resolver,
                (identity, model) -> {},
                new EditorMorphTargetAccess(resolver, (identity, model) -> {}),
                null);
        final var mesh = access.drawables("session-a", fixture.source, fixture.source.model)
                .find(new ArtMeshId("ArtMeshFace"));
        final IllegalStateException failure = assertThrows(IllegalStateException.class, mesh::textureIndex);
        assertTrue(failure.getMessage().contains("Core evaluated data is unavailable"));
    }

    private static VerifiedMemberResolver textureResolver(final boolean textureRead) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        if (textureRead) {
            capabilities.add(
                    dev.turboism.mapping.verification.selector.EditorTextureSelectorContract.READ_CAPABILITY_ID);
        }
        final var all = new java.util.ArrayList<>(selectors());
        all.addAll(textureSelectors());
        return TestVerifiedResolvers.create(
                "5.3.02",
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                all,
                Host.class.getClassLoader());
    }

    private static List<StaticSelector> textureSelectors() {
        return List.of(
                method(
                        "cubism.editor-model.model-source.texture-manager",
                        ModelSource.class,
                        "textureManager",
                        desc(TexManager.class)),
                method(
                        "cubism.editor-model.texture-manager.all-model-images",
                        TexManager.class,
                        "allModelImages",
                        "()Ljava/util/List;"),
                method("cubism.editor-model.model-image.guid", TexImage.class, "guid", desc(Id.class)),
                method(
                        "cubism.editor-model.art-mesh-source.texture-input-extension",
                        ArtMeshSource.class,
                        "textureInputExtension",
                        "()Ljava/lang/Object;"),
                method(
                        "cubism.editor-model.texture-input-extension.current-input",
                        TexExt.class,
                        "current",
                        "()Ljava/lang/Object;"),
                StaticSelector.classSelector(
                        "cubism.editor-model.texture-input-model-image.class", internal(TexInput.class)),
                method(
                        "cubism.editor-model.texture-input-model-image.model-image",
                        TexInput.class,
                        "modelImageOrNull",
                        desc(TexImage.class)));
    }

    private static List<StaticSelector> selectors() {
        return List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)),
                StaticSelector.staticMethod(
                        "cubism.editor-model.app-controller.instance",
                        internal(Host.class),
                        "instance",
                        desc(Host.class),
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method(
                        "cubism.editor-model.app-controller.current-document",
                        Host.class,
                        "currentDocument",
                        desc(Document.class)),
                method(
                        "cubism.editor-model.app-controller.complete-pack",
                        Host.class,
                        "completePack",
                        desc(CompletePack.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)),
                method(
                        "cubism.editor-model.modeling-document.model-source",
                        Document.class,
                        "modelSource",
                        desc(ModelSource.class)),
                method(
                        "cubism.editor-model.modeling-document.edit-mode",
                        Document.class,
                        "editMode",
                        desc(EditMode.class)),
                method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"),
                method(
                        "cubism.editor-model.edit-mode.begin",
                        EditMode.class,
                        "begin",
                        "(Ljava/lang/String;)" + type(GroupUndo.class)),
                method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"),
                method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"),
                method(
                        "cubism.editor-model.undo.add-listener",
                        Undo.class,
                        "addListener",
                        "(" + type(Listener.class) + ")Z"),
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method(
                        "cubism.editor-model.model-source.current-instance",
                        ModelSource.class,
                        "currentInstance",
                        desc(Model.class)),
                method("cubism.editor-model.model-source.parts", ModelSource.class, "allParts", "()Ljava/util/List;"),
                method(
                        "cubism.editor-model.model-source.all-glues",
                        ModelSource.class,
                        "allGlues",
                        "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)),
                method(
                        "cubism.editor-model.model.parameter-set",
                        Model.class,
                        "parameterSet",
                        desc(ParameterSet.class)),
                method("cubism.editor-model.parameter.source", Parameter.class, "source", desc(ParameterSource.class)),
                method("cubism.editor-model.parameter-source.guid", ParameterSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.parameter-set.class", internal(ParameterSet.class)),
                method(
                        "cubism.editor-model.parameter-set.parameters",
                        ParameterSet.class,
                        "parameters",
                        "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(Parameter.class)),
                method("cubism.editor-model.parameter.id", Parameter.class, "id", desc(Id.class)),
                method(
                        "cubism.editor-model.model-source.all-art-meshes",
                        ModelSource.class,
                        "allArtMeshes",
                        "()Ljava/util/List;"),
                method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"),
                StaticSelector.classSelector(
                        "cubism.editor-model.art-mesh-source.class", internal(ArtMeshSource.class)),
                StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(ArtMesh.class)),
                method(
                        "cubism.editor-model.parameter-controllable-source.id",
                        ObjectSource.class,
                        "id",
                        desc(Id.class)),
                method(
                        "cubism.editor-model.parameter-controllable-source.local-name",
                        ObjectSource.class,
                        "localName",
                        "()Ljava/lang/String;"),
                method(
                        "cubism.editor-model.parameter-controllable-source.visible",
                        ObjectSource.class,
                        "visible",
                        "()Z"),
                method("cubism.editor-model.parameter-controllable-source.locked", ObjectSource.class, "locked", "()Z"),
                method(
                        "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
                        ObjectSource.class,
                        "visibleInHierarchy",
                        "()Z"),
                method(
                        "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
                        ObjectSource.class,
                        "lockedInHierarchy",
                        "()Z"),
                method("cubism.editor-model.part-source.parent", ObjectSource.class, "parent", desc(PartSource.class)),
                method(
                        "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                        ObjectSource.class,
                        "targetDeformerSource",
                        desc(ObjectSource.class)),
                method(
                        "cubism.editor-model.parameter-controllable-source.handler",
                        ObjectSource.class,
                        "handler",
                        desc(Handler.class)),
                method(
                        "cubism.editor-model.parameter-controllable-source.set-visible",
                        ObjectSource.class,
                        "setVisible",
                        "(Z)V"),
                method(
                        "cubism.editor-model.parameter-controllable-source.set-locked",
                        ObjectSource.class,
                        "setLocked",
                        "(Z)V"),
                StaticSelector.classSelector(
                        "cubism.editor-model.parameter-controllable-handler.class", internal(Handler.class)),
                method(
                        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
                        Handler.class,
                        "undo",
                        "(Ljava/lang/String;)" + type(Undo.class)),
                StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)),
                method("cubism.editor-model.part-source.id", PartSource.class, "id", desc(Id.class)),
                method("cubism.editor-model.art-mesh.source", ArtMesh.class, "source", desc(ArtMeshSource.class)),
                method("cubism.editor-model.art-mesh-source.guid", ArtMeshSource.class, "guid", desc(Id.class)),
                method(
                        "cubism.editor-model.art-mesh-source.clip-guid-list",
                        ArtMeshSource.class,
                        "clipGuids",
                        desc(ClipGuidList.class)),
                method(
                        "cubism.editor-model.art-mesh-source.set-clip-guid-list",
                        ArtMeshSource.class,
                        "setClipGuidList",
                        "(" + type(ClipGuidList.class) + ")V"),
                StaticSelector.classSelector("cubism.editor-model.c-array-list.class", internal(ClipGuidList.class)),
                StaticSelector.constructor(
                        "cubism.editor-model.c-array-list.create",
                        internal(ClipGuidList.class),
                        "(" + type(java.util.Collection.class) + ")V",
                        StaticSelector.ACCESS_PUBLIC),
                method(
                        "cubism.editor-model.art-mesh.current-keyform",
                        ArtMesh.class,
                        "currentForm",
                        desc(ArtMeshForm.class)),
                method("cubism.editor-model.drawable-form.opacity", Form.class, "opacity", "()F"),
                method("cubism.editor-model.drawable-form.set-opacity", Form.class, "setOpacity", "(F)V"),
                method("cubism.editor-model.drawable-form.draw-order", Form.class, "drawOrder", "()I"),
                method("cubism.editor-model.art-mesh-form.positions", ArtMeshForm.class, "positions", "()[F"),
                method("cubism.editor-model.art-mesh-form.set-positions", ArtMeshForm.class, "setPositions", "([F)V"),
                method("cubism.editor-model.art-mesh-source.positions", ArtMeshSource.class, "positions", "()[F"),
                method(
                        "cubism.editor-model.art-mesh-source.set-positions",
                        ArtMeshSource.class,
                        "setPositions",
                        "([F)V"),
                method("cubism.editor-model.art-mesh-source.uvs", ArtMeshSource.class, "uvs", "()[F"),
                method("cubism.editor-model.art-mesh-source.set-uvs", ArtMeshSource.class, "setUvs", "([F)V"),
                method("cubism.editor-model.art-mesh-source.indices", ArtMeshSource.class, "indices", "()[I"),
                method("cubism.editor-model.art-mesh-source.set-indices", ArtMeshSource.class, "setIndices", "([I)V"),
                method("cubism.editor-model.art-mesh-source.culling", ArtMeshSource.class, "culling", "()Z"),
                method(
                        "cubism.editor-model.art-mesh-source.user-data",
                        ArtMeshSource.class,
                        "userData",
                        "()Ljava/lang/String;"),
                method("cubism.editor-model.art-mesh-source.inverted-mask", ArtMeshSource.class, "invertedMask", "()Z"),
                method(
                        "cubism.editor-model.art-mesh-source.set-inverted-mask",
                        ArtMeshSource.class,
                        "setInvertClippingMask",
                        "(Z)V"),
                method(
                        "cubism.editor-model.model-source.all-deformers",
                        ModelSource.class,
                        "allDeformers",
                        "()Ljava/util/List;"),
                method("cubism.editor-model.model.all-deformers", Model.class, "allDeformers", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.glue-source.class", internal(GlueSource.class)),
                method(
                        "cubism.editor-model.glue-source.target-art-mesh-a",
                        GlueSource.class,
                        "targetA",
                        desc(ArtMeshSource.class)),
                method(
                        "cubism.editor-model.glue-source.target-art-mesh-b",
                        GlueSource.class,
                        "targetB",
                        desc(ArtMeshSource.class)),
                StaticSelector.classSelector("cubism.editor-model.warp-source.class", internal(WarpSource.class)),
                StaticSelector.classSelector("cubism.editor-model.warp.class", internal(Warp.class)),
                StaticSelector.classSelector(
                        "cubism.editor-model.rotation-source.class", internal(RotationSource.class)),
                StaticSelector.classSelector("cubism.editor-model.rotation.class", internal(Rotation.class)),
                method("cubism.editor-model.deformer.source", Deformer.class, "source", desc(ObjectSource.class)),
                method("cubism.editor-model.deformer.current-keyform", Deformer.class, "currentForm", desc(Form.class)),
                method("cubism.editor-model.deformer-form.opacity", Form.class, "opacity", "()F"),
                method("cubism.editor-model.deformer-form.set-opacity", Form.class, "setOpacity", "(F)V"),
                method("cubism.editor-model.warp-source.row", WarpSource.class, "row", "()I"),
                method("cubism.editor-model.warp-source.set-row", WarpSource.class, "setRow", "(I)V"),
                method("cubism.editor-model.warp-source.col", WarpSource.class, "col", "()I"),
                method("cubism.editor-model.warp-source.set-col", WarpSource.class, "setCol", "(I)V"),
                method("cubism.editor-model.warp-source.quad-transform", WarpSource.class, "quadTransform", "()Z"),
                method(
                        "cubism.editor-model.warp-source.set-quad-transform",
                        WarpSource.class,
                        "setQuadTransform",
                        "(Z)V"),
                method("cubism.editor-model.warp-form.positions", WarpForm.class, "positions", "()[F"),
                method("cubism.editor-model.warp-form.set-positions", WarpForm.class, "setPositions", "([F)V"),
                method("cubism.editor-model.rotation-source.base-angle", RotationSource.class, "baseAngle", "()F"),
                method(
                        "cubism.editor-model.rotation-source.set-base-angle",
                        RotationSource.class,
                        "setBaseAngle",
                        "(F)V"),
                method("cubism.editor-model.rotation-form.angle", RotationForm.class, "angle", "()F"),
                method("cubism.editor-model.rotation-form.set-angle", RotationForm.class, "setAngle", "(F)V"),
                method("cubism.editor-model.rotation-form.origin-x", RotationForm.class, "originX", "()F"),
                method("cubism.editor-model.rotation-form.set-origin-x", RotationForm.class, "setOriginX", "(F)V"),
                method("cubism.editor-model.rotation-form.origin-y", RotationForm.class, "originY", "()F"),
                method("cubism.editor-model.rotation-form.set-origin-y", RotationForm.class, "setOriginY", "(F)V"),
                method("cubism.editor-model.rotation-form.scale", RotationForm.class, "scale", "()F"),
                method("cubism.editor-model.rotation-form.set-scale", RotationForm.class, "setScale", "(F)V"),
                method("cubism.editor-model.rotation-form.reflect-x", RotationForm.class, "reflectX", "()Z"),
                method("cubism.editor-model.rotation-form.set-reflect-x", RotationForm.class, "setReflectX", "(Z)V"),
                method("cubism.editor-model.rotation-form.reflect-y", RotationForm.class, "reflectY", "()Z"),
                method("cubism.editor-model.rotation-form.set-reflect-y", RotationForm.class, "setReflectY", "(Z)V"),
                StaticSelector.classSelector("cubism.editor-model.keyform-grid.class", internal(KeyformGrid.class)),
                method(
                        "cubism.editor-model.parameter-controllable.keyform-grid",
                        ObjectSource.class,
                        "keyformGrid",
                        desc(KeyformGrid.class)),
                method(
                        "cubism.editor-model.keyform-grid.bindings",
                        KeyformGrid.class,
                        "bindings",
                        "()Ljava/util/List;"),
                method(
                        "cubism.editor-model.keyform-grid.add-key",
                        KeyformGrid.class,
                        "addKey",
                        "(F" + type(Id.class) + ")V"),
                method(
                        "cubism.editor-model.keyform-grid.remove-key",
                        KeyformGrid.class,
                        "removeKey",
                        "(F" + type(Id.class) + ")V"),
                method(
                        "cubism.editor-model.keyform-grid.remove-all-key",
                        KeyformGrid.class,
                        "removeAllKey",
                        "(" + type(ParameterSet.class) + type(Id.class) + ")V"),
                method(
                        "cubism.editor-model.keyform-grid.rearrange-keys",
                        KeyformGrid.class,
                        "rearrange",
                        "(" + type(Id.class) + "Ljava/util/List;Ljava/util/List;)V"),
                method(
                        "cubism.editor-model.keyform-grid.find-binding",
                        KeyformGrid.class,
                        "findBinding",
                        "(" + type(Id.class) + ")" + type(KeyformBinding.class)),
                method(
                        "cubism.editor-model.keyform-grid.reverse-parameter",
                        KeyformGrid.class,
                        "reverse",
                        "(" + type(Id.class) + ")V"),
                method(
                        "cubism.editor-model.keyform-grid.change-parameter",
                        KeyformGrid.class,
                        "change",
                        "(" + type(Id.class) + type(Id.class) + ")V"),
                StaticSelector.classSelector(
                        "cubism.editor-model.keyform-binding.class", internal(KeyformBinding.class)),
                method(
                        "cubism.editor-model.keyform-binding.parameter-id",
                        KeyformBinding.class,
                        "parameterId",
                        desc(Id.class)),
                method(
                        "cubism.editor-model.keyform-binding.parameter-guid",
                        KeyformBinding.class,
                        "parameterGuid",
                        desc(Id.class)),
                method("cubism.editor-model.id.guid", Id.class, "guid", desc(Id.class)),
                method("cubism.editor-model.keyform-binding.keys", KeyformBinding.class, "keys", "()Ljava/util/List;"),
                method(
                        "cubism.editor-model.complete-pack.update-part-palette",
                        CompletePack.class,
                        "updateParts",
                        "(Z)V"),
                method(
                        "cubism.editor-model.complete-pack.update-deformer-palette",
                        CompletePack.class,
                        "updateDeformers",
                        "(Z)V"),
                method(
                        "cubism.editor-model.complete-pack.update-parameter",
                        CompletePack.class,
                        "updateParameter",
                        "(Z)V"),
                method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"),
                method(
                        "cubism.editor-model.model-source.update-instances",
                        ModelSource.class,
                        "updateInstances",
                        "()V"),
                method("cubism.editor-model.parameter-source.combined", ParameterSource.class, "combined", "()Z"),
                method("cubism.editor-model.parameter-source.minimum", ParameterSource.class, "minimum", "()F"),
                method("cubism.editor-model.parameter-source.maximum", ParameterSource.class, "maximum", "()F"),
                method(
                        "cubism.editor-model.parameter-controllable.morph-target-set",
                        ObjectSource.class,
                        "morphTargetSet",
                        desc(MorphTargetSet.class)),
                StaticSelector.classSelector(
                        "cubism.editor-model.morph-target-set.class", internal(MorphTargetSet.class)),
                method(
                        "cubism.editor-model.morph-target-set.morph-targets",
                        MorphTargetSet.class,
                        "morphTargets",
                        "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.morph-target.class", internal(HostMorphTarget.class)),
                method(
                        "cubism.editor-model.morph-target.parameter-guid",
                        HostMorphTarget.class,
                        "parameterGuid",
                        desc(Id.class)),
                method(
                        "cubism.editor-model.morph-target.key-value",
                        HostMorphTarget.class,
                        "keyValue",
                        "()Ljava/lang/Float;"),
                method(
                        "cubism.editor-model.morph-target.keyform-guid",
                        HostMorphTarget.class,
                        "keyformGuid",
                        desc(Id.class)),
                method(
                        "cubism.editor-model.model-source.parameter-source-set",
                        ModelSource.class,
                        "parameterSourceSet",
                        desc(ParameterSourceSet.class)),
                StaticSelector.classSelector(
                        "cubism.editor-model.parameter-source-set.class", internal(ParameterSourceSet.class)),
                method(
                        "cubism.editor-model.parameter-source-set.get",
                        ParameterSourceSet.class,
                        "get",
                        "(" + type(Id.class) + ")" + type(ParameterSource.class)),
                method(
                        "cubism.editor-model.parameter-source-set.get-by-id",
                        ParameterSourceSet.class,
                        "getById",
                        "(" + type(Id.class) + ")" + type(ParameterSource.class)),
                method("cubism.editor-model.parameter-source.id", ParameterSource.class, "id", desc(Id.class)),
                StaticSelector.constructor(
                        "cubism.editor-model.parameter-id.create",
                        internal(Id.class),
                        "(Ljava/lang/String;)V",
                        StaticSelector.ACCESS_PUBLIC),
                method("cubism.editor-model.form-guid.value", Id.class, "value", "()Ljava/lang/String;"));
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static String desc(final Class<?> type) {
        return "()" + type(type);
    }
}
