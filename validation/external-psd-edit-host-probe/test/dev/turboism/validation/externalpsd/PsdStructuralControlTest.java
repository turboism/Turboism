package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryId;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import java.util.List;
import java.util.Optional;

/** Pure history admission regressions; never substitutes for native/SDK host comparison. */
public final class PsdStructuralControlTest {
    private PsdStructuralControlTest() { }

    public static void main(final String[] args) {
        final HistorySnapshot empty = snapshot("doc", "manager", 1, 0, List.of());
        final HistorySnapshot first = snapshot("doc", "manager", 1, 1, List.of("a"));
        final HistorySnapshot second = snapshot("doc", "manager", 1, 2, List.of("a", "b"));
        check(!PsdStructuralControl.oneReplacementEntry(empty, empty), "wait before commit");
        check(PsdStructuralControl.oneReplacementEntry(empty, first), "first native commit");
        check(PsdStructuralControl.oneReplacementEntry(first, second), "one commit preserves prefix");
        reject(() -> PsdStructuralControl.oneReplacementEntry(empty, second));
        reject(() -> PsdStructuralControl.oneReplacementEntry(first,
            snapshot("doc", "manager", 1, 1, List.of("other"))));
        reject(() -> PsdStructuralControl.oneReplacementEntry(first,
            snapshot("doc", "other", 1, 2, List.of("a", "b"))));
        reject(() -> PsdStructuralControl.oneReplacementEntry(first,
            snapshot("other", "manager", 1, 2, List.of("a", "b"))));
        reject(() -> PsdStructuralControl.oneReplacementEntry(first,
            snapshot("doc", "manager", 2, 2, List.of("a", "b"))));
        reject(() -> PsdStructuralControl.oneReplacementEntry(first,
            snapshot("doc", "manager", 1, 2, List.of("a", "a"))));
        reject(() -> PsdStructuralControl.oneReplacementEntry(first, empty));
        reject(() -> PsdStructuralControl.oneReplacementEntry(
            snapshot("doc", "manager", 1, 0, List.of("a")), second));
        reject(() -> PsdStructuralControl.requireHistory(HistorySnapshot.unavailable()));
        reject(() -> PsdStructuralControl.requireHistory(new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE, 1, 1, 1,
            List.of(new HistoryEntry(0, "label only", true)), true, false, "doc", "manager")));
        for (final String variant : List.of("add", "delete", "merge", "canvas")) {
            check(PsdStructuralControl.variantSha256(variant).matches("[0-9a-f]{64}"), "fixed source digest");
        }
        try {
            PsdStructuralControl.variantSha256("unknown");
            throw new AssertionError("unknown variant admitted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: PsdStructuralControlTest");
    }

    private static HistorySnapshot snapshot(final String document, final String manager,
        final long generation, final int position, final List<String> ids) {
        final java.util.ArrayList<HistoryEntry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < ids.size(); index++) entries.add(new HistoryEntry(index,
            "Import PSD", true, Optional.empty(), Optional.of(new HistoryEntryId(ids.get(index))),
            Optional.empty()));
        return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, generation, ids.size(),
            position, entries, position > 0, position < entries.size(), document, manager);
    }

    private static void reject(final Runnable operation) {
        try { operation.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("unsafe history admitted");
    }

    private static void check(final boolean value, final String message) {
        if (!value) throw new AssertionError(message);
    }
}
