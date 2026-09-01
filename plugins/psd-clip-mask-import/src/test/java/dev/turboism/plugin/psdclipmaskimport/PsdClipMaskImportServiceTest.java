package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PsdClipMaskImportServiceTest {

    private static final ArtMeshId TARGET_A = new ArtMeshId("ArtMeshA");
    private static final ArtMeshId TARGET_B = new ArtMeshId("ArtMeshB");
    private static final ArtMeshId TARGET_OVERWRITE = new ArtMeshId("ArtMeshOverwrite");
    private static final ArtMeshId MISSING = new ArtMeshId("NotInModel");
    private static final ArtMeshId MASK_A = new ArtMeshId("ArtMeshMaskA");
    private static final ArtMeshId MASK_B = new ArtMeshId("ArtMeshMaskB");

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
        assertEquals(1, host.batches.size(),
            "the whole plan must be committed as exactly one backend batch call");
        assertEquals(List.of(
            new ClipMaskReplacement(TARGET_A, List.of(), false, List.of(MASK_A, MASK_B), false),
            new ClipMaskReplacement(TARGET_B, List.of(), false, List.of(MASK_A, MASK_B), false),
            new ClipMaskReplacement(
                TARGET_OVERWRITE, List.of(MASK_B), false, List.of(MASK_A, MASK_B), false
            )
        ), host.batches.get(0),
            "assignments carry an empty expected state and conflicts carry the existing state");
        assertEquals("INFO", host.ui.notifications.get(0).severity());
        assertEquals("psd.clip-mask-import.import", host.ui.notifications.get(0).id(),
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
        assertTrue(preview.body().contains("ArtMeshA -> [ArtMeshMaskA, ArtMeshMaskB]"),
            "localized relationship rows keep target and mask values visible");
        assertTrue(preview.body().contains("sources: psd-face/clipped-a, psd-face/clipped-dupe"),
            "localized relationship rows keep source refs visible");
        assertTrue(preview.body().contains("OVERWRITE REQUIRED"));
        assertTrue(preview.body().contains("ArtMeshOverwrite"));
        assertTrue(preview.body().contains("currently [ArtMeshMaskB] not inverted"),
            "localized conflict rows keep target, existing masks and the inversion word visible");
        assertTrue(preview.body().contains("-> [ArtMeshMaskA, ArtMeshMaskB] not inverted"),
            "localized conflict rows show the planned non-inverted state");
        assertTrue(preview.body().contains("1 relationship was skipped, never written:"),
            "the skipped heading uses the localized singular template");
        assertTrue(preview.body().contains(
            "NotInModel (sources: psd-face/clipped-missing): target ArtMesh does not exist in the model"),
            "skip rows must show the localized reason plus target and source data");
        assertFalse(preview.body().contains(
            "ArtMesh is bound to the PSD layer but does not exist in the model."),
            "planner-owned English skip detail must never reach the localized preview");
        assertFalse(preview.body().contains("psd.clip-mask-import."),
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
        assertTrue(message.contains("All PSD clip-mask relationship candidates were skipped"),
            "all-skipped wording must be distinct from no-candidates: " + message);
        assertFalse(message.contains("No PSD clip-mask relationship candidates were found"), message);
        assertTrue(message.contains("ArtMeshA"), "skip target must stay visible: " + message);
        assertTrue(message.contains("psd-face/clipped-missing"), "source layer must be visible: " + message);
        assertTrue(message.contains("one or more masks do not exist in the model"),
            "the skip reason must be the localized copy: " + message);
        assertFalse(message.contains("MASK_UNRESOLVED"),
            "raw enum names must not leak into localized copy: " + message);
        assertFalse(message.contains("Mask identity does not exist in the model"),
            "planner-owned English skip detail must never reach localized copy: " + message);
        assertFalse(message.contains("NotInModel"),
            "missing-mask data exists only in the planner's English detail and must not leak: " + message);
        assertFalse(message.contains("psd.clip-mask-import."),
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
                "already matches"
            ));
        }
        final PsdClipMaskPlan plan = new PsdClipMaskPlan(List.of(), List.of(), skips);
        final PsdClipMaskImportService.ImportResult result =
            new PsdClipMaskImportService.ImportResult(
                PsdClipMaskImportService.ImportOutcome.NO_WRITE,
                0,
                skips.size(),
                0
            );

        final String message = service.noWriteText(plan, result);

        assertTrue(message.contains("Skipped0"), message);
        assertTrue(message.contains("Skipped9"), message);
        assertFalse(message.contains("Skipped10"), message);
        assertFalse(message.contains("Skipped11"), message);
        assertTrue(message.contains("2 additional skipped relationships omitted"), message);
        assertTrue(message.contains("0 applied, 12 skipped, 0 failures"), message);
    }

    @Test
    void planChangeAfterPreviewAbortsWithZeroWritesAndZeroBatchCalls() {
        final RecordingHost host = new RecordingHost();
        host.ui.onConfirm = () -> host.psds.set(0, RecordingHost.psdWithoutRelationship());
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(0, result.skipped());
        assertEquals(1, result.failures());
        assertEquals(0, host.batches.size(),
            "a plan change between preview and commit must abort with zero backend calls");
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
        assertTrue(host.ui.notifications.get(0).message().contains("0 applied, 0 skipped, 1 failure"));
    }

    @Test
    void inversionChangeAfterPreviewAbortsWithZeroWritesAndZeroBatchCalls() {
        final RecordingHost host = new RecordingHost();
        host.ui.onConfirm = () -> host.invertedState.put(TARGET_OVERWRITE, true);
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(1, result.failures());
        assertEquals(0, host.batches.size(),
            "an inversion change between preview and commit must abort with zero backend calls");
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
    }

    @Test
    void invertedOverwriteConflictPreviewShowsTheInversionTransition() {
        final RecordingHost host = new RecordingHost();
        host.invertedState.put(TARGET_OVERWRITE, true);
        final PsdClipMaskImportService service = service(host);

        service.importClipMasks();

        final DialogRequest preview = host.ui.lastPreview;
        assertTrue(preview.body().contains("currently [ArtMeshMaskB] inverted"),
            "existing inversion must read through the localized word");
        assertTrue(preview.body().contains("-> [ArtMeshMaskA, ArtMeshMaskB] not inverted"),
            "planned state must read through the localized not-inverted word");
        assertFalse(preview.body().contains("inverted=true"),
            "raw boolean transitions must not leak into the localized preview");
    }

    @Test
    void activeIdentityChangeAfterPreviewFailsClosedWithoutCallingTheBackend() {
        final RecordingHost host = new RecordingHost();
        host.ui.onConfirm = () -> host.context.activeDocumentId = "document-2";
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(1, result.failures());
        assertEquals(0, host.batches.size(),
            "identity mismatch must abort before any backend call");
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
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
        assertEquals(1, host.batches.size(),
            "document snapshots and editor models use different model-id namespaces on the host");
    }

    @Test
    void activeEditorModelChangeAfterPreviewFailsClosedWithoutCallingTheBackend() {
        final RecordingHost host = new RecordingHost();
        host.ui.onConfirm = () -> host.activeModelId = new ModelId("model-b");
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(1, result.failures());
        assertEquals(0, host.batches.size(),
            "an active editor-model change must abort before any backend call");
        assertEquals("WARNING", host.ui.notifications.get(0).severity());
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
        host.backendFailure = new IllegalStateException(
            "com.live2d.cubism.clipmask.secret-internal-detail"
        );
        final PsdClipMaskImportService service = service(host);

        final PsdClipMaskImportService.ImportResult result = service.importClipMasks();

        assertEquals(PsdClipMaskImportService.ImportOutcome.FAILED, result.outcome());
        assertEquals(0, result.applied());
        assertEquals(1, result.skipped());
        assertEquals(1, result.failures());
        assertEquals(0, host.batches.size(),
            "a rejected batch must never be recorded as applied");
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
        host.modelFailure = new IllegalStateException(
            "com.live2d.cubism.secret-internal-detail: file C:\\host\\path"
        );
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
            assertFalse(copy.startsWith("psd."),
                "reason must be translated copy, not a key name: " + copy);
            assertTrue(localization.format(key).equals(copy),
                "plain reason keys must survive MessageFormat formatting unchanged");
        }
    }

    private static PsdClipMaskImportService service(final RecordingHost host) {
        return new PsdClipMaskImportService(host.modelAccess, host.context, host.ui);
    }

    private static final class RecordingHost {
        final RecordingUiHost ui = new RecordingUiHost();
        final RecordingPluginContext context = new RecordingPluginContext();
        final CubismModelAccess modelAccess;
        final List<PsdClipMaskDocumentSnapshot> psds = new ArrayList<>(List.of(psdFace()));
        final java.util.Map<ArtMeshId, Boolean> invertedState = new java.util.HashMap<>();
        final List<List<ClipMaskReplacement>> batches = new ArrayList<>();
        ModelId activeModelId = new ModelId("model-a");
        RuntimeException modelFailure;
        RuntimeException backendFailure;

        RecordingHost() {
            modelAccess = new CubismModelAccess() {
                @Override public CubismModel active() {
                    return new CubismModel() {
                @Override public ModelId id() { return activeModelId; }
                @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                    throw new UnsupportedOperationException();
                }
                @Override public dev.turboism.sdk.cubism.model.Parts parts() {
                    throw new UnsupportedOperationException();
                }
                @Override public Drawables drawables() {
                    final List<Drawable> drawables = List.of(
                        drawable(TARGET_A, List.of(), invertedState),
                        drawable(TARGET_B, List.of(), invertedState),
                        drawable(TARGET_OVERWRITE, List.of(MASK_B), invertedState),
                        drawable(MASK_A, List.of(), invertedState),
                        drawable(MASK_B, List.of(), invertedState)
                    );
                    return new Drawables() {
                        @Override public List<Drawable> all() { return drawables; }
                        @Override public Drawable find(final ArtMeshId requested) {
                            return drawables.stream().filter(d -> d.id().equals(requested)).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("no drawable " + requested));
                        }
                    };
                }
                @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
                    throw new UnsupportedOperationException();
                }
                @Override public dev.turboism.sdk.cubism.model.Glues glues() {
                    throw new UnsupportedOperationException();
                }
                @Override public void update() { throw new UnsupportedOperationException(); }
                @Override public List<PsdClipMaskDocumentSnapshot> psdDocuments() {
                    if (modelFailure != null) {
                        throw modelFailure;
                    }
                    return psds;
                }
                @Override public void replaceArtMeshClipMasks(
                    final List<ClipMaskReplacement> replacements
                ) {
                    if (backendFailure != null) {
                        throw backendFailure;
                    }
                    batches.add(List.copyOf(replacements));
                }
                    };
                }
            };
        }

        private static PsdClipMaskDocumentSnapshot psdFace() {
            return new PsdClipMaskDocumentSnapshot(
                "psd-face",
                "textures/face.psd",
                List.of(
                    new PsdLayerSnapshot("mask-base", "Mask Base", true,
                        List.of(MASK_A, MASK_B), Optional.empty(), List.of()),
                    new PsdLayerSnapshot("clipped-a", "Clipped A", true,
                        List.of(TARGET_A), Optional.of("mask-base"), List.of()),
                    new PsdLayerSnapshot("clipped-b", "Clipped B", true,
                        List.of(TARGET_B), Optional.of("mask-base"), List.of()),
                    new PsdLayerSnapshot("clipped-overwrite", "Clipped Overwrite", true,
                        List.of(TARGET_OVERWRITE), Optional.of("mask-base"), List.of()),
                    new PsdLayerSnapshot("clipped-missing", "Clipped Missing", true,
                        List.of(MISSING), Optional.of("mask-base"), List.of()),
                    new PsdLayerSnapshot("clipped-dupe", "Clipped Dupe", true,
                        List.of(TARGET_A), Optional.of("mask-base"), List.of())
                )
            );
        }

        static PsdClipMaskDocumentSnapshot psdWithMissingMask() {
            return new PsdClipMaskDocumentSnapshot(
                "psd-face",
                "textures/face.psd",
                List.of(
                    new PsdLayerSnapshot("mask-base", "Mask Base", true,
                        List.of(MASK_A, MISSING), Optional.empty(), List.of()),
                    new PsdLayerSnapshot("clipped-missing", "Clipped Missing Mask", true,
                        List.of(TARGET_A), Optional.of("mask-base"), List.of())
                )
            );
        }

        static PsdClipMaskDocumentSnapshot psdWithoutRelationship() {
            return new PsdClipMaskDocumentSnapshot(
                "psd-face",
                "textures/face.psd",
                List.of(
                    new PsdLayerSnapshot("plain-layer", "Plain", true,
                        List.of(MASK_A), Optional.empty(), List.of())
                )
            );
        }

        static PsdClipMaskDocumentSnapshot psdWithSelfOnlyMasks() {
            return new PsdClipMaskDocumentSnapshot(
                "psd-face",
                "textures/face.psd",
                List.of(
                    new PsdLayerSnapshot("self-base", "Self Base", true,
                        List.of(TARGET_A), Optional.empty(), List.of()),
                    new PsdLayerSnapshot("clipped-self", "Clipped Self", true,
                        List.of(TARGET_A), Optional.of("self-base"), List.of())
                )
            );
        }
    }

    private static Drawable drawable(
        final ArtMeshId id,
        final List<ArtMeshId> masks,
        final java.util.Map<ArtMeshId, Boolean> invertedState
    ) {
        return new Drawable() {
            @Override public ArtMeshId id() { return id; }
            @Override public byte constantFlag() { return 0; }
            @Override public byte dynamicFlag() { return 0; }
            @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
            @Override public int textureIndex() { return 0; }
            @Override public int drawOrder() { return 0; }
            @Override public int renderOrder() { return 0; }
            @Override public float getOpacity() { return 1.0F; }
            @Override public IntSequence masks() { return emptyInts(); }
            @Override public FloatSequence vertexPositions() { return emptyFloats(); }
            @Override public FloatSequence vertexUvs() { return emptyFloats(); }
            @Override public IntSequence indices() { return emptyInts(); }
            @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
            @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
            @Override public int parentPartIndex() { return -1; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return emptyInts(); }
            @Override public List<ArtMeshId> maskIds() { return masks; }
            @Override public boolean invertedMask() {
                return Boolean.TRUE.equals(invertedState.get(id));
            }
        };
    }

    private static final class RecordingPluginContext implements PluginContext {

        @Override public dev.turboism.sdk.i18n.PluginLocalization localization() {
            return new FakeLocalization();
        }
        String activeDocumentId = "document-1";
        String activeDocumentModelId = "model-a";
        boolean documentHasModel = true;

        @Override public PluginDescriptor descriptor() { return new TestPluginDescriptor(); }
        @Override public PluginLogger logger() {
            return new PluginLogger() {
                @Override public void debug(String message) { }
                @Override public void info(String message) { }
                @Override public void warn(String message) { }
                @Override public void error(String message) { }
                @Override public void error(String message, Throwable throwable) { }
            };
        }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public CubismFacade cubism() {
            return new CubismFacade() {
                @Override public CubismRuntimeSnapshot runtime() { throw new UnsupportedOperationException(); }
                @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
                @Override public Optional<DocumentSnapshot> activeDocument() {
                    return Optional.of(new DocumentSnapshot(
                        activeDocumentId, "Doc", "documents/" + activeDocumentId + ".cdi3.json",
                        Optional.empty(), documentHasModel ? Optional.of(new ModelSnapshot(
                            activeDocumentModelId, "Model A", List.of(), List.of(), List.of(), List.of()
                        )) : Optional.empty()
                    ));
                }
                @Override public Optional<ModelSnapshot> activeModel() {
                    return Optional.of(new ModelSnapshot(
                        "model-a", "Model A", List.of(), List.of(), List.of(), List.of()
                    ));
                }
                @Override public boolean isHostPresent() { return true; }
                @Override public CubismModelAccess model() { throw new UnsupportedOperationException(); }
                @Override public TransactionManager transactionManager() { throw new UnsupportedOperationException(); }
            };
        }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public ActionRegistry actions() { throw new UnsupportedOperationException(); }
        @Override public MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { throw new UnsupportedOperationException(); }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        final List<StatusNotification> notifications = new ArrayList<>();
        boolean confirmResult = true;
        Runnable onConfirm = () -> { };
        DialogRequest lastPreview;

        @Override public Registration contributeOverlay(final OverlayContribution contribution) {
            throw new UnsupportedOperationException();
        }
        @Override public Registration contributeBoundingBoxOverlayButton(final BoundingBoxOverlayButton contribution) {
            throw new UnsupportedOperationException();
        }
        @Override public ContextSourceSnapshot contextSource() { throw new UnsupportedOperationException(); }
        @Override public ViewportSnapshot viewport() { throw new UnsupportedOperationException(); }
        @Override public Registration openDialog(final DialogRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean confirmDialog(final DialogRequest request) {
            lastPreview = request;
            if (confirmResult) {
                onConfirm.run();
            }
            return confirmResult;
        }
        @Override public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
            throw new UnsupportedOperationException();
        }
        @Override public Registration contributeCollapsibleSection(
            final CollapsibleSectionContribution contribution
        ) {
            return () -> { };
        }
        @Override public Optional<String> requestFile(final FileChooserRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override public Registration notifyStatus(final StatusNotification notification) {
            notifications.add(notification);
            return () -> { };
        }
        @Override public Registration contributeContextMenu(
            final ContextMenuRegistry.ContextMenuContribution contribution
        ) {
            throw new UnsupportedOperationException();
        }
        @Override public Registration contributeMainToolbar(
            final MainToolbarRegistry.MainToolbarContribution contribution
        ) {
            throw new UnsupportedOperationException();
        }
        @Override public Registration contributePaletteToolbar(
            final PaletteToolbarRegistry.PaletteToolbarContribution contribution
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    /**
     * Test localization backed by the real base catalog on the test classpath.
     * {@code format} applies MessageFormat exactly like the production runtime,
     * so assertions prove real template formatting, never key-name output.
     */
    private static final class FakeLocalization implements dev.turboism.sdk.i18n.PluginLocalization {
        private final java.util.Map<String, String> catalog = loadBaseCatalog();

        @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
        @Override public String text(final String key) {
            return catalog.getOrDefault(key, key);
        }
        @Override public String format(final String key, final Object... arguments) {
            return new java.text.MessageFormat(catalog.getOrDefault(key, key), java.util.Locale.ENGLISH)
                .format(arguments);
        }
        @Override public boolean contains(final String key) { return catalog.containsKey(key); }

        private static java.util.Map<String, String> loadBaseCatalog() {
            final java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            try (java.io.InputStream stream = FakeLocalization.class.getClassLoader()
                    .getResourceAsStream("META-INF/turboism/i18n/messages.properties")) {
                if (stream == null) {
                    throw new IllegalStateException("base catalog missing from test classpath");
                }
                final java.util.Properties properties = new java.util.Properties();
                properties.load(stream);
                properties.forEach((key, value) -> values.put((String) key, (String) value));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("cannot load base catalog", failure);
            }
            return java.util.Map.copyOf(values);
        }
    }
}
