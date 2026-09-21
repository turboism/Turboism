package dev.turboism.adapter.cubism.editor;

import dev.turboism.core.runtime.psd.PsdExportHost;
import dev.turboism.core.runtime.psd.PsdReplaceHost;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdReplaceSelectorContract;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureRelationsSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
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
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                fixture.resolver,
                "session-a",
                fixture.source,
                new Object(),
                fixture.model,
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                snapshot("session-a", "old", true, 7, 1, false)
            ).orElseThrow();
        session.finish(
            snapshot("session-a", "incoming", true, 7, 2, false),
            nativeReturned(),
            null
        );

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
    void nativeObservationBoundsCompositeRecordsWithoutDroppingTargetOrRequestedRaws()
        throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");

        final NativeFixture fixture = largeNativeFixture();
        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                fixture.resolver,
                "session-a",
                fixture.source,
                new Object(),
                fixture.model,
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                snapshot("session-a", "old", true, 7, 1, false)
            ).orElseThrow();

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
        final EditorTextureReplacementDiagnostic.Session session =
            EditorTextureReplacementDiagnostic.begin(
                resolver(false),
                "session-a",
                fixture.source,
                new Object(),
                fixture.model,
                new RawImageId("old"),
                EditorTextureReplacementDiagnostic.RawIdentity.available("incoming"),
                snapshot("old", true)
            ).orElseThrow();
        session.finish(snapshot("incoming", true), nativeReturned(), null);

        final String line = Files.readAllLines(diagnosticArtifact()).get(0);
        assertTrue(line.contains("nativeRelationObservation=UNAVAILABLE"));
        assertTrue(line.contains("nativeRelationObservationCause=native-relation-aliases-unavailable"));
        assertFalse(line.contains("hasLayerInputData=true"));
    }

    @Test
    void replacementCallSiteLeavesDiagnosticDisabledAndInvokesNativeOnce() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final CallSiteFixture fixture = callSiteFixture();
        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_RETURNED", result.nativeStatus());
        assertTrue(result.nativeReturned());
        assertEquals(1, CallSiteNativeProcess.calls);
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
            CallSiteNativeProcess.NativeMutation.INCOMING_NOT_REGISTERED
        );

        for (final CallSiteNativeProcess.NativeMutation mutation : rejected) {
            final CallSiteFixture fixture = callSiteFixture();
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
    void replacementCallSiteReportsTheExactIncomingRawOnlyAfterAffectedImagesSwitch() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final CallSiteFixture fixture = callSiteFixture();

        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_RETURNED", result.nativeStatus());
        assertTrue(result.nativeReturned());
        assertEquals(Optional.of(new RawImageId("incoming")), result.afterRawImageId());
        assertEquals(1, CallSiteNativeProcess.calls);
    }

    @Test
    void productionIncomingIdentityFailureStopsBeforeNativeWhileDiagnosticFailureStaysIsolated()
        throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final CallSiteFixture productionFailure = callSiteFixture();
        CallSiteLayeredImage.failIncomingGuidReadAt = 1;

        final PsdReplaceHost.Replacement rejected = replaceAtCallSite(productionFailure);

        assertEquals(1, CallSiteLayeredImage.incomingGuidReads);
        assertEquals("UNAVAILABLE", rejected.nativeStatus());
        assertFalse(rejected.nativeReturned());
        assertEquals(0, CallSiteNativeProcess.calls);

        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture diagnosticFailure = callSiteFixture();
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
        final CallSiteFixture fixture = callSiteFixture();
        final ModelTextures textures = new EditorTextureAccess(
            fixture.resolver,
            (identity, model) -> { }
        ).textures("session-a", fixture.source, fixture.model);
        final PsdExportHost host = (PsdExportHost) textures;

        final PsdExportHost.Observation first = host.exportPsdTo(
            new RawImageId("old"), tempDir.resolve("first-export.psd"), () -> { }
        );
        final PsdExportHost.Observation second = host.exportPsdTo(
            new RawImageId("old"), tempDir.resolve("second-export.psd"), () -> { }
        );

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
        assertEquals(2, CallSiteFilterEnv.hasReads,
            "only the first export pre/post direct native captures are added");
    }

    @Test
    void reobtainedTexturesFacadeSharesOneGatePerSessionButNewGenerationRecordsIndependently()
        throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture();
        final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong(7L);
        final EditorTextureAccess access = new EditorTextureAccess(
            fixture.resolver,
            (identity, model) -> { },
            generation::get
        );

        final PsdExportHost firstFacade = (PsdExportHost) access.textures(
            "session-a", fixture.source, fixture.model);
        assertTrue(firstFacade.exportPsdTo(
            new RawImageId("old"), tempDir.resolve("facade-1.psd"), () -> { }
        ).readable());

        // This is the production call pattern: EditorBackedCubismModelAccess creates a new
        // texture facade for every model.textures() call.  The exact same session key must reuse
        // the already-consumed gate rather than add a second pre/post pair.
        final PsdExportHost reobtainedFacade = (PsdExportHost) access.textures(
            "session-a", fixture.source, fixture.model);
        assertTrue(reobtainedFacade.exportPsdTo(
            new RawImageId("old"), tempDir.resolve("facade-2.psd"), () -> { }
        ).readable());

        generation.incrementAndGet();
        final PsdExportHost nextGenerationFacade = (PsdExportHost) access.textures(
            "session-a", fixture.source, fixture.model);
        assertTrue(nextGenerationFacade.exportPsdTo(
            new RawImageId("old"), tempDir.resolve("facade-3.psd"), () -> { }
        ).readable());

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
        final CallSiteFixture firstFixture = callSiteFixture();
        final EditorTextureAccess access = new EditorTextureAccess(
            firstFixture.resolver,
            (identity, model) -> { }
        );

        assertTrue(((PsdExportHost) access.textures(
            "session-a", firstFixture.source, firstFixture.model
        )).exportPsdTo(
            new RawImageId("old"), tempDir.resolve("source-model-1.psd"), () -> { }
        ).readable());

        final CallSiteFixture secondFixture = callSiteFixture();
        assertTrue(((PsdExportHost) access.textures(
            "session-a", secondFixture.source, secondFixture.model
        )).exportPsdTo(
            new RawImageId("old"), tempDir.resolve("source-model-2.psd"), () -> { }
        ).readable());

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
        final CallSiteFixture fixture = callSiteFixture();
        final PsdExportHost host = (PsdExportHost) new EditorTextureAccess(
            fixture.resolver,
            (identity, model) -> { }
        ).textures("session-a", fixture.source, fixture.model);
        final IllegalStateException expected = new IllegalStateException("admission rejected");

        final IllegalStateException actual = assertThrows(
            IllegalStateException.class,
            () -> host.exportPsdTo(
                new RawImageId("old"), tempDir.resolve("rejected.psd"), () -> { throw expected; }
            )
        );
        assertSame(expected, actual);
        assertEquals(0, CallSiteFilterEnv.hasReads);
        assertEquals(0, CallSiteLayeredImage.saveCalls);
        assertFalse(Files.exists(diagnosticArtifact()));

        final java.util.concurrent.atomic.AtomicInteger acceptedAdmissions =
            new java.util.concurrent.atomic.AtomicInteger();
        final PsdExportHost.Observation accepted = host.exportPsdTo(
            new RawImageId("old"), tempDir.resolve("accepted.psd"), acceptedAdmissions::incrementAndGet
        );
        assertTrue(accepted.readable());
        assertEquals(1, CallSiteLayeredImage.saveCalls);
        assertEquals(2, acceptedAdmissions.get(), "only the original pre/post access guards admit");
        assertEquals(2, Files.readAllLines(diagnosticArtifact()).size());
    }

    @Test
    void exportDiagnosticIsDefaultOffAndAddsNoNativeCapture() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        final CallSiteFixture fixture = callSiteFixture();
        final PsdExportHost.Observation result = ((PsdExportHost) new EditorTextureAccess(
            fixture.resolver,
            (identity, model) -> { }
        ).textures("session-a", fixture.source, fixture.model)).exportPsdTo(
            new RawImageId("old"), tempDir.resolve("default-off.psd"), () -> { }
        );

        assertTrue(result.readable());
        assertEquals(0, CallSiteFilterEnv.hasReads);
        assertFalse(Files.exists(diagnosticArtifact()));
    }

    @Test
    void exportDiagnosticCaptureFailureDoesNotChangeExportResult() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture();
        CallSiteFilterEnv.throwOnHasRead = 1;

        final PsdExportHost.Observation result = ((PsdExportHost) new EditorTextureAccess(
            fixture.resolver,
            (identity, model) -> { }
        ).textures("session-a", fixture.source, fixture.model)).exportPsdTo(
            new RawImageId("old"), tempDir.resolve("capture-failure.psd"), () -> { }
        );

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
        final CallSiteFixture fixture = callSiteFixture();
        final java.util.concurrent.atomic.AtomicInteger guardCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        final IllegalStateException expected = new IllegalStateException("post export model is stale");

        final PsdExportHost host = (PsdExportHost) new EditorTextureAccess(
            fixture.resolver,
            (identity, model) -> {
                // textures() performs one check, then EditorRawImagePsdAccess performs its
                // pre-save and post-save checks.  Fail the latter: the native save has happened,
                // but the original export exception must remain visible and diagnostic post
                // capture must stay unavailable.
                if (guardCalls.incrementAndGet() == 3) {
                    throw expected;
                }
            }
        ).textures("session-a", fixture.source, fixture.model);

        final IllegalStateException actual = assertThrows(
            IllegalStateException.class,
            () -> host.exportPsdTo(
                new RawImageId("old"), tempDir.resolve("post-guard-failure.psd"), () -> { }
            )
        );
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

    @Test
    void replacementCallSiteContinuesWhenIncomingDiagnosticIdentityReadFailsBeforeNative()
        throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture();
        // The first incoming GUID read is the production application-evidence read. Fail the
        // second one so this test remains specifically diagnostic-only.
        CallSiteLayeredImage.failIncomingGuidReadAt = 2;

        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_RETURNED", result.nativeStatus());
        assertTrue(result.nativeReturned());
        assertEquals(1, CallSiteNativeProcess.calls);
        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("incomingRawStatus=UNAVAILABLE"));
        assertTrue(lines.get(0).contains("incoming-guid-unavailable"));
        assertTrue(lines.get(1).contains("nativeReturned=true"));
        assertFalse(lines.get(1).contains("committed=true"));
    }

    @Test
    void replacementCallSiteKeepsNativeResultWhenEnabledDiagnosticReadFails() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture();
        // The first read belongs to the ordinary before snapshot. The second
        // is the diagnostic pre-read; the native call must still be reached.
        CallSiteFilterEnv.throwOnHasRead = 2;

        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_RETURNED", result.nativeStatus());
        assertTrue(result.nativeReturned());
        assertEquals(1, CallSiteNativeProcess.calls);
        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("nativeRelationObservation=UNAVAILABLE"));
        assertTrue(lines.get(0).contains("native-relation-observation-failed"));
        assertTrue(lines.get(1).contains("nativeRelationObservation=AVAILABLE"));
        assertTrue(lines.get(1).contains("nativeReturnObservation=SYNCHRONOUS_RETURN_ONLY"));
        assertFalse(lines.get(1).contains("committed=true"));
    }

    @Test
    void replacementCallSiteContinuesWhenEnabledDiagnosticLinkageErrorOccurs() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture();
        // The diagnostic pre-read is the second filter read after the ordinary
        // relation projection. A host LinkageError must remain diagnostic-only.
        CallSiteFilterEnv.throwOnHasReadLinkageError = 2;

        final PsdReplaceHost.Replacement result = replaceAtCallSite(fixture);

        assertEquals("NATIVE_RETURNED", result.nativeStatus());
        assertTrue(result.nativeReturned());
        assertEquals(1, CallSiteNativeProcess.calls);
        final List<String> lines = Files.readAllLines(diagnosticArtifact());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("nativeRelationObservation=UNAVAILABLE"));
        assertTrue(lines.get(0).contains("native-relation-observation-failed"));
        assertTrue(lines.get(1).contains("nativeReturned=true"));
    }

    @Test
    void replacementCallSitePreservesNativeFailureFactsAndDoesNotClaimCommit() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        System.setProperty(EditorTextureReplacementDiagnostic.ENABLE_PROPERTY, "true");
        final CallSiteFixture fixture = callSiteFixture();
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
        return snapshot(
            "session-a", currentRaw, replaced, 7, currentRaw.equals("old") ? 1 : 2, true
        );
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
        return snapshot(binding, currentRaw, replaced, generation, revision, true);
    }

    private static TextureRelationsSnapshot snapshot(
        final String binding,
        final String currentRaw,
        final boolean replaced,
        final long generation,
        final long revision,
        final boolean publicLayerInputProjection
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
            publicLayerInputProjection
                ? Map.of(
                    current,
                    List.of(new RawLayerBinding(
                        current,
                        new RawLayerId("layer"),
                        0,
                        RawLayerBinding.DetailAvailability.AVAILABLE,
                        RawLayerBinding.DetailAvailability.AVAILABLE
                    ))
                )
                : Map.of(),
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

    private static NativeFixture nativeFixture() {
        return nativeFixture(true);
    }

    private static NativeFixture largeNativeFixture() {
        final EditorTextureRelationsAccessTest.HostId oldId =
            new EditorTextureRelationsAccessTest.HostId("old");
        final EditorTextureRelationsAccessTest.HostId incomingId =
            new EditorTextureRelationsAccessTest.HostId("incoming");
        final EditorTextureRelationsAccessTest.LayeredImage old =
            new EditorTextureRelationsAccessTest.LayeredImage(
                oldId, "Old", 100, 100, new File("/old.psd"),
                new EditorTextureRelationsAccessTest.PsdDocument(), List.of()
            );
        final EditorTextureRelationsAccessTest.LayeredImage incoming =
            new EditorTextureRelationsAccessTest.LayeredImage(
                incomingId, "Incoming", 100, 100, new File("/incoming.psd"),
                new EditorTextureRelationsAccessTest.PsdDocument(), List.of()
            );
        final List<EditorTextureRelationsAccessTest.ModelImage> modelImages = new ArrayList<>();
        final List<EditorTextureRelationsAccessTest.HostModelImageGroup> groups = new ArrayList<>();
        for (int imageIndex = 0; imageIndex < 33; imageIndex++) {
            final boolean target = imageIndex == 0;
            final List<EditorTextureRelationsAccessTest.HostId> linked = target
                ? List.of(oldId)
                : longIds("linked-" + imageIndex + '-', 32);
            final Map<EditorTextureRelationsAccessTest.HostId, List<
                EditorTextureRelationsAccessTest.LayerInput>> selectorKeys = new LinkedHashMap<>();
            for (int keyIndex = 0; keyIndex < 32; keyIndex++) {
                selectorKeys.put(
                    new EditorTextureRelationsAccessTest.HostId(
                        longValue("selector-" + imageIndex + '-', keyIndex)
                    ),
                    List.of()
                );
            }
            final EditorTextureRelationsAccessTest.ModelImage image =
                new EditorTextureRelationsAccessTest.ModelImage(
                    new EditorTextureRelationsAccessTest.HostId(
                        target ? "target-model" : "filler-model-" + imageIndex
                    ),
                    target ? "Target" : "Filler",
                    100,
                    100,
                    linked,
                    new EditorTextureRelationsAccessTest.FilterEnv(
                        true,
                        new EditorTextureRelationsAccessTest.SelectorMap(selectorKeys),
                        true,
                        target ? oldId : linked.get(0)
                    )
                );
            modelImages.add(image);
            groups.add(
                new EditorTextureRelationsAccessTest.HostModelImageGroup(
                    target ? "Target group" : "Filler group " + imageIndex,
                    "memo",
                    List.of(image),
                    target ? List.of(oldId, incomingId) : linked
                )
            );
        }
        final EditorTextureRelationsAccessTest.TextureManager manager =
            new EditorTextureRelationsAccessTest.TextureManager(
                List.of(
                    new EditorTextureRelationsAccessTest.Wrapper(old, "import", "modified", true),
                    new EditorTextureRelationsAccessTest.Wrapper(incoming, null, null, true)
                ),
                groups,
                modelImages,
                List.of(),
                groups
            );
        final EditorTextureRelationsAccessTest.ModelSource source =
            new EditorTextureRelationsAccessTest.ModelSource(manager, List.of());
        final EditorTextureRelationsAccessTest.Model model =
            new EditorTextureRelationsAccessTest.Model(List.of());
        return new NativeFixture(source, model, resolver(true));
    }

    private static List<EditorTextureRelationsAccessTest.HostId> longIds(
        final String prefix,
        final int count
    ) {
        final List<EditorTextureRelationsAccessTest.HostId> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(new EditorTextureRelationsAccessTest.HostId(longValue(prefix, index)));
        }
        return values;
    }

    private static String longValue(final String prefix, final int index) {
        return prefix + index + '-' + "x".repeat(112);
    }

    private static NativeFixture nativeFixture(final boolean emptySelectorMap) {
        final EditorTextureRelationsAccessTest.HostId oldId =
            new EditorTextureRelationsAccessTest.HostId("old");
        final EditorTextureRelationsAccessTest.HostId incomingId =
            new EditorTextureRelationsAccessTest.HostId("incoming");
        final EditorTextureRelationsAccessTest.LayeredImage old =
            new EditorTextureRelationsAccessTest.LayeredImage(
                oldId, "Old", 100, 100, new File("/old.psd"),
                new EditorTextureRelationsAccessTest.PsdDocument(), List.of()
            );
        final EditorTextureRelationsAccessTest.LayeredImage incoming =
            new EditorTextureRelationsAccessTest.LayeredImage(
                incomingId, "Incoming", 100, 100, new File("/incoming.psd"),
                new EditorTextureRelationsAccessTest.PsdDocument(), List.of()
            );
        final EditorTextureRelationsAccessTest.ModelImage modelImage =
            new EditorTextureRelationsAccessTest.ModelImage(
                new EditorTextureRelationsAccessTest.HostId("model-a"),
                "Image", 100, 100, List.of(oldId),
                new EditorTextureRelationsAccessTest.FilterEnv(
                    true,
                    new EditorTextureRelationsAccessTest.SelectorMap(
                        emptySelectorMap ? Map.of() : Map.of(oldId, List.of())
                    ),
                    true,
                    oldId
                )
            );
        final EditorTextureRelationsAccessTest.HostModelImageGroup group =
            new EditorTextureRelationsAccessTest.HostModelImageGroup(
                "Group A", "memo", List.of(modelImage), List.of(oldId, incomingId)
            );
        final EditorTextureRelationsAccessTest.TextureManager manager =
            new EditorTextureRelationsAccessTest.TextureManager(
                List.of(
                    new EditorTextureRelationsAccessTest.Wrapper(old, "import", "modified", true),
                    new EditorTextureRelationsAccessTest.Wrapper(incoming, null, null, true)
                ),
                List.of(group),
                List.of(modelImage),
                List.of(),
                List.of(group)
            );
        final EditorTextureRelationsAccessTest.ModelSource source =
            new EditorTextureRelationsAccessTest.ModelSource(manager, List.of());
        final EditorTextureRelationsAccessTest.Model model =
            new EditorTextureRelationsAccessTest.Model(List.of());
        return new NativeFixture(source, model, resolver(true));
    }

    private static VerifiedMemberResolver resolver(final boolean authorized) {
        final Map<String, StaticSelector> selectors = new LinkedHashMap<>();
        for (final String alias : EditorTextureRelationsSelectorContract.REQUIRED_ALIASES) {
            selectors.put(alias, StaticSelector.classSelector(alias, internal(Object.class)));
        }
        putMethod(selectors, "cubism.editor-model.model-source.texture-manager",
            EditorTextureRelationsAccessTest.ModelSource.class, "textureManager",
            desc(EditorTextureRelationsAccessTest.TextureManager.class));
        putMethod(selectors, "cubism.editor-model.texture-manager.raw-images",
            EditorTextureRelationsAccessTest.TextureManager.class, "rawImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.all-model-images",
            EditorTextureRelationsAccessTest.TextureManager.class, "allModelImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.model-image-groups",
            EditorTextureRelationsAccessTest.TextureManager.class, "modelImageGroups", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.image",
            EditorTextureRelationsAccessTest.Wrapper.class, "image",
            desc(EditorTextureRelationsAccessTest.LayeredImage.class));
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.replaced",
            EditorTextureRelationsAccessTest.Wrapper.class, "isReplaced", "()Z");
        putClass(selectors, "cubism.editor-model.layered-image.class",
            EditorTextureRelationsAccessTest.LayeredImage.class);
        putMethod(selectors, "cubism.editor-model.layered-image.guid",
            EditorTextureRelationsAccessTest.LayeredImage.class, "getGuid",
            desc(EditorTextureRelationsAccessTest.HostId.class));
        putClass(selectors, "cubism.editor-model.model-image.class",
            EditorTextureRelationsAccessTest.ModelImage.class);
        putMethod(selectors, "cubism.editor-model.model-image.guid",
            EditorTextureRelationsAccessTest.ModelImage.class, "getGuid",
            desc(EditorTextureRelationsAccessTest.HostId.class));
        putMethod(selectors, "cubism.editor-model.model-image.linked-raw-image-guids",
            EditorTextureRelationsAccessTest.ModelImage.class, "getLinkedRawImageGuids", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model-image.input-filter-env",
            EditorTextureRelationsAccessTest.ModelImage.class, "getInputFilterEnv",
            desc(EditorTextureRelationsAccessTest.FilterEnv.class));
        putClass(selectors, "cubism.editor-model.model-image-filter-env.class",
            EditorTextureRelationsAccessTest.FilterEnv.class);
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.has-layer-input-data",
            EditorTextureRelationsAccessTest.FilterEnv.class, "getHasLayerInputData", "()Z");
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.layer-input-data",
            EditorTextureRelationsAccessTest.FilterEnv.class, "getLayerInputData",
            desc(EditorTextureRelationsAccessTest.SelectorMap.class));
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.has-current-image-guid",
            EditorTextureRelationsAccessTest.FilterEnv.class, "getHasCurrentImageGuid", "()Z");
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.current-image-guid",
            EditorTextureRelationsAccessTest.FilterEnv.class, "getCurrentImageGuid",
            desc(EditorTextureRelationsAccessTest.HostId.class));
        putClass(selectors, "cubism.editor-model.layer-selector-map.class",
            EditorTextureRelationsAccessTest.SelectorMap.class);
        putMethod(selectors, "cubism.editor-model.layer-selector-map.image-to-layer-input",
            EditorTextureRelationsAccessTest.SelectorMap.class, "getImageToLayerInput", "()Ljava/util/Map;");
        putClass(selectors, "cubism.editor-model.model-image-group.class",
            EditorTextureRelationsAccessTest.HostModelImageGroup.class);
        putMethod(selectors, "cubism.editor-model.model-image-group.group-name",
            EditorTextureRelationsAccessTest.HostModelImageGroup.class, "getGroupName", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.model-image-group.model-images",
            EditorTextureRelationsAccessTest.HostModelImageGroup.class, "getModelImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model-image-group.linked-raw-image-guids",
            EditorTextureRelationsAccessTest.HostModelImageGroup.class, "getLinkedRawImageGuids", "()Ljava/util/List;");
        putClass(selectors, "cubism.editor-model.guid.class", EditorTextureRelationsAccessTest.HostId.class);
        putMethod(selectors, "cubism.editor-model.guid.value",
            EditorTextureRelationsAccessTest.HostId.class, "value", "()Ljava/lang/String;");
        final Set<String> capabilities = authorized
            ? Set.of(EditorTextureRelationsSelectorContract.CAPABILITY_ID)
            : Set.of("unrelated-capability");
        return TestVerifiedResolvers.create(
            "5.3.02",
            EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            new ArrayList<>(selectors.values()),
            EditorTextureRelationsAccessTest.class.getClassLoader()
        );
    }

    private static void putClass(
        final Map<String, StaticSelector> selectors,
        final String alias,
        final Class<?> type
    ) {
        selectors.put(alias, StaticSelector.classSelector(alias, internal(type)));
    }

    private static void putMethod(
        final Map<String, StaticSelector> selectors,
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        selectors.put(alias, StaticSelector.method(
            alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC
        ));
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    private record NativeFixture(
        EditorTextureRelationsAccessTest.ModelSource source,
        EditorTextureRelationsAccessTest.Model model,
        VerifiedMemberResolver resolver
    ) { }

    private CallSiteFixture callSiteFixture() throws Exception {
        // Callers arm a failure after construction so fixture seeding cannot consume it.
        CallSiteLayeredImage.failIncomingGuidReadAt = 0;
        CallSiteLayeredImage.incomingGuidReads = 0;
        final List<CallSiteWrapper> wrappers = new ArrayList<>();
        final CallSiteLayeredImage old = new CallSiteLayeredImage("old", "Old");
        final CallSiteLayeredImage incoming = new CallSiteLayeredImage("incoming", "Incoming");
        final MutableModelImage modelImage = new MutableModelImage(old);
        final CallSiteGroup group =
            new CallSiteGroup(
                "Group A",
                "memo",
                List.of(modelImage),
                List.of(old.getGuid(), incoming.getGuid())
            );
        wrappers.add(new CallSiteWrapper(old, "import", "modified", true));
        final CallSiteTextureManager manager =
            new CallSiteTextureManager(
                wrappers,
                List.of(group),
                List.of(modelImage),
                List.of(),
                List.of(group)
            );
        final CallSiteSource source = new CallSiteSource(manager);
        final CallSiteModel model = new CallSiteModel();
        final CallSiteFixture fixture = new CallSiteFixture(
            source,
            model,
            new CallSiteDocument(),
            wrappers,
            modelImage,
            callSiteResolver(),
            tempDir.resolve("staged.psd")
        );
        Files.writeString(fixture.stage, "synthetic-stage");
        CallSiteNativeProcess.active = fixture;
        CallSiteAppController.currentDocument = fixture.document;
        // Fixture construction uses the same reviewed GUID accessor to seed linked IDs; start
        // the counter after setup so failures below target production/diagnostic call-site reads.
        CallSiteLayeredImage.incomingGuidReads = 0;
        return fixture;
    }

    private PsdReplaceHost.Replacement replaceAtCallSite(final CallSiteFixture fixture) {
        final ModelTextures textures = new EditorTextureAccess(
            fixture.resolver,
            (identity, model) -> { }
        ).textures("session-a", fixture.source, fixture.model);
        final PsdReplaceHost.Replacement result = ((PsdReplaceHost) textures).replaceWithStagedPsd(
            new RawImageId("old"),
            fixture.stage,
            () -> { }
        );
        return result;
    }

    private static VerifiedMemberResolver callSiteResolver() {
        final Map<String, StaticSelector> selectors = new LinkedHashMap<>();
        addAliases(selectors, EditorTextureRelationsSelectorContract.REQUIRED_ALIASES);
        addAliases(selectors, EditorTextureSelectorContract.READ_REQUIRED_ALIASES);
        addAliases(selectors, EditorRawImagePsdSelectorContract.REQUIRED_ALIASES);
        addAliases(selectors, EditorRawImagePsdReplaceSelectorContract.REQUIRED_ALIASES);

        putMethod(selectors, "cubism.editor-model.model-source.texture-manager",
            CallSiteSource.class, "textureManager", desc(CallSiteTextureManager.class));
        putMethod(selectors, "cubism.editor-model.texture-manager.raw-images",
            CallSiteTextureManager.class, "rawImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.model-image-groups",
            CallSiteTextureManager.class, "modelImageGroups", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.all-model-images",
            CallSiteTextureManager.class, "allModelImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.texture-atlases",
            CallSiteTextureManager.class, "textureAtlases", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.art-mesh-usable-model-image-groups",
            CallSiteTextureManager.class, "artMeshUsableModelImageGroups", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.image",
            CallSiteWrapper.class, "image", desc(CallSiteLayeredImage.class));
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.import-time",
            CallSiteWrapper.class, "getImportTime", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.modified-time",
            CallSiteWrapper.class, "getModifiedTime", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.replaced",
            CallSiteWrapper.class, "isReplaced", "()Z");
        putClass(selectors, "cubism.editor-model.layered-image.class", CallSiteLayeredImage.class);
        putMethod(selectors, "cubism.editor-model.layered-image.guid",
            CallSiteLayeredImage.class, "getGuid", desc(CallSiteId.class));
        putMethod(selectors, "cubism.editor-model.layered-image.name",
            CallSiteLayeredImage.class, "getName", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.layered-image.width",
            CallSiteLayeredImage.class, "getWidth", "()I");
        putMethod(selectors, "cubism.editor-model.layered-image.height",
            CallSiteLayeredImage.class, "getHeight", "()I");
        putMethod(selectors, "cubism.editor-model.layered-image.psd-doc",
            CallSiteLayeredImage.class, "getPsdDoc", desc(CallSitePsdDocument.class));
        putMethod(selectors, "cubism.editor-model.layered-image.children",
            CallSiteLayeredImage.class, "getChildren", "()Ljava/util/List;");
        putClass(selectors, "cubism.editor-model.psd-progress.class", CallSiteProgress.class);
        putStaticMethod(selectors, "cubism.editor-model.psd-progress.default",
            CallSiteProgressFactory.class, "e", desc(CallSiteProgress.class));
        putMethod(selectors, "cubism.editor-model.layered-image.save-psd",
            CallSiteLayeredImage.class,
            "save",
            "(Ljava/io/File;L" + internal(CallSiteProgress.class) + ";)V");
        putClass(selectors, "cubism.editor-model.model-image.class", CallSiteModelImage.class);
        putMethod(selectors, "cubism.editor-model.model-image.guid",
            CallSiteModelImage.class, "getGuid", desc(CallSiteId.class));
        putMethod(selectors, "cubism.editor-model.model-image.name",
            CallSiteModelImage.class, "getName", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.model-image.width",
            CallSiteModelImage.class, "getWidth", "()I");
        putMethod(selectors, "cubism.editor-model.model-image.height",
            CallSiteModelImage.class, "getHeight", "()I");
        putMethod(selectors, "cubism.editor-model.model-image.linked-raw-image-guids",
            CallSiteModelImage.class, "getLinkedRawImageGuids", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model-image.input-filter-env",
            CallSiteModelImage.class, "getInputFilterEnv", desc(CallSiteFilterEnv.class));
        putClass(selectors, "cubism.editor-model.model-image-filter-env.class", CallSiteFilterEnv.class);
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.has-layer-input-data",
            CallSiteFilterEnv.class, "getHasLayerInputData", "()Z");
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.layer-input-data",
            CallSiteFilterEnv.class, "getLayerInputData", desc(CallSiteSelectorMap.class));
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.has-current-image-guid",
            CallSiteFilterEnv.class, "getHasCurrentImageGuid", "()Z");
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.current-image-guid",
            CallSiteFilterEnv.class, "getCurrentImageGuid", desc(CallSiteId.class));
        putClass(selectors, "cubism.editor-model.layer-selector-map.class", CallSiteSelectorMap.class);
        putMethod(selectors, "cubism.editor-model.layer-selector-map.image-to-layer-input",
            CallSiteSelectorMap.class, "getImageToLayerInput", "()Ljava/util/Map;");
        putClass(selectors, "cubism.editor-model.model-image-group.class", CallSiteGroup.class);
        putMethod(selectors, "cubism.editor-model.model-image-group.group-name",
            CallSiteGroup.class, "getGroupName", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.model-image-group.memo",
            CallSiteGroup.class, "getMemo", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.model-image-group.model-images",
            CallSiteGroup.class, "getModelImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model-image-group.linked-raw-image-guids",
            CallSiteGroup.class, "getLinkedRawImageGuids", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model-source.all-art-meshes",
            CallSiteSource.class, "allArtMeshes", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model.all-art-meshes",
            CallSiteModel.class, "allArtMeshes", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.guid.value",
            CallSiteId.class, "value", "()Ljava/lang/String;");

        putClass(selectors, "cubism.editor-model.app-controller.class", CallSiteAppController.class);
        putStaticMethod(selectors, "cubism.editor-model.app-controller.instance",
            CallSiteAppController.class, "instance", desc(CallSiteAppController.class));
        putMethod(selectors, "cubism.editor-model.app-controller.current-document",
            CallSiteAppController.class, "currentDocument", desc(CallSiteDocument.class));
        putClass(selectors, "cubism.editor-model.modeling-document.class", CallSiteDocument.class);
        putMethod(selectors, "cubism.editor-command.canvas.edit-mode",
            CallSiteDocument.class, "editMode", desc(CallSiteEditMode.class));
        putMethod(selectors, "cubism.editor-command.canvas.is-editing",
            CallSiteEditMode.class, "isEditing", "()Z");
        putClass(selectors, "cubism.editor-model.psd-import-process.class", CallSiteNativeProcess.class);
        selectors.put(
            "cubism.editor-model.psd-import-process.instance",
            StaticSelector.field(
                "cubism.editor-model.psd-import-process.instance",
                internal(CallSiteNativeProcess.class),
                "INSTANCE",
                desc(CallSiteNativeProcess.class).substring(2),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
            )
        );
        putMethod(selectors, "cubism.editor-model.psd-import-process.replace",
            CallSiteNativeProcess.class, "replace",
            "(" + desc(CallSiteAppController.class).substring(2)
                + desc(CallSiteLayeredImage.class).substring(2)
                + "Ljava/io/File;"
                + desc(CallSiteDocument.class).substring(2)
                + "Ljava/util/List;)V");

        putClass(selectors, "cubism.editor-model.psd-document.class", CallSiteParsed.class);
        putClass(selectors, "cubism.editor-model.psd-document-companion.class", CallSiteCompanion.class);
        selectors.put(
            "cubism.editor-model.psd-document.companion",
            StaticSelector.field(
                "cubism.editor-model.psd-document.companion",
                internal(CallSitePsdDocument.class),
                "companion",
                desc(CallSiteCompanion.class).substring(2),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
            )
        );
        putMethod(selectors, "cubism.editor-model.psd-document.parse-file",
            CallSiteCompanion.class, "parseFile",
            "(Ljava/io/File;ZZ)" + desc(CallSiteParsed.class).substring(2));
        selectors.put(
            "cubism.editor-model.layered-image.from-psd",
            StaticSelector.constructor(
                "cubism.editor-model.layered-image.from-psd",
                internal(CallSiteLayeredImage.class),
                "(" + desc(CallSiteParsed.class).substring(2)
                    + "Ljava/io/File;Ljava/lang/String;)V",
                0
            )
        );

        final Set<String> capabilities = Set.of(
            EditorTextureSelectorContract.READ_CAPABILITY_ID,
            EditorRawImagePsdReplaceSelectorContract.CAPABILITY_ID,
            EditorRawImagePsdSelectorContract.CAPABILITY_ID
        );
        return TestVerifiedResolvers.create(
            "5.3.02",
            EditorTextureSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            new ArrayList<>(selectors.values()),
            EditorTextureReplacementDiagnosticTest.class.getClassLoader()
        );
    }

    private static void addAliases(
        final Map<String, StaticSelector> selectors,
        final Set<String> aliases
    ) {
        for (final String alias : aliases) {
            selectors.putIfAbsent(alias, StaticSelector.classSelector(alias, internal(Object.class)));
        }
    }

    private static void putStaticMethod(
        final Map<String, StaticSelector> selectors,
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        selectors.put(alias, StaticSelector.staticMethod(
            alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
        ));
    }

    private static final class CallSiteFixture {
        final CallSiteSource source;
        final CallSiteModel model;
        final CallSiteDocument document;
        final List<CallSiteWrapper> wrappers;
        final MutableModelImage modelImage;
        final VerifiedMemberResolver resolver;
        final Path stage;
        final CallSiteNativeProcess nativeProcess = CallSiteNativeProcess.INSTANCE;

        CallSiteFixture(
            final CallSiteSource source,
            final CallSiteModel model,
            final CallSiteDocument document,
            final List<CallSiteWrapper> wrappers,
            final MutableModelImage modelImage,
            final VerifiedMemberResolver resolver,
            final Path stage
        ) {
            this.source = source;
            this.model = model;
            this.document = document;
            this.wrappers = wrappers;
            this.modelImage = modelImage;
            this.resolver = resolver;
            this.stage = stage;
        }
    }

    private static final class CallSiteAppController {
        private static final CallSiteAppController INSTANCE = new CallSiteAppController();
        static CallSiteDocument currentDocument;

        public static CallSiteAppController instance() {
            return INSTANCE;
        }

        public CallSiteDocument currentDocument() {
            return currentDocument;
        }
    }

    private static final class CallSiteDocument {
        private final CallSiteEditMode editMode = new CallSiteEditMode();

        public CallSiteEditMode editMode() {
            return editMode;
        }
    }

    private static final class CallSiteEditMode {
        public boolean isEditing() {
            return false;
        }
    }

    public static final class CallSiteParsed {
        final File source;

        CallSiteParsed(final File source) {
            this.source = source;
        }
    }

    public static final class CallSiteCompanion {
        public CallSiteParsed parseFile(
            final File source,
            final boolean first,
            final boolean second
        ) {
            if (!source.isFile() || source.length() == 0) return null;
            return new CallSiteParsed(source);
        }
    }

    public static final class CallSitePsdDocument {
        public static final CallSiteCompanion companion = new CallSiteCompanion();
    }

    private static final class CallSiteId {
        private final String value;

        CallSiteId(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private static final class CallSiteLayeredImage {
        static int failIncomingGuidReadAt;
        static int incomingGuidReads;
        static int saveCalls;
        private final CallSiteId guid;
        private final String name;
        private final File psdFile;
        private final CallSitePsdDocument psdDocument = new CallSitePsdDocument();

        CallSiteLayeredImage(final String id, final String name) {
            this.guid = new CallSiteId(id);
            this.name = name;
            this.psdFile = new File("/" + id + ".psd");
        }

        CallSiteLayeredImage(
            final CallSiteParsed parsed,
            final File source,
            final String name
        ) {
            if (parsed == null || !parsed.source.equals(source)) {
                throw new IllegalStateException("wrong parsed stage");
            }
            this.guid = new CallSiteId("incoming");
            this.name = name;
            this.psdFile = source;
        }

        public CallSiteId getGuid() {
            if ("incoming".equals(guid.value) && ++incomingGuidReads == failIncomingGuidReadAt) {
                throw new IllegalStateException("incoming diagnostic identity failure");
            }
            return guid;
        }

        public String getName() {
            return name;
        }

        public int getWidth() {
            return 100;
        }

        public int getHeight() {
            return 100;
        }

        public File getPsdFile() {
            return psdFile;
        }

        public CallSitePsdDocument getPsdDoc() {
            return psdDocument;
        }

        public List<?> getChildren() {
            return List.of();
        }

        public void save(final File target, final CallSiteProgress progress) {
            if (progress == null) throw new IllegalStateException("missing export progress");
            saveCalls++;
            try {
                Files.writeString(target.toPath(), "synthetic-export");
            } catch (Exception failure) {
                throw new IllegalStateException("synthetic export failed", failure);
            }
        }
    }

    public static final class CallSiteProgress {
    }

    public static final class CallSiteProgressFactory {
        private CallSiteProgressFactory() {
        }

        public static CallSiteProgress e() {
            return new CallSiteProgress();
        }
    }

    private static final class CallSiteWrapper {
        private final CallSiteLayeredImage image;
        private final String importTime;
        private final String modifiedTime;
        private final boolean replaced;

        CallSiteWrapper(
            final CallSiteLayeredImage image,
            final String importTime,
            final String modifiedTime,
            final boolean replaced
        ) {
            this.image = image;
            this.importTime = importTime;
            this.modifiedTime = modifiedTime;
            this.replaced = replaced;
        }

        public CallSiteLayeredImage image() {
            return image;
        }

        public String getImportTime() {
            return importTime;
        }

        public String getModifiedTime() {
            return modifiedTime;
        }

        public boolean isReplaced() {
            return replaced;
        }
    }

    private static final class CallSiteFilterEnv {
        static int hasReads;
        static int throwOnHasRead;
        static int throwOnHasReadLinkageError;

        private final boolean hasLayerInputData;
        private final CallSiteSelectorMap layerInputData;
        private final boolean hasCurrentImageGuid;
        private final CallSiteId currentImageGuid;

        CallSiteFilterEnv(
            final boolean hasLayerInputData,
            final CallSiteSelectorMap layerInputData,
            final boolean hasCurrentImageGuid,
            final CallSiteId currentImageGuid
        ) {
            this.hasLayerInputData = hasLayerInputData;
            this.layerInputData = layerInputData;
            this.hasCurrentImageGuid = hasCurrentImageGuid;
            this.currentImageGuid = currentImageGuid;
        }

        public boolean getHasLayerInputData() {
            hasReads++;
            if (throwOnHasRead == hasReads) {
                throw new IllegalStateException("diagnostic filter observation failure");
            }
            if (throwOnHasReadLinkageError == hasReads) {
                throwOnHasReadLinkageError = 0;
                throw new NoClassDefFoundError("diagnostic filter linkage failure");
            }
            return hasLayerInputData;
        }

        public CallSiteSelectorMap getLayerInputData() {
            return layerInputData;
        }

        public boolean getHasCurrentImageGuid() {
            return hasCurrentImageGuid;
        }

        public CallSiteId getCurrentImageGuid() {
            return currentImageGuid;
        }
    }

    private static final class CallSiteSelectorMap {
        private final Map<CallSiteId, List<?>> imageToLayerInput;

        CallSiteSelectorMap(final Map<CallSiteId, List<?>> imageToLayerInput) {
            this.imageToLayerInput = imageToLayerInput;
        }

        public Map<CallSiteId, List<?>> getImageToLayerInput() {
            return imageToLayerInput;
        }
    }

    private static class CallSiteModelImage {
        private final CallSiteId guid;
        private List<CallSiteId> linkedRawImageGuids;
        private CallSiteFilterEnv inputFilterEnv;

        CallSiteModelImage(final CallSiteLayeredImage old) {
            guid = new CallSiteId("model-a");
            linkedRawImageGuids = List.of(old.getGuid());
            inputFilterEnv = new CallSiteFilterEnv(
                true,
                new CallSiteSelectorMap(Map.of()),
                true,
                old.getGuid()
            );
        }

        public CallSiteId getGuid() {
            return guid;
        }

        public String getName() {
            return "Image";
        }

        public int getWidth() {
            return 100;
        }

        public int getHeight() {
            return 100;
        }

        public List<CallSiteId> getLinkedRawImageGuids() {
            return linkedRawImageGuids;
        }

        public CallSiteFilterEnv getInputFilterEnv() {
            return inputFilterEnv;
        }

        void replace(final CallSiteId incoming) {
            linkedRawImageGuids = List.of(incoming);
            inputFilterEnv = new CallSiteFilterEnv(
                true,
                new CallSiteSelectorMap(Map.of()),
                true,
                incoming
            );
        }
    }

    private static final class MutableModelImage extends CallSiteModelImage {
        MutableModelImage(final CallSiteLayeredImage old) {
            super(old);
        }
    }

    private static final class CallSiteGroup {
        private final String name;
        private final String memo;
        private final List<CallSiteModelImage> modelImages;
        private final List<CallSiteId> linkedRawImageGuids;

        CallSiteGroup(
            final String name,
            final String memo,
            final List<CallSiteModelImage> modelImages,
            final List<CallSiteId> linkedRawImageGuids
        ) {
            this.name = name;
            this.memo = memo;
            this.modelImages = modelImages;
            this.linkedRawImageGuids = linkedRawImageGuids;
        }

        public String getGroupName() {
            return name;
        }

        public String getMemo() {
            return memo;
        }

        public List<CallSiteModelImage> getModelImages() {
            return modelImages;
        }

        public List<CallSiteId> getLinkedRawImageGuids() {
            return linkedRawImageGuids;
        }
    }

    private static final class CallSiteTextureManager {
        private final List<CallSiteWrapper> rawImages;
        private final List<CallSiteGroup> modelImageGroups;
        private final List<CallSiteModelImage> allModelImages;
        private final List<?> textureAtlases;
        private final List<CallSiteGroup> artMeshUsableModelImageGroups;

        CallSiteTextureManager(
            final List<CallSiteWrapper> rawImages,
            final List<CallSiteGroup> modelImageGroups,
            final List<CallSiteModelImage> allModelImages,
            final List<?> textureAtlases,
            final List<CallSiteGroup> artMeshUsableModelImageGroups
        ) {
            this.rawImages = rawImages;
            this.modelImageGroups = modelImageGroups;
            this.allModelImages = allModelImages;
            this.textureAtlases = textureAtlases;
            this.artMeshUsableModelImageGroups = artMeshUsableModelImageGroups;
        }

        public List<CallSiteWrapper> rawImages() {
            return rawImages;
        }

        public List<CallSiteGroup> modelImageGroups() {
            return modelImageGroups;
        }

        public List<CallSiteModelImage> allModelImages() {
            return allModelImages;
        }

        public List<?> textureAtlases() {
            return textureAtlases;
        }

        public List<CallSiteGroup> artMeshUsableModelImageGroups() {
            return artMeshUsableModelImageGroups;
        }
    }

    private static final class CallSiteSource {
        private final CallSiteTextureManager textureManager;

        CallSiteSource(final CallSiteTextureManager textureManager) {
            this.textureManager = textureManager;
        }

        public CallSiteTextureManager textureManager() {
            return textureManager;
        }

        public List<?> allArtMeshes() {
            return List.of();
        }
    }

    private static final class CallSiteModel {
        public List<?> allArtMeshes() {
            return List.of();
        }
    }

    public static final class CallSiteNativeProcess {
        enum NativeMutation {
            CORRECT_INCOMING,
            OLD_UNCHANGED,
            REGISTER_INCOMING_ONLY,
            WRONG_RAW,
            INCOMING_NOT_REGISTERED
        }

        public static final CallSiteNativeProcess INSTANCE = new CallSiteNativeProcess();
        static CallSiteFixture active;
        static int calls;
        static boolean fail;
        static NativeMutation mutation = NativeMutation.CORRECT_INCOMING;

        public void replace(
            final CallSiteAppController app,
            final CallSiteLayeredImage incoming,
            final File stage,
            final CallSiteDocument document,
            final List<?> targets
        ) {
            calls++;
            if (fail) throw new IllegalStateException("native call root cause");
            if (active == null) throw new IllegalStateException("missing call-site fixture");
            if (mutation != NativeMutation.OLD_UNCHANGED
                && mutation != NativeMutation.INCOMING_NOT_REGISTERED) {
                active.wrappers.add(new CallSiteWrapper(
                    incoming, "import-incoming", "modified-incoming", true
                ));
            }
            switch (mutation) {
                case CORRECT_INCOMING, REGISTER_INCOMING_ONLY -> {
                    if (mutation == NativeMutation.CORRECT_INCOMING) {
                        active.modelImage.replace(incoming.getGuid());
                    }
                }
                case OLD_UNCHANGED -> { }
                case INCOMING_NOT_REGISTERED -> active.modelImage.replace(incoming.getGuid());
                case WRONG_RAW -> {
                    final CallSiteLayeredImage third = new CallSiteLayeredImage("third", "Third");
                    active.wrappers.add(new CallSiteWrapper(
                        third, "import-third", "modified-third", true
                    ));
                    active.modelImage.replace(third.getGuid());
                }
            }
        }
    }

    private Path diagnosticArtifact() {
        return tempDir.resolve("logs").resolve(EditorTextureReplacementDiagnostic.ARTIFACT);
    }

}
