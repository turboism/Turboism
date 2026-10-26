package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorParameterStructureSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditorParameterStructureAccessTest {

    @Test
    void parameterGuidReadsNativeSourceAcrossEvaluationRebuildAndRejectsSameIdReplacement() {
        final Fixture f = new Fixture();
        Host.document = f.document;
        final var model = new EditorBackedCubismModelAccess(resolver(true), "session-a").active();
        final var retained = model.parameters().find(new ParameterId("P1"));
        assertEquals("guid-P1", retained.guid());
        f.source.model.parameterSet.parameters.set(0, new RuntimeParameter(f.p1));
        assertEquals("guid-P1", retained.guid());
        final var replacement =
                new ParameterSource(new Id("P1"), "P1", 0, 1, 0, "", new Id("replacement-native-guid"), Id.NORMAL);
        f.root.children.set(0, replacement);
        f.source.sources.set(0, replacement);
        f.source.sourceSet.all.set(0, replacement);
        f.source.model.parameterSet.parameters.set(0, new RuntimeParameter(replacement));
        assertThrows(IllegalStateException.class, retained::guid);
        assertEquals(
                "replacement-native-guid",
                model.parameters().find(new ParameterId("P1")).guid());
        assertEquals(0, f.editMode.edits.size());
        assertEquals(0, f.source.updateCount);
        Host.document = null;
        assertThrows(IllegalStateException.class, retained::guid);
    }

    @Test
    void missingParameterGuidSelectorFailsInsteadOfUsingDisplayId() {
        final Fixture f = new Fixture();
        Host.document = f.document;
        final var parameter = new EditorBackedCubismModelAccess(
                        resolver(true, "cubism.editor-model.parameter-source.guid"), "session-a")
                .active()
                .parameters()
                .find(new ParameterId("P1"));
        assertThrows(RuntimeException.class, parameter::guid);
        assertEquals(0, f.editMode.edits.size());
    }

    @Test
    void blankNativeParameterGuidIsNotAValidIdentity() {
        final Fixture f = new Fixture();
        Host.document = f.document;
        final var invalid = new ParameterSource(new Id("P1"), "P1", 0, 1, 0, "", new Id(" "), Id.NORMAL);
        f.source.model.parameterSet.parameters.set(0, new RuntimeParameter(invalid));
        final var parameter = new EditorBackedCubismModelAccess(resolver(true), "session-a")
                .active()
                .parameters()
                .find(new ParameterId("P1"));
        assertThrows(IllegalStateException.class, parameter::guid);
        assertEquals(0, f.editMode.edits.size());
    }

    @Test
    void createsCopiesMovesAndDeletesParametersThroughNativeUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var model = access.active();

        final var created = model.parameters()
                .create(new ParameterDefinition(
                        new ParameterId("NewParam"), "NewParam", 0.0F, 0.5F, 1.0F, ParameterType.NORMAL, true));
        assertEquals(new ParameterId("NewParam"), created.id());
        assertEquals(2, fixture.root.children.size());
        assertEquals("NewParam", ((ParameterSource) fixture.root.children.get(1)).id.value);
        assertEquals(1, fixture.editMode.edits.size());
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount);
        assertTrue(ModelHandler.lastAdded.repeat);

        final var copied = model.parameters().copy(new ParameterId("NewParam"));
        assertEquals("NewParam_1", copied.id().value());
        assertEquals(3, fixture.root.children.size());

        final var folder = model.parameterGroups().addGroup("Folder A");
        assertEquals(4, fixture.root.children.size());
        model.parameterGroups().moveParameter(new ParameterId("NewParam"), folder.id());
        assertEquals(1, ModelHandler.lastGroup.children.size());
        assertEquals("NewParam", ((ParameterSource) ModelHandler.lastGroup.children.get(0)).id.value);
        assertEquals(4, fixture.editMode.edits.size());

        folder.rename("Folder B");
        assertEquals("Folder B", ModelHandler.lastGroup.name);

        model.parameterGroups().removeGroup(folder.id());
        assertEquals(2, fixture.root.children.size());
        model.parameters().remove(new ParameterId("NewParam"));
        assertEquals(2, fixture.root.children.size());
        assertEquals(7, fixture.editMode.edits.size());
    }

    @Test
    void indexedMoveUsesMixedPostRemovalOrderAndNativeUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(true), "session-a").active();
        final var groups = model.parameterGroups();
        final var folder = groups.addGroup("Folder");
        final var root = groups.root();
        final var first = dev.turboism.sdk.cubism.model.ParameterGroupChild.parameter(new ParameterId("P1"));
        final var second = dev.turboism.sdk.cubism.model.ParameterGroupChild.group(folder.id());
        final var retained = root.orderedChildren();
        assertEquals(List.of(first, second), retained);
        assertThrows(UnsupportedOperationException.class, () -> retained.clear());
        int edits = fixture.editMode.edits.size();
        groups.moveParameter(new ParameterId("P1"), root.id(), 0);
        assertEquals(edits, fixture.editMode.edits.size(), "same slot must not create Undo");
        assertThrows(IllegalArgumentException.class, () -> groups.moveParameter(new ParameterId("P1"), root.id(), 2));
        assertThrows(IllegalArgumentException.class, () -> groups.moveParameter(new ParameterId("P1"), root.id(), -1));
        assertEquals(edits, fixture.editMode.edits.size());

        groups.moveParameter(new ParameterId("P1"), root.id(), 1);
        assertEquals(List.of(second, first), root.orderedChildren());
        assertEquals(List.of(first, second), retained, "read result is immutable, not a live view");
        final Undo move = fixture.editMode.edits.get(edits);
        move.undo();
        assertEquals(List.of(first, second), root.orderedChildren());
        move.redo();
        assertEquals(List.of(second, first), root.orderedChildren());
        groups.moveParameter(new ParameterId("P1"), folder.id(), 0);
        assertEquals(List.of(second), root.orderedChildren());
        assertEquals(List.of(first), folder.orderedChildren());
        groups.moveParameter(new ParameterId("P1"), root.id(), 0);
        assertEquals(List.of(first, second), root.orderedChildren());
        groups.moveParameter(new ParameterId("P1"), root.id());
        assertEquals(List.of(second, first), root.orderedChildren(), "legacy append also uses post-removal index");
    }

    @Test
    void mixedReadRejectsUnknownEntryWithoutSilentlyDroppingIt() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var root = new EditorBackedCubismModelAccess(resolver(true), "session-a")
                .active()
                .parameterGroups()
                .root();
        fixture.root.children.add(new Object());
        assertThrows(IllegalStateException.class, root::orderedChildren);
        assertTrue(fixture.editMode.edits.isEmpty());
    }

    @Test
    void copySurvivesHostUndoRedoSequenceWithStableId() {
        // Reproduces the matrix sequence for parameters.copy: the host registers an
        // Undo_AddOrRemove_Parameter_ undo whose constructor applies the add (construct-and-redo),
        // undo() removes the parameter, redo() re-applies it. The copy id returned to the caller
        // must stay queryable across the whole sequence (findById empty, never throwing).
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var model = access.active();

        // before: source present, copy absent
        assertTrue(model.parameters().findById(new ParameterId("P1")).isPresent());
        assertTrue(model.parameters().findById(new ParameterId("P1_1")).isEmpty());

        // write: copy returns the id registered in host state (children tail read-back)
        final var copy = model.parameters().copy(new ParameterId("P1"));
        final ParameterId copyId = copy.id();
        assertEquals("P1_1", copyId.value());

        // after: copy present
        assertTrue(model.parameters().findById(copyId).isPresent());
        assertEquals(2, fixture.root.children.size());

        // undo: host removes the parameter; the live copy.id() must not throw and findById is empty
        fixture.editMode.undo();
        assertTrue(model.parameters().findById(copy.id()).isEmpty());
        assertTrue(model.parameters().findById(copyId).isEmpty());

        // redo: host re-applies the parameter; id stays stable
        fixture.editMode.redo();
        assertTrue(model.parameters().findById(copy.id()).isPresent());
        assertEquals(copyId, copy.id());

        // undo again: absent again, still no throw
        fixture.editMode.undo();
        assertTrue(model.parameters().findById(copy.id()).isEmpty());

        // restored: source untouched, model back to the before state
        assertTrue(model.parameters().findById(new ParameterId("P1")).isPresent());
        assertEquals(1, fixture.root.children.size());
    }

    @Test
    void addGroupConstructsWithANonNullParameterGroupGuid() {
        // CParameterGroup(String, CParameterGroupGuid, CParameterGroupId) rejects a null guid
        // (Intrinsics.checkNotNullParameter); the adapter must construct a fresh guid first.
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");

        final var folder = access.active().parameterGroups().addGroup("Folder A");

        assertEquals("Folder A_1", folder.id().value());
        assertNotNull(ParameterGroup.lastGuid);
        assertTrue(ParameterGroup.lastGuid instanceof CParameterGroupGuid);
        assertEquals(2, fixture.root.children.size());
    }

    @Test
    void missingCapabilityFailsClosedBeforeAnyWrite() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        final var model = access.active();
        assertThrows(
                UnsupportedOperationException.class,
                () -> model.parameters()
                        .create(new ParameterDefinition(
                                new ParameterId("X"), "X", 0, 0, 0, ParameterType.NORMAL, false)));
        assertThrows(
                UnsupportedOperationException.class, () -> model.parameters().remove(new ParameterId("P1")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> model.parameterGroups().addGroup("F"));
        assertEquals(0, fixture.editMode.edits.size());
        assertTrue(!fixture.document.dirty);
    }

    @Test
    void absentTargetsFailsClosed() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var model = access.active();
        assertThrows(
                java.util.NoSuchElementException.class, () -> model.parameters().copy(new ParameterId("Missing")));
        assertThrows(
                java.util.NoSuchElementException.class, () -> model.parameters().remove(new ParameterId("Missing")));
        assertThrows(
                java.util.NoSuchElementException.class,
                () -> model.parameterGroups().moveParameter(new ParameterId("P1"), new ParameterGroupId("Missing")));
        assertThrows(
                java.util.NoSuchElementException.class,
                () -> model.parameters()
                        .create(
                                new ParameterDefinition(
                                        new ParameterId("X"), "X", 0, 0, 0, ParameterType.NORMAL, false),
                                java.util.Optional.of(new ParameterGroupId("Missing"))));
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void createManyCreatesAllParametersInsideOneUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var model = access.active();

        final var created = model.parameters()
                .createMany(List.of(
                        new ParameterDefinition(
                                new ParameterId("A"), "A", 0.0F, 0.5F, 1.0F, ParameterType.NORMAL, true),
                        new ParameterDefinition(
                                new ParameterId("B"), "B", -1.0F, 0.0F, 1.0F, ParameterType.NORMAL, false),
                        new ParameterDefinition(
                                new ParameterId("C"), "C", 0.0F, 0.0F, 1.0F, ParameterType.BLEND_SHAPE, false)));

        assertEquals(
                List.of(new ParameterId("A"), new ParameterId("B"), new ParameterId("C")),
                created.stream().map(p -> p.id()).toList());
        assertEquals(4, fixture.root.children.size());
        assertTrue(((ParameterSource) fixture.root.children.get(1)).repeat);
        assertTrue(!((ParameterSource) fixture.root.children.get(2)).repeat);
        // one edit-mode envelope for the whole batch, never one per parameter
        assertEquals(1, fixture.editMode.beginCount);
        assertEquals(1, fixture.editMode.endCount);
        assertTrue(!fixture.editMode.aborted);
        // every child operation registered inside that single envelope
        assertEquals(3, fixture.editMode.edits.size());
        assertEquals(1, fixture.editMode.lastUndo.listenerCount());
        assertTrue(fixture.document.dirty);
        assertEquals(1, fixture.source.updateCount);

        fixture.editMode.undo();
        assertEquals(1, fixture.root.children.size());
        assertEquals(2, fixture.source.updateCount);
        assertEquals(2, fixture.pack.updateParameterCount);
        assertEquals(2, fixture.pack.repaintCount);

        fixture.editMode.redo();
        assertEquals(4, fixture.root.children.size());
        assertEquals(3, fixture.source.updateCount);
        assertEquals(3, fixture.pack.updateParameterCount);
        assertEquals(3, fixture.pack.repaintCount);
    }

    @Test
    void createManyIndexesExistingParametersOnlyOnce() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");

        access.active()
                .parameters()
                .createMany(List.of(
                        new ParameterDefinition(new ParameterId("A"), "A", 0, 0, 1, ParameterType.NORMAL, false),
                        new ParameterDefinition(new ParameterId("B"), "B", 0, 0, 1, ParameterType.NORMAL, false),
                        new ParameterDefinition(new ParameterId("C"), "C", 0, 0, 1, ParameterType.NORMAL, false)));

        assertEquals(1, fixture.source.model.parameterSet.parameterCollectionReads);
    }

    @Test
    void removeManyDeletesAllParametersInsideOneUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var model = access.active();

        model.parameters()
                .createMany(List.of(
                        new ParameterDefinition(new ParameterId("A"), "A", 0, 0, 1, ParameterType.NORMAL, false),
                        new ParameterDefinition(new ParameterId("B"), "B", 0, 0, 1, ParameterType.NORMAL, false)));
        assertEquals(3, fixture.root.children.size());

        model.parameters().removeMany(List.of(new ParameterId("A"), new ParameterId("B")));
        assertEquals(1, fixture.root.children.size());
        assertEquals(2, fixture.editMode.beginCount);
        assertEquals(2, fixture.editMode.endCount);
        assertTrue(!fixture.editMode.aborted);
    }

    @Test
    void batchValidationFailsClosedBeforeAnyWrite() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var model = access.active();

        // duplicate id inside the batch
        assertThrows(
                IllegalArgumentException.class,
                () -> model.parameters()
                        .createMany(List.of(
                                new ParameterDefinition(
                                        new ParameterId("A"), "A", 0, 0, 1, ParameterType.NORMAL, false),
                                new ParameterDefinition(
                                        new ParameterId("A"), "A2", 0, 0, 1, ParameterType.NORMAL, false))));
        // id already present in the model
        assertThrows(
                IllegalArgumentException.class,
                () -> model.parameters()
                        .createMany(List.of(new ParameterDefinition(
                                new ParameterId("P1"), "P1", 0, 0, 1, ParameterType.NORMAL, false))));
        // empty batch
        assertThrows(IllegalArgumentException.class, () -> model.parameters().createMany(List.of()));
        assertThrows(IllegalArgumentException.class, () -> model.parameters().removeMany(List.of()));
        // absent target
        assertThrows(
                java.util.NoSuchElementException.class,
                () -> model.parameters().removeMany(List.of(new ParameterId("Missing"))));
        assertEquals(0, fixture.editMode.beginCount);
        assertEquals(0, fixture.editMode.endCount);
        assertEquals(1, fixture.root.children.size());
    }

    @Test
    void batchWritesFailClosedWithoutExactCapabilityEvidence() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(resolver(false), "session-a");
        final var model = access.active();

        assertThrows(
                UnsupportedOperationException.class,
                () -> model.parameters()
                        .createMany(List.of(new ParameterDefinition(
                                new ParameterId("A"), "A", 0, 0, 1, ParameterType.NORMAL, false))));
        assertThrows(
                UnsupportedOperationException.class,
                () -> model.parameters().removeMany(List.of(new ParameterId("P1"))));
        assertEquals(0, fixture.editMode.beginCount);
        assertEquals(0, fixture.editMode.endCount);
    }

    @Test
    void indexedMovesJoinOneAuthoringRootAndRollbackRestoresOrder() {
        EditorHostThread.dispatch("parameter reorder transaction test", () -> {
            for (boolean fail : List.of(false, true)) {
                final var scoped = new ParameterScopedFixture("", true);
                // Create a second parameter before the transaction; keep native edits out of its root.
                scoped.access.create(
                        "model",
                        scoped.fixture.source,
                        scoped.fixture.source.model,
                        definition("P2"),
                        Optional.empty());
                scoped.fixture.editMode.edits.clear();
                scoped.fixture.editMode.beginCount = 0;
                final var rootId = new ParameterGroupId(scoped.fixture.root.id.value);
                final var result = scoped.coordinator.execute(
                        scoped.binding, AuthoringTransactionOptions.of("reorder parameters"), () -> {
                            scoped.access.moveParameter(
                                    "model",
                                    scoped.fixture.source,
                                    scoped.fixture.source.model,
                                    new ParameterId("P1"),
                                    rootId,
                                    1);
                            assertEquals("P2", ((ParameterSource) scoped.fixture.root.children.get(0)).id.value);
                            if (fail) throw new IllegalStateException("injected after native move");
                            return null;
                        });
                assertEquals(
                        fail ? AuthoringTransactionOutcome.ROLLED_BACK : AuthoringTransactionOutcome.COMMITTED,
                        result.outcome(),
                        () -> String.valueOf(result.diagnosticId()));
                assertEquals(1, scoped.fixture.editMode.beginCount, "move must not open a nested edit");
                if (!fail) {
                    scoped.roots.get(0).undo();
                    assertEquals("P1", ((ParameterSource) scoped.fixture.root.children.get(0)).id.value);
                    scoped.roots.get(0).redo();
                    assertEquals("P2", ((ParameterSource) scoped.fixture.root.children.get(0)).id.value);
                } else {
                    assertEquals("P1", ((ParameterSource) scoped.fixture.root.children.get(0)).id.value);
                }
                assertEquals(2, scoped.fixture.root.children.size());
            }
            return null;
        });
    }

    @Test
    void singleAndBatchParameterCreatesShareOneRootAndUndoRedo() {
        EditorHostThread.dispatch("parameter transaction test", () -> {
            final var scoped = new ParameterScopedFixture();
            final var result = scoped.coordinator.execute(
                    scoped.binding, AuthoringTransactionOptions.of("import rotation axes"), () -> {
                        scoped.create("X");
                        scoped.access.createMany(
                                "model",
                                scoped.fixture.source,
                                scoped.fixture.source.model,
                                List.of(definition("Y"), definition("Z")),
                                Optional.empty());
                        return null;
                    });
            assertEquals(
                    AuthoringTransactionOutcome.COMMITTED,
                    result.outcome(),
                    () -> String.valueOf(result.diagnosticId()));
            assertEquals(1, scoped.fixture.editMode.beginCount);
            assertEquals(1, scoped.roots.size());
            assertTrue(scoped.lastRefreshRequirements.contains(EditorRefreshRequirement.PARAMETER_STRUCTURE));
            assertEquals(List.of("P1", "X", "Y", "Z"), scoped.ids());
            scoped.roots.get(0).undo();
            assertEquals(List.of("P1"), scoped.ids());
            assertEquals(List.of(scoped.fixture.p1), scoped.fixture.root.children);
            scoped.roots.get(0).redo();
            assertEquals(List.of("P1", "X", "Y", "Z"), scoped.ids());
            return null;
        });
    }

    @Test
    void parameterCreateFailuresRestoreEverySourceAndFolderWithoutNestedEdits() {
        EditorHostThread.dispatch("parameter recovery test", () -> {
            for (boolean nativeAbort : List.of(false, true))
                for (int rejectAt : List.of(0, 1, 2)) {
                    final var scoped = new ParameterScopedFixture();
                    scoped.nativeAbort = nativeAbort;
                    scoped.fixture.editMode.rejectAt = rejectAt;
                    final var result = scoped.coordinator.execute(
                            scoped.binding, AuthoringTransactionOptions.of("failing rotation axes"), () -> {
                                scoped.access.createMany(
                                        "model",
                                        scoped.fixture.source,
                                        scoped.fixture.source.model,
                                        List.of(definition("X"), definition("Y")),
                                        Optional.empty());
                                throw new IllegalStateException("failure after axes");
                            });
                    final String tag = "nativeAbort=" + nativeAbort + " rejectAt=" + rejectAt;
                    assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome(), tag);
                    assertEquals(List.of("P1"), scoped.ids(), tag);
                    assertEquals(List.of(scoped.fixture.p1), scoped.fixture.source.sources, tag);
                    assertEquals(List.of(scoped.fixture.p1), scoped.fixture.root.children, tag);
                    assertEquals(List.of(scoped.fixture.p1), scoped.fixture.source.sourceSet.all, tag);
                    assertEquals(0, scoped.roots.size(), tag);
                    assertEquals(1, scoped.fixture.editMode.beginCount, tag);
                }
            return null;
        });
    }

    @Test
    void duplicateParameterBatchFailsBeforeAnyNativeEdit() {
        EditorHostThread.dispatch("parameter duplicate test", () -> {
            final var scoped = new ParameterScopedFixture();
            final var result =
                    scoped.coordinator.execute(scoped.binding, AuthoringTransactionOptions.of("duplicate axes"), () -> {
                        scoped.access.createMany(
                                "model",
                                scoped.fixture.source,
                                scoped.fixture.source.model,
                                List.of(definition("X"), definition("X")),
                                Optional.empty());
                        return null;
                    });
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(0, scoped.fixture.editMode.beginCount);
            assertEquals(List.of("P1"), scoped.ids());
            return null;
        });
    }

    @Test
    void missingRecoveryAliasRejectsParameterCreateBeforeNativeMutation() {
        EditorHostThread.dispatch("parameter preflight test", () -> {
            final var scoped = new ParameterScopedFixture("cubism.editor-model.undo.execute-undo");
            final var result = scoped.coordinator.execute(
                    scoped.binding, AuthoringTransactionOptions.of("missing recovery"), () -> {
                        scoped.create("X");
                        return null;
                    });
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(0, scoped.fixture.editMode.beginCount);
            assertEquals(List.of("P1"), scoped.ids());
            assertEquals(List.of(scoped.fixture.p1), scoped.fixture.source.sourceSet.all);
            return null;
        });
    }

    @Test
    void brokenNativeUndoCannotReportCleanParameterRollback() {
        EditorHostThread.dispatch("parameter residual test", () -> {
            final var scoped = new ParameterScopedFixture();
            scoped.fixture.editMode.leaveSourceResidue = true;
            final var result = scoped.coordinator.execute(
                    scoped.binding, AuthoringTransactionOptions.of("broken parameter undo"), () -> {
                        scoped.create("X");
                        throw new IllegalStateException("import failure");
                    });
            assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
            assertEquals(2, scoped.fixture.source.sourceSet.all.size());
            assertEquals(List.of(scoped.fixture.p1), scoped.fixture.root.children);
            return null;
        });
    }

    @Test
    void rejectedParameterListenerRestoresEagerAdd() {
        EditorHostThread.dispatch("parameter listener test", () -> {
            final var scoped = new ParameterScopedFixture();
            scoped.fixture.editMode.rejectListener = true;
            scoped.nativeAbort = false;
            final var result = scoped.coordinator.execute(
                    scoped.binding, AuthoringTransactionOptions.of("rejected listener"), () -> {
                        scoped.create("X");
                        return null;
                    });
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(List.of("P1"), scoped.ids());
            assertEquals(List.of(scoped.fixture.p1), scoped.fixture.root.children);
            assertEquals(List.of(scoped.fixture.p1), scoped.fixture.source.sourceSet.all);
            assertEquals(0, scoped.roots.size());
            return null;
        });
    }

    private static ParameterDefinition definition(final String id) {
        return new ParameterDefinition(new ParameterId(id), id, -30F, 0F, 30F, ParameterType.NORMAL, false);
    }

    private static final class ParameterScopedFixture implements EditorAuthoringTransactionCoordinator.Host {
        @Override
        public void undoEditGroup(
                final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding
                        expected,
                final Object edit) {
            if (envelopeJoin) ((GroupUndo) edit).undo();
        }

        final Fixture fixture = new Fixture();
        final List<GroupUndo> roots = new ArrayList<>();
        final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(this);
        final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
                "parameter-test", "document", 1, "model", 1, Thread.currentThread());
        final EditorParameterStructureAccess access;
        boolean nativeAbort = true;
        boolean envelopeJoin;
        Set<EditorRefreshRequirement> lastRefreshRequirements = Set.of();

        ParameterScopedFixture() {
            this("");
        }

        ParameterScopedFixture(final String excludedAlias) {
            this(excludedAlias, false);
        }

        ParameterScopedFixture(final String excludedAlias, final boolean envelopeJoin) {
            this.envelopeJoin = envelopeJoin;
            Host.document = fixture.document;
            access = new EditorParameterStructureAccess(
                    resolver(true, excludedAlias, envelopeJoin), (identity, model) -> {}, coordinator, () -> binding);
        }

        void create(final String id) {
            access.create("model", fixture.source, fixture.source.model, definition(id), Optional.empty());
        }

        List<String> ids() {
            return fixture.source.model.parameterSet.parameters.stream()
                    .map(p -> p.source.id.value)
                    .toList();
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding current) {
            return binding.equals(current) && current.hostThread() == Thread.currentThread();
        }

        @Override
        public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding current) {
            final var entries = new ArrayList<HistoryEntry>();
            for (int i = 0; i < roots.size(); i++) entries.add(new HistoryEntry(i, "edit-" + i, true));
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    1,
                    roots.size(),
                    roots.size(),
                    entries,
                    !roots.isEmpty(),
                    false,
                    "document",
                    "history");
        }

        @Override
        public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding current, final String label) {
            final var edit = fixture.editMode.begin(label);
            roots.add(edit);
            return edit;
        }

        @Override
        public void endEdit(
                final EditorAuthoringTransactionCoordinator.Binding current, final Object edit, final boolean abort) {
            fixture.editMode.end(abort, null);
            if (abort) {
                if (nativeAbort && !envelopeJoin) ((GroupUndo) edit).undo();
                roots.remove(edit);
                fixture.editMode.edits.clear();
            }
        }

        @Override
        public void refresh(
                final EditorAuthoringTransactionCoordinator.Binding current,
                final Set<EditorRefreshRequirement> requirements) {
            lastRefreshRequirements = Set.copyOf(requirements);
        }

        @Override
        public Optional<String> committedHistoryEntryId(
                final EditorAuthoringTransactionCoordinator.Binding current,
                final HistorySnapshot before,
                final HistorySnapshot after,
                final String id,
                final String label) {
            return after.position() == before.position() + 1
                    ? Optional.of("entry-" + after.position())
                    : Optional.empty();
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            final String value = failure == null
                    ? code
                    : code + "#" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
            return value.length() > 120 ? value.substring(0, 120) : value;
        }
    }

    private static VerifiedMemberResolver resolver(final boolean includeCapability) {
        return resolver(includeCapability, "");
    }

    private static VerifiedMemberResolver resolver(final boolean includeCapability, final String excludedAlias) {
        return resolver(includeCapability, excludedAlias, false);
    }

    private static VerifiedMemberResolver resolver(
            final boolean includeCapability, final String excludedAlias, final boolean envelopeJoin) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                desc(Host.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                desc(Document.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.complete-pack",
                Host.class,
                "completePack",
                desc(CompletePack.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.model-source",
                Document.class,
                "modelSource",
                desc(ModelSource.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.guid.class", internal(Id.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method(
                "cubism.editor-model.edit-mode.begin",
                EditMode.class,
                "begin",
                "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method("cubism.editor-model.undo.execute-undo", Undo.class, "undo", "()V"));
        selectors.add(method(
                "cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-parameter", CompletePack.class, "updateParameter", "(Z)V"));
        selectors.add(
                method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.model-source.current-instance",
                ModelSource.class,
                "currentInstance",
                desc(Model.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.handler", ModelSource.class, "handler", desc(ModelHandler.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.parameter-source-set",
                ModelSource.class,
                "parameterSourceSet",
                desc(ParameterSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.root-parameter-group",
                ModelSource.class,
                "rootGroup",
                desc(ParameterGroup.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.model-handler.class", internal(ModelHandler.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.model-handler.create-free-id-default",
                internal(ModelHandler.class),
                "createFreeIdDefault",
                "(L" + internal(ModelHandler.class) + ";L" + internal(Id.class)
                        + ";Ljava/lang/Object;ILjava/lang/Object;)L" + internal(Id.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.model-handler.remove-parameter",
                ModelHandler.class,
                "removeParameter",
                "(L" + internal(Id.class) + ";L" + internal(ParameterSet.class) + ";Z)L" + internal(GroupUndo.class)
                        + ";"));
        selectors.add(method(
                "cubism.editor-model.model-handler.move-parameter",
                ModelHandler.class,
                "moveParameter",
                "(L" + internal(ParameterGroup.class) + ";L" + internal(ParameterSource.class) + ";I)L"
                        + internal(Undo.class) + ";"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.parameter-source-set.class", internal(ParameterSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-source-set.get",
                ParameterSourceSet.class,
                "get",
                "(L" + internal(Id.class) + ";)L" + internal(ParameterSource.class) + ";"));
        selectors.add(method(
                "cubism.editor-model.parameter-source-set.get-by-id",
                ParameterSourceSet.class,
                "getById",
                "(L" + internal(Id.class) + ";)L" + internal(ParameterSource.class) + ";"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.parameter-source.class", internal(ParameterSource.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.parameter-source.create",
                internal(ParameterSource.class),
                "(L" + internal(Id.class) + ";Ljava/lang/String;FFFLjava/lang/String;L" + internal(Id.class) + ";L"
                        + internal(Id.class) + ";)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method("cubism.editor-model.parameter-source.id", ParameterSource.class, "id", desc(Id.class)));
        selectors.add(
                method("cubism.editor-model.parameter-source.guid", ParameterSource.class, "guid", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-source.name", ParameterSource.class, "name", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.parameter-source.repeat", ParameterSource.class, "isRepeat", "()Z"));
        selectors.add(method("cubism.editor-model.parameter-source.minimum", ParameterSource.class, "minValue", "()F"));
        selectors.add(method("cubism.editor-model.parameter-source.maximum", ParameterSource.class, "maxValue", "()F"));
        selectors.add(
                method("cubism.editor-model.parameter-source.default", ParameterSource.class, "defaultValue", "()F"));
        selectors.add(method(
                "cubism.editor-model.parameter-source.param-type", ParameterSource.class, "paramType", desc(Id.class)));
        selectors.add(
                method("cubism.editor-model.parameter-source.set-repeat", ParameterSource.class, "setRepeat", "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-source.parent-group",
                ParameterSource.class,
                "parentGroup",
                desc(ParameterGroup.class)));
        selectors.add(StaticSelector.field(
                "cubism.editor-model.parameter-source.type-normal",
                internal(Id.class),
                "NORMAL",
                "L" + internal(Id.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(StaticSelector.field(
                "cubism.editor-model.parameter-source.type-morph-target",
                internal(Id.class),
                "MORPH_TARGET",
                "L" + internal(Id.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.parameter-id.class", internal(Id.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.parameter-id.create",
                internal(Id.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.id.class", internal(Id.class)));
        selectors.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.parameter-group.class", internal(ParameterGroup.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.parameter-group.create",
                internal(ParameterGroup.class),
                "(Ljava/lang/String;Ljava/lang/Object;L" + internal(Id.class) + ";)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.parameter-group-id.create",
                internal(Id.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.parameter-group-guid.create",
                internal(CParameterGroupGuid.class),
                "()V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.parameter-group.handler",
                ParameterGroup.class,
                "handler",
                desc(ParameterGroupHandler.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-group.set-name",
                ParameterGroup.class,
                "setName",
                "(Ljava/lang/String;)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-group.set-folder-opened",
                ParameterGroup.class,
                "setFolderOpened",
                "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-group.children",
                ParameterGroup.class,
                "children",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.parameter-group.parent",
                ParameterGroup.class,
                "parent",
                desc(ParameterGroup.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-group.name", ParameterGroup.class, "name", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.parameter-group.id", ParameterGroup.class, "id", desc(Id.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.parameter-group-handler.class", internal(ParameterGroupHandler.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-group-handler.add-parameter-child",
                ParameterGroupHandler.class,
                "addParameterChild",
                "(L" + internal(ParameterSource.class) + ";I)L" + internal(Undo.class) + ";"));
        selectors.add(method(
                "cubism.editor-model.parameter-group-handler.add-group-child",
                ParameterGroupHandler.class,
                "addGroupChild",
                "(L" + internal(ParameterGroup.class) + ";I)L" + internal(Undo.class) + ";"));
        selectors.add(method(
                "cubism.editor-model.parameter-group-handler.remove-descendant",
                ParameterGroupHandler.class,
                "removeDescendant",
                "(L" + internal(ParameterGroup.class) + ";L" + internal(ParameterSet.class) + ";ZZ)L"
                        + internal(Undo.class) + ";"));
        selectors.add(method(
                "cubism.editor-model.model.parameter-set", Model.class, "parameterSet", desc(ParameterSet.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.parameter-set.class", internal(ParameterSet.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-set.parameters",
                ParameterSet.class,
                "parameters",
                "()Ljava/util/List;"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.simple-undo.create",
                internal(Undo.class),
                "(Ljava/lang/String;L" + internal(Object.class) + ";Ljava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(RuntimeParameter.class)));
        selectors.add(method("cubism.editor-model.parameter.id", RuntimeParameter.class, "id", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.parameter.source", RuntimeParameter.class, "source", desc(ParameterSource.class)));
        if (envelopeJoin) selectors.add(method("cubism.editor-model.undo.group-undo", GroupUndo.class, "undo", "()V"));
        return TestVerifiedResolvers.create(
                "5.3.02",
                "adapter.editor-model.readwrite",
                includeCapability
                        ? java.util.Set.of(
                                "cubism.editor-model.read",
                                EditorParameterStructureSelectorContract.CAPABILITY_ID,
                                envelopeJoin
                                        ? dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract
                                                .EDIT_BEGIN_CAPABILITY_ID
                                        : "test.no-envelope-join",
                                dev.turboism.mapping.verification.selector.EditorParameterGroupsReadSelectorContract
                                        .CAPABILITY_ID)
                        : java.util.Set.of("cubism.editor-model.read"),
                selectors.stream()
                        .filter(selector -> !selector.alias().equals(excludedAlias))
                        .toList(),
                Host.class.getClassLoader());
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

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }

        public CompletePack completePack() {
            return document.pack;
        }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode = new EditMode();
        final CompletePack pack = new CompletePack();
        boolean dirty;

        Document(final ModelSource source) {
            this.source = source;
        }

        public ModelSource modelSource() {
            return source;
        }

        public EditMode editMode() {
            return editMode;
        }

        public void markDirty() {
            dirty = true;
        }
    }

    public static final class ModelSource {
        final List<ParameterSource> sources = new ArrayList<>();
        final ParameterGroup root = new ParameterGroup("ROOT");
        final ModelHandler handler = new ModelHandler();
        final ParameterSourceSet sourceSet = new ParameterSourceSet();
        Model model = new Model();
        int updateCount;

        public Id guid() {
            return new Id("model-a");
        }

        public Model currentInstance() {
            return model;
        }

        public ModelHandler handler() {
            return handler;
        }

        public ParameterSourceSet parameterSourceSet() {
            return sourceSet;
        }

        public ParameterGroup rootGroup() {
            return root;
        }

        public void updateInstances() {
            updateCount++;
        }
    }

    public static final class Model {
        final ParameterSet parameterSet = new ParameterSet();

        public ParameterSet parameterSet() {
            return parameterSet;
        }
    }

    public static final class RuntimeParameter {
        final ParameterSource source;

        RuntimeParameter(final ParameterSource source) {
            this.source = source;
        }

        public Id id() {
            return source.id;
        }

        public ParameterSource source() {
            return source;
        }
    }

    public static final class ParameterSourceSet {
        final List<ParameterSource> all = new ArrayList<>();

        public ParameterSource get(final Id guid) {
            return all.stream()
                    .filter(s -> s.guid == guid || s.guid.value.equals(guid.value))
                    .findFirst()
                    .orElse(null);
        }

        public ParameterSource getById(final Id id) {
            return all.stream()
                    .filter(s -> s.id.value.equals(id.value))
                    .findFirst()
                    .orElse(null);
        }
    }

    public static final class ParameterSource {
        final Id id;
        final Id guid;
        final ParameterGroupHandler groupHandler;
        ParameterGroup parentGroup;
        boolean repeat;

        ParameterSource(final String id) {
            this.id = new Id(id);
            this.guid = new Id("guid-" + id);
            this.groupHandler = new ParameterGroupHandler();
        }

        public ParameterSource(
                final Id id,
                final String name,
                final float min,
                final float max,
                final float def,
                final String description,
                final Id guid,
                final Id type) {
            this.id = id;
            this.guid = guid == null ? new Id("guid-" + id.value) : guid;
            this.groupHandler = new ParameterGroupHandler();
        }

        public Id id() {
            return id;
        }

        public Id guid() {
            return guid;
        }

        public String name() {
            return id.value;
        }

        public boolean isRepeat() {
            return repeat;
        }

        public float minValue() {
            return 0.0F;
        }

        public float maxValue() {
            return 1.0F;
        }

        public float defaultValue() {
            return 0.5F;
        }

        public Id paramType() {
            return Id.NORMAL;
        }

        public void setRepeat(final boolean repeat) {
            this.repeat = repeat;
        }

        public ParameterGroup parentGroup() {
            return parentGroup;
        }
    }

    public static final class CParameterGroupGuid {
        public CParameterGroupGuid() {}
    }

    public static final class ParameterGroup {
        static Object lastGuid;
        final Id id;
        final String initialName;
        final ParameterGroupHandler groupHandler = new ParameterGroupHandler();
        final List<Object> children = new ArrayList<>();
        String name;
        ParameterGroup parent;

        ParameterGroup(final String id) {
            this.id = new Id(id);
            this.name = id;
            this.initialName = id;
        }

        public ParameterGroup(final String name, final Object guid, final Id id) {
            this.id = id;
            this.name = name;
            this.initialName = name;
            lastGuid = guid;
        }

        public Id id() {
            return id;
        }

        public Id guid() {
            return new Id("g-" + id.value);
        }

        public String name() {
            return name;
        }

        public ParameterGroupHandler handler() {
            return groupHandler;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public void setFolderOpened(final boolean ignored) {}

        public List<Object> children() {
            return children;
        }

        public ParameterGroup parent() {
            return parent;
        }
    }

    public static final class ParameterGroupHandler {
        public Undo addParameterChild(final ParameterSource source, final int index) {
            return Host.document.source.handler.addParameter(source, index);
        }

        public Undo addGroupChild(final ParameterGroup group, final int index) {
            return Host.document.source.handler.addGroup(group, index);
        }

        public Undo removeDescendant(
                final ParameterGroup group, final ParameterSet set, final boolean a, final boolean b) {
            return Host.document.source.handler.removeGroup(group);
        }
    }

    public static final class ModelHandler {
        static int nextId = 1;
        static ParameterSource lastAdded;
        static ParameterGroup lastGroup;

        public static Id createFreeIdDefault(
                final ModelHandler handler, final Id base, final Object idMap, final int flags, final Object marker) {
            return new Id(base.value + "_" + (nextId++));
        }

        public GroupUndo removeParameter(final Id guid, final ParameterSet set, final boolean flag) {
            final ModelSource source = Host.document.source;
            source.sources.removeIf(s -> s.guid.value.equals(guid.value));
            source.root.children.removeIf(
                    child -> child instanceof ParameterSource s && s.guid.value.equals(guid.value));
            source.model.parameterSet.parameters.removeIf(p -> p.source.guid.value.equals(guid.value));
            return new GroupUndo(new ArrayList<>());
        }

        public Undo moveParameter(final ParameterGroup group, final ParameterSource source, final int index) {
            final ParameterGroup original = source.parentGroup;
            final int originalIndex = original.children.indexOf(source);
            final Undo undo = new Undo() {
                @Override
                public void undo() {
                    group.children.remove(source);
                    original.children.add(originalIndex, source);
                    source.parentGroup = original;
                }

                @Override
                public void redo() {
                    source.parentGroup.children.remove(source);
                    group.children.add(index, source);
                    source.parentGroup = group;
                }
            };
            undo.redo();
            return undo;
        }

        public Undo addParameter(final ParameterSource source, final int index) {
            final ModelSource ms = Host.document.source;
            source.parentGroup = ms.root;
            ms.root.children.add(source);
            ms.sources.add(source);
            ms.model.parameterSet.parameters.add(new RuntimeParameter(source));
            ms.sourceSet.all.add(source);
            lastAdded = source;
            // Mirrors the host Undo_AddOrRemove_Parameter_: the add is applied immediately
            // (construct-and-redo); undo() removes the parameter, redo() re-applies it.
            return new ParameterAddUndo(source);
        }

        public static final class ParameterAddUndo extends Undo {
            final ParameterSource source;

            ParameterAddUndo(final ParameterSource source) {
                this.source = source;
            }

            @Override
            public void undo() {
                final ModelSource ms = Host.document.source;
                ms.sources.remove(source);
                ms.root.children.remove(source);
                ms.model.parameterSet.parameters.removeIf(p -> p.source == source);
                if (!Host.document.editMode.leaveSourceResidue) ms.sourceSet.all.remove(source);
            }

            @Override
            public void redo() {
                final ModelSource ms = Host.document.source;
                source.parentGroup = ms.root;
                ms.root.children.add(source);
                ms.sources.add(source);
                ms.model.parameterSet.parameters.add(new RuntimeParameter(source));
                ms.sourceSet.all.add(source);
            }
        }

        public Undo addGroup(final ParameterGroup group, final int index) {
            final ModelSource ms = Host.document.source;
            group.parent = ms.root;
            ms.root.children.add(group);
            lastGroup = group;
            return new Undo();
        }

        public Undo removeGroup(final ParameterGroup group) {
            final ModelSource ms = Host.document.source;
            if (group.parent != null) group.parent.children.remove(group);
            return new Undo();
        }
    }

    public static final class ParameterSet {
        final List<RuntimeParameter> parameters = new ArrayList<>();
        int parameterCollectionReads;

        public List<RuntimeParameter> parameters() {
            parameterCollectionReads++;
            return parameters;
        }
    }

    public static final class Id {
        public static final Id NORMAL = new Id("NORMAL");
        public static final Id MORPH_TARGET = new Id("MORPH_TARGET");
        final String value;

        public Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final class EditMode {
        final List<Undo> edits = new ArrayList<>();
        boolean aborted;
        int beginCount;
        int endCount;
        GroupUndo lastUndo;
        boolean leaveSourceResidue;
        boolean rejectListener;
        int rejectAt;
        int admissionCount;

        public GroupUndo begin(final String name) {
            beginCount++;
            lastUndo = new GroupUndo(edits);
            return lastUndo;
        }

        public void end(final boolean abort, final Object ignored) {
            endCount++;
            aborted = abort;
        }
        /** Simulates the host native Undo command (menu click) for the latest edit envelope. */
        public void undo() {
            if (lastUndo != null) lastUndo.undo();
        }
        /** Simulates the host native Redo command for the latest edit envelope. */
        public void redo() {
            if (lastUndo != null) lastUndo.redo();
        }
    }

    public static final class GroupUndo extends Undo {
        final List<Undo> edits;

        GroupUndo(final List<Undo> edits) {
            this.edits = edits;
        }

        public boolean add(final Undo undo, final boolean significant) {
            final var owner = Host.document.editMode;
            if (++owner.admissionCount == owner.rejectAt) return false;
            edits.add(undo);
            return true;
        }

        @Override
        public void undo() {
            for (int index = edits.size() - 1; index >= 0; index--)
                edits.get(index).undo();
            changed();
        }

        @Override
        public void redo() {
            for (Undo edit : edits) edit.redo();
            changed();
        }
    }

    public static class Undo {
        private final List<Listener> listeners = new ArrayList<>();

        public Undo() {}

        public Undo(final String name, final Object target, final Object copyParam) {}

        public boolean addListener(final Listener listener) {
            if (Host.document.editMode.rejectListener) return false;
            listeners.add(listener);
            return true;
        }

        public int listenerCount() {
            return listeners.size();
        }

        void changed() {
            listeners.forEach(listener -> listener.changed(this));
        }

        public void undo() {}

        public void redo() {}
    }

    @FunctionalInterface
    public interface Listener {
        void changed(Object ignored);
    }

    public static final class CompletePack {
        int updateParameterCount;
        int repaintCount;

        public void updateParameter(final boolean immediate) {
            updateParameterCount++;
        }

        public void repaint(final boolean immediate) {
            repaintCount++;
        }
    }

    private static final class Fixture {
        final ModelSource source;
        final ParameterGroup root;
        final ModelHandler handler;
        final Document document;
        final EditMode editMode;
        final CompletePack pack;
        final ParameterSource p1;

        Fixture() {
            ModelHandler.nextId = 1;
            ModelHandler.lastAdded = null;
            ModelHandler.lastGroup = null;
            ParameterGroup.lastGuid = null;
            source = new ModelSource();
            root = source.root;
            handler = source.handler;
            p1 = new ParameterSource("P1");
            p1.parentGroup = root;
            root.children.add(p1);
            source.sources.add(p1);
            source.sourceSet.all.add(p1);
            source.model.parameterSet.parameters.add(new RuntimeParameter(p1));
            document = new Document(source);
            editMode = document.editMode;
            pack = document.pack;
        }
    }
}
