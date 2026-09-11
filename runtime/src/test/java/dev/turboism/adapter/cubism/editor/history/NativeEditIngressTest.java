package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.turboism.adapter.cubism.editor.history.NativeUndoIngressObserverTest.internal;
import static dev.turboism.adapter.cubism.editor.history.NativeUndoIngressObserverTest.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the native edit ingress publishes only facts an exact decode proves, publishes them
 * once, and never lets a failure escape into the caller that drained it.
 */
class NativeEditIngressTest {

    @Test
    void anExactPartMembershipCommitPublishesTheHierarchyOperationWithItsSubject() {
        final Recorder recorder = new Recorder();

        recorder.manager.commit(recorder.partEntry(true));
        assertEquals(1, recorder.ingress.drain());

        assertEquals(1, recorder.published.size());
        assertEquals(CubismOperation.SET_HIERARCHY_PARENT, recorder.operation());
        assertEquals(CubismOperationOrigin.HOST_UI, recorder.origin());
        assertEquals(Optional.of("mesh-1"), recorder.subject());
        assertEquals(Optional.of("Add Part"), recorder.label());
    }

    @Test
    void aDetachCommitPublishesTheDetachOperation() {
        final Recorder recorder = new Recorder();

        recorder.manager.commit(recorder.partEntry(false));
        assertEquals(1, recorder.ingress.drain());

        assertEquals(CubismOperation.DETACH_HIERARCHY_PARENT, recorder.operation());
    }

    @Test
    void anEntryTurboismItselfAuthoredIsNotPublishedTwice() {
        final Recorder recorder = new Recorder();

        final PartMembershipEntry own = recorder.partEntry(true);
        EditorHistoryMetadataRegistry.registerTransaction(own, "transaction-1");
        recorder.manager.commit(own);
        assertEquals(1, recorder.ingress.drain(), "the change is still observed");

        assertTrue(recorder.published.isEmpty(), "Turboism's own commit must not be republished");
        assertEquals(0, recorder.ingress.publishedCount());
        assertEquals(1, recorder.ingress.suppressedOwnCommitCount());
    }

    @Test
    void anEntryOnlyKnownFromASnapshotProjectionIsStillPublished() {
        final Recorder recorder = new Recorder();

        final PartMembershipEntry nativeEntry = recorder.partEntry(true);
        // A snapshot projection allocates an entry identity without claiming authorship.
        assertNotNull(EditorHistoryMetadataRegistry.metadata(nativeEntry).entryId());
        recorder.manager.commit(nativeEntry);
        assertEquals(1, recorder.ingress.drain());

        assertEquals(CubismOperation.SET_HIERARCHY_PARENT, recorder.operation());
        assertEquals(0, recorder.ingress.suppressedOwnCommitCount());
    }

    @Test
    void anUndecodableCommitFallsBackToTheGenericEditorCommand() {
        final Recorder recorder = new Recorder();

        recorder.manager.commit(new NativeUndoIngressObserverTest.Entry("Rename part"));
        assertEquals(1, recorder.ingress.drain());

        assertEquals(CubismOperation.EXECUTE_EDITOR_COMMAND, recorder.operation());
        assertEquals(CubismOperationOrigin.HOST_UI, recorder.origin());
        assertTrue(recorder.subject().isEmpty());
        assertEquals(
            Optional.of("Rename part"),
            recorder.label(),
            "a native name may be published as presentation, but never as an identity"
        );
    }

    @Test
    void anUndoAndRedoNameNoSubjectAndNoLabelOfTheirOwn() {
        final Recorder recorder = new Recorder();

        recorder.manager.commit(new NativeUndoIngressObserverTest.Entry("Rename part"));
        recorder.ingress.drain();
        recorder.published.clear();

        recorder.manager.undo();
        recorder.ingress.drain();
        recorder.manager.redo();
        recorder.ingress.drain();

        // The observer only labels a commit, where the newly appended entry is exact. An undo or a
        // redo establishes which entry moved, not a name for the move itself, so the ingress
        // publishes neither a subject nor a label rather than deriving one from the moved entry.
        assertTrue(recorder.published.get(0).label().isEmpty());
        assertTrue(recorder.published.get(1).label().isEmpty());
        assertTrue(recorder.published.get(0).subjectId().isEmpty());
        assertTrue(recorder.published.get(1).subjectId().isEmpty());
    }

