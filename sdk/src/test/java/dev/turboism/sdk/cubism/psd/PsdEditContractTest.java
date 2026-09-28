package dev.turboism.sdk.cubism.psd;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.plugin.Registration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PsdEditContractTest {
    private static final RawImageId SOURCE = new RawImageId("raw-source");
    private static final RawImageId AFTER = new RawImageId("raw-after");
    // These are value-test doubles, not runtime-issued capabilities or host evidence.
    private static final PsdFileRevision REVISION = new PsdFileRevision() {};
    private static final PsdEditFile FILE = new PsdEditFile() {
        @Override
        public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
            throw new UnsupportedOperationException("not runtime issued");
        }

        @Override
        public Registration observeSaves(Consumer<PsdFileRevision> listener) {
            throw new UnsupportedOperationException("not runtime issued");
        }

        @Override
        public CompletionStage<PsdFileOperationResult> stop() {
            throw new UnsupportedOperationException("not runtime issued");
        }
    };

    @Test
    void exportedValueRequiresBothHandleAndBaseline() {
        var result = export(PsdExportResult.Status.EXPORTED, Optional.of(FILE), Optional.of(REVISION));
        assertSame(FILE, result.file().orElseThrow());
        assertSame(REVISION, result.initialRevision().orElseThrow());
        assertEquals(SOURCE, result.source());
        assertThrows(
                IllegalArgumentException.class,
                () -> export(PsdExportResult.Status.EXPORTED, Optional.empty(), Optional.of(REVISION)));
        assertThrows(
                IllegalArgumentException.class,
                () -> export(PsdExportResult.Status.EXPORTED, Optional.of(FILE), Optional.empty()));
    }

    @Test
    void everyUnsuccessfulExportWithholdsHandlesAndRevisions() {
        for (var status : PsdExportResult.Status.values()) {
            if (status == PsdExportResult.Status.EXPORTED) continue;
            assertTrue(export(status, Optional.empty(), Optional.empty()).file().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> export(status, Optional.of(FILE), Optional.empty()));
            assertThrows(IllegalArgumentException.class, () -> export(status, Optional.empty(), Optional.of(REVISION)));
        }
    }

    @Test
    void appliedRequiresObservedIdentityAndConsumedRevision() {
        var result = replace(PsdReplaceResult.Status.APPLIED, Optional.of(AFTER), Optional.of(REVISION));
        assertEquals(AFTER, result.after().orElseThrow());
        assertSame(REVISION, result.consumedRevision().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> replace(PsdReplaceResult.Status.APPLIED, Optional.empty(), Optional.of(REVISION)));
        assertThrows(
                IllegalArgumentException.class,
                () -> replace(PsdReplaceResult.Status.APPLIED, Optional.of(AFTER), Optional.empty()));
    }

    @Test
    void unsuccessfulReplacementCannotConsumeRevisionIncludingPartialFailure() {
        for (var status : PsdReplaceResult.Status.values()) {
            if (status == PsdReplaceResult.Status.APPLIED) continue;
            var result = replace(status, Optional.empty(), Optional.empty());
            assertTrue(result.consumedRevision().isEmpty());
            assertThrows(
                    IllegalArgumentException.class, () -> replace(status, Optional.of(AFTER), Optional.of(REVISION)));
        }
    }

    @Test
    void partialFailureMayCarryObservationWithoutClaimingConsumption() {
        var result = replace(PsdReplaceResult.Status.PARTIAL_FAILURE, Optional.of(AFTER), Optional.empty());
        assertEquals(AFTER, result.after().orElseThrow());
        assertTrue(result.consumedRevision().isEmpty());
    }

    @Test
    void suppliedSnapshotMustBeAvailableAndContainObservedTarget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PsdReplaceResult(
                        PsdReplaceResult.Status.APPLIED,
                        "",
                        SOURCE,
                        Optional.of(AFTER),
                        Optional.of(REVISION),
                        Optional.of(TextureRelationsSnapshot.unavailable())));
        var emptyGraph = new TextureRelationsSnapshot(
                TextureRelationsSnapshot.Availability.AVAILABLE,
                "session",
                1,
                1,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PsdReplaceResult(
                        PsdReplaceResult.Status.APPLIED,
                        "",
                        SOURCE,
                        Optional.of(AFTER),
                        Optional.of(REVISION),
                        Optional.of(emptyGraph)));
    }

    @Test
    void valueFieldsRejectNullRatherThanImplyUnavailable() {
        assertThrows(
                NullPointerException.class,
                () -> new PsdExportResult(null, "", SOURCE, Optional.empty(), Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new PsdExportResult(PsdExportResult.Status.FAILED, "", null, Optional.empty(), Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new PsdExportResult(PsdExportResult.Status.FAILED, "", SOURCE, null, Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new PsdReplaceResult(
                        PsdReplaceResult.Status.FAILED, "", SOURCE, Optional.empty(), null, Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new PsdFileOperationResult(PsdFileOperationResult.Status.STOPPED, null));
    }

    @Test
    void handlesExposeOnlyFrozenOperationsAndOpaqueRevision() throws Exception {
        assertEquals(
                Set.of("openInDefaultApplication", "observeSaves", "stop"),
                Arrays.stream(PsdEditFile.class.getDeclaredMethods())
                        .map(m -> m.getName())
                        .collect(Collectors.toSet()));
        assertEquals(0, PsdFileRevision.class.getDeclaredMethods().length);
        assertEquals(
                CompletionStage.class,
                PsdEditFile.class.getMethod("openInDefaultApplication").getReturnType());
        assertEquals(CompletionStage.class, PsdEditFile.class.getMethod("stop").getReturnType());
        assertEquals(
                Registration.class,
                PsdEditFile.class.getMethod("observeSaves", Consumer.class).getReturnType());
        for (var type : List.of(
                PsdEditFile.class,
                PsdFileRevision.class,
                PsdExportResult.class,
                PsdReplaceResult.class,
                PsdFileOperationResult.class)) {
            for (var method : type.getDeclaredMethods()) {
                String surface = method.toGenericString();
                for (var forbidden :
                        List.of("java.nio.file.Path", "java.io.File", "java.net.URI", "byte[]", "com.live2d")) {
                    assertFalse(surface.contains(forbidden), surface);
                }
            }
        }
    }

    private static PsdExportResult export(
            PsdExportResult.Status status, Optional<PsdEditFile> file, Optional<PsdFileRevision> revision) {
        return new PsdExportResult(status, "diagnostic", SOURCE, file, revision);
    }

    private static PsdReplaceResult replace(
            PsdReplaceResult.Status status, Optional<RawImageId> after, Optional<PsdFileRevision> revision) {
        return new PsdReplaceResult(status, "diagnostic", SOURCE, after, revision, Optional.empty());
    }
}
