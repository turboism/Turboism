package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.DiagnosticObservationRecorder.diagnosticUnavailable;
import static dev.turboism.validation.externalpsd.DiagnosticObservationRecorder.targetFingerprint;
import static dev.turboism.validation.externalpsd.ExactHostRowDispatch.objectIdentity;
import static dev.turboism.validation.externalpsd.GuiReadinessDiagnostics.captureGuiThreadDump;
import static dev.turboism.validation.externalpsd.GuiRowDispatch.componentIdentity;
import static dev.turboism.validation.externalpsd.GuiRowDispatch.hierarchyDigest;
import static dev.turboism.validation.externalpsd.GuiRowDispatch.popupMarker;
import static dev.turboism.validation.externalpsd.GuiRowDispatch.rowAttempt;
import static dev.turboism.validation.externalpsd.PerformanceEvidence.performanceAdmission;
import static dev.turboism.validation.externalpsd.PersistSaveChecks.describeWindows;
import static dev.turboism.validation.externalpsd.PersistSaveChecks.ownerChain;
import static dev.turboism.validation.externalpsd.PersistSaveChecks.targetIdentityFromRelationsOnEdt;
import static dev.turboism.validation.externalpsd.PsdStructureInspection.imageDataSha256;
import static dev.turboism.validation.externalpsd.PsdStructureInspection.sha256;
import static dev.turboism.validation.externalpsd.RawExportCoordination.diagnosticExportLabel;
import static dev.turboism.validation.externalpsd.RawExportCoordination.modelImageIds;
import static dev.turboism.validation.externalpsd.RawExportCoordination.selectorProjection;
import static dev.turboism.validation.externalpsd.SessionFileReadiness.awaitExportResult;
import static dev.turboism.validation.externalpsd.SessionFileReadiness.awaitStableSessionFile;
import static dev.turboism.validation.externalpsd.TargetResolution.contentProfileName;
import static dev.turboism.validation.externalpsd.TargetResolution.remainingMillis;
import static dev.turboism.validation.externalpsd.TargetResolution.remainingStructuralMillis;
import static dev.turboism.validation.externalpsd.TargetResolution.uniqueModelImageRelation;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.cubism.event.ProjectFileLifecycleEvent;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * Test-only 025 external PSD edit pipeline probe.
 *
 * <p>Drives the SDK seam end to end: relation resolution → native export → runtime-issued
 * handle → observed stable saves → explicit-target native replacement → native Undo/Redo
 * → handle stop → same-binding recovery. The probe never guesses file paths from plugin input;
 * it locates the runtime allocation only for simulated external writes, exactly as an external
 * editor would see it. Persistence and GUI validation use the validation-only fixture helper to
 * correlate decoded target RGB fingerprints; no production pixel API is involved.</p>
 *
 * <p>Phases ({@code -Dturboism.validation.externalpsd.phase}): {@code pipeline} (default),
 * {@code prepare-fixture}, {@code reopen}, and {@code gui}.
 * runs the full save/replace/undo/stop pipeline and, with {@code .persist=1}, appends a
 * mediated SAVE_AS plus lifecycle-event confirmation; {@code reopen} re-exports the validated
 * target layer from a previously saved fixture copy; {@code gui} right-clicks a real object row,
 * clicks the contributed menu item, and verifies the plugin's own session auto-imports a
 * written save. {@code prepare-fixture} is a preparation-only phase: it drives the official
 * PSD chooser and SAVE_AS helper before ordinary model readiness, then binds the helper's exact
 * returned host window for the shared close path.</p>
 *
 * <p>NOT covered (recorded honestly): multi-document isolation, F3/F4 fixture entities.</p>
 */
public final class ExternalPsdEditHostProbe implements TurboismPlugin {
    private static final long TASK_WINDOW_BIND_TIMEOUT_MILLIS = 30_000L;
    private static final long TASK_WINDOW_BIND_POLL_MILLIS = 250L;
    private PluginContext context;
    private volatile boolean stopped;
    private volatile PsdValidationContent.Profile contentProfile =
        PsdValidationContent.Profile.SEVEN_LAYER_CONTROL;
    private volatile List<String> stableSharedModelImageUsers = List.of();
    private Thread worker;
    private volatile GuiReadinessDiagnostics.GuiWaitDiagnostics guiWaitDiagnostics;
    /** GUI-only cache: an off-EDT verified context is reused by all tables from one loader. */
    private final Map<ClassLoader, ExactHostRowTarget.HostAccessPreparation> hostAccessByLoader =
        new IdentityHashMap<>();
    /** The first exact host window containing the verified fixture target; never re-bound. */
    private volatile Window guiBoundWindow;
    /** Worker-prepared reflection handles for the official CEAppCtrl→main-frame window chain. */
    private volatile PersistSaveChecks.HostWindowAccess hostWindowAccess;
    /** Validation-only sampler; disable closes it before interrupting the worker. */
    private volatile ExternalPsdPerformanceSampler performanceSampler;
    private OfficialPsdReplacementBaseline.CompositionReader compositionReader;
    private OfficialPsdReplacementBaseline.AuthoringColorReader structuralColorReader;
    private OfficialPsdReplacementBaseline.InputDetailReader structuralInputReader;
    private OfficialPsdReplacementBaseline.CompositionObservation baselineComposition;
    private volatile String structuralRunId = "";

    @Override public void init(final PluginContext context) { this.context = context; }

    @Override public void enable() {
        final String runId = System.getProperty("turboism.validation.externalpsd.runId", "");
        if (runId.isBlank()) throw new IllegalStateException("Task-scoped runId is required");
        worker = new Thread(() -> run(runId), "external-psd-edit-host-probe");
        worker.setDaemon(true);
        worker.start();
    }

    @Override public void disable() {
        stopped = true;
        final GuiReadinessDiagnostics.GuiWaitDiagnostics diagnostics = guiWaitDiagnostics;
        if (diagnostics != null) diagnostics.close("disabled");
        final ExternalPsdPerformanceSampler sampler = performanceSampler;
        if (sampler != null) sampler.close();
        if (worker != null) worker.interrupt();
    }
    @Override public void shutdown() { disable(); }

    private void run(final String runId) {
        final Properties result = new Properties();
        final int cycles = Integer.getInteger("turboism.validation.externalpsd.cycles", 3);
        result.setProperty("schemaVersion", "1");
        result.setProperty("runId", runId);
        result.setProperty("profile", "025-external-edit-pipeline-v1");
        result.setProperty("expectedHostVersion",
            GuiReadinessDiagnostics.expectedHostVersion(System.getProperty("turboism.validation.hostVersion", "5.3.02")));
        result.setProperty("hostIdentityEvidence", "runner exact JAR/BAT identity and lifecycle evidence");
        result.setProperty("cycles.requested", Integer.toString(cycles));
        final String phase = System.getProperty(
            "turboism.validation.externalpsd.phase", "pipeline");
        result.setProperty("phase", phase);
        final String requestedContentProfile = System.getProperty(
            TargetResolution.CONTENT_PROFILE_PROPERTY, TargetResolution.CONTROL_CONTENT_PROFILE);
        result.setProperty("contentProfile", requestedContentProfile);
        final String requestedPerformance = System.getProperty(PerformanceEvidence.PERFORMANCE_PROPERTY, "");
        result.setProperty("performance.requested", requestedPerformance.isBlank()
            ? "0" : requestedPerformance);
        ExternalPsdPerformanceSampler sampler = null;
        PerformanceEvidence.PerformanceCycleTimings performanceTimings = null;
        Throwable terminalFailure = null;
        try {
            try {
                stableSharedModelImageUsers = List.of();
                compositionReader = null;
                structuralColorReader = null;
                structuralInputReader = null;
                baselineComposition = null;
                contentProfile = TargetResolution.parseContentProfile(requestedContentProfile);
                result.setProperty("contentProfile", contentProfileName(contentProfile));
                result.setProperty("contentValidation",
                    Boolean.toString(TargetResolution.contentValidationRequired(contentProfile, "1".equals(
                        System.getProperty("turboism.validation.externalpsd.persist")))));
                final PerformanceEvidence.PerformanceAdmission performanceAdmission = performanceAdmission(
                    phase, contentProfile, cycles, requestedPerformance);
                PerformanceEvidence.recordPerformanceAdmission(result, performanceAdmission);
                if (performanceAdmission.requested() && !performanceAdmission.admitted()) {
                    throw new IllegalStateException("performance admission rejected: "
                        + performanceAdmission.diagnostic());
                }
                if (TargetResolution.isPrepareFixturePhase(phase)) {
                    runPrepareFixture(result);
                } else {
                    if ("gui".equals(phase)) awaitGuiReadyTrigger(result);
                    awaitReady();
                    if (performanceAdmission.admitted()) {
                        PerformanceEvidence.recordPerformanceEnvironment(result);
                        sampler = ExternalPsdPerformanceSampler.start();
                        performanceSampler = sampler;
                        performanceTimings = new PerformanceEvidence.PerformanceCycleTimings();
                        result.setProperty("performance.lifecycle.start", "STARTED");
                    }
                    switch (phase) {
                        case "reopen" -> runReopen(result);
                        case "gui" -> runGui(result);
                        case "pipeline" -> runPipeline(result, cycles, performanceTimings);
                        case "export-profile" -> runExportProfile(result);
                        case "structure-native", "structure-sdk" -> runStructuralControl(result, phase);
                        case "prepare-second-document" -> runSecondDocumentPreparation(result);
                        default -> throw new IllegalStateException("unknown probe phase " + phase);
                    }
                }
                result.setProperty("status", "PASS");
            } catch (PersistSaveChecks.Blocked blocked) {
                terminalFailure = blocked;
                result.setProperty("status", "BLOCKED");
                result.setProperty("expected", blocked.expected);
                result.setProperty("actual", blocked.getMessage());
                context.logger().warn("EXTERNAL_PSD_EDIT_RESULT status=BLOCKED "
                    + blocked.getMessage());
            } catch (Throwable error) {
                terminalFailure = error;
                if (stopped) return;
                result.setProperty("status", "FAIL");
                result.setProperty("expected", "external edit save→replace→undo→stop→recover pipeline");
                result.setProperty("actual", error.toString());
                if (error.getCause() != null) result.setProperty("cause", error.getCause().toString());
                final StringWriter trace = new StringWriter();
                error.printStackTrace(new java.io.PrintWriter(trace));
                result.setProperty("failureTrace", trace.toString());
            }
        } finally {
            if (sampler != null) {
                PerformanceEvidence.finishPerformanceEvidence(result, sampler, performanceTimings, cycles,
                    terminalFailure);
                performanceSampler = null;
            }
        }
        if (stopped) return;
        Path stateDir = null;
        try {
            stateDir = context.paths().stateDir();
            Files.createDirectories(stateDir);
            final var output = new StringWriter();
            result.store(output, "025 external PSD edit pipeline probe; not full feature acceptance");
            Files.writeString(stateDir.resolve("external-psd-edit-result.pending"),
                output.toString());
            Files.move(stateDir.resolve("external-psd-edit-result.pending"),
                stateDir.resolve("external-psd-edit-result.properties"),
                StandardCopyOption.REPLACE_EXISTING);
            context.logger().info("EXTERNAL_PSD_EDIT_RESULT status=" + result.getProperty("status"));
        } catch (Throwable error) {
            context.logger().error("EXTERNAL_PSD_EDIT_RESULT_WRITE_FAILED", error);
        }
        if (stopped) return;
        if (stateDir == null) {
            context.logger().error(
                "EXTERNAL_PSD_EDIT_EXIT_FAILED no task state directory was available");
            return;
        }
        try {
            PersistSaveChecks.armExitWatchdog(stateDir);
            // The exact GUI target is closed asynchronously.  A missing target is left to the
            // shared Supervisor rather than guessed from an unrelated visible window.
            closeHostGracefully();
        } catch (Throwable error) {
            context.logger().error("EXTERNAL_PSD_EDIT_EXIT_FAILED", error);
        }
    }

    /** Diagnostic collection only: arbitrary task fixture, no PSD decoding or model writes. */
    private void runExportProfile(final Properties result) throws Exception {
        result.setProperty("exportProfile.acceptance", "NOT_CLAIMED");
        PerformanceEvidence.recordPerformanceEnvironment(result);
        final var document = context.cubism().activeDocument().orElseThrow();
        final String expected = System.getProperty("turboism.validation.fixtureName", "");
        final String path = document.relativePath();
        final String actual = path.substring(path.lastIndexOf('/') + 1);
        result.setProperty("fixture.expected", expected);
        result.setProperty("fixture.actual", actual);
        if (expected.isBlank() || !expected.equals(actual)) {
            throw new IllegalStateException("Export profile requires the exact task fixture");
        }
        final var model = context.cubism().model().active();
        result.setProperty("documentId", document.documentId());
        result.setProperty("modelId", model.id().value());
        final var textures = model.textures();
        TargetResolution.Target target = null;
        for (final var drawable : model.drawables().all()) {
            final var query = new dev.turboism.sdk.cubism.model.TextureSourceQuery(
                Set.of(drawable.id()), Set.of());
            final var sources = textures.sources(query);
            if (!sources.isAvailable() || sources.artMeshInputs().size() != 1) continue;
            final var mesh = sources.artMeshInputs().get(0);
            if (mesh.currentInputIndex().isEmpty()) continue;
            final var current = mesh.inputs().get(mesh.currentInputIndex().getAsInt());
            final List<TextureInputBinding> inputs = current.kind() == TextureInputBinding.Kind.ATLAS
                ? mesh.inputs().stream().filter(input -> input.kind() == TextureInputBinding.Kind.MODEL_IMAGE).toList()
                : List.of(current);
            final Set<RawImageId> raws = new LinkedHashSet<>();
            ModelImageId anchor = null;
            boolean valid = !inputs.isEmpty();
            for (final var input : inputs) {
                if (!input.isResolved() || input.modelImageId().isEmpty()) { valid = false; break; }
                final var image = sources.modelImage(input.modelImageId().orElseThrow());
                if (image.isEmpty() || image.orElseThrow().currentRawImageId().isEmpty()) { valid = false; break; }
                anchor = image.orElseThrow().id();
                raws.add(image.orElseThrow().currentRawImageId().orElseThrow());
            }
            if (!valid || raws.size() != 1) continue;
            final var raw = raws.iterator().next();
            if (sources.rawImage(raw).isEmpty()) continue;
            target = new TargetResolution.Target(mesh, anchor, raw, false, new TargetResolution.TargetIdentity(document.documentId(),
                model.id().value(), sources.binding(), sources.generation(), anchor.value(), mesh.id().value(), raw.value()));
            break;
        }
        if (target == null) throw new IllegalStateException("No selected ArtMesh has a unique PSD source");
        bindExactTaskWindow(result, target);
        result.setProperty("exportProfile.artMesh", target.artMesh().id().value());
        result.setProperty("exportProfile.raw", target.raw().value());
        final Path state = context.paths().stateDir();
        Files.createDirectories(state);
        try (final ExportProfileRecorder recorder = new ExportProfileRecorder(state)) {
            recorder.checkpoint("baseline");
            for (int cycle = 1; cycle <= 3 && !stopped; cycle++) {
                recorder.stage("export-" + cycle);
                final long started = System.nanoTime();
                final var exported = textures.exportRawImagePsd(target.raw()).toCompletableFuture().get();
                result.setProperty("exportProfile." + cycle + ".millis",
                    Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
                result.setProperty("exportProfile." + cycle + ".status", exported.status().name());
                if (exported.status() != PsdExportResult.Status.EXPORTED) {
                    throw new IllegalStateException("Profile export failed: " + exported);
                }
                final var file = exported.file().orElseThrow();
                try {
                    recorder.stage("idle-" + cycle);
                    Thread.sleep(3000L);
                } finally {
                    file.stop().toCompletableFuture().get();
                }
                recorder.checkpoint("stopped-" + cycle);
            }
            recorder.finish();
            result.setProperty("exportProfile.collection", "COMPLETE");
        }
    }

    /**
     * Runs the official PSD new-model preparation before ordinary readiness. The helper owns all
     * chooser/model/SAVE_AS observations; this phase only adopts its returned exact host window
     * so the existing result and native close paths remain the single lifecycle boundary.
     */
    private void runPrepareFixture(final Properties result) throws Exception {
        final OfficialPsdFixturePreparation.PreparationResult preparation =
            OfficialPsdFixturePreparation.prepareFixture(context, () -> stopped, result);
        if (preparation == null || preparation.window() == null) {
            throw new IllegalStateException(
                "official PSD preparation returned no exact host window");
        }
        guiBoundWindow = preparation.window();
        result.setProperty("prepare.boundWindow.identity", objectIdentity(guiBoundWindow));
        result.setProperty("prepare.boundWindow.class", guiBoundWindow.getClass().getName());
        result.setProperty("expected", "official PSD new-model import and SAVE_AS preparation");
        result.setProperty("actual",
            "official PSD chooser, relation graph, SAVE_AS, and exact close-window binding verified");
    }

    /**
     * Drives Cubism's own window-close path for the exact window that contained the verified GUI
     * target.  The close event is posted asynchronously because a dirty document can enter a
     * modal option dialog on the EDT; the worker then observes that dialog without waiting for
     * the close event through {@code invokeAndWait}.
     */
    private void closeHostGracefully() {
        final Window target = guiBoundWindow;
        if (target == null) {
            context.logger().warn(
                "EXTERNAL_PSD_EDIT_EXIT_SKIPPED no exact task target window was bound; "
                    + "Supervisor owns cleanup");
            return;
        }
        try {
            final HostCloseCoordinator.HostCloseResult result = HostCloseCoordinator.closeBoundHostWindow(target);
            if (result.success()) {
                context.logger().info("EXTERNAL_PSD_EDIT_EXIT " + result.diagnostic());
            } else {
                context.logger().error("EXTERNAL_PSD_EDIT_EXIT_FAILED " + result.diagnostic());
            }
        } catch (Throwable error) {
            context.logger().error("EXTERNAL_PSD_EDIT_EXIT_FAILED", error);
        }
    }

    /** F2 preparation only: two saved controls coexist; no replacement or isolation claim. */
    private void runSecondDocumentPreparation(final Properties result) throws Exception {
        result.setProperty("f2.acceptance", "NOT_CLAIMED");
        result.setProperty("secondDocument.scope", "OPEN_AND_OBSERVED_IDENTITY_ONLY");
        structuralRunId = result.getProperty("runId", "");
        if (structuralRunId.isBlank()) throw new IllegalStateException("second-document task ID missing");
        if (contentProfile != PsdValidationContent.Profile.SEVEN_LAYER_CONTROL) throw new IllegalArgumentException(
            "second-document preparation requires control7");
        final Path home = context.paths().stateDir().toAbsolutePath().normalize().getParent().getParent();
        final Path source = home.resolve("second-document/prepared-control.cmo3");
        final TargetResolution.Target first = resolveTarget(result);
        if (!"bd254b80-4cda-4a7e-a187-c4aa4d41ed44".equals(first.raw().value())) {
            throw new IllegalStateException("first document is not the fixed persisted control");
        }
        hostWindowAccess = prepareHostWindowAccess();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240L);
        final CubismModel firstModel = structuralEdt(deadline, () -> {
            requireStructuralModelOnEdt(first);
            return context.cubism().model().active();
        });
        final String firstContent = structuralEdt(deadline, () -> {
            requireStructuralModelOnEdt(first);
            return context.cubism().activeDocument().orElseThrow().contentId().orElseThrow();
        });
        final var prepared = OfficialSecondDocumentOpen.open(
            new OfficialSecondDocumentOpen.Request(home, source,
                "7ed2d0296791cca5f3ad8a7ccd955999a35dc755d32a62268cdfd0007d95b41c",
                first.identity().documentId(), firstContent, guiBoundWindow,
                TargetResolution.remainingStructuralMillis(deadline)), () -> stopped,
            () -> structuralTaskActive(deadline), (second, completing) -> {
                if (!completing) {
                    requireStructuralModelOnEdt(first);
                    verifyCurrentBoundTaskWindowOnEdt(first.identity());
                }
                final var document = context.cubism().activeDocument().orElseThrow();
                final CubismModel model = context.cubism().model().active();
                final TextureRelationsSnapshot relations = model.textures().relations();
                if (officialCurrentHostWindowOnEdt() != guiBoundWindow
                    || !model.id().value().equals(second.modelId()) || !relations.isAvailable()
                    || relations.rawImages().size() != second.rawNames().size()
                    || relations.modelImages().size() != second.images().size()
                    || !relations.rawImages().stream().map(raw -> raw.id().value())
                        .collect(java.util.stream.Collectors.toSet()).equals(second.rawNames().keySet())) {
                    throw new IllegalStateException("second document SDK/native identity or fixed control differs");
                }
                final Map<String, OfficialSecondDocumentOpen.Image> sdkImages = new java.util.HashMap<>();
                for (var image : relations.modelImages()) {
                    sdkImages.put(image.id().value(), new OfficialSecondDocumentOpen.Image(
                        image.currentRawImageId().orElseThrow().value(),
                        image.linkedRawImageIds().stream().map(RawImageId::value).toList(),
                        image.inputsByRawImage().keySet().stream().map(RawImageId::value)
                            .collect(java.util.stream.Collectors.toSet())));
                }
                if (!sdkImages.equals(second.images())) throw new IllegalStateException(
                    "second document SDK/native model-image relations differ");
                result.setProperty("secondDocument.sdk.binding", relations.binding());
                result.setProperty("secondDocument.sdk.generation", Long.toString(relations.generation()));
                if (completing && (!second.rawNames().keySet().equals(Set.of(
                        "ba17684e-3801-461f-97ac-7f59dd720bb5")) || second.images().size() != 7)) {
                    throw new IllegalStateException("second control identity differs");
                }
                result.setProperty("secondDocument.sdk." + (completing ? "second" : "first")
                    + ".documentId", document.documentId());
                result.setProperty("secondDocument.sdk." + (completing ? "second" : "first")
                    + ".contentId", document.contentId().orElseThrow());
                return new OfficialSecondDocumentOpen.SdkIdentity(
                    document.documentId(), document.contentId().orElseThrow());
        });
        structuralEdt(deadline, () -> {
            if (!structuralTaskActive(deadline) || officialCurrentHostWindowOnEdt() != guiBoundWindow
                || !context.cubism().activeDocument().orElseThrow().documentId().equals(
                    result.getProperty("secondDocument.sdk.second.documentId"))) {
                throw new IllegalStateException("second document changed before stale-read observation");
            }
            TargetResolution.requireStaleEditorReadRejected(() -> firstModel.textures().relations());
            result.setProperty("secondDocument.staleFirstModelRead", "REJECTED");
            return Boolean.TRUE;
        });
        for (var entry : Map.of("first", prepared.first(), "second", prepared.second()).entrySet()) {
            final String prefix = "secondDocument." + entry.getKey();
            final var document = entry.getValue();
            result.setProperty(prefix + ".documentId", document.documentId());
            result.setProperty(prefix + ".contentId", document.contentId());
            result.setProperty(prefix + ".modelId", document.modelId());
            result.setProperty(prefix + ".path", document.file().toString());
            result.setProperty(prefix + ".rawNames", document.rawNames().toString());
            result.setProperty(prefix + ".modelImages", document.images().toString());
        }
        result.setProperty("secondDocument.commandCalls", Integer.toString(prepared.commandCalls()));
        if (TargetResolution.f2AcceptanceRequested()) {
            runSecondDocumentAcceptance(result, firstModel, first, prepared);
        } else {
            result.setProperty("f2.acceptance", "NOT_CLAIMED");
        }
        result.setProperty("secondDocument.status", "PREPARED");
    }

