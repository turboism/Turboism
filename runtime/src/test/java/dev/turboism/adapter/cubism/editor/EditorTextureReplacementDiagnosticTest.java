package dev.turboism.adapter.cubism.editor;

import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelImageGroupRelation;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawImageDetails;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTextureReplacementDiagnosticTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY);
        System.clearProperty("turboism.editorObjectValidation.trace");
        System.clearProperty("turboism.home");
    }

    @Test
    void defaultOffDoesNotValidateOrWriteReplacementObservation() {
        System.setProperty("turboism.home", tempDir.toString());

        assertTrue(EditorTextureReplacementDiagnostic.begin(
            "session-a",
            null,
            null,
            new RawImageId("old"),
            EditorTextureReplacementDiagnostic.RawIdentity.unavailable("not-read-while-disabled"),
            TextureRelationsSnapshot.unavailable()
        ).isEmpty());
        assertEquals(
            "diagnostic-disabled",
            EditorTextureReplacementDiagnostic.resolveIncomingRaw(null, null).cause()
        );

        assertFalse(Files.exists(diagnosticArtifact()));
    }

    @Test
    void enabledPathCorrelatesPreAndPostAndRecordsReviewedRelationFields() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final Object document = new Object();
        final Object model = new Object();
        final TextureRelationsSnapshot before = snapshot("old", true);
        final TextureRelationsSnapshot after = snapshot("incoming", true);
        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                "session-a",
                document,
                model,
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                before
            ).orElseThrow();

        session.finish(after, nativeReturned(), null);
        session.finish(after, nativeReturned(), "duplicate-finish-must-be-ignored");

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertEquals(field(lines.get(0), "correlation"), field(lines.get(1), "correlation"));
        assertEquals(field(lines.get(0), "documentIdentity"), field(lines.get(1), "documentIdentity"));
        assertEquals(field(lines.get(0), "modelIdentity"), field(lines.get(1), "modelIdentity"));
        assertEquals(field(lines.get(0), "currentGuardBinding"), field(lines.get(1), "currentGuardBinding"));
        assertTrue(lines.get(0).contains("phase=pre"));
        assertTrue(lines.get(1).contains("phase=post"));
        assertTrue(lines.get(0).contains("currentGuardPrePassed=PASSED_BY_CALLER"));
        assertTrue(lines.get(1).contains("currentGuardPrePassed=true"));
        assertTrue(lines.get(1).contains("currentGuardPostPassed=true"));
        assertTrue(lines.get(0).contains("observation=AVAILABLE"));
        assertTrue(lines.get(1).contains("observation=AVAILABLE"));
        assertTrue(lines.get(0).contains("hasLayerInputData:true"));
        assertTrue(lines.get(0).contains("currentImageGuid:old"));
        assertTrue(lines.get(0).contains("selectorKeys:old"));
        assertTrue(lines.get(0).contains("containsOldRaw:true"));
        assertTrue(lines.get(0).contains("groups=name:Group_A"));
        assertTrue(lines.get(0).contains("artPathExclusion=UNAVAILABLE:reviewed-alias-not-admitted"));
        assertTrue(lines.get(0).contains("nativeCompletionCallback=UNAVAILABLE:reviewed-alias-not-admitted"));
        assertTrue(lines.get(1).contains("currentImageGuid:incoming"));
        assertTrue(lines.get(1).contains("rawWrappers=id:old_present:true_replaced:true"));
        assertTrue(lines.get(1).contains("id:incoming_present:true_replaced:true"));
        assertTrue(lines.get(1).contains("nativeReturned=true"));
    }

    @Test
    void missingIncomingIdentityIsRecordedUnavailableRatherThanComposed() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                "session-a",
                new Object(),
                new Object(),
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.unavailable("guid-not-readable"),
                snapshot("old", true)
            ).orElseThrow();
        session.finish(snapshot("incoming", true), nativeReturned(), null);

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("incomingRawStatus=UNAVAILABLE"));
        assertTrue(lines.get(0).contains("incomingRawCause=guid-not-readable"));
        assertTrue(lines.get(0).contains("observation=UNAVAILABLE"));
        assertTrue(lines.get(1).contains("observation=UNAVAILABLE"));
        assertTrue(lines.get(1).contains("observationCause=guid-not-readable"));
    }

    @Test
    void relationFromAnotherSessionIsUnavailableInsteadOfBeingJoinedToThisCall() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                "session-a",
                new Object(),
                new Object(),
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                snapshot("other-session", "old", true)
            ).orElseThrow();
        session.finish(snapshot("other-session", "incoming", true), nativeReturned(), null);

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("observation=UNAVAILABLE"));
        assertTrue(lines.get(0).contains("observationCause=relation-binding-mismatch"));
        assertTrue(lines.get(1).contains("observation=UNAVAILABLE"));
        assertTrue(lines.get(1).contains("observationCause=relation-binding-mismatch"));
    }

    @Test
    void unavailablePostKeepsNativeReturnFactsButDoesNotClaimRelationEvidence() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                "session-a",
                new Object(),
                new Object(),
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                snapshot("old", true)
            ).orElseThrow();
        session.finish(TextureRelationsSnapshot.unavailable(), nativeReturned(), "post-not-available");

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("observation=UNAVAILABLE"));
        assertTrue(lines.get(1).contains("observationCause=post-not-available"));
        assertTrue(lines.get(1).contains("nativeStatus=NATIVE_RETURNED_UNVERIFIED"));
        assertTrue(lines.get(1).contains("nativeInvocationAttempted=true"));
        assertTrue(lines.get(1).contains("nativeReturned=true"));
    }

    @Test
    void nativeOperationIsInvokedOnceAndItsExceptionIsNotReplaced() {
        final java.util.concurrent.atomic.AtomicInteger invocations =
            new java.util.concurrent.atomic.AtomicInteger();
        final EditorRawImagePsdReplaceAccess.ReplaceResult result = nativeReturned();

        assertSame(
            result,
            EditorTextureReplacementDiagnostic.invokeNativeOnce(() -> {
                invocations.incrementAndGet();
                return result;
            })
        );
        assertEquals(1, invocations.get());

        final IllegalStateException failure = new IllegalStateException("native-root-cause");
        final IllegalStateException propagated = assertThrows(
            IllegalStateException.class,
            () -> EditorTextureReplacementDiagnostic.invokeNativeOnce(() -> {
                invocations.incrementAndGet();
                throw failure;
            })
        );
        assertSame(failure, propagated);
        assertEquals(2, invocations.get());
    }

    @Test
    void diagnosticLineAndValuesRemainBounded() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final String oversized = "x".repeat(100_000);

        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                oversized,
                new Object(),
                new Object(),
                new RawImageId(oversized),
                EditorTextureReplacementDiagnostic.RawIdentity.available(oversized),
                snapshot("old", true)
            ).orElseThrow();
        session.finish(snapshot("incoming", true), nativeReturned(), null);

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.length() <= EditorTextureReplacementDiagnostic.MAX_LINE_LENGTH));
        assertTrue(lines.stream().allMatch(line -> !line.contains(oversized)));
    }

    @Test
    void partialNativeFailureRemainsFailureAndIsNotReportedAsApplied() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                "session-a",
                new Object(),
                new Object(),
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                snapshot("old", true)
            ).orElseThrow();
        final EditorRawImagePsdReplaceAccess.ReplaceResult failure =
            new EditorRawImagePsdReplaceAccess.ReplaceResult(
                EditorRawImagePsdReplaceAccess.ReplaceStatus.PARTIAL_FAILURE,
                true,
                true,
                true,
                false,
                EditorRawImagePsdReplaceAccess.MutationState.UNKNOWN,
                true,
                true,
                EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.NATIVE_INVOCATION,
                "java.lang.IllegalStateException",
                "native root cause"
            );
        session.finish(null, failure, "post-not-read-after-native-failure");

        final String line = Files.readAllLines(diagnosticArtifact()).get(1);
        assertTrue(line.contains("nativeStatus=PARTIAL_FAILURE"));
        assertTrue(line.contains("nativeInvocationAttempted=true"));
        assertTrue(line.contains("nativeReturned=false"));
        assertTrue(line.contains("nativeFailurePhase=NATIVE_INVOCATION"));
        assertTrue(line.contains("observation=UNAVAILABLE"));
    }

    @Test
    void postRelationCannotLookLikeNativeEvidenceWhenNativeWasNotAttempted() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                "session-a",
                new Object(),
                new Object(),
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                snapshot("old", true)
            ).orElseThrow();
        final EditorRawImagePsdReplaceAccess.ReplaceResult notAttempted =
            new EditorRawImagePsdReplaceAccess.ReplaceResult(
                EditorRawImagePsdReplaceAccess.ReplaceStatus.STALE_BEFORE_NATIVE,
                false,
                false,
                false,
                false,
                EditorRawImagePsdReplaceAccess.MutationState.NOT_ATTEMPTED,
                false,
                false,
                EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.CURRENT_GUARD_BEFORE_NATIVE,
                "STALE",
                "stale before native"
            );
        session.finish(snapshot("incoming", true), notAttempted, null);

        final String line = Files.readAllLines(diagnosticArtifact()).get(1);
        assertTrue(line.contains("observation=UNAVAILABLE"));
        assertTrue(line.contains("observationCause=native-not-invoked"));
        assertTrue(line.contains("nativeInvocationAttempted=false"));
        assertTrue(line.contains("nativeReturned=false"));
    }

    @Test
    void postRelationFromAnotherModelGenerationIsUnavailable() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                "session-a",
                new Object(),
                new Object(),
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                snapshot("session-a", "old", true, 7, 1)
            ).orElseThrow();
        session.finish(
            snapshot("session-a", "incoming", true, 8, 2),
            nativeReturned(),
            null
        );

        final String line = Files.readAllLines(diagnosticArtifact()).get(1);
        assertTrue(line.contains("observation=UNAVAILABLE"));
        assertTrue(line.contains("observationCause=relation-generation-mismatch"));
    }

    private static EditorRawImagePsdReplaceAccess.ReplaceResult nativeReturned() {
        return new EditorRawImagePsdReplaceAccess.ReplaceResult(
            EditorRawImagePsdReplaceAccess.ReplaceStatus.NATIVE_RETURNED_UNVERIFIED,
            true,
            true,
            true,
            true,
            EditorRawImagePsdReplaceAccess.MutationState.UNKNOWN,
            false,
            true,
            EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.NONE,
            null,
            "native returned"
        );
    }

    private static TextureRelationsSnapshot snapshot(
        final String currentRaw,
        final boolean replaced
    ) {
        return snapshot("session-a", currentRaw, replaced, 7, currentRaw.equals("old") ? 1 : 2);
    }

    private static TextureRelationsSnapshot snapshot(
        final String binding,
        final String currentRaw,
        final boolean replaced
    ) {
        return snapshot(binding, currentRaw, replaced, 7, currentRaw.equals("old") ? 1 : 2);
    }

    private static TextureRelationsSnapshot snapshot(
        final String binding,
        final String currentRaw,
        final boolean replaced,
        final long generation,
        final long revision
    ) {
        final RawImageId old = new RawImageId("old");
        final RawImageId incoming = new RawImageId("incoming");
        final RawImageId current = new RawImageId(currentRaw);
        final ModelImageId modelId = new ModelImageId("model-a");
        final ModelImageEntry entry = new ModelImageEntry() {
            @Override public ModelImageId id() { return modelId; }
            @Override public String name() { return "Image"; }
            @Override public int width() { return 100; }
            @Override public int height() { return 100; }
        };
        final ModelImageRelation model = new ModelImageRelation(
            modelId,
            entry,
            List.of(old, incoming),
            Optional.of(current),
            Map.of(
                current,
                List.of(new RawLayerBinding(
                    current,
                    new RawLayerId("layer"),
                    0,
                    RawLayerBinding.DetailAvailability.AVAILABLE,
                    RawLayerBinding.DetailAvailability.AVAILABLE
                ))
            ),
            List.of()
        );
        final ModelImageGroup group = new ModelImageGroup() {
            @Override public String groupName() { return "Group A"; }
            @Override public String memo() { return "memo"; }
            @Override public List<ModelImageEntry> modelImages() { return List.of(entry); }
        };
        return new TextureRelationsSnapshot(
            TextureRelationsSnapshot.Availability.AVAILABLE,
            binding,
            generation,
            revision,
            currentRaw.equals("old")
                ? List.of(raw(old, replaced))
                : List.of(raw(old, replaced), raw(incoming, replaced)),
            List.of(model),
            List.of(new ModelImageGroupRelation(
                group,
                List.of(modelId),
                List.of(old, incoming),
                Optional.of(true)
            )),
            List.of()
        );
    }

    private static RawImageDetails raw(final RawImageId id, final boolean replaced) {
        final RawTexture texture = new RawTexture() {
            @Override public RawImageId id() { return id; }
            @Override public String name() { return id.value(); }
            @Override public int width() { return 100; }
            @Override public int height() { return 100; }
        };
        return new RawImageDetails(
            texture,
            RawImageDetails.SourceKind.PSD,
            List.of(),
            replaced,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static String field(final String line, final String key) {
        for (final String part : line.split(" ")) {
            if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        }
        return "";
    }

    private Path diagnosticArtifact() {
        return tempDir.resolve("logs").resolve(EditorTextureReplacementDiagnostic.ARTIFACT);
    }
}
