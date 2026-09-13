package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.SwingUtilities;
import java.io.StringWriter;
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
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
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
 * <p>NOT covered (recorded honestly): GUI context-menu timing, real editor application,
 * document save/close/reopen persistence (no SDK seam), multi-document isolation.</p>
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
        result.setProperty("guiContextMenu", "NOT_TESTED: no semantic SDK seam for right-click timing");
        result.setProperty("realEditorApplication", "NOT_TESTED: default-application launch recorded only");
        result.setProperty("documentPersistence", "NOT_TESTED: no SDK document save/close/reopen seam");
        try {
            awaitReady();
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

                runSaveCycles(result, file, target, tempFile, revisions, cycles);
                runCorruptedSave(result, file, target, tempFile, revisions);
                runUndoRedo(result, target);
                assertNoReplayAfterIdle(result, revisions);
                runStopAndRecovery(result, file, target, tempFile, revisions);
            } finally {
                subscription.close();
                file.stop();
            }
            result.setProperty("expected", "full pipeline assertions hold");
            result.setProperty("actual", "full pipeline assertions hold");
            result.setProperty("status", "PASS");
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
            Runtime.getRuntime().exit("PASS".equals(result.getProperty("status")) ? 0 : 2);
        } catch (Exception error) {
            context.logger().warn("EXTERNAL_PSD_EDIT_RESULT_WRITE_FAILED " + error);
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
        return target;
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

    private void runSaveCycles(final Properties result, final PsdEditFile file, final Target target,
        final Path tempFile, final Deque<PsdFileRevision> revisions, final int cycles) throws Exception {
        for (int i = 1; i <= cycles; i++) {
            final byte[] current = Files.readAllBytes(tempFile);
            final byte[] mutated = mutateLayerName(current, i)
                .orElseThrow(() -> new IllegalStateException("PSD layer-name mutation failed"));
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
                final byte[] second = mutateLayerName(mutated, i + 100)
                    .orElseThrow(() -> new IllegalStateException("Second mutation failed"));
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
        final List<int[]> names = layerNameRanges(psd);
        if (names.isEmpty()) return Optional.empty();
        final byte[] copy = psd.clone();
        final int[] range = names.get((cycle - 1) % names.size());
        final int offset = range[0] + (range[1] > 1 ? (cycle - 1) / names.size() % range[1] : 0);
        byte replacement = (byte) ('a' + (cycle - 1) % 26);
        if (replacement == copy[offset]) {
            replacement = (byte) (replacement == 'a' ? 'b' : 'a');
        }
        copy[offset] = replacement;
        return Optional.of(copy);
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

    private record Target(ArtMeshTextureInputs artMesh,
        dev.turboism.sdk.cubism.id.ModelImageId modelImage, RawImageId raw, boolean rawReplaced) {}
}