    @Test
    void undoingAndRedoingAHostEditPublishesTheirOwnOrigins() {
        final Recorder recorder = new Recorder();

        recorder.manager.commit(recorder.partEntry(true));
        recorder.ingress.drain();
        recorder.published.clear();

        recorder.manager.undo();
        assertEquals(1, recorder.ingress.drain());
        recorder.manager.redo();
        assertEquals(1, recorder.ingress.drain());

        assertEquals(CubismOperation.UNDO, recorder.published.get(0).operation());
        assertEquals(CubismOperationOrigin.UNDO, recorder.published.get(0).origin());
        assertEquals(CubismOperation.REDO, recorder.published.get(1).operation());
        assertEquals(CubismOperationOrigin.REDO, recorder.published.get(1).origin());
    }

    @Test
    void aPublisherFailureIsCountedInsteadOfEscapingTheDrain() {
        final NativeUndoIngressObserverTest.Manager manager =
            new NativeUndoIngressObserverTest.Manager();
        final NativeEditIngress ingress = new NativeEditIngress(resolver(), manager, (o, r, s, l) -> {
            throw new IllegalStateException("publisher failure");
        });
        ingress.attach();

        manager.commit(new PartMembershipEntry("BodyMesh", true));

        assertEquals(1, ingress.drain(), "the observer still reports the observed change");
        assertEquals(1, ingress.failureCount());
        assertEquals(0, ingress.publishedCount());
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-history.read", "cubism.editor-history.semantic-read"),
            selectors(),
            NativeEditIngressTest.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        final ArrayList<StaticSelector> all = new ArrayList<>();
        all.add(method(
            "cubism.editor-history.manager.entries",
            NativeUndoIngressObserverTest.Manager.class,
            "entries",
            "()Ljava/util/List;"
        ));
        all.add(method(
            "cubism.editor-history.manager.position",
            NativeUndoIngressObserverTest.Manager.class,
            "position",
            "()I"
        ));
        all.add(method(
            "cubism.editor-history.entry.presentation-name",
            NativeUndoIngressObserverTest.Entry.class,
            "presentationName",
            "()Ljava/lang/String;"
        ));
        all.add(StaticSelector.classSelector(
            NativeUndoIngressObserver.LISTENER_CLASS_ALIAS,
            internal(NativeUndoIngressObserverTest.Listener.class)
        ));
        all.add(method(
            NativeUndoIngressObserver.LISTENER_ADD_ALIAS,
            NativeUndoIngressObserverTest.Manager.class,
            "addUndoStateChangeListener",
            "(L" + internal(NativeUndoIngressObserverTest.Listener.class) + ";)V"
        ));
        all.add(method(
            NativeUndoIngressObserver.LISTENER_REMOVE_ALIAS,
            NativeUndoIngressObserverTest.Manager.class,
            "removeUndoStateChangeListener",
            "(L" + internal(NativeUndoIngressObserverTest.Listener.class) + ";)V"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-history.semantic.part-membership.class",
            internal(PartMembershipEntry.class)
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.part",
            PartMembershipEntry.class,
            "getPart",
            "()L" + internal(PartSourceDouble.class) + ";"
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.child",
            PartMembershipEntry.class,
            "getChild",
            "()L" + internal(ArtMeshDouble.class) + ";"
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.index",
            PartMembershipEntry.class,
            "getInsertIndex",
            "()I"
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.is-add",
            PartMembershipEntry.class,
            "isAdd",
            "()Z"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.part-source.class",
            internal(PartSourceDouble.class)
        ));
        all.add(method(
            "cubism.editor-model.part-source.id",
            PartSourceDouble.class,
            "id",
            "()L" + internal(PartId.class) + ";"
        ));
        all.add(method(
            "cubism.editor-model.part-id.value",
            PartId.class,
            "value",
            "()Ljava/lang/String;"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.art-mesh-source.class",
            internal(ArtMeshDouble.class)
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.warp-source.class",
            internal(WarpDouble.class)
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.rotation-source.class",
            internal(RotationDouble.class)
        ));
        all.add(method(
            "cubism.editor-model.parameter-controllable-source.id",
            ControllableSource.class,
            "id",
            "()L" + internal(SourceId.class) + ";"
        ));
        all.add(method(
            "cubism.editor-model.parameter-controllable-source.local-name",
            SourceBase.class,
            "getLocalName",
            "()Ljava/lang/String;"
        ));
        all.add(method(
            "cubism.editor-model.id.value",
            SourceId.class,
            "value",
            "()Ljava/lang/String;"
        ));
        return List.copyOf(all);
    }

