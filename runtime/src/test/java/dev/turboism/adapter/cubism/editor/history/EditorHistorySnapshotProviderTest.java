package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import org.junit.jupiter.api.Test;
import dev.turboism.sdk.cubism.history.HistoryAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorHistorySnapshotProviderTest {

    @Test
    void projectsImmutableHistoryAndAdvancesRevisionOnlyWhenStateChanges() {
        final Manager manager = new Manager();
        manager.entries.add(new Entry("Set Parameter", true));
        manager.position = 1;
        Host.document = new Document(manager);
        final AtomicLong generation = new AtomicLong(4);
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            generation::get
        );

        final HistorySnapshot first = provider.snapshot();
        final HistorySnapshot same = provider.snapshot();
        manager.position = 0;
        final HistorySnapshot undone = provider.snapshot();

        assertEquals(HistorySnapshot.Availability.AVAILABLE, first.availability());
        assertEquals(4, first.generation());
        assertEquals(1, first.position());
        assertEquals("Set Parameter", first.entries().get(0).label());
        assertTrue(first.canUndo());
        assertFalse(first.canRedo());
        assertFalse(first.documentBindingId().isBlank());
        assertFalse(first.managerBindingId().isBlank());
        assertTrue(provider.isCurrentBinding(first));
        assertEquals(first.revision(), same.revision());
        assertEquals(first.revision() + 1, undone.revision());
        assertFalse(undone.canUndo());
        assertTrue(undone.canRedo());
    }

    @Test
    void enrichesOnlyEntriesRegisteredByTurboism() {
        final Manager manager = new Manager();
        final Entry nativeEntry = new Entry("Native edit", true);
        final Entry turboismEntry = new Entry("Turboism: Set Parameter Value", true);
        manager.entries.add(nativeEntry);
        manager.entries.add(turboismEntry);
        manager.position = 2;
        Host.document = new Document(manager);
        EditorHistoryMetadataRegistry.register(turboismEntry, new HistoryAction(
            HistoryAction.Kind.SET_PARAMETER_VALUE,
            "PARAMETER",
            "ParamAngleX",
            "value",
            Optional.of("0.0"),
            Optional.of("-19.8"),
            HistoryAction.DetailLevel.FULL
        ));
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            () -> 6
        );

        final HistorySnapshot snapshot = provider.snapshot();

        assertEquals(HistoryAction.DetailLevel.LABEL_ONLY, snapshot.entries().get(0).detailLevel());
        assertEquals(HistoryAction.DetailLevel.FULL, snapshot.entries().get(1).detailLevel());
        assertEquals("ParamAngleX", snapshot.entries().get(1).action().orElseThrow().targetId());
    }

    @Test
    void movesToRequestedPositionAndReturnsTheObservedSnapshot() {
        final Manager manager = new Manager();
        manager.entries.add(new Entry("First", true));
        manager.entries.add(new Entry("Second", true));
        manager.entries.add(new Entry("Third", true));
        manager.position = 3;
        Host.document = new Document(manager);
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            () -> 7
        );
        final HistorySnapshot before = provider.snapshot();

        final HistoryMoveResult result = provider.moveTo(before, 1);

        assertEquals(HistoryMoveResult.Outcome.MOVED, result.outcome());
        assertEquals(1, result.snapshot().position());
        assertEquals(before.revision() + 1, result.snapshot().revision());
        assertTrue(result.diagnosticId().isEmpty());
    }

    @Test
    void bindingIdentityRejectsDocumentAndManagerFocusDrift() {
        final Manager firstManager = managerAt(1);
        final Document firstDocument = new Document(firstManager);
        Host.document = firstDocument;
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            () -> 8
        );
        final HistorySnapshot first = provider.snapshot();
        assertTrue(provider.isCurrentBinding(first));

        Host.document = new Document(new Manager());
        assertFalse(provider.isCurrentBinding(first));

        Host.document = new Document(firstManager);
        assertFalse(provider.isCurrentBinding(first));

        Host.document = firstDocument;
        assertTrue(provider.isCurrentBinding(first));
    }

    @Test
    void collectedBindingsAreRemovedWithoutChangingLiveBindingIdentity() {
        final Document firstDocument = new Document(managerAt(1));
        Host.document = firstDocument;
        final EditorHistorySnapshotProvider.BindingIdentityTracker documents =
            new EditorHistorySnapshotProvider.BindingIdentityTracker("history-document-");
        final EditorHistorySnapshotProvider.BindingIdentityTracker managers =
            new EditorHistorySnapshotProvider.BindingIdentityTracker("history-manager-");
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            () -> 9,
            documents,
            managers
        );
        provider.snapshot();

        Host.document = new Document(managerAt(1));
        final HistorySnapshot replacement = provider.snapshot();
        assertEquals(4, provider.trackedBindingIdentityCount());
        assertTrue(provider.isCurrentBinding(replacement));

        documents.clearAndEnqueueForTesting(firstDocument);
        managers.clearAndEnqueueForTesting(firstDocument.manager);

        assertTrue(provider.isCurrentBinding(replacement));
        assertEquals(replacement.documentBindingId(), provider.snapshot().documentBindingId());
        assertEquals(replacement.managerBindingId(), provider.snapshot().managerBindingId());
        assertEquals(2, provider.trackedBindingIdentityCount());
    }

    @Test
    void boundMoveRejectsDocumentAndManagerFocusDriftBeforeCallingTheHost() {
        final Manager firstManager = managerAt(3);
        final Document firstDocument = new Document(firstManager);
        Host.document = firstDocument;
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            () -> 9
        );
        final HistorySnapshot expected = provider.snapshot();

        Host.document = new Document(new Manager());
        assertEquals(
            HistoryMoveResult.Outcome.REJECTED_STALE,
            provider.moveTo(expected, 1).outcome()
        );
        assertEquals(3, firstManager.position);
        assertEquals(0, firstManager.moveCalls);

        Host.document = new Document(firstManager);
        assertEquals(
            HistoryMoveResult.Outcome.REJECTED_STALE,
            provider.moveTo(expected, 1).outcome()
        );
        assertEquals(3, firstManager.position);
        assertEquals(0, firstManager.moveCalls);

        Host.document = firstDocument;
        assertEquals(HistoryMoveResult.Outcome.MOVED, provider.moveTo(expected, 1).outcome());
        assertEquals(1, firstManager.position);
        assertEquals(1, firstManager.moveCalls);
    }

    @Test
    void undoAndRedoUseTheBindingAwareMoveAndFailClosedWhenFocusChangesAfterSnapshot() {
        final Manager firstManager = managerAt(3);
        final Document firstDocument = new Document(firstManager);
        Host.document = firstDocument;
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            () -> 10
        );

        assertEquals(HistoryMoveResult.Outcome.MOVED, provider.undo(2).outcome());
        assertEquals(1, firstManager.position);
        assertEquals(1, firstManager.moveCalls);
        assertEquals(HistoryMoveResult.Outcome.MOVED, provider.redo(1).outcome());
        assertEquals(2, firstManager.position);
        assertEquals(2, firstManager.moveCalls);

        firstManager.afterCanUndo = () -> Host.document = new Document(new Manager());
        assertEquals(HistoryMoveResult.Outcome.REJECTED_STALE, provider.undo(1).outcome());
        assertEquals(2, firstManager.position);
        assertEquals(2, firstManager.moveCalls);
    }

    @Test
    void rejectsStaleAndInvalidRequestsBeforeCallingTheHost() {
        final Manager manager = managerAt(3);
        Host.document = new Document(manager);
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            () -> 9
        );
        final HistorySnapshot before = provider.snapshot();

        assertEquals(HistoryMoveResult.Outcome.REJECTED_STALE,
            provider.moveTo(9, before.revision() + 1, 1).outcome());
        assertEquals(HistoryMoveResult.Outcome.INVALID_POSITION,
            provider.moveTo(9, before.revision(), 4).outcome());
        assertEquals(3, manager.position);
        assertEquals(0, manager.moveCalls);
    }

    private static Manager managerAt(final int position) {
        final Manager manager = new Manager();
        manager.entries.add(new Entry("First", true));
        manager.entries.add(new Entry("Second", true));
        manager.entries.add(new Entry("Third", true));
        manager.position = position;
        return manager;
    }

    @Test
    void exact5303RuntimeAuthorizationFailsClosedWithoutHistoryCapabilityOrSelectors() {
        final VerifiedMemberResolver resolver5303 = TestVerifiedResolvers.create(
            "5.3.03",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-model.read", "cubism.editor-model.texture.read"),
            List.of(
                StaticSelector.classSelector(
                    "cubism.editor-model.app-controller.class", internal(Host.class)
                )
            ),
            Host.class.getClassLoader()
        );
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver5303),
            () -> 11
        );

        assertEquals(HistorySnapshot.Availability.UNAVAILABLE, provider.snapshot().availability());
        assertEquals(HistoryMoveResult.Outcome.UNAVAILABLE, provider.moveTo(11, 0, 0).outcome());
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void generationReplacementAndMissingDocumentFailClosed() {
        final AtomicLong generation = new AtomicLong(0);
        final EditorHistorySnapshotProvider provider = new EditorHistorySnapshotProvider(
            () -> Optional.of(resolver()),
            generation::get
        );

        assertEquals(HistorySnapshot.Availability.UNAVAILABLE, provider.snapshot().availability());
        generation.set(2);
        Host.document = null;
        assertEquals(HistorySnapshot.Availability.UNAVAILABLE, provider.snapshot().availability());
        assertEquals(HistoryMoveResult.Outcome.UNAVAILABLE, provider.moveTo(2, 0, 0).outcome());
        assertEquals("history.move.unavailable", provider.moveTo(2, 0, 0).diagnosticId().orElseThrow());
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-model.read", "cubism.editor-history.read", "cubism.editor-history.move"),
            List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)),
                StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)),
                method("cubism.editor-history.document.undo-manager", Document.class, "undoManager", desc(Manager.class)),
                StaticSelector.classSelector("cubism.editor-history.manager.class", internal(Manager.class)),
                method("cubism.editor-history.manager.entries", Manager.class, "entries", "()Ljava/util/List;"),
                method("cubism.editor-history.manager.position", Manager.class, "position", "()I"),
                method("cubism.editor-history.manager.can-undo", Manager.class, "canUndo", "()Z"),
                method("cubism.editor-history.manager.can-redo", Manager.class, "canRedo", "()Z"),
                method("cubism.editor-history.manager.move-to", Manager.class, "moveTo", "(I)V"),
                StaticSelector.classSelector("cubism.editor-history.entry.class", internal(Entry.class)),
                method("cubism.editor-history.entry.presentation-name", Entry.class, "presentationName", "()Ljava/lang/String;"),
                method("cubism.editor-history.entry.significant", Entry.class, "significant", "()Z")
            ),
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    public static final class Host {
        private static Document document;
        public static Host instance() { return new Host(); }
        public Document currentDocument() { return document; }
    }

    public static final class Document {
        private final Manager manager;
        Document(final Manager manager) { this.manager = manager; }
        public Manager undoManager() { return manager; }
    }

    public static final class Manager {
        private final List<Entry> entries = new ArrayList<>();
        private int position;
        private int moveCalls;
        private Runnable afterCanUndo = () -> { };
        public List<Entry> entries() { return entries; }
        public int position() { return position; }
        public boolean canUndo() {
            final boolean result = position > 0;
            afterCanUndo.run();
            afterCanUndo = () -> { };
            return result;
        }
        public boolean canRedo() { return position < entries.size(); }
        public void moveTo(final int target) { moveCalls++; position = target; }
    }

    public static final class Entry {
        private final String label;
        private final boolean significant;
        Entry(final String label, final boolean significant) {
            this.label = label;
            this.significant = significant;
        }
        public String presentationName() { return label; }
        public boolean significant() { return significant; }
    }
}
