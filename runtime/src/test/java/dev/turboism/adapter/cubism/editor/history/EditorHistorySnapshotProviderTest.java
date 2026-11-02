package dev.turboism.adapter.cubism.editor.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EditorHistorySnapshotProviderTest {

    @Test
    void projectsImmutableHistoryAndAdvancesRevisionOnlyWhenStateChanges() {
        final Manager manager = new Manager();
        manager.entries.add(new Entry("Set Parameter", true));
        manager.position = 1;
        Host.document = new Document(manager);
        final AtomicLong generation = new AtomicLong(4);
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), generation::get);

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
        assertSame(first, same, "unchanged history should reuse its immutable projection");
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
        EditorHistoryMetadataRegistry.register(
                turboismEntry,
                new HistoryAction(
                        HistoryAction.Kind.SET_PARAMETER_VALUE,
                        "PARAMETER",
                        "ParamAngleX",
                        "value",
                        Optional.of("0.0"),
                        Optional.of("-19.8"),
                        HistoryAction.DetailLevel.FULL));
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 6);

        final HistorySnapshot snapshot = provider.snapshot();

        assertEquals(
                HistoryAction.DetailLevel.LABEL_ONLY, snapshot.entries().get(0).detailLevel());
        assertEquals(HistoryAction.DetailLevel.FULL, snapshot.entries().get(1).detailLevel());
        assertEquals(
                "ParamAngleX", snapshot.entries().get(1).action().orElseThrow().targetId());
    }

    @Test
    void authoritativeSemanticMetadataWinsBeforeAnyNativeFallback() {
        final Manager manager = new Manager();
        final Entry entry = new Entry("Host label", true);
        manager.entries.add(entry);
        manager.position = 1;
        Host.document = new Document(manager);
        EditorHistoryMetadataRegistry.registerDetail(
                entry,
                HistoryEntryDetail.labelOnly(
                        "Captured semantic summary",
                        HistoryOrigin.turboism("test-plugin", "operation-1"),
                        "history.detail.captured-label-only"));
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 6);

        final var projected = provider.snapshot().entries().get(0).detail();
        assertEquals("Captured semantic summary", projected.summary());
        assertEquals(HistoryOrigin.Kind.TURBOISM, projected.origin().kind());
        assertEquals(
                "history.detail.captured-label-only",
                projected.degradationCode().orElseThrow());
    }

    @Test
    void observedDetailFillsEntriesThatHaveNoAuthoritativeMetadata() {
        final Manager manager = new Manager();
        final Entry entry = new Entry("Native edit", true);
        manager.entries.add(entry);
        manager.position = 1;
        Host.document = new Document(manager);
        EditorHistoryMetadataRegistry.registerObserved(
                entry,
                HistoryEntryDetail.labelOnly(
                        "Observed at commit", HistoryOrigin.hostUnattributed(), "history.detail.native-decoded"));
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 6);

        final var projected = provider.snapshot().entries().get(0).detail();

        assertEquals("Observed at commit", projected.summary());
        assertEquals(HistoryOrigin.Kind.HOST_UNATTRIBUTED, projected.origin().kind());
        assertFalse(
                EditorHistoryMetadataRegistry.claimsProvenance(entry),
                "reading an observed detail must not claim the entry");
    }

    @Test
    void anObservedGroupThatGrewAfterCommitIsDecodedAgain() {
        final Manager manager = new Manager();
        final Entry entry = new Entry("Grouped edit", true);
        entry.groupCount = 2;
        manager.entries.add(entry);
        manager.position = 1;
        Host.document = new Document(manager);
        EditorHistoryMetadataRegistry.registerObserved(
                entry,
                new HistoryEntryDetail(
                        "Observed while open",
                        HistoryAction.DetailLevel.PARTIAL,
                        HistoryOrigin.hostUnattributed(),
                        List.of(),
                        List.of(),
                        Optional.of(new HistoryGroup(
                                Optional.empty(),
                                1,
                                List.of(HistoryEntryDetail.labelOnly(
                                        "child", HistoryOrigin.hostUnattributed(), "history.detail.native-decoded")),
                                false)),
                        Optional.of("history.detail.group-partial")));
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 6);

        final var projected = provider.snapshot().entries().get(0).detail();

        assertNotEquals("Observed while open", projected.summary());
        assertTrue(projected.group().isEmpty(), "the stale group projection must be replaced");
    }

    @Test
    void assignsStableEntryIdsAndProjectsRegisteredTransactionMetadata() {
        final Manager manager = new Manager();
        final Entry entry = new Entry("Adjust eye glues", true);
        manager.entries.add(entry);
        manager.position = 1;
        Host.document = new Document(manager);
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 6);

        final HistorySnapshot first = provider.snapshot();
        final String entryId = first.entries().get(0).entryId().orElseThrow().value();
        EditorHistoryMetadataRegistry.registerTransaction(entry, "transaction-1");
        final HistorySnapshot enriched = provider.snapshot();

        assertTrue(entryId.startsWith("history-entry-"));
        assertEquals(entryId, enriched.entries().get(0).entryId().orElseThrow().value());
        assertEquals("transaction-1", enriched.entries().get(0).transactionId().orElseThrow());
    }

    @Test
    void mutableLabelsAndSameSizedHistoryReplacementStillAdvanceRevision() {
        final Manager manager = new Manager();
        final Entry entry = new Entry("Before", true);
        manager.entries.add(entry);
        manager.position = 1;
        Host.document = new Document(manager);
        final var provider = new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 1);
        final var before = provider.snapshot();
        entry.label = "After";
        final var renamed = provider.snapshot();
        assertEquals("Before", before.entries().get(0).label());
        assertEquals("After", renamed.entries().get(0).label());
        assertEquals(before.revision() + 1, renamed.revision());
        manager.entries.set(0, new Entry("After", true));
        final var replaced = provider.snapshot();
        assertNotEquals(
                renamed.entries().get(0).entryId(), replaced.entries().get(0).entryId());
        assertEquals(renamed.revision() + 1, replaced.revision());
        assertSame(replaced, provider.snapshot());
    }

    @Test
    void metadataReusesImmutableValuesAndNeverUsesNativeEquality() {
        final Object first = new Object() {
            @Override
            public int hashCode() {
                throw new AssertionError("native hashCode");
            }

            @Override
            public boolean equals(final Object other) {
                throw new AssertionError("native equals");
            }
        };
        final Object second = new Object();
        final var original = EditorHistoryMetadataRegistry.metadata(first);
        assertSame(original, EditorHistoryMetadataRegistry.metadata(first));
        assertNotEquals(
                original.entryId(),
                EditorHistoryMetadataRegistry.metadata(second).entryId());
        EditorHistoryMetadataRegistry.registerTransaction(first, "transaction-updated");
        final var updated = EditorHistoryMetadataRegistry.metadata(first);
        assertEquals(original.entryId(), updated.entryId());
        assertEquals(Optional.empty(), original.transactionId());
        assertEquals(Optional.of("transaction-updated"), updated.transactionId());
        assertSame(updated, EditorHistoryMetadataRegistry.metadata(first));
    }

    @Test
    void movesToRequestedPositionAndReturnsTheObservedSnapshot() {
        final Manager manager = new Manager();
        manager.entries.add(new Entry("First", true));
        manager.entries.add(new Entry("Second", true));
        manager.entries.add(new Entry("Third", true));
        manager.position = 3;
        Host.document = new Document(manager);
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 7);
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
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 8);
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
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 9, documents, managers);
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
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 9);
        final HistorySnapshot expected = provider.snapshot();

        Host.document = new Document(new Manager());
        assertEquals(
                HistoryMoveResult.Outcome.REJECTED_STALE,
                provider.moveTo(expected, 1).outcome());
        assertEquals(3, firstManager.position);
        assertEquals(0, firstManager.moveCalls);

        Host.document = new Document(firstManager);
        assertEquals(
                HistoryMoveResult.Outcome.REJECTED_STALE,
                provider.moveTo(expected, 1).outcome());
        assertEquals(3, firstManager.position);
        assertEquals(0, firstManager.moveCalls);

        Host.document = firstDocument;
        assertEquals(
                HistoryMoveResult.Outcome.MOVED, provider.moveTo(expected, 1).outcome());
        assertEquals(1, firstManager.position);
        assertEquals(1, firstManager.moveCalls);
    }

    @Test
    void undoAndRedoUseTheBindingAwareMoveAndFailClosedWhenFocusChangesAfterSnapshot() {
        final Manager firstManager = managerAt(3);
        final Document firstDocument = new Document(firstManager);
        Host.document = firstDocument;
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 10);

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
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), () -> 9);
        final HistorySnapshot before = provider.snapshot();

        assertEquals(
                HistoryMoveResult.Outcome.REJECTED_STALE,
                provider.moveTo(9, before.revision() + 1, 1).outcome());
        assertEquals(
                HistoryMoveResult.Outcome.INVALID_POSITION,
                provider.moveTo(9, before.revision(), 4).outcome());
        assertEquals(3, manager.position);
        assertEquals(0, manager.moveCalls);
    }

    @Test
    void unchangedHistoryReusesTheDecodedDetailWithoutDecodingAgain() {
        final Manager manager = new Manager();
        final GroupEntry settled = groupedEntry("Settled edit");
        final GroupEntry tip = groupedEntry("Open edit");
        manager.entries.add(settled);
        manager.entries.add(tip);
        manager.position = 2;
        Host.document = new Document(manager);
        final VerifiedMemberResolver resolver = groupDecodingResolver();
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver), () -> 3);

        final HistorySnapshot first = provider.snapshot();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, first.entries().get(0).detailLevel());
        assertEquals(1, settled.decodeReads(), "the first projection decodes the entry");
        assertEquals(1, provider.memoizedDetailCount(), "only the entry below the tip is memoized");

        final HistorySnapshot same = provider.snapshot();

        assertSame(first, same, "unchanged history reuses its immutable projection");
        assertEquals(1, settled.decodeReads(), "an unchanged entry below the tip is not decoded again");
        assertEquals(2, settled.labelReads(), "mutable host labels are still re-read on every projection");
        assertEquals(2, tip.decodeReads(), "the undo tip is projected from the host on every poll");
        assertEquals(3, same.generation());
        assertEquals(1, same.revision());
    }

    @Test
    void aGroupThatIsStillReceivingChildrenIsDecodedAgain() {
        final Manager manager = new Manager();
        final GroupEntry settled = groupedEntry("Settled edit");
        manager.entries.add(settled);
        manager.entries.add(groupedEntry("Open edit"));
        manager.position = 2;
        Host.document = new Document(manager);
        final VerifiedMemberResolver resolver = groupDecodingResolver();
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver), () -> 3);
        final HistorySnapshot first = provider.snapshot();
        assertEquals(1, settled.decodeReads());

        settled.children.add(new Entry("Later child", true));
        final HistorySnapshot grown = provider.snapshot();

        assertEquals(2, settled.decodeReads(), "a group that grew after the projection is decoded again");
        assertEquals(
                2,
                grown.entries().get(0).detail().group().orElseThrow().children().size());
        assertEquals(first.revision() + 1, grown.revision());
    }

    @Test
    void aRenamedEntryDropsItsMemoizedDetail() {
        final Manager manager = new Manager();
        final GroupEntry settled = groupedEntry("Before");
        manager.entries.add(settled);
        manager.entries.add(groupedEntry("Open edit"));
        manager.position = 2;
        Host.document = new Document(manager);
        final VerifiedMemberResolver resolver = groupDecodingResolver();
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver), () -> 3);
        final HistorySnapshot before = provider.snapshot();
        assertEquals("Before", before.entries().get(0).detail().summary());

        ((Entry) settled).label = "After";
        final HistorySnapshot renamed = provider.snapshot();
        assertEquals("After", renamed.entries().get(0).label());
        assertEquals("After", renamed.entries().get(0).detail().summary(), "the detail follows the renamed entry");
        assertEquals(2, settled.decodeReads(), "a renamed entry is decoded again");
        assertEquals(before.revision() + 1, renamed.revision());
    }

    @Test
    void metadataRegisteredAfterTheProjectionReplacesTheMemoizedDetail() {
        final Manager manager = new Manager();
        final GroupEntry settled = groupedEntry("Grouped edit");
        manager.entries.add(settled);
        manager.entries.add(groupedEntry("Open edit"));
        manager.position = 2;
        Host.document = new Document(manager);
        final VerifiedMemberResolver resolver = groupDecodingResolver();
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver), () -> 3);
        final HistorySnapshot decoded = provider.snapshot();

        EditorHistoryMetadataRegistry.registerDetail(
                settled,
                HistoryEntryDetail.labelOnly(
                        "Captured semantic summary", HistoryOrigin.hostUnattributed(), "history.detail.captured"));
        final HistorySnapshot captured = provider.snapshot();

        assertEquals(
                "Captured semantic summary", captured.entries().get(0).detail().summary());
        assertEquals(
                decoded.entries().get(0).entryId(), captured.entries().get(0).entryId());
        assertEquals(decoded.revision() + 1, captured.revision());
    }

    @Test
    void appendingEntriesDecodesOnlyTheNewOnes() {
        final Manager manager = new Manager();
        final GroupEntry first = groupedEntry("First");
        final GroupEntry second = groupedEntry("Second");
        final GroupEntry third = groupedEntry("Third");
        manager.entries.add(first);
        manager.entries.add(second);
        manager.entries.add(third);
        manager.position = 3;
        Host.document = new Document(manager);
        final VerifiedMemberResolver resolver = groupDecodingResolver();
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver), () -> 4);
        provider.snapshot();
        assertEquals(1, first.decodeReads());
        assertEquals(1, second.decodeReads());

        final GroupEntry fourth = groupedEntry("Fourth");
        manager.entries.add(fourth);
        manager.position = 4;
        final HistorySnapshot appended = provider.snapshot();

        assertEquals(4, appended.entries().size());
        assertEquals(1, first.decodeReads(), "an append keeps the settled prefix memoized");
        assertEquals(1, second.decodeReads(), "an append keeps the settled prefix memoized");
        assertEquals(2, third.decodeReads(), "only the entry that stopped being the tip is decoded again");
        assertEquals(1, fourth.decodeReads(), "the new tip is decoded");
        assertEquals(2, appended.revision());

        final GroupEntry fifth = groupedEntry("Fifth");
        manager.entries.add(fifth);
        manager.position = 5;
        final HistorySnapshot appendedAgain = provider.snapshot();

        assertEquals(1, first.decodeReads());
        assertEquals(1, second.decodeReads());
        assertEquals(2, third.decodeReads(), "the previous tip is memoized once it is settled");
        assertEquals(2, fourth.decodeReads());
        assertEquals(1, fifth.decodeReads());
        assertEquals(3, appendedAgain.revision());
    }

    @Test
    void aCursorMoveKeepsSettledMemosAndReprojectsTheTail() {
        final Manager manager = new Manager();
        final GroupEntry first = groupedEntry("First");
        final GroupEntry second = groupedEntry("Second");
        final GroupEntry third = groupedEntry("Third");
        final GroupEntry fourth = groupedEntry("Fourth");
        manager.entries.add(first);
        manager.entries.add(second);
        manager.entries.add(third);
        manager.entries.add(fourth);
        manager.position = 4;
        Host.document = new Document(manager);
        final VerifiedMemberResolver resolver = groupDecodingResolver();
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver), () -> 4);
        final HistorySnapshot applied = provider.snapshot();
        assertEquals(1, first.decodeReads());

        manager.position = 2;
        final HistorySnapshot undone = provider.snapshot();

        assertEquals(2, undone.position());
        assertEquals(applied.revision() + 1, undone.revision());
        assertEquals(1, first.decodeReads(), "the entry below the new cursor keeps its memo");
        assertEquals(2, second.decodeReads(), "the entry under the cursor is projected again");
        assertEquals(2, third.decodeReads(), "the redo tail is projected again");
    }

    @Test
    void aTruncatedRedoTailIsPruned() {
        final Manager manager = new Manager();
        final GroupEntry first = groupedEntry("First");
        final GroupEntry second = groupedEntry("Second");
        final GroupEntry third = groupedEntry("Third");
        manager.entries.add(first);
        manager.entries.add(second);
        manager.entries.add(third);
        manager.position = 3;
        Host.document = new Document(manager);
        final VerifiedMemberResolver resolver = groupDecodingResolver();
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver), () -> 4);
        provider.snapshot();
        assertEquals(2, provider.memoizedDetailCount());

        manager.entries.subList(1, manager.entries.size()).clear();
        manager.entries.add(groupedEntry("After undo"));
        manager.position = 2;
        final HistorySnapshot truncated = provider.snapshot();

        assertEquals(2, truncated.entries().size());
        assertEquals(1, first.decodeReads(), "the surviving settled entry keeps its memo");
        assertEquals(1, provider.memoizedDetailCount(), "the memos of the dropped redo tail are pruned");
    }

    @Test
    void generationRevisionAndBindingTrackEveryChangeWithMemoizedDetails() {
        final Manager manager = new Manager();
        final GroupEntry first = groupedEntry("First");
        manager.entries.add(first);
        manager.entries.add(groupedEntry("Second"));
        manager.position = 2;
        Host.document = new Document(manager);
        final AtomicLong generation = new AtomicLong(5);
        final VerifiedMemberResolver resolver = groupDecodingResolver();
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver), generation::get);

        final HistorySnapshot initial = provider.snapshot();
        assertEquals(5, initial.generation());
        assertEquals(1, initial.revision());
        assertSame(initial, provider.snapshot());
        assertEquals(1, provider.snapshot().revision());
        assertEquals(1, first.decodeReads());

        manager.position = 0;
        final HistorySnapshot undone = provider.snapshot();
        assertEquals(5, undone.generation());
        assertEquals(2, undone.revision());
        assertEquals(0, undone.position());
        assertFalse(undone.canUndo());
        assertEquals(2, first.decodeReads(), "a fully undone history has no settled entry to reuse");

        final GroupEntry appended = groupedEntry("Third");
        manager.entries.add(appended);
        manager.position = 3;
        final HistorySnapshot grown = provider.snapshot();
        assertEquals(3, grown.revision());
        assertEquals(3, grown.entries().size());
        assertEquals(1, appended.decodeReads());
        assertEquals(3, first.decodeReads());

        Host.document = new Document(manager);
        final HistorySnapshot rebound = provider.snapshot();
        assertEquals(4, rebound.revision());
        assertNotEquals(grown.documentBindingId(), rebound.documentBindingId());
        assertEquals(grown.managerBindingId(), rebound.managerBindingId());
        assertEquals(4, first.decodeReads(), "a document switch drops every memoized detail");

        generation.set(6);
        final HistorySnapshot next = provider.snapshot();
        assertEquals(6, next.generation());
        assertEquals(1, next.revision(), "a new generation restarts the revision sequence");
        assertEquals(5, first.decodeReads());
    }

    private static GroupEntry groupedEntry(final String label) {
        final GroupEntry entry = new GroupEntry(label);
        entry.children.add(new Entry("Unknown child", true));
        return entry;
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
                List.of(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class))),
                Host.class.getClassLoader());
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver5303), () -> 11);

        assertEquals(
                HistorySnapshot.Availability.UNAVAILABLE, provider.snapshot().availability());
        assertEquals(
                HistoryMoveResult.Outcome.UNAVAILABLE, provider.moveTo(11, 0, 0).outcome());
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void generationReplacementAndMissingDocumentFailClosed() {
        final AtomicLong generation = new AtomicLong(0);
        final EditorHistorySnapshotProvider provider =
                new EditorHistorySnapshotProvider(() -> Optional.of(resolver()), generation::get);

        assertEquals(
                HistorySnapshot.Availability.UNAVAILABLE, provider.snapshot().availability());
        generation.set(2);
        Host.document = null;
        assertEquals(
                HistorySnapshot.Availability.UNAVAILABLE, provider.snapshot().availability());
        assertEquals(
                HistoryMoveResult.Outcome.UNAVAILABLE, provider.moveTo(2, 0, 0).outcome());
        assertEquals(
                "history.move.unavailable",
                provider.moveTo(2, 0, 0).diagnosticId().orElseThrow());
    }

    private static VerifiedMemberResolver resolver() {
        return historyResolver(
                Set.of("cubism.editor-model.read", "cubism.editor-history.read", "cubism.editor-history.move"),
                List.of());
    }

    /** Admits the exact semantic-group family so a projection actually decodes a native entry. */
    private static VerifiedMemberResolver groupDecodingResolver() {
        return historyResolver(
                Set.of(
                        "cubism.editor-model.read",
                        "cubism.editor-history.read",
                        "cubism.editor-history.move",
                        "cubism.editor-history.semantic-read"),
                List.of(
                        StaticSelector.classSelector(
                                "cubism.editor-history.semantic.group.class", internal(GroupEntry.class)),
                        method(
                                "cubism.editor-history.semantic.group.edits",
                                GroupEntry.class,
                                "edits",
                                "()Ljava/util/List;")));
    }

    private static VerifiedMemberResolver historyResolver(
            final Set<String> capabilities, final List<StaticSelector> extraSelectors) {
        final ArrayList<StaticSelector> selectors = new ArrayList<>(List.of(
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
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)),
                method(
                        "cubism.editor-history.document.undo-manager",
                        Document.class,
                        "undoManager",
                        desc(Manager.class)),
                StaticSelector.classSelector("cubism.editor-history.manager.class", internal(Manager.class)),
                method("cubism.editor-history.manager.entries", Manager.class, "entries", "()Ljava/util/List;"),
                method("cubism.editor-history.manager.position", Manager.class, "position", "()I"),
                method("cubism.editor-history.manager.can-undo", Manager.class, "canUndo", "()Z"),
                method("cubism.editor-history.manager.can-redo", Manager.class, "canRedo", "()Z"),
                method("cubism.editor-history.manager.move-to", Manager.class, "moveTo", "(I)V"),
                StaticSelector.classSelector("cubism.editor-history.entry.class", internal(Entry.class)),
                method(
                        "cubism.editor-history.entry.presentation-name",
                        Entry.class,
                        "presentationName",
                        "()Ljava/lang/String;"),
                method("cubism.editor-history.entry.significant", Entry.class, "significant", "()Z"),
                method("cubism.editor-history.semantic.group.count", Entry.class, "count", "()I")));
        selectors.addAll(extraSelectors);
        return TestVerifiedResolvers.create(
                "5.3.02", "adapter.editor-model.readwrite", capabilities, selectors, Host.class.getClassLoader());
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
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

        public static Host instance() {
            return new Host();
        }

        public Document currentDocument() {
            return document;
        }
    }

    public static final class Document {
        private final Manager manager;

        Document(final Manager manager) {
            this.manager = manager;
        }

        public Manager undoManager() {
            return manager;
        }
    }

    public static final class Manager {
        private final List<Entry> entries = new ArrayList<>();
        private int position;
        private int moveCalls;
        private Runnable afterCanUndo = () -> {};

        public List<Entry> entries() {
            return entries;
        }

        public int position() {
            return position;
        }

        public boolean canUndo() {
            final boolean result = position > 0;
            afterCanUndo.run();
            afterCanUndo = () -> {};
            return result;
        }

        public boolean canRedo() {
            return position < entries.size();
        }

        public void moveTo(final int target) {
            moveCalls++;
            position = target;
        }
    }

    public static class Entry {
        private String label;
        private final boolean significant;
        private int groupCount;
        private int labelReads;

        Entry(final String label, final boolean significant) {
            this.label = label;
            this.significant = significant;
        }

        public String presentationName() {
            labelReads++;
            return label;
        }

        public boolean significant() {
            return significant;
        }

        public int count() {
            return groupCount;
        }

        int labelReads() {
            return labelReads;
        }
    }

    /** Exact-class native group whose children are appended after the first projection. */
    public static final class GroupEntry extends Entry {
        private final List<Entry> children = new ArrayList<>();
        private int decodeReads;

        GroupEntry(final String label) {
            super(label, true);
        }

        public List<Entry> edits() {
            decodeReads++;
            return children;
        }

        @Override
        public int count() {
            return children.size();
        }

        int decodeReads() {
            return decodeReads;
        }
    }
}
