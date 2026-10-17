package dev.turboism.plugin.psdclipmaskimport;

import static dev.turboism.plugin.psdclipmaskimport.PsdClipMaskImportTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.StatusNotification;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PsdClipMaskImportServiceTest {

    @Test
    void cancelledPreviewReturnsCancelledResultWithZeroCountsAndZeroBatchCalls() {
        final RecordingHost host = new RecordingHost();
        host.ui.confirmResult = false;
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.CANCELLED, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failures());
        assertEquals(0, host.batches.size(), "a cancelled import must never call the backend");
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
        assertTrue(host.ui.notifications.get(0).message().contains("cancelled"));
        assertTrue(host.ui.notifications.get(0).message().contains("0 applied, 0 skipped, 0 failures"));
    }

    @Test
    void confirmedImportReturnsAppliedCountsAndCallsTheBackendOnceWithTheFullBatch() {
        final RecordingHost host = new RecordingHost();
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.APPLIED, result.outcome());
        assertEquals(3, result.applied());
        assertEquals(1, result.skipped());
        assertEquals(0, result.failures());
        assertEquals(1, host.batches.size(), "the whole plan must be committed as exactly one backend batch call");
        assertEquals(
                List.of(
                        new ClipMaskReplacement(TARGET_A, List.of(), false, List.of(MASK_A, MASK_B), false),
                        new ClipMaskReplacement(TARGET_B, List.of(), false, List.of(MASK_A, MASK_B), false),
                        new ClipMaskReplacement(
                                TARGET_OVERWRITE, List.of(MASK_B), false, List.of(MASK_A, MASK_B), false)),
                host.batches.get(0),
                "assignments carry an empty expected state and conflicts carry the existing state");
        assertEquals("INFO", host.ui.notifications.get(0).severity());
        assertEquals(
                "psd.clip-mask-import.import",
                host.ui.notifications.get(0).id(),
                "a confirmed and committed import reports .import even when conflicts were overwritten");
        assertTrue(host.ui.notifications.get(0).message().contains("3 applied, 1 skipped, 0 failures"));
    }

    @Test
    void previewListsTargetsMasksAllContributingSourcesConflictsAndSkips() {
        final RecordingHost host = new RecordingHost();
        final PsdClipMaskImportService service = service(host);

        service.importClipMasks();

        final DialogRequest preview = host.ui.lastPreview;
        assertEquals("psd-clip-mask-import.preview", preview.id());
        assertTrue(
                preview.body().contains("ArtMeshA -> [ArtMeshMaskA, ArtMeshMaskB]"),
                "localized relationship rows keep target and mask values visible");
        assertTrue(
                preview.body().contains("sources: psd-face/clipped-a, psd-face/clipped-dupe"),
                "localized relationship rows keep source refs visible");
        assertTrue(preview.body().contains("OVERWRITE REQUIRED"));
        assertTrue(preview.body().contains("ArtMeshOverwrite"));
        assertTrue(
                preview.body().contains("currently [ArtMeshMaskB] not inverted"),
                "localized conflict rows keep target, existing masks and the inversion word visible");
        assertTrue(
                preview.body().contains("-> [ArtMeshMaskA, ArtMeshMaskB] not inverted"),
                "localized conflict rows show the planned non-inverted state");
        assertTrue(
                preview.body().contains("1 relationship was skipped, never written:"),
                "the skipped heading uses the localized singular template");
        assertTrue(
                preview.body()
                        .contains(
                                "NotInModel (sources: psd-face/clipped-missing): target ArtMesh does not exist in the model"),
                "skip rows must show the localized reason plus target and source data");
        assertFalse(
                preview.body().contains("ArtMesh is bound to the PSD layer but does not exist in the model."),
                "planner-owned English skip detail must never reach the localized preview");
        assertFalse(
                preview.body().contains("psd.clip-mask-import."),
                "preview body must be formatted copy, never raw localization keys");
    }

    @Test
    void onlyMissingMaskPlanReportsLocalizedSkipInTheNoWriteNotification() {
        final RecordingHost host = new RecordingHost();
        host.psds.set(0, RecordingHost.psdWithMissingMask());
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.NO_WRITE, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(1, result.skipped());
        assertEquals(0, result.failures());
        assertEquals(0, host.batches.size(), "no-write plans must never call the backend");
        assertNull(host.ui.lastPreview, "only-skips plans must not open a confirmation dialog");
        final StatusNotification notification = host.ui.notifications.get(0);
        assertEquals("psd.clip-mask-import.no-write", notification.id());
        assertEquals("INFO", notification.severity());
        final String message = notification.message();
        assertTrue(
                message.contains("All PSD clip-mask relationship candidates were skipped"),
                "all-skipped wording must be distinct from no-candidates: " + message);
        assertFalse(message.contains("No PSD clip-mask relationship candidates were found"), message);
        assertTrue(message.contains("ArtMeshA"), "skip target must stay visible: " + message);
        assertTrue(message.contains("psd-face/clipped-missing"), "source layer must be visible: " + message);
        assertTrue(
                message.contains("one or more masks do not exist in the model"),
                "the skip reason must be the localized copy: " + message);
        assertFalse(
                message.contains("MASK_UNRESOLVED"), "raw enum names must not leak into localized copy: " + message);
        assertFalse(
                message.contains("Mask identity does not exist in the model"),
                "planner-owned English skip detail must never reach localized copy: " + message);
        assertFalse(
                message.contains("NotInModel"),
                "missing-mask data exists only in the planner's English detail and must not leak: " + message);
        assertFalse(
                message.contains("psd.clip-mask-import."),
                "the notification must be formatted copy, never raw localization keys: " + message);
        assertTrue(message.contains("0 applied, 1 skipped, 0 failures"), message);
    }

    @Test
    void noWriteNotificationCapsSkipDetailsAndReportsTheOmittedCount() {
        final RecordingHost host = new RecordingHost();
        final PsdClipMaskImportService service = service(host);
        final List<PsdClipMaskPlan.Skip> skips = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            skips.add(new PsdClipMaskPlan.Skip(
                    new ArtMeshId("Skipped" + index),
                    List.of(new PsdClipMaskPlan.SourceRef("psd-face", "layer-" + index)),
                    PsdClipMaskPlan.SkipReason.ALREADY_MATCHES,
                    "already matches"));
        }
        final PsdClipMaskPlan plan = new PsdClipMaskPlan(List.of(), List.of(), skips);
        final PsdClipMaskImportService.ImportResult result = new PsdClipMaskImportService.ImportResult(
                PsdClipMaskImportService.ImportOutcome.NO_WRITE, 0, skips.size(), 0);

        final String message = service.noWriteText(plan, result);

        assertTrue(message.contains("Skipped0"), message);
        assertTrue(message.contains("Skipped9"), message);
        assertFalse(message.contains("Skipped10"), message);
        assertFalse(message.contains("Skipped11"), message);
        assertTrue(message.contains("2 additional skipped relationships omitted"), message);
        assertTrue(message.contains("0 applied, 12 skipped, 0 failures"), message);
    }

    @ParameterizedTest(name = "mutation after preview aborts: {0}")
    @ValueSource(strings = {"plan_change", "inversion_change", "active_identity_change", "active_editor_model_change"})
    void mutationAfterPreviewAbortsWithZeroWritesAndZeroBatchCalls(String mutationType) {
        final RecordingHost host = new RecordingHost();
        host.ui.onConfirm = () -> {
            switch (mutationType) {
                case "plan_change" -> host.psds.set(0, RecordingHost.psdWithoutRelationship());
                case "inversion_change" -> host.invertedState.put(TARGET_OVERWRITE, true);
                case "active_identity_change" -> host.context.activeDocumentId = "document-2";
                case "active_editor_model_change" -> host.activeModelId = new ModelId("model-b");
            }
        };
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(1, result.failures());
        assertEquals(0, host.batches.size(), "mutation after preview must abort before any backend call");
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
        if ("plan_change".equals(mutationType)) {
            assertEquals(0, result.skipped());
            assertTrue(host.ui.notifications.get(0).message().contains("0 applied, 0 skipped, 1 failure"));
        }
    }

    @Test
    void invertedOverwriteConflictPreviewShowsTheInversionTransition() {
        final RecordingHost host = new RecordingHost();
        host.invertedState.put(TARGET_OVERWRITE, true);
        final PsdClipMaskImportService service = service(host);

        service.importClipMasks();

        final DialogRequest preview = host.ui.lastPreview;
        assertTrue(
                preview.body().contains("currently [ArtMeshMaskB] inverted"),
                "existing inversion must read through the localized word");
        assertTrue(
                preview.body().contains("-> [ArtMeshMaskA, ArtMeshMaskB] not inverted"),
                "planned state must read through the localized not-inverted word");
        assertFalse(
                preview.body().contains("inverted=true"),
                "raw boolean transitions must not leak into the localized preview");
    }

    @Test
    void syntheticDocumentSnapshotModelIdDoesNotRejectTheActiveEditorModel() {
        final RecordingHost host = new RecordingHost();
        host.context.activeDocumentModelId = "runtime-synthetic-model-identity";
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.APPLIED, result.outcome());
        assertEquals(3, result.applied());
        assertEquals(0, result.failures());
        assertEquals(
                1,
                host.batches.size(),
                "document snapshots and editor models use different model-id namespaces on the host");
    }

    @Test
    void missingDocumentModelFailsClosedWithoutCallingTheBackend() {
        final RecordingHost host = new RecordingHost();
        host.context.documentHasModel = false;
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(1, result.failures());
        assertEquals(0, host.batches.size());
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
    }

    @Test
    void backendRejectionReturnsFailedResultWithCountsAndSanitizedMessage() {
        final RecordingHost host = new RecordingHost();
        host.backendFailure = new IllegalStateException("com.live2d.cubism.clipmask.secret-internal-detail");
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(1, result.skipped());
        assertEquals(1, result.failures());
        assertEquals(0, host.batches.size(), "a rejected batch must never be recorded as applied");
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
        final String message = host.ui.notifications.get(0).message();
        assertTrue(message.contains("failed safely"), message);
        assertTrue(message.contains("0 applied, 1 skipped, 1 failure"), message);
        assertFalse(message.contains("com.live2d"), message);
        assertFalse(message.contains("secret-internal-detail"), message);
    }

    @Test
    void rawHostExceptionTextIsNeverExposedToThePluginUi() {
        final RecordingHost host = new RecordingHost();
        host.modelFailure = new IllegalStateException("com.live2d.cubism.secret-internal-detail: file C:\\host\\path");
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(1, result.failures());
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
        final String message = host.ui.notifications.get(0).message();
        assertFalse(message.contains("com.live2d"), message);
        assertFalse(message.contains("secret-internal-detail"), message);
        assertFalse(message.contains("C:\\host"), message);
        assertEquals(0, host.batches.size());
    }

    @Test
    void emptyCandidateSetReturnsNoWriteResultWithZeroCounts() {
        final RecordingHost host = new RecordingHost();
        host.psds.clear();
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.NO_WRITE, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failures());
        assertEquals(0, host.batches.size());
        assertEquals("INFO", host.ui.notifications.get(0).severity());
        assertTrue(host.ui.notifications.get(0).message().contains("nothing was written"));
        assertTrue(host.ui.notifications.get(0).message().contains("0 applied, 0 skipped, 0 failures"));
    }

    @Test
    void noResolvedMasksOnlyPlanReturnsNoWriteWithSkippedCount() {
        final RecordingHost host = new RecordingHost();
        host.psds.set(0, RecordingHost.psdWithSelfOnlyMasks());
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.NO_WRITE, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(1, result.skipped());
        assertEquals(0, result.failures());
        assertEquals(0, host.batches.size());
        assertTrue(host.ui.notifications.get(0).message().contains("1 skipped"));
    }

    @Test
    void everySkipReasonHasLocalizedCopyInTheBaseCatalog() {
        final FakeLocalization localization = new FakeLocalization();
        for (PsdClipMaskPlan.SkipReason reason : PsdClipMaskPlan.SkipReason.values()) {
            final String key = PsdClipMaskImportService.skipReasonKey(reason);
            assertTrue(localization.contains(key), "missing base catalog key " + key);
            final String copy = localization.text(key);
            assertFalse(copy.isBlank(), "blank localized reason for " + reason);
            assertFalse(copy.startsWith("psd."), "reason must be translated copy, not a key name: " + copy);
            assertTrue(
                    localization.format(key).equals(copy),
                    "plain reason keys must survive MessageFormat formatting unchanged");
        }
    }
}