    /**
     * F2 acceptance in the managed second document: explicit-target SDK replace with decoded
     * RGB proof, then stale first-document write rejection, then a fresh native object-graph
     * observation proves the first document is untouched. Official dual-input remains a
     * separate slice and is recorded as PENDING, never as acceptance.
     */
    private void runSecondDocumentAcceptance(final Properties result, final CubismModel firstModel,
        final TargetResolution.Target firstTarget, final OfficialSecondDocumentOpen.Result prepared)
        throws Exception {
        result.setProperty("f2.acceptance", "REQUESTED");
        result.setProperty("f2.scope", "SDK_REPLACE_STALE_WRITE_FIRST_ISOLATION");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240L);
        final AtomicReference<TargetResolution.Target> resolved = new AtomicReference<>();
        structuralEdt(deadline, () -> {
            final var document = context.cubism().activeDocument().orElseThrow();
            if (!document.documentId().equals(
                    result.getProperty("secondDocument.sdk.second.documentId"))) {
                throw new IllegalStateException("active document is not the prepared second document");
            }
            final CubismModel model = context.cubism().model().active();
            final TextureRelationsSnapshot relations = model.textures().relations();
            if (!relations.isAvailable()) {
                throw new IllegalStateException("second document relations unavailable");
            }
            final TargetResolution.Target picked = pickTarget(relations).orElseThrow(() ->
                new IllegalStateException("second document has no resolvable current raw"));
            resolved.set(new TargetResolution.Target(picked.artMesh(), picked.modelImage(), picked.raw(),
                picked.rawReplaced(), new TargetResolution.TargetIdentity(document.documentId(), model.id().value(),
                    relations.binding(), relations.generation(), picked.modelImage().value(),
                    picked.artMesh().id().value(), picked.raw().value())));
            return Boolean.TRUE;
        });
        final TargetResolution.Target target = resolved.get();
        if (firstTarget.identity().documentId().equals(target.identity().documentId())) {
            throw new IllegalStateException("first and second document identities do not differ");
        }
        result.setProperty("f2.target.documentId", target.identity().documentId());
        result.setProperty("f2.target.raw", target.raw().value());
        result.setProperty("f2.target.generation", Long.toString(target.identity().generation()));
        final TempExportTracker.TempTracker tracker = new TempExportTracker.TempTracker();
        Throwable acceptanceFailure = null;
        try {
            final TempExportTracker.TrackedExport baseline = exportTracked(result, target.raw(), tracker, "f2Baseline");
            final byte[] baselineBytes = Files.readAllBytes(baseline.path());
            final PsdValidationContent.Fingerprint baselineRgb =
                DiagnosticObservationRecorder.targetFingerprint(baselineBytes, "F2 second-document baseline", contentProfile);
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "f2.baseline.targetRgb", baselineRgb);
            result.setProperty("f2.baseline.sha256", sha256(baselineBytes));
            final byte[] mutated = PsdValidationContent.invertTargetLayerRgb(
                baselineBytes, contentProfile);
            final PsdValidationContent.Fingerprint written =
                DiagnosticObservationRecorder.targetFingerprint(mutated, "F2 second-document mutation", contentProfile);
            if (baselineRgb.equals(written)) throw new IllegalStateException(
                "F2 RGB mutation did not change the decoded target content");
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "f2.mutation.targetRgb", written);
            result.setProperty("f2.mutation.sha256", sha256(mutated));
            final Deque<SessionFileReadiness.RevisionEvent> revisions = new ArrayDeque<>();
            RawImageId afterRaw = null;
            final Registration subscription = baseline.file().observeSaves(revision -> {
                synchronized (revisions) {
                    revisions.add(new SessionFileReadiness.RevisionEvent(revision, System.nanoTime()));
                    revisions.notifyAll();
                }
            });
            try {
                SessionFileReadiness.assertNoRevision(revisions, 500L, "F2 baseline revision replayed");
                requireTargetBinding(result, target, "f2.writeAdmission", 0L);
                if (!structuralTaskActive(deadline)) {
                    throw new IllegalStateException("F2 write cancelled");
                }
                Files.write(baseline.path(), mutated);
                final PsdFileRevision revision = awaitRevision(revisions, 20).revision();
                if (!PsdStructureInspection.sha256(mutated).equals(PsdStructureInspection.sha256(Files.readAllBytes(baseline.path())))) {
                    throw new IllegalStateException("F2 issued file changed unexpectedly before replace");
                }
                final CompletionStage<PsdReplaceResult> stage = structuralEdt(deadline, () -> {
                    requireTargetBinding(result, target, "f2.replaceAdmission", 0L);
                    return context.cubism().model().active().textures()
                        .replaceRawImagePsd(target.raw(), baseline.file(), revision);
                });
                final PsdReplaceResult replaced = stage.toCompletableFuture().get(
                    TargetResolution.remainingStructuralMillis(deadline), TimeUnit.MILLISECONDS);
                DiagnosticObservationRecorder.recordImportCompletion(result, "f2", replaced);
                if (replaced.status() != PsdReplaceResult.Status.APPLIED
                    || !replaced.before().equals(target.raw())
                    || !replaced.consumedRevision().equals(Optional.of(revision))) {
                    throw new IllegalStateException(
                        "F2 second-document replacement was not APPLIED/consumed");
                }
                result.setProperty("f2.afterRaw", replaced.after().map(RawImageId::value)
                    .orElse(RawExportCoordination.UNAVAILABLE_VALUE));
                afterRaw = replaced.after().orElse(target.raw());
                TargetResolution.requireStaleEditorReadRejected(() -> firstModel.textures()
                    .replaceRawImagePsd(firstTarget.raw(), baseline.file(), revision));
                result.setProperty("f2.staleWriteTarget", "REJECTED");
                SessionFileReadiness.assertNoRevision(revisions, 500L, "F2 revision delivered twice");
            } finally {
                subscription.close();
            }
            final TempExportTracker.TrackedExport post = exportTracked(result,
                afterRaw != null ? afterRaw : target.raw(), tracker, "f2PostReplace");
            final byte[] postBytes = Files.readAllBytes(post.path());
            final PsdValidationContent.Fingerprint postRgb =
                DiagnosticObservationRecorder.targetFingerprint(postBytes, "F2 post-replace fresh export", contentProfile);
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "f2.postReplace.targetRgb", postRgb);
            result.setProperty("f2.postReplace.sha256", sha256(postBytes));
            if (!written.equals(postRgb)) throw new IllegalStateException(
                "fresh native export after F2 replace differs from the written mutation");
            final OfficialSecondDocumentOpen.Host nativeHost = OfficialSecondDocumentOpen.newNativeHost();
            verifyFirstDocumentIsolation(result, nativeHost, prepared, afterRaw, deadline);
            verifyOfficialUndoRedoRefresh(result, nativeHost, prepared, target, afterRaw, deadline);
            result.setProperty("f2.dualInput", "PENDING");
            result.setProperty("f2.actual",
                "second-document SDK replace APPLIED; fresh export matches written RGB; stale write rejected; "
                    + "native re-observation shows the first document unchanged; official undo/redo "
                    + "relations refresh verified");
            result.setProperty("f2.acceptance", "PASS");
        } catch (Exception failure) {
            acceptanceFailure = failure;
            throw failure;
        } catch (Error failure) {
            acceptanceFailure = failure;
            throw failure;
        } finally {
            TempExportTracker.stopAllPreservingPrimary(tracker, result, acceptanceFailure);
        }
    }

    /**
     * Proves the second-document replacement stayed inside the second document: a fresh native
     * object-graph observation must show the first document exactly as it was at open time,
     * while the second document carries the applied replacement raw. No document activation,
     * no UI interaction, no pixel reads - identity, relation and dirty state only.
     */
    private void verifyFirstDocumentIsolation(final Properties result,
        final OfficialSecondDocumentOpen.Host host, final OfficialSecondDocumentOpen.Result prepared,
        final RawImageId afterRaw, final long deadline) throws Exception {
        final OfficialSecondDocumentOpen.Recheck recheck = structuralEdt(deadline,
            () -> OfficialSecondDocumentOpen.reobserve(prepared, host));
        final var first = recheck.first();
        final boolean rawSetUnchanged = first.rawNames().equals(prepared.first().rawNames());
        final boolean imagesUnchanged = first.images().equals(prepared.first().images());
        final boolean dirtyUnchanged = first.dirty() == prepared.first().dirty();
        final boolean contentGuidUnchanged = first.contentId().equals(prepared.first().contentId());
        result.setProperty("f2.firstIsolation.documentId", first.documentId());
        result.setProperty("f2.firstIsolation.rawSet", rawSetUnchanged ? "UNCHANGED" : "CHANGED");
        result.setProperty("f2.firstIsolation.images", imagesUnchanged ? "UNCHANGED" : "CHANGED");
        result.setProperty("f2.firstIsolation.dirty", Boolean.toString(first.dirty()));
        result.setProperty("f2.firstIsolation.dirtyUnchanged", Boolean.toString(dirtyUnchanged));
        result.setProperty("f2.firstIsolation.contentGuidUnchanged",
            Boolean.toString(contentGuidUnchanged));
        if (first.nativeDocument() != prepared.first().nativeDocument()
            || !first.documentId().equals(prepared.first().documentId())) throw new IllegalStateException(
            "first document identity changed across the second-document replace");
        if (!rawSetUnchanged) throw new IllegalStateException(
            "first document raw identity set changed across the second-document replace");
        if (!imagesUnchanged) throw new IllegalStateException(
            "first document model-image relations changed across the second-document replace");
        if (!dirtyUnchanged) throw new IllegalStateException(
            "first document dirty state changed across the second-document replace");
        final var second = recheck.second();
        final java.util.Set<String> secondCurrents = second.images().values().stream()
            .map(OfficialSecondDocumentOpen.Image::current).collect(java.util.stream.Collectors.toSet());
        result.setProperty("f2.secondAfter.rawCount", Integer.toString(second.rawNames().size()));
        result.setProperty("f2.secondAfter.currentRaw", secondCurrents.toString());
        if (!second.rawNames().containsKey(afterRaw.value()) || !secondCurrents.equals(Set.of(afterRaw.value()))) {
            throw new IllegalStateException(
                "second document does not carry the applied replacement raw as every current");
        }
        result.setProperty("f2.firstIsolation", "PASS");
    }

    /**
     * FR-008: relations must be re-read after every import, undo and redo. Drives the official
     * command_undo/command_redo on the live second document and requires the SDK relations to
     * track each official change (candidate cardinality and current raw) with no stale cache.
     */
    private void verifyOfficialUndoRedoRefresh(final Properties result,
        final OfficialSecondDocumentOpen.Host host, final OfficialSecondDocumentOpen.Result prepared,
        final TargetResolution.Target target, final RawImageId afterRaw, final long deadline) throws Exception {
        structuralEdt(deadline, () -> {
            host.undo(prepared.second().nativeDocument());
            return Boolean.TRUE;
        });
        awaitNoDialog(host, deadline);
        final TextureRelationsSnapshot afterUndo = structuralEdt(deadline, () -> {
            final TextureRelationsSnapshot relations =
                context.cubism().model().active().textures().relations();
            if (!relations.isAvailable()) throw new IllegalStateException(
                "relations unavailable after official undo");
            return relations;
        });
        final TargetResolution.Target undoTarget = pickTarget(afterUndo).orElseThrow(
            () -> new IllegalStateException("no resolvable current raw after official undo"));
        result.setProperty("f2.undoRefresh.rawCount", Integer.toString(afterUndo.rawImages().size()));
        result.setProperty("f2.undoRefresh.current", undoTarget.raw().value());
        if (afterUndo.rawImages().size() != 1 || !undoTarget.raw().equals(target.raw())) {
            throw new IllegalStateException(
                "SDK relations after official undo differ from the pre-replacement state");
        }
        structuralEdt(deadline, () -> {
            host.redo(prepared.second().nativeDocument());
            return Boolean.TRUE;
        });
        awaitNoDialog(host, deadline);
        final TextureRelationsSnapshot afterRedo = structuralEdt(deadline, () -> {
            final TextureRelationsSnapshot relations =
                context.cubism().model().active().textures().relations();
            if (!relations.isAvailable()) throw new IllegalStateException(
                "relations unavailable after official redo");
            return relations;
        });
        final TargetResolution.Target redoTarget = pickTarget(afterRedo).orElseThrow(
            () -> new IllegalStateException("no resolvable current raw after official redo"));
        result.setProperty("f2.redoRefresh.rawCount", Integer.toString(afterRedo.rawImages().size()));
        result.setProperty("f2.redoRefresh.current", redoTarget.raw().value());
        if (afterRedo.rawImages().size() != 2 || !redoTarget.raw().equals(afterRaw)) {
            throw new IllegalStateException(
                "SDK relations after official redo differ from the replaced state");
        }
        result.setProperty("f2.undoRedoRefresh", "PASS");
    }

    private void awaitNoDialog(final OfficialSecondDocumentOpen.Host host,
        final long deadline) throws Exception {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (System.nanoTime() < until) {
            final Boolean clear = structuralEdt(deadline, () -> Boolean.valueOf(!host.hasDialog()));
            if (clear) return;
            Thread.sleep(200L);
        }
        throw new IllegalStateException("official dialog still showing after undo/redo");
    }

    /** Collects one F5 variant; a separate audit compares independent native/SDK task copies. */
    private void runStructuralControl(final Properties result, final String phase) throws Exception {
        if (contentProfile != PsdValidationContent.Profile.F1_2048_20) throw new IllegalArgumentException(
            "structural control requires the F1 content profile");
        structuralRunId = result.getProperty("runId", "");
        if (structuralRunId.isBlank()) throw new IllegalStateException("structural task identity is missing");
        final String variant = System.getProperty("turboism.validation.externalpsd.structureVariant", "");
        final String expectedSha = PsdStructuralControl.variantSha256(variant);
        final Path home = context.paths().stateDir().toAbsolutePath().normalize().getParent().getParent();
        SessionFileReadiness.requireNoSymlinkPath(home, "structural task home");
        final Path source = home.resolve("structural-input/external-edit.psd");
        SessionFileReadiness.requireNoSymlinkPath(source, "structural source");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException(
            "fixed structural source is missing");
        final byte[] incoming = Files.readAllBytes(source);
        if (!expectedSha.equals(PsdStructureInspection.sha256(incoming))) throw new IllegalStateException(
            "structural source SHA differs from the fixed official-writer asset");
        result.setProperty("structure.variant", variant);
        result.setProperty("structure.source.sha256", expectedSha);
        result.setProperty("structure.comparison", "NOT_RUN: compare independent native and SDK evidence");
        result.setProperty("structure.finalViewport", "UNAVAILABLE");
        final TargetResolution.Target target = resolveTarget(result);
        hostWindowAccess = prepareHostWindowAccess();
        structuralColorReader = OfficialPsdReplacementBaseline.prepareAuthoringColorReader(context);
        structuralInputReader = OfficialPsdReplacementBaseline.prepareInputDetailReader(context);
        result.setProperty("structure.inputDetails.version", "1");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240L);
        final PsdStructuralState.Basis basis = structuralEdt(deadline, () -> {
            final CubismModel model = requireStructuralModelOnEdt(target);
            return PsdStructuralState.basis(model, model.textures().relations());
        });
        final TargetResolution.StructuralObservation before = structuralObservation(target, basis, deadline);
        PsdStructuralControl.requireHistory(before.history());
        TargetResolution.recordStructuralObservation(result, "before", before);
        final TempExportTracker.TempTracker tracker = new TempExportTracker.TempTracker();
        Throwable primary = null;
        try {
            final TempExportTracker.TrackedExport issued = exportTracked(result, target.raw(), tracker, "structureBaseline");
            DiagnosticObservationRecorder.targetFingerprint(Files.readAllBytes(issued.path()), "structural F1 baseline", contentProfile);
            Optional<RawImageId> expectedAfter = Optional.empty();
            if ("structure-native".equals(phase)) {
                final var identityReader = OfficialPsdReplacementBaseline.prepareIdentityReader(
                    context, target.raw().value());
                final var identity = structuralEdt(deadline, () -> {
                    requireStructuralModelOnEdt(target);
                    return identityReader.observe();
                });
                final var nativeResult = OfficialPsdReplacementBaseline.replace(context, () -> stopped,
                    () -> structuralTaskActive(deadline),
                    new OfficialPsdReplacementBaseline.ReplacementRequest(source, home, expectedSha,
                        "external-edit.psd", identity.documentId(), identity.modelId(), identity.rawGuid(),
                        identity.modelingDocument(), identity.layeredImage(), guiBoundWindow,
                        identityReader, remainingStructuralMillis(deadline)));
                nativeResult.evidence().forEach((key, value) ->
                    result.setProperty("structure.native." + key, value));
                result.setProperty("structure.native.chooserComplete",
                    Boolean.toString(nativeResult.chooserOperationComplete()));
            } else {
                final Deque<SessionFileReadiness.RevisionEvent> revisions = new ArrayDeque<>();
                final Registration subscription = issued.file().observeSaves(revision -> {
                    synchronized (revisions) {
                        revisions.add(new SessionFileReadiness.RevisionEvent(revision, System.nanoTime()));
                        revisions.notifyAll();
                    }
                });
                try {
                    SessionFileReadiness.assertNoRevision(revisions, 500L, "structural baseline revision replayed");
                    requireTargetBinding(result, target, "structure.beforeWrite", 0L);
                    if (!structuralTaskActive(deadline)) throw new IllegalStateException(
                        "structural write cancelled");
                    Files.write(issued.path(), incoming);
                    final PsdFileRevision revision = awaitRevision(revisions, 20).revision();
                    // Revisions are opaque. Prove the task-owned file bytes independently, then
                    // require that this exact issued token was consumed by the bound operation.
                    if (!expectedSha.equals(PsdStructureInspection.sha256(Files.readAllBytes(issued.path())))) {
                        throw new IllegalStateException("structural issued file changed unexpectedly");
                    }
                    final CompletionStage<PsdReplaceResult> replaceStage = structuralEdt(deadline, () -> {
                        final CubismModel model = requireStructuralModelOnEdt(target);
                        requireTargetBinding(result, target, "structure.replaceAdmission", 0L);
                        return model.textures().replaceRawImagePsd(target.raw(), issued.file(), revision);
                    });
                    final PsdReplaceResult replaced = replaceStage.toCompletableFuture().get(
                        TargetResolution.remainingStructuralMillis(deadline), TimeUnit.MILLISECONDS);
                    DiagnosticObservationRecorder.recordImportCompletion(result, "structure.sdk", replaced);
                    if (replaced.status() != PsdReplaceResult.Status.APPLIED
                        || !replaced.before().equals(target.raw())
                        || !replaced.consumedRevision().equals(Optional.of(revision))) {
                        throw new IllegalStateException("structural SDK replacement was not APPLIED/consumed");
                    }
                    expectedAfter = replaced.after();
                    result.setProperty("structure.sdk.afterRaw", expectedAfter
                        .map(RawImageId::value).orElse(RawExportCoordination.UNAVAILABLE_VALUE));
                    SessionFileReadiness.assertNoRevision(revisions, 500L, "structural revision was delivered twice");
                } finally {
                    subscription.close();
                }
            }
            final TargetResolution.StructuralObservation after = awaitStructuralCommit(target, basis, before, expectedAfter, deadline);
            TargetResolution.recordStructuralObservation(result, "after", after);
            structuralEdt(deadline, () -> {
                requireStructuralModelOnEdt(target);
                PersistSaveChecks.requireHistoryMoved(context.cubism().history().moveTo(after.history(),
                    before.history().position()), "structural undo");
                return Boolean.TRUE;
            });
            final TargetResolution.StructuralObservation undo = structuralObservation(target, basis, deadline);
            TargetResolution.recordStructuralObservation(result, "undo", undo);
            structuralEdt(deadline, () -> {
                requireStructuralModelOnEdt(target);
                PersistSaveChecks.requireHistoryMoved(context.cubism().history().moveTo(undo.history(),
                    after.history().position()), "structural redo");
                return Boolean.TRUE;
            });
            final TargetResolution.StructuralObservation redo = structuralObservation(target, basis, deadline);
            TargetResolution.recordStructuralObservation(result, "redo", redo);
            if (!after.values().equals(redo.values())) throw new IllegalStateException(
                "structural redo differs from the observed native post state");
            // Structural edits may remove the original image. Bind a fresh surviving target only
            // for SAVE_AS; the complete before/after graph remains in the comparison evidence.
            final TargetResolution.Target saveTarget = structuralEdt(deadline, () -> {
                final CubismModel model = requireStructuralModelOnEdt(target);
                final TextureRelationsSnapshot relations = model.textures().relations();
                final TargetResolution.Target picked = pickTarget(relations, PsdValidationContent.Profile.SEVEN_LAYER_CONTROL)
                    .orElseThrow(() -> new IllegalStateException("no surviving SAVE_AS anchor"));
                return new TargetResolution.Target(picked.artMesh(), picked.modelImage(), picked.raw(), picked.rawReplaced(),
                    new TargetResolution.TargetIdentity(target.identity().documentId(), model.id().value(),
                        relations.binding(), relations.generation(), picked.modelImage().value(),
                        picked.artMesh().id().value(), picked.raw().value()));
            });
            if (!structuralTaskActive(deadline)) throw new IllegalStateException(
                "structural save cancelled before admission");
            saveBoundDocumentCopy(result, saveTarget, deadline, () -> structuralTaskActive(deadline));
            TargetResolution.recordStructuralObservation(result, "saved", structuralObservation(target, basis, deadline));
            final Path saved = home.resolve("persisted-document.cmo3");
            SessionFileReadiness.requireNoSymlinkPath(saved, "structural saved CMO");
            if (!Files.isRegularFile(saved, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException(
                "structural saved CMO is missing");
            result.setProperty("structure.savedCmo.sha256", sha256(saved));
            result.setProperty("structure.collection", "PASS");
            result.setProperty("expected", "one structural native/SDK observation and SAVE_AS");
            result.setProperty("actual", "history commit, public graph, Undo/Redo and SAVE_AS collected; comparison pending");
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            TempExportTracker.stopAllPreservingPrimary(tracker, result, primary);
        }
    }

    private boolean structuralTaskActive(final long deadline) {
        return !stopped && !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline
            && structuralRunId.equals(System.getProperty("turboism.validation.externalpsd.runId"));
    }

    private CubismModel requireStructuralModelOnEdt(final TargetResolution.Target original) {
        if (!SwingUtilities.isEventDispatchThread() || stopped
            || !structuralRunId.equals(System.getProperty("turboism.validation.externalpsd.runId"))) throw new IllegalStateException(
            "structural observation is off EDT or stopped");
        if (officialCurrentHostWindowOnEdt() != guiBoundWindow) throw new IllegalStateException(
            "structural host window changed");
        final var document = context.cubism().activeDocument().orElseThrow();
        final CubismModel model = context.cubism().model().active();
        final TextureRelationsSnapshot relations = model.textures().relations();
        if (!original.identity().documentId().equals(document.documentId())
            || !original.identity().modelId().equals(model.id().value())
            || !relations.isAvailable() || !original.identity().binding().equals(relations.binding())
            || original.identity().generation() != relations.generation()) {
            throw new IllegalStateException("structural document/model generation changed");
        }
        return model;
    }

    private TargetResolution.StructuralObservation structuralObservation(final TargetResolution.Target original,
        final PsdStructuralState.Basis basis, final long deadline) throws Exception {
        return structuralEdt(deadline, () -> {
            final CubismModel model = requireStructuralModelOnEdt(original);
            final TextureRelationsSnapshot relations = model.textures().relations();
            final var colors = structuralColorReader.observe();
            final var inputs = structuralInputReader.observe();
            final Map<String, String> values = PsdStructuralState.capture(model, relations, basis, colors, inputs);
            requireStructuralModelOnEdt(original);
            final var history = context.cubism().history().snapshot();
            boolean modal = false;
            for (final Window window : Window.getWindows()) {
                if (!(window instanceof Dialog dialog) || !dialog.isModal() || !dialog.isShowing()) continue;
                Window owner = dialog.getOwner();
                while (owner != null && owner != guiBoundWindow) owner = owner.getOwner();
                if (owner == guiBoundWindow) modal = true;
            }
            return new TargetResolution.StructuralObservation(values, history,
                relations.rawImages().stream().map(raw -> raw.id().value()).collect(java.util.stream.Collectors.toSet()),
                modal);
        });
    }

    private TargetResolution.StructuralObservation awaitStructuralCommit(final TargetResolution.Target target,
        final PsdStructuralState.Basis basis, final TargetResolution.StructuralObservation before,
        final Optional<RawImageId> expectedAfter, final long deadline) throws Exception {
        TargetResolution.StructuralObservation previous = null;
        int stable = 0;
        while (!stopped && System.nanoTime() < deadline) {
            final TargetResolution.StructuralObservation current = structuralObservation(target, basis, deadline);
            if (PsdStructuralControl.oneReplacementEntry(before.history(), current.history())
                && !current.modalShowing()) {
                final Set<String> added = new HashSet<>(current.rawIds());
                added.removeAll(before.rawIds());
                if (added.size() != 1 || expectedAfter.isPresent()
                    && !added.contains(expectedAfter.orElseThrow().value())) throw new IllegalStateException(
                    "structural replacement must expose the single observed incoming raw identity");
                stable = previous != null && previous.values().equals(current.values()) ? stable + 1 : 1;
                if (stable >= 3) return current;
                previous = current;
            } else {
                previous = null;
                stable = 0;
            }
            Thread.sleep(Math.min(200L, remainingStructuralMillis(deadline)));
        }
        throw new IllegalStateException("structural native history/graph observation timed out");
    }

    private <T> T structuralEdt(final long deadline, final Callable<T> operation) throws Exception {
        return structuralEdt(deadline, () -> structuralTaskActive(deadline), operation);
    }

    static <T> T structuralEdt(final long deadline, final BooleanSupplier taskActive,
        final Callable<T> operation) throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        try {
            final HostCloseCoordinator.EdtCall<T> call = HostCloseCoordinator.invokeEdtBounded(() -> {
                if (!taskActive.getAsBoolean() || !active.get() || System.nanoTime() >= deadline) throw new IllegalStateException(
                    "structural operation cancelled before EDT entry");
                return operation.call();
            }, remainingStructuralMillis(deadline));
            if (!call.completed()) throw new IllegalStateException("structural EDT deadline exhausted");
            if (call.failure() != null) throw new IllegalStateException("structural EDT operation failed", call.failure());
            if (!taskActive.getAsBoolean() || System.nanoTime() >= deadline) throw new IllegalStateException("structural deadline exhausted");
            return Objects.requireNonNull(call.value(), "structural observation");
        } finally {
            active.set(false);
        }
    }

    /** Full save→replace→undo→stop→recover pipeline plus optional persist tail. */
    private void runPipeline(final Properties result, final int cycles,
        final PerformanceEvidence.PerformanceCycleTimings performanceTimings) throws Exception {
        if (cycles < 1) throw new IllegalArgumentException("cycles must be at least 1");
        result.setProperty("realEditorApplication",
            "default-application launch recorded; OPENED requires the task .psd association");
        final TargetResolution.Target initialTarget = resolveTarget(result);
        final String compositionProfile = System.getProperty(
            "turboism.validation.externalpsd.compositionProfile", "");
        if (!compositionProfile.isEmpty()) {
            if (!Set.of("normal", "legacy").contains(compositionProfile)) {
                throw new IllegalArgumentException("unknown composition profile " + compositionProfile);
            }
            hostWindowAccess = prepareHostWindowAccess();
            compositionReader = OfficialPsdReplacementBaseline.prepareCompositionReader(context);
            baselineComposition = readComposition(result, "composition.baseline", initialTarget);
            TargetResolution.requireCompositionProfile(baselineComposition, compositionProfile);
            result.setProperty("composition.profile", compositionProfile);
        }
        final boolean persistValidation = "1".equals(
            System.getProperty("turboism.validation.externalpsd.persist"));
        final boolean validateContent = TargetResolution.contentValidationRequired(contentProfile,
            persistValidation);
        final TempExportTracker.TempTracker tracker = new TempExportTracker.TempTracker();
        Throwable pipelineFailure = null;
        try {
            if (persistValidation) {
                requireTargetBinding(result, initialTarget,
                    "export.baseline.targetBinding.before", 0L);
            }
            final TempExportTracker.TrackedExport primary = exportTracked(
                result, initialTarget.raw(), tracker, "baseline");
            final Path tempFile = primary.path();
            if (persistValidation) {
                requireTargetBinding(result, initialTarget,
                    "export.baseline.targetBinding.after", 0L);
            }
            final byte[] baselineBytes = Files.readAllBytes(tempFile);
            final PsdValidationContent.Fingerprint baselineFingerprint = validateContent
                ? targetFingerprint(baselineBytes, "pipeline baseline", contentProfile) : null;
            result.setProperty("tempFile.discovered", Boolean.toString(tempFile.getFileName()
                .toString().equals("external-edit.psd")));
            result.setProperty("baseline.bytes", Integer.toString(baselineBytes.length));
            result.setProperty("baseline.sha256", sha256(baselineBytes));
            result.setProperty("baseline.revisionIssued", "true");
            if (validateContent) {
                DiagnosticObservationRecorder.recordTargetFingerprint(result, "content.baselineTargetRgb", baselineFingerprint);
                result.setProperty("content.baselineTargetRgbSha256", baselineFingerprint.sha256());
            }
            if (persistValidation) {
                DiagnosticObservationRecorder.recordTargetFingerprint(result, "persist.baselineTargetRgb", baselineFingerprint);
                result.setProperty("persist.baselineTargetRgbSha256", baselineFingerprint.sha256());
                captureDiagnosticObservation(
                    result,
                    "persist.observation.baseline",
                    initialTarget,
                    baselineFingerprint,
                    "native-export-result",
                    "",
                    primary.exportStartedAtEpochMillis(),
                    primary.exportCompletedAtEpochMillis(),
                    0L,
                    true
                );
            }

            if (persistValidation) {
                // A second independent native export is required before any external edit. It is
                // not a copy of the first export and is stopped before the save subscription starts.
                requireTargetBinding(result, initialTarget,
                    "export.baselineSecond.targetBinding.before", 0L);
                final TempExportTracker.TrackedExport second = exportTracked(
                    result, initialTarget.raw(), tracker, "baselineSecond");
                Throwable secondaryFailure = null;
                try {
                    requireTargetBinding(result, initialTarget,
                        "export.baselineSecond.targetBinding.after", 0L);
                    final PsdValidationContent.Fingerprint secondFingerprint = DiagnosticObservationRecorder.targetFingerprint(
                        Files.readAllBytes(second.path()), "pipeline second baseline",
                        contentProfile);
                    DiagnosticObservationRecorder.recordTargetFingerprint(
                        result, "persist.baselineSecondTargetRgb", secondFingerprint);
                    result.setProperty("persist.baselineSecondTargetRgbSha256",
                        secondFingerprint.sha256());
                    captureDiagnosticObservation(
                        result,
                        "persist.observation.baselineSecond",
                        initialTarget,
                        secondFingerprint,
                        "native-export-result",
                        "",
                        second.exportStartedAtEpochMillis(),
                        second.exportCompletedAtEpochMillis(),
                        0L,
                        true
                    );
                    PersistSaveChecks.requireStableBaseline(baselineFingerprint, secondFingerprint);
                } catch (Exception failure) {
                    secondaryFailure = failure;
                    throw failure;
                } catch (Error failure) {
                    secondaryFailure = failure;
                    throw failure;
                } finally {
                    TempExportTracker.stopPreservingPrimary(tracker, second, result, secondaryFailure);
                }
            }

            final PsdEditFile file = primary.file();
            final Deque<SessionFileReadiness.RevisionEvent> revisions = new ArrayDeque<>();
            final Registration subscription = file.observeSaves(revision -> {
                synchronized (revisions) {
                    // PsdFileRevision has no stable timestamp.  This callback timestamp is a
                    // probe observation used only for the explicitly labelled lower-bound
                    // diagnostic; it is never presented as native refresh timing.
                    revisions.add(new SessionFileReadiness.RevisionEvent(revision, System.nanoTime()));
                    revisions.notifyAll();
                }
            });
            try {
                SessionFileReadiness.assertNoRevision(revisions, 1500,
                    "baseline revision must not be replayed to subscribers");
                result.setProperty("baseline.replayed", "false");

                final PsdFileOperationResult opened = file.openInDefaultApplication()
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
                result.setProperty("defaultApplication.status", opened.status().name());
                result.setProperty("defaultApplication.diagnostic", opened.diagnostic());

                final SessionFileReadiness.SaveCyclesResult cyclesResult = runSaveCycles(
                    result, file, initialTarget, tempFile, revisions, tracker, cycles,
                    validateContent, performanceTimings);
                PersistSaveChecks.runAttributionUndoReleaseProbe(result);
                final TargetResolution.Target currentTarget = cyclesResult.currentTarget();
                final TargetResolution.Target lastBeforeTarget = cyclesResult.lastBeforeTarget();
                final PsdStructureInspection.Mutation marker = cyclesResult.lastMutation();
                final PsdValidationContent.Fingerprint postBeforeUndo = validateContent
                    ? exportTargetFingerprint(result, currentTarget, tracker, "postBeforeUndo")
                    : null;
                if (validateContent) {
                    result.setProperty("content.postBeforeUndoTargetRgbSha256",
                        postBeforeUndo.sha256());
                }
                if (persistValidation) {
                    result.setProperty("persist.postBeforeUndoTargetRgbSha256",
                        postBeforeUndo.sha256());
                    PersistSaveChecks.requireChangedPost(baselineFingerprint, postBeforeUndo);
                }

                runCorruptedSave(result, file, currentTarget, tempFile, revisions);
                runUndoRedo(result, lastBeforeTarget, currentTarget, tracker,
                    baselineFingerprint, postBeforeUndo);
                SessionFileReadiness.assertNoReplayAfterIdle(result, revisions);
                runStopAndRecovery(result, primary, currentTarget, tempFile, revisions, tracker);
                PersistSaveChecks.recordEnvironment(result);
                if (marker != null) {
                    // Retained as diagnostic coordinates only; the persistence gate uses the
                    // decoded target RGB fingerprint below, never this legacy marker.
                    result.setProperty("persist.markerLayer", Integer.toString(marker.layer()));
                    result.setProperty("persist.markerOffset", Integer.toString(marker.nameOffset()));
                    result.setProperty("persist.markerChar", Integer.toString(marker.letter()));
                }
                if (persistValidation) {
                    runPersistTail(result, currentTarget, tracker,
                        baselineFingerprint, postBeforeUndo);
                } else {
                    result.setProperty("documentPersistence",
                        "NOT_TESTED: persist tail not requested");
                }
            } finally {
                subscription.close();
            }
        } catch (Exception failure) {
            pipelineFailure = failure;
            throw failure;
        } catch (Error failure) {
            pipelineFailure = failure;
            throw failure;
        } finally {
            // This is also the leak guard for an export whose path discovery or later assertion
            // failed. A failed stop is recorded and prevents any quarantine PASS claim.
            TempExportTracker.stopAllPreservingPrimary(tracker, result, pipelineFailure);
        }
        result.setProperty("expected", "full pipeline assertions hold");
        result.setProperty("actual", "full pipeline assertions hold");
    }

    /**
     * Reopens a fixture copy saved by a persist run and proves the externally applied edit
     * survived a real native save → file → reopen roundtrip. The gate is the fresh native
     * export's decoded target-layer RGB fingerprint; legacy full-file/composite hashes remain
     * diagnostics only.
     */
    private void runReopen(final Properties result) throws Exception {
        final TargetResolution.Target target = resolveTarget(result);
        final String expectedTarget = System.getProperty(
            "turboism.validation.externalpsd.postEditTargetRgbSha256", "");
        if (!RawExportCoordination.isCanonicalSha256(expectedTarget)) {
            throw new IllegalStateException(
                "reopen requires a canonical lowercase postEditTargetRgbSha256");
        }
        final TempExportTracker.TempTracker tracker = new TempExportTracker.TempTracker();
        Throwable reopenFailure = null;
        try {
            requireTargetBinding(result, target, "export.reopen.targetBinding.before", 0L);
            final TempExportTracker.TrackedExport exported = exportTracked(result, target.raw(), tracker, "reopen");
            requireTargetBinding(result, target, "export.reopen.targetBinding.after", 0L);
            final byte[] bytes = Files.readAllBytes(exported.path());
            final PsdValidationContent.Fingerprint actual = DiagnosticObservationRecorder.targetFingerprint(
                bytes, "reopen target layer", contentProfile);
            result.setProperty("reopen.layerCount",
                Integer.toString(PsdStructureInspection.layerNameRanges(bytes).size()));
            result.setProperty("reopen.bytes", Integer.toString(bytes.length));
            result.setProperty("reopen.sha256", sha256(bytes));
            result.setProperty("reopen.imageSha256", imageDataSha256(bytes));
            result.setProperty("reopen.expectedTargetRgbSha256", expectedTarget);
            result.setProperty("reopen.targetRgbSha256", actual.sha256());
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "reopen.targetRgb", actual);
            final String expectedFile = System.getProperty(
                "turboism.validation.externalpsd.postEditSha256", "");
            result.setProperty("reopen.fileShaMatched",
                Boolean.toString(!expectedFile.isBlank()
                    && expectedFile.equals(result.getProperty("reopen.sha256"))));
            PersistSaveChecks.requireReopenTarget(expectedTarget, actual);
        } catch (Exception failure) {
            reopenFailure = failure;
            throw failure;
        } catch (Error failure) {
            reopenFailure = failure;
            throw failure;
        } finally {
            TempExportTracker.stopAllPreservingPrimary(tracker, result, reopenFailure);
        }
        result.setProperty("expected", "reopened fixture retains the external-edit content");
        result.setProperty("actual", "decoded target RGB verified in a fresh native export");
    }

    /**
     * Real GUI path: right-click an object row until a popup exposes the contributed item,
     * click it, then verify the product plugin's own session opens the runtime-issued temp
     * file and auto-imports a written save.
     */
    private void runGui(final Properties result) throws Exception {
        final TempExportTracker.TempTracker tracker = new TempExportTracker.TempTracker();
        Throwable guiFailure = null;
        try {
            final TargetResolution.Target target = resolveTarget(result);
            result.setProperty("gui.menuLabel", System.getProperty(
                "turboism.validation.externalpsd.menuLabel", "Edit PSD Externally"));
            final GuiRowDispatch.GuiTargetState before = observeGuiTarget(target);
            GuiRowDispatch.recordGuiTargetState(result, "before", before);
            result.setProperty("gui.generationBefore", Long.toString(before.generation()));
            GuiRowDispatch.recordGuiTargetState(result, "after",
                GuiRowDispatch.GuiTargetState.unobserved("native RGB application not attempted"));
            if (!before.observed()) {
                throw new PersistSaveChecks.Blocked("target-specific GUI replacement observation is available",
                    "gui.before unavailable: " + before.diagnostic());
            }
            if (!TargetResolution.targetIdentityMatches(target.identity(), before.identity())) {
                throw new PersistSaveChecks.Blocked(
                    "GUI target remains bound to the resolved document/model/image/ArtMesh/raw",
                    "gui.before identity is unavailable or stale: " + before);
            }
            if (before.rawReplaced()) {
                throw new PersistSaveChecks.Blocked("fixture target starts with isReplaced=false",
                    "gui.before.rawReplaced=true; no available new replacement observation");
            }
            final SessionFileReadiness.TempCandidateSnapshot sessionCandidatesBefore = snapshotTempCandidates();

            final Set<String> labels = new LinkedHashSet<>(List.of(result.getProperty("gui.menuLabel"),
                "Edit PSD Externally",
                "外部编辑 PSD", "外部編輯 PSD",
                "外部でPSDを編集", "외부에서 PSD 편집"));
            final GuiRowDispatch.GuiClick click = clickContributedItem(labels, 64, result, target);
            if (!click.clicked()) {
                throw new PersistSaveChecks.Blocked("context menu with the contributed item was reachable",
                    click.diagnostic());
            }
            result.setProperty("gui.menuPresent", "true");
            result.setProperty("gui.itemClicked", "true");

            final SessionFileReadiness.StablePsdSnapshot sessionSnapshot;
            try {
                sessionSnapshot = awaitSessionTempFile(sessionCandidatesBefore, 90, result);
            } catch (SessionFileReadiness.SessionFileReadinessException failure) {
                throw new PersistSaveChecks.Blocked("a unique, complete and stable new session PSD",
                    failure.getMessage());
            }
            result.setProperty("gui.sessionFile",
                sessionSnapshot.path().getParent().getFileName().toString());
            final SessionFileReadiness.StablePsdSnapshot writeSnapshot;
            try {
                writeSnapshot = SessionFileReadiness.confirmSessionFileForWrite(sessionSnapshot, result);
            } catch (SessionFileReadiness.SessionFileReadinessException failure) {
                throw new PersistSaveChecks.Blocked("the validated session PSD remains unchanged before mutation",
                    failure.getMessage());
            }
            final byte[] baselineBytes = writeSnapshot.bytes();
            final PsdValidationContent.Fingerprint sessionBaseline = DiagnosticObservationRecorder.targetFingerprint(
                baselineBytes, "GUI session baseline", contentProfile);
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "gui.session.baselineTargetRgb", sessionBaseline);
            result.setProperty("gui.session.baselineTargetRgbSha256", sessionBaseline.sha256());
            final byte[] mutated = PsdValidationContent.invertTargetLayerRgb(
                baselineBytes, contentProfile);
            final PsdValidationContent.Fingerprint written = DiagnosticObservationRecorder.targetFingerprint(
                mutated, "GUI session RGB mutation", contentProfile);
            if (sessionBaseline.equals(written)) {
                throw new IllegalStateException(
                    "GUI session RGB mutation did not change the decoded target content");
            }
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "gui.session.mutation.targetRgb", written);
            result.setProperty("gui.session.mutation.targetRgbSha256", written.sha256());
            result.setProperty("gui.session.mutation.bytes", Integer.toString(mutated.length));
            result.setProperty("gui.session.mutation.sha256", sha256(mutated));
            result.setProperty("gui.session.mutation.status", "PREPARED");
            SessionFileReadiness.writeSessionFile(writeSnapshot, mutated);
            result.setProperty("gui.session.mutation.status", "WRITTEN");

            final GuiRowDispatch.GuiNativeApplicationObservation observation = awaitGuiNativeApplication(
                result, target, before, written, tracker, 90);
            result.setProperty("gui.autoImportApplied", Boolean.toString(observation.applied()));
            result.setProperty("gui.nativeRgbMatchedWritten",
                Boolean.toString(observation.freshCurrentRgb() != null
                    && written.equals(observation.freshCurrentRgb())));
            GuiRowDispatch.recordGuiTargetState(result, "after", observation.after());
            result.setProperty("gui.generationAfter", Long.toString(observation.after().generation()));
            result.setProperty("gui.wrapperReplaced.before", Boolean.toString(before.rawReplaced()));
            result.setProperty("gui.wrapperReplaced.after",
                Boolean.toString(observation.after().rawReplaced()));
            if (observation.freshCurrentRgb() != null) {
                DiagnosticObservationRecorder.recordTargetFingerprint(result, "gui.currentRaw.targetRgb",
                    observation.freshCurrentRgb());
                result.setProperty("gui.currentRaw.targetRgbSha256",
                    observation.freshCurrentRgb().sha256());
            }
            if (!observation.applied()) {
                if (observation.stale()) {
                    throw new PersistSaveChecks.Blocked(
                        "GUI target document/model/image/generation/ArtMesh remains stable",
                        observation.diagnostic());
                }
                throw new IllegalStateException(
                    "plugin session did not apply the written RGB mutation to the current raw: "
                        + observation.diagnostic());
            }
            result.setProperty("expected",
                "context-menu session file RGB mutation matches a fresh native current-raw export");
            result.setProperty("actual",
                "menu click → issued session file RGB write → fresh current-raw native RGB match");
        } catch (Exception failure) {
            guiFailure = failure;
            throw failure;
        } catch (Error failure) {
            guiFailure = failure;
            throw failure;
        } finally {
            TempExportTracker.stopAllPreservingPrimary(tracker, result, guiFailure);
        }
    }

    /**
     * Waits for the exact-host runner's GUI readiness handshake before any GUI target state is
     * sampled.  The state directory comes only from the authenticated plugin context; no system
     * property can redirect this wait to another task or another window.
     */
    private void awaitGuiReadyTrigger(final Properties result) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new PersistSaveChecks.Blocked("task-local GUI readiness trigger is awaited off the EDT",
                "GUI readiness trigger wait was requested on the EDT");
        }
        final String runId = result.getProperty("runId", "");
        final GuiReadinessDiagnostics.GuiTriggerArm arm = GuiReadinessDiagnostics.prepareGuiTrigger(context.paths().stateDir());
        if (!arm.armed()) {
            throw new PersistSaveChecks.Blocked("runner-created task-local GUI readiness trigger",
                arm.diagnostic());
        }
        result.setProperty("gui.readinessTrigger", "armed");
        result.setProperty("gui.readinessTriggerPath", arm.path().toString());
        // The wrapper's readiness set includes this marker.  It must be emitted only after the
        // state directory and trigger have been checked, so a late worker cannot mistake the
        // runner's newly-created trigger for stale evidence.
        context.logger().info(GuiReadinessDiagnostics.GUI_TRIGGER_ARMED_MARKER + " path=" + arm.path());
        final long armedAtNanos = System.nanoTime();
        final AtomicReference<String> waitingStage = new AtomicReference<>(
            GuiReadinessDiagnostics.GUI_WAIT_DIAGNOSTIC_STAGE);
        final GuiReadinessDiagnostics.GuiWaitDiagnostics diagnostics;
        try {
            diagnostics = GuiReadinessDiagnostics.startGuiWaitDiagnostics(
                arm.path().getParent(), runId, armedAtNanos, () -> stopped,
                () -> Files.exists(arm.path(), LinkOption.NOFOLLOW_LINKS), waitingStage,
                GuiReadinessDiagnostics.SYSTEM_GUI_DIAGNOSTIC_CLOCK, Thread::sleep,
                () -> captureGuiThreadDump(), message -> context.logger().warn(message));
            guiWaitDiagnostics = diagnostics;
            if (stopped) diagnostics.close("disabled");
        } catch (Throwable failure) {
            // Diagnostics are strictly observational.  A restricted management interface or a
            // daemon-creation failure must not change the readiness wait or manufacture a pass.
            context.logger().warn("EXTERNAL_PSD_EDIT_GUI_THREAD_DIAGNOSTIC unavailable: "
                + failure);
        }
        final GuiReadinessDiagnostics.GuiWaitDiagnostics activeDiagnostics = guiWaitDiagnostics;
        try {
            final GuiReadinessDiagnostics.GuiTriggerWait wait = GuiReadinessDiagnostics.waitForArmedGuiTrigger(
                arm, GuiReadinessDiagnostics.GUI_READY_TRIGGER_TIMEOUT_MILLIS, () -> stopped, Thread::sleep);
            if (!wait.ready()) {
                waitingStage.set("armed-wait-ended:" + wait.diagnostic());
                throw new PersistSaveChecks.Blocked("runner-created task-local GUI readiness trigger",
                    wait.diagnostic());
            }
            waitingStage.set("trigger-received");
            if (activeDiagnostics != null) activeDiagnostics.markTriggerReceived();
            result.setProperty("gui.readinessTrigger", "received");
            result.setProperty("gui.readinessTriggerPath", wait.path().toString());
            context.logger().info("EXTERNAL_PSD_EDIT_GUI_READY_TRIGGER_RECEIVED path="
                + wait.path());
        } finally {
            if (activeDiagnostics != null) {
                activeDiagnostics.close("wait-ended");
                if (guiWaitDiagnostics == activeDiagnostics) guiWaitDiagnostics = null;
                result.setProperty("gui.waitDiagnostics.samples",
                    Integer.toString(activeDiagnostics.writtenSamples()));
                result.setProperty("gui.waitDiagnostics.unavailable",
                    Integer.toString(activeDiagnostics.unavailableSamples()));
            }
        }
    }

    private void awaitReady() throws Exception {
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(180).toNanos();
        boolean ready = false;
        while (!stopped && System.nanoTime() < deadline) {
            final AtomicReference<Boolean> active = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> {
                if (stopped) return;
                try {
                    active.set(context.cubism().isHostPresent()
                        && context.cubism().activeDocument().isPresent()
                        && context.cubism().activeModel().isPresent());
                } catch (IllegalStateException unavailable) {
                    // Startup is bounded; projection errors after readiness are not retried.
                }
            });
            if (active.get()) { ready = true; break; }
            Thread.sleep(1000);
        }
        if (stopped) throw new IllegalStateException("Probe stopped before readiness");
        if (!ready) throw new IllegalStateException("Active fixture/model readiness timed out");
    }

    private TargetResolution.Target resolveTarget(final Properties result) throws Exception {
        final AtomicReference<TargetResolution.Target> found = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            if (stopped) return;
            try {
                final var document = context.cubism().activeDocument().orElseThrow();
                final String expectedFixture = System.getProperty("turboism.validation.fixtureName", "");
                final String relativePath = document.relativePath();
                final String actualFixture = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                result.setProperty("fixture.expected", expectedFixture);
                result.setProperty("fixture.actual", actualFixture);
                if (expectedFixture.isBlank() || !expectedFixture.equals(actualFixture)) {
                    throw new IllegalStateException("Active document is not the task fixture copy");
                }
                final var model = context.cubism().model().active();
                result.setProperty("documentId", document.documentId());
                result.setProperty("modelId", model.id().value());
                final TextureRelationsSnapshot relations = model.textures().relations();
                if (!relations.isAvailable()) throw new IllegalStateException("Relations unavailable");
                result.setProperty("binding", relations.binding());
                result.setProperty("generation", Long.toString(relations.generation()));
                result.setProperty("rawCount", Integer.toString(relations.rawImages().size()));
                result.setProperty("modelImageCount", Integer.toString(relations.modelImages().size()));
                result.setProperty("artMeshCount", Integer.toString(relations.artMeshInputs().size()));
                final TargetResolution.Target picked = pickTarget(relations).orElseThrow(() ->
                    new IllegalStateException("No ArtMesh resolves to a current raw image"));
                final TargetResolution.Target resolved = new TargetResolution.Target(
                    picked.artMesh(),
                    picked.modelImage(),
                    picked.raw(),
                    picked.rawReplaced(),
                    new TargetResolution.TargetIdentity(
                        document.documentId(),
                        model.id().value(),
                        relations.binding(),
                        relations.generation(),
                        picked.modelImage().value(),
                        picked.artMesh().id().value(),
                        picked.raw().value())
                );
                TargetResolution.requireSharedModelImageRelation(
                    result, "relation.modelImage", resolved.identity(), relations, contentProfile);
                stableSharedModelImageUsers = TargetResolution.sharedModelImageUserIds(
                    resolved.identity(), relations);
                result.setProperty("relation.modelImage.stableUsingArtMeshIds",
                    stableSharedModelImageUsers.toString());
                found.set(resolved);
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException("Target resolution failed", failure.get());
        final TargetResolution.Target target = found.get();
        result.setProperty("relation.artMesh", target.artMesh().id().value());
        result.setProperty("relation.modelImage", target.modelImage().value());
        result.setProperty("relation.raw", target.raw().value());
        result.setProperty("relation.rawReplaced",
            Boolean.toString(TargetResolution.rawReplaced(target)));
        final long shared = relationsOf(target).map(relations -> relations.artMeshInputs().stream()
            .filter(mesh -> mesh.currentInputIndex().isPresent())
            .map(mesh -> mesh.inputs().get(mesh.currentInputIndex().getAsInt()))
            .filter(input -> input.kind() == TextureInputBinding.Kind.MODEL_IMAGE
                && input.isResolved() && input.modelImageId().isPresent())
            .flatMap(input -> relations.modelImage(input.modelImageId().orElseThrow()).stream())
            .flatMap(image -> image.currentRawImageId().stream())
            .filter(raw -> raw.value().equals(target.raw().value()))
            .count()).orElse(0L);
        result.setProperty("relation.sharedRawArtMeshes", Long.toString(shared));
        bindExactTaskWindow(result, target);
        return target;
    }

    /**
     * Binds shutdown to the task document's exact host window for every phase. The SDK document
     * and fixture checks above establish which document is active; this additional reviewed-table
     * check proves that the same active window contains the exact ArtMesh domain ID. The Window
     * object is retained for the whole run, including SAVE_AS, so teardown never re-resolves by a
     * stale filename, title, or arbitrary visible-window fallback.
     */
    private void bindExactTaskWindow(final Properties result, final TargetResolution.Target target)
        throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("task window binding must run off the EDT");
        }
        if (target == null || target.artMesh() == null || target.artMesh().id() == null) {
            throw new PersistSaveChecks.Blocked("the task fixture ArtMesh can be bound to one host window",
                "task target identity is unavailable for window binding");
        }
        final String expectedDomainId = target.artMesh().id().value();
        result.setProperty("exit.targetWindow.bindingStatus", "WAITING");
        result.setProperty("exit.targetWindow.documentId", result.getProperty("documentId", ""));
        result.setProperty("exit.targetWindow.modelId", result.getProperty("modelId", ""));
        result.setProperty("exit.targetWindow.artMesh", expectedDomainId);
        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(TASK_WINDOW_BIND_TIMEOUT_MILLIS);
        int attempts = 0;
        int revealedRows = 0;
        String lastDiagnostic = "no active reviewed host window has been observed";
        while (!stopped && System.nanoTime() < deadline) {
            attempts++;
            final ExactHostRowDispatch.ReviewedTables reviewed = PersistSaveChecks.visibleReviewedTables();
            result.setProperty("exit.targetWindow.bindingAttempt", Integer.toString(attempts));
            result.setProperty("exit.targetWindow.candidate", reviewed.windowIdentity());
            result.setProperty("exit.targetWindow.candidateDiagnostic", reviewed.diagnostic());
            if (!reviewed.proven() || reviewed.window() == null) {
                lastDiagnostic = reviewed.diagnostic();
            } else if (reviewed.tables().isEmpty()) {
                lastDiagnostic = "active window has no reviewed tree-table yet: "
                    + reviewed.windowIdentity();
            } else {
                final ExactHostRowDispatch.ExactCapture capture = captureExactRows(reviewed.tables(), target,
                    reviewed.windowIdentity());
                result.setProperty("exit.targetWindow.targetStatus",
                    capture.targetStatus().name());
                result.setProperty("exit.targetWindow.tableDiagnostics",
                    String.join("\n---\n", capture.tableDiagnostics()));
                if (!capture.hostAvailable()) {
                    result.setProperty("exit.targetWindow.bindingStatus", "BLOCKED");
                    throw new PersistSaveChecks.Blocked("the task fixture ArtMesh can be bound to one host window",
                        "exact host access unavailable for task window binding: "
                            + capture.diagnostic());
                }
                if (capture.targetStatus() == ExactHostRowDispatch.ExactTargetSelectionStatus.AMBIGUOUS
                    || capture.targetStatus() == ExactHostRowDispatch.ExactTargetSelectionStatus.REJECTED) {
                    result.setProperty("exit.targetWindow.bindingStatus", "BLOCKED");
                    throw new PersistSaveChecks.Blocked("the task fixture ArtMesh can be bound to one host window",
                        "exact task window target rejected: " + capture.diagnostic());
                }
                if (!capture.rows().isEmpty()) {
                    final TargetResolution.TaskWindowBindingDecision binding = TargetResolution.validateTaskWindowBinding(
                        result.getProperty("fixture.expected", ""),
                        result.getProperty("fixture.actual", ""),
                        result.getProperty("documentId", ""), result.getProperty("modelId", ""),
                        reviewed.windowIdentity(), expectedDomainId,
                        capture.rows().stream().map(ExactHostRowDispatch.ExactDispatchCapture::captured).toList());
                    if (!binding.bound()) {
                        result.setProperty("exit.targetWindow.bindingStatus", "BLOCKED");
                        throw new PersistSaveChecks.Blocked(
                            "the task fixture ArtMesh can be bound to one host window",
                            binding.diagnostic());
                    }
                    guiBoundWindow = reviewed.window();
                    result.setProperty("exit.targetWindow.bindingStatus", "BOUND");
                    result.setProperty("exit.targetWindow.window",
                        reviewed.windowIdentity());
                    result.setProperty("exit.targetWindow.windowObject",
                        ExactHostRowDispatch.objectIdentity(guiBoundWindow));
                    result.setProperty("exit.targetWindow.entrances",
                        Integer.toString(capture.rows().size()));
                    result.setProperty("exit.targetWindow.bindingDiagnostic",
                        "active task fixture window contains exact ArtMesh entrances: "
                            + capture.diagnostic());
                    return;
                }
                if (capture.hostAvailable() && capture.rows().isEmpty()
                    && capture.targetStatus() == ExactHostRowDispatch.ExactTargetSelectionStatus.NOT_FOUND
                    && revealTaskWindowRow(reviewed.tables(), expectedDomainId)) {
                    revealedRows++;
                    result.setProperty("exit.targetWindow.revealedRows",
                        Integer.toString(revealedRows));
                }
                lastDiagnostic = capture.diagnostic();
            }
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            Thread.sleep(Math.min(TASK_WINDOW_BIND_POLL_MILLIS,
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
        }
        result.setProperty("exit.targetWindow.bindingStatus", stopped ? "STOPPED" : "BLOCKED");
        final String terminal = stopped ? "task window binding stopped" :
            "task window binding timed out after " + TASK_WINDOW_BIND_TIMEOUT_MILLIS + "ms";
        throw new PersistSaveChecks.Blocked("the task fixture ArtMesh can be bound to one host window",
            terminal + "; attempts=" + attempts + " last=" + lastDiagnostic);
    }

    private Optional<TextureRelationsSnapshot> relationsOf(final TargetResolution.Target target) {
        final AtomicReference<TextureRelationsSnapshot> snapshot = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> snapshot.set(
                context.cubism().model().active().textures().relations()));
        } catch (Exception unavailable) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get());
    }

    /** Reads the public relation projection on the EDT; no host model is read off-thread. */
    private TextureRelationsSnapshot relationsSnapshotOnEdt() throws Exception {
        final AtomicReference<TextureRelationsSnapshot> snapshot = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable read = () -> {
            try {
                snapshot.set(context.cubism().model().active().textures().relations());
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) read.run();
            else SwingUtilities.invokeAndWait(read);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (InvocationTargetException invocationFailure) {
            final Throwable cause = invocationFailure.getCause();
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("raw relation observation dispatch failed", cause);
        }
        if (failure.get() != null) {
            final Throwable error = failure.get();
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("raw relation observation failed", error);
        }
        return snapshot.get();
    }

    private TextureRelationsSnapshot captureCycleRelations(final Properties result,
        final String prefix) throws Exception {
        try {
            final TextureRelationsSnapshot relations = relationsSnapshotOnEdt();
            RawExportCoordination.recordRawImageRelationSnapshot(result, prefix, relations, "");
            return relations;
        } catch (Exception failure) {
            RawExportCoordination.recordRawImageRelationSnapshot(result, prefix, null, failure.toString());
            throw failure;
        } catch (Error failure) {
            RawExportCoordination.recordRawImageRelationSnapshot(result, prefix, null, failure.toString());
            throw failure;
        }
    }

    private Optional<TargetResolution.Target> pickTarget(final TextureRelationsSnapshot relations) {
        return pickTarget(relations, contentProfile);
    }

    private static Optional<TargetResolution.Target> pickTarget(final TextureRelationsSnapshot relations,
        final PsdValidationContent.Profile profile) {
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(profile, "profile");
        final List<TargetResolution.Target> candidates = new ArrayList<>();
        for (final ArtMeshTextureInputs mesh : relations.artMeshInputs()) {
            if (mesh.currentInputIndex().isEmpty()) continue;
            final TextureInputBinding input = mesh.inputs().get(mesh.currentInputIndex().getAsInt());
            if (input.kind() != TextureInputBinding.Kind.MODEL_IMAGE
                || !input.isResolved() || input.modelImageId().isEmpty()) continue;
            final var relation = profile == PsdValidationContent.Profile.F1_2048_20
                ? uniqueModelImageRelation(relations, input.modelImageId().orElseThrow())
                : relations.modelImage(input.modelImageId().orElseThrow());
            if (relation.isEmpty() || relation.orElseThrow().currentRawImageId().isEmpty()) continue;
            final ModelImageRelation modelImage = relation.orElseThrow();
            if (profile == PsdValidationContent.Profile.F1_2048_20) {
                final List<String> users = modelImage.usingArtMeshIds().stream()
                    .map(ArtMeshId::value).distinct().toList();
                if (users.size() < 2 || !users.contains(mesh.id().value())) continue;
            }
            final RawImageId raw = modelImage.currentRawImageId().orElseThrow();
            final boolean replaced = relations.rawImage(raw)
                .map(dev.turboism.sdk.cubism.model.RawImageDetails::isReplaced).orElse(false);
            candidates.add(new TargetResolution.Target(mesh, input.modelImageId().orElseThrow(), raw, replaced,
                TargetResolution.TargetIdentity.unavailable()));
            if (profile != PsdValidationContent.Profile.F1_2048_20) {
                return Optional.of(candidates.get(0));
            }
        }
        return candidates.stream()
            .sorted(Comparator
                .comparing((TargetResolution.Target target) -> target.modelImage().value())
                .thenComparing(target -> target.artMesh().id().value())
                .thenComparing(target -> target.raw().value()))
            .findFirst();
    }

    static Optional<TargetResolution.TargetSelection> selectTargetForTest(
        final TextureRelationsSnapshot relations, final PsdValidationContent.Profile profile) {
        return pickTarget(relations, profile).map(target -> new TargetResolution.TargetSelection(
            target.modelImage().value(), target.artMesh().id().value(), target.raw().value()));
    }

    /** Captures the direct, task-scoped runtime allocation set before an export. */
    private SessionFileReadiness.TempCandidateSnapshot snapshotTempCandidates() throws Exception {
        return SessionFileReadiness.snapshotTempCandidates(SessionFileReadiness.tempRoot());
    }

    private PsdExportResult export(final Properties result, final RawImageId raw) throws Exception {
        return export(result, raw, 0L);
    }

    private PsdExportResult export(final Properties result, final RawImageId raw,
        final long budgetMillis) throws Exception {
        final long started = System.nanoTime();
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "native export invocation");
        final AtomicReference<CompletionStage<PsdExportResult>> stage = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (stopped) throw new IllegalStateException("Probe stopped");
                stage.set(context.cubism().model().active().textures().exportRawImagePsd(raw));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException("Export invocation failed", failure.get());
        return awaitExportResult(result, raw, stage.get());
    }

    private TempExportTracker.TrackedExport exportTracked(final Properties result, final RawImageId raw,
        final TempExportTracker.TempTracker tracker, final String label) throws Exception {
        return exportTracked(result, raw, tracker, label, 0L);
    }

    private TempExportTracker.TrackedExport exportTracked(final Properties result, final RawImageId raw,
        final TempExportTracker.TempTracker tracker, final String label, final long budgetMillis) throws Exception {
        final SessionFileReadiness.TempCandidateSnapshot before = snapshotTempCandidates();
        final Path marker = SessionFileReadiness.tempMarker();
        final long exportStartedAtEpochMillis = System.currentTimeMillis();
        final PsdExportResult exported = export(result, raw, budgetMillis);
        final TempExportTracker.TrackedExport tracked = tracker.register(
            label, exported.file().orElseThrow(), before.root());
        tracked.setExportTimes(exportStartedAtEpochMillis, System.currentTimeMillis());
        final Path path = SessionFileReadiness.locateNewTempFile(before, marker, result, label);
        tracked.setPath(path);
        result.setProperty("export." + label + ".path", path.toString());
        return tracked;
    }

    private PsdValidationContent.Fingerprint exportTargetFingerprint(final Properties result,
        final TargetResolution.Target target, final TempExportTracker.TempTracker tracker, final String label) throws Exception {
        requireTargetBinding(result, target, "export." + label + ".targetBinding.before", 0L);
        final TempExportTracker.TrackedExport exported = exportTracked(result, target.raw(), tracker, label);
        Throwable primary = null;
        try {
            requireTargetBinding(result, target, "export." + label + ".targetBinding.after", 0L);
            return targetFingerprint(Files.readAllBytes(exported.path()), label, contentProfile);
        } catch (Exception failure) {
            primary = failure;
            throw failure;
        } catch (Error failure) {
            primary = failure;
            throw failure;
        } finally {
            TempExportTracker.stopPreservingPrimary(tracker, exported, result, primary);
        }
    }

    /**
     * Captures public relation and PSD-document projections around a fresh native export. This is
     * deliberately diagnostic-only: the supplied fingerprint is the only content evidence and is
     * produced by re-exporting the raw image through the public SDK. The target identity is checked
     * on the same EDT observation; an active-document/model switch is never combined with the
     * fingerprint as an AVAILABLE observation.
     */
    private DiagnosticObservationRecorder.DiagnosticObservation captureDiagnosticObservation(final Properties result,
        final String prefix, final TargetResolution.Target target,
        final PsdValidationContent.Fingerprint fingerprint, final String boundary,
        final String diagnostic, final long freshExportStartedAtEpochMillis,
        final long freshExportCompletedAtEpochMillis, final long budgetMillis,
        final boolean record) throws DiagnosticObservationRecorder.ObservationBudgetExceededException {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        final long metadataStartedAtEpochMillis = System.currentTimeMillis();
        final DiagnosticObservationRecorder.DiagnosticObservation observation = observeDiagnosticObservation(
            target,
            fingerprint,
            freshExportStartedAtEpochMillis,
            freshExportCompletedAtEpochMillis,
            metadataStartedAtEpochMillis,
            budgetMillis);
        if (record) {
            DiagnosticObservationRecorder.recordDiagnosticCapture(
                result,
                normalizedPrefix,
                target,
                observation,
                boundary,
                diagnostic
            );
        }
        return observation;
    }

    private DiagnosticObservationRecorder.DiagnosticObservation captureFreshDiagnosticObservation(final Properties result,
        final String prefix, final TargetResolution.Target target, final TempExportTracker.TempTracker tracker,
        final String boundary, final long budgetMillis, final boolean record) throws Exception {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        final long started = System.nanoTime();
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "fresh diagnostic observation");
        requireTargetBinding(result, target,
            normalizedPrefix + ".targetBinding.beforeExport",
            DiagnosticObservationRecorder.remainingDiagnosticBudgetMillis(started, budgetMillis));
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "fresh diagnostic export");
        final TempExportTracker.TrackedExport exported = exportTracked(
            result, target.raw(), tracker, diagnosticExportLabel(normalizedPrefix),
            DiagnosticObservationRecorder.remainingDiagnosticBudgetMillis(started, budgetMillis));
        Throwable primary = null;
        try {
            DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "fresh diagnostic export");
            final PsdValidationContent.Fingerprint fingerprint = DiagnosticObservationRecorder.targetFingerprint(
                Files.readAllBytes(exported.path()), normalizedPrefix, contentProfile);
            DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "fresh diagnostic metadata");
            final DiagnosticObservationRecorder.DiagnosticObservation observation = captureDiagnosticObservation(
                result,
                normalizedPrefix,
                target,
                fingerprint,
                boundary,
                "",
                exported.exportStartedAtEpochMillis(),
                exported.exportCompletedAtEpochMillis(),
                DiagnosticObservationRecorder.remainingDiagnosticBudgetMillis(started, budgetMillis),
                false
            );
            DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "fresh diagnostic metadata");
            if (record) {
                DiagnosticObservationRecorder.recordDiagnosticCapture(result, normalizedPrefix, target, observation, boundary, "");
            }
            return observation;
        } catch (Exception failure) {
            primary = failure;
            throw failure;
        } catch (Error failure) {
            primary = failure;
            throw failure;
        } finally {
            TempExportTracker.stopPreservingPrimary(tracker, exported, result, primary);
        }
    }

    /** Performs the public read projections on the EDT and returns explicit unavailable fields. */
    private DiagnosticObservationRecorder.DiagnosticObservation observeDiagnosticObservation(final TargetResolution.Target target,
        final PsdValidationContent.Fingerprint fingerprint,
        final long freshExportStartedAtEpochMillis,
        final long freshExportCompletedAtEpochMillis,
        final long metadataStartedAtEpochMillis, final long budgetMillis)
        throws DiagnosticObservationRecorder.ObservationBudgetExceededException {
        final AtomicReference<DiagnosticObservationRecorder.DiagnosticObservation> captured = new AtomicReference<>();
        final Runnable observe = () -> captured.set(observeDiagnosticObservationOnEdt(
            target,
            fingerprint,
            freshExportStartedAtEpochMillis,
            freshExportCompletedAtEpochMillis,
            metadataStartedAtEpochMillis
        ));
        final long started = System.nanoTime();
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "public diagnostic observation dispatch");
        try {
            if (SwingUtilities.isEventDispatchThread()) observe.run();
            else SwingUtilities.invokeAndWait(observe);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return diagnosticUnavailable(
                "public observation dispatch interrupted",
                fingerprint,
                TargetResolution.TargetIdentity.unavailable(),
                freshExportStartedAtEpochMillis,
                freshExportCompletedAtEpochMillis,
                metadataStartedAtEpochMillis,
                System.currentTimeMillis());
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            return diagnosticUnavailable(
                "public observation dispatch failed: " + cause,
                fingerprint,
                TargetResolution.TargetIdentity.unavailable(),
                freshExportStartedAtEpochMillis,
                freshExportCompletedAtEpochMillis,
                metadataStartedAtEpochMillis,
                System.currentTimeMillis());
        }
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "public diagnostic observation");
        final DiagnosticObservationRecorder.DiagnosticObservation observation = captured.get();
        return observation == null
            ? diagnosticUnavailable(
                "public observation returned no value",
                fingerprint,
                TargetResolution.TargetIdentity.unavailable(),
                freshExportStartedAtEpochMillis,
                freshExportCompletedAtEpochMillis,
                metadataStartedAtEpochMillis,
                System.currentTimeMillis())
            : observation;
    }

    private DiagnosticObservationRecorder.DiagnosticObservation observeDiagnosticObservationOnEdt(final TargetResolution.Target target,
        final PsdValidationContent.Fingerprint fingerprint,
        final long freshExportStartedAtEpochMillis,
        final long freshExportCompletedAtEpochMillis,
        final long metadataStartedAtEpochMillis) {
        String rawIds = RawExportCoordination.UNAVAILABLE_VALUE;
        String modelImageIds = RawExportCoordination.UNAVAILABLE_VALUE;
        String selectorProjection = RawExportCoordination.UNAVAILABLE_VALUE;
        String livePsd = RawExportCoordination.UNAVAILABLE_VALUE;
        boolean relationsAvailable = false;
        boolean livePsdAvailable = false;
        boolean targetRawSnapshotAvailable = false;
        final List<String> diagnostics = new ArrayList<>();
        final TargetResolution.TargetIdentity expected = target == null
            ? TargetResolution.TargetIdentity.unavailable() : target.identity();
        final CubismModel model;
        String documentId = RawExportCoordination.UNAVAILABLE_VALUE;
        try {
            final var document = context.cubism().activeDocument().orElse(null);
            if (document == null) diagnostics.add("active document unavailable");
            else documentId = RawExportCoordination.valueOrUnavailable(document.documentId());
        } catch (RuntimeException failure) {
            diagnostics.add("active document unavailable: " + failure);
        }
        try {
            model = context.cubism().model().active();
        } catch (RuntimeException failure) {
            return diagnosticUnavailable(
                "active model unavailable: " + failure,
                fingerprint,
                TargetResolution.TargetIdentity.unavailable(),
                freshExportStartedAtEpochMillis,
                freshExportCompletedAtEpochMillis,
                metadataStartedAtEpochMillis,
                System.currentTimeMillis());
        }

        String modelId = RawExportCoordination.valueOrUnavailable(model.id().value());
        String binding = RawExportCoordination.UNAVAILABLE_VALUE;
        String targetModelImageId = RawExportCoordination.UNAVAILABLE_VALUE;
        String targetRawId = RawExportCoordination.UNAVAILABLE_VALUE;
        String targetArtMeshId = RawExportCoordination.UNAVAILABLE_VALUE;
        long generation = -1L;
        TextureRelationsSnapshot relations = null;
        try {
            relations = model.textures().relations();
            if (relations == null || !relations.isAvailable()) {
                diagnostics.add("relations unavailable");
            } else {
                relationsAvailable = true;
                binding = RawExportCoordination.valueOrUnavailable(relations.binding());
                generation = relations.generation();
                rawIds = RawExportCoordination.rawImageIds(relations);
                modelImageIds = modelImageIds(relations);
                selectorProjection = selectorProjection(relations);
                final Optional<ModelImageRelation> targetRelation = relations.modelImages().stream()
                    .filter(relation -> relation.id().value().equals(expected.modelImageId()))
                    .findFirst();
                if (targetRelation.isPresent()) {
                    targetModelImageId = targetRelation.orElseThrow().id().value();
                    targetRawId = targetRelation.orElseThrow().currentRawImageId()
                        .map(RawImageId::value).orElse(RawExportCoordination.UNAVAILABLE_VALUE);
                    try {
                        targetArtMeshId = PersistSaveChecks.matchingArtMeshIdOnEdt(expected, relations);
                    } catch (RuntimeException unavailable) {
                        diagnostics.add("target ArtMesh identity unavailable: " + unavailable);
                    }
                } else {
                    diagnostics.add("target model-image relation unavailable");
                }
            }
        } catch (RuntimeException failure) {
            diagnostics.add("relations unavailable: " + failure);
        }

        try {
            final List<PsdClipMaskDocumentSnapshot> documents = model.psdDocuments();
            if (documents == null) {
                diagnostics.add("live PSD documents unavailable: null result");
            } else {
                livePsdAvailable = true;
                livePsd = RawExportCoordination.livePsdDocuments(documents);
                targetRawSnapshotAvailable = PersistSaveChecks.hasUniquePsdSnapshotForRawImage(
                    documents, targetRawId);
                if (!targetRawSnapshotAvailable) {
                    diagnostics.add("target raw image snapshot is absent or ambiguous for rawId="
                        + targetRawId);
                }
            }
        } catch (RuntimeException failure) {
            diagnostics.add("live PSD documents unavailable: " + failure);
        }

        final TargetResolution.TargetIdentity observed = new TargetResolution.TargetIdentity(
            documentId, modelId, binding, generation, targetModelImageId, targetArtMeshId,
            targetRawId);
        final boolean identityVerified = targetRawSnapshotAvailable
            && TargetResolution.targetIdentityMatches(expected, observed);
        if (!identityVerified) {
            diagnostics.add("target identity mismatch expected=" + expected
                + " actual=" + observed);
        }
        final DiagnosticObservationRecorder.DiagnosticStatus status = PersistSaveChecks.diagnosticStatusForTarget(
            expected, observed, relationsAvailable, livePsdAvailable, fingerprint,
            targetRawSnapshotAvailable);
        return new DiagnosticObservationRecorder.DiagnosticObservation(
            status,
            diagnostics.isEmpty() ? "" : String.join("; ", diagnostics),
            rawIds,
            modelImageIds,
            selectorProjection,
            livePsd,
            relationsAvailable,
            livePsdAvailable,
            fingerprint,
            observed,
            identityVerified,
            freshExportStartedAtEpochMillis,
            freshExportCompletedAtEpochMillis,
            metadataStartedAtEpochMillis,
            System.currentTimeMillis()
        );
    }

    private TargetResolution.TargetIdentity currentTargetIdentityOnEdt(final TargetResolution.TargetIdentity expected) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("target identity observation must run on EDT");
        }
        final var document = context.cubism().activeDocument().orElseThrow(
            () -> new IllegalStateException("active document unavailable"));
        final CubismModel model = context.cubism().model().active();
        final TextureRelationsSnapshot relations = model.textures().relations();
        return targetIdentityFromRelationsOnEdt(
            expected, document.documentId(), model, relations);
    }

    private void requireTargetBinding(final Properties result, final TargetResolution.Target target,
        final String prefix, final long budgetMillis) throws Exception {
        Objects.requireNonNull(target, "target");
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        final long started = System.nanoTime();
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "target binding observation");
        final AtomicReference<TargetResolution.TargetIdentity> actual = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable observe = () -> {
            try {
                actual.set(currentTargetIdentityOnEdt(target.identity()));
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) observe.run();
            else SwingUtilities.invokeAndWait(observe);
        } catch (InterruptedException interrupted) {
            PersistSaveChecks.recordTargetBindingCheck(
                result, normalizedPrefix, target.identity(), TargetResolution.TargetIdentity.unavailable());
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (InvocationTargetException invocationFailure) {
            final Throwable cause = invocationFailure.getCause();
            if (cause instanceof Error error) throw error;
            PersistSaveChecks.recordTargetBindingCheck(
                result, normalizedPrefix, target.identity(), TargetResolution.TargetIdentity.unavailable());
            throw new IllegalStateException("target binding observation dispatch failed", cause);
        }
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "target binding observation");
        if (failure.get() != null) {
            final Throwable error = failure.get();
            final TargetResolution.TargetIdentity observed = actual.get() == null
                ? TargetResolution.TargetIdentity.unavailable() : actual.get();
            PersistSaveChecks.recordTargetBindingCheck(result, normalizedPrefix, target.identity(), observed);
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("target binding observation failed", error);
        }
        final TargetResolution.TargetIdentity observed = actual.get();
        PersistSaveChecks.recordTargetBindingCheck(result, normalizedPrefix, target.identity(), observed);
        if (!TargetResolution.targetIdentityMatches(target.identity(), observed)) {
            throw new IllegalStateException("target binding changed expected=" + target.identity()
                + " actual=" + observed);
        }
    }

    /** Requires the stable target anchor while allowing its raw lineage to have moved. */
    private void requireStableTargetBinding(final Properties result, final TargetResolution.Target target,
        final String prefix, final long budgetMillis) throws Exception {
        Objects.requireNonNull(target, "target");
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        final long started = System.nanoTime();
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "stable target observation");
        final AtomicReference<TargetResolution.TargetIdentity> actual = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable observe = () -> {
            try {
                actual.set(currentTargetIdentityOnEdt(target.identity()));
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) observe.run();
            else SwingUtilities.invokeAndWait(observe);
        } catch (InterruptedException interrupted) {
            PersistSaveChecks.recordStableTargetBindingCheck(result, normalizedPrefix, target.identity(),
                TargetResolution.TargetIdentity.unavailable());
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (InvocationTargetException invocationFailure) {
            final Throwable cause = invocationFailure.getCause();
            if (cause instanceof Error error) throw error;
            PersistSaveChecks.recordStableTargetBindingCheck(result, normalizedPrefix, target.identity(),
                TargetResolution.TargetIdentity.unavailable());
            throw new IllegalStateException("stable target observation dispatch failed", cause);
        }
        DiagnosticObservationRecorder.requireBudgetAvailable(started, budgetMillis, "stable target observation");
        if (failure.get() != null) {
            final Throwable error = failure.get();
            final TargetResolution.TargetIdentity observed = actual.get() == null
                ? TargetResolution.TargetIdentity.unavailable() : actual.get();
            PersistSaveChecks.recordStableTargetBindingCheck(result, normalizedPrefix, target.identity(), observed);
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("stable target observation failed", error);
        }
        final TargetResolution.TargetIdentity observed = actual.get();
        PersistSaveChecks.recordStableTargetBindingCheck(result, normalizedPrefix, target.identity(), observed);
        if (!TargetResolution.stableTargetIdentityMatches(target.identity(), observed)) {
            throw new IllegalStateException("stable target changed expected=" + target.identity()
                + " actual=" + observed);
        }
    }

    /**
     * Exports the pre-replacement raw as a separate diagnostic.  It is deliberately not produced
     * from the current-target export: after native matching succeeds the current incoming raw
     * must never be labelled as {@code raw.old}.
     */
    private void recordCycleOldRawFingerprint(final Properties result, final String prefix,
        final TargetResolution.Target oldTarget, final TempExportTracker.TempTracker tracker) throws Exception {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix) + ".raw.old";
        final RawImageId oldRaw = oldTarget.raw();
        result.setProperty(normalizedPrefix + ".id", oldRaw.value());
        result.setProperty(normalizedPrefix + ".rgb.observation",
            "independent fresh native export of the pre-replacement raw; diagnostic only");
        result.setProperty(normalizedPrefix + ".diagnosticOnly", "true");
        TempExportTracker.TrackedExport exported = null;
        Throwable primary = null;
        boolean contentOnlyFailure = false;
        try {
            requireStableTargetBinding(result, oldTarget,
                normalizedPrefix + ".targetBinding.before", 0L);
            final RawExportCoordination.RawExportObservation before = observeRawExportOnEdt(oldTarget, oldRaw);
            if (!before.relationsAvailable() || before.candidateCount() != 1) {
                throw new IllegalStateException("old raw is not uniquely present before export: "
                    + before);
            }
            exported = exportTracked(result, oldRaw, tracker, normalizedPrefix + ".export");
            requireStableTargetBinding(result, oldTarget,
                normalizedPrefix + ".targetBinding.after", 0L);
            final RawExportCoordination.RawExportObservation after = observeRawExportOnEdt(oldTarget, oldRaw);
            if (!after.relationsAvailable() || after.candidateCount() != 1) {
                throw new IllegalStateException("old raw disappeared or became ambiguous after export: "
                    + after);
            }
            try {
                final PsdValidationContent.Fingerprint fingerprint = DiagnosticObservationRecorder.targetFingerprint(
                    Files.readAllBytes(exported.path()), normalizedPrefix + " old raw",
                    contentProfile);
                result.setProperty(normalizedPrefix + ".rgb.status", "AVAILABLE");
                result.setProperty(normalizedPrefix + ".rgb.source",
                    "fresh native export of the old raw resource");
                DiagnosticObservationRecorder.recordTargetFingerprint(result, normalizedPrefix + ".rgb", fingerprint);
            } catch (Exception contentFailure) {
                // Missing/malformed old RGB is diagnostic-only; the identity and export-handle
                // gates above remain phase-fatal.
                primary = contentFailure;
                contentOnlyFailure = true;
                result.setProperty(normalizedPrefix + ".rgb.status", "UNAVAILABLE");
                result.setProperty(normalizedPrefix + ".rgb.diagnostic",
                    contentFailure.toString());
            }
        } catch (Exception failure) {
            primary = failure;
            result.setProperty(normalizedPrefix + ".rgb.status", "UNAVAILABLE");
            result.setProperty(normalizedPrefix + ".rgb.diagnostic", failure.toString());
        } catch (Error failure) {
            primary = failure;
            result.setProperty(normalizedPrefix + ".rgb.status", "UNAVAILABLE");
            result.setProperty(normalizedPrefix + ".rgb.diagnostic", failure.toString());
        } finally {
            if (exported != null) {
                try {
                    tracker.stop(exported, result);
                } catch (Throwable cleanup) {
                    result.setProperty(normalizedPrefix + ".stopStatus", "FAILED");
                    result.setProperty(normalizedPrefix + ".stopDiagnostic", cleanup.toString());
                    if (primary != null) TempExportTracker.addSuppressed(primary, cleanup);
                    else primary = cleanup;
                    contentOnlyFailure = false;
                }
            }
        }
        if (primary != null && !contentOnlyFailure) TempExportTracker.rethrowCleanup(primary);
    }

    /** Captures the candidate and target relation on the EDT; currentRaw is diagnostic only. */
    private RawExportCoordination.RawExportObservation rawExportObservationOnEdt(final TargetResolution.Target target,
        final RawImageId candidate) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("raw export relation observation must run on EDT");
        }
        try {
            final var document = context.cubism().activeDocument().orElse(null);
            if (document == null) {
                return RawExportCoordination.RawExportObservation.unavailable(candidate,
                    "active document unavailable");
            }
            final CubismModel model = context.cubism().model().active();
            final TextureRelationsSnapshot relations = model.textures().relations();
            if (relations == null || !relations.isAvailable()) {
                return RawExportCoordination.RawExportObservation.unavailable(candidate, "relations unavailable");
            }
            final String expectedModelImageId = target.identity().modelImageId();
            final int modelImageCount = (int) relations.modelImages().stream()
                .filter(relation -> relation != null
                    && expectedModelImageId.equals(relation.id().value()))
                .count();
            String currentRawId = RawExportCoordination.UNAVAILABLE_VALUE;
            if (modelImageCount == 1) {
                currentRawId = relations.modelImages().stream()
                    .filter(relation -> relation != null
                        && expectedModelImageId.equals(relation.id().value()))
                    .findFirst()
                    .flatMap(ModelImageRelation::currentRawImageId)
                    .map(RawImageId::value)
                    .orElse(RawExportCoordination.UNAVAILABLE_VALUE);
            }
            final String artMeshId = PersistSaveChecks.matchingArtMeshIdOnEdt(target.identity(), relations);
            int candidateCount = 0;
            for (final var raw : relations.rawImages()) {
                if (raw == null || raw.id() == null) {
                    return RawExportCoordination.RawExportObservation.unavailable(candidate,
                        "raw relation contains an unavailable entry");
                }
                if (candidate.equals(raw.id())) candidateCount++;
            }
            return new RawExportCoordination.RawExportObservation(
                document.documentId(),
                model.id().value(),
                relations.binding(),
                expectedModelImageId,
                artMeshId,
                candidate.value(),
                currentRawId,
                relations.generation(),
                modelImageCount,
                candidateCount,
                true,
                ""
            );
        } catch (RuntimeException failure) {
            return RawExportCoordination.RawExportObservation.unavailable(candidate,
                "raw export relation observation failed: " + failure);
        }
    }

    private RawExportCoordination.RawExportObservation observeRawExportOnEdt(final TargetResolution.Target target,
        final RawImageId candidate) throws Exception {
        final AtomicReference<RawExportCoordination.RawExportObservation> observed = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable read = () -> {
            try {
                observed.set(rawExportObservationOnEdt(target, candidate));
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) read.run();
            else SwingUtilities.invokeAndWait(read);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return RawExportCoordination.RawExportObservation.unavailable(candidate,
                "raw export postflight observation interrupted");
        } catch (InvocationTargetException invocationFailure) {
            final Throwable cause = invocationFailure.getCause();
            if (cause instanceof Error error) throw error;
            return RawExportCoordination.RawExportObservation.unavailable(candidate,
                "raw export postflight dispatch failed: " + cause);
        }
        if (failure.get() != null) {
            final Throwable error = failure.get();
            if (error instanceof Error fatal) throw fatal;
            return RawExportCoordination.RawExportObservation.unavailable(candidate,
                "raw export postflight observation failed: " + error);
        }
        return observed.get() == null
            ? RawExportCoordination.RawExportObservation.unavailable(candidate,
                "raw export postflight returned no observation")
            : observed.get();
    }

    /**
     * Runs the newly-added-raw export through the public SDK while keeping the host preflight and
     * invocation in one EDT task. A failed postflight stops the already-registered handle before
     * the caller can inspect bytes.
     */
    private RawExportCoordination.RawImageExportHandle trackedRawExportHandle(final Properties result,
        final TargetResolution.Target target, final TempExportTracker.TempTracker tracker, final String label,
        final RawImageId raw) throws Exception {
        final SessionFileReadiness.TempCandidateSnapshot before = snapshotTempCandidates();
        final Path marker = SessionFileReadiness.tempMarker();
        final long exportStartedAtEpochMillis = System.currentTimeMillis();
        final AtomicReference<TempExportTracker.TrackedExport> trackedReference = new AtomicReference<>();
        final RawExportCoordination.RawExportStarter starter = (candidate, preflight) -> {
            final AtomicReference<RawExportCoordination.RawExportObservation> beforeObservation = new AtomicReference<>();
            final AtomicReference<CompletionStage<PsdExportResult>> stage = new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Runnable invoke = () -> {
                try {
                    if (stopped) throw new IllegalStateException("Probe stopped");
                    final RawExportCoordination.RawExportObservation observation = rawExportObservationOnEdt(
                        target, candidate);
                    beforeObservation.set(observation);
                    preflight.verify(observation);
                    stage.set(context.cubism().model().active().textures()
                        .exportRawImagePsd(candidate));
                } catch (Throwable error) {
                    failure.set(error);
                }
            };
            try {
                if (SwingUtilities.isEventDispatchThread()) invoke.run();
                else SwingUtilities.invokeAndWait(invoke);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (InvocationTargetException invocationFailure) {
                final Throwable cause = invocationFailure.getCause();
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("raw export invocation dispatch failed", cause);
            }
            if (failure.get() != null) {
                final Throwable error = failure.get();
                if (error instanceof Error fatal) throw fatal;
                throw new IllegalStateException("raw export invocation failed", error);
            }
            final PsdExportResult exported = SessionFileReadiness.awaitExportResult(result, candidate, stage.get());
            final TempExportTracker.TrackedExport tracked = tracker.register(
                label, exported.file().orElseThrow(), before.root());
            tracked.setExportTimes(exportStartedAtEpochMillis, System.currentTimeMillis());
            trackedReference.set(tracked);
            final RawExportCoordination.RawImageExportHandle handle = new RawExportCoordination.RawImageExportHandle() {
                @Override public RawImageId rawId() { return candidate; }
                @Override public byte[] bytes() throws Exception {
                    return Files.readAllBytes(tracked.path());
                }
                @Override public PsdFileOperationResult stop() throws Exception {
                    return tracker.stop(tracked, result);
                }
            };
            return new RawExportCoordination.RawExportStarted(beforeObservation.get(), handle);
        };
        final RawExportCoordination.RawImageExportHandle handle = RawExportCoordination.coordinateNewRawExport(
            result,
            label,
            target.identity(),
            raw,
            starter,
            candidate -> observeRawExportOnEdt(target, candidate),
            candidateHandle -> tracker.stop(trackedReference.get(), result)
        );
        final TempExportTracker.TrackedExport tracked = trackedReference.get();
        try {
            if (tracked == null) throw new IllegalStateException(
                "raw export coordination returned no tracked export");
            final Path path = SessionFileReadiness.locateNewTempFile(before, marker, result, label);
            tracked.setPath(path);
            result.setProperty("export." + label + ".path", path.toString());
            return handle;
        } catch (Exception failure) {
            if (tracked != null) TempExportTracker.stopPreservingPrimary(tracker, tracked, result, failure);
            throw failure;
        } catch (Error failure) {
            if (tracked != null) TempExportTracker.stopPreservingPrimary(tracker, tracked, result, failure);
            throw failure;
        }
    }

    private SessionFileReadiness.SaveCyclesResult runSaveCycles(final Properties result, final PsdEditFile file,
        final TargetResolution.Target initialTarget, final Path tempFile, final Deque<SessionFileReadiness.RevisionEvent> revisions,
        final TempExportTracker.TempTracker tracker, final int cycles, final boolean validateTargetContent,
        final PerformanceEvidence.PerformanceCycleTimings performanceTimings)
        throws Exception {
        PsdStructureInspection.Mutation lastMutation = null;
        TargetResolution.Target currentTarget = initialTarget;
        TargetResolution.Target lastBeforeTarget = initialTarget;
        PersistSaveChecks.recordMemoryAttribution(result, "cycles.memoryBaseline.", false);
        for (int i = 1; i <= cycles; i++) {
            final String prefix = "cycle." + i + ".";
            PersistSaveChecks.recordMemoryAttribution(result, prefix + "memoryBefore.", false);
            final TargetResolution.Target beforeTarget = currentTarget;
            final byte[] current = Files.readAllBytes(tempFile);
            final SessionFileReadiness.CycleWritePlan plan = SessionFileReadiness.prepareSaveCycleBytes(
                current, i, cycles, validateTargetContent, contentProfile);
            final PsdStructureInspection.Mutation mutation = plan.mutation();
            final byte[] mutated = plan.firstWrite();
            result.setProperty(prefix + "lineage.beforeRaw", beforeTarget.raw().value());
            result.setProperty(prefix + "lineage.beforeGeneration",
                Long.toString(beforeTarget.identity().generation()));
            result.setProperty(prefix + "targetRgbMutation",
                plan.targetRgbMutated() ? "INVERTED_ONCE" : "UNCHANGED");
            if (validateTargetContent) {
                final byte[] finalWrite = i == 3
                    ? plan.overlapFinalWrite() : plan.firstWrite();
                TargetResolution.recordCycleWrittenPsd(result, prefix, finalWrite, contentProfile);
            }
            lastMutation = mutation;
            if (i == 1 && Boolean.getBoolean("turboism.validation.externalpsd.atomicFileControl")) {
                AtomicReplaceFileControl.observe(result, tempFile);
            }
            final long writeStart = System.nanoTime();
            if (i == 2) {
                final Path sibling = tempFile.resolveSibling("external-edit.psd.tmp");
                Files.write(sibling, mutated);
                // The native host may still hold the previously replaced PSD while its import
                // settles (wineserver fd evidence, plan 2026-09-26). A real editor surfaces the
                // sharing failure to the user and the user retries the save; the probe bounds
                // that retry, snapshots the holder state per attempt, and still fails closed.
                final int maxMoveAttempts = 12;
                for (int attempt = 1; ; attempt++) {
                    final String movePrefix = prefix + (attempt > 1
                        ? "failedMoveHandles.attempt" + attempt + "." : "failedMoveHandles.");
                    try {
                        Files.move(sibling, tempFile, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                        if (attempt > 1) {
                            result.setProperty(prefix + "write.atomicMove.retried", "true");
                            result.setProperty(prefix + "write.atomicMove.attempts",
                                Integer.toString(attempt));
                        }
                        break;
                    } catch (java.io.IOException failure) {
                        try {
                            final PersistSaveChecks.VerifiedHostArtifact artifact = exactHostArtifactForDialog();
                            WindowsPsdHandleObservation.observe(result, movePrefix,
                                artifact.loader(), artifact.artifact(), tempFile, sibling);
                        } catch (Exception | LinkageError | OutOfMemoryError diagnosticFailure) {
                            result.setProperty(movePrefix + "status", "UNAVAILABLE");
                            result.setProperty(movePrefix + "diagnostic",
                                diagnosticFailure.toString());
                        }
                        result.setProperty(prefix + "write.atomicMove.rejectedAt",
                            Integer.toString(attempt));
                        if (attempt >= maxMoveAttempts
                            || !(failure instanceof java.nio.file.AccessDeniedException)) {
                            throw failure;
                        }
                        Thread.sleep(400L);
                    }
                }
            } else if (i == 3) {
                Files.write(tempFile, mutated);
                Files.write(tempFile, plan.overlapFinalWrite());
                result.setProperty("cycle." + i + ".overlapFinalContainsRgbMutation",
                    Boolean.toString(plan.targetRgbMutated()));
            } else {
                Files.write(tempFile, mutated);
            }
            final long writeCompletedNanos = System.nanoTime();
            final SessionFileReadiness.RevisionEvent revisionEvent = awaitRevision(revisions, 20);
            final PsdFileRevision revision = revisionEvent.revision();
            final TextureRelationsSnapshot relationsBefore;
            try {
                requireTargetBinding(result, beforeTarget,
                    prefix + "targetBinding.beforeImport", 0L);
                relationsBefore = captureCycleRelations(result, prefix + "rawRelation.before");
                if (!TargetResolution.recordExpectedRawPresence(result, prefix + "rawRelation.before",
                    relationsBefore, beforeTarget.raw())) {
                    throw new IllegalStateException("current lineage raw is absent before import: "
                        + beforeTarget.raw().value());
                }
                TargetResolution.requireStableSharedModelImageRelation(result,
                    prefix + "rawRelation.before.sharedModelImage",
                    beforeTarget.identity(), relationsBefore, contentProfile,
                    stableSharedModelImageUsers);
            } catch (Exception failure) {
                TargetResolution.recordRawRelationImportFailure(result, prefix, failure);
                throw failure;
            } catch (Error failure) {
                TargetResolution.recordRawRelationImportFailure(result, prefix, failure);
                throw failure;
            }
            final long detectedMs = (System.nanoTime() - writeStart) / 1_000_000;
            final long replaceStart = System.nanoTime();
            final PsdReplaceResult replaced;
            try {
                replaced = replace(file, beforeTarget.raw(), revision);
            } catch (Exception failure) {
                TargetResolution.recordRawRelationImportFailure(result, prefix, failure);
                throw failure;
            } catch (Error failure) {
                TargetResolution.recordRawRelationImportFailure(result, prefix, failure);
                throw failure;
            }
            final long publicCompletionNanos = System.nanoTime();
            final long replaceMs = (System.nanoTime() - replaceStart) / 1_000_000;
            result.setProperty(prefix + "revisionDeliveredMs", Long.toString(detectedMs));
            result.setProperty(prefix + "replaceMs", Long.toString(replaceMs));
            result.setProperty(prefix + "status", replaced.status().name());
            result.setProperty(prefix + "diagnostic", replaced.diagnostic());
            result.setProperty(prefix + "after", replaced.after().map(RawImageId::value).orElse(""));
            result.setProperty(prefix + "consumed", Boolean.toString(
                replaced.consumedRevision().isPresent()));
            DiagnosticObservationRecorder.recordImportCompletion(result, prefix, replaced);

            if (replaced.status() != PsdReplaceResult.Status.APPLIED
                || replaced.consumedRevision().isEmpty() || replaced.after().isEmpty()) {
                TargetResolution.recordNoNewRawObserved(result, prefix,
                    "new raw export skipped because public replacement was not fully APPLIED");
                throw new IllegalStateException("Explicit-target replacement not applied in cycle "
                    + i + ": " + replaced.status() + " " + replaced.diagnostic());
            }

            final TextureRelationsSnapshot relationsAfter;
            final TargetResolution.TargetIdentity observedAfter;
            try {
                relationsAfter = captureCycleRelations(result, prefix + "rawRelation.after");
                observedAfter = observeTargetIdentityOnWorker(beforeTarget);
                PersistSaveChecks.recordTargetIdentity(result, prefix + "lineage.after.observed", observedAfter);
            } catch (Exception failure) {
                TargetResolution.recordRawRelationImportFailure(result, prefix, failure);
                throw failure;
            } catch (Error failure) {
                TargetResolution.recordRawRelationImportFailure(result, prefix, failure);
                throw failure;
            }

            TargetResolution.requireStableSharedModelImageRelation(result,
                prefix + "rawRelation.after.sharedModelImage",
                beforeTarget.identity(), relationsAfter, contentProfile,
                stableSharedModelImageUsers);

            final TargetResolution.TargetIdentity acceptedIdentity;
            try {
                acceptedIdentity = PersistSaveChecks.acceptAppliedTargetLineage(
                    beforeTarget.identity(), revision, replaced, relationsAfter, observedAfter);
            } catch (Exception failure) {
                result.setProperty(prefix + "lineage.status", "REJECTED");
                result.setProperty(prefix + "lineage.diagnostic", failure.toString());
                throw failure;
            }
            final TargetResolution.Target appliedTarget = TargetResolution.targetWithIdentity(beforeTarget, acceptedIdentity,
                relationsAfter);
            lastBeforeTarget = beforeTarget;
            currentTarget = appliedTarget;
            result.setProperty(prefix + "lineage.status", "ADVANCED");
            result.setProperty(prefix + "lineage.afterRaw", appliedTarget.raw().value());
            result.setProperty(prefix + "lineage.afterGeneration",
                Long.toString(appliedTarget.identity().generation()));
            verifyCompositionPreserved(result, prefix + "composition", appliedTarget);

            final RawExportCoordination.RawImageRelationDelta delta = RawExportCoordination.rawImageRelationDelta(
                relationsBefore, relationsAfter);
            RawExportCoordination.recordRawImageRelationDelta(result, prefix + "rawRelation", delta);
            TargetResolution.requireExpectedRawForDelta(result, prefix + "rawRelation",
                prefix + "raw.new", beforeTarget.raw(), relationsBefore, relationsAfter, delta);
            final Optional<RawImageId> newCandidate = TargetResolution.requireRawImageDeltaForCycle(
                result, prefix, delta, appliedTarget.raw());
            if (newCandidate.isPresent()) {
                RawExportCoordination.inspectUniqueNewRaw(
                    result, prefix + "raw.new", beforeTarget.raw(), delta, contentProfile,
                    candidate -> trackedRawExportHandle(
                        result, appliedTarget, tracker, prefix + "raw.new", candidate));
            }

            if (validateTargetContent) {
                result.setProperty(prefix + "raw.old.id", beforeTarget.raw().value());
                recordCycleOldRawFingerprint(result, prefix, beforeTarget, tracker);
                final String completionPrefix =
                    RawExportCoordination.joinPrefix("persist.observation." + prefix, "importCompletion");
                final String settlePrefix =
                    RawExportCoordination.joinPrefix("persist.observation." + prefix, "boundedSettle");
                final DiagnosticObservationRecorder.DiagnosticObservation completion;
                final long freshObservationCompletedNanos;
                try {
                    completion = captureFreshDiagnosticObservation(
                        result, completionPrefix, appliedTarget, tracker,
                        "fresh-native-export-after-public-import-completion", 0L, true);
                    freshObservationCompletedNanos = System.nanoTime();
                } catch (Exception failure) {
                    DiagnosticObservationRecorder.recordFreshDiagnosticFailurePreservingPrimary(
                        result, completionPrefix, settlePrefix, failure);
                    throw failure;
                } catch (Error failure) {
                    DiagnosticObservationRecorder.recordFreshDiagnosticFailurePreservingPrimary(
                        result, completionPrefix, settlePrefix, failure);
                    throw failure;
                }
                if (performanceTimings != null) {
                    performanceTimings.record(result, prefix, writeCompletedNanos,
                        revisionEvent.callbackNanos(), publicCompletionNanos,
                        freshObservationCompletedNanos);
                }
                final AtomicInteger settleAttempt = new AtomicInteger();
                final AtomicReference<String> settleAttemptPrefix = new AtomicReference<>();
                final DiagnosticObservationRecorder.BoundedSettleResult settled = DiagnosticObservationRecorder.awaitBoundedSettle(
                    completion,
                    remainingMillis -> {
                        final String attemptPrefix = settlePrefix + ".attempt."
                            + settleAttempt.incrementAndGet();
                        settleAttemptPrefix.set(attemptPrefix);
                        return captureFreshDiagnosticObservation(
                            result, attemptPrefix, appliedTarget, tracker,
                            "fresh-native-export-after-bounded-settle-poll", remainingMillis, false);
                    },
                    DiagnosticObservationRecorder.BOUNDED_SETTLE_MAX_ATTEMPTS,
                    DiagnosticObservationRecorder.BOUNDED_SETTLE_MAX_DURATION_MILLIS,
                    nanos -> LockSupport.parkNanos(nanos),
                    observation -> {
                        final String attemptPrefix = settleAttemptPrefix.get();
                        if (attemptPrefix != null) {
                            DiagnosticObservationRecorder.recordDiagnosticCapture(result, attemptPrefix, appliedTarget,
                                observation, "fresh-native-export-after-bounded-settle-poll", "");
                        }
                    });
                DiagnosticObservationRecorder.recordBoundedSettle(result, settlePrefix, settled);
            } else {
                DiagnosticObservationRecorder.recordSettleNotAttempted(result,
                    RawExportCoordination.joinPrefix("persist.observation." + prefix, "boundedSettle"),
                    "persist diagnostics not requested");
            }

            result.setProperty(prefix + "currentRawAfter", currentTarget.raw().value());
            result.setProperty("applied.currentRaw", currentTarget.raw().value());
            result.setProperty("applied.currentGeneration",
                Long.toString(currentTarget.identity().generation()));
            Thread.sleep(1500);
            synchronized (revisions) {
                result.setProperty(prefix + "extraRevisions", Integer.toString(revisions.size()));
                revisions.clear();
            }
            PersistSaveChecks.recordMemoryAttribution(result, prefix + "memoryAfter.", true);
        }
        result.setProperty("cycles.currentRaw", currentTarget.raw().value());
        result.setProperty("cycles.lastBeforeRaw", lastBeforeTarget.raw().value());
        return new SessionFileReadiness.SaveCyclesResult(lastMutation, currentTarget, lastBeforeTarget);
    }

    private void runCorruptedSave(final Properties result, final PsdEditFile file, final TargetResolution.Target target,
        final Path tempFile, final Deque<SessionFileReadiness.RevisionEvent> revisions) throws Exception {
        Files.write(tempFile, "not-a-psd-corrupted-save".getBytes(StandardCharsets.UTF_8));
        final PsdFileRevision revision = awaitRevision(revisions, 20).revision();
        final PsdReplaceResult corrupted = replace(file, target.raw(), revision);
        result.setProperty("corrupted.status", corrupted.status().name());
        result.setProperty("corrupted.diagnostic", corrupted.diagnostic());
        result.setProperty("corrupted.consumed", Boolean.toString(corrupted.consumedRevision().isPresent()));
        if (corrupted.status() == PsdReplaceResult.Status.APPLIED
            || corrupted.consumedRevision().isPresent()) {
            throw new IllegalStateException("Corrupted save was applied or consumed");
        }
        result.setProperty("corrupted.check", "PASS");
    }

    private void runUndoRedo(final Properties result, final TargetResolution.Target lastBeforeTarget,
        final TargetResolution.Target appliedTarget, final TempExportTracker.TempTracker tracker,
        final PsdValidationContent.Fingerprint baselineFingerprint,
        final PsdValidationContent.Fingerprint postFingerprint) throws Exception {
        final String appliedRaw = appliedTarget.raw().value();
        final AtomicReference<dev.turboism.sdk.cubism.history.HistoryMoveResult> undo =
            new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> undo.set(context.cubism().history().undo(1)));
        result.setProperty("undo.outcome", undo.get().outcome().name());
        PersistSaveChecks.requireHistoryMoved(undo.get(), "undo");
        requireTargetBinding(result, lastBeforeTarget, "undo.targetBinding", 0L);
        verifyCompositionPreserved(result, "undo.composition", lastBeforeTarget);
        final String afterUndo = currentRawOnEdt(lastBeforeTarget.modelImage().value());
        result.setProperty("undo.currentRaw", afterUndo);
        if (!lastBeforeTarget.raw().value().equals(afterUndo)) {
            throw new IllegalStateException("Undo did not restore the last replacement-before raw: "
                + "expected=" + lastBeforeTarget.raw().value() + " actual=" + afterUndo);
        }
        final TextureRelationsSnapshot undoRelations =
            captureCycleRelations(result, "undo.rawRelation");
        TargetResolution.requireStableSharedModelImageRelation(result, "undo.rawRelation.sharedModelImage",
            lastBeforeTarget.identity(), undoRelations, contentProfile,
            stableSharedModelImageUsers);
        if (baselineFingerprint != null) {
            final PsdValidationContent.Fingerprint undoFingerprint = exportTargetFingerprint(
                result, lastBeforeTarget, tracker, "undo");
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "undo.targetRgb", undoFingerprint);
            result.setProperty("undo.targetRgbSha256", undoFingerprint.sha256());
            if (!baselineFingerprint.equals(undoFingerprint)) {
                throw new IllegalStateException(
                    "Undo did not restore the baseline target RGB fingerprint");
            }
        }
        final AtomicReference<dev.turboism.sdk.cubism.history.HistoryMoveResult> redo =
            new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> redo.set(context.cubism().history().redo(1)));
        result.setProperty("redo.outcome", redo.get().outcome().name());
        PersistSaveChecks.requireHistoryMoved(redo.get(), "redo");
        requireTargetBinding(result, appliedTarget, "redo.targetBinding", 0L);
        verifyCompositionPreserved(result, "redo.composition", appliedTarget);
        final String afterRedo = currentRawOnEdt(appliedTarget.modelImage().value());
        result.setProperty("redo.currentRaw", afterRedo);
        if (!appliedRaw.equals(afterRedo)) {
            throw new IllegalStateException("Redo did not restore the applied raw identity");
        }
        final TextureRelationsSnapshot redoRelations =
            captureCycleRelations(result, "redo.rawRelation");
        TargetResolution.requireStableSharedModelImageRelation(result, "redo.rawRelation.sharedModelImage",
            appliedTarget.identity(), redoRelations, contentProfile,
            stableSharedModelImageUsers);
        if (postFingerprint != null) {
            final PsdValidationContent.Fingerprint redoFingerprint = exportTargetFingerprint(
                result, appliedTarget, tracker, "redo");
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "redo.targetRgb", redoFingerprint);
            result.setProperty("redo.targetRgbSha256", redoFingerprint.sha256());
            if (!postFingerprint.equals(redoFingerprint)) {
                throw new IllegalStateException(
                    "Redo did not restore the post-edit target RGB fingerprint");
            }
        }
    }

    private OfficialPsdReplacementBaseline.CompositionObservation readComposition(
        final Properties result, final String prefix, final TargetResolution.Target target) throws Exception {
        requireTargetBinding(result, target, prefix + ".targetBinding", 0L);
        final AtomicReference<OfficialPsdReplacementBaseline.CompositionObservation> observed =
            new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (stopped || officialCurrentHostWindowOnEdt() != guiBoundWindow) {
                    throw new IllegalStateException("composition task stopped or changed window");
                }
                final var value = compositionReader.observe();
                if (!target.identity().documentId().equals(value.documentId())
                    || !target.identity().modelId().equals(value.modelId())) {
                    throw new IllegalStateException("composition belongs to another document/model");
                }
                observed.set(value);
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException(
            "live native composition observation failed", failure.get());
        final var value = observed.get();
        result.setProperty(prefix + ".documentId", value.documentId());
        result.setProperty(prefix + ".modelId", value.modelId());
        result.setProperty(prefix + ".targetVersion", Integer.toString(value.targetVersion()));
        result.setProperty(prefix + ".meshCount", Integer.toString(value.meshes().size()));
        for (final var entry : new java.util.TreeMap<>(value.meshes()).entrySet()) {
            final String key = prefix + ".mesh." + entry.getKey();
            result.setProperty(key + ".guid", entry.getValue().guid());
            result.setProperty(key + ".color", entry.getValue().color());
            result.setProperty(key + ".alpha", entry.getValue().alpha());
        }
        return value;
    }

    private void verifyCompositionPreserved(final Properties result, final String prefix,
        final TargetResolution.Target target) throws Exception {
        if (compositionReader == null) return;
        TargetResolution.requireSameComposition(baselineComposition, readComposition(result, prefix, target));
        result.setProperty(prefix + ".preserved", "true");
    }

    private void runStopAndRecovery(final Properties result, final TempExportTracker.TrackedExport primary,
        final TargetResolution.Target target, final Path tempFile, final Deque<SessionFileReadiness.RevisionEvent> revisions,
        final TempExportTracker.TempTracker tracker)
        throws Exception {
        final PsdFileOperationResult stopResult = tracker.stop(primary, result);
        result.setProperty("stop.status", stopResult.status().name());
        if (stopResult.status() != PsdFileOperationResult.Status.STOPPED) {
            throw new IllegalStateException("Edit handle did not stop cleanly");
        }
        synchronized (revisions) { revisions.clear(); }
        Files.write(tempFile, "post-stop-write".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(4000);
        synchronized (revisions) {
            result.setProperty("stop.postWriteRevisions", Integer.toString(revisions.size()));
            if (!revisions.isEmpty()) {
                throw new IllegalStateException("Stopped handle still published revisions");
            }
        }
        final TempExportTracker.TrackedExport again = exportTracked(result, target.raw(), tracker, "recovery");
        result.setProperty("recovery.exportStatus", PsdExportResult.Status.EXPORTED.name());
        final Path secondFile = again.path();
        result.setProperty("recovery.newFile", Boolean.toString(!secondFile.equals(tempFile)));
        final PsdFileOperationResult secondStop = tracker.stop(again, result);
        result.setProperty("recovery.stopStatus", secondStop.status().name());
        if (secondStop.status() != PsdFileOperationResult.Status.STOPPED) {
            throw new IllegalStateException("Recovered handle did not stop cleanly");
        }
    }

    private PsdReplaceResult replace(final PsdEditFile file, final RawImageId raw,
        final PsdFileRevision revision) throws Exception {
        final AtomicReference<CompletionStage<PsdReplaceResult>> stage = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (stopped) throw new IllegalStateException("Probe stopped");
                stage.set(context.cubism().model().active().textures()
                    .replaceRawImagePsd(raw, file, revision));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException("Replace invocation failed", failure.get());
        return stage.get().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }

    private String currentRawOnEdt(final String modelImageId) throws Exception {
        final AtomicReference<String> current = new AtomicReference<>("");
        SwingUtilities.invokeAndWait(() -> {
            final TextureRelationsSnapshot relations =
                context.cubism().model().active().textures().relations();
            relations.modelImages().stream()
                .filter(image -> image.id().value().equals(modelImageId))
                .findFirst()
                .flatMap(image -> image.currentRawImageId())
                .ifPresent(raw -> current.set(raw.value()));
        });
        return current.get();
    }

    private TargetResolution.TargetIdentity observeTargetIdentityOnWorker(final TargetResolution.Target target) throws Exception {
        Objects.requireNonNull(target, "target");
        final AtomicReference<TargetResolution.TargetIdentity> observed = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable read = () -> {
            try {
                observed.set(currentTargetIdentityOnEdt(target.identity()));
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) read.run();
            else SwingUtilities.invokeAndWait(read);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (InvocationTargetException invocationFailure) {
            final Throwable cause = invocationFailure.getCause();
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("target identity observation dispatch failed", cause);
        }
        if (failure.get() != null) {
            final Throwable error = failure.get();
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("target identity observation failed", error);
        }
        return Objects.requireNonNull(observed.get(), "target identity observation");
    }

    private SessionFileReadiness.RevisionEvent awaitRevision(final Deque<SessionFileReadiness.RevisionEvent> revisions,
        final int timeoutSeconds) throws Exception {
        synchronized (revisions) {
            final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (revisions.isEmpty() && System.currentTimeMillis() < deadline && !stopped) {
                revisions.wait(250);
            }
            if (revisions.isEmpty()) {
                throw new IllegalStateException("Stable save revision not delivered within "
                    + timeoutSeconds + "s");
            }
            return revisions.poll();
        }
    }

    /**
     * Captures the final SAVE_AS admission and invokes the command in the same EDT dispatch as
     * the document/model/relation/window checks.  The worker owns the grant and all event waits;
     * this method never waits for a lifecycle event while running on the EDT.
     */
    private PersistSaveChecks.SaveAsExecution executeSaveAsIfAdmittedOnEdt(final TargetResolution.Target target,
        final UserFileHandle handle, final AtomicLong lifecycleSequence,
        final BooleanSupplier taskActive) throws Exception {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(lifecycleSequence, "lifecycleSequence");
        final AtomicReference<PersistSaveChecks.SaveAsExecution> execution = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable invoke = () -> {
            try {
                if (!SwingUtilities.isEventDispatchThread()) {
                    throw new IllegalStateException("SAVE_AS admission must run on EDT");
                }
                final AtomicReference<PersistSaveChecks.PersistDocumentState> before = new AtomicReference<>();
                final PersistSaveChecks.SaveAsAdmission admission = PersistSaveChecks.admitAndExecuteSaveAsOnEdt(
                    target.identity(), componentIdentity(guiBoundWindow),
                    () -> {
                        final PersistSaveChecks.PersistDocumentState state = currentPersistDocumentStateOnEdt(
                            target.identity());
                        before.set(state);
                        return state;
                    },
                    () -> stopped || !taskActive.getAsBoolean(),
                    () -> {
                        // No await or callback is allowed between this sequence capture and the
                        // native command.  The shared seam has just rechecked all task state.
                        final long executeBeforeSequence = lifecycleSequence.get();
                        final EditorCommandResult saved = context.services().find(EditorCommandService.class).orElse(EditorCommandService.unavailable()).execute(
                            new EditorFileCommandRequest(
                                EditorFileCommand.SAVE_AS, handle,
                                EditorOverwritePolicy.REPLACE_EXISTING));
                        execution.set(new PersistSaveChecks.SaveAsExecution(saved, before.get(),
                            executeBeforeSequence));
                    });
                if (!admission.admitted()) {
                    throw new IllegalStateException("SAVE_AS admission rejected: "
                        + admission.diagnostic());
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) invoke.run();
            else SwingUtilities.invokeAndWait(invoke);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (InvocationTargetException dispatchFailure) {
            final Throwable cause = dispatchFailure.getCause();
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("SAVE_AS admission dispatch failed", cause);
        }
        if (failure.get() != null) {
            final Throwable error = failure.get();
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("SAVE_AS admission failed", error);
        }
        return Objects.requireNonNull(execution.get(), "SAVE_AS execution result");
    }

    private PersistSaveChecks.PersistDocumentState observePersistDocumentState(final TargetResolution.TargetIdentity expected)
        throws Exception {
        final AtomicReference<PersistSaveChecks.PersistDocumentState> observed = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable read = () -> {
            try {
                observed.set(currentPersistDocumentStateOnEdt(expected));
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) read.run();
            else SwingUtilities.invokeAndWait(read);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (InvocationTargetException dispatchFailure) {
            final Throwable cause = dispatchFailure.getCause();
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("SAVE_AS post-state dispatch failed", cause);
        }
        if (failure.get() != null) {
            final Throwable error = failure.get();
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("SAVE_AS post-state observation failed", error);
        }
        return Objects.requireNonNull(observed.get(), "SAVE_AS post-state observation");
    }

    /**
     * Re-checks the already-bound task window on the same EDT as the SDK document/relation read.
     * The CEAppCtrl→main-frame chain proves the current document host window. The live reviewed
     * tree-table is only supplemental row-domain evidence; an ArtMesh domain ID is not a unique
     * document/window owner identity across documents.
     */
    private PersistSaveChecks.BoundTaskWindowEvidence verifyCurrentBoundTaskWindowOnEdt(
        final TargetResolution.TargetIdentity expected) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("task window verification must run on EDT");
        }
        final Window bound = guiBoundWindow;
        if (bound == null) throw new IllegalStateException("bound task window is unavailable");
        final Window chainWindow = officialCurrentHostWindowOnEdt();
        if (chainWindow != bound) {
            throw new IllegalStateException("official current document window changed from the "
                + "bound task window: expected=" + GuiRowDispatch.componentIdentity(bound)
                + " actual=" + GuiRowDispatch.componentIdentity(chainWindow));
        }
        if (!bound.isShowing() || !bound.isDisplayable()) {
            throw new IllegalStateException("bound task window is not showing/displayable");
        }
        if (expected == null || expected.artMeshId().isBlank()
            || RawExportCoordination.UNAVAILABLE_VALUE.equals(expected.artMeshId())) {
            throw new IllegalStateException("bound task ArtMesh identity is unavailable");
        }
        final List<ExactHostRowDispatch.ExactTableRef> tables = new ArrayList<>();
        ExactHostRowDispatch.collectReviewedTables(bound, bound, tables);
        if (tables.isEmpty()) {
            throw new IllegalStateException(
                "bound task window has no live reviewed host tree-table");
        }
        int exactMatches = 0;
        for (final ExactHostRowDispatch.ExactTableRef table : tables) {
            final ExactHostRowTarget.HostAccessPreparation preparation =
                hostAccessByLoader.get(table.modelClass().getClassLoader());
            if (preparation == null || !preparation.available()) continue;
            final Object currentModel = table.table().getModel();
            if (currentModel == null || currentModel.getClass() != table.modelClass()
                || !GuiRowDispatch.isLiveComponent(table.table())) {
                continue;
            }
            for (int row = 0; row < table.table().getRowCount(); row++) {
                final ExactHostRowTarget.Resolution resolution = ExactHostRowTarget.resolve(
                    table.table(), row, preparation.context());
                if (resolution.available() && expected.artMeshId().equals(
                    resolution.target().domainId())) {
                    exactMatches++;
                }
            }
        }
        if (exactMatches == 0) {
            throw new IllegalStateException(
                "bound current host window no longer exposes the task ArtMesh row domain");
        }
        return new PersistSaveChecks.BoundTaskWindowEvidence(chainWindow, componentIdentity(chainWindow), exactMatches);
    }

    /** Reads public SDK state plus freshly verified task-window evidence; all reads are on EDT. */
    private PersistSaveChecks.PersistDocumentState currentPersistDocumentStateOnEdt(
        final TargetResolution.TargetIdentity expected) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("SAVE_AS document state must be read on EDT");
        }
        final var document = context.cubism().activeDocument().orElseThrow(
            () -> new IllegalStateException("active document unavailable before SAVE_AS"));
        final CubismModel model = context.cubism().model().active();
        final TextureRelationsSnapshot relations = model.textures().relations();
        final TargetResolution.TargetIdentity identity = PersistSaveChecks.targetIdentityFromRelationsOnEdt(
            expected, document.documentId(), model, relations);
        final PersistSaveChecks.BoundTaskWindowEvidence bound = verifyCurrentBoundTaskWindowOnEdt(expected);
        return new PersistSaveChecks.PersistDocumentState(
            identity,
            document.contentId(),
            document.relativePath(),
            bound.identity(),
            bound.window().isShowing(),
            bound.window().isDisplayable());
    }

    private void awaitSaveAfterSequence(final List<PersistSaveChecks.PersistSaveEvent> saves,
        final long executeBeforeSequence, final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (stopped) throw new IllegalStateException("stopped while waiting for SAVE After");
            if (saves.stream().anyMatch(event -> event != null
                && event.sequence() > executeBeforeSequence)) return;
            Thread.sleep(100L);
        }
        if (saves.stream().noneMatch(event -> event != null
            && event.sequence() > executeBeforeSequence)) {
            throw new IllegalStateException("SAVE lifecycle After event was not observed after "
                + "execute sequence " + executeBeforeSequence);
        }
    }

    /**
     * Performs the dialog proof on the worker before SAVE_AS can be issued.  No dialog is opened
     * here: the exact 5.3.02 classes, code source, JAR digest, localizer template, and the
     * UUOption/aM result shape are checked without invoking the native confirmation method.
     */
    private PersistSaveChecks.UnusedRawDialogProof prepareUnusedRawDialogProof() {
        if (SwingUtilities.isEventDispatchThread()) {
            return PersistSaveChecks.UnusedRawDialogProof.unavailable("dialog proof must run off EDT");
        }
        try {
            final PersistSaveChecks.VerifiedHostArtifact verifiedArtifact = exactHostArtifactForDialog();
            final Path expectedArtifact = verifiedArtifact.artifact();
            final ClassLoader loader = verifiedArtifact.loader();
            final Class<?> localizer = PersistSaveChecks.loadExactClass(loader, PersistSaveChecks.OFFICIAL_LOCALIZER_CLASS);
            final Class<?> option = PersistSaveChecks.loadExactClass(loader, PersistSaveChecks.OFFICIAL_OPTION_CLASS);
            final Class<?> actionResult = PersistSaveChecks.loadExactClass(loader, PersistSaveChecks.OFFICIAL_ACTION_RESULT_CLASS);
            final Class<?> window = PersistSaveChecks.loadExactClass(loader, PersistSaveChecks.OFFICIAL_WINDOW_CLASS);
            final Class<?> document = PersistSaveChecks.loadExactClass(loader, PersistSaveChecks.OFFICIAL_MODELING_DOCUMENT_CLASS);
            final Class<?> action = PersistSaveChecks.loadExactClass(loader, PersistSaveChecks.OFFICIAL_ACTION_CLASS);
            final List<Class<?>> officialClasses = List.of(
                localizer, option, actionResult, window, document, action);
            for (final Class<?> type : officialClasses) {
                if (type.getClassLoader() != loader) {
                    throw new IllegalStateException("official class loader mismatch: "
                        + type.getName());
                }
                if (!expectedArtifact.equals(PersistSaveChecks.codeSourcePath(type))) {
                    throw new IllegalStateException("official class code source mismatch: "
                        + type.getName());
                }
            }
            final String digest = PsdStructureInspection.sha256(expectedArtifact);
            if (!ExactHostRowTarget.HOST_JAR_SHA256.equals(digest)) {
                throw new IllegalStateException("official 5.3.02 JAR SHA-256 mismatch: " + digest);
            }

            final Field localizerField = PersistSaveChecks.exactField(localizer, "a", localizer,
                true, true, true);
            final Object localizerInstance = localizerField.get(null);
            final Method localize = PersistSaveChecks.exactMethod(localizer, "a", String.class,
                false, true, String.class, String[].class);
            if (!Modifier.isPublic(localize.getModifiers())) {
                throw new IllegalStateException("localizer method is not public");
            }
            final Object templateValue = localize.invoke(localizerInstance,
                PersistSaveChecks.UNUSED_RAW_MESSAGE_KEY, new String[0]);
            if (!(templateValue instanceof String template) || template.isBlank()
                || template.equals(PersistSaveChecks.UNUSED_RAW_MESSAGE_KEY)) {
                throw new IllegalStateException("CUB3-3054 localizer template is unavailable");
            }
            // This is the exact TextureManagerHandler call shape: two format arguments are
            // required before the message can be considered the full unused-raw template.
            if (!template.contains("%d") || !template.contains("%s")) {
                throw new IllegalStateException(
                    "CUB3-3054 template does not expose count and names placeholders: "
                        + template);
            }
            final String formatted = String.format(Locale.ROOT, template, 1,
                "unused source image");
            if (formatted.isBlank() || formatted.equals(template)) {
                throw new IllegalStateException("CUB3-3054 template did not format");
            }

            final Method optionResult = PersistSaveChecks.exactMethod(option, "d", actionResult,
                false, true, String.class, String.class, boolean.class);
            final Method optionResultDefault = PersistSaveChecks.exactMethod(option, "d", actionResult,
                true, false, option, String.class, String.class, boolean.class,
                int.class, Object.class);
            final Method threeButton = PersistSaveChecks.exactMethod(option, "f", actionResult,
                false, true, String.class, String.class, boolean.class, window);
            final Method buttonHelper = PersistSaveChecks.exactMethod(option, "b", JButton.class,
                false, true, String.class, int.class);
            if (!Modifier.isPrivate(threeButton.getModifiers())
                || !Modifier.isPrivate(buttonHelper.getModifiers())) {
                throw new IllegalStateException("UUOption button helpers are not private");
            }
            if (!Modifier.isPublic(optionResult.getModifiers())
                || !Modifier.isPublic(optionResultDefault.getModifiers())) {
                throw new IllegalStateException("UUOption.d is not public");
            }
            PersistSaveChecks.exactActionResultField(actionResult, "a", "YES");
            PersistSaveChecks.exactActionResultField(actionResult, "b", "NO");
            PersistSaveChecks.exactActionResultField(actionResult, "c", "CANCEL");
            final Method saveDocument = PersistSaveChecks.exactMethod(document, "saveDocument", boolean.class,
                false, true, java.io.File.class, boolean.class);
            if (!Modifier.isPublic(saveDocument.getModifiers())
                || !Modifier.isFinal(saveDocument.getModifiers())) {
                throw new IllegalStateException("CModelingDocument.saveDocument shape changed");
            }
            // Keep these locals visible to the proof review: the method shapes above are the
            // non-modal, validation-only evidence for UUOption.d -> aM.b at option index 1.
            if (optionResult.getReturnType() != optionResultDefault.getReturnType()
                || threeButton.getReturnType() != actionResult
                || buttonHelper.getReturnType() != JButton.class) {
                throw new IllegalStateException("UUOption/aM method return shape changed");
            }
            return PersistSaveChecks.UnusedRawDialogProof.proven(expectedArtifact, loader, template,
                "exact JAR/classloader/code-source; CUB3-3054 formatted with two arguments; "
                    + "UUOption.f creates three JButton options; index 1 returns aM.b=NO; "
                    + "N->Click is checked on the live JOptionPane");
        } catch (Throwable failure) {
            return PersistSaveChecks.UnusedRawDialogProof.unavailable(
                "official CUB3-3054 dialog proof unavailable: " + failure);
        }
    }

    private PersistSaveChecks.VerifiedHostArtifact exactHostArtifactForDialog() throws Exception {
        final List<PersistSaveChecks.VerifiedHostArtifact> candidates = new ArrayList<>();
        for (final Map.Entry<ClassLoader, ExactHostRowTarget.HostAccessPreparation> entry
            : hostAccessByLoader.entrySet()) {
            final ClassLoader loader = entry.getKey();
            if (loader == null) continue;
            final ExactHostRowTarget.HostAccessPreparation preparation = entry.getValue();
            if (preparation == null || !preparation.available()) continue;

            // Take the artifact from the live verified class code source, never from an offline
            // javap path or a host-independent configuration value. The row resolver's context
            // is the first proof of the loader/artifact pair; this re-check prevents the modal
            // dialog proof from drifting to another JAR.
            final Class<?> tableModel = PersistSaveChecks.loadExactClass(loader, "com.live2d.ui.treeTable.j");
            final Path artifact = PersistSaveChecks.codeSourcePath(tableModel);
            final ExactHostRowTarget.HostAccessContext context = preparation.context();
            if (!artifact.equals(context.artifact())) {
                throw new IllegalStateException(
                    "live tree-table code source differs from the verified row context: "
                        + artifact + " != " + context.artifact());
            }
            if (!Files.isRegularFile(artifact)) {
                throw new IllegalStateException("live host code source is not a regular artifact: "
                    + artifact);
            }
            final String digest = PsdStructureInspection.sha256(artifact);
            if (!ExactHostRowTarget.HOST_JAR_SHA256.equals(context.artifactSha256())
                || !ExactHostRowTarget.HOST_JAR_SHA256.equals(digest)) {
                throw new IllegalStateException("verified host artifact SHA-256 mismatch: context="
                    + context.artifactSha256() + " live=" + digest);
            }
            candidates.add(new PersistSaveChecks.VerifiedHostArtifact(loader, artifact));
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException("expected one exact host classloader, found "
                + candidates.size());
        }
        return candidates.get(0);
    }

    /** Prepares the same official main-frame identity chain used by the fixture helper. */
    private PersistSaveChecks.HostWindowAccess prepareHostWindowAccess() throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("host window access preflight must run off EDT");
        }
        final PersistSaveChecks.VerifiedHostArtifact artifact = exactHostArtifactForDialog();
        final ClassLoader loader = artifact.loader();
        final Class<?> app = PersistSaveChecks.loadExactClass(loader, "com.live2d.cubism.CEAppCtrl");
        final Class<?> frameController = PersistSaveChecks.loadExactClass(loader,
            "com.live2d.cubism.view.CEMainFrameCtrl");
        final Class<?> cFrame = PersistSaveChecks.loadExactClass(loader, "com.live2d.ui.window.CFrame");
        final Class<?> windowBase = PersistSaveChecks.loadExactClass(loader, PersistSaveChecks.OFFICIAL_WINDOW_CLASS);
        for (final Class<?> type : List.of(app, frameController, cFrame, windowBase)) {
            if (!artifact.artifact().equals(PersistSaveChecks.codeSourcePath(type))) {
                throw new IllegalStateException("official window-chain code source mismatch: "
                    + type.getName());
            }
        }
        final Method appInstance = PersistSaveChecks.exactMethod(app, "access$get_instance$cp", app,
            true, true);
        final Method mainFrameController = PersistSaveChecks.exactMethod(app, "getMainFrameCtrl",
            frameController, false, true);
        final Method mainFrame = PersistSaveChecks.exactMethod(frameController, "getMainFrame", cFrame,
            false, true);
        final Method swingWindow = cFrame.getMethod("getJWindow");
        if (!Modifier.isPublic(swingWindow.getModifiers())
            || Modifier.isStatic(swingWindow.getModifiers())
            || swingWindow.getReturnType() != Window.class
            || swingWindow.getDeclaringClass() != windowBase) {
            throw new IllegalStateException("official getJWindow shape is not exact");
        }
        final Method swingFrame = cFrame.getMethod("getJFrame");
        if (!Modifier.isPublic(swingFrame.getModifiers())
            || Modifier.isStatic(swingFrame.getModifiers())
            || swingFrame.getReturnType() != javax.swing.JFrame.class
            || swingFrame.getDeclaringClass() != cFrame) {
            throw new IllegalStateException("official getJFrame shape is not exact");
        }
        return new PersistSaveChecks.HostWindowAccess(artifact.artifact(), appInstance, mainFrameController,
            mainFrame, swingWindow, swingFrame);
    }

    private Window officialCurrentHostWindowOnEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("official host window chain must run on EDT");
        }
        final PersistSaveChecks.HostWindowAccess access = hostWindowAccess;
        if (access == null) throw new IllegalStateException("official host window access unavailable");
        try {
            final Object controller = access.appInstance().invoke(null);
            if (controller == null) throw new IllegalStateException(
                "official application controller is not ready");
            final Object frameController = access.mainFrameController().invoke(controller);
            if (frameController == null) throw new IllegalStateException(
                "official main-frame controller is not ready");
            final Object cFrame = access.mainFrame().invoke(frameController);
            if (cFrame == null) throw new IllegalStateException("official CFrame is not ready");
            final Object window = access.swingWindow().invoke(cFrame);
            final Object frame = access.swingFrame().invoke(cFrame);
            if (!(window instanceof Window current) || !(frame instanceof Window frameWindow)
                || current != frameWindow || !current.isShowing() || !current.isDisplayable()) {
                throw new IllegalStateException(
                    "official main-frame Swing window is not showing/displayable or mismatched");
            }
            return current;
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("official host window chain failed",
                failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("official host window chain failed", failure);
        }
    }

    /**
     * Mediated persistence: a fixed-grant write handle → typed SAVE_AS → the native
     * {@code saveDocument} hook must surface a {@link ProjectFileOperationType#SAVE} After
     * event. The granted target lives outside the task fixture copy, so the runner's
     * fixture-unchanged guarantee still holds.
     */
    private void runPersistTail(final Properties result, final TargetResolution.Target target,
        final TempExportTracker.TempTracker tracker, final PsdValidationContent.Fingerprint baselineFingerprint,
        final PsdValidationContent.Fingerprint postBeforeUndo) throws Exception {
        result.setProperty("persist.saveSucceeded", "false");
        result.setProperty("persist.targetContentChanged", "false");
        result.setProperty("persist.tempQuarantine.status", "NOT_ATTEMPTED");
        result.setProperty("persist.tempQuarantine.taskOwned", "false");
        result.setProperty("persist.tempQuarantine.sourceMissing", "false");

        // Prepare the exact CEAppCtrl→main-frame→Swing window chain off the EDT.  Every later
        // current-document/window observation invokes only these verified handles on the EDT.
        hostWindowAccess = prepareHostWindowAccess();
        result.setProperty("persist.windowProof.status", "PROVEN");
        result.setProperty("persist.windowProof.artifact",
            hostWindowAccess.artifact().toString());

        // Undo/Redo must leave the same native post state that will be saved. Re-export it once
        // immediately before SAVE_AS, so the persistence gate does not trust staged PSD bytes or
        // a history/raw identity alone.
        final PsdValidationContent.Fingerprint preSavePost = exportTargetFingerprint(
            result, target, tracker, "postBeforeSave");
        DiagnosticObservationRecorder.recordTargetFingerprint(result, "persist.preSavePostEditTargetRgb", preSavePost);
        result.setProperty("persist.preSavePostEditTargetRgbSha256", preSavePost.sha256());
        if (!postBeforeUndo.equals(preSavePost)) {
            throw new IllegalStateException(
                "fresh post-edit target RGB fingerprint changed before SAVE_AS");
        }
        PersistSaveChecks.requireChangedPost(baselineFingerprint, preSavePost);

        saveBoundDocumentCopy(result, target);

        // A second fresh native export after SAVE_AS is the durable post fingerprint. The
        // legacy full-file/composite values are retained only as optional diagnostics.
        requireTargetBinding(result, target,
            "export.postAfterSave.targetBinding.before", 0L);
        final TempExportTracker.TrackedExport postExport = exportTracked(
            result, target.raw(), tracker, "postAfterSave");
        Throwable postExportFailure = null;
        try {
            requireTargetBinding(result, target,
                "export.postAfterSave.targetBinding.after", 0L);
            final byte[] postBytes = Files.readAllBytes(postExport.path());
            final PsdValidationContent.Fingerprint post = DiagnosticObservationRecorder.targetFingerprint(
                postBytes, "persist post-save target", contentProfile);
            DiagnosticObservationRecorder.recordTargetFingerprint(result, "persist.postEditTargetRgb", post);
            result.setProperty("persist.postEditTargetRgbSha256", post.sha256());
            result.setProperty("persist.postEditBytes", Integer.toString(postBytes.length));
            result.setProperty("persist.postEditSha256", sha256(postBytes));
            result.setProperty("persist.postEditImageSha256", imageDataSha256(postBytes));
            if (!preSavePost.equals(post)) {
                throw new IllegalStateException(
                    "fresh post-save target RGB fingerprint differs from pre-SAVE_AS state");
            }
            PersistSaveChecks.requireChangedPost(baselineFingerprint, post);
            result.setProperty("persist.targetContentChanged", "true");
        } catch (Exception failure) {
            postExportFailure = failure;
            throw failure;
        } catch (Error failure) {
            postExportFailure = failure;
            throw failure;
        } finally {
            TempExportTracker.stopPreservingPrimary(tracker, postExport, result, postExportFailure);
        }
        tracker.quarantine(result, context.paths().stateDir());
        PersistSaveChecks.requirePersistEvidence(result);
    }

    /** Same typed SAVE_AS and exact lifecycle/owner gates for all validation phases. */
    private void saveBoundDocumentCopy(final Properties result, final TargetResolution.Target target) throws Exception {
        saveBoundDocumentCopy(result, target, Long.MAX_VALUE, () -> !stopped);
    }

    private void saveBoundDocumentCopy(final Properties result, final TargetResolution.Target target,
        final long deadline, final BooleanSupplier taskActive) throws Exception {
        final PersistSaveChecks.UnusedRawDialogProof dialogProof = prepareUnusedRawDialogProof();
        result.setProperty("persist.dialogProof.status",
            dialogProof.available() ? "PROVEN" : "UNAVAILABLE");
        result.setProperty("persist.dialogProof.diagnostic", dialogProof.diagnostic());
        if (dialogProof.artifact() != null) {
            result.setProperty("persist.dialogProof.artifact",
                dialogProof.artifact().toString());
        }
        if (!dialogProof.messageTemplate().isBlank()) {
            result.setProperty("persist.dialogProof.template", dialogProof.messageTemplate());
        }
        if (!dialogProof.available()) {
            throw new PersistSaveChecks.Blocked("exact 5.3.02 CUB3-3054 keep/no dialog proof",
                dialogProof.diagnostic());
        }

        final List<PersistSaveChecks.PersistSaveEvent> saves = new CopyOnWriteArrayList<>();
        final AtomicLong lifecycleSequence = new AtomicLong();
        final AtomicInteger beforeEvents = new AtomicInteger();
        final AtomicInteger onEvents = new AtomicInteger();
        final Registration subscription = context.eventBus().subscribe(
            ProjectFileLifecycleEvent.After.class,
            event -> {
                final long sequence = lifecycleSequence.incrementAndGet();
                if (event.operation().operation() == ProjectFileOperationType.SAVE) {
                    saves.add(PersistSaveChecks.PersistSaveEvent.from(sequence, event));
                }
            });
        final Registration beforeSub = context.eventBus().subscribe(
            ProjectFileLifecycleEvent.Before.class,
            event -> beforeEvents.incrementAndGet());
        final Registration onSub = context.eventBus().subscribe(
            ProjectFileLifecycleEvent.On.class,
            event -> onEvents.incrementAndGet());
        try {
            final UserFileRequestResult granted = context.userFiles().request(
                new UserFileRequest(
                    "external-psd-persist",
                    "Persist edited document",
                    List.of("cmo3"),
                    UserFileMode.WRITE,
                    UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture().get(Math.min(60_000L, remainingStructuralMillis(deadline)),
                    TimeUnit.MILLISECONDS);
            if (!taskActive.getAsBoolean()) throw new IllegalStateException("SAVE_AS task expired during grant");
            result.setProperty("persist.grant.status", granted.status().name());
            final UserFileHandle handle = granted.handle().orElseThrow(() ->
                new IllegalStateException("No write grant issued: " + granted.status()));
            final DialogAnswerWatcher watcher = new DialogAnswerWatcher(guiBoundWindow, target,
                dialogProof, taskActive);
            final PersistSaveChecks.SaveAsExecution execution;
            try {
                if (deadline == Long.MAX_VALUE) {
                    watcher.start();
                    execution = executeSaveAsIfAdmittedOnEdt(target, handle, lifecycleSequence, taskActive);
                } else {
                    structuralEdt(deadline, () -> { watcher.start(); return Boolean.TRUE; });
                    execution = structuralEdt(deadline, () -> executeSaveAsIfAdmittedOnEdt(
                        target, handle, lifecycleSequence, taskActive));
                }
            } finally {
                watcher.close();
            }
            final EditorCommandResult saved = execution.result();
            result.setProperty("persist.saveAs.status", saved.status().name());
            result.setProperty("persist.saveAs.executed", Boolean.toString(saved.executed()));
            if (!watcher.actions.isEmpty()) {
                result.setProperty("persist.dialogActions", watcher.actions.toString());
            }
            if (!watcher.diagnostic().isBlank()) {
                result.setProperty("persist.dialogHandling", watcher.diagnostic());
            }
            if (!saved.executed()) {
                // A blocked native save leaves its modal dialog open; record which windows
                // are up so the failure identifies the blocker instead of a bare FAILED.
                result.setProperty("persist.openWindows", describeWindows());
                throw new IllegalStateException("SAVE_AS did not execute: " + saved.status());
            }
            result.setProperty("persist.save.executeBeforeSequence",
                Long.toString(execution.executeBeforeSequence()));
            awaitSaveAfterSequence(saves,
                execution.executeBeforeSequence(), Math.min(15_000L, remainingStructuralMillis(deadline)));
            result.setProperty("persist.saveEvents", Integer.toString(saves.size()));
            result.setProperty("persist.beforeEvents", Integer.toString(beforeEvents.get()));
            result.setProperty("persist.onEvents", Integer.toString(onEvents.get()));
            final PersistSaveChecks.PersistDocumentState afterSave = observePersistDocumentState(target.identity());
            PersistSaveChecks.recordPersistDocumentState(result, "persist.save.after", afterSave);
            final PersistSaveChecks.PersistSaveValidation saveValidation = PersistSaveChecks.validatePersistSaveAfter(
                execution.executeBeforeSequence(), execution.before(), afterSave, saves,
                PersistSaveChecks.PERSISTED_DOCUMENT_BASENAME);
            result.setProperty("persist.save.validation", saveValidation.diagnostic());
            if (!saveValidation.accepted()) {
                throw new IllegalStateException("SAVE lifecycle identity rejected: "
                    + saveValidation.diagnostic());
            }
            final PersistSaveChecks.PersistSaveEvent saveEvent = saveValidation.event();
            result.setProperty("persist.saveSucceeded", "true");
            result.setProperty("persist.saveEventSequence",
                Long.toString(saveEvent.sequence()));
            result.setProperty("persist.saveRequestFileName",
                saveEvent.requestFileName().orElse(""));
            result.setProperty("persist.saveRequestContentId",
                saveEvent.requestContentId().orElse(RawExportCoordination.UNAVAILABLE_VALUE));
            result.setProperty("persist.saveResultContentId",
                saveEvent.resultContentId().orElse(RawExportCoordination.UNAVAILABLE_VALUE));
            // The request filename is the pre-SAVE_AS event field and is diagnostic only.  The
            // fresh document projection is the sole saved-file evidence.
            result.setProperty("persist.savedFile",
                PersistSaveChecks.pathBasename(afterSave.relativePath()));
            result.setProperty("documentPersistence", "SAVE_AS executed + SAVE event confirmed");

        } finally {
            subscription.close();
            beforeSub.close();
            onSub.close();
        }
    }

    /**
     * Observes only the task-bound SAVE dialog.  The timer and every Swing read/action run on the
     * EDT; no worker thread enumerates windows.  Only the exact CUB3-3054 JOptionPane shape
     * proven off-EDT and rechecked live on the EDT can authorize the index-1 keep/no button.
     */
    private final class DialogAnswerWatcher implements AutoCloseable {
        private final Window boundWindow;
        private final TargetResolution.Target target;
        private final PersistSaveChecks.UnusedRawDialogProof dialogProof;
        private final BooleanSupplier taskActive;
        private final Set<Window> baseline = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Window> observed = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<String> actions = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Timer timer;
        private volatile String diagnostic = "";

        DialogAnswerWatcher(final Window boundWindow, final TargetResolution.Target target,
            final PersistSaveChecks.UnusedRawDialogProof dialogProof, final BooleanSupplier taskActive) {
            this.boundWindow = boundWindow;
            this.target = Objects.requireNonNull(target, "target");
            this.dialogProof = Objects.requireNonNull(dialogProof, "dialogProof");
            this.taskActive = Objects.requireNonNull(taskActive, "taskActive");
            timer = new Timer(250, ignored -> inspectOnEdt());
            timer.setRepeats(true);
        }

        void start() throws Exception {
            GuiRowDispatch.runOnEdt(() -> {
                baseline.addAll(Arrays.asList(Window.getWindows()));
                timer.start();
            });
        }

        String diagnostic() { return diagnostic == null ? "" : diagnostic; }

        private void inspectOnEdt() {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("save dialog inspection must run on EDT");
            }
            if (closed.get() || stopped || !taskActive.getAsBoolean()) return;
            final String boundIdentity = GuiRowDispatch.componentIdentity(boundWindow);
            PersistSaveChecks.PersistDocumentState state = null;
            PersistSaveChecks.SaveAsAdmission admission = PersistSaveChecks.SaveAsAdmission.rejected("task state unavailable");
            try {
                state = currentPersistDocumentStateOnEdt(target.identity());
                admission = PersistSaveChecks.saveAsAdmission(target.identity(), boundIdentity, state, stopped || !taskActive.getAsBoolean());
            } catch (RuntimeException unavailable) {
                diagnostic = "save dialog task state unavailable: " + unavailable;
            }
            final List<Dialog> candidates = new ArrayList<>();
            for (final Window window : Window.getWindows()) {
                if (window instanceof Dialog dialog && dialog.isShowing()
                    && dialog.isDisplayable() && !baseline.contains(window)
                    && !observed.contains(window)) {
                    candidates.add(dialog);
                }
            }
            final int candidateCount = candidates.size();
            for (final Dialog dialog : candidates) {
                observed.add(dialog);
                final List<String> buttons = new ArrayList<>();
                final List<String> messages = new ArrayList<>();
                final List<String> actionCommands = new ArrayList<>();
                PersistSaveChecks.collectDialogText(dialog, buttons, messages, 0);
                PersistSaveChecks.collectDialogActionCommands(dialog, actionCommands, 0);
                final List<JOptionPane> panes = PersistSaveChecks.findOptionPanes(dialog, 0);
                final JOptionPane pane = panes.size() == 1 ? panes.get(0) : null;
                final String fullMessage = pane == null ? "" : PersistSaveChecks.optionPaneMessageOnEdt(pane);
                if (!fullMessage.isBlank()) messages.add(0, fullMessage);
                final boolean messageProven = panes.size() == 1
                    && dialogProof.messageMatchesOnEdt(fullMessage);
                final PersistSaveChecks.DialogButtonEvidence buttonEvidence = panes.size() == 1
                    ? dialogProof.buttonsOnEdt(pane)
                    : PersistSaveChecks.DialogButtonEvidence.unavailable(
                        "dialog does not contain exactly one JOptionPane");
                if (buttonEvidence.proven()) {
                    actionCommands.add(buttonEvidence.actionToken());
                }
                final List<String> ownerChain = ownerChain(dialog);
                final String documentId = state == null ? "" : state.targetIdentity().documentId();
                final long generation = state == null ? -1L : state.targetIdentity().generation();
                final PersistSaveChecks.SaveDialogSnapshot snapshot = new PersistSaveChecks.SaveDialogSnapshot(
                    GuiRowDispatch.componentIdentity(dialog), boundIdentity, ownerChain,
                    boundWindow != null && ownerChain.contains(boundIdentity),
                    dialog.isShowing(), dialog.isDisplayable(), documentId,
                    target.identity().documentId(), generation, target.identity().generation(),
                    admission.admitted(), messageProven ? PersistSaveChecks.UNUSED_RAW_MESSAGE_KEY : "",
                    messageProven, buttonEvidence.actionToken(), buttonEvidence.proven(), messages,
                    buttons,
                    actionCommands, candidateCount, stopped || !taskActive.getAsBoolean(), closed.get());
                final PersistSaveChecks.SaveDialogDecision decision = PersistSaveChecks.classifySaveDialogForTest(snapshot);
                diagnostic = decision.diagnostic();
                actions.add("observed outcome=" + decision.outcome() + " " + snapshot.diagnostic());
                if (decision.outcome() == PersistSaveChecks.SaveDialogOutcome.KEEP) {
                    queueProvenKeepAction(dialog, pane, snapshot);
                }
            }
        }

        private void queueProvenKeepAction(final Dialog dialog, final JOptionPane pane,
            final PersistSaveChecks.SaveDialogSnapshot snapshot) {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("save dialog action must be queued on EDT");
            }
            SwingUtilities.invokeLater(() -> {
                if (closed.get() || stopped || !taskActive.getAsBoolean() || !dialog.isShowing() || !dialog.isDisplayable()) {
                    actions.add("keep action skipped after close/stop/dialog change");
                    return;
                }
                final List<Dialog> current = new ArrayList<>();
                for (final Window window : Window.getWindows()) {
                    if (window instanceof Dialog currentDialog && currentDialog.isShowing()
                        && currentDialog.isDisplayable() && !baseline.contains(window)) {
                        current.add(currentDialog);
                    }
                }
                if (current.size() != 1 || current.get(0) != dialog
                    || !ownerChain(dialog).contains(GuiRowDispatch.componentIdentity(boundWindow))) {
                    actions.add("keep action skipped after owner/candidate change");
                    return;
                }
                try {
                    final PersistSaveChecks.PersistDocumentState state = currentPersistDocumentStateOnEdt(
                        target.identity());
                    final PersistSaveChecks.SaveAsAdmission admission = PersistSaveChecks.saveAsAdmission(
                        target.identity(), componentIdentity(boundWindow), state, stopped || !taskActive.getAsBoolean());
                    if (!admission.admitted()) {
                        actions.add("keep action skipped after task identity change: "
                        + admission.diagnostic());
                        return;
                    }
                } catch (RuntimeException unavailable) {
                    actions.add("keep action skipped because task identity is unavailable: "
                        + unavailable);
                    return;
                }
                final List<JOptionPane> currentPanes = PersistSaveChecks.findOptionPanes(dialog, 0);
                if (currentPanes.size() != 1 || currentPanes.get(0) != pane) {
                    actions.add("keep action skipped because JOptionPane owner changed");
                    return;
                }
                final String fullMessage = PersistSaveChecks.optionPaneMessageOnEdt(pane);
                final PersistSaveChecks.DialogButtonEvidence currentEvidence = dialogProof.buttonsOnEdt(pane);
                if (!dialogProof.messageMatchesOnEdt(fullMessage)
                    || !currentEvidence.proven()) {
                    actions.add("keep action skipped because exact CUB3-3054 evidence changed");
                    return;
                }
                final JButton currentAction = currentEvidence.keepButton();
                if (currentAction == null || !currentAction.isShowing()
                    || !currentAction.isDisplayable() || !currentAction.isEnabled()) {
                    actions.add("keep action skipped because exact action is no longer operable");
                    return;
                }
                if (closed.get() || stopped || !taskActive.getAsBoolean()) return;
                currentAction.doClick();
                actions.add("clicked proven CUB3-3054 keep action");
            });
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                GuiRowDispatch.runOnEdt(timer::stop);
            } catch (Exception failure) {
                diagnostic = "save dialog watcher close dispatch failed: " + failure;
            }
        }
    }

    private GuiRowDispatch.GuiTargetState observeGuiTarget(final TargetResolution.Target target) throws Exception {
        final AtomicReference<GuiRowDispatch.GuiTargetState> observed = new AtomicReference<>(
            GuiRowDispatch.GuiTargetState.unobserved("target state was not observed"));
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    if (!SwingUtilities.isEventDispatchThread()) {
                        throw new IllegalStateException("GUI target observation must run on EDT");
                    }
                    final var document = context.cubism().activeDocument().orElseThrow(
                        () -> new IllegalStateException("active document unavailable"));
                    final var model = context.cubism().model().active();
                    final var relations = model.textures().relations();
                    final TargetResolution.TargetIdentity identity = PersistSaveChecks.targetIdentityFromRelationsOnEdt(
                        target.identity(), document.documentId(), model, relations);
                    final RawImageId currentRaw = new RawImageId(identity.rawId());
                    final boolean rawReplaced = relations.rawImage(currentRaw)
                        .map(dev.turboism.sdk.cubism.model.RawImageDetails::isReplaced)
                        .orElseThrow(() -> new IllegalStateException(
                            "target raw image details unavailable"));
                    observed.set(new GuiRowDispatch.GuiTargetState(true, identity, rawReplaced, ""));
                } catch (RuntimeException unavailable) {
                    observed.set(GuiRowDispatch.GuiTargetState.unobserved(unavailable.toString()));
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return GuiRowDispatch.GuiTargetState.unobserved("observation interrupted");
        } catch (Exception unavailable) {
            return GuiRowDispatch.GuiTargetState.unobserved(unavailable.toString());
        }
        return observed.get();
    }

    /**
     * Scans only the reviewed host tree-table model, resolves the selected ArtMesh by its exact
     * domain identity, and clicks the first popup item whose text equals one of {@code labels}.
     * Generic Swing row diagnostics below remain offline test seams and are not a GUI fallback.
     */
    private GuiRowDispatch.GuiClick clickContributedItem(final Set<String> labels, final int rowBudget,
        final Properties result, final TargetResolution.Target expectedTarget) throws Exception {
        int attempts = 0;
        int popups = 0;
        final Set<String> menuTexts = new LinkedHashSet<>();
        final List<String> rowDiagnostics = new ArrayList<>();
        String diagnostic = "no visible reviewed host tree-table row found";
        String boundWindowIdentity = guiBoundWindow == null ? ""
            : GuiRowDispatch.componentIdentity(guiBoundWindow);
        hostAccessByLoader.clear();
        final long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && !stopped) {
            final ExactHostRowDispatch.ReviewedTables reviewed = PersistSaveChecks.visibleReviewedTables();
            result.setProperty("gui.exactTarget.window", reviewed.windowIdentity());
            result.setProperty("gui.exactTarget.windowSelection", reviewed.diagnostic());
            final TargetResolution.GuiWindowBindingDecision currentWindow = TargetResolution.decideGuiWindowBinding(
                boundWindowIdentity, reviewed.windowIdentity(), reviewed.proven(), false);
            if (!boundWindowIdentity.isBlank()
                && currentWindow.waitForBoundWindow()) {
                diagnostic = currentWindow.diagnostic();
                result.setProperty("gui.exactTarget.windowBinding", boundWindowIdentity);
                result.setProperty("gui.exactTarget.windowBindingStatus", "WAITING");
                result.setProperty("gui.exactTarget.windowBindingDiagnostic", diagnostic);
                Thread.sleep(250);
                continue;
            }
            if (!reviewed.proven()) {
                result.setProperty("gui.exactTarget.status", "REJECTED");
                result.setProperty("gui.exactTarget.diagnostic", reviewed.diagnostic());
                return new GuiRowDispatch.GuiClick(false, reviewed.diagnostic());
            }
            final List<ExactHostRowDispatch.ExactTableRef> tables = reviewed.tables();
            if (tables.isEmpty()) {
                diagnostic = reviewed.diagnostic();
                Thread.sleep(1000);
                continue;
            }
            final ExactHostRowDispatch.ExactCapture capture = captureExactRows(tables, expectedTarget,
                reviewed.windowIdentity());
            result.setProperty("gui.exactTableDiagnostics",
                String.join("\n---\n", capture.tableDiagnostics()));
            if (!capture.hostAvailable()) {
                result.setProperty("gui.hostAccess.status", "BLOCKED");
                result.setProperty("gui.hostAccess.diagnostic", capture.diagnostic());
                return new GuiRowDispatch.GuiClick(false, "host access unavailable: " + capture.diagnostic());
            }
            result.setProperty("gui.hostAccess.status", "PREPARED_OFF_EDT");
            result.setProperty("gui.hostAccess.preflightCount",
                Integer.toString(hostAccessByLoader.size()));
            result.setProperty("gui.exactTarget.status", capture.targetStatus().name());
            if (capture.targetStatus() == ExactHostRowDispatch.ExactTargetSelectionStatus.AMBIGUOUS
                || capture.targetStatus() == ExactHostRowDispatch.ExactTargetSelectionStatus.REJECTED) {
                diagnostic = capture.diagnostic();
                result.setProperty("gui.exactTarget.status", capture.targetStatus().name());
                result.setProperty("gui.exactTarget.diagnostic", diagnostic);
                return new GuiRowDispatch.GuiClick(false, diagnostic);
            }
            if (capture.rows().isEmpty()) {
                diagnostic = capture.diagnostic();
                Thread.sleep(1000);
                continue;
            }
            final TargetResolution.GuiWindowBindingDecision targetWindow = TargetResolution.decideGuiWindowBinding(
                boundWindowIdentity, reviewed.windowIdentity(), reviewed.proven(), true);
            if (targetWindow.bindNow()) {
                boundWindowIdentity = reviewed.windowIdentity();
                guiBoundWindow = reviewed.window();
                result.setProperty("gui.exactTarget.windowBinding", boundWindowIdentity);
                result.setProperty("gui.exactTarget.windowBindingStatus", "BOUND");
                result.setProperty("gui.exactTarget.windowBindingDiagnostic",
                    targetWindow.diagnostic() + " object=" + ExactHostRowDispatch.objectIdentity(guiBoundWindow));
            }
            for (final ExactHostRowDispatch.ExactDispatchCapture captured : capture.rows()) {
                if (attempts >= rowBudget) break;
                if (stopped) return new GuiRowDispatch.GuiClick(false, "probe stopped");
                attempts++;
                final GuiRowDispatch.RowAttempt rowAttempt = ExactHostRowDispatch.exactRowAttempt(captured);
                popups += rowAttempt.popupCount();
                String menuDiagnostic = "no selected popup";
                final JPopupMenu popup = rowAttempt.popup();
                final AtomicReference<JMenuItem> foundItem = new AtomicReference<>();
                if (popup != null) {
                    final Set<String> rowMenuTexts = new LinkedHashSet<>();
                    final AtomicReference<String> popupMarker = new AtomicReference<>("");
                    SwingUtilities.invokeAndWait(() -> {
                        foundItem.set(GuiRowDispatch.findItem(popup, labels, rowMenuTexts));
                        popupMarker.set(popupMarker(popup));
                    });
                    menuTexts.addAll(rowMenuTexts);
                    menuDiagnostic = "selectedPopup=" + popupMarker.get()
                        + " item=" + (foundItem.get() == null
                            ? "none" : foundItem.get().getText())
                        + " menuTexts=" + rowMenuTexts;
                }
                final String rowDiagnostic = "attempt=" + attempts
                    + " family=" + captured.captured().identity().rowFamily()
                    + " widget=" + captured.captured().widgetIdentity() + " row="
                    + captured.captured().viewRow() + " "
                    + rowAttempt.diagnostic() + " " + menuDiagnostic;
                rowDiagnostics.add(rowDiagnostic);
                result.setProperty("gui.row." + attempts, rowDiagnostic);
                if (!rowAttempt.dispatchFailureTrace().isBlank()) {
                    result.setProperty("gui.row." + attempts
                        + ".dispatchFailureTrace", rowAttempt.dispatchFailureTrace());
                }
                context.logger().warn("EXTERNAL_PSD_EDIT_GUI_ATTEMPT " + rowDiagnostic);
                result.setProperty("gui.rowDiagnostics", String.join("\n---\n", rowDiagnostics));
                if (rowAttempt.terminalRejection()
                    || !rowAttempt.dispatchFailureTrace().isBlank()) {
                    result.setProperty("gui.exactTarget.status", "REJECTED");
                    return new GuiRowDispatch.GuiClick(false, rowAttempt.diagnostic());
                }
                final JMenuItem item = foundItem.get();
                if (popup == null) continue;
                if (item == null) {
                    GuiRowDispatch.dismissPopup();
                    continue;
                }
                result.setProperty("gui.popupRow", Integer.toString(rowAttempt.dispatchRow()));
                result.setProperty("gui.popupComponent", rowAttempt.dispatchComponent());
                GuiRowDispatch.clickItem(item);
                result.setProperty("gui.attempts", Integer.toString(attempts));
                result.setProperty("gui.popupsSeen", Integer.toString(popups));
                return new GuiRowDispatch.GuiClick(true,
                    "clicked exact ArtMesh " + captured.captured().identity().rowFamily()
                        + " row " + rowAttempt.dispatchRow());
            }
            if (attempts >= rowBudget) break;
            diagnostic = "exact ArtMesh entrances exhausted without the item; popups seen "
                + popups + " menuTexts=" + menuTexts + " rowDiagnostics="
                + String.join(" || ", rowDiagnostics);
            Thread.sleep(1000);
        }
        diagnostic = "exact ArtMesh row exhausted without the item; popups seen " + popups
            + " menuTexts=" + menuTexts
            + " rowDiagnostics=" + String.join(" || ", rowDiagnostics);
        result.setProperty("gui.attempts", Integer.toString(attempts));
        result.setProperty("gui.popupsSeen", Integer.toString(popups));
        result.setProperty("gui.menuTexts", menuTexts.toString());
        result.setProperty("gui.rowDiagnostics", String.join("\n---\n", rowDiagnostics));
        result.setProperty("gui.hierarchy", hierarchyDigest());
        return new GuiRowDispatch.GuiClick(false, diagnostic);
    }

    /** Runs exactly once per class loader for this GUI phase, always on the worker thread. */
    private ExactHostRowTarget.HostAccessPreparation prepareHostAccess(
        final ExactHostRowDispatch.ExactTableRef table) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("host access preflight must run off EDT");
        }
        final ClassLoader loader = table.modelClass().getClassLoader();
        if (hostAccessByLoader.containsKey(loader)) return hostAccessByLoader.get(loader);
        final ExactHostRowTarget.HostAccessPreparation preparation =
            ExactHostRowTarget.prepareHostAccess(table.modelClass());
        hostAccessByLoader.put(loader, preparation);
        return preparation;
    }

    private ExactHostRowDispatch.ExactCapture captureExactRows(final List<ExactHostRowDispatch.ExactTableRef> tables,
        final TargetResolution.Target expectedTarget, final String targetWindowIdentity) throws Exception {
        final List<String> tableDiagnostics = new ArrayList<>();
        if (targetWindowIdentity == null || targetWindowIdentity.isBlank()) {
            return new ExactHostRowDispatch.ExactCapture(List.of(), tableDiagnostics,
                "active target window identity is unavailable", false,
                ExactHostRowDispatch.ExactTargetSelectionStatus.REJECTED);
        }
        int availableContexts = 0;
        for (final ExactHostRowDispatch.ExactTableRef table : tables) {
            final ExactHostRowTarget.HostAccessPreparation preparation = prepareHostAccess(table);
            if (preparation.available()) {
                availableContexts++;
                tableDiagnostics.add(table.diagnostic() + " preflight=available artifact="
                    + preparation.context().artifact() + " sha256="
                    + preparation.context().artifactSha256());
            } else {
                tableDiagnostics.add(table.diagnostic() + " preflight=unavailable reason="
                    + preparation.reason());
            }
        }
        if (availableContexts == 0) {
            return new ExactHostRowDispatch.ExactCapture(List.of(), tableDiagnostics,
                String.join(" || ", tableDiagnostics), false,
                ExactHostRowDispatch.ExactTargetSelectionStatus.REJECTED);
        }

        final AtomicReference<ExactHostRowDispatch.ExactCapture> captured = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final List<ExactHostRowDispatch.ExactDispatchCapture> rows = new ArrayList<>();
            final List<String> currentDiagnostics = new ArrayList<>(tableDiagnostics);
            final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .getActiveWindow();
            if (active == null || !active.isShowing() || !active.isDisplayable()
                || !targetWindowIdentity.equals(GuiRowDispatch.componentIdentity(active))) {
                captured.set(new ExactHostRowDispatch.ExactCapture(List.of(), List.copyOf(currentDiagnostics),
                    "active target window changed before exact row capture; expected="
                        + targetWindowIdentity + " actual=" + GuiRowDispatch.componentIdentity(active), true,
                    ExactHostRowDispatch.ExactTargetSelectionStatus.NOT_FOUND));
                return;
            }
            for (final ExactHostRowDispatch.ExactTableRef table : tables) {
                final ExactHostRowTarget.HostAccessPreparation preparation =
                    hostAccessByLoader.get(table.modelClass().getClassLoader());
                if (preparation == null || !preparation.available()) continue;
                final JTable widget = table.table();
                final Object model = widget.getModel();
                if (model == null || model.getClass() != table.modelClass()) {
                    currentDiagnostics.add(table.diagnostic()
                        + " capture=table model was replaced before EDT capture");
                    continue;
                }
                if (!GuiRowDispatch.isLiveComponent(widget)) {
                    currentDiagnostics.add(table.diagnostic()
                        + " capture=table became detached before EDT capture");
                    continue;
                }
                final List<String> rowFacts = new ArrayList<>();
                for (int row = 0; row < widget.getRowCount(); row++) {
                    final ExactHostRowTarget.Resolution resolution =
                        ExactHostRowTarget.resolve(widget, row, preparation.context());
                    if (!resolution.available()) {
                        rowFacts.add("row=" + row + " unavailable=" + resolution.reason());
                        continue;
                    }
                    final ExactHostRowTarget.Target target = resolution.target();
                    rowFacts.add("row=" + row + " identity=" + target.identity()
                        + " state=" + target.state()
                        + " cell=" + target.nameCell().bounds()
                        + " click=" + target.nameClickPoint());
                    if (!target.domainId().equals(expectedTarget.artMesh().id().value())) {
                        continue;
                    }
                    final ExactHostRowDispatch.ExactCapturedRow descriptor = ExactHostRowDispatch.exactCapturedRow(widget, table, target);
                    rows.add(new ExactHostRowDispatch.ExactDispatchCapture(descriptor, preparation.context()));
                }
                currentDiagnostics.add(table.diagnostic() + " rows=" + rowFacts);
            }
            final List<ExactHostRowDispatch.ExactCapturedRow> descriptors = rows.stream()
                .map(ExactHostRowDispatch.ExactDispatchCapture::captured).toList();
            final ExactHostRowDispatch.ExactTargetSelection selection = ExactHostRowDispatch.selectExactTargetRows(descriptors,
                targetWindowIdentity, expectedTarget.artMesh().id().value());
            final IdentityHashMap<ExactHostRowDispatch.ExactCapturedRow, ExactHostRowDispatch.ExactDispatchCapture> byDescriptor =
                new IdentityHashMap<>();
            for (final ExactHostRowDispatch.ExactDispatchCapture row : rows) byDescriptor.put(row.captured(), row);
            final List<ExactHostRowDispatch.ExactDispatchCapture> ordered = new ArrayList<>();
            for (final ExactHostRowDispatch.ExactCapturedRow descriptor : selection.rows()) {
                final ExactHostRowDispatch.ExactDispatchCapture row = byDescriptor.get(descriptor);
                if (row == null) {
                    captured.set(new ExactHostRowDispatch.ExactCapture(List.of(), List.copyOf(currentDiagnostics),
                        "exact target capture mapping was lost", true,
                        ExactHostRowDispatch.ExactTargetSelectionStatus.REJECTED));
                    return;
                }
                ordered.add(row);
            }
            currentDiagnostics.add("targetSelection=" + selection.status()
                + " reason=" + selection.reason());
            final String diagnostic = selection.available()
                ? "exact target entrances=" + ordered.stream()
                    .map(ExactHostRowDispatch.ExactDispatchCapture::diagnostic).toList()
                : selection.reason();
            captured.set(new ExactHostRowDispatch.ExactCapture(List.copyOf(ordered), List.copyOf(currentDiagnostics),
                diagnostic, true, selection.status()));
        });
        return captured.get();
    }

    /**
     * Reveals the exact target row in the reviewed tree-tables so the next capture can resolve
     * it. Real projects keep ArtMesh rows inside collapsed parts and below the viewport, so the
     * row must be scrolled into view before it can be bound. View-only mutation dispatched to
     * the EDT; returns whether any table revealed the row. Failures leave the capture verdict
     * untouched.
     */
    private boolean revealTaskWindowRow(final List<ExactHostRowDispatch.ExactTableRef> tables,
        final String expectedDomainId) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("row reveal must be dispatched from off the EDT");
        }
        final AtomicBoolean revealed = new AtomicBoolean();
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (final ExactHostRowDispatch.ExactTableRef table : tables) {
                    final ExactHostRowTarget.HostAccessPreparation preparation =
                        hostAccessByLoader.get(table.modelClass().getClassLoader());
                    if (preparation == null || !preparation.available()) continue;
                    final JTable widget = table.table();
                    final Object model = widget.getModel();
                    if (model == null || model.getClass() != table.modelClass()
                        || !GuiRowDispatch.isLiveComponent(widget)) continue;
                    if (ExactHostRowTarget.revealRow(widget, preparation.context(),
                        expectedDomainId)) {
                        revealed.set(true);
                    }
                }
            });
        } catch (Exception failure) {
            return revealed.get();
        }
        return revealed.get();
    }

    /**
     * Binds the GUI session to exactly one directory created after the click and waits for a
     * complete, repeatedly identical PSD snapshot.  The file's mtime is compared as stability
     * metadata, but it is never used to choose a candidate.
     */
    private SessionFileReadiness.StablePsdSnapshot awaitSessionTempFile(final SessionFileReadiness.TempCandidateSnapshot before,
        final int timeoutSeconds, final Properties result) throws Exception {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("session PSD timeout must be positive");
        }
        return awaitStableSessionFile(before, timeoutSeconds * 1000L, result,
            () -> stopped, this::snapshotTempCandidates, Thread::sleep,
            SessionFileReadiness.PLATFORM_FILE_KEY_SUPPLIER);
    }

    /**
     * Waits for GUI import evidence. The wrapper flag and current raw are observed separately;
     * only a fresh native export of that current raw matching the bytes written to the issued
     * session file can complete the phase.
     */
    private GuiRowDispatch.GuiNativeApplicationObservation awaitGuiNativeApplication(final Properties result,
        final TargetResolution.Target target, final GuiRowDispatch.GuiTargetState before,
        final PsdValidationContent.Fingerprint written, final TempExportTracker.TempTracker tracker,
        final int timeoutSeconds) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        GuiRowDispatch.GuiTargetState last = GuiRowDispatch.GuiTargetState.unobserved("no post-click target observation");
        PsdValidationContent.Fingerprint lastFreshCurrent = null;
        String lastDiagnostic = "no stable current-raw RGB observation";
        long nextExportAt = 0L;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline && !stopped) {
            attempt++;
            last = observeGuiTarget(target);
            if (last.observed()) {
                if (!GuiRowDispatch.sameGuiTarget(before, last, target.identity())) {
                    return new GuiRowDispatch.GuiNativeApplicationObservation(false, true, last,
                        lastFreshCurrent,
                        "GUI target became stale: before=" + before + " after=" + last);
                }
                final long now = System.currentTimeMillis();
                if (now >= nextExportAt) {
                    nextExportAt = now + GuiReadinessDiagnostics.GUI_CURRENT_RAW_EXPORT_POLL_MILLIS;
                    final String attemptPrefix = "gui.currentRaw.attempt." + attempt;
                    result.setProperty(attemptPrefix + ".rawId", last.raw());
                    result.setProperty(attemptPrefix + ".wrapperReplaced",
                        Boolean.toString(last.rawReplaced()));
                    try {
                        final TargetResolution.Target currentTarget = TargetResolution.targetAtGuiIdentity(
                            target, last.identity(), last.rawReplaced());
                        lastFreshCurrent = exportTargetFingerprint(
                            result, currentTarget, tracker, attemptPrefix);
                        result.setProperty(attemptPrefix + ".status", "OBSERVED");
                        result.setProperty(attemptPrefix + ".targetRgbSha256",
                            lastFreshCurrent.sha256());
                        if (GuiRowDispatch.acceptsNativeGuiApplication(
                            before, last, target.identity(), written, lastFreshCurrent)) {
                            return new GuiRowDispatch.GuiNativeApplicationObservation(true, false, last,
                                lastFreshCurrent,
                                "fresh native current-raw RGB matches the issued session mutation;"
                                    + " wrapperReplaced=" + last.rawReplaced());
                        }
                        lastDiagnostic = "fresh current-raw RGB did not match the issued session"
                            + " mutation: raw=" + last.raw()
                            + " wrapperReplaced=" + last.rawReplaced()
                            + " expected=" + written.sha256()
                            + " actual=" + lastFreshCurrent.sha256();
                    } catch (Exception failure) {
                        result.setProperty(attemptPrefix + ".status", "UNAVAILABLE");
                        result.setProperty(attemptPrefix + ".diagnostic",
                            SessionFileReadiness.safeDiagnostic(failure.toString()));
                        lastDiagnostic = "fresh current-raw export unavailable: " + failure;
                    }
                }
            }
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) break;
            Thread.sleep(Math.min(500L, remaining));
        }
        final String diagnostic = stopped
            ? "GUI current-raw RGB wait stopped"
            : "no fresh current-raw RGB export matched the issued session mutation before timeout; "
                + lastDiagnostic;
        return new GuiRowDispatch.GuiNativeApplicationObservation(false, false, last, lastFreshCurrent, diagnostic);
    }

}
