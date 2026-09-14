package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.event.cubism.ProjectFileLifecycleEvent;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Test-only 025 external PSD edit pipeline probe.
 *
 * <p>Drives the SDK seam end to end: relation resolution → native export → runtime-issued
 * handle → observed stable saves → explicit-target native replacement → native Undo/Redo
 * → handle stop → same-binding recovery. The probe never inspects pixels and never guesses
 * file paths from plugin input; it locates the runtime allocation only for simulated
 * external writes, exactly as an external editor would see it. In the explicit persistence
 * phase only, the validation-only fixture helper decodes the target RGB fingerprint; the
 * ordinary GUI phase does not parse PSD pixels.</p>
 *
 * <p>Phases ({@code -Dturboism.validation.externalpsd.phase}): {@code pipeline} (default)
 * runs the full save/replace/undo/stop pipeline and, with {@code .persist=1}, appends a
 * mediated SAVE_AS plus lifecycle-event confirmation; {@code reopen} re-exports the validated
 * target layer from a previously saved fixture copy; {@code gui} right-clicks a real object row,
 * clicks the contributed menu item, and verifies the plugin's own session auto-imports a
 * written save.</p>
 *
 * <p>NOT covered (recorded honestly): multi-document isolation, F3/F4 fixture entities.</p>
 */
