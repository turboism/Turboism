package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTextureRelationsTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.runtime.psd.PsdExportHost;
import dev.turboism.core.runtime.psd.PsdReplaceHost;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelImageGroupRelation;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawImageDetails;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorTextureReplacementDiagnosticTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY);
        System.clearProperty("turboism.editorObjectValidation.trace");
        System.clearProperty("turboism.home");
        CallSiteAppController.currentDocument = null;
        CallSiteFilterEnv.hasReads = 0;
        CallSiteFilterEnv.throwOnHasRead = 0;
        CallSiteFilterEnv.throwOnHasReadLinkageError = 0;
        CallSiteLayeredImage.failIncomingGuidReadAt = 0;
        CallSiteLayeredImage.incomingGuidReads = 0;
        CallSiteLayeredImage.saveCalls = 0;
        CallSiteNativeProcess.calls = 0;
        CallSiteNativeProcess.fail = false;
        CallSiteNativeProcess.mutation = CallSiteNativeProcess.NativeMutation.CORRECT_INCOMING;
        CallSiteNativeProcess.active = null;
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
                        TextureRelationsSnapshot.unavailable())
                .isEmpty());
        assertEquals(
                "diagnostic-disabled",
                EditorTextureReplacementDiagnostic.resolveIncomingRaw(null, null)
                        .cause());

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
        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        "session-a",
                        document,
                        model,
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        before)
                .orElseThrow();

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
        assertTrue(lines.get(0).contains("nativeRelationObservation=UNAVAILABLE"));
        assertTrue(lines.get(0).contains("nativeRelationObservationCause=resolver-unavailable"));
        assertTrue(lines.get(0).contains("artPathExclusion=UNAVAILABLE:reviewed-alias-not-admitted"));
        assertTrue(lines.get(0).contains("nativeCompletionCallback=UNAVAILABLE:reviewed-alias-not-admitted"));
        assertTrue(lines.get(1).contains("nativeRelationObservation=UNAVAILABLE"));
        assertTrue(lines.get(1).contains("nativeRelationObservationCause=resolver-unavailable"));
        assertTrue(lines.get(1).contains("nativeReturned=true"));
        assertTrue(lines.get(1).contains("nativeReturnObservation=SYNCHRONOUS_RETURN_ONLY"));
    }

    @Test
    void nativeObservationKeepsHasLayerInputIndependentFromEmptySelectorMap() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final NativeFixture fixture = nativeFixture();
        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        fixture.resolver(),
                        "session-a",
                        fixture.source(),
                        new Object(),
                        fixture.model(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        snapshot("session-a", "old", true, 7, 1, false))
                .orElseThrow();
        session.finish(snapshot("session-a", "incoming", true, 7, 2, false), nativeReturned(), null);

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("nativeRelationObservation=AVAILABLE"));
        assertTrue(lines.get(0).contains("nativeModelImage.0.id=model-a"));
        assertTrue(lines.get(0).contains("nativeModelImage.0.target=true"));
        assertTrue(lines.get(0).contains("nativeModelImage.0.hasLayerInputData=true"));
        assertTrue(lines.get(0).contains("nativeModelImage.0.selectorKeysStatus=AVAILABLE"));
        assertTrue(lines.get(0).contains("nativeModelImage.0.selectorKeysCount=0"));
        assertTrue(lines.get(0).contains("nativeModelImage.0.currentImageGuid=old"));
        assertTrue(lines.get(0).contains("nativeModelImage.0.linkedRaw.0=old"));
        assertTrue(lines.get(0).contains("nativeGroup.0.linkedRaw.0=old"));
        assertTrue(lines.get(0).contains("nativeRawWrapper.0.id=old"));
        assertTrue(lines.get(0).contains("nativeRawWrapper.1.id=incoming"));
    }

    @Test
    void nativeObservationBoundsCompositeRecordsWithoutDroppingTargetOrRequestedRaws() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final NativeFixture fixture = largeNativeFixture();
        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        fixture.resolver(),
                        "session-a",
                        fixture.source(),
                        new Object(),
                        fixture.model(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        snapshot("session-a", "old", true, 7, 1, false))
                .orElseThrow();

        final String line = Files.readAllLines(diagnosticArtifact()).get(0);

        assertTrue(line.contains("nativeRelationObservation=AVAILABLE"));
        assertTrue(line.contains("nativeModelImageTotalCount=33"));
        assertTrue(line.contains("nativeModelImageTruncated=true"));
        assertTrue(line.contains("nativeModelImage.0.id=target-model"));
        assertTrue(line.contains("nativeModelImage.0.target=true"));
        assertTrue(line.contains("nativeModelImage.0.hasLayerInputData=true"));
        assertTrue(line.contains("nativeModelImage.0.selectorKeysStatus=AVAILABLE"));
        assertTrue(line.contains("nativeModelImage.0.selectorKeysCount=32"));
        assertTrue(line.contains("nativeGroupTotalCount=33"));
        assertTrue(line.contains("nativeGroupTruncated=true"));
        assertTrue(line.contains("nativeRawWrapper.0.id=old"));
        assertTrue(line.contains("nativeRawWrapper.1.id=incoming"));
        assertTrue(line.contains("diagnosticTruncated=true"));

        session.finish(snapshot("session-a", "incoming", true, 7, 2, false), nativeReturned(), null);
    }

    @Test
    void missingNativeRelationCapabilityIsUnavailableAndDoesNotUsePublicProjection() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final NativeFixture fixture = nativeFixture(false);
        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        resolver(false),
                        "session-a",
                        fixture.source(),
                        new Object(),
                        fixture.model(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        snapshot("old", true))
                .orElseThrow();
        session.finish(snapshot("incoming", true), nativeReturned(), null);

        final String line = Files.readAllLines(diagnosticArtifact()).get(0);
        assertTrue(line.contains("nativeRelationObservation=UNAVAILABLE"));
        assertTrue(line.contains("nativeRelationObservationCause=native-relation-aliases-unavailable"));
        assertFalse(line.contains("hasLayerInputData=true"));
    }

    @Test
    void replacementCallSiteLeavesDiagnosticDisabledAndInvokesNativeOnce() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final CallSiteFixture fixture = callSiteFixture(tempDir);
        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_RETURNED", result.nativeStatus());
        assertTrue(result.nativeReturned());
        assertEquals(1, CallSiteNativeProcess.calls);
        assertEquals(
                "external-edit.psd",
                fixture.wrappers.get(1).image.getName(),
                "incoming name must describe the issued edit file, not the old raw or staging file");
        assertEquals(
                fixture.stage.toFile(),
                fixture.wrappers.get(1).image.getPsdFile(),
                "native parsing must still use the stable stage");
        assertEquals(2, CallSiteFilterEnv.hasReads, "only the ordinary before/after projection reads");
        assertFalse(Files.exists(diagnosticArtifact()));
    }

    @Test
    void replacementCallSiteRequiresTheExactIncomingRawForApplicationEvidence() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final List<CallSiteNativeProcess.NativeMutation> rejected = List.of(
                CallSiteNativeProcess.NativeMutation.OLD_UNCHANGED,
                CallSiteNativeProcess.NativeMutation.REGISTER_INCOMING_ONLY,
                CallSiteNativeProcess.NativeMutation.WRONG_RAW,
                CallSiteNativeProcess.NativeMutation.INCOMING_NOT_REGISTERED);

        for (final CallSiteNativeProcess.NativeMutation mutation : rejected) {
            final CallSiteFixture fixture = callSiteFixture(tempDir);
            CallSiteNativeProcess.mutation = mutation;

            final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

            assertEquals("NATIVE_RETURNED", result.nativeStatus(), mutation.name());
            assertTrue(result.nativeReturned(), mutation.name());
            assertTrue(result.relationsAvailable(), mutation.name());
            assertTrue(result.afterRawImageId().isEmpty(), mutation.name());
            assertEquals(1, CallSiteNativeProcess.calls, mutation.name());
            CallSiteNativeProcess.calls = 0;
            CallSiteNativeProcess.active = null;
        }
    }

    @Test
    void replacementCallSiteDoesNotClaimApplicationForAnEmptySelector() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final CallSiteFixture fixture = callSiteFixture(tempDir);

        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_RETURNED", result.nativeStatus());
        assertTrue(result.nativeReturned());
        assertTrue(
                result.afterRawImageId().isEmpty(),
                "this diagnostic fixture switches current/linked raw but its selector map stays empty");
        assertEquals(1, CallSiteNativeProcess.calls);
    }

    @Test
    void productionIncomingIdentityFailureStopsBeforeNativeWhileDiagnosticFailureStaysIsolated() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final CallSiteFixture productionFailure = callSiteFixture(tempDir);
        CallSiteLayeredImage.failIncomingGuidReadAt = 1;

        final PsdReplaceHost.Replacement rejected = replaceAtCallSite(productionFailure);

        assertEquals(1, CallSiteLayeredImage.incomingGuidReads);
        assertEquals("UNAVAILABLE", rejected.nativeStatus());
        assertFalse(rejected.nativeReturned());
        assertEquals(0, CallSiteNativeProcess.calls);

        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture diagnosticFailure = callSiteFixture(tempDir);
        CallSiteLayeredImage.failIncomingGuidReadAt = 2;

        final PsdReplaceHost.Replacement accepted = replaceAtCallSite(diagnosticFailure);

        assertEquals("NATIVE_RETURNED", accepted.nativeStatus());
        assertTrue(accepted.nativeReturned());
        assertEquals(1, CallSiteNativeProcess.calls);
        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("incomingRawStatus=UNAVAILABLE"));
        assertTrue(lines.get(0).contains("incoming-guid-unavailable"));
    }

    @Test
    void exportCallSiteCapturesOnlyTheFirstExportAndLeavesLaterExportUninstrumented() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture(tempDir);
        final ModelTextures textures = new EditorTextureAccess(fixture.resolver, (identity, model) -> {})
                .textures("session-a", fixture.source, fixture.model);
        final PsdExportHost host = (PsdExportHost) textures;

        final PsdExportHost.Observation first =
                host.exportPsdTo(new RawImageId("old"), tempDir.resolve("first-export.psd"), () -> {});
        final PsdExportHost.Observation second =
                host.exportPsdTo(new RawImageId("old"), tempDir.resolve("second-export.psd"), () -> {});

        assertTrue(first.readable());
        assertTrue(second.readable());
        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("phase=export-pre"));
        assertTrue(lines.get(1).contains("phase=export-post"));
        assertEquals(field(lines.get(0), "correlation"), field(lines.get(1), "correlation"));
        assertTrue(lines.get(0).contains("nativeOperation=PSD_EXPORT"));
        assertTrue(lines.get(0).contains("oldRaw=old"));
        assertTrue(lines.get(0).contains("nativeRelationObservation=AVAILABLE"));
        assertTrue(lines.get(1).contains("nativeRelationObservation=AVAILABLE"));
        assertTrue(lines.get(0).contains("currentGuardPreStatus=PASSED"));
        assertTrue(lines.get(1).contains("currentGuardPostStatus=PASSED"));
        assertTrue(lines.get(0).contains("nativeCompletionCallback=UNAVAILABLE"));
        assertFalse(lines.get(0).contains("nativeReturned="));
        assertEquals(2, CallSiteFilterEnv.hasReads, "only the first export pre/post direct native captures are added");
    }

    @Test
    void reobtainedTexturesFacadeSharesOneGatePerSessionButNewGenerationRecordsIndependently() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture(tempDir);
        final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong(7L);
        final EditorTextureAccess access =
                new EditorTextureAccess(fixture.resolver, (identity, model) -> {}, generation::get);

        final PsdExportHost firstFacade = (PsdExportHost) access.textures("session-a", fixture.source, fixture.model);
        assertTrue(firstFacade
                .exportPsdTo(new RawImageId("old"), tempDir.resolve("facade-1.psd"), () -> {})
                .readable());

        // This is the production call pattern: EditorBackedCubismModelAccess creates a new
        // texture facade for every model.textures() call.  The exact same session key must reuse
        // the already-consumed gate rather than add a second pre/post pair.
        final PsdExportHost reobtainedFacade =
                (PsdExportHost) access.textures("session-a", fixture.source, fixture.model);
        assertTrue(reobtainedFacade
                .exportPsdTo(new RawImageId("old"), tempDir.resolve("facade-2.psd"), () -> {})
                .readable());

        generation.incrementAndGet();
        final PsdExportHost nextGenerationFacade =
                (PsdExportHost) access.textures("session-a", fixture.source, fixture.model);
        assertTrue(nextGenerationFacade
                .exportPsdTo(new RawImageId("old"), tempDir.resolve("facade-3.psd"), () -> {})
                .readable());

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(4, lines.size());
        assertEquals(field(lines.get(0), "correlation"), field(lines.get(1), "correlation"));
        assertEquals(field(lines.get(2), "correlation"), field(lines.get(3), "correlation"));
        assertTrue(!field(lines.get(0), "correlation").equals(field(lines.get(2), "correlation")));
        assertEquals(4, CallSiteFilterEnv.hasReads);
    }

    @Test
    void newSourceAndModelObjectsDoNotReuseAnotherSessionGate() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture firstFixture = callSiteFixture(tempDir);
        final EditorTextureAccess access = new EditorTextureAccess(firstFixture.resolver, (identity, model) -> {});

        assertTrue(((PsdExportHost) access.textures("session-a", firstFixture.source, firstFixture.model))
                .exportPsdTo(new RawImageId("old"), tempDir.resolve("source-model-1.psd"), () -> {})
                .readable());

        final CallSiteFixture secondFixture = callSiteFixture(tempDir);
        assertTrue(((PsdExportHost) access.textures("session-a", secondFixture.source, secondFixture.model))
                .exportPsdTo(new RawImageId("old"), tempDir.resolve("source-model-2.psd"), () -> {})
                .readable());

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(4, lines.size());
        assertTrue(!field(lines.get(0), "correlation").equals(field(lines.get(2), "correlation")));
        assertEquals(2, CallSiteLayeredImage.saveCalls);
    }

    @Test
    void admissionRejectionPrecedesDiagnosticReadsAndDoesNotConsumeSessionGate() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture(tempDir);
        final PsdExportHost host = (PsdExportHost) new EditorTextureAccess(fixture.resolver, (identity, model) -> {})
                .textures("session-a", fixture.source, fixture.model);
        final IllegalStateException expected = new IllegalStateException("admission rejected");

        final IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> host.exportPsdTo(new RawImageId("old"), tempDir.resolve("rejected.psd"), () -> {
                    throw expected;
                }));
        assertSame(expected, actual);
        assertEquals(0, CallSiteFilterEnv.hasReads);
        assertEquals(0, CallSiteLayeredImage.saveCalls);
        assertFalse(Files.exists(diagnosticArtifact()));

        final java.util.concurrent.atomic.AtomicInteger acceptedAdmissions =
                new java.util.concurrent.atomic.AtomicInteger();
        final PsdExportHost.Observation accepted = host.exportPsdTo(
                new RawImageId("old"), tempDir.resolve("accepted.psd"), acceptedAdmissions::incrementAndGet);
        assertTrue(accepted.readable());
        assertEquals(1, CallSiteLayeredImage.saveCalls);
        assertEquals(2, acceptedAdmissions.get(), "only the original pre/post access guards admit");
        assertEquals(2, Files.readAllLines(diagnosticArtifact()).size());
    }

    @Test
    void exportDiagnosticIsDefaultOffAndAddsNoNativeCapture() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final CallSiteFixture fixture = callSiteFixture(tempDir);
        final PsdExportHost.Observation result = ((PsdExportHost)
                        new EditorTextureAccess(fixture.resolver, (identity, model) -> {})
                                .textures("session-a", fixture.source, fixture.model))
                .exportPsdTo(new RawImageId("old"), tempDir.resolve("default-off.psd"), () -> {});

        assertTrue(result.readable());
        assertEquals(0, CallSiteFilterEnv.hasReads);
        assertFalse(Files.exists(diagnosticArtifact()));
    }

    @Test
    void exportDiagnosticCaptureFailureDoesNotChangeExportResult() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture(tempDir);
        CallSiteFilterEnv.throwOnHasRead = 1;

        final PsdExportHost.Observation result = ((PsdExportHost)
                        new EditorTextureAccess(fixture.resolver, (identity, model) -> {})
                                .textures("session-a", fixture.source, fixture.model))
                .exportPsdTo(new RawImageId("old"), tempDir.resolve("capture-failure.psd"), () -> {});

        assertTrue(result.readable());
        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("phase=export-pre"));
        assertTrue(lines.get(0).contains("nativeRelationObservation=UNAVAILABLE"));
        assertTrue(lines.get(1).contains("phase=export-post"));
        assertTrue(lines.get(1).contains("nativeRelationObservation=AVAILABLE"));
    }

    @Test
    void exportDiagnosticDoesNotReadPostStateAfterGuardBecomesUnavailable() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture(tempDir);
        final java.util.concurrent.atomic.AtomicInteger guardCalls = new java.util.concurrent.atomic.AtomicInteger();
        final IllegalStateException expected = new IllegalStateException("post export model is stale");

        final PsdExportHost host = (PsdExportHost) new EditorTextureAccess(fixture.resolver, (identity, model) -> {
                    // textures() performs one check, then EditorRawImagePsdAccess performs its
                    // pre-save and post-save checks.  Fail the latter: the native save has happened,
                    // but the original export exception must remain visible and diagnostic post
                    // capture must stay unavailable.
                    if (guardCalls.incrementAndGet() == 3) {
                        throw expected;
                    }
                })
                .textures("session-a", fixture.source, fixture.model);

        final IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> host.exportPsdTo(new RawImageId("old"), tempDir.resolve("post-guard-failure.psd"), () -> {}));
        assertSame(expected, actual);
        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        final String post = lines.get(1);
        assertTrue(post.contains("phase=export-post"));
        assertTrue(post.contains("currentGuardPostStatus=UNAVAILABLE"));
        assertTrue(post.contains("nativeRelationObservation=UNAVAILABLE"));
        assertTrue(post.contains("post-current-guard-failed"));
        assertFalse(post.contains("nativeModelImage.0.id="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"guidRead", "hasRead", "linkageError"})
    void replacementCallSiteContinuesWhenDiagnosticFails(final String failureType) throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture(tempDir);

        if ("guidRead".equals(failureType)) {
            CallSiteLayeredImage.failIncomingGuidReadAt = 2;
        } else if ("hasRead".equals(failureType)) {
            CallSiteFilterEnv.throwOnHasRead = 2;
        } else {
            CallSiteFilterEnv.throwOnHasReadLinkageError = 2;
        }

        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_RETURNED", result.nativeStatus());
        assertTrue(result.nativeReturned());
        assertEquals(1, CallSiteNativeProcess.calls);
        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        if ("guidRead".equals(failureType)) {
            assertTrue(lines.get(0).contains("incomingRawStatus=UNAVAILABLE"));
            assertTrue(lines.get(0).contains("incoming-guid-unavailable"));
            assertTrue(lines.get(1).contains("nativeReturned=true"));
            assertFalse(lines.get(1).contains("committed=true"));
        } else if ("hasRead".equals(failureType)) {
            assertTrue(lines.get(0).contains("nativeRelationObservation=UNAVAILABLE"));
            assertTrue(lines.get(0).contains("native-relation-observation-failed"));
            assertTrue(lines.get(1).contains("nativeRelationObservation=AVAILABLE"));
            assertTrue(lines.get(1).contains("nativeReturnObservation=SYNCHRONOUS_RETURN_ONLY"));
            assertFalse(lines.get(1).contains("committed=true"));
        } else {
            assertTrue(lines.get(0).contains("nativeRelationObservation=UNAVAILABLE"));
            assertTrue(lines.get(0).contains("native-relation-observation-failed"));
            assertTrue(lines.get(1).contains("nativeReturned=true"));
        }
    }

    @Test
    void replacementCallSitePreservesNativeFailureFactsAndDoesNotClaimCommit() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture(tempDir);
        CallSiteNativeProcess.fail = true;

        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_OUTCOME_UNKNOWN", result.nativeStatus());
        assertFalse(result.nativeReturned());
        assertTrue(result.mutationUnknown());
        assertEquals(1, CallSiteNativeProcess.calls);
        final String post = Files.readAllLines(diagnosticArtifact()).get(1);
        assertTrue(post.contains("nativeInvocationAttempted=true"));
        assertTrue(post.contains("nativeReturned=false"));
        assertTrue(post.contains("nativeReturnObservation=SYNCHRONOUS_RETURN_ONLY"));
        assertFalse(post.contains("committed=true"));
    }

    @Test
    void missingIncomingIdentityIsRecordedUnavailableRatherThanComposed() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        "session-a",
                        new Object(),
                        new Object(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.unavailable("guid-not-readable"),
                        snapshot("old", true))
                .orElseThrow();
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

        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        "session-a",
                        new Object(),
                        new Object(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        snapshot("other-session", "old", true))
                .orElseThrow();
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

        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        "session-a",
                        new Object(),
                        new Object(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        snapshot("old", true))
                .orElseThrow();
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
    void diagnosticLineAndValuesRemainBounded() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final String oversized = "x".repeat(100_000);

        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        oversized,
                        new Object(),
                        new Object(),
                        new RawImageId(oversized),
                        EditorTextureReplacementDiagnostic.RawIdentity.available(oversized),
                        snapshot("old", true))
                .orElseThrow();
        session.finish(snapshot("incoming", true), nativeReturned(), null);

        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(
                lines.stream().allMatch(line -> line.length() <= EditorTextureReplacementDiagnostic.MAX_LINE_LENGTH));
        assertTrue(lines.stream().allMatch(line -> !line.contains(oversized)));
    }

    @Test
    void partialNativeFailureRemainsFailureAndIsNotReportedAsApplied() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        "session-a",
                        new Object(),
                        new Object(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        snapshot("old", true))
                .orElseThrow();
        final EditorRawImagePsdReplaceAccess.ReplaceResult failure = new EditorRawImagePsdReplaceAccess.ReplaceResult(
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
                "native root cause");
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

        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        "session-a",
                        new Object(),
                        new Object(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        snapshot("old", true))
                .orElseThrow();
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
                        "stale before native");
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

        final EditorTextureReplacementDiagnostic.Session session = EditorTextureReplacementDiagnostic.begin(
                        "session-a",
                        new Object(),
                        new Object(),
                        new RawImageId("old"),
                        EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                        snapshot("session-a", "old", true, 7, 1))
                .orElseThrow();
        session.finish(snapshot("session-a", "incoming", true, 8, 2), nativeReturned(), null);

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
                "native returned");
    }

    private static TextureRelationsSnapshot snapshot(final String currentRaw, final boolean replaced) {
        return snapshot("session-a", currentRaw, replaced, 7, currentRaw.equals("old") ? 1 : 2, true);
    }

    private static TextureRelationsSnapshot snapshot(
            final String binding, final String currentRaw, final boolean replaced) {
        return snapshot(binding, currentRaw, replaced, 7, currentRaw.equals("old") ? 1 : 2);
    }

    private static TextureRelationsSnapshot snapshot(
            final String binding,
            final String currentRaw,
            final boolean replaced,
            final long generation,
            final long revision) {
        return snapshot(binding, currentRaw, replaced, generation, revision, true);
    }

    private static TextureRelationsSnapshot snapshot(
            final String binding,
            final String currentRaw,
            final boolean replaced,
            final long generation,
            final long revision,
            final boolean publicLayerInputProjection) {
        final RawImageId old = new RawImageId("old");
        final RawImageId incoming = new RawImageId("incoming");
        final RawImageId current = new RawImageId(currentRaw);
        final ModelImageId modelId = new ModelImageId("model-a");
        final ModelImageEntry entry = new ModelImageEntry() {
            @Override
            public ModelImageId id() {
                return modelId;
            }

            @Override
            public String name() {
                return "Image";
            }

            @Override
            public int width() {
                return 100;
            }

            @Override
            public int height() {
                return 100;
            }
        };
        final ModelImageRelation model = new ModelImageRelation(
                modelId,
                entry,
                List.of(old, incoming),
                Optional.of(current),
                publicLayerInputProjection
                        ? Map.of(
                                current,
                                List.of(new RawLayerBinding(
                                        current,
                                        new RawLayerId("layer"),
                                        0,
                                        RawLayerBinding.DetailAvailability.AVAILABLE,
                                        RawLayerBinding.DetailAvailability.AVAILABLE)))
                        : Map.of(),
                List.of());
        final ModelImageGroup group = new ModelImageGroup() {
            @Override
            public String groupName() {
                return "Group A";
            }

            @Override
            public String memo() {
                return "memo";
            }

            @Override
            public List<ModelImageEntry> modelImages() {
                return List.of(entry);
            }
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
                        group, List.of(modelId), List.of(old, incoming), Optional.of(true))),
                List.of());
    }

    private static RawImageDetails raw(final RawImageId id, final boolean replaced) {
        final RawTexture texture = new RawTexture() {
            @Override
            public RawImageId id() {
                return id;
            }

            @Override
            public String name() {
                return id.value();
            }

            @Override
            public int width() {
                return 100;
            }

            @Override
            public int height() {
                return 100;
            }
        };
        return new RawImageDetails(
                texture,
                RawImageDetails.SourceKind.PSD,
                List.of(),
                replaced,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
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