    /** One wired ingress plus the publications it produced. */
    private static final class Recorder {
        private final NativeUndoIngressObserverTest.Manager manager =
            new NativeUndoIngressObserverTest.Manager();
        private final List<Published> published = new ArrayList<>();
        private final NativeEditIngress ingress;

        Recorder() {
            this.ingress = new NativeEditIngress(
                resolver(),
                manager,
                (operation, origin, subject, label) -> published.add(
                    new Published(operation, origin, subject, label)
                )
            );
            this.ingress.attach();
        }

        PartMembershipEntry partEntry(final boolean add) {
            return new PartMembershipEntry("BodyMesh", add);
        }

        CubismOperation operation() {
            return published.get(0).operation();
        }

        CubismOperationOrigin origin() {
            return published.get(0).origin();
        }

        Optional<String> subject() {
            return published.get(0).subjectId();
        }

        Optional<String> label() {
            return published.get(0).label();
        }
    }

    private record Published(
        CubismOperation operation,
        CubismOperationOrigin origin,
        Optional<String> subjectId,
        Optional<String> label
    ) {
    }

    /** Test double for {@code Editor_Part$Undo_AddOrRemovePartChild}. */
    public static final class PartMembershipEntry extends NativeUndoIngressObserverTest.Entry {
        private final PartSourceDouble part;
        private final ArtMeshDouble child;
        private final boolean add;

        PartMembershipEntry(final String childName, final boolean add) {
            super("Add Part");
            this.part = new PartSourceDouble(add ? "PartB" : "PartA");
            this.child = new ArtMeshDouble(childName);
            this.add = add;
        }

        public PartSourceDouble getPart() {
            return part;
        }

        public ArtMeshDouble getChild() {
            return child;
        }

        public int getInsertIndex() {
            return 0;
        }

        public boolean isAdd() {
            return add;
        }
    }

    /** Test double for {@code ACParameterControllableSource}. */
    public static class SourceBase {
        private final String localName;

        SourceBase(final String localName) {
            this.localName = localName;
        }

        public String getLocalName() {
            return localName;
        }
    }

    public static class ControllableSource extends SourceBase {
        ControllableSource(final String localName) {
            super(localName);
        }

        public SourceId id() {
            return new SourceId("mesh-1");
        }
    }

    public static final class ArtMeshDouble extends ControllableSource {
        ArtMeshDouble(final String localName) {
            super(localName);
        }
    }

    public static final class WarpDouble extends ControllableSource {
        WarpDouble(final String localName) {
            super(localName);
        }
    }

    public static final class RotationDouble extends ControllableSource {
        RotationDouble(final String localName) {
            super(localName);
        }
    }

    public static final class PartSourceDouble extends SourceBase {
        PartSourceDouble(final String localName) {
            super(localName);
        }

        public PartId id() {
            return new PartId(getLocalName());
        }
    }

    public record SourceId(String value) {
    }

    public record PartId(String value) {
    }
}
