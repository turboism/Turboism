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
 * external writes, exactly as an external editor would see it.</p>
 *
 * <p>Phases ({@code -Dturboism.validation.externalpsd.phase}): {@code pipeline} (default)
 * runs the full save/replace/undo/stop pipeline and, with {@code .persist=1}, appends a
 * mediated SAVE_AS plus lifecycle-event confirmation; {@code reopen} re-exports the marker
 * layer from a previously saved fixture copy; {@code gui} right-clicks a real object row,
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
        result.setProperty("realEditorApplication",
            "default-application launch recorded; OPENED requires the task .psd association");
        final Target target = resolveTarget(result);
        final Path before = tempMarker();
        final PsdExportResult exported = export(result, target.raw());
        final PsdEditFile file = exported.file().orElseThrow();
        final Path tempFile = locateTempFile(before, result);
        final byte[] baselineBytes = Files.readAllBytes(tempFile);
        result.setProperty("tempFile.discovered", Boolean.toString(tempFile.getFileName()
            .toString().equals("external-edit.psd")));
        result.setProperty("baseline.bytes", Integer.toString(baselineBytes.length));
        result.setProperty("baseline.sha256", sha256(baselineBytes));
        result.setProperty("baseline.revisionIssued",
            Boolean.toString(exported.initialRevision().isPresent()));

        final Deque<PsdFileRevision> revisions = new ArrayDeque<>();
        final Registration subscription = file.observeSaves(revision -> {
            synchronized (revisions) { revisions.add(revision); revisions.notifyAll(); }
        });
        try {
            assertNoRevision(revisions, 1500, "baseline revision must not be replayed to subscribers");
            result.setProperty("baseline.replayed", "false");

            final PsdFileOperationResult opened = file.openInDefaultApplication()
                .toCompletableFuture().get(60, TimeUnit.SECONDS);
            result.setProperty("defaultApplication.status", opened.status().name());
            result.setProperty("defaultApplication.diagnostic", opened.diagnostic());

            final Mutation marker =
                runSaveCycles(result, file, target, tempFile, revisions, cycles);
            runCorruptedSave(result, file, target, tempFile, revisions);
            runUndoRedo(result, target);
            assertNoReplayAfterIdle(result, revisions);
            runStopAndRecovery(result, file, target, tempFile, revisions);
            recordEnvironment(result);
            if (marker != null) {
                result.setProperty("persist.markerLayer", Integer.toString(marker.layer()));
                result.setProperty("persist.markerOffset", Integer.toString(marker.nameOffset()));
                result.setProperty("persist.markerChar", Integer.toString(marker.letter()));
            }
            if ("1".equals(System.getProperty("turboism.validation.externalpsd.persist"))) {
                runPersistTail(result, target);
            } else {
                result.setProperty("documentPersistence",
                    "NOT_TESTED: persist tail not requested");
            }
        } finally {
            subscription.close();
            file.stop();
        }
        result.setProperty("expected", "full pipeline assertions hold");
        result.setProperty("actual", "full pipeline assertions hold");
    }

    /**
     * Reopens a fixture copy saved by a persist run and proves the externally applied edit
     * survived a real native save → file → reopen roundtrip. Layer names are normalized by the
     * host's import, so the gate is the exported image-data section hash recorded as
     * {@code persist.postEditImageSha256}; the full-file hash is recorded for context.
     */
    private void runReopen(final Properties result) throws Exception {
        final Target target = resolveTarget(result);
        final Path before = tempMarker();
        final PsdExportResult exported = export(result, target.raw());
        final PsdEditFile file = exported.file().orElseThrow();
        try {
            final Path tempFile = locateTempFile(before, result);
            final byte[] bytes = Files.readAllBytes(tempFile);
            result.setProperty("reopen.layerCount",
                Integer.toString(layerNameRanges(bytes).size()));
            result.setProperty("reopen.bytes", Integer.toString(bytes.length));
            result.setProperty("reopen.sha256", sha256(bytes));
            result.setProperty("reopen.imageSha256", imageDataSha256(bytes));
            final String expectedImage = System.getProperty(
                "turboism.validation.externalpsd.postEditImageSha256", "");
            final String expectedFile = System.getProperty(
                "turboism.validation.externalpsd.postEditSha256", "");
            result.setProperty("reopen.expectedImageSha256", expectedImage);
            result.setProperty("reopen.fileShaMatched",
                Boolean.toString(!expectedFile.isBlank()
                    && expectedFile.equals(result.getProperty("reopen.sha256"))));
            if (expectedImage.isBlank()) {
                throw new IllegalStateException(
                    "reopen requires -Dturboism.validation.externalpsd.postEditImageSha256");
            }
            if (!expectedImage.equals(result.getProperty("reopen.imageSha256"))) {
                throw new IllegalStateException(
                    "reopened document image data does not match the persisted post-edit content");
            }
        } finally {
            file.stop();
        }
        result.setProperty("expected", "reopened fixture retains the external-edit content");
        result.setProperty("actual", "image data verified in the reopened document's raw image");
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

    private Path tempMarker() throws Exception {
        final Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        final Path marker = tmp.resolve(".external-psd-probe-" + ProcessHandle.current().pid());
        return Files.writeString(marker, Long.toString(System.currentTimeMillis()));
    }

    private Path locateTempFile(final Path marker, final Properties result) throws Exception {
        final Path tmp = marker.getParent();
        try (Stream<Path> stream = Files.list(tmp)) {
            final List<Path> candidates = stream
                .filter(path -> path.getFileName().toString().startsWith("turboism-psd-"))
                .filter(path -> isNewerThan(path, marker))
                .map(path -> path.resolve("external-edit.psd"))
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(ExternalPsdEditHostProbe::modified)
                    .reversed())
                .toList();
            result.setProperty("tempFile.candidates", Integer.toString(candidates.size()));
            if (candidates.isEmpty()) {
                throw new IllegalStateException("Runtime-issued temporary PSD not discoverable");
            }
            return candidates.get(0).toRealPath(LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static long modified(final Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception error) { return 0; }
    }

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

    private Mutation runSaveCycles(final Properties result, final PsdEditFile file, final Target target,
        final Path tempFile, final Deque<PsdFileRevision> revisions, final int cycles) throws Exception {
        Mutation lastMutation = null;
        for (int i = 1; i <= cycles; i++) {
            final byte[] current = Files.readAllBytes(tempFile);
            final Mutation mutation = mutationFor(current, i)
                .orElseThrow(() -> new IllegalStateException("PSD layer-name mutation failed"));
            final byte[] mutated = applyMutation(current, mutation);
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

    private void runUndoRedo(final Properties result, final Target target) throws Exception {
        final String appliedRaw = result.getProperty("applied.currentRaw", target.raw().value());
        final AtomicReference<dev.turboism.sdk.cubism.history.HistoryMoveResult> undo =
            new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> undo.set(context.cubism().history().undo(1)));
        result.setProperty("undo.outcome", undo.get().outcome().name());
        if (undo.get().outcome()
            != dev.turboism.sdk.cubism.history.HistoryMoveResult.Outcome.MOVED) {
            throw new IllegalStateException("Native replace was not undoable: "
                + undo.get().outcome());
        }
        final String afterUndo = currentRawOnEdt(target.modelImage().value());
        result.setProperty("undo.currentRaw", afterUndo);
        final AtomicReference<dev.turboism.sdk.cubism.history.HistoryMoveResult> redo =
            new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> redo.set(context.cubism().history().redo(1)));
        result.setProperty("redo.outcome", redo.get().outcome().name());
        final String afterRedo = currentRawOnEdt(target.modelImage().value());
        result.setProperty("redo.currentRaw", afterRedo);
        if (!appliedRaw.equals(afterRedo)) {
            throw new IllegalStateException("Redo did not restore the applied raw identity");
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

    private void runStopAndRecovery(final Properties result, final PsdEditFile file,
        final Target target, final Path tempFile, final Deque<PsdFileRevision> revisions)
        throws Exception {
        final PsdFileOperationResult stopResult = file.stop()
            .toCompletableFuture().get(60, TimeUnit.SECONDS);
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
        final Path before = tempMarker();
        final PsdExportResult again = export(new Properties(), target.raw());
        result.setProperty("recovery.exportStatus", again.status().name());
        final PsdEditFile second = again.file().orElseThrow();
        final Path secondFile = locateTempFile(before, new Properties());
        result.setProperty("recovery.newFile", Boolean.toString(!secondFile.equals(tempFile)));
        final PsdFileOperationResult secondStop = second.stop()
            .toCompletableFuture().get(60, TimeUnit.SECONDS);
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
    private void runPersistTail(final Properties result, final Target target) throws Exception {
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
            result.setProperty("documentPersistence", "SAVE_AS executed + SAVE event confirmed");

            // Content-level marker for the reopen stage: layer names are normalized by the
            // host's own import, but the replaced image content must round-trip byte-exact.
            final Path postMarker = tempMarker();
            final PsdExportResult postExport = export(result, target.raw());
            postExport.file().ifPresent(PsdEditFile::stop);
            final Path postTemp = locateTempFile(postMarker, result);
            final byte[] postBytes = Files.readAllBytes(postTemp);
            result.setProperty("persist.postEditBytes", Integer.toString(postBytes.length));
            result.setProperty("persist.postEditSha256", sha256(postBytes));
            result.setProperty("persist.postEditImageSha256", imageDataSha256(postBytes));
        } finally {
            subscription.close();
            beforeSub.close();
            onSub.close();
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
                    final int rows = widget.rows();
                    for (int row = 0; row < rows && attempts < rowBudget; row++, attempts++) {
                        if (stopped) return new GuiClick(false, "probe stopped");
                        final RowAttempt rowAttempt = widget.rightClick(row);
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
                        final String rowDiagnostic = "attempt=" + (attempts + 1)
                            + " widget=" + widget.name() + " row=" + row + " "
                            + rowAttempt.diagnostic() + " " + menuDiagnostic;
                        rowDiagnostics.add(rowDiagnostic);
                        result.setProperty("gui.row." + (attempts + 1), rowDiagnostic);
                        if (!rowAttempt.dispatchFailureTrace().isBlank()) {
                            result.setProperty("gui.row." + (attempts + 1)
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
                        result.setProperty("gui.popupRow", Integer.toString(row));
                        result.setProperty("gui.popupComponent", widget.name());
                        clickItem(item);
                        result.setProperty("gui.attempts", Integer.toString(attempts));
                        result.setProperty("gui.popupsSeen", Integer.toString(popups));
                        return new GuiClick(true, "clicked row " + row);
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

    /** A selectable row widget the host popup can be raised on: JTree row or JList cell. */
    private sealed interface RowWidget {
        String name();
        int rows() throws Exception;
        RowAttempt rightClick(int row) throws Exception;
    }

    private record TreeWidget(JTree tree) implements RowWidget {
        public String name() { return tree.getClass().getName(); }
        public int rows() throws Exception {
            final AtomicReference<Integer> rows = new AtomicReference<>(0);
            SwingUtilities.invokeAndWait(() -> rows.set(tree.getRowCount()));
            return rows.get();
        }
        public RowAttempt rightClick(final int row) throws Exception {
            final PopupAttempt attempt = dismissPopup();
            return rowAttempt(attempt, tree, row, () -> {
                tree.expandRow(row);
                final var bounds = tree.getRowBounds(row);
                if (bounds == null) return RightClickDispatch.notDispatched(
                    "row bounds unavailable");
                return dispatchRightClick(tree, bounds.x + bounds.width / 2,
                    bounds.y + bounds.height / 2);
            });
        }
    }

    private record TableWidget(javax.swing.JTable table) implements RowWidget {
        public String name() { return table.getClass().getName(); }
        public int rows() throws Exception {
            final AtomicReference<Integer> rows = new AtomicReference<>(0);
            SwingUtilities.invokeAndWait(() -> rows.set(table.getRowCount()));
            return rows.get();
        }
        public RowAttempt rightClick(final int row) throws Exception {
            final PopupAttempt attempt = dismissPopup();
            return rowAttempt(attempt, table, row, () -> {
                final var bounds = table.getCellRect(row, 0, true);
                if (bounds == null) return RightClickDispatch.notDispatched(
                    "cell bounds unavailable");
                return dispatchRightClick(table, bounds.x + bounds.width / 2,
                    bounds.y + bounds.height / 2);
            });
        }
    }

    private record ListWidget(javax.swing.JList<?> list) implements RowWidget {
        public String name() { return list.getClass().getName(); }
        public int rows() throws Exception {
            final AtomicReference<Integer> rows = new AtomicReference<>(0);
            SwingUtilities.invokeAndWait(() -> rows.set(list.getModel().getSize()));
            return rows.get();
        }
        public RowAttempt rightClick(final int row) throws Exception {
            final PopupAttempt attempt = dismissPopup();
            return rowAttempt(attempt, list, row, () -> {
                final var bounds = list.getCellBounds(row, row);
                if (bounds == null) return RightClickDispatch.notDispatched(
                    "cell bounds unavailable");
                return dispatchRightClick(list, bounds.x + bounds.width / 2,
                    bounds.y + bounds.height / 2);
            });
        }
    }

    private static RowAttempt rowAttempt(final PopupAttempt attempt, final Component target,
        final int row, final java.util.function.Supplier<RightClickDispatch> dispatch)
        throws Exception {
        final AtomicReference<RightClickDispatch> outcome = new AtomicReference<>();
        RightClickDispatchException dispatchFailure = null;
        try {
            SwingUtilities.invokeAndWait(() -> outcome.set(dispatch.get()));
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RightClickDispatchException failure) {
                dispatchFailure = failure;
            } else {
                throw wrapped;
            }
        }
        final PopupCapture popup = awaitPopup(attempt);
        final RightClickDispatch dispatchResult = outcome.get();
        final String dispatchDiagnostic = dispatchResult != null
            ? dispatchResult.diagnostic()
            : dispatchFailure != null
                ? dispatchFailure.dispatch().diagnostic()
                : "not-dispatched";
        final String failureTrace = dispatchFailure == null ? ""
            : stackTrace(dispatchFailure);
        final String diagnostic = "target=" + componentIdentity(target)
            + " row=" + row
            + " " + dispatchDiagnostic
            + (failureTrace.isBlank() ? "" : " dispatchExceptionTrace=" + failureTrace)
            + " popupCount=" + popup.popupCount()
            + " popupMaxCount=" + popup.maxPopupCount()
            + " " + popup.diagnostic();
        return new RowAttempt(popup.popup(), popup.popupCount(), diagnostic, failureTrace);
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
        final long now = System.currentTimeMillis();
        final MouseEvent pressed = new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON3_DOWN_MASK, x, y, 1, triggerOnPress, MouseEvent.BUTTON3);
        final MouseEvent released = new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
            InputEvent.BUTTON3_DOWN_MASK, x, y, 1, !triggerOnPress, MouseEvent.BUTTON3);
        final RightClickDispatch dispatch = new RightClickDispatch(
            "synthetic=" + mouseEventMarker(pressed) + "," + mouseEventMarker(released)
                + " triggerPhase=" + (triggerOnPress ? "MOUSE_PRESSED" : "MOUSE_RELEASED")
                + " target=" + componentState(target)
                + " renderer=" + rendererState(target, x, y)
                + " pointer=" + pointerState());
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
            if (component instanceof JTree tree && tree.isShowing()) {
                widgets.add(new TreeWidget(tree));
            } else if (component instanceof javax.swing.JList<?> list && list.isShowing()) {
                widgets.add(new ListWidget(list));
            } else if (component instanceof javax.swing.JTable table && table.isShowing()) {
                // CTreeTable hosts a JTree inside a JTable; the table receives the clicks.
                widgets.add(new TableWidget(table));
            }
            if (component instanceof Container child) {
                collectRowWidgets(child, widgets);
            }
        }
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
        String dispatchFailureTrace) {
        RowAttempt {
            diagnostic = diagnostic == null ? "" : diagnostic;
            dispatchFailureTrace = dispatchFailureTrace == null ? "" : dispatchFailureTrace;
        }
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
