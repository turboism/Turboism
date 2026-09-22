package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocVersion;
import dev.turboism.sdk.cubism.core.OwnedCanvasInfo;
import dev.turboism.sdk.cubism.core.OwnedDeformer;
import dev.turboism.sdk.cubism.core.OwnedDrawable;
import dev.turboism.sdk.cubism.core.OwnedGlue;
import dev.turboism.sdk.cubism.core.OwnedMoc;
import dev.turboism.sdk.cubism.core.OwnedModel;
import dev.turboism.sdk.cubism.core.OwnedParameter;
import dev.turboism.sdk.cubism.core.OwnedPart;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault-injection matrix for {@link ProtectedExportOrchestrator} over a fake host.
 *
 * <p>The fake models the proven exact-host recipe: {@code openFile} rebinds the active
 * document, the copy gets its own deformer census, the apply command removes the
 * selector's selected deformer, and the re-driven native export walks inner-dialog
 * suppression, chooser redirect and worker completion back through the orchestrator's
 * real seams.</p>
 */
class ProtectedExportOrchestratorTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void publishesOnlyAfterValidationAndRestoration() throws Exception {
        final Fixture fixture = new Fixture();
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();

        assertTrue(orchestrator.requestExport(fixture.outerDialog));
        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();

        assertEquals(ProtectedExportOrchestrator.Phase.PUBLISHED, report.reached());
        assertTrue(report.published());
        assertEquals(2, report.publishedFiles().size());
        // The user's real pick directory received the published output.
        assertTrue(Files.isRegularFile(
            fixture.realPick.toPath().getParent().resolve("model.moc3")));
        assertTrue(Files.isRegularFile(
            fixture.realPick.toPath().getParent().resolve("model.model3.json")));
        // The original is the active document again; the copy is gone from the project.
        assertTrue(fixture.host.activeDoc == fixture.host.original);
        assertFalse(fixture.host.project.contains(fixture.host.copy));
        // The copy carried the obfuscated identities; the original is untouched.
        assertTrue(fixture.host.copy.model.artMeshes.stream()
            .allMatch(mesh -> mesh.name.matches("ArtMesh_[0-9a-f]{16,}")
                && mesh.drawableId.matches("@[0-9a-f]{16,}")));
        assertEquals("meshA", fixture.host.original.model.artMeshes.get(0).name);
        assertEquals("id-a", fixture.host.original.model.artMeshes.get(0).drawableId);
        // Task-owned staging is removed (root may remain but must be empty).
        assertTrue(!Files.exists(fixture.stagingRoot)
            || Files.list(fixture.stagingRoot).findAny().isEmpty());
        orchestrator.close();
    }

    @Test
    void publishesAndCleansWhenEditorMarksCopyReadOnly() throws Exception {
        final Fixture fixture = new Fixture();
        // The editor marks its saved copy read-only; a Windows JVM refuses
        // DeleteFile on it. Deletion must clear the attribute, not fail.
        fixture.host.copyFileMarkedReadOnlyOnOpen = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();

        assertTrue(orchestrator.requestExport(fixture.outerDialog));
        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();

        assertEquals(ProtectedExportOrchestrator.Phase.PUBLISHED, report.reached());
        assertTrue(report.published());
        assertTrue(!Files.exists(fixture.stagingRoot)
            || Files.list(fixture.stagingRoot).findAny().isEmpty());
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Obfuscation (M5)
    // ------------------------------------------------------------------

    @Test
    void rejectsWhenArtMeshDisappearsBeforeObfuscation() throws Exception {
        final Fixture fixture = new Fixture();
        // The copy census sees both meshes; the per-target apply cannot resolve
        // the first one again.
        fixture.host.vanishArtMeshAfterCensus = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.OBFUSCATE_FAILED_KEY,
            report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenStagedMocKeepsOriginalDrawableIds() throws Exception {
        final Fixture fixture = new Fixture();
        // The staged moc3 reports identities that were never planned — nothing
        // may publish.
        fixture.host.exportKeepsOriginalDrawableIds = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(report.failureKey().startsWith(
            ProtectedExportOrchestrator.VALIDATION_FAILED_KEY));
        assertFalse(report.published());
        assertFalse(Files.exists(
            fixture.realPick.toPath().getParent().resolve("model.moc3")));
        orchestrator.close();
    }

    @Test
    void rejectsWhenStagedMocRetainsDeformers() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.exportLeavesDeformer = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(report.failureKey().startsWith(
            ProtectedExportOrchestrator.VALIDATION_FAILED_KEY));
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenStagedMocParameterIdsDiverge() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.exportAddsParameter = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(report.failureKey().startsWith(
            ProtectedExportOrchestrator.VALIDATION_FAILED_KEY));
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenStagedGeometryDriftsFromEvaluatedCopy() throws Exception {
        // The exported model reproduces every structural contract (IDs, ranges,
        // key positions) but its evaluated geometry differs from the copy's —
        // only the sampled-behavior oracle can see it.
        final Fixture fixture = new Fixture();
        fixture.host.exportDriftsGeometry = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(report.failureKey().startsWith(
                ProtectedExportOrchestrator.VALIDATION_FAILED_KEY),
            "expected validation rejection, got " + report.failureKey());
        assertTrue(report.failureKey().contains("behavior-drift"),
            "expected behavior-drift detail, got " + report.failureKey());
        assertFalse(report.published());
        assertTrue(report.originalRestored());
        assertTrue(report.cleanupErrors().isEmpty());
        orchestrator.close();
    }

    @Test
    void rejectsWhenStagedGeometryIsNonFinite() throws Exception {
        // A staged drawable emitting NaN vertices must reject: delta checks
        // alone can never catch NaN because Math.abs(NaN - x) > tolerance
        // is false.
        final Fixture fixture = new Fixture();
        fixture.host.exportWritesNonFinite = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(report.failureKey().startsWith(
                ProtectedExportOrchestrator.VALIDATION_FAILED_KEY),
            "expected validation rejection, got " + report.failureKey());
        assertNotNull(report.failureDetail());
        assertTrue(report.failureDetail().contains("non-finite-output"),
            "expected non-finite-output detail, got " + report.failureDetail());
        assertFalse(report.published());
        assertTrue(report.originalRestored());
        assertTrue(report.cleanupErrors().isEmpty());
        orchestrator.close();
    }

    @Test
    void rejectsWhenStagedGeometryHasNoVertices() throws Exception {
        // An empty vertex array must not count as compared coverage.
        final Fixture fixture = new Fixture();
        fixture.host.exportWritesEmptyGeometry = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(report.failureKey().startsWith(
                ProtectedExportOrchestrator.VALIDATION_FAILED_KEY),
            "expected validation rejection, got " + report.failureKey());
        assertNotNull(report.failureDetail());
        assertTrue(report.failureDetail().contains("invalid-output-vertices"),
            "expected invalid-output-vertices detail, got " + report.failureDetail());
        assertFalse(report.published());
        assertTrue(report.originalRestored());
        assertTrue(report.cleanupErrors().isEmpty());
        orchestrator.close();
    }

    @Test
    void rejectsWhenFlattenChangesEvaluatedGeometry() throws Exception {
        // The pre-flatten baseline is captured on the bound copy before any
        // mutation; a flatten that diverges from it must reject BEFORE the
        // native export runs — proving the exporter faithfully serializes a
        // corrupted copy is not acceptance.
        final Fixture fixture = new Fixture();
        fixture.host.flattenCorruptsGeometry = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.BEHAVIOR_MISMATCH_KEY,
            report.failureKey());
        assertEquals(ProtectedExportOrchestrator.Phase.FAILED, report.reached());
        assertFalse(report.published());
        assertNull(fixture.host.exportedModel,
            "native export must not run when flatten changed behavior");
        assertTrue(report.originalRestored());
        assertTrue(report.cleanupErrors().isEmpty());
        orchestrator.close();
    }

    @Test
    void rejectsWhenUnboundDeformerDropsDeformation() throws Exception {
        // A deformer with no keyform bindings contributes nothing the host's
        // apply can preserve: bindings are copied at bound keys only, so an
        // unbound deformer with real deformation is silently dropped and the
        // behavior oracle must fail closed rather than publish a divergent
        // model.
        final Fixture fixture = new Fixture();
        fixture.host.unboundDeformerConstant = 7f;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.BEHAVIOR_MISMATCH_KEY,
            report.failureKey());
        assertFalse(report.published());
        assertNull(fixture.host.exportedModel,
            "native export must not run when flatten changed behavior");
        orchestrator.close();
    }

    @Test
    void restoresSampledParametersAfterCapture() throws Exception {
        // Sampling writes live parameter values on the copy; a document saved
        // with non-default values must get them back — the real export has to
        // run from the restored state, not from the last sample point.
        final Fixture fixture = new Fixture();
        fixture.host.copyParameterStartsOffDefault = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertTrue(report.published(),
            "expected publish, failed with " + report.failureKey());
        for (FakeParameter parameter : fixture.host.copy.model.parameters) {
            assertEquals(parameter.max, parameter.currentValue,
                "parameter " + parameter.id + " must be restored to its"
                    + " pre-capture value");
        }
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Admission and identity gates
    // ------------------------------------------------------------------

    @Test
    void rejectsWhenRedirectSeamNotInstalled() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.seamInstalled.set(false);
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertFalse(orchestrator.requestExport(fixture.outerDialog));
        orchestrator.close();
    }

    @Test
    void rejectsWhenPluginBindingUnavailable() throws Exception {
        final Fixture fixture = new Fixture();
        // Plugin already unloaded at decide time: the session arms, then the first
        // boundary check refuses — no copy is ever created.
        fixture.bindingLive.set(false);
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.NOT_ADMITTED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsStaleHostGenerationMidRun() throws Exception {
        final Fixture fixture = new Fixture();
        // The host epoch advances while the copy binds; the next boundary must refuse.
        fixture.host.generationBumpsOnBind = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.NOT_ADMITTED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenPluginBindingDiesMidRun() throws Exception {
        final Fixture fixture = new Fixture();
        // The binding check runs at every mutation boundary; flip it once bound.
        fixture.host.bindingDiesOnBind = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.NOT_ADMITTED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsSecondConcurrentArm() throws Exception {
        final Fixture fixture = new Fixture();
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        // First arm parks inside the (blocking) fake bind; a second request cannot displace it.
        fixture.host.bindDelayMillis = 5_000L;
        assertTrue(orchestrator.requestExport(fixture.outerDialog));
        assertFalse(orchestrator.requestExport(fixture.outerDialog));
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Copy binding and flattening
    // ------------------------------------------------------------------

    @Test
    void rejectsWhenCopyNeverBinds() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.bindCopyDoc = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.BIND_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenDocumentChangesBeforePreflight() throws Exception {
        final Fixture fixture = new Fixture();
        // The original leaves the project while the copy binds.
        fixture.host.originalRemovedOnBind = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.BIND_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenDeformerDisappearsBeforeApply() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.vanishDeformerAfterBindCensus = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.FLATTEN_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenDeformerSurvivesApply() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.applyRemovesDeformer = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.FLATTEN_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenSelectionCarriesForeignDeformer() throws Exception {
        final Fixture fixture = new Fixture();
        // The selector reports an extra unplanned deformer beside the target —
        // applying would consume a foreign object, so flatten must refuse.
        fixture.host.selectsExtraDeformer = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.FLATTEN_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        // The copy mutation never ran: all deformers survive on the copy model.
        assertEquals(3, fixture.host.copy.model.deformers.size());
        orchestrator.close();
    }

    @Test
    void rejectsWhenUnsupportedObjectEntersCensus() throws Exception {
        final Fixture fixture = new Fixture();
        // A glue-like unsupported family member inside getAllObjects must reject
        // the session at preflight — before any copy is created or written.
        fixture.host.original.model.unsupportedObjects.add(new Object());
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(fixture.host.copy == null, "no copy may be bound");
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    @Test
    void rejectsWhenPhysicsSettingsArePresent() throws Exception {
        final Fixture fixture = new Fixture();
        // Physics settings live outside getAllObjects and need their own gate.
        fixture.host.original.model.physicsSettings.add(new Object());
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(fixture.host.copy == null);
        orchestrator.close();
    }

    @Test
    void rejectsWhenMotionSyncSettingsArePresent() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.original.model.motionSyncSettings.add(new Object());
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenPartIdentityDriftsDuringFlatten() throws Exception {
        final Fixture fixture = new Fixture();
        // An out-of-band mutation renames a part while flatten runs — the
        // post-mutation census compares against the bound snapshot and refuses.
        fixture.host.mutatePartNameMidRun = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.OBFUSCATE_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsDuplicateDeformerIdentitiesAtPreflight() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.duplicateDeformerGuids = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsExtendedInterpolationAtPreflight() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.extendedInterpolation = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Export, staging, validation
    // ------------------------------------------------------------------

    @Test
    void rejectsWhenNativeDriverVetoesBeforeDialog() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.exportShowsDialog = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenWorkerWritesNothingToStaging() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.exportWritesFiles = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertTrue(report.failureKey().startsWith(
            ProtectedExportOrchestrator.VALIDATION_FAILED_KEY));
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenStagedMocFailsToLoad() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.mocLoads = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertTrue(report.failureKey().startsWith(
            ProtectedExportOrchestrator.VALIDATION_FAILED_KEY));
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenWorkerCallbackNeverArrives() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.exportCompletes = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_TIMEOUT_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void reportsCancelledWhenInnerDialogIsCancelled() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.cancelInnerDialog = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_CANCELLED_KEY, report.failureKey());
        assertEquals(ProtectedExportOrchestrator.Phase.CANCELLED, report.reached());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void reportsCancelledWhenChooserIsCancelled() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.cancelChooser = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_CANCELLED_KEY, report.failureKey());
        assertFalse(report.published());
        // AC07: a cancelled session still restores the original document and
        // removes every task-owned file — the report carries the proof.
        assertTrue(report.originalRestored(),
            "original document must be active and intact after cancellation");
        assertTrue(report.cleanedUp(),
            "cleanup errors: " + report.cleanupErrors());
        assertTrue(fixture.host.activeDoc == fixture.host.original);
        assertFalse(fixture.host.project.contains(fixture.host.copy));
        assertFalse(fixture.host.copy.file.exists(),
            "copy file must be deleted");
        assertTrue(fixture.destinationFiles().isEmpty(),
            "destination must stay untouched: " + fixture.destinationFiles());
        orchestrator.close();
    }

    @Test
    void restoresOriginalWhenExportTimesOut() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.exportCompletes = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_TIMEOUT_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(report.originalRestored());
        assertTrue(report.cleanedUp(), "cleanup errors: " + report.cleanupErrors());
        assertTrue(fixture.host.activeDoc == fixture.host.original);
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Restoration and cleanup
    // ------------------------------------------------------------------

    @Test
    void rejectsWhenOriginalCannotBeRestored() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.restoreOriginal = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.RESTORE_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        assertFalse(Files.exists(
            fixture.realPick.toPath().getParent().resolve("model.moc3")));
        orchestrator.close();
    }

    @Test
    void rejectsDirtyOriginalAtPreflight() throws Exception {
        final Fixture fixture = new Fixture();
        // A file copy cannot represent unsaved in-memory edits.
        fixture.host.original.modified = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenPublicationCannotWriteDestination() throws Exception {
        final Fixture fixture = new Fixture();
        // The real destination's parent is a regular file: publish must fail and
        // the staging area must still be cleaned.
        final Path blocked = tempDir.resolve("blocked-destination");
        Files.writeString(blocked, "not a directory");
        fixture.host.realPick = blocked.resolve("model.moc3").toFile();
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PUBLISH_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(!Files.exists(fixture.stagingRoot)
            || Files.list(fixture.stagingRoot).findAny().isEmpty());
        orchestrator.close();
    }

    @Test
    void publishRollbackFailureReportsRecoveryLocation() throws Exception {
        // The supervisor probe reproduced the gap: a place-move failure that
        // also breaks the rollback restore retains the scratch backup as the
        // only surviving copy of the user's original bytes — but the report
        // must name that recovery location, not a bare publish-failed key.
        final Fixture fixture = new Fixture();
        Files.writeString(fixture.realPick.toPath(), "ORIGINAL-USER-BYTES");
        fixture.publishMoveOp = (source, target) -> {
            if (source.toString().contains("incoming")
                && target.getFileName().toString().endsWith(".json")) {
                throw new IOException("injected place failure");
            }
            if (source.toString().contains("backups")) {
                throw new IOException("injected rollback failure");
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        };
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PUBLISH_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        final String detail = report.failureDetail();
        assertNotNull(detail, "publish failure must carry diagnostics");
        assertTrue(detail.contains("suppressed:"), detail);
        assertTrue(detail.contains(".turboism-publish-"),
            "retained recovery path must reach the report: " + detail);

        // The retained scratch under the destination still holds the user's
        // original bytes — the reported path is real, not a truncated stub.
        final Path destination = fixture.realPick.toPath().getParent();
        try (DirectoryStream<Path> scratches =
                Files.newDirectoryStream(destination, ".turboism-publish-*")) {
            boolean recovered = false;
            for (Path scratch : scratches) {
                try (var walk = Files.walk(scratch)) {
                    recovered |= walk.filter(Files::isRegularFile).anyMatch(path -> {
                        try {
                            return Files.readString(path)
                                .equals("ORIGINAL-USER-BYTES");
                        } catch (IOException failure) {
                            return false;
                        }
                    });
                }
            }
            assertTrue(recovered,
                "retained scratch must hold the user's original bytes");
        }
        orchestrator.close();
    }

    @Test
    void rejectsWhenCopyHandleCannotBeReleased() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.releaseCopyHandle = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.RESTORE_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Hidden structure families
    // ------------------------------------------------------------------

    @Test
    void rejectsModelFeatureInvisibleToCensus() throws Exception {
        final Fixture fixture = new Fixture();
        // Multiply colour lives on ArtMesh colour composition — the object
        // census sees only supported families, the host contain* gate must
        // still reject before any copy is written.
        fixture.host.multiplyColor = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(fixture.host.copy == null || !fixture.host.copy.file.exists(),
            "unsupported model must be refused before a copy is written");
        orchestrator.close();
    }

    @Test
    void rejectsMorphTargetsEmbeddedInsideArtMesh() throws Exception {
        final Fixture fixture = new Fixture();
        // A keyform morph-target set embedded in an ArtMesh source — the exact
        // hidden family the r19 fixture carried — must reject at admission even
        // though every census object is a supported category.
        fixture.host.embeddedMorphTargets = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsExtensionAttachedToDeformer() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.attachedExtension = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Selection reentrancy and mid-session revocation
    // ------------------------------------------------------------------

    @Test
    void refusesApplyWhenSelectionSwitchesActiveDocument() throws Exception {
        final Fixture fixture = new Fixture();
        // selectSource returns with the original document active again — the
        // supervisor probe reproduced an apply landing after exactly this
        // reentrancy. The apply must never run, and the original's dirty and
        // undo state must stay untouched.
        fixture.host.switchActiveDocOnSelect = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.FLATTEN_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        assertEquals(0, fixture.host.applyCalls.get(),
            "apply must not run after the selection switched documents");
        assertFalse(fixture.host.original.modified,
            "original dirty flag must stay untouched");
        assertTrue(report.originalRestored());
        assertTrue(report.cleanedUp(), "cleanup errors: " + report.cleanupErrors());
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    @Test
    void refusesPublishWhenPluginUnloadedDuringNativeExport() throws Exception {
        final Fixture fixture = new Fixture();
        // The plugin binding dies while the native modal flow runs — the
        // completion already fired, so this is exactly the probe scenario.
        fixture.host.revokeBindingOnNativeExport = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.REVOKED_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(fixture.destinationFiles().isEmpty(),
            "revoked session must not publish: " + fixture.destinationFiles());
        assertTrue(report.originalRestored());
        assertTrue(report.cleanedUp(), "cleanup errors: " + report.cleanupErrors());
        orchestrator.close();
    }

    @Test
    void refusesPublishWhenHostGenerationChangesDuringNativeExport()
            throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.bumpGenerationOnNativeExport = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.REVOKED_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    @Test
    void refusesPublishWhenOriginalDocumentClosesDuringNativeExport()
            throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.removeOriginalOnNativeExport = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.REVOKED_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(fixture.destinationFiles().isEmpty());
        // Teardown reopens the original file — restoration still proves out.
        assertTrue(report.originalRestored());
        assertTrue(report.cleanedUp(), "cleanup errors: " + report.cleanupErrors());
        orchestrator.close();
    }

    @Test
    void refusesPublishWhenCopyDocumentClosesDuringNativeExport()
            throws Exception {
        final Fixture fixture = new Fixture();
        fixture.host.removeCopyOnNativeExport = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.REVOKED_KEY, report.failureKey());
        assertFalse(report.published());
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    @Test
    void ignoresCompletionCallbackArrivingAfterTimeout() throws Exception {
        final Fixture fixture = new Fixture();
        // The native flow never completes — the session times out, disarms, and
        // clears the export window. A callback landing afterwards must be a
        // no-op: no second report and certainly no publication.
        fixture.host.exportCompletes = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_TIMEOUT_KEY, report.failureKey());
        assertFalse(report.published());
        assertNotNull(fixture.host.completion,
            "the fake must retain the gated completion callback");
        fixture.host.completion.accept(fixture.realPick, List.of("late.moc3"));
        assertNull(fixture.reports.poll(2L, TimeUnit.SECONDS),
            "a late callback must not produce another report");
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Fixture: fake host, inline EDT, blocking report queue
    // ------------------------------------------------------------------

    private static final ProtectedExportOrchestrator.EdtDispatcher INLINE_EDT =
        new ProtectedExportOrchestrator.EdtDispatcher() {
            @Override
            public <T> T call(final Callable<T> action) throws Exception {
                return action.call();
            }

            @Override
            public void submit(final Runnable task) {
                task.run();
            }
        };

    private final class Fixture {
        final FakeHost host = new FakeHost();
        final Object outerDialog = new Object();
        final File realPick;
        final Path stagingRoot;
        final AtomicLong hostGeneration = new AtomicLong(7L);
        final AtomicBoolean seamInstalled = new AtomicBoolean(true);
        final AtomicBoolean bindingLive = new AtomicBoolean(true);
        final BlockingQueue<ProtectedExportOrchestrator.Report> reports =
            new LinkedBlockingQueue<>();
        volatile boolean mocLoads = true;
        /** Injected into the staging move seam — drives real rollback paths. */
        volatile ProtectedExportStaging.MoveOp publishMoveOp;
        ProtectedExportOrchestrator orchestrator;

        Fixture() throws IOException {
            stagingRoot = tempDir.resolve("staging");
            final Path destinationDir = tempDir.resolve("destination");
            Files.createDirectories(destinationDir);
            realPick = destinationDir.resolve("model.moc3").toFile();
            host.original.file = tempDir.resolve("source.cmo3").toFile();
            Files.writeString(host.original.file.toPath(), "fixture-cmo3");
            host.activeDoc = host.original;
            host.project.add(host.original);
            host.project.add(host.otherDoc);
            host.realPick = realPick;
            host.bindingLiveFlag = bindingLive;
            host.hostGenerationFlag = hostGeneration;
            host.orchestratorRef = () -> orchestrator;
        }

        /** Live parameter values per instantiated fake model — the writer's target. */
        final IdentityHashMap<OwnedModel, Map<String, Float>> fakeModelValues =
            new IdentityHashMap<>();

        /**
         * Fake Core-parameter write seam mirroring {@code OwnedMocRuntime}'s
         * runtime-private path: writes the model's live value table, which
         * {@code drawables()} reads when re-projecting evaluated positions.
         */
        private void writeFakeParameter(
            final OwnedModel model,
            final String parameterId,
            final float value
        ) {
            final Map<String, Float> values = fakeModelValues.get(model);
            if (values == null || !values.containsKey(parameterId)) {
                throw new IllegalStateException("parameter absent: " + parameterId);
            }
            values.put(parameterId, value);
        }

        ProtectedExportOrchestrator orchestrator() {
            if (orchestrator == null) {
                orchestrator = new ProtectedExportOrchestrator(
                    host,
                    new ProtectedExportStaging(
                        data -> mocLoads ? fakeMoc() : null,
                        this::writeFakeParameter,
                        publishMoveOp != null ? publishMoveOp
                            : (source, target) -> Files.move(source, target,
                                StandardCopyOption.REPLACE_EXISTING)),
                    stagingRoot,
                    "dev.turboism.plugin.protected-export",
                    "protected-export",
                    seamInstalled::get,
                    bindingLive::get,
                    hostGeneration::get,
                    INLINE_EDT,
                    reports::add,
                    400L,
                    400L
                );
            }
            return orchestrator;
        }

        ProtectedExportOrchestrator.Report awaitReport() throws InterruptedException {
            final ProtectedExportOrchestrator.Report report =
                reports.poll(30L, TimeUnit.SECONDS);
            assertNotNull(report, "session report never arrived");
            return report;
        }

        /** Regular files currently in the user's real destination directory. */
        List<Path> destinationFiles() throws IOException {
            try (var stream = Files.list(realPick.toPath().getParent())) {
                return stream.filter(Files::isRegularFile).toList();
            }
        }

        private OwnedMoc fakeMoc() {
            return new OwnedMoc() {
                @Override
                public MocVersion version() {
                    return MocVersion.V5_0;
                }

                @Override
                public MocConsistency consistency() {
                    return MocConsistency.CONSISTENT;
                }

                @Override
                public long nativeHandle() {
                    return 1L;
                }

                @Override
                public OwnedModel instantiateModel() {
                    final FakeModel exported = host.exportedModel;
                    if (exported == null) {
                        return null;
                    }
                    return fakeModel(exported);
                }

                @Override
                public void close() {
                }
            };
        }

        /**
         * Projects the model the fake export saw into the read surface the staged
         * validator consumes — drawable IDs reflect the copy's (obfuscated) state
         * unless a fault knob rewrites them.
         */
        private OwnedModel fakeModel(final FakeModel exported) {
            // Live parameter values the writer mutates; evaluated drawable
            // positions re-project from this table exactly like the real Core.
            final Map<String, Float> liveValues = new HashMap<>();
            exported.parameters.forEach(
                parameter -> liveValues.put(parameter.id, parameter.defaultValue));
            final List<OwnedPart> parts = new ArrayList<>();
            exported.parts.forEach(part ->
                parts.add(new OwnedPart(part.id, 1f, -1)));
            final List<OwnedDeformer> deformers = new ArrayList<>();
            if (host.exportLeavesDeformer) {
                deformers.add(new OwnedDeformer("d-left", -1, List.of()));
            }
            final OwnedModel model = new OwnedModel() {
                @Override
                public long nativeHandle() {
                    return 1L;
                }

                @Override
                public OwnedCanvasInfo canvasInfo() {
                    return new OwnedCanvasInfo(1000f, 1000f, 500f, 500f, 1000f);
                }

                @Override
                public List<OwnedParameter> parameters() {
                    final List<OwnedParameter> projected = new ArrayList<>();
                    exported.parameters.forEach(parameter -> {
                        // Serialized keys = union of key positions across the
                        // model's bindings for the parameter.
                        final Set<Float> keys = new java.util.TreeSet<>();
                        exported.artMeshes.forEach(mesh ->
                            mesh.bindings.forEach(binding -> {
                                if (binding.parameterId.equals(parameter.id)) {
                                    keys.addAll(binding.keys);
                                }
                            }));
                        exported.deformers.forEach(deformer ->
                            deformer.bindings.forEach(binding -> {
                                if (binding.parameterId.equals(parameter.id)) {
                                    keys.addAll(binding.keys);
                                }
                            }));
                        projected.add(new OwnedParameter(parameter.id, 0,
                            parameter.min, parameter.max, parameter.defaultValue,
                            liveValues.get(parameter.id),
                            List.copyOf(keys), java.util.Optional.empty()));
                    });
                    if (host.exportAddsParameter) {
                        projected.add(new OwnedParameter("param-injected", 0,
                            0f, 1f, 0f, 0f, List.of(0f, 1f),
                            java.util.Optional.empty()));
                    }
                    return projected;
                }

                @Override
                public List<OwnedPart> parts() {
                    return parts;
                }

                @Override
                public List<OwnedDrawable> drawables() {
                    final List<OwnedDrawable> projected = new ArrayList<>();
                    exported.artMeshes.forEach(mesh -> {
                        final float[] positions =
                            evalPositions(mesh.bindings, liveValues);
                        for (int i = 0; i < positions.length; i++) {
                            positions[i] += mesh.basePositions[i];
                        }
                        if (host.exportDriftsGeometry) {
                            // The exporter's serialized geometry diverges from
                            // live evaluation — contracts still match, only the
                            // behavior oracle can see the positional drift.
                            for (int i = 0; i < positions.length; i++) {
                                positions[i] *= 1.5f;
                            }
                        }
                        if (host.exportWritesNonFinite && positions.length > 0) {
                            // NaN output must never equal finite evidence —
                            // Math.abs(NaN - x) > tolerance is false.
                            positions[0] = Float.NaN;
                        }
                        final float[] stagedPositions = host.exportWritesEmptyGeometry
                            ? new float[0]
                            : positions;
                        // Serialize in moc model space like the real exporter:
                        // origin-centered units, Y flipped (moc Y points up).
                        final List<Float> vertexPositions = new ArrayList<>();
                        for (int i = 0; i < stagedPositions.length; i++) {
                            vertexPositions.add(((i & 1) == 0 ? 1f : -1f)
                                * (stagedPositions[i] - 500f) / 1000f);
                        }
                        projected.add(new OwnedDrawable(
                            host.exportKeepsOriginalDrawableIds
                                ? mesh.guid.replace("-guid", "-original")
                                : mesh.drawableId,
                            (byte) 0, (byte) 0, BlendMode.NORMAL, 0, 0, 0, 1f,
                            List.of(), vertexPositions, List.of(), List.of(),
                            new Color(1f, 1f, 1f, 1f), new Color(0f, 0f, 0f, 0f),
                            -1, -1, List.of()));
                    });
                    return projected;
                }

                @Override
                public List<OwnedGlue> glues() {
                    return List.of();
                }

                @Override
                public List<OwnedDeformer> deformers() {
                    return deformers;
                }

                @Override
                public void update() {
                }

                @Override
                public void close() {
                }
            };
            fakeModelValues.put(model, liveValues);
            return model;
        }
    }

    private static final class FakeSelector {
        final List<Object> selected = new ArrayList<>();
    }

    /** Minimal document/model graph the orchestrator's phases actually read. */
    private static final class FakeDoc {
        File file;
        final FakeModel model = new FakeModel();
        final Object content = new Object();
        final FakeSelector selector = new FakeSelector();
        final Object editMode = new Object();
        final Object undo = new Object();
        boolean modified;

        FakeDoc() {
            model.document = this;
        }
    }

    /** One keyform binding: parameter ID plus the key positions it contributes. */
    private static final class FakeBinding {
        final String parameterId;
        final List<Float> keys;

        FakeBinding(final String parameterId, final List<Float> keys) {
            this.parameterId = parameterId;
            this.keys = List.copyOf(keys);
        }
    }

    private static final class FakeDeformer {
        final String guid;
        final String targetGuid;
        final List<FakeBinding> bindings;
        /**
         * Constant deformation this deformer contributes to every mesh it
         * contains — the fake's stand-in for a deformer-local transform. A
         * bound deformer carries it into the meshes' keyform shapes when the
         * host apply runs; an unbound deformer's constant is silently dropped
         * by apply, mirroring the real host gap the behavior oracle must
         * fail closed on.
         */
        final float constant;

        FakeDeformer(final String guid, final String targetGuid) {
            this(guid, targetGuid, List.of());
        }

        FakeDeformer(
            final String guid,
            final String targetGuid,
            final List<FakeBinding> bindings
        ) {
            this(guid, targetGuid, bindings, 0f);
        }

        FakeDeformer(
            final String guid,
            final String targetGuid,
            final List<FakeBinding> bindings,
            final float constant
        ) {
            this.guid = guid;
            this.targetGuid = targetGuid;
            this.bindings = new ArrayList<>(bindings);
            this.constant = constant;
        }
    }

    private static final class FakeArtMesh {
        final String guid;
        String name;
        String drawableId;
        final List<FakeBinding> bindings;
        /** Authored base shape contribution added into evaluated positions. */
        float[] basePositions = new float[4];
        /** Post-evaluation vertex positions, filled by {@code evaluateModelInstance}. */
        float[] evaluatedPositions;

        FakeArtMesh(final String guid, final String name, final String drawableId) {
            this(guid, name, drawableId, List.of());
        }

        FakeArtMesh(
            final String guid,
            final String name,
            final String drawableId,
            final List<FakeBinding> bindings
        ) {
            this.guid = guid;
            this.name = name;
            this.drawableId = drawableId;
            this.bindings = new ArrayList<>(bindings);
        }
    }

    private static final class FakeParameter {
        final String id;
        final float min;
        final float max;
        final float defaultValue;
        final boolean repeat;
        /** Live current value — the fake's parameter-instance write target. */
        float currentValue;

        FakeParameter(final String id) {
            this(id, 0f, 1f, 0f, false);
        }

        FakeParameter(
            final String id,
            final float min,
            final float max,
            final float defaultValue,
            final boolean repeat
        ) {
            this.id = id;
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.repeat = repeat;
            this.currentValue = defaultValue;
        }
    }

    private static final class FakePart {
        final String guid;
        final String id;
        String name;
        final List<String> childGuids;

        FakePart(final String guid, final String id) {
            this(guid, id, "part-name-" + id, new ArrayList<>());
        }

        FakePart(
            final String guid,
            final String id,
            final String name,
            final List<String> childGuids
        ) {
            this.guid = guid;
            this.id = id;
            this.name = name;
            this.childGuids = childGuids;
        }
    }

    private static final class FakeModel {
        final List<FakeDeformer> deformers = new ArrayList<>();
        final List<FakeArtMesh> artMeshes = new ArrayList<>();
        final List<FakeParameter> parameters = new ArrayList<>();
        final List<FakePart> parts = new ArrayList<>();
        /** Objects in allObjects that are none of the supported families. */
        final List<Object> unsupportedObjects = new ArrayList<>();
        final List<Object> physicsSettings = new ArrayList<>();
        final List<Object> motionSyncSettings = new ArrayList<>();
        // The host enumerates a synthetic root part in getAllParts but never
        // serializes it into exported output.
        final FakePart rootPart =
            new FakePart("root-part-guid", "__RootPart__");
        FakeDoc document;
    }

    /**
     * Instance-side ArtMesh handle — the fake mirrors the real host split where
     * evaluated geometry lives on instance objects that point back to sources.
     */
    private static final class FakeInstanceMesh {
        final FakeArtMesh source;

        FakeInstanceMesh(final FakeArtMesh source) {
            this.source = source;
        }
    }

    /**
     * Deterministic evaluated geometry shared by the fake host (post-evaluation
     * positions) and the fake staged model (Core-side vertex positions): every
     * bound parameter's current value contributes, weighted by that binding's
     * key count — a dropped or duplicated binding changes the output.
     */
    private static float[] evalPositions(
        final List<FakeBinding> bindings,
        final Map<String, Float> values
    ) {
        float sum = 0f;
        float weighted = 0f;
        for (FakeBinding binding : bindings) {
            final Float value = values.get(binding.parameterId);
            if (value != null) {
                sum += value;
                weighted += value * binding.keys.size();
            }
        }
        return new float[]{sum, weighted, sum + weighted, sum - weighted};
    }

    private static final class FakeHost implements ProtectedExportHostOperations {
        final FakeDoc original = new FakeDoc();
        final FakeDoc otherDoc = new FakeDoc();
        final Set<FakeDoc> project = new LinkedHashSet<>();
        volatile FakeDoc activeDoc;
        volatile FakeDoc copy;
        volatile File realPick;
        volatile Supplier<ProtectedExportOrchestrator> orchestratorRef;
        volatile AtomicBoolean bindingLiveFlag;
        volatile AtomicLong hostGenerationFlag;
        volatile BiConsumer<File, List<String>> completion;
        volatile FakeModel exportedModel;

        // fault knobs
        volatile boolean bindCopyDoc = true;
        volatile long bindDelayMillis;
        volatile boolean bindingDiesOnBind;
        volatile boolean generationBumpsOnBind;
        volatile boolean originalRemovedOnBind;
        volatile boolean applyRemovesDeformer = true;
        volatile boolean vanishDeformerAfterBindCensus;
        volatile boolean duplicateDeformerGuids;
        volatile boolean extendedInterpolation;
        volatile boolean exportShowsDialog = true;
        volatile boolean exportWritesFiles = true;
        volatile boolean exportCompletes = true;
        volatile boolean cancelInnerDialog;
        volatile boolean cancelChooser;
        volatile boolean restoreOriginal = true;
        volatile boolean releaseCopyHandle = true;
        volatile boolean exportKeepsOriginalDrawableIds;
        volatile boolean exportLeavesDeformer;
        volatile boolean exportDriftsGeometry;
        volatile boolean exportWritesNonFinite;
        volatile boolean exportWritesEmptyGeometry;
        volatile boolean exportAddsParameter;
        volatile boolean vanishArtMeshAfterCensus;
        volatile boolean copyFileMarkedReadOnlyOnOpen;
        volatile boolean selectsExtraDeformer;
        volatile boolean mutatePartNameMidRun;
        volatile boolean switchActiveDocOnSelect;
        volatile boolean multiplyColor;
        volatile boolean embeddedMorphTargets;
        volatile boolean attachedExtension;
        volatile boolean revokeBindingOnNativeExport;
        volatile boolean bumpGenerationOnNativeExport;
        volatile boolean flattenCorruptsGeometry;
        volatile boolean copyParameterStartsOffDefault;
        /**
         * Constant deformation carried by the unbound fixture deformer —
         * nonzero exercises the real host gap: the apply step silently drops
         * it, and the post-flatten behavior oracle must fail closed.
         */
        volatile float unboundDeformerConstant;
        volatile boolean removeOriginalOnNativeExport;
        volatile boolean removeCopyOnNativeExport;
        final AtomicInteger applyCalls = new AtomicInteger();
        private int copyCensusCalls;
        private int artMeshCensusCalls;

        FakeHost() {
            original.model.deformers.add(new FakeDeformer("g-leaf", "g-root",
                List.of(new FakeBinding("param-1", List.of(0f, 0.5f, 1f)))));
            original.model.deformers.add(new FakeDeformer("g-root", null,
                List.of(new FakeBinding("param-1", List.of(0f, 1f)))));
            // Nested under g-root so flattening order and reparenting are
            // exercised for an unbound deformer too.
            original.model.deformers.add(new FakeDeformer("g-unbound", "g-root",
                List.of(), unboundDeformerConstant));
            original.model.artMeshes.add(
                new FakeArtMesh("m-a-guid", "meshA", "id-a",
                    List.of(new FakeBinding("param-1", List.of(0f)))));
            original.model.artMeshes.add(
                new FakeArtMesh("m-b-guid", "meshB", "id-b"));
            original.model.parameters.add(new FakeParameter("param-1"));
            final FakePart part = new FakePart("part-1-guid", "part-1");
            part.childGuids.addAll(
                List.of("m-a-guid", "m-b-guid", "g-leaf", "g-root", "g-unbound"));
            original.model.parts.add(part);
            otherDoc.file = new File("other.cmo3");
        }

        private FakeDoc docFor(final File file) {
            if (file.equals(original.file)) {
                return original;
            }
            if (copy != null && file.equals(copy.file)) {
                return copy;
            }
            return null;
        }

        @Override
        public Object appController() {
            return this;
        }

        @Override
        public Object currentDocument() {
            return activeDoc;
        }

        @Override
        public Object currentProject() {
            return this;
        }

        @Override
        public boolean projectContains(final Object document) {
            return project.contains(document);
        }

        @Override
        public List<?> projectDocuments() {
            return List.copyOf(project);
        }

        @Override
        public Object mainFrame() {
            return new Object();
        }

        @Override
        public void openFile(final File file) {
            if (bindDelayMillis > 0L && !file.equals(original.file)) {
                try {
                    Thread.sleep(bindDelayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (file.equals(original.file)) {
                if (restoreOriginal) {
                    // Reopening a document that left the project re-adds it —
                    // the native open path does the same.
                    project.add(original);
                    activeDoc = original;
                }
                return;
            }
            final FakeDoc bound = docFor(file);
            if (bound != null) {
                activeDoc = bound;
                return;
            }
            if (bindingDiesOnBind && bindingLiveFlag != null) {
                // Plugin unload lands while the copy binds.
                bindingLiveFlag.set(false);
            }
            if (generationBumpsOnBind && hostGenerationFlag != null) {
                hostGenerationFlag.incrementAndGet();
            }
            if (originalRemovedOnBind) {
                project.remove(original);
            }
            if (!bindCopyDoc) {
                return;
            }
            final FakeDoc fresh = new FakeDoc();
            fresh.file = file;
            for (FakeDeformer deformer : original.model.deformers) {
                // Knobs are assigned after fixture construction; the copy is
                // where evaluation/bake actually consume the constant.
                final float constant = "g-unbound".equals(deformer.guid)
                    ? unboundDeformerConstant : deformer.constant;
                fresh.model.deformers.add(
                    new FakeDeformer(deformer.guid, deformer.targetGuid,
                        deformer.bindings, constant));
            }
            for (FakeArtMesh mesh : original.model.artMeshes) {
                final FakeArtMesh copyMesh = new FakeArtMesh(
                    mesh.guid, mesh.name, mesh.drawableId, mesh.bindings);
                copyMesh.basePositions = mesh.basePositions.clone();
                fresh.model.artMeshes.add(copyMesh);
            }
            for (FakeParameter parameter : original.model.parameters) {
                fresh.model.parameters.add(new FakeParameter(parameter.id,
                    parameter.min, parameter.max, parameter.defaultValue,
                    parameter.repeat));
            }
            if (copyParameterStartsOffDefault) {
                // A document saved with non-default parameter values: sampling
                // must restore these, not leave the last sample behind.
                fresh.model.parameters.forEach(
                    parameter -> parameter.currentValue = parameter.max);
            }
            for (FakePart part : original.model.parts) {
                fresh.model.parts.add(new FakePart(part.guid, part.id, part.name,
                    new ArrayList<>(part.childGuids)));
            }
            fresh.model.unsupportedObjects.addAll(original.model.unsupportedObjects);
            fresh.model.physicsSettings.addAll(original.model.physicsSettings);
            fresh.model.motionSyncSettings.addAll(original.model.motionSyncSettings);
            copy = fresh;
            project.add(fresh);
            activeDoc = fresh;
            if (copyFileMarkedReadOnlyOnOpen) {
                // The editor marks saved documents read-only.
                file.setWritable(false);
            }
        }

        @Override
        public void closeFileContent(final Object fileContent) {
            if (copy != null && copy.content == fileContent) {
                project.remove(copy);
            }
        }

        @Override
        public boolean isModelingDocument(final Object document) {
            return document instanceof FakeDoc;
        }

        @Override
        public Object documentModelSource(final Object document) {
            return ((FakeDoc) document).model;
        }

        @Override
        public Object documentFileContent(final Object document) {
            return ((FakeDoc) document).content;
        }

        @Override
        public Object documentSelector(final Object document) {
            return ((FakeDoc) document).selector;
        }

        @Override
        public Object documentCurrentEditMode(final Object document) {
            return ((FakeDoc) document).editMode;
        }

        @Override
        public Object documentMainEditMode(final Object document) {
            return ((FakeDoc) document).editMode;
        }

        @Override
        public File documentFile(final Object document) {
            return ((FakeDoc) document).file;
        }

        @Override
        public Object documentUndoManager(final Object document) {
            return ((FakeDoc) document).undo;
        }

        @Override
        public void markDocumentSaved(final Object document) {
            ((FakeDoc) document).modified = false;
        }

        @Override
        public File fileContentFile(final Object fileContent) {
            for (FakeDoc doc : project) {
                if (doc.content == fileContent) {
                    return doc.file;
                }
            }
            return null;
        }

        @Override
        public boolean fileContentModified(final Object fileContent) {
            for (FakeDoc doc : project) {
                if (doc.content == fileContent) {
                    return doc.modified;
                }
            }
            return false;
        }

        @Override
        public int undoPosition(final Object undoManager) {
            return 0;
        }

        @Override
        public int undoEditCount(final Object undoManager) {
            return 0;
        }

        @Override
        public boolean undoCanUndo(final Object undoManager) {
            return false;
        }

        @Override
        public boolean isMainSelector(final Object selector) {
            return selector instanceof FakeSelector;
        }

        @Override
        public boolean isMainEditMode(final Object editMode) {
            return editMode != null;
        }

        @Override
        public void clearSelection(final Object selector) {
            ((FakeSelector) selector).selected.clear();
        }

        @Override
        public int selectedCount(final Object selector) {
            return ((FakeSelector) selector).selected.size();
        }

        @Override
        public void selectSource(final Object selector, final Object source) {
            ((FakeSelector) selector).selected.add(source);
            if (selectsExtraDeformer) {
                // A foreign deformer coexists in the selection — the apply must
                // refuse rather than consume an unplanned target.
                ((FakeSelector) selector).selected.add(
                    new FakeDeformer("g-foreign", null));
            }
            if (switchActiveDocOnSelect) {
                // Selection callbacks reenter host code: the active document
                // flips back to the original while the selection itself still
                // holds the planned deformer.
                activeDoc = original;
            }
        }

        @Override
        public List<?> selectedDeformers(final Object selector) {
            return List.copyOf(((FakeSelector) selector).selected);
        }

        @Override
        public void applyDeformerToParameters(final Object mainEditMode) {
            applyCalls.incrementAndGet();
            final FakeDoc doc = (FakeDoc) activeDoc;
            if (applyRemovesDeformer) {
                if (mutatePartNameMidRun && doc == copy) {
                    // A mutation outside the orchestrator's plan touches part
                    // identity — the post-mutation census must catch it.
                    doc.model.parts.forEach(part -> part.name = "part-mutated");
                }
                final List<FakeDeformer> removed = doc.model.deformers.stream()
                    .filter(d -> doc.selector.selected.contains(d))
                    .toList();
                doc.model.deformers.removeAll(removed);
                // A removed deformer's bindings move onto every ArtMesh it
                // deformed (the fake treats every deformer as deforming every
                // mesh). Appending — rather than unioning key positions — keeps
                // the fake's evaluation exactly invariant under the move, as
                // the real keyform-transfer apply does. The parameter key
                // union the census records is preserved either way.
                for (FakeDeformer deformer : removed) {
                    for (FakeArtMesh mesh : doc.model.artMeshes) {
                        for (FakeBinding binding : deformer.bindings) {
                            mesh.bindings.add(new FakeBinding(
                                binding.parameterId,
                                new ArrayList<>(binding.keys)));
                        }
                        if (!deformer.bindings.isEmpty()) {
                            // Bound deformers preserve their constant
                            // contribution through the bound keyforms; an
                            // unbound deformer's constant is silently dropped —
                            // the real-host gap the behavior oracle must catch.
                            for (int i = 0; i < mesh.basePositions.length; i++) {
                                mesh.basePositions[i] += deformer.constant;
                            }
                        }
                    }
                    for (FakePart part : doc.model.parts) {
                        part.childGuids.remove(deformer.guid);
                    }
                    doc.model.rootPart.childGuids.remove(deformer.guid);
                }
            }
            doc.modified = true;
            doc.selector.selected.clear();
        }

        @Override
        public Object modelSourceDocument(final Object modelSource) {
            return ((FakeModel) modelSource).document;
        }

        @Override
        public Object modelSourceCurrentInstance(final Object modelSource) {
            return modelSource;
        }

        @Override
        public List<?> allDeformers(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            if (duplicateDeformerGuids && model == original.model) {
                return List.of(
                    new FakeDeformer("g-dup", null), new FakeDeformer("g-dup", null));
            }
            if (copy != null && model == copy.model && vanishDeformerAfterBindCensus) {
                // First copy census is the binding-confirmation plan; later reads
                // (flatten's re-resolve) no longer see the leaf deformer.
                copyCensusCalls++;
                if (copyCensusCalls >= 2) {
                    return model.deformers.stream()
                        .filter(d -> !"g-leaf".equals(d.guid))
                        .toList();
                }
            }
            return List.copyOf(model.deformers);
        }

        @Override
        public List<?> allObjects(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            final List<Object> all = new ArrayList<>();
            all.addAll(model.deformers);
            all.addAll(model.artMeshes);
            // Parameter sources are not parameter-controllable on the real host:
            // getAllObjects never returns them.
            all.add(model.rootPart);
            all.addAll(model.parts);
            all.addAll(model.unsupportedObjects);
            return all;
        }

        @Override
        public List<?> allArtMeshes(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            if (copy != null && vanishArtMeshAfterCensus && model == copy.model
                && model.artMeshes.size() > 1 && ++artMeshCensusCalls > 1) {
                // The census planned over both meshes; later reads (per-target
                // re-resolution, the post-pass census) no longer see the first.
                return List.copyOf(model.artMeshes.subList(1, model.artMeshes.size()));
            }
            return List.copyOf(model.artMeshes);
        }

        @Override
        public List<?> allParts(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            final List<Object> all = new ArrayList<>(model.parts.size() + 1);
            all.add(model.rootPart);
            all.addAll(model.parts);
            return List.copyOf(all);
        }

        @Override
        public Object rootPart(final Object modelSource) {
            return ((FakeModel) modelSource).rootPart;
        }

        @Override
        public List<?> allParameters(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).parameters);
        }

        @Override
        public List<?> allPhysicsSettings(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).physicsSettings);
        }

        @Override
        public List<?> allMotionSyncSettings(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).motionSyncSettings);
        }

        @Override
        public String modelSourceGuid(final Object modelSource) {
            return "model-guid";
        }

        @Override
        public List<?> liveParameters(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).parameters);
        }

        @Override
        public String parameterInstanceId(final Object parameter) {
            return parameter instanceof FakeParameter p ? p.id : null;
        }

        @Override
        public float parameterInstanceValue(final Object parameter) {
            return parameter instanceof FakeParameter p
                ? p.currentValue : Float.NaN;
        }

        @Override
        public void setParameterInstanceValue(
            final Object parameterInstance,
            final float value
        ) {
            ((FakeParameter) parameterInstance).currentValue = value;
        }

        @Override
        public void evaluateModelInstance(final Object modelInstance) {
            // The fake's "update model": every ArtMesh's evaluated positions are
            // recomputed from the current parameter values — the same function
            // the fake staged model evaluates on the Core side.
            final FakeModel model = (FakeModel) modelInstance;
            final Map<String, Float> values = new HashMap<>();
            for (FakeParameter parameter : model.parameters) {
                values.put(parameter.id, parameter.currentValue);
            }
            for (FakeArtMesh mesh : model.artMeshes) {
                // Deformer bindings deform every mesh they contain; the fake
                // models the worst case (all meshes) so an apply that appends
                // those bindings onto the mesh keeps evaluation identical.
                final List<FakeBinding> effective =
                    new ArrayList<>(mesh.bindings);
                for (FakeDeformer deformer : model.deformers) {
                    effective.addAll(deformer.bindings);
                }
                mesh.evaluatedPositions = evalPositions(effective, values);
                for (int i = 0; i < mesh.evaluatedPositions.length; i++) {
                    mesh.evaluatedPositions[i] += mesh.basePositions[i];
                    for (FakeDeformer deformer : model.deformers) {
                        mesh.evaluatedPositions[i] += deformer.constant;
                    }
                }
                if (flattenCorruptsGeometry && model.deformers.isEmpty()) {
                    // A flatten that damaged geometry: evaluated positions
                    // diverge from the pre-flatten snapshot even though the
                    // exporter would faithfully reproduce them.
                    for (int i = 0; i < mesh.evaluatedPositions.length; i++) {
                        mesh.evaluatedPositions[i] *= 1.5f;
                    }
                }
            }
        }

        @Override
        public List<?> modelInstanceArtMeshes(final Object modelInstance) {
            return ((FakeModel) modelInstance).artMeshes.stream()
                .map(FakeInstanceMesh::new)
                .toList();
        }

        @Override
        public Object artMeshInstanceSource(final Object artMeshInstance) {
            return artMeshInstance instanceof FakeInstanceMesh mesh
                ? mesh.source : null;
        }

        @Override
        public float[] evaluatedArtMeshPositions(final Object artMeshInstance) {
            if (!(artMeshInstance instanceof FakeInstanceMesh mesh)) {
                return null;
            }
            return mesh.source.evaluatedPositions == null
                ? null : mesh.source.evaluatedPositions.clone();
        }

        @Override
        public List<?> deformerChildren(final Object deformerSource) {
            // The fake models the worst case: every deformer contains every
            // mesh, so an unbound deformer's constant reaches all of them.
            // Flatten operates on the bound copy, which is the active doc.
            return List.copyOf(((FakeDoc) activeDoc).model.artMeshes);
        }

        @Override
        public String objectGuid(final Object source) {
            if (source instanceof FakeParameter) {
                throw new IllegalArgumentException("not a controllable source");
            }
            if (source instanceof FakeDeformer deformer) {
                return deformer.guid;
            }
            if (source instanceof FakeArtMesh mesh) {
                return mesh.guid;
            }
            if (source instanceof FakePart part) {
                return part.guid;
            }
            return "object-guid";
        }

        @Override
        public String objectIdString(final Object source) {
            // Mirror the real host: the controllable accessor rejects
            // parameter sources with IllegalArgumentException.
            if (source instanceof FakeParameter) {
                throw new IllegalArgumentException("not a controllable source");
            }
            if (source instanceof FakeArtMesh mesh) {
                return mesh.drawableId;
            }
            if (source instanceof FakePart part) {
                return part.id;
            }
            return "object-id-" + System.identityHashCode(source);
        }

        @Override
        public List<?> keyformBindings(final Object controllableSource) {
            if (controllableSource instanceof FakeDeformer deformer) {
                return deformer.bindings;
            }
            if (controllableSource instanceof FakeArtMesh mesh) {
                return mesh.bindings;
            }
            return List.of();
        }

        @Override
        public String keyformBindingParameterId(final Object binding) {
            return ((FakeBinding) binding).parameterId;
        }

        @Override
        public List<Float> keyformBindingKeys(final Object binding) {
            return ((FakeBinding) binding).keys;
        }

        @Override
        public boolean isPartSource(final Object object) {
            return object instanceof FakePart;
        }

        @Override
        public List<String> partChildGuids(final Object partSource) {
            return List.copyOf(((FakePart) partSource).childGuids);
        }

        @Override
        public String objectLocalName(final Object source) {
            if (source instanceof FakeParameter) {
                throw new IllegalArgumentException("not a controllable source");
            }
            if (source instanceof FakeArtMesh mesh) {
                return mesh.name;
            }
            if (source instanceof FakePart part) {
                return part.name;
            }
            return "object-name-" + System.identityHashCode(source);
        }

        @Override
        public String parameterSourceIdString(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter
                ? parameter.id : null;
        }

        @Override
        public String parameterSourceName(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter
                ? "param-name-" + parameter.id : null;
        }

        @Override
        public Float parameterSourceMinValue(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter
                ? parameter.min : null;
        }

        @Override
        public Float parameterSourceMaxValue(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter
                ? parameter.max : null;
        }

        @Override
        public Float parameterSourceDefaultValue(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter
                ? parameter.defaultValue : null;
        }

        @Override
        public Boolean parameterSourceRepeat(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter
                ? parameter.repeat : null;
        }

        @Override
        public void setObjectLocalName(final Object source, final String name) {
            ((FakeArtMesh) source).name = name;
        }

        @Override
        public String drawableIdString(final Object drawableSource) {
            return drawableSource instanceof FakeArtMesh mesh ? mesh.drawableId : null;
        }

        @Override
        public void setDrawableId(final Object drawableSource, final String idString) {
            ((FakeArtMesh) drawableSource).drawableId = idString;
        }

        @Override
        public boolean isDeformerSource(final Object object) {
            return object instanceof FakeDeformer;
        }

        @Override
        public boolean isWarpDeformer(final Object object) {
            return object instanceof FakeDeformer;
        }

        @Override
        public boolean isRotationDeformer(final Object object) {
            return object instanceof FakeDeformer;
        }

        @Override
        public boolean isArtMeshSource(final Object object) {
            return object instanceof FakeArtMesh;
        }

        @Override
        public String deformerGuid(final Object deformerSource) {
            return ((FakeDeformer) deformerSource).guid;
        }

        @Override
        public String deformerTargetGuid(final Object deformerSource) {
            return ((FakeDeformer) deformerSource).targetGuid;
        }

        @Override
        public boolean usesExtendedInterpolation(final Object modelSource) {
            return extendedInterpolation;
        }

        @Override
        public List<String> unsupportedModelFeatures(final Object modelSource) {
            // Mirrors the host contain* gates: any populated flag is a family
            // the census cannot see — multiply colour lives on ArtMesh colour
            // composition, not the object list.
            return multiplyColor ? List.of("multiply-color") : List.of();
        }

        @Override
        public List<String> embeddedUnsupportedFamilies(final Object controllableSource) {
            final List<String> detected = new ArrayList<>();
            if (embeddedMorphTargets
                && controllableSource instanceof FakeArtMesh mesh
                && "m-a-guid".equals(mesh.guid)) {
                detected.add("keyform-morph-target-set");
            }
            if (attachedExtension && controllableSource instanceof FakeDeformer) {
                detected.add("extension:com.live2d.cubism.doc.model.extension.controller.CControllerExtension");
            }
            return detected;
        }

        @Override
        public boolean isExportDialog(final Object owner) {
            return owner != null;
        }

        @Override
        public Object dialogModelSource(final Object dialogOwner) {
            return original.model;
        }

        @Override
        public Object exportDriver() {
            return this;
        }

        @Override
        public void invokeNativeExport(
            final Object driver,
            final Object modelSource,
            final Object frame,
            final Object completionCallback
        ) {
            if (!exportShowsDialog) {
                return; // native pre-check veto: no dialog, no chooser
            }
            final ProtectedExportOrchestrator orchestrator = orchestratorRef.get();
            final Object innerDialog = new Object();
            orchestrator.suppressDialogOptions(innerDialog);
            if (cancelInnerDialog) {
                orchestrator.dialogCancelled(innerDialog);
                return;
            }
            if (cancelChooser) {
                orchestrator.redirectPickedFile(null);
                return;
            }
            final Object stagedPick = orchestrator.redirectPickedFile(realPick);
            if (!(stagedPick instanceof File staged)) {
                return;
            }
            exportedModel = (FakeModel) modelSource;
            final List<String> paths = new ArrayList<>();
            if (exportWritesFiles) {
                try {
                    final Path parent = staged.toPath().getParent();
                    Files.write(staged.toPath(), new byte[]{1, 2, 3});
                    paths.add(staged.toPath().toAbsolutePath().toString());
                    final Path modelJson = parent.resolve(
                        staged.getName().replace(".moc3", "") + ".model3.json");
                    Files.writeString(modelJson,
                        "{\"FileReferences\":{\"Moc\":\"" + staged.getName() + "\"}}");
                    paths.add(modelJson.toAbsolutePath().toString());
                } catch (IOException ignored) {
                }
            } else {
                // Reported but never written: the validator must refuse to publish.
                paths.add(staged.toPath().toAbsolutePath().toString());
            }
            if (exportCompletes && completion != null) {
                completion.accept(staged, paths);
            }
            if (revokeBindingOnNativeExport && bindingLiveFlag != null) {
                // Plugin unload lands while the native modal flow runs.
                bindingLiveFlag.set(false);
            }
            if (bumpGenerationOnNativeExport && hostGenerationFlag != null) {
                hostGenerationFlag.incrementAndGet();
            }
            if (removeOriginalOnNativeExport) {
                project.remove(original);
            }
            if (removeCopyOnNativeExport && copy != null) {
                project.remove(copy);
            }
        }

        @Override
        public Object newExportCompletionProxy(
            final BiConsumer<File, List<String>> callback
        ) {
            completion = callback;
            return callback;
        }

        @Override
        public boolean releaseFileHandleFor(final File file) {
            return releaseCopyHandle;
        }

        @Override
        public List<String> fileHandleDiagnostics(final File file) {
            return List.of();
        }
    }
}
