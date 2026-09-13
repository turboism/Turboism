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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    // Fixture: fake host, inline EDT, blocking report queue
    // ------------------------------------------------------------------

    private static final ProtectedExportOrchestrator.EdtDispatcher INLINE_EDT =
        new ProtectedExportOrchestrator.EdtDispatcher() {
            @Override
            public <T> T call(final Callable<T> action) throws Exception {
                return action.call();
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

        ProtectedExportOrchestrator orchestrator() {
            if (orchestrator == null) {
                orchestrator = new ProtectedExportOrchestrator(
                    host,
                    new ProtectedExportStaging(data -> mocLoads ? fakeMoc() : null),
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
            final List<OwnedDrawable> drawables = new ArrayList<>();
            exported.artMeshes.forEach(mesh -> drawables.add(new OwnedDrawable(
                host.exportKeepsOriginalDrawableIds
                    ? mesh.guid.replace("-guid", "-original") : mesh.drawableId,
                (byte) 0, (byte) 0, BlendMode.NORMAL, 0, 0, 0, 1f,
                List.of(), List.of(), List.of(), List.of(),
                new Color(1f, 1f, 1f, 1f), new Color(0f, 0f, 0f, 0f),
                -1, -1, List.of())));
            final List<OwnedParameter> parameters = new ArrayList<>();
            exported.parameters.forEach(parameter -> parameters.add(
                new OwnedParameter(parameter.id, 0, 0f, 1f, 0f, 0f,
                    List.of(0f, 1f), java.util.Optional.empty())));
            if (host.exportAddsParameter) {
                parameters.add(new OwnedParameter("param-injected", 0, 0f, 1f, 0f,
                    0f, List.of(0f, 1f), java.util.Optional.empty()));
            }
            final List<OwnedPart> parts = new ArrayList<>();
            exported.parts.forEach(part ->
                parts.add(new OwnedPart(part.id, 1f, -1)));
            final List<OwnedDeformer> deformers = new ArrayList<>();
            if (host.exportLeavesDeformer) {
                deformers.add(new OwnedDeformer("d-left", -1, List.of()));
            }
            return new OwnedModel() {
                @Override
                public long nativeHandle() {
                    return 1L;
                }

                @Override
                public OwnedCanvasInfo canvasInfo() {
                    return new OwnedCanvasInfo(1f, 1f, 0f, 0f, 1f);
                }

                @Override
                public List<OwnedParameter> parameters() {
                    return parameters;
                }

                @Override
                public List<OwnedPart> parts() {
                    return parts;
                }

                @Override
                public List<OwnedDrawable> drawables() {
                    return drawables;
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

    private static final class FakeDeformer {
        final String guid;
        final String targetGuid;

        FakeDeformer(final String guid, final String targetGuid) {
            this.guid = guid;
            this.targetGuid = targetGuid;
        }
    }

    private static final class FakeArtMesh {
        final String guid;
        String name;
        String drawableId;

        FakeArtMesh(final String guid, final String name, final String drawableId) {
            this.guid = guid;
            this.name = name;
            this.drawableId = drawableId;
        }
    }

    private static final class FakeParameter {
        final String id;

        FakeParameter(final String id) {
            this.id = id;
        }
    }

    private static final class FakePart {
        final String id;

        FakePart(final String id) {
            this.id = id;
        }
    }

    private static final class FakeModel {
        final List<FakeDeformer> deformers = new ArrayList<>();
        final List<FakeArtMesh> artMeshes = new ArrayList<>();
        final List<FakeParameter> parameters = new ArrayList<>();
        final List<FakePart> parts = new ArrayList<>();
        FakeDoc document;
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
        volatile boolean exportAddsParameter;
        volatile boolean vanishArtMeshAfterCensus;
        private int copyCensusCalls;
        private int artMeshCensusCalls;

        FakeHost() {
            original.model.deformers.add(new FakeDeformer("g-leaf", "g-root"));
            original.model.deformers.add(new FakeDeformer("g-root", null));
            original.model.artMeshes.add(
                new FakeArtMesh("m-a-guid", "meshA", "id-a"));
            original.model.artMeshes.add(
                new FakeArtMesh("m-b-guid", "meshB", "id-b"));
            original.model.parameters.add(new FakeParameter("param-1"));
            original.model.parts.add(new FakePart("part-1"));
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
                fresh.model.deformers.add(
                    new FakeDeformer(deformer.guid, deformer.targetGuid));
            }
            for (FakeArtMesh mesh : original.model.artMeshes) {
                fresh.model.artMeshes.add(
                    new FakeArtMesh(mesh.guid, mesh.name, mesh.drawableId));
            }
            for (FakeParameter parameter : original.model.parameters) {
                fresh.model.parameters.add(new FakeParameter(parameter.id));
            }
            for (FakePart part : original.model.parts) {
                fresh.model.parts.add(new FakePart(part.id));
            }
            copy = fresh;
            project.add(fresh);
            activeDoc = fresh;
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
        }

        @Override
        public List<?> selectedDeformers(final Object selector) {
            return List.copyOf(((FakeSelector) selector).selected);
        }

        @Override
        public void applyDeformerToParameters(final Object mainEditMode) {
            final FakeDoc doc = (FakeDoc) activeDoc;
            if (applyRemovesDeformer) {
                doc.model.deformers.removeIf(d -> doc.selector.selected.contains(d));
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
            all.addAll(model.parameters);
            all.addAll(model.parts);
            return all;
        }

        @Override
        public List<?> allArtMeshes(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            if (vanishArtMeshAfterCensus && model == copy.model
                && model.artMeshes.size() > 1 && ++artMeshCensusCalls > 1) {
                // The census planned over both meshes; later reads (per-target
                // re-resolution, the post-pass census) no longer see the first.
                return List.copyOf(model.artMeshes.subList(1, model.artMeshes.size()));
            }
            return List.copyOf(model.artMeshes);
        }

        @Override
        public List<?> allParts(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).parts);
        }

        @Override
        public List<?> allParameters(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).parameters);
        }

        @Override
        public String modelSourceGuid(final Object modelSource) {
            return "model-guid";
        }

        @Override
        public List<?> liveParameters(final Object modelSource) {
            return List.of();
        }

        @Override
        public String parameterInstanceId(final Object parameter) {
            return null;
        }

        @Override
        public float parameterInstanceValue(final Object parameter) {
            return Float.NaN;
        }

        @Override
        public String objectGuid(final Object source) {
            if (source instanceof FakeDeformer deformer) {
                return deformer.guid;
            }
            if (source instanceof FakeArtMesh mesh) {
                return mesh.guid;
            }
            return "object-guid";
        }

        @Override
        public String objectIdString(final Object source) {
            if (source instanceof FakeArtMesh mesh) {
                return mesh.drawableId;
            }
            if (source instanceof FakeParameter parameter) {
                return parameter.id;
            }
            if (source instanceof FakePart part) {
                return part.id;
            }
            return "object-id-" + System.identityHashCode(source);
        }

        @Override
        public String objectLocalName(final Object source) {
            if (source instanceof FakeArtMesh mesh) {
                return mesh.name;
            }
            return "object-name-" + System.identityHashCode(source);
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
