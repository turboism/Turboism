package dev.turboism.validation.textures;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.model.TextureInputBinding;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only, read-only smoke probe. Does not claim the complete 025 relation matrix. */
public final class TextureRelationsHostProbe implements TurboismPlugin {
    private PluginContext context;
    private volatile boolean stopped;
    private Thread worker;

    @Override public void init(final PluginContext context) { this.context = context; }

    @Override public void enable() {
        final String runId = System.getProperty("turboism.validation.textures.runId", "");
        if (runId.isBlank()) throw new IllegalStateException("Task-scoped runId is required");
        worker = new Thread(() -> run(runId), "texture-relations-host-smoke");
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
        result.setProperty("schemaVersion", "1");
        result.setProperty("runId", runId);
        result.setProperty("profile", "025-relations-read-only-smoke");
        result.setProperty("expectedHostVersion", "5.3.02");
        result.setProperty("hostIdentityEvidence", "runner exact JAR/BAT identity and lifecycle evidence");
        result.setProperty("writeUndoPersistence", "NOT_APPLICABLE: read-only smoke; no authoring mutation");
        result.setProperty("full025Acceptance", "NOT_TESTED");
        result.setProperty("sharedAndMultipleInputsMatrix", "NOT_TESTED: requires dedicated fixture expectations");
        try {
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
            if (stopped) return;
            if (!ready) throw new IllegalStateException("Active fixture/model readiness timed out");
            final AtomicReference<TextureRelationsSnapshot> observed = new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                if (stopped) return;
                try {
                    final var document = context.cubism().activeDocument().orElseThrow();
                    final String expectedFixture = System.getProperty("turboism.validation.fixtureName", "");
                    // The SDK intentionally omits filePath. Its verified relativePath ends in
                    // the sanitized source basename; runner-generated fixture names are ASCII-safe.
                    final String relativePath = document.relativePath();
                    final String actualFixture = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                    result.setProperty("fixture.expected", expectedFixture);
                    result.setProperty("fixture.actual", actualFixture);
                    if (expectedFixture.isBlank() || !expectedFixture.equals(actualFixture))
                        throw new IllegalStateException("Active document is not the task fixture copy");
                    final var model = context.cubism().model().active();
                    result.setProperty("documentId", document.documentId());
                    result.setProperty("modelId", model.id().value());
                    observed.set(model.textures().relations());
                } catch (Throwable error) { failure.set(error); }
            });
            if (stopped) return;
            if (failure.get() != null) throw new IllegalStateException("Relation projection failed", failure.get());
            final var snapshot = observed.get();
            validate(snapshot);
            result.setProperty("binding", snapshot.binding());
            result.setProperty("generation", Long.toString(snapshot.generation()));
            result.setProperty("observationRevision", Long.toString(snapshot.revision()));
            result.setProperty("rawCount", Integer.toString(snapshot.rawImages().size()));
            result.setProperty("modelImageCount", Integer.toString(snapshot.modelImages().size()));
            result.setProperty("artMeshCount", Integer.toString(snapshot.artMeshInputs().size()));
            result.setProperty("assertion", "available nonempty relation graph with resolved mesh links");
            result.setProperty("expected", "true");
            result.setProperty("actual", "true");
            result.setProperty("status", "PASS");
        } catch (Throwable error) {
            if (stopped) return;
            result.setProperty("status", "FAIL");
            result.setProperty("expected", "available nonempty coherent relation graph");
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
            result.store(output, "025 read-only smoke; not full feature acceptance");
            Files.writeString(dir.resolve("relations-result.pending"), output.toString());
            Files.move(dir.resolve("relations-result.pending"), dir.resolve("relations-result.properties"),
                StandardCopyOption.REPLACE_EXISTING);
            context.logger().info("TEXTURE_RELATIONS_SMOKE_RESULT status=" + result.getProperty("status"));
            // Only this explicitly enabled test process is exited; no model was changed or saved.
            Runtime.getRuntime().exit("PASS".equals(result.getProperty("status")) ? 0 : 2);
        } catch (Exception error) {
            context.logger().warn("TEXTURE_RELATIONS_SMOKE_RESULT_WRITE_FAILED " + error);
        }
    }

    static void validate(final TextureRelationsSnapshot snapshot) {
        if (snapshot == null || !snapshot.isAvailable() || snapshot.binding().isBlank()
            || snapshot.rawImages().isEmpty() || snapshot.modelImages().isEmpty()
            || snapshot.artMeshInputs().isEmpty()) {
            throw new IllegalStateException("Relation projection unavailable or empty");
        }
        final var rawIds = new HashSet<>();
        snapshot.rawImages().forEach(raw -> {
            if (!rawIds.add(raw.id())) throw new IllegalStateException("Duplicate raw identity");
        });
        final var modelIds = new HashSet<>();
        snapshot.modelImages().forEach(image -> {
            if (!modelIds.add(image.id())) throw new IllegalStateException("Duplicate model-image identity");
            if (image.currentRawImageId().isPresent() && !rawIds.contains(image.currentRawImageId().get()))
                throw new IllegalStateException("Current raw identity missing from raw graph");
        });
        int resolvedModelInputs = 0;
        final var meshIds = new HashSet<>();
        for (final var mesh : snapshot.artMeshInputs()) {
            if (!meshIds.add(mesh.id())) throw new IllegalStateException("Duplicate ArtMesh identity");
            for (final var input : mesh.inputs()) {
                if (input.kind() == TextureInputBinding.Kind.MODEL_IMAGE && input.isResolved()) {
                    if (!modelIds.contains(input.modelImageId().orElseThrow()))
                        throw new IllegalStateException("Resolved input missing from model-image graph");
                    resolvedModelInputs++;
                }
            }
        }
        if (resolvedModelInputs == 0) throw new IllegalStateException("No resolved model-image inputs");
    }
}