public final class ExternalPsdEditHostProbe implements TurboismPlugin {
    private PluginContext context;
    private volatile boolean stopped;
    private Thread worker;

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
        if (worker != null) worker.interrupt();
    }
    @Override public void shutdown() { disable(); }

    private void run(final String runId) {
        final Properties result = new Properties();
        final int cycles = Integer.getInteger("turboism.validation.externalpsd.cycles", 3);
        result.setProperty("schemaVersion", "1");
        result.setProperty("runId", runId);
        result.setProperty("profile", "025-external-edit-pipeline-v1");
        result.setProperty("expectedHostVersion", "5.3.02");
        result.setProperty("hostIdentityEvidence", "runner exact JAR/BAT identity and lifecycle evidence");
        result.setProperty("cycles.requested", Integer.toString(cycles));
        final String phase = System.getProperty(
            "turboism.validation.externalpsd.phase", "pipeline");
        result.setProperty("phase", phase);
        try {
            awaitReady();
            switch (phase) {
                case "reopen" -> runReopen(result);
                case "gui" -> runGui(result);
                case "pipeline" -> runPipeline(result, cycles);
                default -> throw new IllegalStateException("unknown probe phase " + phase);
            }
            result.setProperty("status", "PASS");
        } catch (Blocked blocked) {
            result.setProperty("status", "BLOCKED");
            result.setProperty("expected", blocked.expected);
            result.setProperty("actual", blocked.getMessage());
            context.logger().warn("EXTERNAL_PSD_EDIT_RESULT status=BLOCKED " + blocked.getMessage());
        } catch (Throwable error) {
            if (stopped) return;
            result.setProperty("status", "FAIL");
            result.setProperty("expected", "external edit save→replace→undo→stop→recover pipeline");
            result.setProperty("actual", error.toString());
            if (error.getCause() != null) result.setProperty("cause", error.getCause().toString());
            final StringWriter trace = new StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace));
            result.setProperty("failureTrace", trace.toString());
        }
        if (stopped) return;
        try {
            final var dir = context.paths().stateDir();
            Files.createDirectories(dir);
            final var output = new StringWriter();
            result.store(output, "025 external PSD edit pipeline probe; not full feature acceptance");
            Files.writeString(dir.resolve("external-psd-edit-result.pending"), output.toString());
            Files.move(dir.resolve("external-psd-edit-result.pending"),
                dir.resolve("external-psd-edit-result.properties"),
                StandardCopyOption.REPLACE_EXISTING);
            context.logger().info("EXTERNAL_PSD_EDIT_RESULT status=" + result.getProperty("status"));
            armExitWatchdog(dir);
            closeDefaultApplication();
            // Native save initializes JOGL/GlueGen; letting the JVM fall into ExitProcess with
            // live GL resources deadlocks DLL detach under Wine and freezes the whole session.
            // Drive the host's own close path instead so it releases the engine first.
            closeHostGracefully();
        } catch (Exception error) {
            context.logger().warn("EXTERNAL_PSD_EDIT_RESULT_WRITE_FAILED " + error);
        }
    }

    /**
     * Simulates the user closing their external editor. The task-scoped .psd association opens
     * notepad.exe as a detached session process; Proton waits for the whole wine session, so a
     * surviving editor would hold the launcher open after the JVM exits and defeat the runner's
     * graceful-exit evidence. Bounded and recorded, never gated.
     */
    private static void closeDefaultApplication() {
        try {
            final Process taskkill = new ProcessBuilder(
                "taskkill.exe", "/F", "/IM", "notepad.exe").start();
            taskkill.waitFor(15, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            // Editor teardown is best-effort; the exit watchdog still bounds the run.
        }
    }

    /**
     * Drives Cubism's own window-close path (equivalent to Alt+F4 on the document frame) so the
     * host releases its engine and GL resources before the JVM reaches ExitProcess.
     */
    private static void closeHostGracefully() {
        try {
            final Runnable close = () -> {
                Frame modelFrame = null;
                Frame cubismFrame = null;
                Frame fallbackFrame = null;
                for (final Frame frame : Frame.getFrames()) {
                    if (!frame.isVisible()) continue;
                    if (fallbackFrame == null) fallbackFrame = frame;
                    final String title = frame.getTitle();
                    if (title != null && title.contains(".cmo3")) {
                        modelFrame = frame;
                        break;
                    }
                    if (cubismFrame == null && title != null && title.contains("Cubism")) {
                        cubismFrame = frame;
                    }
                }
                final Frame frame = modelFrame != null
                    ? modelFrame : cubismFrame != null ? cubismFrame : fallbackFrame;
                if (frame != null) {
                    frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                }
            };
            if (SwingUtilities.isEventDispatchThread()) close.run();
            else SwingUtilities.invokeAndWait(close);
        } catch (Throwable ignored) {
            // The exit watchdog still bounds the run if the close event is refused.
        }
    }

    /**
     * Daemon watchdog armed just before {@code Runtime.exit}: if shutdown hooks stall, it dumps all
     * thread stacks as evidence and then halts so the run still terminates inside the exit window.
     */
    private static void armExitWatchdog(final Path stateDir) {
        try {
            Files.writeString(
                stateDir.resolve("external-psd-exit-armed.txt"),
                "watchdog armed " + java.time.Instant.now());
        } catch (Throwable ignored) {
        }
        final Thread dumper = new Thread(() -> {
            try {
                Thread.sleep(15000);
                // Per-thread stack traces use handshakes, not a global safepoint, so a thread
                // wedged in native code cannot stall the dump itself.
                final var text = new StringBuilder("JVM exit watchdog: shutdown stalled\n");
                for (Thread thread : allThreads()) {
                    text.append('\n').append('"').append(thread.getName()).append('"')
                        .append(' ').append(thread.getState());
                    for (StackTraceElement frame : thread.getStackTrace()) {
                        text.append("\n    at ").append(frame);
                    }
                }
                Files.writeString(stateDir.resolve("external-psd-exit-threads.txt"), text);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
            }
        }, "external-psd-exit-dump");
        final Thread killer = new Thread(() -> {
            try {
                Thread.sleep(35000);
            } catch (InterruptedException interrupted) {
                return;
            }
            // Runtime.halt would block on the shutdown lock while a wedged hook still holds it;
            // destroying our own process bypasses the JVM shutdown machinery entirely.
            ProcessHandle.current().destroyForcibly();
        }, "external-psd-exit-watchdog");
        dumper.setDaemon(true);
        killer.setDaemon(true);
        dumper.start();
        killer.start();
    }

    private static List<Thread> allThreads() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while (group.getParent() != null) group = group.getParent();
        Thread[] threads = new Thread[group.activeCount() + 16];
        int count;
        while ((count = group.enumerate(threads, true)) == threads.length) {
            threads = new Thread[threads.length * 2];
        }
        final List<Thread> alive = new ArrayList<>(count);
        for (int index = 0; index < count; index++) alive.add(threads[index]);
        return alive;
    }

    /** Full save→replace→undo→stop→recover pipeline plus optional persist tail. */
    private void runPipeline(final Properties result, final int cycles) throws Exception {
        if (cycles < 1) throw new IllegalArgumentException("cycles must be at least 1");
        result.setProperty("realEditorApplication",
            "default-application launch recorded; OPENED requires the task .psd association");
        final Target target = resolveTarget(result);
        final boolean persistValidation = "1".equals(
            System.getProperty("turboism.validation.externalpsd.persist"));
        final TempTracker tracker = new TempTracker();
        try {
            final TrackedExport primary = exportTracked(result, target.raw(), tracker, "baseline");
            final Path tempFile = primary.path();
            final byte[] baselineBytes = Files.readAllBytes(tempFile);
            final PsdValidationContent.Fingerprint baselineFingerprint = persistValidation
                ? targetFingerprint(baselineBytes, "pipeline baseline") : null;
            result.setProperty("tempFile.discovered", Boolean.toString(tempFile.getFileName()
                .toString().equals("external-edit.psd")));
            result.setProperty("baseline.bytes", Integer.toString(baselineBytes.length));
            result.setProperty("baseline.sha256", sha256(baselineBytes));
            result.setProperty("baseline.revisionIssued", "true");
            if (persistValidation) {
                recordTargetFingerprint(result, "persist.baselineTargetRgb", baselineFingerprint);
                result.setProperty("persist.baselineTargetRgbSha256", baselineFingerprint.sha256());
            }

            if (persistValidation) {
                // A second independent native export is required before any external edit. It is
                // not a copy of the first export and is stopped before the save subscription starts.
                final TrackedExport second = exportTracked(
                    result, target.raw(), tracker, "baselineSecond");
                try {
                    final PsdValidationContent.Fingerprint secondFingerprint = targetFingerprint(
                        Files.readAllBytes(second.path()), "pipeline second baseline");
                    recordTargetFingerprint(
                        result, "persist.baselineSecondTargetRgb", secondFingerprint);
                    result.setProperty("persist.baselineSecondTargetRgbSha256",
                        secondFingerprint.sha256());
                    requireStableBaseline(baselineFingerprint, secondFingerprint);
                } finally {
                    tracker.stop(second, result);
                }
            }

            final PsdEditFile file = primary.file();
            final Deque<PsdFileRevision> revisions = new ArrayDeque<>();
            final Registration subscription = file.observeSaves(revision -> {
                synchronized (revisions) { revisions.add(revision); revisions.notifyAll(); }
            });
            try {
                assertNoRevision(revisions, 1500,
                    "baseline revision must not be replayed to subscribers");
                result.setProperty("baseline.replayed", "false");

                final PsdFileOperationResult opened = file.openInDefaultApplication()
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
                result.setProperty("defaultApplication.status", opened.status().name());
                result.setProperty("defaultApplication.diagnostic", opened.diagnostic());

                final Mutation marker = runSaveCycles(
                    result, file, target, tempFile, revisions, cycles, persistValidation);
                final PsdValidationContent.Fingerprint postBeforeUndo = persistValidation
                    ? exportTargetFingerprint(result, target.raw(), tracker, "postBeforeUndo") : null;
                if (persistValidation) {
                    result.setProperty("persist.postBeforeUndoTargetRgbSha256",
                        postBeforeUndo.sha256());
                    requireChangedPost(baselineFingerprint, postBeforeUndo);
                }

                runCorruptedSave(result, file, target, tempFile, revisions);
                runUndoRedo(result, target, tracker, baselineFingerprint, postBeforeUndo);
                assertNoReplayAfterIdle(result, revisions);
                runStopAndRecovery(result, primary, target, tempFile, revisions, tracker);
                recordEnvironment(result);
                if (marker != null) {
                    // Retained as diagnostic coordinates only; the persistence gate uses the
                    // decoded target RGB fingerprint below, never this legacy marker.
                    result.setProperty("persist.markerLayer", Integer.toString(marker.layer()));
                    result.setProperty("persist.markerOffset", Integer.toString(marker.nameOffset()));
                    result.setProperty("persist.markerChar", Integer.toString(marker.letter()));
                }
                if (persistValidation) {
                    runPersistTail(result, target, tracker, baselineFingerprint, postBeforeUndo);
                } else {
                    result.setProperty("documentPersistence",
                        "NOT_TESTED: persist tail not requested");
                }
            } finally {
                subscription.close();
            }
        }
        finally {
            // This is also the leak guard for an export whose path discovery or later assertion
            // failed. A failed stop is recorded and prevents any quarantine PASS claim.
            tracker.stopAll(result);
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
        final Target target = resolveTarget(result);
        final String expectedTarget = System.getProperty(
            "turboism.validation.externalpsd.postEditTargetRgbSha256", "");
        if (!isCanonicalSha256(expectedTarget)) {
            throw new IllegalStateException(
                "reopen requires a canonical lowercase postEditTargetRgbSha256");
        }
        final TempTracker tracker = new TempTracker();
        try {
            final TrackedExport exported = exportTracked(result, target.raw(), tracker, "reopen");
            final byte[] bytes = Files.readAllBytes(exported.path());
            final PsdValidationContent.Fingerprint actual = targetFingerprint(
                bytes, "reopen target layer");
            result.setProperty("reopen.layerCount",
                Integer.toString(layerNameRanges(bytes).size()));
            result.setProperty("reopen.bytes", Integer.toString(bytes.length));
            result.setProperty("reopen.sha256", sha256(bytes));
            result.setProperty("reopen.imageSha256", imageDataSha256(bytes));
            result.setProperty("reopen.expectedTargetRgbSha256", expectedTarget);
            result.setProperty("reopen.targetRgbSha256", actual.sha256());
            recordTargetFingerprint(result, "reopen.targetRgb", actual);
            final String expectedFile = System.getProperty(
                "turboism.validation.externalpsd.postEditSha256", "");
            result.setProperty("reopen.fileShaMatched",
                Boolean.toString(!expectedFile.isBlank()
                    && expectedFile.equals(result.getProperty("reopen.sha256"))));
            requireReopenTarget(expectedTarget, actual);
        } finally {
            tracker.stopAll(result);
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
        final Target target = resolveTarget(result);
        result.setProperty("gui.menuLabel", System.getProperty(
            "turboism.validation.externalpsd.menuLabel", "Edit PSD Externally"));
        final GuiTargetState before = observeGuiTarget(target);
        recordGuiTargetState(result, "before", before);
        result.setProperty("gui.generationBefore", Long.toString(before.generation()));
        recordGuiTargetState(result, "after",
            GuiTargetState.unobserved("auto-import not attempted"));
        if (!before.observed()) {
            throw new Blocked("target-specific GUI replacement observation is available",
                "gui.before unavailable: " + before.diagnostic());
        }
        if (before.binding().isBlank() || before.generation() < 0 || before.raw().isBlank()
            || !target.raw().value().equals(before.raw())) {
            throw new Blocked("GUI target remains bound to the resolved document and raw image",
                "gui.before target identity is unavailable or stale: " + before);
        }
        if (before.rawReplaced()) {
            throw new Blocked("fixture target starts with isReplaced=false",
                "gui.before.rawReplaced=true; no available new replacement observation");
        }
        final Path marker = tempMarker();

        final Set<String> labels = new LinkedHashSet<>(List.of(result.getProperty("gui.menuLabel"),
            "Edit PSD Externally",
            "外部编辑 PSD", "外部編輯 PSD",
            "外部でPSDを編集", "외부에서 PSD 편집"));
        final GuiClick click = clickContributedItem(labels, 64, result);
        if (!click.clicked()) {
            throw new Blocked("context menu with the contributed item was reachable",
                click.diagnostic());
        }
        result.setProperty("gui.menuPresent", "true");
        result.setProperty("gui.itemClicked", "true");

        final Path sessionFile = awaitSessionTempFile(marker, 90);
        result.setProperty("gui.sessionFile", sessionFile.getParent().getFileName().toString());
        final byte[] mutated = mutateLayerName(Files.readAllBytes(sessionFile), 900)
            .orElseThrow(() -> new IllegalStateException("session PSD has no mutable layer name"));
        Files.write(sessionFile, mutated);

        final AutoImportObservation observation = awaitAutoImport(target, before, 90);
        result.setProperty("gui.autoImportApplied", Boolean.toString(observation.applied()));
        recordGuiTargetState(result, "after", observation.after());
        result.setProperty("gui.generationAfter", Long.toString(observation.after().generation()));
        if (!observation.applied()) {
            if (observation.stale()) {
                throw new Blocked("GUI target binding, generation and raw image remain stable",
                    observation.diagnostic());
            }
            throw new IllegalStateException(
                "plugin session did not auto-import the written save: "
                    + observation.diagnostic());
        }
        result.setProperty("expected", "context-menu item starts a session and auto-imports saves");
        result.setProperty("actual", "menu click → session file → written save auto-applied");
    }

    /** Blocked signal: the evidence condition could not be reached, distinct from a failure. */
    private static final class Blocked extends IllegalStateException {
        final String expected;
        Blocked(final String expected, final String actual) {
            super(actual);
            this.expected = expected;
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

    private Target resolveTarget(final Properties result) throws Exception {
        final AtomicReference<Target> found = new AtomicReference<>();
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
                found.set(pickTarget(relations).orElseThrow(() ->
                    new IllegalStateException("No ArtMesh resolves to a current raw image")));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException("Target resolution failed", failure.get());
        final Target target = found.get();
        result.setProperty("relation.artMesh", target.artMesh().id().value());
        result.setProperty("relation.modelImage", target.modelImage().value());
        result.setProperty("relation.raw", target.raw().value());
        result.setProperty("relation.rawReplaced",
            Boolean.toString(target.rawReplaced));
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
        return target;
    }

    private Optional<TextureRelationsSnapshot> relationsOf(final Target target) {
        final AtomicReference<TextureRelationsSnapshot> snapshot = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> snapshot.set(
                context.cubism().model().active().textures().relations()));
        } catch (Exception unavailable) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get());
    }

    private Optional<Target> pickTarget(final TextureRelationsSnapshot relations) {
        for (final ArtMeshTextureInputs mesh : relations.artMeshInputs()) {
            if (mesh.currentInputIndex().isEmpty()) continue;
            final TextureInputBinding input = mesh.inputs().get(mesh.currentInputIndex().getAsInt());
            if (input.kind() != TextureInputBinding.Kind.MODEL_IMAGE
                || !input.isResolved() || input.modelImageId().isEmpty()) continue;
            final var relation = relations.modelImage(input.modelImageId().orElseThrow());
            if (relation.isEmpty() || relation.orElseThrow().currentRawImageId().isEmpty()) continue;
            final RawImageId raw = relation.orElseThrow().currentRawImageId().orElseThrow();
            final boolean replaced = relations.rawImage(raw)
                .map(dev.turboism.sdk.cubism.model.RawImageDetails::isReplaced).orElse(false);
            return Optional.of(new Target(mesh, input.modelImageId().orElseThrow(), raw, replaced));
        }
        return Optional.empty();
    }

    private static final String TEMP_DIRECTORY_PREFIX = "turboism-psd-";
    private static final String TEMP_FILE_NAME = "external-edit.psd";

    private Path tempRoot() throws Exception {
        final String configured = System.getProperty("java.io.tmpdir", "");
        if (configured.isBlank()) throw new IllegalStateException("java.io.tmpdir is blank");
        final Path configuredPath = Path.of(configured);
        if (!configuredPath.isAbsolute()) {
            throw new IllegalStateException("java.io.tmpdir must be absolute");
        }
        requireNoSymlinkPath(configuredPath, "java.io.tmpdir");
        if (Files.isSymbolicLink(configuredPath)
            || !Files.isDirectory(configuredPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("java.io.tmpdir is not a real directory");
        }
        final Path root = configuredPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (root.equals(Path.of("/"))) throw new IllegalStateException("java.io.tmpdir is root");
        return root;
    }

    private Path tempMarker() throws Exception {
        final Path marker = tempRoot().resolve(
            ".external-psd-probe-" + ProcessHandle.current().pid());
        return Files.writeString(marker, Long.toString(System.nanoTime()));
    }

    /** Captures the direct, task-scoped runtime allocation set before an export. */
    private TempCandidateSnapshot snapshotTempCandidates() throws Exception {
        final Path tmp = tempRoot();
        final Set<Path> directories = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(tmp)) {
            for (final Path candidate : stream.toList()) {
                final String name = candidate.getFileName().toString();
                if (!name.startsWith(TEMP_DIRECTORY_PREFIX)) continue;
                if (Files.isSymbolicLink(candidate)
                    || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(
                        "temporary PSD candidate is not a real directory: " + candidate);
                }
                requireNoSymlinkPath(candidate, "temporary PSD candidate");
                directories.add(candidate.toAbsolutePath().normalize());
            }
        }
        return new TempCandidateSnapshot(tmp, directories);
    }

    private Path locateNewTempFile(final TempCandidateSnapshot before, final Path marker,
        final Properties result, final String label) throws Exception {
        final TempCandidateSnapshot after = snapshotTempCandidates();
        result.setProperty("export." + label + ".candidateBefore",
            Integer.toString(before.directories().size()));
        result.setProperty("export." + label + ".candidateAfter",
            Integer.toString(after.directories().size()));
        final Path directory = uniqueNewTempCandidate(before.directories(), after.directories());
        if (!directory.getParent().equals(after.root())) {
            throw new IllegalStateException("temporary PSD candidate escaped its temp root");
        }
        final boolean markerAuxiliary = modified(directory) >= modified(marker);
        result.setProperty("export." + label + ".markerAfterCandidate",
            Boolean.toString(markerAuxiliary));
        result.setProperty("export." + label + ".directory", directory.toString());
        return requireTrackedTempFile(directory, after.root());
    }

    /** Resolves an export only when exactly one allocation directory was added. */
    static Path uniqueNewTempCandidate(final Set<Path> before, final Set<Path> after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("temporary candidate sets are required");
        }
        final Set<Path> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        if (added.size() != 1) {
            throw new IllegalStateException(
                "expected exactly one new temporary PSD candidate, found " + added.size());
        }
        return added.iterator().next();
    }

    /** Validates the one runtime PSD file inside a candidate directory without following links. */
    static Path requireTrackedTempFile(final Path directory, final Path expectedTempRoot)
        throws Exception {
        if (directory == null || expectedTempRoot == null) {
            throw new IllegalArgumentException("temporary candidate paths are required");
        }
        final Path root = requireOwnedDirectory(expectedTempRoot, "temporary root");
        final Path candidate = directory.toAbsolutePath().normalize();
        if (!candidate.getParent().equals(root)
            || !candidate.getFileName().toString().startsWith(TEMP_DIRECTORY_PREFIX)) {
            throw new IllegalStateException("temporary PSD candidate escaped its temp root: "
                + candidate);
        }
        if (Files.isSymbolicLink(candidate)
            || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("temporary PSD candidate is not a real directory: "
                + candidate);
        }
        requireNoSymlinkPath(candidate, "temporary PSD candidate");
        final Path file = candidate.resolve(TEMP_FILE_NAME);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("temporary PSD candidate has no regular PSD file: " + file);
        }
        requireNoSymlinkPath(file, "temporary PSD file");
        return file.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireNoSymlinkPath(final Path path, final String label) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException(label + " contains a symlink: " + current);
            }
            current = current.getParent();
        }
    }

    private static long modified(final Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception error) { return 0; }
    }

    /** GUI-only session discovery; persist exports use candidate-set binding above. */
    private static boolean isNewerThan(final Path path, final Path marker) {
        try {
            return Files.getLastModifiedTime(path)
                .compareTo(Files.getLastModifiedTime(marker)) > 0;
        } catch (Exception error) { return false; }
    }

    private PsdExportResult export(final Properties result, final RawImageId raw) throws Exception {
        final AtomicReference<CompletionStage<PsdExportResult>> stage = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (stopped) throw new IllegalStateException("Probe stopped");
                stage.set(context.cubism().model().active().textures().exportRawImagePsd(raw));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new IllegalStateException("Export invocation failed", failure.get());
        final PsdExportResult exported = stage.get().toCompletableFuture().get(120, TimeUnit.SECONDS);
        result.setProperty("export.status", exported.status().name());
        result.setProperty("export.diagnostic", exported.diagnostic());
        if (exported.status() != PsdExportResult.Status.EXPORTED
            || exported.file().isEmpty() || exported.initialRevision().isEmpty()) {
            throw new IllegalStateException("Native export did not issue an edit handle: "
                + exported.status() + " " + exported.diagnostic());
        }
        return exported;
    }

    private TrackedExport exportTracked(final Properties result, final RawImageId raw,
        final TempTracker tracker, final String label) throws Exception {
        final TempCandidateSnapshot before = snapshotTempCandidates();
        final Path marker = tempMarker();
        final PsdExportResult exported = export(result, raw);
        final TrackedExport tracked = tracker.register(
            label, exported.file().orElseThrow(), before.root());
        final Path path = locateNewTempFile(before, marker, result, label);
        tracked.setPath(path);
        result.setProperty("export." + label + ".path", path.toString());
        return tracked;
    }

    private PsdValidationContent.Fingerprint exportTargetFingerprint(final Properties result,
        final RawImageId raw, final TempTracker tracker, final String label) throws Exception {
        final TrackedExport exported = exportTracked(result, raw, tracker, label);
        try {
            return targetFingerprint(Files.readAllBytes(exported.path()), label);
        } finally {
            tracker.stop(exported, result);
        }
    }

    private static PsdValidationContent.Fingerprint targetFingerprint(final byte[] bytes,
        final String label) {
        try {
            return PsdValidationContent.targetLayerRgbFingerprint(bytes);
        } catch (PsdValidationContent.ValidationException invalid) {
            throw new IllegalStateException(label + " is not a supported validation PSD: "
                + invalid.getMessage(), invalid);
        }
    }

    private static void recordTargetFingerprint(final Properties result, final String prefix,
        final PsdValidationContent.Fingerprint fingerprint) {
        final PsdValidationContent.Bounds bounds = fingerprint.bounds();
        result.setProperty(prefix + ".sha256", fingerprint.sha256());
        result.setProperty(prefix + ".bounds",
            bounds.top() + "," + bounds.left() + "," + bounds.bottom() + "," + bounds.right());
        result.setProperty(prefix + ".width", Integer.toString(fingerprint.width()));
        result.setProperty(prefix + ".height", Integer.toString(fingerprint.height()));
        result.setProperty(prefix + ".channelIds", fingerprint.channelIds().toString());
    }

    private static boolean isCanonicalSha256(final String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /** Requires the native history move that the following fresh export is meant to observe. */
    static void requireHistoryMoved(
        final dev.turboism.sdk.cubism.history.HistoryMoveResult move, final String direction) {
        final String name = direction == null || direction.isBlank() ? "history" : direction;
        if (move == null || move.outcome()
            != dev.turboism.sdk.cubism.history.HistoryMoveResult.Outcome.MOVED) {
            throw new IllegalStateException(name + " did not move native history: "
                + (move == null ? "null result" : move.outcome()));
        }
    }

    /**
     * Final persistence evidence gate owned by the probe before it reports a successful phase.
     * The runner repeats the same contract while binding the result to its Supervisor job.
     */
    static void requirePersistEvidence(final Properties result) {
        if (result == null) throw new IllegalStateException("persistence evidence is missing");
        final String baseline = result.getProperty("persist.baselineTargetRgbSha256");
        final String second = result.getProperty("persist.baselineSecondTargetRgbSha256");
        final String post = result.getProperty("persist.postEditTargetRgbSha256");
        if (!isCanonicalSha256(baseline) || !isCanonicalSha256(second)
            || !isCanonicalSha256(post)) {
            throw new IllegalStateException(
                "persistence target RGB hashes are missing or not canonical lowercase SHA-256");
        }
        if (!baseline.equals(second)) {
            throw new IllegalStateException(
                "independent native baseline target RGB fingerprints are unstable");
        }
        if (baseline.equals(post)) {
            throw new IllegalStateException(
                "fresh native post-edit target RGB fingerprint did not change");
        }
        requireExactProperty(result, "persist.targetContentChanged", "true");
        requireExactProperty(result, "persist.saveSucceeded", "true");
        requireExactProperty(result, "persist.tempQuarantine.status", "MOVED");
        requireExactProperty(result, "persist.tempQuarantine.taskOwned", "true");
        requireExactProperty(result, "persist.tempQuarantine.sourceMissing", "true");
    }

    private static void requireExactProperty(final Properties result, final String key,
        final String expected) {
        if (!expected.equals(result.getProperty(key))) {
            throw new IllegalStateException(
                "persistence evidence " + key + " must be " + expected);
        }
    }

    static void requireStableBaseline(final PsdValidationContent.Fingerprint first,
        final PsdValidationContent.Fingerprint second) {
        if (first == null || second == null || !isCanonicalSha256(first.sha256())
            || !isCanonicalSha256(second.sha256())) {
            throw new IllegalStateException("native baseline target RGB evidence is missing or invalid");
        }
        if (!first.equals(second)) {
            throw new IllegalStateException(
                "independent native baseline target RGB fingerprints are unstable");
        }
    }

    static void requireChangedPost(final PsdValidationContent.Fingerprint baseline,
        final PsdValidationContent.Fingerprint post) {
        if (baseline == null || post == null || !isCanonicalSha256(baseline.sha256())
            || !isCanonicalSha256(post.sha256())) {
            throw new IllegalStateException("native post-edit target RGB evidence is missing or invalid");
        }
        if (baseline.sha256().equals(post.sha256())) {
            throw new IllegalStateException(
                "fresh native post-edit target RGB fingerprint did not change");
        }
    }

    static void requireReopenTarget(final String expected,
        final PsdValidationContent.Fingerprint actual) {
        if (!isCanonicalSha256(expected) || actual == null
            || !isCanonicalSha256(actual.sha256())) {
            throw new IllegalStateException(
                "reopen target RGB evidence is missing or not canonical lowercase SHA-256");
        }
        if (!expected.equals(actual.sha256())) {
            throw new IllegalStateException(
                "reopened document target RGB fingerprint does not match the saved post-edit fingerprint");
        }
    }

    private Mutation runSaveCycles(final Properties result, final PsdEditFile file, final Target target,
        final Path tempFile, final Deque<PsdFileRevision> revisions, final int cycles,
        final boolean validateTargetContent) throws Exception {
        Mutation lastMutation = null;
        for (int i = 1; i <= cycles; i++) {
            final byte[] current = Files.readAllBytes(tempFile);
            final Mutation mutation = mutationFor(current, i)
                .orElseThrow(() -> new IllegalStateException("PSD layer-name mutation failed"));
            byte[] mutated = applyMutation(current, mutation);
            final boolean finalCycle = validateTargetContent && isFinalRgbMutationCycle(i, cycles);
            if (finalCycle) {
                // The content mutation is deliberately performed once, only for the last valid
                // cycle. The overlap path below applies another name-only mutation to this same
                // byte array, so its final write still carries the RGB inversion.
                mutated = PsdValidationContent.invertTargetLayerRgb(mutated);
            }
            result.setProperty("cycle." + i + ".targetRgbMutation",
                finalCycle ? "INVERTED_ONCE" : "UNCHANGED");
            lastMutation = mutation;
            final long writeStart = System.nanoTime();
            if (i == 2) {
                // Atomic-rename save: write sibling then move over the issued file.
                final Path sibling = tempFile.resolveSibling("external-edit.psd.tmp");
                Files.write(sibling, mutated);
                Files.move(sibling, tempFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } else if (i == 3) {
                // Overlapping save: a second distinct write lands inside the debounce
                // window; latest-pending must win and publish a single revision.
                final Mutation secondMutation = mutationFor(mutated, i + 100)
                    .orElseThrow(() -> new IllegalStateException("Second mutation failed"));
                final byte[] second = applyMutation(mutated, secondMutation);
                Files.write(tempFile, mutated);
                Files.write(tempFile, second);
                result.setProperty("cycle." + i + ".overlapFinalContainsRgbMutation",
                    Boolean.toString(finalCycle));
            } else {
                Files.write(tempFile, mutated);
            }
            final PsdFileRevision revision = awaitRevision(revisions, 20);
            final long detectedMs = (System.nanoTime() - writeStart) / 1_000_000;
            final long replaceStart = System.nanoTime();
            final PsdReplaceResult replaced = replace(file, target.raw(), revision);
            final long replaceMs = (System.nanoTime() - replaceStart) / 1_000_000;
            final String prefix = "cycle." + i + ".";
            result.setProperty(prefix + "revisionDeliveredMs", Long.toString(detectedMs));
            result.setProperty(prefix + "replaceMs", Long.toString(replaceMs));
            result.setProperty(prefix + "status", replaced.status().name());
            result.setProperty(prefix + "diagnostic", replaced.diagnostic());
            result.setProperty(prefix + "after", replaced.after().map(RawImageId::value).orElse(""));
            result.setProperty(prefix + "consumed", Boolean.toString(replaced.consumedRevision().isPresent()));
            if (replaced.status() != PsdReplaceResult.Status.APPLIED
                || replaced.consumedRevision().isEmpty() || replaced.after().isEmpty()) {
                throw new IllegalStateException("Explicit-target replacement not applied in cycle " + i
                    + ": " + replaced.status() + " " + replaced.diagnostic());
            }
            final String observed = currentRawOnEdt(target.modelImage().value());
            result.setProperty(prefix + "currentRawAfter", observed);
            result.setProperty("applied.currentRaw", observed);
            // Drain: an overlapping save may publish a second revision after the lane
            // settles; leftovers must not leak into the corrupted-save assertion.
            Thread.sleep(1500);
            synchronized (revisions) {
                result.setProperty(prefix + "extraRevisions", Integer.toString(revisions.size()));
                revisions.clear();
            }
        }
        return lastMutation;
    }

    static boolean isFinalRgbMutationCycle(final int cycle, final int cycles) {
        if (cycles < 1 || cycle < 1 || cycle > cycles) {
            throw new IllegalArgumentException("cycle must be within a positive cycle count");
        }
        return cycle == cycles;
    }

    private void runCorruptedSave(final Properties result, final PsdEditFile file, final Target target,
        final Path tempFile, final Deque<PsdFileRevision> revisions) throws Exception {
        Files.write(tempFile, "not-a-psd-corrupted-save".getBytes(StandardCharsets.UTF_8));
        final PsdFileRevision revision = awaitRevision(revisions, 20);
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

    private void runUndoRedo(final Properties result, final Target target,
        final TempTracker tracker, final PsdValidationContent.Fingerprint baselineFingerprint,
        final PsdValidationContent.Fingerprint postFingerprint) throws Exception {
        final String appliedRaw = result.getProperty("applied.currentRaw", target.raw().value());
        final AtomicReference<dev.turboism.sdk.cubism.history.HistoryMoveResult> undo =
            new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> undo.set(context.cubism().history().undo(1)));
        result.setProperty("undo.outcome", undo.get().outcome().name());
        requireHistoryMoved(undo.get(), "undo");
        final String afterUndo = currentRawOnEdt(target.modelImage().value());
        result.setProperty("undo.currentRaw", afterUndo);
        if (baselineFingerprint != null) {
            final PsdValidationContent.Fingerprint undoFingerprint = exportTargetFingerprint(
                result, target.raw(), tracker, "undo");
            recordTargetFingerprint(result, "undo.targetRgb", undoFingerprint);
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
        requireHistoryMoved(redo.get(), "redo");
        final String afterRedo = currentRawOnEdt(target.modelImage().value());
        result.setProperty("redo.currentRaw", afterRedo);
        if (!appliedRaw.equals(afterRedo)) {
            throw new IllegalStateException("Redo did not restore the applied raw identity");
        }
        if (postFingerprint != null) {
            final PsdValidationContent.Fingerprint redoFingerprint = exportTargetFingerprint(
                result, target.raw(), tracker, "redo");
            recordTargetFingerprint(result, "redo.targetRgb", redoFingerprint);
            result.setProperty("redo.targetRgbSha256", redoFingerprint.sha256());
            if (!postFingerprint.equals(redoFingerprint)) {
                throw new IllegalStateException(
                    "Redo did not restore the post-edit target RGB fingerprint");
            }
        }
    }

    private void assertNoReplayAfterIdle(final Properties result,
        final Deque<PsdFileRevision> revisions) throws Exception {
        final int before;
        synchronized (revisions) { before = revisions.size(); }
        Thread.sleep(3000);
        final int after;
        synchronized (revisions) { after = revisions.size(); }
        result.setProperty("idle.revisionsBefore", Integer.toString(before));
        result.setProperty("idle.revisionsAfter", Integer.toString(after));
        if (after != before) {
            throw new IllegalStateException("New revisions appeared without an external save");
        }
    }

    private void runStopAndRecovery(final Properties result, final TrackedExport primary,
        final Target target, final Path tempFile, final Deque<PsdFileRevision> revisions,
        final TempTracker tracker)
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
        final TrackedExport again = exportTracked(result, target.raw(), tracker, "recovery");
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

    private void assertNoRevision(final Deque<PsdFileRevision> revisions, final long millis,
        final String message) throws Exception {
        synchronized (revisions) {
            final long deadline = System.currentTimeMillis() + millis;
            while (revisions.isEmpty() && System.currentTimeMillis() < deadline) {
                revisions.wait(200);
            }
            if (!revisions.isEmpty()) throw new IllegalStateException(message);
        }
    }

    private PsdFileRevision awaitRevision(final Deque<PsdFileRevision> revisions,
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

    private static String sha256(final byte[] bytes) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /**
     * Returns a copy of {@code psd} with one ASCII letter substituted into a layer's Pascal
     * name, deterministic per {@code cycle}. The file stays structurally valid: same length,
     * same layout, only name bytes change. Returns empty when no mutable name exists.
     */
    static Optional<byte[]> mutateLayerName(final byte[] psd, final int cycle) {
        return mutationFor(psd, cycle).map(mutation -> applyMutation(psd, mutation));
    }

    /** Deterministic per-cycle mutation coordinates over the parsed layer-name table. */
    static Optional<Mutation> mutationFor(final byte[] psd, final int cycle) {
        final List<int[]> names = layerNameRanges(psd);
        if (names.isEmpty()) return Optional.empty();
        final int layer = (cycle - 1) % names.size();
        final int[] range = names.get(layer);
        final int nameOffset = range[1] > 1 ? (cycle - 1) / names.size() % range[1] : 0;
        byte replacement = (byte) ('a' + (cycle - 1) % 26);
        if (replacement == psd[range[0] + nameOffset]) {
            replacement = (byte) (replacement == 'a' ? 'b' : 'a');
        }
        return Optional.of(new Mutation(layer, nameOffset, (char) replacement));
    }

    private static byte[] applyMutation(final byte[] psd, final Mutation mutation) {
        final int[] range = layerNameRanges(psd).get(mutation.layer());
        final byte[] copy = psd.clone();
        copy[range[0] + mutation.nameOffset()] = (byte) mutation.letter();
        return copy;
    }

    /** (layerIndex, byteOffsetWithinName, letter) of one name-byte substitution. */
    record Mutation(int layer, int nameOffset, char letter) {
    }

    /** (nameByteStart, nameByteLength) per layer record; empty on any structural anomaly. */
    static List<int[]> layerNameRanges(final byte[] psd) {
        final List<int[]> names = new ArrayList<>();
        try {
            if (psd.length < 30 || psd[0] != '8' || psd[1] != 'B'
                || psd[2] != 'P' || psd[3] != 'S') return names;
            final ByteBuffer buffer = ByteBuffer.wrap(psd).order(ByteOrder.BIG_ENDIAN);
            buffer.position(26);
            buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
            buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
            final int layerMaskStart = buffer.position();
            final int layerMaskLength = buffer.getInt();
            if (layerMaskLength < 4) return names;
            buffer.position(layerMaskStart + 4);
            final int layerInfoLength = buffer.getInt();
            if (layerInfoLength < 2) return names;
            int count = Math.abs(buffer.getShort());
            for (int layer = 0; layer < count && buffer.remaining() > 0; layer++) {
                buffer.position(buffer.position() + 16);
                final int channels = Short.toUnsignedInt(buffer.getShort());
                buffer.position(buffer.position() + channels * 6);
                buffer.position(buffer.position() + 12); // blend sig+key, opacity, clipping, flags, filler
                final int extraStart = buffer.position();
                final int extraLength = buffer.getInt();
                final int extraDataStart = extraStart + 4;
                buffer.position(extraDataStart);
                final int maskLength = buffer.getInt();
                buffer.position(buffer.position() + maskLength);
                final int rangesLength = buffer.getInt();
                buffer.position(buffer.position() + rangesLength);
                final int nameStart = buffer.position();
                final int nameLength = Byte.toUnsignedInt(buffer.get());
                if (nameLength > 0) names.add(new int[] {nameStart + 1, nameLength});
                // All layer records are contiguous; channel image data follows the last record.
                buffer.position(extraDataStart + extraLength);
            }
        } catch (RuntimeException malformed) {
            names.clear();
        }
        return names;
    }

    /** SHA-256 over the PSD image-data section (everything after the layer-and-mask record). */
    static String imageDataSha256(final byte[] psd) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(psd).order(ByteOrder.BIG_ENDIAN);
        if (psd.length < 30 || psd[0] != '8' || psd[1] != 'B'
            || psd[2] != 'P' || psd[3] != 'S') {
            throw new IllegalStateException("not a PSD: cannot locate image data section");
        }
        buffer.position(26);
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        return sha256(java.util.Arrays.copyOfRange(psd, buffer.position(), psd.length));
    }

    private void recordEnvironment(final Properties result) throws Exception {
        final Runtime runtime = Runtime.getRuntime();
        result.setProperty("env.processors", Integer.toString(runtime.availableProcessors()));
        result.setProperty("env.heapMaxBytes", Long.toString(runtime.maxMemory()));
        result.setProperty("env.heapUsedBytes",
            Long.toString(runtime.totalMemory() - runtime.freeMemory()));
        final long start = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> { });
        result.setProperty("env.edtDispatchMs",
            Long.toString((System.nanoTime() - start) / 1_000_000));
    }

    /**
     * Mediated persistence: a fixed-grant write handle → typed SAVE_AS → the native
     * {@code saveDocument} hook must surface a {@link ProjectFileOperationType#SAVE} After
     * event. The granted target lives outside the task fixture copy, so the runner's
     * fixture-unchanged guarantee still holds.
     */
    private void runPersistTail(final Properties result, final Target target,
        final TempTracker tracker, final PsdValidationContent.Fingerprint baselineFingerprint,
        final PsdValidationContent.Fingerprint postBeforeUndo) throws Exception {
        result.setProperty("persist.saveSucceeded", "false");
        result.setProperty("persist.targetContentChanged", "false");
        result.setProperty("persist.tempQuarantine.status", "NOT_ATTEMPTED");
        result.setProperty("persist.tempQuarantine.taskOwned", "false");
        result.setProperty("persist.tempQuarantine.sourceMissing", "false");

        // Undo/Redo must leave the same native post state that will be saved. Re-export it once
        // immediately before SAVE_AS, so the persistence gate does not trust staged PSD bytes or
        // a history/raw identity alone.
        final PsdValidationContent.Fingerprint preSavePost = exportTargetFingerprint(
            result, target.raw(), tracker, "postBeforeSave");
        recordTargetFingerprint(result, "persist.preSavePostEditTargetRgb", preSavePost);
        result.setProperty("persist.preSavePostEditTargetRgbSha256", preSavePost.sha256());
        if (!postBeforeUndo.equals(preSavePost)) {
            throw new IllegalStateException(
                "fresh post-edit target RGB fingerprint changed before SAVE_AS");
        }
        requireChangedPost(baselineFingerprint, preSavePost);

        final List<ProjectFileLifecycleEvent.After> saves = new CopyOnWriteArrayList<>();
        final AtomicInteger beforeEvents = new AtomicInteger();
        final AtomicInteger onEvents = new AtomicInteger();
        final Registration subscription = context.eventBus().subscribe(
            ProjectFileLifecycleEvent.After.class,
            event -> {
                if (event.operation().operation() == ProjectFileOperationType.SAVE) {
                    saves.add(event);
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
                .toCompletableFuture().get(60, TimeUnit.SECONDS);
            result.setProperty("persist.grant.status", granted.status().name());
            final UserFileHandle handle = granted.handle().orElseThrow(() ->
                new IllegalStateException("No write grant issued: " + granted.status()));
            final Set<java.awt.Window> baselineWindows = Set.of(java.awt.Window.getWindows());
            final DialogAnswerWatcher watcher = new DialogAnswerWatcher(baselineWindows);
            final EditorCommandResult saved;
            try {
                saved = context.editorCommands().execute(
                    new EditorFileCommandRequest(
                        EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REPLACE_EXISTING));
            } finally {
                watcher.close();
            }
            result.setProperty("persist.saveAs.status", saved.status().name());
            result.setProperty("persist.saveAs.executed", Boolean.toString(saved.executed()));
            if (!watcher.actions.isEmpty()) {
                result.setProperty("persist.dialogActions", watcher.actions.toString());
            }
            if (!saved.executed()) {
                // A blocked native save leaves its modal dialog open; record which windows
                // are up so the failure identifies the blocker instead of a bare FAILED.
                result.setProperty("persist.openWindows", describeWindows());
                throw new IllegalStateException("SAVE_AS did not execute: " + saved.status());
            }
            final long deadline = System.currentTimeMillis() + 15_000;
            while (saves.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            result.setProperty("persist.saveEvents", Integer.toString(saves.size()));
            result.setProperty("persist.beforeEvents", Integer.toString(beforeEvents.get()));
            result.setProperty("persist.onEvents", Integer.toString(onEvents.get()));
            if (saves.isEmpty()) {
                throw new IllegalStateException("SAVE lifecycle event not observed");
            }
            final var saveResult = saves.get(0).result();
            result.setProperty("persist.saveSucceeded",
                Boolean.toString(saveResult.succeeded()));
            result.setProperty("persist.savedFile",
                saveResult.request().fileName().orElse(""));
            if (!saveResult.succeeded()) {
                throw new IllegalStateException("SAVE lifecycle completed without success");
            }
            result.setProperty("persist.saveSucceeded", "true");
            result.setProperty("documentPersistence", "SAVE_AS executed + SAVE event confirmed");

            // A second fresh native export after SAVE_AS is the durable post fingerprint. The
            // legacy full-file/composite values are retained only as optional diagnostics.
            final TrackedExport postExport = exportTracked(
                result, target.raw(), tracker, "postAfterSave");
            try {
                final byte[] postBytes = Files.readAllBytes(postExport.path());
                final PsdValidationContent.Fingerprint post = targetFingerprint(
                    postBytes, "persist post-save target");
                recordTargetFingerprint(result, "persist.postEditTargetRgb", post);
                result.setProperty("persist.postEditTargetRgbSha256", post.sha256());
                result.setProperty("persist.postEditBytes", Integer.toString(postBytes.length));
                result.setProperty("persist.postEditSha256", sha256(postBytes));
                result.setProperty("persist.postEditImageSha256", imageDataSha256(postBytes));
                if (!preSavePost.equals(post)) {
                    throw new IllegalStateException(
                        "fresh post-save target RGB fingerprint differs from pre-SAVE_AS state");
                }
                requireChangedPost(baselineFingerprint, post);
                result.setProperty("persist.targetContentChanged", "true");
            } finally {
                tracker.stop(postExport, result);
            }
            tracker.quarantine(result, context.paths().stateDir());
            requirePersistEvidence(result);
        } finally {
            subscription.close();
            beforeSub.close();
            onSub.close();
        }
    }

    private record TempCandidateSnapshot(Path root, Set<Path> directories) {
        private TempCandidateSnapshot {
            root = root.toAbsolutePath().normalize();
            directories = Set.copyOf(directories);
        }
    }

    private static final class TrackedExport {
        private final String label;
        private final PsdEditFile file;
        private Path path;
        private PsdFileOperationResult stopResult;
        private Throwable stopFailure;
        private boolean stopAttempted;

        private TrackedExport(final String label, final PsdEditFile file) {
            this.label = label;
            this.file = file;
        }

        private PsdEditFile file() { return file; }
        private Path path() { return path; }
        private void setPath(final Path path) { this.path = path; }
        private Path directory() { return path == null ? null : path.getParent(); }
    }

    private static final class TempTracker {
        private final List<TrackedExport> exports = new ArrayList<>();
        private Path tempRoot;

        private TrackedExport register(final String label, final PsdEditFile file,
            final Path root) {
            if (label == null || label.isBlank() || file == null || root == null) {
                throw new IllegalArgumentException("tracked export identity is incomplete");
            }
            if (exports.stream().anyMatch(export -> export.label.equals(label))) {
                throw new IllegalArgumentException("duplicate tracked export label " + label);
            }
            final Path normalizedRoot = root.toAbsolutePath().normalize();
            if (tempRoot == null) tempRoot = normalizedRoot;
            if (!tempRoot.equals(normalizedRoot)) {
                throw new IllegalStateException("native exports changed task temp root");
            }
            final TrackedExport tracked = new TrackedExport(label, file);
            exports.add(tracked);
            return tracked;
        }

        private PsdFileOperationResult stop(final TrackedExport tracked,
            final Properties result) throws Exception {
            if (!exports.contains(tracked)) {
                throw new IllegalArgumentException("unregistered export handle");
            }
            if (tracked.stopAttempted) {
                if (tracked.stopFailure != null) {
                    throw asException(tracked.stopFailure);
                }
                if (tracked.stopResult == null) {
                    throw new IllegalStateException("tracked stop has no result");
                }
                return tracked.stopResult;
            }
            tracked.stopAttempted = true;
            try {
                final PsdFileOperationResult stopped = tracked.file.stop()
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
                tracked.stopResult = stopped;
                result.setProperty("export." + tracked.label + ".stopStatus",
                    stopped.status().name());
                result.setProperty("export." + tracked.label + ".stopDiagnostic",
                    stopped.diagnostic());
                if (stopped.status() != PsdFileOperationResult.Status.STOPPED) {
                    throw new IllegalStateException("export handle did not stop: "
                        + tracked.label + " status=" + stopped.status());
                }
                return stopped;
            } catch (Exception failure) {
                tracked.stopFailure = failure;
                result.setProperty("export." + tracked.label + ".stopStatus", "FAILED");
                result.setProperty("export." + tracked.label + ".stopDiagnostic",
                    failure.toString());
                throw failure;
            }
        }

        private void stopAll(final Properties result) {
            for (final TrackedExport tracked : exports) {
                try {
                    stop(tracked, result);
                } catch (Throwable failure) {
                    result.setProperty("export." + tracked.label + ".stopStatus", "FAILED");
                    result.setProperty("export." + tracked.label + ".stopDiagnostic",
                        failure.toString());
                }
            }
        }

        private boolean allStopped() {
            return !exports.isEmpty() && exports.stream().allMatch(export ->
                export.stopAttempted && export.stopResult != null
                    && export.stopResult.status() == PsdFileOperationResult.Status.STOPPED);
        }

        private void quarantine(final Properties result, final Path taskRoot) throws Exception {
            if (!allStopped()) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.diagnostic",
                    "not all native export handles stopped");
                throw new IllegalStateException("cannot quarantine while an export handle is active");
            }
            final String runId = System.getProperty("turboism.validation.externalpsd.runId", "");
            if (!runId.matches("[A-Za-z0-9._-]+")) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.diagnostic", "invalid task runId");
                throw new IllegalStateException("task runId is not safe for quarantine naming");
            }
            final Path owner = taskRoot.toAbsolutePath().normalize();
            final Path destination = owner.resolve("external-psd-quarantine-" + runId);
            result.setProperty("persist.tempQuarantine.root", destination.toString());
            for (int index = 0; index < exports.size(); index++) {
                final TrackedExport tracked = exports.get(index);
                final String prefix = "persist.tempQuarantine.item." + (index + 1);
                result.setProperty(prefix + ".source",
                    tracked.directory() == null ? "" : tracked.directory().toString());
                result.setProperty(prefix + ".target",
                    tracked.directory() == null ? ""
                        : destination.resolve(tracked.directory().getFileName()).toString());
                result.setProperty(prefix + ".stopStatus", tracked.stopResult.status().name());
                result.setProperty(prefix + ".moved", "false");
            }
            try {
                final QuarantineReport report = moveTrackedTempDirectories(
                    exports.stream().map(TrackedExport::directory).toList(),
                    tempRoot, owner, destination);
                for (int index = 0; index < report.entries().size(); index++) {
                    final QuarantineEntry entry = report.entries().get(index);
                    final String prefix = "persist.tempQuarantine.item." + (index + 1);
                    result.setProperty(prefix + ".target", entry.target().toString());
                    result.setProperty(prefix + ".moved", Boolean.toString(entry.moved()));
                }
                result.setProperty("persist.tempQuarantine.status", report.status());
                result.setProperty("persist.tempQuarantine.taskOwned",
                    Boolean.toString(report.taskOwned()));
                result.setProperty("persist.tempQuarantine.sourceMissing",
                    Boolean.toString(report.sourceMissing()));
            } catch (Exception failure) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.taskOwned", "false");
                result.setProperty("persist.tempQuarantine.sourceMissing", "false");
                result.setProperty("persist.tempQuarantine.diagnostic", failure.toString());
                throw failure;
            }
        }

        private static Exception asException(final Throwable failure) {
            if (failure instanceof Exception exception) return exception;
            if (failure instanceof Error error) throw error;
            return new IllegalStateException(failure);
        }
    }

    /** Atomic, no-overwrite move used by the persist tail and its offline path-boundary tests. */
    static QuarantineReport moveTrackedTempDirectories(final List<Path> sources,
        final Path expectedTempRoot, final Path taskRoot, final Path destinationRoot)
        throws Exception {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("tracked temporary directories are required");
        }
        final Path tempRoot = requireOwnedDirectory(expectedTempRoot, "temporary root");
        final Path owner = requireOwnedDirectory(taskRoot, "task quarantine root");
        final Path destination = destinationRoot.toAbsolutePath().normalize();
        if (!destination.getParent().equals(owner)) {
            throw new IllegalStateException("quarantine destination escaped task root");
        }
        requireNoSymlinkPath(destination.getParent(), "quarantine destination");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(destination)) {
            throw new IllegalStateException("quarantine destination already exists: " + destination);
        }

        final Set<Path> uniqueSources = new LinkedHashSet<>();
        final List<QuarantineEntry> entries = new ArrayList<>(sources.size());
        for (final Path sourceValue : sources) {
            if (sourceValue == null) throw new IllegalArgumentException("null tracked source");
            final Path source = sourceValue.toAbsolutePath().normalize();
            if (!source.getParent().equals(tempRoot)
                || !source.getFileName().toString().startsWith(TEMP_DIRECTORY_PREFIX)) {
                throw new IllegalStateException("tracked source escaped task temp root: " + source);
            }
            if (!uniqueSources.add(source)) {
                throw new IllegalStateException("duplicate tracked source: " + source);
            }
            if (Files.isSymbolicLink(source)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("tracked source is not a real directory: " + source);
            }
            requireNoSymlinkPath(source, "tracked source");
            try (Stream<Path> descendants = Files.walk(source)) {
                descendants.forEach(path -> {
                    if (Files.isSymbolicLink(path)) {
                        throw new IllegalStateException("tracked source contains a symlink: " + path);
                    }
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException("tracked source contains an unsupported item: "
                            + path);
                    }
                });
            }
            final Path target = destination.resolve(source.getFileName());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
                throw new IllegalStateException("quarantine target already exists: " + target);
            }
            entries.add(new QuarantineEntry(source, target, false));
        }

        Files.createDirectory(destination);
        for (final QuarantineEntry entry : entries) {
            try {
                Files.move(entry.source(), entry.target(), StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                throw new IllegalStateException(
                    "atomic quarantine move is unsupported for " + entry.source(), unsupported);
            }
        }
        boolean sourceMissing = true;
        final List<QuarantineEntry> moved = new ArrayList<>(entries.size());
        for (final QuarantineEntry entry : entries) {
            final boolean missing = !Files.exists(entry.source(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(entry.source());
            sourceMissing &= missing;
            if (!Files.isDirectory(entry.target(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(entry.target())) {
                throw new IllegalStateException("quarantine target is not a real directory: "
                    + entry.target());
            }
            moved.add(new QuarantineEntry(entry.source(), entry.target(), true));
        }
        if (!sourceMissing) throw new IllegalStateException("not all tracked sources are missing");
        return new QuarantineReport("MOVED", true, true, List.copyOf(moved));
    }

    private static Path requireOwnedDirectory(final Path value, final String label) {
        if (value == null) throw new IllegalArgumentException(label + " is null");
        final Path path = value.toAbsolutePath().normalize();
        if (path.equals(Path.of("/")) || Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(label + " is not a real directory: " + path);
        }
        requireNoSymlinkPath(path, label);
        return path;
    }

    record QuarantineEntry(Path source, Path target, boolean moved) {
    }

    record QuarantineReport(String status, boolean taskOwned, boolean sourceMissing,
        List<QuarantineEntry> entries) {
        QuarantineReport {
            status = status == null ? "FAILED" : status;
            entries = List.copyOf(entries);
        }
    }

    /**
     * Snapshot of currently showing top-level windows taken from a non-EDT thread: class,
     * title, and for dialogs the visible button labels and text so a blocking modal can be
     * identified from evidence alone.
     */
    private static String describeWindows() {
        final StringBuilder text = new StringBuilder();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(window.getClass().getSimpleName());
            final String title = window instanceof java.awt.Dialog dialog ? dialog.getTitle()
                : window instanceof java.awt.Frame frame ? frame.getTitle() : null;
            if (title != null && !title.isBlank()) {
                text.append('\'').append(title).append('\'');
            }
            if (window instanceof java.awt.Dialog) {
                final List<String> buttons = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                collectDialogText(window, buttons, labels, 0);
                if (!buttons.isEmpty()) {
                    text.append(" buttons=").append(buttons);
                }
                if (!labels.isEmpty()) {
                    text.append(" text=").append(labels);
                }
            }
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    private static void collectDialogText(final java.awt.Component component,
        final List<String> buttons, final List<String> labels, final int depth) {
        if (depth > 6) {
            return;
        }
        if (component instanceof javax.swing.AbstractButton button
            && button.getText() != null && !button.getText().isBlank()) {
            buttons.add(button.getText().trim());
        }
        if (component instanceof javax.swing.JLabel label
            && label.getText() != null && !label.getText().isBlank()) {
            labels.add(label.getText().replaceAll("\\s+", " ").trim());
        }
        if (component instanceof javax.swing.text.JTextComponent textComponent
            && textComponent.getText() != null && !textComponent.getText().isBlank()) {
            labels.add(textComponent.getText().replaceAll("\\s+", " ").trim());
        }
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                collectDialogText(child, buttons, labels, depth + 1);
            }
        }
    }

    /**
     * Polls for JDialogs that appear while a mediated save runs on the EDT. Native save flows
     * can open a modal confirmation (e.g. unused raw images); without an answer the EDT task
     * times out. Each new dialog's content is recorded; a button is clicked only when its
     * label unambiguously means "keep / do not remove" so the save proceeds untouched.
     */
    private static final class DialogAnswerWatcher implements AutoCloseable {
        private static final List<String> KEEP_LABELS = List.of(
            "いいえ", "不删除", "保留", "否", "No(N)", "No", "Keep", "Keep all");
        private final Set<java.awt.Window> baseline;
        final List<String> actions = new CopyOnWriteArrayList<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Thread thread;

        DialogAnswerWatcher(final Set<java.awt.Window> baseline) {
            this.baseline = baseline;
            thread = new Thread(this::poll, "external-psd-dialog-watcher");
            thread.setDaemon(true);
            thread.start();
        }

        private void poll() {
            final Set<java.awt.Window> answered = new HashSet<>();
            while (!stopped.get()) {
                for (final java.awt.Window window : java.awt.Window.getWindows()) {
                    if (!(window instanceof java.awt.Dialog dialog)
                        || baseline.contains(window) || !dialog.isShowing()
                        || answered.contains(window)) {
                        continue;
                    }
                    answered.add(window);
                    final List<String> buttons = new ArrayList<>();
                    final List<String> labels = new ArrayList<>();
                    collectDialogText(dialog, buttons, labels, 0);
                    actions.add("dialog title='" + dialog.getTitle()
                        + "' buttons=" + buttons + " text=" + labels);
                    final javax.swing.AbstractButton keep =
                        findButton(dialog, KEEP_LABELS, 0);
                    if (keep != null) {
                        // Modal dialogs run a nested event pump, so a queued click still runs.
                        SwingUtilities.invokeLater(() -> keep.doClick());
                        actions.add("clicked '" + keep.getText().trim() + "'");
                    }
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static javax.swing.AbstractButton findButton(final java.awt.Component component,
            final List<String> wanted, final int depth) {
            if (depth > 6 || !(component instanceof java.awt.Container container)) {
                return null;
            }
            for (final java.awt.Component child : container.getComponents()) {
                if (child instanceof javax.swing.AbstractButton button
                    && button.getText() != null
                    && wanted.stream().anyMatch(w -> button.getText().trim().equals(w))) {
                    return button;
                }
                final javax.swing.AbstractButton nested = findButton(child, wanted, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        @Override
        public void close() {
            stopped.set(true);
            try {
                thread.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private GuiTargetState observeGuiTarget(final Target target) throws Exception {
        final AtomicReference<GuiTargetState> observed = new AtomicReference<>(
            GuiTargetState.unobserved("target state was not observed"));
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    final var relations = context.cubism().model().active().textures().relations();
                    if (!relations.isAvailable()) throw new IllegalStateException(
                        "relations unavailable");
                    final RawImageId currentRaw = relations.modelImage(target.modelImage())
                        .flatMap(image -> image.currentRawImageId())
                        .orElseThrow(() -> new IllegalStateException(
                            "target model image has no current raw image"));
                    final boolean rawReplaced = relations.rawImage(currentRaw)
                        .map(dev.turboism.sdk.cubism.model.RawImageDetails::isReplaced)
                        .orElseThrow(() -> new IllegalStateException(
                            "target raw image details unavailable"));
                    observed.set(new GuiTargetState(true, relations.binding(),
                        relations.generation(), currentRaw.value(), rawReplaced, ""));
                } catch (RuntimeException unavailable) {
                    observed.set(GuiTargetState.unobserved(unavailable.toString()));
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return GuiTargetState.unobserved("observation interrupted");
        } catch (Exception unavailable) {
            return GuiTargetState.unobserved(unavailable.toString());
        }
        return observed.get();
    }

    private static void recordGuiTargetState(final Properties result, final String phase,
        final GuiTargetState state) {
        final String prefix = "gui." + phase + ".";
        result.setProperty(prefix + "observed", Boolean.toString(state.observed()));
        result.setProperty(prefix + "binding", state.binding());
        result.setProperty(prefix + "generation", Long.toString(state.generation()));
        result.setProperty(prefix + "raw", state.raw());
        result.setProperty(prefix + "rawReplaced", Boolean.toString(state.rawReplaced()));
        if (!state.diagnostic().isBlank()) {
            result.setProperty(prefix + "diagnostic", state.diagnostic());
        }
    }

    static boolean acceptsAutoImport(final GuiTargetState before,
        final GuiTargetState after, final String expectedRaw) {
        return before.observed() && after.observed()
            && !before.rawReplaced() && after.rawReplaced()
            && before.binding().equals(after.binding())
            && before.generation() == after.generation()
            && expectedRaw.equals(before.raw())
            && expectedRaw.equals(after.raw());
    }

    private static boolean sameGuiTarget(final GuiTargetState before,
        final GuiTargetState after, final String expectedRaw) {
        return before.observed() && after.observed()
            && before.binding().equals(after.binding())
            && before.generation() == after.generation()
            && expectedRaw.equals(before.raw())
            && expectedRaw.equals(after.raw());
    }

    /**
     * Scans visible row widgets ({@link JTree} rows and {@link javax.swing.JList} cells — the
     * object list is a CList wrapping a JList in this host), dispatches a real popup-trigger
     * right-click on each, and clicks the first menu item whose text equals one of {@code labels}.
     * Returns after the first successful click or when the row budget is exhausted.
     */
    private GuiClick clickContributedItem(final Set<String> labels, final int rowBudget,
        final Properties result) throws Exception {
        int attempts = 0;
        int popups = 0;
        final Set<String> menuTexts = new LinkedHashSet<>();
        final List<String> rowDiagnostics = new ArrayList<>();
        String diagnostic = "no visible row widget found";
        final long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && !stopped) {
            final List<RowWidget> widgets = visibleRowWidgets();
            if (!widgets.isEmpty()) {
                for (final RowWidget widget : widgets) {
                    final List<CapturedRow> rows = widget.captureRows();
                    for (final CapturedRow captured : rows) {
                        if (attempts >= rowBudget) break;
                        if (stopped) return new GuiClick(false, "probe stopped");
                        attempts++;
                        final RowAttempt rowAttempt = widget.rightClick(captured);
                        popups += rowAttempt.popupCount();
                        String menuDiagnostic = "no selected popup";
                        final JPopupMenu popup = rowAttempt.popup();
                        final AtomicReference<JMenuItem> foundItem = new AtomicReference<>();
                        if (popup != null) {
                            final Set<String> rowMenuTexts = new LinkedHashSet<>();
                            final AtomicReference<String> popupMarker = new AtomicReference<>("");
                            SwingUtilities.invokeAndWait(() -> {
                                foundItem.set(findItem(popup, labels, rowMenuTexts));
                                popupMarker.set(popupMarker(popup));
                            });
                            menuTexts.addAll(rowMenuTexts);
                            menuDiagnostic = "selectedPopup=" + popupMarker.get()
                                + " item=" + (foundItem.get() == null
                                    ? "none" : foundItem.get().getText())
                                + " menuTexts=" + rowMenuTexts;
                        }
                        final String rowDiagnostic = "attempt=" + attempts
                            + " widget=" + widget.name() + " row=" + captured.row() + " "
                            + rowAttempt.diagnostic() + " " + menuDiagnostic;
                        rowDiagnostics.add(rowDiagnostic);
                        result.setProperty("gui.row." + attempts, rowDiagnostic);
                        if (!rowAttempt.dispatchFailureTrace().isBlank()) {
                            result.setProperty("gui.row." + attempts
                                + ".dispatchFailureTrace", rowAttempt.dispatchFailureTrace());
                        }
                        context.logger().warn("EXTERNAL_PSD_EDIT_GUI_ATTEMPT " + rowDiagnostic);
                        result.setProperty("gui.rowDiagnostics", String.join("\n---\n", rowDiagnostics));
                        final JMenuItem item = foundItem.get();
                        if (popup == null) continue;
                        if (item == null) {
                            dismissPopup();
                            continue;
                        }
                        result.setProperty("gui.popupRow", Integer.toString(rowAttempt.dispatchRow()));
                        result.setProperty("gui.popupComponent", rowAttempt.dispatchComponent());
                        clickItem(item);
                        result.setProperty("gui.attempts", Integer.toString(attempts));
                        result.setProperty("gui.popupsSeen", Integer.toString(popups));
                        return new GuiClick(true,
                            "clicked row " + rowAttempt.dispatchRow());
                    }
                    diagnostic = "rows exhausted without the item; popups seen " + popups
                        + " widgets=" + widgets.stream().map(RowWidget::name).toList()
                        + " menuTexts=" + menuTexts
                        + " rowDiagnostics=" + String.join(" || ", rowDiagnostics);
                }
            }
            Thread.sleep(1000);
        }
        result.setProperty("gui.attempts", Integer.toString(attempts));
        result.setProperty("gui.popupsSeen", Integer.toString(popups));
        result.setProperty("gui.menuTexts", menuTexts.toString());
        result.setProperty("gui.rowDiagnostics", String.join("\n---\n", rowDiagnostics));
        result.setProperty("gui.hierarchy", hierarchyDigest());
        return new GuiClick(false, diagnostic);
    }

    /** A selectable row widget the host popup can be raised on: JTree row, table cell, or JList cell. */
    private sealed interface RowWidget {
        String name();
        List<CapturedRow> captureRows() throws Exception;
        RowAttempt rightClick(CapturedRow row) throws Exception;
    }

    private record TreeWidget(JTree tree) implements RowWidget {
        public String name() { return tree.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                tree, RowKind.TREE)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    private record TableWidget(javax.swing.JTable table) implements RowWidget {
        public String name() { return table.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                table, RowKind.TABLE)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    private record ListWidget(javax.swing.JList<?> list) implements RowWidget {
        public String name() { return list.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                list, RowKind.LIST)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    private static final int MAX_ROW_DISPATCH_ATTEMPTS = 3;

    @FunctionalInterface
    interface RowDispatchAttempt {
        boolean retry(int attemptNumber) throws Exception;
    }

    /** Runs the same bounded retry policy used by the live row dispatcher. */
    static int runBoundedRowDispatches(final RowDispatchAttempt operation) throws Exception {
        if (operation == null) throw new IllegalArgumentException("row dispatch operation is required");
        int attempts = 0;
        while (attempts < MAX_ROW_DISPATCH_ATTEMPTS) {
            attempts++;
            if (!operation.retry(attempts)) break;
        }
        return attempts;
    }

    private static RowAttempt rightClickRow(final CapturedRow captured) throws Exception {
        return rowAttempt(dismissPopup(), captured);
    }

    /**
     * Runs one captured row through a bounded stale-widget recovery loop. Every invocation of
     * {@link #dispatchCapturedRow(CapturedRow, boolean)} is made on the EDT; returning to the
     * worker between retries gives a replacement table a chance to become visible.
     */
    private static RowAttempt rowAttempt(PopupAttempt attempt, final CapturedRow captured)
        throws Exception {
        final AtomicReference<RowDispatchResult> rowOutcome = new AtomicReference<>();
        final AtomicReference<RightClickDispatchException> dispatchFailure = new AtomicReference<>();
        final AtomicReference<PopupAttempt> popupAttempt = new AtomicReference<>(attempt);
        final List<String> retryReasons = new ArrayList<>();
        final int attempts = runBoundedRowDispatches(attemptNumber -> {
            rowOutcome.set(null);
            try {
                final boolean triggerOnPress = popupTriggerOnPress(
                    System.getProperty("os.name", ""));
                SwingUtilities.invokeAndWait(() -> rowOutcome.set(
                    dispatchCapturedRow(captured, triggerOnPress)));
            } catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof RightClickDispatchException failure) {
                    dispatchFailure.set(failure);
                    return false;
                }
                throw wrapped;
            }

            final RowDispatchResult result = rowOutcome.get();
            if (result == null || !result.retryable()) return false;
            retryReasons.add("attempt=" + attemptNumber + " phase=" + result.phase()
                + " reason=" + result.reason());
            if (attemptNumber < MAX_ROW_DISPATCH_ATTEMPTS) {
                final PopupAttempt retryPopup = dismissPopup();
                popupAttempt.set(mergePopupAttempts(popupAttempt.get(), retryPopup));
            }
            return true;
        });
        final PopupCapture popup = awaitPopup(popupAttempt.get());
        final RowDispatchResult rowResult = rowOutcome.get();
        final RightClickDispatchException failure = dispatchFailure.get();
        final String dispatchDiagnostic = rowResult != null
            ? rowResult.dispatch().diagnostic()
            : failure != null
                ? failure.dispatch().diagnostic()
                : "not-dispatched";
        final String failureTrace = failure == null ? "" : stackTrace(failure);
        final String diagnostic = "capture=" + captured.diagnostic()
            + " attempts=" + attempts
            + " dispatchRow=" + (rowResult == null ? -1 : rowResult.row())
            + " dispatchComponent=" + (rowResult == null ? "" : rowResult.component())
            + " retryLimit=" + MAX_ROW_DISPATCH_ATTEMPTS
            + " retryReasons=" + retryReasons
            + " " + dispatchDiagnostic
            + (failureTrace.isBlank() ? "" : " dispatchExceptionTrace=" + failureTrace)
            + " popupCount=" + popup.popupCount()
            + " popupMaxCount=" + popup.maxPopupCount()
            + " " + popup.diagnostic();
        return new RowAttempt(popup.popup(), popup.popupCount(), diagnostic, failureTrace,
            rowResult == null ? -1 : rowResult.row(),
            rowResult == null ? "" : rowResult.component());
    }

    private static RowDispatchResult dispatchCapturedRow(final CapturedRow captured,
        final boolean triggerOnPress) {
        RowResolution selection = resolveActiveRow(captured);
        if (!selection.available()) {
            return RowDispatchResult.retry(captured, "before-left", selection.reason());
        }

        // Preserve the old tree behavior, but re-resolve because expansion itself can rebuild the
        // host widget before the selection click.
        if (selection.row().component() instanceof JTree tree) {
            tree.expandRow(selection.row().row());
            selection = resolveActiveRow(captured);
            if (!selection.available()) {
                return RowDispatchResult.retry(captured, "before-left", selection.reason());
            }
        }

        final AtomicReference<RowResolution> afterSelection = new AtomicReference<>();
        final CurrentRow selected = selection.row();
        final String prefix = rowDispatchPrefix(captured, selected);
        try {
            final RightClickDispatch dispatch = dispatchRightClickAfterSelection(
                selected.component(), centerX(selected.bounds()), centerY(selected.bounds()),
                triggerOnPress, prefix, () -> {
                    final RowResolution resolved = resolveActiveRow(captured);
                    afterSelection.set(resolved);
                    if (!resolved.available()) return null;
                    final CurrentRow row = resolved.row();
                    return new DispatchTarget(row.component(), centerX(row.bounds()),
                        centerY(row.bounds()));
                });
            final CurrentRow dispatched = afterSelection.get().row();
            return RowDispatchResult.success(dispatch, dispatched);
        } catch (RowRelocationException retry) {
            final RowResolution resolved = afterSelection.get();
            final String reason = resolved == null
                ? retry.getMessage() : resolved.reason();
            return RowDispatchResult.retry(captured, "after-left", reason);
        }
    }

    private static String rowDispatchPrefix(final CapturedRow captured,
        final CurrentRow selection) {
        return "captureWidget=" + captured.widgetIdentity()
            + " captureRow=" + captured.row()
            + " captureWindow=" + captured.windowIdentity()
            + " captureRowKey=" + captured.rowKeyDiagnostic()
            + " captureBounds=" + captured.bounds()
            + " selectionWidget=" + componentIdentity(selection.component())
            + " selectionState=" + componentState(selection.component())
            + " selectionRow=" + selection.row()
            + " selectionBounds=" + boundsMarker(selection.bounds()) + " ";
    }

    private static int centerX(final Rectangle bounds) {
        return bounds.x + bounds.width / 2;
    }

    private static int centerY(final Rectangle bounds) {
        return bounds.y + bounds.height / 2;
    }

    private static void dispatchLeftClick(final Component target, final int x, final int y) {
        final long now = System.currentTimeMillis();
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
    }

    static RightClickDispatch dispatchRightClick(final Component target, final int x, final int y) {
        return dispatchRightClick(target, x, y,
            popupTriggerOnPress(System.getProperty("os.name", "")));
    }

    static RightClickDispatch dispatchRightClick(final Component target, final int x, final int y,
        final boolean triggerOnPress) {
        dispatchLeftClick(target, x, y);
        return dispatchRightClickEvents(target, x, y, triggerOnPress, "");
    }

    /**
     * Dispatches the right-click after the selection click has been completed. The caller supplies
     * a fresh target, so a host table replacement between the two phases cannot receive the right
     * click merely because it was the object captured before selection.
     */
    static RightClickDispatch dispatchRightClickAfterSelection(final Component selectionTarget,
        final int selectionX, final int selectionY, final boolean triggerOnPress,
        final java.util.function.Supplier<DispatchTarget> refreshedTarget) {
        return dispatchRightClickAfterSelection(selectionTarget, selectionX, selectionY,
            triggerOnPress, "", refreshedTarget);
    }

    private static RightClickDispatch dispatchRightClickAfterSelection(
        final Component selectionTarget, final int selectionX, final int selectionY,
        final boolean triggerOnPress, final String diagnosticPrefix,
        final java.util.function.Supplier<DispatchTarget> refreshedTarget) {
        try {
            dispatchLeftClick(selectionTarget, selectionX, selectionY);
        } catch (RuntimeException failure) {
            final RightClickDispatch dispatch = new RightClickDispatch(diagnosticPrefix
                + "selectionTarget=" + componentState(selectionTarget)
                + " selectionCoordinates=(" + selectionX + ',' + selectionY + ')');
            throw new RightClickDispatchException(dispatch,
                List.of(new DispatchFailure("MOUSE_LEFT", failure)));
        }
        final DispatchTarget target = refreshedTarget.get();
        if (target == null || target.component() == null) {
            throw new RowRelocationException(
                "active row could not be revalidated after the selection click");
        }
        return dispatchRightClickEvents(target.component(), target.x(), target.y(),
            triggerOnPress, diagnosticPrefix
                + " dispatchWidget=" + componentIdentity(target.component())
                + " dispatchState=" + componentState(target.component())
                + " dispatchCoordinates=(" + target.x() + ',' + target.y() + ") ");
    }

    private static RightClickDispatch dispatchRightClickEvents(final Component target,
        final int x, final int y, final boolean triggerOnPress, final String diagnosticPrefix) {
        final long now = System.currentTimeMillis();
        final MouseEvent pressed = new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON3_DOWN_MASK, x, y, 1, triggerOnPress, MouseEvent.BUTTON3);
        final MouseEvent released = new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
            InputEvent.BUTTON3_DOWN_MASK, x, y, 1, !triggerOnPress, MouseEvent.BUTTON3);
        final List<DispatchFailure> failures = new ArrayList<>();
        try {
            target.dispatchEvent(pressed);
        } catch (RuntimeException failure) {
            failures.add(new DispatchFailure("MOUSE_PRESSED", failure));
        }
        try {
            target.dispatchEvent(released);
        } catch (RuntimeException failure) {
            failures.add(new DispatchFailure("MOUSE_RELEASED", failure));
        }
        final RightClickDispatch dispatch = new RightClickDispatch(diagnosticPrefix
            + "synthetic=" + mouseEventMarker(pressed) + "," + mouseEventMarker(released)
                + " triggerPhase=" + (triggerOnPress ? "MOUSE_PRESSED" : "MOUSE_RELEASED")
                + " target=" + componentState(target)
                + " renderer=" + rendererState(target, x, y)
                + " pointer=" + pointerState());
        if (!failures.isEmpty()) throw new RightClickDispatchException(dispatch, failures);
        return dispatch;
    }

    static boolean popupTriggerOnPress(final String osName) {
        return !(osName == null ? "" : osName).toLowerCase(Locale.ROOT).contains("win");
    }

    private static PopupCapture awaitPopup(final PopupAttempt attempt) throws Exception {
        // The host may build and show the popup asynchronously after the event returns.
        final List<String> polls = new ArrayList<>();
        PopupSnapshot last = new PopupSnapshot(null, 0, "none", "[]");
        int maxPopupCount = 0;
        for (int poll = 0; poll < 20; poll++) {
            final AtomicReference<PopupSnapshot> snapshot = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                final List<JPopupMenu> visibleAfter = popupMenus(true);
                final JPopupMenu associated = popupForAttempt(
                    attempt.visibleBefore(), attempt.dismissed(), visibleAfter);
                snapshot.set(new PopupSnapshot(associated, visibleAfter.size(),
                    popupAssociation(attempt, associated), popupMarkers(visibleAfter)));
            });
            last = snapshot.get();
            maxPopupCount = Math.max(maxPopupCount, last.popupCount());
            polls.add("poll=" + poll + " popupCount=" + last.popupCount()
                + " association=" + last.association() + " menus=" + last.markers());
            if (last.popup() != null) {
                return new PopupCapture(last.popup(), last.popupCount(), maxPopupCount,
                    popupDiagnostic(polls));
            }
            Thread.sleep(150);
        }
        return new PopupCapture(null, last.popupCount(), maxPopupCount,
            popupDiagnostic(polls));
    }

    private static String popupAssociation(final PopupAttempt attempt,
        final JPopupMenu popup) {
        if (popup == null) return "none";
        if (containsIdentity(attempt.dismissed(), popup)) return "reused-dismissed";
        if (containsIdentity(attempt.visibleBefore(), popup)) return "stale-visible-before";
        return "new";
    }

    private static String popupDiagnostic(final List<String> polls) {
        if (polls.isEmpty()) return "popupPolls=0";
        return "popupPolls=" + polls.size() + " last=" + polls.get(polls.size() - 1)
            + " history=" + String.join(" || ", polls);
    }

    private static String popupMarkers(final List<JPopupMenu> popups) {
        final List<String> markers = new ArrayList<>(popups.size());
        for (JPopupMenu popup : popups) markers.add(popupMarker(popup));
        return markers.toString();
    }

    /** Returns an identity and Swing visibility marker without reading host-private state. */
    static String popupMarker(final JPopupMenu popup) {
        if (popup == null) return "null";
        final StringBuilder marker = new StringBuilder(componentState(popup));
        marker.append(" invoker=").append(componentState(popup.getInvoker()));
        marker.append(" components=");
        appendMenuComponents(popup, marker, 0);
        return marker.toString();
    }

    private static void appendMenuComponents(final Container container,
        final StringBuilder marker, final int depth) {
        marker.append('[');
        final Component[] components = container.getComponents();
        for (int index = 0; index < components.length; index++) {
            if (index > 0) marker.append(", ");
            final Component component = components[index];
            marker.append(index).append(':').append(menuComponentMarker(component));
            if (depth < 4 && component instanceof Container child) {
                marker.append(" children=");
                appendMenuComponents(child, marker, depth + 1);
            }
        }
        marker.append(']');
    }

    private static String menuComponentMarker(final Component component) {
        final StringBuilder marker = new StringBuilder(componentState(component));
        if (component instanceof JMenuItem item) {
            marker.append(" menuItem=true text=").append(quoted(item.getText()))
                .append(" actionCommand=").append(quoted(item.getActionCommand()));
        }
        return marker.toString();
    }

    private static String quoted(final String value) {
        if (value == null) return "<null>";
        return "'" + value.replace("\\", "\\\\")
            .replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    private static String componentIdentity(final Component component) {
        if (component == null) return "null";
        return component.getClass().getName() + '@'
            + Integer.toHexString(System.identityHashCode(component));
    }

    private static String componentState(final Component component) {
        if (component == null) return "null";
        final Container parent = component.getParent();
        return componentIdentity(component)
            + "{visible=" + component.isVisible()
            + ",showing=" + component.isShowing()
            + ",displayable=" + component.isDisplayable()
            + ",enabled=" + component.isEnabled()
            + ",parent=" + componentIdentity(parent)
            + ",locationOnScreen=" + locationOnScreen(component) + '}';
    }

    private static String locationOnScreen(final Component component) {
        try {
            final Point point = component.getLocationOnScreen();
            return '(' + Integer.toString(point.x) + ',' + Integer.toString(point.y) + ')';
        } catch (RuntimeException failure) {
            return "<" + failure + ">";
        }
    }

    private static String rendererState(final Component target, final int x, final int y) {
        if (!(target instanceof JTable table)) return "not-JTable";
        // Renderer preparation is host code and may mutate/rebuild a table. It is diagnostic
        // only, so never invoke it for a detached target; in the live path it is deliberately
        // evaluated after the right-click events have completed.
        if (!isLiveComponent(table)) {
            return "target-not-live prepared=<skipped renderer preparation>";
        }
        final Point point = new Point(x, y);
        final int row = table.rowAtPoint(point);
        final int column = table.columnAtPoint(point);
        if (row < 0 || column < 0) {
            return "cell=none row=" + row + " column=" + column;
        }
        try {
            final TableCellRenderer renderer = table.getCellRenderer(row, column);
            final Component prepared = table.prepareRenderer(renderer, row, column);
            return "cell=" + row + "," + column
                + " renderer=" + rendererIdentity(renderer)
                + " prepared=" + componentState(prepared);
        } catch (RuntimeException failure) {
            return "cell=" + row + "," + column
                + " rendererError=" + failure;
        }
    }

    private static String rendererIdentity(final TableCellRenderer renderer) {
        if (renderer == null) return "null";
        if (renderer instanceof Component component) return componentIdentity(component);
        return renderer.getClass().getName() + '@'
            + Integer.toHexString(System.identityHashCode(renderer));
    }

    private static String pointerState() {
        try {
            final PointerInfo pointer = MouseInfo.getPointerInfo();
            if (pointer == null) return "null";
            final Point location = pointer.getLocation();
            return '(' + Integer.toString(location.x) + ',' + Integer.toString(location.y) + ')';
        } catch (RuntimeException failure) {
            return "<" + failure + ">";
        }
    }

    private static String mouseEventMarker(final MouseEvent event) {
        return "{" + mouseEventName(event.getID())
            + ",local=(" + event.getX() + ',' + event.getY() + ')'
            + ",screen=(" + event.getXOnScreen() + ',' + event.getYOnScreen() + ')'
            + ",button=" + event.getButton()
            + ",popupTrigger=" + event.isPopupTrigger()
            + ",source=" + componentIdentity(event.getComponent()) + '}';
    }

    private static String mouseEventName(final int id) {
        return switch (id) {
            case MouseEvent.MOUSE_PRESSED -> "MOUSE_PRESSED";
            case MouseEvent.MOUSE_RELEASED -> "MOUSE_RELEASED";
            case MouseEvent.MOUSE_CLICKED -> "MOUSE_CLICKED";
            default -> Integer.toString(id);
        };
    }

    private static String stackTrace(final Throwable failure) {
        final StringWriter trace = new StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(trace));
        return trace.toString();
    }

    private record GuiClick(boolean clicked, String diagnostic) {
    }

    private List<RowWidget> visibleRowWidgets() throws Exception {
        final AtomicReference<List<RowWidget>> found = new AtomicReference<>(List.of());
        SwingUtilities.invokeAndWait(() -> {
            final List<RowWidget> widgets = new ArrayList<>();
            for (final Frame frame : Frame.getFrames()) {
                collectRowWidgets(frame, widgets);
            }
            found.set(widgets);
        });
        return found.get();
    }

    private static void collectRowWidgets(final Container container,
        final List<RowWidget> widgets) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTree tree && isLiveComponent(tree)) {
                widgets.add(new TreeWidget(tree));
            } else if (component instanceof javax.swing.JList<?> list
                && isLiveComponent(list)) {
                widgets.add(new ListWidget(list));
            } else if (component instanceof javax.swing.JTable table
                && isLiveComponent(table)) {
                // CTreeTable hosts a JTree inside a JTable; the table receives the clicks.
                widgets.add(new TableWidget(table));
            }
            if (component instanceof Container child) {
                collectRowWidgets(child, widgets);
            }
        }
    }

    private static boolean isLiveComponent(final Component component) {
        return component != null && component.isShowing() && component.isDisplayable()
            && component.getParent() != null;
    }

    /** Captures row keys and bounds without calling a renderer, and must run on the EDT. */
    private static List<CapturedRow> captureRowsOnEdt(final Component component,
        final RowKind kind) {
        // The widget was discovered while showing, but may be detached by the time this
        // snapshot runs. Keep its row key/bounds for relocation diagnostics; live validation is
        // intentionally performed again by resolveActiveRow immediately before dispatch.
        if (component == null) return List.of();
        final int count = rowCount(component, kind);
        final List<CapturedRow> rows = new ArrayList<>(Math.max(0, count));
        for (int row = 0; row < count; row++) {
            final Rectangle bounds;
            try {
                bounds = rowBounds(component, kind, row);
            } catch (RuntimeException failure) {
                rows.add(capturedRow(component, kind, row, null, RowKey.unavailable()));
                continue;
            }
            final RowKey key = rowKey(component, kind, row);
            rows.add(capturedRow(component, kind, row, bounds, key));
        }
        return List.copyOf(rows);
    }

    private static CapturedRow capturedRow(final Component component, final RowKind kind,
        final int row, final Rectangle bounds, final RowKey key) {
        return new CapturedRow(kind, component.getClass().getName(),
            componentIdentity(component), componentIdentity(
                SwingUtilities.getWindowAncestor(component)), row,
            key.value(), key.available(), boundsMarker(bounds));
    }

    private static int rowCount(final Component component, final RowKind kind) {
        return switch (kind) {
            case TREE -> ((JTree) component).getRowCount();
            case TABLE -> ((JTable) component).getRowCount();
            case LIST -> ((javax.swing.JList<?>) component).getModel().getSize();
        };
    }

    private static Rectangle rowBounds(final Component component, final RowKind kind,
        final int row) {
        return switch (kind) {
            case TREE -> ((JTree) component).getRowBounds(row);
            case TABLE -> {
                final JTable table = (JTable) component;
                yield table.getColumnCount() == 0 ? null : table.getCellRect(row, 0, true);
            }
            case LIST -> ((javax.swing.JList<?>) component).getCellBounds(row, row);
        };
    }

    private static boolean validBounds(final Rectangle bounds) {
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    private static RowKey rowKey(final Component component, final RowKind kind, final int row) {
        try {
            return switch (kind) {
                case TREE -> treeRowKey((JTree) component, row);
                case TABLE -> tableRowKey((JTable) component, row);
                case LIST -> listRowKey((javax.swing.JList<?>) component, row);
            };
        } catch (RuntimeException failure) {
            return RowKey.unavailable();
        }
    }

    /** Package-private seam for exercising the same row-key calculation as captureRowsOnEdt. */
    static RowKey rowKeyForTest(final Component component, final RowKind kind, final int row) {
        return rowKey(component, kind, row);
    }

    private static RowKey treeRowKey(final JTree tree, final int row) {
        final javax.swing.tree.TreePath path = tree.getPathForRow(row);
        if (path == null) return RowKey.unavailable();
        final StringBuilder key = new StringBuilder("tree:");
        for (final Object value : path.getPath()) {
            final Optional<String> stable = stableRowValue(value);
            if (stable.isEmpty()) return RowKey.unavailable();
            key.append(stable.get());
        }
        return RowKey.of(key.toString());
    }

    private static RowKey tableRowKey(final JTable table, final int row) {
        if (table.getColumnCount() == 0) return RowKey.unavailable();
        final StringBuilder key = new StringBuilder("table:");
        for (int column = 0; column < table.getColumnCount(); column++) {
            final Optional<String> stable = stableRowValue(table.getValueAt(row, column));
            if (stable.isEmpty()) return RowKey.unavailable();
            key.append(stable.get());
        }
        return RowKey.of(key.toString());
    }

    private static RowKey listRowKey(final javax.swing.JList<?> list, final int row) {
        final Optional<String> stable = stableRowValue(list.getModel().getElementAt(row));
        return stable.isEmpty() ? RowKey.unavailable() : RowKey.of("list:" + stable.get());
    }

    /**
     * Returns a full, structured value token for the host's row values. The table/tree/list
     * models expose labels and other value objects through toString; identity-only text cannot
     * relocate a row across a host component replacement, so it is deliberately unavailable.
     */
    private static Optional<String> stableRowValue(final Object value) {
        if (value == null) return Optional.of(stableToken("null", ""));
        final String type = value.getClass().getName();
        final String text;
        try {
            text = value.toString();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (text == null || looksLikeDefaultIdentityText(type, text)) {
            return Optional.empty();
        }
        return Optional.of(stableToken(type, text));
    }

    private static String stableToken(final String type, final String value) {
        return lengthPrefix(type) + lengthPrefix(value);
    }

    private static String lengthPrefix(final String value) {
        return value.length() + ":" + value;
    }

    private static boolean looksLikeDefaultIdentityText(final String type, final String text) {
        final String prefix = type + '@';
        if (!text.startsWith(prefix) || text.length() == prefix.length()) return false;
        for (int index = prefix.length(); index < text.length(); index++) {
            final char character = text.charAt(index);
            final boolean hex = character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f'
                || character >= 'A' && character <= 'F';
            if (!hex) return false;
        }
        return true;
    }

    private static List<CurrentRow> currentRowsOnEdt() {
        final List<CurrentRow> rows = new ArrayList<>();
        for (final Frame frame : Frame.getFrames()) {
            if (frame.isShowing()) collectCurrentRows(frame, rows);
        }
        return rows;
    }

    private static void collectCurrentRows(final Container container,
        final List<CurrentRow> rows) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTree tree && isLiveComponent(tree)) {
                addCurrentRows(tree, RowKind.TREE, rows);
            } else if (component instanceof JTable table && isLiveComponent(table)) {
                addCurrentRows(table, RowKind.TABLE, rows);
            } else if (component instanceof javax.swing.JList<?> list
                && isLiveComponent(list)) {
                addCurrentRows(list, RowKind.LIST, rows);
            }
            if (component instanceof Container child) collectCurrentRows(child, rows);
        }
    }

    private static void addCurrentRows(final Component component, final RowKind kind,
        final List<CurrentRow> rows) {
        for (final CapturedRow row : captureRowsOnEdt(component, kind)) {
            final Rectangle bounds = rowBounds(component, kind, row.row());
            if (validBounds(bounds)) rows.add(new CurrentRow(row, component, bounds));
        }
    }

    /** Resolves a capture to one current live row; ambiguous or unkeyed replacement is rejected. */
    private static RowResolution resolveActiveRow(final CapturedRow captured) {
        final List<CurrentRow> all = currentRowsOnEdt();
        return resolveActiveRow(captured, all);
    }

    /** Package-private seam for testing the production resolver with a deterministic live-row set. */
    static RowResolution resolveActiveRowForTest(final CapturedRow captured,
        final List<CurrentRow> currentRows) {
        return resolveActiveRow(captured, currentRows);
    }

    private static RowResolution resolveActiveRow(final CapturedRow captured,
        final List<CurrentRow> allRows) {
        if (captured == null) return RowResolution.unavailable("captured row is unavailable");
        final List<CurrentRow> all = allRows == null ? List.of() : allRows;
        final List<CurrentRow> sameWidgetType = all.stream()
            .filter(row -> row.descriptor().kind() == captured.kind())
            .filter(row -> row.descriptor().widgetClass().equals(captured.widgetClass()))
            .toList();
        if (sameWidgetType.isEmpty()) {
            return RowResolution.unavailable("no live widget of captured type");
        }

        final List<CurrentRow> sameWindow = sameWidgetType.stream()
            .filter(row -> row.descriptor().windowIdentity().equals(captured.windowIdentity()))
            .toList();
        if (sameWindow.isEmpty()) {
            return RowResolution.unavailable("no live row in captured window");
        }
        final List<CurrentRow> candidates = sameWindow;
        if (!captured.rowKeyAvailable()) {
            return RowResolution.unavailable(
                "captured row has no stable key; row index reuse is refused");
        }

        final List<CurrentRow> keyed = candidates.stream()
            .filter(row -> row.descriptor().rowKeyAvailable())
            .filter(row -> row.descriptor().rowKey().equals(captured.rowKey()))
            .toList();
        final List<CurrentRow> sameIdentity = keyed.stream()
            .filter(row -> row.descriptor().widgetIdentity().equals(captured.widgetIdentity()))
            .filter(row -> row.row() == captured.row())
            .toList();
        if (sameIdentity.size() == 1) return RowResolution.available(sameIdentity.get(0));
        if (sameIdentity.size() > 1) {
            return RowResolution.unavailable("captured widget has ambiguous matching rows");
        }
        if (keyed.size() == 1) return RowResolution.available(keyed.get(0));
        if (keyed.isEmpty()) {
            return RowResolution.unavailable("no live row matches captured row key");
        }
        return RowResolution.unavailable("live row key matches are ambiguous: " + keyed.size());
    }

    private static String boundsMarker(final Rectangle bounds) {
        return bounds == null ? "null"
            : "(" + bounds.x + ',' + bounds.y + ',' + bounds.width + ',' + bounds.height + ')';
    }

    /**
     * Component-hierarchy digest recorded when no row widget could be clicked, so a BLOCKED run
     * still identifies which widget the object list actually is. Lists every showing component
     * in every showing window, deduplicated with counts.
     */
    private String hierarchyDigest() {
        final AtomicReference<String> digest = new AtomicReference<>("");
        try {
            SwingUtilities.invokeAndWait(() -> {
                final StringBuilder text = new StringBuilder();
                final Map<String, Integer> counts = new LinkedHashMap<>();
                for (final Window window : Window.getWindows()) {
                    if (!window.isShowing()) continue;
                    text.append("window(").append(window.getClass().getSimpleName());
                    if (window instanceof Frame frame) text.append(":'").append(frame.getTitle()).append('\'');
                    if (window instanceof Dialog dialog) text.append(":'").append(dialog.getTitle()).append('\'');
                    text.append(") ");
                    collectWidgetNames(window, counts);
                }
                counts.forEach((name, count) -> {
                    if (text.length() < 3500) {
                        text.append('<').append(name).append('x').append(count).append("> ");
                    }
                });
                digest.set(text.toString());
            });
        } catch (Exception ignored) {
        }
        return digest.get();
    }

    private static void collectWidgetNames(final Container container,
        final Map<String, Integer> counts) {
        for (final Component component : container.getComponents()) {
            if (component.isShowing()) {
                counts.merge(component.getClass().getName(), 1, Integer::sum);
            }
            if (component instanceof Container child) {
                collectWidgetNames(child, counts);
            }
        }
    }

    private static List<JPopupMenu> popupMenus(final boolean visibleOnly) {
        final List<JPopupMenu> popups = new ArrayList<>();
        for (final MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath()) {
            if (element instanceof JPopupMenu popup
                && (!visibleOnly || popup.isVisible())) {
                addPopup(popups, popup);
            }
        }
        for (final Window window : Window.getWindows()) {
            collectPopupMenus(window, popups, visibleOnly);
        }
        return popups;
    }

    private static void collectPopupMenus(final Container container,
        final List<JPopupMenu> popups, final boolean visibleOnly) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JPopupMenu popup
                && (!visibleOnly || popup.isVisible())) {
                addPopup(popups, popup);
            }
            if (component instanceof Container child) {
                collectPopupMenus(child, popups, visibleOnly);
            }
        }
    }

    private static void addPopup(final List<JPopupMenu> popups, final JPopupMenu candidate) {
        for (final JPopupMenu popup : popups) {
            if (popup == candidate) return;
        }
        popups.add(candidate);
    }

    static JPopupMenu popupForAttempt(final List<JPopupMenu> visibleBefore,
        final List<JPopupMenu> dismissed, final List<JPopupMenu> visibleAfter) {
        for (final JPopupMenu popup : visibleAfter) {
            if (!containsIdentity(visibleBefore, popup) || containsIdentity(dismissed, popup)) {
                return popup;
            }
        }
        return null;
    }

    private static boolean containsIdentity(final List<JPopupMenu> popups,
        final JPopupMenu candidate) {
        for (final JPopupMenu popup : popups) {
            if (popup == candidate) return true;
        }
        return false;
    }

    private static JMenuItem findItem(final JPopupMenu popup, final Set<String> labels,
        final Set<String> seen) {
        return findItem(popup, labels, seen, 0);
    }

    private static JMenuItem findItem(final Container container, final Set<String> labels,
        final Set<String> seen, final int depth) {
        if (depth > 4) return null;
        for (final Component component : container.getComponents()) {
            if (component instanceof JMenuItem item) {
                if (seen.size() < 200) seen.add(item.getText());
                if (labels.contains(item.getText())) return item;
            }
            if (component instanceof Container child) {
                final JMenuItem item = findItem(child, labels, seen, depth + 1);
                if (item != null) return item;
            }
        }
        return null;
    }

    private static PopupAttempt mergePopupAttempts(final PopupAttempt first,
        final PopupAttempt second) {
        return new PopupAttempt(mergePopupLists(first.visibleBefore(), second.visibleBefore()),
            mergePopupLists(first.dismissed(), second.dismissed()));
    }

    private static List<JPopupMenu> mergePopupLists(final List<JPopupMenu> first,
        final List<JPopupMenu> second) {
        final List<JPopupMenu> merged = new ArrayList<>(first.size() + second.size());
        for (final JPopupMenu popup : first) {
            if (!containsIdentity(merged, popup)) merged.add(popup);
        }
        for (final JPopupMenu popup : second) {
            if (!containsIdentity(merged, popup)) merged.add(popup);
        }
        return List.copyOf(merged);
    }

    private static PopupAttempt dismissPopup() throws Exception {
        final AtomicReference<PopupAttempt> attempt = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final List<JPopupMenu> visibleBefore = popupMenus(true);
            for (final JPopupMenu popup : visibleBefore) popup.setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            // Clearing the selection can repost or retain a host-owned popup; enforce the close
            // after the manager has processed the selected path as well.
            for (final JPopupMenu popup : visibleBefore) popup.setVisible(false);
            final List<JPopupMenu> dismissed = visibleBefore.stream()
                .filter(popup -> !popup.isVisible())
                .toList();
            attempt.set(new PopupAttempt(List.copyOf(visibleBefore), dismissed));
        });
        return attempt.get();
    }

    private void clickItem(final JMenuItem item) throws Exception {
        SwingUtilities.invokeAndWait(item::doClick);
    }

    /** Polls the task temp root for a turboism-psd-* session created after {@code marker}. */
    private Path awaitSessionTempFile(final Path marker, final int timeoutSeconds)
        throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline && !stopped) {
            try (Stream<Path> stream = Files.list(marker.getParent())) {
                final Optional<Path> newest = stream
                    .filter(path -> path.getFileName().toString().startsWith("turboism-psd-"))
                    .filter(path -> isNewerThan(path, marker))
                    .map(path -> path.resolve("external-edit.psd"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparing(ExternalPsdEditHostProbe::modified));
                if (newest.isPresent()) return newest.get().toRealPath(LinkOption.NOFOLLOW_LINKS);
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
            "plugin session temp file did not appear within " + timeoutSeconds + "s");
    }

    /** Waits for a target-specific false-to-true replacement without accepting stale state. */
    private AutoImportObservation awaitAutoImport(final Target target,
        final GuiTargetState before,
        final int timeoutSeconds) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        GuiTargetState last = GuiTargetState.unobserved("no post-click target observation");
        while (System.currentTimeMillis() < deadline && !stopped) {
            last = observeGuiTarget(target);
            if (last.observed()) {
                if (!sameGuiTarget(before, last, target.raw().value())) {
                    return new AutoImportObservation(false, true, last,
                        "GUI target became stale: before=" + before + " after=" + last);
                }
                if (acceptsAutoImport(before, last, target.raw().value())) {
                    return new AutoImportObservation(true, false, last,
                        "target raw isReplaced changed false-to-true");
                }
            }
            Thread.sleep(500);
        }
        return new AutoImportObservation(false, false, last,
            "no target-specific false-to-true replacement observed before timeout");
    }

    private record PopupAttempt(List<JPopupMenu> visibleBefore,
        List<JPopupMenu> dismissed) {}

    private record PopupSnapshot(JPopupMenu popup, int popupCount,
        String association, String markers) {}

    private record PopupCapture(JPopupMenu popup, int popupCount, int maxPopupCount,
        String diagnostic) {
        PopupCapture {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    private record RowAttempt(JPopupMenu popup, int popupCount, String diagnostic,
        String dispatchFailureTrace, int dispatchRow, String dispatchComponent) {
        RowAttempt {
            diagnostic = diagnostic == null ? "" : diagnostic;
            dispatchFailureTrace = dispatchFailureTrace == null ? "" : dispatchFailureTrace;
            dispatchComponent = dispatchComponent == null ? "" : dispatchComponent;
        }
    }

    enum RowKind {
        TREE, TABLE, LIST
    }

    record RowKey(String value, boolean available) {
        RowKey {
            value = value == null ? "" : value;
        }

        static RowKey of(final String value) {
            return new RowKey(value, value != null && !value.isBlank());
        }

        static RowKey unavailable() {
            return new RowKey("", false);
        }
    }

    record CapturedRow(RowKind kind, String widgetClass, String widgetIdentity,
        String windowIdentity, int row, String rowKey, boolean rowKeyAvailable, String bounds) {
        CapturedRow {
            widgetClass = widgetClass == null ? "" : widgetClass;
            widgetIdentity = widgetIdentity == null ? "" : widgetIdentity;
            windowIdentity = windowIdentity == null ? "" : windowIdentity;
            rowKey = rowKey == null ? "" : rowKey;
            bounds = bounds == null ? "null" : bounds;
        }

        String rowKeyDiagnostic() {
            return rowKeyAvailable
                ? "available#" + Integer.toHexString(rowKey.hashCode()) : "unavailable";
        }

        String diagnostic() {
            return "widget=" + widgetIdentity
                + " class=" + widgetClass
                + " window=" + windowIdentity
                + " row=" + row
                + " rowKey=" + rowKeyDiagnostic()
                + " bounds=" + bounds;
        }
    }

    record CurrentRow(CapturedRow descriptor, Component component, Rectangle bounds) {
        int row() { return descriptor.row(); }
    }

    record RowResolution(CurrentRow row, String reason) {
        static RowResolution available(final CurrentRow row) {
            return new RowResolution(row, "");
        }

        static RowResolution unavailable(final String reason) {
            return new RowResolution(null, reason == null ? "unknown" : reason);
        }

        boolean available() { return row != null; }
    }

    /** Coordinates and component chosen immediately before a popup-trigger dispatch. */
    static record DispatchTarget(Component component, int x, int y) {
    }

    private record RowDispatchResult(RightClickDispatch dispatch, int row, String component,
        boolean retryable, String phase, String reason) {
        static RowDispatchResult success(final RightClickDispatch dispatch,
            final CurrentRow row) {
            return new RowDispatchResult(dispatch, row.row(), componentIdentity(row.component()),
                false, "", "");
        }

        static RowDispatchResult retry(final CapturedRow captured, final String phase,
            final String reason) {
            final String actual = reason == null || reason.isBlank() ? "unknown" : reason;
            return new RowDispatchResult(
                RightClickDispatch.notDispatched("capture=" + captured.diagnostic()
                    + " phase=" + phase + " reason=" + actual),
                -1, "", true, phase, actual);
        }
    }

    private static final class RowRelocationException extends RuntimeException {
        RowRelocationException(final String message) {
            super(message);
        }
    }

    static int rowDispatchAttemptLimit() {
        return MAX_ROW_DISPATCH_ATTEMPTS;
    }

    static record RightClickDispatch(String diagnostic) {
        RightClickDispatch {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static RightClickDispatch notDispatched(final String diagnostic) {
            return new RightClickDispatch("not-dispatched=" + diagnostic);
        }
    }

    private record DispatchFailure(String phase, RuntimeException error) {}

    /** Right-click handler failures are rethrown after both popup-trigger phases are attempted. */
    static final class RightClickDispatchException extends RuntimeException {
        private final RightClickDispatch dispatch;

        RightClickDispatchException(final RightClickDispatch dispatch,
            final List<DispatchFailure> failures) {
            super("right-click dispatch failed phases="
                + failures.stream().map(DispatchFailure::phase).toList()
                + " " + dispatch.diagnostic(), phaseFailure(failures.get(0)));
            this.dispatch = dispatch;
            for (int index = 1; index < failures.size(); index++) {
                addSuppressed(phaseFailure(failures.get(index)));
            }
        }

        RightClickDispatch dispatch() { return dispatch; }
    }

    private static RuntimeException phaseFailure(final DispatchFailure failure) {
        return new RuntimeException("right-click " + failure.phase() + " dispatch failed",
            failure.error());
    }

    static record GuiTargetState(boolean observed, String binding, long generation, String raw,
        boolean rawReplaced, String diagnostic) {
        GuiTargetState {
            binding = binding == null ? "" : binding;
            raw = raw == null ? "" : raw;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static GuiTargetState unobserved(final String diagnostic) {
            return new GuiTargetState(false, "", -1L, "", false, diagnostic);
        }
    }

    private record AutoImportObservation(boolean applied, boolean stale,
        GuiTargetState after, String diagnostic) {}

    private record Target(ArtMeshTextureInputs artMesh,
        dev.turboism.sdk.cubism.id.ModelImageId modelImage, RawImageId raw, boolean rawReplaced) {}
}
