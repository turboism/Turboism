package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import java.util.Map;

/** Admission for independently collected F5 native and SDK observations; no host writes. */
final class PsdStructuralControl {
    private PsdStructuralControl() { }

    static String variantSha256(final String variant) {
        final String sha = Map.of(
            "add", "27094b874216df76071f378e8855bfdc788e8d26ffedff13cce80b5309d4ece9",
            "delete", "db512b4f347812560f44d45fe8cc9a3c3142b1e208e3fa18ca76354298a5db36",
            "merge", "202929b36f8e357263bc318878dc3f025cd9073f9bab3cd700e42962732cb32d",
            "canvas", "e17a892ec70bdafc6f77a94d5cf6c7f7856ee9af53624e61ec4e9f2aab485493"
        ).get(variant);
        if (sha == null) throw new IllegalArgumentException("unknown structural variant: " + variant);
        return sha;
    }

    static void requireHistory(final HistorySnapshot value) {
        if (value == null || value.availability() != HistorySnapshot.Availability.AVAILABLE
            || value.documentBindingId().isBlank() || value.managerBindingId().isBlank()) {
            throw new IllegalStateException("structural control requires bound native history");
        }
        final java.util.Set<Object> identities = new java.util.HashSet<>();
        for (final var entry : value.entries()) {
            if (entry.entryId().isEmpty() || !identities.add(entry.entryId().orElseThrow())) {
                throw new IllegalStateException("structural control requires unique history entry identities");
            }
        }
    }

    /** A chooser return alone is insufficient; observe the same manager with one committed entry. */
    static boolean oneReplacementEntry(final HistorySnapshot before, final HistorySnapshot after) {
        requireHistory(before);
        requireHistory(after);
        if (!before.documentBindingId().equals(after.documentBindingId())
            || !before.managerBindingId().equals(after.managerBindingId())
            || before.generation() != after.generation()) throw new IllegalStateException(
                "structural control history binding changed");
        if (before.position() != before.entries().size()) throw new IllegalStateException(
            "structural baseline must be at the native history tip");
        if (after.entries().size() < before.entries().size()) throw new IllegalStateException(
            "structural replacement removed existing history entries");
        for (int index = 0; index < before.entries().size(); index++) {
            final var old = before.entries().get(index);
            final var current = after.entries().get(index);
            if (old.entryId().isEmpty() || !old.entryId().equals(current.entryId())) {
                throw new IllegalStateException("structural replacement changed existing history entries");
            }
        }
        if (after.position() == before.position() && after.entries().size() == before.entries().size()) {
            return false;
        }
        if (after.position() != before.position() + 1 || after.entries().size() != after.position()
            || !after.canUndo()) throw new IllegalStateException(
                "structural replacement did not produce exactly one native history entry");
        return true;
    }
}
