package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorHistoryMetadataRegistryTest {

    @Test
    void capturedDetailIsAuthoritativeAndPreservesStableIdentity() {
        final Object nativeEntry = new Object();
        final HistoryAction action = action("0.0", "1.0");
        final HistoryEntryDetail detail = detail("0.0", "1.0");

        final String beforeId = EditorHistoryMetadataRegistry.metadata(nativeEntry).entryId().value();
        EditorHistoryMetadataRegistry.registerTransaction(nativeEntry, "transaction-1");
        EditorHistoryMetadataRegistry.registerCaptured(nativeEntry, detail, Optional.of(action));
        final EditorHistoryMetadataRegistry.EntryMetadata metadata =
            EditorHistoryMetadataRegistry.metadata(nativeEntry);

        assertEquals(beforeId, metadata.entryId().value());
        assertEquals("transaction-1", metadata.transactionId().orElseThrow());
        assertEquals(action, metadata.action().orElseThrow());
        assertEquals(detail, metadata.detail().orElseThrow());
    }

    @Test
    void conservativeAppendRegistrationRejectsAmbiguousChanges() {
        final Object first = new Object();
        final Object appended = new Object();
        final Object replacement = new Object();
        final HistoryEntryDetail detail = detail("0.0", "1.0");

        EditorHistoryMetadataRegistry.registerAppended(
            List.of(first),
            List.of(replacement, appended),
            detail,
            Optional.of(action("0.0", "1.0"))
        );

        assertTrue(EditorHistoryMetadataRegistry.metadata(appended).detail().isEmpty());
    }

    @Test
    void nativeEntryIdentityNotEqualsControlsMetadataIdentity() {
        final EqualEntry first = new EqualEntry();
        final EqualEntry second = new EqualEntry();

        assertNotEquals(
            EditorHistoryMetadataRegistry.metadata(first).entryId(),
            EditorHistoryMetadataRegistry.metadata(second).entryId()
        );
    }

    @Test
    void observedDetailIsNotAuthorshipAndYieldsToCapturedDetail() {
        final Object nativeEntry = new Object();
        final HistoryEntryDetail observed = detail("0.0", "1.0");
        final HistoryEntryDetail captured = detail("1.0", "2.0");

        EditorHistoryMetadataRegistry.registerObserved(nativeEntry, observed);

        assertFalse(
            EditorHistoryMetadataRegistry.claimsProvenance(nativeEntry),
            "a commit-time observation is host-attributed, not Turboism authorship"
        );
        assertEquals(observed, EditorHistoryMetadataRegistry.observed(nativeEntry).orElseThrow());
        assertTrue(EditorHistoryMetadataRegistry.metadata(nativeEntry).detail().isEmpty());

        EditorHistoryMetadataRegistry.registerCaptured(
            nativeEntry, captured, Optional.of(action("1.0", "2.0")));

        assertTrue(EditorHistoryMetadataRegistry.claimsProvenance(nativeEntry));
        assertEquals(captured, EditorHistoryMetadataRegistry.metadata(nativeEntry).detail().orElseThrow());
        assertEquals(observed, EditorHistoryMetadataRegistry.observed(nativeEntry).orElseThrow());
    }

    @Test
    void observedDetailSurvivesCompatibilityAnnotation() {
        final Object nativeEntry = new Object();
        final HistoryEntryDetail observed = detail("0.0", "1.0");

        EditorHistoryMetadataRegistry.registerObserved(nativeEntry, observed);
        EditorHistoryMetadataRegistry.register(nativeEntry, action("0.0", "1.0"));

        assertEquals(observed, EditorHistoryMetadataRegistry.observed(nativeEntry).orElseThrow());
    }

    private static HistoryAction action(final String before, final String after) {
        return new HistoryAction(
            HistoryAction.Kind.SET_PARAMETER_VALUE,
            "PARAMETER",
            "ParamAngleX",
            "value",
            Optional.of(before),
            Optional.of(after),
            HistoryAction.DetailLevel.FULL
        );
    }

    private static HistoryEntryDetail detail(final String before, final String after) {
        return new HistoryEntryDetail(
            "Set parameter",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.turboism("dev.turboism.runtime", "cubism.parameter.set-value"),
            List.of(new HistoryTarget("PARAMETER", Optional.of("ParamAngleX"), Optional.empty())),
            List.of(HistoryChange.set(0, "value", before, after)),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static final class EqualEntry {
        @Override
        public boolean equals(final Object other) {
            return other instanceof EqualEntry;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
