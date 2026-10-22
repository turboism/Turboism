package dev.turboism.exportsettings;

import static dev.turboism.exportsettings.ProtectedExportTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        final Fixture fixture = new Fixture(tempDir);
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();

        assertTrue(orchestrator.requestExport(fixture.outerDialog));
        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();

        assertEquals(ProtectedExportOrchestrator.Phase.PUBLISHED, report.reached());
        assertTrue(report.published());
        assertEquals(2, report.publishedFiles().size());
        // The user's real pick directory received the published output.
        assertTrue(Files.isRegularFile(fixture.realPick.toPath().getParent().resolve("model.moc3")));
        assertTrue(Files.isRegularFile(fixture.realPick.toPath().getParent().resolve("model.model3.json")));
        // The original is the active document again; the copy is gone from the project.
        assertTrue(fixture.host.activeDoc == fixture.host.original);
        assertFalse(fixture.host.project.contains(fixture.host.copy));
        // The copy carried the obfuscated identities; the original is untouched.
        assertTrue(fixture.host.copy.model.artMeshes.stream()
                .allMatch(mesh ->
                        mesh.name.matches("ArtMesh_[0-9a-f]{16,}") && mesh.drawableId.matches("@[0-9a-f]{16,}")));
        assertEquals("meshA", fixture.host.original.model.artMeshes.get(0).name);
        assertEquals("id-a", fixture.host.original.model.artMeshes.get(0).drawableId);
        // Task-owned staging is removed (root may remain but must be empty).
        assertTrue(!Files.exists(fixture.stagingRoot)
                || Files.list(fixture.stagingRoot).findAny().isEmpty());
        orchestrator.close();
    }

    @Test
    void publishesAndCleansWhenEditorMarksCopyReadOnly() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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
        final Fixture fixture = new Fixture(tempDir);
        // The copy census sees both meshes; the per-target apply cannot resolve
        // the first one again.
        fixture.host.vanishArtMeshAfterCensus = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.OBFUSCATE_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @FunctionalInterface
    private interface FixtureCustomizer {
        void accept(Fixture fixture) throws Exception;
    }

    private static class ExportExecution {
        final Fixture fixture;
        final ProtectedExportOrchestrator.Report report;

        ExportExecution(Fixture fixture, ProtectedExportOrchestrator.Report report) {
            this.fixture = fixture;
            this.report = report;
        }
    }

    private ExportExecution runExport(FixtureCustomizer customizer) throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        customizer.accept(fixture);
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));
        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        orchestrator.close();
        return new ExportExecution(fixture, report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"drawableIds", "deformers", "parameterIds"})
    void rejectsWhenStagedMocDiverges(final String divergenceType) throws Exception {
        final ExportExecution exec = runExport(fixture -> {
            if ("drawableIds".equals(divergenceType)) fixture.host.exportKeepsOriginalDrawableIds = true;
            else if ("deformers".equals(divergenceType)) fixture.host.exportLeavesDeformer = true;
            else if ("parameterIds".equals(divergenceType)) fixture.host.exportAddsParameter = true;
        });
        assertNotNull(exec.report.failureKey());
        assertTrue(exec.report.failureKey().startsWith(ProtectedExportOrchestrator.VALIDATION_FAILED_KEY));
        assertFalse(exec.report.published());
        if ("drawableIds".equals(divergenceType)) {
            assertFalse(Files.exists(exec.fixture.realPick.toPath().getParent().resolve("model.moc3")));
        }
    }

    @Test
    void rejectsWhenStagedGeometryDriftsFromEvaluatedCopy() throws Exception {
        // The exported model reproduces every structural contract (IDs, ranges,
        // key positions) but its evaluated geometry differs from the copy's —
        // only the sampled-behavior oracle can see it.
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.exportDriftsGeometry = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(
                report.failureKey().startsWith(ProtectedExportOrchestrator.VALIDATION_FAILED_KEY),
                "expected validation rejection, got " + report.failureKey());
        assertTrue(
                report.failureKey().contains("behavior-drift"),
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
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.exportWritesNonFinite = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(
                report.failureKey().startsWith(ProtectedExportOrchestrator.VALIDATION_FAILED_KEY),
                "expected validation rejection, got " + report.failureKey());
        assertNotNull(report.failureDetail());
        assertTrue(
                report.failureDetail().contains("non-finite-output"),
                "expected non-finite-output detail, got " + report.failureDetail());
        assertFalse(report.published());
        assertTrue(report.originalRestored());
        assertTrue(report.cleanupErrors().isEmpty());
        orchestrator.close();
    }

    @Test
    void rejectsWhenStagedGeometryHasNoVertices() throws Exception {
        // An empty vertex array must not count as compared coverage.
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.exportWritesEmptyGeometry = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNotNull(report.failureKey());
        assertTrue(
                report.failureKey().startsWith(ProtectedExportOrchestrator.VALIDATION_FAILED_KEY),
                "expected validation rejection, got " + report.failureKey());
        assertNotNull(report.failureDetail());
        assertTrue(
                report.failureDetail().contains("invalid-output-vertices"),
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
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.flattenCorruptsGeometry = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.BEHAVIOR_MISMATCH_KEY, report.failureKey());
        assertEquals(ProtectedExportOrchestrator.Phase.FAILED, report.reached());
        assertFalse(report.published());
        assertNull(fixture.host.exportedModel, "native export must not run when flatten changed behavior");
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
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.unboundDeformerConstant = 7f;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.BEHAVIOR_MISMATCH_KEY, report.failureKey());
        assertFalse(report.published());
        assertNull(fixture.host.exportedModel, "native export must not run when flatten changed behavior");
        orchestrator.close();
    }

    @Test
    void restoresSampledParametersAfterCapture() throws Exception {
        // Sampling writes live parameter values on the copy; a document saved
        // with non-default values must get them back — the real export has to
        // run from the restored state, not from the last sample point.
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.copyParameterStartsOffDefault = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertTrue(report.published(), "expected publish, failed with " + report.failureKey());
        for (FakeParameter parameter : fixture.host.copy.model.parameters) {
            assertEquals(
                    parameter.max,
                    parameter.currentValue,
                    "parameter " + parameter.id + " must be restored to its" + " pre-capture value");
        }
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Admission and identity gates
    // ------------------------------------------------------------------

    @Test
    void rejectsWhenRedirectSeamNotInstalled() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        fixture.seamInstalled.set(false);
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertFalse(orchestrator.requestExport(fixture.outerDialog));
        orchestrator.close();
    }

    @Test
    void refusedRequestsReportTheGateTheyStoppedAt() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        fixture.seamInstalled.set(false);
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        final List<ExportSettingsVetoDiagnostic> refusals = new ArrayList<>();
        orchestrator.refusalReporter(refusals::add);

        assertFalse(orchestrator.requestExport(fixture.outerDialog));
        assertEquals(1, refusals.size(), "a refused request must name itself for the user-visible sink");
        assertEquals(
                ProtectedExportOrchestrator.NOT_ADMITTED_KEY, refusals.get(0).key());
        assertEquals("redirect-seam-missing", refusals.get(0).detail());
        orchestrator.close();
    }

    @Test
    void busyRefusalReportsAndKeepsTheArmedSession() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        final List<ExportSettingsVetoDiagnostic> refusals = new ArrayList<>();
        orchestrator.refusalReporter(refusals::add);
        fixture.host.bindDelayMillis = 5_000L;

        assertTrue(orchestrator.requestExport(fixture.outerDialog));
        assertFalse(orchestrator.requestExport(fixture.outerDialog));
        assertEquals(1, refusals.size());
        assertEquals(
                ProtectedExportOrchestrator.NOT_ADMITTED_KEY, refusals.get(0).key());
        assertEquals("busy", refusals.get(0).detail());
        orchestrator.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bindingUnavailable", "staleGeneration", "bindingDies"})
    void rejectsWhenPluginBindingOrHostGenerationFailsMidRun(final String failureKind) throws Exception {
        final ExportExecution exec = runExport(fixture -> {
            if ("bindingUnavailable".equals(failureKind)) fixture.bindingLive.set(false);
            else if ("staleGeneration".equals(failureKind)) fixture.host.generationBumpsOnBind = true;
            else if ("bindingDies".equals(failureKind)) fixture.host.bindingDiesOnBind = true;
        });
        assertEquals(ProtectedExportOrchestrator.NOT_ADMITTED_KEY, exec.report.failureKey());
        assertFalse(exec.report.published());
    }

    @Test
    void rejectsSecondConcurrentArm() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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

    @ParameterizedTest
    @ValueSource(strings = {"neverBinds", "docChanges"})
    void rejectsWhenCopyFailsToBind(final String scenario) throws Exception {
        final ExportExecution exec = runExport(fixture -> {
            if ("neverBinds".equals(scenario)) fixture.host.bindCopyDoc = false;
            else if ("docChanges".equals(scenario)) fixture.host.originalRemovedOnBind = true;
        });
        assertEquals(ProtectedExportOrchestrator.BIND_FAILED_KEY, exec.report.failureKey());
        assertFalse(exec.report.published());
    }

    @ParameterizedTest
    @ValueSource(strings = {"vanishes", "survives"})
    void rejectsWhenDeformerMutationFailsBeforeOrAfterApply(final String scenario) throws Exception {
        final ExportExecution exec = runExport(fixture -> {
            if ("vanishes".equals(scenario)) fixture.host.vanishDeformerAfterBindCensus = true;
            else if ("survives".equals(scenario)) fixture.host.applyRemovesDeformer = false;
        });
        assertEquals(ProtectedExportOrchestrator.FLATTEN_FAILED_KEY, exec.report.failureKey());
        assertFalse(exec.report.published());
    }

    @Test
    void rejectsWhenSelectionCarriesForeignDeformer() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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
    void rejectsWhenUnpinnableObjectEntersCensus() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A census member that is not a controllable source has no pinnable
        // identity — the session rejects at preflight before any copy is made.
        fixture.host.original.model.unpinnableObjects.add(new Object());
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertEquals("protected-export.unpinnable-structure:unknown=1", report.failureDetail());
        assertFalse(report.published());
        assertTrue(fixture.host.copy == null, "no copy may be bound");
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    @Test
    void rejectsWithFamilyCountsWhenUnpinnableObjectsEnterCensus() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // An admitted Glue beside two members with no pinnable identity: the
        // family-count detail must cover only the rejected members — Glue is a
        // pass-through channel and never counts as unpinnable.
        fixture.host.original.model.glues.add(new FakeGlue(
                "glue-1-guid",
                "Glue1",
                "glue-one",
                fixture.host.original.model.artMeshes.get(0),
                fixture.host.original.model.artMeshes.get(1)));
        fixture.host.original.model.unpinnableObjects.add(new FakeUnsupported("art-path"));
        fixture.host.original.model.unpinnableObjects.add(new Object());
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertEquals("protected-export.unpinnable-structure:art-path=1,unknown=1", report.failureDetail());
        assertFalse(report.published());
        assertTrue(fixture.host.copy == null, "no copy may be bound");
        orchestrator.close();
    }

    @Test
    void rejectsUnknownCensusFamilyAsUnpinnable() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A controllable member of a family whose references cannot be
        // enumerated (deform-path) cannot be safely pinned — it fails closed.
        fixture.host.original.model.passThrough.add(
                new FakePassThrough("deform-path", "dp-1-guid", "dp-1", "deformPath", List.of()));
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertEquals("protected-export.unpinnable-structure:deform-path=1", report.failureDetail());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsDuplicateCensusIdentities() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A pass-through member colliding with an ArtMesh GUID makes the census
        // ambiguous — fail closed.
        fixture.host.original.model.passThrough.add(
                new FakePassThrough("art-path", "m-a-guid", "ap-1", "artPath", List.of()));
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertEquals("protected-export.duplicate-guid:m-a-guid", report.failureDetail());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void publishesWithPassThroughFamiliesUnchanged() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // ArtPath and alias members ride the session untouched: admitted by
        // admission, pinned by the census, preserved verbatim on the original.
        fixture.host.original.model.passThrough.add(
                new FakePassThrough("art-path", "ap-1-guid", "ArtPath1", "art-path-one", List.of("m-a-guid")));
        fixture.host.original.model.passThrough.add(
                new FakePassThrough("alias", "al-1-guid", "Alias1", "alias-one", List.of("m-b-guid")));
        fixture.host.original.model.parts.get(0).childGuids.add("ap-1-guid");
        fixture.host.original.model.parts.get(0).childGuids.add("al-1-guid");
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNull(report.failureKey());
        assertTrue(report.published());
        for (FakePassThrough member : fixture.host.original.model.passThrough) {
            assertTrue(member.name.endsWith("-one"), "original pass-through identity untouched: " + member.name);
        }
        orchestrator.close();
    }

    @Test
    void rejectsWhenPassThroughIdentityDriftsDuringFlatten() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // An out-of-band mutation renames a pass-through member while flatten
        // runs — the post-mutation census compares against the bound snapshot
        // and refuses.
        fixture.host.original.model.passThrough.add(
                new FakePassThrough("art-path", "ap-1-guid", "ArtPath1", "art-path-one", List.of()));
        fixture.host.mutatePassThroughMidRun = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.OBFUSCATE_FAILED_KEY, report.failureKey());
        assertEquals("pass-through-identity-drift", report.failureDetail());
        assertFalse(report.published());
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    @Test
    void publishesWithGluePassingThroughUnchanged() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A model carrying a Glue relation must export: the Glue rides the
        // session untouched — no flatten, no rename, no re-identification.
        fixture.host.original.model.glues.add(new FakeGlue(
                "glue-1-guid",
                "Glue1",
                "glue-one",
                fixture.host.original.model.artMeshes.get(0),
                fixture.host.original.model.artMeshes.get(1)));
        fixture.host.original.model.parts.get(0).childGuids.add("glue-1-guid");
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNull(report.failureKey());
        assertTrue(report.published());
        assertTrue(fixture.destinationFiles().size() >= 2);
        // The original document's Glue is untouched — same object, same fields.
        final FakeGlue glue = fixture.host.original.model.glues.get(0);
        assertEquals("Glue1", glue.id);
        assertEquals("glue-one", glue.name);
        assertEquals("m-a-guid", glue.targetA.guid);
        assertEquals("m-b-guid", glue.targetB.guid);
        orchestrator.close();
    }

    @Test
    void rejectsWhenGlueIdentityDriftsDuringFlatten() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // An out-of-band mutation renames the Glue while flatten runs — the
        // pass-through census compares against the bound snapshot and refuses.
        fixture.host.original.model.glues.add(new FakeGlue(
                "glue-1-guid",
                "Glue1",
                "glue-one",
                fixture.host.original.model.artMeshes.get(0),
                fixture.host.original.model.artMeshes.get(1)));
        fixture.host.mutateGlueMidRun = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.OBFUSCATE_FAILED_KEY, report.failureKey());
        assertEquals("pass-through-identity-drift", report.failureDetail());
        assertFalse(report.published());
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    @Test
    void rejectsWhenGlueTargetDoesNotResolveToArtMesh() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A Glue whose target stopped resolving is corrupt input, not a
        // pass-through case — fail closed at preflight.
        fixture.host.original.model.glues.add(
                new FakeGlue("glue-1-guid", "Glue1", "glue-one", fixture.host.original.model.artMeshes.get(0), null));
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertEquals("glue-reference-drift", report.failureDetail());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenExportDropsGlue() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // The staged artifact must carry the Glue under its unchanged ID —
        // a native export that dropped it is a validation rejection.
        fixture.host.original.model.glues.add(new FakeGlue(
                "glue-1-guid",
                "Glue1",
                "glue-one",
                fixture.host.original.model.artMeshes.get(0),
                fixture.host.original.model.artMeshes.get(1)));
        fixture.host.exportDropsGlue = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals("protected-export.validation-failed:protected-export.moc3-glue-ids", report.failureKey());
        assertFalse(report.published());
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    /**
     * A fixture physics setting with realistic behavior content: one input and
     * one output bound to {@code param-1}, three pendulum vertices.
     */
    private static FakePhysicsSettings physicsSetting(final String guid, final String id, final String name) {
        final FakePhysicsSettings settings = new FakePhysicsSettings(guid, id, name);
        settings.inputs.add(new FakePhysicsInput("param-1-guid", 1f, "SRC_TO_X", false));
        settings.outputs.add(new FakePhysicsOutput("param-1-guid", 0, 1f, "SRC_TO_X", false));
        for (int i = 0; i < 3; i++) {
            settings.vertices.add(new FakePhysicsVertex(i * 0.5f, 0f, 1f, 0.2f, 0.9f, 1f));
        }
        return settings;
    }

    @Test
    void publishesWithPhysicsSettingsObfuscated() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // Physics settings live outside getAllObjects — obfuscation rewrites
        // their name/ID to GUID-hash tokens while every behavior value is
        // pinned by the content signature, and the staged physics3.json
        // (emitted because the user's physics output checkbox is on) must
        // reproduce the content under the canonical positional IDs.
        fixture.host.original.model.physicsSettings.add(physicsSetting("phys-1-guid", "hairPhys", "hair"));
        fixture.host.original.model.physicsSettings.add(physicsSetting("phys-2-guid", "skirtPhys", "skirt"));
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNull(report.failureKey(), "expected publish: " + report.failureKey() + " " + report.failureDetail());
        assertTrue(report.published());
        final Path physics3 = fixture.realPick.toPath().getParent().resolve("model.physics3.json");
        assertTrue(Files.isRegularFile(physics3));
        final String body = Files.readString(physics3);
        assertTrue(body.contains("\"PhysicsSetting1\""), body);
        assertFalse(body.contains("hairPhys"), "authored ID leaked");
        assertFalse(body.contains("skirtPhys"), "authored ID leaked");
        assertFalse(body.contains("\"hair\""), "authored name leaked");
        // The copy's settings carry the planned tokens — the authored names/IDs
        // are gone from the exported model.
        assertTrue(fixture.host.copy.model.physicsSettings.stream()
                .allMatch(setting -> setting instanceof FakePhysicsSettings physics
                        && physics.id.startsWith("PhysicsId_")
                        && physics.name.startsWith("Physics_")));
        // The original document is untouched.
        assertTrue(fixture.host.original.model.physicsSettings.stream()
                .allMatch(setting -> setting instanceof FakePhysicsSettings physics
                        && (physics.id.equals("hairPhys") || physics.id.equals("skirtPhys"))
                        && (physics.name.equals("hair") || physics.name.equals("skirt"))));
        orchestrator.close();
    }

    @Test
    void publishesWhenPhysicsOutputCheckboxIsOff() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // Physics settings pinned but the user's native output checkbox off: no
        // physics3.json is staged and validation must not demand one.
        fixture.host.original.model.physicsSettings.add(physicsSetting("phys-1-guid", "hairPhys", "hair"));
        fixture.host.exportWritesPhysics = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNull(report.failureKey(), "expected publish: " + report.failureKey());
        assertTrue(report.published());
        orchestrator.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"physics3-ids", "physics3-content"})
    void rejectsWhenStagedPhysicsDiverges(final String divergenceType) throws Exception {
        final boolean isIds = "physics3-ids".equals(divergenceType);
        final ExportExecution exec = runExport(fixture -> {
            fixture.host.original.model.physicsSettings.add(physicsSetting("phys-1-guid", "hairPhys", "hair"));
            if (isIds) {
                fixture.host.original.model.physicsSettings.add(physicsSetting("phys-2-guid", "skirtPhys", "skirt"));
                fixture.host.exportDropsPhysicsSetting = true;
            } else {
                fixture.host.exportMutatesPhysicsContent = true;
            }
        });
        assertNotNull(exec.report.failureKey());
        assertTrue(
                exec.report.failureKey().contains(divergenceType),
                "expected " + divergenceType + " rejection, got " + exec.report.failureKey());
        assertFalse(exec.report.published());
    }

    @Test
    void rejectsWhenPhysicsParameterReferenceIsUnresolvable() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A physics input referencing a parameter GUID the model does not have
        // cannot be proven preserved — fail closed at the census.
        final FakePhysicsSettings dangling = physicsSetting("phys-1-guid", "hairPhys", "hair");
        dangling.inputs.add(new FakePhysicsInput("ghost-param-guid", 1f, "SRC_TO_X", false));
        fixture.host.original.model.physicsSettings.add(dangling);
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertTrue(
                report.failureDetail().contains("unpinnable-settings"),
                "expected unpinnable-settings rejection, got " + report.failureDetail());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenSettingsIdentityDriftsDuringFlatten() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // An out-of-band mutation flips a physics signature mid-run; the
        // post-mutation census compares against the bound snapshot.
        fixture.host.original.model.physicsSettings.add(physicsSetting("phys-1-guid", "hairPhys", "hair"));
        fixture.host.mutateSettingsMidRun = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.OBFUSCATE_FAILED_KEY, report.failureKey());
        assertEquals("settings-identity-drift", report.failureDetail());
        assertFalse(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsUnpinnablePhysicsSettingsEntry() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A settings entry with no stable GUID/ID cannot be pinned — it rejects
        // rather than riding through unseen.
        fixture.host.original.model.physicsSettings.add(new Object());
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, report.failureKey());
        assertEquals("protected-export.unpinnable-settings:unknown", report.failureDetail());
        assertFalse(report.published());
        assertTrue(fixture.host.copy == null);
        orchestrator.close();
    }

    @Test
    void publishesWithMotionSyncSettingsPassingThrough() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.original.model.motionSyncSettings.add(
                new FakeMotionSyncSettings("ms-1-guid", "MotionSync1", "voice", 4242));
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNull(report.failureKey(), "expected publish: " + report.failureKey());
        assertTrue(report.published());
        orchestrator.close();
    }

    @Test
    void rejectsWhenPartIdentityDriftsDuringFlatten() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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
        final Fixture fixture = new Fixture(tempDir);
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
        final Fixture fixture = new Fixture(tempDir);
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
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.exportShowsDialog = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        orchestrator.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"writesNothing", "failsToLoad"})
    void rejectsWhenWorkerFailsValidation(final String failureKind) throws Exception {
        final ExportExecution exec = runExport(fixture -> {
            if ("writesNothing".equals(failureKind)) fixture.host.exportWritesFiles = false;
            else if ("failsToLoad".equals(failureKind)) fixture.mocLoads = false;
        });
        assertTrue(exec.report.failureKey().startsWith(ProtectedExportOrchestrator.VALIDATION_FAILED_KEY));
        assertFalse(exec.report.published());
    }

    @Test
    void rejectsWhenWorkerCallbackNeverArrives() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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
        final Fixture fixture = new Fixture(tempDir);
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
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.cancelChooser = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_CANCELLED_KEY, report.failureKey());
        assertFalse(report.published());
        // AC07: a cancelled session still restores the original document and
        // removes every task-owned file — the report carries the proof.
        assertTrue(report.originalRestored(), "original document must be active and intact after cancellation");
        assertTrue(report.cleanedUp(), "cleanup errors: " + report.cleanupErrors());
        assertTrue(fixture.host.activeDoc == fixture.host.original);
        assertFalse(fixture.host.project.contains(fixture.host.copy));
        assertFalse(fixture.host.copy.file.exists(), "copy file must be deleted");
        assertTrue(
                fixture.destinationFiles().isEmpty(), "destination must stay untouched: " + fixture.destinationFiles());
        orchestrator.close();
    }

    @Test
    void restoresOriginalWhenExportTimesOut() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.restoreOriginal = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.RESTORE_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        assertFalse(Files.exists(fixture.realPick.toPath().getParent().resolve("model.moc3")));
        orchestrator.close();
    }

    @Test
    void publishesDirtyOriginalFromLiveState() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // Unsaved in-memory edit: the disk file predates it, so a file copy
        // would silently drop it. Protected export must stage the live model
        // instead — same content basis as native export.
        fixture.host.original.modified = true;
        fixture.host.original.model.parameters.set(0, new FakeParameter("param-1", 0f, 2f, 0f, false));
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertTrue(
                report.published(),
                "dirty original must publish: " + report.failureKey() + " " + report.failureDetail());
        assertEquals(1, fixture.host.serializeCalls.get());
        assertSame(
                fixture.host.original.model,
                fixture.host.serializedFrom,
                "the staged copy must serialize the live model source");
        assertEquals(
                2f, fixture.host.exportedModel.parameters.get(0).max, "the unsaved edit must reach the exported model");
        assertTrue(fixture.host.original.modified, "the original's unsaved state must survive the export");
        assertSame(fixture.host.original, fixture.host.activeDoc);
        assertFalse(fixture.host.project.contains(fixture.host.copy), "the disposable copy must be retired");
        assertEquals(
                "fixture-cmo3",
                Files.readString(fixture.host.original.file.toPath()),
                "the original file's bytes must stay untouched");
        orchestrator.close();
    }

    @Test
    void rejectsWhenSerializationFails() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        fixture.host.serializeFails = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.BIND_FAILED_KEY, report.failureKey());
        assertFalse(report.published());
        assertNull(fixture.host.copy);
        assertEquals("fixture-cmo3", Files.readString(fixture.host.original.file.toPath()));
        orchestrator.close();
    }

    @Test
    void rejectsWhenPublicationCannotWriteDestination() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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
        // must name that recovery location verbatim, under a path long enough
        // to break the generic diagnostic bound and past the suppressed cap.
        final Fixture fixture = new Fixture(tempDir);
        final Path destination = Files.createDirectories(
                tempDir.resolve("destination-with-a-deliberately-long-segment-name-".repeat(5)));
        fixture.host.realPick = destination.resolve("model.moc3").toFile();
        Files.writeString(fixture.host.realPick.toPath(), "ORIGINAL-USER-BYTES");
        Files.writeString(destination.resolve("model.model3.json"), "ORIGINAL-JSON-BYTES");
        fixture.publishMoveOp = (source, target) -> {
            if (source.toString().contains("incoming")
                    && target.getFileName().toString().endsWith(".json")) {
                final IOException place = new IOException("injected place failure");
                for (int i = 0; i < 6; i++) {
                    place.addSuppressed(new IOException("injected diagnostic " + i));
                }
                throw place;
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
        assertTrue(
                detail.contains("more suppressed"),
                "eight generic suppressed entries must overflow the cap: " + detail);
        assertTrue(detail.contains("recovery-path: "), detail);

        // The retained scratch under the destination still holds the user's
        // original bytes — and the report must contain its full path verbatim,
        // not a stub truncated by the generic describe() bound.
        boolean pathReported = false;
        boolean recovered = false;
        try (DirectoryStream<Path> scratches = Files.newDirectoryStream(destination, ".turboism-publish-*")) {
            for (Path scratch : scratches) {
                pathReported |= detail.contains(scratch.toString());
                try (var walk = Files.walk(scratch)) {
                    recovered |= walk.filter(Files::isRegularFile).anyMatch(path -> {
                        try {
                            return Files.readString(path).equals("ORIGINAL-USER-BYTES");
                        } catch (IOException failure) {
                            return false;
                        }
                    });
                }
            }
        }
        assertTrue(pathReported, "report must contain the actual retained scratch path verbatim: " + detail);
        assertTrue(recovered, "retained scratch must still hold the user's original bytes");
        orchestrator.close();
    }

    @Test
    void rejectsWhenCopyHandleCannotBeReleased() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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
    void publishesWithContentFeatureFlagSet() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // Multiply colour lives on ArtMesh colour composition — the object
        // census never sees it; the host contain* gate pins it as pass-through
        // content so flatten/obfuscation must leave the flag set unchanged.
        fixture.host.multiplyColor = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNull(report.failureKey(), "expected publish: " + report.failureKey());
        assertTrue(report.published());
        orchestrator.close();
    }

    @Test
    void publishesWithMorphTargetsEmbeddedInsideArtMesh() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A keyform morph-target set embedded in an ArtMesh source is
        // pass-through content: the census pins its signature on the owning
        // object rather than rejecting the family outright.
        fixture.host.embeddedMorphTargets = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNull(report.failureKey(), "expected publish: " + report.failureKey());
        assertTrue(report.published());
        orchestrator.close();
    }

    @Test
    void publishesWithExtensionAttachedToDeformer() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // A feature extension attached to a flattened deformer is pinned in the
        // census and legitimately disappears with its owner — not a rejection.
        fixture.host.attachedExtension = true;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertNull(report.failureKey(), "expected publish: " + report.failureKey());
        assertTrue(report.published());
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Selection reentrancy and mid-session revocation
    // ------------------------------------------------------------------

    @Test
    void refusesApplyWhenSelectionSwitchesActiveDocument() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
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
        assertEquals(0, fixture.host.applyCalls.get(), "apply must not run after the selection switched documents");
        assertFalse(fixture.host.original.modified, "original dirty flag must stay untouched");
        assertTrue(report.originalRestored());
        assertTrue(report.cleanedUp(), "cleanup errors: " + report.cleanupErrors());
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pluginUnloaded", "generationChanged", "originalClosed", "copyClosed"})
    void refusesPublishWhenSessionRevokedDuringNativeExport(final String revocationCause) throws Exception {
        final ExportExecution exec = runExport(fixture -> {
            if ("pluginUnloaded".equals(revocationCause)) fixture.host.revokeBindingOnNativeExport = true;
            else if ("generationChanged".equals(revocationCause)) fixture.host.bumpGenerationOnNativeExport = true;
            else if ("originalClosed".equals(revocationCause)) fixture.host.removeOriginalOnNativeExport = true;
            else if ("copyClosed".equals(revocationCause)) fixture.host.removeCopyOnNativeExport = true;
        });
        assertEquals(ProtectedExportOrchestrator.REVOKED_KEY, exec.report.failureKey());
        assertFalse(exec.report.published());
        assertTrue(exec.fixture.destinationFiles().isEmpty());
        if ("pluginUnloaded".equals(revocationCause) || "originalClosed".equals(revocationCause)) {
            assertTrue(exec.report.originalRestored());
            assertTrue(exec.report.cleanedUp(), "cleanup errors: " + exec.report.cleanupErrors());
        }
    }

    @Test
    void ignoresCompletionCallbackArrivingAfterTimeout() throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        // The native flow never completes — the session times out, disarms, and
        // clears the export window. A callback landing afterwards must be a
        // no-op: no second report and certainly no publication.
        fixture.host.exportCompletes = false;
        final ProtectedExportOrchestrator orchestrator = fixture.orchestrator();
        assertTrue(orchestrator.requestExport(fixture.outerDialog));

        final ProtectedExportOrchestrator.Report report = fixture.awaitReport();
        assertEquals(ProtectedExportOrchestrator.EXPORT_TIMEOUT_KEY, report.failureKey());
        assertFalse(report.published());
        assertNotNull(fixture.host.completion, "the fake must retain the gated completion callback");
        fixture.host.completion.accept(fixture.realPick, List.of("late.moc3"));
        assertNull(fixture.reports.poll(2L, TimeUnit.SECONDS), "a late callback must not produce another report");
        assertTrue(fixture.destinationFiles().isEmpty());
        orchestrator.close();
    }

    // ------------------------------------------------------------------
    // Fixture: fake host, inline EDT, blocking report queue
    // ------------------------------------------------------------------

}
